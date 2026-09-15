package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyServerApi;
import restudio.rebase.resource.ResourcePoolClient;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ResourcePoolController {
    private static final int PAGE_SIZE = 25;
    private static final int MAX_POLLS = 120;

    public record PageState(int nextPage, boolean hasMore, boolean loading, String message) {
        public PageState {
            if (nextPage < 1) {
                throw new IllegalArgumentException("Next Resource Page Is Invalid");
            }
            message = message == null ? "" : message;
        }

        public static PageState first(boolean hasMore) {
            return new PageState(1, hasMore, false, "");
        }

        public static PageState empty() {
            return first(false);
        }

        public PageState start() {
            return new PageState(nextPage, hasMore, true, "");
        }

        public PageState fail(Throwable failure) {
            return new PageState(nextPage, hasMore, false, ResourcePoolController.message(failure));
        }

        public PageState advance(boolean more) {
            return new PageState(nextPage + 1, more, false, "");
        }

        public PageState stop() {
            return loading ? new PageState(nextPage, hasMore, false, message) : this;
        }
    }

    public record PoolView(ResourcePoolModels.Pool pool, List<ResourcePoolModels.Draft> drafts,
                           List<ResourcePoolModels.Allocation> allocations, Map<UUID, ResourcePoolModels.Progress> progress,
                           PageState draftPages, PageState allocationPages) {
        public PoolView {
            Objects.requireNonNull(pool, "pool");
            drafts = List.copyOf(drafts == null ? List.of() : drafts);
            allocations = List.copyOf(allocations == null ? List.of() : allocations);
            progress = Map.copyOf(progress == null ? Map.of() : progress);
            draftPages = draftPages == null ? PageState.empty() : draftPages;
            allocationPages = allocationPages == null ? PageState.empty() : allocationPages;
        }

        public PoolView(ResourcePoolModels.Pool pool, List<ResourcePoolModels.Draft> drafts,
                        List<ResourcePoolModels.Allocation> allocations, Map<UUID, ResourcePoolModels.Progress> progress) {
            this(pool, drafts, allocations, progress, PageState.empty(), PageState.empty());
        }
    }

    public record Snapshot(String accountId, List<PoolView> pools, List<ResourcePoolModels.Offer> offers,
                           List<ResourcePoolModels.PurchaseStatus> purchases, PageState poolPages,
                           PageState purchasePages, boolean loading, String message, long generation) {
        public Snapshot {
            accountId = accountId == null ? "" : accountId;
            pools = List.copyOf(pools == null ? List.of() : pools);
            offers = List.copyOf(offers == null ? List.of() : offers);
            purchases = List.copyOf(purchases == null ? List.of() : purchases);
            poolPages = poolPages == null ? PageState.empty() : poolPages;
            purchasePages = purchasePages == null ? PageState.empty() : purchasePages;
            message = message == null ? "" : message;
        }

        public Snapshot(String accountId, List<PoolView> pools, List<ResourcePoolModels.Offer> offers,
                        List<ResourcePoolModels.PurchaseStatus> purchases, boolean loading, String message,
                        long generation) {
            this(accountId, pools, offers, purchases, PageState.empty(), PageState.empty(), loading, message, generation);
        }

        public Snapshot(String accountId, List<PoolView> pools, boolean loading, String message, long generation) {
            this(accountId, pools, List.of(), List.of(), PageState.empty(), PageState.empty(), loading, message, generation);
        }

        public static Snapshot empty() {
            return new Snapshot("", List.of(), List.of(), List.of(), PageState.empty(), PageState.empty(), false, "", 0);
        }

        public boolean pending() {
            if (purchases.stream().anyMatch(purchase -> purchase.status() == ResourcePoolModels.PurchaseState.PENDING
                    || purchase.status() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW)) {
                return true;
            }
            for (PoolView view : pools) {
                if (view.drafts().stream().anyMatch(draft -> draft.state() == ResourcePoolModels.DraftState.ACTIVATING)) {
                    return true;
                }
                if (view.allocations().stream().anyMatch(allocation -> allocation.state() == ResourcePoolModels.AllocationState.PENDING
                        || allocation.state() == ResourcePoolModels.AllocationState.DISABLE_PENDING)) {
                    return true;
                }
                if (view.progress().values().stream().anyMatch(progress -> progress.operation().state() == ResourcePoolModels.OperationState.PENDING)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record PurchaseIntent(UUID purchaseRequestId, String offerId) {
        public PurchaseIntent {
            Objects.requireNonNull(purchaseRequestId, "purchaseRequestId");
            if (offerId == null || offerId.isBlank()) {
                throw new IllegalArgumentException("Offer Identity Is Required");
            }
        }
    }

    public record PurchaseResult(UUID purchaseRequestId, String offerId, UUID poolId,
                                 ResourcePoolModels.PurchaseState state, String checkoutUrl,
                                 String reviewReason, String failureReason) {
        public PurchaseResult {
            Objects.requireNonNull(purchaseRequestId, "purchaseRequestId");
            if (offerId == null || offerId.isBlank()) {
                throw new IllegalArgumentException("Offer Identity Is Required");
            }
            Objects.requireNonNull(poolId, "poolId");
            Objects.requireNonNull(state, "state");
        }

        private static PurchaseResult from(ResourcePoolModels.Checkout checkout) {
            return new PurchaseResult(checkout.purchaseRequestId(), checkout.offerId(), checkout.poolId(),
                    checkout.status(), checkout.url(), null, null);
        }

        private static PurchaseResult from(ResourcePoolModels.PurchaseStatus purchase) {
            return new PurchaseResult(purchase.purchaseRequestId(), purchase.offerId(), purchase.poolId(),
                    purchase.status(), purchase.checkoutUrl(), purchase.reviewReason(), purchase.failureReason());
        }
    }

    public record Creation(UUID poolId, UUID draftId, UUID createRequestId, UUID activationRequestId) {
        public Creation {
            Objects.requireNonNull(poolId, "poolId");
            Objects.requireNonNull(draftId, "draftId");
            Objects.requireNonNull(createRequestId, "createRequestId");
            Objects.requireNonNull(activationRequestId, "activationRequestId");
        }
    }

    private enum MutationKind {
        ASSIGN,
        DISABLE
    }

    private static final class PendingMutation {
        private final UUID requestId = UUID.randomUUID();
        private final MutationKind kind;
        private final ResourcePoolModels.Allocation allocation;
        private final String accountId;
        private final String ramMiB;
        private final String cpuPercent;
        private ResourcePoolModels.Allocation authority;

        private PendingMutation(MutationKind kind, ResourcePoolModels.Allocation allocation, String accountId,
                                String ramMiB, String cpuPercent) {
            this.kind = kind;
            this.allocation = allocation;
            this.accountId = accountId;
            this.ramMiB = ramMiB;
            this.cpuPercent = cpuPercent;
        }
    }

    private static final class LoadedPage<T> {
        private ResourcePoolModels.Page<T> value;
    }

    private static final class LoadedRefresh {
        private List<PoolView> pools;
        private List<ResourcePoolModels.Offer> offers;
        private ResourcePoolModels.Page<ResourcePoolModels.PurchaseStatus> purchases;
        private PageState poolPages;
        private PageState purchasePages;
    }

    private record LoadedPoolPage(ResourcePoolModels.Page<ResourcePoolModels.Pool> page, List<PoolView> pools) {
    }

    private record LoadedAllocationPage(ResourcePoolModels.Page<ResourcePoolModels.Allocation> page,
                                        Map<UUID, ResourcePoolModels.Progress> progress) {
    }

    private static final class PendingPurchase {
        private final PurchaseIntent intent;
        private final String accountId;
        private final Async<PurchaseResult> result = Async.pending();
        private Async<?> request;
        private boolean submitted;

        private PendingPurchase(PurchaseIntent intent, String accountId) {
            this.intent = intent;
            this.accountId = accountId;
        }
    }

    private final ResourcePoolClient api;
    private final TaskScheduler scheduler;
    private final Supplier<String> account;
    private Consumer<Snapshot> listener = ignored -> {};
    private Snapshot snapshot = Snapshot.empty();
    private TaskScheduler.ScheduledTask pollTask;
    private Async<?> read;
    private final Map<String, Async<?>> pageReads = new LinkedHashMap<>();
    private final Map<String, PendingMutation> mutations = new LinkedHashMap<>();
    private final Map<UUID, UUID> activationIds = new LinkedHashMap<>();
    private final Map<UUID, ResourcePoolModels.Offer> purchaseOffers = new LinkedHashMap<>();
    private final Map<UUID, PendingPurchase> purchaseRequests = new LinkedHashMap<>();
    private long generation;
    private int polls;
    private boolean refreshQueued;
    private boolean disposed;

    public ResourcePoolController(RemotelyServerApi serverApi, TaskScheduler scheduler, Supplier<String> account) {
        this(Objects.requireNonNull(serverApi, "serverApi").resourcePools(), scheduler, account);
    }

    ResourcePoolController(ResourcePoolClient api, TaskScheduler scheduler, Supplier<String> account) {
        this.api = Objects.requireNonNull(api, "api");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.account = Objects.requireNonNull(account, "account");
    }

    public void listen(Consumer<Snapshot> listener) {
        this.listener = listener == null ? ignored -> {} : listener;
        this.listener.accept(snapshot);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public Creation begin(UUID poolId) {
        return new Creation(poolId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    public Creation resume(ResourcePoolModels.Draft draft) {
        Objects.requireNonNull(draft, "draft");
        UUID activation = draft.activationRequestId() == null
                ? activationIds.computeIfAbsent(draft.id(), ignored -> UUID.randomUUID()) : draft.activationRequestId();
        return new Creation(draft.poolId(), draft.id(), UUID.randomUUID(), activation);
    }

    public void refresh() {
        polls = 0;
        refresh(true);
    }

    private void refresh(boolean visibleLoading) {
        if (disposed) {
            return;
        }
        if (!pageReads.isEmpty()) {
            if (!visibleLoading) {
                refreshQueued = true;
                return;
            }
            cancelPageReads();
            clearPageLoading();
        }
        String accountId = currentAccount();
        if (read != null && !read.isDone()) {
            if (accountId.equals(snapshot.accountId())) {
                if (!visibleLoading) {
                    refreshQueued = true;
                }
                return;
            }
            Async<?> previous = read;
            read = null;
            refreshQueued = false;
            previous.cancel();
        }
        cancelPoll();
        long ticket = ++generation;
        boolean sameAccount = accountId.equals(snapshot.accountId());
        boolean resetPages = visibleLoading || !sameAccount;
        if (!sameAccount) {
            mutations.clear();
            activationIds.clear();
            purchaseOffers.clear();
            purchaseRequests.values().forEach(ResourcePoolController::cancelPurchase);
            purchaseRequests.clear();
        }
        if (accountId.isBlank()) {
            publish(new Snapshot("", List.of(), List.of(), List.of(), PageState.empty(), PageState.empty(), false,
                    "Sign In To View Resources", ticket));
            return;
        }
        if (!sameAccount) {
            publish(new Snapshot(accountId, List.of(), List.of(), List.of(), PageState.empty(), PageState.empty(), true,
                    "", ticket));
        } else if (visibleLoading) {
            publish(new Snapshot(accountId, snapshot.pools(), snapshot.offers(), snapshot.purchases(), snapshot.poolPages(),
                    snapshot.purchasePages(), true, "", ticket));
        }
        Async<LoadedRefresh> request;
        try {
            LoadedRefresh loaded = new LoadedRefresh();
            Async<List<PoolView>> pools = timed(api.listPools(0, PAGE_SIZE)).thenCompose(page -> loadPools(page.items(), 0, new ArrayList<>())
                    .thenApply(views -> {
                        loaded.poolPages = PageState.first(page.hasMore());
                        return views;
                    }));
            Async<List<ResourcePoolModels.Offer>> offers = timed(api.getPurchaseOffers())
                    .thenApply(value -> loaded.offers = List.copyOf(value));
            Async<ResourcePoolModels.Page<ResourcePoolModels.PurchaseStatus>> purchases = timed(api.listPurchases(0, PAGE_SIZE))
                    .thenApply(value -> {
                        loaded.purchasePages = PageState.first(value.hasMore());
                        return loaded.purchases = value;
                    });
            request = Async.allOf(pools.thenApply(value -> loaded.pools = value), offers, purchases)
                    .thenApply(ignored -> loaded);
        } catch (RuntimeException failure) {
            finishRefresh(null, ticket, accountId, null, failure, resetPages);
            return;
        }
        read = request;
        request.whenComplete((loaded, failure) -> finishRefresh(request, ticket, accountId, loaded, failure, resetPages));
    }

    private Async<List<PoolView>> loadPools(List<ResourcePoolModels.Pool> pools, int index, List<PoolView> loaded) {
        if (index >= pools.size()) {
            return Async.completed(List.copyOf(loaded));
        }
        ResourcePoolModels.Pool pool = pools.get(index);
        LoadedPage<ResourcePoolModels.Draft> drafts = new LoadedPage<>();
        LoadedPage<ResourcePoolModels.Allocation> allocations = new LoadedPage<>();
        Async<ResourcePoolModels.Page<ResourcePoolModels.Draft>> draftRead = timed(api.listDrafts(pool.id(), 0, PAGE_SIZE))
                .thenApply(value -> drafts.value = value);
        Async<ResourcePoolModels.Page<ResourcePoolModels.Allocation>> allocationRead = timed(api.listAllocations(pool.id(), 0, PAGE_SIZE))
                .thenApply(value -> allocations.value = value);
        return Async.allOf(draftRead, allocationRead).thenCompose(ignored -> {
            return loadProgress(pool.id(), allocations.value.items(), 0, new LinkedHashMap<>()).thenCompose(progress -> {
                loaded.add(new PoolView(pool, drafts.value.items(), allocations.value.items(), progress,
                        PageState.first(drafts.value.hasMore()), PageState.first(allocations.value.hasMore())));
                return loadPools(pools, index + 1, loaded);
            });
        });
    }

    private Async<Map<UUID, ResourcePoolModels.Progress>> loadProgress(UUID poolId,
                                                                       List<ResourcePoolModels.Allocation> allocations,
                                                                       int index,
                                                                       Map<UUID, ResourcePoolModels.Progress> loaded) {
        if (index >= allocations.size()) {
            return loadMutationProgress(poolId, new ArrayList<>(mutations.values()), 0, loaded);
        }
        ResourcePoolModels.Allocation allocation = allocations.get(index);
        UUID requestId = allocation.currentRequestId();
        if (requestId == null) {
            return loadProgress(poolId, allocations, index + 1, loaded);
        }
        return timed(api.getProgress(poolId, requestId)).thenCompose(progress -> {
            loaded.put(requestId, progress);
            return loadProgress(poolId, allocations, index + 1, loaded);
        });
    }

    private Async<Map<UUID, ResourcePoolModels.Progress>> loadMutationProgress(UUID poolId,
                                                                               List<PendingMutation> pending,
                                                                               int index,
                                                                               Map<UUID, ResourcePoolModels.Progress> loaded) {
        if (index >= pending.size()) {
            return Async.completed(Map.copyOf(loaded));
        }
        PendingMutation mutation = pending.get(index);
        if (!poolId.equals(mutation.allocation.poolId()) || loaded.containsKey(mutation.requestId)) {
            return loadMutationProgress(poolId, pending, index + 1, loaded);
        }
        return timed(api.getProgress(poolId, mutation.requestId)).thenCompose(progress -> {
            loaded.put(mutation.requestId, progress);
            return loadMutationProgress(poolId, pending, index + 1, loaded);
        }).exceptionallyCompose(failure -> loadMutationProgress(poolId, pending, index + 1, loaded));
    }

    private void finishRefresh(Async<?> request, long ticket, String accountId, LoadedRefresh loaded, Throwable failure,
                               boolean resetPages) {
        if (read == request) {
            read = null;
        }
        if (!current(ticket, accountId)) {
            return;
        }
        if (failure != null) {
            publish(new Snapshot(accountId, snapshot.pools(), snapshot.offers(), snapshot.purchases(), snapshot.poolPages(),
                    snapshot.purchasePages(), false, message(failure), ticket));
            refreshQueued();
            return;
        }
        Snapshot next;
        try {
            next = refreshSnapshot(accountId, loaded, resetPages, ticket);
        } catch (RuntimeException validationFailure) {
            publish(new Snapshot(accountId, snapshot.pools(), snapshot.offers(), snapshot.purchases(), snapshot.poolPages(),
                    snapshot.purchasePages(), false, message(validationFailure), ticket));
            refreshQueued();
            return;
        }
        if (!current(ticket, accountId)) {
            return;
        }
        publish(next);
        clearSettledMutations(next);
        if (refreshQueued()) {
            return;
        }
        if (next.pending()) {
            schedulePoll();
        }
    }

    private Snapshot refreshSnapshot(String accountId, LoadedRefresh loaded, boolean resetPages, long ticket) {
        List<PoolView> pools = validatePools(loaded.pools);
        List<ResourcePoolModels.PurchaseStatus> purchases = validatePurchases(loaded.purchases.items());
        PageState poolPages = loaded.poolPages;
        PageState purchasePages = loaded.purchasePages;
        if (!resetPages) {
            pools = mergePoolFront(snapshot.pools(), pools);
            purchases = mergePurchaseFront(snapshot.purchases(), purchases);
            if (snapshot.poolPages().nextPage() > 1) {
                poolPages = snapshot.poolPages();
            }
            if (snapshot.purchasePages().nextPage() > 1) {
                purchasePages = snapshot.purchasePages();
            }
        }
        String purchaseMessage = reconcilePurchases(accountId, purchases);
        return new Snapshot(accountId, pools, loaded.offers, purchases, poolPages, purchasePages, false,
                purchaseMessage, ticket);
    }

    private static List<PoolView> validatePools(List<PoolView> pools) {
        LinkedHashMap<UUID, PoolView> unique = new LinkedHashMap<>();
        for (PoolView pool : pools) {
            PoolView checked = new PoolView(pool.pool(), validateDrafts(pool.drafts()),
                    validateAllocations(pool.allocations()), pool.progress(), pool.draftPages(), pool.allocationPages());
            if (unique.putIfAbsent(pool.pool().id(), checked) != null) {
                throw new IllegalStateException("Resource Pool Identity Is Duplicated");
            }
        }
        return List.copyOf(unique.values());
    }

    private static List<ResourcePoolModels.Draft> validateDrafts(List<ResourcePoolModels.Draft> drafts) {
        LinkedHashMap<UUID, ResourcePoolModels.Draft> unique = new LinkedHashMap<>();
        for (ResourcePoolModels.Draft draft : drafts) {
            if (unique.putIfAbsent(draft.id(), draft) != null) {
                throw new IllegalStateException("Server Draft Identity Is Duplicated");
            }
        }
        return List.copyOf(unique.values());
    }

    private static List<ResourcePoolModels.Allocation> validateAllocations(
            List<ResourcePoolModels.Allocation> allocations) {
        LinkedHashMap<String, ResourcePoolModels.Allocation> unique = new LinkedHashMap<>();
        for (ResourcePoolModels.Allocation allocation : allocations) {
            if (unique.putIfAbsent(allocation.serverId(), allocation) != null) {
                throw new IllegalStateException("Pool Server Identity Is Duplicated");
            }
        }
        return List.copyOf(unique.values());
    }

    private static List<ResourcePoolModels.PurchaseStatus> validatePurchases(
            List<ResourcePoolModels.PurchaseStatus> purchases) {
        LinkedHashMap<UUID, ResourcePoolModels.PurchaseStatus> requests = new LinkedHashMap<>();
        LinkedHashMap<UUID, ResourcePoolModels.PurchaseStatus> identities = new LinkedHashMap<>();
        for (ResourcePoolModels.PurchaseStatus purchase : purchases) {
            if (requests.putIfAbsent(purchase.purchaseRequestId(), purchase) != null) {
                throw new IllegalStateException("Purchase Request Identity Is Duplicated");
            }
            if (identities.putIfAbsent(purchase.purchaseId(), purchase) != null) {
                throw new IllegalStateException("Purchase Identity Is Duplicated");
            }
        }
        return List.copyOf(requests.values());
    }

    private static List<PoolView> mergePoolFront(List<PoolView> existing, List<PoolView> firstPage) {
        LinkedHashMap<UUID, PoolView> merged = new LinkedHashMap<>();
        for (PoolView pool : firstPage) {
            PoolView previous = existing.stream().filter(value -> value.pool().id().equals(pool.pool().id()))
                    .findFirst().orElse(null);
            merged.put(pool.pool().id(), previous == null ? pool : mergePoolFront(previous, pool));
        }
        for (PoolView pool : existing) {
            merged.putIfAbsent(pool.pool().id(), pool);
        }
        return List.copyOf(merged.values());
    }

    private static List<PoolView> appendPools(List<PoolView> existing, List<PoolView> added) {
        LinkedHashMap<UUID, PoolView> merged = new LinkedHashMap<>();
        existing.forEach(pool -> merged.put(pool.pool().id(), pool));
        for (PoolView pool : added) {
            if (merged.putIfAbsent(pool.pool().id(), pool) != null) {
                throw new IllegalStateException("Resource Pool Identity Is Duplicated Across Pages");
            }
        }
        return List.copyOf(merged.values());
    }

    private static PoolView mergePoolFront(PoolView existing, PoolView firstPage) {
        PageState drafts = existing.draftPages().nextPage() > 1 ? existing.draftPages() : firstPage.draftPages();
        PageState allocations = existing.allocationPages().nextPage() > 1
                ? existing.allocationPages() : firstPage.allocationPages();
        LinkedHashMap<UUID, ResourcePoolModels.Progress> progress = new LinkedHashMap<>(existing.progress());
        progress.putAll(firstPage.progress());
        return new PoolView(firstPage.pool(), mergeDraftFront(existing.drafts(), firstPage.drafts()),
                mergeAllocationFront(existing.allocations(), firstPage.allocations()), progress, drafts, allocations);
    }

    private static List<ResourcePoolModels.Draft> mergeDraftFront(List<ResourcePoolModels.Draft> existing,
                                                                   List<ResourcePoolModels.Draft> firstPage) {
        LinkedHashMap<UUID, ResourcePoolModels.Draft> merged = new LinkedHashMap<>();
        firstPage.forEach(draft -> merged.put(draft.id(), draft));
        existing.forEach(draft -> merged.putIfAbsent(draft.id(), draft));
        return List.copyOf(merged.values());
    }

    private static List<ResourcePoolModels.Draft> appendDrafts(List<ResourcePoolModels.Draft> existing,
                                                                List<ResourcePoolModels.Draft> added) {
        LinkedHashMap<UUID, ResourcePoolModels.Draft> merged = new LinkedHashMap<>();
        existing.forEach(draft -> merged.put(draft.id(), draft));
        for (ResourcePoolModels.Draft draft : added) {
            if (merged.putIfAbsent(draft.id(), draft) != null) {
                throw new IllegalStateException("Server Draft Identity Is Duplicated Across Pages");
            }
        }
        return List.copyOf(merged.values());
    }

    private static List<ResourcePoolModels.Allocation> mergeAllocationFront(
            List<ResourcePoolModels.Allocation> existing, List<ResourcePoolModels.Allocation> firstPage) {
        LinkedHashMap<String, ResourcePoolModels.Allocation> merged = new LinkedHashMap<>();
        firstPage.forEach(allocation -> merged.put(allocation.serverId(), allocation));
        existing.forEach(allocation -> merged.putIfAbsent(allocation.serverId(), allocation));
        return List.copyOf(merged.values());
    }

    private static List<ResourcePoolModels.Allocation> appendAllocations(
            List<ResourcePoolModels.Allocation> existing, List<ResourcePoolModels.Allocation> added) {
        LinkedHashMap<String, ResourcePoolModels.Allocation> merged = new LinkedHashMap<>();
        existing.forEach(allocation -> merged.put(allocation.serverId(), allocation));
        for (ResourcePoolModels.Allocation allocation : added) {
            if (merged.putIfAbsent(allocation.serverId(), allocation) != null) {
                throw new IllegalStateException("Pool Server Identity Is Duplicated Across Pages");
            }
        }
        return List.copyOf(merged.values());
    }

    private static List<ResourcePoolModels.PurchaseStatus> mergePurchaseFront(
            List<ResourcePoolModels.PurchaseStatus> existing, List<ResourcePoolModels.PurchaseStatus> firstPage) {
        LinkedHashMap<UUID, ResourcePoolModels.PurchaseStatus> merged = new LinkedHashMap<>();
        for (ResourcePoolModels.PurchaseStatus purchase : firstPage) {
            ResourcePoolModels.PurchaseStatus previous = existing.stream()
                    .filter(value -> value.purchaseRequestId().equals(purchase.purchaseRequestId()))
                    .findFirst().orElse(null);
            if (previous != null && (!previous.purchaseId().equals(purchase.purchaseId())
                    || !previous.offerId().equals(purchase.offerId()))) {
                throw new IllegalStateException("Purchase Identity Changed During Refresh");
            }
            merged.put(purchase.purchaseRequestId(), purchase);
        }
        existing.forEach(purchase -> merged.putIfAbsent(purchase.purchaseRequestId(), purchase));
        return validatePurchases(List.copyOf(merged.values()));
    }

    private static List<ResourcePoolModels.PurchaseStatus> appendPurchases(
            List<ResourcePoolModels.PurchaseStatus> existing, List<ResourcePoolModels.PurchaseStatus> added) {
        LinkedHashMap<UUID, ResourcePoolModels.PurchaseStatus> requests = new LinkedHashMap<>();
        LinkedHashMap<UUID, ResourcePoolModels.PurchaseStatus> identities = new LinkedHashMap<>();
        existing.forEach(purchase -> {
            requests.put(purchase.purchaseRequestId(), purchase);
            identities.put(purchase.purchaseId(), purchase);
        });
        for (ResourcePoolModels.PurchaseStatus purchase : added) {
            if (requests.putIfAbsent(purchase.purchaseRequestId(), purchase) != null) {
                throw new IllegalStateException("Purchase Request Identity Is Duplicated Across Pages");
            }
            if (identities.putIfAbsent(purchase.purchaseId(), purchase) != null) {
                throw new IllegalStateException("Purchase Identity Is Duplicated Across Pages");
            }
        }
        return List.copyOf(requests.values());
    }

    private String reconcilePurchases(String accountId, List<ResourcePoolModels.PurchaseStatus> purchases) {
        String mismatch = "";
        for (PendingPurchase pending : new ArrayList<>(purchaseRequests.values())) {
            if (!pending.accountId.equals(accountId)) {
                continue;
            }
            ResourcePoolModels.PurchaseStatus purchase = purchases.stream()
                    .filter(value -> value.purchaseRequestId().equals(pending.intent.purchaseRequestId()))
                    .findFirst().orElse(null);
            if (purchase == null) {
                continue;
            }
            if (!purchase.offerId().equals(pending.intent.offerId())) {
                mismatch = "Purchase Offer Identity Does Not Match";
                continue;
            }
            if (terminal(purchase.status()) && purchaseRequests.get(pending.intent.purchaseRequestId()) == pending) {
                Async<?> request = pending.request;
                pending.request = null;
                purchaseRequests.remove(pending.intent.purchaseRequestId(), pending);
                purchaseOffers.remove(pending.intent.purchaseRequestId());
                if (!pending.result.isDone()) {
                    pending.result.complete(PurchaseResult.from(purchase));
                }
                if (request != null) {
                    request.cancel();
                }
            }
        }
        return mismatch;
    }

    private static boolean terminal(ResourcePoolModels.PurchaseState state) {
        return state != ResourcePoolModels.PurchaseState.PENDING
                && state != ResourcePoolModels.PurchaseState.NEEDS_REVIEW;
    }

    private boolean refreshQueued() {
        if (!refreshQueued || disposed) {
            return false;
        }
        refreshQueued = false;
        refresh(false);
        return true;
    }

    private void clearSettledMutations(Snapshot current) {
        for (PoolView view : current.pools()) {
            for (ResourcePoolModels.Progress progress : view.progress().values()) {
                if (progress.operation().state() == ResourcePoolModels.OperationState.SETTLED
                        || progress.operation().state() == ResourcePoolModels.OperationState.SUPERSEDED) {
                    mutations.values().removeIf(value -> value.requestId.equals(progress.operation().requestId()));
                }
            }
        }
    }

    private void schedulePoll() {
        if (disposed || pollTask != null || polls >= MAX_POLLS) {
            if (polls >= MAX_POLLS) {
                publish(new Snapshot(snapshot.accountId(), snapshot.pools(), snapshot.offers(), snapshot.purchases(),
                        snapshot.poolPages(), snapshot.purchasePages(), false,
                        "Automatic Refresh Paused. Refresh To Check Progress", snapshot.generation()));
            }
            return;
        }
        try {
            boolean[] scheduling = {true};
            TaskScheduler.ScheduledTask scheduled = scheduler.schedule(() -> {
                if (scheduling[0]) {
                    return;
                }
                pollTask = null;
                polls++;
                refresh(false);
            }, Duration.ofSeconds(3));
            scheduling[0] = false;
            pollTask = scheduled;
        } catch (RuntimeException failure) {
            publish(new Snapshot(snapshot.accountId(), snapshot.pools(), snapshot.offers(), snapshot.purchases(),
                    snapshot.poolPages(), snapshot.purchasePages(), false,
                    "Automatic Refresh Is Unavailable. Refresh To Check Progress", snapshot.generation()));
        }
    }

    public void loadMorePools() {
        Snapshot current = snapshot;
        PageState pages = current.poolPages();
        if (!startPage("pools", pages)) {
            return;
        }
        publish(new Snapshot(current.accountId(), current.pools(), current.offers(), current.purchases(), pages.start(),
                current.purchasePages(), current.loading(), current.message(), current.generation()));
        int page = pages.nextPage();
        Async<LoadedPoolPage> request;
        try {
            request = timed(api.listPools(page, PAGE_SIZE)).thenCompose(result ->
                    loadPools(result.items(), 0, new ArrayList<>()).thenApply(pools -> new LoadedPoolPage(result, pools)));
        } catch (RuntimeException failure) {
            finishPoolPage(null, current.generation(), current.accountId(), null, failure);
            return;
        }
        trackPage("pools", request,
                (loaded, failure) -> finishPoolPage(request, current.generation(), current.accountId(), loaded, failure));
    }

    private void finishPoolPage(Async<?> request, long ticket, String accountId,
                                LoadedPoolPage loaded, Throwable failure) {
        if (!finishPage("pools", request, ticket, accountId)) {
            return;
        }
        Snapshot current = snapshot;
        if (failure != null) {
            publish(new Snapshot(current.accountId(), current.pools(), current.offers(), current.purchases(),
                    current.poolPages().fail(failure), current.purchasePages(), current.loading(), current.message(),
                    current.generation()));
            finishPageCycle();
            return;
        }
        try {
            List<PoolView> pools = appendPools(current.pools(), validatePools(loaded.pools()));
            publish(new Snapshot(current.accountId(), pools, current.offers(), current.purchases(),
                    current.poolPages().advance(loaded.page().hasMore()), current.purchasePages(), current.loading(),
                    current.message(), current.generation()));
        } catch (RuntimeException validationFailure) {
            publish(new Snapshot(current.accountId(), current.pools(), current.offers(), current.purchases(),
                    current.poolPages().fail(validationFailure), current.purchasePages(), current.loading(),
                    current.message(), current.generation()));
        }
        finishPageCycle();
    }

    public void loadMorePurchases() {
        Snapshot current = snapshot;
        PageState pages = current.purchasePages();
        if (!startPage("purchases", pages)) {
            return;
        }
        publish(new Snapshot(current.accountId(), current.pools(), current.offers(), current.purchases(),
                current.poolPages(), pages.start(), current.loading(), current.message(), current.generation()));
        int page = pages.nextPage();
        Async<ResourcePoolModels.Page<ResourcePoolModels.PurchaseStatus>> request;
        try {
            request = timed(api.listPurchases(page, PAGE_SIZE));
        } catch (RuntimeException failure) {
            finishPurchasePage(null, current.generation(), current.accountId(), null, failure);
            return;
        }
        trackPage("purchases", request,
                (loaded, failure) -> finishPurchasePage(request, current.generation(), current.accountId(), loaded, failure));
    }

    private void finishPurchasePage(Async<?> request, long ticket, String accountId,
                                    ResourcePoolModels.Page<ResourcePoolModels.PurchaseStatus> loaded,
                                    Throwable failure) {
        if (!finishPage("purchases", request, ticket, accountId)) {
            return;
        }
        Snapshot current = snapshot;
        if (failure != null) {
            publish(new Snapshot(current.accountId(), current.pools(), current.offers(), current.purchases(),
                    current.poolPages(), current.purchasePages().fail(failure), current.loading(), current.message(),
                    current.generation()));
            finishPageCycle();
            return;
        }
        try {
            List<ResourcePoolModels.PurchaseStatus> purchases = appendPurchases(current.purchases(),
                    validatePurchases(loaded.items()));
            String mismatch = reconcilePurchases(accountId, purchases);
            if (!current(ticket, accountId)) {
                return;
            }
            publish(new Snapshot(current.accountId(), current.pools(), current.offers(), purchases, current.poolPages(),
                    current.purchasePages().advance(loaded.hasMore()), current.loading(), mismatch, current.generation()));
        } catch (RuntimeException validationFailure) {
            publish(new Snapshot(current.accountId(), current.pools(), current.offers(), current.purchases(),
                    current.poolPages(), current.purchasePages().fail(validationFailure), current.loading(),
                    current.message(), current.generation()));
        }
        finishPageCycle();
    }

    public void loadMoreDrafts(UUID poolId) {
        PoolView pool = pool(poolId);
        if (pool == null || !startPage("drafts:" + poolId, pool.draftPages())) {
            return;
        }
        replacePool(pool, new PoolView(pool.pool(), pool.drafts(), pool.allocations(), pool.progress(),
                pool.draftPages().start(), pool.allocationPages()));
        int page = pool.draftPages().nextPage();
        long ticket = generation;
        String accountId = snapshot.accountId();
        Async<ResourcePoolModels.Page<ResourcePoolModels.Draft>> request;
        try {
            request = timed(api.listDrafts(poolId, page, PAGE_SIZE));
        } catch (RuntimeException failure) {
            finishDraftPage(null, poolId, ticket, accountId, null, failure);
            return;
        }
        String key = "drafts:" + poolId;
        trackPage(key, request,
                (loaded, failure) -> finishDraftPage(request, poolId, ticket, accountId, loaded, failure));
    }

    private void finishDraftPage(Async<?> request, UUID poolId, long ticket, String accountId,
                                 ResourcePoolModels.Page<ResourcePoolModels.Draft> loaded, Throwable failure) {
        String key = "drafts:" + poolId;
        if (!finishPage(key, request, ticket, accountId)) {
            return;
        }
        PoolView pool = pool(poolId);
        if (pool == null) {
            finishPageCycle();
            return;
        }
        if (failure != null) {
            replacePool(pool, new PoolView(pool.pool(), pool.drafts(), pool.allocations(), pool.progress(),
                    pool.draftPages().fail(failure), pool.allocationPages()));
            finishPageCycle();
            return;
        }
        try {
            List<ResourcePoolModels.Draft> drafts = appendDrafts(pool.drafts(), validateDrafts(loaded.items()));
            replacePool(pool, new PoolView(pool.pool(), drafts, pool.allocations(), pool.progress(),
                    pool.draftPages().advance(loaded.hasMore()), pool.allocationPages()));
        } catch (RuntimeException validationFailure) {
            replacePool(pool, new PoolView(pool.pool(), pool.drafts(), pool.allocations(), pool.progress(),
                    pool.draftPages().fail(validationFailure), pool.allocationPages()));
        }
        finishPageCycle();
    }

    public void loadMoreAllocations(UUID poolId) {
        PoolView pool = pool(poolId);
        if (pool == null || !startPage("allocations:" + poolId, pool.allocationPages())) {
            return;
        }
        replacePool(pool, new PoolView(pool.pool(), pool.drafts(), pool.allocations(), pool.progress(),
                pool.draftPages(), pool.allocationPages().start()));
        int page = pool.allocationPages().nextPage();
        long ticket = generation;
        String accountId = snapshot.accountId();
        Async<LoadedAllocationPage> request;
        try {
            request = timed(api.listAllocations(poolId, page, PAGE_SIZE)).thenCompose(result ->
                    loadProgress(poolId, result.items(), 0, new LinkedHashMap<>())
                            .thenApply(progress -> new LoadedAllocationPage(result, progress)));
        } catch (RuntimeException failure) {
            finishAllocationPage(null, poolId, ticket, accountId, null, failure);
            return;
        }
        String key = "allocations:" + poolId;
        trackPage(key, request,
                (loaded, failure) -> finishAllocationPage(request, poolId, ticket, accountId, loaded, failure));
    }

    private void finishAllocationPage(Async<?> request, UUID poolId, long ticket, String accountId,
                                      LoadedAllocationPage loaded, Throwable failure) {
        String key = "allocations:" + poolId;
        if (!finishPage(key, request, ticket, accountId)) {
            return;
        }
        PoolView pool = pool(poolId);
        if (pool == null) {
            finishPageCycle();
            return;
        }
        if (failure != null) {
            replacePool(pool, new PoolView(pool.pool(), pool.drafts(), pool.allocations(), pool.progress(),
                    pool.draftPages(), pool.allocationPages().fail(failure)));
            finishPageCycle();
            return;
        }
        try {
            List<ResourcePoolModels.Allocation> allocations = appendAllocations(pool.allocations(),
                    validateAllocations(loaded.page().items()));
            LinkedHashMap<UUID, ResourcePoolModels.Progress> progress = new LinkedHashMap<>(pool.progress());
            progress.putAll(loaded.progress());
            replacePool(pool, new PoolView(pool.pool(), pool.drafts(), allocations, progress, pool.draftPages(),
                    pool.allocationPages().advance(loaded.page().hasMore())));
        } catch (RuntimeException validationFailure) {
            replacePool(pool, new PoolView(pool.pool(), pool.drafts(), pool.allocations(), pool.progress(),
                    pool.draftPages(), pool.allocationPages().fail(validationFailure)));
        }
        finishPageCycle();
    }

    private boolean startPage(String key, PageState pages) {
        if (disposed || snapshot.loading() || read != null && !read.isDone() || pages.loading() || !pages.hasMore()
                || pageReads.containsKey(key)) {
            return false;
        }
        if (!currentAccount().equals(snapshot.accountId())) {
            refresh();
            return false;
        }
        cancelPoll();
        return true;
    }

    private <T> void trackPage(String key, Async<T> request, BiConsumer<T, Throwable> completion) {
        pageReads.put(key, request);
        request.whenComplete(completion);
    }

    private boolean finishPage(String key, Async<?> request, long ticket, String accountId) {
        if (request != null && pageReads.get(key) != request) {
            return false;
        }
        if (request != null) {
            pageReads.remove(key);
        }
        return current(ticket, accountId);
    }

    private void finishPageCycle() {
        if (!pageReads.isEmpty()) {
            return;
        }
        if (refreshQueued()) {
            return;
        }
        if (snapshot.pending()) {
            schedulePoll();
        }
    }

    private PoolView pool(UUID poolId) {
        return snapshot.pools().stream().filter(pool -> pool.pool().id().equals(poolId)).findFirst().orElse(null);
    }

    private void replacePool(PoolView previous, PoolView replacement) {
        List<PoolView> pools = new ArrayList<>(snapshot.pools());
        int index = pools.indexOf(previous);
        if (index < 0) {
            return;
        }
        pools.set(index, replacement);
        publish(new Snapshot(snapshot.accountId(), pools, snapshot.offers(), snapshot.purchases(), snapshot.poolPages(),
                snapshot.purchasePages(), snapshot.loading(), snapshot.message(), snapshot.generation()));
    }

    private void clearPageLoading() {
        List<PoolView> pools = snapshot.pools().stream().map(pool -> new PoolView(pool.pool(), pool.drafts(),
                pool.allocations(), pool.progress(), pool.draftPages().stop(), pool.allocationPages().stop())).toList();
        publish(new Snapshot(snapshot.accountId(), pools, snapshot.offers(), snapshot.purchases(),
                snapshot.poolPages().stop(), snapshot.purchasePages().stop(), snapshot.loading(), snapshot.message(),
                snapshot.generation()));
    }

    public PurchaseIntent beginPurchase(ResourcePoolModels.Offer offer) {
        Objects.requireNonNull(offer, "offer");
        String accountId = currentAccount();
        if (accountId.isBlank() || !accountId.equals(snapshot.accountId())) {
            throw new IllegalStateException("Refresh Resources Before Starting Checkout");
        }
        ResourcePoolModels.Offer authoritative = snapshot.offers().stream()
                .filter(candidate -> candidate.id().equals(offer.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Offer Is No Longer Available"));
        if (!authoritative.equals(offer)) {
            throw new IllegalArgumentException("Offer Changed. Review The Current Offer");
        }
        boolean unresolved = snapshot.purchases().stream().anyMatch(purchase -> purchase.offerId().equals(offer.id())
                && (purchase.status() == ResourcePoolModels.PurchaseState.PENDING
                || purchase.status() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW));
        boolean localUnresolved = purchaseRequests.values().stream().anyMatch(purchase -> purchase.submitted
                && purchase.accountId.equals(accountId) && purchase.intent.offerId().equals(offer.id()));
        if (unresolved || localUnresolved) {
            throw new IllegalStateException("This Offer Already Has A Purchase Awaiting Payment Or Review");
        }
        PurchaseIntent intent = new PurchaseIntent(UUID.randomUUID(), offer.id());
        purchaseOffers.put(intent.purchaseRequestId(), offer);
        return intent;
    }

    public Async<PurchaseResult> checkout(PurchaseIntent intent) {
        Objects.requireNonNull(intent, "intent");
        String accountId = currentAccount();
        if (accountId.isBlank() || !accountId.equals(snapshot.accountId())) {
            return Async.failed(new IllegalStateException("Refresh Resources Before Starting Checkout"));
        }
        if (read != null && !read.isDone()) {
            return Async.failed(new IllegalStateException("Wait For Resource Refresh Before Starting Checkout"));
        }
        ResourcePoolModels.Offer reviewed = purchaseOffers.get(intent.purchaseRequestId());
        ResourcePoolModels.Offer currentOffer = snapshot.offers().stream()
                .filter(offer -> offer.id().equals(intent.offerId())).findFirst().orElse(null);
        if (reviewed == null || !reviewed.id().equals(intent.offerId())) {
            return Async.failed(new IllegalArgumentException("Checkout Intent Is Unavailable"));
        }
        if (!reviewed.equals(currentOffer)) {
            return Async.failed(new IllegalArgumentException("Offer Is No Longer Available"));
        }
        PendingPurchase pending = purchaseRequests.get(intent.purchaseRequestId());
        if (pending == null) {
            pending = new PendingPurchase(intent, accountId);
            purchaseRequests.put(intent.purchaseRequestId(), pending);
        } else if (!pending.intent.equals(intent) || !pending.accountId.equals(accountId)) {
            return Async.failed(new IllegalStateException("Checkout Identity Does Not Match"));
        }
        if (pending.submitted) {
            return pending.result;
        }
        pending.submitted = true;
        PendingPurchase request = pending;
        Async<ResourcePoolModels.Checkout> operation;
        try {
            operation = timed(api.checkout(new ResourcePoolModels.CheckoutRequest(
                    intent.purchaseRequestId(), intent.offerId())));
        } catch (RuntimeException failure) {
            discoverPurchase(request, failure);
            return request.result;
        }
        request.request = operation;
        operation.whenComplete((checkout, failure) -> {
            if (!purchaseCurrent(request)) {
                request.result.fail(new IllegalStateException("Account Changed During Checkout"));
                return;
            }
            if (failure != null) {
                discoverPurchase(request, failure);
                return;
            }
            request.request = null;
            completePurchase(request, PurchaseResult.from(checkout));
            refresh(false);
        });
        return request.result;
    }

    public void discardPurchase(PurchaseIntent intent) {
        if (intent != null && !purchaseRequests.containsKey(intent.purchaseRequestId())) {
            purchaseOffers.remove(intent.purchaseRequestId());
        }
    }

    private void discoverPurchase(PendingPurchase pending, Throwable checkoutFailure) {
        Async<ResourcePoolModels.PurchaseStatus> discovery;
        try {
            discovery = timed(api.getPurchase(pending.intent.purchaseRequestId()));
        } catch (RuntimeException failure) {
            pending.request = null;
            pending.result.fail(purchaseUnknown(checkoutFailure));
            return;
        }
        pending.request = discovery;
        discovery.whenComplete((purchase, failure) -> {
            pending.request = null;
            if (!purchaseCurrent(pending)) {
                pending.result.fail(new IllegalStateException("Account Changed During Checkout"));
                return;
            }
            if (failure != null) {
                pending.result.fail(purchaseUnknown(checkoutFailure));
                refresh(false);
                return;
            }
            if (!pending.intent.offerId().equals(purchase.offerId())) {
                pending.result.fail(new IllegalStateException("Checkout Offer Identity Does Not Match"));
                return;
            }
            completePurchase(pending, PurchaseResult.from(purchase));
            refresh(false);
        });
    }

    private void completePurchase(PendingPurchase pending, PurchaseResult result) {
        pending.result.complete(result);
        if (result.state() != ResourcePoolModels.PurchaseState.PENDING
                && result.state() != ResourcePoolModels.PurchaseState.NEEDS_REVIEW
                && purchaseRequests.get(pending.intent.purchaseRequestId()) == pending) {
            purchaseRequests.remove(pending.intent.purchaseRequestId());
            purchaseOffers.remove(pending.intent.purchaseRequestId());
        }
    }

    private boolean purchaseCurrent(PendingPurchase pending) {
        return !disposed && pending.accountId.equals(currentAccount())
                && purchaseRequests.get(pending.intent.purchaseRequestId()) == pending;
    }

    private static void cancelPurchase(PendingPurchase pending) {
        if (pending.request != null) {
            pending.request.cancel();
            pending.request = null;
        }
        pending.result.fail(new IllegalStateException("Resource Session Changed During Checkout"));
    }

    public Async<ResourcePoolModels.Draft> createDraft(Creation creation, String name,
                                                       ServerScreenHost.PoolResources resources,
                                                       Map<String, String> settings,
                                                       Map<String, String> initialFiles) {
        Objects.requireNonNull(creation, "creation");
        Objects.requireNonNull(resources, "resources");
        if (resources.gameId().isBlank() || resources.profileId().isBlank()) {
            throw new IllegalArgumentException("Game And Profile Are Required");
        }
        ResourcePoolModels.CreateDraftRequest request = new ResourcePoolModels.CreateDraftRequest(
                creation.createRequestId(), creation.draftId(), name, resources.gameId(), resources.profileId(),
                settings == null ? Map.of() : settings, initialFiles == null ? Map.of() : initialFiles);
        Async<ResourcePoolModels.Draft> result = Async.pending();
        String accountId = currentAccount();
        timed(api.createDraft(creation.poolId(), request)).whenComplete((admission, failure) -> {
            if (!currentAccount().equals(accountId)) {
                result.fail(new IllegalStateException("Account Changed During Server Creation"));
                return;
            }
            if (failure == null) {
                result.complete(admission.draft());
                refresh(false);
                return;
            }
            timed(api.getDraft(creation.poolId(), creation.draftId())).whenComplete((draft, discoveryFailure) -> {
                if (!currentAccount().equals(accountId)) {
                    result.fail(new IllegalStateException("Account Changed During Server Creation"));
                    return;
                }
                if (discoveryFailure != null) {
                    result.fail(unknown("Server Draft Creation", failure));
                    return;
                }
                if (!draft.metadata().equals(new ResourcePoolModels.DraftMetadata(name, resources.gameId(),
                        resources.profileId(), settings == null ? Map.of() : settings,
                        initialFiles == null ? Map.of() : initialFiles))) {
                    result.fail(new IllegalStateException("Server Draft Identity Does Not Match"));
                    return;
                }
                result.complete(draft);
                refresh(false);
            });
        });
        return result;
    }

    public Async<ResourcePoolModels.Draft> activate(ResourcePoolModels.Draft draft,
                                                     ServerScreenHost.PoolResources resources) {
        Objects.requireNonNull(draft, "draft");
        validate(resources);
        Async<ResourcePoolModels.Draft> result = Async.pending();
        activate(resume(draft), draft, resources, result, currentAccount());
        return result;
    }

    private void activate(Creation creation, ResourcePoolModels.Draft draft,
                          ServerScreenHost.PoolResources resources, Async<ResourcePoolModels.Draft> result,
                          String accountId) {
        if (draft.state() != ResourcePoolModels.DraftState.DRAFT) {
            UUID accepted = draft.activationRequestId();
            if (accepted != null && accepted.equals(creation.activationRequestId())) {
                result.complete(draft);
            } else if (draft.state() == ResourcePoolModels.DraftState.ACTIVE) {
                result.complete(draft);
            } else {
                result.fail(new IllegalStateException("Server Activation Identity Does Not Match"));
            }
            return;
        }
        ResourcePoolModels.ActivateDraftRequest request = new ResourcePoolModels.ActivateDraftRequest(
                creation.activationRequestId(), draft.revision(), resources.installerRamMiB(),
                resources.installerCpuPercent(), resources.runtimeRamMiB(), resources.runtimeCpuPercent(),
                resources.diskMiB(), resources.backupMiB());
        timed(api.activateDraft(creation.poolId(), creation.draftId(), request)).whenComplete((admission, failure) -> {
            if (!currentAccount().equals(accountId)) {
                result.fail(new IllegalStateException("Account Changed During Server Activation"));
                return;
            }
            if (failure == null) {
                result.complete(admission.draft());
                refresh(false);
                return;
            }
            timed(api.getDraft(creation.poolId(), creation.draftId())).whenComplete((discovered, discoveryFailure) -> {
                if (!currentAccount().equals(accountId)) {
                    result.fail(new IllegalStateException("Account Changed During Server Activation"));
                    return;
                }
                if (discoveryFailure != null) {
                    result.fail(unknown("Server Activation", failure));
                    return;
                }
                if (discovered.state() == ResourcePoolModels.DraftState.DRAFT) {
                    result.fail(new IllegalStateException("Server Activation Was Not Accepted: " + message(failure)));
                    return;
                }
                if (!creation.activationRequestId().equals(discovered.activationRequestId())) {
                    result.fail(new IllegalStateException("Server Activation Identity Does Not Match"));
                    return;
                }
                result.complete(discovered);
                refresh(false);
            });
        });
    }

    public Async<ResourcePoolModels.Progress> assign(ResourcePoolModels.Allocation allocation,
                                                      String ramMiB, String cpuPercent) {
        positive(ramMiB, "RAM");
        positive(cpuPercent, "CPU");
        return mutate(allocation, MutationKind.ASSIGN, ramMiB, cpuPercent);
    }

    public Async<ResourcePoolModels.Progress> disable(ResourcePoolModels.Allocation allocation) {
        return mutate(allocation, MutationKind.DISABLE, "0", "0");
    }

    private Async<ResourcePoolModels.Progress> mutate(ResourcePoolModels.Allocation allocation, MutationKind kind,
                                                       String ramMiB, String cpuPercent) {
        Objects.requireNonNull(allocation, "allocation");
        String key = allocation.poolId() + ":" + allocation.serverId();
        PendingMutation pending = mutations.get(key);
        if (pending == null) {
            pending = new PendingMutation(kind, allocation, currentAccount(), ramMiB, cpuPercent);
            mutations.put(key, pending);
        } else if (!pending.accountId.equals(currentAccount())) {
            return Async.failed(new IllegalStateException("Account Changed During Resource Request"));
        } else if (pending.kind != kind || !pending.ramMiB.equals(ramMiB) || !pending.cpuPercent.equals(cpuPercent)) {
            return Async.failed(new IllegalStateException("Refresh Before Changing This Resource Request"));
        }
        PendingMutation request = pending;
        Async<ResourcePoolModels.Progress> result = Async.pending();
        if (request.authority != null) {
            dispatchMutation(request, result);
            return result;
        }
        ResourcePoolModels.Action action = kind == MutationKind.ASSIGN
                ? ResourcePoolModels.Action.ASSIGN : ResourcePoolModels.Action.DISABLE;
        timed(api.refreshAllocation(allocation.poolId(), allocation.serverId(), action)).whenComplete((refreshed, refreshFailure) -> {
            if (!request.accountId.equals(currentAccount())) {
                result.fail(new IllegalStateException("Account Changed During Resource Request"));
                return;
            }
            if (refreshFailure != null) {
                result.fail(new IllegalStateException("Resource Authority Refresh Failed: " + message(refreshFailure)));
                return;
            }
            request.authority = refreshed;
            dispatchMutation(request, result);
        });
        return result;
    }

    private void dispatchMutation(PendingMutation request, Async<ResourcePoolModels.Progress> result) {
        if (!request.accountId.equals(currentAccount())) {
            result.fail(new IllegalStateException("Account Changed During Resource Request"));
            return;
        }
        ResourcePoolModels.Allocation allocation = request.authority;
        Async<ResourcePoolModels.Mutation> mutation;
        try {
            mutation = request.kind == MutationKind.ASSIGN
                    ? api.assign(allocation.poolId(), allocation.serverId(), new ResourcePoolModels.AssignRequest(
                    request.requestId, allocation.nodeId(), allocation.revision(), allocation.providerRevision(),
                    request.ramMiB, request.cpuPercent))
                    : api.disable(allocation.poolId(), allocation.serverId(), new ResourcePoolModels.DisableRequest(
                    request.requestId, allocation.nodeId(), allocation.revision(), allocation.providerRevision()));
        } catch (RuntimeException failure) {
            result.fail(failure);
            return;
        }
        timed(mutation).whenComplete((accepted, failure) -> {
            if (!request.accountId.equals(currentAccount())) {
                result.fail(new IllegalStateException("Account Changed During Resource Request"));
                return;
            }
            if (failure == null) {
                result.complete(new ResourcePoolModels.Progress(accepted.operation(), accepted.hosting()));
                refresh(false);
                return;
            }
            timed(api.getProgress(allocation.poolId(), request.requestId)).whenComplete((progress, discoveryFailure) -> {
                if (!request.accountId.equals(currentAccount())) {
                    result.fail(new IllegalStateException("Account Changed During Resource Request"));
                    return;
                }
                if (discoveryFailure != null) {
                    result.fail(unknown(request.kind == MutationKind.DISABLE ? "Disable" : "Resource Assignment", failure));
                    refresh(false);
                    return;
                }
                result.complete(progress);
                refresh(false);
            });
        });
    }

    private static void validate(ServerScreenHost.PoolResources resources) {
        Objects.requireNonNull(resources, "resources");
        if (resources.gameId().isBlank() || resources.profileId().isBlank()) {
            throw new IllegalArgumentException("Game And Profile Are Required");
        }
        positive(resources.installerRamMiB(), "Installer RAM");
        positive(resources.installerCpuPercent(), "Installer CPU");
        positive(resources.runtimeRamMiB(), "Runtime RAM");
        positive(resources.runtimeCpuPercent(), "Runtime CPU");
        positive(resources.diskMiB(), "Disk");
        decimal(resources.backupMiB(), "Backup");
    }

    private <T> Async<T> timed(Async<T> source) {
        return Async.withTimeout(source, scheduler, Duration.ofSeconds(20));
    }

    private static void positive(String value, String name) {
        decimal(value, name);
        if ("0".equals(value)) {
            throw new IllegalArgumentException(name + " Must Be Greater Than Zero");
        }
    }

    private static void decimal(String value, String name) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(name + " Is Invalid");
        }
    }

    private boolean current(long ticket, String accountId) {
        return !disposed && generation == ticket && currentAccount().equals(accountId);
    }

    private String currentAccount() {
        String value = account.get();
        return value == null ? "" : value.trim();
    }

    private void publish(Snapshot next) {
        snapshot = next;
        listener.accept(next);
    }

    private void cancelPoll() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    private void cancelPageReads() {
        List<Async<?>> reads = new ArrayList<>(pageReads.values());
        pageReads.clear();
        reads.forEach(Async::cancel);
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        generation++;
        refreshQueued = false;
        cancelPoll();
        cancelPageReads();
        Async<?> current = read;
        read = null;
        if (current != null) {
            current.cancel();
        }
        purchaseRequests.values().forEach(ResourcePoolController::cancelPurchase);
        purchaseRequests.clear();
        purchaseOffers.clear();
        listener = ignored -> {};
    }

    private static IllegalStateException unknown(String action, Throwable failure) {
        return new IllegalStateException(action + " Outcome Is Unknown. Retry Uses The Same Request Identity: " + message(failure));
    }

    private static IllegalStateException purchaseUnknown(Throwable failure) {
        return new IllegalStateException("Checkout Outcome Is Unknown. Refresh Purchases Before Starting Another Checkout: "
                + message(failure));
    }

    public static String message(Throwable failure) {
        String result = "Operation Failed";
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                result = current.getMessage();
            }
            current = current.getCause();
        }
        return result;
    }
}
