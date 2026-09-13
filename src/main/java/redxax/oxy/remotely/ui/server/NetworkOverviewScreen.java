package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.util.TextLines;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkDiscoveryResult;
import redxax.oxy.remotely.network.NetworkJob;
import redxax.oxy.remotely.network.NetworkJobStatus;
import redxax.oxy.remotely.network.NetworkJobType;
import redxax.oxy.remotely.network.NetworkIncident;
import redxax.oxy.remotely.network.NetworkIncidentSeverity;
import redxax.oxy.remotely.network.NetworkIncidentStatus;
import redxax.oxy.remotely.network.NetworkLifecycleJob;
import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.network.NetworkLifecycleStep;
import redxax.oxy.remotely.network.NetworkLifecycleStatus;
import redxax.oxy.remotely.network.NetworkMember;
import redxax.oxy.remotely.network.NetworkPathSync;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import redxax.oxy.remotely.network.NetworkRuntimeNodePresence;
import redxax.oxy.remotely.network.NetworkRuntimeNodeStatus;
import redxax.oxy.remotely.network.NetworkSharedDataPolicy;
import redxax.oxy.remotely.network.NetworkPreflightStatus;
import redxax.oxy.remotely.network.NetworkValidationIssue;
import redxax.oxy.remotely.network.RoutingGroup;
import redxax.oxy.remotely.network.RoutingStrategy;
import redxax.oxy.remotely.network.SyncDataFamily;
import redxax.oxy.remotely.network.SyncLocationPolicy;
import redxax.oxy.remotely.network.SyncRealm;
import restudio.rescreen.platform.Async;
import restudio.rebase.ui.widgets.LifecycleButtonWidget;
import redxax.oxy.remotely.ui.widgets.NetworkTopologyWidget;
import restudio.rebase.ui.widgets.editor.TextAreaWidget;

