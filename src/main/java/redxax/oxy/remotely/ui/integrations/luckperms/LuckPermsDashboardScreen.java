package redxax.oxy.remotely.ui.integrations.luckperms;

import java.time.Duration;
import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.TaskSchedulers;
import redxax.oxy.remotely.util.BrowserSafeState;

import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsClient;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsNetworkClient;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsNetworkClient.DistributionResult;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsNetworkClient.Snapshot;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsNetworkClient.TargetResult;
import redxax.oxy.remotely.data.player.model.PlayerAvatarUrl;
import redxax.oxy.remotely.flow.ui.OptionCatalogSelector;
import restudio.rescreen.theme.ThemeManager;
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
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Identifier;
import restudio.resync.permissions.LuckPermsManagementContract;
import restudio.resync.permissions.LuckPermsManagementContract.ChangeSet;
import restudio.resync.permissions.LuckPermsManagementContract.EffectivePreview;
import restudio.resync.permissions.LuckPermsManagementContract.EntityCreate;
import restudio.resync.permissions.LuckPermsManagementContract.EntityDelete;
import restudio.resync.permissions.LuckPermsManagementContract.EntityType;
import restudio.resync.permissions.LuckPermsManagementContract.GroupPage;
import restudio.resync.permissions.LuckPermsManagementContract.GroupSummary;
import restudio.resync.permissions.LuckPermsManagementContract.NodeData;
import restudio.resync.permissions.LuckPermsManagementContract.NodeKind;
import restudio.resync.permissions.LuckPermsManagementContract.Overview;
import restudio.resync.permissions.LuckPermsManagementContract.PageRequest;
import restudio.resync.permissions.LuckPermsManagementContract.PreviewRequest;
import restudio.resync.permissions.LuckPermsManagementContract.SubjectChange;
import restudio.resync.permissions.LuckPermsManagementContract.SubjectDetail;
import restudio.resync.permissions.LuckPermsManagementContract.SubjectRef;
import restudio.resync.permissions.LuckPermsManagementContract.SubjectType;
import restudio.resync.permissions.LuckPermsManagementContract.TrackChange;
import restudio.resync.permissions.LuckPermsManagementContract.TrackDetail;
import restudio.resync.permissions.LuckPermsManagementContract.UserPage;
import restudio.resync.permissions.LuckPermsManagementContract.UserSummary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import restudio.rescreen.platform.Async;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public final class LuckPermsDashboardScreen extends ReScreen {
    private static final int PAGE_SIZE = 80;
    private static final int TREE_WIDTH = 258;
    private static final int HEADER_HEIGHT = 36;
    private static final String RICH_RESET = "<reset>";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

    private final Screen parent;
    private final ReSyncLuckPermsClient client;
    private final ReSyncLuckPermsNetworkClient networkClient;
    private final List<UserSummary> users = new ArrayList<>();
    private final List<GroupSummary> groups = new ArrayList<>();
    private final List<TrackDetail> tracks = new ArrayList<>();
    private final Map<String, TreeEntryWidget> userEntries = new LinkedHashMap<>();
    private final Map<String, TreeEntryWidget> groupEntries = new LinkedHashMap<>();
    private final Map<String, TreeEntryWidget> trackEntries = new LinkedHashMap<>();
    private final LuckPermsServerIcons serverIcons;
    private final Map<SubjectRef, SubjectView> subjectViews = new LinkedHashMap<>();
    private final Map<String, SubjectDetail> groupGraphDetails = new LinkedHashMap<>();
    private final Map<String, TrackView> trackViews = new LinkedHashMap<>();
    private final List<Setting> workspaces = new ArrayList<>();
    private final List<MessageWidget> auditWidgets = new ArrayList<>();
    private final List<MessageWidget> previewMatches = new ArrayList<>();
    private final ReSyncLuckPermsClient.Listener listener = new ReSyncLuckPermsClient.Listener() {
        @Override
        public void onInvalidated(LuckPermsManagementContract.Invalidation invalidation) {
            ui(() -> refreshInvalidated(invalidation));
        }

        @Override
        public void onAvailability(boolean available, String message) {
            ui(() -> {
                LuckPermsDashboardScreen.this.available = available;
                setStatus(available ? "Connected" : "LuckPerms Unavailable", available ? "Changes Save Immediately" : message, available ? "nice" : "danger");
                if (available && !loaded) {
                    loadAll();
                }
            });
        }
    };

    private ReSyncLuckPermsClient.Subscription subscription;
    private SidePanel workTree;
    private Container workspace;
    private Setting groupsTree;
    private Setting usersTree;
    private Setting tracksTree;
    private Setting toolsTree;
    private Setting networkTree;
    private Setting overviewView;
    private Setting addPermissionView;
    private Setting checkView;
    private Setting inheritanceView;
    private Setting networkView;
    private List<Setting> activeSections = List.of();
    private MessageWidget status;
    private MessageWidget networkStatus;
    private SearchMode searchMode;
    private TextInputWidget treeSearch;
    private TreeEntryWidget groupsMore;
    private TreeEntryWidget usersMore;
    private TreeEntryWidget networkEntry;
    private LuckPermsInheritanceGraphWidget inheritanceGraph;
    private LuckPermsNetworkGraphWidget networkGraph;
    private Snapshot networkSnapshot;
    private SubjectView activeSubject;
    private TrackView activeTrack;
    private String userCursor = "";
    private String groupCursor = "";
    private String filter = "";
    private String treeFilter = "";
    private boolean usersHasMore;
    private boolean groupsHasMore;
    private boolean loadingUsers;
    private boolean loadingGroups;
    private boolean loadingTracks;
    private boolean available;
    private boolean loaded;
    private boolean disposed;
    private boolean networkRefreshScheduled;
    private boolean networkTreeAttached;
    private int networkRefreshAttempts;
    private long previewSequence;

    public LuckPermsDashboardScreen(Screen parent, ReSyncLuckPermsClient client) {
        this(parent, client, new LuckPermsServerIcons());
    }

    public LuckPermsDashboardScreen(Screen parent, ReSyncLuckPermsClient client, LuckPermsServerIcons serverIcons) {
        this.parent = parent;
        this.client = client;
        this.serverIcons = serverIcons == null ? new LuckPermsServerIcons() : serverIcons;
        networkClient = client.network();
    }

    public static Async<LuckPermsDashboardScreen> prepare(Screen parent, ReSyncLuckPermsClient client) {
        Objects.requireNonNull(client, "client");
        return Async.completed(new LuckPermsDashboardScreen(parent, client));
    }

    @Override
    public String getDesktopAppId() {
        return "luckperms";
    }

    @Override
    public String getDesktopAppTitle() {
        return "Permissions";
    }

    @Override
    public String getDesktopAppIconPath() {
        return "op.png";
    }

    @Override
    public void init() {
        super.init();
        disposed = false;
        loaded = false;
        users.clear();
        groups.clear();
        tracks.clear();
        userEntries.clear();
        groupEntries.clear();
        trackEntries.clear();
        subjectViews.clear();
        groupGraphDetails.clear();
        trackViews.clear();
        workspaces.clear();
        auditWidgets.clear();
        previewMatches.clear();
        activeSections = List.of();
        activeSubject = null;
        activeTrack = null;
        filter = "";
        treeFilter = "";
        networkTreeAttached = false;
        if (subscription != null) {
            subscription.close();
        }
        OptionCatalogLoader.preload(client.serverId(), List.of(
            OptionCatalogLoader.request("server:luckperms:permission"),
            OptionCatalogLoader.request("server:luckperms:group"),
            OptionCatalogLoader.request("server:luckperms:track"),
            OptionCatalogLoader.request("server:minecraft:world"),
            OptionCatalogLoader.request("server:resync:network_node")));
        searchMode = new SearchMode(false);
        searchMode.setPlaceholder("Search Permissions");
        searchMode.setOnTextChange(this::search);
        header().addRight("close.png", this::close, "Close");
        header().addRight("create.png", this::showCreateMenu, "Create User, Group, Or Track");
        header().addRight("add.png", this::addForActive, "Add Permission");
        header().setSearchMode(searchMode, true);
        header().build();
        buildWorkTree();
        buildWorkspace();
        subscription = client.subscribe(listener);
        available = client.isAvailable();
        showOverview();
        loadAll();
    }

    private void buildWorkTree() {
        workTree = createSidePanel("luckperms-work-tree").required().left().y(HEADER_HEIGHT).height(Math.max(100, height - HEADER_HEIGHT - 5))
            .minWidth(210).maxWidth(380).maxWidthRatio(45).width(TREE_WIDTH).padding(3).gap(2).scrolling(true).show();
        treeSearch = new TextInputWidget.Builder().placeholder("Search Users, Groups, And Tracks").search(true).size(TREE_WIDTH - 10, 18).onChange(value -> {
            treeFilter = normalize(value);
            filterTree();
            workTree.container().resetScroll();
        }).build();
        workTree.addWidget(treeSearch);
        groupsTree = new Setting.Builder("Groups").build();
        usersTree = new Setting.Builder("Users").build();
        tracksTree = new Setting.Builder("Tracks").build();
        toolsTree = new Setting.Builder("Tools").build();
        networkTree = new Setting.Builder("Network").build();
        workTree.addWidget(groupsTree, usersTree, tracksTree, toolsTree);
        addTreeRow(groupsTree, "loading", new TreeEntryWidget("Loading Groups", "Reading Inheritance", "manager.png", () -> {}));
        addTreeRow(usersTree, "loading", new TreeEntryWidget("Loading Users", "Reading Players", "", () -> {}));
        addTreeRow(tracksTree, "loading", new TreeEntryWidget("Loading Tracks", "Reading Promotion Paths", "graph.png", () -> {}));
        addTreeRow(toolsTree, "overview", new TreeEntryWidget("Overview", "Permission Status And Activity", "info.png", this::showOverview));
        addTreeRow(toolsTree, "add-permission", new TreeEntryWidget("Add Permission", "Grant Or Deny Direct Access", "add.png", this::showGlobalPermissionComposer));
        addTreeRow(toolsTree, "check", new TreeEntryWidget("Permission Check", "Resolve Allowed Or Denied Access", "search.png", this::showPermissionCheck));
        addTreeRow(toolsTree, "inheritance", new TreeEntryWidget("Group Inheritance", "Connect Permission Groups", "graph.png", this::showInheritance));
        networkEntry = new TreeEntryWidget("Permission Delivery", "Choose Network Servers", "server.png", this::showNetwork);
        addTreeRow(networkTree, "network", networkEntry);
    }

    private void buildWorkspace() {
        int left = treeWidth();
        workspace = createContainer("luckperms-workspace", left, HEADER_HEIGHT, Math.max(220, width - left - 5), Math.max(100, height - HEADER_HEIGHT - 5));
        workspace.layout(new ManagedLayout()).padding(5).columns(1).verticalSpacing(4).scrolling(true).backgroundDrawing(true);
        overviewView = addWorkspace(new Setting.Builder("Permissions").build());
        addPermissionView = addWorkspace(new Setting.Builder("Add Permission").build());
        checkView = addWorkspace(new Setting.Builder("Permission Check").build());
        inheritanceView = addWorkspace(new Setting.Builder("Group Inheritance").build());
        networkView = addWorkspace(new Setting.Builder("Permission Delivery").build());
        status = new MessageWidget(contentWidth(), "Connecting", "Opening Permission Management");
        addRow(overviewView, "status", 30, status);
        for (String title : List.of("Players", "Groups", "Tracks")) {
            addRow(overviewView, "metric:" + title, 30, new MessageWidget(contentWidth(), title, "Loading"));
        }
        addRow(overviewView, "activity", 30, infoRow("Recent Changes", "The Latest LuckPerms Activity On This Server", "Live Audit"));
        for (int i = 0; i < 6; i++) {
            MessageWidget audit = new MessageWidget(contentWidth(), "", "");
            auditWidgets.add(audit);
            addRow(overviewView, "audit:" + i, 30, audit);
            overviewView.setRowVisibility("audit:" + i, false);
        }
        buildGlobalPermissionComposer();
        buildPermissionCheck();
        inheritanceGraph = new LuckPermsInheritanceGraphWidget(0, 0, contentWidth(), this::openGroupFromGraph, this::connectGroups, this::disconnectGroups);
        inheritanceGraph.setMinimumHeight(Math.max(280, workspace.getHeight() - 80));
        addRow(inheritanceView, "help", 30, infoRow("Group Inheritance", "Drag A Link To A Parent. Use Remove Link, Then Choose A Connected Parent.", "Changes Save Immediately"));
        addRow(inheritanceView, "graph", inheritanceGraph.getHeight(), inheritanceGraph);
        networkStatus = new MessageWidget(contentWidth(), "Checking Network", "Permission Delivery Appears Only For Network Servers");
        addRow(networkView, "status", 30, networkStatus);
        setActiveContainer(workspace);
    }

    private Setting addWorkspace(Setting setting) {
        setting.visible = false;
        workspaces.add(setting);
        return setting;
    }

    private void loadAll() {
        if (loaded) {
            return;
        }
        loaded = true;
        refreshOverview();
        loadGroups(true);
        loadUsers(true);
        loadTracks();
        refreshNetwork();
    }

    private void refreshOverview() {
        complete(client.overview(), this::applyOverview, "Could Not Load Permission Status");
    }

    private void applyOverview(Overview overview) {
        available = overview.available();
        List<String> values = List.of(
            overview.knownUsers() + " Known • " + overview.onlineUsers() + " Online",
            overview.groups() + " Permission Groups",
            overview.tracks() + " Promotion Tracks");
        for (int i = 0; i < values.size(); i++) {
            String metricTitle = List.of("Players", "Groups", "Tracks").get(i);
            PopupWidget.PopupRow row = overviewView.getRows().stream().filter(candidate -> candidate.id.equals("metric:" + metricTitle)).findFirst().orElse(null);
            if (row != null && !row.getWidgets().isEmpty() && row.getWidgets().getFirst() instanceof MessageWidget metric) {
                metric.setText(metricTitle, values.get(i));
            }
        }
        setTreeState(overviewView, "activity", "Recent Changes", overview.lastChangedAt() == 0 ? "No Permission Changes Yet" : "Latest Change • " + TIME.format(Instant.ofEpochMilli(overview.lastChangedAt())));
        for (int i = 0; i < auditWidgets.size(); i++) {
            boolean visible = i < overview.audit().size();
            if (visible) {
                LuckPermsManagementContract.AuditEntry entry = overview.audit().get(i);
                String actor = entry.actor().isBlank() ? "Server" : entry.actor();
                auditWidgets.get(i).setText(entry.action() + " • " + entry.target(), actor + " • " + TIME.format(Instant.ofEpochMilli(entry.changedAt())));
                auditWidgets.get(i).setHiddenText(entry.success() ? "Saved" : "Failed");
                auditWidgets.get(i).setAccent(entry.success() ? ThemeManager.getDefaultAccent() : ThemeManager.getAccent("danger"));
            }
            overviewView.setRowVisibility("audit:" + i, visible);
        }
        setStatus(overview.available() ? overview.serverName() : "LuckPerms Unavailable", overview.available()
            ? "LuckPerms " + (overview.version().isBlank() ? "Ready" : overview.version()) + " • Saved Revision " + overview.revision()
            : "Install Or Enable LuckPerms", overview.available() ? "nice" : "danger");
        updateWorkspace();
    }

    private void loadUsers(boolean reset) {
        if (loadingUsers) {
            return;
        }
        loadingUsers = true;
        complete(client.users(new PageRequest(reset ? "" : userCursor, PAGE_SIZE, "")), page -> {
            loadingUsers = false;
            applyUsers(page, reset);
        }, "Could Not Load Users", () -> loadingUsers = false);
    }

    private void applyUsers(UserPage page, boolean reset) {
        if (reset) {
            users.clear();
            userCursor = "";
            userEntries.values().forEach(entry -> entry.visible = false);
        }
        for (UserSummary user : page.items()) {
            int index = findUser(user.uniqueId());
            if (index >= 0) {
                users.set(index, user);
            } else {
                users.add(user);
            }
            TreeEntryWidget entry = userEntries.computeIfAbsent(user.uniqueId(), id -> {
                TreeEntryWidget created = new TreeEntryWidget(displayUser(user), "", "", () -> openSubject(new SubjectRef(SubjectType.USER, id)));
                addTreeRow(usersTree, "user:" + id, created);
                return created;
            });
            updateUserEntry(user, entry);
            requestUserFace(user.uniqueId(), user.username(), entry::setGeneratedIcon);
            entry.visible = true;
        }
        userCursor = page.nextCursor();
        usersHasMore = page.hasMore();
        setTreeState(usersTree, "loading", "No Users", "Players Appear After LuckPerms Has Seen Them");
        usersTree.setRowVisibility("loading", users.isEmpty());
        if (usersMore == null) {
            usersMore = new TreeEntryWidget("Load More Users", "Continue Browsing", "down.png", () -> loadUsers(false));
            addTreeRow(usersTree, "more", usersMore);
        }
        usersMore.visible = usersHasMore;
        usersTree.setRowVisibility("more", usersHasMore);
        filterTree();
    }

    private void loadGroups(boolean reset) {
        if (loadingGroups) {
            return;
        }
        loadingGroups = true;
        complete(client.groups(new PageRequest(reset ? "" : groupCursor, PAGE_SIZE, "")), page -> {
            loadingGroups = false;
            applyGroups(page, reset);
        }, "Could Not Load Groups", () -> loadingGroups = false);
    }

    private void applyGroups(GroupPage page, boolean reset) {
        if (reset) {
            groups.clear();
            groupCursor = "";
            groupEntries.values().forEach(entry -> entry.visible = false);
        }
        for (GroupSummary group : page.items()) {
            int index = findGroup(group.name());
            if (index >= 0) {
                groups.set(index, group);
            } else {
                groups.add(group);
            }
            TreeEntryWidget entry = groupEntries.computeIfAbsent(group.name(), name -> {
                TreeEntryWidget created = new TreeEntryWidget(displayGroup(group), "", "manager.png", () -> openSubject(new SubjectRef(SubjectType.GROUP, name)));
                addTreeRow(groupsTree, "group:" + name, created);
                return created;
            });
            entry.setEntry(displayGroup(group), group.weight() == null ? group.name() : group.name() + " • Priority " + group.weight(), group.members() + " Members");
            entry.visible = true;
        }
        groupCursor = page.nextCursor();
        groupsHasMore = page.hasMore();
        setTreeState(groupsTree, "loading", "No Groups", "Create A Group To Build Permission Inheritance");
        groupsTree.setRowVisibility("loading", groups.isEmpty());
        if (groupsMore == null) {
            groupsMore = new TreeEntryWidget("Load More Groups", "Continue Browsing", "down.png", () -> loadGroups(false));
            addTreeRow(groupsTree, "more", groupsMore);
        }
        groupsMore.visible = groupsHasMore;
        groupsTree.setRowVisibility("more", groupsHasMore);
        filterTree();
        refreshInheritanceGraph();
    }

    private void loadTracks() {
        if (loadingTracks) {
            return;
        }
        loadingTracks = true;
        complete(client.tracks(), values -> {
            loadingTracks = false;
            tracks.clear();
            tracks.addAll(values);
            trackEntries.values().forEach(entry -> entry.visible = false);
            for (TrackDetail track : values) {
                TreeEntryWidget entry = trackEntries.computeIfAbsent(track.name(), name -> {
                    TreeEntryWidget created = new TreeEntryWidget(name, "", "graph.png", () -> openTrack(name));
                    addTreeRow(tracksTree, "track:" + name, created);
                    return created;
                });
                entry.setEntry(track.name(), track.groups().isEmpty() ? "No Groups" : String.join(" › ", track.groups()), track.groups().size() + " Groups");
                entry.visible = true;
                TrackView view = trackViews.get(track.name());
                if (view != null && !view.saving) {
                    view.apply(track);
                }
            }
            setTreeState(tracksTree, "loading", "No Tracks", "Create A Track For Ordered Promotions");
            tracksTree.setRowVisibility("loading", values.isEmpty());
            filterTree();
        }, "Could Not Load Tracks", () -> loadingTracks = false);
    }

    private void openSubject(SubjectRef subject) {
        SubjectView cached = subjectViews.get(subject);
        if (cached != null) {
            activeSubject = cached;
            activeTrack = null;
            showSubjectView(cached);
            return;
        }
        setStatus("Loading", "Opening " + subject.id(), "calm");
        complete(client.subject(subject), detail -> {
            SubjectView view = new SubjectView(detail);
            subjectViews.put(subject, view);
            activeSubject = view;
            activeTrack = null;
            showSubjectView(view);
            if (subject.type() == SubjectType.GROUP) {
                refreshInheritanceGraph();
            }
        }, "Could Not Load Player Or Group");
    }

    private void openTrack(String name) {
        TrackDetail track = tracks.stream().filter(value -> value.name().equals(name)).findFirst().orElse(null);
        if (track == null) {
            return;
        }
        TrackView view = trackViews.computeIfAbsent(name, ignored -> new TrackView(track));
        activeTrack = view;
        activeSubject = null;
        show(view.setting);
    }

    private void showOverview() {
        activeSubject = null;
        activeTrack = null;
        show(overviewView);
    }

    private void showPermissionCheck() {
        activeSubject = null;
        activeTrack = null;
        show(checkView);
    }

    private void showGlobalPermissionComposer() {
        activeSubject = null;
        activeTrack = null;
        show(addPermissionView);
    }

    private void showInheritance() {
        activeSubject = null;
        activeTrack = null;
        show(inheritanceView);
        loadGroupDetails();
    }

    private void showNetwork() {
        if (networkSnapshot == null || networkSnapshot.networkId().isBlank()) {
            setStatus("No Permission Network", "This Server Saves Permissions Locally", "calm");
            return;
        }
        activeSubject = null;
        activeTrack = null;
        show(networkView);
        refreshNetwork();
    }

    private void show(Setting setting) {
        showSections(List.of(setting));
    }

    private void showSubjectView(SubjectView view) {
        showSections(view.sections());
    }

    private void showSections(List<Setting> sections) {
        activeSections = sections == null ? List.of() : List.copyOf(sections);
        workspaces.forEach(panel -> panel.visible = activeSections.contains(panel));
        workspace.detachWidgets();
        workspace.beginBatchAdd();
        activeSections.forEach(workspace::addWidget);
        workspace.endBatchAdd();
        workspace.resetScroll();
        if (searchMode != null) {
            if (activeSubject == null && activeTrack == null) filter = "";
            searchMode.setPlaceholder(activeSubject != null ? "Search Permissions And Metadata" : "Search Track Groups");
            header().setSearchMode(activeSubject != null || activeTrack != null ? searchMode : null, true);
        }
        if (activeSubject != null) activeSubject.filter(filter);
        else if (activeTrack != null) activeTrack.filter(filter);
        else filterTree();
        updateWorkspace();
    }

    private void buildPermissionCheck() {
        SelectorField target = choiceField("Choose User Or Group", this::subjectChoices, "");
        SelectorField permission = catalogField("Choose Permission", "server:luckperms:permission", this::permissionOptions, "");
        SelectorField scope = choiceField("Everywhere", this::scopeChoices, "everywhere");
        MessageWidget result = new MessageWidget(contentWidth(), "Choose Access", "Select Who And Which Permission To Check");
        addRow(checkView, "help", 30, infoRow("Effective Permission", "See Whether Access Is Allowed Or Denied And Which Group Decided It", "Live LuckPerms Result"));
        addRow(checkView, "target", 34, paired("User Or Group", "Choose Who To Check", target, "Permission", "Choose The Access To Resolve", permission, 0.42f));
        addRow(checkView, "scope", 32, titled("Applies To", "Check Everywhere Or Within One Server Or World", scope));
        addRow(checkView, "result", 30, result);
        for (int i = 0; i < 12; i++) {
            MessageWidget match = new MessageWidget(contentWidth(), "", "");
            previewMatches.add(match);
            addRow(checkView, "match:" + i, 30, match);
            checkView.setRowVisibility("match:" + i, false);
        }
        addRow(checkView, "action", 30, actionButton("Check Permission", () -> resolvePermission(target, permission, scope, result)));
    }

    private void buildGlobalPermissionComposer() {
        SelectorField target = choiceField("Choose User Or Group", this::subjectChoices, "");
        SelectorField permission = catalogField("Choose Permission", "server:luckperms:permission", this::permissionOptions, "");
        SelectorField scope = choiceField("Everywhere", this::scopeChoices, "everywhere");
        ToggleWidget value = permissionToggle(true);
        addRow(addPermissionView, "help", 30, infoRow("Direct Permission", "Choose Who Receives Access And Whether It Is Allowed", "Saves Immediately"));
        addRow(addPermissionView, "target", 34, paired("User Or Group", "Choose Who Receives This Permission", target, "Applies To", "Use Everywhere Or Limit Access", scope, 0.62f));
        addRow(addPermissionView, "permission", 34, permissionValuePair(permission, value));
        addRow(addPermissionView, "action", 30, actionButton("Add Permission", () -> addGlobalPermission(target, permission, scope, value)));
    }

    private void addGlobalPermission(SelectorField target, SelectorField permission, SelectorField scope, ToggleWidget value) {
        SubjectRef subject = selectedSubject(target);
        if (subject == null || permission.value().isBlank()) {
            setStatus("Choose A User And Permission", "Both Values Are Required", "danger");
            return;
        }
        Consumer<SubjectView> add = view -> {
            Map<String, List<String>> contexts = selectedScope(scope);
            boolean exists = view.desired.directNodes().stream().anyMatch(node -> node.kind() == NodeKind.PERMISSION && node.key().equalsIgnoreCase(permission.value()) && node.contexts().equals(contexts));
            if (exists) {
                setStatus("Permission Already Added", permission.value(), "calm");
                return;
            }
            NodeData node = new NodeData(UUID.randomUUID().toString(), NodeKind.PERMISSION, permission.value(), value.getValue(), null, contexts, null);
            view.mutate(detail -> withNodes(detail, append(detail.directNodes(), node)));
            showSubjectView(view);
            activeSubject = view;
        };
        SubjectView view = subjectViews.get(subject);
        if (view != null) {
            add.accept(view);
        } else {
            complete(client.subject(subject), detail -> {
                SubjectView created = new SubjectView(detail);
                subjectViews.put(subject, created);
                add.accept(created);
            }, "Could Not Load User Or Group");
        }
    }

    private void resolvePermission(SelectorField target, SelectorField permission, SelectorField scope, MessageWidget result) {
        SubjectRef subject = selectedSubject(target);
        if (subject == null || permission.value().isBlank()) {
            result.setText("Choose Access", "Select A User Or Group And A Permission");
            return;
        }
        long sequence = ++previewSequence;
        String requestId = Long.toString(sequence);
        result.setText("Checking", "Resolving Direct And Inherited Permissions");
        PreviewRequest request = new PreviewRequest(requestId, subject, permission.value(), selectedScope(scope), ChangeSet.empty());
        complete(client.preview(request), preview -> {
            if (sequence != previewSequence || !requestId.equals(preview.requestId())) {
                return;
            }
            applyPreview(result, preview);
        }, "Could Not Check Permission");
    }

    private void applyPreview(MessageWidget result, EffectivePreview preview) {
        for (int i = 0; i < previewMatches.size(); i++) checkView.setRowVisibility("match:" + i, false);
        if (!preview.resolved()) {
            result.setText("Not Set", "No Direct Or Inherited Permission Matched");
            result.setHiddenText("No Match");
            return;
        }
        LuckPermsManagementContract.PreviewMatch winning = preview.matches().stream().filter(LuckPermsManagementContract.PreviewMatch::effective).findFirst().orElse(null);
        String source = winning == null ? "Direct" : winning.source().id();
        String path = winning == null || winning.inheritancePath().isEmpty() ? "Direct" : String.join(" › ", winning.inheritancePath());
        result.setText(preview.allowed() ? "Allowed" : "Denied", source + " • " + path + " • " + preview.matches().size() + " Matches");
        result.setHiddenText(preview.allowed() ? "Allow" : "Deny");
        result.setAccent(preview.allowed() ? ThemeManager.getAccent("nice") : ThemeManager.getAccent("danger"));
        for (int i = 0; i < Math.min(preview.matches().size(), previewMatches.size()); i++) {
            LuckPermsManagementContract.PreviewMatch match = preview.matches().get(i);
            String matchPath = match.inheritancePath().isEmpty() ? "Direct" : String.join(" › ", match.inheritancePath());
            MessageWidget row = previewMatches.get(i);
            row.setText(match.source().id(), (match.node().value() ? "Allow" : "Deny") + " • " + contexts(match.node()) + " • " + matchPath);
            row.setHiddenText(match.effective() ? "Winning Match" : "Match");
            row.setAccent(match.effective() ? ThemeManager.getAccent(match.node().value() ? "nice" : "danger") : ThemeManager.getDefaultAccent());
            if (match.source().type() == SubjectType.USER) {
                row.setIcon((Identifier) null);
                requestUserFace(match.source().id(), match.source().id(), row::setGeneratedIcon);
            } else {
                row.setIcon(Identifier.icon("manager.png"));
            }
            checkView.setRowVisibility("match:" + i, true);
        }
        updateWorkspace();
    }

    private void addForActive() {
        if (activeSubject != null) {
            activeSubject.showPermissionComposer();
            return;
        }
        if (activeTrack != null) {
            activeTrack.showGroupComposer();
            return;
        }
        showGlobalPermissionComposer();
    }

    private void showCreateMenu() {
        PopupWidget[] reference = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Create Permission Entry").width(320).setResizable(false);
        builder.addRow(new PopupWidget.PopupRow.Builder("", createChoice("Group", "Create A Permission Group", "manager.png", () -> openCreate(reference[0], EntityType.GROUP))).minHeight(30).build());
        builder.addRow(new PopupWidget.PopupRow.Builder("", createChoice("User", "Add A Player To Permission Management", "add.png", () -> openCreate(reference[0], EntityType.USER))).minHeight(30).build());
        builder.addRow(new PopupWidget.PopupRow.Builder("", createChoice("Track", "Create An Ordered Promotion Path", "graph.png", () -> openCreate(reference[0], EntityType.TRACK))).minHeight(30).build());
        reference[0] = builder.build();
        addDrawableChild(reference[0]);
        reference[0].show();
    }

    private MountableButtonWidget createChoice(String title, String description, String icon, Runnable action) {
        MountableButtonWidget button = new MountableButtonWidget.Builder(title).description(description).iconPath(icon).onClick(action).build();
        button.setHeight(30);
        button.entranceAnimationEnabled = false;
        button.setAnimateLayout(false);
        return button;
    }

    private void openCreate(PopupWidget popup, EntityType type) {
        if (popup != null) popup.hide();
        createEntity(type);
    }

    private void createEntity(EntityType type) {
        activeSubject = null;
        activeTrack = null;
        Setting create = addWorkspace(new Setting.Builder("New " + entityName(type)).build());
        TextInputWidget name = input(type == EntityType.USER ? "Player Name" : "Name", "", 250);
        addRow(create, "help", 30, infoRow("New " + entityName(type), type == EntityType.USER ? "Enter The Player's Minecraft Name" : "Use A Short Unique Name", "Saves Immediately"));
        addRow(create, "name", 32, titled(type == EntityType.USER ? "Player Name" : "Name", "", name));
        addRow(create, "create", 30, actionButton("Create " + entityName(type), () -> {
            String id = name.getText().trim();
            if (id.isBlank()) {
                setStatus("Name Required", "Enter A Name", "danger");
                return;
            }
            ChangeSet change = new ChangeSet(UUID.randomUUID().toString(), List.of(), List.of(), List.of(new EntityCreate(type, id, type == EntityType.USER ? id : "")), List.of());
            persist(change, result -> {
                if (!sourceSaved(result)) {
                    return;
                }
                setStatus(entityName(type) + " Created", id, "nice");
                switch (type) {
                    case USER -> loadUsers(true);
                    case GROUP -> loadGroups(true);
                    case TRACK -> loadTracks();
                }
            });
        }));
        show(create);
    }

    private void deleteEntity(EntityType type, String id, Setting view) {
        ChangeSet change = new ChangeSet(UUID.randomUUID().toString(), List.of(), List.of(), List.of(), List.of(new EntityDelete(type, id)));
        persist(change, result -> {
            if (!sourceSaved(result)) {
                return;
            }
            view.visible = false;
            switch (type) {
                case USER -> {
                    TreeEntryWidget entry = userEntries.get(id);
                    if (entry != null) entry.visible = false;
                    usersTree.setRowVisibility("user:" + id, false);
                    subjectViews.remove(new SubjectRef(SubjectType.USER, id));
                }
                case GROUP -> {
                    TreeEntryWidget entry = groupEntries.get(id);
                    if (entry != null) entry.visible = false;
                    groupsTree.setRowVisibility("group:" + id, false);
                    subjectViews.remove(new SubjectRef(SubjectType.GROUP, id));
                    groupGraphDetails.remove(id);
                    refreshInheritanceGraph();
                }
                case TRACK -> {
                    TreeEntryWidget entry = trackEntries.get(id);
                    if (entry != null) entry.visible = false;
                    tracksTree.setRowVisibility("track:" + id, false);
                    trackViews.remove(id);
                }
            }
            setStatus(entityName(type) + " Removed", id, "nice");
            showOverview();
        });
    }

    private void loadGroupDetails() {
        for (GroupSummary group : groups) {
            SubjectRef subject = new SubjectRef(SubjectType.GROUP, group.name());
            if (!subjectViews.containsKey(subject) && !groupGraphDetails.containsKey(group.name())) {
                client.subject(subject).whenComplete((detail, error) -> ui(() -> {
                    if (error == null && !subjectViews.containsKey(subject)) {
                        groupGraphDetails.put(group.name(), detail);
                        refreshInheritanceGraph();
                    }
                }));
            }
        }
    }

    private void refreshInheritanceGraph() {
        if (inheritanceGraph == null) {
            return;
        }
        List<LuckPermsInheritanceGraphWidget.GroupNode> nodes = groups.stream().map(group -> {
            SubjectView view = subjectViews.get(new SubjectRef(SubjectType.GROUP, group.name()));
            SubjectDetail detail = view == null ? groupGraphDetails.get(group.name()) : view.desired;
            Set<String> parents = detail == null ? Set.of() : detail.directNodes().stream().filter(node -> node.kind() == NodeKind.INHERITANCE).map(NodeData::key).collect(Collectors.toCollection(LinkedHashSet::new));
            return new LuckPermsInheritanceGraphWidget.GroupNode(group.name(), displayGroup(group), group.weight(), parents);
        }).toList();
        inheritanceGraph.apply(nodes);
        inheritanceGraph.setWidth(contentWidth());
        updateWorkspace();
    }

    private void openGroupFromGraph(String group) {
        openSubject(new SubjectRef(SubjectType.GROUP, group));
    }

    private void connectGroups(String child, String parent) {
        if (child.equals(parent)) {
            setStatus("Choose Another Group", "A Group Cannot Inherit Itself", "danger");
            return;
        }
        SubjectRef subject = new SubjectRef(SubjectType.GROUP, child);
        SubjectView view = subjectViews.get(subject);
        if (view == null) {
            complete(client.subject(subject), detail -> {
                SubjectView loadedView = new SubjectView(detail);
                subjectViews.put(subject, loadedView);
                connectGroups(child, parent);
            }, "Could Not Load Group");
            return;
        }
        boolean exists = view.desired.directNodes().stream().anyMatch(node -> node.kind() == NodeKind.INHERITANCE && node.key().equalsIgnoreCase(parent));
        if (exists) {
            setStatus("Already Connected", child + " Inherits " + parent, "calm");
            return;
        }
        boolean hasParent = view.desired.directNodes().stream().anyMatch(node -> node.kind() == NodeKind.INHERITANCE);
        if (hasParent) {
            setStatus("One Parent Per Group", "Remove The Current Parent Before Connecting Another", "danger");
            return;
        }
        if (inheritsFrom(parent, child, new LinkedHashSet<>())) {
            setStatus("Connection Would Create A Cycle", parent + " Already Inherits From " + child, "danger");
            return;
        }
        view.mutate(detail -> withNodes(detail, append(detail.directNodes(), new NodeData(UUID.randomUUID().toString(), NodeKind.INHERITANCE, parent, true, null, Map.of(), null))));
    }

    private boolean inheritsFrom(String group, String target, Set<String> visited) {
        if (group.equalsIgnoreCase(target)) return true;
        if (!visited.add(group.toLowerCase(Locale.ROOT))) return false;
        SubjectView view = subjectViews.get(new SubjectRef(SubjectType.GROUP, group));
        SubjectDetail detail = view == null ? groupGraphDetails.get(group) : view.desired;
        if (detail == null) return false;
        return detail.directNodes().stream().filter(node -> node.kind() == NodeKind.INHERITANCE)
            .anyMatch(node -> inheritsFrom(node.key(), target, visited));
    }

    private void disconnectGroups(String child, String parent) {
        SubjectRef subject = new SubjectRef(SubjectType.GROUP, child);
        SubjectView view = subjectViews.get(subject);
        if (view == null) {
            complete(client.subject(subject), detail -> {
                SubjectView loadedView = new SubjectView(detail);
                subjectViews.put(subject, loadedView);
                disconnectGroups(child, parent);
            }, "Could Not Load Group");
            return;
        }
        view.mutate(detail -> withNodes(detail, detail.directNodes().stream()
            .filter(node -> node.kind() != NodeKind.INHERITANCE || !node.key().equalsIgnoreCase(parent)).toList()));
    }

    private void refreshNetwork() {
        networkRefreshAttempts = 0;
        complete(networkClient.snapshot(), this::applyNetwork, "Could Not Load Permission Network");
    }

    private void applyNetwork(Snapshot snapshot) {
        networkSnapshot = snapshot;
        boolean networked = !snapshot.networkId().isBlank();
        if (networked && !networkTreeAttached) {
            workTree.addWidget(networkTree);
            networkTreeAttached = true;
        } else if (!networked && networkTreeAttached) {
            workTree.container().removeWidget(networkTree);
            networkTreeAttached = false;
        }
        workTree.container().updateWidgetPositions();
        clampTreeScroll();
        if (!networked) {
            networkView.visible = false;
            if (activeSections.contains(networkView)) showOverview();
            return;
        }
        if (networkGraph == null) {
            networkGraph = new LuckPermsNetworkGraphWidget(0, 0, contentWidth(), snapshot,
                configuration -> complete(networkClient.configure(configuration), this::applyNetwork, "Could Not Update Permission Delivery"), serverIcons);
            addRow(networkView, "graph", Math.max(180, networkGraph.getHeight()), networkGraph);
        } else {
            networkGraph.applySnapshot(snapshot);
            networkGraph.setWidth(contentWidth());
        }
        long receiving = snapshot.targets().stream().filter(ReSyncLuckPermsNetworkClient.Target::selected).count();
        long ready = snapshot.targets().stream().filter(ReSyncLuckPermsNetworkClient.Target::available).count();
        networkStatus.setText("Permission Delivery", receiving + " Receiving • " + ready + " Ready");
        networkEntry.setEntry("Permission Delivery", snapshot.networkName(), receiving + " Servers");
        scheduleNetworkRefresh(snapshot);
        updateWorkspace();
    }

    private void scheduleNetworkRefresh(Snapshot snapshot) {
        boolean settling = snapshot.targets().stream().anyMatch(target -> target.detail().contains("Connecting") || target.connected() && !target.available());
        if (!settling || networkRefreshScheduled || networkRefreshAttempts >= 20) {
            return;
        }
        networkRefreshScheduled = true;
        networkRefreshAttempts++;
        AsyncTools.schedule(TaskSchedulers.current(), Duration.ofMillis(500), () -> networkClient.snapshot().whenComplete((updated, error) -> ui(() -> {
            networkRefreshScheduled = false;
            if (error == null) {
                applyNetwork(updated);
            }
        })));
    }

    private void persist(ChangeSet change, Consumer<DistributionResult> applied) {
        persist(change, applied, () -> {});
    }

    private void persist(ChangeSet change, Consumer<DistributionResult> applied, Runnable failed) {
        if (!available) {
            setStatus("LuckPerms Unavailable", "Reconnect Before Changing Permissions", "danger");
            failed.run();
            return;
        }
        setStatus("Saving", "Applying Permission Change", "calm");
        complete(networkClient.save(change), result -> {
            applied.accept(result);
            TargetResult failedTarget = result.targets().stream().filter(target -> !target.success()).findFirst().orElse(null);
            if (failedTarget != null) {
                setStatus(sourceSaved(result) ? "Saved Locally" : "Permission Change Failed", failedTarget.name() + " • " + failedTarget.message(), sourceSaved(result) ? "calm" : "danger");
            }
        }, "Could Not Save Permission Change", failed);
    }

    private boolean sourceSaved(DistributionResult result) {
        return result.targets().stream().anyMatch(target -> target.instanceId().equals(client.serverId()) && target.success());
    }

    private long sourceRevision(DistributionResult result, EntityType type, String id, long fallback) {
        return result.targets().stream().filter(target -> target.instanceId().equals(client.serverId()) && target.success() && target.result() != null)
            .filter(target -> target.result().entities().stream().anyMatch(entity -> entity.type() == type && entity.id().equalsIgnoreCase(id) && entity.success()))
            .mapToLong(target -> target.result().revision()).max().orElse(fallback + 1);
    }

    private void refreshInvalidated(LuckPermsManagementContract.Invalidation invalidation) {
        if (invalidation.scopes().contains(EntityType.USER)) loadUsers(true);
        if (invalidation.scopes().contains(EntityType.GROUP)) {
            if (invalidation.ids().isEmpty()) groupGraphDetails.clear();
            else invalidation.ids().forEach(groupGraphDetails::remove);
            loadGroups(true);
            if (inheritanceView.visible) loadGroupDetails();
        }
        if (invalidation.scopes().contains(EntityType.TRACK)) loadTracks();
        subjectViews.forEach((subject, view) -> {
            EntityType type = subject.type() == SubjectType.USER ? EntityType.USER : EntityType.GROUP;
            if (invalidation.scopes().contains(type) && (invalidation.ids().isEmpty() || invalidation.ids().contains(subject.id())) && !view.saving) {
                complete(client.subject(subject), view::apply, "Could Not Refresh " + subject.id());
            }
        });
        refreshOverview();
    }

    private void filterTree() {
        for (UserSummary user : users) {
            SubjectView view = subjectViews.get(new SubjectRef(SubjectType.USER, user.uniqueId()));
            boolean visible = matches(treeFilter, user.username(), user.uniqueId(), user.primaryGroup(), user.prefix(), user.suffix(), view == null ? "" : view.searchText());
            TreeEntryWidget entry = userEntries.get(user.uniqueId());
            if (entry != null) entry.visible = visible;
            usersTree.setRowVisibility("user:" + user.uniqueId(), visible);
        }
        for (GroupSummary group : groups) {
            SubjectView view = subjectViews.get(new SubjectRef(SubjectType.GROUP, group.name()));
            boolean visible = matches(treeFilter, group.name(), group.displayName(), view == null ? "" : view.searchText());
            TreeEntryWidget entry = groupEntries.get(group.name());
            if (entry != null) entry.visible = visible;
            groupsTree.setRowVisibility("group:" + group.name(), visible);
        }
        for (TrackDetail track : tracks) {
            boolean visible = matches(treeFilter, track.name(), String.join(" ", track.groups()));
            TreeEntryWidget entry = trackEntries.get(track.name());
            if (entry != null) entry.visible = visible;
            tracksTree.setRowVisibility("track:" + track.name(), visible);
        }
        workTree.container().updateWidgetPositions();
        clampTreeScroll();
    }

    private void search(String query) {
        filter = normalize(query);
        if (activeSubject != null) {
            activeSubject.filter(filter);
        } else if (activeTrack != null) {
            activeTrack.filter(filter);
        } else {
            filterTree();
        }
    }

    private void clampTreeScroll() {
        Container tree = workTree.container();
        int visibleHeight = tree.getContentHeight() - tree.getPadding() * 2;
        float maximum = Math.max(0, tree.calculateTotalHeight() - visibleHeight);
        if (tree.getScrollOffset() > maximum) tree.setScrollOffset(maximum);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (workTree != null) {
            workTree.y(HEADER_HEIGHT).height(Math.max(100, height - HEADER_HEIGHT - 5));
            workTree.updateContainerBounds();
        }
        layoutWorkspaceBounds();
    }

    @Override
    protected void onSidePanelWidthChanged() {
        super.onSidePanelWidthChanged();
        layoutWorkspaceBounds();
    }

    private void layoutWorkspaceBounds() {
        if (workspace != null) {
            int left = treeWidth();
            workspace.setPosition(left, HEADER_HEIGHT);
            workspace.setSize(Math.max(220, width - left - 5), Math.max(100, height - HEADER_HEIGHT - 5));
            if (treeSearch != null) treeSearch.setWidth(Math.max(80, workTree.getConfiguredWidth() - 10));
            workspaces.forEach(setting -> setting.getRows().forEach(row -> row.getWidgets().forEach(widget -> {
                if (widget instanceof MessageWidget message) message.setWidth(contentWidth());
            })));
            if (inheritanceGraph != null) {
                inheritanceGraph.setWidth(contentWidth());
                inheritanceGraph.setMinimumHeight(Math.max(280, workspace.getHeight() - 80));
            }
            if (networkGraph != null) networkGraph.setWidth(contentWidth());
            updateWorkspace();
        }
    }

    private int treeWidth() {
        return workTree == null ? TREE_WIDTH + 8 : workTree.getConfiguredWidth() + 8;
    }

    private int contentWidth() {
        return workspace == null ? Math.max(220, width - TREE_WIDTH - 22) : Math.max(220, workspace.getEffectiveWidth() - 14);
    }

    private void updateWorkspace() {
        if (workspace != null) workspace.updateWidgetPositions();
        if (workTree != null) workTree.container().updateWidgetPositions();
    }

    private void setStatus(String title, String detail, String accent) {
        if (status != null) {
            status.setText(title, detail);
            status.setAccent(ThemeManager.getAccent(accent));
        }
    }

    private void addTreeRow(Setting setting, String id, AnimatedWidget widget) {
        setting.addRow(new PopupWidget.PopupRow.Builder("", widget).id(id).minHeight(28).build());
    }

    private void setTreeState(Setting setting, String id, String title, String detail) {
        setting.getRows().stream().filter(row -> row.id.equals(id)).findFirst().flatMap(row -> row.getWidgets().stream().findFirst())
            .ifPresent(widget -> {
                if (widget instanceof MessageWidget message) message.setText(title, detail);
            });
    }

    private void addRow(Setting setting, String id, int height, AnimatedWidget... widgets) {
        setting.addRow(new PopupWidget.PopupRow.Builder("", widgets).id(id).minHeight(height).build());
    }

    private void addBottomRow(Setting setting, String id, int height, AnimatedWidget... widgets) {
        setting.addRow(new PopupWidget.PopupRow.Builder("", widgets).id(id).minHeight(height).alignBottom().build());
    }

    private static void addOrderedRow(List<PopupWidget.PopupRow> ordered, Map<String, PopupWidget.PopupRow> rows, String id) {
        PopupWidget.PopupRow row = rows.get(id);
        if (row != null) ordered.add(row);
    }

    private MessageWidget infoRow(String title, String description, String detail) {
        MessageWidget row = new MessageWidget(contentWidth(), title, description);
        row.setHiddenText(detail);
        return row;
    }

    private MessageWidget inactiveRow(String title, String description) {
        MessageWidget row = infoRow(title, description, "");
        row.setActive(false);
        row.selectable = false;
        return row;
    }

    private IconButton categoryRow(String title) {
        IconButton row = new IconButton.Builder().label(title).centered(true).active(false).animateElevation(false).size(contentWidth(), 18).build();
        row.entranceAnimationEnabled = false;
        row.selectable = false;
        return row;
    }

    private TitledRowWidget titled(String title, String description, AnimatedWidget... widgets) {
        return new TitledRowWidget.Builder().title(title).description(description).gap(2).addWidget(widgets).size(contentWidth(), 32).roundedCorners(false).build();
    }

    private TitledRowWidget paired(String firstTitle, String firstDescription, AnimatedWidget first, String secondTitle, String secondDescription, AnimatedWidget second, float firstShare) {
        return new TitledRowWidget.Builder().gap(2).fieldSpacing(2).minFieldWidth(90).percentageSplit(firstShare).addField(firstTitle, firstDescription, first)
            .addField(secondTitle, secondDescription, new TitledRowWidget.FieldOptions().alignRight(), second).size(contentWidth(), 34).roundedCorners(false).build();
    }

    private TitledRowWidget permissionValuePair(SelectorField permission, ToggleWidget value) {
        return new TitledRowWidget.Builder().gap(2).fieldSpacing(2).minFieldWidth(90).fixedSecondFieldWidth(value.getWidth())
            .addField("Permission", "Choose The Access To Add", permission)
            .addField("Value", "Allow Grants. Deny Blocks.", new TitledRowWidget.FieldOptions().alignRight(), value)
            .size(contentWidth(), 34).roundedCorners(false).build();
    }

    private AnimatedButton actionButton(String title, Runnable action) {
        return new AnimatedButton.Builder().label(title).onClick(action).centered(false).size(150, 18).roundedCorners(false).build();
    }

    private AnimatedButton destructiveButton(String title, Runnable action) {
        AnimatedButton[] reference = new AnimatedButton[1];
        boolean[] armed = {false};
        int[] generation = {0};
        reference[0] = new AnimatedButton.Builder().label(title).onClick(() -> {
            if (armed[0]) {
                armed[0] = false;
                generation[0]++;
                action.run();
                return;
            }
            armed[0] = true;
            int confirmation = ++generation[0];
            reference[0].setMessage("Click To Confirm");
            reference[0].setAccent(ThemeManager.getAccent("danger"));
            AsyncTools.schedule(TaskSchedulers.current(), Duration.ofSeconds(2), () -> ui(() -> {
                if (!armed[0] || confirmation != generation[0]) return;
                armed[0] = false;
                reference[0].setMessage(title);
                reference[0].setAccent(ThemeManager.getDefaultAccent());
            }));
        }).centered(false).size(150, 18).roundedCorners(false).build();
        return reference[0];
    }

    private ToggleWidget permissionToggle(boolean allowed) {
        ToggleWidget toggle = new ToggleWidget.Builder().label(allowed ? "Allow" : "Deny").toggled(allowed).size(64, 18).build();
        toggle.setOnChange(() -> toggle.setMessage(toggle.getValue() ? "Allow" : "Deny"));
        return toggle;
    }

    private ToggleWidget enabledToggle(boolean enabled) {
        ToggleWidget toggle = new ToggleWidget.Builder().label(enabled ? "Enabled" : "Disabled").toggled(enabled).size(72, 18).build();
        toggle.setOnChange(() -> toggle.setMessage(toggle.getValue() ? "Enabled" : "Disabled"));
        return toggle;
    }

    private SquareButtonWidget rowAction(String icon, String hint, Runnable action) {
        return new SquareButtonWidget.Builder().imagePath(icon).hint(hint).hintDelay(0.15f).onClick(action).accentType(ThemeManager.getDefaultAccent()).animateElevation(false).size(18, 18).build();
    }

    private SquareButtonWidget destructiveRowAction(String icon, String hint, Runnable action) {
        SquareButtonWidget[] reference = new SquareButtonWidget[1];
        boolean[] armed = {false};
        int[] generation = {0};
        reference[0] = new SquareButtonWidget.Builder().imagePath(icon).hint(hint).hintDelay(0.15f).onClick(() -> {
            if (armed[0]) {
                armed[0] = false;
                generation[0]++;
                action.run();
                return;
            }
            armed[0] = true;
            int confirmation = ++generation[0];
            reference[0].setHint("Click To Confirm");
            reference[0].setAccent(ThemeManager.getAccent("danger"));
            AsyncTools.schedule(TaskSchedulers.current(), Duration.ofSeconds(2), () -> ui(() -> {
                if (!armed[0] || confirmation != generation[0]) return;
                armed[0] = false;
                reference[0].setHint(hint);
                reference[0].setAccent(ThemeManager.getDefaultAccent());
            }));
        }).accentType(ThemeManager.getDefaultAccent()).animateElevation(false).size(18, 18).build();
        return reference[0];
    }

    private SelectorField catalogField(String placeholder, String source, Supplier<List<String>> fallback, String selected) {
        return new SelectorField(placeholder, selected, () -> catalogChoices(source, fallback.get()), source);
    }

    private SelectorField choiceField(String placeholder, Supplier<List<SelectorChoice>> choices, String selected) {
        return new SelectorField(placeholder, selected, choices, "");
    }

    private List<SelectorChoice> catalogChoices(String source, List<String> fallback) {
        List<OptionCatalogItem> items = OptionCatalogLoader.snapshot(client.serverId(), source, Map.of()).items();
        if (items.isEmpty()) return fallback.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).map(value -> new SelectorChoice(value, value, "", "", value)).toList();
        return items.stream().map(item -> new SelectorChoice(item.getValue(), item.getLabel(), item.getDescription(), item.getIcon(),
            item.getValue() + " " + item.getLabel() + " " + item.getDescription() + " " + item.getGroup())).toList();
    }

    private List<String> permissionOptions() {
        LinkedHashSet<String> values = subjectViews.values().stream().flatMap(view -> view.desired.directNodes().stream()).filter(node -> node.kind() == NodeKind.PERMISSION)
            .map(NodeData::key).filter(value -> !value.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<SelectorChoice> subjectChoices() {
        List<SelectorChoice> values = new ArrayList<>();
        groups.stream().sorted(Comparator.comparing(GroupSummary::name, String.CASE_INSENSITIVE_ORDER)).forEach(group -> values.add(new SelectorChoice("group:" + group.name(), displayGroup(group), "Group • " + group.name(), "manager.png", group.name() + " " + group.displayName())));
        users.stream().sorted(Comparator.comparing(LuckPermsDashboardScreen::displayUser, String.CASE_INSENSITIVE_ORDER)).forEach(user -> values.add(new SelectorChoice("user:" + user.uniqueId(), displayUser(user), "User • " + user.primaryGroup(), "", user.username() + " " + user.uniqueId())));
        return values;
    }

    private void requestUserFace(String uniqueId, String username, Consumer<Identifier> consumer) {
        if (consumer == null) return;
        String playerId = uniqueId == null ? "" : uniqueId.trim();
        String playerName = username == null ? "" : username.trim();
        if (!playerId.isBlank() && (playerName.equalsIgnoreCase(playerId) || playerName.equalsIgnoreCase(shortId(playerId)))) playerName = "";
        String source = PlayerAvatarUrl.resolve(playerName, playerId, 32);
        if (source.isBlank()) return;
        Identifier face = ScreenManager.getInstance().imageAssets().registerRemoteImage(source);
        if (face != null) consumer.accept(face);
    }

    private List<SelectorChoice> groupChoices() {
        return groups.stream().sorted(Comparator.comparing(GroupSummary::name, String.CASE_INSENSITIVE_ORDER))
            .map(group -> new SelectorChoice(group.name(), displayGroup(group), group.name(), "manager.png", group.name() + " " + group.displayName())).toList();
    }

    private List<SelectorChoice> scopeChoices() {
        List<SelectorChoice> choices = new ArrayList<>();
        choices.add(new SelectorChoice("everywhere", "Everywhere", "All Servers And Worlds", "world.png", "global all"));
        OptionCatalogLoader.snapshot(client.serverId(), "server:resync:network_node", Map.of()).items().forEach(item -> choices.add(new SelectorChoice("server:" + item.getValue(), item.getLabel(), "Server", item.getIcon(), item.getValue() + " " + item.getLabel())));
        OptionCatalogLoader.snapshot(client.serverId(), "server:minecraft:world", Map.of()).items().forEach(item -> choices.add(new SelectorChoice("world:" + item.getValue(), item.getLabel(), "World", item.getIcon(), item.getValue() + " " + item.getLabel())));
        return choices;
    }

    private SubjectRef selectedSubject(SelectorField field) {
        if (field.value().startsWith("group:")) return new SubjectRef(SubjectType.GROUP, field.value().substring(6));
        if (field.value().startsWith("user:")) return new SubjectRef(SubjectType.USER, field.value().substring(5));
        return null;
    }

    private static Map<String, List<String>> selectedScope(SelectorField field) {
        if (field.value().startsWith("server:")) return Map.of("server", List.of(field.value().substring(7)));
        if (field.value().startsWith("world:")) return Map.of("world", List.of(field.value().substring(6)));
        return Map.of();
    }

    private static TextInputWidget input(String placeholder, String value, int width) {
        return new TextInputWidget.Builder().placeholder(placeholder).text(value == null ? "" : value).size(width, 18).build();
    }

    private int findUser(String id) {
        for (int i = 0; i < users.size(); i++) if (users.get(i).uniqueId().equals(id)) return i;
        return -1;
    }

    private int findGroup(String name) {
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).name().equals(name)) return i;
        return -1;
    }

    private static SubjectDetail withRevision(SubjectDetail detail, long revision) {
        return new SubjectDetail(revision, detail.subject(), detail.name(), detail.primaryGroup(), detail.weight(), detail.directNodes());
    }

    private static SubjectDetail withNodes(SubjectDetail detail, List<NodeData> nodes) {
        return new SubjectDetail(detail.revision(), detail.subject(), detail.name(), detail.primaryGroup(), detail.weight(), nodes);
    }

    private static List<NodeData> append(List<NodeData> nodes, NodeData node) {
        List<NodeData> updated = new ArrayList<>(nodes);
        updated.add(node);
        return List.copyOf(updated);
    }

    private static String displayUser(UserSummary user) {
        return user.username().isBlank() ? shortId(user.uniqueId()) : user.username();
    }

    private static String displayGroup(GroupSummary group) {
        return group.displayName().isBlank() ? group.name() : group.displayName();
    }

    private static String entityName(EntityType type) {
        return switch (type) {
            case USER -> "User";
            case GROUP -> "Group";
            case TRACK -> "Track";
        };
    }

    private static String shortId(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String query, String... values) {
        if (query.isBlank()) return true;
        for (String value : values) if (value != null && value.toLowerCase(Locale.ROOT).contains(query)) return true;
        return false;
    }

    private static String contexts(NodeData node) {
        List<String> values = new ArrayList<>();
        node.contexts().forEach((key, entries) -> entries.forEach(value -> values.add(key + ": " + value)));
        if (node.expiresAt() != null) values.add("Expires " + TIME.format(Instant.ofEpochSecond(node.expiresAt())));
        return values.isEmpty() ? "Everywhere" : String.join(" • ", values);
    }

    private static String permissionCategory(String permission) {
        String value = permission == null ? "" : permission.trim();
        int separator = value.indexOf('.');
        String namespace = (separator > 0 ? value.substring(0, separator) : "Other").replace('_', ' ');
        return switch (namespace.toLowerCase(Locale.ROOT)) {
            case "bukkit" -> "Bukkit";
            case "minecraft" -> "Minecraft";
            case "resync" -> "ReSync";
            case "luckperms", "lp" -> "LuckPerms";
            case "other" -> "Other";
            default -> Character.toUpperCase(namespace.charAt(0)) + namespace.substring(1);
        };
    }

    private static String metadataTitle(NodeKind kind) {
        return switch (kind) {
            case PREFIX -> "Prefix";
            case SUFFIX -> "Suffix";
            case META -> "Custom Metadata";
            case DISPLAY_NAME -> "Display Name";
            case WEIGHT -> "Weight";
            case UNKNOWN -> "LuckPerms Data";
            case PERMISSION -> "Permission";
            case INHERITANCE -> "Inheritance";
        };
    }

    private static String metadataDescription(NodeData node) {
        List<String> details = new ArrayList<>();
        if (node.priority() != null) details.add("Priority " + node.priority());
        if (!node.contexts().isEmpty() || node.expiresAt() != null) details.add(contexts(node));
        return details.isEmpty() ? "Saved By LuckPerms" : String.join(" • ", details);
    }

    private static String metadataName(NodeData node) {
        if (node.kind() != NodeKind.META) return metadataTitle(node.kind());
        String[] parts = node.key().split("\\.", 3);
        return parts.length > 1 && !parts[1].isBlank() ? "Metadata • " + parts[1] : "Custom Metadata";
    }

    private static String metadataValue(NodeData node) {
        String[] parts = node.key().split("\\.", 3);
        return switch (node.kind()) {
            case PREFIX, SUFFIX, META -> parts.length == 3 ? parts[2] : node.key();
            case DISPLAY_NAME, WEIGHT -> parts.length >= 2 ? node.key().substring(node.key().indexOf('.') + 1) : node.key();
            default -> node.key();
        };
    }

    private static String metadataKey(NodeData node, String value) {
        String[] parts = node.key().split("\\.", 3);
        return switch (node.kind()) {
            case PREFIX -> "prefix." + (node.priority() == null ? parts.length > 1 ? parts[1] : "0" : node.priority()) + "." + value;
            case SUFFIX -> "suffix." + (node.priority() == null ? parts.length > 1 ? parts[1] : "0" : node.priority()) + "." + value;
            case META -> "meta." + (parts.length > 1 ? parts[1] : "value") + "." + value;
            case DISPLAY_NAME -> "displayname." + value;
            case WEIGHT -> "weight." + value;
            default -> value;
        };
    }

    private String styledSubject(SubjectDetail detail) {
        NodeData prefix = effectiveMetadata(detail, NodeKind.PREFIX, new LinkedHashSet<>());
        NodeData suffix = effectiveMetadata(detail, NodeKind.SUFFIX, new LinkedHashSet<>());
        boolean inheritanceLoaded = metadataInheritanceLoaded(detail, new LinkedHashSet<>());
        String prefixValue = prefix == null && !inheritanceLoaded ? fallbackMetadata(detail, true) : prefix == null ? "" : metadataValue(prefix);
        String suffixValue = suffix == null && !inheritanceLoaded ? fallbackMetadata(detail, false) : suffix == null ? "" : metadataValue(suffix);
        if (prefixValue.isBlank() && suffixValue.isBlank()) return "";
        String name = detail.name().isBlank() ? detail.subject().id() : detail.name();
        return richIdentity(prefixValue, name, suffixValue);
    }

    private NodeData effectiveMetadata(SubjectDetail detail, NodeKind kind, Set<String> visited) {
        String subjectKey = detail.subject().type() + ":" + detail.subject().id().toLowerCase(Locale.ROOT);
        if (!visited.add(subjectKey)) return null;
        NodeData selected = detail.directNodes().stream().filter(node -> node.kind() == kind)
            .max(Comparator.comparingInt(LuckPermsDashboardScreen::metadataPriority)).orElse(null);
        LinkedHashSet<String> parents = detail.directNodes().stream().filter(node -> node.kind() == NodeKind.INHERITANCE)
            .map(NodeData::key).filter(parent -> !parent.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (detail.subject().type() == SubjectType.USER && !detail.primaryGroup().isBlank()) parents.add(detail.primaryGroup());
        for (String parent : parents) {
            SubjectDetail parentDetail = localGroupDetail(parent);
            if (parentDetail == null) continue;
            NodeData inherited = effectiveMetadata(parentDetail, kind, visited);
            if (inherited != null && (selected == null || metadataPriority(inherited) > metadataPriority(selected))) selected = inherited;
        }
        return selected;
    }

    private boolean metadataInheritanceLoaded(SubjectDetail detail, Set<String> visited) {
        String subjectKey = detail.subject().type() + ":" + detail.subject().id().toLowerCase(Locale.ROOT);
        if (!visited.add(subjectKey)) return true;
        LinkedHashSet<String> parents = detail.directNodes().stream().filter(node -> node.kind() == NodeKind.INHERITANCE)
            .map(NodeData::key).filter(parent -> !parent.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (detail.subject().type() == SubjectType.USER && !detail.primaryGroup().isBlank()) parents.add(detail.primaryGroup());
        for (String parent : parents) {
            SubjectDetail parentDetail = localGroupDetail(parent);
            if (parentDetail == null || !metadataInheritanceLoaded(parentDetail, visited)) return false;
        }
        return true;
    }

    private SubjectDetail localGroupDetail(String group) {
        SubjectView view = subjectViews.get(new SubjectRef(SubjectType.GROUP, group));
        if (view != null) return view.desired;
        SubjectDetail exact = groupGraphDetails.get(group);
        if (exact != null) return exact;
        return groupGraphDetails.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(group)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private String fallbackMetadata(SubjectDetail detail, boolean prefix) {
        if (detail.subject().type() != SubjectType.USER) return "";
        return users.stream().filter(user -> user.uniqueId().equals(detail.subject().id())).findFirst()
            .map(user -> prefix ? user.prefix() : user.suffix()).orElse("");
    }

    private static int metadataPriority(NodeData node) {
        return node.priority() == null ? 0 : node.priority();
    }

    private static String richIdentity(String prefix, String name, String suffix) {
        return RICH_RESET + prefix + name + suffix + RICH_RESET;
    }

    private void updateUserEntry(UserSummary user, TreeEntryWidget entry) {
        SubjectView view = subjectViews.get(new SubjectRef(SubjectType.USER, user.uniqueId()));
        String styled;
        int directNodes;
        if (view != null) {
            styled = styledSubject(view.desired);
            directNodes = view.desired.directNodes().size();
        } else {
            NodeData prefix = inheritedMetadata(user.primaryGroup(), NodeKind.PREFIX);
            NodeData suffix = inheritedMetadata(user.primaryGroup(), NodeKind.SUFFIX);
            String prefixValue = prefix == null ? user.prefix() : metadataValue(prefix);
            String suffixValue = suffix == null ? user.suffix() : metadataValue(suffix);
            styled = prefixValue.isBlank() && suffixValue.isBlank() ? "" : richIdentity(prefixValue, displayUser(user), suffixValue);
            directNodes = user.directNodes();
        }
        String state = RICH_RESET + (user.online() ? "Online" : "Offline") + " • " + user.primaryGroup();
        entry.setEntry(displayUser(user), (styled.isBlank() ? "" : styled + " • ") + state, directNodes + " Direct");
        entry.setRichText(true);
    }

    private NodeData inheritedMetadata(String group, NodeKind kind) {
        SubjectDetail detail = localGroupDetail(group);
        return detail == null ? null : effectiveMetadata(detail, kind, new LinkedHashSet<>());
    }

    private void refreshUserPresentations() {
        users.forEach(user -> {
            TreeEntryWidget entry = userEntries.get(user.uniqueId());
            if (entry != null) updateUserEntry(user, entry);
        });
        subjectViews.forEach((subject, view) -> {
            if (subject.type() == SubjectType.USER) view.updateSummary();
        });
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank() ? "Permission Request Failed" : current.getMessage();
    }

    private <T> void complete(Async<T> future, Consumer<T> success, String failure) {
        complete(future, success, failure, () -> {});
    }

    private <T> void complete(Async<T> future, Consumer<T> success, String failure, Runnable failed) {
        future.whenComplete((value, error) -> ui(() -> {
            if (error != null) {
                failed.run();
                setStatus(failure, message(error), "danger");
            } else {
                success.accept(value);
            }
        }));
    }

    private void ui(Runnable action) {
        ScreenManager.getInstance().execute(() -> {
            if (!disposed) action.run();
        });
    }

    @Override
    public void close() {
        ScreenManager.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        disposed = true;
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        super.removed();
    }

    private record SelectorChoice(String value, String label, String description, String icon, String search) {
        private SelectorChoice {
            value = value == null ? "" : value;
            label = label == null || label.isBlank() ? value : label;
            description = description == null ? "" : description;
            icon = icon == null ? "" : icon;
            search = search == null ? "" : search;
        }
    }

    private final class SelectorField extends AnimatedButton {
        private final String placeholder;
        private final Supplier<List<SelectorChoice>> choices;
        private final String catalogSource;
        private Runnable onValueChange;
        private String value;

        private SelectorField(String placeholder, String value, Supplier<List<SelectorChoice>> choices, String catalogSource) {
            super(0, 0, 220, 18, "");
            this.placeholder = placeholder;
            this.choices = choices;
            this.catalogSource = catalogSource;
            centered = false;
            roundedCorners = false;
            animateElevation = false;
            setAction(this::openSelector);
            setValue(value);
        }

        private String value() {
            return value;
        }

        private void setValue(String value) {
            this.value = value == null ? "" : value;
            String label = choices.get().stream().filter(choice -> choice.value().equals(this.value)).map(SelectorChoice::label).findFirst().orElse(this.value.isBlank() ? placeholder : this.value);
            setMessage(label);
            if (onValueChange != null) onValueChange.run();
        }

        private void openSelector() {
            Screen overlay = ScreenManager.getInstance().getDesktopUiOverlay();
            Screen host = overlay == null ? LuckPermsDashboardScreen.this : overlay;
            ItemSelectorWidget[] reference = new ItemSelectorWidget[1];
            ItemSelectorWidget.Builder builder = new ItemSelectorWidget.Builder(host).size(Math.clamp(Math.max(260, getWidth()), 260, 420), Math.clamp(height - 100, 180, 340))
                .searchPlaceholder("Search").emptyMessage("No Matching Options").dismissOnSelect(true).onClose(() -> host.remove(reference[0]));
            if (catalogSource.isBlank()) {
                choices.get().forEach(choice -> {
                    if (choice.value().startsWith("user:")) {
                        String uniqueId = choice.value().substring(5);
                        MountableButtonWidget entry = new MountableButtonWidget.Builder(choice.label()).description(choice.description()).onClick(() -> {
                            setValue(choice.value());
                            if (reference[0] != null) reference[0].hide();
                        }).build();
                        entry.selectable = true;
                        entry.setSelected(choice.value().equals(value));
                        entry.entranceAnimationEnabled = false;
                        entry.setAnimateLayout(false);
                        requestUserFace(uniqueId, choice.label(), entry::setGeneratedIcon);
                        builder.addCustomEntry(entry, choice.search());
                    } else {
                        builder.addItem(choice.label(), choice.icon(), choice.description(), choice.search(), () -> setValue(choice.value()));
                    }
                });
            } else {
                builder.asyncItems(OptionCatalogSelector.refreshAction(client.serverId(), catalogSource), () -> OptionCatalogSelector.snapshot(client.serverId(), catalogSource, Map.of(),
                    () -> choices.get().stream().map(SelectorChoice::value).toList(), this::value, this::setValue, "No Matching Options"));
            }
            reference[0] = builder.build();
            host.addDrawableChild(reference[0]);
            reference[0].show(getX(), getY() + getHeight());
        }
    }

    private final class SubjectView {
        private final Setting setting;
        private final Setting permissionsSetting;
        private final Setting inheritanceSetting;
        private final Setting metadataSetting;
        private final Setting composerSetting;
        private final MessageWidget summary;
        private final Map<String, PermissionRow> permissionRows = new LinkedHashMap<>();
        private final Map<String, InheritanceRow> inheritanceRows = new LinkedHashMap<>();
        private final Map<String, MetadataRow> metadataRows = new LinkedHashMap<>();
        private final Map<String, IconButton> permissionCategories = new LinkedHashMap<>();
        private final TextInputWidget name;
        private final SelectorField primary;
        private final TextInputWidget weight;
        private SelectorField newPermission;
        private SelectorField newScope;
        private ToggleWidget newValue;
        private SubjectDetail acknowledged;
        private SubjectDetail desired;
        private long generation;
        private boolean saving;
        private boolean applying;
        private boolean composerVisible;

        private SubjectView(SubjectDetail detail) {
            acknowledged = detail;
            desired = detail;
            setting = addWorkspace(new Setting.Builder(detail.subject().type() == SubjectType.USER ? "User" : "Group").build());
            permissionsSetting = addWorkspace(new Setting.Builder("Direct Permissions").build());
            inheritanceSetting = addWorkspace(new Setting.Builder("Inheritance").build());
            metadataSetting = addWorkspace(new Setting.Builder("Metadata").build());
            composerSetting = addWorkspace(new Setting.Builder("Add Permission").build());
            summary = new MessageWidget(contentWidth(), detail.subject().id(), "Loading Permissions");
            summary.setRichText(true);
            addRow(setting, "summary", 30, summary);
            if (detail.subject().type() == SubjectType.USER) requestUserFace(detail.subject().id(), detail.name(), summary::setGeneratedIcon);
            name = input("Display Name", detail.name(), 220);
            primary = choiceField("Choose Main Group", LuckPermsDashboardScreen.this::groupChoices, detail.primaryGroup());
            weight = input("Priority", detail.weight() == null ? "" : detail.weight().toString(), 100);
            name.onChange = this::identityChanged;
            if (detail.subject().type() == SubjectType.USER) {
                name.setActive(false);
                primary.onValueChange = this::identityChanged;
                addRow(setting, "identity", 34, paired("Player Name", "The Player's Minecraft Account Name", name, "Main Group", "The Player's Main Rank", primary, 0.5f));
            } else {
                weight.onChange = this::identityChanged;
                addRow(setting, "identity", 34, paired("Display Name", "The Name Players See", name, "Priority", "Higher Groups Appear Above Lower Groups", weight, 0.72f));
            }
            newPermission = catalogField("Choose Permission", "server:luckperms:permission", LuckPermsDashboardScreen.this::permissionOptions, "");
            newScope = choiceField("Everywhere", LuckPermsDashboardScreen.this::scopeChoices, "everywhere");
            newValue = permissionToggle(true);
            addRow(composerSetting, "composer-scope", 32, titled("Applies To", "Use Everywhere Or Limit Access To One Server Or World", newScope));
            addBottomRow(composerSetting, "composer", 34, permissionValuePair(newPermission, newValue), rowAction("add.png", "Add Permission", this::addPermission));
            addRow(permissionsSetting, "empty", 30, inactiveRow("No Direct Permissions", "Use Add Permission To Grant Or Deny Access"));
            addRow(inheritanceSetting, "empty", 30, inactiveRow("No Inheritance", "This Entry Does Not Inherit Any Groups"));
            addRow(metadataSetting, "add", 30, actionButton("Add Metadata", this::showMetadataCreator));
            addRow(metadataSetting, "empty", 30, inactiveRow("No Metadata", "Prefixes, Suffixes, And Custom Values Appear Here"));
            addRow(setting, "remove", 30, destructiveButton(detail.subject().type() == SubjectType.USER ? "Remove User" : "Remove Group", () -> deleteEntity(detail.subject().type() == SubjectType.USER ? EntityType.USER : EntityType.GROUP, detail.subject().id(), setting)));
            render(detail);
        }

        private List<Setting> sections() {
            List<Setting> sections = new ArrayList<>();
            sections.add(setting);
            if (composerVisible) sections.add(composerSetting);
            sections.add(permissionsSetting);
            sections.add(inheritanceSetting);
            sections.add(metadataSetting);
            return List.copyOf(sections);
        }

        private void apply(SubjectDetail detail) {
            acknowledged = detail;
            desired = detail;
            generation++;
            render(detail);
        }

        private void render(SubjectDetail detail) {
            if (detail.subject().type() == SubjectType.GROUP) {
                groupGraphDetails.put(detail.subject().id(), detail);
                if (inheritanceView.visible) refreshInheritanceGraph();
                refreshUserPresentations();
            }
            applying = true;
            name.setText(detail.name());
            primary.setValue(detail.primaryGroup());
            weight.setText(detail.weight() == null ? "" : detail.weight().toString());
            updateSummary();
            Set<String> visiblePermissions = new LinkedHashSet<>();
            Set<String> visibleInheritance = new LinkedHashSet<>();
            Set<String> visibleMetadata = new LinkedHashSet<>();
            boolean rowsAdded = false;
            for (NodeData node : detail.directNodes()) {
                if (node.kind() == NodeKind.PERMISSION) {
                    visiblePermissions.add(node.id());
                    PermissionRow row = permissionRows.get(node.id());
                    if (row == null) {
                        String id = node.id();
                        PermissionRow created = new PermissionRow(id);
                        addRow(permissionsSetting, "permission:" + id, 30, created.row);
                        permissionRows.put(id, created);
                        row = created;
                        rowsAdded = true;
                    }
                    String category = permissionCategory(node.key());
                    if (!permissionCategories.containsKey(category)) {
                        IconButton header = categoryRow(category);
                        permissionCategories.put(category, header);
                        addRow(permissionsSetting, "category:" + category, 18, header);
                        rowsAdded = true;
                    }
                    row.apply(node);
                    permissionsSetting.setRowVisibility("permission:" + node.id(), true);
                } else if (node.kind() == NodeKind.INHERITANCE) {
                    visibleInheritance.add(node.id());
                    InheritanceRow row = inheritanceRows.get(node.id());
                    if (row == null) {
                        String id = node.id();
                        InheritanceRow created = new InheritanceRow(id);
                        addRow(inheritanceSetting, "inheritance:" + id, 30, created.row);
                        inheritanceRows.put(id, created);
                        row = created;
                        rowsAdded = true;
                    }
                    row.apply(node);
                    inheritanceSetting.setRowVisibility("inheritance:" + node.id(), true);
                } else {
                    visibleMetadata.add(node.id());
                    MetadataRow row = metadataRows.get(node.id());
                    if (row == null) {
                        String id = node.id();
                        MetadataRow created = new MetadataRow(id);
                        addRow(metadataSetting, "metadata:" + id, 30, created.row);
                        metadataRows.put(id, created);
                        row = created;
                        rowsAdded = true;
                    }
                    row.apply(node);
                    metadataSetting.setRowVisibility("metadata:" + node.id(), true);
                }
            }
            permissionRows.keySet().stream().filter(id -> !visiblePermissions.contains(id)).forEach(id -> permissionsSetting.setRowVisibility("permission:" + id, false));
            inheritanceRows.keySet().stream().filter(id -> !visibleInheritance.contains(id)).forEach(id -> inheritanceSetting.setRowVisibility("inheritance:" + id, false));
            metadataRows.keySet().stream().filter(id -> !visibleMetadata.contains(id)).forEach(id -> metadataSetting.setRowVisibility("metadata:" + id, false));
            Set<String> visibleCategories = detail.directNodes().stream().filter(node -> node.kind() == NodeKind.PERMISSION).map(node -> permissionCategory(node.key())).collect(Collectors.toSet());
            permissionCategories.keySet().forEach(category -> permissionsSetting.setRowVisibility("category:" + category, visibleCategories.contains(category)));
            permissionsSetting.setRowVisibility("empty", visiblePermissions.isEmpty());
            inheritanceSetting.setRowVisibility("empty", visibleInheritance.isEmpty());
            metadataSetting.setRowVisibility("empty", visibleMetadata.isEmpty());
            if (rowsAdded) reorderRows();
            applying = false;
            filter(filter);
            updateWorkspace();
        }

        private void updateSummary() {
            long permissions = desired.directNodes().stream().filter(node -> node.kind() == NodeKind.PERMISSION).count();
            long inherited = desired.directNodes().stream().filter(node -> node.kind() == NodeKind.INHERITANCE).count();
            String statusText = desired.subject().type() == SubjectType.USER ? userStatus(desired.subject().id()) : groupStatus(desired.subject().id());
            String styled = styledSubject(desired);
            summary.setText(desired.name().isBlank() ? desired.subject().id() : desired.name(), (styled.isBlank() ? "" : styled + " • ") + RICH_RESET + statusText + " • " + permissions + " Direct • " + inherited + " Parents");
            summary.setHiddenText(saving ? "Saving" : "Saved");
        }

        private void reorderRows() {
            Map<String, PopupWidget.PopupRow> permissionRowsById = permissionsSetting.getRows().stream().collect(Collectors.toMap(row -> row.id, row -> row, (left, right) -> left, LinkedHashMap::new));
            List<PopupWidget.PopupRow> orderedPermissions = new ArrayList<>();
            Map<String, List<NodeData>> categories = desired.directNodes().stream().filter(node -> node.kind() == NodeKind.PERMISSION)
                .collect(Collectors.groupingBy(node -> permissionCategory(node.key()), LinkedHashMap::new, Collectors.toList()));
            categories.entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)).forEach(entry -> {
                addOrderedRow(orderedPermissions, permissionRowsById, "category:" + entry.getKey());
                entry.getValue().forEach(node -> addOrderedRow(orderedPermissions, permissionRowsById, "permission:" + node.id()));
            });
            addOrderedRow(orderedPermissions, permissionRowsById, "empty");
            permissionRowsById.values().stream().filter(row -> !orderedPermissions.contains(row)).forEach(orderedPermissions::add);
            permissionsSetting.setRows(orderedPermissions);

            Map<String, PopupWidget.PopupRow> inheritanceRowsById = inheritanceSetting.getRows().stream().collect(Collectors.toMap(row -> row.id, row -> row, (left, right) -> left, LinkedHashMap::new));
            List<PopupWidget.PopupRow> orderedInheritance = new ArrayList<>();
            desired.directNodes().stream().filter(node -> node.kind() == NodeKind.INHERITANCE).forEach(node -> addOrderedRow(orderedInheritance, inheritanceRowsById, "inheritance:" + node.id()));
            addOrderedRow(orderedInheritance, inheritanceRowsById, "empty");
            inheritanceRowsById.values().stream().filter(row -> !orderedInheritance.contains(row)).forEach(orderedInheritance::add);
            inheritanceSetting.setRows(orderedInheritance);

            Map<String, PopupWidget.PopupRow> metadataRowsById = metadataSetting.getRows().stream().collect(Collectors.toMap(row -> row.id, row -> row, (left, right) -> left, LinkedHashMap::new));
            List<PopupWidget.PopupRow> orderedMetadata = new ArrayList<>();
            addOrderedRow(orderedMetadata, metadataRowsById, "add");
            desired.directNodes().stream().filter(node -> node.kind() != NodeKind.PERMISSION && node.kind() != NodeKind.INHERITANCE)
                .sorted(Comparator.comparing((NodeData node) -> metadataTitle(node.kind()), String.CASE_INSENSITIVE_ORDER).thenComparing(NodeData::key, String.CASE_INSENSITIVE_ORDER))
                .forEach(node -> addOrderedRow(orderedMetadata, metadataRowsById, "metadata:" + node.id()));
            addOrderedRow(orderedMetadata, metadataRowsById, "empty");
            metadataRowsById.values().stream().filter(row -> !orderedMetadata.contains(row)).forEach(orderedMetadata::add);
            metadataSetting.setRows(orderedMetadata);
        }

        private String searchText() {
            StringBuilder text = new StringBuilder(desired.name()).append(' ').append(desired.primaryGroup()).append(' ');
            desired.directNodes().forEach(node -> text.append(node.kind()).append(' ').append(node.key()).append(' ').append(contexts(node)).append(' '));
            return text.toString();
        }

        private void filter(String query) {
            Map<String, Boolean> categoryMatches = new LinkedHashMap<>();
            for (NodeData node : desired.directNodes()) {
                boolean visible = matches(query, node.key(), node.kind().name(), contexts(node), node.value() ? "allowed enabled" : "denied disabled");
                if (node.kind() == NodeKind.PERMISSION) {
                    permissionsSetting.setRowVisibility("permission:" + node.id(), visible);
                    categoryMatches.merge(permissionCategory(node.key()), visible, Boolean::logicalOr);
                } else if (node.kind() == NodeKind.INHERITANCE) {
                    inheritanceSetting.setRowVisibility("inheritance:" + node.id(), visible);
                } else {
                    metadataSetting.setRowVisibility("metadata:" + node.id(), visible);
                }
            }
            permissionCategories.keySet().forEach(category -> permissionsSetting.setRowVisibility("category:" + category, categoryMatches.getOrDefault(category, false)));
            boolean hasPermissions = desired.directNodes().stream().anyMatch(node -> node.kind() == NodeKind.PERMISSION && matches(query, node.key(), contexts(node), node.value() ? "allowed enabled" : "denied disabled"));
            boolean hasInheritance = desired.directNodes().stream().anyMatch(node -> node.kind() == NodeKind.INHERITANCE && matches(query, node.key(), contexts(node)));
            boolean hasMetadata = desired.directNodes().stream().anyMatch(node -> node.kind() != NodeKind.PERMISSION && node.kind() != NodeKind.INHERITANCE && matches(query, node.key(), node.kind().name(), contexts(node)));
            permissionsSetting.setRowVisibility("empty", !hasPermissions);
            inheritanceSetting.setRowVisibility("empty", !hasInheritance);
            metadataSetting.setRowVisibility("empty", !hasMetadata);
            updateWorkspace();
        }

        private void identityChanged() {
            if (applying) return;
            Integer parsedWeight = null;
            if (!weight.getText().isBlank()) {
                try {
                    parsedWeight = Integer.valueOf(weight.getText().trim());
                } catch (NumberFormatException exception) {
                    setStatus("Priority Must Be A Number", weight.getText(), "danger");
                    return;
                }
            }
            desired = new SubjectDetail(desired.revision(), desired.subject(), name.getText(), primary.value(), parsedWeight, desired.directNodes());
            generation++;
            scheduleIdentitySave();
        }

        private void scheduleIdentitySave() {
            long identityGeneration = generation;
            AsyncTools.schedule(TaskSchedulers.current(), Duration.ofMillis(450), () -> ui(() -> {
                if (identityGeneration != generation || applying) return;
                saveNext();
            }));
        }

        private void showPermissionComposer() {
            composerVisible = true;
            showSubjectView(this);
            workspace.scrollToWidget(composerSetting);
        }

        private void showMetadataCreator() {
            PopupWidget[] reference = new PopupWidget[1];
            DropDownWidget<String> type = new DropDownWidget.Builder<>(List.of("Prefix", "Suffix", "Custom Metadata"))
                .selectedItem("Prefix")
                .onSelectionChanged(selected -> {
                    if (reference[0] == null) return;
                    boolean custom = "Custom Metadata".equals(selected);
                    reference[0].setRowVisibility("key", custom);
                    reference[0].setRowVisibility("priority", !custom);
                })
                .size(220, 18)
                .build();
            TextInputWidget key = input("Metadata Key", "", 220);
            TextInputWidget value = input("MiniMessage Value", "", 220);
            TextInputWidget priority = input("Priority", "100", 90);
            PopupWidget.Builder builder = new PopupWidget.Builder("Add Metadata").width(360).setResizable(false).setExpandWithDropdowns(true).setAntiOutOfBound(true);
            builder.addRow(new PopupWidget.PopupRow.Builder("Type", type).id("type").minHeight(18).build());
            builder.addRow(new PopupWidget.PopupRow.Builder("Key", key).id("key").description("The Name Used To Read This Custom Value").minHeight(18).build());
            builder.addRow(new PopupWidget.PopupRow.Builder("Value", value).id("value").description("MiniMessage Formatting Is Supported").minHeight(18).build());
            builder.addRow(new PopupWidget.PopupRow.Builder("Priority", priority).id("priority").description("Higher Values Take Priority").minHeight(18).build());
            builder.addTitleAction("Add", () -> addMetadata(metadataType(type.getSelectedItem()), key.getText(), value.getText(), priority.getText(), reference[0]), PopupWidget.TitleActionRole.PRIMARY);
            reference[0] = builder.build();
            reference[0].setRowVisibility("key", false);
            addDrawableChild(reference[0]);
            reference[0].show();
        }

        private String metadataType(String selected) {
            return switch (selected) {
                case "Suffix" -> "suffix";
                case "Custom Metadata" -> "meta";
                default -> "prefix";
            };
        }

        private void addMetadata(String type, String rawKey, String rawValue, String rawPriority, PopupWidget popup) {
            String value = rawValue == null ? "" : rawValue;
            if (value.isBlank()) {
                setStatus("Metadata Value Required", "Enter The Prefix, Suffix, Or Custom Value", "danger");
                return;
            }
            NodeKind kind;
            String key;
            Integer priority = null;
            if ("meta".equals(type)) {
                String metadataKey = rawKey == null ? "" : rawKey.trim();
                if (metadataKey.isBlank()) {
                    setStatus("Metadata Key Required", "Enter A Name For This Custom Value", "danger");
                    return;
                }
                kind = NodeKind.META;
                key = "meta." + metadataKey + "." + value;
            } else {
                try {
                    priority = Integer.valueOf(rawPriority == null || rawPriority.isBlank() ? "100" : rawPriority.trim());
                } catch (NumberFormatException exception) {
                    setStatus("Priority Must Be A Number", rawPriority, "danger");
                    return;
                }
                kind = "suffix".equals(type) ? NodeKind.SUFFIX : NodeKind.PREFIX;
                key = (kind == NodeKind.SUFFIX ? "suffix." : "prefix.") + priority + "." + value;
            }
            NodeData node = new NodeData(UUID.randomUUID().toString(), kind, key, true, priority, Map.of(), null);
            if (popup != null) popup.hide();
            mutate(detail -> withNodes(detail, append(detail.directNodes(), node)));
        }

        private void addPermission() {
            String permission = newPermission.value();
            if (permission.isBlank()) {
                setStatus("Choose A Permission", "Select The Permission To Add", "danger");
                return;
            }
            Map<String, List<String>> contexts = selectedScope(newScope);
            boolean exists = desired.directNodes().stream().anyMatch(node -> node.kind() == NodeKind.PERMISSION && node.key().equalsIgnoreCase(permission) && node.contexts().equals(contexts));
            if (exists) {
                setStatus("Permission Already Added", permission, "calm");
                return;
            }
            NodeData node = new NodeData(UUID.randomUUID().toString(), NodeKind.PERMISSION, permission, newValue.getValue(), null, contexts, null);
            composerVisible = false;
            mutate(detail -> withNodes(detail, append(detail.directNodes(), node)));
            showSubjectView(this);
        }

        private void mutate(UnaryOperator<SubjectDetail> mutation) {
            desired = mutation.apply(desired);
            generation++;
            render(desired);
            saveNext();
        }

        private void saveNext() {
            if (saving || desired.equals(acknowledged)) return;
            saving = true;
            SubjectDetail sending = desired;
            long sentGeneration = generation;
            summary.setHiddenText("Saving");
            SubjectChange subjectChange = new SubjectChange(sending.subject(), acknowledged.revision(), sending.name(), sending.primaryGroup(), sending.weight(), sending.directNodes());
            ChangeSet change = new ChangeSet(UUID.randomUUID().toString(), List.of(subjectChange), List.of(), List.of(), List.of());
            persist(change, result -> {
                saving = false;
                if (!sourceSaved(result)) {
                    desired = acknowledged;
                    render(acknowledged);
                    return;
                }
                long revision = sourceRevision(result, sending.subject().type() == SubjectType.USER ? EntityType.USER : EntityType.GROUP, sending.subject().id(), acknowledged.revision());
                acknowledged = withRevision(sending, revision);
                if (generation == sentGeneration) {
                    desired = acknowledged;
                } else {
                    desired = withRevision(desired, revision);
                }
                render(desired);
                setStatus("Saved", sending.subject().id(), "nice");
                refreshTreeEntry();
                if (sending.subject().type() == SubjectType.GROUP) refreshInheritanceGraph();
                saveNext();
            }, () -> {
                saving = false;
                desired = acknowledged;
                render(acknowledged);
            });
        }

        private void refreshTreeEntry() {
            if (desired.subject().type() == SubjectType.USER) {
                refreshUserPresentations();
            } else {
                TreeEntryWidget entry = groupEntries.get(desired.subject().id());
                if (entry != null) entry.setEntry(desired.name().isBlank() ? desired.subject().id() : desired.name(), desired.subject().id() + (desired.weight() == null ? "" : " • Priority " + desired.weight()), desired.directNodes().size() + " Nodes");
            }
        }

        private final class PermissionRow {
            private final String id;
            private final MountableButtonWidget row;
            private final ToggleWidget value;
            private NodeData node;

            private PermissionRow(String id) {
                this.id = id;
                value = permissionToggle(true);
                value.setOnChange(() -> {
                    value.setMessage(value.getValue() ? "Allow" : "Deny");
                    toggle();
                });
                row = new MountableButtonWidget.Builder("").addWidget(value).addButton(destructiveRowAction("delete.png", "Delete Permission", this::delete)).build();
                row.setSize(contentWidth(), 30);
                row.entranceAnimationEnabled = false;
                row.setAnimateLayout(false);
            }

            private void apply(NodeData node) {
                this.node = node;
                row.setName(node.key());
                row.setDescription(contexts(node));
                row.setHiddenText(node.expiresAt() != null ? "Temporary" : node.contexts().isEmpty() ? "" : "Scoped");
                row.setAccent(ThemeManager.getDefaultAccent());
                value.setValue(node.value());
                value.setMessage(node.value() ? "Allow" : "Deny");
            }

            private void toggle() {
                if (applying || node == null) return;
                replace(new NodeData(node.id(), node.kind(), node.key(), value.getValue(), node.priority(), node.contexts(), node.expiresAt()));
            }

            private void delete() {
                mutate(detail -> withNodes(detail, detail.directNodes().stream().filter(candidate -> !candidate.id().equals(id)).toList()));
            }

            private void replace(NodeData replacement) {
                mutate(detail -> withNodes(detail, detail.directNodes().stream().map(candidate -> candidate.id().equals(id) ? replacement : candidate).toList()));
            }
        }

        private final class InheritanceRow {
            private final String id;
            private final MountableButtonWidget row;
            private NodeData node;

            private InheritanceRow(String id) {
                this.id = id;
                row = new MountableButtonWidget.Builder("").icon(Identifier.icon("link.png")).addButton(destructiveRowAction("delete.png", "Remove Inheritance", this::delete)).build();
                row.setSize(contentWidth(), 30);
                row.entranceAnimationEnabled = false;
                row.setAnimateLayout(false);
            }

            private void apply(NodeData node) {
                this.node = node;
                row.setName(node.key());
                row.setDescription("Inherits This Group's Permissions • " + contexts(node));
                row.setHiddenText("Inherited");
            }

            private void delete() {
                mutate(detail -> withNodes(detail, detail.directNodes().stream().filter(candidate -> !candidate.id().equals(id)).toList()));
            }
        }

        private final class MetadataRow {
            private final String id;
            private final MountableButtonWidget row;
            private final TextInputWidget key;
            private final ToggleWidget value;
            private NodeData node;

            private MetadataRow(String id) {
                this.id = id;
                key = input("Metadata Value", "", 240);
                key.onChange = this::changed;
                value = enabledToggle(true);
                value.setOnChange(() -> {
                    value.setMessage(value.getValue() ? "Enabled" : "Disabled");
                    changed();
                });
                row = new MountableButtonWidget.Builder("").addWidget(key).addWidget(value).addButton(destructiveRowAction("delete.png", "Delete Metadata", this::delete)).build();
                row.setSize(contentWidth(), 30);
                row.entranceAnimationEnabled = false;
                row.setAnimateLayout(false);
            }

            private void apply(NodeData node) {
                this.node = node;
                row.setName(metadataName(node));
                row.setDescription(metadataDescription(node));
                row.setHiddenText(node.priority() == null ? "" : "Priority " + node.priority());
                row.setAccent(ThemeManager.getDefaultAccent());
                key.setText(metadataValue(node));
                value.setValue(node.value());
                value.setMessage(node.value() ? "Enabled" : "Disabled");
            }

            private void changed() {
                if (applying || node == null || key.getText().isBlank()) return;
                NodeData replacement = new NodeData(node.id(), node.kind(), metadataKey(node, key.getText().trim()), value.getValue(), node.priority(), node.contexts(), node.expiresAt());
                mutate(detail -> withNodes(detail, detail.directNodes().stream().map(candidate -> candidate.id().equals(id) ? replacement : candidate).toList()));
            }

            private void delete() {
                mutate(detail -> withNodes(detail, detail.directNodes().stream().filter(candidate -> !candidate.id().equals(id)).toList()));
            }
        }
    }

    private final class TrackView {
        private final Setting setting;
        private final MessageWidget summary;
        private final List<TrackGroupRow> rows = new ArrayList<>();
        private final SelectorField group;
        private TrackDetail acknowledged;
        private TrackDetail desired;
        private boolean saving;
        private long generation;

        private TrackView(TrackDetail track) {
            acknowledged = track;
            desired = track;
            setting = addWorkspace(new Setting.Builder("Promotion Track").build());
            summary = new MessageWidget(contentWidth(), track.name(), "Promotion Order");
            addRow(setting, "summary", 30, summary);
            addRow(setting, "help", 30, infoRow("Promotion Order", "Players Move From The First Group To The Last", "Changes Save Immediately"));
            group = choiceField("Choose Group", LuckPermsDashboardScreen.this::groupChoices, "");
            addRow(setting, "composer", 32, titled("Add Group", "Choose The Next Group In This Promotion Path", group), rowAction("add.png", "Add Group", this::addGroup));
            setting.setRowVisibility("composer", false);
            addRow(setting, "remove", 30, destructiveButton("Remove Track", () -> deleteEntity(EntityType.TRACK, track.name(), setting)));
            render(track);
        }

        private void apply(TrackDetail track) {
            acknowledged = track;
            desired = track;
            render(track);
        }

        private void render(TrackDetail track) {
            summary.setText(track.name(), track.groups().size() + " Groups • Promotes From Top To Bottom");
            summary.setHiddenText(saving ? "Saving" : "Saved");
            while (rows.size() < track.groups().size()) {
                TrackGroupRow row = new TrackGroupRow(rows.size());
                rows.add(row);
                addRow(setting, "group:" + row.slot, 30, row.row);
                reorderRows();
            }
            for (int i = 0; i < rows.size(); i++) {
                boolean visible = i < track.groups().size();
                if (visible) rows.get(i).apply(track.groups().get(i), track.groups().size());
                setting.setRowVisibility("group:" + i, visible);
            }
            updateWorkspace();
        }

        private void filter(String query) {
            for (int i = 0; i < rows.size(); i++) {
                boolean visible = i < desired.groups().size() && matches(query, desired.groups().get(i), i == 0 ? "starting group" : "promotion");
                setting.setRowVisibility("group:" + i, visible);
            }
            updateWorkspace();
        }

        private void reorderRows() {
            Map<String, PopupWidget.PopupRow> rowsById = setting.getRows().stream().collect(Collectors.toMap(row -> row.id, row -> row, (left, right) -> left, LinkedHashMap::new));
            List<PopupWidget.PopupRow> ordered = new ArrayList<>();
            addOrderedRow(ordered, rowsById, "summary");
            addOrderedRow(ordered, rowsById, "help");
            for (int i = 0; i < rows.size(); i++) addOrderedRow(ordered, rowsById, "group:" + i);
            addOrderedRow(ordered, rowsById, "composer");
            addOrderedRow(ordered, rowsById, "remove");
            rowsById.values().stream().filter(row -> !ordered.contains(row)).forEach(ordered::add);
            setting.setRows(ordered);
        }

        private void showGroupComposer() {
            setting.setRowVisibility("composer", true);
            updateWorkspace();
        }

        private void addGroup() {
            String value = group.value();
            if (value.isBlank() || desired.groups().contains(value)) return;
            List<String> order = new ArrayList<>(desired.groups());
            order.add(value);
            mutate(List.copyOf(order));
            setting.setRowVisibility("composer", false);
        }

        private void mutate(List<String> order) {
            desired = new TrackDetail(desired.name(), desired.revision(), order);
            generation++;
            render(desired);
            saveNext();
        }

        private void saveNext() {
            if (saving || desired.equals(acknowledged)) return;
            saving = true;
            TrackDetail sending = desired;
            long sentGeneration = generation;
            render(desired);
            ChangeSet change = new ChangeSet(UUID.randomUUID().toString(), List.of(), List.of(new TrackChange(sending.name(), acknowledged.revision(), sending.groups())), List.of(), List.of());
            persist(change, result -> {
                saving = false;
                if (!sourceSaved(result)) {
                    desired = acknowledged;
                    render(acknowledged);
                    return;
                }
                long revision = sourceRevision(result, EntityType.TRACK, sending.name(), acknowledged.revision());
                acknowledged = new TrackDetail(sending.name(), revision, sending.groups());
                desired = generation == sentGeneration ? acknowledged : new TrackDetail(desired.name(), revision, desired.groups());
                render(desired);
                TreeEntryWidget entry = trackEntries.get(sending.name());
                if (entry != null) entry.setEntry(sending.name(), sending.groups().isEmpty() ? "No Groups" : String.join(" › ", sending.groups()), sending.groups().size() + " Groups");
                setStatus("Saved", sending.name(), "nice");
                saveNext();
            }, () -> {
                saving = false;
                desired = acknowledged;
                render(acknowledged);
            });
        }

        private final class TrackGroupRow {
            private final int slot;
            private final MountableButtonWidget row;

            private TrackGroupRow(int slot) {
                this.slot = slot;
                row = new MountableButtonWidget.Builder("").addButton(rowAction("up.png", "Move Up", this::up)).addButton(rowAction("down.png", "Move Down", this::down))
                    .addButton(destructiveRowAction("delete.png", "Remove Group", this::delete)).build();
                row.setSize(contentWidth(), 30);
                row.entranceAnimationEnabled = false;
                row.setAnimateLayout(false);
            }

            private void apply(String name, int size) {
                row.setName((slot + 1) + ". " + name);
                row.setDescription(slot == 0 ? "Starting Group" : "Promotes After " + desired.groups().get(slot - 1));
                row.setHiddenText(slot == size - 1 ? "Highest Group" : "Promotion " + (slot + 1));
            }

            private void up() {
                if (slot == 0 || slot >= desired.groups().size()) return;
                List<String> order = new ArrayList<>(desired.groups());
                String value = order.remove(slot);
                order.add(slot - 1, value);
                mutate(List.copyOf(order));
            }

            private void down() {
                if (slot < 0 || slot >= desired.groups().size() - 1) return;
                List<String> order = new ArrayList<>(desired.groups());
                String value = order.remove(slot);
                order.add(slot + 1, value);
                mutate(List.copyOf(order));
            }

            private void delete() {
                if (slot >= desired.groups().size()) return;
                List<String> order = new ArrayList<>(desired.groups());
                order.remove(slot);
                mutate(List.copyOf(order));
            }
        }
    }

    private String userStatus(String id) {
        UserSummary user = users.stream().filter(value -> value.uniqueId().equals(id)).findFirst().orElse(null);
        return user == null ? "User" : user.online() ? "Online" : user.lastSeen() > 0 ? "Last Seen " + TIME.format(Instant.ofEpochMilli(user.lastSeen())) : "Offline";
    }

    private String groupStatus(String id) {
        GroupSummary group = groups.stream().filter(value -> value.name().equals(id)).findFirst().orElse(null);
        return group == null ? "Group" : group.members() + " Members";
    }

    private static class MessageWidget extends MountableButtonWidget {
        private MessageWidget(int width, String title, String detail) {
            super(title, "", detail, BrowserSafeState.list(), null);
            setSize(width, 30);
            animateElevation = false;
            elevateOnFocused = false;
            enableHoverColors = false;
            roundedCorners = false;
            active = true;
        }

        protected void setText(String title, String detail) {
            setName(title);
            setDescription(detail);
        }
    }

    private static final class TreeEntryWidget extends MessageWidget {
        private TreeEntryWidget(String title, String detail, String icon, Runnable action) {
            super(TREE_WIDTH - 12, title, detail);
            if (icon != null && !icon.isBlank()) setIcon(Identifier.icon(icon));
            setOnClick(action);
            selectable = true;
            enableHoverColors = true;
        }

        private void setEntry(String title, String detail, String badge) {
            setText(title, detail);
            setHiddenText(badge);
            visible = true;
        }
    }
}
