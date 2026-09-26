package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.resource.marketplace.HostedModpackSelection;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DoubleSliderWidget;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Identifier;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class ResourcePoolScreen extends ReScreen {
    private static final int MAX_PARALLEL_CHANGES = 4;
    private final Screen parent;
    private final RemotelyClient remotelyClient;
    private final ResourcePoolController controller;
    private final ServerIconManager iconManager;
    private final HostedModpackSelection modpack;
    private final Map<UUID, PoolAllocationEditor> editors = new HashMap<>();
    private final Map<String, MountableButtonWidget> serverRows = new HashMap<>();
    private final Map<UUID, List<ResourceAllocationBarWidget>> poolBars = new HashMap<>();
    private final ResourceAccentHover accentHover = new ResourceAccentHover();
    private final Map<String, List<Runnable>> editorSync = new HashMap<>();
    private final Map<String, boolean[]> editorValidity = new HashMap<>();
    private final Map<UUID, IconButton> draftRows = new HashMap<>();
    private final List<PoolWatch> poolWatches = new ArrayList<>();
    private final List<Runnable> changeWatches = new ArrayList<>();
    private final List<PendingChange> pendingChanges = new ArrayList<>();
    private final Set<String> deletingServers = new HashSet<>();
    private IconButton applyChanges;
    private IconButton discardChanges;
    private String changeAccount = "";
    private int changesInFlight;
    private int reductionsInFlight;
    private boolean changeWaiting;
    private boolean reductionWaiting;
    private String changeFailure;
    private Notification.Type changeFailureType;
    private long changeRun;
    private Notification changeNotice;
    private Map<String, String> serverNames = Map.of();
    private Map<String, ServerModels.ClientServerView> serverViews = Map.of();
    private boolean serverNamesLoaded;
    private final Map<String, Identifier> serverIcons = new HashMap<>();
    private Container content;
    private String shownAccount = "";
    private String namesAccount = "";
    private String observedAccount = "";
    private boolean observedAuthenticated;
    private boolean handoff;
    private boolean closed;
    private String expandedServerId = "";
    private String highlightedServerId;
    private Accent highlightedOriginal;
    private ResourcePoolController.Snapshot displayed;
    private String layoutStamp;

    private record PendingChange(UUID poolId, PoolAllocationEditor.Change change) {}

    public ResourcePoolScreen(Screen parent, RemotelyClient remotelyClient) {
        this(parent, remotelyClient, null);
    }

    public ResourcePoolScreen(Screen parent, RemotelyClient remotelyClient, HostedModpackSelection modpack) {
        this.parent = parent;
        this.remotelyClient = remotelyClient;
        this.modpack = modpack;
        enableNavigation(parent);
        TaskScheduler scheduler = remotelyClient != null && remotelyClient.getComposition() != null
                ? remotelyClient.getComposition().scheduler() : TaskScheduler.unavailable();
        controller = new ResourcePoolController(remotelyClient.getApiClient(), scheduler, this::accountId);
        iconManager = new ServerIconManager(host().iconProvider());
    }

    public static void openCreate(Screen parent, RemotelyClient remotelyClient) {
        ResourcePoolScreen resources = new ResourcePoolScreen(parent, remotelyClient);
        boolean[] opened = {false};
        resources.controller.listen(snapshot -> resources.host().application().execute(() -> {
            if (opened[0] || snapshot.loading() || snapshot.generation() == 0) return;
            opened[0] = true;
            if (snapshot.pools().size() == 1 && !snapshot.poolPages().hasMore()) {
                ResourcePoolModels.Pool pool = snapshot.pools().getFirst().pool();
                resources.handoff = true;
                ScreenManager.getInstance().navigate(resources, new ServerConfigurationScreen(resources, remotelyClient,
                        resources.controller.begin(pool.id())));
            }
        }));
        ScreenManager.getInstance().replaceScreen(parent, resources);
        resources.controller.refresh();
    }

    private ServerScreenHost host() {
        return remotelyClient.getHost().serverScreenHost(remotelyClient);
    }

    private String accountId() {
        return host().accountIdentity().subjectId();
    }

    ResourcePoolController controller() {
        return controller;
    }

    void refreshPool() {
        controller.refresh();
    }

    ResourcePoolController.PoolView poolView(UUID poolId) {
        return controller.snapshot().pools().stream().filter(view -> view.pool().id().equals(poolId)).findFirst().orElse(null);
    }

    PoolAllocationEditor poolEditor(UUID poolId) {
        ResourcePoolController.PoolView view = poolView(poolId);
        if (view == null) return null;
        PoolAllocationEditor editor = editors.computeIfAbsent(poolId, ignored -> new PoolAllocationEditor());
        editor.accept(view);
        return editor;
    }

    Runnable watchPool(UUID poolId, Consumer<ResourcePoolController.PoolView> listener) {
        PoolWatch watch = new PoolWatch(poolId, controller.snapshot().accountId(), Objects.requireNonNull(listener, "listener"));
        poolWatches.add(watch);
        listener.accept(poolView(poolId));
        return () -> poolWatches.remove(watch);
    }

    Runnable watchChanges(Runnable listener) {
        changeWatches.add(listener);
        listener.run();
        return () -> changeWatches.remove(listener);
    }

    boolean hasChanges(UUID poolId) {
        PoolAllocationEditor editor = editors.get(poolId);
        return editor != null && editor.hasChanges();
    }

    boolean canApplyChanges(UUID poolId) {
        PoolAllocationEditor editor = editors.get(poolId);
        return editor != null && editor.hasChanges() && !applyingChanges() && !editor.hasPending();
    }

    boolean applyingChanges() {
        return changeNotice != null;
    }

    void updateChangeActions() {
        if (applyChanges != null) applyChanges.setActive(!applyingChanges() && editors.values().stream().noneMatch(PoolAllocationEditor::hasPending)
                && editorValidity.values().stream().allMatch(values -> values[0] && values[1] && values[2])
                && editors.values().stream().anyMatch(PoolAllocationEditor::hasChanges));
        if (discardChanges != null) discardChanges.setActive(!applyingChanges()
                && editors.values().stream().anyMatch(PoolAllocationEditor::hasChanges));
        for (Runnable listener : List.copyOf(changeWatches)) listener.run();
    }

    String poolServerName(String serverId) {
        return serverName(serverId);
    }

    public String getDesktopAppId() {
        return "resource-pools";
    }

    public String getDesktopAppTitle() {
        return "Resources";
    }

    public String getDesktopAppIconPath() {
        return "pool.png";
    }

    @Override
    public void init() {
        super.init();
        handoff = false;
        closed = false;
        ServerScreenHost.AccountIdentity identity = host().accountIdentity();
        observedAccount = identity.subjectId();
        observedAuthenticated = identity.authenticated();
        applyChanges = new IconButton.Builder().size(18, 18)
                .imagePath("checkmark.png").hint("Apply All Previewed Server Changes")
                .onClick(() -> applyChanges(null)).build();
        discardChanges = new IconButton.Builder().size(18, 18)
                .imagePath("delete.png").hint("Discard All Resource Previews")
                .onClick(() -> discardChanges(null)).build();
        header().addLeft("close.png", this::close, "Back")
                .addRight(discardChanges).addRight(applyChanges)
                .addRight("reload.png", controller::refresh, "Refresh")
                .build();
        updateChangeActions();
        content = createContainer("resource_pools", 6, 38, width - 12, Math.max(80, height - 44))
                .columns(1).padding(4).verticalSpacing(4).layout(new ManagedLayout()).scrolling(true).backgroundDrawing(true);
        layoutStamp = null;
        displayed = null;
        setActiveContainer(content);
        ResourcePoolController.Snapshot initial = controller.snapshot();
        render(initial);
        displayed = initial;
        controller.listen(snapshot -> host().application().execute(() -> {
            setLoading(snapshot.generation() == 0 || snapshot.loading() || snapshot.poolPages().loading()
                    || snapshot.purchasePages().loading()
                    || snapshot.pools().stream().anyMatch(view -> view.draftPages().loading() || view.allocationPages().loading()));
            for (PoolWatch watch : List.copyOf(poolWatches)) {
                watch.listener().accept(snapshot.accountId().equals(watch.accountId()) ? snapshot.pools().stream()
                        .filter(view -> view.pool().id().equals(watch.poolId())).findFirst().orElse(null) : null);
            }
            display(snapshot);
            if (!pendingChanges.isEmpty()) advanceChanges();
            updateChangeActions();
        }));
        controller.refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (closed) return;
        display(controller.snapshot());
        ServerScreenHost.AccountIdentity identity = host().accountIdentity();
        if (observedAuthenticated != identity.authenticated() || !observedAccount.equals(identity.subjectId())) {
            observedAccount = identity.subjectId();
            observedAuthenticated = identity.authenticated();
            controller.refresh();
        }
    }

    private static boolean sameContent(ResourcePoolController.Snapshot old, ResourcePoolController.Snapshot next) {
        return old != null && Objects.equals(old.accountId(), next.accountId())
                && old.pools().equals(next.pools()) && old.offers().equals(next.offers())
                && old.purchases().equals(next.purchases()) && old.poolPages().equals(next.poolPages())
                && old.purchasePages().equals(next.purchasePages()) && old.loading() == next.loading()
                && old.message().equals(next.message());
    }

    private void display(ResourcePoolController.Snapshot snapshot) {
        if (closed || content == null) return;
        if (displayed == snapshot) return;
        if (!sameContent(displayed, snapshot)) render(snapshot);
        displayed = snapshot;
    }

    private record PoolWatch(UUID poolId, String accountId, Consumer<ResourcePoolController.PoolView> listener) {}

    private void render(ResourcePoolController.Snapshot snapshot) {
        if (content == null) {
            return;
        }
        if (!shownAccount.equals(snapshot.accountId())) {
            remotelyClient.storageBreakdownIndex().retainAccount(snapshot.accountId());
            editors.clear();
            serverRows.clear();
            poolBars.clear();
            editorSync.clear();
            editorValidity.clear();
            draftRows.clear();
            serverNames = Map.of();
            serverViews = Map.of();
            serverNamesLoaded = false;
            deletingServers.clear();
            serverIcons.clear();
            shownAccount = snapshot.accountId();
        }
        if (!snapshot.accountId().isBlank() && !namesAccount.equals(snapshot.accountId())) {
            loadServerNames(snapshot.accountId());
        }
        if (!snapshot.loading()) {
            editors.keySet().removeIf(id -> snapshot.pools().stream().noneMatch(view -> view.pool().id().equals(id)));
        }
        String nextLayout = layoutStamp(snapshot);
        if (nextLayout.equals(layoutStamp)) {
            refreshExisting(snapshot);
            return;
        }
        content.clearWidgets();
        accentHover.clear();
        highlightedServerId = null;
        highlightedOriginal = null;
        serverRows.clear();
        poolBars.clear();
        editorSync.clear();
        editorValidity.clear();
        draftRows.clear();
        if ((snapshot.generation() == 0 || snapshot.loading()) && snapshot.pools().isEmpty()) {
            content.addWidget(summary("Loading Resource Pools", "Checking Available Resources And Servers", "calm"));
            layoutStamp = nextLayout;
            return;
        }
        if (!snapshot.message().isBlank()) {
            content.addWidget(summary(snapshot.pools().isEmpty() ? "Resource Pools Unavailable" : "Resources Need Attention",
                    snapshot.message(), "danger"));
            if (snapshot.pools().isEmpty()) {
                layoutStamp = nextLayout;
                return;
            }
        }
        if (!snapshot.loading() && !snapshot.offers().isEmpty()) {
            content.addWidget(new IconButton.Builder().size(rowWidth(), 28).label("Add Resources")
                    .hint("Review Available Resource Offers").imagePath("Reactor.png")
                    .onClick(() -> showOffers(snapshot.offers())).build());
        }
        renderPurchases(snapshot);
        if (snapshot.pools().isEmpty()) {
            content.addWidget(summary("No Resource Pools", "Capacity Appears After Payment And Review", "warning"));
            renderPageAction("Resource Pools", "Load More Pools", snapshot.poolPages(), controller::loadMorePools);
            layoutStamp = nextLayout;
            return;
        }
        for (ResourcePoolController.PoolView view : snapshot.pools()) {
            renderPool(view);
        }
        renderPageAction("Resource Pools", "Load More Pools", snapshot.poolPages(), controller::loadMorePools);
        layoutStamp = nextLayout;
    }

    private String layoutStamp(ResourcePoolController.Snapshot snapshot) {
        StringBuilder stamp = new StringBuilder(snapshot.accountId()).append('|').append(snapshot.message())
                .append('|').append(snapshot.pools().isEmpty() && (snapshot.generation() == 0 || snapshot.loading()));
        snapshot.offers().forEach(offer -> stamp.append('|').append(offer.id()));
        snapshot.purchases().forEach(purchase -> stamp.append('|').append(purchase.purchaseId()).append(purchase.status()));
        stamp.append('|').append(snapshot.poolPages().hasMore()).append(snapshot.poolPages().loading())
                .append(snapshot.purchasePages().hasMore()).append(snapshot.purchasePages().loading());
        for (ResourcePoolController.PoolView view : snapshot.pools()) {
            stamp.append('|').append(view.pool().id()).append(view.allocationPages().hasMore())
                    .append(view.allocationPages().loading()).append(view.draftPages().hasMore())
                    .append(view.draftPages().loading());
            visibleAllocations(view.allocations()).forEach(allocation -> stamp.append('|').append(allocation.serverId()).append(allocation.state()));
            view.drafts().stream().filter(draft -> draft.state() != ResourcePoolModels.DraftState.ACTIVE)
                    .forEach(draft -> stamp.append('|').append(draft.id()));
        }
        return stamp.toString();
    }

    private void refreshExisting(ResourcePoolController.Snapshot snapshot) {
        for (ResourcePoolController.PoolView view : snapshot.pools()) {
            PoolAllocationEditor editor = editors.get(view.pool().id());
            if (editor == null) continue;
            String revision = editor.selected() == null ? "" : editor.selected().revision();
            List<BigInteger> limits = editor.selected() == null ? List.of() : List.of(
                    editor.limit(PoolAllocationEditor.Resource.RAM), editor.limit(PoolAllocationEditor.Resource.CPU),
                    editor.limit(PoolAllocationEditor.Resource.DISK));
            editor.accept(view);
            if (editor.selected() != null && (!revision.equals(editor.selected().revision()) || !limits.equals(List.of(
                    editor.limit(PoolAllocationEditor.Resource.RAM), editor.limit(PoolAllocationEditor.Resource.CPU),
                    editor.limit(PoolAllocationEditor.Resource.DISK))))) {
                syncEditor(editor.selected().serverId());
            }
            List<ResourcePoolModels.Allocation> ordered = orderedAllocations(visibleAllocations(view.allocations()));
            List<ResourceAllocationBarWidget> bars = poolBars.get(view.pool().id());
            if (bars != null) {
                bars.forEach(bar -> bar.setAllocations(ordered));
            }
            updateAllocationRows(editor);
            for (ResourcePoolModels.Draft draft : view.drafts()) {
                IconButton row = draftRows.get(draft.id());
                if (row == null) continue;
                String state = draft.state() == ResourcePoolModels.DraftState.UNKNOWN ? "Activation Needs Review"
                        : title(draft.state().name());
                row.setMessage(draft.metadata().name() + " • " + state);
                row.setHint(draftHint(draft));
                row.setOnClick(draft.state() == ResourcePoolModels.DraftState.DRAFT
                        ? () -> showDraftActivation(draft) : draft.state() == ResourcePoolModels.DraftState.UNKNOWN
                        ? () -> showDraftReview(draft) : null);
            }
        }
        updateServerLabels();
    }

    private void renderPurchases(ResourcePoolController.Snapshot snapshot) {
        if (snapshot.purchases().isEmpty()) {
            renderPageAction("Resource Purchases", "Load More Purchases", snapshot.purchasePages(),
                    controller::loadMorePurchases);
            return;
        }
        content.addWidget(summary("Resource Purchases", "Payment And Review Do Not Reserve Capacity", "calm"));
        for (ResourcePoolModels.PurchaseStatus purchase : snapshot.purchases()) {
            ResourcePoolModels.Offer offer = offer(snapshot.offers(), purchase.offerId());
            String label = offer == null ? "Resource Purchase" : offer.label();
            String hint = purchaseHint(purchase);
            IconButton.Builder row = new IconButton.Builder().size(rowWidth(), 28)
                    .label(label + " • " + title(purchase.status().name())).hint(hint).imagePath("Reactor.png");
            if (purchase.checkoutUrl() != null && !purchase.checkoutUrl().isBlank()
                    && (purchase.status() == ResourcePoolModels.PurchaseState.PENDING
                    || purchase.status() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW)) {
                row.onClick(() -> showPurchase(purchase, offer));
            }
            content.addWidget(row.build());
        }
        renderPageAction("Resource Purchases", "Load More Purchases", snapshot.purchasePages(),
                controller::loadMorePurchases);
    }

    private static String purchaseHint(ResourcePoolModels.PurchaseStatus purchase) {
        return switch (purchase.status()) {
            case PENDING -> "Awaiting Payment Or Review • Capacity Is Not Yet Available";
            case NEEDS_REVIEW -> text(purchase.reviewReason(), "Purchase Needs Review • Capacity Is Not Yet Available");
            case ACTIVE -> "Resources Granted To The Pool";
            case PAYMENT_FAILED -> text(purchase.failureReason(), "Payment Failed • No Capacity Granted");
            case CANCELLED -> "Purchase Cancelled • No Capacity Granted";
            case REFUNDED -> "Purchase Refunded • Capacity May Be Removed";
            case EXPIRED -> "Checkout Expired • No Capacity Granted";
            case FAILED -> text(purchase.failureReason(), "Purchase Failed • No Capacity Granted");
        };
    }

    private void renderPool(ResourcePoolController.PoolView view) {
        ResourcePoolModels.Pool pool = view.pool();
        PoolAllocationEditor editor = editors.computeIfAbsent(pool.id(), ignored -> new PoolAllocationEditor());
        editor.accept(view);
        renderPoolBoard(editor);
        List<ResourcePoolModels.Draft> pendingDrafts = view.drafts().stream()
                .filter(draft -> draft.state() != ResourcePoolModels.DraftState.ACTIVE).toList();
        if (!pendingDrafts.isEmpty()) {
            Setting drafts = new Setting.Builder("Server Drafts").build();
            for (ResourcePoolModels.Draft draft : pendingDrafts) {
                IconButton row = draftRow(draft);
                draftRows.put(draft.id(), row);
                addSettingRow(drafts, row);
            }
            drafts.fitContentHeight();
            content.addWidget(drafts);
        }
        renderPageAction("Server Drafts", "Load More Drafts", view.draftPages(),
                () -> controller.loadMoreDrafts(pool.id()));
        renderPageAction("Pool Servers", "Load More Servers", view.allocationPages(),
                () -> controller.loadMoreAllocations(pool.id()));
    }

    private void renderPoolBoard(PoolAllocationEditor editor) {
        ResourcePoolController.PoolView view = editor.view();
        List<ResourcePoolModels.Allocation> active = new ArrayList<>();
        List<ResourcePoolModels.Allocation> inactive = new ArrayList<>();
        for (ResourcePoolModels.Allocation allocation : visibleAllocations(view.allocations())) {
            if (allocation.state() == ResourcePoolModels.AllocationState.DISABLED) inactive.add(allocation);
            else active.add(allocation);
        }
        active.sort(Comparator.comparing(allocation -> serverName(allocation.serverId()), String.CASE_INSENSITIVE_ORDER));
        List<ResourcePoolModels.Allocation> barAllocations = orderedAllocations(visibleAllocations(view.allocations()));
        Setting capacity = new Setting.Builder("Resource Pool").build();
        ResourceAllocationBarWidget ramBar = new ResourceAllocationBarWidget(editor, PoolAllocationEditor.Resource.RAM, barAllocations, this::serverName);
        ResourceAllocationBarWidget cpuBar = new ResourceAllocationBarWidget(editor, PoolAllocationEditor.Resource.CPU, barAllocations, this::serverName);
        ResourceAllocationBarWidget diskBar = new ResourceAllocationBarWidget(editor, PoolAllocationEditor.Resource.DISK, barAllocations, this::serverName);
        for (ResourceAllocationBarWidget bar : List.of(ramBar, cpuBar, diskBar)) {
            bar.onHover(hover -> highlightServer(bar, hover));
            bar.onChange(() -> barEdited(editor));
        }
        poolBars.put(view.pool().id(), List.of(ramBar, cpuBar, diskBar));
        Runnable refreshBars = () -> { ramBar.refresh(); cpuBar.refresh(); diskBar.refresh(); updateAllocationRows(editor); };
        addAllocationBar(capacity, ramBar);
        addAllocationBar(capacity, cpuBar);
        addAllocationBar(capacity, diskBar);
        addSettingRow(capacity, new MountableButtonWidget.Builder("Allocation Key")
                .description("Hover For Capacity • Hatched: Disabled Disk • *: Stop Or Restart To Free RAM")
                .build());
        capacity.fitContentHeight();
        content.addWidget(capacity);

        Setting.Builder serverBuilder = new Setting.Builder("Servers");
        serverBuilder.setExpandWithDropdowns(true);
        Setting servers = serverBuilder.build();
        if (active.isEmpty()) {
            addSettingRow(servers, new MountableButtonWidget.Builder("No Active Servers")
                    .description("Create A Server To Allocate Resources").build());
        }
        for (ResourcePoolModels.Allocation allocation : active) {
            String serverName = serverName(allocation.serverId());
            MountableButtonWidget row = new MountableButtonWidget.Builder(serverName)
                    .description(serverSummary(editor, allocation))
                    .icon(serverIcons.getOrDefault(allocation.serverId(), Identifier.icon("server.png")))
                    .onClick(() -> selectServer(editor, allocation.serverId())).build();
            row.setHint("Adjust " + serverName + " Resources");
            serverRows.put(allocation.serverId(), row);
            if (allocation.serverId().equals(expandedServerId) && editor.selected() != null
                    && editor.selected().serverId().equals(allocation.serverId())) {
                row.setEmbeddedBody(editorRows(editor, refreshBars), true);
            }
            addSettingRow(servers, row);
        }
        addSettingRow(servers, new MountableButtonWidget.Builder(modpack == null ? "Create Server" : "Create Modpack Server")
                .description(modpack == null ? "Allocate Resources From This Pool" : modpack.name())
                .onClick(() -> create(view.pool().id())).build());
        servers.fitContentHeight();
        content.addWidget(servers);

        if (!inactive.isEmpty()) {
            Setting.Builder inactiveBuilder = new Setting.Builder("Disabled Servers (" + inactive.size() + ")");
            inactiveBuilder.setExpandWithDropdowns(true);
            Setting inactiveGroup = inactiveBuilder.build();
            for (ResourcePoolModels.Allocation allocation : inactive) {
                String name = serverNames.getOrDefault(allocation.serverId(),
                        "Unlinked Allocation " + allocation.serverId().substring(0, Math.min(8, allocation.serverId().length())));
                MountableButtonWidget row = new MountableButtonWidget.Builder(name)
                        .description(serverSummary(editor, allocation))
                        .icon(serverIcons.getOrDefault(allocation.serverId(), Identifier.icon("server.png")))
                        .onClick(() -> selectServer(editor, allocation.serverId())).build();
                serverRows.put(allocation.serverId(), row);
                if (allocation.serverId().equals(expandedServerId) && editor.selected() != null
                        && editor.selected().serverId().equals(allocation.serverId())) {
                    row.setEmbeddedBody(editorRows(editor, refreshBars), true);
                }
                addSettingRow(inactiveGroup, row);
            }
            inactiveGroup.fitContentHeight();
            inactiveGroup.collapse(!inactive.stream().anyMatch(value -> value.serverId().equals(expandedServerId)));
            content.addWidget(inactiveGroup);
        }
    }

    private static List<ResourcePoolModels.Allocation> orderedAllocations(List<ResourcePoolModels.Allocation> allocations) {
        return allocations.stream().sorted(Comparator
                .comparing((ResourcePoolModels.Allocation allocation) -> allocation.state() == ResourcePoolModels.AllocationState.DISABLED)
                .thenComparing(ResourcePoolModels.Allocation::serverId)).toList();
    }

    private List<ResourcePoolModels.Allocation> visibleAllocations(List<ResourcePoolModels.Allocation> allocations) {
        return allocations.stream().filter(allocation -> !retired(allocation)).toList();
    }

    private boolean retired(ResourcePoolModels.Allocation allocation) {
        return serverNamesLoaded && allocation.state() == ResourcePoolModels.AllocationState.DISABLED
                && !serverNames.containsKey(allocation.serverId())
                && PoolAllocationEditor.number(allocation.retained().diskMiB()).signum() == 0
                && PoolAllocationEditor.number(allocation.retained().backupMiB()).signum() == 0;
    }

    private List<AnimatedWidget> editorRows(PoolAllocationEditor editor, Runnable refreshBars) {
        ResourcePoolModels.Allocation selected = editor.selected();
        if (selected == null) return List.of();
        List<AnimatedWidget> rows = new ArrayList<>();
        if (editor.limit(PoolAllocationEditor.Resource.RAM).signum() > 0
                    && editor.limit(PoolAllocationEditor.Resource.CPU).signum() > 0
                    && editor.limit(PoolAllocationEditor.Resource.DISK).signum() > 0
                    && (selected.state() == ResourcePoolModels.AllocationState.ACTIVE
                    || selected.state() == ResourcePoolModels.AllocationState.DISABLED)) {
                IconButton disable = selected.state() == ResourcePoolModels.AllocationState.ACTIVE
                        ? new IconButton.Builder().size(90, 20).label("Disable")
                        .imagePath("delete.png").autoWidthOnTextChange(true)
                        .onClick(() -> { if (editor.selected() != null) disable(editor.selected()); }).build() : null;
                boolean[] valid = {true, true, true};
                editorValidity.put(selected.serverId(), valid);
                List<Runnable> sync = new ArrayList<>();
                rows.add(allocationControl(editor, PoolAllocationEditor.Resource.RAM, 0, disable, valid, refreshBars, sync));
                rows.add(allocationControl(editor, PoolAllocationEditor.Resource.CPU, 1, disable, valid, refreshBars, sync));
                rows.add(allocationControl(editor, PoolAllocationEditor.Resource.DISK, 2, disable, valid, refreshBars, sync));
                sync.add(() -> updateActions(editor, disable));
                editorSync.put(selected.serverId(), sync);
                updateActions(editor, disable);
                if (disable != null) rows.add(new MountableButtonWidget.Builder("Actions").addWidget(disable).build());
            } else if (selected.state() == ResourcePoolModels.AllocationState.PENDING) {
                boolean waitingForRestart = new BigInteger(selected.desired().ramMiB())
                        .compareTo(new BigInteger(selected.effective().ramMiB())) < 0;
                rows.add(new MountableButtonWidget.Builder("Resource Change Pending")
                        .description(waitingForRestart ? "RAM Reduction Waits For A Stop Or Restart"
                                : "Provider Is Applying This Change").build());
            } else {
                rows.add(new MountableButtonWidget.Builder("No Resources Assigned")
                        .description("This Server Has No Adjustable Allocation").build());
            }
        if (selected.state() == ResourcePoolModels.AllocationState.DISABLED) {
            String serverId = selected.serverId();
            IconButton delete = new IconButton.Builder().size(90, 20).label("Delete Permanently")
                    .imagePath("delete.png").autoWidthOnTextChange(true)
                    .onClick(() -> confirmDelete(serverId)).build();
            rows.add(new MountableButtonWidget.Builder("Remove Server")
                    .description("Delete Server Files And Release Retained Disk")
                    .addWidget(delete).build());
        }
        if (!selected.serverId().isBlank()) {
            IconButton breakdown = new IconButton.Builder().size(150, 20).label("Storage Breakdown")
                    .imagePath("disk.png").autoWidthOnTextChange(true)
                    .onClick(() -> openStorageSettings(selected)).build();
            rows.add(new MountableButtonWidget.Builder("Server Files")
                    .description("See Which Folders And Files Use This Server's Disk")
                    .addWidget(breakdown).build());
        }
        return rows;
    }

    private void openStorageSettings(ResourcePoolModels.Allocation allocation) {
        String accountId = controller.snapshot().accountId();
        if (accountId.isBlank() || !accountId.equals(shownAccount)) return;
        ServerModels.ClientServerView cached = serverViews.get(allocation.serverId());
        if (cached != null) {
            openStorageSettings(allocation, cached);
            return;
        }
        host().restudioServers().whenComplete((servers, failure) -> host().application().execute(() -> {
            if (closed || !accountId.equals(controller.snapshot().accountId())) return;
            ServerModels.ClientServerView server = failure == null && servers != null ? servers.stream()
                    .filter(value -> value != null && allocation.serverId().equals(value.identifier))
                    .findFirst().orElse(null) : null;
            if (server == null) {
                new Notification("Server Settings Unavailable", "This Server Could Not Be Found", Notification.Type.WARN);
                return;
            }
            openStorageSettings(allocation, server);
        }));
    }

    private void openStorageSettings(ResourcePoolModels.Allocation allocation, ServerModels.ClientServerView server) {
        host().openServerConfiguration(this, server, ServerConfigurationScreen.STORAGE_TAB,
                allocation.retained().diskMiB());
    }

    private MountableButtonWidget allocationControl(PoolAllocationEditor editor, PoolAllocationEditor.Resource resource,
                                                    int index, IconButton disable,
                                                    boolean[] valid, Runnable refreshBars, List<Runnable> sync) {
        BigInteger limit = editor.limit(resource);
        TextInputWidget input = new TextInputWidget.Builder()
                .text(editor.value(resource).toString()).numericOnly(true).size(64, 20).build();
        DoubleSliderWidget slider = new DoubleSliderWidget.Builder()
                .size(Math.min(230, Math.max(90, rowWidth() - 165)), 20)
                .label(editor.value(resource).toString()).value(ratio(editor.value(resource), limit))
                .fineStep(limit.signum() <= 0 ? 0 : 1.0 / limit.doubleValue())
                .hint("Drag To Preview A Resource Change • Shift Drag For 1 MiB Or 1% Steps").build();
        boolean[] syncing = {false};
        sync.add(() -> {
            syncing[0] = true;
            String value = editor.value(resource).toString();
            input.setText(value);
            slider.label = value;
            slider.setValue(ratio(editor.value(resource), editor.limit(resource)));
            slider.setFineStep(editor.limit(resource).signum() <= 0 ? 0 : 1.0 / editor.limit(resource).doubleValue());
            valid[index] = true;
            syncing[0] = false;
        });
        slider.onChange = () -> {
            if (syncing[0]) return;
            if (editor.propose(resource, amount(slider.getValue(), editor.limit(resource)))) {
                slider.label = editor.value(resource).toString();
                slider.setValue(ratio(editor.value(resource), editor.limit(resource)));
                syncing[0] = true;
                input.setText(slider.label);
                syncing[0] = false;
                valid[index] = true;
            }
            updateActions(editor, disable);
            refreshBars.run();
        };
        input.onChange = () -> {
            if (syncing[0]) return;
            try {
                BigInteger value = new BigInteger(input.getText().trim());
                valid[index] = value.signum() > 0 && value.compareTo(editor.limit(resource)) <= 0 && editor.propose(resource, value);
                if (valid[index]) {
                    slider.setValue(ratio(editor.value(resource), editor.limit(resource)));
                    slider.label = editor.value(resource).toString();
                }
            } catch (NumberFormatException failure) {
                valid[index] = false;
            }
            updateActions(editor, disable);
            refreshBars.run();
        };
        String label = switch (resource) {
            case RAM -> "RAM";
            case CPU -> "CPU";
            case DISK -> "Disk";
            case BACKUP -> "Backup Storage";
        };
        return new MountableButtonWidget.Builder(label).description("Drag Or Enter A Value")
                .addWidget(slider).addWidget(input).build();
    }

    private static void updateActions(PoolAllocationEditor editor, IconButton disable) {
        if (disable != null) disable.setActive(!editor.locked() && !editor.busy() && !editor.submitted() && !editor.changed());
    }

    private static void addSettingRow(Setting setting, AnimatedWidget row) {
        addSettingRow(setting, row, 30);
    }

    private static void addSettingRow(Setting setting, AnimatedWidget row, int height) {
        row.setHeight(height);
        row.entranceAnimationEnabled = false;
        setting.addRow(new PopupWidget.PopupRow.Builder("", row).minHeight(height).build());
    }

    private static void addAllocationBar(Setting setting, ResourceAllocationBarWidget bar) {
        bar.setHeight(18);
        bar.entranceAnimationEnabled = false;
        setting.addRow(new PopupWidget.PopupRow.Builder("", bar).minHeight(18).build());
    }

    private void selectServer(PoolAllocationEditor editor, String serverId) {
        if (editor.selected() != null && editor.selected().serverId().equals(serverId)) {
            expandedServerId = serverId.equals(expandedServerId) ? "" : serverId;
            setServerExpanded(editor, serverId, !expandedServerId.isEmpty());
            return;
        }
        MountableButtonWidget previous = serverRows.get(expandedServerId);
        if (previous != null) previous.setEmbeddedBody(null, false);
        editorValidity.remove(expandedServerId);
        editorSync.remove(expandedServerId);
        editor.select(serverId);
        expandedServerId = serverId;
        setServerExpanded(editor, serverId, true);
    }

    private void setServerExpanded(PoolAllocationEditor editor, String serverId, boolean expanded) {
        MountableButtonWidget row = serverRows.get(serverId);
        if (row == null) return;
        if (expanded) {
            if (!row.hasEmbeddedBody()) row.setEmbeddedBody(editorRows(editor, () -> refreshBars(editor.view().pool().id())), true);
        } else {
            row.setEmbeddedBody(null, false);
            editorValidity.remove(serverId);
            editorSync.remove(serverId);
        }
    }

    void refreshBars(UUID poolId) {
        poolBars.getOrDefault(poolId, List.of()).forEach(ResourceAllocationBarWidget::refresh);
    }

    private void updateAllocationRows(PoolAllocationEditor editor) {
        for (ResourcePoolModels.Allocation allocation : editor.view().allocations()) {
            MountableButtonWidget row = serverRows.get(allocation.serverId());
            if (row != null) row.setDescription(serverSummary(editor, allocation));
        }
        updateChangeActions();
    }

    private static String serverSummary(PoolAllocationEditor editor, ResourcePoolModels.Allocation allocation) {
        String id = allocation.serverId();
        if (allocation.state() == ResourcePoolModels.AllocationState.DISABLED && !editor.changed(id)) {
            BigInteger disk = PoolAllocationEditor.number(allocation.retained().diskMiB());
            BigInteger backup = PoolAllocationEditor.number(allocation.retained().backupMiB());
            String retained = disk.signum() == 0 ? "No Disk Retained" : disk + " MiB Disk Retained";
            return "Disabled  •  RAM And CPU Released  •  " + retained
                    + (backup.signum() == 0 ? "" : "  •  Backup Storage Retained");
        }
        String status = editor.busy(id) ? "  •  Applying"
                : editor.submitted(id) || allocation.state() == ResourcePoolModels.AllocationState.PENDING ? "  •  Updating"
                : editor.waitingForCapacity(id) ? "  •  Waiting For Capacity"
                : editor.changed(id) ? "  •  Preview" : "";
        if (allocation.state() == ResourcePoolModels.AllocationState.DISABLE_PENDING) {
            status += "  •  RAM And CPU Release Awaits Stop";
        } else if (allocation.state() == ResourcePoolModels.AllocationState.PENDING
                && PoolAllocationEditor.number(allocation.desired().ramMiB()).compareTo(
                PoolAllocationEditor.number(allocation.effective().ramMiB())) < 0) {
            status += "  •  Stop Or Restart To Release RAM";
        }
        return editor.allocation(allocation, PoolAllocationEditor.Resource.RAM) + " MiB RAM  •  "
                + editor.allocation(allocation, PoolAllocationEditor.Resource.CPU) + "% CPU  •  "
                + editor.allocation(allocation, PoolAllocationEditor.Resource.DISK) + " MiB Disk" + status;
    }

    private void barEdited(PoolAllocationEditor editor) {
        ResourcePoolModels.Allocation selected = editor.selected();
        if (selected == null) return;
        String serverId = selected.serverId();
        if (!serverId.equals(expandedServerId)) {
            MountableButtonWidget previous = serverRows.get(expandedServerId);
            if (previous != null) previous.setEmbeddedBody(null, false);
            editorValidity.remove(expandedServerId);
            editorSync.remove(expandedServerId);
            expandedServerId = serverId;
        }
        setServerExpanded(editor, serverId, true);
        syncEditor(serverId);
        refreshBars(editor.view().pool().id());
        updateAllocationRows(editor);
    }

    private void highlightServer(ResourceAllocationBarWidget bar, ResourceAllocationBarWidget.HoveredServer hover) {
        ResourceAllocationBarWidget.HoveredServer before = accentHover.shown();
        ResourceAllocationBarWidget.HoveredServer target = accentHover.update(bar, hover, System.currentTimeMillis());
        if (Objects.equals(before, target)) return;
        MountableButtonWidget previous = serverRows.get(highlightedServerId);
        if (previous != null) previous.setAccent(highlightedOriginal);
        highlightedServerId = target == null ? null : target.serverId();
        MountableButtonWidget row = serverRows.get(highlightedServerId);
        highlightedOriginal = row == null ? null : row.getAccent();
        if (row != null) row.setAccent(target.accent());
    }

    private void syncEditor(String serverId) {
        editorSync.getOrDefault(serverId, List.of()).forEach(Runnable::run);
    }

    private String serverName(String id) {
        return serverNames.getOrDefault(id, "Server Name Unavailable");
    }

    private void updateServerLabels() {
        ResourcePoolController.Snapshot snapshot = displayed;
        if (snapshot == null) return;
        for (ResourcePoolController.PoolView view : snapshot.pools()) {
            for (ResourcePoolModels.Allocation allocation : view.allocations()) {
                MountableButtonWidget row = serverRows.get(allocation.serverId());
                if (row == null) continue;
                String name = serverName(allocation.serverId());
                row.setName(name);
                row.setHint("Adjust " + name + " Resources");
                Identifier icon = serverIcons.get(allocation.serverId());
                if (icon != null && !icon.equals(row.getIconId())) row.setIcon(icon);
            }
            refreshBars(view.pool().id());
        }
    }

    private static double ratio(BigInteger value, BigInteger limit) {
        return limit.signum() <= 0 ? 0 : new BigDecimal(value).divide(new BigDecimal(limit), 16, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigInteger amount(double ratio, BigInteger limit) {
        return new BigDecimal(limit).multiply(BigDecimal.valueOf(ratio)).setScale(0, RoundingMode.HALF_UP).toBigInteger();
    }

    private void renderPageAction(String name, String label, ResourcePoolController.PageState pages, Runnable action) {
        if (!pages.message().isBlank()) {
            content.addWidget(summary(name + " Need Attention", pages.message(), "danger"));
        }
        if (!pages.hasMore()) {
            return;
        }
        if (pages.loading()) {
            return;
        }
        content.addWidget(new IconButton.Builder().size(rowWidth(), 24)
                .label(label).hint(label)
                .imagePath("down.png").accentType(ThemeManager.getAccent("calm"))
                .onClick(action).build());
    }

    private static String specs(ResourcePoolModels.Resources resources) {
        return resources.ramMiB() + " MiB RAM • " + resources.cpuQuotaPercent() + "% CPU • "
                + resources.diskMiB() + " MiB Disk";
    }

    private void loadServerNames(String accountId) {
        namesAccount = accountId;
        host().restudioServers().whenComplete((servers, failure) -> host().application().execute(() -> {
            if (closed || !accountId.equals(shownAccount) || !accountId.equals(namesAccount) || failure != null) return;
            Map<String, String> names = new HashMap<>();
            Map<String, ServerModels.ClientServerView> views = new HashMap<>();
            if (servers != null) {
                for (ServerModels.ClientServerView server : servers) {
                    if (server != null && server.identifier != null && server.name != null && !server.name.isBlank()) {
                        names.put(server.identifier, server.name);
                        views.put(server.identifier, server);
                        Object target = host().iconTarget(server);
                        if (target != null) {
                            Identifier icon = iconManager.getQuickIconId(target);
                            if (icon != null) serverIcons.put(server.identifier, icon);
                            String serverId = server.identifier;
                            iconManager.loadIconIdAsync(target, loaded -> updateServerIcon(accountId, serverId, loaded));
                            iconManager.loadRemoteIconAsync(target,
                                    () -> iconManager.loadIconIdAsync(target, loaded -> updateServerIcon(accountId, serverId, loaded)));
                        }
                    }
                }
            }
            serverNames = Map.copyOf(names);
            serverViews = Map.copyOf(views);
            serverNamesLoaded = true;
            if (displayed != null && !layoutStamp(displayed).equals(layoutStamp)) render(displayed);
            else updateServerLabels();
        }));
    }

    private void updateServerIcon(String accountId, String serverId, Identifier icon) {
        if (icon == null) return;
        host().application().execute(() -> {
            if (closed || !accountId.equals(shownAccount) || icon.equals(serverIcons.get(serverId))) return;
            serverIcons.put(serverId, icon);
            MountableButtonWidget row = serverRows.get(serverId);
            if (row != null) row.setIcon(icon);
        });
    }

    private IconButton draftRow(ResourcePoolModels.Draft draft) {
        String state = draft.state() == ResourcePoolModels.DraftState.UNKNOWN ? "Activation Needs Review"
                : title(draft.state().name());
        String hint = draftHint(draft);
        IconButton.Builder card = new IconButton.Builder().size(rowWidth(), 30)
                .label(draft.metadata().name() + " • " + state).hint(hint).imagePath("server.png");
        if (draft.state() == ResourcePoolModels.DraftState.DRAFT) {
            card.onClick(() -> showDraftActivation(draft));
        } else if (draft.state() == ResourcePoolModels.DraftState.UNKNOWN) {
            card.onClick(() -> showDraftReview(draft));
        }
        return card.build();
    }

    private void showDraftReview(ResourcePoolModels.Draft draft) {
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Activation Needs Review").width(390)
                .addTitleAction("Check Status", () -> {
                    popup[0].hide();
                    controller.refresh();
                }, "Refresh This Draft", PopupWidget.TitleActionRole.PRIMARY)
                .onClose(() -> popup[0].hide());
        builder.addRow(detail("Server", draft.metadata().name()));
        builder.addRow(detail("Status", "Provider Result Is Unclear"));
        builder.addRow(detail("Reason", text(draft.reason(), "The Provider Result Is Unclear")));
        builder.addRow(detail("Next Step", "Check Status Before Trying Again"));
        popup[0] = show(builder.build());
    }

    private String draftHint(ResourcePoolModels.Draft draft) {
        if (draft.state() == ResourcePoolModels.DraftState.ABANDONED) {
            return "Previous Activation Stopped • Pool Resources Released";
        }
        if (draft.state() == ResourcePoolModels.DraftState.UNKNOWN) {
            return draft.reason() == null || draft.reason().isBlank() ? "Needs Review • Reservation Retained" : draft.reason();
        }
        if (draft.reserved() != null) {
            return draft.reserved().ramMiB() + " MiB RAM • " + draft.reserved().cpuQuotaPercent()
                    + "% CPU • " + (draft.state() == ResourcePoolModels.DraftState.ACTIVE ? "Active" : "Reservation Retained");
        }
        return "Ready To Review And Activate";
    }

    private void showOffers(List<ResourcePoolModels.Offer> offers) {
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Add Resources").width(430).virtualizeRows(true)
                .onClose(() -> popup[0].hide());
        for (ResourcePoolModels.Offer offer : offers) {
            IconButton card = new IconButton.Builder().size(380, 30)
                    .label(offer.label() + " • " + price(offer))
                    .hint(offer.locationLabel() + " • " + offer.cpuClassLabel() + " • " + offerSpecs(offer))
                    .imagePath("Reactor.png").onClick(() -> {
                popup[0].hide();
                showOfferReview(offer);
            }).build();
            builder.addRow(new PopupWidget.PopupRow.Builder("", card).minHeight(30).build());
        }
        popup[0] = show(builder.build());
    }

    private void showOfferReview(ResourcePoolModels.Offer offer) {
        ResourcePoolController.PurchaseIntent intent;
        try {
            intent = controller.beginPurchase(offer);
        } catch (RuntimeException failure) {
            new Notification("Purchase Needs Attention", ResourcePoolController.message(failure), Notification.Type.ERROR);
            return;
        }
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Review Resource Purchase").width(400)
                .addTitleAction("Continue", () -> {
                    popup[0].hide();
                    submitCheckout(intent, offer);
                }, "Create Checkout", PopupWidget.TitleActionRole.PRIMARY)
                .onClose(() -> {
                    controller.discardPurchase(intent);
                    popup[0].hide();
                });
        builder.addRow(detail("Offer", offer.label()));
        builder.addRow(detail("Plan", offer.planName()));
        builder.addRow(detail("Domain", offer.locationLabel() + " • " + offer.cpuClassLabel()));
        builder.addRow(detail("Compute", offer.ramMiB() + " MiB RAM • " + offer.cpuQuotaPercent() + "% CPU"));
        builder.addRow(detail("Disk", offer.diskMiB() + " MiB"));
        builder.addRow(detail("Price", price(offer)));
        builder.addRow(detail("Availability", "Capacity Is Granted Only After Payment And Review"));
        popup[0] = show(builder.build());
    }

    private void submitCheckout(ResourcePoolController.PurchaseIntent intent, ResourcePoolModels.Offer offer) {
        Notification notice = operationNotice("Creating Checkout", offer.label());
        controller.checkout(intent).whenComplete((purchase, failure) -> host().application().execute(() -> {
            if (!closed) {
                finishCheckout(notice, purchase, failure, offer);
            }
        }));
    }

    private void finishCheckout(Notification notice, ResourcePoolController.PurchaseResult purchase, Throwable failure,
                                ResourcePoolModels.Offer offer) {
        if (failure != null) {
            notice.update().message("Checkout Needs Attention").description(ResourcePoolController.message(failure))
                    .type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            controller.refresh();
            return;
        }
        String message = switch (purchase.state()) {
            case ACTIVE -> "Resources Active";
            case PENDING -> "Checkout Ready";
            case NEEDS_REVIEW -> "Purchase Needs Review";
            case PAYMENT_FAILED -> "Payment Failed";
            case CANCELLED -> "Purchase Cancelled";
            case REFUNDED -> "Purchase Refunded";
            case EXPIRED -> "Checkout Expired";
            case FAILED -> "Purchase Failed";
        };
        String description = switch (purchase.state()) {
            case ACTIVE -> "Capacity Is Available In The Resource Pool";
            case PENDING -> "Complete Checkout And Review Before Capacity Is Available";
            case NEEDS_REVIEW -> text(purchase.reviewReason(), "Capacity Is Not Yet Available");
            case PAYMENT_FAILED, FAILED -> text(purchase.failureReason(), "No Capacity Was Granted");
            case CANCELLED, EXPIRED -> "No Capacity Was Granted";
            case REFUNDED -> "Capacity May Be Removed From The Resource Pool";
        };
        Notification.Type type = purchase.state() == ResourcePoolModels.PurchaseState.ACTIVE ? Notification.Type.SUCCESS
                : purchase.state() == ResourcePoolModels.PurchaseState.PENDING ? Notification.Type.INFO
                : purchase.state() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW ? Notification.Type.WARN
                : Notification.Type.ERROR;
        notice.update().message(message).description(description).type(type).loading(false).autoSlideOut(true).commit();
        if (purchase.checkoutUrl() != null && !purchase.checkoutUrl().isBlank()
                && (purchase.state() == ResourcePoolModels.PurchaseState.PENDING
                || purchase.state() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW)) {
            showCheckoutLink(purchase.checkoutUrl(), offer, title(purchase.state().name()));
        }
        controller.refresh();
    }

    private void showPurchase(ResourcePoolModels.PurchaseStatus purchase, ResourcePoolModels.Offer offer) {
        String label = offer == null ? "Resource Purchase" : offer.label();
        showCheckoutLink(purchase.checkoutUrl(), offer, label + " • " + title(purchase.status().name()));
    }

    private void showCheckoutLink(String url, ResourcePoolModels.Offer offer, String state) {
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Resource Checkout").width(400)
                .addTitleAction("Open Checkout", () -> {
                    popup[0].hide();
                    host().openExternal(url);
                }, "Open Secure Checkout", PopupWidget.TitleActionRole.PRIMARY)
                .onClose(() -> popup[0].hide());
        builder.addRow(detail("Status", state));
        if (offer != null) {
            builder.addRow(detail("Offer", offer.label()));
            builder.addRow(detail("Resources", offerSpecs(offer)));
            builder.addRow(detail("Domain", offer.locationLabel() + " • " + offer.cpuClassLabel()));
            builder.addRow(detail("Price", price(offer)));
        }
        builder.addRow(detail("Availability", "Payment And Review Must Finish Before Capacity Is Granted"));
        popup[0] = show(builder.build());
    }

    private void create(UUID poolId) {
        handoff = true;
        ScreenManager.getInstance().navigate(this, new ServerConfigurationScreen(this, remotelyClient, controller.begin(poolId), modpack));
    }

    void applyChanges(UUID poolId) {
        if (applyingChanges() || editors.values().stream().anyMatch(PoolAllocationEditor::hasPending)) return;
        if (poolId == null && editorValidity.values().stream().anyMatch(values -> !values[0] || !values[1] || !values[2])) {
            new Notification("Check Resource Values", "Enter Valid Values Before Applying Changes", Notification.Type.WARN);
            return;
        }
        List<PendingChange> changes = new ArrayList<>();
        editors.forEach((id, editor) -> {
            if (poolId == null || poolId.equals(id)) editor.changes().forEach(change -> changes.add(new PendingChange(id, change)));
        });
        if (changes.isEmpty()) return;
        changes.sort(Comparator.comparing((PendingChange pending) -> pending.change().reduction()).reversed()
                .thenComparing(pending -> pending.poolId().toString()).thenComparing(pending -> pending.change().serverId()));
        changeAccount = controller.snapshot().accountId();
        changeRun++;
        changeWaiting = false;
        reductionWaiting = false;
        changeFailure = null;
        changeFailureType = null;
        reductionsInFlight = 0;
        pendingChanges.addAll(changes);
        changes.forEach(change -> editors.get(change.poolId()).lock(true));
        changeNotice = new Notification("Applying Resource Changes", changes.size() + " Server Changes Queued", Notification.Type.INFO);
        updateChangeActions();
        advanceChanges();
    }

    void discardChanges(UUID poolId) {
        if (applyingChanges()) return;
        editors.forEach((id, editor) -> {
            if (poolId != null && !poolId.equals(id)) return;
            editor.lock(false);
            editor.discardAll();
            refreshBars(id);
            if (editor.selected() != null) syncEditor(editor.selected().serverId());
            updateAllocationRows(editor);
        });
        updateChangeActions();
    }

    private void advanceChanges() {
        if (closed && !handoff) return;
        if (changeFailure != null) {
            if (changesInFlight == 0) finishChanges(changeFailure, changeFailureType);
            return;
        }
        if (pendingChanges.isEmpty()) {
            if (changesInFlight == 0 && changeNotice != null) stopChanges("All Server Changes Submitted", Notification.Type.SUCCESS);
            return;
        }
        if (!changeAccount.equals(controller.snapshot().accountId())) {
            stopChanges("Account Changed", Notification.Type.ERROR);
            return;
        }
        pendingChanges.removeIf(pending -> {
            PoolAllocationEditor editor = editors.get(pending.poolId());
            return editor != null && (pending.change().reduction()
                    ? editor.reductionSettled(pending.change()) : editor.applied(pending.change()));
        });
        if (pendingChanges.isEmpty()) {
            if (changesInFlight > 0) return;
            stopChanges("All Server Changes Submitted", Notification.Type.SUCCESS);
            return;
        }
        for (PendingChange pending : pendingChanges) {
            PoolAllocationEditor editor = editors.get(pending.poolId());
            if (editor == null || !editor.matches(pending.change())
                    && !(pending.change().reduction() && editor.applied(pending.change()))) {
                stopChanges("A Server Changed While Applying Resources", Notification.Type.WARN);
                return;
            }
        }
        boolean reductionsPending = reductionsInFlight > 0
                || pendingChanges.stream().anyMatch(pending -> pending.change().reduction());
        if (!reductionsPending && reductionWaiting) {
            reductionWaiting = false;
            changeWaiting = false;
        }
        for (PendingChange pending : List.copyOf(pendingChanges)) {
            if (changesInFlight >= MAX_PARALLEL_CHANGES) return;
            PoolAllocationEditor editor = editors.get(pending.poolId());
            if (pending.change().reduction()) {
                if (editor.applied(pending.change())) continue;
                if (editor.busy(pending.change().serverId()) || editor.submitted(pending.change().serverId())) continue;
                submitChange(pending, editor);
                if (pendingChanges.isEmpty()) return;
                continue;
            }
            if (reductionsPending) continue;
            if (editor.independentCapacity()) {
                if (editor.busy(pending.change().serverId()) || editor.submitted(pending.change().serverId())) continue;
            } else if (!editor.canApply(pending.change().serverId())) continue;
            submitChange(pending, editor);
            if (pendingChanges.isEmpty()) return;
        }
        if (changesInFlight > 0) return;
        if (reductionsPending) {
            boolean unreleased = pendingChanges.stream().filter(pending -> pending.change().reduction()).anyMatch(pending -> {
                PoolAllocationEditor editor = editors.get(pending.poolId());
                ResourcePoolModels.Allocation allocation = editor.allocation(pending.change().serverId());
                return editor.applied(pending.change()) && allocation != null
                        && (allocation.state() == ResourcePoolModels.AllocationState.ACTIVE
                        || allocation.state() == ResourcePoolModels.AllocationState.DISABLED);
            });
            if (unreleased) {
                stopChanges("A Reduction Did Not Release Its Resources. Refresh And Review The Pool", Notification.Type.WARN);
                return;
            }
            if (!changeWaiting) {
                changeWaiting = true;
                reductionWaiting = true;
                changeNotice.update().message("Waiting For Reductions To Settle")
                        .description("Server Increases Resume After Released Resources Are Available")
                        .type(Notification.Type.INFO).loading(false).commit();
            }
            return;
        }
        if (pendingChanges.stream().anyMatch(pending -> editors.get(pending.poolId()).hasPending())) return;
        if (pendingChanges.stream().noneMatch(pending -> editors.get(pending.poolId())
                .waitingForCapacity(pending.change().serverId()))) {
            stopChanges("Refresh The Pool And Review Remaining Changes", Notification.Type.WARN);
            return;
        }
        if (!changeWaiting) {
            changeWaiting = true;
            changeNotice.update().message("Waiting For Free Capacity")
                    .description("Remaining Changes Resume When Capacity Becomes Free")
                    .type(Notification.Type.INFO).loading(false).commit();
        }
    }

    private void submitChange(PendingChange pending, PoolAllocationEditor editor) {
        PoolAllocationEditor.Change change = pending.change();
        ResourcePoolModels.Allocation allocation = editor.allocation(change.serverId());
        boolean ready = allocation != null && (change.reduction() ? editor.beginReduction(change.serverId())
                : editor.independentCapacity() ? editor.beginIndependent(change.serverId()) : editor.begin(change.serverId()));
        if (!ready) {
            stopChanges("Refresh The Pool And Review Remaining Changes", Notification.Type.WARN);
            return;
        }
        changesInFlight++;
        if (change.reduction()) reductionsInFlight++;
        changeWaiting = false;
        changeNotice.update().message("Applying Resource Changes")
                .description("Updating " + serverName(change.serverId())).type(Notification.Type.INFO).loading(true).commit();
        if (editor.selected() != null) syncEditor(editor.selected().serverId());
        updateAllocationRows(editor);
        long run = changeRun;
        try {
            controller.assign(allocation, change.ram().toString(), change.cpu().toString(), change.disk().toString())
                    .whenComplete((progress, failure) -> host().application().execute(() -> {
                        boolean accepted = failure == null && progress != null
                                && progress.operation().state() != ResourcePoolModels.OperationState.UNKNOWN;
                        editor.finish(change.serverId(), accepted);
                        if (run != changeRun) return;
                        changesInFlight--;
                        if (change.reduction()) reductionsInFlight--;
                        if (editor.selected() != null) syncEditor(editor.selected().serverId());
                        updateAllocationRows(editor);
                        if (!accepted) {
                            controller.refresh();
                            stopChanges(failure == null ? "Resource Change Needs Review"
                                    : ResourcePoolController.message(failure), Notification.Type.ERROR);
                        } else {
                            if (changeFailure != null) {
                                if (changesInFlight == 0) finishChanges(changeFailure, changeFailureType);
                                return;
                            }
                            if (!editor.applied(change)) {
                                changeNotice.update().message("Waiting For Server Update")
                                        .description("Remaining Changes Continue After The Pool Refreshes")
                                        .type(Notification.Type.INFO).loading(false).commit();
                            }
                            advanceChanges();
                        }
                    }));
        } catch (RuntimeException failure) {
            editor.finish(change.serverId(), false);
            changesInFlight--;
            if (change.reduction()) reductionsInFlight--;
            stopChanges(ResourcePoolController.message(failure), Notification.Type.ERROR);
        }
    }

    private void stopChanges(String message, Notification.Type type) {
        if (changesInFlight > 0) {
            changeFailure = message;
            changeFailureType = type;
            changeNotice.update().message("Finishing Submitted Changes")
                    .description("Other Submitted Server Changes May Still Complete")
                    .type(Notification.Type.INFO).loading(true).commit();
            return;
        }
        finishChanges(message, type);
    }

    private void finishChanges(String message, Notification.Type type) {
        changeRun++;
        pendingChanges.clear();
        changesInFlight = 0;
        reductionsInFlight = 0;
        changeWaiting = false;
        reductionWaiting = false;
        changeFailure = null;
        changeFailureType = null;
        editors.values().forEach(editor -> editor.lock(false));
        if (changeNotice != null) {
            changeNotice.update().message(type == Notification.Type.SUCCESS ? message : "Resource Changes Need Attention")
                    .description(type == Notification.Type.SUCCESS ? "All Previewed Changes Were Submitted"
                            : message + ". Review Applied And Pending Changes Before Retrying")
                    .type(type).loading(false).autoSlideOut(true).commit();
            changeNotice = null;
        }
        updateChangeActions();
    }

    private void showDraftActivation(ResourcePoolModels.Draft draft) {
        String accountId = controller.snapshot().accountId();
        remotelyClient.getApiClient().resourcePools().getDraftOptions().whenComplete((options, failure) ->
                host().application().execute(() -> {
                    if (closed || !accountId.equals(controller.snapshot().accountId())) return;
                    if (failure != null || options == null) {
                        new Notification("Activation Unavailable", "Could Not Check Setup Requirements", Notification.Type.ERROR);
                        return;
                    }
                    ResourcePoolController.PoolView current = poolView(draft.poolId());
                    if (current == null || current.drafts().stream().noneMatch(value -> value.id().equals(draft.id())
                            && value.state() == ResourcePoolModels.DraftState.DRAFT)) {
                        new Notification("Draft Changed", "Refresh Resources And Review The Draft Again", Notification.Type.WARN);
                        return;
                    }
                    showDraftActivation(draft, options);
                }));
    }

    private void showDraftActivation(ResourcePoolModels.Draft draft, ResourcePoolModels.DraftOptions options) {
        ResourcePoolController.PoolView view = poolView(draft.poolId());
        if (view == null) {
            new Notification("Draft Needs Attention", "Refresh Resources And Try Again", Notification.Type.WARN);
            return;
        }
        DraftResources resources = new DraftResources(view, draft, options);
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Activate " + draft.metadata().name()).width(390)
                .padding(6).rowGap(3).labelGap(2)
                .addTitleAction("Activate", () -> {
                    if (!resources.canActivate()) {
                        new Notification("Draft Needs Resources", resources.missingCapacity(), Notification.Type.WARN);
                        return;
                    }
                    popup[0].hide();
                    Notification notice = operationNotice("Submitting Activation", draft.metadata().name());
                    controller.activate(draft, resources.values())
                            .whenComplete((updated, failure) -> host().application().execute(() -> finishActivation(notice, updated, failure)));
                }, "Activate Server", PopupWidget.TitleActionRole.PRIMARY)
                .onClose(() -> popup[0].hide());
        if (!resources.canActivate() && !controller.snapshot().offers().isEmpty()) {
            builder.addTitleAction("Add Resources", () -> {
                popup[0].hide();
                showOffers(controller.snapshot().offers());
            }, "Review Available Resource Offers", PopupWidget.TitleActionRole.SECONDARY);
        }
        builder.addRow(new PopupWidget.PopupRow.Builder("Setup RAM", resources.control(0))
                .description("Memory Available During Installation").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Setup CPU", resources.control(1))
                .description("CPU Available During Installation").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Server RAM", resources.control(2))
                .description("Memory Available While The Server Runs").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Server CPU", resources.control(3))
                .description("CPU Available While The Server Runs").build());
        builder.addRow(new PopupWidget.PopupRow.Builder("Disk", resources.control(4))
                .description("Storage For Server Files").build());
        popup[0] = show(builder.build());
    }

    private void disable(ResourcePoolModels.Allocation allocation) {
        Notification notice = operationNotice("Submitting Disable", allocation.serverId());
        controller.disable(allocation).whenComplete((progress, failure) ->
                host().application().execute(() -> finishMutation(notice, progress, failure, true)));
    }

    private void confirmDelete(String serverId) {
        if (!pendingChanges.isEmpty() || editors.values().stream().anyMatch(PoolAllocationEditor::hasChanges)
                || deletingServers.contains(serverId)) {
            new Notification("Apply Resource Changes First", "Finish Or Discard Pending Changes Before Deleting A Server", Notification.Type.WARN);
            return;
        }
        ResourcePoolModels.Allocation allocation = editors.values().stream().map(editor -> editor.allocation(serverId))
                .filter(Objects::nonNull).findFirst().orElse(null);
        String name = serverNames.get(serverId);
        if (allocation == null || allocation.state() != ResourcePoolModels.AllocationState.DISABLED
                || name == null || name.isBlank()) {
            new Notification("Server Needs Review", "Refresh Resources Before Deleting This Server", Notification.Type.WARN);
            return;
        }
        String accountId = controller.snapshot().accountId();
        PopupWidget[] popup = new PopupWidget[1];
        TextInputWidget confirmation = new TextInputWidget.Builder().placeholder(name).size(300, 20).build();
        PopupWidget.Builder builder = new PopupWidget.Builder("Delete " + name).width(400)
                .addTitleAction("Delete Permanently", () -> {
                    if (!name.equals(confirmation.getText()) || !accountId.equals(controller.snapshot().accountId())) {
                        new Notification("Name Does Not Match", "Enter The Server Name Exactly To Delete It", Notification.Type.WARN);
                        return;
                    }
                    popup[0].hide();
                    deletingServers.add(serverId);
                    Notification notice = operationNotice("Deleting Server", name);
                    remotelyClient.getApiClient().deleteServer(serverId, name).whenComplete((ignored, failure) ->
                            host().application().execute(() -> {
                                deletingServers.remove(serverId);
                                if (closed || !accountId.equals(controller.snapshot().accountId())) return;
                                if (failure == null) {
                                    notice.update().message("Server Deleted").description(name + " And Its Files Were Removed")
                                            .type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
                                } else {
                                    notice.update().message("Deletion Needs Attention")
                                            .description(ResourcePoolController.message(failure)).type(Notification.Type.ERROR)
                                            .loading(false).autoSlideOut(true).commit();
                                }
                                controller.refresh();
                                loadServerNames(accountId);
                            }));
                }, "Delete Server And Files", PopupWidget.TitleActionRole.DESTRUCTIVE)
                .onClose(() -> popup[0].hide());
        builder.addRow(detail("Server", name));
        builder.addRow(detail("Files", "All Server Files And Worlds Will Be Deleted"));
        builder.addRow(new PopupWidget.PopupRow.Builder("Enter Server Name", confirmation)
                .description("Type The Exact Server Name To Confirm Permanent Deletion").build());
        popup[0] = show(builder.build());
    }

    private void finishActivation(Notification notice, ResourcePoolModels.Draft draft, Throwable failure) {
        if (failure != null) {
            notice.update().message("Activation Needs Attention").description(ResourcePoolController.message(failure))
                    .type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            controller.refresh();
            return;
        }
        Notification.Type type = draft.state() == ResourcePoolModels.DraftState.ACTIVE ? Notification.Type.SUCCESS
                : draft.state() == ResourcePoolModels.DraftState.UNKNOWN ? Notification.Type.WARN : Notification.Type.INFO;
        String message = draft.state() == ResourcePoolModels.DraftState.ACTIVE ? "Server Active"
                : draft.state() == ResourcePoolModels.DraftState.UNKNOWN ? "Activation Needs Review" : "Activation Submitted";
        String detail = draft.state() == ResourcePoolModels.DraftState.UNKNOWN
                ? draft.reason() == null ? "Reservation Retained" : draft.reason() : draft.metadata().name();
        notice.update().message(message).description(detail).type(type).loading(false).autoSlideOut(true).commit();
        controller.refresh();
    }

    private void finishMutation(Notification notice, ResourcePoolModels.Progress progress, Throwable failure, boolean disable) {
        if (failure != null) {
            notice.update().message(disable ? "Disable Needs Attention" : "Resource Change Needs Attention")
                    .description(ResourcePoolController.message(failure)).type(Notification.Type.ERROR)
                    .loading(false).autoSlideOut(true).commit();
            controller.refresh();
            return;
        }
        boolean settled = progress.operation().state() == ResourcePoolModels.OperationState.SETTLED;
        boolean review = progress.operation().state() == ResourcePoolModels.OperationState.UNKNOWN
                || progress.hosting() != null && progress.hosting().state() == ResourcePoolModels.HostingState.NEEDS_REVIEW;
        String message = review ? "Operation Needs Review" : settled ? disable ? "Resources Disabled" : "Resources Updated"
                : disable ? "Disable Submitted" : "Resource Change Submitted";
        String detail = review && progress.hosting() != null && progress.hosting().reason() != null
                ? progress.hosting().reason() : disable && !settled ? "Resources Stay Reserved Until Disable Settles" : progressLabel(progress);
        notice.update().message(message).description(detail).type(review ? Notification.Type.WARN
                : settled ? Notification.Type.SUCCESS : Notification.Type.INFO).loading(false).autoSlideOut(true).commit();
        controller.refresh();
    }

    private PopupWidget.PopupRow detail(String name, String value) {
        AnimatedButton text = new AnimatedButton.Builder().size(340, 22).label(value).centered(false).build();
        return new PopupWidget.PopupRow.Builder(name, text).contentWidth().build();
    }

    private PopupWidget show(PopupWidget popup) {
        popup.setX((width - popup.getWidth()) / 2);
        popup.setY((height - popup.getHeight()) / 2);
        addDrawableChild(popup);
        popup.show();
        return popup;
    }

    private Notification operationNotice(String message, String description) {
        return new Notification.Builder().message(message).description(description).type(Notification.Type.INFO)
                .loading(true).autoSlideOut(false).build();
    }

    private AnimatedButton summary(String label, String hint, String accent) {
        Accent color = ThemeManager.getAccent(accent);
        return new AnimatedButton.Builder().size(rowWidth(), 22).label(label).hint(hint).accentType(color).build();
    }

    private int rowWidth() {
        return Math.max(220, width - 24);
    }

    private static boolean zero(ResourcePoolModels.Resources resources) {
        return "0".equals(resources.ramMiB()) && "0".equals(resources.cpuQuotaPercent())
                && "0".equals(resources.diskMiB()) && "0".equals(resources.backupMiB());
    }

    private static String compute(ResourcePoolModels.Compute compute) {
        return compute.ramMiB() + " MiB RAM / " + compute.cpuQuotaPercent() + "% CPU";
    }

    private static ResourcePoolModels.Offer offer(List<ResourcePoolModels.Offer> offers, String offerId) {
        return offers.stream().filter(offer -> offer.id().equals(offerId)).findFirst().orElse(null);
    }

    private static String offerSpecs(ResourcePoolModels.Offer offer) {
        return offer.ramMiB() + " MiB RAM • " + offer.cpuQuotaPercent() + "% CPU • " + offer.diskMiB() + " MiB Disk";
    }

    private static String price(ResourcePoolModels.Offer offer) {
        if (offer.priceCurrency() == null || offer.billingPeriodLabel() == null) {
            return "See Checkout For Price And Billing Period";
        }
        String cents = offer.priceCents();
        String padded = cents.length() < 3 ? "0".repeat(3 - cents.length()) + cents : cents;
        return offer.priceCurrency() + " " + padded.substring(0, padded.length() - 2) + "."
                + padded.substring(padded.length() - 2) + " • " + offer.billingPeriodLabel();
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean pendingRestart(ResourcePoolModels.Allocation allocation) {
        return allocation.state() == ResourcePoolModels.AllocationState.ACTIVE
                && (!allocation.desired().ramMiB().equals(allocation.effective().ramMiB())
                || !allocation.desired().cpuQuotaPercent().equals(allocation.effective().cpuQuotaPercent()));
    }

    private static String progressLabel(ResourcePoolModels.Progress progress) {
        String operation = title(progress.operation().state().name());
        if (progress.hosting() == null) {
            return operation;
        }
        String result = operation + " • " + title(progress.hosting().state().name());
        return progress.hosting().reason() == null || progress.hosting().reason().isBlank()
                ? result : result + " • " + progress.hosting().reason();
    }

    private static String title(String value) {
        String[] words = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScreenManager.getInstance().goBack(this, parent);
    }

    @Override
    public void onResumed() {
        closed = false;
        handoff = false;
        controller.refresh();
    }

    @Override
    public void removed() {
        closed = true;
        setLoading(false);
        if (!handoff) {
            if (applyingChanges()) finishChanges("Submitted Changes May Still Finish. Refresh Resources Before Retrying", Notification.Type.WARN);
            controller.dispose();
        }
        super.removed();
    }

    private static final class DraftResources {
        private final ResourcePoolModels.Draft draft;
        private final BigInteger installerRam;
        private final BigInteger installerCpu;
        private final BigInteger[] capacity = new BigInteger[4];
        private final BigInteger[] selected = new BigInteger[6];
        private final DoubleSliderWidget[] sliders = new DoubleSliderWidget[6];

        private DraftResources(ResourcePoolController.PoolView view, ResourcePoolModels.Draft draft,
                               ResourcePoolModels.DraftOptions options) {
            this.draft = draft;
            installerRam = BigInteger.valueOf(Math.max(1, options.minimumInstallerRamMiB()));
            installerCpu = BigInteger.valueOf(Math.max(1, options.minimumInstallerCpuPercent()));
            ResourcePoolModels.Resources free = view.pool().balance().available();
            ResourcePoolModels.Compute held = draft.reserved();
            ResourcePoolModels.Storage storage = draft.retained();
            capacity[0] = number(free.ramMiB()).add(number(held == null ? null : held.ramMiB()));
            capacity[1] = number(free.cpuQuotaPercent()).add(number(held == null ? null : held.cpuQuotaPercent()));
            capacity[2] = number(free.diskMiB()).add(number(storage == null ? null : storage.diskMiB()));
            capacity[3] = number(free.backupMiB()).add(number(storage == null ? null : storage.backupMiB()));
            selected[0] = initial(draft.installer() == null ? null : draft.installer().ramMiB(), capacity[0], 2048).max(installerRam).min(capacity[0]);
            selected[1] = initial(draft.installer() == null ? null : draft.installer().cpuQuotaPercent(), capacity[1], 200).max(installerCpu).min(capacity[1]);
            selected[2] = initial(draft.runtime() == null ? null : draft.runtime().ramMiB(), capacity[0], 2048);
            selected[3] = initial(draft.runtime() == null ? null : draft.runtime().cpuQuotaPercent(), capacity[1], 200);
            selected[4] = initial(storage == null ? null : storage.diskMiB(), capacity[2], 10240);
            selected[5] = number(storage == null ? null : storage.backupMiB()).min(capacity[3]);
        }

        private boolean canActivate() {
            return capacity[0].compareTo(installerRam) >= 0 && capacity[1].compareTo(installerCpu) >= 0
                    && capacity[2].signum() > 0;
        }

        private String missingCapacity() {
            List<String> missing = new ArrayList<>();
            if (capacity[0].compareTo(installerRam) < 0) missing.add(installerRam + " MiB Setup RAM");
            if (capacity[1].compareTo(installerCpu) < 0) missing.add(installerCpu + "% Setup CPU");
            if (capacity[2].signum() == 0) missing.add("Disk");
            return "Add " + String.join(", ", missing) + " To This Pool Before Activation";
        }

        private DoubleSliderWidget control(int index) {
            String title = switch (index) {
                case 0, 2 -> "RAM";
                case 1, 3 -> "CPU";
                case 4 -> "Disk";
                default -> "Backup Storage";
            };
            DoubleSliderWidget slider = new DoubleSliderWidget.Builder().size(230, 20)
                    .value(fraction(index)).label(label(index))
                    .hint("Drag To Adjust " + title + " • " + format(capacity[resource(index)], index) + " Available").build();
            sliders[index] = slider;
            slider.setActive(capacity[resource(index)].compareTo(minimum(index)) > 0);
            slider.onChange = () -> {
                BigInteger limit = capacity[resource(index)];
                BigInteger range = limit.subtract(minimum(index)).max(BigInteger.ZERO);
                selected[index] = minimum(index).add(amount(slider.getValue(), range)).min(limit);
                refresh();
            };
            slider.setTextCommitHandler(value -> {
                String amount = value.trim().replaceFirst("[^0-9].*$", "");
                if (amount.isEmpty()) return;
                BigInteger entered = new BigInteger(amount);
                if (index >= 4 && value.trim().toLowerCase(Locale.ROOT).matches("^[0-9]+\\s*gib.*")) {
                    entered = entered.multiply(BigInteger.valueOf(1024));
                }
                selected[index] = entered.max(minimum(index)).min(capacity[resource(index)]);
                slider.setValue(fraction(index));
                refresh();
            });
            refresh();
            return slider;
        }

        private void refresh() {
            for (int index = 0; index < sliders.length; index++) {
                if (sliders[index] == null) continue;
                sliders[index].label = label(index);
            }
        }

        private double fraction(int index) {
            BigInteger span = capacity[resource(index)].subtract(minimum(index));
            return span.signum() <= 0 ? 0 : ratio(selected[index].subtract(minimum(index)), span);
        }

        private String label(int index) {
            BigInteger used = index < 4 ? selected[index % 2].max(selected[index % 2 + 2]) : selected[index];
            BigInteger free = capacity[resource(index)].subtract(used).max(BigInteger.ZERO);
            return format(selected[index], index) + " • " + format(free, index) + " Free";
        }

        private static String format(BigInteger value, int index) {
            if (index == 1 || index == 3) return value + "%";
            if ((index == 4 || index == 5) && value.mod(BigInteger.valueOf(1024)).signum() == 0) {
                return value.divide(BigInteger.valueOf(1024)) + " GiB";
            }
            return value + " MiB";
        }

        private static int resource(int index) {
            return index < 4 ? index % 2 : index - 2;
        }

        private BigInteger minimum(int index) {
            return switch (index) {
                case 0 -> installerRam;
                case 1 -> installerCpu;
                case 5 -> BigInteger.ZERO;
                default -> BigInteger.ONE;
            };
        }

        private static BigInteger initial(String value, BigInteger limit, long fallback) {
            BigInteger proposed = value == null ? BigInteger.valueOf(fallback) : number(value);
            return proposed.max(BigInteger.ONE).min(limit.max(BigInteger.ZERO));
        }

        private static BigInteger number(String value) {
            return value == null ? BigInteger.ZERO : new BigInteger(value);
        }

        private ServerScreenHost.PoolResources values() {
            return new ServerScreenHost.PoolResources(draft.metadata().gameId(), draft.metadata().profileId(),
                    selected[0].toString(), selected[1].toString(), selected[2].toString(), selected[3].toString(),
                    selected[4].toString(), selected[5].toString());
        }
    }
}
