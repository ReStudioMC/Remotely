package redxax.oxy.remotely.flow.ui.studio;

import java.time.Duration;
import restudio.rescreen.platform.Async;
import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.AutomationDefinitionDraft;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncCollaborationClient;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.data.flow.world.WorldOperationResult;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.data.TabDefinition;
import redxax.oxy.remotely.flow.data.TriggerBinding;
import redxax.oxy.remotely.flow.ui.AdvancementDesignerScreen;
import redxax.oxy.remotely.flow.ui.AutomationDefinitionDesignerScreen;
import redxax.oxy.remotely.flow.ui.ContentDesignerScreen;
import redxax.oxy.remotely.flow.ui.DialogDesignerScreen;
import redxax.oxy.remotely.flow.ui.GraphEditorScreen;
import redxax.oxy.remotely.flow.ui.GuiDesignerScreen;
import redxax.oxy.remotely.flow.ui.ManagedResourceDesignerScreen;
import redxax.oxy.remotely.flow.ui.ResourceDesigners;
import redxax.oxy.remotely.flow.ui.ScoreboardDesignerScreen;
import redxax.oxy.remotely.flow.ui.StudioCloseHandledScreen;
import redxax.oxy.remotely.flow.ui.TabDesignerScreen;
import redxax.oxy.remotely.flow.ui.WorldDesignerScreen;
import redxax.oxy.remotely.flow.ui.marketplace.ReSyncMarketplaceScreen;
import redxax.oxy.remotely.ui.collaboration.CollaborationAvatarResolver;
import redxax.oxy.remotely.ui.collaboration.CollaborationOverlay;
import redxax.oxy.remotely.ui.integrations.luckperms.LuckPermsDashboardScreen;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import redxax.oxy.remotely.worldgen.data.WorldGenSerializer;
import redxax.oxy.remotely.worldgen.ui.WorldGenEditorScreen;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.core.WidgetCleanup;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.widgets.ContextMenuWidget;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.rescreen.TabsManager;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.IconMessage;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.ReorderableWidget;
import restudio.rescreen.ui.widgets.RowWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.resync.contract.install.ReSyncInstallationStatus;
import restudio.rescreen.ui.widgets.TitledRowWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Identifier;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.command.CommandGraphMetadata;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static restudio.rescreen.config.Config.deltaTime;
import static restudio.rescreen.render.TextRenderer.tr;

public class StudioScreen extends StudioInfiniteScreen {
    private static final BrowserWork.Executor STUDIO_RESOURCE_OPENS = BrowserWork.executor();
    private final Map<String, CursorPosition> studioCursorPositions = new HashMap<>();
    private String studioCursorDocumentKey;

    private static final class CursorPosition {
        private double x;
        private double y;
        private boolean initialized;

        private void moveTo(double targetX, double targetY) {
            if (!initialized) {
                x = targetX;
                y = targetY;
                initialized = true;
            }
            double factor = 1.0 - Math.exp(-22.0 * Math.max(0.0F, deltaTime));
            x += (targetX - x) * factor;
            y += (targetY - y) * factor;
        }
    }

    protected boolean studioMode;
    protected TabsManager studioTabsManager;
    protected ReSyncContentBrowserWidget studioContentBrowser;
    protected SidePanel studioResourcePanel;
    protected StudioPanel studioResourceStudioPanel;
    protected final ReSyncStudioPanelState studioPanelState = new ReSyncStudioPanelState();
    private final CollaborationOverlay collaborationOverlay = new CollaborationOverlay(
        new CollaborationAvatarResolver(), runnable -> ScreenManager.getInstance().execute(runnable));
    private TextInputWidget collaborationChatInput;
    private String collaborationChatDraft = "";
    private ContextMenuWidget studioContextMenuPointerTarget;
    private final Set<String> pendingStudioResourceRequests = BrowserSafeState.set();
    private static final long CORE_STUDIO_REBIND_TIMEOUT_MILLIS = 15_000L;
    private static final long LUCKPERMS_PREPARE_TIMEOUT_SECONDS = 15L;
    private String pendingCoreStudioDocumentKey = "";
    private String pendingCoreStudioPreviousDocumentKey = "";
    private long pendingCoreStudioDocumentAt;
    private boolean restoringPendingCoreStudioSelection;
    private final Map<String, String> terminalCoreStudioRebinds = new HashMap<>();
    private final Set<String> terminalLegacyCoreDocuments = new HashSet<>();
    private final Set<String> diagnosedLegacyCoreConflicts = new HashSet<>();
    private final Map<String, CoreOpenIntent> pendingCoreOpenIntents = new LinkedHashMap<>();
    private static final long CORE_OPEN_RETRY_MILLIS = 1_000L;

    private enum CoreStudioOwnership {
        NOT_GRAPH,
        PENDING_CORE,
        CORE_REQUIRED,
        TERMINAL_CORE,
        TERMINAL_LEGACY
    }

    private enum CoreOpenPhase {
        REQUESTING,
        PREPARING
    }

    private record CoreOpenIntent(ReSyncResourceType type, String id, String title, String previousDocumentKey,
                                  StudioDocument retainedDocument, long acceptedAt, long lastRequestAt,
                                  CoreOpenPhase phase, boolean activated) {
        private CoreOpenIntent requested(long now) {
            return new CoreOpenIntent(type, id, title, previousDocumentKey, retainedDocument, acceptedAt, now,
                phase, activated);
        }

        private CoreOpenIntent preparing(boolean activated) {
            return new CoreOpenIntent(type, id, title, previousDocumentKey, retainedDocument, acceptedAt,
                lastRequestAt, CoreOpenPhase.PREPARING, this.activated || activated);
        }

        private String key() {
            return ReSyncProjectMetadata.resourceKey(type.typeId(), id);
        }
    }

    @Override
    public Map<String, Widget> collaborationContainers() {
        Map<String, Widget> containers = new LinkedHashMap<>(super.collaborationContainers());
        if (studioResourcePanel != null && isSidePanelActive(studioResourcePanel) && studioResourcePanel.isVisible()) {
            containers.put(studioResourcePanel.id(), studioResourcePanel.container());
        }
        return Collections.unmodifiableMap(containers);
    }

    private long collaborationChatOpenedAt;
    private IconButton collaborationChangeBadge;
    private long lastPresenceAt;
    private int lastPresenceX = Integer.MIN_VALUE;
    private int lastPresenceY = Integer.MIN_VALUE;
    private String lastPresenceDocument = "";
    private boolean lastPresenceTyping;
    protected final List<AnimatedWidget> studioResourcePanelWidgets = new ArrayList<>();
    protected String studioResourcePanelKey = "";
    protected final Map<String, TextInputWidget> studioResourcePanelInputs = new HashMap<>();
    protected final Map<String, ToggleWidget> studioResourcePanelToggles = new HashMap<>();
    protected IconMessage studioEmptyMessage;
    protected final List<StudioDocument> studioDocuments = new ArrayList<>();
    protected static final long STUDIO_TAB_STATE_REFRESH_NANOS = 50_000_000L;
    private static final String AUTOMATION_DOCUMENT_TYPE = "automation_definition_workspace";
    private static final String AUTOMATION_DOCUMENT_ID = "all";
    protected long nextStudioTabStateRefreshAt;
    protected StudioDocument activeStudioDocument;
    protected final FlowGraph studioEmptyGraph = new FlowGraph();
    protected boolean syncingStudioTabSelection;
    protected String activeNodeRegistryServerId;
    protected final Gson gson = new Gson();
    protected TextInputWidget commandLabelInput;
    protected final List<TextInputWidget> commandPathInputs = new ArrayList<>();
    private ReorderableWidget<RowWidget> commandPathList;
    protected ToggleWidget commandStructuredToggle;
    private boolean syncingCommandPanel;
    protected final List<AnimatedWidget> headerButtons = new ArrayList<>();
    protected final List<AnimatedWidget> activeViewHeaderButtons = new ArrayList<>();
    protected ItemSelectorWidget activeStudioSelector;
    protected boolean fullEditorHeaderCloseRequested;
    protected ReSyncResourceDragPayload studioResourceDrag;
    protected AnimatedWidget studioResourceDragWidget;
    private int studioResourceDragSourceX;
    private int studioResourceDragSourceY;
    private int studioResourceDragSourceWidth;
    private int studioResourceDragSourceHeight;
    private int studioResourceDragDetachedWidth;
    private int studioResourceDragGrabX;
    private int studioResourceDragGrabY;
    private StudioResourceDragDestination studioResourceDragDestination;
    private final Map<String, Long> studioResourceOpenIntents = new HashMap<>();
    private final Map<String, Object> studioResourcePanelResources = new HashMap<>();
    private final Object studioPanelSaveLock = new Object();
    private final Map<String, ArrayDeque<StudioPanelSaveIntent>> studioPanelSaves = new HashMap<>();
    private int studioPanelSaveCount;
    private long studioResourceOpenVersion;

    private record StudioResourceDragDestination(int x, int y, int width, int height, boolean close) {
    }

    private record PreparedStudioResource(String type, String id, String title, Object value, boolean fullEditor,
                                          boolean replaceExisting) {
    }

    private record StudioPanelSaveIntent(String serverId, ReSyncResourceType type, String id,
                                         DesignerSaveNotifications.SaveTicket ticket, Consumer<Object> mutation) {
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (openCollaborationChat(event)) {
            return true;
        }
        if (super.keyPressed(event)) {
            return true;
        }
        return handleStudioHistoryShortcut(event);
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        if (collaborationChatInput != null && System.currentTimeMillis() - collaborationChatOpenedAt < 150L
            && "t".equalsIgnoreCase(event.text())) {
            return true;
        }
        return super.textInput(event);
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (routeStudioContextMenuMouseClicked(event)) {
            return true;
        }
        TextInputWidget input = collaborationChatInput;
        if (input != null) {
            if (input.isMouseOver(event.x(), event.y())) {
                setFocusedWidget(input);
                return input.mouseClicked(event.retarget(input, event.x(), event.y()));
            }
            dismissCollaborationChat(true);
        }
        return super.mouseClicked(event);
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (routeStudioContextMenuMouseDragged(event)) {
            return true;
        }
        TextInputWidget input = collaborationChatInput;
        if (input != null && getFocusedWidget() == input
            && input.mouseDragged(event.retarget(input, event.x(), event.y(), event.deltaX(), event.deltaY()))) {
            return true;
        }
        return super.mouseDragged(event);
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (routeStudioContextMenuMouseReleased(event)) {
            return true;
        }
        return super.mouseReleased(event);
    }

    protected final boolean routeStudioContextMenuMouseClicked(ReMouseEvent event) {
        ContextMenuWidget menu = topmostOpenStudioContextMenu();
        if (menu == null) {
            studioContextMenuPointerTarget = null;
            return false;
        }
        studioContextMenuPointerTarget = menu;
        Widget.dispatchMouseClicked(menu, event.retarget(menu, event.x(), event.y()));
        return true;
    }

    protected final boolean routeStudioContextMenuMouseDragged(ReMouseEvent event) {
        ContextMenuWidget menu = studioContextMenuPointerTarget != null
            ? studioContextMenuPointerTarget
            : topmostOpenStudioContextMenu();
        if (menu == null) {
            return false;
        }
        Widget.dispatchMouseDragged(menu, event.retarget(menu, event.x(), event.y(), event.deltaX(), event.deltaY()));
        return true;
    }

    protected final boolean routeStudioContextMenuMouseReleased(ReMouseEvent event) {
        ContextMenuWidget menu = studioContextMenuPointerTarget != null
            ? studioContextMenuPointerTarget
            : topmostOpenStudioContextMenu();
        if (menu == null) {
            return false;
        }
        try {
            Widget.dispatchMouseReleased(menu, event.retarget(menu, event.x(), event.y()));
        } finally {
            studioContextMenuPointerTarget = null;
        }
        return true;
    }

    private ContextMenuWidget topmostOpenStudioContextMenu() {
        for (int index = hudWidgets.size() - 1; index >= 0; index--) {
            if (hudWidgets.get(index) instanceof ContextMenuWidget menu && menu.isVisible() && menu.isOpen()) {
                return menu;
            }
        }
        for (int index = widgets.size() - 1; index >= 0; index--) {
            if (widgets.get(index) instanceof ContextMenuWidget menu && menu.isVisible() && menu.isOpen()) {
                return menu;
            }
        }
        return null;
    }

    private boolean openCollaborationChat(ReKeyEvent event) {
        if (collaborationChatInput != null || event.key() != ReKey.T || event.repeat() || event.modifiers().control()
            || event.modifiers().alt() || event.modifiers().superKey() || event.modifiers().shift()
            || !studioMode || isStudioKeyboardInputFocused()) {
            return false;
        }
        ReSyncStudioView view = activeStudioView();
        if (view instanceof StudioSelectorView selectorView && selectorView.hasActiveStudioSelector()) {
            return false;
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || !manager.isFlowClientConnected(studioServerId())) {
            return false;
        }
        ReSyncFlowClient client = manager.ensureFlowClient(studioServerId());
        if (client == null || !client.supportsFlowCapability("collaboration_chat")) {
            return false;
        }
        collaborationChatInput = new TextInputWidget.Builder()
            .placeholder("Message")
            .text(collaborationChatDraft)
            .maxLength(240)
            .size(Math.clamp(width - 40, 140, 280), 18)
            .onChange(value -> lastPresenceAt = 0L)
            .onEnter(this::sendCollaborationChat)
            .onEscape(this::cancelCollaborationChat)
            .build();
        collaborationChatOpenedAt = System.currentTimeMillis();
        setFocusedWidget(collaborationChatInput);
        collaborationChatInput.selectAll();
        return true;
    }

    private void sendCollaborationChat() {
        if (collaborationChatInput == null) {
            return;
        }
        String message = collaborationChatInput.getText().trim();
        boolean sent = false;
        if (!message.isBlank()) {
            FlowManager manager = FlowManager.getInstance();
            if (manager != null) {
                ReSyncFlowClient client = manager.ensureFlowClient(studioServerId());
                sent = client != null && manager.isFlowClientConnected(studioServerId())
                    && manager.withCurrentFlowClientNow(studioServerId(), client,
                        current -> current.collaboration().publishMessage(message));
                if (!sent) {
                    showFlowAdmissionIssue(manager, "Collaboration");
                }
            }
        }
        dismissCollaborationChat(!sent && !message.isBlank());
    }

    private void requestStudioResource(FlowManager manager, ReSyncResourceType type, String id) {
        String serverId = studioServerId();
        if (manager == null || type == null || serverId == null || serverId.isBlank() || id == null || id.isBlank()) {
            new Notification("ReSync", "Connection Unavailable", Notification.Type.ERROR);
            return;
        }
        ReSyncFlowClient client = manager.ensureFlowClient(serverId);
        if (client != null && manager.withCurrentFlowClientNow(serverId, client,
            current -> current.requestResource(type, id, true))) {
            return;
        }
        String requestKey = serverId + ":" + type.typeId() + ":" + id;
        if (!pendingStudioResourceRequests.add(requestKey)) {
            return;
        }
        boolean loading = showFlowAdmissionIssue(manager, "ReSync");
        manager.ensureFlowClientAsync(serverId).whenComplete((resolved, error) ->
            ScreenManager.getInstance().execute(() -> {
                if (!pendingStudioResourceRequests.remove(requestKey)) {
                    return;
                }
                if (resolved != null && error == null
                    && manager.withCurrentFlowClientNow(serverId, resolved,
                        current -> current.requestResource(type, id, true))) {
                    return;
                }
                if (resolved != null && error == null) {
                    String coreKey = ReSyncProjectMetadata.resourceKey(type.typeId(), id);
                    if (type.isGraph() && (pendingCoreOpenIntents.containsKey(coreKey)
                        || terminalCoreStudioRebinds.containsKey(coreKey))) {
                        return;
                    }
                    requestStudioResource(manager, type, id);
                } else if (loading) {
                    showFlowUnavailable(manager, "ReSync");
                }
            }));
    }

