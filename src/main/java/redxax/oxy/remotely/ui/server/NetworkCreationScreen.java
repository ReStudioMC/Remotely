package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.network.NetworkMemberManagement;
import redxax.oxy.remotely.network.HostedNetworkClient;
import redxax.oxy.remotely.network.HostedNetworkPendingStore;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.TabsManager;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsForm;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class NetworkCreationScreen extends ReScreen implements DesktopWindowBehaviorProvider {
    private static final ServerScreenHost.HostView LOCAL_HOST = new ServerScreenHost.HostView("", "Local", "LOCAL", "", 0, true, false);
    private static final List<NetworkMemberRole> BACKEND_ROLES = List.of(
        NetworkMemberRole.LOBBY,
        NetworkMemberRole.FALLBACK,
        NetworkMemberRole.GAMEPLAY,
        NetworkMemberRole.RESTRICTED,
        NetworkMemberRole.MAINTENANCE,
        NetworkMemberRole.CUSTOM
    );

    private final Screen parent;
    private final ServerScreenHost host;
    private final List<ServerModels.ClientServerView> selected;
    private final List<Member> members = new ArrayList<>();
    private final List<Setting> networkSections = new ArrayList<>();
    private List<ServerScreenHost.HostView> remoteHosts = List.of();
    private final List<ResourcePoolModels.Pool> pools = new ArrayList<>();
    private ResourcePoolModels.DraftOptions poolOptions;
    private final SearchMode search = new SearchMode(false);
    private Container networkContainer;
    private ConfigOption<String> nameOption;
    private ConfigOption<Integer> entryPortOption;
    private ConfigOption<Boolean> reSyncOption;
    private ConfigOption<Boolean> firewallVerifiedOption;
    private IconButton cancelButton;
    private IconButton createButton;
    private boolean bootstrapped;
    private boolean creating;
    private boolean closed;
    private long generation;
    private int backendNumber;
    private String createdNetworkId;
    private String recoveryNetworkId;
    private NetworkCreationPlan pendingPlan;
    private HostedNetworkPendingStore.Pending pendingHosted;
    private boolean createdHosted;
    private UUID requestId = UUID.randomUUID();
    private String account;
    private String hostFailure = "";
    private boolean loadingHosts;
    private long hostsRevision;
    private long poolsRevision;
    private boolean loadingPools;
    private boolean morePools;
    private int nextPoolPage;
    private String poolFailure = "";

    public NetworkCreationScreen(Screen parent, ServerScreenHost host, List<ServerModels.ClientServerView> selected) {
        this.parent = parent;
        enableNavigation(parent);
        this.host = host;
        this.selected = selected == null ? List.of() : selected.stream().filter(server -> server != null && !serverId(server).isBlank()).toList();
    }

    @Override
    public String getDesktopAppId() {
        return "network-creation";
    }

    @Override
    public String getDesktopAppTitle() {
        return "Create Network";
    }

    @Override
    public String getDesktopAppIconPath() {
        return "network.png";
    }

    @Override
    public DesktopWindowBehavior getDesktopWindowBehavior() {
        return DesktopWindowBehavior.SINGLETON;
    }

    @Override
    public boolean closeThroughDesktopOverlay() {
        return !creating;
    }

    @Override
    public void init() {
        super.init();
        closed = false;
        cancelButton = new IconButton.Builder().size(18, 18).label("Cancel").imagePath("goback.png").autoWidthOnTextChange(true).onClick(this::cancel).build();
        createButton = new IconButton.Builder().size(18, 18).label("Create Network").imagePath("checkmark.png").autoWidthOnTextChange(true).onClick(this::create).build();
        header().addLeft(cancelButton).addRight(createButton).setSearchMode(search, true).searchDebounce(100).build();
        tabs().builder()
            .allowAdd(true)
            .allowClose(true)
            .allowRename(false)
            .allowReorder(true)
            .position(6, 36)
            .size(width - 12, 18)
            .onTabSelected(this::selectTab)
            .onPlusButtonClicked(() -> addNewMember(false, true))
            .onTabCloseRequested(this::canCloseTab)
            .onTabClosed(this::tabClosed)
            .build();
        search.setPlaceholder("Search Network Setup");
        search.setOnTextChange(this::filterActiveTab);
        networkContainer = createContentContainer();
        tabs().addTab("Network", networkContainer, "network.png");
        if (!bootstrapped) {
            bootstrapped = true;
            account = accountKey();
            if (host.accountIdentity().authenticated()) {
                try {
                    pendingHosted = host.pendingHostedNetwork();
                    if (pendingHosted != null && pendingHosted.terminal() != null) {
                        recoveryNetworkId = host.hostedNetworkViewId(pendingHosted.command().networkId());
                        createdHosted = true;
                    }
                } catch (RuntimeException error) {
                    fail(rootMessage(error));
                }
            }
            createNetworkOptions();
            bootstrapMembers();
            loadHosts();
            if (members.stream().anyMatch(Member::hosted) || host.serverManagerMode() == ServerScreenHost.ServerManagerMode.REACTOR_ONLY) loadPools();
        } else {
            for (Member member : members) attachMember(member, false);
        }
        rebuildNetwork();
        tabs().setActiveTab(networkContainer);
        refreshHeader();
    }

    private Container createContentContainer() {
        Container container = createContainer(6, 60, width - 12, Math.max(80, height - 66));
        container.layout(new ManagedLayout()).columns(1).padding(4).verticalSpacing(6).scrolling(true).backgroundDrawing(false).setSearchMode(search);
        return container;
    }

    private void createNetworkOptions() {
        nameOption = ConfigOption.<String>builder("Network Name")
            .description("The Name Shown For This Network In Remotely")
            .bind(() -> "New Network", value -> {})
            .defaultValue("New Network")
            .resettable(false)
            .build();
        entryPortOption = ConfigOption.<Integer>builder("Player Port")
            .description("The Public Port Players Use To Connect To The Proxy")
            .bind(() -> 25565, value -> {})
            .defaultValue(25565)
            .resettable(false)
            .build();
        reSyncOption = ConfigOption.<Boolean>builder("Install ReSync")
            .description("Add Live Status, Player Controls, Shared Chat, And Shared Content To Managed Servers")
            .bind(() -> true, value -> {})
            .defaultValue(true)
            .resettable(false)
            .build();
        firewallVerifiedOption = ConfigOption.<Boolean>builder("Private Network Or Firewall Verified")
            .description("Confirm Backend Ports Are Private Or Firewalled So Only The Proxy Can Reach Them")
            .bind(() -> false, value -> {})
            .defaultValue(false)
            .resettable(false)
            .build();
        networkSections.clear();
        networkSections.add(new Setting.Builder("Network")
            .addOption(nameOption)
            .addOption(entryPortOption)
            .addOption(reSyncOption)
            .addOption(firewallVerifiedOption)
            .build());
    }

    private void bootstrapMembers() {
        ServerModels.ClientServerView proxy = selected.stream().filter(NetworkCreationScreen::isProxy).findFirst().orElse(null);
        if (proxy == null) addNewMember(true, false);
        else addExistingMember(proxy, true);
        selected.stream().filter(server -> !isProxy(server)).forEach(server -> addExistingMember(server, false));
        if (members.stream().noneMatch(member -> !member.proxy)) addNewMember(false, false);
    }

    private void addExistingMember(ServerModels.ClientServerView server, boolean proxy) {
        Member member = new Member(proxy, server, proxy ? "proxy" : route(displayName(server)), proxy ? NetworkMemberRole.PROXY : NetworkMemberRole.GAMEPLAY);
        member.host = host.resolveRemoteHost(server, null);
        member.reactorExisting = reactor(server) || host.serverManagerMode() == ServerScreenHost.ServerManagerMode.REACTOR_ONLY;
        member.panelExisting = !member.reactorExisting && panel(server);
        members.add(member);
        attachMember(member, false);
    }

    private void addNewMember(boolean proxy, boolean select) {
        if (closed || creating || pendingPlan != null || pendingHosted != null || proxy && members.stream().anyMatch(member -> member.proxy)) return;
        int number = proxy ? 1 : ++backendNumber;
        String name = proxy ? "Proxy" : "Backend " + number;
        Member member = new Member(proxy, null, proxy ? "proxy" : route(name), proxy ? NetworkMemberRole.PROXY : members.stream().noneMatch(current -> !current.proxy) ? NetworkMemberRole.LOBBY : NetworkMemberRole.GAMEPLAY);
        members.add(member);
        attachMember(member, select);
        rebuildNetwork();
        refreshHeader();
        loadMember(member, name);
    }

    private void loadMember(Member member, String name) {
        member.failure = "";
        buildMember(member);
        long requestGeneration = generation;
        long draftRevision = ++member.draftRevision;
        String requestAccount = account;
        Async<ServerConfigurationDraft> request;
        try {
            request = ServerConfigurationDraft.create(this, host, member.proxy ? "VELOCITY" : "PAPER", name,
                member.host, member.poolView, member.poolView == null ? null : poolOptions);
        } catch (RuntimeException error) {
            request = Async.failed(error);
        }
        request.whenComplete((draft, error) -> ScreenManager.getInstance().execute(() -> {
            if (!current(member, requestGeneration, requestAccount) || draftRevision != member.draftRevision
                    || member.existing != null || member.external) {
                if (draft != null) draft.close();
                return;
            }
            if (error != null) member.failure = rootMessage(error);
            else {
                member.draft = draft;
                draft.onChanged(() -> {
                    if (closed || !members.contains(member)) return;
                    buildMember(member);
                    rebuildNetwork();
                    refreshHeader();
                });
            }
            buildMember(member);
            refreshHeader();
        }));
    }

    private void loadHosts() {
        loadingHosts = true;
        hostFailure = "";
        long request = ++hostsRevision;
        String requestAccount = account;
        Async<List<ServerScreenHost.HostView>> lookup;
        try {
            lookup = host.remoteHosts();
        } catch (RuntimeException error) {
            lookup = Async.failed(error);
        }
        lookup.whenComplete((values, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed || request != hostsRevision || !Objects.equals(requestAccount, accountKey())) return;
            loadingHosts = false;
            if (error != null) hostFailure = rootMessage(error);
            else remoteHosts = values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
            members.forEach(this::buildMember);
        }));
    }

    private void loadPools() {
        if (closed || creating || pendingPlan != null || pendingHosted != null) return;
        loadingPools = true;
        poolFailure = "";
        long request = ++poolsRevision;
        String requestAccount = account;
        Async<ResourcePoolModels.DraftOptions> lookup;
        try {
            lookup = host.resourcePools().getDraftOptions();
        } catch (RuntimeException error) {
            lookup = Async.failed(error);
        }
        lookup.whenComplete((options, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed || request != poolsRevision || !Objects.equals(requestAccount, accountKey())) return;
            if (error != null || options == null) {
                loadingPools = false;
                poolFailure = error == null ? "Server Images Are Unavailable" : rootMessage(error);
                members.forEach(this::buildMember);
                return;
            }
            poolOptions = options;
            loadPoolPage(0, request, requestAccount);
        }));
    }

    private void loadPoolPage(int page, long request, String requestAccount) {
        loadingPools = true;
        Async<ResourcePoolModels.Page<ResourcePoolModels.Pool>> lookup;
        try {
            lookup = host.resourcePools().listPools(page, 50);
        } catch (RuntimeException error) {
            lookup = Async.failed(error);
        }
        lookup.whenComplete((result, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed || request != poolsRevision || !Objects.equals(requestAccount, accountKey())) return;
            loadingPools = false;
            if (error != null || result == null) {
                poolFailure = error == null ? "Resource Pools Are Unavailable" : rootMessage(error);
            } else {
                if (page == 0) pools.clear();
                for (ResourcePoolModels.Pool pool : result.items()) {
                    if (pools.stream().noneMatch(current -> current.id().equals(pool.id()))) pools.add(pool);
                }
                morePools = result.hasMore();
                nextPoolPage = result.page() + 1;
                poolFailure = "";
            }
            members.forEach(this::buildMember);
        }));
    }

    private void loadMorePools() {
        if (closed || creating || pendingPlan != null || pendingHosted != null || loadingPools || !morePools) return;
        loadPoolPage(nextPoolPage, poolsRevision, account);
        members.forEach(this::buildMember);
    }

    private void choosePool(Member member) {
        if (!Objects.equals(account, accountKey())) {
            new Notification("Account Changed", "Reopen Network Setup To Choose A Resource Pool", Notification.Type.WARN);
            return;
        }
        if (loadingPools) {
            new Notification("Resource Pools Are Loading", "Wait For The Pool List To Load", Notification.Type.INFO);
            return;
        }
        if (poolOptions == null && poolFailure.isBlank()) {
            loadPools();
            new Notification("Loading Resource Pools", "Choose A Pool When The List Is Ready", Notification.Type.INFO);
            return;
        }
        if (poolOptions == null || pools.isEmpty()) {
            new Notification("No Resource Pools", poolFailure.isBlank() ? "Refresh Resource Pools Before Choosing A Pool" : poolFailure,
                Notification.Type.WARN);
            return;
        }
        ResourcePoolModels.Pool[] selected = {member.poolView == null ? pools.getFirst() : pools.stream()
            .filter(pool -> pool.id().equals(member.poolView.pool().id())).findFirst().orElse(pools.getFirst())};
        SettingsForm.open(this, close -> {
            SettingsForm.Builder form = new SettingsForm.Builder("Choose Resource Pool");
            form.onClose(close);
            form.addDropdown("Pool", List.copyOf(pools), selected[0], NetworkCreationScreen::poolLabel, value -> selected[0] = value);
            form.addTitleAction("Use Pool", () -> {
                close.run();
                if (member.poolView != null && member.poolView.pool().id().equals(selected[0].id())) return;
                loadSelectedPool(member, selected[0]);
            }, PopupWidget.TitleActionRole.PRIMARY);
            return form.build();
        });
    }

    private void loadSelectedPool(Member member, ResourcePoolModels.Pool selected) {
        if (closed || creating || pendingPlan != null || pendingHosted != null || !members.contains(member)) return;
        long requestGeneration = generation;
        long lookupRevision = ++member.lookupRevision;
        String requestAccount = account;
        Async<ResourcePoolModels.Pool> lookup;
        try {
            lookup = host.resourcePools().getPool(selected.id());
        } catch (RuntimeException error) {
            lookup = Async.failed(error);
        }
        lookup.whenComplete((pool, error) -> ScreenManager.getInstance().execute(() -> {
            if (!current(member, requestGeneration, requestAccount) || lookupRevision != member.lookupRevision) return;
            if (error != null || pool == null) {
                new Notification("Resource Pool Unavailable", error == null ? "Refresh Resource Pools And Try Again" : rootMessage(error), Notification.Type.ERROR);
                return;
            }
            confirmSwitch(member, () -> switchPool(member, pool));
        }));
    }

    private void chooseNewHost(Member member) {
        if (!Objects.equals(account, accountKey())) {
            new Notification("Account Changed", "Reopen Network Setup To Choose A Server Host", Notification.Type.WARN);
            return;
        }
        if (loadingHosts) {
            new Notification("Hosts Are Loading", "Wait For The Host List To Load", Notification.Type.INFO);
            return;
        }
        List<ServerScreenHost.HostView> choices = new ArrayList<>();
        choices.add(LOCAL_HOST);
        remoteHosts.stream().filter(value -> !value.panel() && "SSH".equalsIgnoreCase(value.type())).forEach(choices::add);
        ServerScreenHost.HostView[] selected = {member.host == null ? LOCAL_HOST : member.host};
        SettingsForm.open(this, close -> {
            SettingsForm.Builder form = new SettingsForm.Builder("Choose Server Host");
            form.onClose(close);
            form.addDropdown("Host", choices, selected[0], ServerScreenHost.HostView::name, value -> selected[0] = value);
            form.addTitleAction("Use Host", () -> {
                close.run();
                requestNewHost(member, selected[0] == LOCAL_HOST ? null : selected[0]);
            }, PopupWidget.TitleActionRole.PRIMARY);
            return form.build();
        });
    }

    private void chooseExistingHost(Member member) {
        if (!Objects.equals(account, accountKey())) {
            new Notification("Account Changed", "Reopen Network Setup To Choose A Server", Notification.Type.WARN);
            return;
        }
        if (loadingHosts) {
            new Notification("Hosts Are Loading", "Wait For The Host List To Load", Notification.Type.INFO);
            return;
        }
        List<ServerScreenHost.HostView> choices = new ArrayList<>();
        choices.add(LOCAL_HOST);
        choices.addAll(remoteHosts);
        ServerScreenHost.HostView[] selected = {member.host == null ? LOCAL_HOST : member.host};
        SettingsForm.open(this, close -> {
            SettingsForm.Builder form = new SettingsForm.Builder("Find Existing Server");
            form.onClose(close);
            form.addDropdown("Host", choices, selected[0], ServerScreenHost.HostView::name, value -> selected[0] = value);
            form.addTitleAction("Browse Servers", () -> {
                close.run();
                loadExisting(member, selected[0] == LOCAL_HOST ? null : selected[0], false);
            }, PopupWidget.TitleActionRole.PRIMARY);
            return form.build();
        });
    }

    private void loadExisting(Member member, ServerScreenHost.HostView selectedHost, boolean reactor) {
        if (closed || creating || !members.contains(member)) return;
        long requestGeneration = generation;
        long lookupRevision = ++member.lookupRevision;
        String requestAccount = account;
        Async<List<ServerModels.ClientServerView>> lookup;
        try {
            lookup = reactor ? host.restudioServers() : selectedHost == null ? host.localServers() : host.hostServers(selectedHost);
        } catch (RuntimeException error) {
            lookup = Async.failed(error);
        }
        lookup.whenComplete((values, error) -> ScreenManager.getInstance().execute(() -> {
            if (!current(member, requestGeneration, requestAccount) || lookupRevision != member.lookupRevision) return;
            if (tabs().getActiveTab() != member.tab) return;
            if (error != null) {
                new Notification("Server List Unavailable", rootMessage(error), Notification.Type.ERROR);
                return;
            }
            List<ServerModels.ClientServerView> available = (values == null ? List.<ServerModels.ClientServerView>of() : values).stream()
                .filter(Objects::nonNull)
                .filter(server -> !serverId(server).isBlank() && isProxy(server) == member.proxy)
                .filter(server -> members.stream().noneMatch(other -> other != member && other.existing != null
                    && serverId(other.existing).equals(serverId(server))))
                .toList();
            if (available.isEmpty()) {
                new Notification("No Available Servers", member.proxy ? "This Host Has No Velocity Proxy To Add" : "This Host Has No Backend To Add", Notification.Type.WARN);
                return;
            }
            chooseExistingServer(member, selectedHost, available, reactor);
        }));
    }

    private void chooseExistingServer(Member member, ServerScreenHost.HostView selectedHost,
                                      List<ServerModels.ClientServerView> available, boolean reactorSource) {
        ServerModels.ClientServerView[] selected = {available.getFirst()};
        SettingsForm.open(this, close -> {
            SettingsForm.Builder form = new SettingsForm.Builder("Choose Existing Server");
            form.onClose(close);
            form.addDropdown("Server", available, selected[0], NetworkCreationScreen::displayName, value -> selected[0] = value);
            form.addTitleAction("Use Server", () -> {
                close.run();
                if (member.proxy && !reactorSource && panel(selected[0])) {
                    new Notification("Panel Proxy Unavailable", "Choose A Proxy With Atomic Configuration Access", Notification.Type.WARN);
                    return;
                }
                if (member.existing != null && serverId(member.existing).equals(serverId(selected[0]))
                    && Objects.equals(member.host, selectedHost)
                    && member.reactorExisting == (reactorSource || !panel(selected[0]) && reactor(selected[0]))) return;
                confirmSwitch(member, () -> switchExisting(member, selectedHost, selected[0], reactorSource));
            }, PopupWidget.TitleActionRole.PRIMARY);
            return form.build();
        });
    }

    private void requestNewHost(Member member, ServerScreenHost.HostView selectedHost) {
        if (member.existing == null && member.poolView == null && !member.external && Objects.equals(member.host, selectedHost)) return;
        confirmSwitch(member, () -> switchNew(member, selectedHost));
    }

    private void confirmSwitch(Member member, Runnable change) {
        if (closed || creating || pendingPlan != null || pendingHosted != null || !members.contains(member)) return;
        boolean endpointEdited = !member.proxy && (member.address.isModified() || member.preferredPort.isModified()
            || member.external && member.externalName.isModified());
        if (member.draft == null && !endpointEdited) {
            change.run();
            return;
        }
        SettingsForm.open(this, close -> {
            SettingsForm.Builder form = new SettingsForm.Builder("Replace Server Configuration");
            form.onClose(close);
            form.addRow(new PopupWidget.PopupRow.Builder("Current Configuration", message("Configuration Will Be Replaced",
                member.draft != null && member.draft.hasPendingChanges() ? "Switching The Server Source Discards Unsaved Changes"
                    : endpointEdited ? "Switching The Server Source Clears The Backend Address And Port"
                    : "Switching The Server Source Replaces Its Current Configuration")).build());
            form.addTitleAction("Discard And Switch", () -> {
                close.run();
                change.run();
            }, PopupWidget.TitleActionRole.DESTRUCTIVE);
            return form.build();
        });
    }

    private void switchExisting(Member member, ServerScreenHost.HostView selectedHost,
                                ServerModels.ClientServerView server, boolean reactorSource) {
        if (closed || creating || !members.contains(member) || server == null || !Objects.equals(account, accountKey())) return;
        member.lookupRevision++;
        member.clearDraft();
        member.clearEndpoint();
        member.existing = server;
        member.external = false;
        member.host = selectedHost;
        member.poolView = null;
        member.poolCreation = null;
        member.reactorExisting = reactorSource || !panel(server) && reactor(server);
        member.panelExisting = !member.reactorExisting && panel(server);
        member.failure = "";
        member.tab.setName(member.title());
        buildMember(member);
        rebuildNetwork();
        refreshHeader();
    }

    private void switchNew(Member member, ServerScreenHost.HostView selectedHost) {
        if (closed || creating || !members.contains(member) || !Objects.equals(account, accountKey())) return;
        member.lookupRevision++;
        String name = member.title();
        member.clearDraft();
        member.clearEndpoint();
        member.existing = null;
        member.external = false;
        member.host = selectedHost;
        member.poolView = null;
        member.poolCreation = null;
        member.reactorExisting = false;
        member.panelExisting = false;
        member.tab.setName(name);
        loadMember(member, name);
        rebuildNetwork();
        refreshHeader();
    }

    private void switchPool(Member member, ResourcePoolModels.Pool pool) {
        if (closed || creating || pendingPlan != null || pendingHosted != null || !members.contains(member) || !Objects.equals(account, accountKey())) return;
        member.lookupRevision++;
        String name = member.title();
        member.clearDraft();
        member.clearEndpoint();
        member.existing = null;
        member.external = false;
        member.host = null;
        member.reactorExisting = false;
        member.panelExisting = false;
        member.poolView = new ResourcePoolController.PoolView(pool, List.of(), List.of(), Map.of());
        member.poolCreation = new ResourcePoolController.Creation(pool.id(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        member.tab.setName(name);
        loadMember(member, name);
        rebuildNetwork();
        refreshHeader();
    }

    private void switchExternal(Member member) {
        if (closed || creating || pendingPlan != null || pendingHosted != null || !members.contains(member) || !Objects.equals(account, accountKey())) return;
        member.lookupRevision++;
        String name = member.title();
        member.clearDraft();
        member.clearEndpoint();
        member.existing = null;
        member.external = true;
        member.host = null;
        member.poolView = null;
        member.poolCreation = null;
        member.reactorExisting = false;
        member.panelExisting = false;
        member.externalName.set(name);
        member.tab.setName(name);
        buildMember(member);
        rebuildNetwork();
        refreshHeader();
    }

    private boolean current(Member member, long requestGeneration, String requestAccount) {
        return !closed && requestGeneration == generation && members.contains(member)
            && Objects.equals(requestAccount, account) && Objects.equals(account, accountKey());
    }

    private String accountKey() {
        ServerScreenHost.AccountIdentity identity = host.accountIdentity();
        return identity.authenticated() + ":" + identity.subjectId();
    }

    private void attachMember(Member member, boolean select) {
        member.container = createContentContainer();
        member.tab = tabs().addTab(member.title(), member.container, member.proxy ? "network.png" : "server.png");
        member.tab.setData(member);
        buildMember(member);
        if (select) tabs().setActiveTab(member.container);
    }

    private void buildMember(Member member) {
        if (member.container == null) return;
        member.container.detachWidgets();
        if (pendingHosted != null || pendingPlan != null && pendingPlan.hosted()) {
            Setting.Builder locked = new Setting.Builder("Network Request");
            locked.addRow("", message(member.title(), "The Original Reactor Request Is Saved For Retry. Reopen Network Setup To Change Its Configuration"));
            member.container.addWidget(locked.build());
            filter(member.container, searchText());
            member.container.updateWidgetPositions();
            return;
        }
        Setting.Builder source = new Setting.Builder("Server Source");
        source.addRow("", message(member.external ? "External Backend" : member.existing == null ? "Create New Server" : "Existing Server",
            member.provider() + " • " + member.title()));
        if (member.existing == null) {
            source.addRow("", new IconButton.Builder().label("Choose Host").imagePath("server.png").size(150, 20)
                .onClick(() -> chooseNewHost(member)).build());
        } else {
            source.addRow("", new IconButton.Builder().label("Create New Server").imagePath("server.png").size(150, 20)
                .onClick(() -> requestNewHost(member, null)).build());
        }
        source.addRow("", new IconButton.Builder().label("Choose Existing Server").imagePath("server.png").size(180, 20)
            .onClick(() -> chooseExistingHost(member)).build());
        source.addRow("", new IconButton.Builder().label("Choose Reactor Server").imagePath("server.png").size(180, 20)
            .onClick(() -> loadExisting(member, null, true)).build());
        source.addRow("", new IconButton.Builder().label("Choose Resource Pool").imagePath("pool.png").size(180, 20)
            .onClick(() -> choosePool(member)).build());
        if (!member.proxy && !member.external) source.addRow("", new IconButton.Builder().label("Add External Backend").imagePath("server.png").size(180, 20)
            .onClick(() -> confirmSwitch(member, () -> switchExternal(member))).build());
        if (morePools) source.addRow("", new IconButton.Builder().label("Load More Pools").imagePath("add.png").size(150, 20)
            .onClick(this::loadMorePools).build());
        if (!hostFailure.isBlank()) {
            source.addRow("", message("Remote Hosts Unavailable", hostFailure));
            source.addRow("", new IconButton.Builder().label("Retry Hosts").imagePath("refresh.png").size(120, 20)
                .onClick(this::loadHosts).build());
        }
        if (!poolFailure.isBlank()) {
            source.addRow("", message("Resource Pools Unavailable", poolFailure));
            source.addRow("", new IconButton.Builder().label("Retry Pools").imagePath("refresh.png").size(120, 20)
                .onClick(this::loadPools).build());
        }
        member.container.addWidget(source.build());
        if (!member.proxy) {
            Setting.Builder networking = new Setting.Builder("Networking");
            networking.addOption(member.route);
            networking.addOption(member.role);
            if (member.external) networking.addOption(member.externalName);
            if (!member.hosted()) {
                networking.addOption(member.address);
                networking.addOption(member.preferredPort);
            }
            if (member.panelExisting || member.external) {
                networking.addRow("", message("External Backend",
                    "Use Its Assigned Address And Port. Configure Forwarding And Restrict Access To The Proxy In The Panel"));
            }
            Setting section = networking.build();
            section.fitContentHeight();
            member.container.addWidget(section);
        }
        if (member.existing != null) {
            Setting.Builder existing = new Setting.Builder("Server");
            existing.addRow("", message(displayName(member.existing), serverDescription(member.existing)));
            member.container.addWidget(existing.build());
        } else if (member.external) {
            Setting.Builder external = new Setting.Builder("External Backend");
            external.addRow("", message("Managed Outside Remotely", "The Provider Controls Files, Power, And Forwarding"));
            member.container.addWidget(external.build());
        } else if (member.draft == null) {
            Setting.Builder status = new Setting.Builder(member.failure.isBlank() ? "Loading Server Configuration" : "Configuration Unavailable");
            status.addRow("", message(member.failure.isBlank() ? "Preparing " + member.title() : "Could Not Prepare " + member.title(),
                member.failure.isBlank() ? "Loading The Server Software And Resource Options" : member.failure));
            if (!member.failure.isBlank()) {
                status.addRow("", new IconButton.Builder().label("Retry").imagePath("refresh.png").size(80, 20)
                    .onClick(() -> loadMember(member, member.title())).build());
            }
            member.container.addWidget(status.build());
        } else {
            if (!member.draft.failure().isBlank()) {
                Setting.Builder status = new Setting.Builder(member.draft.failure());
                status.addRow("", new IconButton.Builder().label("Retry").imagePath("refresh.png").size(80, 20)
                    .onClick(() -> member.draft.reload(host)).build());
                member.container.addWidget(status.build());
            }
            for (Setting section : member.draft.sections()) {
                section.fitContentHeight();
                member.container.addWidget(section);
            }
        }
        filter(member.container, searchText());
        member.container.updateWidgetPositions();
    }

    private void rebuildNetwork() {
        if (networkContainer == null) return;
        networkContainer.clearWidgets();
        if (pendingHosted != null || pendingPlan != null && pendingPlan.hosted()) {
            Setting.Builder request = new Setting.Builder("Reactor Network Request");
            if (pendingHosted != null && pendingHosted.terminal() != null) {
                request.addRow("", message("Network Needs Recovery", "Open The Network To Review Its Outcome Before Starting Another Network"));
                request.addRow("", new IconButton.Builder().label("Acknowledge Outcome And Start New")
                    .imagePath("checkmark.png").size(240, 20).onClick(this::acknowledgeHostedOutcome).build());
            } else {
                request.addRow("", message("Resume The Original Request", "The Same Network And Server Draft Identities Will Be Used"));
            }
            networkContainer.addWidget(request.build());
        } else {
            for (Setting section : networkSections) {
                section.fitContentHeight();
                networkContainer.addWidget(section);
            }
        }
        Setting.Builder topology = new Setting.Builder("Servers");
        for (Member member : members) {
            String description = (member.external ? "External" : member.existing == null ? "New" : "Existing") + " • " + member.provider() + " • "
                + (member.proxy ? "Velocity Proxy" : roleName(member.role.get()) + " • " + member.route.get() + " • " + member.endpoint());
            MountableButtonWidget row = message(member.title(), description + " • Click To Configure");
            row.setOnClick(() -> tabs().setActiveTab(member.container));
            topology.addRow("", row);
        }
        networkContainer.addWidget(topology.build());
        Setting.Builder review = new Setting.Builder("Review");
        review.addRow("", message("Check Server Sources", "Confirm Each Host, Server, And Network Role Before Creating The Network"));
        boolean hosted = members.stream().anyMatch(Member::hosted);
        if (hosted && members.stream().anyMatch(member -> !member.hosted() && !member.panelExisting && !member.external)) {
            review.addRow("", message("Choose One Network Authority", "Reactor Servers Can Include External Panel Backends, But Not Local Or SSH Members"));
        }
        for (Member member : members) {
            if (!member.proxy) review.addRow("", message(member.title(), member.endpoint()));
        }
        if (members.stream().anyMatch(member -> member.panelExisting || member.external)) {
            review.addRow("", message("External Backends", "Their Provider Controls Server Files, Power, And Forwarding"));
        }
        networkContainer.addWidget(review.build());
        filter(networkContainer, searchText());
        networkContainer.updateWidgetPositions();
    }

    private void selectTab(TabsManager.Tab tab) {
        if (tab == null) return;
        setActiveContainer(tab.getContainer());
        filter(tab.getContainer(), searchText());
    }

    private boolean canCloseTab(TabsManager.Tab tab) {
        return !creating && pendingPlan == null && pendingHosted == null && tab != null && tab.getData() instanceof Member member && !member.proxy;
    }

    private void tabClosed(TabsManager.Tab tab) {
        if (tab == null || !(tab.getData() instanceof Member member)) return;
        members.remove(member);
        member.close();
        rebuildNetwork();
        refreshHeader();
    }

    private void filterActiveTab(String query) {
        TabsManager.Tab tab = tabs().getActiveTab();
        if (tab != null) filter(tab.getContainer(), query);
    }

    private void filter(Container container, String query) {
        if (container == null) return;
        for (AnimatedWidget widget : container.getWidgets()) {
            if (widget instanceof Setting setting) setting.filter(query);
        }
        container.updateWidgetPositions();
        container.resetScroll();
    }

    private String searchText() {
        return header().searchBox == null ? "" : header().searchBox.getText();
    }

    private void create() {
        if (creating || closed) return;
        if (!Objects.equals(account, accountKey())) {
            fail("Account Changed. Reopen Network Setup Before Creating A Network");
            return;
        }
        if (createdNetworkId != null) {
            openCreatedNetwork(createdNetworkId);
            return;
        }
        if (recoveryNetworkId != null) {
            openCreatedNetwork(recoveryNetworkId);
            return;
        }
        NetworkCreationPlan plan;
        HostedNetworkPendingStore.Pending hosted = pendingHosted;
        try {
            plan = hosted != null ? null : pendingPlan == null ? plan() : pendingPlan;
        } catch (RuntimeException error) {
            fail(rootMessage(error));
            return;
        }
        if (plan != null && plan.hosted()) pendingPlan = plan;
        String networkName = hosted == null ? plan.name() : hosted.command().name();
        boolean hostedCreation = hosted != null || plan.hosted();
        creating = true;
        refreshHeader();
        long requestGeneration = ++generation;
        Notification notification = new Notification.Builder()
            .message("Creating Network")
            .description(networkName)
            .type(Notification.Type.INFO)
            .loading(true)
            .autoSlideOut(false)
            .build();
        Async<String> request;
        try {
            request = hosted == null ? host.createNetwork(plan) : host.resumeHostedNetwork();
        } catch (RuntimeException error) {
            request = Async.failed(error);
        }
        request.whenComplete((proxyId, error) -> ScreenManager.getInstance().execute(() -> {
            HostedNetworkClient.OperationFailure operation = operationFailure(error);
            if (error == null) {
                notification.update().message("Network Created").description(networkName).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            } else {
                notification.update().message(operation == null ? "Network Creation Failed" : "Network Needs Recovery")
                    .description(rootMessage(error)).type(operation == null ? Notification.Type.ERROR : Notification.Type.WARN)
                    .loading(false).autoSlideOut(true).commit();
            }
            if (closed || requestGeneration != generation) return;
            creating = false;
            if (error == null) {
                pendingHosted = null;
                pendingPlan = null;
            } else if (hostedCreation) {
                try {
                    pendingHosted = host.pendingHostedNetwork();
                } catch (RuntimeException ignored) {
                    pendingHosted = hosted;
                }
                if (operation != null) pendingPlan = null;
            }
            if (error != null) {
                if (operation == null) fail(rootMessage(error));
                else {
                    recoveryNetworkId = host.hostedNetworkViewId(operation.status().networkId());
                    createdHosted = true;
                }
                if (pendingPlan != null || pendingHosted != null) {
                    members.forEach(this::buildMember);
                    rebuildNetwork();
                }
                refreshHeader();
                return;
            }
            createdNetworkId = proxyId;
            createdHosted = hostedCreation;
            refreshHeader();
            openCreatedNetwork(proxyId);
        }));
    }

    private void openCreatedNetwork(String proxyId) {
        if (createdHosted) {
            host.openNetworkSettings(parent, proxyId);
            return;
        }
        creating = true;
        refreshHeader();
        Async<List<ServerScreenHost.NetworkView>> request;
        try {
            request = host.networks();
        } catch (RuntimeException error) {
            request = Async.failed(error);
        }
        request.whenComplete((networks, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed) return;
            creating = false;
            ServerScreenHost.NetworkView network = error == null && networks != null
                ? networks.stream().filter(candidate -> proxyId.equals(candidate.proxyId())).findFirst().orElse(null) : null;
            if (network == null) {
                new Notification("Network Created", "Open The Network From Server Manager To View Its Topology", Notification.Type.WARN);
                refreshHeader();
                return;
            }
            ScreenManager.getInstance().replaceScreen(this, new NetworkOverviewScreen(parent, host.networkOverviewProvider(), network.id()));
        }));
    }

    private void acknowledgeHostedOutcome() {
        if (creating || closed || pendingHosted == null || pendingHosted.terminal() == null
                || !Objects.equals(account, accountKey())) return;
        try {
            host.acknowledgeHostedNetwork(pendingHosted.command().requestId(), pendingHosted.command().networkId());
        } catch (RuntimeException error) {
            fail(rootMessage(error));
            return;
        }
        pendingHosted = null;
        pendingPlan = null;
        recoveryNetworkId = null;
        createdHosted = false;
        requestId = UUID.randomUUID();
        if (members.isEmpty()) bootstrapMembers();
        members.forEach(this::buildMember);
        rebuildNetwork();
        refreshHeader();
    }

    private NetworkCreationPlan plan() {
        if (!Objects.equals(account, accountKey())) throw new IllegalStateException("Account Changed. Reopen Network Setup Before Creating A Network");
        if (members.stream().filter(member -> member.proxy).count() != 1) throw new IllegalArgumentException("One Proxy Is Required");
        if (members.stream().noneMatch(member -> !member.proxy)) throw new IllegalArgumentException("Add At Least One Backend");
        if (members.stream().anyMatch(member -> member.existing == null && member.draft == null && !member.external)) {
            throw new IllegalStateException("Wait For Every Server Configuration To Load");
        }
        boolean hosted = members.stream().anyMatch(Member::hosted);
        if (hosted && members.stream().anyMatch(member -> !member.hosted() && !member.panelExisting && !member.external)) {
            throw new IllegalArgumentException("Choose Reactor For Every Managed Member. External Panel Backends May Join With An Address And Port");
        }
        if (!hosted && members.stream().anyMatch(member -> member.external)) {
            throw new IllegalArgumentException("An External Backend Without An Imported Panel Server Needs A Reactor Network");
        }
        if (members.stream().anyMatch(member -> member.proxy && member.panelExisting)) {
            throw new IllegalArgumentException("A Panel Proxy Needs Atomic Configuration Access Before It Can Be Managed");
        }
        if (members.stream().anyMatch(member -> (member.panelExisting || member.external) && !member.proxy
                && ((member.address.get() == null || member.address.get().isBlank())
                || member.preferredPort.get() == null || member.preferredPort.get() == 0))) {
            throw new IllegalArgumentException("Set A Proxy-Reachable Address And Assigned Port For Every External Backend");
        }
        if (members.stream().anyMatch(member -> member.external
                && (member.externalName.get() == null || member.externalName.get().isBlank()))) {
            throw new IllegalArgumentException("Every External Backend Needs A Server Name");
        }
        if (members.stream().anyMatch(member -> member.panelExisting || member.external) && !firewallVerifiedOption.get()) {
            throw new IllegalArgumentException("Verify External Backend Ports Are Private Or Firewalled So Only The Proxy Can Reach Them");
        }
        String name = nameOption.get() == null ? "" : nameOption.get().trim();
        if (name.isBlank()) throw new IllegalArgumentException("Network Name Is Required");
        int entryPort = entryPortOption.get();
        if (entryPort < 1 || entryPort > 65535) throw new IllegalArgumentException("Player Port Must Be Between 1 And 65535");
        Set<String> routes = new LinkedHashSet<>();
        for (Member member : members) {
            if (member.draft != null) member.draft.apply();
            if (member.proxy) {
                String loader = member.existing == null ? String.valueOf(member.draft.target().modLoader()) : member.existing.loader;
                if (!"VELOCITY".equalsIgnoreCase(loader)) throw new IllegalArgumentException("The Proxy Must Use Velocity");
            } else {
                String routeName = member.route.get() == null ? "" : member.route.get().trim();
                if (routeName.isBlank()) throw new IllegalArgumentException("Every Backend Needs A Route Name");
                if (!routes.add(routeName.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Backend Route Names Must Be Unique");
            }
        }
        boolean installReSync = reSyncOption.get();
        List<NetworkCreationPlan.Server> servers = members.stream().map(member -> member.plan(installReSync, hosted)).toList();
        return new NetworkCreationPlan(name, entryPort, servers, firewallVerifiedOption.get(), requestId);
    }

    private void refreshHeader() {
        if (createButton == null || cancelButton == null) return;
        boolean ready = !creating && (recoveryNetworkId != null || createdNetworkId != null || pendingHosted != null || pendingPlan != null
            || members.stream().anyMatch(member -> member.proxy) && members.stream().anyMatch(member -> !member.proxy)
            && members.stream().noneMatch(member -> member.existing == null && !member.external
                && (member.draft == null || !member.draft.ready())));
        createButton.setMessage(creating ? createdNetworkId == null ? "Creating Network" : "Opening Network"
            : recoveryNetworkId != null ? "Open Network Recovery"
            : createdNetworkId == null ? pendingHosted != null ? "Resume Network Request"
                : pendingPlan == null ? "Create Network" : "Retry Network Request" : "Open Network");
        createButton.setIcon(creating ? Identifier.animatedIcon("loadingGreen.png") : Identifier.icon("checkmark.png"));
        createButton.setActive(ready);
        cancelButton.setActive(!creating);
    }

    private void fail(String message) {
        new Notification("Network Setup", message, Notification.Type.ERROR);
    }

    private void cancel() {
        if (creating || closed) return;
        close();
    }

    @Override
    public void close() {
        if (creating || closed) return;
        ScreenManager.getInstance().goBack(this, parent);
    }

    @Override
    public void onDesktopWindowClosing() {
        if (creating) return;
        closed = true;
        generation++;
        closeDrafts();
    }

    private void closeDrafts() {
        members.forEach(Member::close);
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (creating) return true;
        if (event.key() == ReKey.ESCAPE) {
            cancel();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        return creating || super.mouseClicked(event);
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        return creating || super.textInput(event);
    }

    @Override
    public void removed() {
        if (!closed) {
            closed = true;
            generation++;
            closeDrafts();
        }
        super.removed();
    }

    private MountableButtonWidget message(String title, String description) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(title).description(description).build();
        row.setHeight(30);
        return row;
    }

    private static String serverId(ServerModels.ClientServerView server) {
        if (server == null) return "";
        if (server.identifier != null && !server.identifier.isBlank()) return server.identifier;
        return server.uuid == null ? "" : server.uuid;
    }

    private static boolean isProxy(ServerModels.ClientServerView server) {
        return server != null && "VELOCITY".equalsIgnoreCase(server.loader);
    }

    private boolean reactor(ServerModels.ClientServerView server) {
        String type = host.identity(server).backendType();
        return "RESTUDIO".equalsIgnoreCase(type) || "REACTOR".equalsIgnoreCase(type);
    }

    private boolean panel(ServerModels.ClientServerView server) {
        String type = host.identity(server).backendType();
        return host.isPanel(server) || "PTERODACTYL".equalsIgnoreCase(type);
    }

    private static String displayName(ServerModels.ClientServerView server) {
        return server == null || server.name == null || server.name.isBlank() ? "Server" : server.name;
    }

    private static String serverDescription(ServerModels.ClientServerView server) {
        String software = server.loader == null || server.loader.isBlank() ? "Minecraft Server" : server.loader;
        return software + (server.version == null || server.version.isBlank() ? "" : " • " + server.version);
    }

    private static String poolLabel(ResourcePoolModels.Pool pool) {
        return pool.domain().location() + " • " + pool.domain().cpuClass() + " • " + pool.balance().available().ramMiB()
            + " MiB Free RAM • " + pool.id().toString().substring(0, 8);
    }

    private static String route(String name) {
        String route = name == null ? "server" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return route.isBlank() ? "server" : route;
    }

    private static String roleName(NetworkMemberRole role) {
        String value = role == null ? "Gameplay" : role.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank() ? "Network Creation Failed" : current.getMessage();
    }

    private static HostedNetworkClient.OperationFailure operationFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof HostedNetworkClient.OperationFailure operation) return operation;
        }
        return null;
    }

    private final class Member {
        private final boolean proxy;
        private ServerModels.ClientServerView existing;
        private ServerScreenHost.HostView host;
        private ResourcePoolController.PoolView poolView;
        private ResourcePoolController.Creation poolCreation;
        private boolean reactorExisting;
        private boolean panelExisting;
        private boolean external;
        private final String externalId = "external:" + UUID.randomUUID();
        private final ConfigOption<String> externalName;
        private final ConfigOption<String> route;
        private final ConfigOption<NetworkMemberRole> role;
        private final ConfigOption<String> address;
        private final ConfigOption<Integer> preferredPort;
        private ServerConfigurationDraft draft;
        private Container container;
        private TabsManager.Tab tab;
        private String failure = "";
        private long draftRevision;
        private long lookupRevision;

        private Member(boolean proxy, ServerModels.ClientServerView existing, String route, NetworkMemberRole role) {
            this.proxy = proxy;
            this.existing = existing;
            this.route = ConfigOption.<String>builder("Route Name")
                .description("The Short Name Used By The Proxy And Routing Rules")
                .bind(() -> route, value -> {})
                .defaultValue(route)
                .resettable(false)
                .build();
            this.role = ConfigOption.<NetworkMemberRole>builder("Network Role")
                .description("How This Backend Participates In Player Routing")
                .bind(() -> role, value -> {})
                .options(BACKEND_ROLES)
                .display(NetworkCreationScreen::roleName)
                .defaultValue(role)
                .resettable(false)
                .build();
            this.address = ConfigOption.<String>builder("Backend Address")
                .description("The Address The Proxy Uses To Reach This Backend. Leave Blank When Remotely Can Resolve It")
                .bind(() -> "", value -> {})
                .defaultValue("")
                .resettable(false)
                .build();
            this.externalName = ConfigOption.<String>builder("Server Name")
                .description("The Name Shown For This External Backend")
                .bind(() -> "External Backend", value -> {})
                .defaultValue("External Backend")
                .resettable(false)
                .build();
            this.preferredPort = ConfigOption.<Integer>builder("Backend Port")
                .description("Use 0 For An Automatic Port. Provider Servers Use Their Assigned Port")
                .bind(() -> 0, value -> {})
                .defaultValue(0)
                .range(0, 65535)
                .resettable(false)
                .build();
            this.route.addChangeListener(value -> rebuildNetwork());
            this.role.addChangeListener(value -> rebuildNetwork());
            this.address.addChangeListener(value -> rebuildNetwork());
            this.preferredPort.addChangeListener(value -> rebuildNetwork());
            this.externalName.addChangeListener(value -> {
                if (external && tab != null) tab.setName(title());
                rebuildNetwork();
            });
        }

        private String title() {
            if (existing != null) return displayName(existing);
            if (external) return externalName.get();
            if (draft != null && draft.target().name() != null && !draft.target().name().isBlank()) return draft.target().name();
            return proxy ? "Proxy" : routeName();
        }

        private String provider() {
            if (poolView != null) return "Reactor • " + poolView.pool().domain().location();
            if (external) return "External";
            if (panelExisting) return "External Panel";
            if (reactorExisting) return "Reactor";
            if (host != null) return host.name();
            if (existing == null) return "Local";
            ServerScreenHost.ServerIdentity identity = NetworkCreationScreen.this.host.identity(existing);
            return identity.local() ? "Local" : identity.backendType().isBlank() ? "Remote" : identity.backendType();
        }

        private String routeName() {
            String value = route.get();
            if (value == null || value.isBlank()) return "Backend";
            String display = value.replace('-', ' ').replace('_', ' ');
            return Character.toUpperCase(display.charAt(0)) + display.substring(1);
        }

        private String endpoint() {
            if (hosted()) return "Address And Port: Provider Assigned";
            String value = address.get() == null ? "" : address.get().trim();
            int port = preferredPort.get() == null ? 0 : preferredPort.get();
            return "Address: " + (value.isBlank() ? "Not Set" : value) + " • Port: " + (port == 0
                ? panelExisting || external ? "Required For External Backend" : "Automatic Or Provider Assigned" : port);
        }

        private NetworkCreationPlan.Server plan(boolean installReSync, boolean hostedNetwork) {
            String id = external ? externalId : existing == null ? "" : serverId(existing);
            Object template = draft == null ? null : draft.target().raw();
            String location = draft == null ? "" : draft.location();
            NetworkMemberSource source = poolView != null ? draft.hostedSource(poolCreation)
                : reactorExisting ? new NetworkMemberSource.ExistingServer(id)
                : panelExisting && hostedNetwork ? new NetworkMemberSource.External(id, displayName(existing), address.get())
                : external ? new NetworkMemberSource.External(id, externalName.get(), address.get()) : null;
            return new NetworkCreationPlan.Server(id, template, host, location, draft == null ? null : draft.settings(), proxy,
                proxy ? "proxy" : route.get(), proxy ? NetworkMemberRole.PROXY : role.get(), 0,
                installReSync && !panelExisting && !external, proxy ? "" : address.get(),
                proxy || preferredPort.get() == null ? 0 : preferredPort.get(), source,
                panelExisting || external ? NetworkMemberManagement.EXTERNAL : NetworkMemberManagement.MANAGED);
        }

        private boolean hosted() {
            return poolView != null || reactorExisting;
        }

        private void clearEndpoint() {
            if (proxy) return;
            address.set("");
            preferredPort.set(0);
        }

        private void clearDraft() {
            draftRevision++;
            if (draft != null) {
                draft.close();
                draft = null;
            }
        }

        private void close() {
            lookupRevision++;
            clearDraft();
        }
    }
}