import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.TabsManager;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class NetworkOverviewScreen extends ReScreen {
    static final List<String> TAB_NAMES = List.of("Overview", "Servers", "Sharing", "Settings");
    static final List<String> RIGHT_HEADER_ACTIONS = List.of("Save", "Close");
    private final Screen parent;
    private final String networkId;
    private NetworkDefinition network;
    private NetworkDiscoveryResult discovery;
    private List<NetworkOverviewProvider.ServerView> instances = List.of();
    private final Map<String, NetworkOverviewProvider.ServerView> instancesById = new LinkedHashMap<>();
    private Map<String, NetworkOverviewProvider.NetworkCapability> capabilities = Map.of();
    private Map<String, Boolean> reSyncInstalled = Map.of();
    private PopupWidget dissolvePopup;
    private TextInputWidget networkNameInput;
    private final List<ConfigOption<?>> sharingOptions = new ArrayList<>();
    private List<RoutingGroup> routingGroups = List.of();
    private List<SyncRealm> syncRealms = List.of();
    private Container overviewContainer;
    private Container serversContainer;
    private Container sharingContainer;
    private Container settingsContainer;
    private final Map<Container, PlayerGroupEditor> playerGroupEditors = new LinkedHashMap<>();
    private SquareButtonWidget deletePlayerGroupButton;
    private NetworkTopologyWidget topologyWidget;
    private Setting activitySetting;
    private Setting attentionSetting;
    private Setting serversSetting;
    private Setting routingSetting;
    private Setting playerDataSetting;
    private Setting pathSyncSetting;
    private final Map<String, MountableButtonWidget> activityRows = new LinkedHashMap<>();
    private final Map<String, MountableButtonWidget> attentionRows = new LinkedHashMap<>();
    private final Map<String, AttentionItem> attentionItems = new LinkedHashMap<>();
    private final Map<String, MountableButtonWidget> serverRows = new LinkedHashMap<>();
    private final Map<String, MemberActions> memberActions = new LinkedHashMap<>();
    private final Map<String, MountableButtonWidget> routingRows = new LinkedHashMap<>();
    private final Map<String, MountableButtonWidget> playerDataRows = new LinkedHashMap<>();
    private final Map<String, MountableButtonWidget> pathSyncRows = new LinkedHashMap<>();
    private final NetworkOverviewProvider provider;
    private final Consumer<NetworkOverviewProvider.OverviewState> networkChangeListener = this::queueNetworkState;
    private final Consumer<NetworkRuntimeSnapshot> runtimeChangeListener = this::queueRuntimeSnapshot;
    private NetworkRuntimeSnapshot runtimeSnapshot;
    private List<NetworkIncident> incidents = List.of();
    private List<NetworkLifecycleJob> lifecycleJobs = List.of();
    private List<NetworkJob> activeJobs = List.of();
    private Map<String, Integer> transferFailureHeat = Map.of();
    private String serverSearchQuery = "";
    private LifecycleButtonWidget networkPowerButton;
    private boolean applyingNetworkChange;
    private long refreshGeneration;
    private boolean sharedChat;
    private boolean sharedResources;
    private List<NetworkPathSync> pathSyncs = List.of();
    private NetworkSharedDataPolicy.SelectionMode chatChannelMode;
    private String chatChannels;
    private long chatRetentionMillis;
    private NetworkSharedDataPolicy.SelectionMode resourceTypeMode;
    private String resourceTypes;
    private NetworkSharedDataPolicy.ConflictPolicy resourceConflictPolicy;
    private int maximumPayloadBytes;
    private boolean batchingLayout;
    private boolean networkChangeListenerRegistered;
    private boolean runtimeChangeListenerRegistered;

    public NetworkOverviewScreen(Screen parent, NetworkOverviewProvider provider, String networkId) {
        this.parent = parent;
        enableNavigation(parent);
        this.networkId = networkId;
        this.provider = provider == null ? NetworkOverviewProvider.unavailableProvider() : provider;
    }

    @Override
    public void close() {
        ScreenManager.getInstance().goBack(this, parent);
    }

    public String getDesktopAppId() {
        return "network-overview";
    }

    public String getDesktopAppTitle() {
        return network == null ? "Network" : network.name();
    }

    public String getDesktopAppIconPath() {
        return "network.png";
    }

    @Override
    public void init() {
        super.init();
        playerGroupEditors.values().forEach(PlayerGroupEditor::close);
        playerGroupEditors.clear();
        if (!provider.available()) {
            new Notification("Network Unavailable", Notification.Type.ERROR);
            ScreenManager.getInstance().goBack(this, parent);
            return;
        }
        registerNetworkChangeListener();
        registerRuntimeChangeListener();
        applyingNetworkChange = true;
        activityRows.clear();
        attentionRows.clear();
        attentionItems.clear();
        serverRows.clear();
        memberActions.clear();
        routingRows.clear();
        playerDataRows.clear();
        serverSearchQuery = "";
        networkPowerButton = new LifecycleButtonWidget(this::toggleNetworkPower, "Network");
        deletePlayerGroupButton = new SquareButtonWidget.Builder().imagePath("delete.png").hint("Delete Player Group").accentType(ThemeManager.getAccent("danger")).onClick(this::deleteActivePlayerGroup).build();
        deletePlayerGroupButton.visible = false;
        header()
            .addLeft(networkPowerButton)
            .addRight("close.png", () -> ScreenManager.getInstance().goBack(this, parent), RIGHT_HEADER_ACTIONS.getLast())
            .addRight("save.png", this::saveAll, RIGHT_HEADER_ACTIONS.getFirst())
            .addRight(deletePlayerGroupButton)
            .build();
        int contentY = 60;
        int contentHeight = Math.max(80, height - contentY - 6);
        overviewContainer = tabContainer("network_overview", contentY, contentHeight);
        serversContainer = tabContainer("network_servers", contentY, contentHeight);
        sharingContainer = tabContainer("network_sharing", contentY, contentHeight);
        settingsContainer = tabContainer("network_settings", contentY, contentHeight);
        tabs().addTab(TAB_NAMES.get(0), overviewContainer);
        tabs().addTab(TAB_NAMES.get(1), serversContainer);
        tabs().addTab(TAB_NAMES.get(2), sharingContainer);
        tabs().addTab(TAB_NAMES.get(3), settingsContainer);
        tabsManager.builder().allowAdd(false).allowClose(true).allowRename(false).allowReorder(false).position(6, 36).size(width - 12, 18)
            .onTabSelected(this::selectNetworkTab)
            .onTabCloseRequested(tab -> tab != null && playerGroupEditors.containsKey(tab.getContainer()))
            .onTabClosed(tab -> closePlayerGroupEditor(tab.getContainer()))
            .build();
        tabs().setActiveTab(overviewContainer);
        setActiveContainer(overviewContainer);
        long generation = ++refreshGeneration;
        provider.load(networkId).whenComplete((state, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (generation != refreshGeneration) {
                return;
            }
            applyingNetworkChange = false;
            if (throwable != null || state == null || state.network() == null) {
                new Notification("Network Unavailable", throwable == null ? "Network Data Is Unavailable" : rootMessage(throwable), Notification.Type.ERROR);
                ScreenManager.getInstance().goBack(this, parent);
                return;
            }
            applyInitialState(state);
            createDissolvePopup();
            buildContent();
            updateNetworkPowerButton();
        }));
    }

    private void saveAll() {
        if (applyingNetworkChange) {
            return;
        }
        if (!requireNetworkCapability("save")) return;
        for (PlayerGroupEditor editor : List.copyOf(playerGroupEditors.values())) {
            if (!editor.apply()) return;
        }
        sharingOptions.forEach(ConfigOption::apply);
        String name = networkNameInput.getText() == null ? "" : networkNameInput.getText().trim();
        if (name.isBlank()) {
            new Notification("Name Required", "Enter a network name before saving.", Notification.Type.ERROR);
            return;
        }
        Map<String, Boolean> features = new LinkedHashMap<>(network.features());
        features.put(NetworkDefinition.FEATURE_SHARED_CHAT, sharedChat);
        features.put(NetworkDefinition.FEATURE_SHARED_RESOURCES, sharedResources);
        features.put(NetworkDefinition.FEATURE_PATH_SYNC, pathSyncs.stream().anyMatch(NetworkPathSync::enabled));
        NetworkSharedDataPolicy policy;
        try {
            policy = new NetworkSharedDataPolicy(chatChannelMode, commaSet(chatChannels), chatRetentionMillis, resourceTypeMode, commaSet(resourceTypes), pathSyncs, resourceConflictPolicy, maximumPayloadBytes);
        } catch (IllegalArgumentException exception) {
            new Notification("Path Sync Invalid", exception.getMessage(), Notification.Type.ERROR);
            return;
        }
        boolean nameChanged = !name.equals(network.name());
        boolean routingChanged = !routingGroups.equals(network.routingGroups());
        boolean sharingChanged = !syncRealms.equals(network.syncRealms()) || !features.equals(network.features()) || !policy.equals(network.sharedDataPolicy());
        if (!nameChanged && !routingChanged && !sharingChanged) {
            new Notification("Network Is Current", network.name(), Notification.Type.INFO);
            return;
        }
        applyingNetworkChange = true;
        List<RoutingGroup> targetRouting = List.copyOf(routingGroups);
        List<SyncRealm> targetRealms = List.copyOf(syncRealms);
        Notification notification = operationNotification("Saving Network", network.name());
        NetworkOverviewProvider.SaveRequest request = new NetworkOverviewProvider.SaveRequest(
                nameChanged ? name : network.name(), targetRouting, targetRealms, features, policy);
        Async<NetworkDefinition> save = provider.save(networkId, request);
        save.whenComplete((updated, throwable) -> ScreenManager.getInstance().execute(() -> {
            applyingNetworkChange = false;
            if (throwable != null) {
                notification.update().message("Save Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                refresh();
                return;
            }
            notification.update().message("Network Saved").description(updated.name()).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            refresh();
        }));
    }

    private void applyInitialState(NetworkOverviewProvider.OverviewState state) {
        network = state.network();
        instances = state.servers();
        instancesById.clear();
        instances.forEach(instance -> instancesById.put(instance.id(), instance));
        discovery = state.discovery();
        runtimeSnapshot = latestRuntimeSnapshot(runtimeSnapshot, state.runtime());
        incidents = state.incidents();
        lifecycleJobs = state.lifecycleJobs();
        activeJobs = state.jobs();
        transferFailureHeat = state.transferFailureHeat();
        capabilities = state.capabilities();
        reSyncInstalled = state.reSyncInstalled();
        loadSharingDraft();
        routingGroups = List.copyOf(network.routingGroups());
        syncRealms = List.copyOf(network.syncRealms());
    }

    private Container tabContainer(String id, int y, int height) {
        return createContainer(id, 6, y, width - 12, height).columns(1).padding(4).verticalSpacing(2).layout(new ManagedLayout()).scrolling(true).backgroundDrawing(true);
    }

    private void buildContent() {
        batchingLayout = true;
        try {
            populateOverviewTab(overviewContainer);
            populateServersTab(serversContainer);
            populateSharingTab(sharingContainer);
            populateSettingsTab(settingsContainer);
        } finally {
            batchingLayout = false;
        }
        overviewContainer.updateWidgetPositions();
        serversContainer.updateWidgetPositions();
        sharingContainer.updateWidgetPositions();
        settingsContainer.updateWidgetPositions();
    }

    private void requestLayout(Container container) {
        if (!batchingLayout && container != null) {
            container.updateWidgetPositions();
        }
    }

    private void populateOverviewTab(Container container) {
        int topologyWidth = Math.max(220, container.getEffectiveWidth() - 8);
        topologyWidget = new NetworkTopologyWidget(0, 0, topologyWidth, NetworkTopologyWidget.preferredHeight(topologyWidth, network.members().size()), network, discovery.observations(), () -> runtimeSnapshot, null, () -> transferFailureHeat, this::displayName, this::memberState, this::memberIcon, this::toggleMemberPower, this::openMember);
        container.addWidget(topologyWidget);

        populateCurrentJobs(container);
        populateAttention(container);
        requestLayout(container);
    }

    private void populateAttention(Container container) {
        attentionSetting = new Setting.Builder("Needs Attention").build();
        container.addWidget(attentionSetting);
        syncAttentionRows();
    }

    private AttentionItem attentionItem(String id, String rawMessage, String detail, boolean blocking, NetworkMember member) {
        String message = rawMessage == null ? "" : rawMessage.toLowerCase(Locale.ROOT);
        String title;
        String description;
        if (message.contains("port")) {
            title = "Server Port";
            description = "The saved connection port does not match the server's current port.";
        } else if (message.contains("firewall") || message.contains("security") || message.contains("protection")) {
            title = "Server Protection";
            description = "Remotely has not confirmed that this backend rejects unsafe direct connections.";
        } else if (message.contains("runtime") || message.contains("reconnect") || message.contains("presence")) {
            title = "Server Connection";
            description = "This server has not finished connecting to the shared network.";
        } else {
            title = "Network Setup";
            description = "A saved network setting cannot currently be applied as configured.";
        }
        String label = member == null ? title : displayName(member) + " • " + title;
        return new AttentionItem(id, label, description, detail == null || detail.isBlank() ? rawMessage : detail, blocking, member);
    }

    private void populateServersTab(Container container) {
        populateServers(container);
        populateRouting(container);
    }

    private boolean reSyncAvailable() {
        return network != null && network.runtime().enabled() && network.members().stream().filter(NetworkMember::isManaged).allMatch(member -> member.resyncEnabled() && reSyncInstalled.getOrDefault(member.instanceId(), false));
    }

    private boolean networkSupports(String operation) {
        if (capabilities.isEmpty()) return true;
        NetworkOverviewProvider.NetworkCapability capability = capabilities.get(operation);
        return capability != null && capability.supported();
    }

    private boolean requireNetworkCapability(String operation) {
        if (networkSupports(operation)) return true;
        NetworkOverviewProvider.NetworkCapability capability = capabilities.get(operation);
        String reason = capability == null || capability.reason().isBlank() ? "Network Operation Is Unavailable" : capability.reason();
        new Notification("Unavailable", reason, Notification.Type.WARN);
        return false;
    }

    private void populateSharingTab(Container container) {
        sharingOptions.clear();
        pathSyncRows.clear();
        playerDataRows.clear();
        if (!reSyncAvailable()) {
            Setting.Builder unavailable = new Setting.Builder("ReSync Required");
            unavailable.addRow("install", "", actionRow("Install ReSync", "Install ReSync To Access Shared Chat, Content, Files, And Player Data.", "download.png", this::installNetworkReSync));
            container.addWidget(unavailable.build());
        }
        Setting.Builder chat = new Setting.Builder("Shared Chat");
        ConfigOption<Boolean> chatEnabled = ConfigOption.<Boolean>builder("Share Chat").description("Share network chat between servers.").bind(() -> sharedChat, value -> sharedChat = value).defaultValue(false).resettable(false).dependsOn(this::reSyncAvailable).build();
        ConfigOption<NetworkSharedDataPolicy.SelectionMode> channelMode = ConfigOption.<NetworkSharedDataPolicy.SelectionMode>builder("Which Channels").description("All shares every channel. Only Listed shares the channels in Channel Names. All Except Listed shares every channel except those in Channel Names.").options(List.of(NetworkSharedDataPolicy.SelectionMode.values())).display(this::selectionModeLabel).bind(() -> chatChannelMode, value -> chatChannelMode = value).defaultValue(NetworkSharedDataPolicy.SelectionMode.ALL).resettable(false).dependsOn(this::reSyncAvailable).build();
        ConfigOption<String> channelList = ConfigOption.<String>builder("Channel Names").description("Enter channel names separated by commas. These names are used by Which Channels.").bind(() -> chatChannels, value -> chatChannels = value).defaultValue("").resettable(false).dependsOn(this::reSyncAvailable).dependsOn(() -> channelMode.get() != NetworkSharedDataPolicy.SelectionMode.ALL).build();
        ConfigOption<Long> retention = ConfigOption.<Long>builder("Offline Delivery").description("1 Minute covers brief reconnects. 2 Minutes covers restarts. 15 Minutes covers maintenance. 1 Hour covers longer outages. 1 Day keeps messages until tomorrow.").options(List.of(60_000L, 120_000L, 900_000L, 3_600_000L, 86_400_000L)).display(this::durationLabel).bind(() -> chatRetentionMillis, value -> chatRetentionMillis = value).defaultValue(120_000L).resettable(false).dependsOn(this::reSyncAvailable).build();
        addSharingOption(chat, chatEnabled);
        addSharingOption(chat, channelMode);
        addSharingOption(chat, channelList);
        addSharingOption(chat, retention);
        container.addWidget(chat.build());

        Setting.Builder resources = new Setting.Builder("Shared Resources");
        ConfigOption<Boolean> resourcesEnabled = ConfigOption.<Boolean>builder("Share Custom Content").description("Keep ReSync content the same everywhere.").bind(() -> sharedResources, value -> sharedResources = value).defaultValue(false).resettable(false).dependsOn(this::reSyncAvailable).build();
        ConfigOption<NetworkSharedDataPolicy.SelectionMode> typeMode = ConfigOption.<NetworkSharedDataPolicy.SelectionMode>builder("Which Content").description("All shares everything. Only Listed shares Content Names. All Except Listed skips Content Names. Folder Organization always matches the network.").options(List.of(NetworkSharedDataPolicy.SelectionMode.values())).display(this::selectionModeLabel).bind(() -> resourceTypeMode, value -> resourceTypeMode = value).defaultValue(NetworkSharedDataPolicy.SelectionMode.ALL).resettable(false).dependsOn(this::reSyncAvailable).build();
        ConfigOption<String> typeList = ConfigOption.<String>builder("Content Names").description("Enter content type names separated by commas. These names are used by Which Content.").bind(() -> resourceTypes, value -> resourceTypes = value).defaultValue("").resettable(false).dependsOn(this::reSyncAvailable).dependsOn(() -> typeMode.get() != NetworkSharedDataPolicy.SelectionMode.ALL).build();
        ConfigOption<NetworkSharedDataPolicy.ConflictPolicy> conflicts = ConfigOption.<NetworkSharedDataPolicy.ConflictPolicy>builder("When Both Changed").description("Network Wins keeps the last accepted shared copy. Local Wins keeps the copy on the server that changed it. This also applies to synced files.").options(List.of(NetworkSharedDataPolicy.ConflictPolicy.values())).display(this::conflictPolicyLabel).bind(() -> resourceConflictPolicy, value -> resourceConflictPolicy = value).defaultValue(NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS).resettable(false).dependsOn(this::reSyncAvailable).build();
        ConfigOption<Integer> maximumSize = ConfigOption.<Integer>builder("Largest Shared Item").description("Sets the largest custom content item or server file that can be shared. Larger files stay local and are reported by ReSync.").options(List.of(65_536, 262_144, 500_000)).display(this::sizeLabel).bind(() -> maximumPayloadBytes, value -> maximumPayloadBytes = value).defaultValue(NetworkSharedDataPolicy.DEFAULT_MAXIMUM_PAYLOAD_BYTES).resettable(false).dependsOn(this::reSyncAvailable).build();
        addSharingOption(resources, resourcesEnabled);
        addSharingOption(resources, typeMode);
        addSharingOption(resources, typeList);
        addSharingOption(resources, conflicts);
        addSharingOption(resources, maximumSize);
        container.addWidget(resources.build());

        Setting.Builder paths = new Setting.Builder("Path Sync");
        MountableButtonWidget addPathSync = actionRow("Add Path Sync", reSyncAvailable() ? "Keep Selected Files And Folders Identical Across Chosen Servers. Save Network Settings And Restart ReSync Once; New, Changed, And Deleted Files Then Sync Automatically." : "Install ReSync To Access This Feature", "add.png", () -> openPathSync(null));
        addPathSync.setActive(reSyncAvailable());
        paths.addRow("add", "", addPathSync);
        AnimatedButton pathSyncEmpty = inactive("Files Stay Local Until You Add A Sync", ThemeManager.getDefaultAccent());
        pathSyncEmpty.setVisible(pathSyncs.isEmpty());
        paths.addRow("empty", "", pathSyncEmpty);
        for (NetworkPathSync sync : pathSyncs) {
            MountableButtonWidget row = pathSyncRow(sync);
            pathSyncRows.put(sync.id(), row);
            paths.addRow("sync:" + sync.id(), "", row);
        }
        pathSyncSetting = paths.build();
        pathSyncSetting.setRowVisibility("empty", pathSyncs.isEmpty());
        container.addWidget(pathSyncSetting);

        Setting.Builder playerData = new Setting.Builder("Player Data");
        MountableButtonWidget addPlayerGroup = actionRow("Add Player Group", reSyncAvailable() ? "Share Selected Player Data Between A Group Of Servers." : "Install ReSync To Access This Feature", "add.png", () -> openPlayerGroup(null));
        addPlayerGroup.setActive(reSyncAvailable());
        playerData.addRow("add", "", addPlayerGroup);
        AnimatedButton playerDataEmpty = inactive("Player Data Stays On Each Server", ThemeManager.getDefaultAccent());
        playerDataEmpty.setVisible(syncRealms.isEmpty());
        playerData.addRow("empty", "", playerDataEmpty);
        for (SyncRealm realm : syncRealms) {
            MountableButtonWidget row = playerDataRow(realm);
            playerDataRows.put(realm.id(), row);
            playerData.addRow("realm:" + realm.id(), "", row);
        }
        playerDataSetting = playerData.build();
        playerDataSetting.setRowVisibility("empty", syncRealms.isEmpty());
        container.addWidget(playerDataSetting);
        requestLayout(container);
    }

    private void populateSettingsTab(Container container) {
        Setting.Builder identity = new Setting.Builder("Network Name");
        networkNameInput = new TextInputWidget.Builder().text(network.name()).placeholder("Network Name").maxLength(64).build();
        identity.addRow("name", "Name", networkNameInput);
        container.addWidget(identity.build());

        Setting.Builder maintenance = new Setting.Builder("Maintenance");
        MountableButtonWidget reapply = actionRow("Reapply Network Settings", "Restore the saved proxy, server, and ReSync settings when files were changed outside Remotely.", "reload.png", this::reconcile);
        reapply.setActive(networkSupports("reconcile"));
        maintenance.addRow("reapply", "", reapply);
        MountableButtonWidget entry = actionRow("Test Player Entry", "Verify that the proxy can send players to the configured servers.", "checkmark.png", this::runPreflight);
        entry.setActive(networkSupports("preflight"));
        maintenance.addRow("entry", "", entry);
        MountableButtonWidget key = actionRow("Replace Connection Key", "Create a new private key shared by the proxy and managed servers. Use this if the current key may have been exposed.", "shades.png", this::prepareSecretRotation);
        key.setActive(networkSupports("secretRotation"));
        maintenance.addRow("key", "", key);
        container.addWidget(maintenance.build());

        Setting.Builder runtime = new Setting.Builder("Network Commands");
        if (networkSupports("command") || networkSupports("broadcast")) {
            if (networkSupports("command")) {
                TextInputWidget command = new TextInputWidget.Builder().placeholder("Run A Proxy Command").maxLength(2048).build();
                runtime.addRow("command", "Proxy", command, inlineAction("Run", "terminal.png", () -> executeProxyCommand(command)));
            }
            if (networkSupports("broadcast")) {
                TextInputWidget broadcast = new TextInputWidget.Builder().placeholder("Message Every Player").maxLength(8192).build();
                runtime.addRow("broadcast", "Players", broadcast, inlineAction("Send", "chat.png", () -> broadcastMessage(broadcast)));
            }
        } else {
            MountableButtonWidget install = actionRow("Install ReSync", "Install ReSync To Access Live Network Commands, Player Controls, Sharing, And Status.", "download.png", this::installNetworkReSync);
            install.setActive(networkSupports("resync"));
            runtime.addRow("install", "", install);
        }
        container.addWidget(runtime.build());

        Setting.Builder safety = new Setting.Builder("Network Removal");
        MountableButtonWidget dissolve = actionRow("Dissolve Network", "Restore every managed server to independent operation and remove this network without deleting server files or worlds.", "delete.png", ThemeManager.getAccent("danger"), this::openDissolvePopup);
        dissolve.setActive(networkSupports("dissolve"));
        safety.addRow("dissolve", "", dissolve);
        container.addWidget(safety.build());
        requestLayout(container);
    }

    private void loadSharingDraft() {
        sharedChat = network.featureEnabled(NetworkDefinition.FEATURE_SHARED_CHAT);
        sharedResources = network.featureEnabled(NetworkDefinition.FEATURE_SHARED_RESOURCES);
        NetworkSharedDataPolicy policy = network.sharedDataPolicy();
        chatChannelMode = policy.chatChannelMode();
        chatChannels = String.join(", ", policy.chatChannels());
        chatRetentionMillis = policy.chatRetentionMillis();
        resourceTypeMode = policy.resourceTypeMode();
        resourceTypes = String.join(", ", policy.resourceTypes());
        pathSyncs = policy.pathSyncs();
        resourceConflictPolicy = policy.resourceConflictPolicy();
        maximumPayloadBytes = policy.maximumPayloadBytes();
    }

    private void addSharingOption(Setting.Builder setting, ConfigOption<?> option) {
        sharingOptions.add(option);
        setting.addOption(option);
    }

    private void openPathSync(NetworkPathSync existing) {
        boolean[] enabled = {existing == null || existing.enabled()};
        String[] name = {existing == null ? "" : existing.name()};
        String[] servers = {existing == null ? displayNames(pathSyncServerMembers().stream().map(NetworkMember::nodeId).toList()) : displayNames(existing.nodeIds())};
        String[] paths = {existing == null ? "" : String.join(", ", existing.paths())};
        String[] commands = {existing == null ? "" : String.join("\n", existing.commands())};
        NetworkSharedDataPolicy.ConflictPolicy[] conflict = {existing == null ? NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS : existing.conflictPolicy()};
        PopupWidget[] popup = new PopupWidget[1];
        Runnable save = () -> {
            try {
                NetworkPathSync updated = buildPathSync(existing, name[0], enabled[0], servers[0], paths[0], conflict[0], commands[0]);
                List<NetworkPathSync> draft = new ArrayList<>(pathSyncs);
                if (existing == null) {
                    draft.add(updated);
                } else {
                    draft.set(draft.indexOf(existing), updated);
                }
                validatePathSyncDraft(draft);
                pathSyncs = List.copyOf(draft);
                popup[0].hide();
                syncPathSyncRows();
            } catch (RuntimeException exception) {
                new Notification("Path Sync Invalid", rootMessage(exception), Notification.Type.ERROR);
            }
        };
        PopupWidget.Builder builder = new PopupWidget.Builder(existing == null ? "Add Path Sync" : "Edit " + existing.name()).width(360).setResizable(true).setExpandWithDropdowns(true).onClose(() -> popup[0].hide());
        if (existing != null) {
            builder.addTitleAction("Delete", () -> {
                pathSyncs = pathSyncs.stream().filter(sync -> !sync.id().equals(existing.id())).toList();
                popup[0].hide();
                syncPathSyncRows();
            }, "Delete Path Sync", PopupWidget.TitleActionRole.DESTRUCTIVE);
        }
        builder.addTitleAction(existing == null ? "Add" : "Update", save, existing == null ? "Add Path Sync" : "Update Path Sync", PopupWidget.TitleActionRole.PRIMARY);
        builder.addToggle("Enabled", "Pause This Sync Without Deleting Its Files, Servers, Or Commands.", enabled[0], value -> enabled[0] = value);
        TextInputWidget nameInput = new TextInputWidget.Builder().text(name[0]).placeholder("LuckPerms").maxLength(64).onChange(value -> name[0] = value).build();
        TextInputWidget serversInput = new TextInputWidget.Builder().text(servers[0]).placeholder("Lobby, Creative").onChange(value -> servers[0] = value).build();
        TextInputWidget pathsInput = new TextInputWidget.Builder().text(paths[0]).placeholder("config, plugins/LuckPerms, server.properties").onChange(value -> paths[0] = value).build();
        builder.addRow(new PopupWidget.PopupRow.Builder("Name", nameInput).description("Use A Short Name That Explains What This Sync Keeps Identical.").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Servers", serversInput).description("Choose At Least Two Server Names, Separated With Commas. Only These Servers Share This Entry.").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Files And Folders", pathsInput).description("Enter Paths From The Server Folder, Separated With Commas. Use A Period For The Entire Server Folder.").build());
        builder.addScrollSelector("When Both Changed", "Network Wins Uses The Last Accepted Shared Copy. Local Wins Publishes This Server's Copy When Both Sides Changed.", Arrays.stream(NetworkSharedDataPolicy.ConflictPolicy.values()).map(this::conflictPolicyLabel).toList(), conflict[0].ordinal(), index -> conflict[0] = NetworkSharedDataPolicy.ConflictPolicy.values()[index]);
        TextAreaWidget commandsInput = new TextAreaWidget.Builder().text(commands[0]).placeholder("luckperms reloadconfig\nwhitelist reload").wordWrap(false).size(320, 72).onChange(value -> commands[0] = value).build();
        builder.addRow(new PopupWidget.PopupRow.Builder("Commands After Sync", commandsInput).description("Optional Console Commands, One Per Line And Without A Slash. They Run Only On Servers That Received File Changes, After The Files Settle.").build());
        popup[0] = showPopup(builder.build());
    }

    private NetworkPathSync buildPathSync(NetworkPathSync existing, String rawName, boolean enabled, String rawServers, String rawPaths, NetworkSharedDataPolicy.ConflictPolicy conflict, String rawCommands) {
        String id = existing == null ? "sync-" + UUID.randomUUID() : existing.id();
        String name = rawName == null || rawName.isBlank() ? "File Sync" : rawName.trim();
        Map<String, NetworkMember> members = new LinkedHashMap<>();
        for (NetworkMember member : pathSyncServerMembers()) {
            members.put(displayName(member).toLowerCase(Locale.ROOT), member);
            members.putIfAbsent(member.routeName().toLowerCase(Locale.ROOT), member);
        }
        Set<String> nodeIds = commaValues(rawServers).stream().map(value -> {
            NetworkMember member = members.get(value.toLowerCase(Locale.ROOT));
            if (member == null) {
                throw new IllegalArgumentException("Unknown ReSync Server " + value);
            }
            return member.nodeId();
        }).collect(Collectors.toCollection(LinkedHashSet::new));
        if (enabled && nodeIds.size() < 2) {
            throw new IllegalArgumentException("Choose at least two servers");
        }
        List<String> commands = TextLines.stream(rawCommands).map(String::trim).filter(value -> !value.isBlank()).toList();
        return new NetworkPathSync(id, name, enabled, nodeIds, commaSet(rawPaths), conflict, commands);
    }

    private List<NetworkMember> pathSyncServerMembers() {
        return network.members().stream().filter(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()).toList();
    }

    private void validatePathSyncDraft(List<NetworkPathSync> draft) {
        new NetworkSharedDataPolicy(NetworkSharedDataPolicy.SelectionMode.ALL, Set.of(), 120_000, NetworkSharedDataPolicy.SelectionMode.ALL, Set.of(), draft, NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS, NetworkSharedDataPolicy.DEFAULT_MAXIMUM_PAYLOAD_BYTES);
    }

    private void openPlayerGroup(SyncRealm existing) {
        if (existing != null) {
            PlayerGroupEditor opened = playerGroupEditors.values().stream().filter(editor -> editor.id.equals(existing.id())).findFirst().orElse(null);
            if (opened != null) {
                tabs().setActiveTab(opened.container);
                return;
            }
        }
        String id = existing == null ? nextPlayerGroupId() : existing.id();
        ConfigOption<String> name = groupOption("Name", "Name This Group Of Shared Player Settings", existing == null ? "Shared Players" : existing.name()).build();
        ConfigOption<String> pluginData = groupOption("Namespaces", "Plugin Namespaces To Share, Separated By Commas", existing == null ? "" : String.join(", ", existing.persistentDataNamespaces())).build();
        ConfigOption<String> snapshots = groupOption("Recovery Copies", "Recent Copies To Keep For Each Player", Integer.toString(existing == null ? 20 : existing.retainedSnapshots())).build();
        ConfigOption<String> days = groupOption("Recovery Days", "Days To Keep A Recovery Copy Before It Expires", Integer.toString(existing == null ? 30 : existing.retentionDays())).build();
        ConfigOption<SyncLocationPolicy> location = groupOption("Return Location", "Where The Player Returns When Moving Between Servers", existing == null ? SyncLocationPolicy.NEVER : existing.locationPolicy())
            .options(List.of(SyncLocationPolicy.values())).display(this::locationLabel).build();
        Map<String, ConfigOption<Boolean>> servers = new LinkedHashMap<>();
        Map<SyncDataFamily, ConfigOption<Boolean>> families = new LinkedHashMap<>();
        Set<SyncDataFamily> initialFamilies = existing == null ? Set.of(SyncDataFamily.PRESENCE) : existing.dataFamilies();
        for (SyncDataFamily family : SyncDataFamily.values()) {
            families.put(family, groupOption(playerDataLabel(family), playerDataDescription(family), initialFamilies.contains(family)).build());
        }
        Setting.Builder membership = new Setting.Builder("Servers");
        for (NetworkMember member : network.members()) {
            if (member.isProxy()) continue;
            ConfigOption<Boolean> included = groupOption(displayName(member), "Include This Server In The Player Group", existing != null && existing.nodeIds().contains(member.nodeId())).build();
            servers.put(member.nodeId(), included);
            membership.addOption(included);
        }
        List<Setting> sections = new ArrayList<>();
        sections.add(new Setting.Builder("Group").addOption(name).addOption(families.get(SyncDataFamily.PRESENCE)).build());
        sections.add(membership.build());
        sections.add(groupSection("Inventory", families, SyncDataFamily.INVENTORY, SyncDataFamily.ENDER_CHEST));
        sections.add(groupSection("Player State", families, SyncDataFamily.EXPERIENCE, SyncDataFamily.VITALS, SyncDataFamily.EFFECTS, SyncDataFamily.PLAYER_STATE));
        sections.add(groupSection("Progress", families, SyncDataFamily.ADVANCEMENTS, SyncDataFamily.RECIPES, SyncDataFamily.STATISTICS));
        sections.add(new Setting.Builder("Location").addOption(families.get(SyncDataFamily.LOCATION)).addOption(location).build());
        sections.add(new Setting.Builder("Plugin Data").addOption(families.get(SyncDataFamily.PERSISTENT_DATA)).addOption(pluginData).build());
        sections.add(new Setting.Builder("Recovery").addOption(snapshots).addOption(days).build());
        Container editor = createContainer(6, 60, width - 12, Math.max(1, height - 66))
            .columns(1).padding(4).verticalSpacing(6).layout(new ManagedLayout()).scrolling(true).backgroundDrawing(false);
        SearchMode search = new SearchMode(false);
        search.setPlaceholder("Search Player Settings");
        search.setOnTextChange(query -> {
            sections.forEach(section -> section.filter(query));
            editor.updateWidgetPositions();
            editor.resetScroll();
        });
        editor.setSearchMode(search);
        for (Setting section : sections) {
            section.fitContentHeight();
            editor.addWidget(section);
        }
        PlayerGroupEditor groupEditor = new PlayerGroupEditor(id, editor, search, name, pluginData, snapshots, days, location, servers, families);
        playerGroupEditors.put(editor, groupEditor);
        groupEditor.tab = tabs().addTab(existing == null ? "New Player Group" : existing.name(), editor, "ReSync.png");
        groupEditor.tab.setData(groupEditor);
        tabs().setActiveTab(editor);
        editor.updateWidgetPositions();
        updatePlayerGroupHeader();
    }

    private <T> ConfigOption.Builder<T> groupOption(String name, String description, T value) {
        return ConfigOption.<T>builder(name).description(description).bind(() -> value, null).defaultValue(value).resettable(false);
    }

    private Setting groupSection(String title, Map<SyncDataFamily, ConfigOption<Boolean>> options, SyncDataFamily... families) {
        Setting.Builder section = new Setting.Builder(title);
        for (SyncDataFamily family : families) section.addOption(options.get(family));
        return section.build();
    }

    private void selectNetworkTab(TabsManager.Tab tab) {
        if (tab == null) return;
        setActiveContainer(tab.getContainer());
        updatePlayerGroupHeader();
    }

    private void updatePlayerGroupHeader() {
        if (deletePlayerGroupButton == null) return;
        TabsManager.Tab active = tabs().getActiveTab();
        PlayerGroupEditor editor = active == null ? null : playerGroupEditors.get(active.getContainer());
        boolean visible = editor != null && syncRealms.stream().anyMatch(realm -> realm.id().equals(editor.id));
        if (deletePlayerGroupButton.visible != visible) {
            deletePlayerGroupButton.visible = visible;
            if (visible) deletePlayerGroupButton.resetEntranceAnimation();
            header().requestLayoutUpdate();
        }
    }

    private void deleteActivePlayerGroup() {
        TabsManager.Tab active = tabs().getActiveTab();
        PlayerGroupEditor editor = active == null ? null : playerGroupEditors.get(active.getContainer());
        if (editor == null) return;
        syncRealms = syncRealms.stream().filter(realm -> !realm.id().equals(editor.id)).toList();
        syncPlayerDataRows();
        int index = tabs().getTabs().indexOf(active);
        if (index >= 0) tabs().removeTabRaw(index);
        closePlayerGroupEditor(editor.container);
    }

    private void closePlayerGroupEditor(Container container) {
        PlayerGroupEditor editor = playerGroupEditors.remove(container);
        if (editor != null) editor.close();
        updatePlayerGroupHeader();
    }

    private SyncRealm buildPlayerGroup(String id, String rawName, Set<String> nodes, Set<SyncDataFamily> families, SyncLocationPolicy location, String rawPluginData, String rawSnapshots, String rawDays) {
        String name = rawName == null || rawName.isBlank() ? "Shared Players" : rawName.trim();
        if (nodes.size() < 2) {
            throw new IllegalArgumentException("Choose At Least Two Servers");
        }
        if (families.isEmpty()) {
            throw new IllegalArgumentException("Choose At Least One Kind Of Player Information");
        }
        int retainedSnapshots = Integer.parseInt(rawSnapshots.trim());
        int retentionDays = Integer.parseInt(rawDays.trim());
        Set<String> namespaces = commaValues(rawPluginData).stream().collect(Collectors.toCollection(LinkedHashSet::new));
        return new SyncRealm(id, name, nodes, families, location, namespaces, retainedSnapshots, retentionDays);
    }

    private String nextPlayerGroupId() {
        int index = 1;
        while (true) {
            String candidate = "players-" + index;
            boolean used = syncRealms.stream().anyMatch(realm -> realm.id().equals(candidate))
                || playerGroupEditors.values().stream().anyMatch(editor -> editor.id.equals(candidate));
            if (!used) return candidate;
            index++;
        }
    }

    private String playerDataLabel(SyncDataFamily family) {
        return switch (family) {
            case PRESENCE -> "Online Presence";
            case INVENTORY -> "Inventory";
            case ENDER_CHEST -> "Ender Chest";
            case EXPERIENCE -> "Experience";
            case VITALS -> "Health And Hunger";
            case EFFECTS -> "Potion Effects";
            case PLAYER_STATE -> "Player State";
            case ADVANCEMENTS -> "Advancements";
            case RECIPES -> "Discovered Recipes";
            case STATISTICS -> "Statistics";
            case LOCATION -> "Location";
            case PERSISTENT_DATA -> "Plugin Data";
        };
    }

    private String playerDataDescription(SyncDataFamily family) {
        return switch (family) {
            case PRESENCE -> "Share Which Server Each Player Is Connected To";
            case INVENTORY -> "Share Inventory, Armor, Offhand, And The Selected Hotbar Slot";
            case ENDER_CHEST -> "Keep The Same Ender Chest Contents On Every Server In This Player Group";
            case EXPERIENCE -> "Share Experience Points And Levels";
            case VITALS -> "Share Health, Hunger, Air, And Other Survival Values";
            case EFFECTS -> "Carry Active Potion And Status Effects To The Next Server";
            case PLAYER_STATE -> "Share The Player's Other General State Between Servers";
            case ADVANCEMENTS -> "Share Advancement Progress And Completed Criteria";
            case RECIPES -> "Share Discovered Crafting Recipes";
            case STATISTICS -> "Share The Player's Minecraft Statistics Across These Servers";
            case LOCATION -> "Remember Player Locations Using The Selected Return Policy";
            case PERSISTENT_DATA -> "Share The Player Data Belonging To The Listed Plugin Namespaces";
        };
    }

    private String locationLabel(SyncLocationPolicy policy) {
        return switch (policy) {
            case NEVER -> "Keep Location Local";
            case SAME_SERVER_ONLY -> "Return On Same Server";
            case REALM_RETURN_POINT -> "Use Group Return Point";
            case EXACT_COMPATIBLE_WORLD -> "Follow Compatible World";
        };
    }

    private Set<String> commaSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String displayNames(Iterable<String> nodeIds) {
        List<String> names = new ArrayList<>();
        nodeIds.forEach(nodeId -> names.add(member(nodeId).map(this::displayName).orElse("Missing Server")));
        return String.join(", ", names);
    }

    private Optional<NetworkMember> member(String nodeId) {
        return network.members().stream().filter(member -> member.nodeId().equals(nodeId)).findFirst();
    }

    private Optional<NetworkMember> findMember(String name) {
        String normalized = name == null ? "" : name.trim();
        return network.members().stream().filter(member -> !member.isProxy()).filter(member -> displayName(member).equalsIgnoreCase(normalized) || member.routeName().equalsIgnoreCase(normalized)).findFirst();
    }

    private List<String> commaValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private PopupWidget showPopup(PopupWidget popup) {
        popup.setX((width - popup.getWidth()) / 2);
        popup.setY((height - popup.getHeight()) / 2);
        addDrawableChild(popup);
        popup.show();
        return popup;
    }

    private String selectionModeLabel(NetworkSharedDataPolicy.SelectionMode mode) {
        return switch (mode) {
            case ALL -> "All";
            case ALLOW_LIST -> "Only Listed";
            case DENY_LIST -> "All Except Listed";
        };
    }

    private String conflictPolicyLabel(NetworkSharedDataPolicy.ConflictPolicy policy) {
        return policy == NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS ? "Network Wins" : "Local Wins";
    }

    private String durationLabel(long millis) {
        if (millis % 86_400_000 == 0) {
            long days = millis / 86_400_000;
            return days + (days == 1 ? " Day" : " Days");
        }
        if (millis % 3_600_000 == 0) {
            long hours = millis / 3_600_000;
            return hours + (hours == 1 ? " Hour" : " Hours");
        }
        long minutes = Math.max(1, millis / 60_000);
        return minutes + (minutes == 1 ? " Minute" : " Minutes");
    }

    private String sizeLabel(int bytes) {
        return bytes % 1_048_576 == 0 ? bytes / 1_048_576 + " MB" : bytes / 1_024 + " KB";
    }

    private IconButton inlineAction(String label, String icon, Runnable action) {
        return inlineAction(label, icon, ThemeManager.getDefaultAccent(), action);
    }

    private IconButton inlineAction(String label, String icon, Accent accent, Runnable action) {
        return new IconButton.Builder().label(label).imagePath(icon).accentType(accent).onClick(action).build();
    }

    private MountableButtonWidget actionRow(String label, String description, String icon, Runnable action) {
        return actionRow(label, description, icon, ThemeManager.getDefaultAccent(), action);
    }

    private MountableButtonWidget actionRow(String label, String description, String icon, Accent accent, Runnable action) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(label).description(description).iconPath(icon).onClick(action).build();
        row.setAccent(accent);
        row.setSize(220, 30);
        return row;
    }

    private AnimatedButton inactive(String label, Accent accent) {
        return new AnimatedButton.Builder().label(label).accentType(accent).active(false).build();
    }

    private MountableButtonWidget compactRow(String label, String hint, Accent accent) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(label).build();
        row.setHint(hint);
        row.setAccent(accent);
        row.setSize(220, 20);
        return row;
    }

    private MountableButtonWidget memberRow(Container container, NetworkMember member, NetworkRuntimeSnapshot runtime) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(displayName(member)).icon(memberIcon(member)).build();
        String nodeId = member.nodeId();
        row.setOnClick(() -> currentMember(nodeId).ifPresent(this::openMember));
        memberActions.put(nodeId, mountMemberActions(row, member));
        updateMemberRow(container, row, member, runtime);
        loadMemberIcon(member, row);
        return row;
    }

    private MemberActions mountMemberActions(MountableButtonWidget row, NetworkMember member) {
        String nodeId = member.nodeId();
        row.addMountedWidget(rowAction("terminal.png", "Open Terminal", () -> currentMember(nodeId).ifPresent(this::openMember)));
        SquareButtonWidget acceptPlayers = null;
        SquareButtonWidget drainServer = null;
        SquareButtonWidget maintenanceMode = null;
        if (!member.isProxy() && member.isManaged()) {
            acceptPlayers = rowAction("checkmark.png", "Accept Players", () -> currentMember(nodeId).ifPresent(current -> setRuntimeMode(current, NetworkRuntimeNodeStatus.ONLINE)));
            drainServer = rowAction("close.png", "Drain Server", () -> currentMember(nodeId).ifPresent(current -> setRuntimeMode(current, NetworkRuntimeNodeStatus.DRAINING)));
            maintenanceMode = rowAction("hide.png", "Maintenance Mode", () -> currentMember(nodeId).ifPresent(current -> setRuntimeMode(current, NetworkRuntimeNodeStatus.MAINTENANCE)));
            row.addMountedWidget(acceptPlayers);
            row.addMountedWidget(drainServer);
            row.addMountedWidget(maintenanceMode);
        }
        SquareButtonWidget installReSync = null;
        if (member.isManaged()) {
            installReSync = rowAction("download.png", "Install ReSync", this::installNetworkReSync, ThemeManager.getAccent("nice"));
            row.addMountedWidget(installReSync);
        }
        if (!member.isProxy()) {
            row.addMountedWidget(rowAction("unmerge.png", "Detach", () -> currentMember(nodeId).ifPresent(current -> confirmDetach(current, instancesById.get(current.instanceId()))), ThemeManager.getAccent("danger")));
        }
        return new MemberActions(acceptPlayers, drainServer, maintenanceMode, installReSync);
    }

    private void updateMemberRow(Container container, MountableButtonWidget row, NetworkMember member, NetworkRuntimeSnapshot runtime) {
        NetworkOverviewProvider.ServerView instance = instancesById.get(member.instanceId());
        NetworkRuntimeNodePresence presence = runtime == null ? null : runtime.node(member.nodeId()).orElse(null);
        boolean connected = runtime != null && runtime.connected();
        boolean installed = reSyncInstalled.getOrDefault(member.instanceId(), false);
        String status;
        String capacity = "";
        if (member.isManaged() && !installed) {
            status = "ReSync Not Installed";
        } else if (member.isManaged() && (!member.resyncEnabled() || !network.runtime().enabled())) {
            status = "ReSync Disabled";
        } else if (member.isProxy()) {
            status = connected ? "Online" : runtime == null ? "Unavailable" : titleCase(runtime.state().name());
        } else if (presence == null) {
            status = member.resyncEnabled() ? "Unavailable" : instance == null && member.isManaged() ? "Missing" : "Configured";
        } else {
            status = titleCase(presence.status().name());
            capacity = " • " + presence.players() + (presence.capacity() > 0 ? "/" + presence.capacity() : "") + " Players";
        }
        String management = member.isProxy() ? "Proxy" : member.isManaged() ? "Server" : "External Server";
        row.setName(displayName(member));
        row.setIcon(memberIcon(member));
        row.setDescription(management + capacity);
        row.setHiddenText(status);
        updateMemberActions(member, runtime, presence, installed);
        styleRow(container, row, memberAccent(member, presence, instance), 30);
    }

    private void updateMemberActions(NetworkMember member, NetworkRuntimeSnapshot runtime, NetworkRuntimeNodePresence presence, boolean installed) {
        MemberActions actions = memberActions.get(member.nodeId());
        if (actions == null) {
            return;
        }
        boolean available = networkSupports("runtimeControl") && installed && member.resyncEnabled() && network.runtime().enabled() && runtime != null && runtime.connected() && presence != null;
        String unavailableHint = !networkSupports("runtimeControl") ? "Runtime Controls Are Unavailable" : !installed || !member.resyncEnabled() || !network.runtime().enabled() ? "Install ReSync To Access This Feature" : "ReSync Is Connecting";
        updateRuntimeAction(actions.acceptPlayers(), "Accept Players", unavailableHint, available);
        updateRuntimeAction(actions.drainServer(), "Drain Server", unavailableHint, available);
        updateRuntimeAction(actions.maintenanceMode(), "Maintenance Mode", unavailableHint, available);
        if (actions.installReSync() != null) {
            boolean installAvailable = networkSupports("resync") && (!installed || !member.resyncEnabled() || !network.runtime().enabled());
            actions.installReSync().setVisible(installAvailable);
            actions.installReSync().setActive(installAvailable);
            actions.installReSync().setHint(installAvailable ? "Install ReSync" : "ReSync Installed");
        }
    }

    private void updateRuntimeAction(SquareButtonWidget action, String availableHint, String unavailableHint, boolean available) {
        if (action == null) {
            return;
        }
        action.setActive(available);
        action.setHint(available ? availableHint : unavailableHint);
    }

    private Optional<NetworkMember> currentMember(String nodeId) {
        return network.members().stream().filter(member -> member.nodeId().equals(nodeId)).findFirst();
    }

    private String displayName(NetworkMember member) {
        if (member == null) {
            return "Unavailable";
        }
        NetworkOverviewProvider.ServerView instance = instancesById.get(member.instanceId());
        if (instance != null && instance.name() != null && !instance.name().isBlank()) {
            return instance.name();
        }
        String route = member.routeName().replace('-', ' ').trim();
        return route.isBlank() ? member.isProxy() ? "Proxy" : "External Server" : titleCase(route);
    }

    private String memberState(NetworkMember member) {
        if (member == null || !member.isManaged()) {
            return null;
        }
        NetworkOverviewProvider.ServerView instance = instancesById.get(member.instanceId());
        return instance == null ? null : instance.state();
    }

    private String networkState() {
        List<String> states = network.members().stream().filter(NetworkMember::isManaged).map(this::memberState).filter(state -> state != null).toList();
        if (states.stream().anyMatch(state -> state.equals("STARTING"))) {
            return "STARTING";
        }
        if (states.stream().anyMatch(state -> state.equals("STOPPING"))) {
            return "STOPPING";
        }
        if (states.stream().anyMatch(state -> state.equals("RUNNING") || state.equals("SAVED") || state.equals("SAVING"))) {
            return "RUNNING";
        }
        if (states.stream().anyMatch(state -> state.equals("CRASHED"))) {
            return "CRASHED";
        }
        return "STOPPED";
    }

    private void updateNetworkPowerButton() {
        if (networkPowerButton == null || network == null) {
            return;
        }
        networkPowerButton.setActive(networkSupports("lifecycle"));
        AnimatedWidget saveButton = header().getButtonByImagePath("save.png");
        if (saveButton != null) saveButton.setActive(networkSupports("save"));
        networkPowerButton.updateState(networkState());
        header().requestLayoutUpdate();
    }

    private void toggleNetworkPower() {
        if (network == null) {
            return;
        }
        lifecycle(canStart(networkState()) ? NetworkLifecycleOperation.START : NetworkLifecycleOperation.STOP);
    }

    private void toggleMemberPower(NetworkMember member) {
        if (applyingNetworkChange || member == null || !member.isManaged()) {
            return;
        }
        if (!requireNetworkCapability("memberLifecycle")) return;
        NetworkOverviewProvider.ServerView instance = instancesById.get(member.instanceId());
        if (instance == null) {
            new Notification("Server Unavailable", displayName(member), Notification.Type.ERROR);
            return;
        }
        NetworkLifecycleOperation operation = canStart(instance.state()) ? NetworkLifecycleOperation.START : NetworkLifecycleOperation.STOP;
        applyingNetworkChange = true;
        Notification notification = operationNotification(operation == NetworkLifecycleOperation.START ? "Starting Server" : "Stopping Server", displayName(member));
        provider.memberLifecycle(networkId, member.nodeId(), operation).whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
            applyingNetworkChange = false;
            if (throwable != null || job == null || job.status() != NetworkLifecycleStatus.SUCCEEDED) {
                notification.update().message("Server Action Failed").description(throwable == null ? job == null ? "The server action did not finish" : job.message() : rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            } else {
                notification.update().message(operation == NetworkLifecycleOperation.START ? "Server Started" : "Server Stopped").description(displayName(member)).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            }
            refresh();
        }));
    }

    private Accent memberAccent(NetworkMember member, NetworkRuntimeNodePresence presence, NetworkOverviewProvider.ServerView instance) {
        if (member.isManaged() && instance == null) {
            return ThemeManager.getAccent("danger");
        }
        if (presence != null && (presence.status() == NetworkRuntimeNodeStatus.DRAINING || presence.status() == NetworkRuntimeNodeStatus.MAINTENANCE)) {
            return ThemeManager.getDefaultAccent();
        }
        if (presence != null && presence.status() == NetworkRuntimeNodeStatus.REVOKED) {
            return ThemeManager.getAccent("danger");
        }
        return ThemeManager.getDefaultAccent();
    }

    private Identifier memberIcon(NetworkMember member) {
        NetworkOverviewProvider.ServerView instance = instancesById.get(member.instanceId());
        String icon = instance == null ? "" : instance.icon();
        return iconIdentifier(icon.isBlank() ? member.isProxy() ? "network.png" : "server.png" : icon);
    }

    private void loadMemberIcon(NetworkMember member, MountableButtonWidget row) {
        NetworkOverviewProvider.ServerView instance = instancesById.get(member.instanceId());
        if (instance != null && !instance.icon().isBlank()) {
            Identifier icon = iconIdentifier(instance.icon());
            row.setIcon(icon);
            if (topologyWidget != null) {
                topologyWidget.setMemberIcon(member.nodeId(), icon);
            }
        }
    }

    private String serverSearchText(NetworkMember member) {
        return (displayName(member) + " " + member.routeName() + " " + member.address()).toLowerCase(Locale.ROOT);
    }

    private Identifier iconIdentifier(String value) {
        return value != null && value.contains(":") ? Identifier.of(value) : Identifier.icon(value == null || value.isBlank() ? "unknown.png" : value);
    }

    private void syncServerRows() {
        if (serversSetting == null) {
            return;
        }
        Set<String> desired = network.members().stream().map(NetworkMember::nodeId).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String nodeId : new ArrayList<>(serverRows.keySet())) {
            if (desired.contains(nodeId)) {
                continue;
            }
            serverRows.remove(nodeId);
            memberActions.remove(nodeId);
            serversSetting.removeRow("server:" + nodeId);
        }
        for (NetworkMember member : network.members()) {
            MountableButtonWidget row = serverRows.get(member.nodeId());
            if (row == null) {
                row = memberRow(serversContainer, member, runtimeSnapshot);
                serverRows.put(member.nodeId(), row);
                serversSetting.addRow("server:" + member.nodeId(), "", row);
            } else {
                updateMemberRow(serversContainer, row, member, runtimeSnapshot);
            }
            serversSetting.setRowVisibility("server:" + member.nodeId(), serverSearchQuery.isBlank() || serverSearchText(member).contains(serverSearchQuery));
        }
        requestLayout(serversContainer);
    }

    private MountableButtonWidget routingRow(RoutingGroup group) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(group.name()).build();
        row.setOnClick(() -> openRoutingGroup(routingGroups.stream().filter(candidate -> candidate.id().equals(group.id())).findFirst().orElse(null)));
        row.addMountedWidget(rowAction("edit.png", "Edit Join Rule", () -> openRoutingGroup(routingGroups.stream().filter(candidate -> candidate.id().equals(group.id())).findFirst().orElse(null))));
        updateRoutingRow(row, group);
        return row;
    }

    private void updateRoutingRow(MountableButtonWidget row, RoutingGroup group) {
        String servers = displayNames(group.nodeIds());
        row.setName(group.name());
        row.setDescription(servers.isBlank() ? "No Servers Selected" : servers);
        row.setHiddenText(routingStrategyLabel(group.strategy()));
        row.setAccent(ThemeManager.getDefaultAccent());
        row.setSize(Math.max(220, serversContainer.getEffectiveWidth() - 10), 30);
    }

    private void syncRoutingRows() {
        if (routingSetting == null) {
            return;
        }
        Set<String> desired = routingGroups.stream().map(RoutingGroup::id).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String id : new ArrayList<>(routingRows.keySet())) {
            if (desired.contains(id)) {
                continue;
            }
            routingRows.remove(id);
            routingSetting.removeRow("rule:" + id);
        }
        for (RoutingGroup group : routingGroups) {
            MountableButtonWidget row = routingRows.get(group.id());
            if (row == null) {
                row = routingRow(group);
                routingRows.put(group.id(), row);
                routingSetting.addRow("rule:" + group.id(), "", row);
            } else {
                updateRoutingRow(row, group);
            }
        }
        routingSetting.setRowVisibility("empty", routingGroups.isEmpty());
        requestLayout(serversContainer);
    }

    private MountableButtonWidget playerDataRow(SyncRealm realm) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(realm.name()).build();
        row.setOnClick(() -> openPlayerGroup(syncRealms.stream().filter(candidate -> candidate.id().equals(realm.id())).findFirst().orElse(null)));
        row.addMountedWidget(rowAction("edit.png", "Edit Player Data", () -> openPlayerGroup(syncRealms.stream().filter(candidate -> candidate.id().equals(realm.id())).findFirst().orElse(null))));
        updatePlayerDataRow(row, realm);
        return row;
    }

    private void updatePlayerDataRow(MountableButtonWidget row, SyncRealm realm) {
        String families = realm.dataFamilies().stream().map(value -> titleCase(value.name())).collect(Collectors.joining(", "));
        row.setName(realm.name());
        row.setDescription(displayNames(realm.nodeIds()));
        row.setHiddenText(families);
        row.setAccent(ThemeManager.getDefaultAccent());
        row.setSize(Math.max(220, sharingContainer.getEffectiveWidth() - 10), 30);
    }

    private void syncPlayerDataRows() {
        if (playerDataSetting == null) {
            return;
        }
        Set<String> desired = syncRealms.stream().map(SyncRealm::id).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String id : new ArrayList<>(playerDataRows.keySet())) {
            if (desired.contains(id)) {
                continue;
            }
            playerDataRows.remove(id);
            playerDataSetting.removeRow("realm:" + id);
        }
        for (SyncRealm realm : syncRealms) {
            MountableButtonWidget row = playerDataRows.get(realm.id());
            if (row == null) {
                row = playerDataRow(realm);
                playerDataRows.put(realm.id(), row);
                playerDataSetting.addRow("realm:" + realm.id(), "", row);
            } else {
                updatePlayerDataRow(row, realm);
            }
        }
        playerDataSetting.setRowVisibility("empty", syncRealms.isEmpty());
        requestLayout(sharingContainer);
    }

    private MountableButtonWidget pathSyncRow(NetworkPathSync sync) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(sync.name()).build();
        row.setOnClick(() -> openPathSync(pathSyncs.stream().filter(candidate -> candidate.id().equals(sync.id())).findFirst().orElse(null)));
        row.addMountedWidget(rowAction("edit.png", "Edit Path Sync", () -> openPathSync(pathSyncs.stream().filter(candidate -> candidate.id().equals(sync.id())).findFirst().orElse(null))));
        row.addMountedWidget(new ToggleWidget.Builder().label("").toggled(sync.enabled()).size(34, 18).onChange(value -> setPathSyncEnabled(sync.id(), value)).build());
        updatePathSyncRow(row, sync);
        return row;
    }

    private void updatePathSyncRow(MountableButtonWidget row, NetworkPathSync sync) {
        String paths = String.join(", ", sync.paths());
        row.setName(sync.name());
        row.setDescription(paths.isBlank() ? "No Files Selected" : paths);
        row.setHiddenText((sync.enabled() ? "Enabled" : "Paused") + " • " + displayNames(sync.nodeIds()) + (sync.commands().isEmpty() ? "" : " • " + sync.commands().size() + " Commands"));
        row.setAccent(sync.enabled() ? ThemeManager.getDefaultAccent() : ThemeManager.getAccent("neutral"));
        row.setSize(Math.max(220, sharingContainer.getEffectiveWidth() - 10), 30);
    }

    private void setPathSyncEnabled(String id, boolean enabled) {
        List<NetworkPathSync> draft = pathSyncs.stream().map(sync -> sync.id().equals(id) ? sync.withEnabled(enabled) : sync).toList();
        try {
            validatePathSyncDraft(draft);
            pathSyncs = draft;
        } catch (RuntimeException exception) {
            new Notification("Path Sync Invalid", rootMessage(exception), Notification.Type.ERROR);
        }
        syncPathSyncRows();
    }

    private void syncPathSyncRows() {
        if (pathSyncSetting == null) {
            return;
        }
        for (String id : new ArrayList<>(pathSyncRows.keySet())) {
            pathSyncSetting.removeRow("sync:" + id);
        }
        pathSyncRows.clear();
        for (NetworkPathSync sync : pathSyncs) {
            MountableButtonWidget row = pathSyncRow(sync);
            pathSyncRows.put(sync.id(), row);
            pathSyncSetting.addRow("sync:" + sync.id(), "", row);
        }
        pathSyncSetting.setRowVisibility("empty", pathSyncs.isEmpty());
        requestLayout(sharingContainer);
    }

    private void syncActivityRows() {
        if (activitySetting == null) {
            return;
        }
        List<NetworkLifecycleJob> visibleLifecycle = lifecycleJobs.stream().filter(job -> job.status() == NetworkLifecycleStatus.RUNNING || job.status() == NetworkLifecycleStatus.INTERRUPTED).limit(1).toList();
        List<NetworkJob> visibleJobs = activeJobs.stream().filter(job -> job.status() == NetworkJobStatus.RUNNING || job.status() == NetworkJobStatus.INTERRUPTED || job.status() == NetworkJobStatus.ROLLING_BACK).limit(1).toList();
        Set<String> desired = new LinkedHashSet<>();
        visibleLifecycle.forEach(job -> desired.add("lifecycle:" + job.jobId()));
        visibleJobs.forEach(job -> desired.add("job:" + job.jobId()));
        for (String id : new ArrayList<>(activityRows.keySet())) {
            if (desired.contains(id)) {
                continue;
            }
            activityRows.remove(id);
            activitySetting.removeRow(id);
        }
        for (NetworkLifecycleJob job : visibleLifecycle) {
            String id = "lifecycle:" + job.jobId();
            MountableButtonWidget row = activityRows.computeIfAbsent(id, ignored -> {
                MountableButtonWidget created = new MountableButtonWidget.Builder("").build();
                activitySetting.addRow(id, "", created);
                return created;
            });
            long completed = job.steps().stream().filter(NetworkLifecycleStep::complete).count();
            row.setName(titleCase(job.operation().name()));
            row.setDescription(completed + " Of " + job.steps().size() + " Servers Complete");
            row.setHiddenText(lifecycleStatus(job.status()));
            row.setAccent(ThemeManager.getDefaultAccent());
            row.setOnClick(job.canResume() ? () -> resumeLifecycle(job) : () -> {});
            row.clearMountedWidgets();
            row.addMountedWidget(job.canResume() ? rowAction("reload.png", "Continue", () -> resumeLifecycle(job)) : rowAction("info.png", "In Progress", () -> {}));
        }
        for (NetworkJob job : visibleJobs) {
            String id = "job:" + job.jobId();
            MountableButtonWidget row = activityRows.computeIfAbsent(id, ignored -> {
                MountableButtonWidget created = new MountableButtonWidget.Builder("").build();
                activitySetting.addRow(id, "", created);
                return created;
            });
            row.setName(friendlyJobName(job.type()));
            row.setDescription("Remotely Is Applying Network Changes");
            row.setHiddenText(jobStatus(job.status()));
            row.setAccent(ThemeManager.getDefaultAccent());
            row.setOnClick(job.canResume() ? () -> resume(job) : () -> {});
            row.clearMountedWidgets();
            if (job.canResume()) {
                row.addMountedWidget(rowAction("reload.png", "Resume", () -> resume(job)));
            }
            if (job.canRollback() && (job.status() == NetworkJobStatus.INTERRUPTED || job.status() == NetworkJobStatus.FAILED)) {
                row.addMountedWidget(rowAction("history.png", "Rollback", () -> rollback(job), ThemeManager.getAccent("danger")));
            }
        }
        activitySetting.setVisible(!desired.isEmpty());
        requestLayout(overviewContainer);
    }

    private void syncAttentionRows() {
        if (attentionSetting == null) {
            return;
        }
        Map<String, AttentionItem> desired = new LinkedHashMap<>();
        discovery.issues().stream().filter(issue -> issue.severity() != NetworkValidationIssue.Severity.INFO).forEach(issue -> {
            NetworkMember member = attentionMember(issue.subject());
            String id = "issue:" + issue.code() + ":" + issue.subject();
            desired.put(id, attentionItem(id, issue.message(), issue.message(), issue.blocksPersistence(), member));
        });
        incidents.stream().filter(incident -> incident.status() == NetworkIncidentStatus.OPEN).forEach(incident -> {
            NetworkMember member = attentionMember(incident.nodeId());
            String detail = incident.detail().isBlank() ? incident.summary() : incident.detail();
            desired.put("incident:" + incident.incidentId(), attentionItem("incident:" + incident.incidentId(), incident.summary(), detail, incident.severity() == NetworkIncidentSeverity.CRITICAL, member));
        });
        attentionItems.clear();
        attentionItems.putAll(desired);
        for (String id : new ArrayList<>(attentionRows.keySet())) {
            if (desired.containsKey(id)) {
                continue;
            }
            attentionRows.remove(id);
            attentionSetting.removeRow(id);
        }
        desired.forEach((id, item) -> {
            MountableButtonWidget row = attentionRows.computeIfAbsent(id, ignored -> {
                MountableButtonWidget created = new MountableButtonWidget.Builder("").build();
                created.addMountedWidget(rowAction("info.png", "Open Details", () -> openAttention(id)));
                attentionSetting.addRow(id, "", created);
                return created;
            });
            row.setName(item.title());
            row.setDescription(item.description());
            row.setAccent(item.blocking() ? ThemeManager.getAccent("danger") : ThemeManager.getDefaultAccent());
            row.setOnClick(() -> openAttention(id));
        });
        attentionSetting.setVisible(!desired.isEmpty());
        requestLayout(overviewContainer);
    }

    private NetworkMember attentionMember(String subject) {
        String value = subject == null ? "" : subject.trim();
        return network.members().stream().filter(member -> member.nodeId().equalsIgnoreCase(value) || member.instanceId().equalsIgnoreCase(value) || member.routeName().equalsIgnoreCase(value) || displayName(member).equalsIgnoreCase(value)).findFirst().orElse(null);
    }

    private void openAttention(String id) {
        AttentionItem item = attentionItems.get(id);
        if (item == null) {
            return;
        }
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder(item.title()).width(330).onClose(() -> popup[0].hide());
        if (item.member() != null) {
            builder.addTitleAction("Open Server", () -> {
                popup[0].hide();
                openMember(item.member());
            }, PopupWidget.TitleActionRole.PRIMARY);
        }
        builder.addMarkdown("", item.description() + "\n\n" + item.detail());
        popup[0] = showPopup(builder.build());
    }

    private void populateCurrentJobs(Container container) {
        activitySetting = new Setting.Builder("Current Activity").build();
        container.addWidget(activitySetting);
        syncActivityRows();
    }

    private String friendlyJobName(NetworkJobType type) {
        return switch (type) {
            case QUICK_CREATE -> "Creating Network";
            case ATTACH -> "Adding Server";
            case DETACH -> "Removing Server";
            case ROUTING -> "Updating Join Rules";
            case REALMS -> "Updating Shared Data";
            case ROTATE_SECRET -> "Refreshing Connection Security";
            case RECONCILE -> "Repairing Network";
            case DELETE -> "Dissolving Network";
            case ADOPT -> "Connecting Existing Network";
            case LIFECYCLE -> "Updating Network";
        };
    }

    private MountableButtonWidget infoRow(Container container, String title, String description, String hiddenText) {
        return infoRow(container, title, description, hiddenText, ThemeManager.getDefaultAccent());
    }

    private MountableButtonWidget infoRow(Container container, String title, String description, String hiddenText, Accent accent) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(title).description(description).hiddenText(hiddenText).build();
        styleRow(container, row, accent, 32);
        return row;
    }

    private SquareButtonWidget rowAction(String icon, String hint, Runnable action) {
        return rowAction(icon, hint, action, ThemeManager.getDefaultAccent());
    }

    private SquareButtonWidget rowAction(String icon, String hint, Runnable action, Accent accent) {
        return new SquareButtonWidget.Builder().imagePath(icon).hint(hint).hintDelay(0.15f).onClick(action).accentType(accent).animateElevation(false).size(18, 18).build();
    }

    private void styleRow(Container container, MountableButtonWidget row, Accent accent, int height) {
        row.setAccent(accent);
        row.setSize(Math.max(220, container.getEffectiveWidth() - 10), height);
    }

    private void populateServers(Container container) {
        TextInputWidget search = new TextInputWidget.Builder().size(Math.max(220, container.getEffectiveWidth() - 8), 20).placeholder("Search Servers").search(true).onChange(value -> {
            serverSearchQuery = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            network.members().forEach(member -> serversSetting.setRowVisibility("server:" + member.nodeId(), serverSearchQuery.isBlank() || serverSearchText(member).contains(serverSearchQuery)));
            requestLayout(container);
        }).build();
        Setting.Builder servers = new Setting.Builder("Network Servers");
        servers.addRow("search", "", search);
        MountableButtonWidget addServer = actionRow("Add Server", "Add an existing Remotely server or register a server managed elsewhere.", "merge.png", this::openAddServer);
        addServer.setActive(networkSupports("membership") || networkSupports("externalMembership"));
        styleRow(container, addServer, ThemeManager.getDefaultAccent(), 30);
        servers.addRow("add", "", addServer);
        for (NetworkMember member : network.members()) {
            MountableButtonWidget row = memberRow(container, member, runtimeSnapshot);
            serverRows.put(member.nodeId(), row);
            servers.addRow("server:" + member.nodeId(), "", row);
        }
        serversSetting = servers.build();
        network.members().forEach(member -> serversSetting.setRowVisibility("server:" + member.nodeId(), serverSearchQuery.isBlank() || serverSearchText(member).contains(serverSearchQuery)));
        container.addWidget(serversSetting);
        requestLayout(container);
    }

    private void openAddServer() {
        provider.availableServers(networkId).whenComplete((available, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (throwable != null) {
                new Notification("Servers Unavailable", rootMessage(throwable), Notification.Type.ERROR);
                return;
            }
            openAddServer(available);
        }));
    }

    private void openAddServer(List<NetworkOverviewProvider.ServerView> available) {
        List<String> serverIds = available.stream().map(NetworkOverviewProvider.ServerView::id).toList();
        String[] selectedServer = {serverIds.isEmpty() ? "" : serverIds.getFirst()};
        List<String> joinRules = new ArrayList<>();
        joinRules.add("");
        routingGroups.stream().map(RoutingGroup::id).forEach(joinRules::add);
        String[] joinRule = {""};
        PopupWidget[] popup = new PopupWidget[1];
        Runnable add = () -> {
            NetworkOverviewProvider.ServerView instance = instancesById.get(selectedServer[0]);
            if (instance == null) {
                new Notification("Choose A Server", "Create a server in Remotely first, or register an external server.", Notification.Type.ERROR);
                return;
            }
            popup[0].hide();
            attachManaged(instance, joinRule[0]);
        };
        Runnable external = () -> {
            popup[0].hide();
            openExternalServer();
        };
        PopupWidget.Builder builder = new PopupWidget.Builder("Add Server").width(260).setExpandWithDropdowns(true).onClose(() -> popup[0].hide())
            .addTitleAction("External", external, "Register External Server", PopupWidget.TitleActionRole.SECONDARY);
        if (available.isEmpty()) {
            builder.addRow(new PopupWidget.PopupRow.Builder("Remotely Servers", inactive("No Available Servers", ThemeManager.getDefaultAccent())).id("available").description("Every existing Remotely server is already part of a network. Create another server first, or register a server that is managed elsewhere.").build());
        } else {
            builder.addTitleAction("Add", add, "Add Server", PopupWidget.TitleActionRole.PRIMARY);
            builder.addDropdown("Server", "Choose a Remotely server that is not already connected to a network.", serverIds, selectedServer[0], this::instanceName, value -> selectedServer[0] = value);
            builder.addDropdown("Join Rule", "Choose Later leaves the server out of player routing. A named rule makes the server available through that join path.", joinRules, joinRule[0], value -> value.isBlank() ? "Choose Later" : routingGroupName(value), value -> joinRule[0] = value);
        }
        popup[0] = showPopup(builder.build());
    }

    private void openExternalServer() {
        String[] name = {"External Server"};
        String[] address = {""};
        String[] port = {"25565"};
        String[] capacity = {"0"};
        Boolean[] ownership = {Boolean.FALSE};
        List<String> joinRules = new ArrayList<>();
        joinRules.add("");
        routingGroups.stream().map(RoutingGroup::id).forEach(joinRules::add);
        String[] joinRule = {""};
        PopupWidget[] popup = new PopupWidget[1];
        Runnable add = () -> {
            if (!ownership[0]) {
                new Notification("Confirm Server Ownership", "Confirm that you manage this server and its protection before registering it.", Notification.Type.ERROR);
                return;
            }
            try {
                popup[0].hide();
                attachExternal(name[0], address[0], parsePort(port[0]), parseCapacity(capacity[0]), joinRule[0]);
            } catch (RuntimeException exception) {
                new Notification("Server Details Invalid", rootMessage(exception), Notification.Type.ERROR);
            }
        };
        PopupWidget.Builder builder = new PopupWidget.Builder("Register External Server").width(280).setExpandWithDropdowns(true).onClose(() -> popup[0].hide())
            .addTitleAction("Register", add, "Register External Server", PopupWidget.TitleActionRole.PRIMARY);
        builder.addTextField("Name", "Enter the server name shown in Remotely and used by proxy routing.", name[0], value -> name[0] = value);
        builder.addTextField("Address", "The private address the proxy uses to reach this server. Do not use a public address unless the backend is securely protected.", address[0], value -> address[0] = value);
        builder.addTextField("Port", "The Minecraft port this server listens on. It must be a whole number between 1 and 65535.", port[0], value -> port[0] = value);
        builder.addTextField("Player Limit", "Optional capacity used when choosing a server. Enter 0 when the server does not publish a fixed limit.", capacity[0], value -> capacity[0] = value);
        builder.addDropdown("Join Rule", "Choose Later leaves the server out of player routing. A named rule makes the server available through that join path.", joinRules, joinRule[0], value -> value.isBlank() ? "Choose Later" : routingGroupName(value), value -> joinRule[0] = value);
        builder.addToggle("Manual Ownership", "Confirm that Remotely only manages the proxy route. You remain responsible for starting this server, configuring forwarding, and protecting it from direct public access.", false, value -> ownership[0] = value);
        popup[0] = showPopup(builder.build());
    }

    private void attachManaged(NetworkOverviewProvider.ServerView instance, String joinRule) {
        if (applyingNetworkChange) {
            return;
        }
        if (!network.runtime().enabled()) {
            runManagedAttach(instance, joinRule, false);
            return;
        }
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Install ReSync").width(400);
        builder.addRow(new PopupWidget.PopupRow.Builder("Enable Live Server Features").id("resync").description("Install The Latest ReSync On " + instance.name() + " For Player Controls, Shared Features, Events, And Live Status.").build());
        builder.addTitleAction("Continue Without ReSync", () -> {
            popup[0].hide();
            runManagedAttach(instance, joinRule, false);
        }, PopupWidget.TitleActionRole.SECONDARY);
        builder.addTitleAction("Install ReSync", () -> {
            popup[0].hide();
            runManagedAttach(instance, joinRule, true);
        }, PopupWidget.TitleActionRole.PRIMARY);
        popup[0] = showPopup(builder.build());
    }

    private void runManagedAttach(NetworkOverviewProvider.ServerView instance, String joinRule, boolean installReSync) {
        if (!requireNetworkCapability("membership")) return;
        applyingNetworkChange = true;
        Notification notification = operationNotification(installReSync ? "Installing ReSync" : "Adding Server", instance.name());
        provider.attach(networkId, new NetworkOverviewProvider.AttachRequest(instance.id(), joinRule, installReSync))
            .whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
                applyingNetworkChange = false;
                finishOperation(notification, job, throwable, "Server Added");
            }));
    }

    private void attachExternal(String name, String address, int port, int capacity, String joinRule) {
        if (applyingNetworkChange) {
            return;
        }
        if (!requireNetworkCapability("externalMembership")) return;
        applyingNetworkChange = true;
        Notification notification = operationNotification("Registering Server", name);
        provider.attachExternal(networkId, new NetworkOverviewProvider.ExternalAttachRequest(name, address, port, capacity, joinRule))
            .whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
                applyingNetworkChange = false;
                finishOperation(notification, job, throwable, "Server Registered");
            }));
    }

    private String instanceName(String instanceId) {
        NetworkOverviewProvider.ServerView instance = instancesById.get(instanceId);
        return instance == null ? "Unavailable Server" : instance.name();
    }

    private int parsePort(String value) {
        int port = Integer.parseInt(value == null ? "" : value.trim());
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port Must Be Between 1 And 65535");
        }
        return port;
    }

    private int parseCapacity(String value) {
        int capacity = Integer.parseInt(value == null ? "" : value.trim());
        if (capacity < 0) {
            throw new IllegalArgumentException("Player Limit Cannot Be Negative");
        }
        return capacity;
    }

    private void populateRouting(Container container) {
        Setting.Builder rules = new Setting.Builder("Player Join Rules");
        rules.addRow("add", "", actionRow("Add Join Rule", "Choose where players are sent when they join.", "add.png", () -> openRoutingGroup(null)));
        AnimatedButton routingEmpty = inactive("No Join Rules Yet", ThemeManager.getDefaultAccent());
        routingEmpty.setVisible(routingGroups.isEmpty());
        rules.addRow("empty", "", routingEmpty);
        for (RoutingGroup group : routingGroups) {
            MountableButtonWidget row = routingRow(group);
            routingRows.put(group.id(), row);
            rules.addRow("rule:" + group.id(), "", row);
        }
        routingSetting = rules.build();
        routingSetting.setRowVisibility("empty", routingGroups.isEmpty());
        container.addWidget(routingSetting);
        requestLayout(container);
    }

    private void openRoutingGroup(RoutingGroup existing) {
        String[] name = {existing == null ? "Join Rule" : existing.name()};
        String[] servers = {existing == null ? "" : displayNames(existing.nodeIds())};
        String[] weights = {existing == null ? "" : routingWeights(existing)};
        String[] addresses = {existing == null ? "" : String.join(", ", existing.forcedHosts())};
        String[] permission = {existing == null ? "" : existing.permission()};
        RoutingStrategy[] strategy = {existing == null ? RoutingStrategy.ORDERED : existing.strategy()};
        List<String> fallbacks = new ArrayList<>();
        fallbacks.add("");
        routingGroups.stream().filter(group -> existing == null || !group.id().equals(existing.id())).map(RoutingGroup::id).forEach(fallbacks::add);
        String[] fallback = {existing == null ? "" : existing.fallbackGroupId()};
        PopupWidget[] popup = new PopupWidget[1];
        Runnable save = () -> {
            try {
                RoutingGroup updated = buildRoutingGroup(existing, name[0], strategy[0], servers[0], weights[0], addresses[0], fallback[0], permission[0]);
                List<RoutingGroup> draft = new ArrayList<>(routingGroups);
                if (existing == null) {
                    draft.add(updated);
                } else {
                    draft.set(draft.indexOf(existing), updated);
                }
                routingGroups = List.copyOf(draft);
                popup[0].hide();
                syncRoutingRows();
            } catch (RuntimeException exception) {
                new Notification("Join Rule Invalid", rootMessage(exception), Notification.Type.ERROR);
            }
        };
        PopupWidget.Builder builder = new PopupWidget.Builder(existing == null ? "Add Join Rule" : "Edit " + existing.name()).width(300).setResizable(true).setExpandWithDropdowns(true).onClose(() -> popup[0].hide());
        if (existing != null) {
            builder.addTitleAction("Delete", () -> {
                if (routingGroups.stream().anyMatch(group -> group.fallbackGroupId().equals(existing.id()))) {
                    new Notification("Rule Still Used", "Choose another fallback for the rules that depend on it first.", Notification.Type.ERROR);
                    return;
                }
                routingGroups = routingGroups.stream().filter(group -> !group.equals(existing)).toList();
                popup[0].hide();
                syncRoutingRows();
            }, "Delete Join Rule", PopupWidget.TitleActionRole.DESTRUCTIVE);
        }
        builder.addTitleAction(existing == null ? "Add" : "Update", save, existing == null ? "Add Join Rule" : "Update Join Rule", PopupWidget.TitleActionRole.PRIMARY);
        builder.addTextField("Name", "Enter a short name for this player join rule.", name[0], value -> name[0] = value);
        builder.addScrollSelector("Player Choice", "Use Listed Order tries Servers from left to right. Choose Least Busy uses the server with the fewest players. Use Custom Priorities uses the values in Priorities.", Arrays.stream(RoutingStrategy.values()).map(this::routingStrategyLabel).toList(), strategy[0].ordinal(), index -> strategy[0] = RoutingStrategy.values()[index]);
        builder.addTextField("Servers", "List server names in the order players should try them, separated with commas.", servers[0], value -> servers[0] = value);
        builder.addTextField("Priorities", "Only used with Custom Priorities. Enter each server name followed by = and a positive number, such as Survival=3.", weights[0], value -> weights[0] = value);
        builder.addTextField("Join Addresses", "Optional addresses that should send players through this rule, such as play.example.com. Separate multiple addresses with commas.", addresses[0], value -> addresses[0] = value);
        builder.addDropdown("If Unavailable", "No Other Rule ends this path when every server is unavailable. A named rule tries that player path next.", fallbacks, fallback[0], value -> value.isBlank() ? "No Other Rule" : routingGroupName(value), value -> fallback[0] = value);
        builder.addTextField("Access Permission", "Optional permission required to use this rule. Leave it empty when every player may use it.", permission[0], value -> permission[0] = value);
        popup[0] = showPopup(builder.build());
    }

    private RoutingGroup buildRoutingGroup(RoutingGroup existing, String rawName, RoutingStrategy strategy, String rawServers, String rawWeights, String rawAddresses, String rawFallback, String permission) {
        String id = existing == null ? nextRoutingId() : existing.id();
        String name = rawName == null || rawName.isBlank() ? "Join Rule" : rawName.trim();
        Map<String, NetworkMember> members = network.members().stream().filter(member -> !member.isProxy()).collect(Collectors.toMap(member -> displayName(member).toLowerCase(Locale.ROOT), member -> member, (first, second) -> first, LinkedHashMap::new));
        network.members().stream().filter(member -> !member.isProxy()).forEach(member -> members.putIfAbsent(member.routeName().toLowerCase(Locale.ROOT), member));
        List<String> nodeIds = commaValues(rawServers).stream().map(value -> {
            NetworkMember member = members.get(value.toLowerCase(Locale.ROOT));
            if (member == null) {
                throw new IllegalArgumentException("Unknown Server " + value);
            }
            return member.nodeId();
        }).distinct().toList();
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String value : commaValues(rawWeights)) {
            int separator = value.indexOf('=');
            if (separator <= 0 || separator >= value.length() - 1) {
                throw new IllegalArgumentException("Priorities Use Server=Number");
            }
            NetworkMember member = members.get(value.substring(0, separator).trim().toLowerCase(Locale.ROOT));
            if (member == null || !nodeIds.contains(member.nodeId())) {
                throw new IllegalArgumentException("Priority Uses A Server That Is Not In This Rule");
            }
            int weight = Integer.parseInt(value.substring(separator + 1).trim());
            if (weight < 1 || weight > 10_000) {
                throw new IllegalArgumentException("Priorities Must Be Between 1 And 10000");
            }
            weights.put(member.nodeId(), weight);
        }
        if (strategy == RoutingStrategy.WEIGHTED && nodeIds.stream().anyMatch(nodeId -> !weights.containsKey(nodeId))) {
            throw new IllegalArgumentException("Custom Priorities Need A Number For Every Server");
        }
        Set<String> addresses = commaValues(rawAddresses).stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new));
        String fallback = rawFallback == null ? "" : rawFallback.trim();
        return new RoutingGroup(id, name, strategy, nodeIds, weights, fallback, addresses, permission);
    }

    private String nextRoutingId() {
        if (routingGroups.stream().noneMatch(group -> group.id().equals("fallback"))) {
            return "fallback";
        }
        int index = 1;
        List<String> ids = routingGroups.stream().map(RoutingGroup::id).toList();
        while (ids.contains("rule-" + index)) {
            index++;
        }
        return "rule-" + index;
    }

    private String routingGroupName(String id) {
        return routingGroups.stream().filter(group -> group.id().equals(id)).map(RoutingGroup::name).findFirst().orElse("No Other Rule");
    }

    private String routingStrategyLabel(RoutingStrategy strategy) {
        return switch (strategy) {
            case ORDERED -> "Use Listed Order";
            case LEAST_PLAYERS -> "Choose Least Busy";
            case WEIGHTED -> "Use Custom Priorities";
        };
    }

    private String routingWeights(RoutingGroup group) {
        return group.nodeIds().stream().filter(group.weights()::containsKey).map(nodeId -> member(nodeId).map(this::displayName).orElse("Missing Server") + "=" + group.weights().get(nodeId)).collect(Collectors.joining(", "));
    }

    private void runPreflight() {
        if (!requireNetworkCapability("preflight")) return;
        Notification notification = operationNotification("Checking Join Path", network.name());
        provider.preflight(networkId).whenComplete((report, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (throwable != null) {
                notification.update().message("Player Join Check Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                refresh();
                return;
            }
            boolean passed = report.status() == NetworkPreflightStatus.SUCCEEDED;
            notification.update().message(passed ? "Join Path Ready" : "Join Path Needs Attention").description(report.summary()).type(passed ? Notification.Type.SUCCESS : Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            refresh();
        }));
    }

    private void prepareSecretRotation() {
        if (applyingNetworkChange) {
            return;
        }
        if (!requireNetworkCapability("secretRotation")) return;
        applyingNetworkChange = true;
        Notification notification = operationNotification("Refreshing Connection Security", network.name());
        provider.rotateSecret(networkId)
            .whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
                applyingNetworkChange = false;
                finishOperation(notification, job, throwable, "Connection Security Refreshed");
            }));
    }

    private void createDissolvePopup() {
        PopupWidget.Builder builder = new PopupWidget.Builder("Dissolve Network").width(300).onClose(() -> dissolvePopup.hide())
            .addTitleAction("Dissolve", this::dissolve, "Restore Servers And Dissolve", PopupWidget.TitleActionRole.DESTRUCTIVE);
        builder.addRow(new PopupWidget.PopupRow.Builder("Restore Independent Servers", inactive("Servers And Worlds Are Kept", ThemeManager.getDefaultAccent())).id("confirmDissolve").description("Remotely removes shared routing, forwarding, and ReSync settings, then restores each managed server. Server files and worlds remain.").build());
        dissolvePopup = builder.build();
        dissolvePopup.hide();
        addDrawableChild(dissolvePopup);
    }

    private void openDissolvePopup() {
        dissolvePopup.setX((width - dissolvePopup.getWidth()) / 2);
        dissolvePopup.setY((height - dissolvePopup.getHeight()) / 2);
        dissolvePopup.show();
    }

    private void dissolve() {
        if (applyingNetworkChange) {
            return;
        }
        if (!requireNetworkCapability("dissolve")) return;
        applyingNetworkChange = true;
        dissolvePopup.hide();
        Notification notification = operationNotification("Dissolving Network", network.name());
        provider.dissolve(networkId).whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
            applyingNetworkChange = false;
            if (throwable != null) {
                notification.update().message("Dissolve Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            if (job == null || job.status() != NetworkJobStatus.SUCCEEDED) {
                notification.update().message("Network Needs Attention").description(job == null ? "Dissolve job did not finish" : job.message()).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            notification.update().message("Network Dissolved").description(network.name()).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            ScreenManager.getInstance().goBack(this, parent);
        }));
    }

    private void openMember(NetworkMember member) {
        if (!member.isManaged()) {
            new Notification("External Server", member.address() + ":" + member.port(), Notification.Type.INFO);
            return;
        }
        NetworkOverviewProvider.ServerView instance = instancesById.get(member.instanceId());
        if (instance == null) {
            new Notification("Server Unavailable", displayName(member), Notification.Type.ERROR);
            return;
        }
        provider.openServer(this, instance.id());
    }

    private void confirmDetach(NetworkMember member, NetworkOverviewProvider.ServerView instance) {
        PopupWidget[] popup = new PopupWidget[1];
        Runnable detach = () -> {
            popup[0].hide();
            if (member.isManaged()) {
                if (instance == null) {
                    new Notification("Server Unavailable", displayName(member), Notification.Type.ERROR);
                    return;
                }
                detachManaged(member, instance);
            } else {
                detachExternal(member);
            }
        };
        PopupWidget.Builder builder = new PopupWidget.Builder("Detach " + displayName(member)).width(290).onClose(() -> popup[0].hide())
            .addTitleAction("Detach", detach, "Restore Independent Settings", PopupWidget.TitleActionRole.DESTRUCTIVE);
        builder.addRow(new PopupWidget.PopupRow.Builder("Make Server Independent", inactive("Server Files Are Kept", ThemeManager.getDefaultAccent())).id("detachServer").description("Remotely removes this server from routing and shared ReSync settings, then restores its independent configuration. Files and worlds remain.").build());
        popup[0] = showPopup(builder.build());
    }

    private void setRuntimeMode(NetworkMember member, NetworkRuntimeNodeStatus status) {
        if (!requireNetworkCapability("runtimeControl")) return;
        String action = status == NetworkRuntimeNodeStatus.ONLINE ? "Resuming" : status == NetworkRuntimeNodeStatus.DRAINING ? "Draining" : "Starting Maintenance";
        Notification notification = operationNotification(action + " Server", displayName(member));
        provider.runtimeNodeMode(networkId, member.nodeId(), status).whenComplete((unused, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (throwable != null) {
                notification.update().message("Server Update Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            notification.update().message("Server Updated").description(displayName(member) + " • " + titleCase(status.name())).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            refresh();
        }));
    }

    private void installNetworkReSync() {
        if (applyingNetworkChange) {
            return;
        }
        if (!requireNetworkCapability("resync")) return;
        List<NetworkOverviewProvider.ServerView> targets = network.members().stream().filter(NetworkMember::isManaged).map(member -> instancesById.get(member.instanceId())).filter(instance -> instance != null).distinct().toList();
        List<String> backendIds = network.members().stream().filter(member -> member.isManaged() && !member.isProxy() && instancesById.containsKey(member.instanceId())).map(NetworkMember::instanceId).toList();
        if (targets.stream().noneMatch(instance -> instance.id().equals(network.proxyInstanceId())) || backendIds.isEmpty()) {
            new Notification("ReSync Installation Failed", "The Proxy And At Least One Managed Server Are Required", Notification.Type.ERROR);
            return;
        }
        applyingNetworkChange = true;
        Notification notification = operationNotification("Installing ReSync", "Preparing Live Network Features");
        provider.installReSync(networkId).whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
            applyingNetworkChange = false;
            if (throwable != null || job == null || job.status() != NetworkJobStatus.SUCCEEDED) {
                notification.update().message("ReSync Installation Failed").description(throwable != null ? rootMessage(throwable) : job == null ? "Network Job Did Not Finish" : job.message()).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                refresh();
                return;
            }
            notification.update().message("ReSync Installed").description("Live Network Features Are Ready").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            client.setScreen(new NetworkOverviewScreen(parent, provider, networkId));
        }));
    }

    private void executeProxyCommand(TextInputWidget input) {
        if (!requireNetworkCapability("command")) return;
        String command = input.getText().trim();
        Notification notification = operationNotification("Running Proxy Command", network.name());
        provider.executeProxyCommand(networkId, command).whenComplete((unused, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (throwable != null) {
                notification.update().message("Proxy Command Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            input.setText("");
            notification.update().message("Proxy Command Complete").description(network.name()).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
        }));
    }

    private void broadcastMessage(TextInputWidget input) {
        if (!requireNetworkCapability("broadcast")) return;
        String message = input.getText().trim();
        Notification notification = operationNotification("Broadcasting Message", network.name());
        provider.broadcastMessage(networkId, message).whenComplete((unused, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (throwable != null) {
                notification.update().message("Broadcast Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            input.setText("");
            notification.update().message("Broadcast Sent").description(network.name()).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
        }));
    }

    private void detachExternal(NetworkMember member) {
        if (applyingNetworkChange) {
            return;
        }
        if (!requireNetworkCapability("externalMembership")) return;
        applyingNetworkChange = true;
        Notification notification = operationNotification("Detaching External Server", displayName(member));
        provider.detach(networkId, member.nodeId()).whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
            applyingNetworkChange = false;
            finishOperation(notification, job, throwable, "External Server Removed");
        }));
    }

    private void detachManaged(NetworkMember member, NetworkOverviewProvider.ServerView instance) {
        if (applyingNetworkChange) {
            return;
        }
        if (!requireNetworkCapability("membership")) return;
        applyingNetworkChange = true;
        Notification notification = operationNotification("Detaching Server", displayName(member));
        provider.detach(networkId, member.nodeId()).whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
            applyingNetworkChange = false;
            finishOperation(notification, job, throwable, "Server Detached");
        }));
    }

    private void reconcile() {
        if (applyingNetworkChange) {
            return;
        }
        if (!requireNetworkCapability("reconcile")) return;
        applyingNetworkChange = true;
        Notification notification = operationNotification("Healing Network", network.name());
        provider.reconcile(networkId).whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
            applyingNetworkChange = false;
            finishOperation(notification, job, throwable);
        }));
    }

    private void resume(NetworkJob job) {
        if (!requireNetworkCapability("jobRecovery")) return;
        Notification notification = operationNotification("Resuming Network", job.message());
        provider.resumeJob(networkId, job.jobId()).whenComplete((updated, throwable) -> ScreenManager.getInstance().execute(() -> finishOperation(notification, updated, throwable)));
    }

    private void rollback(NetworkJob job) {
        if (!requireNetworkCapability("jobRecovery")) return;
        Notification notification = operationNotification("Rolling Back Network", job.message());
        provider.rollbackJob(networkId, job.jobId()).whenComplete((updated, throwable) -> ScreenManager.getInstance().execute(() -> finishOperation(notification, updated, throwable)));
    }

    private void lifecycle(NetworkLifecycleOperation operation) {
        if (applyingNetworkChange) {
            return;
        }
        if (!requireNetworkCapability("lifecycle")) return;
        applyingNetworkChange = true;
        networkPowerButton.updateState(operation == NetworkLifecycleOperation.START ? "STARTING" : "STOPPING");
        header().requestLayoutUpdate();
        Notification notification = operationNotification(titleCase(operation.name()) + " Network", network.name());
        provider.lifecycle(networkId, operation).whenComplete((job, throwable) -> ScreenManager.getInstance().execute(() -> {
            applyingNetworkChange = false;
            finishLifecycle(notification, job, throwable);
        }));
    }

    private void resumeLifecycle(NetworkLifecycleJob job) {
        if (!requireNetworkCapability("lifecycleRecovery")) return;
        Notification notification = operationNotification("Resuming " + titleCase(job.operation().name()), job.message());
        provider.resumeLifecycle(networkId, job.jobId()).whenComplete((updated, throwable) -> ScreenManager.getInstance().execute(() -> finishLifecycle(notification, updated, throwable)));
    }

    private Notification operationNotification(String message, String description) {
        return new Notification.Builder().message(message).description(description).type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
    }

    private void finishOperation(Notification notification, NetworkJob job, Throwable throwable) {
        finishOperation(notification, job, throwable, "Network Ready");
    }

    private void finishOperation(Notification notification, NetworkJob job, Throwable throwable, String successMessage) {
        if (throwable != null) {
            notification.update().message("Network Operation Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            refresh();
            return;
        }
        if (job == null || (job.status() != NetworkJobStatus.SUCCEEDED && job.status() != NetworkJobStatus.ROLLED_BACK)) {
            notification.update().message("Network Needs Attention").description(job == null ? "Network operation did not finish" : job.message()).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            refresh();
            return;
        }
        NetworkValidationIssue warning = job.issues().stream().filter(issue -> issue.code().equals("detach.restore-point.missing")).findFirst().orElse(null);
        if (job.status() == NetworkJobStatus.SUCCEEDED && warning != null) {
            notification.update().message(successMessage).description(warning.message()).type(Notification.Type.WARN).loading(false).autoSlideOut(true).commit();
            refresh();
            return;
        }
        String description = job.status() == NetworkJobStatus.SUCCEEDED && job.restartRequired() ? "Restart Affected Servers To Apply Changes" : job.message();
        notification.update().message(job.status() == NetworkJobStatus.ROLLED_BACK ? "Network Rolled Back" : successMessage).description(description).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
        refresh();
    }

    private void finishLifecycle(Notification notification, NetworkLifecycleJob job, Throwable throwable) {
        if (throwable != null) {
            notification.update().message("Network Operation Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            refresh();
            return;
        }
        if (job == null || job.status() != NetworkLifecycleStatus.SUCCEEDED) {
            notification.update().message("Network Needs Attention").description(job == null ? "The network action did not finish" : job.message()).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            refresh();
            return;
        }
        notification.update().message("Network Ready").description(job.message()).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
        refresh();
    }

    private void refresh() {
        if (!provider.available()) {
            new Notification("Network Unavailable", Notification.Type.ERROR);
            return;
        }
        long generation = ++refreshGeneration;
        NetworkDefinition previous = network;
        boolean nameDirty = networkNameInput != null && !networkNameInput.getText().trim().equals(previous.name());
        boolean routingDirty = !routingGroups.equals(previous.routingGroups());
        boolean realmsDirty = !syncRealms.equals(previous.syncRealms()) || !playerGroupEditors.isEmpty();
        provider.load(networkId).whenComplete((state, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (generation != refreshGeneration) {
                return;
            }
            if (throwable != null) {
                new Notification("Refresh Failed", rootMessage(throwable), Notification.Type.ERROR);
                return;
            }
            network = state.network();
            instances = state.servers();
            instancesById.clear();
            instances.forEach(instance -> instancesById.put(instance.id(), instance));
            discovery = state.discovery();
            runtimeSnapshot = latestRuntimeSnapshot(runtimeSnapshot, state.runtime());
            incidents = state.incidents();
            lifecycleJobs = state.lifecycleJobs();
            activeJobs = state.jobs();
            transferFailureHeat = state.transferFailureHeat();
            capabilities = state.capabilities();
            reSyncInstalled = state.reSyncInstalled();
            if (!routingDirty) {
                routingGroups = List.copyOf(network.routingGroups());
            }
            if (!realmsDirty) {
                syncRealms = List.copyOf(network.syncRealms());
            }
            if (!nameDirty && networkNameInput != null) {
                networkNameInput.setText(network.name());
            }
            updateNetworkPowerButton();
            topologyWidget.applyNetwork(network, discovery.observations());
            batchingLayout = true;
            try {
                syncServerRows();
                syncRoutingRows();
                syncPlayerDataRows();
                syncActivityRows();
                syncAttentionRows();
            } finally {
                batchingLayout = false;
            }
            overviewContainer.updateWidgetPositions();
            serversContainer.updateWidgetPositions();
            sharingContainer.updateWidgetPositions();
        }));
    }

    private void registerRuntimeChangeListener() {
        if (runtimeChangeListenerRegistered) {
            return;
        }
        provider.addRuntimeListener(runtimeChangeListener);
        runtimeChangeListenerRegistered = true;
    }

    private void registerNetworkChangeListener() {
        if (networkChangeListenerRegistered) {
            return;
        }
        provider.addListener(networkChangeListener);
        networkChangeListenerRegistered = true;
    }

    private void queueNetworkState(NetworkOverviewProvider.OverviewState state) {
        if (state != null && state.network() != null && networkId.equals(state.network().networkId())) {
            refresh();
        }
    }

    private void queueRuntimeSnapshot(NetworkRuntimeSnapshot snapshot) {
        if (snapshot == null || !networkId.equals(snapshot.networkId())) {
            return;
        }
        ScreenManager.getInstance().execute(() -> applyRuntimeSnapshot(snapshot));
    }

    private void applyRuntimeSnapshot(NetworkRuntimeSnapshot snapshot) {
        if (!runtimeChangeListenerRegistered || snapshot == null || !networkId.equals(snapshot.networkId())) {
            return;
        }
        NetworkRuntimeSnapshot latest = latestRuntimeSnapshot(runtimeSnapshot, snapshot);
        if (latest == runtimeSnapshot) {
            return;
        }
        runtimeSnapshot = latest;
        if (network == null || serversContainer == null) {
            return;
        }
        for (NetworkMember member : network.members()) {
            MountableButtonWidget row = serverRows.get(member.nodeId());
            if (row != null) {
                updateMemberRow(serversContainer, row, member, runtimeSnapshot);
            }
        }
    }

    private NetworkRuntimeSnapshot latestRuntimeSnapshot(NetworkRuntimeSnapshot current, NetworkRuntimeSnapshot candidate) {
        if (candidate == null || !networkId.equals(candidate.networkId())) {
            return current;
        }
        return current == null || candidate.updatedAt() >= current.updatedAt() ? candidate : current;
    }

    @Override
    public void removed() {
        refreshGeneration++;
        playerGroupEditors.values().forEach(PlayerGroupEditor::close);
        playerGroupEditors.clear();
        if (networkChangeListenerRegistered) {
            provider.removeListener(networkChangeListener);
        }
        if (runtimeChangeListenerRegistered) {
            provider.removeRuntimeListener(runtimeChangeListener);
        }
        networkChangeListenerRegistered = false;
        runtimeChangeListenerRegistered = false;
        super.removed();
    }

    private String lifecycleStatus(NetworkLifecycleStatus status) {
        return switch (status) {
            case READY -> "Ready";
            case RUNNING -> "Running";
            case INTERRUPTED -> "Interrupted";
            case SUCCEEDED -> "Complete";
            case FAILED -> "Failed";
        };
    }

    private String jobStatus(NetworkJobStatus status) {
        return switch (status) {
            case PLANNING -> "Planning";
            case READY -> "Ready";
            case RUNNING -> "Running";
            case INTERRUPTED -> "Interrupted";
            case ROLLING_BACK -> "Rolling Back";
            case SUCCEEDED -> "Complete";
            case ROLLED_BACK -> "Rolled Back";
            case FAILED -> "Failed";
            case BLOCKED -> "Blocked";
        };
    }

    private String titleCase(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (char character : normalized.toCharArray()) {
            result.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = character == ' ';
        }
        return result.toString();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "Network Operation Failed" : message;
    }

    private boolean canStart(String state) {
        return state == null || state.isBlank() || state.equals("STOPPED") || state.equals("CRASHED");
    }

    private final class PlayerGroupEditor {
        private final String id;
        private final Container container;
        private final SearchMode search;
        private final ConfigOption<String> name;
        private final ConfigOption<String> pluginData;
        private final ConfigOption<String> snapshots;
        private final ConfigOption<String> days;
        private final ConfigOption<SyncLocationPolicy> location;
        private final Map<String, ConfigOption<Boolean>> servers;
        private final Map<SyncDataFamily, ConfigOption<Boolean>> families;
        private TabsManager.Tab tab;

        private PlayerGroupEditor(String id, Container container, SearchMode search, ConfigOption<String> name,
                                  ConfigOption<String> pluginData, ConfigOption<String> snapshots, ConfigOption<String> days,
                                  ConfigOption<SyncLocationPolicy> location, Map<String, ConfigOption<Boolean>> servers,
                                  Map<SyncDataFamily, ConfigOption<Boolean>> families) {
            this.id = id;
            this.container = container;
            this.search = search;
            this.name = name;
            this.pluginData = pluginData;
            this.snapshots = snapshots;
            this.days = days;
            this.location = location;
            this.servers = servers;
            this.families = families;
        }

        private boolean apply() {
            try {
                Set<String> nodes = servers.entrySet().stream().filter(entry -> entry.getValue().get()).map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
                Set<SyncDataFamily> selected = families.entrySet().stream().filter(entry -> entry.getValue().get()).map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
                SyncRealm updated = buildPlayerGroup(id, name.get(), nodes, selected, location.get(), pluginData.get(), snapshots.get(), days.get());
                List<SyncRealm> draft = new ArrayList<>(syncRealms);
                int index = -1;
                for (int candidate = 0; candidate < draft.size(); candidate++) {
                    if (draft.get(candidate).id().equals(id)) {
                        index = candidate;
                        break;
                    }
                }
                if (index < 0) draft.add(updated);
                else draft.set(index, updated);
                syncRealms = List.copyOf(draft);
                if (tab != null) tab.setName(updated.name());
                syncPlayerDataRows();
                updatePlayerGroupHeader();
                return true;
            } catch (RuntimeException exception) {
                new Notification("Player Group Invalid", rootMessage(exception), Notification.Type.ERROR);
                return false;
            }
        }

        private void close() {
            search.setOnTextChange(null);
            container.clearWidgets();
        }
    }

    private record AttentionItem(String id, String title, String description, String detail, boolean blocking, NetworkMember member) {
    }

    private record MemberActions(SquareButtonWidget acceptPlayers, SquareButtonWidget drainServer, SquareButtonWidget maintenanceMode, SquareButtonWidget installReSync) {
    }

}
