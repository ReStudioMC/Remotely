package redxax.oxy.remotely.flow.ui.studio;

import redxax.oxy.remotely.util.BrowserWork;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.AutomationDefinitionDraft;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.data.flow.ReSyncCollaborationClient;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncLifecycleDiagnostics;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.CustomContentGraphAdapter;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.ui.AsyncTaskWorker;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.ContentDesignerScreen;
import redxax.oxy.remotely.flow.ui.OptionCatalogSelector;
import redxax.oxy.remotely.ui.collaboration.CollaborationVisuals;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.backend.TransferSink;
import restudio.rebase.backend.TransferSource;
import restudio.rebase.ui.screens.editor.CompactWorkspaceBrowserWidget;
import restudio.rebase.ui.screens.editor.WorkspaceTreeExplorer;
import restudio.rebase.ui.widgets.FileEntryWidget;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.core.WidgetComposite;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.ContextMenuWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ImageWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Identifier;
import restudio.resync.contract.install.ReSyncInstallationStatus;
import restudio.resync.permissions.LuckPermsManagementContract;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ReSyncContentBrowserWidget extends AnimatedWidget {
    private static final int BROWSER_HISTORY_LIMIT = 30;
    private static final AsyncTaskWorker BROWSER_HISTORY = new AsyncTaskWorker(1, 1, 16);
    private static final OptionCatalogLoader.Profile CONTENT_CATALOGS = OptionCatalogLoader.profile(
        "server:custom_content:provider", "server:minecraft:material");
    private final Gson gson = new Gson();
    private final StudioScreen screen;
    private static final int STUDIO_CONTENT_BROWSER_DEFAULT_WIDTH = 190;
    private static final int STUDIO_CONTENT_BROWSER_MIN_WIDTH = 150;
    public static final int STUDIO_CONTENT_BROWSER_TOP = 38;
    public static final int STUDIO_CONTENT_BROWSER_BOTTOM = 8;
    public static final int STUDIO_CONTENT_BROWSER_GAP = 4;
    private static final int STUDIO_CONTENT_BROWSER_ENTRY_HEIGHT = 16;
    private static final int STUDIO_CONTENT_BROWSER_TOOL_SIZE = 16;
    private static final int MAX_REBUILD_RETRIES = 2;
    private final RemotePath projectRoot;
    private String currentFolder = "";
    private final Deque<String> backHistory = new ArrayDeque<>();
    private final Deque<String> forwardHistory = new ArrayDeque<>();
    private ReSyncProjectMetadata.ResourceEntry selectedResource;
    private ReSyncProjectMetadata.FolderEntry selectedFolder;
    private boolean selectedProjectRoot;
    private BrowserClipboard clipboard;
    private final Deque<BrowserHistoryEntry> undoHistory = new ArrayDeque<>();
    private final Deque<BrowserHistoryEntry> redoHistory = new ArrayDeque<>();
    private final Map<BrowserHistoryEntry, Set<String>> settledHistoryDeleteKeys = new HashMap<>();
    private boolean browserHistoryReplayPending;
    private int lastMouseX;
    private int lastMouseY;
    private final Container treeContainer;
    private final TextInputWidget nameInput;
    private final TextInputWidget searchInput;
    private ReSyncProjectTreeProvider treeProvider;
    private final WorkspaceTreeExplorer treeExplorer;
    private final CompactWorkspaceBrowserWidget browser;
    private final SidePanel sidePanel;
    private final SquareButtonWidget createButton;
    private final SquareButtonWidget marketplaceButton;
    private final SquareButtonWidget permissionsButton;
    private final SquareButtonWidget updateButton;
    private final SquareButtonWidget installationStatusButton;
    private ItemSelectorWidget createSelector;
    private ItemSelectorWidget createContentSelector;
    private AssetBrowserSnapshot lastAssetBrowserSnapshot;
    private final RebuildPublicationGate rebuildPublicationGate = new RebuildPublicationGate();
    private final LatestRequestDrain<BrowserRebuildRequest> rebuildDrain;
    private final CreationVisibilityGate<PendingCreatedResource> creationVisibilityGate = new CreationVisibilityGate<>();
    private long lastProjectMetadataStamp = Long.MIN_VALUE;
    private long lastPublishedBrowserGeneration = -1L;
    private volatile long scheduledProjectMetadataStamp = Long.MIN_VALUE;
    private volatile long scheduledDecorationRevision = Long.MIN_VALUE;
    private volatile long failedProjectMetadataStamp = Long.MIN_VALUE;
    private volatile long failedDecorationRevision = Long.MIN_VALUE;
    private long collaborationDecorationRevision;
    private List<BrowserEditor> collaborationEditors = List.of();
    private final Map<FileEntryWidget, WorkspaceTreeExplorer.NodeRef> decoratedRows = new LinkedHashMap<>();
    private Map<String, String> resourceIconPaths = Map.of();
    private final Map<String, CollaborationChatHighlight> collaborationChatHighlights = new HashMap<>();
    private final Map<String, BrowserAvatarWidget> collaborationAvatars = new HashMap<>();
    private boolean treeInitialized;
    private List<RemotePath> expandedTreePaths = List.of();
    private long expandedTreeRevision;
    private final BrowserLayoutGate layoutGate = new BrowserLayoutGate();
    private BrowserSelectionState pendingSelectionRestore = BrowserSelectionState.empty();
    private boolean temporarilyHidden;
    private boolean shortcutFocused;
    private volatile boolean disposed;
    private ReSyncInstallationStatus installationStatus;
    private final Set<String> pendingDeleteKeys = BrowserSafeState.set();
    private final Set<String> pendingDeleteFolders = BrowserSafeState.set();

    private record AssetBrowserSnapshot(List<String> folders, List<String> resources, long collaborationRevision) {
    }

    static record BrowserFolder(String path, String parentPath, String name, int sortOrder, boolean collapsed) {
        BrowserFolder {
            path = ReSyncProjectMetadata.normalizePath(path);
            parentPath = ReSyncProjectMetadata.normalizePath(parentPath);
            name = name != null ? name : "";
        }

        private ReSyncProjectMetadata.FolderEntry materialize() {
            ReSyncProjectMetadata.FolderEntry folder = new ReSyncProjectMetadata.FolderEntry();
            folder.setPath(path);
            folder.setParentPath(parentPath);
            folder.setName(name);
            folder.setSortOrder(sortOrder);
            folder.setCollapsed(collapsed);
            return folder;
        }
    }

    static record BrowserResource(String type, String id, String displayName, String path, int sortOrder,
                                  String iconPath, boolean enabled) {
        BrowserResource {
            type = type != null ? type : "";
            id = id != null ? id : "";
            displayName = displayName != null ? displayName : "";
            path = ReSyncProjectMetadata.normalizePath(path);
            iconPath = iconPath != null ? iconPath : "";
        }

        private String key() {
            return resourceKey(type, id);
        }

        private ReSyncProjectMetadata.ResourceEntry materialize() {
            ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
            resource.setType(type);
            resource.setId(id);
            resource.setDisplayName(displayName);
            resource.setPath(path);
            resource.setSortOrder(sortOrder);
            return resource;
        }
    }

    private record PreparedBrowserRebuild(long generation, long metadataStamp, RemotePath revealPath,
                                          ReSyncProjectTreeProvider provider, List<RemotePath> expandedPaths,
                                          boolean expandAll, Map<String, String> iconPaths,
                                          AssetBrowserSnapshot snapshot, long expandedTreeRevision,
                                          int folderCount, int resourceCount, int attempt,
                                          long startedAtNanos) {
    }

    private record BrowserRebuildRequest(long generation, long metadataStamp, long decorationRevision,
                                         RemotePath revealPath, int attempt, boolean expandAll,
                                         List<RemotePath> expandedPaths, long expandedTreeRevision,
                                         long startedAtNanos) {
        private BrowserRebuildRequest {
            expandedPaths = List.copyOf(expandedPaths);
        }
    }

    private record PendingCreatedResource(ReSyncResourceCreator.Result result, String targetFolder,
                                          long startedAtNanos, String resourceKey, RemotePath folderPath,
                                          RemotePath revealPath, OneShotCreationPublication publication) {
        private String pendingKey() {
            return resourceKey != null ? resourceKey : "folder\u0000" + folderPath;
        }

        private boolean visibleIn(ReSyncProjectTreeProvider provider) {
            return resourceKey != null ? provider.containsResourceKey(resourceKey)
                : provider.containsFolderPath(folderPath);
        }
    }

    static final class OneShotCreationPublication {
        private boolean openAttempted;
        private boolean openCompleted;
        private boolean diagnosticAttempted;

        synchronized void publish(Runnable opener, Runnable diagnostic) {
            Objects.requireNonNull(opener, "Creation opener is required");
            Objects.requireNonNull(diagnostic, "Creation diagnostic is required");
            if (!openAttempted) {
                openAttempted = true;
                opener.run();
                openCompleted = true;
            }
            if (!openCompleted || diagnosticAttempted) {
                return;
            }
            diagnosticAttempted = true;
            diagnostic.run();
        }

        synchronized boolean openCompleted() {
            return openCompleted;
        }
    }

    static record BrowserSelectionState(Set<String> resourceKeys, Set<String> folderPaths, boolean projectRoot,
                                        float scrollOffset) {
        BrowserSelectionState {
            resourceKeys = resourceKeys != null ? Set.copyOf(resourceKeys) : Set.of();
            folderPaths = folderPaths != null ? folderPaths.stream().map(ReSyncProjectMetadata::normalizePath)
                .collect(Collectors.toUnmodifiableSet()) : Set.of();
        }

        static BrowserSelectionState empty() {
            return new BrowserSelectionState(Set.of(), Set.of(), false, 0.0F);
        }

        int size() {
            return resourceKeys.size() + folderPaths.size() + (projectRoot ? 1 : 0);
        }
    }

    static final class BrowserLayoutGate {
        private int x = Integer.MIN_VALUE;
        private int y = Integer.MIN_VALUE;
        private int width = Integer.MIN_VALUE;
        private int height = Integer.MIN_VALUE;
        private int panelX = Integer.MIN_VALUE;
        private int panelY = Integer.MIN_VALUE;
        private int panelWidth = Integer.MIN_VALUE;
        private int panelHeight = Integer.MIN_VALUE;
        private boolean dirty;

        boolean update(int x, int y, int width, int height) {
            if (this.x == x && this.y == y && this.width == width && this.height == height) {
                return false;
            }
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            dirty = true;
            return true;
        }

        boolean updatePanel(int x, int y, int width, int height) {
            if (panelX == x && panelY == y && panelWidth == width && panelHeight == height) {
                return false;
            }
            panelX = x;
            panelY = y;
            panelWidth = width;
            panelHeight = height;
            dirty = true;
            return true;
        }

        void invalidate() {
            dirty = true;
        }

        boolean drain() {
            if (!dirty) {
                return false;
            }
            dirty = false;
            return true;
        }
    }

    static final class RebuildPublicationGate {
        private long generation;

        synchronized long next() {
            return ++generation;
        }

        synchronized boolean current(long candidate) {
            return candidate == generation;
        }

        synchronized boolean publish(long candidate, Runnable publication) {
            if (!current(candidate)) {
                return false;
            }
            publication.run();
            return true;
        }

        synchronized void invalidate() {
            generation++;
        }
    }

    static final class LatestRequestDrain<T> {
        private final Consumer<Runnable> executor;
        private final Consumer<T> preparation;
        private final BiConsumer<T, RuntimeException> failure;
        private T pending;
        private boolean draining;

        LatestRequestDrain(Consumer<Runnable> executor, Consumer<T> preparation,
                           BiConsumer<T, RuntimeException> failure) {
            this.executor = Objects.requireNonNull(executor, "Rebuild executor is required");
            this.preparation = Objects.requireNonNull(preparation, "Rebuild preparation is required");
            this.failure = Objects.requireNonNull(failure, "Rebuild failure handler is required");
        }

        void submit(T request) {
            boolean dispatch;
            synchronized (this) {
                pending = Objects.requireNonNull(request, "Rebuild request is required");
                dispatch = !draining;
                if (dispatch) {
                    draining = true;
                }
            }
            if (!dispatch) {
                return;
            }
            try {
                executor.accept(this::drain);
            } catch (RuntimeException exception) {
                T rejected;
                synchronized (this) {
                    rejected = pending;
                    pending = null;
                    draining = false;
                }
                if (rejected != null) {
                    failure.accept(rejected, exception);
                }
            }
        }

        private void drain() {
            while (true) {
                T request;
                synchronized (this) {
                    request = pending;
                    pending = null;
                    if (request == null) {
                        draining = false;
                        return;
                    }
                }
                try {
                    preparation.accept(request);
                } catch (RuntimeException exception) {
                    try {
                        failure.accept(request, exception);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }

        synchronized boolean quiescent() {
            return !draining && pending == null;
        }

        synchronized int pendingCount() {
            return pending == null ? 0 : 1;
        }

        synchronized void clear() {
            pending = null;
        }
    }

    static final class CreationVisibilityGate<T> {
        private final Map<String, T> pending = new LinkedHashMap<>();
        private boolean closed;

        private record Delivery<T>(String key, T value) {
        }

        synchronized boolean await(String key, T value) {
            Objects.requireNonNull(key, "Creation resource key is required");
            Objects.requireNonNull(value, "Pending creation is required");
            if (closed) {
                return false;
            }
            pending.put(key, value);
            return true;
        }

        synchronized void publish(Predicate<T> visible, Consumer<T> publisher) {
            Objects.requireNonNull(visible, "Published provider lookup is required");
            Objects.requireNonNull(publisher, "Creation publisher is required");
            if (closed) {
                return;
            }
            List<Delivery<T>> deliveries = pending.entrySet().stream()
                .map(entry -> new Delivery<>(entry.getKey(), entry.getValue()))
                .toList();
            for (Delivery<T> delivery : deliveries) {
                T value = pending.get(delivery.key());
                if (value != delivery.value() || !visible.test(value)) {
                    continue;
                }
                publisher.accept(value);
                acknowledge(delivery.key(), value);
            }
        }

        private void acknowledge(String key, T value) {
            if (pending.get(key) == value) {
                pending.remove(key);
            }
        }

        synchronized int pendingCount() {
            return pending.size();
        }

        synchronized void close() {
            closed = true;
            pending.clear();
        }
    }

    private record CollaborationChatHighlight(int color, long expiresAt) {
    }

    record BrowserEditor(String sessionId, ReSyncCollaborationClient.Identity identity, String resourceType,
                         String resourceId, int color, boolean customColor, Identifier avatar, long avatarRevision) {
        static BrowserEditor from(ReSyncCollaborationClient.Presence presence, int color, Identifier avatar, long avatarRevision) {
            return new BrowserEditor(presence.sessionId(), presence.identity(), presence.resourceType(),
                presence.resourceId(), color, presence.customColor(), avatar, avatarRevision);
        }
    }

    private static final class BrowserAvatarWidget extends AnimatedWidget implements WidgetComposite {
        private static final int AVATAR_SIZE = 12;
        private final StudioScreen screen;
        private final String sessionId;
        private final BrowserAvatarImage avatar;

        private BrowserAvatarWidget(StudioScreen screen, String sessionId, Identifier image) {
            super(0, 0, AVATAR_SIZE, AVATAR_SIZE, "");
            this.screen = screen;
            this.sessionId = sessionId;
            avatar = new BrowserAvatarImage(image);
            transparent = true;
            animateElevation = false;
            entranceAnimationEnabled = false;
            enableHoverColors = false;
            setAnimateLayout(true);
            setAnimateLayoutPosition(false);
            setLayoutAnimationFactor(0.85f);
        }

        private void update(Identifier image) {
            if (!Objects.equals(avatar.getImageId(), image)) {
                avatar.setImage(image);
            }
            setWidth(AVATAR_SIZE);
        }

        @Override
        public void tick() {
            ReSyncCollaborationClient.Presence presence = screen.collaborationPresence(sessionId);
            if (presence != null) {
                Identifier image = screen.collaborationAvatar(presence);
                update(image != null ? image : Identifier.icon("steve.png"));
            }
            super.tick();
            avatar.tick();
        }

        @Override
        protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
            int avatarX = getX() + getWidth() - AVATAR_SIZE;
            avatar.setPosition(avatarX, getY() + Math.max(0, (getHeight() - AVATAR_SIZE) / 2));
            avatar.render(context, mouseX, mouseY, 0);
        }

        @Override
        public List<? extends Widget> getChildWidgets() {
            return List.of(avatar);
        }
    }

    private static final class BrowserAvatarImage extends ImageWidget {
        private BrowserAvatarImage(Identifier image) {
            super(image, 0, 0, BrowserAvatarWidget.AVATAR_SIZE, BrowserAvatarWidget.AVATAR_SIZE);
            transparent = true;
            animateElevation = false;
            entranceAnimationEnabled = false;
            enableHoverColors = false;
            setPixelated(true);
        }
    }

    private record ClipboardResource(String type, String id, String path) {
    }

    private record BrowserClipboard(List<ClipboardResource> resources, List<String> folderPaths, boolean cut) {
        private BrowserClipboard {
            resources = List.copyOf(resources);
            folderPaths = List.copyOf(folderPaths);
        }
    }

    private record BrowserSelection(List<ReSyncProjectMetadata.ResourceEntry> resources, List<ReSyncProjectMetadata.FolderEntry> folders, boolean projectRoot, int selectedCount) {
        private int size() {
            return resources.size() + folders.size();
        }
    }

    private record ResourceSnapshot(String type, String id, String payload, String context) {
    }

    private record BrowserEditStart(String label, FlowManager.ResourceReadLease metadata, Map<String, FlowManager.ResourceReadLease> resources) {
    }

    private record WorldGenCopyRequest(String serverId, String sourceId, String targetId, String destination,
                                       WorldGenManager.WorldGenMetadataIntent metadataIntent, BrowserEditStart edit) {
    }

    private record BrowserHistoryEntry(String label, String beforeMetadata, String afterMetadata, Map<String, ResourceSnapshot> beforeResources, Map<String, ResourceSnapshot> afterResources, Set<String> affectedKeys) {
    }

    private record BrowserStateResult(boolean restored, Set<String> deletedKeys) {
        private BrowserStateResult {
            deletedKeys = Set.copyOf(deletedKeys);
        }

        private static BrowserStateResult failed() {
            return new BrowserStateResult(false, Set.of());
        }
    }

    public ReSyncContentBrowserWidget(StudioScreen screen, int x, int y, int width, int height) {
        super(x, y, width, height, "");
        this.screen = screen;
        this.projectRoot = RemotePath.of("ReSync");
        rebuildDrain = new LatestRequestDrain<>(BROWSER_HISTORY::execute, this::prepareRebuild,
            this::handleRebuildFailure);
        animateElevation = false;
        entranceAnimationEnabled = false;
        enableHoverColors = false;
        nameInput = new TextInputWidget.Builder()
            .placeholder("Selected")
            .size(110, 18)
            .build();
        treeProvider = ReSyncProjectTreeProvider.empty(projectRoot);
        createButton = new SquareButtonWidget.Builder()
            .imagePath("add.png")
            .size(STUDIO_CONTENT_BROWSER_TOOL_SIZE, STUDIO_CONTENT_BROWSER_TOOL_SIZE)
            .hint("Create")
            .onClick(this::showCreateMenu)
            .build();
        marketplaceButton = new SquareButtonWidget.Builder()
            .imagePath("market.png")
            .size(STUDIO_CONTENT_BROWSER_TOOL_SIZE, STUDIO_CONTENT_BROWSER_TOOL_SIZE)
            .hint("Marketplace")
            .onClick(screen::openReSyncMarketplace)
            .build();
        permissionsButton = new SquareButtonWidget.Builder()
            .imagePath("op.png")
            .size(STUDIO_CONTENT_BROWSER_TOOL_SIZE, STUDIO_CONTENT_BROWSER_TOOL_SIZE)
            .hint("Permissions")
            .onClick(screen::openReSyncPermissions)
            .build();
        permissionsButton.setVisible(false);
        updateButton = new SquareButtonWidget.Builder()
            .imagePath("ReSync.png")
            .size(STUDIO_CONTENT_BROWSER_TOOL_SIZE, STUDIO_CONTENT_BROWSER_TOOL_SIZE)
            .hint("Update ReSync")
            .onClick(screen::updateReSyncFromContentBrowser)
            .build();
        updateButton.setVisible(false);
        installationStatusButton = new SquareButtonWidget.Builder()
            .imagePath("info.png")
            .size(STUDIO_CONTENT_BROWSER_TOOL_SIZE, STUDIO_CONTENT_BROWSER_TOOL_SIZE)
            .hint("ReSync Data Status")
            .onClick(() -> screen.showReSyncInstallationStatus(installationStatus))
            .build();
        installationStatusButton.setVisible(false);
        SquareButtonWidget switchBrowser = new SquareButtonWidget.Builder()
            .imagePath("resources.png")
            .size(STUDIO_CONTENT_BROWSER_TOOL_SIZE, STUDIO_CONTENT_BROWSER_TOOL_SIZE)
            .hint("Sub Resources")
            .onClick(() -> screen.openDefinitions(AutomationDefinitionDraft.VARIABLE))
            .build();
        browser = new CompactWorkspaceBrowserWidget(
            screen,
            "studioContentBrowser",
            STUDIO_CONTENT_BROWSER_TOP,
            STUDIO_CONTENT_BROWSER_BOTTOM,
            STUDIO_CONTENT_BROWSER_DEFAULT_WIDTH,
            STUDIO_CONTENT_BROWSER_MIN_WIDTH,
            120,
            STUDIO_CONTENT_BROWSER_ENTRY_HEIGHT,
            this::openTreeFile,
            false,
            SidePanel.Anchor.LEFT,
            updateButton,
            installationStatusButton,
            switchBrowser,
            permissionsButton,
            marketplaceButton,
            createButton
        );
        sidePanel = browser.sidePanel().renderedByOwner();
        sidePanel.collapsible("Content Browser");
        searchInput = browser.searchInput();
        treeContainer = browser.treeContainer();
        treeExplorer = browser.treeExplorer();
        treeExplorer.setNailingEnabled(false);
        treeExplorer.setToggleDirectoriesOnActivation(false);
        treeExplorer.setOnNodeActivated(this::activateTreeNode);
        treeExplorer.setOnNodeOpened(this::openTreeNode);
        treeExplorer.setOnNodeRightClick(this::rightClickTreeNode);
        treeExplorer.setOnNodeDragStarted(this::startResourceDrag);
        treeExplorer.setOnNodePrepared(this::prepareTreeNode);
        treeExplorer.setOnExpandedStateChanged(paths -> {
            expandedTreePaths = List.copyOf(paths);
            expandedTreeRevision++;
        });
        sidePanel.show();
        CONTENT_CATALOGS.preload(screen.studioServerId());
        layoutGate.update(getX(), getY(), getWidth(), getHeight());
        layoutContainersIfDirty();
        FlowManager manager = FlowManager.getInstance();
        scheduleRebuild(manager != null ? manager.projectBrowserStamp(screen.studioServerId()) : Long.MIN_VALUE);
    }

    @Override
    protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
    }

    private void updateSearch(String query) {
        if (treeExplorer != null) {
            treeExplorer.setSearchQuery(query);
        }
    }

    public SidePanel sidePanel() {
        return sidePanel;
    }

    public void setInstallationStatus(ReSyncInstallationStatus status) {
        installationStatus = status;
        installationStatusButton.setVisible(status != null);
        installationStatusButton.setIcon(Identifier.icon(status != null && status.blocksStartup() ? "stop.png" : "info.png"));
        installationStatusButton.setHint(status == null ? "ReSync Data Status" : status.title());
        browser.layout();
    }

    public int visibleLayoutWidth() {
        if (temporarilyHidden || sidePanel == null) {
            return 0;
        }
        return sidePanel.layoutWidth(8);
    }

    public void updateShortcutFocus(ReMouseEvent event) {
        shortcutFocused = !temporarilyHidden && sidePanel != null && sidePanel.isVisible() && sidePanel.isMouseOver(event.x(), event.y());
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (temporarilyHidden) {
            return false;
        }
        lastMouseX = (int) event.x();
        lastMouseY = (int) event.y();
        if (handleHistoryMouseButton(event)) {
            return true;
        }
        if (sidePanel == null) {
            return false;
        }
        boolean handled = sidePanel.mouseClicked(event.retarget(sidePanel, event.x(), event.y()));
        if (handled) {
            pendingSelectionRestore = captureTreeSelection();
            applySelection(pendingSelectionRestore);
        }
        return handled || event.button() == ReMouseButton.RIGHT && sidePanel.isMouseOver(event.x(), event.y());
    }

    public boolean handleHistoryMouseButton(int button) {
        if (temporarilyHidden) {
            return false;
        }
        if (button == 3) {
            navigateHistoryBack();
            return true;
        }
        if (button == 4) {
            navigateHistoryForward();
            return true;
        }
        return false;
    }

    public boolean handleHistoryMouseButton(ReMouseEvent event) {
        if (temporarilyHidden) {
            return false;
        }
        if (event.button() == ReMouseButton.BACK) {
            navigateHistoryBack();
            return true;
        }
        if (event.button() == ReMouseButton.FORWARD) {
            navigateHistoryForward();
            return true;
        }
        return handleHistoryMouseButton(event.nativeButton());
    }

    private boolean handleHistoryMouseButton(ReMouseButton button) {
        if (temporarilyHidden) {
            return false;
        }
        if (button == ReMouseButton.BACK) {
            navigateHistoryBack();
            return true;
        }
        if (button == ReMouseButton.FORWARD) {
            navigateHistoryForward();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (temporarilyHidden) {
            return false;
        }
        if (sidePanel != null && sidePanel.mouseReleased(event.retarget(sidePanel, event.x(), event.y()))) {
            layoutGate.invalidate();
            layoutContainersIfDirty();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (temporarilyHidden) {
            return false;
        }
        if (sidePanel != null && sidePanel.mouseDragged(event.retarget(sidePanel, event.x(), event.y(), event.deltaX(), event.deltaY()))) {
            layoutGate.invalidate();
            layoutContainersIfDirty();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        if (temporarilyHidden) {
            return false;
        }
        if (sidePanel != null && sidePanel.mouseScrolled(event.retarget(sidePanel, event.x(), event.y()))) {
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (temporarilyHidden) {
            return false;
        }
        if (!browserShortcutActive()) return false;
        if (sidePanel != null && sidePanel.keyPressed(event.retarget(sidePanel))) {
            return true;
        }
        boolean shortcut = event.modifiers().control() || event.modifiers().superKey();
        if (shortcut && event.key() == ReKey.Z) {
            if (event.modifiers().shift()) redoBrowserEdit();
            else undoBrowserEdit();
            return true;
        }
        if (shortcut && event.key() == ReKey.Y) {
            redoBrowserEdit();
            return true;
        }
        if (shortcut && event.key() == ReKey.C) {
            copySelected();
            return true;
        }
        if (shortcut && event.key() == ReKey.X) {
            cutSelected();
            return true;
        }
        if (shortcut && event.key() == ReKey.V) {
            pasteSelected();
            return true;
        }
        if (event.key() == ReKey.F2) {
            renameSelected();
            return true;
        }
        if (event.key() == ReKey.DELETE) {
            deleteSelected();
            return true;
        }
        return false;
    }

    private boolean browserShortcutActive() {
        return shortcutFocused && !temporarilyHidden && sidePanel != null && sidePanel.isVisible();
    }

    private BrowserEditStart beginBrowserEdit(String label, List<ReSyncProjectMetadata.ResourceEntry> resources) {
        if (browserHistoryReplayPending) return null;
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) return null;
        FlowManager.ResourceReadLease metadata = manager.snapshotProjectMetadata(screen.studioServerId());
        if (metadata == null) return null;
        Map<String, FlowManager.ResourceReadLease> snapshots = new LinkedHashMap<>();
        for (ReSyncProjectMetadata.ResourceEntry resource : resources) {
            FlowManager.ResourceReadLease snapshot = manager.snapshotResource(screen.studioServerId(), resource.getType(), resource.getId());
            if (snapshot != null) snapshots.put(resource.key(), snapshot);
        }
        return new BrowserEditStart(label, metadata, Map.copyOf(snapshots));
    }

    private void commitBrowserEdit(BrowserEditStart edit, Set<String> affectedKeys) {
        if (edit == null) return;
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) return;
        FlowManager.ResourceReadLease afterMetadata = manager.snapshotProjectMetadata(screen.studioServerId());
        if (afterMetadata == null) return;
        Map<String, FlowManager.ResourceReadLease> afterResources = new LinkedHashMap<>();
        for (String key : affectedKeys) {
            ReSyncProjectMetadata.ResourceEntry resource = manager.getProjectResource(screen.studioServerId(), key);
            if (resource == null) continue;
            FlowManager.ResourceReadLease snapshot = manager.snapshotResource(screen.studioServerId(), resource.getType(), resource.getId());
            if (snapshot != null) afterResources.put(resource.key(), snapshot);
        }
        try {
            BROWSER_HISTORY.execute(() -> materializeBrowserEdit(edit, afterMetadata, Map.copyOf(afterResources), Set.copyOf(affectedKeys)));
        } catch (IllegalStateException exception) {
            undoHistory.clear();
            redoHistory.clear();
            settledHistoryDeleteKeys.clear();
        }
    }

    private void materializeBrowserEdit(BrowserEditStart edit, FlowManager.ResourceReadLease afterMetadata,
                                        Map<String, FlowManager.ResourceReadLease> afterResourceLeases, Set<String> affectedKeys) {
        try {
            String beforePayload = edit.metadata().materialize();
            String afterPayload = afterMetadata.materialize();
            if (Objects.equals(beforePayload, afterPayload)) return;
            ReSyncProjectMetadata before = (ReSyncProjectMetadata) ReSyncResourceType.PROJECT_METADATA.deserialize(beforePayload);
            ReSyncProjectMetadata after = (ReSyncProjectMetadata) ReSyncResourceType.PROJECT_METADATA.deserialize(afterPayload);
            Map<String, ReSyncProjectMetadata.ResourceEntry> beforeEntries = resourceEntries(before);
            Map<String, ReSyncProjectMetadata.ResourceEntry> afterEntries = resourceEntries(after);
            Map<String, ResourceSnapshot> beforeResources = materializeResources(edit.resources(), affectedKeys);
            Map<String, ResourceSnapshot> afterResources = materializeResources(afterResourceLeases, affectedKeys);
            boolean reversible = affectedKeys.stream().allMatch(key -> !beforeEntries.containsKey(key) || beforeResources.containsKey(key))
                && affectedKeys.stream().allMatch(key -> !afterEntries.containsKey(key) || afterResources.containsKey(key));
            ScreenManager.getInstance().execute(() -> finishBrowserEdit(edit.label(), beforePayload, afterPayload,
                beforeResources, afterResources, affectedKeys, reversible));
        } catch (RuntimeException exception) {
            ScreenManager.getInstance().execute(() -> {
                undoHistory.clear();
                redoHistory.clear();
                settledHistoryDeleteKeys.clear();
            });
        }
    }

    private Map<String, ResourceSnapshot> materializeResources(Map<String, FlowManager.ResourceReadLease> leases, Set<String> affectedKeys) {
        Map<String, ResourceSnapshot> snapshots = new LinkedHashMap<>();
        for (String key : affectedKeys) {
            FlowManager.ResourceReadLease lease = leases.get(key);
            if (lease == null) continue;
            String payload = lease.materialize();
            if (payload != null) snapshots.put(key, new ResourceSnapshot(lease.type(), lease.id(), payload, lease.context()));
        }
        return Map.copyOf(snapshots);
    }

    private void finishBrowserEdit(String label, String beforeMetadata, String afterMetadata,
                                   Map<String, ResourceSnapshot> beforeResources, Map<String, ResourceSnapshot> afterResources,
                                   Set<String> affectedKeys, boolean reversible) {
        if (!reversible) {
            undoHistory.clear();
            redoHistory.clear();
            settledHistoryDeleteKeys.clear();
            return;
        }
        undoHistory.addLast(new BrowserHistoryEntry(label, beforeMetadata, afterMetadata, beforeResources, afterResources, Set.copyOf(affectedKeys)));
        while (undoHistory.size() > BROWSER_HISTORY_LIMIT) undoHistory.removeFirst();
        settledHistoryDeleteKeys.keySet().removeAll(redoHistory);
        redoHistory.clear();
    }

    private Map<String, ReSyncProjectMetadata.ResourceEntry> resourceEntries(ReSyncProjectMetadata metadata) {
        Map<String, ReSyncProjectMetadata.ResourceEntry> entries = new LinkedHashMap<>();
        if (metadata != null) {
            for (ReSyncProjectMetadata.ResourceEntry resource : metadata.getResources()) entries.put(resource.key(), resource);
        }
        return entries;
    }

    private Async<Boolean> restoreResourceSettled(FlowManager manager, ResourceSnapshot snapshot) {
        ReSyncResourceType type = snapshot != null ? ReSyncResourceType.byTypeId(snapshot.type()) : null;
        if (type == null) return Async.completed(false);
        String serverId = screen.studioServerId();
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, type, snapshot.id(), snapshot.id());
        if (ticket == null) return Async.completed(false);
        Async<Boolean> completion = Async.pending();
        ticket.whenFinished((saved, current) -> completion.complete(Boolean.TRUE.equals(saved) && Boolean.TRUE.equals(current)));
        try {
            DesignerSaveNotifications.withoutAutomaticNotifications(() -> {
                switch (type) {
                    case FLOW, FUNCTION, COMMAND -> manager.saveGraph(serverId, type, FlowSerializer.deserialize(snapshot.payload()), ticket);
                    case CUSTOM_CONTENT -> manager.saveCustomContent(serverId, FlowSerializer.deserializeCustomContent(snapshot.payload()), ticket);
                    case GUI -> manager.saveGui(serverId, FlowSerializer.deserializeGui(snapshot.payload()), ticket);
                    case SCOREBOARD -> manager.saveScoreboard(serverId, FlowSerializer.deserializeScoreboard(snapshot.payload()), ticket);
                    case TAB -> manager.saveTab(serverId, FlowSerializer.deserializeTab(snapshot.payload()), ticket);
                    default -> manager.saveJsonResource(serverId, type, gson.fromJson(snapshot.payload(), JsonObject.class), ticket);
                }
                return true;
            });
        } catch (RuntimeException exception) {
            DesignerSaveNotifications.failExact(ticket, "History Restore Failed");
        }
        if (type != ReSyncResourceType.COMMAND) return completion;
        return completion.thenCompose(saved -> saved
            ? manager.setCommandBindingAwait(serverId, snapshot.id(), snapshot.context().isBlank() ? snapshot.id() : snapshot.context())
            : Async.completed(false));
    }

    private void undoBrowserEdit() {
        if (browserHistoryReplayPending || undoHistory.isEmpty()) return;
        BrowserHistoryEntry edit = undoHistory.peekLast();
        browserHistoryReplayPending = true;
        restoreBrowserEdit(edit, true);
    }

    private void redoBrowserEdit() {
        if (browserHistoryReplayPending || redoHistory.isEmpty()) return;
        BrowserHistoryEntry edit = redoHistory.peekLast();
        browserHistoryReplayPending = true;
        restoreBrowserEdit(edit, false);
    }

    private void restoreBrowserEdit(BrowserHistoryEntry edit, boolean before) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            restoreHistoryEntry(edit, before, false);
            return;
        }
        FlowManager.ResourceReadLease currentMetadata = manager.snapshotProjectMetadata(screen.studioServerId());
        if (currentMetadata == null) {
            restoreHistoryEntry(edit, before, false);
            return;
        }
        Map<String, ReSyncProjectMetadata.ResourceEntry> currentEntries = new LinkedHashMap<>();
        Map<String, FlowManager.ResourceReadLease> currentSnapshots = new LinkedHashMap<>();
        Map<String, ResourceSnapshot> expectedCurrentResources = before ? edit.afterResources() : edit.beforeResources();
        for (String key : edit.affectedKeys()) {
            ReSyncProjectMetadata.ResourceEntry entry = manager.getProjectResource(screen.studioServerId(), key);
            if (entry != null) currentEntries.put(key, entry);
            ResourceSnapshot expected = expectedCurrentResources.get(key);
            String type = expected != null ? expected.type() : entry != null ? entry.getType() : null;
            String id = expected != null ? expected.id() : entry != null ? entry.getId() : null;
            FlowManager.ResourceReadLease snapshot = type != null && id != null
                ? manager.snapshotResource(screen.studioServerId(), type, id) : null;
            if (snapshot != null) currentSnapshots.put(key, snapshot);
        }
        if (worldGenHistoryAffected(edit, currentEntries)) {
            browserHistoryReplayPending = false;
            new Notification(before ? "Undo Unavailable" : "Redo Unavailable", "World Generation Changes", Notification.Type.WARN);
            return;
        }
        Set<String> settledDeleteKeys = settledHistoryDeleteKeys.getOrDefault(edit, Set.of());
        try {
            BROWSER_HISTORY.execute(() -> {
                try {
                    ReSyncProjectMetadata target = (ReSyncProjectMetadata) ReSyncResourceType.PROJECT_METADATA.deserialize(before ? edit.beforeMetadata() : edit.afterMetadata());
                    Map<String, ResourceSnapshot> targetSnapshots = before ? edit.beforeResources() : edit.afterResources();
                    if (!currentMetadata.isCurrent() || currentSnapshots.values().stream().anyMatch(snapshot -> !snapshot.isCurrent())) {
                        ScreenManager.getInstance().execute(() -> restoreHistoryEntry(edit, before, false));
                        return;
                    }
                    applyBrowserState(manager, currentMetadata, target, Map.copyOf(currentEntries),
                        Map.copyOf(currentSnapshots), expectedCurrentResources, targetSnapshots, edit.affectedKeys(),
                        settledDeleteKeys)
                        .whenComplete((result, failure) -> {
                            BrowserStateResult restored = failure == null && result != null ? result : BrowserStateResult.failed();
                            ScreenManager.getInstance().execute(() -> restoreHistoryEntry(edit, before, restored));
                        });
                } catch (RuntimeException exception) {
                    ScreenManager.getInstance().execute(() -> restoreHistoryEntry(edit, before, false));
                }
            });
        } catch (IllegalStateException exception) {
            restoreHistoryEntry(edit, before, false);
        }
    }

    private boolean worldGenHistoryAffected(BrowserHistoryEntry edit,
                                            Map<String, ReSyncProjectMetadata.ResourceEntry> currentEntries) {
        return currentEntries.values().stream().anyMatch(entry -> ReSyncResourceDragPayload.WORLDGEN.equals(entry.getType()))
            || edit.beforeResources().values().stream().anyMatch(snapshot -> ReSyncResourceDragPayload.WORLDGEN.equals(snapshot.type()))
            || edit.afterResources().values().stream().anyMatch(snapshot -> ReSyncResourceDragPayload.WORLDGEN.equals(snapshot.type()));
    }

    private void restoreHistoryEntry(BrowserHistoryEntry edit, boolean before, boolean restored) {
        restoreHistoryEntry(edit, before, new BrowserStateResult(restored, Set.of()));
    }

    private void restoreHistoryEntry(BrowserHistoryEntry edit, boolean before, BrowserStateResult result) {
        browserHistoryReplayPending = false;
        if (!result.deletedKeys().isEmpty()) {
            Set<String> settled = new LinkedHashSet<>(settledHistoryDeleteKeys.getOrDefault(edit, Set.of()));
            settled.addAll(result.deletedKeys());
            settledHistoryDeleteKeys.put(edit, Set.copyOf(settled));
            screen.removeStudioDocuments(result.deletedKeys());
        }
        Deque<BrowserHistoryEntry> source = before ? undoHistory : redoHistory;
        Deque<BrowserHistoryEntry> destination = before ? redoHistory : undoHistory;
        boolean restored = result.restored() && source.peekLast() == edit;
        if (!restored) {
            new Notification(before ? "Undo Failed" : "Redo Failed", "Changes Not Restored", Notification.Type.ERROR);
            return;
        }
        source.removeLast();
        destination.addLast(edit);
        settledHistoryDeleteKeys.remove(edit);
        selectedResource = null;
        selectedFolder = null;
        selectedProjectRoot = false;
        rebuild();
        new Notification(before ? "Undo" : "Redo", edit.label(), Notification.Type.SUCCESS);
    }

    private Async<BrowserStateResult> applyBrowserState(FlowManager manager,
                                      FlowManager.ResourceReadLease currentMetadata, ReSyncProjectMetadata target,
                                      Map<String, ReSyncProjectMetadata.ResourceEntry> currentEntries,
                                      Map<String, FlowManager.ResourceReadLease> currentSnapshots,
                                      Map<String, ResourceSnapshot> currentStateSnapshots,
                                      Map<String, ResourceSnapshot> targetSnapshots, Set<String> affectedKeys,
                                      Set<String> settledDeleteKeys) {
        Map<String, ReSyncProjectMetadata.ResourceEntry> targetEntries = resourceEntries(target);
        if (!currentMetadata.isCurrent() || currentSnapshots.values().stream().anyMatch(snapshot -> !snapshot.isCurrent())) {
            return Async.completed(BrowserStateResult.failed());
        }
        Map<String, Async<Boolean>> payloadSettlements = new LinkedHashMap<>();
        Set<String> deleteKeys = new LinkedHashSet<>();
        for (String key : affectedKeys) {
            ReSyncProjectMetadata.ResourceEntry currentEntry = currentEntries.get(key);
            ReSyncProjectMetadata.ResourceEntry targetEntry = targetEntries.get(key);
            FlowManager.ResourceReadLease currentSnapshot = currentSnapshots.get(key);
            ResourceSnapshot sourceSnapshot = currentStateSnapshots.get(key);
            ResourceSnapshot targetSnapshot = targetSnapshots.get(key);
            if (targetEntry == null && settledDeleteKeys.contains(key)) {
                deleteKeys.add(key);
                payloadSettlements.put(key, Async.completed(true));
            } else if (targetEntry == null && sourceSnapshot != null) {
                if (currentSnapshot != null && (!sourceSnapshot.type().equals(currentSnapshot.type())
                    || !sourceSnapshot.id().equals(currentSnapshot.id()))) {
                    return Async.completed(BrowserStateResult.failed());
                }
                ReSyncResourceType type = ReSyncResourceType.byTypeId(sourceSnapshot.type());
                if (type == null) return Async.completed(BrowserStateResult.failed());
                deleteKeys.add(key);
                payloadSettlements.put(key, manager.deleteResourceSettled(screen.studioServerId(), type, sourceSnapshot.id())
                    .thenApply(result -> result != null && result.deleted()));
            } else if (targetEntry == null && currentEntry != null) {
                return Async.completed(BrowserStateResult.failed());
            } else if (targetEntry != null) {
                if (targetSnapshot == null || !targetEntry.getType().equals(targetSnapshot.type())
                    || !targetEntry.getId().equals(targetSnapshot.id())) {
                    return Async.completed(BrowserStateResult.failed());
                }
                payloadSettlements.put(key, restoreResourceSettled(manager, targetSnapshot));
            }
        }
        Async<?>[] settlements = payloadSettlements.values().toArray(new Async<?>[0]);
        return Async.allOf(settlements).handle((ignored, failure) -> {
            Set<String> deleted = deleteKeys.stream()
                .filter(key -> terminalSuccess(payloadSettlements.get(key))).collect(Collectors.toUnmodifiableSet());
            boolean payloadsSettled = failure == null && payloadSettlements.values().stream().allMatch(ReSyncContentBrowserWidget::terminalSuccess);
            return new BrowserStateResult(payloadsSettled, deleted);
        }).thenCompose(payloadResult -> {
            if (!payloadResult.restored()) {
                return Async.completed(new BrowserStateResult(false, payloadResult.deletedKeys()));
            }
            return saveProjectMetadataSettled(manager, target)
                .handle((saved, failure) -> new BrowserStateResult(failure == null && Boolean.TRUE.equals(saved), payloadResult.deletedKeys()));
        });
    }

    private Async<Boolean> saveProjectMetadataSettled(FlowManager manager, ReSyncProjectMetadata metadata) {
        String serverId = screen.studioServerId();
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId,
            ReSyncResourceType.PROJECT_METADATA, serverId, "Project Metadata");
        if (ticket == null) return Async.completed(false);
        Async<Boolean> completion = Async.pending();
        ticket.whenFinished((saved, current) -> completion.complete(Boolean.TRUE.equals(saved) && Boolean.TRUE.equals(current)));
        try {
            manager.saveProjectMetadata(serverId, metadata, true, ticket);
        } catch (RuntimeException exception) {
            DesignerSaveNotifications.failExact(ticket, "History Presentation Restore Failed");
        }
        return completion;
    }

    private static boolean terminalSuccess(Async<Boolean> settlement) {
        return settlement != null && settlement.isDone() && !BrowserWork.failed(settlement)
            && !settlement.isCancelled() && Boolean.TRUE.equals(settlement.getNow(false));
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        if (temporarilyHidden) {
            return false;
        }
        if (sidePanel != null && sidePanel.textInput(event.retarget(sidePanel))) {
            return true;
        }
        return false;
    }

    @Override
    public void tick() {
        if (disposed) return;
        super.tick();
        FlowManager manager = FlowManager.getInstance();
        ReSyncFlowClient client = manager != null ? manager.existingFlowClient(screen.studioServerId()) : null;
        boolean permissionsAvailable = client != null && client.isPluginChannelAvailable(LuckPermsManagementContract.CHANNEL_ID);
        if (permissionsButton.isVisible() != permissionsAvailable) {
            permissionsButton.setVisible(permissionsAvailable);
            layoutGate.invalidate();
        }
        updateButton.setVisible(screen.hasReSyncUpdateAvailable() && !screen.isReSyncUpdateRunning());
        layoutContainersIfDirty();
        long now = System.currentTimeMillis();
        long metadataStamp = manager != null ? manager.projectBrowserStamp(screen.studioServerId()) : Long.MIN_VALUE;
        if (collaborationChatHighlights.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now)) {
            refreshRowDecorations();
        }
        if (lastProjectMetadataStamp != metadataStamp) scheduleRebuild(metadataStamp);
        ReSyncCollaborationClient collaboration = client != null ? client.collaboration() : null;
        List<BrowserEditor> editors = new ArrayList<>();
        if (collaboration != null) {
            for (ReSyncCollaborationClient.Presence presence : collaboration.snapshot()) {
                if (collaboration.isSelf(presence) || !presence.active() || presence.identity() == null) continue;
                Identifier avatar = screen.collaborationAvatar(presence);
                long revision = avatar != null ? ScreenManager.getInstance().imageAssets().imageRevision(avatar) : 0L;
                editors.add(BrowserEditor.from(presence, screen.collaborationColor(presence), avatar, revision));
            }
        }
        editors.sort(Comparator.comparing(BrowserEditor::sessionId));
        updateCollaboration(editors);
    }

    void updateCollaboration(List<BrowserEditor> editors) {
        if (collaborationEditors.equals(editors)) return;
        collaborationEditors = List.copyOf(editors);
        Set<String> sessions = new HashSet<>();
        for (BrowserEditor editor : editors) sessions.add(editor.sessionId());
        collaborationAvatars.keySet().retainAll(sessions);
        refreshRowDecorations();
    }

    private void refreshRowDecorations() {
        Set<Widget> mounted = new HashSet<>(treeContainer.getWidgets());
        decoratedRows.keySet().retainAll(mounted);
        decoratedRows.forEach((widget, ref) -> decorateTreeNode(ref, widget));
    }

    static boolean retryRebuild(int attempt) {
        return attempt < MAX_REBUILD_RETRIES;
    }

    @Override
    public void setPosition(int x, int y) {
        if (getX() == x && getY() == y) {
            return;
        }
        super.setPosition(x, y);
        if (layoutGate != null) {
            layoutGate.update(getX(), getY(), getWidth(), getHeight());
        }
    }

    @Override
    public void setSize(int width, int height) {
        if (getWidth() == width && getHeight() == height) {
            return;
        }
        super.setSize(width, height);
        if (layoutGate != null) {
            layoutGate.update(getX(), getY(), getWidth(), getHeight());
        }
    }

    public void rebuild() {
        FlowManager manager = FlowManager.getInstance();
        scheduledProjectMetadataStamp = Long.MIN_VALUE;
        scheduledDecorationRevision = Long.MIN_VALUE;
        failedProjectMetadataStamp = Long.MIN_VALUE;
        failedDecorationRevision = Long.MIN_VALUE;
        scheduleRebuild(manager != null ? manager.projectBrowserStamp(screen.studioServerId()) : Long.MIN_VALUE);
    }

    void dispose() {
        disposed = true;
        collaborationEditors = List.of();
        collaborationAvatars.clear();
        decoratedRows.clear();
        collaborationChatHighlights.clear();
        rebuildPublicationGate.invalidate();
        rebuildDrain.clear();
        creationVisibilityGate.close();
        scheduledProjectMetadataStamp = Long.MIN_VALUE;
        scheduledDecorationRevision = Long.MIN_VALUE;
        failedProjectMetadataStamp = Long.MIN_VALUE;
        failedDecorationRevision = Long.MIN_VALUE;
    }

    private void scheduleRebuild(long metadataStamp) {
        scheduleRebuild(metadataStamp, null);
    }

    private void scheduleRebuild(long metadataStamp, RemotePath revealPath) {
        scheduleRebuild(metadataStamp, revealPath, 0);
    }

    private void scheduleRebuild(long metadataStamp, RemotePath revealPath, int attempt) {
        if (disposed) {
            if (ReSyncLifecycleDiagnostics.enabled()) {
                ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_rebuild_suppressed", "serverId",
                    screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection",
                    "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision", metadataStamp,
                    "attempt", attempt, "reason", "disposed");
            }
            return;
        }
        long collaborationRevision = collaborationDecorationRevision;
        long startedAtNanos = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        if (failedProjectMetadataStamp == metadataStamp && failedDecorationRevision == collaborationRevision) {
            if (ReSyncLifecycleDiagnostics.enabled()) {
                ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_rebuild_suppressed", "serverId",
                    screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection",
                    "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision", metadataStamp,
                    "attempt", attempt, "decorationRevision", collaborationRevision, "reason", "previous_failure");
            }
            return;
        }
        if (scheduledProjectMetadataStamp == metadataStamp && scheduledDecorationRevision == collaborationRevision) {
            return;
        }
        long generation = rebuildPublicationGate.next();
        boolean expandAll = !treeInitialized;
        List<RemotePath> expandedPaths = expandedTreePaths;
        long expansionRevision = expandedTreeRevision;
        scheduledProjectMetadataStamp = metadataStamp;
        scheduledDecorationRevision = collaborationRevision;
        if (ReSyncLifecycleDiagnostics.enabled()) {
            ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_rebuild_scheduled", "serverId",
                screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection", "mutationId",
                null, "generation", generation, "authorityEpoch", 0L, "revision", metadataStamp, "attempt", attempt,
                "decorationRevision", collaborationRevision, "expandAll", expandAll, "expandedTreeRevision",
                expansionRevision, "reason", revealPath == null ? "catalog_refresh" : "reveal_path");
        }
        rebuildDrain.submit(new BrowserRebuildRequest(generation, metadataStamp, collaborationRevision, revealPath,
            attempt, expandAll, expandedPaths, expansionRevision, startedAtNanos));
    }

    private void prepareRebuild(BrowserRebuildRequest request) {
        if (disposed || !rebuildPublicationGate.current(request.generation())) {
            if (ReSyncLifecycleDiagnostics.enabled()) {
                ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_rebuild_suppressed", "serverId",
                    screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection",
                    "mutationId", null, "generation", request.generation(), "authorityEpoch", 0L, "revision",
                    request.metadataStamp(), "attempt", request.attempt(), "reason",
                    disposed ? "disposed" : "stale_generation");
            }
            return;
        }
        Set<String> hiddenResourceKeys = Set.copyOf(pendingDeleteKeys);
        Set<String> hiddenFolders = Set.copyOf(pendingDeleteFolders);
        List<BrowserFolder> folders = browserFolders(screen.studioAllFolders()).stream()
            .filter(folder -> !insidePendingFolder(folder.path(), hiddenFolders)).toList();
        List<ReSyncProjectMetadata.ResourceEntry> projectedResources = screen.studioAllResources().stream()
            .filter(resource -> !AutomationDefinitionDraft.supports(resource.getType()))
            .filter(resource -> !hiddenResourceKeys.contains(resource.key()))
            .filter(resource -> !insidePendingFolder(resource.getPath(), hiddenFolders)).toList();
        FlowManager manager = FlowManager.getInstance();
        Map<String, Boolean> activations = manager != null
            ? manager.cachedResourceActivationSnapshot(screen.studioServerId(), projectedResources) : Map.of();
        List<BrowserResource> resources = browserResources(projectedResources, activations);
        Map<String, String> iconPaths = resourceIconPaths(resources);
        AssetBrowserSnapshot snapshot = assetBrowserSnapshot(folders, resources, request.decorationRevision());
        ReSyncProjectTreeProvider provider = prepareTreeProvider(projectRoot, folders, resources);
        List<RemotePath> preparedExpandedPaths = prepareExpandedPaths(provider, request.expandedPaths(),
            request.revealPath(), request.expandAll());
        PreparedBrowserRebuild prepared = new PreparedBrowserRebuild(request.generation(), request.metadataStamp(),
            request.revealPath(), provider, preparedExpandedPaths, request.expandAll(), iconPaths, snapshot,
            request.expandedTreeRevision(), folders.size(), resources.size(), request.attempt(), request.startedAtNanos());
        if (ReSyncLifecycleDiagnostics.enabled()) {
            ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_rebuild_prepared", "serverId",
                screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection", "mutationId",
                null, "generation", request.generation(), "authorityEpoch", 0L, "revision", request.metadataStamp(),
                "attempt", request.attempt(), "decorationRevision", request.decorationRevision(), "folderCount",
                folders.size(), "memberCount", resources.size(), "expandedTreeRevision", request.expandedTreeRevision(),
                "elapsedMs", request.startedAtNanos() == 0L ? -1L : ((
                    System.nanoTime() - request.startedAtNanos()) / 1_000_000L), "reason", "prepared");
        }
        ScreenManager.getInstance().execute(() -> {
            try {
                applyPreparedRebuild(prepared);
            } catch (RuntimeException exception) {
                finishRebuildFailure(request.generation(), request.metadataStamp(), request.decorationRevision(),
                    request.revealPath(), request.attempt(), exception);
            }
        });
    }

    private void handleRebuildFailure(BrowserRebuildRequest request, RuntimeException exception) {
        Runnable failure = () -> finishRebuildFailure(request.generation(), request.metadataStamp(),
            request.decorationRevision(), request.revealPath(), request.attempt(), exception);
        try {
            ScreenManager.getInstance().execute(failure);
        } catch (RuntimeException ignored) {
            failure.run();
        }
    }

    private void finishRebuildFailure(long generation, long metadataStamp, long decorationRevision,
                                      RemotePath revealPath, int attempt, RuntimeException exception) {
        if (disposed || !rebuildPublicationGate.publish(generation, () -> {
            scheduledProjectMetadataStamp = Long.MIN_VALUE;
            scheduledDecorationRevision = Long.MIN_VALUE;
        })) return;
        if (ReSyncLifecycleDiagnostics.enabled()) {
            try {
                ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_projection_failed", "serverId",
                    screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection", "mutationId", null,
                    "generation", generation, "authorityEpoch", 0L, "revision", metadataStamp, "attempt", attempt + 1,
                    "reason", exception != null ? TaskIdentities.failureName(exception) : "rejected");
            } catch (RuntimeException ignored) {
            }
        }
        if (retryRebuild(attempt)) {
            scheduleRebuild(metadataStamp, revealPath, attempt + 1);
        } else {
            failedProjectMetadataStamp = metadataStamp;
            failedDecorationRevision = decorationRevision;
        }
    }

    private void applyPreparedRebuild(PreparedBrowserRebuild prepared) {
        FlowManager manager = FlowManager.getInstance();
        String rejectionReason = disposed ? "disposed" : manager == null ? "manager_missing"
            : !rebuildPublicationGate.current(prepared.generation()) ? "stale_generation"
            : prepared.metadataStamp() != manager.projectBrowserStamp(screen.studioServerId()) ? "metadata_changed"
            : prepared.metadataStamp() != scheduledProjectMetadataStamp ? "scheduled_metadata_changed"
            : prepared.snapshot().collaborationRevision() != scheduledDecorationRevision ? "decoration_changed" : "";
        if (!rejectionReason.isEmpty()) {
            if (ReSyncLifecycleDiagnostics.enabled()) {
                ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_rebuild_prepared_rejected", "serverId",
                    screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection", "mutationId",
                    null, "generation", prepared.generation(), "authorityEpoch", 0L, "revision", prepared.metadataStamp(),
                    "expectedMetadataStamp", scheduledProjectMetadataStamp, "expectedDecorationRevision",
                    scheduledDecorationRevision, "actualDecorationRevision", prepared.snapshot().collaborationRevision(),
                    "elapsedMs", prepared.startedAtNanos() == 0L ? -1L : ((
                    System.nanoTime() - prepared.startedAtNanos()) / 1_000_000L), "reason", rejectionReason);
            }
            if ("manager_missing".equals(rejectionReason)) {
                finishRebuildFailure(prepared.generation(), prepared.metadataStamp(),
                    prepared.snapshot().collaborationRevision(), prepared.revealPath(), prepared.attempt(),
                    new IllegalStateException("Flow manager unavailable during browser publication"));
            }
            return;
        }
        if (prepared.expandedTreeRevision() != expandedTreeRevision) {
            boolean published = rebuildPublicationGate.publish(prepared.generation(), () -> {
                scheduledProjectMetadataStamp = Long.MIN_VALUE;
                scheduledDecorationRevision = Long.MIN_VALUE;
            });
            if (!published) {
                if (ReSyncLifecycleDiagnostics.enabled()) {
                    ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_rebuild_prepared_rejected", "serverId",
                        screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection",
                        "mutationId", null, "generation", prepared.generation(), "authorityEpoch", 0L,
                        "revision", prepared.metadataStamp(), "expectedExpandedTreeRevision", expandedTreeRevision,
                        "actualExpandedTreeRevision", prepared.expandedTreeRevision(), "reason", "stale_generation");
                }
                return;
            }
            if (ReSyncLifecycleDiagnostics.enabled()) {
                ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_rebuild_prepared_rejected", "serverId",
                    screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection", "mutationId",
                    null, "generation", prepared.generation(), "authorityEpoch", 0L, "revision", prepared.metadataStamp(),
                    "expectedExpandedTreeRevision", expandedTreeRevision, "actualExpandedTreeRevision",
                    prepared.expandedTreeRevision(), "reason", "expanded_tree_changed");
            }
            scheduleRebuild(prepared.metadataStamp(), prepared.revealPath());
            return;
        }
        if (!rebuildPublicationGate.publish(prepared.generation(), () -> publishPreparedRebuild(prepared))) {
            if (ReSyncLifecycleDiagnostics.enabled()) {
                ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_rebuild_prepared_rejected", "serverId",
                    screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection", "mutationId",
                    null, "generation", prepared.generation(), "authorityEpoch", 0L, "revision", prepared.metadataStamp(),
                    "reason", "stale_generation");
            }
            return;
        }
        if (ReSyncLifecycleDiagnostics.enabled()) {
            ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_projection_applied", "serverId",
                screen.studioServerId(), "resourceKey", "browser:catalog", "requestId", "projection", "mutationId", null,
                "generation", prepared.generation(), "authorityEpoch", 0L, "revision", prepared.metadataStamp(), "folderCount",
                prepared.folderCount(), "memberCount", prepared.resourceCount(), "elapsedMs",
                prepared.startedAtNanos() == 0L ? -1L : ((
                    System.nanoTime() - prepared.startedAtNanos()) / 1_000_000L), "publication", "committed");
        }
    }

    private void publishPreparedRebuild(PreparedBrowserRebuild prepared) {
        scheduledProjectMetadataStamp = Long.MIN_VALUE;
        scheduledDecorationRevision = Long.MIN_VALUE;
        failedProjectMetadataStamp = Long.MIN_VALUE;
        failedDecorationRevision = Long.MIN_VALUE;
        RemotePath revealPath = prepared.revealPath();
        lastProjectMetadataStamp = prepared.metadataStamp();
        lastPublishedBrowserGeneration = prepared.generation();
        resourceIconPaths = prepared.iconPaths();
        expandedTreePaths = prepared.expandedPaths();
        if (prepared.snapshot().equals(lastAssetBrowserSnapshot)) {
            if (revealPath != null) treeExplorer.expandToPath(revealPath);
            publishVisibleCreatedResources(treeProvider, prepared.generation(), prepared.metadataStamp());
            return;
        }
        lastAssetBrowserSnapshot = prepared.snapshot();
        BrowserSelectionState selection = retainSelection(captureSelection(), prepared.provider(), projectRoot);
        treeProvider = prepared.provider();
        pendingSelectionRestore = selection;
        applySelection(selection);
        mountPreparedTree(prepared.provider(), prepared.expandAll(), revealPath);
        treeContainer.setScrollOffset(selection.scrollOffset());
        treeContainer.setTargetScrollOffset(selection.scrollOffset());
        layoutGate.invalidate();
        layoutContainersIfDirty();
        treeInitialized = true;
        publishVisibleCreatedResources(treeProvider, prepared.generation(), prepared.metadataStamp());
    }

    private void mountPreparedTree(ReSyncProjectTreeProvider provider, boolean expandAll, RemotePath revealPath) {
        decoratedRows.clear();
        if (treeInitialized) {
            browser.replaceProvider(provider, revealPath);
            return;
        }
        browser.setWorkspace(projectRoot, provider, expandAll, expandedTreePaths);
        if (revealPath != null) {
            treeExplorer.expandToPath(revealPath);
        }
    }

    private BrowserSelectionState captureSelection() {
        BrowserSelectionState selected = captureTreeSelection();
        if (selected.size() > 0) {
            return selected;
        }
        if (pendingSelectionRestore.size() > 0) {
            return new BrowserSelectionState(pendingSelectionRestore.resourceKeys(), pendingSelectionRestore.folderPaths(),
                pendingSelectionRestore.projectRoot(), treeContainer.getScrollOffset());
        }
        Set<String> resourceKeys = new LinkedHashSet<>();
        Set<String> folderPaths = new LinkedHashSet<>();
        if (selectedResource != null) {
            resourceKeys.add(selectedResource.key());
        }
        if (selectedFolder != null) {
            folderPaths.add(selectedFolder.getPath());
        }
        return new BrowserSelectionState(resourceKeys, folderPaths, selectedProjectRoot, treeContainer.getScrollOffset());
    }

    private BrowserSelectionState captureTreeSelection() {
        Set<String> resourceKeys = new LinkedHashSet<>();
        Set<String> folderPaths = new LinkedHashSet<>();
        boolean projectSelected = false;
        for (WorkspaceTreeExplorer.NodeRef ref : treeExplorer.selectedNodeRefs()) {
            if (ref == null || ref.path() == null) {
                continue;
            }
            if (ref.directory()) {
                String folderPath = treeProvider.folderPath(ref.path());
                if (folderPath != null) {
                    if (projectRoot.equals(ref.path())) {
                        projectSelected = true;
                    } else {
                        folderPaths.add(folderPath);
                    }
                }
                continue;
            }
            ReSyncProjectMetadata.ResourceEntry resource = treeProvider.resource(ref.path());
            if (resource != null) {
                resourceKeys.add(resource.key());
            }
        }
        return new BrowserSelectionState(resourceKeys, folderPaths, projectSelected, treeContainer.getScrollOffset());
    }

    static BrowserSelectionState retainSelection(BrowserSelectionState selection, ReSyncProjectTreeProvider provider,
                                                  RemotePath root) {
        if (selection == null || provider == null || root == null) {
            return BrowserSelectionState.empty();
        }
        Set<String> resources = selection.resourceKeys().stream().filter(key -> provider.resourcePath(key) != null)
            .collect(Collectors.toUnmodifiableSet());
        Set<String> folders = selection.folderPaths().stream()
            .filter(path -> provider.folder(pathForFolder(root, path)) != null)
            .collect(Collectors.toUnmodifiableSet());
        return new BrowserSelectionState(resources, folders, selection.projectRoot(), selection.scrollOffset());
    }

    private void applySelection(BrowserSelectionState selection) {
        selectedResource = null;
        selectedFolder = null;
        selectedProjectRoot = false;
        nameInput.setText("");
        if (selection == null || selection.size() != 1) {
            return;
        }
        if (!selection.resourceKeys().isEmpty()) {
            RemotePath path = treeProvider.resourcePath(selection.resourceKeys().iterator().next());
            selectedResource = path != null ? treeProvider.resource(path) : null;
            if (selectedResource != null) {
                nameInput.setText(selectedResource.getId());
            }
            return;
        }
        if (!selection.folderPaths().isEmpty()) {
            selectedFolder = treeProvider.folder(pathForFolder(projectRoot, selection.folderPaths().iterator().next()));
            if (selectedFolder != null) {
                nameInput.setText(selectedFolder.getName());
            }
            return;
        }
        selectedProjectRoot = selection.projectRoot();
    }

    private void rebuild(RemotePath revealPath) {
        FlowManager manager = FlowManager.getInstance();
        scheduledProjectMetadataStamp = Long.MIN_VALUE;
        scheduledDecorationRevision = Long.MIN_VALUE;
        scheduleRebuild(manager != null ? manager.projectBrowserStamp(screen.studioServerId()) : Long.MIN_VALUE, revealPath);
    }

    private List<BrowserFolder> browserFolders(List<ReSyncProjectMetadata.FolderEntry> folders) {
        List<BrowserFolder> snapshots = new ArrayList<>(folders.size());
        for (ReSyncProjectMetadata.FolderEntry folder : folders) {
            snapshots.add(new BrowserFolder(folder.getPath(), folder.getParentPath(), folder.getName(), folder.getSortOrder(), folder.isCollapsed()));
        }
        return List.copyOf(snapshots);
    }

    static boolean insidePendingFolder(String path, Set<String> folders) {
        if (path == null || path.isBlank() || folders == null || folders.isEmpty()) {
            return false;
        }
        return folders.stream().anyMatch(folder -> path.equals(folder) || path.startsWith(folder + "/"));
    }

    private List<BrowserResource> browserResources(List<ReSyncProjectMetadata.ResourceEntry> resources,
                                                   Map<String, Boolean> activations) {
        List<BrowserResource> snapshots = new ArrayList<>(resources.size());
        for (ReSyncProjectMetadata.ResourceEntry resource : resources) {
            String iconPath = screen.studioResourceIconPath(resource.getType(), resource.getId());
            snapshots.add(new BrowserResource(resource.getType(), resource.getId(), resource.getDisplayName(), resource.getPath(),
                resource.getSortOrder(), iconPath, resourceEnabled(resource, activations)));
        }
        return List.copyOf(snapshots);
    }

    private Map<String, String> resourceIconPaths(List<BrowserResource> resources) {
        Map<String, String> paths = new HashMap<>();
        for (BrowserResource resource : resources) {
            paths.put(resource.key(), resource.iconPath());
        }
        return Map.copyOf(paths);
    }

    private AssetBrowserSnapshot assetBrowserSnapshot(List<BrowserFolder> folders,
                                                       List<BrowserResource> resources,
                                                       long collaborationRevision) {
        List<String> folderSnapshots = new ArrayList<>();
        for (BrowserFolder folder : folders) {
            folderSnapshots.add(String.join("\u0001",
                folder.path(),
                folder.parentPath(),
                folder.name(),
                String.valueOf(folder.sortOrder()),
                String.valueOf(folder.collapsed())));
        }
        Collections.sort(folderSnapshots);
        List<String> resourceSnapshots = new ArrayList<>();
        for (BrowserResource resource : resources) {
            resourceSnapshots.add(String.join("\u0001",
                resource.type(),
                resource.id(),
                resource.displayName(),
                resource.path(),
                String.valueOf(resource.sortOrder()),
                resource.iconPath(),
                String.valueOf(resource.enabled())));
        }
        Collections.sort(resourceSnapshots);
        return new AssetBrowserSnapshot(folderSnapshots, resourceSnapshots, collaborationRevision);
    }

    private static List<RemotePath> prepareExpandedPaths(ReSyncProjectTreeProvider provider,
                                                         List<RemotePath> currentExpandedPaths,
                                                         RemotePath revealPath, boolean expandAll) {
        if (expandAll) {
            return provider.folderDirectoryPaths();
        }
        if (revealPath == null || provider.folderPath(revealPath) == null || currentExpandedPaths.contains(revealPath)) {
            return currentExpandedPaths;
        }
        List<RemotePath> expandedPaths = new ArrayList<>(currentExpandedPaths.size() + 1);
        expandedPaths.addAll(currentExpandedPaths);
        expandedPaths.add(revealPath);
        return List.copyOf(expandedPaths);
    }

    public void highlightCollaborationChat(String type, String resourceId, int color) {
        collaborationChatHighlights.put(resourceKey(type, resourceId),
            new CollaborationChatHighlight(color, System.currentTimeMillis() + 8000L));
        refreshRowDecorations();
    }

    private void prepareTreeNode(WorkspaceTreeExplorer.NodeRef ref, FileEntryWidget widget) {
        decoratedRows.put(widget, ref);
        ReSyncProjectMetadata.ResourceEntry resource = treeProvider.resource(ref.path());
        if (restoreSelection(ref, resource)) widget.setSelected(true);
        decorateTreeNode(ref, widget);
    }

    private void decorateTreeNode(WorkspaceTreeExplorer.NodeRef ref, FileEntryWidget widget) {
        widget.setPersistentHighlight(false);
        widget.setPersistentAccent(null);
        widget.setGradientEnabled(false);
        widget.setTrailingWidgets(List.of());
        ReSyncProjectMetadata.ResourceEntry resource = treeProvider.resource(ref.path());
        List<AnimatedWidget> accessories = new ArrayList<>();
        List<BrowserEditor> editors = resource != null ? resourceEditors(resource) : collapsedFolderEditors(ref);
        CollaborationChatHighlight chatHighlight = resource != null
            ? collaborationChatHighlights.get(resourceKey(resource.getType(), resource.getId()))
            : collapsedFolderChatHighlight(ref);
        if (editors.isEmpty() && chatHighlight == null) {
            widget.setTrailingWidgets(accessories);
            return;
        }
        List<Integer> colors = new ArrayList<>();
        editors.forEach(editor -> colors.add(editor.color()));
        if (chatHighlight != null) {
            colors.add(chatHighlight.color());
        }
        int color = CollaborationVisuals.blend(colors, 0xFF4E8CFF);
        Accent accent = CollaborationVisuals.accent(color);
        widget.accentType = accent;
        widget.setPersistentHighlight(true);
        widget.setPersistentAccent(accent);
        widget.setGradientEnabled(colors.size() > 1);
        List<BrowserAvatarWidget> avatars = new ArrayList<>();
        List<BrowserEditor> visibleEditors = editors.stream().limit(4).toList();
        for (int index = 0; index < visibleEditors.size(); index++) {
            BrowserEditor presence = visibleEditors.get(index);
            Identifier icon = presence.avatar();
            BrowserAvatarWidget avatar = collaborationAvatars.computeIfAbsent(presence.sessionId(),
                ignored -> new BrowserAvatarWidget(screen, presence.sessionId(),
                    icon != null ? icon : Identifier.icon("steve.png")));
            avatar.update(icon != null ? icon : Identifier.icon("steve.png"));
            avatars.add(avatar);
        }
        accessories.addAll(avatars);
        widget.setTrailingWidgets(accessories);
    }

    private boolean restoreSelection(WorkspaceTreeExplorer.NodeRef ref, ReSyncProjectMetadata.ResourceEntry resource) {
        if (ref == null || ref.path() == null) {
            return false;
        }
        if (resource != null) {
            return pendingSelectionRestore.resourceKeys().contains(resource.key());
        }
        if (!ref.directory()) {
            return false;
        }
        if (projectRoot.equals(ref.path())) {
            return pendingSelectionRestore.projectRoot();
        }
        String folderPath = treeProvider.folderPath(ref.path());
        return folderPath != null && pendingSelectionRestore.folderPaths().contains(folderPath);
    }

    private List<BrowserEditor> collapsedFolderEditors(WorkspaceTreeExplorer.NodeRef ref) {
        if (ref == null || !ref.directory() || ref.expanded()) {
            return List.of();
        }
        return collaborationEditors.stream()
            .filter(presence -> {
                RemotePath resourcePath = treeProvider.resourcePath(presence.resourceType(), presence.resourceId());
                return resourcePath != null && resourcePath.startsWith(ref.path());
            })
            .toList();
    }

    private CollaborationChatHighlight collapsedFolderChatHighlight(WorkspaceTreeExplorer.NodeRef ref) {
        if (ref == null || !ref.directory() || ref.expanded()) {
            return null;
        }
        return collaborationChatHighlights.entrySet().stream()
            .filter(entry -> {
                RemotePath resourcePath = treeProvider.resourcePath(entry.getKey());
                return resourcePath != null && resourcePath.startsWith(ref.path());
            })
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    private List<BrowserEditor> resourceEditors(ReSyncProjectMetadata.ResourceEntry resource) {
        return collaborationEditors.stream()
            .filter(presence -> Objects.equals(resource.getType(), presence.resourceType())
                && Objects.equals(resource.getId(), presence.resourceId()))
            .toList();
    }

    private static String resourceKey(String type, String resourceId) {
        return (type != null ? type : "") + '\u0000' + (resourceId != null ? resourceId : "");
    }

    private boolean resourceEnabled(ReSyncProjectMetadata.ResourceEntry resource, Map<String, Boolean> activations) {
        return resource == null || activations == null || activations.getOrDefault(resource.key(), true);
    }

    private void rebuildCurrentFolderView() {
        rebuild();
    }

    private void selectFolder(String path) {
        selectFolder(path, true);
    }

    private void selectFolder(String path, boolean recordHistory) {
        String normalizedPath = ReSyncProjectMetadata.normalizePath(path);
        if (recordHistory && !Objects.equals(normalizedPath, currentFolder)) {
            backHistory.push(currentFolder);
            forwardHistory.clear();
        }
        currentFolder = normalizedPath;
        nameInput.setText("");
        selectedResource = null;
        selectedFolder = null;
        selectedProjectRoot = false;
        rebuildCurrentFolderView();
    }

    private void navigateHistoryBack() {
        if (!backHistory.isEmpty()) {
            forwardHistory.push(currentFolder);
            selectFolder(backHistory.pop(), false);
            return;
        }
        String parentFolder = parentFolder(currentFolder);
        if (parentFolder != null) {
            selectFolder(parentFolder, false);
        }
    }

    private void navigateHistoryForward() {
        if (forwardHistory.isEmpty()) {
            return;
        }
        backHistory.push(currentFolder);
        selectFolder(forwardHistory.pop(), false);
    }

    private String parentFolder(String path) {
        String normalizedPath = ReSyncProjectMetadata.normalizePath(path);
        if (normalizedPath.isBlank()) {
            return null;
        }
        int separator = normalizedPath.lastIndexOf('/');
        return separator >= 0 ? normalizedPath.substring(0, separator) : "";
    }

    private void openTreeFile(RemotePath path) {
        ReSyncProjectMetadata.ResourceEntry resource = treeProvider.resource(path);
        if (resource == null) {
            return;
        }
        pendingSelectionRestore = BrowserSelectionState.empty();
        selectedResource = resource;
        selectedFolder = null;
        selectedProjectRoot = false;
        nameInput.setText(resource.getId());
        if (ReSyncLifecycleDiagnostics.enabled()) {
            ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_open_dispatched", "serverId",
                screen.studioServerId(), "resourceKey", resource.getType() + ":" + resource.getId(), "requestId",
                "browser", "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision", -1L,
                "browserPath", path.toString());
        }
        screen.openStudioResource(resource);
    }

    private void openTreeNode(WorkspaceTreeExplorer.NodeRef ref) {
        if (ref == null) {
            return;
        }
        if (ref.directory()) {
            String path = treeProvider.folderPath(ref.path());
            if (path != null) {
                selectFolder(path);
            }
            return;
        }
        openTreeFile(ref.path());
    }

    private void activateTreeNode(WorkspaceTreeExplorer.NodeRef ref) {
        if (ref == null) {
            return;
        }
        pendingSelectionRestore = BrowserSelectionState.empty();
        if (ref.directory()) {
            selectedResource = null;
            selectedFolder = treeProvider.folder(ref.path());
            selectedProjectRoot = projectRoot.equals(ref.path());
            if (selectedFolder != null) {
                nameInput.setText(selectedFolder.getName());
            } else if (projectRoot.equals(ref.path())) {
                nameInput.setText("");
            }
            return;
        }
        ReSyncProjectMetadata.ResourceEntry resource = treeProvider.resource(ref.path());
        if (resource != null) {
            selectedResource = resource;
            selectedFolder = null;
            selectedProjectRoot = false;
            nameInput.setText(resource.getId());
        }
    }

    private void startResourceDrag(WorkspaceTreeExplorer.NodeRef ref) {
        if (ref == null || ref.directory()) {
            return;
        }
        ReSyncProjectMetadata.ResourceEntry resource = treeProvider.resource(ref.path());
        if (resource == null) {
            return;
        }
        FileEntryWidget source = null;
        for (Object widget : treeContainer.getWidgets()) {
            if (widget instanceof FileEntryWidget entry && ref.path().equals(entry.getFileEntry().path)) {
                source = entry;
                break;
            }
        }
        if (source == null) {
            return;
        }
        FileEntryWidget transition = new FileEntryWidget.Builder(source.getFileEntry(), treeProvider, Collections.emptyList(), new Object())
            .pos(source.getX(), source.getY())
            .size(source.getWidth(), source.getHeight())
            .minimal(true)
            .treeRow(true)
            .entranceAnimation(false)
            .animateElevation(false)
            .transparent(true)
            .animateLayout(true)
            .animateLayoutPosition(false)
            .build();
        transition.setSelected(true);
        transition.setRelativeScissor(0, 0, 0, 0);
        transition.snapLayout();
        screen.beginStudioResourceDrag(
            new ReSyncResourceDragPayload(resource.getType(), resource.getId(), resource.getDisplayName(), resource.getPath()),
            transition,
            Math.clamp(lastMouseX - source.getX(), 0, source.getWidth()),
            Math.clamp(lastMouseY - source.getY(), 0, source.getHeight())
        );
    }

    private void rightClickTreeNode(WorkspaceTreeExplorer.NodeRef ref) {
        pendingSelectionRestore = BrowserSelectionState.empty();
        treeContainer.clearSelection();
        if (ref != null) {
            for (Object widget : treeContainer.getWidgets()) {
                if (widget instanceof FileEntryWidget entry && ref.path().equals(entry.getFileEntry().path)) {
                    treeContainer.addSelectedWidget(entry);
                    break;
                }
            }
        }
        selectedFolder = null;
        selectedResource = null;
        selectedProjectRoot = false;
        if (ref != null && ref.directory()) {
            selectedFolder = treeProvider.folder(ref.path());
            if (selectedFolder != null) {
                nameInput.setText(selectedFolder.getName());
            } else if (projectRoot.equals(ref.path())) {
                selectedProjectRoot = true;
                nameInput.setText("");
            }
        } else if (ref != null) {
            selectedResource = treeProvider.resource(ref.path());
            if (selectedResource != null) {
                nameInput.setText(selectedResource.getId());
            }
        }
        showExplorerMenu(lastMouseX, lastMouseY);
    }

    protected boolean showExplorerMenu(int mouseX, int mouseY) {
        BrowserSelection selection = browserSelection();
        if (selection.size() == 0 && !selection.projectRoot()) {
            return false;
        }
        String targetFolder = selectionDestination(selection);
        ContextMenuWidget.Builder builder = new ContextMenuWidget.Builder(screen);
        if (selection.size() > 0) {
            builder.addHeaderButton("copy.png", this::copySelected, "Copy")
                .addHeaderButton("cut.png", this::cutSelected, "Cut");
        }
        if (clipboard != null && targetFolder != null) {
            builder.addHeaderButton("paste.png", this::pasteSelected, "Paste");
        }
        if (selection.selectedCount() == 1 && canRename(selection)) {
            builder.addHeaderButton("edit.png", this::renameSelected, "Rename");
        }
        if (canDelete(selection)) {
            builder.addHeaderButton("delete.png", this::deleteSelected, "Delete", ThemeManager.getAccent("danger"));
        }
        if (!selection.resources().isEmpty() && selection.folders().isEmpty()) {
            List<ReSyncProjectMetadata.ResourceEntry> resources = selection.resources();
            FlowManager manager = FlowManager.getInstance();
            if (manager != null && resources.stream().allMatch(resource -> manager.supportsResourceActivation(resource.getType()))) {
                boolean allEnabled = resources.stream().allMatch(resource ->
                        manager.isResourceEnabled(screen.studioServerId(), resource.getType(), resource.getId()));
                builder.addIconItem(allEnabled ? "Disable" : "Enable", allEnabled ? "stop.png" : "start.png",
                        () -> toggleResourceActivation(resources, !allEnabled),
                        allEnabled ? "Keep Selected Resources Saved Without Running" : "Run Selected Resources");
            }
        }
        if (targetFolder != null) {
            builder.addIconItem("Create", "add.png", () -> ScreenManager.getInstance().execute(() -> showCreateMenu(mouseX + 12, mouseY, targetFolder, true)), "Create");
        }
        screen.showStudioContextMenu(mouseX, mouseY, builder);
        return true;
    }

    private void showCreateMenu() {
        showCreateMenu(createButton.getX(), createButton.getY() + createButton.getHeight() + 2,
            selectedCreateTargetFolder(), selectionDestination(browserSelection()) != null);
    }

    private void showCreateMenu(int mouseX, int mouseY, String targetFolder, boolean explicitFolder) {
        closeCreateSelector();
        ItemSelectorWidget[] selectorRef = new ItemSelectorWidget[1];
        var overlay = ScreenManager.getInstance().getPopupOverlay();
        ItemSelectorWidget selector = addCreateSelectorItems(new ItemSelectorWidget.Builder(overlay), targetFolder, explicitFolder)
            .size(220, 260)
            .entryHeight(18)
            .searchPlaceholder("Search Actions")
            .emptyMessage("No Actions")
            .usageScope("resync-content-browser-create")
            .dismissOnSelect(true)
            .onClose(() -> closeCreateSelector(selectorRef[0]))
            .build();
        selector.setLayer(900);
        selector.setPriority(30);
        selectorRef[0] = selector;
        createSelector = selector;
        overlay.addDrawableChild(selector);
        selector.show(mouseX, mouseY);
    }

    private ItemSelectorWidget.Builder addCreateSelectorItems(ItemSelectorWidget.Builder builder, String targetFolder,
                                                              boolean explicitFolder) {
        return builder
            .addItem("New Folder", "folder.png", "Create Folder", "folder directory", () -> showCreateResourcePopup(ReSyncResourceDragPayload.FOLDER, targetFolder))
            .addItem("New Flow", "flow.png", "Create Flow", "flow graph", () -> showCreateResourcePopup(ReSyncResourceDragPayload.FLOW, targetFolder))
            .addItem("New Function", "json.png", "Create Function", "function mcfunction", () -> showCreateResourcePopup(ReSyncResourceDragPayload.FUNCTION, targetFolder))
            .addItem("New Command", "terminal.png", "Create Command", "command terminal", () -> showCreateResourcePopup(ReSyncResourceDragPayload.COMMAND, targetFolder))
            .addItem("New Content", "content.png", "Create Content", "content item block armor",
                () -> showCreateContentPopup(createDestination(ReSyncResourceDragPayload.CUSTOM_CONTENT, targetFolder), explicitFolder))
            .addItem("New GUI", "fullPanel.png", "Create GUI", "gui interface inventory", () -> showCreateResourcePopup(ReSyncResourceDragPayload.GUI, targetFolder))
            .addItem("New Scoreboard", "panel.png", "Create Scoreboard", "scoreboard sidebar", () -> showCreateResourcePopup(ReSyncResourceDragPayload.SCOREBOARD, targetFolder))
            .addItem("New Tab", "topPanel.png", "Create Tab", "tab player list", () -> showCreateResourcePopup(ReSyncResourceDragPayload.TAB, targetFolder))
            .addItem("New Chat", "chat.png", "Create Chat", "chat format", () -> showCreateResourcePopup(ReSyncResourceDragPayload.CHAT, targetFolder))
            .addItem("New Component Builder", "item.png", "Create Component Builder", "item components template", () -> showCreateResourcePopup(ReSyncResourceDragPayload.COMPONENT_BUILDER, targetFolder))
            .addItem("New MOTD", "hi.png", "Create MOTD", "motd server list", () -> showCreateResourcePopup(ReSyncResourceDragPayload.MOTD_PROFILE, targetFolder))
            .addItem("New Message Rule", "edit.png", "Create Message Rule", "message rule", () -> showCreateResourcePopup(ReSyncResourceDragPayload.MESSAGE_RULE, targetFolder))
            .addItem("New Recipe", "crafting.png", "Create Recipe", "recipe crafting", () -> showCreateResourcePopup(ReSyncResourceDragPayload.RECIPE_DEFINITION, targetFolder))
            .addItem("New Advancement", "advancement.png", "Create Advancement", "advancement achievement", () -> showCreateResourcePopup(ReSyncResourceDragPayload.ADVANCEMENT_TREE, targetFolder))
            .addItem("New Dialog", "VanillaButton.png", "Create Dialog", "dialog dialogue", () -> showCreateResourcePopup(ReSyncResourceDragPayload.DIALOG, targetFolder))
            .addItem("New Trade", "trade.png", "Create Trade", "trade profile merchant", () -> showCreateResourcePopup(ReSyncResourceDragPayload.TRADE_PROFILE, targetFolder))
            .addItem("New NPC", "steve.png", "Create NPC", "npc entity", () -> showCreateResourcePopup(ReSyncResourceDragPayload.NPC_DEFINITION, targetFolder))
            .addItem("New Loot Table", "resources.png", "Create Loot Table", "loot table drops", () -> showCreateResourcePopup(ReSyncResourceDragPayload.LOOT_TABLE, targetFolder))
            .addItem("New Text", "text.png", "Create Text", "text template", () -> showCreateResourcePopup(ReSyncResourceDragPayload.TEXT_TEMPLATE, targetFolder))
            .addItem("New Variable", "snippets.png", "Create Variable", "variable reusable value", () -> showCreateResourcePopup(ReSyncResourceDragPayload.VARIABLE_DEFINITION, targetFolder))
            .addItem("New Timer", "history.png", "Create Timer", "timer countdown", () -> showCreateResourcePopup(ReSyncResourceDragPayload.TIMER_DEFINITION, targetFolder))
            .addItem("New Schedule", "calendar.png", "Create Schedule", "schedule timed job", () -> showCreateResourcePopup(ReSyncResourceDragPayload.SCHEDULE_DEFINITION, targetFolder))
            .addItem("New World", "earth.png", "Create World", "world level", () -> showCreateWorldPopup(targetFolder))
            .addItem("Import Worlds", "download.png", "Import Worlds", "import existing worlds", () -> {
                FlowManager manager = FlowManager.getInstance();
                if (manager != null) {
                    manager.importWorlds(screen.studioServerId());
                }
            })
            .addItem("Scan Worlds", "search.png", "Scan Worlds", "scan discover worlds", () -> {
                FlowManager manager = FlowManager.getInstance();
                if (manager != null) {
                    manager.scanWorlds(screen.studioServerId());
                }
            })
            .addItem("New WorldGen", "map.png", "Create WorldGen", "worldgen world generation", () -> showCreateResourcePopup(ReSyncResourceDragPayload.WORLDGEN, targetFolder));
    }

    private void closeCreateSelector() {
        closeCreateSelector(createSelector);
    }

    private void closeCreateSelector(ItemSelectorWidget selector) {
        if (selector != null) {
            selector.onClose = null;
            selector.hide();
            ScreenManager.getInstance().getPopupOverlay().remove(selector);
        }
        if (selector == createSelector) {
            createSelector = null;
        }
        screen.clearStudioFocus();
    }

    private void showCreateWorldPopup(String targetFolder) {
        String destination = createDestination(ReSyncResourceDragPayload.WORLD, targetFolder);
        long startedAtNanos = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        WorldResourceCreator.showCreatePopup(screen, screen.studioServerId(), destination, worldName -> {
            if (ReSyncLifecycleDiagnostics.enabled()) {
                ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_create_callback", "serverId",
                    screen.studioServerId(), "resourceKey", ReSyncResourceDragPayload.WORLD + ":" + worldName,
                    "requestId", "create", "mutationId", null, "generation", -1L, "authorityEpoch", 0L,
                    "revision", -1L, "expectedResult", worldName != null && !worldName.isBlank(),
                    "resourcePresent", worldName != null && !worldName.isBlank(), "targetFolder", destination,
                    "elapsedMs", startedAtNanos == 0L ? -1L : ((
                        System.nanoTime() - startedAtNanos) / 1_000_000L), "reason", "settled_callback");
            }
            awaitCreatedResourceVisibility(new ReSyncResourceCreator.Result(ReSyncResourceDragPayload.WORLD,
                worldName, worldName), destination, startedAtNanos);
        });
    }

    private void showCreateResourcePopup(String type, String targetFolder) {
        String destination = createDestination(type, targetFolder);
        if (ReSyncResourceDragPayload.CUSTOM_CONTENT.equals(type)) {
            showCreateContentPopup(destination, targetFolder != null);
            return;
        }
        WeakReference<ReSyncContentBrowserWidget> widgetReference = new WeakReference<>(this);
        long startedAtNanos = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        ReSyncResourceCreator.showCreatePopup(screen, screen.studioServerId(), type, destination, null, result -> {
            traceCreateCallback(type, null, result, destination, startedAtNanos);
            ReSyncContentBrowserWidget widget = widgetReference.get();
            if (widget != null) {
                widget.awaitCreatedResourceVisibility(result, destination, startedAtNanos);
            }
        });
    }

    private String createDestination(String type, String targetFolder) {
        if (targetFolder != null) {
            return ReSyncProjectMetadata.normalizePath(targetFolder);
        }
        if (ReSyncResourceDragPayload.FOLDER.equals(type)) {
            return ReSyncProjectMetadata.normalizePath(currentFolder);
        }
        return ReSyncProjectMetadata.normalizePath(ReSyncResourceType.defaultFolderFor(type));
    }

    private static String defaultContentFolder(String type) {
        return switch (type == null ? "" : type) {
            case "armor" -> "Content/Armor";
            case "block" -> "Content/Blocks";
            case "projectile" -> "Content/Projectiles";
            default -> "Content/Items";
        };
    }

    private String selectedCreateTargetFolder() {
        String destination = selectionDestination(browserSelection());
        return destination != null ? destination : ReSyncProjectMetadata.normalizePath(currentFolder);
    }

    private void showCreateContentPopup(String targetFolder, boolean explicitFolder) {
        PopupWidget.Builder builder = new PopupWidget.Builder("Create Content")
            .setResizable(false)
            .onClose(this::closeCreateContentSearchSelector);
        TextInputWidget nameInput = new TextInputWidget.Builder()
            .placeholder("Content Name")
            .size(240, 22)
            .build();
        TextInputWidget idInput = new TextInputWidget.Builder()
            .text(ReSyncResourceCreator.suggestedId(screen.studioServerId(), ReSyncResourceDragPayload.CUSTOM_CONTENT, targetFolder))
            .placeholder("fireSword")
            .size(240, 22)
            .build();
        String[] selectedType = {"item"};
        String[] selectedProvider = {"vanilla"};
        String[] selectedAsset = {defaultContentMaterial(selectedType[0])};
        requestContentAssetCatalogs(selectedType[0], selectedProvider[0]);
        AnimatedButton assetButton = new AnimatedButton.Builder()
            .label(selectedAsset[0])
            .size(220, 20)
            .entranceAnimation(false)
            .build();
        DropDownWidget<String> typeDropdown = createContentDropdown(List.of("item", "armor", "block", "projectile"), selectedType[0], value -> {
            selectedType[0] = value;
            selectedAsset[0] = "vanilla".equalsIgnoreCase(selectedProvider[0]) ? defaultContentMaterial(value) : "";
            assetButton.setMessage(assetButtonLabel(selectedAsset[0], selectedProvider[0]));
            requestContentAssetCatalogs(selectedType[0], selectedProvider[0]);
        });
        DropDownWidget<String> providerDropdown = createContentDropdown(providerOptions(), selectedProvider[0], value -> {
            selectedProvider[0] = value;
            selectedAsset[0] = "vanilla".equalsIgnoreCase(value) ? defaultContentMaterial(selectedType[0]) : "";
            assetButton.setMessage(assetButtonLabel(selectedAsset[0], value));
            requestContentAssetCatalogs(selectedType[0], selectedProvider[0]);
        });
        assetButton.setAction(() -> {
            List<String> options = contentAssetOptions(selectedType[0], selectedProvider[0]);
            showCreateContentSearchSelector(options, selectedAsset[0], value -> {
                if (!isRealContentOption(value)) {
                    return;
                }
                selectedAsset[0] = "vanilla".equalsIgnoreCase(selectedProvider[0]) ? value.toUpperCase(Locale.ROOT) : value;
                assetButton.setMessage(assetButtonLabel(selectedAsset[0], selectedProvider[0]));
            }, assetButton.getX(), assetButton.getY() + assetButton.getHeight(), selectedType[0], selectedProvider[0]);
        });
        builder.addRow("Name", nameInput);
        builder.addRow("ID", idInput);
        builder.addRow("Type", typeDropdown);
        builder.addRow("Provider", providerDropdown);
        builder.addRow("Asset", assetButton);

        PopupWidget[] popupRef = new PopupWidget[1];
        Runnable create = () -> {
                String id = idInput.getText() != null ? idInput.getText().trim() : "";
                String name = nameInput.getText() != null ? nameInput.getText().trim() : "";
                if (!id.matches("^[a-zA-Z0-9_]+$")) {
                    new Notification("Error", "Invalid ID. Alphanumeric only.", Notification.Type.ERROR);
                    return;
                }
                if (name.isBlank()) {
                    name = id;
                }
                String folder = explicitFolder ? targetFolder : defaultContentFolder(selectedType[0]);
                if (createContentResource(id, name, selectedType[0], selectedProvider[0], selectedAsset[0], folder,
                    () -> {
                    closeCreateContentSearchSelector();
                    if (popupRef[0] != null) {
                        popupRef[0].hide();
                    }
                })) {
                    return;
                }
            };
        builder.addTitleAction("Create", create, PopupWidget.TitleActionRole.PRIMARY);
        popupRef[0] = builder.build();
        screen.addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    private DropDownWidget<String> createContentDropdown(List<String> options, String selected, Consumer<String> onSelected) {
        List<String> safeOptions = options == null || options.isEmpty() ? List.of("No Options") : options.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return new DropDownWidget.Builder<>(safeOptions)
            .displayFunction(this::contentOptionLabel)
            .selectedItem(safeOptions.contains(selected) ? selected : safeOptions.getFirst())
            .onSelectionChanged(value -> {
                if (isRealContentOption(value)) {
                    onSelected.accept(value);
                }
            })
            .size(110, 20)
            .maxVisibleItems(8)
            .entranceAnimation(false)
            .build();
    }

    private String contentOptionLabel(String value) {
        if (value == null || value.isBlank()) {
            return "None";
        }
        String cleaned = value.trim().replace("minecraft:", "").replace('_', ' ').replace('-', ' ');
        StringBuilder builder = new StringBuilder();
        for (String part : cleaned.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.isEmpty() ? value : builder.toString();
    }

    private boolean isRealContentOption(String value) {
        return value != null && !"Loading".equals(value) && !"No Options".equals(value);
    }

    private void showCreateContentSearchSelector(List<String> options, String selected, Consumer<String> onSelected, int x, int y,
        String type, String provider) {
        closeCreateContentSearchSelector();
        ItemSelectorWidget[] selectorRef = new ItemSelectorWidget[1];
        var overlay = ScreenManager.getInstance().getPopupOverlay();
        String source = "vanilla".equalsIgnoreCase(provider) ? "server:minecraft:material" : "server:custom_content:asset";
        Map<String, Object> context = "vanilla".equalsIgnoreCase(provider) ? Map.of() : customContentCatalogContext(type, provider);
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(overlay)
            .size(220, 240)
            .dismissOnSelect(true)
            .emptyMessage("No Assets")
            .asyncItems(OptionCatalogSelector.refreshAction(screen.studioServerId(), source, context),
                () -> OptionCatalogSelector.snapshot(screen.studioServerId(), source, context, () -> options, () -> selected, onSelected, "No Assets"))
            .onClose(() -> closeCreateContentSearchSelector(selectorRef[0]))
            .build();
        selector.setLayer(900);
        selector.setPriority(30);
        selectorRef[0] = selector;
        selector.setSelectedItem(OptionCatalogSelector.label(screen.studioServerId(), source, context, selected));
        createContentSelector = selector;
        overlay.addDrawableChild(createContentSelector);
        int selectorX = Math.clamp(x, 8, Math.max(8, screen.screenWidth() - selector.getWidth() - 8));
        int selectorY = Math.clamp(y, 32, Math.max(32, screen.screenHeight() - selector.getHeight() - 20));
        createContentSelector.show(selectorX, selectorY);
    }

    private void closeCreateContentSearchSelector() {
        closeCreateContentSearchSelector(createContentSelector);
    }

    private void closeCreateContentSearchSelector(ItemSelectorWidget selector) {
        if (selector != null) {
            selector.onClose = null;
            selector.hide();
            ScreenManager.getInstance().getPopupOverlay().remove(selector);
        }
        if (selector == createContentSelector) {
            createContentSelector = null;
        }
        screen.clearStudioFocus();
    }

    private List<String> providerOptions() {
        Set<String> providers = new LinkedHashSet<>();
        providers.add("vanilla");
        providers.addAll(catalogOptions("server:custom_content:provider"));
        return new ArrayList<>(providers);
    }

    private List<String> contentAssetOptions(String type, String provider) {
        if ("vanilla".equalsIgnoreCase(provider)) {
            return catalogOptions("server:minecraft:material");
        }
        return catalogOptions("server:custom_content:asset", customContentCatalogContext(type, provider));
    }

    private List<String> catalogOptions(String source) {
        return catalogOptions(source, Map.of());
    }

    private List<String> catalogOptions(String source, Map<String, Object> context) {
        return OptionCatalogLoader.snapshot(screen.studioServerId(), source, context).values();
    }

    private void requestContentAssetCatalogs(String type, String provider) {
        if ("vanilla".equalsIgnoreCase(provider)) {
            requestCatalog("server:minecraft:material");
            return;
        }
        requestCatalog("server:custom_content:asset", customContentCatalogContext(type, provider));
    }

    private void requestCatalog(String source) {
        requestCatalog(source, Map.of());
    }

    private void requestCatalog(String source, Map<String, Object> context) {
        OptionCatalogLoader.preload(screen.studioServerId(), source, context);
    }

    private Map<String, Object> customContentCatalogContext(String type, String provider) {
        return Map.of(
            "provider", provider != null ? provider : "",
            "content_type", type != null ? type : ""
        );
    }

    private String defaultContentMaterial(String type) {
        return switch (type) {
            case "block" -> "STONE";
            case "armor" -> "IRON_CHESTPLATE";
            case "projectile" -> "ARROW";
            default -> "STICK";
        };
    }

    private String assetButtonLabel(String asset, String provider) {
        if (asset != null && !asset.isBlank()) {
            return asset;
        }
        return "vanilla".equalsIgnoreCase(provider) ? "Material" : "External ID";
    }

    private void awaitCreatedResourceVisibility(ReSyncResourceCreator.Result result, String targetFolder,
                                                long startedAtNanos) {
        if (disposed || result == null || result.type() == null || result.type().isBlank()
            || result.id() == null || result.id().isBlank()) {
            return;
        }
        if (AutomationDefinitionDraft.supports(ReSyncResourceType.byTypeId(result.type()))) {
            rebuild(pathForFolder(targetFolder));
            return;
        }
        boolean folder = ReSyncResourceDragPayload.FOLDER.equals(result.type());
        RemotePath folderPath = folder ? pathForFolder(targetFolder).resolve(result.id()) : null;
        PendingCreatedResource pending = new PendingCreatedResource(result, targetFolder, startedAtNanos,
            folder ? null : resourceKey(result.type(), result.id()), folderPath,
            folder ? folderPath : pathForFolder(targetFolder), new OneShotCreationPublication());
        if (!creationVisibilityGate.await(pending.pendingKey(), pending)) {
            return;
        }
        rebuild(pending.revealPath());
        try {
            publishVisibleCreatedResources(treeProvider, lastPublishedBrowserGeneration, lastProjectMetadataStamp);
        } catch (RuntimeException ignored) {
        }
    }

    private void publishVisibleCreatedResources(ReSyncProjectTreeProvider provider, long generation,
                                                long metadataStamp) {
        creationVisibilityGate.publish(pending -> !disposed && pending.visibleIn(provider), pending -> {
            if (disposed) {
                throw new IllegalStateException("Browser disposed during creation publication");
            }
            publishVisibleCreatedResource(pending, generation, metadataStamp);
        });
    }

    private void publishVisibleCreatedResource(PendingCreatedResource pending, long generation,
                                               long metadataStamp) {
        pending.publication().publish(() -> openCreatedResourceEditor(pending.result()),
            () -> traceVisibleCreatedResource(pending, generation, metadataStamp));
    }

    private void openCreatedResourceEditor(ReSyncResourceCreator.Result result) {
        String type = result.type();
        String id = result.id();
        Object resource = result.resource();
        switch (type) {
            case ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION,
                 ReSyncResourceDragPayload.COMMAND -> screen.openWorkspaceResource(type, id);
            case ReSyncResourceDragPayload.GUI, ReSyncResourceDragPayload.SCOREBOARD, ReSyncResourceDragPayload.TAB, ReSyncResourceDragPayload.ADVANCEMENT_TREE,
                 ReSyncResourceDragPayload.DIALOG, ReSyncResourceDragPayload.TRADE_PROFILE, ReSyncResourceDragPayload.NPC_DEFINITION,
                 ReSyncResourceDragPayload.LOOT_TABLE -> screen.openStudioDesigner(type, id);
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> {
                if (resource instanceof CustomContentDefinition definition && definition.getGraph() != null) {
                    FlowGraph graph = definition.getGraph();
                    screen.openStudioViewDocument(type, id, id, graph,
                        new ScreenBackedStudioView(screen, new ContentDesignerScreen(screen.studioServerId(), graph, screen)));
                }
            }
            case ReSyncResourceDragPayload.CHAT, ReSyncResourceDragPayload.COMPONENT_BUILDER, ReSyncResourceDragPayload.MOTD_PROFILE, ReSyncResourceDragPayload.MESSAGE_RULE,
                 ReSyncResourceDragPayload.RECIPE_DEFINITION, ReSyncResourceDragPayload.TEXT_TEMPLATE,
                 ReSyncResourceDragPayload.VARIABLE_DEFINITION, ReSyncResourceDragPayload.TIMER_DEFINITION,
                 ReSyncResourceDragPayload.SCHEDULE_DEFINITION -> {
                if (resource instanceof JsonObject json) {
                    screen.openFocusedResourceDocumentOwned(type, id, id, json);
                }
            }
            case ReSyncResourceDragPayload.WORLDGEN -> {
                if (resource instanceof WorldGenProject project) {
                    screen.openStudioWorldGenDocument(id, id, project);
                }
            }
            case ReSyncResourceDragPayload.WORLD -> screen.openStudioWorldDocument(id, id);
            default -> {
            }
        }
    }

    private void traceVisibleCreatedResource(PendingCreatedResource pending, long generation, long metadataStamp) {
        ReSyncResourceCreator.Result result = pending.result();
        String type = result.type();
        String id = result.id();
        Object resource = result.resource();
        if (ReSyncLifecycleDiagnostics.enabled()) {
            ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_create_visible", "serverId",
                screen.studioServerId(), "resourceKey", type + ":" + id, "requestId", "create", "mutationId", null,
                "generation", generation, "authorityEpoch", 0L, "revision", metadataStamp, "targetFolder",
                pending.targetFolder(), "resourcePresent", resource != null, "expectedKeyPresent", true,
                "elapsedMs", pending.startedAtNanos() == 0L ? -1L : ((
                    System.nanoTime() - pending.startedAtNanos()) / 1_000_000L), "publication", "committed");
        }
    }

    private boolean createContentResource(String id, String name, String contentType, String provider, String asset,
                                          String targetFolder, Runnable completed) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return false;
        }
        String normalizedTargetFolder = ReSyncProjectMetadata.normalizePath(targetFolder);
        if (manager.getProjectResource(screen.studioServerId(), ReSyncResourceDragPayload.CUSTOM_CONTENT, id) != null || resourceExists(manager, ReSyncResourceDragPayload.CUSTOM_CONTENT, id)) {
            new Notification("Error", "Content ID already exists", Notification.Type.ERROR);
            return false;
        }
        String requestedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String normalizedType = switch (requestedType) {
            case "block", "armor", "projectile" -> requestedType;
            default -> "item";
        };
        String selectedProvider = provider == null || provider.isBlank() ? "vanilla" : provider;
        FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(screen.studioServerId());
        FlowManager.ProjectResource existing = metadata.resource(ReSyncResourceDragPayload.CUSTOM_CONTENT, id);
        FlowManager.CreationMetadataIntent intent = new FlowManager.CreationMetadataIntent(
            ReSyncResourceDragPayload.CUSTOM_CONTENT, id, name, canonicalResourcePath(normalizedTargetFolder, id), "",
            existing != null ? existing.sortOrder() : metadata.nextResourceSortOrder(), false);
        JsonObject template = new JsonObject();
        template.addProperty("type", normalizedType);
        template.addProperty("name", name);
        template.addProperty("provider", selectedProvider);
        template.addProperty("asset", asset == null ? "" : asset);
        long startedAtNanos = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        FlowManager.CreationAdmission admission = manager.beginResourceCreation(screen.studioServerId(),
            ReSyncResourceType.CUSTOM_CONTENT, id, gson.toJson(template), intent, null, result -> {
                traceCreateCallback(ReSyncResourceDragPayload.CUSTOM_CONTENT, id, result, normalizedTargetFolder,
                    startedAtNanos);
                if (result == null || !ReSyncResourceDragPayload.CUSTOM_CONTENT.equals(result.type())
                    || !id.equals(result.id()) || !(result.resource() instanceof CustomContentDefinition definition)) {
                    new Notification("Create", "Creation Result Invalid", Notification.Type.ERROR);
                    return;
                }
                awaitCreatedResourceVisibility(new ReSyncResourceCreator.Result(ReSyncResourceDragPayload.CUSTOM_CONTENT, id,
                    definition), normalizedTargetFolder, startedAtNanos);
                if (completed != null) {
                    completed.run();
                }
            });
        if (admission.rejected()) {
            new Notification("Create", admission.message(), Notification.Type.ERROR);
            return false;
        }
        return admission.queued();
    }

    private void traceCreateCallback(String expectedType, String expectedId, ReSyncResourceCreator.Result result,
                                     String targetFolder, long startedAtNanos) {
        if (!ReSyncLifecycleDiagnostics.enabled()) {
            return;
        }
        boolean expectedResult = result != null && Objects.equals(expectedType, result.type())
            && (expectedId == null ? result.id() != null && !result.id().isBlank() : Objects.equals(expectedId, result.id()));
        ReSyncFlowClient.traceLifecycle(screen.studioServerId(), "browser_create_callback", "serverId",
            screen.studioServerId(), "resourceKey", (expectedType == null ? "unknown" : expectedType) + ":"
                + (expectedId == null ? "unknown" : expectedId), "requestId", "create", "mutationId", null,
            "generation", -1L, "authorityEpoch", 0L, "revision", -1L, "expectedResult", expectedResult,
            "callbackType", result != null ? result.type() : "none", "callbackId", result != null ? result.id() : "none",
            "resourcePresent", result != null && result.resource() != null, "targetFolder", targetFolder,
            "elapsedMs", startedAtNanos == 0L ? -1L : ((
                System.nanoTime() - startedAtNanos) / 1_000_000L), "reason", expectedResult ? "settled_callback" : "unexpected_result");
    }

    private void traceCreateCallback(String expectedType, String expectedId, FlowManager.CreationResult result,
                                     String targetFolder, long startedAtNanos) {
        traceCreateCallback(expectedType, expectedId,
            result == null ? null : new ReSyncResourceCreator.Result(result.type(), result.id(), result.resource()),
            targetFolder, startedAtNanos);
    }

    private BrowserSelection browserSelection() {
        Map<String, ReSyncProjectMetadata.ResourceEntry> resources = new LinkedHashMap<>();
        Map<String, ReSyncProjectMetadata.FolderEntry> folders = new LinkedHashMap<>();
        boolean rootSelected = false;
        List<WorkspaceTreeExplorer.NodeRef> selectedRefs = treeExplorer.selectedNodeRefs();
        for (WorkspaceTreeExplorer.NodeRef ref : selectedRefs) {
            if (ref.directory()) {
                ReSyncProjectMetadata.FolderEntry folder = treeProvider.folder(ref.path());
                if (folder != null) folders.put(folder.getPath(), folder);
                else if (projectRoot.equals(ref.path())) rootSelected = true;
            } else {
                ReSyncProjectMetadata.ResourceEntry resource = treeProvider.resource(ref.path());
                if (resource != null) resources.put(resource.key(), resource);
            }
        }
        if (resources.isEmpty() && folders.isEmpty() && !rootSelected) {
            if (selectedResource != null) resources.put(selectedResource.key(), selectedResource);
            if (selectedFolder != null) folders.put(selectedFolder.getPath(), selectedFolder);
            rootSelected = selectedProjectRoot;
        }
        List<ReSyncProjectMetadata.FolderEntry> rootFolders = new ArrayList<>();
        folders.values().stream().sorted(Comparator.comparingInt(folder -> folder.getPath().length())).forEach(folder -> {
            if (rootFolders.stream().noneMatch(parent -> folder.getPath().startsWith(parent.getPath() + "/"))) rootFolders.add(folder);
        });
        List<ReSyncProjectMetadata.ResourceEntry> rootResources = resources.values().stream()
            .filter(resource -> rootFolders.stream().noneMatch(folder -> resourceWithinFolder(resource.getPath(), resource.getId(), folder.getPath())))
            .toList();
        int selectedCount = !selectedRefs.isEmpty() ? selectedRefs.size() : rootResources.size() + rootFolders.size() + (rootSelected ? 1 : 0);
        return new BrowserSelection(rootResources, List.copyOf(rootFolders), rootSelected && rootResources.isEmpty() && rootFolders.isEmpty(), selectedCount);
    }

    private String selectionDestination(BrowserSelection selection) {
        if (selection.projectRoot()) return "";
        if (selection.selectedCount() != 1 || selection.size() != 1) return null;
        if (!selection.folders().isEmpty()) return selection.folders().getFirst().getPath();
        return resourceSelectionDestination(selection.resources().getFirst());
    }

    static String resourceSelectionDestination(ReSyncProjectMetadata.ResourceEntry resource) {
        return resource == null ? "" : resourceFolderPath(resource.getPath(), resource.getId());
    }

    private boolean canRename(BrowserSelection selection) {
        if (selection.selectedCount() != 1 || selection.size() != 1) return false;
        if (!selection.folders().isEmpty()) return true;
        ReSyncProjectMetadata.ResourceEntry resource = selection.resources().getFirst();
        if (coreRenameBlocked(resource)) return false;
        return switch (resource.getType()) {
            case ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION, ReSyncResourceDragPayload.COMMAND,
                 ReSyncResourceDragPayload.CUSTOM_CONTENT, ReSyncResourceDragPayload.GUI, ReSyncResourceDragPayload.SCOREBOARD,
                 ReSyncResourceDragPayload.TAB, ReSyncResourceDragPayload.CHAT, ReSyncResourceDragPayload.COMPONENT_BUILDER, ReSyncResourceDragPayload.MOTD_PROFILE,
                 ReSyncResourceDragPayload.MESSAGE_RULE, ReSyncResourceDragPayload.RECIPE_DEFINITION,
                 ReSyncResourceDragPayload.TEXT_TEMPLATE, ReSyncResourceDragPayload.ADVANCEMENT_TREE,
                 ReSyncResourceDragPayload.DIALOG, ReSyncResourceDragPayload.TRADE_PROFILE,
                 ReSyncResourceDragPayload.NPC_DEFINITION, ReSyncResourceDragPayload.LOOT_TABLE,
                 ReSyncResourceDragPayload.VARIABLE_DEFINITION, ReSyncResourceDragPayload.TIMER_DEFINITION,
                 ReSyncResourceDragPayload.SCHEDULE_DEFINITION -> true;
            default -> false;
        };
    }

    private boolean coreRenameBlocked(ReSyncProjectMetadata.ResourceEntry resource) {
        if (resource == null) return false;
        ReSyncResourceType type = ReSyncResourceType.byTypeId(resource.getType());
        FlowManager manager = FlowManager.getInstance();
        return manager != null && type != null && type.isGraph()
            && manager.isCoreGraphAuthorityEnabled(screen.studioServerId(), type);
    }

    private boolean canDelete(BrowserSelection selection) {
        if (selection.size() == 0) return false;
        return selectedResources(selection).stream().allMatch(resource -> switch (resource.getType()) {
            case ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION, ReSyncResourceDragPayload.COMMAND,
                 ReSyncResourceDragPayload.CUSTOM_CONTENT, ReSyncResourceDragPayload.GUI, ReSyncResourceDragPayload.SCOREBOARD,
                 ReSyncResourceDragPayload.TAB, ReSyncResourceDragPayload.CHAT, ReSyncResourceDragPayload.COMPONENT_BUILDER, ReSyncResourceDragPayload.MOTD_PROFILE,
                 ReSyncResourceDragPayload.MESSAGE_RULE, ReSyncResourceDragPayload.RECIPE_DEFINITION,
                 ReSyncResourceDragPayload.TEXT_TEMPLATE, ReSyncResourceDragPayload.ADVANCEMENT_TREE,
                 ReSyncResourceDragPayload.DIALOG, ReSyncResourceDragPayload.TRADE_PROFILE,
                 ReSyncResourceDragPayload.NPC_DEFINITION, ReSyncResourceDragPayload.LOOT_TABLE,
                 ReSyncResourceDragPayload.WORLDGEN, ReSyncResourceDragPayload.WORLD,
                 ReSyncResourceDragPayload.VARIABLE_DEFINITION, ReSyncResourceDragPayload.TIMER_DEFINITION,
                 ReSyncResourceDragPayload.SCHEDULE_DEFINITION -> true;
            default -> false;
        });
    }

    private List<ReSyncProjectMetadata.ResourceEntry> selectedResources(BrowserSelection selection) {
        Map<String, ReSyncProjectMetadata.ResourceEntry> resources = new LinkedHashMap<>();
        for (ReSyncProjectMetadata.ResourceEntry resource : selection.resources()) resources.put(resource.key(), resource);
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) return List.copyOf(resources.values());
        for (ReSyncProjectMetadata.ResourceEntry resource : manager.getProjectResources(screen.studioServerId())) {
            if (!screen.isVisibleStudioResource(resource)) {
                continue;
            }
            if (selection.folders().stream().anyMatch(folder -> resourceWithinFolder(resource.getPath(), resource.getId(), folder.getPath()))) {
                resources.put(resource.key(), resource);
            }
        }
        return List.copyOf(resources.values());
    }

    private void copySelected() {
        BrowserClipboard selected = selectedClipboard(false);
        if (selected == null) {
            return;
        }
        clipboard = selected;
    }

    private void cutSelected() {
        BrowserClipboard selected = selectedClipboard(true);
        if (selected == null) {
            return;
        }
        clipboard = selected;
    }

    private BrowserClipboard selectedClipboard(boolean cut) {
        BrowserSelection selection = browserSelection();
        List<String> folders = selection.folders().stream().map(ReSyncProjectMetadata.FolderEntry::getPath).toList();
        List<ClipboardResource> resources = selection.resources().stream()
            .filter(resource -> folders.stream().noneMatch(folder -> resourceWithinFolder(resource.getPath(), resource.getId(), folder)))
            .map(resource -> new ClipboardResource(resource.getType(), resource.getId(), resource.getPath()))
            .toList();
        return resources.isEmpty() && folders.isEmpty() ? null : new BrowserClipboard(resources, folders, cut);
    }

    private void pasteSelected() {
        if (clipboard == null) {
            new Notification("Paste", "Clipboard Empty", Notification.Type.WARN);
            return;
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return;
        }
        BrowserSelection selection = browserSelection();
        String destination = selectionDestination(selection);
        if (destination == null) {
            new Notification("Paste", "Select One Destination", Notification.Type.WARN);
            return;
        }
        BrowserClipboard activeClipboard = clipboard;
        if (containsWorldGen(manager, activeClipboard)) {
            if (activeClipboard.cut() || !activeClipboard.folderPaths().isEmpty()
                || activeClipboard.resources().stream().anyMatch(resource -> !ReSyncResourceDragPayload.WORLDGEN.equals(resource.type()))) {
                new Notification("Paste", activeClipboard.cut() ? "Move World Generation Unsupported" : "Copy World Generation Separately",
                    Notification.Type.ERROR);
                return;
            }
            pasteWorldGenResources(manager, activeClipboard.resources(), destination);
            return;
        }
        BrowserEditStart edit = beginBrowserEdit(activeClipboard.cut() ? "Move" : "Paste", List.of());
        FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(screen.studioServerId());
        Set<String> affectedKeys = new HashSet<>();
        List<ClipboardResource> failedResources = new ArrayList<>();
        List<String> failedFolders = new ArrayList<>();
        int pasted = 0;
        for (ClipboardResource resource : activeClipboard.resources()) {
            if (pasteResource(manager, metadata, resource, destination, activeClipboard.cut(), affectedKeys)) pasted++;
            else failedResources.add(resource);
        }
        for (String folder : activeClipboard.folderPaths()) {
            if (pasteFolder(manager, metadata, folder, destination, activeClipboard.cut(), affectedKeys)) pasted++;
            else failedFolders.add(folder);
        }
        if (pasted == 0) {
            return;
        }
        if (activeClipboard.cut()) {
            clipboard = failedResources.isEmpty() && failedFolders.isEmpty() ? null : new BrowserClipboard(failedResources, failedFolders, true);
        }
        manager.saveProjectMetadata(metadata, true);
        commitBrowserEdit(edit, affectedKeys);
        rebuild(pathForFolder(destination));
        new Notification(activeClipboard.cut() ? "Moved" : "Pasted", pasted + " Items", Notification.Type.SUCCESS);
    }

    private boolean containsWorldGen(FlowManager manager, BrowserClipboard clipboard) {
        if (clipboard.resources().stream().anyMatch(resource -> ReSyncResourceDragPayload.WORLDGEN.equals(resource.type()))) {
            return true;
        }
        if (clipboard.folderPaths().isEmpty()) {
            return false;
        }
        return manager.getProjectResources(screen.studioServerId()).stream()
            .anyMatch(resource -> ReSyncResourceDragPayload.WORLDGEN.equals(resource.getType())
                && clipboard.folderPaths().stream().anyMatch(folder -> resourceWithinFolder(resource.getPath(), resource.getId(), folder)));
    }

    private void pasteWorldGenResources(FlowManager manager, List<ClipboardResource> resources, String destination) {
        String serverId = screen.studioServerId();
        WorldGenManager worldGen = WorldGenManager.getInstance();
        FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(serverId);
        Set<String> reserved = new HashSet<>();
        List<WorldGenCopyRequest> requests = new ArrayList<>();
        for (ClipboardResource resource : resources) {
            FlowManager.ProjectResource source = metadata.resource(resource.type(), resource.id());
            if (source == null) {
                new Notification("Copy", "Resource Missing", Notification.Type.ERROR);
                continue;
            }
            String targetId = nextWorldGenCopyId(manager, source.id(), destination, reserved);
            reserved.add(ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.WORLDGEN, targetId));
            BrowserEditStart edit = beginBrowserEdit("Paste", List.of());
            if (edit == null) {
                new Notification("Copy", "World Generation Copy Failed", Notification.Type.ERROR);
                continue;
            }
            requests.add(new WorldGenCopyRequest(serverId, source.id(), targetId, destination,
                new WorldGenManager.WorldGenMetadataIntent(targetId, canonicalResourcePath(destination, targetId), -1), edit));
        }
        WeakReference<ReSyncContentBrowserWidget> widgetReference = new WeakReference<>(this);
        for (WorldGenCopyRequest request : requests) {
            WorldGenManager.ProjectSaveSubmission[] submission = new WorldGenManager.ProjectSaveSubmission[1];
            submission[0] = worldGen.duplicateProject(serverId, request.sourceId(), request.targetId(), false,
                request.metadataIntent(),
                settlement -> {
                    ReSyncContentBrowserWidget widget = widgetReference.get();
                    if (widget != null) {
                        widget.completeWorldGenCopy(request, submission[0], settlement);
                    } else if (settlement != null && submission[0] != null
                        && submission[0].equals(settlement.submission()) && settlement.committed()) {
                        worldGen.reconcileWorldGenProjectMetadata(settlement,
                            request.metadataIntent().displayName(), request.metadataIntent().path(),
                            request.metadataIntent().sortOrder(), null);
                    }
                });
            if (submission[0] == null) {
                new Notification("Copy", "World Generation Copy Failed", Notification.Type.ERROR);
            }
        }
    }

    private void completeWorldGenCopy(WorldGenCopyRequest request, WorldGenManager.ProjectSaveSubmission expected,
                                      WorldGenManager.ProjectSaveSubmissionSettlement settlement) {
        String serverId = request.serverId();
        if (settlement == null || expected == null || !expected.equals(settlement.submission())
            || !settlement.committed() || !serverId.equals(settlement.submission().serverId())
            || !request.targetId().equals(settlement.submission().projectId()) || settlement.project() == null
            || !request.targetId().equals(settlement.project().getId())) {
            if (serverId.equals(screen.studioServerId())) {
                new Notification("Copy", "World Generation Copy Failed", Notification.Type.ERROR);
            }
            return;
        }
        String operationId = settlement.submission().operationId();
        Set<String> affectedKeys = Set.of(ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.WORLDGEN,
            request.targetId()));
        WeakReference<ReSyncContentBrowserWidget> widgetReference = new WeakReference<>(this);
        boolean admitted = WorldGenManager.getInstance().reconcileWorldGenProjectMetadata(settlement,
            request.metadataIntent().displayName(), request.metadataIntent().path(), request.metadataIntent().sortOrder(), result -> {
                ReSyncContentBrowserWidget widget = widgetReference.get();
                if (widget == null) {
                    return;
                }
                FlowManager currentManager = FlowManager.getInstance();
                WorldGenManager currentWorldGen = WorldGenManager.getInstance();
                if (result == null || !operationId.equals(result.operationId()) || !serverId.equals(widget.screen.studioServerId())
                    || !serverId.equals(result.serverId())
                    || !request.targetId().equals(result.projectId()) || !result.add() || !result.successful()
                    || currentManager == null || currentWorldGen == null || result.connection() == null
                    || !currentManager.isCurrentServerConnection(result.connection())
                    || currentWorldGen.authorityEpoch(serverId) != result.authorityEpoch()) {
                    if (result != null && !result.successful()) {
                        new Notification("Copy", "World Generation Copy Failed", Notification.Type.ERROR);
                    }
                    return;
                }
                widget.commitBrowserEdit(request.edit(), affectedKeys);
                widget.rebuild(widget.pathForFolder(request.destination()));
                new Notification("Pasted", "World Generation", Notification.Type.SUCCESS);
            });
        if (!admitted) {
            new Notification("Copy", "World Generation Copy Failed", Notification.Type.ERROR);
        }
    }

    private String nextWorldGenCopyId(FlowManager manager, String sourceId, String folder, Set<String> reserved) {
        String base = ReSyncNaming.copyId(sourceId);
        String candidate = base;
        int suffix = 2;
        while (reserved.contains(ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.WORLDGEN, candidate))
            || ReSyncResourceCreator.exists(manager, screen.studioServerId(), ReSyncResourceDragPayload.WORLDGEN, candidate, folder)) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    private boolean pasteResource(FlowManager manager, FlowManager.ProjectMetadataEdit metadata, ClipboardResource source, String destination,
                                  boolean cut, Set<String> affectedKeys) {
        FlowManager.ProjectResource entry = metadata.resource(source.type(), source.id());
        if (entry == null) {
            new Notification("Paste", "Resource Missing", Notification.Type.ERROR);
            return false;
        }
        String targetFolder = ReSyncProjectMetadata.normalizePath(destination);
        if (cut) {
            String targetPath = canonicalResourcePath(targetFolder, entry.id());
            if (entry.path().equals(targetPath)) {
                return true;
            }
            if (folderContainsId(metadata, targetFolder, entry.id(), entry.key())) {
                new Notification("Move", "ID Exists In Folder", Notification.Type.ERROR);
                return false;
            }
            metadata.putResource(entry.type(), entry.id(), entry.displayName(), targetPath, entry.sortOrder());
            return true;
        }
        String copyId = nextCopyId(manager, entry.type(), entry.id(), targetFolder);
        if (!duplicateResource(manager, entry.type(), entry.id(), copyId)) {
            new Notification("Copy", "Resource Cannot Be Copied", Notification.Type.ERROR);
            return false;
        }
        metadata.putResource(entry.type(), copyId, copyId, canonicalResourcePath(targetFolder, copyId), metadata.nextResourceSortOrder());
        affectedKeys.add(ReSyncProjectMetadata.resourceKey(entry.type(), copyId));
        return true;
    }

    private void toggleResourceActivation(List<ReSyncProjectMetadata.ResourceEntry> resources, boolean enabled) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) return;
        boolean changed = resources.stream().map(resource ->
                manager.setResourceEnabled(screen.studioServerId(), resource.getType(), resource.getId(), enabled))
                .reduce(false, Boolean::logicalOr);
        if (!changed) return;
        lastAssetBrowserSnapshot = null;
        rebuild();
        screen.refreshStudioResourcePanel();
    }

    private boolean pasteFolder(FlowManager manager, FlowManager.ProjectMetadataEdit metadata, String sourcePath, String destination,
                                boolean cut, Set<String> affectedKeys) {
        FlowManager.ProjectFolder source = metadata.folder(sourcePath);
        if (source == null) {
            new Notification("Paste", "Folder Missing", Notification.Type.ERROR);
            return false;
        }
        String targetParent = ReSyncProjectMetadata.normalizePath(destination);
        if (cut && (targetParent.equals(sourcePath) || targetParent.startsWith(sourcePath + "/"))) {
            new Notification("Move", "Choose Another Folder", Notification.Type.ERROR);
            return false;
        }
        if (cut) {
            String oldPath = source.path();
            String targetPath = targetParent.isBlank() ? source.name() : targetParent + "/" + source.name();
            if (targetPath.equals(oldPath)) {
                return true;
            }
            if (metadata.folder(targetPath) != null) {
                new Notification("Move", "Folder Already Exists", Notification.Type.ERROR);
                return false;
            }
            relocateFolder(metadata, oldPath, targetPath, targetParent);
            if (currentFolder.equals(oldPath) || currentFolder.startsWith(oldPath + "/")) {
                currentFolder = targetPath + currentFolder.substring(oldPath.length());
            }
            return true;
        }
        copyFolder(manager, metadata, source, targetParent, affectedKeys);
        return true;
    }

    private void relocateFolder(FlowManager.ProjectMetadataEdit metadata, String oldPath, String newPath, String newParent) {
        for (FlowManager.ProjectFolder folder : metadata.folders()) {
            String path = folder.path();
            String parent = folder.parentPath();
            if (path.equals(oldPath)) {
                path = newPath;
                parent = newParent;
            } else if (path.startsWith(oldPath + "/")) {
                path = newPath + path.substring(oldPath.length());
            }
            if (parent.equals(oldPath)) parent = newPath;
            else if (parent.startsWith(oldPath + "/")) parent = newPath + parent.substring(oldPath.length());
            if (!path.equals(folder.path())) metadata.removeFolder(folder.path());
            metadata.putFolder(path, parent, folder.name(), folder.sortOrder(), folder.collapsed());
        }
        for (FlowManager.ProjectResource resource : metadata.resources()) {
            String resourceFolder = resourceFolderPath(resource.path(), resource.id());
            if (resourceWithinFolder(resource.path(), resource.id(), oldPath)) {
                String relocatedFolder = newPath + resourceFolder.substring(oldPath.length());
                metadata.putResource(resource.type(), resource.id(), resource.displayName(),
                    canonicalResourcePath(relocatedFolder, resource.id()), resource.sortOrder());
            }
        }
    }

    private void copyFolder(FlowManager manager, FlowManager.ProjectMetadataEdit metadata, FlowManager.ProjectFolder source,
                            String targetParent, Set<String> affectedKeys) {
        String copyName = nextFolderCopyName(metadata, targetParent, source.name());
        String copyRoot = targetParent.isBlank() ? copyName : targetParent + "/" + copyName;
        String sourceRoot = source.path();
        List<FlowManager.ProjectFolder> folders = metadata.folders().stream()
            .filter(folder -> folder.path().equals(sourceRoot) || folder.path().startsWith(sourceRoot + "/"))
            .sorted((left, right) -> Integer.compare(left.path().length(), right.path().length()))
            .toList();
        for (FlowManager.ProjectFolder folder : folders) {
            String path = copyRoot + folder.path().substring(sourceRoot.length());
            String parent = path.equals(copyRoot) ? targetParent : parentFolder(path);
            metadata.putFolder(path, parent, folder.name(), metadata.nextFolderSortOrder(), false);
        }
        List<FlowManager.ProjectResource> resources = metadata.resources().stream()
            .filter(resource -> resourceWithinFolder(resource.path(), resource.id(), sourceRoot))
            .toList();
        for (FlowManager.ProjectResource resource : resources) {
            String resourceFolder = resourceFolderPath(resource.path(), resource.id());
            String folder = copyRoot + resourceFolder.substring(sourceRoot.length());
            String copyId = nextCopyId(manager, resource.type(), resource.id(), folder);
            if (duplicateResource(manager, resource.type(), resource.id(), copyId)) {
                metadata.putResource(resource.type(), copyId, copyId, canonicalResourcePath(folder, copyId), metadata.nextResourceSortOrder());
                affectedKeys.add(ReSyncProjectMetadata.resourceKey(resource.type(), copyId));
            }
        }
    }

    private boolean duplicateResource(FlowManager manager, String type, String id, String copyId) {
        if (ReSyncResourceDragPayload.WORLDGEN.equals(type)) {
            return false;
        }
        if (ReSyncResourceDragPayload.WORLD.equals(type)) {
            manager.suppressNextWorldSuccessNotification(screen.studioServerId(), "cloneWorld");
            manager.cloneWorld(screen.studioServerId(), id, copyId, false);
            return true;
        }
        return DesignerSaveNotifications.withoutAutomaticNotifications(() -> manager.duplicateResource(screen.studioServerId(), type, id, copyId));
    }

    private String nextCopyId(FlowManager manager, String type, String sourceId, String folder) {
        String base = ReSyncNaming.copyId(sourceId);
        String candidate = base;
        int suffix = 2;
        while (ReSyncResourceCreator.exists(manager, screen.studioServerId(), type, candidate, folder)) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    private String nextFolderCopyName(FlowManager.ProjectMetadataEdit metadata, String parent, String sourceName) {
        String base = sourceName + " Copy";
        String candidate = base;
        int suffix = 2;
        while (metadata.folder(parent.isBlank() ? candidate : parent + "/" + candidate) != null) {
            candidate = base + " " + suffix++;
        }
        return candidate;
    }

    private boolean folderContainsId(FlowManager.ProjectMetadataEdit metadata, String folder, String id, String ignoredKey) {
        return metadata.resources().stream().anyMatch(resource -> resourceFolderPath(resource.path(), resource.id()).equals(folder)
            && resource.id().equals(id) && !resource.key().equals(ignoredKey));
    }

    private boolean resourceExists(FlowManager manager, String type, String id) {
        return ReSyncResourceCreator.exists(manager, screen.studioServerId(), type, id, selectedCreateTargetFolder());
    }

    private static String resourceTypeName(String type) {
        return ReSyncResourceCreator.resourceTypeName(type);
    }

    private void renameSelected() {
        BrowserSelection selection = browserSelection();
        if (!canRename(selection)) return;
        selectedFolder = selection.folders().isEmpty() ? null : selection.folders().getFirst();
        selectedResource = selection.resources().isEmpty() ? null : selection.resources().getFirst();
        selectedProjectRoot = false;
        PopupWidget.Builder builder = new PopupWidget.Builder(selectedFolder != null ? "Rename Folder" : "Rename " + resourceTypeName(selectedResource.getType())).setResizable(false);
        TextInputWidget idInput = new TextInputWidget.Builder()
            .text(selectedFolder != null ? selectedFolder.getName() : selectedResource.getId())
            .placeholder(selectedFolder != null ? "Folder Name" : resourceTypeName(selectedResource.getType()) + " ID")
            .size(220, 22)
            .build();
        builder.addRow(selectedFolder != null ? "Name" : "ID", idInput);

        PopupWidget[] popupRef = new PopupWidget[1];
        Runnable save = () -> {
                String value = idInput.getText() != null ? idInput.getText().trim() : "";
                if (selectedFolder != null) {
                    if (value.isBlank()) {
                        new Notification("Explorer", "Invalid Name", Notification.Type.ERROR);
                        return;
                    }
                } else if (!value.matches("^[a-zA-Z0-9_]+$")) {
                    new Notification("Error", "Invalid ID. Alphanumeric only.", Notification.Type.ERROR);
                    return;
                }
                if (renameSelectedTo(value)) {
                    if (popupRef[0] != null) {
                        popupRef[0].hide();
                    }
                }
            };
        builder.addTitleAction("Save", save, PopupWidget.TitleActionRole.PRIMARY);
        popupRef[0] = builder.build();
        screen.addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    private boolean renameSelectedTo(String newId) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return false;
        }
        if (selectedFolder != null) {
            return renameSelectedFolder(manager, newId);
        }
        if (selectedResource != null && ReSyncResourceDragPayload.WORLD.equals(selectedResource.getType())) {
            new Notification("World", "World Rename Unsupported", Notification.Type.ERROR);
            return false;
        }
        if (selectedResource == null || newId.isBlank() || selectedResource.getId().equals(newId)) {
            return true;
        }
        String resourceFolder = resourceFolderPath(selectedResource.getPath(), selectedResource.getId());
        if (ReSyncResourceCreator.exists(manager, screen.studioServerId(), selectedResource.getType(), newId, resourceFolder)) {
            new Notification("Error", resourceTypeName(selectedResource.getType()) + " ID already exists", Notification.Type.ERROR);
            return false;
        }
        String oldType = selectedResource.getType();
        String oldId = selectedResource.getId();
        if (coreRenameBlocked(selectedResource)) {
            new Notification("Explorer", "Core Rename Unsupported", Notification.Type.ERROR);
            return false;
        }
        FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(screen.studioServerId());
        FlowManager.ProjectResource entry = metadata.resource(oldType, oldId);
        String oldDisplayName = entry != null ? entry.displayName() : oldId;
        if (entry != null) {
            metadata.renameResource(entry.key(), oldType, newId, newId, canonicalResourcePath(resourceFolder, newId), entry.sortOrder());
            manager.saveProjectMetadata(metadata, false);
        }
        boolean renamed = switch (selectedResource.getType()) {
            case ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION -> manager.renameFlow(screen.studioServerId(), oldId, newId);
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> manager.renameCustomContent(screen.studioServerId(), oldId, newId);
            case ReSyncResourceDragPayload.COMMAND -> renameCommandResource(manager, oldId, newId);
            case ReSyncResourceDragPayload.GUI -> manager.renameGui(screen.studioServerId(), oldId, newId);
            case ReSyncResourceDragPayload.SCOREBOARD -> manager.renameScoreboard(screen.studioServerId(), oldId, newId);
            case ReSyncResourceDragPayload.TAB -> manager.renameTab(screen.studioServerId(), oldId, newId);
            case ReSyncResourceDragPayload.CHAT, ReSyncResourceDragPayload.COMPONENT_BUILDER, ReSyncResourceDragPayload.MOTD_PROFILE, ReSyncResourceDragPayload.MESSAGE_RULE,
                 ReSyncResourceDragPayload.RECIPE_DEFINITION, ReSyncResourceDragPayload.TEXT_TEMPLATE, ReSyncResourceDragPayload.ADVANCEMENT_TREE,
                 ReSyncResourceDragPayload.DIALOG, ReSyncResourceDragPayload.TRADE_PROFILE, ReSyncResourceDragPayload.NPC_DEFINITION,
                 ReSyncResourceDragPayload.LOOT_TABLE, ReSyncResourceDragPayload.VARIABLE_DEFINITION,
                 ReSyncResourceDragPayload.TIMER_DEFINITION, ReSyncResourceDragPayload.SCHEDULE_DEFINITION -> {
                ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(selectedResource.getType());
                yield resourceType != null && manager.renameJsonResource(screen.studioServerId(), resourceType, oldId, newId);
            }
            default -> false;
        };
        if (!renamed) {
            if (entry != null) {
                FlowManager.ProjectMetadataEdit rollback = manager.editProjectMetadata(screen.studioServerId());
                rollback.renameResource(ReSyncProjectMetadata.resourceKey(oldType, newId), oldType, oldId, oldDisplayName,
                    entry.path(), entry.sortOrder());
                manager.saveProjectMetadata(rollback, false);
            }
            new Notification("Explorer", "Rename Failed", Notification.Type.ERROR);
            return false;
        }
        screen.renameStudioDocument(oldType, oldId, newId);
        rebuild();
        return true;
    }

    private void deleteSelected() {
        FlowManager manager = FlowManager.getInstance();
        BrowserSelection selection = browserSelection();
        if (manager == null || !canDelete(selection)) return;
        List<ReSyncProjectMetadata.ResourceEntry> resources = selectedResources(selection);
        if (resources.stream().anyMatch(resource -> AutomationDefinitionDraft.supports(resource.getType()))) {
            new Notification("Delete", "This Folder Contains Sub Resources. Delete Them From Their Type Screen First", Notification.Type.WARN);
            return;
        }
        List<ReSyncProjectMetadata.ResourceEntry> worlds = resources.stream().filter(resource -> ReSyncResourceDragPayload.WORLD.equals(resource.getType())).toList();
        if (!worlds.isEmpty()) {
            if (worlds.size() == 1 && resources.size() == 1 && selection.folders().isEmpty()) {
                deleteResource(manager, worlds.getFirst());
            } else {
                new Notification("Delete", "Delete Worlds Separately", Notification.Type.ERROR);
            }
            return;
        }
        List<ReSyncProjectMetadata.ResourceEntry> worldGenResources = resources.stream()
            .filter(resource -> ReSyncResourceDragPayload.WORLDGEN.equals(resource.getType())).toList();
        if (!worldGenResources.isEmpty()) {
            if (worldGenResources.size() == 1 && resources.size() == 1 && selection.folders().isEmpty()) {
                deleteWorldGenResource(manager, worldGenResources.getFirst());
            } else {
                new Notification("Delete", "Delete World Generation Separately", Notification.Type.ERROR);
            }
            return;
        }
        BrowserEditStart edit = beginBrowserEdit("Delete", resources);
        if (edit == null) return;
        Set<String> requestedKeys = new HashSet<>(resources.stream().map(ReSyncProjectMetadata.ResourceEntry::key).toList());
        Set<String> deletedFolders = new HashSet<>();
        for (ReSyncProjectMetadata.FolderEntry folder : selection.folders()) {
            deletedFolders.add(folder.getPath());
        }
        if (requestedKeys.stream().anyMatch(pendingDeleteKeys::contains)
            || deletedFolders.stream().anyMatch(pendingDeleteFolders::contains)) {
            new Notification("Delete", "Delete Already Pending", Notification.Type.WARN);
            return;
        }
        pendingDeleteKeys.addAll(requestedKeys);
        pendingDeleteFolders.addAll(deletedFolders);
        rebuild();
        Map<String, Async<FlowManager.ResourceDeleteResult>> settlements = new LinkedHashMap<>();
        for (ReSyncProjectMetadata.ResourceEntry resource : resources) {
            settlements.put(resource.key(), deleteResource(manager, resource));
        }
        Async<?>[] futures = settlements.values().toArray(new Async<?>[0]);
        WeakReference<ReSyncContentBrowserWidget> widgetReference = new WeakReference<>(this);
        Async.allOf(futures).whenComplete((ignored, exception) -> {
            ReSyncContentBrowserWidget widget = widgetReference.get();
            if (widget == null) return;
            try {
                ScreenManager.getInstance().execute(() -> widget.finishDelete(edit, requestedKeys, deletedFolders,
                    settlements, exception));
            } catch (RuntimeException rejected) {
                widget.pendingDeleteKeys.removeAll(requestedKeys);
                widget.pendingDeleteFolders.removeAll(deletedFolders);
            }
        });
    }

    private void finishDelete(BrowserEditStart edit, Set<String> requestedKeys, Set<String> requestedFolders,
                              Map<String, Async<FlowManager.ResourceDeleteResult>> settlements,
                              Throwable exception) {
        if (disposed) {
            pendingDeleteKeys.removeAll(requestedKeys);
            pendingDeleteFolders.removeAll(requestedFolders);
            return;
        }
        Set<String> deletedKeys = settledDeleteKeys(settlements);
        boolean allResourcesDeleted = exception == null && deletedKeys.size() == requestedKeys.size();
        FlowManager manager = FlowManager.getInstance();
        if (manager != null && allResourcesDeleted && !requestedFolders.isEmpty()) {
            FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(screen.studioServerId());
            Set<String> deletedFolders = new LinkedHashSet<>();
            metadata.folders().stream().filter(folder -> requestedFolders.stream()
                .anyMatch(path -> folder.path().equals(path) || folder.path().startsWith(path + "/")))
                .map(FlowManager.ProjectFolder::path).forEach(path -> {
                    metadata.removeFolder(path);
                    deletedFolders.add(path);
                });
            if (!deletedFolders.isEmpty()) {
                WeakReference<ReSyncContentBrowserWidget> widgetReference = new WeakReference<>(this);
                manager.saveProjectMetadataSettled(metadata, true).whenComplete((saved, metadataFailure) -> {
                    ReSyncContentBrowserWidget widget = widgetReference.get();
                    if (widget == null) return;
                    Runnable finish = () -> widget.publishDeleteResult(edit, requestedKeys, requestedFolders,
                        settlements, deletedKeys, Boolean.TRUE.equals(saved) && metadataFailure == null
                            ? deletedFolders : Set.of(), metadataFailure);
                    try {
                        ScreenManager.getInstance().execute(finish);
                    } catch (RuntimeException rejected) {
                        widget.pendingDeleteKeys.removeAll(requestedKeys);
                        widget.pendingDeleteFolders.removeAll(requestedFolders);
                    }
                });
                return;
            }
        }
        publishDeleteResult(edit, requestedKeys, requestedFolders, settlements, deletedKeys, Set.of(), exception);
    }

    private void publishDeleteResult(BrowserEditStart edit, Set<String> requestedKeys, Set<String> requestedFolders,
                                     Map<String, Async<FlowManager.ResourceDeleteResult>> settlements,
                                     Set<String> deletedKeys, Set<String> deletedFolders, Throwable exception) {
        pendingDeleteKeys.removeAll(requestedKeys);
        pendingDeleteFolders.removeAll(requestedFolders);
        if (disposed) return;
        boolean foldersDeleted = requestedFolders.isEmpty() || !deletedFolders.isEmpty();
        String deletedCurrentFolder = deletedFolders.stream()
            .filter(path -> currentFolder.equals(path) || currentFolder.startsWith(path + "/"))
            .findFirst().orElse(null);
        if (deletedCurrentFolder != null) currentFolder = parentFolder(deletedCurrentFolder);
        commitBrowserEdit(edit, deletedKeys);
        screen.removeStudioDocuments(deletedKeys);
        Set<String> failedKeys = new LinkedHashSet<>(requestedKeys);
        failedKeys.removeAll(deletedKeys);
        Set<String> retainedFolders = foldersDeleted ? Set.of() : Set.copyOf(requestedFolders);
        pendingSelectionRestore = new BrowserSelectionState(failedKeys, retainedFolders, false,
            treeContainer.getScrollOffset());
        selectedResource = null;
        selectedFolder = null;
        selectedProjectRoot = false;
        rebuild();
        int deleted = deletedKeys.size() + (foldersDeleted ? requestedFolders.size() : 0);
        int failed = requestedKeys.size() - deletedKeys.size() + (foldersDeleted ? 0 : requestedFolders.size());
        if (deleted > 0 && failed == 0) {
            new Notification("Deleted", deleted + " Items", Notification.Type.SUCCESS);
        } else if (deleted > 0) {
            new Notification("Delete", deleted + " Deleted · " + failed + " Failed", Notification.Type.WARN);
        } else {
            String message = settlements.values().stream().map(ReSyncContentBrowserWidget::settledDeleteResult)
                .map(FlowManager.ResourceDeleteResult::message).filter(value -> !value.isBlank()).findFirst()
                .orElse(exception != null ? "Delete Failed" : "Project Metadata Delete Failed");
            new Notification("Delete", message, Notification.Type.ERROR);
        }
    }

    static Set<String> settledDeleteKeys(
        Map<String, Async<FlowManager.ResourceDeleteResult>> settlements) {
        if (settlements == null || settlements.isEmpty()) return Set.of();
        return settlements.entrySet().stream()
            .filter(entry -> settledDeleteResult(entry.getValue()).deleted())
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static FlowManager.ResourceDeleteResult settledDeleteResult(
        Async<FlowManager.ResourceDeleteResult> settlement) {
        if (settlement == null || !settlement.isDone() || BrowserWork.failed(settlement)
            || settlement.isCancelled()) {
            return new FlowManager.ResourceDeleteResult("", "", false, "Delete Failed");
        }
        try {
            return settlement.getNow(new FlowManager.ResourceDeleteResult("", "", false, "Delete Failed"));
        } catch (RuntimeException exception) {
            return new FlowManager.ResourceDeleteResult("", "", false, "Delete Failed");
        }
    }

    private void deleteWorldGenResource(FlowManager manager, ReSyncProjectMetadata.ResourceEntry resource) {
        String serverId = screen.studioServerId();
        String projectId = resource.getId();
        BrowserEditStart edit = beginBrowserEdit("Delete", List.of(resource));
        if (edit == null) {
            new Notification("Delete", "World Generation Delete Failed", Notification.Type.ERROR);
            return;
        }
        WeakReference<ReSyncContentBrowserWidget> widgetReference = new WeakReference<>(this);
        WorldGenManager.WorldGenMetadataIntent metadataIntent = new WorldGenManager.WorldGenMetadataIntent(
            resource.getDisplayName(), resource.getPath(), resource.getSortOrder());
        WorldGenManager.ProjectDeleteSubmission[] submission = new WorldGenManager.ProjectDeleteSubmission[1];
        submission[0] = WorldGenManager.getInstance().deleteProject(serverId, projectId,
            metadataIntent,
            settlement -> {
            if (settlement == null || submission[0] == null || !submission[0].equals(settlement.submission())
                || !settlement.committed() || !serverId.equals(settlement.submission().serverId())
                || !projectId.equals(settlement.submission().projectId()) || settlement.authoritativeRevision() < 1L
                || settlement.connection() == null || settlement.authorityEpoch() < 1L) {
                ReSyncContentBrowserWidget widget = widgetReference.get();
                if (widget != null && serverId.equals(widget.screen.studioServerId())) {
                    new Notification("Delete", "World Generation Delete Failed", Notification.Type.ERROR);
                }
                return;
            }
            String key = ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.WORLDGEN, projectId);
            String operationId = settlement.submission().operationId();
            boolean admitted = WorldGenManager.getInstance().reconcileWorldGenProjectMetadata(settlement,
                metadataIntent.displayName(), metadataIntent.path(), metadataIntent.sortOrder(), result -> {
                    ReSyncContentBrowserWidget widget = widgetReference.get();
                    if (widget == null) {
                        return;
                    }
                    FlowManager currentManager = FlowManager.getInstance();
                    WorldGenManager currentWorldGen = WorldGenManager.getInstance();
                    if (result == null || !operationId.equals(result.operationId())
                        || !serverId.equals(widget.screen.studioServerId()) || !serverId.equals(result.serverId())
                        || !projectId.equals(result.projectId())
                        || result.add() || !result.successful() || currentManager == null || currentWorldGen == null
                        || result.connection() == null || !currentManager.isCurrentServerConnection(result.connection())
                        || currentWorldGen.authorityEpoch(serverId) != result.authorityEpoch()) {
                        if (result != null && !result.successful()) {
                            new Notification("Delete", "World Generation Delete Failed", Notification.Type.ERROR);
                        }
                        return;
                    }
                    widget.finishWorldGenDelete(edit, key, metadataIntent.path());
                });
            if (!admitted) {
                new Notification("Delete", "World Generation Delete Failed", Notification.Type.ERROR);
            }
        });
        if (submission[0] == null) {
            new Notification("Delete", "World Generation Delete Failed", Notification.Type.ERROR);
        }
    }

    private void finishWorldGenDelete(BrowserEditStart edit, String key, String path) {
        String normalizedPath = ReSyncProjectMetadata.normalizePath(path);
        String deletedCurrentFolder = normalizedPath.isBlank() ? null
            : (currentFolder.equals(normalizedPath) || currentFolder.startsWith(normalizedPath + "/")
                ? normalizedPath : null);
        if (deletedCurrentFolder != null) {
            currentFolder = parentFolder(deletedCurrentFolder);
        }
        commitBrowserEdit(edit, Set.of(key));
        screen.removeStudioDocuments(Set.of(key));
        if (selectedResource != null && selectedResource.key().equals(key)) {
            selectedResource = null;
        }
        selectedFolder = null;
        selectedProjectRoot = false;
        rebuild();
        new Notification("Deleted", "World Generation", Notification.Type.SUCCESS);
    }

    private Async<FlowManager.ResourceDeleteResult> deleteResource(FlowManager manager,
                                                                               ReSyncProjectMetadata.ResourceEntry resource) {
        if (ReSyncResourceDragPayload.WORLD.equals(resource.getType())) {
                WorldResourceCreator.showDeletePopup(screen, screen.studioServerId(), resource.getId(), () -> {
                    screen.removeStudioDocuments(Set.of(resource.key()));
                    if (selectedResource != null && selectedResource.key().equals(resource.key())) selectedResource = null;
                    rebuild();
                });
            return Async.completed(new FlowManager.ResourceDeleteResult(resource.getType(),
                resource.getId(), false, "World Delete Pending"));
        }
        ReSyncResourceType type = ReSyncResourceType.byTypeId(resource.getType());
        if (type == null) {
            return Async.completed(new FlowManager.ResourceDeleteResult(resource.getType(),
                resource.getId(), false, "Resource Delete Unsupported"));
        }
        return manager.deleteResourceSettled(screen.studioServerId(), type, resource.getId());
    }

    private boolean renameCommandResource(FlowManager manager, String oldId, String newId) {
        return manager.renameFlow(screen.studioServerId(), oldId, newId);
    }

    private CommandBindingContext parseCommandContext(String context) {
        CommandBindingContext parsed = new CommandBindingContext();
        String trimmed = context == null ? "" : context.trim();
        if (trimmed.isBlank()) {
            return parsed;
        }
        if (trimmed.startsWith("{")) {
            try {
                CommandBindingContext decoded = CommandBindingContext.fromJson(trimmed);
                if (decoded != null) {
                    return decoded;
                }
            } catch (RuntimeException ignored) {
            }
        }
        parsed.command = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        parsed.subcommands = new ArrayList<>();
        parsed.structured = false;
        return parsed;
    }

    private String encodeCommandContext(CommandBindingContext command) {
        return command.toJson();
    }

    private boolean renameSelectedFolder(FlowManager manager, String newName) {
        String name = newName == null ? "" : newName.trim();
        if (name.isBlank() || name.contains("/") || name.contains("\\")) {
            new Notification("Explorer", "Invalid Name", Notification.Type.ERROR);
            return false;
        }
        FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(screen.studioServerId());
        String oldPath = selectedFolder.getPath();
        String parent = selectedFolder.getParentPath();
        String newPath = parent.isBlank() ? name : parent + "/" + name;
        if (!oldPath.equals(newPath) && metadata.folder(newPath) != null) {
            new Notification("Explorer", "Folder Already Exists", Notification.Type.ERROR);
            return false;
        }
        relocateFolder(metadata, oldPath, newPath, parent);
        FlowManager.ProjectFolder renamed = metadata.folder(newPath);
        if (renamed != null) metadata.putFolder(newPath, renamed.parentPath(), name, renamed.sortOrder(), renamed.collapsed());
        if (currentFolder.equals(oldPath) || currentFolder.startsWith(oldPath + "/")) {
            currentFolder = newPath + currentFolder.substring(oldPath.length());
        }
        manager.saveProjectMetadata(metadata, true);
        rebuild();
        return true;
    }

    private String iconPathFor(ReSyncProjectMetadata.ResourceEntry resource) {
        String iconPath = resourceIconPaths.get(resource.key());
        return iconPath != null ? iconPath : screen.studioResourceIconPath(resource.getType(), resource.getId());
    }

    private void layoutContainersIfDirty() {
        if (browser == null) {
            return;
        }
        Container panel = sidePanel.container();
        layoutGate.updatePanel(panel.getX(), panel.getY(), panel.getWidth(), panel.getHeight());
        if (!layoutGate.drain()) {
            return;
        }
        browser.layout();
    }

    private void applyBounds(int x, int y, int width, int height) {
        boolean positionChanged = getX() != x || getY() != y;
        boolean sizeChanged = getWidth() != width || getHeight() != height;
        if (positionChanged) {
            super.setPosition(x, y);
        }
        if (sizeChanged) {
            super.setSize(width, height);
        }
        layoutGate.update(x, y, width, height);
    }

    public int browserHeight() {
        return 0;
    }

    public int defaultHeight() {
        return Math.max(120, screen.screenHeight() - STUDIO_CONTENT_BROWSER_TOP - STUDIO_CONTENT_BROWSER_BOTTOM);
    }

    public void resetToDefaultHeight() {
    }

    public void collapse() {
        temporarilyHidden = false;
        if (sidePanel != null) {
            sidePanel.show();
        }
        screen.refreshStudioLayoutPositions();
    }

    public void slideOut() {
        temporarilyHidden = true;
        shortcutFocused = false;
        if (sidePanel != null) {
            sidePanel.hide();
        }
        screen.refreshStudioLayoutPositions();
    }

    public void setTemporarilyHidden(boolean hidden) {
        temporarilyHidden = hidden;
        if (hidden) {
            shortcutFocused = false;
            screen.clearStudioFocus();
        }
        if (sidePanel != null) {
            if (hidden) {
                sidePanel.hideImmediately();
            } else {
                sidePanel.show();
            }
        }
        screen.refreshStudioLayoutPositions();
    }

    public boolean isTemporarilyHidden() {
        return temporarilyHidden;
    }

    public boolean isSlideOutFinished() {
        return sidePanel == null || sidePanel.getAnimatedWidth() <= 1f;
    }

    public boolean isCollapsed() {
        return sidePanel == null || !sidePanel.isVisible();
    }

    public void clampHeight() {
    }

    public void layoutInScreen() {
        applyBounds(0, STUDIO_CONTENT_BROWSER_TOP,
            sidePanel != null ? sidePanel.getConfiguredWidth() : STUDIO_CONTENT_BROWSER_DEFAULT_WIDTH, defaultHeight());
        layoutContainersIfDirty();
    }

    public int editorHeight() {
        return screen.screenHeight();
    }

    public int panelBottomReserve() {
        return 0;
    }

    static ReSyncProjectTreeProvider prepareTreeProvider(RemotePath projectRoot, List<BrowserFolder> allFolders,
                                                         List<BrowserResource> allResources) {
        Map<RemotePath, String> folderPaths = new HashMap<>();
        Map<RemotePath, BrowserFolder> folders = new HashMap<>();
        Map<RemotePath, BrowserResource> resources = new HashMap<>();
        Map<String, RemotePath> resourcePaths = new HashMap<>();
        Map<RemotePath, List<RemoteFileSystemProvider.FileEntry>> entriesByFolder = new HashMap<>();
        folderPaths.put(projectRoot, "");
        for (BrowserFolder folder : allFolders) {
            RemotePath path = pathForFolder(projectRoot, folder.path());
            folderPaths.put(path, folder.path());
            folders.put(path, folder);
            RemotePath parent = folder.parentPath().isBlank() ? projectRoot : pathForFolder(projectRoot, folder.parentPath());
            RemoteFileSystemProvider.FileEntry entry = new RemoteFileSystemProvider.FileEntry(path, true, "-", "", folder.name());
            entry.metadata.put("icon", "explorer.png");
            entriesByFolder.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(entry);
        }
        for (BrowserResource resource : allResources) {
            String folder = resourceFolderPath(resource.path(), resource.id());
            RemotePath path = pathForFolder(projectRoot, folder).resolve(resource.type()).resolve(resource.id());
            resources.put(path, resource);
            resourcePaths.put(resource.key(), path);
            RemotePath parent = folder.isBlank() ? projectRoot : pathForFolder(projectRoot, folder);
            String label = !resource.id().isBlank() ? resource.id() : resource.displayName();
            RemoteFileSystemProvider.FileEntry entry = new RemoteFileSystemProvider.FileEntry(path, false, "", "", label);
            entry.metadata.put("icon", resource.iconPath());
            if (!resource.enabled()) {
                entry.metadata.put("metaText", "●");
                entry.metadata.put("metaAccent", "danger");
                entry.metadata.put("textAccent", "calm");
            }
            entriesByFolder.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(entry);
        }
        Map<RemotePath, List<RemoteFileSystemProvider.FileEntry>> preparedEntries = new HashMap<>();
        for (Map.Entry<RemotePath, List<RemoteFileSystemProvider.FileEntry>> folderEntries : entriesByFolder.entrySet()) {
            List<RemoteFileSystemProvider.FileEntry> entries = folderEntries.getValue();
            entries.sort((left, right) -> Boolean.compare(!left.isDirectory, !right.isDirectory) != 0
                ? Boolean.compare(!left.isDirectory, !right.isDirectory)
                : left.displayName.compareToIgnoreCase(right.displayName));
            for (RemoteFileSystemProvider.FileEntry entry : entries) {
                if (entry.isDirectory) {
                    entry.metadata.put("hasChildren", String.valueOf(!entriesByFolder.getOrDefault(entry.path, List.of()).isEmpty()));
                }
            }
            preparedEntries.put(folderEntries.getKey(), List.copyOf(entries));
        }
        List<RemotePath> directoryPaths = folders.keySet().stream().sorted(Comparator.comparing(RemotePath::toString)).toList();
        return new ReSyncProjectTreeProvider(Map.copyOf(folderPaths), Map.copyOf(folders), Map.copyOf(resources),
            Map.copyOf(resourcePaths), Map.copyOf(preparedEntries), directoryPaths, allFolders.size() + allResources.size());
    }

    static final class ReSyncProjectTreeProvider implements RemoteFileSystemProvider {
        private final Map<RemotePath, String> folderPaths;
        private final Map<RemotePath, BrowserFolder> folders;
        private final Map<RemotePath, BrowserResource> resources;
        private final Map<String, RemotePath> resourcePaths;
        private final Map<RemotePath, List<RemoteFileSystemProvider.FileEntry>> entriesByFolder;
        private final List<RemotePath> folderDirectoryPaths;
        private final int entryCount;

        private ReSyncProjectTreeProvider(Map<RemotePath, String> folderPaths,
                                          Map<RemotePath, BrowserFolder> folders,
                                          Map<RemotePath, BrowserResource> resources,
                                          Map<String, RemotePath> resourcePaths,
                                          Map<RemotePath, List<RemoteFileSystemProvider.FileEntry>> entriesByFolder,
                                          List<RemotePath> folderDirectoryPaths, int entryCount) {
            this.folderPaths = folderPaths;
            this.folders = folders;
            this.resources = resources;
            this.resourcePaths = resourcePaths;
            this.entriesByFolder = entriesByFolder;
            this.folderDirectoryPaths = folderDirectoryPaths;
            this.entryCount = entryCount;
        }

        private static ReSyncProjectTreeProvider empty(RemotePath root) {
            return new ReSyncProjectTreeProvider(Map.of(root, ""), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), 0);
        }

        private String folderPath(RemotePath path) {
            return folderPaths.get(path);
        }

        private ReSyncProjectMetadata.FolderEntry folder(RemotePath path) {
            BrowserFolder folder = folders.get(path);
            return folder != null ? folder.materialize() : null;
        }

        private ReSyncProjectMetadata.ResourceEntry resource(RemotePath path) {
            BrowserResource resource = resources.get(path);
            return resource != null ? resource.materialize() : null;
        }

        private RemotePath resourcePath(String type, String id) {
            return resourcePaths.get(resourceKey(type, id));
        }

        private RemotePath resourcePath(String key) {
            return resourcePaths.get(key);
        }

        boolean containsResourceKey(String key) {
            return resourcePaths.containsKey(key);
        }

        boolean containsFolderPath(RemotePath path) {
            return folderPaths.containsKey(path);
        }

        private List<RemotePath> folderDirectoryPaths() {
            return folderDirectoryPaths;
        }

        int entryCount() {
            return entryCount;
        }

        @Override
        public Async<List<RemoteFileSystemProvider.FileEntry>> ls(RemotePath path) {
            String folder = folderPaths.get(path);
            if (folder == null) {
                return Async.completed(List.of());
            }
            return Async.completed(entriesByFolder.getOrDefault(path, List.of()));
        }

        @Override
        public Async<Void> copy(List<RemotePath> sources, RemotePath destination) {
            return Async.completed(null);
        }

        @Override
        public Async<Void> move(List<RemotePath> sources, RemotePath destination) {
            return Async.completed(null);
        }

        @Override
        public Async<Void> delete(List<RemotePath> paths) {
            return Async.completed(null);
        }

        @Override
        public Async<String> read(RemotePath path) {
            return Async.completed("");
        }

        @Override
        public Async<Void> write(RemotePath path, String content) {
            return Async.completed(null);
        }

        @Override
        public Async<Void> upload(List<TransferSource> sources, RemotePath destination) {
            return Async.completed(null);
        }

        @Override
        public Async<Void> download(List<RemotePath> sources, TransferSink destination) {
            return Async.completed(null);
        }

        @Override
        public Async<Void> rename(RemotePath oldPath, RemotePath newPath) {
            return Async.completed(null);
        }

        @Override
        public Async<Void> createFile(RemotePath path) {
            return Async.completed(null);
        }

        @Override
        public Async<Void> createDirectory(RemotePath path) {
            return Async.completed(null);
        }

        @Override
        public Async<Boolean> exists(RemotePath path) {
            return Async.completed(folderPaths.containsKey(path) || resources.containsKey(path));
        }

        @Override
        public String getMetadata(String key) {
            return switch (key) {
                case "type" -> "RESYNC";
                case "rootIcon" -> "ReSync.png";
                default -> null;
            };
        }
    }

    private RemotePath pathForFolder(String path) {
        return pathForFolder(projectRoot, path);
    }

    private static RemotePath pathForFolder(RemotePath projectRoot, String path) {
        if (path == null || path.isBlank()) {
            return projectRoot;
        }
        RemotePath result = projectRoot;
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                result = result.resolve(part);
            }
        }
        return result;
    }

    private RemotePath pathForResource(ReSyncProjectMetadata.ResourceEntry resource) {
        return pathForFolder(resourceFolderPath(resource.getPath(), resource.getId()))
            .resolve(resource.getType()).resolve(resource.getId());
    }

    static String resourceFolderPath(String path, String id) {
        String normalized = ReSyncProjectMetadata.normalizePath(path);
        String resourceId = id != null ? id.trim() : "";
        if (normalized.isBlank() || resourceId.isBlank()) {
            return normalized;
        }
        int separator = normalized.lastIndexOf('/');
        String name = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        if (!name.equals(resourceId + ".json")) {
            return normalized;
        }
        return separator >= 0 ? normalized.substring(0, separator) : "";
    }

    static String canonicalResourcePath(String folder, String id) {
        String normalizedFolder = ReSyncProjectMetadata.normalizePath(folder);
        String resourceId = id != null ? id.trim() : "";
        if (resourceId.isBlank()) {
            return normalizedFolder;
        }
        return normalizedFolder.isBlank() ? resourceId + ".json" : normalizedFolder + "/" + resourceId + ".json";
    }

    static boolean resourceWithinFolder(String path, String id, String folder) {
        String resourceFolder = resourceFolderPath(path, id);
        String normalizedFolder = ReSyncProjectMetadata.normalizePath(folder);
        return resourceFolder.equals(normalizedFolder)
            || !normalizedFolder.isBlank() && resourceFolder.startsWith(normalizedFolder + "/");
    }

}