    protected void requestCoreOpenResource(FlowManager manager, ReSyncResourceType type, String id) {
        if (manager != null && type != null && type.isGraph() && id != null && !id.isBlank()) {
            String key = ReSyncProjectMetadata.resourceKey(type.typeId(), id);
            CoreOpenIntent intent = pendingCoreOpenIntents.get(key);
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_hydration_requested", "serverId",
                studioServerId(), "resourceKey", key, "requestId", "open", "mutationId", null, "generation",
                -1L, "authorityEpoch", 0L, "revision", -1L, "phase",
                intent != null ? intent.phase() : "untracked", "activate",
                Objects.equals(pendingCoreStudioDocumentKey, key), "elapsedMs",
                intent != null ? Math.max(0L, coreOpenNow() - intent.acceptedAt()) : -1L, "reason",
                "targeted_core_hydration");
            manager.requestCoreGraphHydration(studioServerId(), type, id);
            return;
        }
        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_hydration_rejected", "serverId",
            studioServerId(), "resourceKey", (type != null ? type.typeId() : "unknown") + ":" + id,
            "requestId", "open", "mutationId", null, "generation", -1L, "authorityEpoch", 0L,
            "revision", -1L, "reason", manager == null ? "manager_missing"
                : type == null ? "type_missing" : !type.isGraph() ? "type_not_graph"
                : id == null || id.isBlank() ? "id_missing" : "invalid_input");
    }

    protected long coreOpenNow() {
        return System.currentTimeMillis();
    }

    protected void notifyCoreGraphOpenFailed(String reason) {
        String message = switch (reason == null ? "" : reason) {
            case "resource_read_only" -> "Resource Read Only";
            case "incompatible_capabilities" -> "Unsupported Capabilities";
            case "legacy_draft_dirty", "legacy_ownership_unverified" -> "Legacy Draft Preserved";
            default -> reason == null || reason.isBlank() ? "Resource Unavailable" : reason;
        };
        new Notification("Open Resource", message, Notification.Type.ERROR);
    }

    private CoreOpenIntent acceptCoreOpenIntent(ReSyncResourceType type, String id, String title) {
        if (type == null || !type.isGraph() || id == null || id.isBlank()) {
            return null;
        }
        String key = ReSyncProjectMetadata.resourceKey(type.typeId(), id);
        demotePendingCoreActivationExcept(key);
        String previousDocumentKey = activeStudioDocument != null && !key.equals(activeStudioDocument.key())
            ? activeStudioDocument.key() : "";
        StudioDocument retainedDocument = activeStudioDocument != null && key.equals(activeStudioDocument.key())
            ? activeStudioDocument : null;
        long acceptedAt = coreOpenNow();
        CoreOpenIntent previous = pendingCoreOpenIntents.putIfAbsent(key, new CoreOpenIntent(type, id,
            title == null || title.isBlank() ? id : title, previousDocumentKey, retainedDocument, acceptedAt,
            acceptedAt, CoreOpenPhase.REQUESTING, false));
        if (previous != null) {
            if (previousDocumentKey.isBlank()) {
                previousDocumentKey = previous.previousDocumentKey();
            }
            acceptedAt = previous.acceptedAt();
        }
        pendingCoreStudioDocumentKey = key;
        pendingCoreStudioPreviousDocumentKey = previousDocumentKey;
        pendingCoreStudioDocumentAt = acceptedAt;
        if (previous != null) {
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_open_intent_retained", "serverId",
                studioServerId(), "resourceKey", key, "requestId", "open", "mutationId", null,
                "generation", -1L, "authorityEpoch", 0L, "revision", -1L, "phase", previous.phase(),
                "activate", Objects.equals(pendingCoreStudioDocumentKey, key), "elapsedMs",
                Math.max(0L, coreOpenNow() - previous.acceptedAt()), "reason", "intent_already_pending");
            return previous;
        }
        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_open_intent_accepted", "serverId",
            studioServerId(), "resourceKey", key, "requestId", "open", "mutationId", null, "generation",
            -1L, "authorityEpoch", 0L, "revision", -1L);
        return pendingCoreOpenIntents.get(key);
    }

    private void demotePendingCoreActivationExcept(String retainedKey) {
        if (pendingCoreStudioDocumentKey != null && !pendingCoreStudioDocumentKey.isBlank()
            && !Objects.equals(pendingCoreStudioDocumentKey, retainedKey)) {
            clearPendingCoreStudioDocument();
        }
    }

    boolean hasPendingCoreOpenIntent(String type, String id) {
        return pendingCoreOpenIntents.containsKey(ReSyncProjectMetadata.resourceKey(type, id));
    }

    String coreOpenTerminalReason(String type, String id) {
        return terminalCoreStudioRebinds.get(ReSyncProjectMetadata.resourceKey(type, id));
    }

    private CoreStudioOwnership coreStudioOwnership(FlowManager manager, ReSyncResourceType type, String id) {
        if (type == null || !type.isGraph()) {
            return CoreStudioOwnership.NOT_GRAPH;
        }
        if (manager == null || id == null || id.isBlank()) {
            return CoreStudioOwnership.PENDING_CORE;
        }
        ReSyncFlowClient.CoreGraphActivationOutcome outcome = manager.peekCoreGraphActivation(
            studioServerId(), type, id);
        if (outcome.state() == ReSyncFlowClient.CoreGraphActivationState.LIVE) {
            return CoreStudioOwnership.CORE_REQUIRED;
        }
        if (terminalCoreActivation(outcome)) {
            return CoreStudioOwnership.TERMINAL_CORE;
        }
        ReSyncFlowClient client = manager.existingFlowClient(studioServerId());
        if (client != null && manager.isFlowClientConnected(studioServerId())
            && ReSyncFlowClient.catalogAuthorityAllowsLegacyEditing(client.catalogAuthority())) {
            return CoreStudioOwnership.TERMINAL_LEGACY;
        }
        return CoreStudioOwnership.PENDING_CORE;
    }

    private boolean routeCoreGraphOpen(FlowManager manager, ReSyncResourceType type, String id, String title) {
        CoreStudioOwnership ownership = coreStudioOwnership(manager, type, id);
        String key = ReSyncProjectMetadata.resourceKey(type != null ? type.typeId() : "unknown", id);
        ReSyncFlowClient.CoreGraphActivationOutcome outcome = coreGraphActivation(manager, type, id);
        CoreOpenIntent pendingIntent = pendingCoreOpenIntents.get(key);
        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_activation_decision", "serverId",
            studioServerId(), "resourceKey", key, "requestId", "open", "mutationId", null, "generation",
            -1L, "authorityEpoch", 0L, "revision", outcome.revision(), "ownership", ownership,
            "activationState", outcome.state(), "activationReason", outcome.reason(), "managerPresent",
            manager != null, "reason", ownership == CoreStudioOwnership.NOT_GRAPH ? "route_not_graph"
                : ownership == CoreStudioOwnership.TERMINAL_LEGACY ? "route_legacy_editor"
                : terminalCoreActivation(outcome) && !coreActivationStillSettling(outcome)
                    ? "route_terminal_core" : outcome.state() == ReSyncFlowClient.CoreGraphActivationState.LIVE
                        ? "route_live_core" : "route_pending_core", "phase",
            pendingIntent != null ? pendingIntent.phase() : "none", "activate",
            Objects.equals(pendingCoreStudioDocumentKey, key));
        if (ownership == CoreStudioOwnership.NOT_GRAPH || ownership == CoreStudioOwnership.TERMINAL_LEGACY) {
            return false;
        }
        if (terminalCoreActivation(outcome) && !coreActivationStillSettling(outcome)) {
            String terminalReason = coreActivationReason(outcome);
            terminalCoreStudioRebinds.put(key, terminalReason);
            notifyCoreGraphOpenFailed(terminalReason);
            return true;
        }
        CoreGraphEditorSession session = outcome.state() == ReSyncFlowClient.CoreGraphActivationState.LIVE
            ? manager.peekCoreGraphEditorSession(studioServerId(), type, id).orElse(null) : null;
        if (session != null) {
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_activation_session_ready", "serverId",
                studioServerId(), "resourceKey", key, "requestId", "open", "mutationId", null,
                "generation", -1L, "authorityEpoch", 0L, "revision", session.revision(), "activate", true,
                "reason", "current_session_present");
            acceptCoreOpenIntent(type, id, title);
            openStudioCoreDocument(type.typeId(), id, title, session);
            return true;
        }
        terminalCoreStudioRebinds.remove(key);
        CoreOpenIntent intent = acceptCoreOpenIntent(type, id, title);
        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_open_pending", "serverId",
            studioServerId(), "resourceKey", key, "requestId", "open", "mutationId", null, "generation",
            -1L, "authorityEpoch", 0L, "revision", outcome.revision(), "phase",
            intent != null ? intent.phase() : "missing", "activate", true, "reason", "session_missing");
        showPendingCoreStudioDocument(intent);
        requestCoreOpenResource(manager, type, id);
        return true;
    }

    private void showPendingCoreStudioDocument(CoreOpenIntent intent) {
        retainVisibleStudioDocument();
    }

    private ReSyncFlowClient.CoreGraphActivationOutcome coreGraphActivation(
        FlowManager manager, ReSyncResourceType type, String id) {
        if (manager != null) {
            return manager.peekCoreGraphActivation(studioServerId(), type, id);
        }
        return new ReSyncFlowClient.CoreGraphActivationOutcome(ReSyncFlowClient.CoreGraphActivationState.PENDING,
            "The Core graph connection is unavailable.", 0L);
    }

    private boolean terminalCoreActivation(ReSyncFlowClient.CoreGraphActivationOutcome outcome) {
        return outcome != null && (outcome.state() == ReSyncFlowClient.CoreGraphActivationState.READ_ONLY
            || outcome.state() == ReSyncFlowClient.CoreGraphActivationState.INCOMPATIBLE
            || outcome.state() == ReSyncFlowClient.CoreGraphActivationState.MISSING);
    }

    private boolean coreActivationStillSettling(ReSyncFlowClient.CoreGraphActivationOutcome outcome) {
        if (outcome == null || outcome.state() != ReSyncFlowClient.CoreGraphActivationState.INCOMPATIBLE) {
            return false;
        }
        String reason = outcome.reason() == null ? "" : outcome.reason().toLowerCase(Locale.ROOT);
        return reason.contains("catalog binding") || reason.contains("authoring publication");
    }

    private boolean userVisibleCoreOpenFailure(String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        return !normalized.contains("catalog binding") && !normalized.equals("session_not_current");
    }

    private String coreActivationReason(ReSyncFlowClient.CoreGraphActivationOutcome outcome) {
        if (outcome != null && outcome.reason() != null && !outcome.reason().isBlank()) {
            return outcome.reason();
        }
        if (outcome == null) {
            return "Resource Unavailable";
        }
        return switch (outcome.state()) {
            case READ_ONLY -> "Resource Read Only";
            case INCOMPATIBLE -> "Unsupported Capabilities";
            case MISSING -> "Resource Missing";
            case LIVE, PENDING -> "Resource Unavailable";
        };
    }

    private boolean allowLegacyCoreReplacement(StudioDocument document) {
        ReSyncResourceType type = document != null ? ReSyncResourceType.byTypeId(document.type()) : null;
        if (document == null || document.coreSession() != null || type == null || !type.isGraph()) {
            return true;
        }
        if (document.graph() == null && document.view() == null) {
            return true;
        }
        String reason = isStudioDocumentDirty(document) ? "legacy_draft_dirty"
            : !terminalLegacyCoreDocuments.contains(document.key()) ? "legacy_ownership_unverified" : "";
        if (reason.isEmpty()) {
            return true;
        }
        diagnoseLegacyCoreConflict(document, reason);
        return false;
    }

    private void diagnoseLegacyCoreConflict(StudioDocument document, String reason) {
        if (document == null || reason == null || reason.isBlank()) {
            return;
        }
        if (diagnosedLegacyCoreConflicts.add(document.key())) {
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_legacy_preserved", "serverId",
                studioServerId(), "resourceKey", document.key(), "requestId", "open", "mutationId", null,
                "generation", -1L, "authorityEpoch", 0L, "revision",
                document.graph() != null ? document.graph().getResourceRevision() : -1L,
                "reason", reason);
            new Notification("Core Editor", "Legacy Draft Preserved", Notification.Type.ERROR);
        }
    }

    void drainCoreOpenIntents() {
        if (pendingCoreOpenIntents.isEmpty()) {
            return;
        }
        FlowManager manager = FlowManager.getInstance();
        long now = coreOpenNow();
        for (CoreOpenIntent intent : List.copyOf(pendingCoreOpenIntents.values())) {
            if (manager == null) {
                if (coreOpenExpired(intent, now)) {
                    failCoreOpenIntent(intent, "The Core graph connection is unavailable.");
                }
                continue;
            }
            StudioDocument document = findStudioDocument(intent.key());
            boolean activationOwner = Objects.equals(pendingCoreStudioDocumentKey, intent.key());
            boolean activeDocument = document != null && activeStudioDocument != null
                && Objects.equals(activeStudioDocument.key(), intent.key());
            if (activationOwner && activeDocument && document.coreSession() != null
                && coreStudioDocumentTerminal(document)) {
                failCoreOpenIntent(intent, "widget_publication_failed");
                continue;
            }
            if (document != null && document.coreSession() != null && (!activationOwner
                || activeDocument && coreStudioDocumentReady(document))) {
                pendingCoreOpenIntents.remove(intent.key());
                if (activationOwner) {
                    clearPendingCoreStudioDocument();
                }
                GraphEditorScreen graphEditor = this instanceof GraphEditorScreen editor ? editor : null;
                long generation = graphEditor != null ? graphEditor.coreEditorReadiness().generation() : 0L;
                String topologyChecksum = graphEditor != null
                    ? graphEditor.coreEditorReadiness().topologyChecksum() : "bound";
                ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_open_intent_ready", "serverId",
                    studioServerId(), "resourceKey", intent.key(), "requestId", "open", "mutationId", null,
                    "generation", generation, "authorityEpoch", 0L, "revision",
                    document.coreSession().revision(), "topologyChecksum",
                    topologyChecksum, "phase", intent.phase(), "activate", activationOwner, "documentPresent", true,
                    "activeDocument", activeDocument, "tabPresent", studioTabPresent(intent.key()), "tabSelected",
                    studioTabSelected(intent.key()), "currentSession", isCurrentCoreStudioDocument(document),
                    "editorReady", !(this instanceof GraphEditorScreen editor) || editor.isCoreEditorReady(),
                    "userVisibleReady", coreStudioDocumentReady(document), "elapsedMs",
                    Math.max(0L, now - intent.acceptedAt()), "reason", "document_and_topology_visible");
                continue;
            }
            ReSyncFlowClient.CoreGraphActivationOutcome outcome = coreGraphActivation(
                manager, intent.type(), intent.id());
            if (terminalCoreActivation(outcome) && !coreActivationStillSettling(outcome)) {
                failCoreOpenIntent(intent, coreActivationReason(outcome));
                continue;
            }
            if (coreOpenExpired(intent, now) && !coreActivationPublicationLoading(outcome)) {
                failCoreOpenIntent(intent, coreActivationReason(outcome));
                continue;
            }
            CoreGraphEditorSession session = outcome.state() == ReSyncFlowClient.CoreGraphActivationState.LIVE
                ? manager.peekCoreGraphEditorSession(studioServerId(), intent.type(), intent.id()).orElse(null) : null;
            if (session != null) {
                terminalCoreStudioRebinds.remove(intent.key());
                if (intent.phase() == CoreOpenPhase.REQUESTING) {
                    ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_open_phase", "serverId",
                        studioServerId(), "resourceKey", intent.key(), "requestId", "open", "mutationId", null,
                        "generation", -1L, "authorityEpoch", 0L, "revision", session.revision(), "phase",
                        "requesting_to_preparing", "activate", activationOwner, "documentPresent", document != null,
                        "activeDocument", activeDocument, "elapsedMs", Math.max(0L, now - intent.acceptedAt()),
                        "reason", "current_session_observed");
                    boolean bound;
                    if (document == null || document.coreSession() != session) {
                        bound = bindStudioCoreDocument(intent.type().typeId(), intent.id(), intent.title(), session,
                            activationOwner);
                    } else if (activationOwner && !activeDocument) {
                        selectStudioDocument(intent.key());
                        bound = activeStudioDocument == document;
                    } else {
                        bound = true;
                    }
                    if (bound) {
                        if (activationOwner) {
                            pendingCoreOpenIntents.replace(intent.key(), intent, intent.preparing(true));
                            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_open_preparing", "serverId",
                                studioServerId(), "resourceKey", intent.key(), "requestId", "open", "mutationId",
                                null, "generation", -1L, "authorityEpoch", 0L, "revision", session.revision(),
                                "phase", CoreOpenPhase.PREPARING, "activate", true, "tabPresent",
                                studioTabPresent(intent.key()), "tabSelected", studioTabSelected(intent.key()),
                                "elapsedMs", Math.max(0L, now - intent.acceptedAt()), "reason",
                                "core_document_bound_waiting_for_widgets");
                        } else {
                            pendingCoreOpenIntents.remove(intent.key(), intent);
                            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_background_bind_complete",
                                "serverId", studioServerId(), "resourceKey", intent.key(), "requestId", "open",
                                "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision",
                                session.revision(), "phase", "bound", "activate", false, "elapsedMs",
                                Math.max(0L, now - intent.acceptedAt()), "reason", "background_document_bound");
                        }
                    } else {
                        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_open_bind_rejected", "serverId",
                            studioServerId(), "resourceKey", intent.key(), "requestId", "open", "mutationId", null,
                            "generation", -1L, "authorityEpoch", 0L, "revision", session.revision(), "phase",
                            intent.phase(), "activate", activationOwner, "elapsedMs",
                            Math.max(0L, now - intent.acceptedAt()), "reason", "bind_returned_false");
                    }
                }
                continue;
            }
            if (intent.phase() == CoreOpenPhase.PREPARING && intent.activated()) {
                continue;
            }
            if (now - intent.lastRequestAt() < CORE_OPEN_RETRY_MILLIS) {
                continue;
            }
            pendingCoreOpenIntents.put(intent.key(), intent.requested(now));
            requestCoreOpenResource(manager, intent.type(), intent.id());
        }
    }

    private boolean coreOpenExpired(CoreOpenIntent intent, long now) {
        return intent != null && now - intent.acceptedAt() >= CORE_STUDIO_REBIND_TIMEOUT_MILLIS;
    }

    private boolean coreActivationPublicationLoading(ReSyncFlowClient.CoreGraphActivationOutcome outcome) {
        return outcome != null && outcome.state() == ReSyncFlowClient.CoreGraphActivationState.PENDING
            && outcome.revision() > 0L;
    }

    private boolean failCoreOpenIntent(CoreOpenIntent intent, String reason) {
        if (intent == null || !pendingCoreOpenIntents.remove(intent.key(), intent)) {
            return false;
        }
        String failureReason = reason == null || reason.isBlank() ? "resource_unavailable" : reason;
        terminalCoreStudioRebinds.put(intent.key(), failureReason);
        String previousKey = intent.previousDocumentKey();
        restoreRetainedCoreStudioDocument(intent);
        boolean activationOwner = Objects.equals(pendingCoreStudioDocumentKey, intent.key());
        if (activationOwner) {
            if (pendingCoreStudioPreviousDocumentKey != null && !pendingCoreStudioPreviousDocumentKey.isBlank()) {
                previousKey = pendingCoreStudioPreviousDocumentKey;
            }
            clearPendingCoreStudioDocument();
            restorePendingCoreStudioSelection(previousKey);
        }
        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_open_intent_failed", "serverId",
            studioServerId(), "resourceKey", intent.key(), "requestId", "open", "mutationId", null,
            "generation", -1L, "authorityEpoch", 0L, "revision", -1L, "phase", intent.phase(), "activate",
            activationOwner, "documentPresent", findStudioDocument(intent.key()) != null, "tabPresent",
            studioTabPresent(intent.key()), "tabSelected", studioTabSelected(intent.key()), "elapsedMs",
            Math.max(0L, coreOpenNow() - intent.acceptedAt()), "reason", failureReason);
        if (userVisibleCoreOpenFailure(failureReason)) {
            notifyCoreGraphOpenFailed(failureReason);
        }
        return true;
    }

    protected boolean coreStudioDocumentReady(StudioDocument document) {
        return document != null && document.coreSession() != null && isCurrentCoreStudioDocument(document)
            && (!(this instanceof GraphEditorScreen editor) || editor.isCoreEditorReady());
    }

    protected boolean coreStudioDocumentTerminal(StudioDocument document) {
        return document != null && document.coreSession() != null && this instanceof GraphEditorScreen editor
            && editor.hasTerminalCoreWidgetPublicationFailure();
    }

    private void restoreRetainedCoreStudioDocument(CoreOpenIntent intent) {
        StudioDocument retained = intent != null ? intent.retainedDocument() : null;
        if (retained == null || !Objects.equals(retained.key(), intent.key())) {
            return;
        }
        for (int index = 0; index < studioDocuments.size(); index++) {
            StudioDocument current = studioDocuments.get(index);
            if (!Objects.equals(current.key(), intent.key())) {
                continue;
            }
            studioDocuments.set(index, retained);
            if (activeStudioDocument == current || activeStudioDocument != null
                && Objects.equals(activeStudioDocument.key(), intent.key())) {
                activeStudioDocument = retained;
            }
            syncStudioDocumentTabs();
            return;
        }
    }

    private boolean showFlowAdmissionIssue(FlowManager manager, String title) {
        String issue = manager.getFlowAvailabilityIssue(studioServerId(), null);
        boolean loading = issue == null || "ReSyncProfileLoading".equals(issue) || "ReSyncProfileChanged".equals(issue);
        new Notification(title, loading ? "Connection Loading" : manager.normalizeReSyncNotificationMessage(issue),
            loading ? Notification.Type.INFO : Notification.Type.ERROR);
        return loading;
    }

    private void showFlowUnavailable(FlowManager manager, String title) {
        String issue = manager.getFlowAvailabilityIssue(studioServerId(), null);
        String message = issue == null || "ReSyncProfileLoading".equals(issue) ? "Connection Unavailable"
            : manager.normalizeReSyncNotificationMessage(issue);
        new Notification(title, message, Notification.Type.ERROR);
    }

    private void cancelCollaborationChat() {
        dismissCollaborationChat(false);
    }

    private void dismissCollaborationChat(boolean preserveDraft) {
        TextInputWidget input = collaborationChatInput;
        if (input == null) {
            return;
        }
        collaborationChatDraft = preserveDraft ? input.getText() : "";
        collaborationChatInput = null;
        if (getFocusedWidget() == input) {
            setFocusedWidget(null);
        }
        lastPresenceAt = 0L;
    }

    @Override
    public void tick() {
        super.tick();
        if (studioContentBrowser != null) studioContentBrowser.tick();
        updateStudioTabStates();
        drainCoreOpenIntents();
        ReSyncStudioView activeView = activeStudioView();
        if (activeView != null) {
            activeView.tick();
        }
        if (collaborationChatInput != null) {
            if (getFocusedWidget() != collaborationChatInput || !collaborationChatInput.isFocused()) {
                dismissCollaborationChat(true);
            } else {
                collaborationChatInput.tick();
            }
        }
        collaborationOverlay.tick();
    }

    public void openWorkspaceResource(String type, String id) {
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_open_rejected", "serverId", studioServerId(),
                "resourceKey", type + ":" + id, "requestId", "open", "mutationId", null, "generation", -1L,
                "authorityEpoch", 0L, "revision", -1L, "reason", "invalid_resource_identity");
            return;
        }
        if (AutomationDefinitionDraft.supports(type)) {
            openDefinition(type, id);
            return;
        }
        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_open_requested", "serverId", studioServerId(),
            "resourceKey", type + ":" + id, "requestId", "open", "mutationId", null, "generation", -1L,
            "authorityEpoch", 0L, "revision", -1L);
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return;
        }
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        boolean alreadyOpen = studioDocuments.stream().anyMatch(document -> document.key().equals(key));
        if (alreadyOpen) {
            ReSyncResourceType openType = ReSyncResourceType.byTypeId(type);
            ReSyncProjectMetadata.ResourceEntry openResource = manager.getProjectResource(studioServerId(), type, id);
            String openTitle = openResource != null ? openResource.getDisplayName() : id;
            if (openType != null && openType.isGraph() && routeCoreGraphOpen(manager, openType, id, openTitle)) {
                return;
            }
            selectStudioDocument(key);
            return;
        }
        ReSyncResourceType freshType = ReSyncResourceType.byTypeId(type);
        ReSyncProjectMetadata.ResourceEntry cachedResource = manager.getProjectResource(studioServerId(), type, id);
        String title = cachedResource != null ? cachedResource.getDisplayName() : id;
        if (freshType != null && freshType.isGraph()) {
            if (routeCoreGraphOpen(manager, freshType, id, title)) {
                return;
            }
            if (openStudioResourceSnapshot(type, id, title, false, false)) return;
            requestStudioResource(manager, freshType, id);
            return;
        }
        ReSyncProjectMetadata.ResourceEntry resource = manager.getProjectResource(studioServerId(), type, id);
        if (resource != null) {
            openProjectResource(resource);
            return;
        }
        if (ReSyncResourceDragPayload.WORLDGEN.equals(type)) {
            if (!openStudioResourceSnapshot(type, id, id, false, false)) {
                WorldGenManager.getInstance().requestProject(studioServerId(), id);
            }
            return;
        }
        ReSyncResourceType jsonType = ReSyncResourceType.byTypeId(type);
        if (jsonType != null) {
            if (jsonType == ReSyncResourceType.CUSTOM_CONTENT) {
                if (openStudioResourceSnapshot(type, id, resource != null ? resource.getDisplayName() : id, false, false)) return;
                requestStudioResource(manager, jsonType, id);
                return;
            }
            if (jsonType == ReSyncResourceType.GUI) {
                if (!openStudioResourceSnapshot(type, id, manager.getGuiName(studioServerId(), id), false, false))
                    requestStudioResource(manager, jsonType, id);
                return;
            }
            if (jsonType == ReSyncResourceType.SCOREBOARD) {
                if (!openStudioResourceSnapshot(type, id, manager.getScoreboardName(studioServerId(), id), false, false))
                    requestStudioResource(manager, jsonType, id);
                return;
            }
            if (jsonType == ReSyncResourceType.TAB) {
                if (!openStudioResourceSnapshot(type, id, manager.getTabName(studioServerId(), id), false, false))
                    requestStudioResource(manager, jsonType, id);
                return;
            }
            if (!openStudioResourceSnapshot(type, id, resource != null ? resource.getDisplayName() : id, false, false))
                requestStudioResource(manager, jsonType, id);
            return;
        }
        if (ReSyncResourceDragPayload.WORLD.equals(type)) {
            openStudioWorldDocument(id, id);
            return;
        }
        openOpaqueManagedResourceDocument(type, id, id);
    }

    public void openWorkspaceFlowEditor(String flowId, String branchPin) {
    }

    public void openWorkspaceFlowEditor(String flowId) {
        openWorkspaceFlowEditor(flowId, null);
    }

    public void openWorkspaceCoreEditor(CoreGraphEditorSession session, String title, String branchPin) {
        if (session == null || session.resource() == null) {
            return;
        }
        String type = session.resource().resourceType().value();
        String id = session.resource().id();
        bindStudioCoreDocument(type, id, title, session, true);
    }

    public void bindWorkspaceCoreEditor(CoreGraphEditorSession session, String title) {
        if (session == null || session.resource() == null) {
            return;
        }
        String type = session.resource().resourceType().value();
        String id = session.resource().id();
        bindStudioCoreDocument(type, id, title, session, false);
    }

    public void openWorkspaceGraphEditor(FlowGraph graph) {
        if (graph != null) {
            String type = graph.getResourceType();
            openWorkspaceResource(type == null || type.isBlank() ? ReSyncResourceDragPayload.FLOW : type,
                graph.getId());
        }
    }

    public void openWorkspaceDesigner(String type, String id, boolean fullEditor) {
        openStudioDesigner(type, id, fullEditor);
    }

    public void openWorkspaceContentDesigner(String id, String title, FlowGraph graph) {
        openWorkspaceContentDesigner(id, title, graph, null, true);
    }

    public void openWorkspaceContentDesigner(String id, String title, FlowGraph graph, ContentDesignerScreen screen, boolean persistDocument) {
        if (id == null || id.isBlank() || graph == null || graph.getId() == null || graph.getId().isBlank()) {
            return;
        }
        String documentKey = ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.CUSTOM_CONTENT, id);
        if (persistDocument && studioDocuments.stream().anyMatch(document -> document.key().equals(documentKey))) {
            selectStudioDocument(documentKey);
            return;
        }
        String displayTitle = title != null && !title.isBlank() ? title : id;
        openStudioViewDocument(
            ReSyncResourceDragPayload.CUSTOM_CONTENT,
            id,
            displayTitle,
            graph,
            new ScreenBackedStudioView(this, screen != null ? screen : new ContentDesignerScreen(studioServerId(), graph, this)),
            !persistDocument,
            persistDocument
        );
    }

    public void refreshStudioWorkspace() {
        refreshStudioWorkspace(true);
    }

    public void refreshStudioWorkspace(boolean rebuildContentBrowser) {
        if (rebuildContentBrowser) {
            refreshStudioContentBrowser();
        }
        refreshStudioResourcePanel();
    }

    public void refreshStudioContentBrowserOnly() {
        refreshStudioContentBrowser();
    }

    protected String studioServerId() {
        return "";
    }

    protected String activeStudioDocumentKey() {
        return activeStudioDocument != null ? activeStudioDocument.key() : "none";
    }

    public boolean isStudioMode() {
        return studioMode;
    }

    protected void createStudioContentBrowser() {
        studioContentBrowser = new ReSyncContentBrowserWidget(this, 0, 0, Math.max(0, width), height);
        studioContentBrowser.resetToDefaultHeight();
        studioContentBrowser.clampHeight();
        studioContentBrowser.layoutInScreen();
    }

    protected void createStudioWorkspaceChrome() {
        createStudioWorkspaceChrome(true);
    }

    protected void createStudioWorkspaceChrome(boolean includeWorkspacePanels) {
        studioTabsManager = tabs().builder()
            .position(10, 10)
            .size(width - 220, 18)
            .allowAdd(false)
            .allowClose(true)
            .allowReorder(true)
            .onTabContextMenu(this::showStudioTabMenu)
            .onTabCloseRequested(this::requestStudioTabClose)
            .onTabClosed(tab -> {
                Object data = tab.getData();
                if (data instanceof String key) {
                    removeStudioDocuments(Set.of(key));
                }
            })
            .onTabSelected(tab -> {
                if (syncingStudioTabSelection) {
                    return;
                }
                Object data = tab.getData();
                if (data instanceof String key) {
                    selectStudioDocument(key);
                }
            })
            .build();
        if (includeWorkspacePanels) {
            ensureStudioWorkspacePanels(false);
        }
        syncStudioDocumentTabs();
        clearActiveStudioDocument();
    }

    private void showStudioTabMenu(TabsManager.Tab tab) {
        if (tab == null || tab.getWidget() == null) {
            return;
        }
        ContextMenuWidget.Builder menu = new ContextMenuWidget.Builder(this)
            .addIconItem("Close", "close.png", () -> closeStudioTab(tab), "Close Tab")
            .addIconItem("Close Others", "delete.png", () -> closeOtherStudioTabs(tab), "Keep This Tab")
            .addIconItem("Close All", "close.png", this::closeAllStudioTabs, "Close All Tabs");
        showStudioContextMenu(tab.getWidget().getX(), tab.getWidget().getY() + tab.getWidget().getHeight() + 2, menu);
    }

    private boolean requestStudioTabClose(TabsManager.Tab tab) {
        if (!(tab.getData() instanceof String key)) {
            return true;
        }
        StudioDocument document = findStudioDocument(key);
        if (document == null) {
            return true;
        }
        if (isAutomationDocument(document)) {
            AutomationDefinitionDesignerScreen designer = automationDesigner(document);
            if (designer != null && !designer.canCloseStudioDocument()) {
                return false;
            }
            return discardStudioDocumentForClose(document);
        }
        if (!isStudioDocumentDirty(document)) {
            return true;
        }
        return discardStudioDocumentForClose(document);
    }

    protected boolean discardStudioDocumentForClose(StudioDocument document) {
        if (document == null) {
            return false;
        }
        if (isAutomationDocument(document)) {
            if (document.view() != null) {
                document.view().discardUnsavedChanges();
            }
            return true;
        }
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType type = ReSyncResourceType.byTypeId(document.type());
        if (document.coreSession() != null) {
            if (manager == null || type == null || !manager.ownsCoreGraphEditorSession(
                studioServerId(), type, document.id(), document.coreSession())) {
                new Notification("Core Editor", "Discard Unavailable", Notification.Type.ERROR);
                return false;
            }
            if (!manager.discardCoreGraphSession(studioServerId(), type, document.id())) {
                new Notification("Core Editor", "Discard Unavailable", Notification.Type.ERROR);
                return false;
            }
            return true;
        }
        if (manager != null && type != null) {
            manager.discardResourceDraft(studioServerId(), type, document.id());
        }
        if (document.view() == null && document == activeStudioDocument) {
            discardUnsavedChanges();
        }
        return true;
    }

    private boolean isCurrentCoreStudioDocument(StudioDocument document) {
        if (document == null || document.coreSession() == null) {
            return true;
        }
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType type = ReSyncResourceType.byTypeId(document.type());
        return manager != null && type != null && manager.peekCoreGraphEditorSession(
            studioServerId(), type, document.id()).orElse(null) == document.coreSession();
    }

    protected StudioDocument rebindCoreStudioDocument(StudioDocument document) {
        if (document == null) {
            return null;
        }
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType type = ReSyncResourceType.byTypeId(document.type());
        CoreStudioOwnership ownership = coreStudioOwnership(manager, type, document.id());
        boolean legacyUpgrade = document.coreSession() == null
            && ownership != CoreStudioOwnership.NOT_GRAPH && ownership != CoreStudioOwnership.TERMINAL_LEGACY;
        if (document.coreSession() == null && !legacyUpgrade) {
            return document;
        }
        ReSyncFlowClient.CoreGraphActivationOutcome outcome = coreGraphActivation(manager, type, document.id());
        CoreGraphEditorSession session = outcome.state() == ReSyncFlowClient.CoreGraphActivationState.LIVE
            ? manager.peekCoreGraphEditorSession(studioServerId(), type, document.id()).orElse(null) : null;
        if (session == null) {
            if (legacyUpgrade && document.graph() != null && isStudioDocumentDirty(document)) {
                diagnoseLegacyCoreConflict(document, "legacy_draft_dirty");
            }
            return null;
        }
        if (session == document.coreSession()) {
            return document;
        }
        if (legacyUpgrade && !allowLegacyCoreReplacement(document)) {
            return null;
        }
        StudioDocument rebound = new StudioDocument(document.type(), document.id(), document.title(), null, session, null,
            document.viewport());
        if (legacyUpgrade) {
            if (document.view() != null) {
                document.view().closed();
            }
            studioDocumentClosed(document);
            terminalLegacyCoreDocuments.remove(document.key());
            diagnosedLegacyCoreConflicts.remove(document.key());
        }
        for (int index = 0; index < studioDocuments.size(); index++) {
            if (studioDocuments.get(index) == document) {
                studioDocuments.set(index, rebound);
                if (activeStudioDocument == document) {
                    activeStudioDocument = rebound;
                }
                break;
            }
        }
        return rebound;
    }

    private void retryPendingCoreStudioDocument() {
        String key = pendingCoreStudioDocumentKey;
        if (key == null || key.isBlank()) {
            return;
        }
        if (pendingCoreOpenIntents.containsKey(key)) {
            return;
        }
        StudioDocument document = findStudioDocument(key);
        if (document == null) {
            String previousKey = pendingCoreStudioPreviousDocumentKey;
            clearPendingCoreStudioDocument();
            restorePendingCoreStudioSelection(previousKey);
            return;
        }
        long now = coreOpenNow();
        if (now - pendingCoreStudioDocumentAt >= CORE_STUDIO_REBIND_TIMEOUT_MILLIS) {
            CoreOpenIntent intent = pendingCoreOpenIntents.get(key);
            if (intent == null || !failCoreOpenIntent(intent, "resource_unavailable")) {
                String previousKey = pendingCoreStudioPreviousDocumentKey;
                terminalCoreStudioRebinds.put(key, "resource_unavailable");
                clearPendingCoreStudioDocument();
                restorePendingCoreStudioSelection(previousKey);
            }
            return;
        }
        if (document.coreSession() == null) {
            FlowManager manager = FlowManager.getInstance();
            ReSyncResourceType type = ReSyncResourceType.byTypeId(document.type());
            CoreStudioOwnership ownership = coreStudioOwnership(manager, type, document.id());
            if (ownership == CoreStudioOwnership.PENDING_CORE || ownership == CoreStudioOwnership.CORE_REQUIRED) {
                acceptCoreOpenIntent(type, document.id(), document.title());
                return;
            }
            String previousKey = pendingCoreStudioPreviousDocumentKey;
            clearPendingCoreStudioDocument();
            restorePendingCoreStudioSelection(previousKey);
            return;
        }
        if (rebindCoreStudioDocument(document) != null) {
            clearPendingCoreStudioDocument();
            selectStudioDocument(key);
            return;
        }
    }

    private void clearPendingCoreStudioDocument() {
        pendingCoreStudioDocumentKey = "";
        pendingCoreStudioPreviousDocumentKey = "";
        pendingCoreStudioDocumentAt = 0L;
    }

    protected final boolean blockPendingCoreStudioSave() {
        if (pendingCoreStudioDocumentKey == null || pendingCoreStudioDocumentKey.isBlank()) {
            return false;
        }
        drainCoreOpenIntents();
        if (pendingCoreStudioDocumentKey == null || pendingCoreStudioDocumentKey.isBlank()) {
            return false;
        }
        new Notification("Core Editor", "Editor Loading", Notification.Type.ERROR);
        return true;
    }

    private void restorePendingCoreStudioSelection(String previousKey) {
        StudioDocument previous = previousKey == null || previousKey.isBlank() ? null : findStudioDocument(previousKey);
        if (previous == null) {
            previous = activeStudioDocument;
        }
        if (previous != null && Objects.equals(previous.key(), pendingCoreStudioDocumentKey)) {
            setStudioTabsManagerActiveTab(findStudioTab(previous.key(),
                studioTabsManager != null ? studioTabsManager.getTabs() : List.of()));
            return;
        }
        if (previous == null) {
            return;
        }
        if (activeStudioDocument == null || !activeStudioDocument.key().equals(previous.key())) {
            String pendingKey = pendingCoreStudioDocumentKey;
            String retainedPreviousKey = pendingCoreStudioPreviousDocumentKey;
            long pendingAt = pendingCoreStudioDocumentAt;
            restoringPendingCoreStudioSelection = true;
            try {
                selectStudioDocument(previous.key());
            } finally {
                restoringPendingCoreStudioSelection = false;
            }
            pendingCoreStudioDocumentKey = pendingKey;
            pendingCoreStudioPreviousDocumentKey = retainedPreviousKey;
            pendingCoreStudioDocumentAt = pendingAt;
            return;
        }
        setStudioTabsManagerActiveTab(findStudioTab(previous.key(),
            studioTabsManager != null ? studioTabsManager.getTabs() : List.of()));
    }

    protected StudioDocument findStudioDocument(String key) {
        return studioDocuments.stream().filter(document -> document.key().equals(key)).findFirst().orElse(null);
    }

    final void removeStudioDocuments(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Set<String> removedKeys = keys.stream().filter(Objects::nonNull).filter(key -> !key.isBlank())
            .collect(Collectors.toUnmodifiableSet());
        if (removedKeys.isEmpty()) {
            return;
        }
        int fallbackIndex = studioDocuments.size();
        boolean activeRemoved = activeStudioDocument != null && removedKeys.contains(activeStudioDocument.key());
        for (int index = 0; index < studioDocuments.size(); index++) {
            if (removedKeys.contains(studioDocuments.get(index).key())) {
                fallbackIndex = Math.min(fallbackIndex, index);
            }
        }
        if (activeRemoved) {
            clearActiveStudioDocument();
        }
        List<StudioDocument> removed = studioDocuments.stream()
            .filter(document -> removedKeys.contains(document.key())).toList();
        for (StudioDocument document : removed) {
            if (Objects.equals(pendingCoreStudioDocumentKey, document.key())) {
                clearPendingCoreStudioDocument();
            }
            terminalCoreStudioRebinds.remove(document.key());
            terminalLegacyCoreDocuments.remove(document.key());
            diagnosedLegacyCoreConflicts.remove(document.key());
            pendingCoreOpenIntents.remove(document.key());
            studioResourcePanelResources.remove(document.key());
            studioResourceOpenIntents.remove(document.key());
            studioDocumentClosed(document);
            removePersistedOpenStudioDocument(document.type(), document.id());
            if (document.view() != null) {
                document.view().closed();
            }
        }
        studioDocuments.removeAll(removed);
        syncStudioDocumentTabs();
        if (activeRemoved && !studioDocuments.isEmpty()) {
            selectStudioDocument(studioDocuments.get(Math.min(fallbackIndex, studioDocuments.size() - 1)).key());
        } else if (!activeRemoved) {
            refreshActiveViewHeaderButtons();
            refreshStudioResourcePanel();
        }
    }

    protected boolean isStudioDocumentDirty(StudioDocument document) {
        if (document == null) {
            return false;
        }
        if (document.coreSession() != null) {
            FlowManager manager = FlowManager.getInstance();
            return manager != null && manager.isCoreGraphEditorSessionDirty(studioServerId(),
                ReSyncResourceType.byTypeId(document.type()), document.id(), document.coreSession());
        }
        if (document.view() != null) {
            return document.view().hasUnsavedChanges();
        }
        return document == activeStudioDocument && hasUnsavedChanges();
    }

    protected void discardStudioDocument(StudioDocument document) {
        if (document == null) {
            return;
        }
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType type = ReSyncResourceType.byTypeId(document.type());
        if (document.coreSession() != null) {
            if (manager != null && type != null) {
                manager.discardCoreGraphSession(studioServerId(), type, document.id());
            }
        } else if (manager != null && type != null) {
            manager.discardResourceDraft(studioServerId(), type, document.id());
        }
        if (document.coreSession() == null && document.view() != null) {
            document.view().discardUnsavedChanges();
        }
        if (document.coreSession() == null && document.view() == null && document == activeStudioDocument) {
            discardUnsavedChanges();
        }
    }

    public void markStudioDocumentSaved(String type, String id) {
        markStudioDocumentSaved(type, id, 0L);
    }

    public void markStudioDocumentSaving(String type, String id, long sequence) {
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        StudioDocument document = findStudioDocument(key);
        if (document == null) {
            return;
        }
        if (document.coreSession() == null && document.view() != null) {
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_save_owner", "resourceKey", key,
                "operation", "saving", "owner", "view", "sequence", sequence);
            document.view().markChangesSaving(sequence);
        }
        if (document.coreSession() == null && document.view() == null && document == activeStudioDocument) {
            markChangesSaving(sequence);
        }
    }

    public void markStudioDocumentSaveFailed(String type, String id) {
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        if (findStudioDocument(key) == null) {
            return;
        }
        syncStudioDocumentTabs();
        updateStudioTabStates();
    }

    public void markStudioDocumentSaved(String type, String id, long sequence) {
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        StudioDocument document = findStudioDocument(key);
        if (document == null) {
            return;
        }
        if (document.coreSession() == null && document.view() != null) {
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_save_owner", "resourceKey", key,
                "operation", "saved", "owner", "view", "sequence", sequence);
            document.view().markChangesSaved(sequence);
        }
        if (document.coreSession() == null && document.view() == null && document == activeStudioDocument) {
            markChangesSaved(sequence);
        }
        clearStudioDiscardPrompt(key);
        syncStudioDocumentTabs();
        updateStudioTabStates();
    }

    private void closeStudioTab(TabsManager.Tab tab) {
        if (studioTabsManager == null) {
            return;
        }
        int index = studioTabsManager.getTabs().indexOf(tab);
        if (index >= 0) {
            studioTabsManager.removeTab(index);
        }
    }

    private void closeOtherStudioTabs(TabsManager.Tab retained) {
        if (studioTabsManager == null) {
            return;
        }
        List<TabsManager.Tab> tabs = studioTabsManager.getTabs();
        for (int index = tabs.size() - 1; index >= 0; index--) {
            if (tabs.get(index) != retained) {
                studioTabsManager.removeTab(index);
            }
        }
    }

    private void closeAllStudioTabs() {
        if (studioTabsManager == null) {
            return;
        }
        for (int index = studioTabsManager.getTabs().size() - 1; index >= 0; index--) {
            studioTabsManager.removeTab(index);
        }
    }

    protected void ensureStudioWorkspacePanels(boolean collapseContentBrowser) {
        if (studioContentBrowser == null) {
            createStudioContentBrowser();
        }
        if (collapseContentBrowser && studioContentBrowser != null) {
            studioContentBrowser.collapse();
        }
        if (studioResourceStudioPanel == null) {
            studioResourceStudioPanel = rightStudioPanel("studioResourcePanel")
                .collapsible("Resource Inspector")
                .show();
            studioResourcePanel = studioResourceStudioPanel.sidePanel();
            studioResourceStudioPanel.padding(studioPanelState.padding());
            studioResourcePanel.hide();
        }
    }

    protected void refreshStudioContentBrowser() {
        if (studioContentBrowser != null) {
            studioContentBrowser.rebuild();
        }
    }

    protected void updateStudioLayout() {
        if (studioTabsManager != null) {
            studioTabsManager.setPosition(10, 5);
            studioTabsManager.setSize(Math.max(80, width - studioHeaderRightReserve() - 20), 18);
        }
        if (studioContentBrowser != null) {
            studioContentBrowser.clampHeight();
            studioContentBrowser.layoutInScreen();
        }
        if (studioResourcePanel != null) {
            int previousRowWidth = studioResourceStudioPanel != null ? studioResourceStudioPanel.rowWidth() : studioPanelState.rowWidth();
            if (studioResourceStudioPanel != null) {
                studioResourceStudioPanel.layout();
            }
            int currentRowWidth = studioResourceStudioPanel != null ? studioResourceStudioPanel.rowWidth() : studioPanelState.rowWidth();
            if (previousRowWidth != currentRowWidth) {
                handleStudioResourcePanelRowWidthChanged(previousRowWidth, currentRowWidth);
            }
        }
        for (StudioDocument document : studioDocuments) {
            if (document.view() != null) {
                document.view().resize(width, studioEditorHeight());
            }
        }
    }

    protected void handleStudioResourcePanelRowWidthChanged(int previousRowWidth, int currentRowWidth) {
    }

    protected int studioHeaderRightReserve() {
        int reserve = 0;
        List<AnimatedWidget> buttons = visibleStudioHeaderButtons();
        int visibleCount = 0;
        for (AnimatedWidget button : buttons) {
            if (button != null && button.visible) {
                reserve += button.getWidth();
                visibleCount++;
            }
        }
        return reserve + visibleCount * 5;
    }

    protected int studioEditorHeight() {
        return studioContentBrowser != null ? studioContentBrowser.editorHeight() : height;
    }

    protected boolean studioContentBrowserAffectsLayout() {
        return false;
    }

    @Override
    protected int studioPanelBottomReserve() {
        return super.studioPanelBottomReserve();
    }

    protected void startTopHeaderOpeningAnimation() {
        header().offset(0, -header().headerSize).animateOffsetTo(0, 0);
    }

    protected void startTopHeaderClosingAnimation() {
        header().animateOffsetTo(0, -header().headerSize - 5);
    }

    protected void updateTopHeaderAnimation() {
        header().updateOffsetAnimation();
    }

    protected boolean isTopHeaderAnimationFinished() {
        return header().isOffsetAnimationFinished();
    }

    protected int topHeaderContentTop(int spacing) {
        return Math.clamp(header().headerSize + header().getOffsetY(), 0, header().headerSize) + spacing;
    }

    protected int screenWidth() {
        return width;
    }

    protected int screenHeight() {
        return height;
    }

    public int studioContentBrowserWidth() {
        return studioContentBrowser != null ? studioContentBrowser.visibleLayoutWidth() : 0;
    }

    public int studioContentBrowserPanelWidth() {
        if (studioContentBrowser == null || studioContentBrowser.sidePanel() == null) {
            return 0;
        }
        return studioContentBrowser.sidePanel().layoutWidth(0);
    }

    public void setStudioContentBrowserTemporarilyHidden(boolean hidden) {
        if (studioContentBrowser != null) {
            studioContentBrowser.setTemporarilyHidden(hidden);
        }
    }

    public boolean isStudioContentBrowserTemporarilyHidden() {
        return studioContentBrowser != null && studioContentBrowser.isTemporarilyHidden();
    }

    protected void showStudioContextMenu(int mouseX, int mouseY, ContextMenuWidget.Builder builder) {
        showContextMenu(mouseX, mouseY, builder);
    }

    protected void refreshStudioLayoutPositions() {
        updatePositions();
    }

    protected void clearStudioFocus() {
        setFocusedWidget(null);
    }

    protected void openReSyncMarketplace() {
        ScreenManager.getInstance().setScreen(new ReSyncMarketplaceScreen(this, studioServerId()));
    }

    protected void openReSyncPermissions() {
        FlowManager manager = FlowManager.getInstance();
        String serverId = studioServerId();
        if (manager == null || serverId == null || serverId.isBlank()) {
            new Notification("Permissions", "ReSync Is Not Connected", Notification.Type.ERROR);
            return;
        }
        ReSyncFlowClient client = manager.ensureFlowClient(serverId);
        if (client == null) {
            showFlowAdmissionIssue(manager, "Permissions");
            return;
        }
        LuckPermsDashboardScreen.prepare(this, client.luckPerms())
            
            .whenComplete((screen, error) -> ScreenManager.getInstance().execute(() -> {
                if (error != null || screen == null) {
                    new Notification("Permissions", "Permissions Unavailable", Notification.Type.ERROR);
                    return;
                }
                if (ScreenManager.getInstance().getCurrentScreen() != this) {
                    return;
                }
                if (!manager.withCurrentFlowClientNow(serverId, client,
                    current -> ScreenManager.getInstance().setScreen(screen))) {
                    new Notification("Permissions", "Connection Changed", Notification.Type.ERROR);
                }
            }));
    }

    protected void showReSyncInstallationStatus(ReSyncInstallationStatus status) {
        if (status == null) {
            return;
        }
        PopupWidget.Builder builder = new PopupWidget.Builder(status.title()).setResizable(false).width(440);
        builder.addRow("Status", readOnlyStatus(status.summary()));
        builder.addRow("Data", readOnlyStatus(status.preservesLegacyData() ? "Preserved" : "Unavailable"));
        if (status.state() == ReSyncInstallationStatus.State.LEGACY_DATA_ARCHIVED) {
            builder.addRow("Next Step", readOnlyStatus("Recreate Needed Resources In This Clean Workspace"));
        }
        if (!status.archivePath().isBlank()) {
            builder.addRow("Archive", readOnlyStatus(status.archivePath()));
        }
        PopupWidget popup = builder.build();
        addDrawableChild(popup);
        popup.show();
    }

    private AnimatedButton readOnlyStatus(String text) {
        AnimatedButton button = new AnimatedButton.Builder()
            .label(text)
            .centered(false)
            .entranceAnimation(false)
            .build();
        button.active = false;
        return button;
    }

    public boolean hasReSyncUpdateAvailable() {
        return false;
    }

    public boolean isReSyncUpdateRunning() {
        return false;
    }

    public void updateReSyncFromContentBrowser() {
    }

    protected void openProjectResource(ReSyncProjectMetadata.ResourceEntry resource) {
        openStudioResource(resource);
    }

    private boolean openStudioResourceSnapshot(String type, String id, String title, boolean fullEditor, boolean replaceExisting) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || type == null || type.isBlank() || id == null || id.isBlank()) return false;
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (resourceType != null && resourceType.isGraph() && routeCoreGraphOpen(manager, resourceType, id, title)) {
            return true;
        }
        String requestedServerId = studioServerId();
        FlowManager.ResourceReadLease lease = manager.snapshotResource(requestedServerId, type, id);
        if (lease == null) {
            ReSyncFlowClient.traceLifecycle(requestedServerId, "studio_snapshot_rejected", "serverId",
                requestedServerId, "resourceKey", type + ":" + id, "requestId", "open", "mutationId", null,
                "generation", -1L, "authorityEpoch", 0L, "revision", -1L, "reason", "snapshot_missing");
            return false;
        }
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        long intent = ++studioResourceOpenVersion;
        long startedAt = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        studioResourceOpenIntents.clear();
        studioResourceOpenIntents.put(key, intent);
        ReSyncFlowClient.traceLifecycle(requestedServerId, "studio_snapshot_admitted", "serverId", requestedServerId,
            "resourceKey", type + ":" + id, "requestId", intent, "mutationId", null, "generation", -1L,
            "authorityEpoch", 0L, "revision", -1L);
        try {
            STUDIO_RESOURCE_OPENS.execute(() -> {
                try {
                    Object value = deserializeStudioResource(type, lease.materialize());
                    if (ReSyncResourceDragPayload.WORLDGEN.equals(type)
                        && (!(value instanceof WorldGenProject project) || !id.equals(project.getId()))) {
                        throw new IllegalStateException("World Generation project snapshot is invalid");
                    }
                    PreparedStudioResource prepared = new PreparedStudioResource(type, id, title, value,
                        fullEditor, replaceExisting);
                    ScreenManager.getInstance().execute(() -> {
                        boolean expectedKeyPresent = Objects.equals(studioResourceOpenIntents.get(key), intent);
                        boolean currentServer = Objects.equals(requestedServerId, studioServerId());
                        boolean leaseCurrent = lease.isCurrent();
                        ReSyncFlowClient.traceLifecycle(requestedServerId, "studio_open_callback", "serverId",
                            requestedServerId, "resourceKey", key, "requestId", intent, "mutationId", null,
                            "generation", -1L, "authorityEpoch", 0L, "revision", -1L,
                            "expectedKeyPresent", expectedKeyPresent, "currentServer", currentServer,
                            "leaseCurrent", leaseCurrent, "resourcePresent", prepared.value() != null,
                            "elapsedMs", startedAt == 0L ? -1L : ((
                                System.nanoTime() - startedAt) / 1_000_000L), "reason", !expectedKeyPresent ? "stale_intent"
                                : !currentServer ? "server_changed" : !leaseCurrent ? "lease_stale" : "accepted");
                        if (!expectedKeyPresent) return;
                        if (!currentServer) {
                            studioResourceOpenIntents.remove(key, intent);
                            return;
                        }
                        if (!leaseCurrent) {
                            if (!openStudioResourceSnapshot(type, id, title, fullEditor, replaceExisting)
                                && studioResourceOpenIntents.remove(key, intent)) {
                                if (studioResourcePanelResources.get(key) == prepared.value()) {
                                    studioResourcePanelResources.remove(key);
                                }
                                new Notification("Open Resource", "Resource Is Unavailable", Notification.Type.ERROR);
                            }
                            return;
                        }
                        try {
                            mountStudioResource(prepared);
                            studioResourceOpenIntents.remove(key, intent);
                            ReSyncFlowClient.traceLifecycle(requestedServerId, "studio_resource_opened", "serverId",
                                requestedServerId, "resourceKey", type + ":" + id, "requestId", intent,
                                "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision", -1L,
                                "expectedKeyPresent", true, "resourcePresent", prepared.value() != null,
                                "documentPresent", findStudioDocument(key) != null, "tabPresent",
                                studioTabPresent(key), "tabSelected", studioTabSelected(key),
                                "elapsedMs", startedAt == 0L ? -1L : ((
                                    System.nanoTime() - startedAt) / 1_000_000L));
                        } catch (RuntimeException | Error exception) {
                            if (studioResourcePanelResources.get(key) == prepared.value()) {
                                studioResourcePanelResources.remove(key);
                            }
                            if (studioResourceOpenIntents.remove(key, intent)) {
                                ReSyncFlowClient.traceLifecycle(requestedServerId, "studio_open_failed", "serverId",
                                    requestedServerId, "resourceKey", type + ":" + id, "requestId", intent,
                                    "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision", -1L,
                                    "elapsedMs", startedAt == 0L ? -1L : ((
                                        System.nanoTime() - startedAt) / 1_000_000L), "reason", "mount_failed");
                                new Notification("Open Resource", "Resource Could Not Be Opened", Notification.Type.ERROR);
                            }
                        }
                    });
                } catch (RuntimeException | Error exception) {
                    ScreenManager.getInstance().execute(() -> {
                        if (studioResourceOpenIntents.remove(key, intent)) {
                            ReSyncFlowClient.traceLifecycle(requestedServerId, "studio_open_failed", "serverId",
                                requestedServerId, "resourceKey", type + ":" + id, "requestId", intent,
                                "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision", -1L,
                                "elapsedMs", startedAt == 0L ? -1L : ((
                                    System.nanoTime() - startedAt) / 1_000_000L), "reason", "materialize_failed");
                            new Notification("Open Resource", "Resource Is Unavailable", Notification.Type.ERROR);
                        }
                    });
                }
            });
            return true;
        } catch (IllegalStateException exception) {
            BrowserWork.schedule(Duration.ofMillis(50L), () ->
                ScreenManager.getInstance().execute(() -> {
                    if (Objects.equals(studioResourceOpenIntents.get(key), intent)
                        && Objects.equals(requestedServerId, studioServerId())) {
                        openStudioResourceSnapshot(type, id, title, fullEditor, replaceExisting);
                    }
                }));
            return true;
        }
    }

    private Object deserializeStudioResource(String type, String payload) {
        return switch (type) {
            case ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION, ReSyncResourceDragPayload.COMMAND ->
                FlowSerializer.deserialize(payload);
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> FlowSerializer.deserializeCustomContent(payload);
            case ReSyncResourceDragPayload.GUI -> FlowSerializer.deserializeGui(payload);
            case ReSyncResourceDragPayload.SCOREBOARD -> FlowSerializer.deserializeScoreboard(payload);
            case ReSyncResourceDragPayload.TAB -> FlowSerializer.deserializeTab(payload);
            case ReSyncResourceDragPayload.WORLDGEN -> WorldGenSerializer.deserializeProject(payload);
            default -> gson.fromJson(payload, JsonObject.class);
        };
    }

    private void mountStudioResource(PreparedStudioResource prepared) {
        String type = prepared.type();
        String id = prepared.id();
        String title = prepared.title();
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (prepared.value() instanceof FlowGraph && resourceType != null && resourceType.isGraph()
            && routeCoreGraphOpen(manager, resourceType, id, title)) {
            return;
        }
        if (prepared.value() instanceof FlowGraph graph) {
            openStudioGraphDocumentOwned(type, id, title, graph);
            StudioDocument document = findStudioDocument(key);
            if (document != null && document.graph() != null
                && coreStudioOwnership(manager, resourceType, id) == CoreStudioOwnership.TERMINAL_LEGACY) {
                studioResourcePanelResources.put(key, prepared.value());
            }
            return;
        }
        studioResourcePanelResources.put(key, prepared.value());
        if (prepared.value() instanceof WorldGenProject project) {
            openStudioWorldGenDocument(id, title, project);
        } else if (prepared.value() instanceof CustomContentDefinition content && content.getGraph() != null) {
            FlowGraph graph = content.getGraph();
            openStudioViewDocumentOwned(type, id, title, graph,
                new ScreenBackedStudioView(this, new ContentDesignerScreen(studioServerId(), graph, this)), prepared.replaceExisting(), true);
        } else if (prepared.value() instanceof CustomContentDefinition) {
            requestStudioResource(FlowManager.getInstance(), ReSyncResourceType.CUSTOM_CONTENT, id);
        } else if (prepared.value() instanceof GuiDefinition gui) {
            openStudioViewDocumentOwned(type, id, title, null,
                screenBackedStudioView(new GuiDesignerScreen(gui, studioServerId(), this, prepared.fullEditor(), prepared.fullEditor()), prepared.fullEditor()),
                prepared.replaceExisting(), true);
        } else if (prepared.value() instanceof ScoreboardDefinition scoreboard) {
            openStudioViewDocumentOwned(type, id, title, null,
                screenBackedStudioView(new ScoreboardDesignerScreen(scoreboard, studioServerId(), this, prepared.fullEditor(), prepared.fullEditor()), prepared.fullEditor()),
                prepared.replaceExisting(), true);
        } else if (prepared.value() instanceof TabDefinition tab) {
            openStudioViewDocumentOwned(type, id, title, null,
                screenBackedStudioView(new TabDesignerScreen(tab, studioServerId(), this, prepared.fullEditor(), prepared.fullEditor()), prepared.fullEditor()),
                prepared.replaceExisting(), true);
        } else if (prepared.value() instanceof JsonObject json) {
            if (ReSyncResourceDragPayload.ADVANCEMENT_TREE.equals(type)) {
                openStudioViewDocumentOwned(type, id, title, null,
                    screenBackedStudioView(new AdvancementDesignerScreen(json, studioServerId(), this, prepared.fullEditor(), prepared.fullEditor()), prepared.fullEditor()),
                    prepared.replaceExisting(), true);
            } else if (ReSyncResourceDragPayload.DIALOG.equals(type)) {
                openStudioViewDocumentOwned(type, id, title, null,
                    screenBackedStudioView(new DialogDesignerScreen(json, studioServerId(), this, prepared.fullEditor(), prepared.fullEditor()), prepared.fullEditor()),
                    prepared.replaceExisting(), true);
            } else {
                openFocusedResourceDocumentOwned(type, id, title, json);
            }
        }
    }

    protected void openStudioResource(ReSyncProjectMetadata.ResourceEntry resource) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || resource == null) {
            return;
        }
        if (ReSyncResourceDragPayload.FLOW.equals(resource.getType()) || ReSyncResourceDragPayload.FUNCTION.equals(resource.getType()) || ReSyncResourceDragPayload.COMMAND.equals(resource.getType())) {
            ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(resource.getType());
            if (resourceType != null && routeCoreGraphOpen(manager, resourceType, resource.getId(), resource.getDisplayName())) {
                return;
            }
            if (resourceType != null && openStudioResourceSnapshot(resource.getType(), resource.getId(), resource.getDisplayName(), false, false)) {
                return;
            }
            if (resourceType != null) {
                if (ReSyncResourceDragPayload.COMMAND.equals(resource.getType()) && manager.isCommandFlowIdentityBlocked(studioServerId(), resource.getId())) {
                    new Notification("Command", "ID Conflicts With Content", Notification.Type.ERROR);
                    return;
                }
                requestStudioResource(manager, resourceType, resource.getId());
            }
            return;
        }
        if (ReSyncResourceDragPayload.CUSTOM_CONTENT.equals(resource.getType())) {
            if (!openStudioResourceSnapshot(resource.getType(), resource.getId(), resource.getDisplayName(), false, false))
                requestStudioResource(manager, ReSyncResourceType.CUSTOM_CONTENT, resource.getId());
            return;
        }
        if (ReSyncResourceDragPayload.WORLDGEN.equals(resource.getType())) {
            if (!openStudioResourceSnapshot(resource.getType(), resource.getId(), resource.getDisplayName(), false, false)) {
                WorldGenManager.getInstance().requestProject(studioServerId(), resource.getId());
            }
            return;
        }
        if (ReSyncResourceDragPayload.GUI.equals(resource.getType())
            || ReSyncResourceDragPayload.SCOREBOARD.equals(resource.getType())
            || ReSyncResourceDragPayload.TAB.equals(resource.getType())) {
            openStudioDesigner(resource.getType(), resource.getId());
            return;
        }
        ReSyncResourceType jsonType = ReSyncResourceType.byTypeId(resource.getType());
        if (jsonType != null) {
            if (!openStudioResourceSnapshot(resource.getType(), resource.getId(), resource.getDisplayName(), false, false)) {
                requestStudioResource(manager, jsonType, resource.getId());
            }
            return;
        }
        if (ReSyncResourceDragPayload.WORLD.equals(resource.getType())) {
            openStudioWorldDocument(resource.getId(), resource.getDisplayName());
            return;
        }
        openOpaqueManagedResourceDocument(resource.getType(), resource.getId(), resource.getDisplayName());
    }

    protected void openStudioGraphDocument(String type, String id, String title, FlowGraph targetGraph) {
        openStudioGraphDocument(type, id, title, targetGraph, false);
    }

    protected final void openStudioGraphDocumentOwned(String type, String id, String title, FlowGraph targetGraph) {
        openStudioGraphDocument(type, id, title, targetGraph, true);
    }

    private void openStudioGraphDocument(String type, String id, String title, FlowGraph targetGraph, boolean owned) {
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (resourceType != null && resourceType.isGraph() && routeCoreGraphOpen(manager, resourceType, id, title)) {
            return;
        }
        if (targetGraph == null) {
            return;
        }
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        addStudioDocument(type, id, title == null || title.isBlank() ? id : title, targetGraph, null, false, true, owned);
        syncStudioDocumentTabs();
        selectStudioDocument(key);
    }

    protected void openStudioCoreDocument(String type, String id, String title, CoreGraphEditorSession session) {
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        CoreOpenIntent intent = pendingCoreOpenIntents.get(key);
        if (intent == null && resourceType != null && resourceType.isGraph()) {
            intent = acceptCoreOpenIntent(resourceType, id, title);
        }
        if (bindStudioCoreDocument(type, id, title, session, true) && intent != null) {
            pendingCoreOpenIntents.replace(key, intent, intent.preparing(true));
        }
    }

    protected boolean bindStudioCoreDocument(String type, String id, String title, CoreGraphEditorSession session,
                                             boolean activate) {
        if (type == null || type.isBlank() || id == null || id.isBlank() || session == null
            || session.resource() == null || !type.equals(session.resource().resourceType().value())
            || !id.equals(session.resource().id())) {
            return false;
        }
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (manager == null || resourceType == null || !resourceType.isGraph()) {
            return false;
        }
        CoreGraphEditorSession currentSession = manager.peekCoreGraphEditorSession(
            studioServerId(), resourceType, id).orElse(null);
        if (currentSession != session) {
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_session_rejected", "serverId",
                studioServerId(), "resourceKey", type + ":" + id, "requestId", "open", "mutationId", null,
                "generation", -1L, "authorityEpoch", 0L, "revision", session.revision(), "reason",
                "session_not_current");
            if (activate) {
                acceptCoreOpenIntent(resourceType, id, title);
                requestCoreOpenResource(manager, resourceType, id);
            }
            retainVisibleStudioDocument();
            return false;
        }
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        terminalCoreStudioRebinds.remove(key);
        installCoreGraphSaveHandler();
        String documentTitle = title == null || title.isBlank() ? id : title;
        for (int i = 0; i < studioDocuments.size(); i++) {
            StudioDocument document = studioDocuments.get(i);
            if (!document.key().equals(key)) {
                continue;
            }
            if (document.coreSession() != session || document.graph() != null || document.view() != null) {
                if (document.coreSession() == null && !allowLegacyCoreReplacement(document)) {
                    String reason = isStudioDocumentDirty(document)
                        ? "legacy_draft_dirty" : "legacy_ownership_unverified";
                    CoreOpenIntent intent = pendingCoreOpenIntents.get(key);
                    if (!activate || intent == null || !failCoreOpenIntent(intent, reason)) {
                        terminalCoreStudioRebinds.put(key, reason);
                    }
                    return false;
                }
                if (document.view() != null) {
                    document.view().closed();
                }
                StudioDocument updated = new StudioDocument(type, id, documentTitle, null, session, null,
                    document.viewport());
                if (document.coreSession() == null) {
                    studioDocumentClosed(document);
                }
                replaceStudioCoreDocument(i, document, updated);
                terminalLegacyCoreDocuments.remove(key);
                diagnosedLegacyCoreConflicts.remove(key);
            } else if (!documentTitle.equals(document.title())) {
                StudioDocument updated = new StudioDocument(type, id, documentTitle, null, session, null,
                    document.viewport());
                replaceStudioCoreDocument(i, document, updated);
            }
            persistOpenStudioDocument(type, id, documentTitle, activate);
            syncStudioDocumentTabs();
            if (activate) {
                selectStudioDocument(key);
            }
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_session_bound", "serverId",
                studioServerId(), "resourceKey", type + ":" + id, "requestId", "open", "mutationId", null,
                "generation", -1L, "authorityEpoch", 0L, "revision", session.revision(), "documentState",
                "reused", "activate", activate, "phase", pendingCoreOpenIntents.containsKey(key)
                    ? pendingCoreOpenIntents.get(key).phase() : "none", "tabPresent", studioTabPresent(key),
                "tabSelected", studioTabSelected(key));
            return true;
        }
        terminalLegacyCoreDocuments.remove(key);
        diagnosedLegacyCoreConflicts.remove(key);
        studioDocuments.add(new StudioDocument(type, id, documentTitle, null, session, null,
            new StudioViewportState()));
        persistOpenStudioDocument(type, id, documentTitle, activate);
        syncStudioDocumentTabs();
        if (activate) {
            selectStudioDocument(key);
        }
        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_core_session_bound", "serverId", studioServerId(),
            "resourceKey", type + ":" + id, "requestId", "open", "mutationId", null, "generation", -1L,
            "authorityEpoch", 0L, "revision", session.revision(), "documentState", "created", "activate", activate,
            "phase", pendingCoreOpenIntents.containsKey(key) ? pendingCoreOpenIntents.get(key).phase() : "none",
            "tabPresent", studioTabPresent(key), "tabSelected", studioTabSelected(key));
        return true;
    }

    private void replaceStudioCoreDocument(int index, StudioDocument previous, StudioDocument replacement) {
        boolean active = activeStudioDocument == previous || activeStudioDocument != null
            && Objects.equals(activeStudioDocument.key(), previous.key());
        studioDocuments.set(index, replacement);
        if (active) {
            activeStudioDocument = replacement;
            afterStudioDocumentSelected(replacement);
        }
    }

    private void installCoreGraphSaveHandler() {
        if (!(this instanceof GraphEditorScreen graphEditor)) {
            return;
        }
        graphEditor.setCoreGraphSaveHandler((current, ticket) -> {
            FlowManager manager = FlowManager.getInstance();
            if (manager == null || current == null || activeStudioDocument == null
                || activeStudioDocument.view() != null || activeStudioDocument.coreSession() != current
                || current.resource() == null || !Objects.equals(studioServerId(), current.resource().serverId().canonicalText())) {
                return false;
            }
            ReSyncResourceType type = ReSyncResourceType.byTypeId(current.resource().resourceType().value());
            if (type == null || !type.isGraph()) {
                return false;
            }
            boolean admitted = manager.saveCoreGraph(studioServerId(), type, current, ticket);
            ReSyncFlowClient.traceLifecycle(studioServerId(), admitted ? "studio_save_admitted"
                : "studio_save_rejected", "serverId", studioServerId(), "resourceKey",
                type.typeId() + ":" + current.resource().id(), "requestId",
                ticket != null ? ticket.requestId() : "save", "mutationId",
                ticket != null ? ticket.mutationId() : null, "generation", -1L, "authorityEpoch", 0L,
                "revision", current.revision());
            return admitted;
        });
    }

    protected void openStudioDesigner(String type, String id) {
        openStudioDesigner(type, id, false);
    }

    protected void openStudioDesigner(String type, String id, boolean fullEditor) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || id == null) {
            return;
        }
        if (fullEditor) {
            collapseStudioContentBrowser();
        }
        if (ReSyncResourceDragPayload.GUI.equals(type)) {
            if (!openStudioResourceSnapshot(type, id, manager.getGuiName(studioServerId(), id), fullEditor, fullEditor)) {
                manager.openGuiDesigner(studioServerId(), null, id, this, fullEditor);
            }
            return;
        }
        if (ReSyncResourceDragPayload.SCOREBOARD.equals(type)) {
            if (!openStudioResourceSnapshot(type, id, manager.getScoreboardName(studioServerId(), id), fullEditor, fullEditor)) {
                manager.openScoreboardDesigner(studioServerId(), null, id, this, fullEditor);
            }
            return;
        }
        if (ReSyncResourceDragPayload.TAB.equals(type)) {
            if (!openStudioResourceSnapshot(type, id, manager.getTabName(studioServerId(), id), fullEditor, fullEditor)) {
                manager.openTabDesigner(studioServerId(), null, id, this, fullEditor);
            }
            return;
        }
        if (ReSyncResourceDragPayload.ADVANCEMENT_TREE.equals(type)) {
            if (!openStudioResourceSnapshot(type, id, id, fullEditor, fullEditor))
                requestStudioResource(manager, ReSyncResourceType.ADVANCEMENT_TREE, id);
            return;
        }
        if (ReSyncResourceDragPayload.DIALOG.equals(type)) {
            if (!openStudioResourceSnapshot(type, id, id, fullEditor, fullEditor)) {
                manager.openDialogDesigner(studioServerId(), id, this, fullEditor);
            }
            return;
        }
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (resourceType == null) {
            openOpaqueManagedResourceDocument(type, id, id);
            return;
        }
        if (resourceType.isGraph() || resourceType == ReSyncResourceType.PROJECT_METADATA
            || resourceType == ReSyncResourceType.CUSTOM_CONTENT) {
            return;
        }
        if (!openStudioResourceSnapshot(type, id, id, fullEditor, fullEditor)) {
            requestStudioResource(manager, resourceType, id);
        }
    }

    protected ScreenBackedStudioView screenBackedStudioView(Screen screen, boolean fullEditor) {
        if (fullEditor && screen instanceof StudioCloseHandledScreen closeHandledScreen) {
            closeHandledScreen.setStudioCloseHandler(this::requestCloseFullEditorStudioScreen);
        }
        return new ScreenBackedStudioView(this, screen, fullEditor);
    }

    protected void requestCloseFullEditorStudioScreen() {
        if (fullEditorHeaderCloseRequested) {
            return;
        }
        fullEditorHeaderCloseRequested = true;
        ReSyncStudioView view = activeStudioView();
        if (view instanceof ScreenBackedStudioView screenView && screenView.fullEditor()) {
            screenView.requestCloseAnimation();
        }
        startTopHeaderClosingAnimation();
        if (studioContentBrowser != null) {
            studioContentBrowser.slideOut();
        }
    }

    protected void closeFullEditorStudioScreen() {
        FlowManager manager = FlowManager.getInstance();
        if (manager != null) {
            manager.requestCloseLiveStudioSuperScreen(studioServerId());
        } else {
            ScreenManager.getInstance().execute(() -> ScreenManager.getInstance().setScreen(null));
        }
    }

    protected void collapseStudioContentBrowser() {
        if (studioContentBrowser != null) {
            studioContentBrowser.collapse();
        }
    }

    protected void openStudioWorldGenDocument(String id, String title, WorldGenProject project) {
        openStudioViewDocument(ReSyncResourceDragPayload.WORLDGEN, id, title, new ScreenBackedStudioView(this,
            new WorldGenEditorScreen(studioServerId(), null, this, project, id)));
        if (project == null) {
            WorldGenManager.getInstance().requestProject(studioServerId(), id);
        }
    }

    protected void openStudioWorldDocument(String id, String title) {
        openStudioViewDocument(ReSyncResourceDragPayload.WORLD, id, title == null || title.isBlank() ? id : title, screenBackedStudioView(new WorldDesignerScreen(id, studioServerId(), this), false));
    }

    private List<ReSyncResourceType> studioTypedResourceTypes() {
        return Arrays.stream(ReSyncResourceType.values())
            .filter(type -> type != ReSyncResourceType.PROJECT_METADATA)
            .toList();
    }

    private Map<?, ?> studioTypedResources(FlowManager manager, ReSyncResourceType type) {
        return switch (type) {
            case FLOW, FUNCTION, COMMAND -> manager.getGraphsForServer(studioServerId(), type);
            case GUI -> manager.getGuisForServer(studioServerId());
            case SCOREBOARD -> manager.getScoreboardsForServer(studioServerId());
            case TAB -> manager.getTabsForServer(studioServerId());
            case CUSTOM_CONTENT -> manager.getCustomContentForServer(studioServerId());
            case PROJECT_METADATA -> Map.of();
            default -> manager.getJsonResourcesForServer(studioServerId(), type);
        };
    }

    private String studioTypedResourceId(ReSyncResourceType type, Object key, Object value) {
        String id = key instanceof String text ? text : "";
        if (id.isBlank() && value != null) {
            try {
                id = type.extractId(value);
            } catch (RuntimeException ignored) {
                id = "";
            }
        }
        return id == null ? "" : id.trim();
    }

    private String studioTypedResourceName(ReSyncResourceType type, Object value, String id) {
        if (value != null) {
            try {
                String name = type.extractName(value);
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return id;
    }

    private ReSyncProjectMetadata studioPresentationMetadata(FlowManager manager) {
        FlowManager.ResourceReadLease lease = manager.snapshotProjectMetadata(studioServerId());
        if (lease == null) {
            return null;
        }
        try {
            if (!lease.isCurrent()) {
                return null;
            }
            ReSyncProjectMetadata metadata = (ReSyncProjectMetadata) ReSyncResourceType.PROJECT_METADATA.deserialize(lease.materialize());
            return lease.isCurrent() ? metadata : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void hydrateStudioTypedResources(FlowManager manager, ReSyncResourceType type,
                                             Map<String, ReSyncProjectMetadata.ResourceEntry> resources) {
        boolean missingPresentation = false;
        for (ReSyncProjectMetadata.ResourceEntry resource : resources.values()) {
            if (resource != null && type.typeId().equals(resource.getType())
                && (resource.getDisplayName() == null || resource.getDisplayName().isBlank()
                || resource.getPath() == null || resource.getPath().isBlank())) {
                missingPresentation = true;
                break;
            }
        }
        if (!missingPresentation) {
            return;
        }
        Map<?, ?> typed = studioTypedResources(manager, type);
        for (Map.Entry<?, ?> item : typed.entrySet()) {
            String id = studioTypedResourceId(type, item.getKey(), item.getValue());
            if (id.isBlank() || item.getValue() == null) {
                continue;
            }
            String key = ReSyncProjectMetadata.resourceKey(type.typeId(), id);
            ReSyncProjectMetadata.ResourceEntry existing = resources.get(key);
            if (existing == null) {
                continue;
            }
            if (existing.getDisplayName() == null || existing.getDisplayName().isBlank()) {
                existing.setDisplayName(studioTypedResourceName(type, item.getValue(), id));
            }
            if (existing.getPath() == null || existing.getPath().isBlank()) {
                existing.setPath(type.defaultFolder());
            }
        }
    }

    private List<ReSyncProjectMetadata.ResourceEntry> studioResourceProjection(FlowManager manager) {
        Map<String, ReSyncProjectMetadata.ResourceEntry> resources = new LinkedHashMap<>();
        for (ReSyncProjectMetadata.ResourceEntry resource : manager.getProjectResources(studioServerId())) {
            if (resource != null) {
                resources.put(resource.key(), resource);
            }
        }
        for (ReSyncResourceType type : studioTypedResourceTypes()) {
            hydrateStudioTypedResources(manager, type, resources);
        }
        return List.copyOf(resources.values());
    }

    private List<ReSyncProjectMetadata.FolderEntry> studioFolderProjection(FlowManager manager) {
        Map<String, ReSyncProjectMetadata.FolderEntry> folders = new LinkedHashMap<>();
        ReSyncProjectMetadata metadata = studioPresentationMetadata(manager);
        if (metadata != null) {
            for (ReSyncProjectMetadata.FolderEntry folder : metadata.getFolders()) {
                if (folder != null) {
                    folders.put(ReSyncProjectMetadata.normalizePath(folder.getPath()), folder);
                }
            }
        }
        for (ReSyncProjectMetadata.ResourceEntry resource : studioResourceProjection(manager)) {
            List<String> hierarchy = studioResourceFolderHierarchy(resource.getPath(), resource.getId());
            String parent = "";
            for (String current : hierarchy) {
                if (!folders.containsKey(current)) {
                    ReSyncProjectMetadata.FolderEntry folder = new ReSyncProjectMetadata.FolderEntry();
                    folder.setPath(current);
                    folder.setParentPath(parent);
                    int separator = current.lastIndexOf('/');
                    folder.setName(separator >= 0 ? current.substring(separator + 1) : current);
                    folder.setSortOrder(Integer.MAX_VALUE);
                    folders.put(current, folder);
                }
                parent = current;
            }
        }
        return List.copyOf(folders.values());
    }

    static List<String> studioResourceFolderHierarchy(String path, String id) {
        String folder = ReSyncContentBrowserWidget.resourceFolderPath(path, id);
        if (folder.isBlank()) {
            return List.of();
        }
        List<String> hierarchy = new ArrayList<>();
        String current = "";
        for (String segment : folder.split("/")) {
            if (!segment.isBlank()) {
                current = current.isBlank() ? segment : current + "/" + segment;
                hierarchy.add(current);
            }
        }
        return List.copyOf(hierarchy);
    }

    protected List<ReSyncProjectMetadata.FolderEntry> studioFolders(String parentPath) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return List.of();
        }
        return studioFolderProjection(manager).stream()
            .filter(this::isVisibleStudioFolder)
            .filter(folder -> parentPath.equals(folder.getParentPath()))
            .sorted(Comparator.comparingInt(ReSyncProjectMetadata.FolderEntry::getSortOrder).thenComparing(ReSyncProjectMetadata.FolderEntry::getName))
            .toList();
    }

    protected List<ReSyncProjectMetadata.FolderEntry> studioAllFolders() {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return List.of();
        }
        return studioFolderProjection(manager).stream()
            .filter(this::isVisibleStudioFolder)
            .toList();
    }

    protected List<ReSyncProjectMetadata.ResourceEntry> studioResources(String folderPath) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return List.of();
        }
        return studioResourceProjection(manager).stream()
            .filter(this::isVisibleStudioResource)
            .filter(resource -> studioResourceInFolder(resource, folderPath))
            .sorted(Comparator.comparing(ReSyncProjectMetadata.ResourceEntry::getDisplayName))
            .toList();
    }

    static boolean studioResourceInFolder(ReSyncProjectMetadata.ResourceEntry resource, String folderPath) {
        return resource != null && ReSyncProjectMetadata.normalizePath(folderPath)
            .equals(ReSyncContentBrowserWidget.resourceFolderPath(resource.getPath(), resource.getId()));
    }

    protected List<ReSyncProjectMetadata.ResourceEntry> studioAllResources() {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return List.of();
        }
        return studioResourceProjection(manager).stream()
            .filter(this::isVisibleStudioResource)
            .toList();
    }

    protected boolean isVisibleStudioResource(ReSyncProjectMetadata.ResourceEntry resource) {
        return resource != null;
    }

    protected boolean isVisibleStudioFolder(ReSyncProjectMetadata.FolderEntry folder) {
        return folder != null;
    }

    protected void openFocusedResourceDocument(String type, String id, String title, JsonObject resource) {
        if (AutomationDefinitionDraft.supports(type)) {
            openDefinition(type, id);
            return;
        }
        openStudioViewDocument(type, id, title == null || title.isBlank() ? id : title, focusedResourceView(type, id, detachedJson(resource)));
    }

    protected void openFocusedResourceDocumentOwned(String type, String id, String title, JsonObject resource) {
        if (AutomationDefinitionDraft.supports(type)) {
            openDefinition(type, id);
            return;
        }
        openStudioViewDocumentOwned(type, id, title == null || title.isBlank() ? id : title, null,
            focusedResourceView(type, id, resource), false, true);
    }

    public void openDefinitions(String type) {
        if (!AutomationDefinitionDraft.supports(type)) {
            return;
        }
        openAutomationDocument(type, null, "", null, false);
    }

    public void openDefinition(String type, String id) {
        if (AutomationDefinitionDraft.supports(type) && id != null && !id.isBlank()) {
            openAutomationDocument(type, id, "", null, false);
        }
    }

    public void openDefinitionCreate(String type, String folder, Consumer<ReSyncResourceCreator.Result> completion) {
        if (AutomationDefinitionDraft.supports(type)) {
            openAutomationDocument(type, null, folder, completion, true);
        }
    }

    private void openAutomationDocument(String type, String id, String folder,
                                        Consumer<ReSyncResourceCreator.Result> completion, boolean create) {
        String key = ReSyncProjectMetadata.resourceKey(AUTOMATION_DOCUMENT_TYPE, AUTOMATION_DOCUMENT_ID);
        StudioDocument document = findStudioDocument(key);
        AutomationDefinitionDesignerScreen designer = automationDesigner(document);
        if (designer == null) {
            ReSyncStudioView view = AutomationDefinitionDesignerScreen.typeScreen(this, studioServerId(), type);
            openStudioViewDocument(AUTOMATION_DOCUMENT_TYPE, AUTOMATION_DOCUMENT_ID, "Sub Resources", null, view,
                false, false);
            document = findStudioDocument(key);
            designer = automationDesigner(document);
        } else {
            selectStudioDocument(key);
        }
        if (designer == null) {
            return;
        }
        if (create) {
            designer.showCreate(type, folder, completion);
        } else if (id != null && !id.isBlank()) {
            designer.showDefinition(type, id);
        } else {
            designer.showType(type);
        }
    }

    private AutomationDefinitionDesignerScreen automationDesigner(StudioDocument document) {
        if (document == null || !(document.view() instanceof ScreenBackedStudioView view)
            || !(view.screen() instanceof AutomationDefinitionDesignerScreen designer)) {
            return null;
        }
        return designer;
    }

    private boolean isAutomationDocument(StudioDocument document) {
        return document != null && AUTOMATION_DOCUMENT_TYPE.equals(document.type())
            && AUTOMATION_DOCUMENT_ID.equals(document.id());
    }

    protected void openOpaqueManagedResourceDocument(String type, String id, String title) {
        openStudioViewDocumentOwned(type, id, title == null || title.isBlank() ? id : title, null,
            new ManagedResourceDesignerScreen(this, type, id, null, studioServerId(), this, false), false, true);
    }

    protected ReSyncStudioView focusedResourceView(String type, String id, JsonObject resource) {
        return ResourceDesigners.create(this, type, id, resource, studioServerId(), this);
    }

    protected void openStudioViewDocument(String type, String id, String title, ReSyncStudioView view) {
        openStudioViewDocument(type, id, title, null, view);
    }

    protected void openStudioViewDocument(String type, String id, String title, FlowGraph targetGraph, ReSyncStudioView view) {
        openStudioViewDocument(type, id, title, targetGraph, view, false);
    }

    protected void openStudioViewDocument(String type, String id, String title, ReSyncStudioView view, boolean replaceExisting) {
        openStudioViewDocument(type, id, title, null, view, replaceExisting);
    }

    protected void openStudioViewDocument(String type, String id, String title, FlowGraph targetGraph, ReSyncStudioView view, boolean replaceExisting) {
        openStudioViewDocument(type, id, title, targetGraph, view, replaceExisting, true);
    }

    protected void openStudioViewDocument(String type, String id, String title, FlowGraph targetGraph, ReSyncStudioView view, boolean replaceExisting, boolean persistDocument) {
        addStudioDocument(type, id, title == null || title.isBlank() ? id : title, targetGraph, view, replaceExisting, persistDocument);
        syncStudioDocumentTabs();
        selectStudioDocument(ReSyncProjectMetadata.resourceKey(type, id));
    }

    private void openStudioViewDocumentOwned(String type, String id, String title, FlowGraph targetGraph, ReSyncStudioView view,
                                             boolean replaceExisting, boolean persistDocument) {
        addStudioDocument(type, id, title == null || title.isBlank() ? id : title, targetGraph, view, replaceExisting, persistDocument, true);
        syncStudioDocumentTabs();
        selectStudioDocument(ReSyncProjectMetadata.resourceKey(type, id));
    }

    protected void addStudioDocument(String type, String id, String title, FlowGraph targetGraph, ReSyncStudioView view) {
        addStudioDocument(type, id, title, targetGraph, view, false);
    }

    protected void addStudioDocument(String type, String id, String title, FlowGraph targetGraph, ReSyncStudioView view, boolean replaceExisting) {
        addStudioDocument(type, id, title, targetGraph, view, replaceExisting, true);
    }

    protected void addStudioDocument(String type, String id, String title, FlowGraph targetGraph, ReSyncStudioView view, boolean replaceExisting, boolean persistDocument) {
        addStudioDocument(type, id, title, targetGraph, view, replaceExisting, persistDocument, false);
    }

    private void addStudioDocument(String type, String id, String title, FlowGraph targetGraph, ReSyncStudioView view,
                                   boolean replaceExisting, boolean persistDocument, boolean ownedGraph) {
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (targetGraph != null && resourceType != null && resourceType.isGraph()) {
            FlowManager manager = FlowManager.getInstance();
            if (coreStudioOwnership(manager, resourceType, id) != CoreStudioOwnership.TERMINAL_LEGACY) {
                routeCoreGraphOpen(manager, resourceType, id, title);
                return;
            }
            terminalLegacyCoreDocuments.add(key);
            diagnosedLegacyCoreConflicts.remove(key);
        }
        String documentTitle = persistDocument ? studioDocumentTitle(id, title) : title == null || title.isBlank() ? id : title;
        for (int i = 0; i < studioDocuments.size(); i++) {
            StudioDocument document = studioDocuments.get(i);
            if (document.key().equals(key)) {
                if (document.coreSession() != null) {
                    if (!documentTitle.equals(document.title())) {
                        StudioDocument updatedDocument = new StudioDocument(document.type(), document.id(), documentTitle,
                            document.graph(), document.coreSession(), document.view(), document.viewport());
                        studioDocuments.set(i, updatedDocument);
                        if (activeStudioDocument != null && activeStudioDocument.key().equals(key)) {
                            activeStudioDocument = updatedDocument;
                        }
                    }
                    if (persistDocument) {
                        persistOpenStudioDocument(type, id, documentTitle);
                    }
                    ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_document_retained", "serverId", studioServerId(),
                        "resourceKey", key, "requestId", "studio", "mutationId", null, "authorityEpoch", 0L,
                        "revision", document.coreSession().revision(), "replaceRequested", replaceExisting);
                    return;
                }
                if (replaceExisting) {
                    if (document.view() != null) {
                        document.view().closed();
                    }
                    FlowGraph detachedGraph = ownedGraph ? targetGraph : detachedGraph(targetGraph);
                    StudioDocument updatedDocument = new StudioDocument(type, id, documentTitle, detachedGraph, null,
                        view != null ? view : createStudioDocumentView(type, id, detachedGraph), document.viewport());
                    studioDocuments.set(i, updatedDocument);
                    ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_document_replaced", "serverId", studioServerId(),
                        "resourceKey", key, "requestId", "studio", "mutationId", null, "authorityEpoch", 0L,
                        "revision", detachedGraph != null ? detachedGraph.getResourceRevision() : -1L,
                        "nodeCount", detachedGraph != null && detachedGraph.getNodes() != null ? detachedGraph.getNodes().size() : 0);
                } else if (!documentTitle.equals(document.title())) {
                    StudioDocument updatedDocument = new StudioDocument(document.type(), document.id(), documentTitle,
                        document.graph(), null, document.view(), document.viewport());
                    studioDocuments.set(i, updatedDocument);
                    if (activeStudioDocument != null && activeStudioDocument.key().equals(key)) {
                        activeStudioDocument = updatedDocument;
                    }
                }
                if (persistDocument) {
                    persistOpenStudioDocument(type, id, documentTitle);
                }
                return;
            }
        }
        FlowGraph detachedGraph = ownedGraph ? targetGraph : detachedGraph(targetGraph);
        StudioDocument document = new StudioDocument(type, id, documentTitle, detachedGraph, null,
            view != null ? view : createStudioDocumentView(type, id, detachedGraph), new StudioViewportState());
        studioDocuments.add(document);
        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_document_added", "serverId", studioServerId(),
            "resourceKey", key, "requestId", "studio", "mutationId", null, "authorityEpoch", 0L, "revision",
            detachedGraph != null ? detachedGraph.getResourceRevision() : -1L,
            "nodeCount", detachedGraph != null && detachedGraph.getNodes() != null ? detachedGraph.getNodes().size() : 0);
        if (persistDocument) {
            persistOpenStudioDocument(type, id, documentTitle);
        }
    }

    protected String studioDocumentTitle(String id, String title) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return title == null || title.isBlank() ? "" : title;
    }

    protected FlowGraph detachedGraph(FlowGraph graph) {
        return graph != null ? FlowSerializer.deserialize(FlowSerializer.serialize(graph)) : null;
    }

    protected GuiDefinition detachedGui(GuiDefinition gui) {
        return gui != null ? FlowSerializer.deserializeGui(FlowSerializer.serializeGui(gui)) : null;
    }

    protected ScoreboardDefinition detachedScoreboard(ScoreboardDefinition scoreboard) {
        return scoreboard != null ? FlowSerializer.deserializeScoreboard(FlowSerializer.serializeScoreboard(scoreboard)) : null;
    }

    protected TabDefinition detachedTab(TabDefinition tab) {
        return tab != null ? FlowSerializer.deserializeTab(FlowSerializer.serializeTab(tab)) : null;
    }

    protected JsonObject detachedJson(JsonObject json) {
        return json != null ? json.deepCopy() : new JsonObject();
    }

    protected ReSyncStudioView createStudioDocumentView(String type, String id, FlowGraph targetGraph) {
        return null;
    }

    protected void persistOpenStudioDocument(String type, String id, String title) {
        persistOpenStudioDocument(type, id, title, true);
    }

    protected void persistOpenStudioDocument(String type, String id, String title, boolean activate) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_open_rejected", "serverId", studioServerId(),
                "resourceKey", type + ":" + id, "requestId", "open", "mutationId", null, "generation", -1L,
                "authorityEpoch", 0L, "revision", -1L, "reason", "manager_missing");
            return;
        }
        manager.persistOpenProjectDocument(studioServerId(), type, id, title, activate);
    }

    protected void removePersistedOpenStudioDocument(String type, String id) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return;
        }
        manager.removeOpenProjectDocument(studioServerId(), type, id);
    }

    protected void renameStudioDocument(String type, String oldId, String newId) {
        String oldKey = ReSyncProjectMetadata.resourceKey(type, oldId);
        String newKey = ReSyncProjectMetadata.resourceKey(type, newId);
        clearStudioDiscardPrompt(oldKey);
        boolean activeRenamed = activeStudioDocument != null && activeStudioDocument.key().equals(oldKey);
        int documentIndex = -1;
        if (activeRenamed) {
            documentIndex = studioDocuments.indexOf(activeStudioDocument);
        }
        if (documentIndex < 0) {
            for (int i = 0; i < studioDocuments.size(); i++) {
                if (studioDocuments.get(i).key().equals(oldKey)) {
                    documentIndex = i;
                    break;
                }
            }
        }
        if (documentIndex >= 0) {
            StudioDocument document = studioDocuments.get(documentIndex);
            if (document.coreSession() != null) {
                new Notification("Core Editor", "Rename Unavailable", Notification.Type.WARN);
                return;
            }
            if (document.view() instanceof StudioResourceRenameAware view
                && view.deferResourceRename(type, oldId, newId, () -> renameStudioDocument(type, oldId, newId))) {
                return;
            }
            if (document.view() == null && this instanceof StudioResourceRenameAware view
                && view.deferResourceRename(type, oldId, newId, () -> renameStudioDocument(type, oldId, newId))) {
                return;
            }
            if (document.graph() != null) {
                document.graph().setId(newId);
            }
            if (document.view() != null) {
                document.view().resourceRenamed(type, oldId, newId);
            }
            StudioDocument renamed = new StudioDocument(type, newId, newId, document.graph(), null,
                document.view(), document.viewport());
            studioDocumentRenamed(document, renamed);
            for (int i = studioDocuments.size() - 1; i >= 0; i--) {
                StudioDocument duplicate = studioDocuments.get(i);
                if (i == documentIndex || (!duplicate.key().equals(oldKey) && !duplicate.key().equals(newKey))) {
                    continue;
                }
                if (duplicate.view() != null && duplicate.view() != document.view()) {
                    duplicate.view().closed();
                }
                studioDocumentClosed(duplicate);
                studioDocuments.remove(i);
                if (i < documentIndex) {
                    documentIndex--;
                }
            }
            studioDocuments.set(documentIndex, renamed);
            if (activeRenamed) {
                activeStudioDocument = renamed;
            }
        }
        Object panelResource = studioResourcePanelResources.remove(oldKey);
        if (panelResource != null) {
            studioResourcePanelResources.put(newKey, panelResource);
        }
        if (terminalLegacyCoreDocuments.remove(oldKey)) {
            terminalLegacyCoreDocuments.add(newKey);
        }
        if (diagnosedLegacyCoreConflicts.remove(oldKey)) {
            diagnosedLegacyCoreConflicts.add(newKey);
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager != null) {
            manager.renameOpenProjectDocument(studioServerId(), type, oldId, newId);
        }
        syncStudioDocumentTabs();
        if (activeRenamed) {
            selectStudioDocument(newKey);
        }
    }

    protected ReSyncStudioView activeStudioView() {
        return studioMode && activeStudioDocument != null ? activeStudioDocument.view() : null;
    }

    protected Screen activeStudioViewScreen() {
        ReSyncStudioView view = activeStudioView();
        if (view instanceof ScreenBackedStudioView screenView) {
            return screenView.screen();
        }
        return view instanceof Screen screen ? screen : null;
    }

    protected boolean activeStudioViewUsesResourcePanel() {
        ReSyncStudioView view = activeStudioView();
        return view != null && view.hasPanel();
    }

    @Override
    protected boolean isSidePanelActive(SidePanel panel) {
        if (panel == studioResourcePanel) {
            return activeStudioDocument != null && (activeStudioView() == null || activeStudioViewUsesResourcePanel());
        }
        if (studioContentBrowser != null && panel == studioContentBrowser.sidePanel()) {
            return shouldRenderStudioContentBrowser();
        }
        return super.isSidePanelActive(panel);
    }

    protected boolean activeStudioDocumentUsesFlowGraphCanvas() {
        return activeStudioDocument != null && activeStudioDocument.coreSession() == null
            && activeStudioDocument.view() == null && activeStudioDocument.graph() != null;
    }

    protected void refreshActiveViewHeaderButtons() {
        List<AnimatedWidget> nextButtons = new ArrayList<>();
        ReSyncStudioView view = activeStudioView();
        if (view != null) {
            nextButtons.addAll(view.headerButtons());
        }
        for (AnimatedWidget button : activeViewHeaderButtons) {
            if (button != null) {
                button.visible = false;
            }
        }
        activeViewHeaderButtons.clear();
        activeViewHeaderButtons.addAll(nextButtons);
        for (AnimatedWidget button : activeViewHeaderButtons) {
            if (button != null) {
                button.entranceAnimationEnabled = false;
            }
        }
        rebuildStudioHeaderButtons();
        if (shouldAnimateActiveStudioHeader(view)) {
            fullEditorHeaderCloseRequested = false;
            startTopHeaderOpeningAnimation();
        } else {
            header().offset(0, 0);
        }
    }

    protected boolean shouldAnimateActiveStudioHeader(ReSyncStudioView view) {
        return view instanceof ScreenBackedStudioView screenView && screenView.fullEditor();
    }

    protected void refreshStudioResourcePanel() {
        if (studioResourcePanel == null) {
            return;
        }
        if (activeStudioDocument == null || hidesStudioResourcePanel(activeStudioDocument)) {
            clearStudioResourcePanelWidgets();
            studioResourcePanel.hide();
            return;
        }
        if (!isSidePanelActive(studioResourcePanel)) {
            studioResourcePanel.hide();
            return;
        }
        ReSyncStudioView view = activeStudioView();
        if (view != null && view.hasPanel()) {
            if (view.preferredPanelPlacement() == StudioPanel.Placement.LEFT) {
                studioResourcePanel.left();
            } else {
                studioResourcePanel.right();
            }
        } else {
            studioResourcePanel.right();
        }
        studioResourcePanel.show();
        if (view != null && view.hasPanel()) {
            view.configurePanel(studioResourceStudioPanel);
        } else if (!buildStudioResourcePanel(activeStudioDocument)) {
            clearStudioResourcePanelWidgets();
            studioResourcePanel.hide();
        }
        studioResourcePanel.container().updateWidgetPositions();
    }

    protected boolean hidesStudioResourcePanel(StudioDocument document) {
        return document.view() instanceof ScreenBackedStudioView screenView && screenView.ownsSidePanels()
            || ReSyncResourceDragPayload.FLOW.equals(document.type())
            || ReSyncResourceDragPayload.FUNCTION.equals(document.type())
            || ReSyncResourceDragPayload.CUSTOM_CONTENT.equals(document.type());
    }

    protected boolean buildStudioResourcePanel(StudioDocument document) {
        if (ReSyncResourceDragPayload.GUI.equals(document.type())) {
            buildGuiResourcePanel();
            return true;
        }
        if (ReSyncResourceDragPayload.COMMAND.equals(document.type())) {
            buildCommandResourcePanel();
            return true;
        }
        if (ReSyncResourceDragPayload.SCOREBOARD.equals(document.type())) {
            buildScoreboardResourcePanel();
            return true;
        }
        if (ReSyncResourceDragPayload.TAB.equals(document.type())) {
            buildTabResourcePanel();
            return true;
        }
        if (ReSyncResourceDragPayload.DIALOG.equals(document.type())) {
            buildDialogResourcePanel();
            return true;
        }
        if (ReSyncResourceDragPayload.WORLDGEN.equals(document.type())) {
            buildWorldGenResourcePanel();
            return true;
        }
        return false;
    }

    private Object activeStudioPanelResource() {
        if (activeStudioDocument == null) {
            return null;
        }
        return studioResourcePanelResources.get(activeStudioDocument.key());
    }

    private void submitStudioPanelSave(ReSyncResourceType type, String id, String name,
                                       Consumer<Object> mutation) {
        FlowManager manager = FlowManager.getInstance();
        String serverId = studioServerId();
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, type, id, name);
        if (manager == null || ticket == null || mutation == null) {
            DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
            return;
        }
        String key = serverId + "\u0000" + type.typeId() + "\u0000" + id;
        StudioPanelSaveIntent intent = new StudioPanelSaveIntent(serverId, type, id, ticket, mutation);
        boolean dispatch;
        synchronized (studioPanelSaveLock) {
            if (studioPanelSaveCount >= 32) {
                DesignerSaveNotifications.failExact(ticket, "Studio Is Busy");
                return;
            }
            ArrayDeque<StudioPanelSaveIntent> saves = studioPanelSaves.get(key);
            dispatch = saves == null;
            if (saves == null) {
                saves = new ArrayDeque<>();
                studioPanelSaves.put(key, saves);
            }
            saves.addLast(intent);
            studioPanelSaveCount++;
        }
        if (!dispatch) return;
        try {
            STUDIO_RESOURCE_OPENS.execute(() -> drainStudioPanelSaves(key, manager));
        } catch (IllegalStateException exception) {
            failStudioPanelSaves(key, "Studio Is Busy");
        }
    }

    private void drainStudioPanelSaves(String key, FlowManager manager) {
        while (true) {
            StudioPanelSaveIntent intent;
            synchronized (studioPanelSaveLock) {
                ArrayDeque<StudioPanelSaveIntent> saves = studioPanelSaves.get(key);
                intent = saves != null ? saves.pollFirst() : null;
                if (intent == null) {
                    studioPanelSaves.remove(key);
                    return;
                }
                studioPanelSaveCount--;
            }
            saveStudioPanelIntent(manager, intent);
        }
    }

    private void saveStudioPanelIntent(FlowManager manager, StudioPanelSaveIntent intent) {
        try {
            for (int attempt = 0; attempt < 8 && DesignerSaveNotifications.isPending(intent.ticket()); attempt++) {
                FlowManager.ResourceReadLease lease = manager.snapshotResource(intent.serverId(), intent.type().typeId(), intent.id());
                if (lease == null) {
                    DesignerSaveNotifications.failExact(intent.ticket(), "Save Snapshot Rejected");
                    return;
                }
                Object resource = deserializeStudioResource(intent.type().typeId(), lease.materialize());
                intent.mutation().accept(resource);
                if (manager.saveStudioPanelResource(intent.serverId(), intent.type(), lease, resource, intent.ticket())) return;
            }
            if (DesignerSaveNotifications.isPending(intent.ticket())) {
                DesignerSaveNotifications.failExact(intent.ticket(), "Resource Changed Repeatedly");
            }
        } catch (RuntimeException | Error exception) {
            DesignerSaveNotifications.failExact(intent.ticket(), "Save Snapshot Failed");
        }
    }

    private void failStudioPanelSaves(String key, String message) {
        List<StudioPanelSaveIntent> failed;
        synchronized (studioPanelSaveLock) {
            ArrayDeque<StudioPanelSaveIntent> saves = studioPanelSaves.remove(key);
            if (saves == null) return;
            failed = List.copyOf(saves);
            studioPanelSaveCount -= saves.size();
        }
        failed.forEach(intent -> DesignerSaveNotifications.failExact(intent.ticket(), message));
    }

    protected void buildGuiResourcePanel() {
        GuiDefinition gui = activeStudioPanelResource() instanceof GuiDefinition value ? value : null;
        if (gui == null) {
            return;
        }
        String panelKey = activeStudioDocument.key();
        if (reuseStudioResourcePanel(panelKey)) {
            updateStudioPanelInput("title", gui.getTitle());
            updateStudioPanelInput("rows", String.valueOf(gui.getRows()));
            updateStudioPanelToggle("inventory", gui.isExtendToPlayerInventory());
            return;
        }
        setStudioResourcePanelKey(panelKey);
        TextInputWidget title = panelInput("Title", gui.getTitle());
        TextInputWidget rows = panelInput("Rows", String.valueOf(gui.getRows()));
        ToggleWidget playerInventory = new ToggleWidget.Builder()
            .label("Inventory")
            .toggled(gui.isExtendToPlayerInventory())
            .size(studioPanelState.rowWidth(studioResourcePanel), 18)
            .entranceAnimation(false)
            .build();
        rememberStudioPanelInput("title", title);
        rememberStudioPanelInput("rows", rows);
        rememberStudioPanelToggle("inventory", playerInventory);
        int rowWidth = studioPanelState.rowWidth(studioResourcePanel);
        setStudioResourcePanelWidgets(studioPanelState.row("Title", title, rowWidth, studioResourceDescription("Gui Title")),
            studioPanelState.row("Rows", rows, rowWidth, studioResourceDescription("Gui Rows")),
            studioPanelState.row("Inventory", playerInventory, rowWidth, studioResourceDescription("Gui Inventory")), panelSaveButton(() -> {
            String updatedTitle = title.getText();
            int updatedRows = parseStudioInt(rows.getText(), gui.getRows(), 1, 6);
            boolean updatedInventory = playerInventory.getValue();
            submitStudioPanelSave(ReSyncResourceType.GUI, gui.getId(), updatedTitle, resource -> {
                GuiDefinition updated = (GuiDefinition) resource;
                updated.setTitle(updatedTitle);
                updated.setRows(updatedRows);
                updated.setExtendToPlayerInventory(updatedInventory);
            });
        }));
    }

    protected void buildCommandResourcePanel() {
        if (activeStudioDocument == null) {
            return;
        }
        CoreGraphEditorSession coreSession = activeStudioDocument.coreSession();
        if (coreSession != null) {
            buildCommandResourcePanel(commandContext(coreSession.commandMetadata()));
            return;
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return;
        }
        CommandBindingContext graphCommand = commandContext(activeStudioDocument.graph());
        if (graphCommand != null) {
            buildCommandResourcePanel(graphCommand);
            return;
        }
        TriggerBinding binding = manager.getCommandBinding(studioServerId(), activeStudioDocument.id());
        CommandBindingContext command = parseCommandContext(binding != null ? binding.getContext() : activeStudioDocument.id());
        buildCommandResourcePanel(command);
    }

    protected void buildScoreboardResourcePanel() {
        ScoreboardDefinition scoreboard = activeStudioPanelResource() instanceof ScoreboardDefinition value ? value : null;
        if (scoreboard == null) {
            return;
        }
        String panelKey = activeStudioDocument.key();
        if (reuseStudioResourcePanel(panelKey)) {
            updateStudioPanelInput("title", scoreboard.getTitle());
            updateStudioPanelInput("objective", scoreboard.getObjectiveId());
            updateStudioPanelInput("lines", String.join("|", scoreboard.getLines()));
            return;
        }
        setStudioResourcePanelKey(panelKey);
        TextInputWidget title = panelInput("Title", scoreboard.getTitle());
        TextInputWidget objective = panelInput("Objective", scoreboard.getObjectiveId());
        TextInputWidget lines = panelInput("Lines", String.join("|", scoreboard.getLines()));
        rememberStudioPanelInput("title", title);
        rememberStudioPanelInput("objective", objective);
        rememberStudioPanelInput("lines", lines);
        int rowWidth = studioPanelState.rowWidth(studioResourcePanel);
        setStudioResourcePanelWidgets(studioPanelState.row("Title", title, rowWidth, studioResourceDescription("Scoreboard Title")),
            studioPanelState.row("Objective", objective, rowWidth, studioResourceDescription("Objective")),
            studioPanelState.row("Lines", lines, rowWidth, studioResourceDescription("Scoreboard Lines")), panelSaveButton(() -> {
            String updatedTitle = title.getText();
            String updatedObjective = objective.getText();
            List<String> updatedLines = parseStudioLines(lines.getText());
            submitStudioPanelSave(ReSyncResourceType.SCOREBOARD, scoreboard.getId(), updatedTitle, resource -> {
                ScoreboardDefinition updated = (ScoreboardDefinition) resource;
                updated.setTitle(updatedTitle);
                updated.setObjectiveId(updatedObjective);
                updated.setLines(updatedLines);
            });
        }));
    }

    protected void buildTabResourcePanel() {
        TabDefinition tab = activeStudioPanelResource() instanceof TabDefinition value ? value : null;
        if (tab == null) {
            return;
        }
        String panelKey = activeStudioDocument.key();
        if (reuseStudioResourcePanel(panelKey)) {
            updateStudioPanelInput("header", tab.getHeader());
            updateStudioPanelInput("entry", tab.getEntryFormat());
            updateStudioPanelInput("footer", tab.getFooter());
            return;
        }
        setStudioResourcePanelKey(panelKey);
        TextInputWidget header = panelInput("Header", tab.getHeader());
        TextInputWidget entry = panelInput("Entry", tab.getEntryFormat());
        TextInputWidget footer = panelInput("Footer", tab.getFooter());
        rememberStudioPanelInput("header", header);
        rememberStudioPanelInput("entry", entry);
        rememberStudioPanelInput("footer", footer);
        int rowWidth = studioPanelState.rowWidth(studioResourcePanel);
        setStudioResourcePanelWidgets(studioPanelState.row("Header", header, rowWidth, studioResourceDescription("Tab Header")),
            studioPanelState.row("Entry", entry, rowWidth, studioResourceDescription("Tab Entry")),
            studioPanelState.row("Footer", footer, rowWidth, studioResourceDescription("Tab Footer")), panelSaveButton(() -> {
            String updatedHeader = header.getText();
            String updatedEntry = entry.getText();
            String updatedFooter = footer.getText();
            submitStudioPanelSave(ReSyncResourceType.TAB, tab.getId(), tab.getId(), resource -> {
                TabDefinition updated = (TabDefinition) resource;
                updated.setHeader(updatedHeader);
                updated.setEntryFormat(updatedEntry);
                updated.setFooter(updatedFooter);
            });
        }));
    }

    protected void buildDialogResourcePanel() {
        FlowManager manager = FlowManager.getInstance();
        JsonObject dialog = manager != null ? manager.getJsonResource(studioServerId(), ReSyncResourceType.DIALOG, activeStudioDocument.id()) : null;
        if (dialog == null) {
            return;
        }
        String panelKey = activeStudioDocument.key();
        if (reuseStudioResourcePanel(panelKey)) {
            updateStudioPanelInput("name", jsonText(dialog, "displayName"));
            updateStudioPanelInput("title", jsonText(dialog, "title"));
            updateStudioPanelInput("type", jsonText(dialog, "type"));
            return;
        }
        setStudioResourcePanelKey(panelKey);
        TextInputWidget name = panelInput("Name", jsonText(dialog, "displayName"));
        TextInputWidget title = panelInput("Title", jsonText(dialog, "title"));
        TextInputWidget type = panelInput("Type", jsonText(dialog, "type"));
        rememberStudioPanelInput("name", name);
        rememberStudioPanelInput("title", title);
        rememberStudioPanelInput("type", type);
        int rowWidth = studioPanelState.rowWidth(studioResourcePanel);
        setStudioResourcePanelWidgets(studioPanelState.row("Name", name, rowWidth, studioResourceDescription("Dialog Name")),
            studioPanelState.row("Title", title, rowWidth, studioResourceDescription("Dialog Title")),
            studioPanelState.row("Type", type, rowWidth, studioResourceDescription("Dialog Type")), panelSaveButton(() -> {
            String updatedName = name.getText();
            String updatedTitle = title.getText();
            String updatedType = type.getText();
            String dialogId = ReSyncResourceType.DIALOG.extractId(dialog);
            submitStudioPanelSave(ReSyncResourceType.DIALOG, dialogId, updatedName, resource -> {
                JsonObject updated = (JsonObject) resource;
                updated.addProperty("displayName", updatedName);
                updated.addProperty("title", updatedTitle);
                updated.addProperty("type", updatedType);
                updated.remove("pause");
                updated.remove("external_title");
            });
        }));
    }

    protected void buildWorldGenResourcePanel() {
        String panelKey = activeStudioDocument.key();
        if (reuseStudioResourcePanel(panelKey)) {
            return;
        }
        setStudioResourcePanelKey(panelKey);
        setStudioResourcePanelWidgets(panelSaveButton(() -> {
            String projectId = activeStudioDocument == null ? "" : activeStudioDocument.id();
            Screen screen = activeStudioViewScreen();
            if (!(screen instanceof WorldGenEditorScreen editor) || !editor.requestProjectSave(projectId)) {
                new Notification("World Generation Failed", "Save Not Submitted", Notification.Type.ERROR);
            }
        }));
    }

    protected void useStudioResourcePanel(StudioPanel panel) {
        studioResourceStudioPanel = panel;
        studioResourcePanel = panel != null ? panel.sidePanel() : null;
    }

    protected void clearStudioResourcePanelWidgets() {
        if (studioResourcePanel == null || studioResourcePanelWidgets.isEmpty()) {
            studioResourcePanelKey = "";
            studioResourcePanelInputs.clear();
            studioResourcePanelToggles.clear();
            return;
        }
        Container container = studioResourcePanel.container();
        for (AnimatedWidget widget : new ArrayList<>(studioResourcePanelWidgets)) {
            container.removeWidget(widget);
        }
        studioResourcePanelWidgets.clear();
        studioResourcePanelKey = "";
        studioResourcePanelInputs.clear();
        studioResourcePanelToggles.clear();
    }

    protected void setStudioResourcePanelWidgets(AnimatedWidget... widgets) {
        if (studioResourcePanel == null) {
            return;
        }
        Container container = studioResourcePanel.container();
        container.beginBatchAdd();
        for (AnimatedWidget widget : widgets) {
            ReSyncStudioPanelState.disableEntrance(widget);
            container.addWidget(widget);
            studioResourcePanelWidgets.add(widget);
        }
        container.endBatchAdd();
    }

    protected boolean reuseStudioResourcePanel(String key) {
        return key != null && key.equals(studioResourcePanelKey);
    }

    protected void setStudioResourcePanelKey(String key) {
        if (!Objects.equals(studioResourcePanelKey, key)) {
            clearStudioResourcePanelWidgets();
            studioResourcePanelKey = key;
        }
    }

    protected void rememberStudioPanelInput(String key, TextInputWidget input) {
        studioResourcePanelInputs.put(key, input);
    }

    protected void rememberStudioPanelToggle(String key, ToggleWidget toggle) {
        studioResourcePanelToggles.put(key, toggle);
    }

    protected void updateStudioPanelInput(String key, String value) {
        TextInputWidget input = studioResourcePanelInputs.get(key);
        if (input != null && !input.isFocused() && !Objects.equals(input.getText(), safeStudioText(value))) {
            input.setText(safeStudioText(value));
        }
    }

    protected void updateStudioPanelToggle(String key, boolean value) {
        ToggleWidget toggle = studioResourcePanelToggles.get(key);
        if (toggle != null && toggle.getValue() != value) {
            toggle.setValue(value);
        }
    }

    protected TextInputWidget panelInput(String placeholder, String value) {
        TextInputWidget input = new TextInputWidget.Builder()
            .placeholder(placeholder)
            .forcePlaceholder(false)
            .text(value != null ? value : "")
            .size(studioPanelState.rowWidth(studioResourcePanel), ReSyncStudioPanelState.FIELD_HEIGHT)
            .build();
        ReSyncStudioPanelState.disableEntrance(input);
        return input;
    }

    protected AnimatedButton panelSaveButton(Runnable action) {
        return studioPanelState.action("Save", studioPanelState.rowWidth(studioResourcePanel), action);
    }

    protected void buildCommandResourcePanel(CommandBindingContext command) {
        String panelKey = activeStudioDocument.key();
        List<String> paths = command.subcommands != null ? new ArrayList<>(command.subcommands) : new ArrayList<>();
        if (paths.isEmpty()) {
            paths.add("");
        }
        if (reuseStudioResourcePanel(panelKey) && commandPathInputs.size() == paths.size()) {
            syncingCommandPanel = true;
            try {
                updateStudioPanelInput("command", command.command != null && !command.command.isBlank() ? command.command : activeStudioDocument.id());
                updateStudioPanelToggle("structured", command.structured != null && command.structured);
                reconcileCommandPathOrder(paths);
                for (int i = 0; i < paths.size(); i++) {
                    TextInputWidget input = commandPathInputs.get(i);
                    if (input != null && !input.isFocused() && !Objects.equals(input.getText(), paths.get(i))) {
                        input.setText(paths.get(i));
                    }
                }
            } finally {
                syncingCommandPanel = false;
            }
            return;
        }
        clearStudioResourcePanelWidgets();
        commandPathInputs.clear();
        commandPathList = null;
        commandLabelInput = null;
        commandStructuredToggle = null;
        studioResourcePanelKey = panelKey;
        commandLabelInput = panelInput("Command", command.command != null && !command.command.isBlank() ? command.command : activeStudioDocument.id());
        commandLabelInput.setOnChange(this::commandInteractionChanged);
        commandStructuredToggle = new ToggleWidget.Builder()
            .label("Structured")
            .toggled(command.structured != null && command.structured)
            .size(studioPanelState.rowWidth(studioResourcePanel), 18)
            .entranceAnimation(false)
            .onChange(this::commandInteractionChanged)
            .build();
        rememberStudioPanelInput("command", commandLabelInput);
        rememberStudioPanelToggle("structured", commandStructuredToggle);
        int rowWidth = studioPanelState.rowWidth(studioResourcePanel);
        List<AnimatedWidget> widgets = new ArrayList<>();
        widgets.add(commandSummaryRow(rowWidth));
        widgets.add(studioPanelState.row("Command", commandLabelInput, rowWidth, studioResourceDescription("Command")));
        widgets.add(studioPanelState.row("Structured", commandStructuredToggle, rowWidth, studioResourceDescription("Structured")));
        List<RowWidget> pathRows = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            pathRows.add(commandPathEntryRow(paths, i, rowWidth));
        }
        commandPathList = new ReorderableWidget.Builder<RowWidget>()
            .items(pathRows)
            .rowHeight(18)
            .gap(2)
            .onReordered(this::moveCommandPath)
            .size(rowWidth, paths.size() * 20 - 2)
            .build();
        commandPathList.setHint("Drag To Reorder · Alt + Up/Down");
        TitledRowWidget pathsRow = new TitledRowWidget.Builder()
            .title("Paths")
            .description(studioResourceDescription("Paths"))
            .size(rowWidth, commandPathList.getHeight())
            .gap(4)
            .addWidget(commandPathList)
            .build();
        ReSyncStudioPanelState.disableEntrance(commandPathList);
        ReSyncStudioPanelState.disableEntrance(pathsRow);
        widgets.add(pathsRow);
        widgets.add(new AnimatedButton.Builder()
            .label("Add Path")
            .size(rowWidth, 18)
            .entranceAnimation(false)
            .onClick(() -> {
                CommandBindingContext draft = currentCommandDraft();
                draft.subcommands.add("");
                applyCommandInteraction(draft, true);
            })
            .build());
        setStudioResourcePanelWidgets(widgets.toArray(new AnimatedWidget[0]));
    }

    protected MountableButtonWidget commandSummaryRow(int rowWidth) {
        String command = commandLabelInput != null && commandLabelInput.getText() != null && !commandLabelInput.getText().isBlank()
            ? "/" + normalizeCommandLabel(commandLabelInput.getText())
            : "/" + activeStudioDocument.id();
        MountableButtonWidget row = new MountableButtonWidget.Builder("Command")
            .description(command)
            .iconPath("terminal.png")
            .build();
        row.setSize(rowWidth, 30);
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    protected RowWidget commandPathEntryRow(List<String> paths, int index, int rowWidth) {
        String value = paths.get(index);
        String[] pathExamples = {
            "pvp duel <online_player>",
            "report hacker <offline_player>",
            "database getPlayers <player_with_perm:my.permission.node>",
            "color <text:color_names>"
        };
        int inputWidth = Math.max(120, rowWidth - 26);
        TextInputWidget pathInput = new TextInputWidget.Builder()
            .text(value)
            .placeholder(pathExamples[index % pathExamples.length])
            .forcePlaceholder(false)
            .size(inputWidth, 18)
            .onChange(this::commandInteractionChanged)
            .build();
        ReSyncStudioPanelState.disableEntrance(pathInput);
        commandPathInputs.add(pathInput);
        RowWidget row = new RowWidget.Builder()
            .size(rowWidth, 18)
            .padding(2)
            .addWidget(pathInput)
            .addWidget(new SquareButtonWidget.Builder()
                .imagePath("delete.png")
                .size(18, 18)
                .hint("Remove Path")
                .accentType(ThemeManager.getAccent("danger"))
                .entranceAnimation(false)
                .onClick(() -> removeCommandPath(pathInput))
                .build())
            .build();
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    protected void moveCommandPath(int from, int to) {
        if (from < 0 || to < 0 || from >= commandPathInputs.size() || to >= commandPathInputs.size() || from == to) {
            return;
        }
        commandPathInputs.add(to, commandPathInputs.remove(from));
        applyCommandInteraction(currentCommandDraft(), false);
    }

    private void reconcileCommandPathOrder(List<String> paths) {
        if (commandPathList == null || paths == null || commandPathInputs.size() != paths.size()) {
            return;
        }
        List<RowWidget> rows = commandPathList.getItems();
        if (rows.size() != commandPathInputs.size()) {
            return;
        }
        List<TextInputWidget> reorderedInputs = new ArrayList<>();
        List<RowWidget> reorderedRows = new ArrayList<>();
        boolean[] used = new boolean[commandPathInputs.size()];
        for (String path : paths) {
            int match = -1;
            for (int index = 0; index < commandPathInputs.size(); index++) {
                if (!used[index] && Objects.equals(commandPathInputs.get(index).getText(), path)) {
                    match = index;
                    break;
                }
            }
            if (match < 0) {
                return;
            }
            used[match] = true;
            reorderedInputs.add(commandPathInputs.get(match));
            reorderedRows.add(rows.get(match));
        }
        if (reorderedInputs.equals(commandPathInputs)) {
            return;
        }
        commandPathInputs.clear();
        commandPathInputs.addAll(reorderedInputs);
        commandPathList.setItems(reorderedRows);
    }

    protected void removeCommandPath(TextInputWidget pathInput) {
        int index = commandPathInputs.indexOf(pathInput);
        CommandBindingContext draft = currentCommandDraft();
        if (index >= 0 && index < draft.subcommands.size()) {
            draft.subcommands.remove(index);
        }
        if (draft.subcommands.isEmpty()) {
            draft.subcommands.add("");
        }
        applyCommandInteraction(draft, true);
    }

    protected void rebuildCommandResourcePanel(CommandBindingContext draft) {
        syncingCommandPanel = true;
        try {
            studioResourcePanelKey = "";
            buildCommandResourcePanel(draft);
            studioResourcePanel.container().updateWidgetPositions();
        } finally {
            syncingCommandPanel = false;
        }
    }

    private void commandInteractionChanged() {
        if (!syncingCommandPanel) {
            applyCommandInteraction(currentCommandDraft(), false);
        }
    }

    private void applyCommandInteraction(CommandBindingContext draft, boolean rebuild) {
        if (activeStudioDocument == null || draft == null) {
            return;
        }
        if (activeStudioDocument.coreSession() != null) {
            applyCoreCommandInteraction(draft);
        } else if (activeStudioDocument.graph() != null) {
            applyStudioCollaborationInteraction(() -> applyCommandContext(activeStudioDocument.graph(), draft));
        } else {
            return;
        }
        if (rebuild) {
            rebuildCommandResourcePanel(draft);
        }
    }

    protected void applyCoreCommandInteraction(CommandBindingContext context) {
    }

    protected void applyStudioCollaborationInteraction(Runnable mutation) {
        if (mutation != null) {
            mutation.run();
        }
    }

    protected List<String> collectCommandPathDraft() {
        List<String> paths = new ArrayList<>();
        for (TextInputWidget input : commandPathInputs) {
            paths.add(input != null && input.getText() != null ? input.getText().trim() : "");
        }
        return paths;
    }

    protected List<String> collectCommandPaths() {
        return collectCommandPaths(collectCommandPathDraft());
    }

    protected List<String> collectCommandPaths(List<String> values) {
        List<String> paths = new ArrayList<>();
        if (values == null) {
            return paths;
        }
        for (String value : values) {
            String path = value != null ? value.trim() : "";
            if (!path.isBlank()) {
                paths.add(path);
            }
        }
        return paths;
    }

    protected CommandBindingContext currentCommandDraft() {
        CommandBindingContext draft = new CommandBindingContext();
        draft.command = commandLabelInput != null ? commandLabelInput.getText() : activeStudioDocument.id();
        draft.subcommands = collectCommandPathDraft();
        draft.structured = commandStructuredToggle != null && commandStructuredToggle.getValue();
        return draft;
    }

    protected CommandBindingContext commandContext(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return null;
        }
        for (FlowNode node : graph.getNodes().values()) {
            if (node == null || !isCommandStartNode(node.getType()) || node.getInputValues() == null) {
                continue;
            }
            Object commandValue = node.getInputValues().get("command");
            String command = commandValue != null ? normalizeCommandLabel(String.valueOf(commandValue)) : "";
            if (command.isBlank()) {
                continue;
            }
            CommandBindingContext context = new CommandBindingContext();
            context.command = command;
            Object pathsValue = node.getInputValues().get("subcommands");
            context.subcommands = pathsValue instanceof List<?> paths
                ? paths.stream().map(path -> path != null ? String.valueOf(path) : "").toList()
                : new ArrayList<>();
            Object structuredValue = node.getInputValues().get("structured");
            context.structured = structuredValue instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(structuredValue));
            return context;
        }
        return null;
    }

    protected CommandBindingContext commandContext(CommandGraphMetadata metadata) {
        CommandBindingContext context = new CommandBindingContext();
        context.command = metadata.commandLabel();
        context.subcommands = new ArrayList<>(metadata.commandPaths());
        context.structured = metadata.structured();
        return context;
    }

    protected CommandGraphMetadata commandMetadata(CommandBindingContext context) {
        return new CommandGraphMetadata(normalizeCommandLabel(context.command), context.structured != null && context.structured,
            collectCommandPaths(context.subcommands));
    }

    private boolean isEditingCommandPanel() {
        if (commandLabelInput != null && commandLabelInput.isFocused()) {
            return true;
        }
        if (commandStructuredToggle != null && commandStructuredToggle.isFocused()) {
            return true;
        }
        return commandPathInputs.stream().anyMatch(input -> input != null && input.isFocused());
    }

    protected boolean applyCommandContext(FlowGraph graph, CommandBindingContext context) {
        if (graph == null || graph.getNodes() == null || context == null) {
            return false;
        }
        for (FlowNode node : graph.getNodes().values()) {
            if (node == null || !isCommandStartNode(node.getType())) {
                continue;
            }
            if (node.getInputValues() == null) {
                node.setInputValues(new HashMap<>());
            }
            node.getInputValues().put("command", normalizeCommandLabel(context.command));
            node.getInputValues().put("subcommands", context.subcommands != null ? new ArrayList<>(context.subcommands) : new ArrayList<>());
            node.getInputValues().put("structured", context.structured != null && context.structured);
            return true;
        }
        return false;
    }

    protected boolean isCommandStartNode(String type) {
        return CommandGraphContract.isAnyStart(type);
    }

    protected CommandBindingContext parseCommandContext(String context) {
        CommandBindingContext parsed = new CommandBindingContext();
        parsed.subcommands = new ArrayList<>();
        parsed.structured = false;
        if (context == null || context.isBlank()) {
            return parsed;
        }
        String trimmed = context.trim();
        if (trimmed.startsWith("{")) {
            try {
                CommandBindingContext decoded = CommandBindingContext.fromJson(trimmed);
                if (decoded != null) {
                    parsed.command = normalizeCommandLabel(decoded.command);
                    parsed.subcommands = decoded.subcommands != null ? decoded.subcommands : new ArrayList<>();
                    parsed.structured = decoded.structured != null && decoded.structured;
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }
        parsed.command = normalizeCommandLabel(trimmed);
        return parsed;
    }

    protected String encodeCommandContext(CommandBindingContext command) {
        if (command == null) {
            return "";
        }
        command.command = normalizeCommandLabel(command.command);
        command.subcommands = command.subcommands != null ? command.subcommands : new ArrayList<>();
        command.structured = command.structured != null && command.structured;
        return command.subcommands.isEmpty() && !command.structured ? command.command : command.toJson();
    }

    protected String normalizeCommandLabel(String label) {
        String command = label != null ? label.trim().toLowerCase(Locale.ROOT) : "";
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        return command.matches("^[a-zA-Z0-9:_-]+$") ? command : "";
    }

    protected int parseStudioInt(String value, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(value), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    protected List<String> parseStudioLines(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\|")) {
            if (!line.isBlank()) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    protected String jsonText(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    protected boolean jsonBool(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    protected String studioResourceDescription(String label) {
        return switch (label) {
            case "Command" -> "Root command label.\nDo not include the leading slash.\nExample: trade creates /trade.";
            case "Structured" -> "Structured command mode.\nOn: ReSync stores paths and argument tokens.\nOff: the command is treated as one flat trigger label.";
            case "Paths" -> "Subcommand paths matched after the root command.\nEach row is one path.\nTokens are separated by spaces.\nPlaceholders:\n<text:list_id> Values from a Text list or keys from a Text map.\n<text:map_id:values> Values from a Text map.\n<online_player> Online Bukkit player name.\n<offline_player> Known offline player name.\n<player_with_perm:permission.node> Online player with that permission.\n<any> Any single argument token.\nAny other <name> also matches one token.";
            case "Gui Title" -> "Inventory title shown at the top of the GUI.\nMinecraft displays it in the menu header, so keep it short.";
            case "Gui Rows" -> "Chest row count.\nValid range: 1 to 6.\nEach row adds 9 custom slots.";
            case "Gui Inventory" -> "Player inventory visibility.\nOn: show the player's inventory under the custom menu.\nOff: show only the custom menu slots.";
            case "Scoreboard Title" -> "Sidebar display title.\nMinecraft renders this above all scoreboard lines.";
            case "Objective" -> "Scoreboard objective id.\nKeep it stable because updates target this id.";
            case "Scoreboard Lines" -> "Sidebar rows under the title.\nMinecraft shows up to 15 lines.\nEarlier lines appear higher.";
            case "Tab Header" -> "Text above the player list in the tab overlay.\nSupports multiple lines.";
            case "Tab Entry" -> "Per-player tab row format.\n%player% is replaced in the preview.\nRuntime placeholders depend on the synced tab resource.";
            case "Tab Footer" -> "Text below the player list in the tab overlay.\nSupports multiple lines.";
            default -> "";
        };
    }

    protected void updateStudioTabStates() {
        if (studioTabsManager == null) {
            return;
        }
        long refreshAt = System.nanoTime();
        if (refreshAt < nextStudioTabStateRefreshAt) {
            return;
        }
        nextStudioTabStateRefreshAt = refreshAt + STUDIO_TAB_STATE_REFRESH_NANOS;
        boolean layoutChanged = false;
        for (TabsManager.Tab tab : studioTabsManager.getTabs()) {
            if (!(tab.getData() instanceof String key)) {
                continue;
            }
            StudioDocument document = findStudioDocument(key);
            if (document == null) {
                continue;
            }
            boolean dirty = isStudioDocumentDirty(document);
            if (tab.isUnsaved() != dirty) {
                tab.setUnsaved(dirty);
                layoutChanged = true;
            }
            if (!tab.getName().equals(document.title()) || !Objects.equals(tab.getIconPath(), studioResourceIconPath(document.type(), document.id()))) {
                tab.setName(document.title());
                tab.setIconPath(studioResourceIconPath(document.type(), document.id()));
                layoutChanged = true;
            }
        }
        if (layoutChanged) {
            studioTabsManager.updateLayout();
        }
        retryPendingCoreStudioDocument();
    }

    private void clearStudioDiscardPrompts() {
        if (studioTabsManager != null) {
            studioTabsManager.clearDiscardConfirms();
        }
    }

    protected void clearStudioDiscardPrompt(String key) {
        if (studioTabsManager == null || key == null) {
            return;
        }
        TabsManager.Tab tab = findStudioTab(key, studioTabsManager.getTabs());
        if (tab != null) {
            studioTabsManager.clearDiscardConfirm(tab);
        }
    }

    protected void syncStudioDocumentTabs() {
        if (studioTabsManager == null) {
            return;
        }
        Set<String> documentKeys = new HashSet<>();
        for (StudioDocument document : studioDocuments) {
            documentKeys.add(document.key());
        }
        List<TabsManager.Tab> tabs = studioTabsManager.getTabs();
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Object data = tabs.get(i).getData();
            if (!(data instanceof String key) || !documentKeys.contains(key)) {
                studioTabsManager.removeTabRaw(i);
            }
        }
        tabs = studioTabsManager.getTabs();
        for (StudioDocument document : studioDocuments) {
            TabsManager.Tab tab = findStudioTab(document.key(), tabs);
            if (tab == null) {
                Container container = new Container("document-" + document.key(), 0, 0, 1, 1);
                tab = studioTabsManager.addTab(document.title(), container, studioResourceIconPath(document.type(), document.id()));
                tab.setData(document.key());
                tabs.add(tab);
            } else {
                tab.setName(document.title());
                tab.setIconPath(studioResourceIconPath(document.type(), document.id()));
            }
            tab.setUnsaved(isStudioDocumentDirty(document));
        }
        if (activeStudioDocument != null) {
            TabsManager.Tab activeTab = findStudioTab(activeStudioDocument.key(), studioTabsManager.getTabs());
            if (activeTab != null && studioTabsManager.getActiveTab() != activeTab) {
                setStudioTabsManagerActiveTab(activeTab);
            } else if (activeTab == null) {
                clearActiveStudioDocument();
            }
        }
        studioTabsManager.updateLayout();
    }

    protected void selectStudioDocument(String key) {
        StudioDocument document = findStudioDocument(key);
        if (document == null) {
            clearActiveStudioDocument();
            return;
        }
        if (!restoringPendingCoreStudioSelection) {
            demotePendingCoreActivationExcept(key);
        }
        if (isUnchangedActiveStudioDocument(document)) {
            return;
        }
        clearStudioDiscardPrompts();
        StudioDocument selected = rebindCoreStudioDocument(document);
        if (selected == null) {
            ReSyncResourceType type = ReSyncResourceType.byTypeId(document.type());
            boolean corePending = isCoreStudioDocumentPending(document);
            if (corePending) {
                FlowManager manager = FlowManager.getInstance();
                ReSyncFlowClient.CoreGraphActivationOutcome outcome = coreGraphActivation(
                    manager, type, document.id());
                if (terminalCoreActivation(outcome) && !coreActivationStillSettling(outcome)) {
                    String terminalReason = coreActivationReason(outcome);
                    terminalCoreStudioRebinds.put(key, terminalReason);
                    notifyCoreGraphOpenFailed(terminalReason);
                    return;
                }
                terminalCoreStudioRebinds.remove(key);
                boolean newIntent = !pendingCoreOpenIntents.containsKey(key);
                CoreOpenIntent intent = acceptCoreOpenIntent(type, document.id(), document.title());
                if (newIntent && intent != null) {
                    requestCoreOpenResource(manager, type, document.id());
                }
                if (intent != null && !Objects.equals(pendingCoreStudioDocumentKey, key)) {
                    pendingCoreStudioDocumentKey = key;
                    pendingCoreStudioPreviousDocumentKey = intent.previousDocumentKey();
                    pendingCoreStudioDocumentAt = intent.acceptedAt();
                }
                retainVisibleStudioDocument();
                return;
            }
            return;
        }
        if (!pendingCoreOpenIntents.containsKey(key)) {
            clearPendingCoreStudioDocument();
        }
        terminalCoreStudioRebinds.remove(key);
        activateStudioDocument(selected);
    }

    private void retainVisibleStudioDocument() {
        if (activeStudioDocument == null || studioTabsManager == null) {
            return;
        }
        TabsManager.Tab activeTab = findStudioTab(activeStudioDocument.key(), studioTabsManager.getTabs());
        if (activeTab != null && studioTabsManager.getActiveTab() != activeTab) {
            setStudioTabsManagerActiveTab(activeTab);
        }
    }

    private void activateStudioDocument(StudioDocument selected) {
        if (activeStudioDocument != null && !activeStudioDocument.key().equals(selected.key())
            && activeStudioDocument.view() != null) {
            activeStudioDocument.view().deselected();
        }
        beforeStudioDocumentSelection();
        prepareStudioDocumentSelection();
        activeStudioDocument = selected;
        TabsManager.Tab selectedTab = findStudioTab(selected.key(), studioTabsManager != null
            ? studioTabsManager.getTabs() : List.of());
        setStudioTabsManagerActiveTab(selectedTab);
        CoreOpenIntent pendingIntent = pendingCoreOpenIntents.get(selected.key());
        ReSyncFlowClient.traceLifecycle(studioServerId(), "studio_document_selected", "serverId", studioServerId(),
            "resourceKey", selected.key(), "requestId", "studio", "mutationId", null, "authorityEpoch", 0L,
            "revision", selected.coreSession() != null
                ? selected.coreSession().revision() : selected.graph() != null ? selected.graph().getResourceRevision() : -1L,
            "nodeCount", selected.graph() != null && selected.graph().getNodes() != null
                ? selected.graph().getNodes().size() : 0, "documentPresent", true, "tabPresent",
            selectedTab != null, "tabSelected", studioTabSelected(selected.key()), "phase",
            pendingIntent != null ? pendingIntent.phase() : "none", "activate", true);
        if (selected.view() != null) {
            selected.view().init();
            selected.view().selected();
        }
        refreshActiveViewHeaderButtons();
        afterStudioDocumentSelected(selected);
        if (studioResourcePanel != null) {
            if (selected.view() != null && !selected.view().hasPanel()) {
                studioResourcePanel.hide();
            } else {
                studioResourcePanel.show();
            }
        }
        if (selected.view() == null || selected.view().hasPanel()) {
            refreshStudioResourcePanel();
        }
        updatePositions();
    }

    private boolean isUnchangedActiveStudioDocument(StudioDocument document) {
        if (document == null || activeStudioDocument == null
            || document != activeStudioDocument || !document.key().equals(activeStudioDocument.key())) {
            return false;
        }
        if (document.coreSession() != null) {
            return isCurrentCoreStudioDocument(document);
        }
        if (terminalCoreStudioRebinds.containsKey(document.key())) {
            return true;
        }
        return !isCoreStudioDocumentPending(document);
    }

    protected boolean isCoreStudioDocumentPending(StudioDocument document) {
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType type = document != null ? ReSyncResourceType.byTypeId(document.type()) : null;
        CoreStudioOwnership ownership = document != null
            ? coreStudioOwnership(manager, type, document.id()) : CoreStudioOwnership.NOT_GRAPH;
        return document != null && (document.coreSession() != null || ownership == CoreStudioOwnership.PENDING_CORE
            || ownership == CoreStudioOwnership.CORE_REQUIRED || ownership == CoreStudioOwnership.TERMINAL_CORE);
    }

    protected void prepareStudioDocumentSelection() {
        closeStudioSelector();
        ReSyncStudioView view = activeStudioView();
        if (view instanceof StudioScreen screen) {
            screen.closeStudioSelector();
            screen.clearStudioResourcePanelWidgets();
        }
        if (studioResourcePanel != null) {
            studioResourcePanel.container().clearWidgets();
        }
        studioResourcePanelWidgets.clear();
        studioResourcePanelKey = "";
        studioResourcePanelInputs.clear();
        studioResourcePanelToggles.clear();
    }

    protected void beforeStudioDocumentSelection() {
    }

    protected void afterStudioDocumentSelected(StudioDocument document) {
    }

    protected void studioDocumentClosed(StudioDocument document) {
    }

    protected void studioDocumentRenamed(StudioDocument previous, StudioDocument renamed) {
    }

    protected void clearActiveStudioDocument() {
        if (activeStudioDocument != null && activeStudioDocument.view() != null) {
            activeStudioDocument.view().deselected();
        }
        beforeClearActiveStudioDocument();
        activeStudioDocument = null;
        if (studioResourcePanel != null) {
            clearStudioResourcePanelWidgets();
            studioResourcePanel.hide();
        }
        afterActiveStudioDocumentCleared();
        refreshActiveViewHeaderButtons();
        updatePositions();
    }

    protected void beforeClearActiveStudioDocument() {
    }

    protected void afterActiveStudioDocumentCleared() {
    }

    protected TabsManager.Tab findStudioTab(String key, List<TabsManager.Tab> tabs) {
        for (TabsManager.Tab tab : tabs) {
            if (key.equals(tab.getData())) {
                return tab;
            }
        }
        return null;
    }

    protected boolean studioTabPresent(String key) {
        return studioTabsManager != null && findStudioTab(key, studioTabsManager.getTabs()) != null;
    }

    protected boolean studioTabSelected(String key) {
        if (studioTabsManager == null) {
            return false;
        }
        TabsManager.Tab tab = findStudioTab(key, studioTabsManager.getTabs());
        return tab != null && studioTabsManager.getActiveTab() == tab;
    }

    protected void setStudioTabsManagerActiveTab(TabsManager.Tab tab) {
        if (studioTabsManager == null || tab == null || studioTabsManager.getActiveTab() == tab) {
            return;
        }
        syncingStudioTabSelection = true;
        try {
            studioTabsManager.setActiveTab(tab.getContainer());
        } finally {
            syncingStudioTabSelection = false;
        }
    }

    protected boolean handleStudioWorkspaceMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        ReMouseEvent event = studioMouseEvent(ReMouseEvent.Action.DRAGGED, mouseX, mouseY, button, deltaX, deltaY);
        return event.finish(handleStudioWorkspaceMouseDragged(event));
    }

    protected boolean handleStudioWorkspaceMouseDragged(ReMouseEvent event) {
        if (studioTabsManager != null && Widget.dispatchMouseDragged(studioTabsManager, event)) {
            return true;
        }
        if (handleActiveStudioSelectorMouseDragged(event)) {
            return true;
        }
        if (dispatchOverlayMouseDragged(event.retarget(this, event.x(), event.y(), event.deltaX(), event.deltaY()))) {
            return true;
        }
        ReSyncStudioView priorityView = activeStudioView();
        if (priorityView instanceof StudioPriorityInputView && priorityView.mouseDragged(event)) {
            return true;
        }
        if (studioMode && studioContentBrowser != null && Widget.dispatchMouseDragged(studioContentBrowser, event)) {
            return true;
        }
        ReSyncStudioView view = activeStudioView();
        if (view != null) {
            if (activeStudioViewUsesResourcePanel() && studioResourcePanel != null && Widget.dispatchMouseDragged(studioResourcePanel.container(), event)) {
                return true;
            }
            return view.mouseDragged(event);
        }
        return studioResourcePanel != null && Widget.dispatchMouseDragged(studioResourcePanel.container(), event);
    }

    protected boolean handleStudioWorkspaceMouseClicked(double mouseX, double mouseY, int button) {
        ReMouseEvent event = studioMouseEvent(ReMouseEvent.Action.PRESSED, mouseX, mouseY, button, 0, 0);
        return event.finish(handleStudioWorkspaceMouseClicked(event));
    }

    protected boolean handleStudioWorkspaceMouseClicked(ReMouseEvent event) {
        if (studioMode && studioContentBrowser != null) {
            studioContentBrowser.updateShortcutFocus(event);
        }
        if (handleActiveStudioSelectorMouseClicked(event)) {
            setFocusedWidget(null);
            return true;
        }
        if (dispatchOverlayMouseClicked(event.retarget(this, event.x(), event.y()))) {
            return true;
        }
        ReSyncStudioView priorityView = activeStudioView();
        if (priorityView instanceof StudioPriorityInputView) {
            setFocusedWidget(null);
            if (priorityView.mouseClicked(event)) {
                return true;
            }
        }
        if (handleStudioHudMouseClicked(event)) {
            ReSyncStudioView view = activeStudioView();
            if (view != null) {
                view.clearFocus();
            }
            return true;
        }
        ReSyncStudioView view = activeStudioView();
        if (view != null) {
            if (activeStudioViewUsesResourcePanel() && studioResourcePanel != null && Widget.dispatchMouseClicked(studioResourcePanel.container(), event)) {
                view.clearFocus();
                return true;
            }
            setFocusedWidget(null);
            boolean handled = view.mouseClicked(event);
            return handled || (event.button() == ReMouseButton.RIGHT && !activeStudioDocumentUsesFlowGraphCanvas());
        }
        boolean handled = studioResourcePanel != null && Widget.dispatchMouseClicked(studioResourcePanel.container(), event);
        return handled || (event.button() == ReMouseButton.RIGHT && !activeStudioDocumentUsesFlowGraphCanvas());
    }

    protected boolean handleStudioWorkspaceMouseReleased(double mouseX, double mouseY, int button) {
        ReMouseEvent event = studioMouseEvent(ReMouseEvent.Action.RELEASED, mouseX, mouseY, button, 0, 0);
        return event.finish(handleStudioWorkspaceMouseReleased(event));
    }

    protected boolean handleStudioWorkspaceMouseReleased(ReMouseEvent event) {
        if (studioResourceDrag != null && event.button() == ReMouseButton.LEFT) {
            if (studioMode && studioContentBrowser != null) {
                Widget.dispatchMouseReleased(studioContentBrowser, event);
            }
            ReSyncResourceDragPayload payload = studioResourceDrag;
            studioResourceDrag = null;
            boolean accepted = dropStudioResource(payload, event);
            if (studioResourceDragWidget != null && studioResourceDragDestination == null) {
                if (accepted) {
                    completeStudioResourceDragTo((int) event.x() - 4, (int) event.y() - 4, 8, 8, true);
                } else {
                    completeStudioResourceDragTo(studioResourceDragSourceX, studioResourceDragSourceY,
                        studioResourceDragSourceWidth, studioResourceDragSourceHeight, false);
                }
            }
            return true;
        }
        if (studioTabsManager != null && Widget.dispatchMouseReleased(studioTabsManager, event)) {
            return true;
        }
        if (handleActiveStudioSelectorMouseReleased(event)) {
            return true;
        }
        if (dispatchOverlayMouseReleased(event.retarget(this, event.x(), event.y()))) {
            return true;
        }
        ReSyncStudioView priorityView = activeStudioView();
        if (priorityView instanceof StudioPriorityInputView && priorityView.mouseReleased(event)) {
            return true;
        }
        if (studioMode && studioContentBrowser != null && Widget.dispatchMouseReleased(studioContentBrowser, event)) {
            return true;
        }
        ReSyncStudioView view = activeStudioView();
        if (view != null) {
            if (activeStudioViewUsesResourcePanel() && studioResourcePanel != null && Widget.dispatchMouseReleased(studioResourcePanel.container(), event)) {
                return true;
            }
            return view.mouseReleased(event);
        }
        return studioResourcePanel != null && Widget.dispatchMouseReleased(studioResourcePanel.container(), event);
    }

    public void beginStudioResourceDrag(ReSyncResourceDragPayload payload, AnimatedWidget transition, int grabX, int grabY) {
        if (payload != null && !payload.isFolder() && transition != null) {
            clearStudioResourceDragWidget();
            studioResourceDrag = payload;
            studioResourceDragWidget = transition;
            studioResourceDragSourceX = transition.getX();
            studioResourceDragSourceY = transition.getY();
            studioResourceDragSourceWidth = transition.getWidth();
            studioResourceDragSourceHeight = transition.getHeight();
            String label = payload.displayName() == null || payload.displayName().isBlank() ? payload.id() : payload.displayName();
            studioResourceDragGrabX = Math.clamp(grabX, 0, studioResourceDragSourceWidth);
            studioResourceDragGrabY = Math.clamp(grabY, 0, studioResourceDragSourceHeight);
            studioResourceDragDetachedWidth = Math.clamp(Math.max(tr.getWidth(label) + 40, studioResourceDragGrabX + 12),
                96, studioResourceDragSourceWidth);
            studioResourceDragDestination = null;
        }
    }

    protected void completeStudioResourceDragTo(int x, int y, int width, int height, boolean close) {
        if (studioResourceDragWidget == null) {
            return;
        }
        studioResourceDragDestination = new StudioResourceDragDestination(x, y, Math.max(1, width), Math.max(1, height), close);
        studioResourceDragWidget.setAnimateLayoutPosition(true);
        studioResourceDragWidget.setPosition(x, y);
        studioResourceDragWidget.setWidth(Math.max(1, width));
        studioResourceDragWidget.setHeight(Math.max(1, height));
    }

    protected int[] takeStudioResourceDragBounds() {
        if (studioResourceDragWidget == null) {
            return null;
        }
        int[] bounds = {
            studioResourceDragWidget.getX(),
            studioResourceDragWidget.getY(),
            studioResourceDragWidget.getWidth(),
            studioResourceDragWidget.getHeight()
        };
        clearStudioResourceDragWidget();
        studioResourceDragDestination = null;
        return bounds;
    }

    private void clearStudioResourceDragWidget() {
        if (studioResourceDragWidget == null) {
            return;
        }
        studioResourceDragWidget.visible = false;
        WidgetCleanup.cleanup(studioResourceDragWidget);
        studioResourceDragWidget = null;
    }

    protected boolean dropStudioResource(ReSyncResourceDragPayload payload, ReMouseEvent event) {
        return false;
    }

    protected boolean handleStudioWorkspaceKeyPressed(int keyCode, int scanCode, int modifiers) {
        ReKeyEvent event = currentKeyPressedEvent(keyCode, scanCode, modifiers, false);
        return event.finish(handleStudioWorkspaceKeyPressed(event));
    }

    protected boolean handleStudioWorkspaceKeyPressed(ReKeyEvent event) {
        if (collaborationChatInput != null) {
            return false;
        }
        if (studioTabsManager != null && Widget.dispatchKeyPressed(studioTabsManager, event)) {
            return true;
        }
        if (handleActiveStudioSelectorKeyPressed(event)) {
            return true;
        }
        ReSyncStudioView priorityView = activeStudioView();
        if (priorityView instanceof StudioPriorityInputView && priorityView.keyPressed(event)) {
            return true;
        }
        if (studioMode && studioContentBrowser != null && Widget.dispatchKeyPressed(studioContentBrowser, event)) {
            return true;
        }
        ReSyncStudioView view = activeStudioView();
        if (view != null) {
            if (activeStudioViewUsesResourcePanel() && studioResourcePanel != null && studioResourcePanel.isVisible() && Widget.dispatchKeyPressed(studioResourcePanel.container(), event)) {
                return true;
            }
            return view.keyPressed(event);
        }
        return studioResourcePanel != null && studioResourcePanel.isVisible() && Widget.dispatchKeyPressed(studioResourcePanel.container(), event);
    }

    protected boolean handleStudioWorkspaceCharTyped(char chr, int modifiers) {
        ReTextInputEvent event = currentTextInputEvent(chr, modifiers);
        return event.finish(handleStudioWorkspaceTextInput(event));
    }

    protected boolean handleStudioWorkspaceTextInput(ReTextInputEvent event) {
        if (collaborationChatInput != null) {
            return false;
        }
        if (studioTabsManager != null && Widget.dispatchTextInput(studioTabsManager, event)) {
            return true;
        }
        if (handleActiveStudioSelectorTextInput(event)) {
            return true;
        }
        ReSyncStudioView priorityView = activeStudioView();
        if (priorityView instanceof StudioPriorityInputView && priorityView.textInput(event)) {
            return true;
        }
        if (studioMode && studioContentBrowser != null && Widget.dispatchTextInput(studioContentBrowser, event)) {
            return true;
        }
        ReSyncStudioView view = activeStudioView();
        if (view != null) {
            if (activeStudioViewUsesResourcePanel() && studioResourcePanel != null && studioResourcePanel.isVisible() && Widget.dispatchTextInput(studioResourcePanel.container(), event)) {
                return true;
            }
            return view.textInput(event);
        }
        return studioResourcePanel != null && studioResourcePanel.isVisible() && Widget.dispatchTextInput(studioResourcePanel.container(), event);
    }

    protected boolean handleStudioWorkspaceMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        ReScrollEvent event = currentScrollEvent(mouseX, mouseY, horizontalAmount, verticalAmount);
        return event.finish(handleStudioWorkspaceMouseScrolled(event));
    }

    protected boolean handleStudioWorkspaceMouseScrolled(ReScrollEvent event) {
        if (studioTabsManager != null && Widget.dispatchMouseScrolled(studioTabsManager, event)) {
            return true;
        }
        if (handleActiveStudioSelectorMouseScrolled(event)) {
            return true;
        }
        if (dispatchOverlayMouseScrolled(event.retarget(this, event.x(), event.y()))) {
            return true;
        }
        if (studioMode && studioContentBrowser != null && Widget.dispatchMouseScrolled(studioContentBrowser, event)) {
            return true;
        }
        ReSyncStudioView view = activeStudioView();
        if (view != null) {
            if (activeStudioViewUsesResourcePanel() && studioResourcePanel != null && studioResourcePanel.isVisible() && Widget.dispatchMouseScrolled(studioResourcePanel.container(), event)) {
                return true;
            }
            return view.mouseScrolled(event);
        }
        return studioResourcePanel != null && studioResourcePanel.isVisible() && Widget.dispatchMouseScrolled(studioResourcePanel.container(), event);
    }

    protected boolean handleStudioHudMouseClicked(double mouseX, double mouseY, int button) {
        ReMouseEvent event = studioMouseEvent(ReMouseEvent.Action.PRESSED, mouseX, mouseY, button, 0, 0);
        return event.finish(handleStudioHudMouseClicked(event));
    }

    protected boolean handleStudioHudMouseClicked(ReMouseEvent event) {
        if (!studioMode) {
            return false;
        }
        if (studioContentBrowser != null && studioContentBrowser.handleHistoryMouseButton(event)) {
            return true;
        }
        if (studioTabsManager != null && Widget.dispatchMouseClicked(studioTabsManager, event)) {
            return true;
        }
        return studioContentBrowser != null && Widget.dispatchMouseClicked(studioContentBrowser, event);
    }

    protected boolean handleActiveStudioSelectorMouseClicked(double mouseX, double mouseY, int button) {
        ReMouseEvent event = studioMouseEvent(ReMouseEvent.Action.PRESSED, mouseX, mouseY, button, 0, 0);
        return event.finish(handleActiveStudioSelectorMouseClicked(event));
    }

    protected boolean handleActiveStudioSelectorMouseClicked(ReMouseEvent event) {
        if (activeStudioSelector != null && activeStudioSelector.visible) {
            return Widget.dispatchMouseClicked(activeStudioSelector, event);
        }
        ReSyncStudioView view = activeStudioView();
        if (view instanceof StudioSelectorView selectorView && selectorView.hasActiveStudioSelector()) {
            return view.mouseClicked(event);
        }
        return false;
    }

    protected boolean handleActiveStudioSelectorMouseReleased(double mouseX, double mouseY, int button) {
        ReMouseEvent event = studioMouseEvent(ReMouseEvent.Action.RELEASED, mouseX, mouseY, button, 0, 0);
        return event.finish(handleActiveStudioSelectorMouseReleased(event));
    }

    protected boolean handleActiveStudioSelectorMouseReleased(ReMouseEvent event) {
        if (activeStudioSelector != null && activeStudioSelector.visible) {
            return Widget.dispatchMouseReleased(activeStudioSelector, event);
        }
        ReSyncStudioView view = activeStudioView();
        if (view instanceof StudioSelectorView selectorView && selectorView.hasActiveStudioSelector()) {
            return view.mouseReleased(event);
        }
        return false;
    }

    protected boolean handleActiveStudioSelectorMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        ReMouseEvent event = studioMouseEvent(ReMouseEvent.Action.DRAGGED, mouseX, mouseY, button, deltaX, deltaY);
        return event.finish(handleActiveStudioSelectorMouseDragged(event));
    }

    protected boolean handleActiveStudioSelectorMouseDragged(ReMouseEvent event) {
        if (activeStudioSelector != null && activeStudioSelector.visible) {
            return Widget.dispatchMouseDragged(activeStudioSelector, event);
        }
        ReSyncStudioView view = activeStudioView();
        if (view instanceof StudioSelectorView selectorView && selectorView.hasActiveStudioSelector()) {
            return view.mouseDragged(event);
        }
        return false;
    }

    protected boolean handleActiveStudioSelectorMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        ReScrollEvent event = currentScrollEvent(mouseX, mouseY, horizontalAmount, verticalAmount);
        return event.finish(handleActiveStudioSelectorMouseScrolled(event));
    }

    protected boolean handleActiveStudioSelectorMouseScrolled(ReScrollEvent event) {
        if (activeStudioSelector != null && activeStudioSelector.visible) {
            return Widget.dispatchMouseScrolled(activeStudioSelector, event);
        }
        ReSyncStudioView view = activeStudioView();
        if (view instanceof StudioSelectorView selectorView && selectorView.hasActiveStudioSelector()) {
            return view.mouseScrolled(event);
        }
        return false;
    }

    protected boolean handleActiveStudioSelectorKeyPressed(int keyCode, int scanCode, int modifiers) {
        ReKeyEvent event = currentKeyPressedEvent(keyCode, scanCode, modifiers, false);
        return event.finish(handleActiveStudioSelectorKeyPressed(event));
    }

    protected boolean handleActiveStudioSelectorKeyPressed(ReKeyEvent event) {
        if (activeStudioSelector != null && activeStudioSelector.visible) {
            return Widget.dispatchKeyPressed(activeStudioSelector, event);
        }
        ReSyncStudioView view = activeStudioView();
        if (view instanceof StudioSelectorView selectorView && selectorView.hasActiveStudioSelector()) {
            return view.keyPressed(event);
        }
        return false;
    }

    protected boolean handleActiveStudioSelectorCharTyped(char chr, int modifiers) {
        ReTextInputEvent event = currentTextInputEvent(chr, modifiers);
        return event.finish(handleActiveStudioSelectorTextInput(event));
    }

    protected boolean handleActiveStudioSelectorTextInput(ReTextInputEvent event) {
        if (activeStudioSelector != null && activeStudioSelector.visible) {
            return Widget.dispatchTextInput(activeStudioSelector, event);
        }
        ReSyncStudioView view = activeStudioView();
        if (view instanceof StudioSelectorView selectorView && selectorView.hasActiveStudioSelector()) {
            return view.textInput(event);
        }
        return false;
    }

    private ReMouseEvent studioMouseEvent(ReMouseEvent.Action action, double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return currentMouseEvent(action, mouseX, mouseY, button, deltaX, deltaY);
    }

    protected boolean handlePopupWidgetMouseClicked(ReMouseEvent event) {
        List<Widget> widgetSnapshot = new ArrayList<>(widgets);
        for (int i = widgetSnapshot.size() - 1; i >= 0; i--) {
            Widget widget = widgetSnapshot.get(i);
            if (widget instanceof PopupWidget popup && popup.isVisible() && Widget.dispatchMouseClicked(popup, event)) {
                return true;
            }
        }
        return false;
    }

    protected boolean handlePopupWidgetMouseReleased(ReMouseEvent event) {
        List<Widget> widgetSnapshot = new ArrayList<>(widgets);
        for (int i = widgetSnapshot.size() - 1; i >= 0; i--) {
            Widget widget = widgetSnapshot.get(i);
            if (widget instanceof PopupWidget popup && popup.isVisible() && Widget.dispatchMouseReleased(popup, event)) {
                return true;
            }
        }
        return false;
    }

    protected boolean handlePopupWidgetMouseDragged(ReMouseEvent event) {
        List<Widget> widgetSnapshot = new ArrayList<>(widgets);
        for (int i = widgetSnapshot.size() - 1; i >= 0; i--) {
            Widget widget = widgetSnapshot.get(i);
            if (widget instanceof PopupWidget popup && popup.isVisible() && Widget.dispatchMouseDragged(popup, event)) {
                return true;
            }
        }
        return false;
    }

    protected boolean handlePopupWidgetMouseScrolled(ReScrollEvent event) {
        List<Widget> widgetSnapshot = new ArrayList<>(widgets);
        for (int i = widgetSnapshot.size() - 1; i >= 0; i--) {
            Widget widget = widgetSnapshot.get(i);
            if (widget instanceof PopupWidget popup && popup.isVisible() && Widget.dispatchMouseScrolled(popup, event)) {
                return true;
            }
        }
        return false;
    }

    protected boolean handlePopupWidgetKeyPressed(ReKeyEvent event) {
        List<Widget> widgetSnapshot = new ArrayList<>(widgets);
        for (int i = widgetSnapshot.size() - 1; i >= 0; i--) {
            Widget widget = widgetSnapshot.get(i);
            if (widget instanceof PopupWidget popup && popup.isVisible() && Widget.dispatchKeyPressed(popup, event)) {
                return true;
            }
        }
        return false;
    }

    protected boolean handlePopupWidgetTextInput(ReTextInputEvent event) {
        List<Widget> widgetSnapshot = new ArrayList<>(widgets);
        for (int i = widgetSnapshot.size() - 1; i >= 0; i--) {
            Widget widget = widgetSnapshot.get(i);
            if (widget instanceof PopupWidget popup && popup.isVisible() && Widget.dispatchTextInput(popup, event)) {
                return true;
            }
        }
        return false;
    }

    protected ItemSelectorWidget showStudioSelector(List<String> labels, String selectedLabel, int selectorX, int selectorY, Consumer<ItemSelectorWidget> configure) {
        closeStudioSelector();
        ItemSelectorWidget[] selectorRef = new ItemSelectorWidget[1];
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(this)
            .size(220, 240)
            .dismissOnSelect(true)
            .onClose(() -> closeStudioSelector(selectorRef[0]))
            .build();
        selector.setLayer(900);
        selector.setPriority(30);
        selectorRef[0] = selector;
        if (configure != null) {
            configure.accept(selector);
        } else if (labels != null) {
            for (String label : labels) {
                selector.addItem(label, () -> {});
            }
        }
        if (selectedLabel != null) {
            selector.setSelectedItem(selectedLabel);
        }
        activeStudioSelector = selector;
        addDrawableChild(selector);
        int left = Math.clamp(selectorX, 8, Math.max(8, width - selector.getWidth() - 8));
        int top = Math.clamp(selectorY, 32, Math.max(32, height - selector.getHeight() - 20));
        selector.show(left, top);
        return selector;
    }

    protected ItemSelectorWidget showStudioSelector(ItemSelectorWidget selector, String selectedLabel, int selectorX, int selectorY) {
        closeStudioSelector();
        if (selector == null) {
            return null;
        }
        selector.onClose = () -> closeStudioSelector(selector);
        selector.setLayer(900);
        selector.setPriority(30);
        if (selectedLabel != null) {
            selector.setSelectedItem(selectedLabel);
        }
        activeStudioSelector = selector;
        addDrawableChild(selector);
        int left = Math.clamp(selectorX, 8, Math.max(8, width - selector.getWidth() - 8));
        int top = Math.clamp(selectorY, 32, Math.max(32, height - selector.getHeight() - 20));
        selector.show(left, top);
        return selector;
    }

    protected void closeStudioSelector() {
        closeStudioSelector(activeStudioSelector);
    }

    protected void closeStudioSelector(ItemSelectorWidget selector) {
        if (selector != null) {
            selector.onClose = null;
            selector.hide();
            remove(selector);
        }
        if (selector == activeStudioSelector) {
            activeStudioSelector = null;
            onStudioSelectorClosed();
        }
        setFocusedWidget(null);
    }

    protected void onStudioSelectorClosed() {
    }

    public boolean hasActiveStudioSelector() {
        return activeStudioSelector != null && activeStudioSelector.visible;
    }

    public ItemSelectorWidget activeStudioSelector() {
        return hasActiveStudioSelector() ? activeStudioSelector : null;
    }

    protected List<AnimatedWidget> visibleStudioHeaderButtons() {
        List<AnimatedWidget> buttons = new ArrayList<>();
        if (activeStudioDocument == null) {
            return buttons;
        }
        if (activeStudioView() == null) {
            buttons.addAll(headerButtons);
        } else {
            buttons.addAll(activeViewHeaderButtons);
        }
        return buttons;
    }

    protected void renderStudioEmptyMessage(IDrawContext context, int mouseX, int mouseY) {
        if (studioEmptyMessage == null) {
            return;
        }
        int top = 42;
        int bottom = studioContentBrowserAffectsLayout() ? studioContentBrowser.getY() - 8 : height - 8;
        int centerX = width / 2;
        int centerY = top + Math.max(0, bottom - top) / 2;
        studioEmptyMessage.setPosition(centerX - studioEmptyMessage.getWidth() / 2, centerY - studioEmptyMessage.getHeight() / 2);
        studioEmptyMessage.render(context, mouseX, mouseY, 0);
    }

    protected void layoutStudioHeaderButtons() {
        if (!studioMode) {
            return;
        }
        reconcileFullEditorHeaderButtons();
        header().build();
    }

    protected void reconcileFullEditorHeaderButtons() {
        ReSyncStudioView view = activeStudioView();
        if (!(view instanceof ScreenBackedStudioView screenView) || !screenView.initialized()) {
            return;
        }
        List<AnimatedWidget> buttons = screenView.headerButtons();
        if (!sameHeaderButtons(activeViewHeaderButtons, buttons)) {
            activeViewHeaderButtons.clear();
            activeViewHeaderButtons.addAll(buttons);
            rebuildStudioHeaderButtons();
            return;
        }
        if (!buttons.isEmpty() && !headerContainsButtons(visibleStudioHeaderButtons())) {
            rebuildStudioHeaderButtons();
        }
    }

    protected boolean sameHeaderButtons(List<AnimatedWidget> current, List<AnimatedWidget> expected) {
        if (current.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i) != expected.get(i)) {
                return false;
            }
        }
        return true;
    }

    protected boolean headerContainsButtons(List<AnimatedWidget> buttons) {
        for (AnimatedWidget button : buttons) {
            if (button != null && !header().leftButtons.contains(button) && !header().rightButtons.contains(button)) {
                return false;
            }
        }
        return true;
    }

    protected void rebuildStudioHeaderButtons() {
        if (!studioMode) {
            return;
        }
        header().reset();
        for (AnimatedWidget button : headerButtons) {
            if (button != null) {
                button.visible = false;
            }
        }
        for (AnimatedWidget button : activeViewHeaderButtons) {
            if (button != null) {
                button.visible = false;
            }
        }
        List<AnimatedWidget> buttons = visibleStudioHeaderButtons();
        for (AnimatedWidget button : buttons) {
            if (button != null) {
                button.visible = true;
                button.entranceAnimationEnabled = false;
                header().addRight(button);
            }
        }
        if (activeStudioView() == null) {
            syncDebugHeaderVisibility();
        }
        header().build();
    }

    protected void renderStudioOverlays(IDrawContext context, int mouseX, int mouseY, float delta) {
        updateStudioPresence(mouseX, mouseY);
        if (studioMode) {
            updateStudioTabStates();
            renderDesktopChromeBackground(context, mouseX, mouseY, delta);
            updateFullEditorHeaderClose();
            layoutStudioHeaderButtons();
            for (AnimatedWidget button : header().leftButtons) {
                if (button != null && button.visible) {
                    button.render(context, mouseX, mouseY, delta);
                }
            }
            for (AnimatedWidget button : header().rightButtons) {
                if (button != null && button.visible) {
                    button.render(context, mouseX, mouseY, delta);
                }
            }
            if (studioTabsManager != null) {
                studioTabsManager.setPosition(10, 5 + header().getOffsetY());
                studioTabsManager.render(context, mouseX, mouseY, delta);
            }
        }

        for (Widget widget : hudWidgets) {
            widget.render(context, mouseX, mouseY, delta);
        }

        if (studioMode && shouldRenderStudioContentBrowser()) {
            studioContentBrowser.layoutInScreen();
            renderStudioPanel(studioContentBrowser.sidePanel(), context, mouseX, mouseY, delta);
        }
        renderAdditionalStudioPanels(context, mouseX, mouseY, delta);
        if (activeStudioDocument != null && (activeStudioView() == null || activeStudioViewUsesResourcePanel()) && studioResourceStudioPanel != null) {
            renderStudioPanel(studioResourceStudioPanel, context, mouseX, mouseY, delta);
        }
        renderActiveResourceSelectorOverlay(context, mouseX, mouseY, delta);

        for (Widget widget : widgets) {
            if (widget instanceof ContextMenuWidget) {
                widget.render(context, mouseX, mouseY, delta);
            }
        }
        renderStudioResourceDrag(context, mouseX, mouseY, delta);
        renderStudioCollaboration(context);
        renderCollaborationChatInput(context, mouseX, mouseY, delta);

        for (Widget widget : hudWidgets) {
            if (widget instanceof AnimatedWidget animated) {
                animated.renderHintOverlay(context);
            }
        }
        if (studioMode && studioContentBrowser != null && !studioContentBrowser.isTemporarilyHidden()) {
            studioContentBrowser.sidePanel().container().renderHintOverlay(context);
        }
        renderHeaderHintOverlays(context);
        for (Widget widget : widgets) {
            if (widget instanceof ContextMenuWidget menu && menu.isVisible()) {
                menu.renderHintOverlay(context);
            }
        }
        if (!isOverlayPassDeferred()) {
            Screen viewScreen = activeStudioViewScreen();
            if (viewScreen != null && viewScreen != this) {
                viewScreen.renderOverlayPass(context, mouseX, mouseY, delta);
            }
        }
        if (!isOverlayPassDeferred()) {
            context.pushScissorState();
            context.clearScissor();
            try {
                renderStudioCollaborationOverlay(context);
            } finally {
                context.popScissorState();
            }
        }
    }

    protected void renderStudioCollaborationOverlay(IDrawContext context) {
    }

    private void updateStudioPresence(int mouseX, int mouseY) {
        if (!studioMode) {
            return;
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return;
        }
        String type = activeStudioDocument != null ? safeStudioText(activeStudioDocument.type()) : "";
        String id = activeStudioDocument != null ? safeStudioText(activeStudioDocument.id()) : "";
        String document = type + '\u0000' + id;
        boolean typing = collaborationChatInput != null && !collaborationChatInput.getText().isBlank();
        long now = System.currentTimeMillis();
        if (mouseX == lastPresenceX && mouseY == lastPresenceY && document.equals(lastPresenceDocument)
            && typing == lastPresenceTyping && now - lastPresenceAt < 1000L) {
            return;
        }
        if (document.equals(lastPresenceDocument) && typing == lastPresenceTyping && now - lastPresenceAt < 35L) {
            return;
        }
        lastPresenceX = mouseX;
        lastPresenceY = mouseY;
        lastPresenceDocument = document;
        lastPresenceTyping = typing;
        lastPresenceAt = now;
        ReSyncStudioView view = activeStudioView();
        ReSyncFlowClient client = manager.existingFlowClient(studioServerId());
        if (client == null) {
            return;
        }
        client.collaboration().publishPresence(type, id,
            view != null ? TaskIdentities.typeName(view) : "",
            width > 0 ? (double) mouseX / width : 0.0, height > 0 ? (double) mouseY / height : 0.0,
            activeStudioDocument != null, typing);
    }

    private void renderStudioCollaboration(IDrawContext context) {
        FlowManager manager = FlowManager.getInstance();
        if (!studioMode || manager == null) {
            return;
        }
        ReSyncFlowClient client = manager.existingFlowClient(studioServerId());
        if (client == null) {
            return;
        }
        ensureCollaborationChat(client.collaboration());
        ReSyncCollaborationClient collaboration = client.collaboration();
        List<ReSyncCollaborationClient.Presence> collaborators = collaboration.snapshot();
        renderStudioCollaborators(context, collaboration, collaborators);
        renderRemoteCursors(context, collaboration, collaborators);
        renderRemoteResourceChange(context, collaboration);
    }

    private void ensureCollaborationChat(ReSyncCollaborationClient collaboration) {
        collaborationOverlay.bind(collaboration, this::receiveCollaborationMessage);
    }

    private void receiveCollaborationMessage(ReSyncCollaborationClient.Message message) {
        if (message == null || collaborationOverlay.service() == null || !studioMode) {
            return;
        }
        ReSyncCollaborationClient.Presence presence = collaborationOverlay.service().presence(message.authorSessionId());
        boolean cursorVisible = activeStudioDocument != null && presence != null && presence.active()
            && Objects.equals(activeStudioDocument.type(), message.resourceType())
            && Objects.equals(activeStudioDocument.id(), message.resourceId());
        if (cursorVisible) {
            return;
        }
        String name = message.author() != null
            ? CollaborationOverlay.compactName(message.author().displayName(), 100) : "Collaborator";
        new Notification(name, message.message(), Notification.Type.INFO);
        if (studioContentBrowser != null && message.resourceType() != null && !message.resourceType().isBlank()
            && message.resourceId() != null && !message.resourceId().isBlank()) {
            studioContentBrowser.highlightCollaborationChat(message.resourceType(), message.resourceId(),
                presence != null ? collaborationColor(presence) : message.color());
        }
    }

    private void renderCollaborationChatInput(IDrawContext context, int mouseX, int mouseY, float delta) {
        if (collaborationChatInput == null) {
            return;
        }
        collaborationChatInput.setWidth(Math.clamp(width - 40, 140, 280));
        collaborationChatInput.setPosition((width - collaborationChatInput.getWidth()) / 2, Math.max(4, height - 30));
        collaborationChatInput.render(context, mouseX, mouseY, delta);
    }

    private void renderStudioCollaborators(IDrawContext context, ReSyncCollaborationClient collaboration,
                                           List<ReSyncCollaborationClient.Presence> collaborators) {
        int x = width - studioHeaderRightReserve() - 10;
        int shown = 0;
        for (int i = collaborators.size() - 1; i >= 0; i--) {
            ReSyncCollaborationClient.Presence presence = collaborators.get(i);
            if (collaboration.isSelf(presence) || presence.identity() == null) {
                continue;
            }
            String slot = "header:" + presence.sessionId();
            IconButton badge = collaborationBadge(slot, presence, presence.identity(), collaborationColor(presence), 92);
            x -= badge.getWidth();
            if (x < Math.max(width / 2, 200)) {
                break;
            }
            badge.setPosition(x, 5);
            badge.render(context, 0, 0, 0);
            renderCollaborationMessages(context, slot, presence, badge, true, 160);
            renderCollaborationTyping(context, slot, presence, badge, 160);
            x -= 4;
            shown++;
            if (shown == 5) {
                break;
            }
        }
    }

    private void renderRemoteCursors(IDrawContext context, ReSyncCollaborationClient collaboration,
                                     List<ReSyncCollaborationClient.Presence> collaborators) {
        if (activeStudioDocument == null || rendersWorkspaceCursors()) {
            studioCursorPositions.clear();
            studioCursorDocumentKey = null;
            return;
        }
        if (!Objects.equals(studioCursorDocumentKey, activeStudioDocument.key())) {
            studioCursorPositions.clear();
            studioCursorDocumentKey = activeStudioDocument.key();
        }
        Set<String> activeSessions = new HashSet<>();
        for (ReSyncCollaborationClient.Presence presence : collaborators) {
            if (collaboration.isSelf(presence) || !presence.active() || presence.identity() == null
                || !Objects.equals(activeStudioDocument.type(), presence.resourceType())
                || !Objects.equals(activeStudioDocument.id(), presence.resourceId())) {
                continue;
            }
            activeSessions.add(presence.sessionId());
            CursorPosition position = studioCursorPositions.computeIfAbsent(presence.sessionId(), ignored -> new CursorPosition());
            position.moveTo(presence.x(), presence.y());
            int cursorX = Math.clamp((int) Math.round(position.x * width), 0, Math.max(0, width - 1));
            int cursorY = Math.clamp((int) Math.round(position.y * height), 0, Math.max(0, height - 1));
            renderCollaborationCursor(context, presence, cursorX, cursorY);
        }
        studioCursorPositions.keySet().retainAll(activeSessions);
    }

    protected boolean rendersWorkspaceCursors() {
        return false;
    }

    protected void renderCollaborationCursor(IDrawContext context, ReSyncCollaborationClient.Presence presence, int cursorX, int cursorY) {
        collaborationOverlay.renderCursor(context, presence, cursorX, cursorY, width, height);
    }

    protected CollaborationOverlay collaborationOverlay() {
        return collaborationOverlay;
    }

    protected void renderCollaborationMessages(IDrawContext context, String slot,
                                               ReSyncCollaborationClient.Presence presence,
                                               AnimatedWidget anchor, boolean below, int maxMessageWidth) {
        collaborationOverlay.renderMessages(context, slot, presence, anchor,
            below ? CollaborationOverlay.Order.BELOW : CollaborationOverlay.Order.ABOVE,
            maxMessageWidth, width, height);
    }

    protected void renderCollaborationTyping(IDrawContext context, String slot,
                                             ReSyncCollaborationClient.Presence presence,
                                             AnimatedWidget anchor, int maxMessageWidth) {
        collaborationOverlay.renderTyping(context, slot, presence, anchor, maxMessageWidth);
    }

    protected ReSyncCollaborationClient.Presence collaborationPresence(String sessionId) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || sessionId == null) {
            return null;
        }
        ReSyncFlowClient client = manager.existingFlowClient(studioServerId());
        if (client == null) {
            return null;
        }
        return client.collaboration().snapshot().stream()
            .filter(presence -> Objects.equals(sessionId, presence.sessionId()))
            .findFirst()
            .orElse(null);
    }

    private void renderRemoteResourceChange(IDrawContext context, ReSyncCollaborationClient collaboration) {
        if (activeStudioDocument == null) {
            return;
        }
        ReSyncCollaborationClient.ResourceChange change = collaboration.resourceChange(activeStudioDocument.type(), activeStudioDocument.id());
        if (!shouldRenderRemoteResourceChange(collaboration, change, System.currentTimeMillis())) {
            return;
        }
        String author = change.author() != null ? safeStudioText(change.author().displayName()) : "Collaborator";
        String message = change.deleted() ? author + " Deleted This Resource" : author + " Updated This Resource";
        if (collaborationChangeBadge == null) {
            collaborationChangeBadge = new IconButton.Builder()
                .label(message)
                .size(tr.getWidth(message) + 16, 20)
                .centered(true)
                .autoWidthOnTextChange(true)
                .animateLayout(false)
                .entranceCorner(AnimatedWidget.EntranceCorner.CENTER)
                .active(false)
                .build();
            collaborationChangeBadge.setCursorHoverReactive(false);
        }
        collaborationChangeBadge.setMessage(message);
        collaborationChangeBadge.accentType = change.deleted() ? ThemeManager.getAccent("danger") : ThemeManager.getAccent("nice");
        collaborationChangeBadge.setPosition(Math.max(8, (width - collaborationChangeBadge.getWidth()) / 2), 29);
        collaborationChangeBadge.render(context, 0, 0, 0);
    }

    static boolean shouldRenderRemoteResourceChange(ReSyncCollaborationClient collaboration,
                                                     ReSyncCollaborationClient.ResourceChange change, long now) {
        return collaboration != null && change != null && change.authorSessionId() != null
            && !change.authorSessionId().isBlank() && !collaboration.isOwnChange(change)
            && now - change.changedAt() <= 8000L;
    }

    protected Identifier collaborationAvatar(ReSyncCollaborationClient.Presence presence) {
        return collaborationOverlay.avatar(presence);
    }

    protected Identifier collaborationAvatar(ReSyncCollaborationClient.Identity identity) {
        return collaborationOverlay.avatar(identity);
    }

    protected int collaborationColor(ReSyncCollaborationClient.Presence presence) {
        return collaborationOverlay.color(presence);
    }

    protected Accent collaborationAccent(ReSyncCollaborationClient.Presence presence) {
        return collaborationOverlay.accent(presence);
    }

    protected IconButton collaborationBadge(String slot, ReSyncCollaborationClient.Presence presence,
                                             ReSyncCollaborationClient.Identity identity, int color, int maxNameWidth) {
        return collaborationOverlay.badge(slot, presence, identity, color, maxNameWidth);
    }

    @Override
    public void removed() {
        studioResourceOpenVersion++;
        studioResourceOpenIntents.clear();
        studioResourcePanelResources.clear();
        if (studioContentBrowser != null) studioContentBrowser.dispose();
        collaborationOverlay.close();
        dismissCollaborationChat(false);
        FlowManager manager = FlowManager.getInstance();
        if (studioMode && manager != null && manager.isFlowClientConnected(studioServerId())) {
            ReSyncFlowClient client = manager.existingFlowClient(studioServerId());
            if (client != null) {
                client.collaboration().publishPresence("", "", "", 0.0, 0.0, false, false);
            }
        }
        super.removed();
    }

    private void renderStudioResourceDrag(IDrawContext context, int mouseX, int mouseY, float delta) {
        if (studioResourceDragWidget == null) {
            return;
        }
        if (studioResourceDrag != null) {
            int targetX = mouseX - studioResourceDragGrabX;
            int targetY = mouseY - studioResourceDragGrabY;
            studioResourceDragWidget.setPosition(targetX, targetY);
            studioResourceDragWidget.setWidth(studioResourceDragDetachedWidth);
            studioResourceDragWidget.setHeight(Math.max(18, studioResourceDragSourceHeight));
        }
        studioResourceDragWidget.render(context, mouseX, mouseY, delta);
        if (studioResourceDragDestination == null) {
            return;
        }
        StudioResourceDragDestination destination = studioResourceDragDestination;
        boolean arrived = Math.abs(studioResourceDragWidget.getX() - destination.x()) <= 1
            && Math.abs(studioResourceDragWidget.getY() - destination.y()) <= 1
            && Math.abs(studioResourceDragWidget.getWidth() - destination.width()) <= 1
            && Math.abs(studioResourceDragWidget.getHeight() - destination.height()) <= 1;
        if (!arrived) {
            return;
        }
        if (!destination.close()) {
            clearStudioResourceDragWidget();
            studioResourceDragDestination = null;
        } else if (!studioResourceDragWidget.isClosingAnimationActive()) {
            studioResourceDragWidget.startClosingAnimation(AnimatedWidget.ClosingAnchor.CENTER);
        } else if (studioResourceDragWidget.isClosingAnimationFinished()) {
            clearStudioResourceDragWidget();
            studioResourceDragDestination = null;
        }
    }

    protected void updateFullEditorHeaderClose() {
        boolean contentBrowserClosed = studioContentBrowser == null || studioContentBrowser.isSlideOutFinished();
        ReSyncStudioView view = activeStudioView();
        boolean editorClosed = !(view instanceof ScreenBackedStudioView screenView)
            || screenView.closeAnimationFinished();
        if (fullEditorHeaderCloseRequested && isTopHeaderAnimationFinished() && contentBrowserClosed && editorClosed) {
            fullEditorHeaderCloseRequested = false;
            closeFullEditorStudioScreen();
        }
    }

    protected boolean shouldRenderStudioContentBrowser() {
        return studioContentBrowser != null && (!studioContentBrowser.isTemporarilyHidden() || !studioContentBrowser.isSlideOutFinished());
    }

    protected void renderAdditionalStudioPanels(IDrawContext context, int mouseX, int mouseY, float delta) {
    }

    protected void renderHeaderHintOverlays(IDrawContext context) {
        if (!studioMode) {
            return;
        }
        for (AnimatedWidget button : header().leftButtons) {
            if (button != null && button.visible) {
                button.renderHintOverlay(context);
            }
        }
        for (AnimatedWidget button : header().rightButtons) {
            if (button != null && button.visible) {
                button.renderHintOverlay(context);
            }
        }
    }

    protected void renderStudioDocumentPreview(IDrawContext context) {
        if (!studioMode || activeStudioDocument == null || activeStudioDocument.graph() != null) {
            return;
        }
        int top = 42;
        int bottom = studioContentBrowserAffectsLayout() ? studioContentBrowser.getY() - 8 : height - 8;
        int left = studioContentBrowserWidth() + 12;
        int right = width - 12 - (studioResourcePanel != null ? studioResourcePanel.layoutWidth(0) : 0);
        int areaWidth = Math.max(20, right - left);
        int areaHeight = Math.max(20, bottom - top);
        ReSyncStudioView view = activeStudioView();
        context.pushScissorState();
        try {
            context.enableScissor(left, top, left + areaWidth, top + areaHeight);
            if (view != null) {
                view.renderPreview(context, left, top, areaWidth, areaHeight);
            } else if (ReSyncResourceDragPayload.WORLD.equals(activeStudioDocument.type())) {
                int text = ThemeManager.getColor(ThemeColor.text);
                context.drawText(activeStudioDocument.title(), left + 12, top + 12, text, false);
            }
        } finally {
            context.popScissorState();
        }
    }

    protected void renderActiveResourceSelectorOverlay(IDrawContext context, int mouseX, int mouseY, float delta) {
        ReSyncStudioView view = activeStudioView();
        if (view instanceof StudioOverlayView overlayView) {
            overlayView.renderStudioOverlay(context, mouseX, mouseY, delta);
        }
    }

    protected void syncDebugHeaderVisibility() {
    }

    protected void refreshStudioCatalogDocuments() {
        for (StudioDocument document : studioDocuments) {
            if (document.view() instanceof StudioCatalogRefreshView refreshView) {
                refreshView.onStudioCatalogRefreshed();
            }
        }
    }

    protected void refreshStudioWorldDocuments() {
        for (StudioDocument document : studioDocuments) {
            if (document.view() instanceof WorldStudioDocumentView worldView) {
                worldView.refreshWorlds();
            }
        }
    }

    protected void handleStudioWorldOperationResult(WorldOperationResult result) {
        for (StudioDocument document : studioDocuments) {
            if (document.view() instanceof WorldStudioDocumentView worldView) {
                worldView.handleWorldOperationResult(result);
            }
        }
    }

    protected String studioResourceIconPath(String type, String id) {
        return switch (type) {
            case AUTOMATION_DOCUMENT_TYPE -> "resources.png";
            case ReSyncResourceDragPayload.FUNCTION -> "json.png";
            case ReSyncResourceDragPayload.COMMAND -> "terminal.png";
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> "content.png";
            case ReSyncResourceDragPayload.GUI -> "fullPanel.png";
            case ReSyncResourceDragPayload.SCOREBOARD -> "panel.png";
            case ReSyncResourceDragPayload.TAB -> "topPanel.png";
            case ReSyncResourceDragPayload.CHAT -> "chat.png";
            case ReSyncResourceDragPayload.COMPONENT_BUILDER -> "item.png";
            case ReSyncResourceDragPayload.MOTD_PROFILE -> "hi.png";
            case ReSyncResourceDragPayload.MESSAGE_RULE -> "edit.png";
            case ReSyncResourceDragPayload.RECIPE_DEFINITION -> "crafting.png";
            case ReSyncResourceDragPayload.TEXT_TEMPLATE -> "text.png";
            case ReSyncResourceDragPayload.ADVANCEMENT_TREE -> "advancement.png";
            case ReSyncResourceDragPayload.DIALOG -> "VanillaButton.png";
            case ReSyncResourceDragPayload.TRADE_PROFILE -> "trade.png";
            case ReSyncResourceDragPayload.NPC_DEFINITION -> "steve.png";
            case ReSyncResourceDragPayload.WORLDGEN -> "map.png";
            case ReSyncResourceDragPayload.WORLD -> "earth.png";
            case ReSyncResourceDragPayload.VARIABLE_DEFINITION -> "snippets.png";
            case ReSyncResourceDragPayload.TIMER_DEFINITION -> "history.png";
            case ReSyncResourceDragPayload.SCHEDULE_DEFINITION -> "calendar.png";
            default -> "flow.png";
        };
    }

    protected String safeStudioText(String value) {
        return value == null ? "" : value;
    }

    public static class History<T> {
        private final Supplier<T> snapshotSupplier;
        private final Consumer<T> restoreConsumer;
        private final int limit;
        private final Object stateLock = new Object();
        private HistoryState<T> state = new HistoryState<>(HistoryStack.empty(), HistoryStack.empty(), null, false, 0, 0,
            Map.of());
        private long generation = 1L;
        private volatile boolean restoring;

        public History(Supplier<T> snapshotSupplier, Consumer<T> restoreConsumer, int limit) {
            this.snapshotSupplier = snapshotSupplier;
            this.restoreConsumer = restoreConsumer;
            this.limit = Math.max(1, limit);
        }

        public void capture() {
            synchronized (stateLock) {
                if (restoring) {
                    return;
                }
                HistoryState<T> current = state;
                T snapshot = snapshotSupplier.get();
                T savedSnapshot = current.savedSnapshot();
                boolean savedSnapshotInitialized = current.savedSnapshotInitialized();
                if (!savedSnapshotInitialized) {
                    savedSnapshot = snapshot;
                    savedSnapshotInitialized = true;
                }
                HistoryStack<T> undoStack = current.undoStack().push(snapshot);
                if (undoStack.size() > limit) {
                    undoStack = undoStack.dropOldest();
                }
                int savedCursor = current.savedCursor();
                if (!current.redoStack().isEmpty() && savedCursor > current.cursor()) {
                    savedCursor = Integer.MIN_VALUE;
                }
                setState(new HistoryState<>(undoStack, HistoryStack.empty(), savedSnapshot, savedSnapshotInitialized,
                    current.cursor() + 1, savedCursor, current.savePoints()));
            }
        }

        public void clear() {
            synchronized (stateLock) {
                setState(new HistoryState<>(HistoryStack.empty(), HistoryStack.empty(), snapshotSupplier.get(), true, 0, 0,
                    Map.of()));
            }
        }

        public void invalidate() {
            synchronized (stateLock) {
                setState(new HistoryState<>(HistoryStack.empty(), HistoryStack.empty(), null, false, 0, 0, Map.of()));
            }
        }

        public void clearWithoutSnapshot() {
            invalidate();
        }

        public void resetSaved(T preparedSnapshot) {
            if (preparedSnapshot == null) {
                return;
            }
            synchronized (stateLock) {
                setState(new HistoryState<>(HistoryStack.empty(), HistoryStack.empty(), preparedSnapshot, true, 0, 0,
                    Map.of()));
            }
        }

        public boolean isDirty() {
            synchronized (stateLock) {
                return state.cursor() != state.savedCursor();
            }
        }

        public boolean isDirty(BiPredicate<T, T> equality) {
            synchronized (stateLock) {
                if (equality == null || !state.savedSnapshotInitialized()) {
                    return state.cursor() != state.savedCursor();
                }
                return !equality.test(snapshotSupplier.get(), state.savedSnapshot());
            }
        }

        public void markSaved() {
            synchronized (stateLock) {
                HistoryState<T> current = state;
                setState(new HistoryState<>(current.undoStack(), current.redoStack(), snapshotSupplier.get(), true,
                    current.cursor(), current.cursor(), Map.of()));
            }
        }

        public void markSaving(long sequence) {
            if (sequence <= 0L) {
                return;
            }
            synchronized (stateLock) {
                HistoryState<T> current = state;
                Map<Long, SavePoint<T>> savePoints = new HashMap<>(current.savePoints());
                savePoints.put(sequence, new SavePoint<>(snapshotSupplier.get(), current.cursor()));
                if (savePoints.size() > 32) {
                    savePoints.remove(savePoints.keySet().stream().min(Long::compareTo).orElse(sequence));
                }
                setState(new HistoryState<>(current.undoStack(), current.redoStack(), current.savedSnapshot(),
                    current.savedSnapshotInitialized(), current.cursor(), current.savedCursor(), Map.copyOf(savePoints)));
            }
        }

        public void markSaved(long sequence) {
            if (sequence <= 0L) {
                markSaved();
                return;
            }
            synchronized (stateLock) {
                HistoryState<T> current = state;
                SavePoint<T> point = current.savePoints().get(sequence);
                if (point == null) {
                    return;
                }
                Map<Long, SavePoint<T>> savePoints = new HashMap<>(current.savePoints());
                savePoints.remove(sequence);
                savePoints.keySet().removeIf(value -> value <= sequence);
                setState(new HistoryState<>(current.undoStack(), current.redoStack(), point.snapshot(), true,
                    current.cursor(), point.cursor(), Map.copyOf(savePoints)));
            }
        }

        public void discardChanges() {
            synchronized (stateLock) {
                if (!state.savedSnapshotInitialized()) {
                    setState(new HistoryState<>(HistoryStack.empty(), HistoryStack.empty(), null, false, 0, 0, Map.of()));
                    return;
                }
                restoring = true;
                try {
                    T savedSnapshot = state.savedSnapshot();
                    restoreConsumer.accept(savedSnapshot);
                    setState(new HistoryState<>(HistoryStack.empty(), HistoryStack.empty(), savedSnapshot, true, 0, 0, Map.of()));
                } finally {
                    restoring = false;
                }
            }
        }

        public void rebase(UnaryOperator<T> rebaser) {
            if (rebaser == null) {
                return;
            }
            RebaseState<T> captured = captureRebaseState();
            PreparedRebase<T> prepared = prepareRebase(captured, rebaser);
            if (prepared != null) {
                commitRebase(captured, prepared);
            }
        }

        public boolean undo() {
            synchronized (stateLock) {
                if (state.undoStack().isEmpty() || restoring) {
                    return false;
                }
                restoring = true;
                try {
                    HistoryState<T> current = state;
                    HistoryStack.Pop<T> popped = current.undoStack().pop();
                    HistoryStack<T> redoStack = current.redoStack().push(snapshotSupplier.get());
                    if (redoStack.size() > limit) {
                        redoStack = redoStack.dropOldest();
                    }
                    setState(new HistoryState<>(popped.rest(), redoStack, current.savedSnapshot(),
                        current.savedSnapshotInitialized(), current.cursor() - 1, current.savedCursor(), current.savePoints()));
                    restoreConsumer.accept(popped.value());
                    return true;
                } finally {
                    restoring = false;
                }
            }
        }

        public boolean redo() {
            synchronized (stateLock) {
                if (state.redoStack().isEmpty() || restoring) {
                    return false;
                }
                restoring = true;
                try {
                    HistoryState<T> current = state;
                    HistoryStack.Pop<T> popped = current.redoStack().pop();
                    HistoryStack<T> undoStack = current.undoStack().push(snapshotSupplier.get());
                    if (undoStack.size() > limit) {
                        undoStack = undoStack.dropOldest();
                    }
                    setState(new HistoryState<>(undoStack, popped.rest(), current.savedSnapshot(),
                        current.savedSnapshotInitialized(), current.cursor() + 1, current.savedCursor(), current.savePoints()));
                    restoreConsumer.accept(popped.value());
                    return true;
                } finally {
                    restoring = false;
                }
            }
        }

        public boolean isRestoring() {
            return restoring;
        }

        public T peekUndo() {
            synchronized (stateLock) {
                return state.undoStack().peek();
            }
        }

        public T peekRedo() {
            synchronized (stateLock) {
                return state.redoStack().peek();
            }
        }

        public boolean undoPrepared(T expected, Consumer<T> preparedRestore) {
            synchronized (stateLock) {
                if (restoring || expected == null || preparedRestore == null || state.undoStack().isEmpty()
                    || state.undoStack().peek() != expected) {
                    return false;
                }
                restoring = true;
                try {
                    HistoryState<T> current = state;
                    HistoryStack.Pop<T> popped = current.undoStack().pop();
                    HistoryStack<T> redoStack = current.redoStack().push(snapshotSupplier.get());
                    if (redoStack.size() > limit) {
                        redoStack = redoStack.dropOldest();
                    }
                    preparedRestore.accept(popped.value());
                    setState(new HistoryState<>(popped.rest(), redoStack, current.savedSnapshot(),
                        current.savedSnapshotInitialized(), current.cursor() - 1, current.savedCursor(), current.savePoints()));
                    return true;
                } finally {
                    restoring = false;
                }
            }
        }

        public boolean redoPrepared(T expected, Consumer<T> preparedRestore) {
            synchronized (stateLock) {
                if (restoring || expected == null || preparedRestore == null || state.redoStack().isEmpty()
                    || state.redoStack().peek() != expected) {
                    return false;
                }
                restoring = true;
                try {
                    HistoryState<T> current = state;
                    HistoryStack.Pop<T> popped = current.redoStack().pop();
                    HistoryStack<T> undoStack = current.undoStack().push(snapshotSupplier.get());
                    if (undoStack.size() > limit) {
                        undoStack = undoStack.dropOldest();
                    }
                    preparedRestore.accept(popped.value());
                    setState(new HistoryState<>(undoStack, popped.rest(), current.savedSnapshot(),
                        current.savedSnapshotInitialized(), current.cursor() + 1, current.savedCursor(), current.savePoints()));
                    return true;
                } finally {
                    restoring = false;
                }
            }
        }

        public boolean canUndo() {
            synchronized (stateLock) {
                return !state.undoStack().isEmpty();
            }
        }

        public boolean canRedo() {
            synchronized (stateLock) {
                return !state.redoStack().isEmpty();
            }
        }

        public RebaseState<T> captureRebaseState() {
            synchronized (stateLock) {
                return new RebaseState<>(this, state, generation);
            }
        }

        public PreparedRebase<T> prepareRebase(UnaryOperator<T> rebaser) {
            return prepareRebase(captureRebaseState(), rebaser);
        }

        public PreparedRebase<T> prepareRebase(RebaseState<T> captured, UnaryOperator<T> rebaser) {
            if (captured == null || rebaser == null || captured.owner() != this) {
                return null;
            }
            HistoryState<T> current = captured.state();
            HistoryStack<T> undoStack = current.undoStack().map(rebaser);
            HistoryStack<T> redoStack = current.redoStack().map(rebaser);
            T savedSnapshot = current.savedSnapshotInitialized() ? rebaser.apply(current.savedSnapshot()) : null;
            Map<Long, SavePoint<T>> savePoints;
            if (current.savePoints().isEmpty()) {
                savePoints = Map.of();
            } else {
                Map<Long, SavePoint<T>> transformed = new HashMap<>(current.savePoints().size());
                current.savePoints().forEach((sequence, point) ->
                    transformed.put(sequence, new SavePoint<>(rebaser.apply(point.snapshot()), point.cursor())));
                savePoints = Map.copyOf(transformed);
            }
            HistoryState<T> preparedState = new HistoryState<>(undoStack, redoStack, savedSnapshot,
                current.savedSnapshotInitialized(), current.cursor(), current.savedCursor(), savePoints);
            return new PreparedRebase<>(this, captured, preparedState);
        }

        public boolean commitRebase(PreparedRebase<T> prepared) {
            return prepared != null && commitRebase(prepared.captured(), prepared);
        }

        public boolean commitRebase(RebaseState<T> captured, PreparedRebase<T> prepared) {
            if (captured == null || prepared == null || captured.owner() != this || prepared.owner() != this
                || prepared.captured() != captured) {
                return false;
            }
            synchronized (stateLock) {
                if (restoring || generation != captured.generation() || state != captured.state()) {
                    return false;
                }
                setState(prepared.state());
                return true;
            }
        }

        public long generation() {
            synchronized (stateLock) {
                return generation;
            }
        }

        private void setState(HistoryState<T> next) {
            state = next;
            generation = generation == Long.MAX_VALUE ? 1L : generation + 1L;
        }

        public static final class RebaseState<T> {
            private final History<T> owner;
            private final HistoryState<T> state;
            private final long generation;

            private RebaseState(History<T> owner, HistoryState<T> state, long generation) {
                this.owner = owner;
                this.state = state;
                this.generation = generation;
            }

            public long generation() {
                return generation;
            }

            private History<T> owner() {
                return owner;
            }

            private HistoryState<T> state() {
                return state;
            }
        }

        public static final class PreparedRebase<T> {
            private final History<T> owner;
            private final RebaseState<T> captured;
            private final HistoryState<T> state;

            private PreparedRebase(History<T> owner, RebaseState<T> captured, HistoryState<T> state) {
                this.owner = owner;
                this.captured = captured;
                this.state = state;
            }

            private History<T> owner() {
                return owner;
            }

            private RebaseState<T> captured() {
                return captured;
            }

            private HistoryState<T> state() {
                return state;
            }
        }

        private record HistoryState<T>(HistoryStack<T> undoStack, HistoryStack<T> redoStack, T savedSnapshot,
                                       boolean savedSnapshotInitialized, int cursor, int savedCursor,
                                       Map<Long, SavePoint<T>> savePoints) {
        }

        private static final class HistoryStack<T> {
            private final StackNode<T> head;
            private final int size;

            private HistoryStack(StackNode<T> head, int size) {
                this.head = head;
                this.size = size;
            }

            private static <T> HistoryStack<T> empty() {
                return new HistoryStack<>(null, 0);
            }

            private HistoryStack<T> push(T value) {
                return new HistoryStack<>(new StackNode<>(value, head), size + 1);
            }

            private Pop<T> pop() {
                return new Pop<>(head.value(), new HistoryStack<>(head.next(), size - 1));
            }

            private T peek() {
                return head == null ? null : head.value();
            }

            private boolean isEmpty() {
                return size == 0;
            }

            private int size() {
                return size;
            }

            private HistoryStack<T> dropOldest() {
                if (size <= 1) {
                    return empty();
                }
                List<T> values = new ArrayList<>(size - 1);
                for (StackNode<T> node = head; node.next() != null; node = node.next()) {
                    values.add(node.value());
                }
                return fromHeadOrder(values);
            }

            private HistoryStack<T> map(UnaryOperator<T> mapper) {
                if (isEmpty()) {
                    return this;
                }
                List<T> values = new ArrayList<>(size);
                for (StackNode<T> node = head; node != null; node = node.next()) {
                    values.add(mapper.apply(node.value()));
                }
                return fromHeadOrder(values);
            }

            private static <T> HistoryStack<T> fromHeadOrder(List<T> values) {
                StackNode<T> head = null;
                for (int index = values.size() - 1; index >= 0; index--) {
                    head = new StackNode<>(values.get(index), head);
                }
                return new HistoryStack<>(head, values.size());
            }

            private record StackNode<T>(T value, StackNode<T> next) {
            }

            private record Pop<T>(T value, HistoryStack<T> rest) {
            }
        }

        private record SavePoint<T>(T snapshot, int cursor) {
        }
    }

    boolean coreStudioLoadingVisible() {
        return false;
    }
}
