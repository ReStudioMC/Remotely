package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.ui.widgets.ExternalLinkActions;
import restudio.rebase.ui.screens.feedback.CreateFeedbackPopup;
import restudio.rebase.resource.marketplace.HostedModpackSelection;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.Async;
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
import restudio.rescreen.ui.widgets.DeletionPopup;
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
    private final Map<String, ResourcePoolModels.Allocation> editorAllocations = new HashMap<>();
    private String highlightedServerId;
    private Accent highlightedOriginal;
    private ResourcePoolController.Snapshot displayed;
    private List<ResourcePoolModels.Rate> displayedRates = List.of();
    private boolean createRequested;
    private String layoutStamp;
    private ResourcePurchaseFlow purchaseFlow;
    private final Map<UUID, PoolBoard> poolBoards = new HashMap<>();
    private final Map<UUID, IconButton> purchaseRows = new HashMap<>();
    private final Map<String, IconButton> pageButtons = new HashMap<>();
    private final Map<String, AnimatedButton> summaries = new HashMap<>();
    private final Map<AnimatedWidget, PopupWidget.PopupRow> settingRows = new HashMap<>();
    private final List<AnimatedWidget> nextWidgets = new ArrayList<>();
    private Setting purchaseHistory;
    private IconButton buyResources;
    private PurchaseView purchaseView;

    private static final class PendingChange {
        private final UUID poolId;
        private final PoolAllocationEditor.Change change;
        private UUID requestId;

        private PendingChange(UUID poolId, PoolAllocationEditor.Change change) {
            this.poolId = poolId;
            this.change = change;
        }

        private UUID poolId() {
            return poolId;
        }

        private PoolAllocationEditor.Change change() {
            return change;
        }
    }

    public ResourcePoolScreen(Screen parent, RemotelyClient remotelyClient) {
        this(parent, remotelyClient, null);
    }

    public ResourcePoolScreen(Screen parent, RemotelyClient remotelyClient, HostedModpackSelection modpack) {
        this.parent = parent;
        this.remotelyClient = remotelyClient;
        this.modpack = modpack;
        createRequested = modpack != null;
        enableNavigation(parent);
        TaskScheduler scheduler = remotelyClient != null && remotelyClient.getComposition() != null
                ? remotelyClient.getComposition().scheduler() : TaskScheduler.unavailable();
        controller = new ResourcePoolController(remotelyClient.getApiClient(), scheduler, this::accountId);
        iconManager = new ServerIconManager(host().iconProvider());
    }

    public static void openCreate(Screen parent, RemotelyClient remotelyClient) {
        ResourcePoolScreen resources = new ResourcePoolScreen(parent, remotelyClient);
        resources.createRequested = true;
        ScreenManager.getInstance().replaceScreen(parent, resources);
    }

    private void continueCreation(ResourcePoolController.Snapshot snapshot) {
        if (!createRequested || closed || snapshot.loading() || snapshot.poolPages().loading() || snapshot.generation() == 0 || !snapshot.message().isBlank()) return;
        if (snapshot.pools().isEmpty()) return;
        createRequested = false;
        if (snapshot.pools().size() == 1 && !snapshot.poolPages().hasMore()) {
            create(snapshot.pools().getFirst().pool().id());
            return;
        }
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder(modpack == null ? "Choose A Resource Pool" : "Choose A Modpack Pool")
                .width(430).virtualizeRows(true)
                .onClose(() -> popup[0].hide());
        for (ResourcePoolController.PoolView view : snapshot.pools()) {
            ResourcePoolModels.Pool pool = view.pool();
            ResourcePoolModels.Resources available = pool.balance().available();
            MountableButtonWidget choice = new MountableButtonWidget.Builder(pool.domain().location() + " • " + pool.domain().cpuClass())
                    .description("Available: " + specs(available)).iconPath("pool.png").onClick(() -> {
                        popup[0].hide();
                        if (poolView(pool.id()) != null && snapshot.accountId().equals(controller.snapshot().accountId())) {
                            create(pool.id());
                        } else {
                            new Notification("Resource Pool Changed", "Refresh Resources And Choose A Pool Again", Notification.Type.WARN);
                        }
                    }).build();
            builder.addRow(new PopupWidget.PopupRow.Builder("", choice).minHeight(30).build());
        }
        if (snapshot.poolPages().hasMore()) {
            builder.addTitleAction("Load More", () -> {
                popup[0].hide();
                createRequested = true;
                controller.loadMorePools();
            }, PopupWidget.TitleActionRole.SECONDARY);
        }
        popup[0] = show(builder.build());
    }

    private ServerScreenHost host() {
        return remotelyClient.getHost().serverScreenHost(remotelyClient);
    }

    private String accountId() {
        return host().accountIdentity().authenticated() ? host().hostedNetworkAccount() : "";
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
        observedAccount = accountId();
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
        content = createContainer("resource_pools", 6, 38, width - 12, Math.max(1, height - 44))
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
            continueCreation(snapshot);
        }));
        controller.refresh();
    }

    @Override
    public void tick() {
        if (purchaseFlow != null) purchaseFlow.tick();
        super.tick();
        if (closed) return;
        display(controller.snapshot());
        ServerScreenHost.AccountIdentity identity = host().accountIdentity();
        if (observedAuthenticated != identity.authenticated() || !observedAccount.equals(accountId())) {
            if (purchaseFlow != null) purchaseFlow.close();
            observedAccount = accountId();
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
        List<ResourcePoolModels.Rate> rates = controller.rates();
        if (displayed == snapshot && displayedRates.equals(rates)) return;
        if (purchaseView != null) purchaseView.update(snapshot);
        if (!sameContent(displayed, snapshot) || !displayedRates.equals(rates)) render(snapshot);
        displayed = snapshot;
        displayedRates = rates;
    }

    private record PoolWatch(UUID poolId, String accountId, Consumer<ResourcePoolController.PoolView> listener) {}

    private void render(ResourcePoolController.Snapshot snapshot) {
        if (content == null) {
            return;
        }
        if (!shownAccount.equals(snapshot.accountId())) {
            if (purchaseView != null) {
                purchaseView.dispose();
                purchaseView = null;
            }
            remotelyClient.storageBreakdownIndex().retainAccount(snapshot.accountId());
            editors.clear();
            poolBoards.clear();
            purchaseRows.clear();
            pageButtons.clear();
            summaries.clear();
            settingRows.clear();
            purchaseHistory = null;
            buyResources = null;
            serverRows.clear();
            expandedServerId = "";
            editorAllocations.clear();
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
            poolBoards.keySet().retainAll(editors.keySet());
            poolBars.keySet().retainAll(editors.keySet());
        }
        String nextLayout = layoutStamp(snapshot);
        if (nextLayout.equals(layoutStamp)) {
            refreshExisting(snapshot);
            return;
        }
        nextWidgets.clear();
        refreshExisting(snapshot);
        if ((snapshot.generation() == 0 || snapshot.loading()) && snapshot.pools().isEmpty()) {
            addContent(summary("Loading Resource Pools", "Checking Available Resources And Servers", "calm"));
            finishLayout(nextLayout);
            return;
        }
        if (!snapshot.message().isBlank()) {
            addContent(summary(snapshot.pools().isEmpty() ? "Resource Pools Unavailable" : "Resources Need Attention",
                    snapshot.message(), "danger"));
            if (snapshot.pools().isEmpty()) {
                renderPurchases(snapshot);
                finishLayout(nextLayout);
                return;
            }
        }
        if (!snapshot.loading() && !snapshot.offers().isEmpty() && !controller.rates().isEmpty()) {
            if (buyResources == null) buyResources = new IconButton.Builder().size(rowWidth(), 18).label("Buy Resources").imagePath("Reactor.png")
                    .accentType(ThemeManager.getAccent("danger")).enableGradient(true)
                    .onClick(() -> showOffers(controller.snapshot().offers(), null)).build();
            addContent(buyResources);
        }
        if (snapshot.pools().isEmpty()) {
            addContent(summary("No Resource Pools", "Capacity Appears After Payment And Review", "warning"));
        } else {
            for (ResourcePoolController.PoolView view : snapshot.pools()) renderPool(view);
        }
        renderPageAction("Resource Pools", "Load More Pools", snapshot.poolPages(), controller::loadMorePools);
        renderPurchases(snapshot);
        finishLayout(nextLayout);
    }

    private void addContent(AnimatedWidget widget) {
        widget.setWidth(rowWidth());
        nextWidgets.add(widget);
    }

    private void finishLayout(String stamp) {
        content.replaceWidgets(nextWidgets, true);
        layoutStamp = stamp;
    }

    private String layoutStamp(ResourcePoolController.Snapshot snapshot) {
        StringBuilder stamp = new StringBuilder(snapshot.accountId()).append('|').append(snapshot.message())
                .append('|').append(snapshot.loading()).append('|')
                .append(snapshot.pools().isEmpty() && snapshot.generation() == 0);
        snapshot.offers().forEach(offer -> stamp.append('|').append(offer));
        controller.rates().forEach(rate -> stamp.append('|').append(rate));
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
        for (ResourcePoolModels.PurchaseStatus purchase : snapshot.purchases()) updatePurchase(purchase);
        updateServerLabels();
    }

    private void renderPurchases(ResourcePoolController.Snapshot snapshot) {
        List<PopupWidget.PopupRow> historyRows = new ArrayList<>();
        boolean attention = snapshot.purchases().stream().anyMatch(ResourcePoolScreen::purchaseNeedsAttention);
        if (attention) addContent(summary("Purchases Need Attention", "Complete Checkout Or Review Before Capacity Becomes Available", "warning"));
        for (ResourcePoolModels.PurchaseStatus purchase : snapshot.purchases()) {
            IconButton row = updatePurchase(purchase);
            if (purchaseNeedsAttention(purchase)) addContent(row);
            else historyRows.add(settingRow(row, 30));
        }
        if (purchaseHistory == null) {
            purchaseHistory = new Setting.Builder("Purchase History").build();
            purchaseHistory.collapse(true);
        }
        ResourcePoolController.PageState pages = snapshot.purchasePages();
        if (pages.hasMore() || !pages.message().isBlank()) {
            IconButton more = pageButton("purchase-history", "Load More History", pages, controller::loadMorePurchases);
            historyRows.add(settingRow(more, 24));
        }
        purchaseHistory.setRows(historyRows);
        purchaseHistory.fitContentHeight();
        if (!historyRows.isEmpty()) addContent(purchaseHistory);
    }

    private static boolean purchaseNeedsAttention(ResourcePoolModels.PurchaseStatus purchase) {
        return purchase.status() == ResourcePoolModels.PurchaseState.PENDING
                || purchase.status() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW
                || purchase.status() == ResourcePoolModels.PurchaseState.PAYMENT_FAILED;
    }

    private IconButton updatePurchase(ResourcePoolModels.PurchaseStatus purchase) {
        ResourcePoolModels.Offer offer = offer(controller.snapshot().offers(), purchase.offerId());
        IconButton row = purchaseRows.computeIfAbsent(purchase.purchaseId(), ignored -> new IconButton.Builder()
                .size(rowWidth(), 28).imagePath("Reactor.png").build());
        row.setMessage((offer == null ? "Resource Purchase" : offer.label()) + " • " + title(purchase.status().name()));
        row.setHint(purchaseHint(purchase));
        row.setOnClick(() -> showPurchase(purchase, offer));
        return row;
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
        PoolAllocationEditor editor = editors.computeIfAbsent(view.pool().id(), ignored -> new PoolAllocationEditor());
        editor.accept(view);
        PoolBoard board = poolBoards.computeIfAbsent(view.pool().id(), ignored -> new PoolBoard(editor));
        board.accept(view);
        addContent(board.capacity);
        addContent(board.servers);
        if (!board.inactive.getRows().isEmpty()) addContent(board.inactive);
        if (!controller.snapshot().loading() && !controller.snapshot().offers().isEmpty() && !controller.rates().isEmpty()) {
            addContent(board.expand);
        }
        if (!board.drafts.getRows().isEmpty()) addContent(board.drafts);
        renderPageAction(view.pool().id() + "-drafts", "Server Drafts", "Load More Drafts", view.draftPages(),
                () -> controller.loadMoreDrafts(view.pool().id()));
        renderPageAction(view.pool().id() + "-servers", "Pool Servers", "Load More Servers", view.allocationPages(),
                () -> controller.loadMoreAllocations(view.pool().id()));
    }

    private PopupWidget.PopupRow settingRow(AnimatedWidget widget, int height) {
        return settingRows.computeIfAbsent(widget, ignored -> {
            widget.setHeight(height);
            widget.entranceAnimationEnabled = false;
            return new PopupWidget.PopupRow.Builder("", widget).minHeight(height).build();
        });
    }

    private static Setting serverSetting(String title) {
        Setting.Builder builder = new Setting.Builder(title);
        builder.setExpandWithDropdowns(true);
        return builder.build();
    }

    private final class PoolBoard {
        private final PoolAllocationEditor editor;
        private final Setting capacity = new Setting.Builder("Resource Pool").build();
        private final Setting servers = serverSetting("Servers");
        private final Setting inactive = serverSetting("Disabled Servers");
        private final Setting drafts = new Setting.Builder("Server Drafts").build();
        private final MountableButtonWidget expand;
        private final MountableButtonWidget create;
        private final MountableButtonWidget empty;
        private final List<ResourceAllocationBarWidget> bars;

        private PoolBoard(PoolAllocationEditor editor) {
            this.editor = editor;
            UUID poolId = editor.view().pool().id();
            bars = List.of(new ResourceAllocationBarWidget(editor, PoolAllocationEditor.Resource.RAM, List.of(), ResourcePoolScreen.this::serverName),
                    new ResourceAllocationBarWidget(editor, PoolAllocationEditor.Resource.CPU, List.of(), ResourcePoolScreen.this::serverName),
                    new ResourceAllocationBarWidget(editor, PoolAllocationEditor.Resource.DISK, List.of(), ResourcePoolScreen.this::serverName));
            for (ResourceAllocationBarWidget bar : bars) {
                bar.onHover(hover -> highlightServer(bar, hover));
                bar.onChange(() -> barEdited(editor));
                addAllocationBar(capacity, bar);
            }
            poolBars.put(poolId, bars);
            addSettingRow(capacity, new MountableButtonWidget.Builder("Allocation Key")
                    .description("Hover For Capacity • Hatched: Disabled Disk • *: Stop Or Restart To Free RAM").build());
            capacity.fitContentHeight();
            create = new MountableButtonWidget.Builder(modpack == null ? "Create Server" : "Create Modpack Server")
                    .description(modpack == null ? "Allocate Resources From This Pool" : modpack.name()).onClick(() -> create(poolId)).build();
            empty = new MountableButtonWidget.Builder("No Active Servers").description("Create A Server To Allocate Resources").build();
            expand = ResourcePurchaseFlow.information("Expand Pool", "Choose Your New Resource Capacity", "Reactor.png");
            expand.setCursorHoverReactive(true);
            expand.setOnClick(() -> showOffers(controller.snapshot().offers().stream()
                    .filter(offer -> offer.location().equals(editor.view().pool().domain().location())
                            && offer.cpuClass().equals(editor.view().pool().domain().cpuClass())).toList(), poolId));
            inactive.collapse(true);
        }

        private void accept(ResourcePoolController.PoolView view) {
            List<ResourcePoolModels.Allocation> allocations = orderedAllocations(visibleAllocations(view.allocations()));
            bars.forEach(bar -> bar.setAllocations(allocations));
            List<PopupWidget.PopupRow> activeRows = new ArrayList<>();
            List<PopupWidget.PopupRow> disabledRows = new ArrayList<>();
            for (ResourcePoolModels.Allocation allocation : allocations) {
                MountableButtonWidget row = serverRows.computeIfAbsent(allocation.serverId(), ignored -> new MountableButtonWidget.Builder(serverName(allocation.serverId()))
                        .onClick(() -> selectServer(editor, allocation.serverId())).build());
                row.setName(serverName(allocation.serverId()));
                row.setDescription(serverSummary(editor, allocation));
                row.setIcon(serverIcons.getOrDefault(allocation.serverId(), Identifier.icon("server.png")));
                row.setHint(cleanupPending(allocation) ? "Backups Awaiting Cleanup" : "Adjust " + serverName(allocation.serverId()) + " Resources");
                if (allocation.serverId().equals(expandedServerId) && editor.selected() != null
                        && editor.selected().serverId().equals(allocation.serverId()) && !row.hasEmbeddedBody()) {
                    setServerExpanded(editor, allocation.serverId(), true);
                }
                (allocation.state() == ResourcePoolModels.AllocationState.DISABLED ? disabledRows : activeRows).add(settingRow(row, 30));
            }
            if (activeRows.isEmpty()) activeRows.add(settingRow(empty, 30));
            activeRows.add(settingRow(create, 30));
            servers.setRows(servers.getRows().stream().filter(activeRows::contains).toList());
            inactive.setRows(inactive.getRows().stream().filter(disabledRows::contains).toList());
            servers.setRows(activeRows);
            servers.fitContentHeight();
            inactive.setTitle("Disabled Servers (" + disabledRows.size() + ")");
            inactive.setRows(disabledRows);
            inactive.fitContentHeight();
            List<PopupWidget.PopupRow> pendingRows = new ArrayList<>();
            for (ResourcePoolModels.Draft draft : view.drafts()) {
                if (draft.state() == ResourcePoolModels.DraftState.ACTIVE) continue;
                IconButton row = draftRows.computeIfAbsent(draft.id(), ignored -> draftRow(draft));
                row.setMessage(draft.metadata().name() + " • " + (draft.state() == ResourcePoolModels.DraftState.UNKNOWN ? "Activation Needs Review" : title(draft.state().name())));
                row.setHint(draftHint(draft));
                row.setOnClick(draft.state() == ResourcePoolModels.DraftState.DRAFT ? () -> showDraftActivation(draft)
                        : draft.state() == ResourcePoolModels.DraftState.UNKNOWN ? () -> showDraftReview(draft) : null);
                pendingRows.add(settingRow(row, 30));
            }
            drafts.setRows(pendingRows);
            drafts.fitContentHeight();
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

    private static boolean cleanupPending(ResourcePoolModels.Allocation allocation) {
        return allocation.state() == ResourcePoolModels.AllocationState.DISABLED
                && PoolAllocationEditor.number(allocation.retained().diskMiB()).signum() == 0
                && PoolAllocationEditor.number(allocation.retained().backupMiB()).signum() > 0;
    }

    private List<AnimatedWidget> editorRows(PoolAllocationEditor editor, Runnable refreshBars) {
        ResourcePoolModels.Allocation selected = editor.selected();
        if (selected == null) return List.of();
        if (cleanupPending(selected)) {
            return List.of(new MountableButtonWidget.Builder("Backups Awaiting Cleanup")
                    .description("Retained Backups Remain Visible Until Cleanup Is Confirmed").build());
        }
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
        if (disable != null) disable.setActive(editor.selected() != null && editor.selected().state() == ResourcePoolModels.AllocationState.ACTIVE
                && !editor.locked() && !editor.busy() && !editor.submitted() && !editor.changed());
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
        if (editor.allocation(serverId) == null) return;
        boolean expanded = !serverId.equals(expandedServerId);
        MountableButtonWidget previous = serverRows.get(expandedServerId);
        if (previous != null && expanded) previous.setEmbeddedBody(null, false);
        editor.select(serverId);
        expandedServerId = expanded ? serverId : "";
        setServerExpanded(editor, serverId, expanded);
    }

    private void setServerExpanded(PoolAllocationEditor editor, String serverId, boolean expanded) {
        MountableButtonWidget row = serverRows.get(serverId);
        if (row == null) return;
        if (expanded) {
            ResourcePoolModels.Allocation allocation = editor.allocation(serverId);
            ResourcePoolModels.Allocation previous = editorAllocations.get(serverId);
            if (allocation == null) return;
            if (!row.hasEmbeddedBody() || previous == null || previous.state() != allocation.state()
                    || cleanupPending(previous) != cleanupPending(allocation)) {
                editorSync.remove(serverId);
                editorValidity.remove(serverId);
                row.setEmbeddedBody(editorRows(editor, () -> refreshBars(editor.view().pool().id())), true);
            } else {
                row.setEmbeddedBody(null, true);
                if (previous != null && !previous.equals(allocation)) syncEditor(serverId);
            }
            editorAllocations.put(serverId, allocation);
        } else {
            row.setEmbeddedBody(null, false);
        }
    }

    void refreshBars(UUID poolId) {
        poolBars.getOrDefault(poolId, List.of()).forEach(ResourceAllocationBarWidget::refresh);
    }

    private void updateAllocationRows(PoolAllocationEditor editor) {
        for (ResourcePoolModels.Allocation allocation : editor.view().allocations()) {
            MountableButtonWidget row = serverRows.get(allocation.serverId());
            if (row != null) {
                row.setDescription(serverSummary(editor, allocation));
                ResourcePoolModels.Allocation previous = editorAllocations.get(allocation.serverId());
                if (allocation.serverId().equals(expandedServerId) && previous != null && row.hasEmbeddedBody()
                        && (previous.state() != allocation.state() || cleanupPending(previous) != cleanupPending(allocation))) {
                    setServerExpanded(editor, allocation.serverId(), true);
                }
            }
        }
        updateChangeActions();
    }

    private static String serverSummary(PoolAllocationEditor editor, ResourcePoolModels.Allocation allocation) {
        String id = allocation.serverId();
        if (cleanupPending(allocation)) return "Backups Awaiting Cleanup  •  RAM And CPU Released";
        if (allocation.state() == ResourcePoolModels.AllocationState.DISABLED && !editor.changed(id)) {
            BigInteger disk = PoolAllocationEditor.number(allocation.retained().diskMiB());
            BigInteger backup = PoolAllocationEditor.number(allocation.retained().backupMiB());
            String retained = disk.signum() == 0 ? "No Disk Retained" : disk + " MiB Disk Retained";
            return "Disabled  •  RAM And CPU Released  •  " + retained
                    + (backup.signum() == 0 ? "" : "  •  Backups Retained Within Disk");
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
                row.setHint(cleanupPending(allocation) ? "Backups Awaiting Cleanup" : "Adjust " + name + " Resources");
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
        renderPageAction(name, name, label, pages, action);
    }

    private void renderPageAction(String key, String name, String label, ResourcePoolController.PageState pages, Runnable action) {
        if (!pages.message().isBlank()) addContent(summary(name + " Need Attention", pages.message(), "danger"));
        if (pages.hasMore()) addContent(pageButton(key, label, pages, action));
    }

    private IconButton pageButton(String key, String label, ResourcePoolController.PageState pages, Runnable action) {
        IconButton button = pageButtons.computeIfAbsent(key, ignored -> new IconButton.Builder().size(rowWidth(), 24)
                .imagePath("down.png").accentType(ThemeManager.getAccent("calm")).build());
        button.setMessage(pages.loading() ? "Loading" : pages.message().isBlank() ? label : "Retry Loading");
        button.setHint(pages.message().isBlank() ? label : pages.message());
        button.setActive(!pages.loading());
        button.setOnClick(action);
        return button;
    }

    private static String specs(ResourcePoolModels.Resources resources) {
        return ResourcePurchaseFlow.specs(resources);
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

    private void showOffers(List<ResourcePoolModels.Offer> offers, UUID poolId) {
        if (purchaseFlow != null) purchaseFlow.close();
        try {
            purchaseFlow = new ResourcePurchaseFlow(controller, offers, poolId, width, height,
                    action -> host().application().execute(action), () -> closed, this::submitCheckout);
            show(purchaseFlow.popup());
        } catch (RuntimeException failure) {
            new Notification("Resource Store Unavailable", ResourcePoolController.message(failure), Notification.Type.WARN);
        }
    }

    private static String money(String currency, String cents) {
        return ("USD".equals(currency) ? "$" : currency + " ") + new BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString();
    }

    private void submitCheckout(ResourcePoolController.PurchaseIntent intent, ResourcePoolModels.Offer offer,
                                ResourcePoolModels.Quote quote) {
        String checkoutAccount = accountId();
        Notification notice = operationNotice("Creating Checkout", offer.label());
        controller.checkout(intent).whenComplete((purchase, failure) -> host().application().execute(() -> {
            if (!closed && checkoutAccount.equals(accountId()) && checkoutAccount.equals(controller.snapshot().accountId())) {
                finishCheckout(notice, purchase, failure, offer, quote);
            } else if (!closed) {
                notice.update().message("Account Changed").description("Open Resources In The Original Account To View Checkout")
                        .type(Notification.Type.INFO).loading(false).autoSlideOut(true).commit();
            } else {
                notice.update().message(failure == null ? "Checkout Updated" : "Checkout Needs Attention")
                        .description(failure == null ? "Open Resources To View Your Purchase" : ResourcePoolController.message(failure))
                        .type(failure == null ? Notification.Type.INFO : Notification.Type.ERROR)
                        .loading(false).autoSlideOut(true).commit();
            }
        }));
    }

    private void finishCheckout(Notification notice, ResourcePoolController.PurchaseResult purchase, Throwable failure,
                                ResourcePoolModels.Offer offer, ResourcePoolModels.Quote quote) {
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
                || purchase.state() == ResourcePoolModels.PurchaseState.PAYMENT_FAILED)) {
            showCheckoutLink(purchase.checkoutUrl(), offer, title(purchase.state().name()), quote);
        }
        controller.refresh();
    }

    private void showPurchase(ResourcePoolModels.PurchaseStatus purchase, ResourcePoolModels.Offer ignored) {
        ResourcePoolModels.PurchaseStatus latest = controller.snapshot().purchases().stream()
                .filter(value -> value.purchaseId().equals(purchase.purchaseId())).findFirst().orElse(null);
        if (latest == null || !accountId().equals(controller.snapshot().accountId())) return;
        if (purchaseView == null || !purchaseView.id.equals(latest.purchaseId()) || !purchaseView.account.equals(accountId())) {
            if (purchaseView != null) purchaseView.dispose();
            purchaseView = new PurchaseView(latest);
        }
        purchaseView.update(controller.snapshot());
        purchaseView.popup.centerOnOwner();
        purchaseView.popup.show();
    }

    private final class PurchaseView {
        private final UUID id;
        private final String account;
        private final PopupWidget.Builder builder;
        private PopupWidget popup;
        private final MountableButtonWidget status;
        private final AnimatedButton refresh;
        private final AnimatedButton manage;
        private final AnimatedButton retry;
        private final AnimatedButton recover;
        private final AnimatedButton release;
        private boolean busy;
        private boolean disposed;
        private ResourcePoolModels.PurchaseStatus purchase;
        private String link = "";

        private PurchaseView(ResourcePoolModels.PurchaseStatus purchase) {
            this.purchase = purchase;
            id = purchase.purchaseId();
            account = accountId();
            builder = new PopupWidget.Builder("Resource Purchase").size(Math.min(460, Math.max(1, width - 24)),
                            Math.min(320, Math.max(1, height - 40))).setMinSize(1, 1).padding(6).rowGap(5);
            status = ResourcePurchaseFlow.information("Resource Purchase", purchaseHint(purchase), "Reactor.png");
            builder.addRow("", status);
            builder.addRow("Reference", new TextInputWidget.Builder().text(id.toString()).size(250, 20).build());
            refresh = new AnimatedButton.Builder().label("Refresh Status").onClick(() -> {
                if (current()) controller.refresh();
            }).build();
            builder.addRow("", refresh);
            manage = new AnimatedButton.Builder().label("Manage Pool").onClick(() -> {
                if (!current()) return;
                PoolBoard board = poolBoards.get(this.purchase.poolId());
                if (board == null) return;
                popup.hide();
                content.scrollToWidget(board.capacity);
            }).build();
            builder.addRow("purchase-manage", "", manage);
            retry = new AnimatedButton.Builder().label("Review New Purchase").onClick(() -> {
                if (!current() || !retryAvailable()) return;
                popup.hide();
                showOffers(controller.snapshot().offers(), poolView(this.purchase.poolId()) == null ? null : this.purchase.poolId());
            }).build();
            builder.addRow("purchase-retry", "", retry);
            recover = new AnimatedButton.Builder().label("Recover Checkout").onClick(() -> {
                if (current() && recoveryAvailable() && !busy) perform(controller.recoverPurchase(this.purchase), false);
            }).build();
            builder.addRow("purchase-recover", "", recover);
            release = new AnimatedButton.Builder().label("Release Unpaid Reservation").onClick(this::confirmRelease).build();
            builder.addRow("purchase-release", "", release);
            builder.addRow("", new AnimatedButton.Builder().label("Contact Support").onClick(() -> {
                if (!current()) return;
                popup.hide();
                CreateFeedbackPopup support = new CreateFeedbackPopup("Reactor", true, List.of(), post -> {});
                addDrawableChild(support);
                support.centerOnOwner();
            }).build());
            popup = builder.build();
            addDrawableChild(popup);
        }

        private boolean current() {
            return !disposed && !closed && !account.isBlank() && account.equals(accountId()) && account.equals(controller.snapshot().accountId());
        }

        private boolean retryAvailable() {
            return offer(controller.snapshot().offers(), purchase.offerId()) != null
                    && (purchase.status() == ResourcePoolModels.PurchaseState.EXPIRED || purchase.status() == ResourcePoolModels.PurchaseState.CANCELLED);
        }

        private void update(ResourcePoolController.Snapshot snapshot) {
            if (!current()) {
                popup.hide();
                return;
            }
            ResourcePoolModels.PurchaseStatus latest = snapshot.purchases().stream().filter(value -> value.purchaseId().equals(id)).findFirst().orElse(null);
            if (latest == null) return;
            purchase = latest;
            ResourcePoolModels.Offer offer = offer(snapshot.offers(), purchase.offerId());
            status.setName((offer == null ? "Resource Purchase" : offer.label()) + " • " + title(purchase.status().name()));
            status.setDescription(purchaseHint(purchase));
            refresh.active = !snapshot.loading() && !busy;
            recover.active = release.active = !busy;
            popup.setTitleBadge(busy ? "Updating Purchase" : "");
            popup.setRowVisibility("purchase-recover", recoveryAvailable());
            popup.setRowVisibility("purchase-release", recoveryAvailable());
            refresh.setMessage(snapshot.loading() ? "Checking Status" : "Refresh Status");
            popup.setRowVisibility("purchase-manage", poolBoards.containsKey(purchase.poolId()));
            popup.setRowVisibility("purchase-retry", retryAvailable());
            String url = text(purchase.checkoutUrl(), "");
            if (!url.equals(link)) {
                popup.removeRow("external-status");
                popup.removeRow("external-url");
                popup.removeRow("external-actions");
                link = url;
                if (!url.isBlank()) ExternalLinkActions.add(builder, url, () -> current() && url.equals(purchase.checkoutUrl()) && checkoutAvailable(),
                        host()::openExternal, "Open Secure Checkout");
            }
            boolean checkout = checkoutAvailable();
            popup.setRowVisibility("external-status", checkout);
            popup.setRowVisibility("external-url", checkout);
            popup.setRowVisibility("external-actions", checkout);
        }

        private boolean checkoutAvailable() {
            return purchase.status() == ResourcePoolModels.PurchaseState.PENDING || purchase.status() == ResourcePoolModels.PurchaseState.PAYMENT_FAILED;
        }

        private boolean recoveryAvailable() {
            return purchase.status() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW && text(purchase.checkoutUrl(), "").isBlank();
        }

        private void confirmRelease() {
            if (!current() || !recoveryAvailable() || busy) return;
            PopupWidget[] confirmation = new PopupWidget[1];
            PopupWidget.Builder dialog = new PopupWidget.Builder("Release Unpaid Reservation").width(Math.min(380, Math.max(1, width - 24)))
                    .setMinSize(1, 1).setResizable(false);
            dialog.addMarkdown("", "Only Unpaid Held Capacity Is Released. This Does Not Refund Or Cancel A Payment. The Purchase May Still Need Support Review.");
            dialog.addTitleAction("Release Reservation", () -> {
                confirmation[0].hide();
                if (current() && recoveryAvailable() && !busy) perform(controller.releasePurchase(purchase), true);
            }, PopupWidget.TitleActionRole.DESTRUCTIVE);
            confirmation[0] = show(dialog.build());
        }

        private void perform(Async<ResourcePoolModels.PurchaseStatus> operation, boolean released) {
            busy = true;
            update(controller.snapshot());
            operation.whenComplete((updated, failure) -> host().application().execute(() -> {
                if (!current()) return;
                busy = false;
                update(controller.snapshot());
                if (failure != null) new Notification("Purchase Needs Attention", ResourcePoolController.message(failure), Notification.Type.ERROR);
                else new Notification("Purchase Updated", released ? "Unpaid Reservation Released. Check Review Status"
                        : "Checkout Recovery Checked. Review Your Purchase Status", Notification.Type.INFO);
            }));
        }

        private void dispose() {
            disposed = true;
            popup.hide();
            remove(popup);
        }
    }

    private void showCheckoutLink(String url, ResourcePoolModels.Offer offer, String state, ResourcePoolModels.Quote quote) {
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Secure Checkout").size(Math.min(480, Math.max(1, width - 24)), Math.min(300, Math.max(1, height - 40)))
                .setMinSize(1, 1).padding(8).rowGap(6)
                .onClose(() -> popup[0].hide());
        String value = quote == null ? "Continue Your Purchase" : money(quote.price().currency(), quote.dueCents());
        builder.addRow(new PopupWidget.PopupRow.Builder("", ResourcePurchaseFlow.information(
                "Whop Checkout • " + value, state, "Reactor.png")).build());
        if (quote != null) builder.addRow(new PopupWidget.PopupRow.Builder("", ResourcePurchaseFlow.information(
                "Resources", ResourcePurchaseFlow.specs(quote.price().resources()), "pool.png")).build());
        String checkoutAccount = accountId();
        ExternalLinkActions.add(builder, url, () -> !closed && checkoutAccount.equals(accountId())
                        && checkoutAccount.equals(controller.snapshot().accountId()), host()::openExternal, "Open Secure Checkout");
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
        for (PendingChange pending : pendingChanges) {
            PoolAllocationEditor editor = editors.get(pending.poolId());
            if (editor != null && editor.unknown(pending.change(), pending.requestId)) {
                stopChanges("Resource Change Could Not Be Confirmed. Refresh To Review Its Status", Notification.Type.WARN);
                return;
            }
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
                    && !editor.pending(pending.change(), pending.requestId)
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
            if (editor.pending(pending.change(), pending.requestId)) continue;
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
        if (pendingChanges.stream().anyMatch(pending -> editors.get(pending.poolId()).hasPending()
                || editors.get(pending.poolId()).pending(pending.change(), pending.requestId))) return;
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
            controller.assign(allocation, change.ram().toString(), change.cpu().toString(), change.disk().toString(),
                            requestId -> pending.requestId = requestId)
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
                showOffers(controller.snapshot().offers(), null);
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
                .description("Server Files And Backups Share This Limit").build());
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
        TextInputWidget confirmation = new TextInputWidget.Builder().placeholder(name).size(300, 20).build();
        IconButton entry = new IconButton.Builder().label(name)
                .identifier(serverIcons.getOrDefault(serverId, Identifier.icon("server.png")))
                .iconSize(24).size(0, 30).build();
        entry.setActive(false);
        DeletionPopup.show(this, List.of(entry,
                DeletionPopup.entry("All Server Files And Worlds Will Be Deleted", "explorer.png"),
                DeletionPopup.entry("Enter The Server Name To Confirm", "edit.png"), confirmation),
                DeletionPopup.Action.permanent(popup -> {
                    if (closed || !accountId.equals(controller.snapshot().accountId()) || !accountId.equals(accountId())) return;
                    if (!name.equals(confirmation.getText())) {
                        new Notification("Name Does Not Match", "Enter The Server Name Exactly To Delete It", Notification.Type.WARN);
                        return;
                    }
                    if (!pendingChanges.isEmpty() || editors.values().stream().anyMatch(PoolAllocationEditor::hasChanges)
                            || deletingServers.contains(serverId)) {
                        new Notification("Apply Resource Changes First", "Finish Or Discard Pending Changes Before Deleting A Server", Notification.Type.WARN);
                        return;
                    }
                    popup.hide();
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
                }));
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
        AnimatedButton button = summaries.computeIfAbsent(label, ignored -> new AnimatedButton.Builder().size(rowWidth(), 22)
                .label(label).accentType(ThemeManager.getAccent(accent)).build());
        button.setHint(hint);
        return button;
    }

    private int rowWidth() {
        return Math.max(1, width - 24);
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
        if (purchaseFlow != null) purchaseFlow.close();
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
        if (purchaseFlow != null) purchaseFlow.close();
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
        private final BigInteger[] capacity = new BigInteger[3];
        private final BigInteger[] selected = new BigInteger[5];
        private final DoubleSliderWidget[] sliders = new DoubleSliderWidget[5];

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
            selected[0] = initial(draft.installer() == null ? null : draft.installer().ramMiB(), capacity[0], 2048).max(installerRam).min(capacity[0]);
            selected[1] = initial(draft.installer() == null ? null : draft.installer().cpuQuotaPercent(), capacity[1], 200).max(installerCpu).min(capacity[1]);
            selected[2] = initial(draft.runtime() == null ? null : draft.runtime().ramMiB(), capacity[0], 2048);
            selected[3] = initial(draft.runtime() == null ? null : draft.runtime().cpuQuotaPercent(), capacity[1], 200);
            selected[4] = initial(storage == null ? null : storage.diskMiB(), capacity[2], 10240);
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
                default -> throw new IllegalArgumentException("Unknown Server Resource");
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
            if (index == 4 && value.mod(BigInteger.valueOf(1024)).signum() == 0) {
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
                    selected[4].toString(), "0");
        }
    }
}
