package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.network.NetworkMemberManagement;
import redxax.oxy.remotely.network.HostedNetworkClient;
import redxax.oxy.remotely.network.HostedNetworkPendingStore;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import redxax.oxy.remotely.network.protocol.NetworkOperationStatus;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.resource.ResourcePoolClient;
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
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DeletionPopup;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class NetworkCreationScreen extends ReScreen implements DesktopWindowBehaviorProvider {
    private static final ServerScreenHost.HostView LOCAL_HOST = new ServerScreenHost.HostView("", "This Computer", "LOCAL", "", 0, true, false);
    private enum Source {
        NEW("New Server"), EXISTING("Existing Server"), EXTERNAL("External Backend");

        private final String label;

        Source(String label) {
            this.label = label;
        }
    }
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
    private final NetworkCreationContext context;
    private final List<ServerModels.ClientServerView> selected;
    private final List<Member> members = new ArrayList<>();
    private final List<Setting> networkSections = new ArrayList<>();
    private List<ServerScreenHost.HostView> remoteHosts = List.of();
    private final List<ResourcePoolModels.Pool> pools = new ArrayList<>();
    private ResourcePoolModels.DraftOptions poolOptions;
    private ResourcePoolClient poolClient;
    private final Map<String, NetworkPoolBudget> budgets = new LinkedHashMap<>();
    private final Map<String, ServerList> serverLists = new LinkedHashMap<>();
    private final EnumMap<PoolAllocationEditor.Resource, ResourceAllocationBarWidget> resourceBars = new EnumMap<>(PoolAllocationEditor.Resource.class);
    private String poolId = "";
    private NetworkPoolBudget budget;
    private Setting capacitySetting;
    private Setting reviewSetting;
    private Setting recoverySetting;
    private MountableButtonWidget capacityStatus;
    private MountableButtonWidget reviewStatus;
    private MountableButtonWidget recoveryStatus;
    private IconButton poolRetry;
    private IconButton acknowledgeButton;
    private IconButton discardButton;
    private long poolSelectionRevision;
    private boolean loadingPool;
    private boolean updatingNetwork;
    private final SearchMode search = new SearchMode(false);
    private Container networkContainer;
    private ConfigOption<String> nameOption;
    private ConfigOption<String> subdomainOption;
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
    private String authenticationSession;
    private String hostFailure = "";
    private boolean loadingHosts;
    private long hostsRevision;
    private long poolsRevision;
    private boolean loadingPools;
    private String poolFailure = "";

    public NetworkCreationScreen(Screen parent, ServerScreenHost host, List<ServerModels.ClientServerView> selected) {
        this(parent, host, selected, inferredContext(host, selected));
    }

    private static NetworkCreationContext inferredContext(ServerScreenHost host, List<ServerModels.ClientServerView> selected) {
        ServerModels.ClientServerView server = selected == null || selected.isEmpty() ? null : selected.getFirst();
        String type = server == null ? "" : host.identity(server).backendType();
        boolean reactor = "RESTUDIO".equalsIgnoreCase(type) || "REACTOR".equalsIgnoreCase(type)
            || host.serverManagerMode() == ServerScreenHost.ServerManagerMode.REACTOR_ONLY;
        ServerScreenHost.HostView remote = reactor || server == null ? null : host.resolveRemoteHost(server, null);
        return new NetworkCreationContext(reactor, remote != null && !remote.panel() ? remote : null);
    }

    public NetworkCreationScreen(Screen parent, ServerScreenHost host, List<ServerModels.ClientServerView> selected, NetworkCreationContext context) {
        this.parent = parent;
        enableNavigation(parent);
        this.host = host;
        this.context = Objects.requireNonNull(context, "Network Destination Is Required");
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
            .resetEntranceAnimation(false)
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
            poolClient = context.reactor() ? host.resourcePools() : null;
            authenticationSession = host.authenticationSession();
            account = accountKey();
            if (context.reactor() && host.accountIdentity().authenticated()) {
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
            if (context.reactor()) loadPools();
            else loadHosts();
        } else {
            for (Member member : members) attachMember(member, false);
        }
        refreshNetwork();
        tabs().setActiveTab(networkContainer);
        refreshHeader();
    }

    private Container createContentContainer() {
        Container container = createContainer(6, 60, width - 12, Math.max(80, height - 66));
        container.layout(new ManagedLayout()).columns(1).padding(4).verticalSpacing(6).scrolling(true).setSearchMode(search);
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
        subdomainOption = context.reactor() ? ConfigOption.<String>builder("Subdomain")
            .description("An Optional ReStudio Subdomain For The Network Proxy")
            .bind(() -> "", value -> {})
            .defaultValue("")
            .resettable(false)
            .build() : null;
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
        Setting.Builder network = new Setting.Builder("Network");
        network.addOption(nameOption);
        if (subdomainOption != null) network.addOption(subdomainOption);
        if (context.reactor()) network.addRow("Player Address", message("Assigned By Reactor", "Players Connect Through The Proxy's Assigned Address And Port"));
        else network.addOption(entryPortOption);
        network.addOption(reSyncOption);
        network.addOption(firewallVerifiedOption);
        networkSections.add(network.build());
        createNetworkPanels();
    }

    private void bootstrapMembers() {
        ServerModels.ClientServerView proxy = selected.stream().filter(NetworkCreationScreen::isProxy).findFirst().orElse(null);
        if (proxy == null) addNewMember(true, false);
        else addExistingMember(proxy, true);
        selected.stream().filter(server -> !isProxy(server)).forEach(server -> addExistingMember(server, false));
        if (members.stream().noneMatch(member -> !member.proxy)) addNewMember(false, false);
    }

    private void createNetworkPanels() {
        poolRetry = new IconButton.Builder().label("Refresh Resources").imagePath("reload.png").size(150, 20).onClick(this::loadPools).build();
        Setting.Builder capacity = new Setting.Builder("Network Resources");
        for (PoolAllocationEditor.Resource resource : List.of(PoolAllocationEditor.Resource.RAM, PoolAllocationEditor.Resource.CPU, PoolAllocationEditor.Resource.DISK)) {
            ResourceAllocationBarWidget bar = new ResourceAllocationBarWidget(resource,
                id -> members.stream().filter(member -> member.id.equals(id)).findFirst().map(Member::title).orElse("New Server"));
            bar.editable(() -> !requestLocked() && !loadingPools && !loadingPool && Objects.equals(account, accountKey()));
            bar.setHeight(22);
            resourceBars.put(resource, bar);
            capacity.addRow(new PopupWidget.PopupRow.Builder("", bar).id(resource.name()).minHeight(22).build());
        }
        capacityStatus = message("Shared Capacity", "Loading Resources");
        capacity.addRow("", capacityStatus);
        capacity.addRow(new PopupWidget.PopupRow.Builder("", poolRetry).id("refresh").build());
        capacitySetting = capacity.build();
        reviewStatus = message("Network", "Add A Proxy And At Least One Backend");
        Setting.Builder review = new Setting.Builder("Network Review");
        review.addRow("", reviewStatus);
        reviewSetting = review.build();
        recoveryStatus = message("Network Request", "The Original Reactor Request Is Saved For Retry");
        acknowledgeButton = new IconButton.Builder().label("Acknowledge Outcome And Start New").imagePath("checkmark.png")
            .size(240, 20).onClick(this::acknowledgeHostedOutcome).build();
        discardButton = new IconButton.Builder().label("Discard Request").imagePath("delete.png")
            .size(150, 20).onClick(this::confirmDiscardHostedRequest).build();
        Setting.Builder recovery = new Setting.Builder("Reactor Network Request");
        recovery.addRow("", recoveryStatus);
        recovery.addRow(new PopupWidget.PopupRow.Builder("", acknowledgeButton).id("acknowledge").build());
        recovery.addRow(new PopupWidget.PopupRow.Builder("", discardButton).id("discard").build());
        recoverySetting = recovery.build();
    }

    private boolean requestLocked() {
        return creating || pendingHosted != null || pendingPlan != null && pendingPlan.hosted();
    }

    private void addExistingMember(ServerModels.ClientServerView server, boolean proxy) {
        Member member = new Member(proxy, server, proxy ? "proxy" : route(displayName(server)), proxy ? NetworkMemberRole.PROXY : NetworkMemberRole.GAMEPLAY);
        member.existingHost = context.reactor() ? null : host.resolveRemoteHost(server, context.host());
        member.reactorExisting = context.reactor();
        member.panelExisting = !member.reactorExisting && panel(server);
        members.add(member);
        attachMember(member, false);
        ensureServerList(member);
    }

    private void addNewMember(boolean proxy, boolean select) {
        if (closed || requestLocked() || proxy && members.stream().anyMatch(member -> member.proxy)) return;
        int number = proxy ? 1 : ++backendNumber;
        String name = proxy ? "Proxy" : "Backend " + number;
        Member member = new Member(proxy, null, proxy ? "proxy" : route(name), proxy ? NetworkMemberRole.PROXY : members.stream().noneMatch(current -> !current.proxy) ? NetworkMemberRole.LOBBY : NetworkMemberRole.GAMEPLAY);
        members.add(member);
        attachMember(member, select);
        ensureDraft(member);
        refreshNetwork();
        refreshHeader();
    }

    private void ensureDraft(Member member) {
        if (closed || requestLocked() || member.source() != Source.NEW) return;
        String key = context.reactor() ? budget == null ? "" : "pool:" + budget.view().pool().id() : hostKey(member.host);
        if (key.isBlank()) {
            member.slot = null;
            buildMember(member);
            return;
        }
        DraftSlot slot = member.drafts.get(key);
        if (slot == null) {
            PoolCreationPreview preview = context.reactor() ? budget.create(member.id, poolShares()) : null;
            slot = new DraftSlot(key, context.reactor() ? budget : null, preview);
            member.drafts.put(key, slot);
        }
        member.slot = slot;
        updateBudgetActivity(member);
        if (slot.draft != null || slot.loading) {
            buildMember(member);
            return;
        }
        loadMember(member, slot);
    }

    private void loadMember(Member member, DraftSlot slot) {
        if (closed || requestLocked() || !members.contains(member)) return;
        slot.failure = "";
        slot.loading = true;
        buildMember(member);
        long requestGeneration = generation;
        long revision = ++slot.revision;
        String requestAccount = account;
        Async<ServerConfigurationDraft> request;
        try {
            request = ServerConfigurationDraft.create(this, host, member.proxy ? "VELOCITY" : "PAPER", member.title(),
                slot.budget == null ? member.host : null, slot.budget == null ? null : slot.budget.view(),
                slot.budget == null ? null : poolOptions, poolShares(), slot.preview);
        } catch (RuntimeException error) {
            request = Async.failed(error);
        }
        request.whenComplete((draft, error) -> ScreenManager.getInstance().execute(() -> {
            if (!current(member, requestGeneration, requestAccount) || revision != slot.revision) {
                if (draft != null) draft.close();
                return;
            }
            slot.loading = false;
            if (error != null) slot.failure = rootMessage(error);
            else {
                slot.draft = draft;
                draft.onChanged(() -> {
                    if (!current(member, requestGeneration, requestAccount) || revision != slot.revision) return;
                    buildMember(member);
                    refreshNetwork();
                    refreshHeader();
                });
            }
            buildMember(member);
            refreshNetwork();
            refreshHeader();
        }));
    }

    private int poolShares() {
        return Math.max(1, (int) members.stream().filter(member -> member.source() == Source.NEW).count());
    }

    private void updateBudgetActivity(Member member) {
        for (DraftSlot slot : member.drafts.values()) {
            if (slot.budget != null) slot.budget.setActive(member.id, member.source() == Source.NEW && slot == member.slot && slot.budget == budget);
        }
    }

    private void loadHosts() {
        if (closed || requestLocked()) return;
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
        if (closed || requestLocked() || loadingPools || loadingPool || !Objects.equals(account, accountKey())) return;
        loadingPools = true;
        poolFailure = "";
        long request = ++poolsRevision;
        String requestAccount = account;
        refreshNetwork();
        Async<ResourcePoolModels.DraftOptions> lookup;
        try {
            lookup = poolClient.getDraftOptions();
        } catch (RuntimeException error) {
            lookup = Async.failed(error);
        }
        Async<ResourcePoolModels.Page<ResourcePoolModels.Pool>> firstPage = poolId.isBlank() ? poolPage(0) : null;
        lookup.whenComplete((options, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed || request != poolsRevision || !Objects.equals(requestAccount, accountKey())) return;
            if (error != null || options == null) {
                loadingPools = false;
                poolFailure = error == null ? "Server Images Are Unavailable" : rootMessage(error);
                refreshNetwork();
                return;
            }
            poolOptions = options;
            if (!poolId.isBlank()) {
                loadingPools = false;
                selectPool(poolId, true);
            } else loadPoolPage(0, request, requestAccount, firstPage);
        }));
    }

    private Async<ResourcePoolModels.Page<ResourcePoolModels.Pool>> poolPage(int page) {
        try {
            return poolClient.listPools(page, 50);
        } catch (RuntimeException error) {
            return Async.failed(error);
        }
    }

    private void loadPoolPage(int page, long request, String requestAccount, Async<ResourcePoolModels.Page<ResourcePoolModels.Pool>> lookup) {
        loadingPools = true;
        lookup.whenComplete((result, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed || request != poolsRevision || !Objects.equals(requestAccount, accountKey())) return;
            if (error != null || result == null) {
                loadingPools = false;
                poolFailure = error == null ? "Resources Are Unavailable" : rootMessage(error);
            } else {
                if (page == 0) pools.clear();
                for (ResourcePoolModels.Pool pool : result.items()) {
                    if (pools.stream().noneMatch(current -> current.id().equals(pool.id()))) pools.add(pool);
                }
                if (result.hasMore()) {
                    if (!result.items().isEmpty()) {
                        loadPoolPage(result.page() + 1, request, requestAccount, poolPage(result.page() + 1));
                        return;
                    }
                    loadingPools = false;
                    poolFailure = "Resources Are Unavailable. Refresh To Try Again";
                } else {
                    loadingPools = false;
                    ResourcePoolModels.Pool selected = pools.stream().max((first, second) -> capacitySlots(first).compareTo(capacitySlots(second))).orElse(null);
                    if (selected == null) poolFailure = "No Reactor Resources Are Available For This Network";
                    else selectPool(selected.id().toString(), false, selected);
                }
            }
            members.forEach(this::buildMember);
            refreshNetwork();
            refreshHeader();
        }));
    }

    private BigInteger capacitySlots(ResourcePoolModels.Pool pool) {
        ResourcePoolModels.Resources available = pool.balance().available();
        BigInteger ram = new BigInteger(available.ramMiB()).divide(BigInteger.valueOf(Math.max(1, poolOptions.minimumInstallerRamMiB())));
        BigInteger cpu = new BigInteger(available.cpuQuotaPercent()).divide(BigInteger.valueOf(Math.max(1, poolOptions.minimumInstallerCpuPercent())));
        return ram.min(cpu).min(new BigInteger(available.diskMiB()));
    }

    private void selectPool(String id, boolean refresh) {
        selectPool(id, refresh, null);
    }

    private void selectPool(String id, boolean refresh, ResourcePoolModels.Pool admitted) {
        if (closed || requestLocked() || !Objects.equals(account, accountKey())) return;
        long request = ++poolSelectionRevision;
        poolId = id;
        budget = budgets.get(id);
        loadingPool = false;
        members.forEach(this::updateBudgetActivity);
        members.forEach(this::ensureDraft);
        if (id.isBlank() || budget != null && !refresh) {
            members.forEach(this::ensureDraft);
            members.forEach(this::buildMember);
            refreshNetwork();
            refreshHeader();
            return;
        }
        if (admitted != null) {
            acceptPool(id, admitted);
            members.forEach(this::buildMember);
            refreshNetwork();
            refreshHeader();
            return;
        }
        capacityStatus.setDescription("Loading Resources");
        loadingPool = true;
        long requestGeneration = generation;
        String requestAccount = account;
        Async<ResourcePoolModels.Pool> lookup;
        try {
            lookup = poolClient.getPool(UUID.fromString(id));
        } catch (RuntimeException error) {
            lookup = Async.failed(error);
        }
        lookup.whenComplete((pool, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed || requestGeneration != generation || request != poolSelectionRevision || !id.equals(poolId)
                    || !Objects.equals(requestAccount, accountKey())) return;
            loadingPool = false;
            if (error != null || pool == null) poolFailure = error == null ? "Resources Are Unavailable" : rootMessage(error);
            else {
                try {
                    acceptPool(id, pool);
                } catch (RuntimeException failure) {
                    poolFailure = rootMessage(failure);
                }
            }
            members.forEach(this::buildMember);
            refreshNetwork();
            refreshHeader();
        }));
        members.forEach(this::buildMember);
        refreshNetwork();
        refreshHeader();
    }

    private void acceptPool(String id, ResourcePoolModels.Pool pool) {
        if (!id.equals(pool.id().toString())) throw new IllegalStateException("Reactor Resource Identity Changed");
        ResourcePoolController.PoolView view = new ResourcePoolController.PoolView(pool, List.of(), List.of(), Map.of());
        budget = budgets.get(id);
        if (budget == null) {
            budget = new NetworkPoolBudget(view, poolOptions);
            budgets.put(id, budget);
            budget.onChanged(this::refreshNetwork);
        } else budget.accept(view);
        poolFailure = "";
        members.forEach(this::ensureDraft);
    }

    private static String hostKey(ServerScreenHost.HostView target) {
        return target == null ? "local" : target.type() + ":" + target.id();
    }

    private ServerList serverList(Member member) {
        String key = context.reactor() ? "reactor" : hostKey(member.existingHost);
        return serverLists.computeIfAbsent(key, ignored -> new ServerList());
    }

    private void ensureServerList(Member member) {
        if (member.source() != Source.EXISTING || closed || requestLocked()) return;
        ServerList list = serverList(member);
        if (list.loaded || list.loading) return;
        loadExisting(member, list);
    }

    private void loadExisting(Member member, ServerList list) {
        if (closed || requestLocked() || !members.contains(member) || !Objects.equals(account, accountKey())) return;
        list.loading = true;
        list.failure = "";
        long revision = ++list.revision;
        long requestGeneration = generation;
        String requestAccount = account;
        ServerScreenHost.HostView selectedHost = member.existingHost;
        Async<List<ServerModels.ClientServerView>> lookup;
        try {
            lookup = context.reactor() ? host.restudioServers() : selectedHost == null ? host.localServers() : host.hostServers(selectedHost);
        } catch (RuntimeException error) {
            lookup = Async.failed(error);
        }
        lookup.whenComplete((values, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed || requestGeneration != generation || revision != list.revision || !Objects.equals(requestAccount, accountKey())) return;
            list.loading = false;
            list.loaded = error == null;
            if (error != null) list.failure = rootMessage(error);
            else list.servers = values == null ? List.of() : values.stream().filter(Objects::nonNull)
                .filter(server -> !serverId(server).isBlank()).toList();
            members.forEach(this::buildMember);
            refreshHeader();
        }));
        members.forEach(this::buildMember);
    }

    private void selectSource(Member member) {
        if (member.updating || closed || requestLocked()) return;
        if (member.source() == Source.NEW) ensureDraft(member);
        else if (member.source() == Source.EXISTING) ensureServerList(member);
        updateBudgetActivity(member);
        buildMember(member);
        refreshNetwork();
        refreshHeader();
    }

    private void selectHost(Member member, ServerScreenHost.HostView value) {
        if (member.updating || closed || requestLocked() || !Objects.equals(account, accountKey())) return;
        ServerScreenHost.HostView target = value == LOCAL_HOST ? null : value;
        if (member.source() == Source.NEW) {
            if (Objects.equals(member.host, target)) return;
            member.host = target;
            ensureDraft(member);
        } else {
            if (Objects.equals(member.existingHost, target)) return;
            member.existingHost = target;
            member.existing = null;
            member.serverOption.set("");
            ensureServerList(member);
        }
        members.forEach(this::buildMember);
        refreshNetwork();
        refreshHeader();
    }

    private void selectExisting(Member member, String id) {
        if (member.updating || closed || requestLocked() || !Objects.equals(account, accountKey())) return;
        member.existing = id.isBlank() ? null : serverList(member).servers.stream().filter(server -> serverId(server).equals(id)).findFirst().orElse(null);
        member.reactorExisting = context.reactor();
        member.panelExisting = member.existing != null && !context.reactor() && panel(member.existing);
        members.forEach(this::buildMember);
        refreshNetwork();
        refreshHeader();
    }

    private boolean current(Member member, long requestGeneration, String requestAccount) {
        return !closed && requestGeneration == generation && members.contains(member)
            && Objects.equals(requestAccount, account) && Objects.equals(account, accountKey());
    }

    private String accountKey() {
        if (authenticationSession != null && !Objects.equals(authenticationSession, host.authenticationSession())) return null;
        if (context.reactor()) {
            if (poolClient != null && poolClient != host.resourcePools()) return null;
            return host.hostedNetworkAccount();
        }
        ServerScreenHost.AccountIdentity identity = host.accountIdentity();
        return identity.authenticated() ? host.hostedNetworkAccount() : "false:" + identity.subjectId();
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
        member.updating = true;
        try {
            List<ServerScreenHost.HostView> hosts = new ArrayList<>();
            hosts.add(LOCAL_HOST);
            remoteHosts.stream().filter(value -> member.source() == Source.EXISTING || !value.panel() && "SSH".equalsIgnoreCase(value.type())).forEach(hosts::add);
            ServerScreenHost.HostView selectedHost = member.source() == Source.EXISTING ? member.existingHost : member.host;
            if (selectedHost != null && !hosts.contains(selectedHost)) hosts.add(selectedHost);
            member.hostOption.setOptions(hosts);
            member.hostOption.set(selectedHost == null ? LOCAL_HOST : selectedHost);
            List<String> servers = new ArrayList<>();
            servers.add("");
            ServerList list = serverList(member);
            list.servers.stream().filter(server -> isProxy(server) == member.proxy && (!member.proxy || !panel(server) || context.reactor()))
                .filter(server -> members.stream().noneMatch(other -> other != member && other.source() == Source.EXISTING
                    && other.existing != null && serverId(other.existing).equals(serverId(server))))
                .forEach(server -> servers.add(serverId(server)));
            if (member.existing != null && !servers.contains(serverId(member.existing))) servers.add(serverId(member.existing));
            member.serverOption.setOptions(servers);
            member.serverOption.set(member.existing == null ? "" : serverId(member.existing));
            member.sourceSetting.setVisible(!requestLocked());
            member.sourceSetting.setRowVisibility("status", !hostFailure.isBlank() || member.source() == Source.EXISTING
                && (list.loading || !list.failure.isBlank() || list.loaded && servers.size() == 1));
            member.sourceStatus.setMessage(member.source() == Source.EXISTING ? "Existing Servers" : "Server Hosts");
            member.sourceStatus.setDescription(member.source() == Source.EXISTING ? list.loading ? "Loading Servers"
                : !list.failure.isBlank() ? list.failure : "No Compatible Servers On This Host" : hostFailure);
            member.sourceSetting.setRowVisibility("retry", !hostFailure.isBlank() || member.source() == Source.EXISTING && !list.failure.isBlank());
            member.networking.setVisible(!member.proxy && !requestLocked());
            member.networking.refreshState();
            member.networking.setRowVisibility("external", member.external() || member.source() == Source.EXISTING && member.panelExisting);
            ServerConfigurationDraft draft = member.draft();
            String draftFailure = member.slot == null ? "" : !member.slot.failure.isBlank() ? member.slot.failure : draft == null ? "" : draft.failure();
            member.statusSetting.setVisible(!requestLocked() && (member.source() != Source.NEW || draft == null || !draft.ready()));
            String title;
            String detail;
            if (member.source() == Source.EXISTING) {
                title = member.existing == null ? "Choose A Server" : displayName(member.existing);
                detail = member.existing == null ? "Select A Server From The Entry Above" : serverDescription(member.existing);
            } else if (member.external()) {
                title = "External Backend";
                detail = "Its Provider Controls Files, Power, And Forwarding";
            } else if (context.reactor() && budget == null) {
                title = loadingPools || loadingPool ? "Loading Resources" : "Resources Unavailable";
                detail = poolFailure.isBlank() ? "Preparing Shared Resources For The Network" : poolFailure;
            } else {
                title = !draftFailure.isBlank() ? "Configuration Unavailable" : "Preparing " + member.title();
                detail = !draftFailure.isBlank() ? draftFailure : "Loading Server Configuration";
            }
            member.status.setMessage(title);
            member.status.setDescription(detail);
            member.statusSetting.setRowVisibility("retry", !draftFailure.isBlank() && member.source() == Source.NEW);
            member.requestSetting.setVisible(requestLocked());
            List<AnimatedWidget> widgets = new ArrayList<>(List.of(member.sourceSetting, member.networking, member.statusSetting, member.requestSetting));
            for (DraftSlot slot : member.drafts.values()) {
                if (slot.draft == null) continue;
                for (Setting section : slot.draft.sections()) {
                    section.setVisible(!requestLocked() && member.active(slot));
                    section.fitContentHeight();
                    widgets.add(section);
                }
            }
            for (AnimatedWidget widget : widgets) {
                if (widget instanceof Setting setting) setting.fitContentHeight();
            }
            if (!member.container.getWidgets().equals(widgets)) member.container.replaceWidgets(widgets, true);
            filter(member.container, searchText());
            member.tab.setName(member.title());
        } finally {
            member.updating = false;
        }
    }

    private void refreshNetwork() {
        if (closed || networkContainer == null || reviewSetting == null || updatingNetwork) return;
        updatingNetwork = true;
        try {
            boolean locked = requestLocked();
            for (Setting setting : networkSections) setting.setVisible(!locked);
            capacitySetting.setVisible(context.reactor() && !locked);
            reviewSetting.setVisible(!locked);
            recoverySetting.setVisible(locked);
            poolRetry.setActive(!loadingPools && !loadingPool && !locked);
            for (PoolAllocationEditor.Resource resource : resourceBars.keySet()) {
                capacitySetting.setRowVisibility(resource.name(), budget != null);
            }
            capacityStatus.setDescription(!poolFailure.isBlank() ? poolFailure : loadingPools || loadingPool ? "Loading Resources"
                : budget == null ? "No Reactor Resources Are Available For This Network" : "");
            recoveryStatus.setDescription(pendingHosted != null && pendingHosted.terminal() != null
                ? "Open The Network To Review Its Outcome Before Starting Another Network" : "The Original Request Is Saved. Resume It To Continue Creation");
            recoverySetting.setRowVisibility("acknowledge", pendingHosted != null && pendingHosted.terminal() != null);
            recoverySetting.setRowVisibility("discard", pendingHosted != null || pendingPlan != null);
            discardButton.setActive(!creating && Objects.equals(account, accountKey()));
            refreshCapacity();
            List<PopupWidget.PopupRow> reviewRows = new ArrayList<>();
            reviewRows.add(reviewSetting.getRows().getFirst());
            for (Member member : members) {
                member.review.setMessage(member.title());
                member.review.setDescription(member.provider() + " • " + (member.proxy ? "Proxy" : roleName(member.role.get())) + " • " + member.endpoint());
                reviewRows.add(member.reviewRow);
            }
            if (!reviewSetting.getRows().equals(reviewRows)) reviewSetting.setRows(reviewRows);
            reviewStatus.setDescription("1 Proxy • " + members.stream().filter(member -> !member.proxy).count() + " Backends"
                + (members.stream().anyMatch(Member::external) ? " • Configure External Forwarding Before Connecting Players" : ""));
            List<AnimatedWidget> widgets = new ArrayList<>(networkSections);
            widgets.add(capacitySetting);
            for (Member member : members) {
                for (DraftSlot slot : member.drafts.values()) {
                    if (slot.draft == null) continue;
                    for (Setting resources : slot.draft.resourceSettings()) {
                        resources.setTitle(member.title() + " Resources");
                        resources.setVisible(!locked && member.active(slot));
                        resources.fitContentHeight();
                        widgets.add(resources);
                    }
                }
            }
            widgets.add(reviewSetting);
            widgets.add(recoverySetting);
            for (AnimatedWidget widget : widgets) {
                if (widget instanceof Setting setting) setting.fitContentHeight();
            }
            if (!networkContainer.getWidgets().equals(widgets)) networkContainer.replaceWidgets(widgets, true);
            filter(networkContainer, searchText());
        } finally {
            updatingNetwork = false;
        }
        refreshHeader();
    }

    private void refreshCapacity() {
        resourceBars.values().forEach(bar -> bar.setNetwork(budget));
        if (budget != null && poolFailure.isBlank() && !loadingPools && !loadingPool) {
            capacityStatus.setDescription(budget.possible() ? "Shared By " + budget.previews().size() + " New Servers • Drag Dividers To Allocate Resources"
                : "Not Enough Resources. Reduce The Server Allocations");
        }
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
        refreshNetwork();
        refreshHeader();
    }

    private void filterActiveTab(String query) {
        TabsManager.Tab tab = tabs().getActiveTab();
        if (tab != null) {
            filter(tab.getContainer(), query);
            tab.getContainer().resetScroll();
        }
    }

    private void filter(Container container, String query) {
        if (container == null) return;
        for (AnimatedWidget widget : container.getWidgets()) {
            if (widget instanceof Setting setting) setting.filter(query);
        }
        container.updateWidgetPositions();
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
        Consumer<NetworkOperationStatus> progress = status -> ScreenManager.getInstance().execute(() -> {
            if (closed || requestGeneration != generation || !creating || !Objects.equals(account, accountKey())) return;
            notification.update().message(progressTitle(status)).description(progressDescription(networkName, status)).commit();
        });
        try {
            request = hosted == null ? host.createNetwork(plan, progress) : host.resumeHostedNetwork(progress);
        } catch (RuntimeException error) {
            request = Async.failed(error);
        }
        request.whenComplete((proxyId, error) -> ScreenManager.getInstance().execute(() -> {
            if (!Objects.equals(account, accountKey())) {
                notification.update().message("Account Changed").description("Reopen Network Setup To Review This Request")
                    .type(Notification.Type.WARN).loading(false).autoSlideOut(true).commit();
                if (!closed && requestGeneration == generation) {
                    creating = false;
                    refreshHeader();
                }
                return;
            }
            HostedNetworkClient.OperationFailure operation = operationFailure(error);
            ServerScreenHost.NetworkCreationFailure localFailure = creationFailure(error);
            if (error == null) {
                notification.update().message("Network Created").description(networkName).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            } else {
                notification.update().message(operation == null ? hostedCreation ? "Network Progress Unavailable" : "Network Creation Failed" : "Network Needs Recovery")
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
                if (localFailure != null) {
                    recoveryNetworkId = localFailure.networkId();
                    createdHosted = false;
                } else if (operation == null) fail(rootMessage(error));
                else {
                    recoveryNetworkId = host.hostedNetworkViewId(operation.status().networkId());
                    createdHosted = true;
                }
                if (pendingPlan != null || pendingHosted != null) {
                    members.forEach(this::buildMember);
                    refreshNetwork();
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

    private String progressTitle(NetworkOperationStatus status) {
        return switch (status.state()) {
            case WAITING_FOR_MEMBERS -> "Preparing Servers";
            case ROLLING_BACK -> "Restoring Network";
            default -> switch (status.stage()) {
                case VALIDATING -> "Checking Network";
                case PROVISIONING -> "Creating Servers";
                case PLANNING -> "Preparing Network";
                case APPLYING -> "Configuring Network";
                case LIFECYCLE -> "Starting Network";
                case VERIFYING -> "Checking Network";
                case ROLLBACK -> "Restoring Network";
                case COMPLETE -> "Finishing Network";
            };
        };
    }

    private String progressDescription(String name, NetworkOperationStatus status) {
        List<String> details = new ArrayList<>();
        details.add(name);
        if (!status.message().isBlank()) details.add(status.message());
        if (!status.members().isEmpty()) {
            long ready = status.members().stream().filter(member -> switch (member.state().toUpperCase(Locale.ROOT)) {
                case "ACTIVE", "READY", "BOUND", "EXTERNAL", "COMPLETE", "SUCCEEDED" -> true;
                default -> false;
            }).count();
            String count = ready + " Of " + status.members().size();
            if (!status.message().contains(count + " Ready")) details.add(count + " Servers Ready");
        }
        return String.join(" • ", details);
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

    private void confirmDiscardHostedRequest() {
        if (creating || closed || pendingHosted == null && pendingPlan == null || !Objects.equals(account, accountKey())) return;
        HostedNetworkPendingStore.Pending pending = pendingHosted;
        NetworkCreationPlan plan = pendingPlan;
        long revision = generation;
        DeletionPopup.show(this, List.of(
            DeletionPopup.entry("Discard Saved Network Request", "network.png"),
            DeletionPopup.entry("Any Created Servers Are Kept", "server.png"),
            DeletionPopup.entry("Creation Already Started On Reactor Continues", "info.png")),
            DeletionPopup.Action.action("Discard Request", "delete.png", true, popup -> {
                if (creating || closed || revision != generation || pending != pendingHosted || plan != pendingPlan
                        || !Objects.equals(account, accountKey())) return;
                try {
                    if (pending != null) host.discardHostedNetwork(pending.command().requestId(), pending.command().networkId());
                } catch (RuntimeException error) {
                    fail(rootMessage(error));
                    return;
                }
                popup.hide();
                startNewSetup();
            }));
    }

    private void startNewSetup() {
        ScreenManager.getInstance().replaceScreen(this, new NetworkCreationScreen(parent, host, selected, context));
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
        startNewSetup();
    }

    private NetworkCreationPlan plan() {
        if (!Objects.equals(account, accountKey())) throw new IllegalStateException("Account Changed. Reopen Network Setup Before Creating A Network");
        if (members.stream().filter(member -> member.proxy).count() != 1) throw new IllegalArgumentException("One Proxy Is Required");
        if (members.stream().noneMatch(member -> !member.proxy)) throw new IllegalArgumentException("Add At Least One Backend");
        if (members.stream().anyMatch(member -> member.source() == Source.EXISTING && member.existing == null)) {
            throw new IllegalStateException("Choose An Existing Server For Every Existing Server Entry");
        }
        if (members.stream().anyMatch(member -> member.source() == Source.NEW && (member.draft() == null || !member.draft().ready()))) {
            throw new IllegalStateException("Wait For Every Server Configuration To Load");
        }
        if ((loadingPools || loadingPool || !poolFailure.isBlank()) && members.stream().anyMatch(member -> member.source() == Source.NEW)) {
            throw new IllegalStateException(poolFailure.isBlank() ? "Wait For Resources To Load" : poolFailure);
        }
        boolean hosted = context.reactor();
        if (hosted && members.stream().anyMatch(member -> !member.hosted() && !member.panelExisting && !member.external())) {
            throw new IllegalArgumentException("Choose Reactor For Every Managed Member. External Panel Backends May Join With An Address And Port");
        }
        if (!hosted && members.stream().anyMatch(member -> member.external())) {
            throw new IllegalArgumentException("An External Backend Without An Imported Panel Server Needs A Reactor Network");
        }
        if (members.stream().anyMatch(member -> member.proxy && member.panelExisting)) {
            throw new IllegalArgumentException("A Panel Proxy Needs Atomic Configuration Access Before It Can Be Managed");
        }
        if (members.stream().anyMatch(member -> (member.panelExisting && member.source() == Source.EXISTING || member.external()) && !member.proxy
                && ((member.address.get() == null || member.address.get().isBlank())
                || member.preferredPort.get() == null || member.preferredPort.get() == 0))) {
            throw new IllegalArgumentException("Set A Proxy-Reachable Address And Assigned Port For Every External Backend");
        }
        if (members.stream().anyMatch(member -> member.external()
                && (member.externalName.get() == null || member.externalName.get().isBlank()))) {
            throw new IllegalArgumentException("Every External Backend Needs A Server Name");
        }
        if (members.stream().anyMatch(member -> member.panelExisting && member.source() == Source.EXISTING || member.external()) && !firewallVerifiedOption.get()) {
            throw new IllegalArgumentException("Verify External Backend Ports Are Private Or Firewalled So Only The Proxy Can Reach Them");
        }
        String name = nameOption.get() == null ? "" : nameOption.get().trim();
        if (name.isBlank()) throw new IllegalArgumentException("Network Name Is Required");
        int entryPort = entryPortOption.get();
        if (entryPort < 1 || entryPort > 65535) throw new IllegalArgumentException("Player Port Must Be Between 1 And 65535");
        Set<String> routes = new LinkedHashSet<>();
        for (Member member : members) {
            if (member.source() == Source.NEW) member.draft().apply();
            if (member.proxy) {
                String loader = member.source() == Source.NEW ? String.valueOf(member.draft().target().modLoader()) : member.existing.loader;
                if (!"VELOCITY".equalsIgnoreCase(loader)) throw new IllegalArgumentException("The Proxy Must Use Velocity");
            } else {
                String routeName = member.route.get() == null ? "" : member.route.get().trim();
                if (routeName.isBlank()) throw new IllegalArgumentException("Every Backend Needs A Route Name");
                if (!routes.add(routeName.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Backend Route Names Must Be Unique");
            }
        }
        boolean installReSync = reSyncOption.get();
        List<NetworkCreationPlan.Server> servers = members.stream().map(member -> member.plan(installReSync, hosted)).toList();
        validatePoolCapacity(servers);
        return new NetworkCreationPlan(name, entryPort, servers, firewallVerifiedOption.get(), requestId,
                hosted ? subdomainOption.get() : "");
    }

    private void validatePoolCapacity(List<NetworkCreationPlan.Server> servers) {
        Map<String, BigInteger[]> reserved = new LinkedHashMap<>();
        for (NetworkCreationPlan.Server server : servers) {
            if (!(server.hostedSource() instanceof NetworkMemberSource.Draft draft)) continue;
            BigInteger[] used = reserved.computeIfAbsent(draft.poolId(), key -> new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO});
            used[0] = used[0].add(BigInteger.valueOf(Math.max(draft.installer().ramMiB(), draft.runtime().ramMiB())));
            used[1] = used[1].add(BigInteger.valueOf(Math.max(draft.installer().cpuQuotaPercent(), draft.runtime().cpuQuotaPercent())));
            used[2] = used[2].add(BigInteger.valueOf(draft.retained().diskMiB()));
        }
        for (Map.Entry<String, BigInteger[]> entry : reserved.entrySet()) {
            NetworkPoolBudget pool = budgets.get(entry.getKey());
            if (pool == null) throw new IllegalArgumentException("The Network Resources Are Unavailable");
            BigInteger[] used = entry.getValue();
            ResourcePoolModels.Resources available = pool.view().pool().balance().available();
            List<String> limits = List.of(available.ramMiB(), available.cpuQuotaPercent(), available.diskMiB());
            List<String> names = List.of("RAM", "CPU", "Disk");
            for (int i = 0; i < used.length; i++) {
                if (used[i].compareTo(new BigInteger(limits.get(i))) > 0) {
                    throw new IllegalArgumentException("The Network Needs More " + names.get(i)
                        + " Than Is Available. Reduce The Server Allocations On The Network Tab");
                }
            }
        }
    }

    private void refreshHeader() {
        if (createButton == null || cancelButton == null) return;
        if (discardButton != null) discardButton.setActive(!creating && Objects.equals(account, accountKey()));
        boolean ready = !creating && Objects.equals(account, accountKey()) && (recoveryNetworkId != null || createdNetworkId != null || pendingHosted != null || pendingPlan != null
            || members.stream().anyMatch(member -> member.proxy) && members.stream().anyMatch(member -> !member.proxy)
            && members.stream().noneMatch(member -> member.source() == Source.EXISTING && member.existing == null
                || member.source() == Source.NEW && (member.draft() == null || !member.draft().ready()))
            && (!(loadingPools || loadingPool || !poolFailure.isBlank()) || members.stream().noneMatch(member -> member.source() == Source.NEW))
            && (budget == null || budget.possible()));
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
        budgets.values().forEach(pool -> pool.onChanged(null));
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

    private static ServerScreenHost.NetworkCreationFailure creationFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ServerScreenHost.NetworkCreationFailure failure) return failure;
        }
        return null;
    }

    private static final class ServerList {
        private List<ServerModels.ClientServerView> servers = List.of();
        private boolean loading;
        private boolean loaded;
        private long revision;
        private String failure = "";
    }

    private static final class DraftSlot {
        private final UUID draftId = UUID.randomUUID();
        private final UUID createRequestId = UUID.randomUUID();
        private final UUID activationRequestId = UUID.randomUUID();
        private final NetworkPoolBudget budget;
        private final PoolCreationPreview preview;
        private ServerConfigurationDraft draft;
        private boolean loading;
        private long revision;
        private String failure = "";

        private DraftSlot(String key, NetworkPoolBudget budget, PoolCreationPreview preview) {
            this.budget = budget;
            this.preview = preview;
        }
    }

    private final class Member {
        private final String id = UUID.randomUUID().toString();
        private final boolean proxy;
        private ServerModels.ClientServerView existing;
        private ServerScreenHost.HostView host = context.host();
        private ServerScreenHost.HostView existingHost = context.host();
        private boolean reactorExisting;
        private boolean panelExisting;
        private final String externalId = "external:" + UUID.randomUUID();
        private final ConfigOption<Source> sourceOption;
        private final ConfigOption<ServerScreenHost.HostView> hostOption;
        private final ConfigOption<String> serverOption;
        private final ConfigOption<String> externalName;
        private final ConfigOption<String> route;
        private final ConfigOption<NetworkMemberRole> role;
        private final ConfigOption<String> address;
        private final ConfigOption<Integer> preferredPort;
        private final Map<String, DraftSlot> drafts = new LinkedHashMap<>();
        private DraftSlot slot;
        private final Setting sourceSetting;
        private final Setting networking;
        private final Setting statusSetting;
        private final Setting requestSetting;
        private final MountableButtonWidget sourceStatus;
        private final MountableButtonWidget status;
        private final MountableButtonWidget review;
        private final PopupWidget.PopupRow reviewRow;
        private Container container;
        private TabsManager.Tab tab;
        private boolean updating;

        private Member(boolean proxy, ServerModels.ClientServerView existing, String route, NetworkMemberRole role) {
            this.proxy = proxy;
            this.existing = existing;
            Source initialSource = existing == null ? Source.NEW : Source.EXISTING;
            this.sourceOption = ConfigOption.<Source>builder("Source")
                .description(context.reactor() ? "Create A Reactor Server Or Add A Server To This Network" : "Create A Server On A Host Or Use An Existing Server")
                .bind(() -> initialSource, value -> {})
                .defaultValue(initialSource)
                .options(context.reactor() && !proxy ? List.of(Source.NEW, Source.EXISTING, Source.EXTERNAL) : List.of(Source.NEW, Source.EXISTING))
                .display(value -> value.label)
                .editor(ConfigOption.Editor.DROPDOWN)
                .dependsOn(() -> !requestLocked())
                .resettable(false).build();
            this.hostOption = ConfigOption.<ServerScreenHost.HostView>builder("Host")
                .description("Where This Network Server Runs")
                .bind(() -> host == null ? LOCAL_HOST : host, value -> {})
                .defaultValue(host == null ? LOCAL_HOST : host)
                .options(List.of(LOCAL_HOST))
                .display(ServerScreenHost.HostView::name)
                .editor(ConfigOption.Editor.DROPDOWN)
                .visibleWhen(() -> !context.reactor() && !external())
                .dependsOn(() -> !loadingHosts && !requestLocked())
                .resettable(false).build();
            this.serverOption = ConfigOption.<String>builder("Server")
                .description("An Existing Server On The Selected Host")
                .bind(() -> existing == null ? "" : serverId(existing), value -> {})
                .defaultValue(existing == null ? "" : serverId(existing))
                .options(List.of(""))
                .display(value -> value.isBlank() ? serverList(this).loading ? "Loading Servers" : "Choose Server"
                    : this.existing != null && value.equals(serverId(this.existing)) ? displayName(this.existing)
                    : serverList(this).servers.stream().filter(server -> value.equals(serverId(server))).findFirst().map(NetworkCreationScreen::displayName).orElse("Server Unavailable"))
                .editor(ConfigOption.Editor.DROPDOWN)
                .visibleWhen(() -> source() == Source.EXISTING)
                .dependsOn(() -> !serverList(this).loading && !requestLocked())
                .resettable(false).build();
            this.route = ConfigOption.<String>builder("Route Name")
                .description("The Short Name Used By The Proxy And Routing Rules")
                .bind(() -> route, value -> {}).defaultValue(route).resettable(false).build();
            this.role = ConfigOption.<NetworkMemberRole>builder("Network Role")
                .description("How This Backend Participates In Player Routing")
                .bind(() -> role, value -> {}).options(BACKEND_ROLES).display(NetworkCreationScreen::roleName)
                .defaultValue(role).resettable(false).build();
            this.address = ConfigOption.<String>builder("Backend Address")
                .description("The Address The Proxy Uses To Reach This Backend")
                .bind(() -> "", value -> {}).defaultValue("").visibleWhen(() -> !hosted()).resettable(false).build();
            this.externalName = ConfigOption.<String>builder("Server Name")
                .description("The Name Shown For This External Backend")
                .bind(this::routeName, value -> {}).defaultValue(routeName()).visibleWhen(this::external).resettable(false).build();
            this.preferredPort = ConfigOption.<Integer>builder("Backend Port")
                .description("Use 0 For An Automatic Port. External Backends Need Their Assigned Port")
                .bind(() -> 0, value -> {}).defaultValue(0).range(0, 65535).visibleWhen(() -> !hosted()).resettable(false).build();
            this.sourceStatus = message("Server Source", "");
            IconButton retry = new IconButton.Builder().label("Retry").imagePath("reload.png").size(80, 20).onClick(() -> {
                if (source() == Source.EXISTING) loadExisting(this, serverList(this));
                else loadHosts();
            }).build();
            Setting.Builder source = new Setting.Builder("Server");
            source.addOption(sourceOption);
            source.addOption(hostOption);
            source.addOption(serverOption);
            source.addRow(new PopupWidget.PopupRow.Builder("", sourceStatus).id("status").build());
            source.addRow(new PopupWidget.PopupRow.Builder("", retry).id("retry").build());
            this.sourceSetting = source.build();
            Setting.Builder networking = new Setting.Builder("Networking");
            networking.addOption(this.route);
            networking.addOption(this.role);
            networking.addOption(externalName);
            networking.addOption(address);
            networking.addOption(preferredPort);
            networking.addRow(new PopupWidget.PopupRow.Builder("", message("External Forwarding",
                "Configure Velocity Forwarding With The Proxy's Connection Key And Restrict Access To The Proxy")).id("external").build());
            this.networking = networking.build();
            this.status = message("Server Configuration", "");
            IconButton retryDraft = new IconButton.Builder().label("Retry").imagePath("reload.png").size(80, 20)
                .onClick(() -> {
                    if (slot == null || requestLocked() || closed || !Objects.equals(account, accountKey())) return;
                    if (slot.draft != null && !slot.draft.failure().isBlank()) slot.draft.reload(NetworkCreationScreen.this.host);
                    else if (slot.draft == null && !slot.loading) loadMember(this, slot);
                }).build();
            Setting.Builder state = new Setting.Builder("Server Configuration");
            state.addRow("", status);
            state.addRow(new PopupWidget.PopupRow.Builder("", retryDraft).id("retry").build());
            this.statusSetting = state.build();
            Setting.Builder request = new Setting.Builder("Network Request");
            request.addRow("", message("Server Configuration", "The Original Reactor Request Is Saved For Retry"));
            this.requestSetting = request.build();
            this.review = message("Server", "");
            this.reviewRow = new PopupWidget.PopupRow.Builder("", review).id(id).build();
            this.sourceOption.addChangeListener(value -> selectSource(this));
            this.hostOption.addChangeListener(value -> selectHost(this, value));
            this.serverOption.addChangeListener(value -> selectExisting(this, value));
            this.route.addChangeListener(value -> { if (tab != null) tab.setName(title()); refreshNetwork(); });
            this.role.addChangeListener(value -> refreshNetwork());
            this.address.addChangeListener(value -> refreshNetwork());
            this.preferredPort.addChangeListener(value -> refreshNetwork());
            this.externalName.addChangeListener(value -> { if (external() && tab != null) tab.setName(title()); refreshNetwork(); });
        }

        private ServerConfigurationDraft draft() {
            return slot == null || !active(slot) ? null : slot.draft;
        }

        private boolean active(DraftSlot value) {
            return source() == Source.NEW && value == slot && (!context.reactor() || budget != null && value.budget == budget);
        }

        private String title() {
            if (source() == Source.EXISTING && existing != null) return displayName(existing);
            if (external()) return externalName.get();
            if (source() == Source.NEW && draft() != null && draft().target().name() != null && !draft().target().name().isBlank()) return draft().target().name();
            return proxy ? "Proxy" : routeName();
        }

        private String provider() {
            if (external()) return "External Backend";
            if (source() == Source.EXISTING && panelExisting) return "External Panel";
            if (context.reactor()) return "Reactor";
            ServerScreenHost.HostView target = source() == Source.EXISTING ? existingHost : host;
            return target == null ? LOCAL_HOST.name() : target.name();
        }

        private String routeName() {
            String value = route.get();
            if (value == null || value.isBlank()) return "Backend";
            String display = value.replace('-', ' ').replace('_', ' ');
            return Character.toUpperCase(display.charAt(0)) + display.substring(1);
        }

        private String endpoint() {
            if (hosted()) return "Address And Port Assigned By Reactor";
            String value = address.get() == null ? "" : address.get().trim();
            int port = preferredPort.get() == null ? 0 : preferredPort.get();
            return "Address: " + (value.isBlank() ? "Automatic" : value) + " • Port: " + (port == 0 ? external() || source() == Source.EXISTING && panelExisting ? "Required" : "Automatic" : port);
        }

        private NetworkCreationPlan.Server plan(boolean installReSync, boolean hostedNetwork) {
            boolean create = source() == Source.NEW;
            ServerConfigurationDraft current = create ? draft() : null;
            String server = external() ? externalId : create ? "" : serverId(existing);
            NetworkMemberSource source = create && slot.budget != null ? current.hostedSource(new ResourcePoolController.Creation(
                    slot.budget.view().pool().id(), slot.draftId, slot.createRequestId, slot.activationRequestId))
                : source() == Source.EXISTING && reactorExisting ? new NetworkMemberSource.ExistingServer(server)
                : source() == Source.EXISTING && panelExisting && hostedNetwork ? new NetworkMemberSource.External(server, displayName(existing), address.get())
                : external() ? new NetworkMemberSource.External(server, externalName.get(), address.get()) : null;
            boolean outside = external() || source() == Source.EXISTING && panelExisting;
            return new NetworkCreationPlan.Server(server, current == null ? null : current.target().raw(),
                create ? host : external() ? null : existingHost, current == null ? "" : current.location(), current == null ? null : current.settings(), proxy,
                proxy ? "proxy" : route.get(), proxy ? NetworkMemberRole.PROXY : role.get(), 0, installReSync && !outside,
                proxy ? "" : address.get(), proxy || preferredPort.get() == null ? 0 : preferredPort.get(), source,
                outside ? NetworkMemberManagement.EXTERNAL : NetworkMemberManagement.MANAGED);
        }

        private boolean hosted() {
            return context.reactor() && !external();
        }

        private boolean external() {
            return source() == Source.EXTERNAL;
        }

        private Source source() {
            return sourceOption.get();
        }

        private void close() {
            for (DraftSlot slot : drafts.values()) {
                slot.revision++;
                if (slot.budget != null) slot.budget.remove(id);
                if (slot.draft != null) slot.draft.close();
            }
        }
    }
}
