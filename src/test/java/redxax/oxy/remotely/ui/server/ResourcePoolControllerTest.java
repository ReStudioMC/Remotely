package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;
import restudio.rebase.resource.ResourcePoolClient;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePoolControllerTest {
    @Test
    void invalidActivationResourcesCannotCreateAnOrphanDraft() {
        int[] requests = {0};
        ResourcePoolClient client = new ResourcePoolClient((method, path, body) -> {
            requests[0]++;
            return Async.failed(new AssertionError("Unexpected Request"));
        });
        ResourcePoolController controller = controller(client, new AtomicReference<>("account"));
        ResourcePoolController.Creation creation = controller.begin(UUID.randomUUID());
        ServerScreenHost.PoolResources invalid = new ServerScreenHost.PoolResources(
                "minecraft", "paper", "2048", "200", "2048", "200", "", "0");

        assertThrows(IllegalArgumentException.class,
                () -> controller.createDraft(creation, "QA", invalid, Map.of(), Map.of()));
        assertEquals(0, requests[0]);
    }

    @Test
    void snapshotReportsEveryPendingResourceState() {
        ResourcePoolModels.Pool pool = pool();
        ResourcePoolModels.Draft draft = draft(pool.id(), ResourcePoolModels.DraftState.ACTIVATING, null);
        ResourcePoolModels.Allocation allocation = allocation(pool.id(), ResourcePoolModels.AllocationState.PENDING, null);
        ResourcePoolModels.Progress progress = progress(ResourcePoolModels.OperationState.PENDING);

        assertTrue(snapshot(pool, List.of(draft), List.of(), Map.of()).pending());
        assertTrue(snapshot(pool, List.of(), List.of(allocation), Map.of()).pending());
        assertTrue(snapshot(pool, List.of(), List.of(), Map.of(progress.operation().requestId(), progress)).pending());
        assertTrue(new ResourcePoolController.Snapshot("account", List.of(), List.of(),
                List.of(purchase(ResourcePoolModels.PurchaseState.NEEDS_REVIEW)), false, "", 1).pending());
        assertFalse(snapshot(pool, List.of(), List.of(), Map.of()).pending());
    }

    @Test
    void resumeKeepsOneActivationIdentityUntilTheServerAcceptsIt() {
        ResourcePoolController controller = controller(new ResourcePoolClient((method, path, body) -> Async.failed(
                new AssertionError("Unexpected Request"))), new AtomicReference<>("account"));
        ResourcePoolModels.Draft pending = draft(pool().id(), ResourcePoolModels.DraftState.DRAFT, null);

        UUID first = controller.resume(pending).activationRequestId();
        UUID second = controller.resume(pending).activationRequestId();
        UUID accepted = UUID.randomUUID();

        assertEquals(first, second);
        assertEquals(accepted, controller.resume(draft(pending.poolId(), ResourcePoolModels.DraftState.ACTIVATING,
                accepted)).activationRequestId());
    }

    @Test
    void accountSwitchCancelsAndFencesTheEarlierPoolRead() {
        AtomicReference<String> account = new AtomicReference<>("first");
        List<Async<String>> responses = new ArrayList<>();
        ResourcePoolClient client = new ResourcePoolClient((method, path, body) -> {
            if (path.equals("/billing/resource-pools/offers")) {
                return Async.completed("[]");
            }
            if (path.startsWith("/billing/resource-pools/purchases?")) {
                return Async.completed(emptyPage());
            }
            Async<String> response = Async.pending();
            responses.add(response);
            return response;
        });
        ResourcePoolController controller = controller(client, account);

        controller.refresh();
        Async<String> first = responses.getFirst();
        account.set("second");
        controller.refresh();
        Async<String> second = responses.get(1);

        assertTrue(first.isCancelled());
        assertEquals("second", controller.snapshot().accountId());
        second.complete(emptyPage());
        first.complete(emptyPage());

        assertEquals("second", controller.snapshot().accountId());
        assertFalse(controller.snapshot().loading());
        assertTrue(controller.snapshot().pools().isEmpty());
    }

    @Test
    void explicitRefreshReplacesAStalledRead() {
        List<Async<String>> responses = new ArrayList<>();
        ResourcePoolClient client = new ResourcePoolClient((method, path, body) -> {
            if (path.equals("/billing/resource-pools/offers")) return Async.completed("[]");
            if (path.startsWith("/billing/resource-pools/purchases?")) return Async.completed(emptyPage());
            Async<String> response = Async.pending();
            responses.add(response);
            return response;
        });
        ResourcePoolController controller = controller(client, new AtomicReference<>("account"));

        controller.refresh();
        Async<String> stalled = responses.getFirst();
        controller.refresh();

        assertTrue(stalled.isCancelled());
        assertEquals(2, responses.size());
        responses.get(1).complete(emptyPage());
        assertFalse(controller.snapshot().loading());
        assertTrue(controller.snapshot().message().isBlank());
    }

    @Test
    void everyResourceCollectionContinuesOneUserRequestedPageAtATime() {
        PagingTransport transport = new PagingTransport();
        ResourcePoolController controller = controller(new ResourcePoolClient(transport),
                new AtomicReference<>("account"));

        controller.refresh();

        assertEquals(25, controller.snapshot().pools().size());
        assertEquals(25, controller.snapshot().purchases().size());
        assertTrue(controller.snapshot().poolPages().hasMore());
        assertTrue(controller.snapshot().purchasePages().hasMore());

        controller.loadMorePools();
        controller.loadMorePurchases();

        assertEquals(26, controller.snapshot().pools().size());
        assertEquals(26, controller.snapshot().purchases().size());
        ResourcePoolController.PoolView pool = controller.snapshot().pools().getLast();
        assertEquals(25, pool.drafts().size());
        assertEquals(25, pool.allocations().size());
        assertTrue(pool.draftPages().hasMore());
        assertTrue(pool.allocationPages().hasMore());

        controller.loadMoreDrafts(pool.pool().id());
        pool = controller.snapshot().pools().getLast();
        assertEquals(26, pool.drafts().size());
        assertFalse(pool.draftPages().hasMore());
        assertTrue(pool.allocationPages().hasMore());

        controller.loadMoreAllocations(pool.pool().id());
        pool = controller.snapshot().pools().getLast();
        assertEquals(26, pool.allocations().size());
        assertFalse(pool.allocationPages().hasMore());

        controller.refresh();

        assertEquals(25, controller.snapshot().pools().size());
        assertEquals(25, controller.snapshot().purchases().size());
        assertEquals(1, controller.snapshot().poolPages().nextPage());
        assertEquals(1, controller.snapshot().purchasePages().nextPage());
        assertEquals(25, controller.snapshot().pools().stream().map(view -> view.pool().id()).distinct().count());
        assertEquals(25, controller.snapshot().purchases().stream()
                .map(ResourcePoolModels.PurchaseStatus::purchaseRequestId).distinct().count());
    }

    @Test
    void continuationFailurePreservesRowsAndRetryRejectsCrossPageDuplicates() {
        UUID firstPool = UUID.randomUUID();
        UUID secondPool = UUID.randomUUID();
        Async<String> failedPage = Async.pending();
        int[] continuationCalls = {0};
        ResourcePoolClient client = new ResourcePoolClient((method, path, body) -> {
            if (path.equals("/resource-pools?page=0&size=25")) {
                return Async.completed(page(0, true, poolJson(firstPool)));
            }
            if (path.equals("/resource-pools?page=1&size=25")) {
                continuationCalls[0]++;
                return continuationCalls[0] == 1 ? failedPage : Async.completed(page(1, true, poolJson(secondPool)));
            }
            if (path.equals("/resource-pools?page=2&size=25")) {
                continuationCalls[0]++;
                return Async.completed(page(2, false, poolJson(firstPool)));
            }
            if (path.contains("/drafts?") || path.contains("/allocations?")) return Async.completed(emptyPage());
            if (path.equals("/billing/resource-pools/offers")) return Async.completed("[]");
            if (path.startsWith("/billing/resource-pools/purchases?")) return Async.completed(emptyPage());
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        });
        ResourcePoolController controller = controller(client, new AtomicReference<>("account"));
        controller.refresh();

        controller.loadMorePools();
        controller.loadMorePools();
        assertEquals(1, continuationCalls[0]);
        failedPage.fail(new IllegalStateException("Page Unavailable"));
        assertEquals(1, controller.snapshot().pools().size());
        assertTrue(controller.snapshot().poolPages().message().contains("Page Unavailable"));

        controller.loadMorePools();
        assertEquals(2, controller.snapshot().pools().size());
        controller.loadMorePools();

        assertEquals(2, controller.snapshot().pools().size());
        assertTrue(controller.snapshot().poolPages().message().contains("Duplicated Across Pages"));
    }

    @Test
    void accountSwitchCancelsAndFencesALateContinuation() {
        AtomicReference<String> account = new AtomicReference<>("first");
        ContinuationTransport transport = new ContinuationTransport();
        ResourcePoolController controller = controller(new ResourcePoolClient(transport), account);
        controller.refresh();
        controller.loadMorePools();

        account.set("second");
        controller.refresh();
        transport.continuation.complete(page(1, false, poolJson(UUID.randomUUID())));

        assertTrue(transport.continuation.isCancelled());
        assertEquals("second", controller.snapshot().accountId());
        assertEquals(1, controller.snapshot().pools().size());
    }

    @Test
    void disposalCancelsAndFencesALateContinuation() {
        ContinuationTransport transport = new ContinuationTransport();
        ResourcePoolController controller = controller(new ResourcePoolClient(transport),
                new AtomicReference<>("account"));
        controller.refresh();
        controller.loadMorePools();
        ResourcePoolController.Snapshot before = controller.snapshot();

        controller.dispose();
        transport.continuation.complete(page(1, false, poolJson(UUID.randomUUID())));

        assertTrue(transport.continuation.isCancelled());
        assertSame(before, controller.snapshot());
    }

    @Test
    void checkoutResponseLossUsesOwnerGetAndNeverPostsAgain() {
        AtomicReference<UUID> requestId = new AtomicReference<>();
        int[] posts = {0};
        int[] discoveries = {0};
        UUID purchaseId = UUID.randomUUID();
        UUID poolId = UUID.randomUUID();
        ResourcePoolClient client = new ResourcePoolClient((method, path, body) -> {
            if (path.equals("/resource-pools?page=0&size=25")) return Async.completed(emptyPage());
            if (path.equals("/billing/resource-pools/offers")) return Async.completed(offerJson());
            if (path.startsWith("/billing/resource-pools/purchases?")) return Async.completed(emptyPage());
            if (method.equals("POST") && path.equals("/billing/resource-pools/checkout")) {
                posts[0]++;
                assertTrue(body.contains(requestId.get().toString()));
                assertTrue(body.contains("\"offerId\":\"offer\""));
                return Async.failed(new IllegalStateException("Response Lost"));
            }
            if (method.equals("GET") && path.startsWith("/billing/resource-pools/purchases/")) {
                discoveries[0]++;
                return Async.completed(purchaseJson(requestId.get(), purchaseId, poolId,
                        ResourcePoolModels.PurchaseState.NEEDS_REVIEW));
            }
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        });
        ResourcePoolController controller = controller(client, new AtomicReference<>("account"));
        controller.refresh();
        ResourcePoolController.PurchaseIntent intent = controller.beginPurchase(controller.snapshot().offers().getFirst());
        requestId.set(intent.purchaseRequestId());
        assertEquals(0, posts[0]);

        Async<ResourcePoolController.PurchaseResult> first = controller.checkout(intent);
        Async<ResourcePoolController.PurchaseResult> replay = controller.checkout(intent);

        assertSame(first, replay);
        assertEquals(ResourcePoolModels.PurchaseState.NEEDS_REVIEW, first.join().state());
        assertEquals(1, posts[0]);
        assertEquals(1, discoveries[0]);
    }

    @Test
    void everyConfirmedTerminalPurchaseAllowsANewExplicitIntent() {
        List<ResourcePoolModels.PurchaseState> terminal = List.of(ResourcePoolModels.PurchaseState.ACTIVE,
                ResourcePoolModels.PurchaseState.PAYMENT_FAILED, ResourcePoolModels.PurchaseState.CANCELLED,
                ResourcePoolModels.PurchaseState.REFUNDED, ResourcePoolModels.PurchaseState.EXPIRED,
                ResourcePoolModels.PurchaseState.FAILED);
        for (ResourcePoolModels.PurchaseState state : terminal) {
            PurchaseTransport transport = new PurchaseTransport();
            ResourcePoolController controller = controller(new ResourcePoolClient(transport),
                    new AtomicReference<>("account"));
            controller.refresh();
            ResourcePoolModels.Offer offer = controller.snapshot().offers().getFirst();
            ResourcePoolController.PurchaseIntent submitted = controller.beginPurchase(offer);
            Async<ResourcePoolController.PurchaseResult> original = controller.checkout(submitted);
            assertEquals(ResourcePoolModels.PurchaseState.PENDING, original.join().state());
            assertThrows(IllegalStateException.class, () -> controller.beginPurchase(offer));

            transport.state = state;
            controller.refresh();

            assertEquals(ResourcePoolModels.PurchaseState.PENDING, original.join().state());
            ResourcePoolController.PurchaseIntent next = controller.beginPurchase(offer);
            assertFalse(submitted.purchaseRequestId().equals(next.purchaseRequestId()));
        }
    }

    @Test
    void terminalRefreshResolvesAndFencesAnInFlightCheckout() {
        PendingCheckoutTransport transport = new PendingCheckoutTransport();
        ResourcePoolController controller = controller(new ResourcePoolClient(transport),
                new AtomicReference<>("account"));
        controller.refresh();
        ResourcePoolModels.Offer offer = controller.snapshot().offers().getFirst();
        ResourcePoolController.PurchaseIntent submitted = controller.beginPurchase(offer);

        Async<ResourcePoolController.PurchaseResult> original = controller.checkout(submitted);
        assertFalse(original.isDone());

        controller.refresh();

        assertEquals(ResourcePoolModels.PurchaseState.ACTIVE, original.join().state());
        assertTrue(transport.checkout.isCancelled());
        assertFalse(transport.checkout.complete(checkoutJson(submitted.purchaseRequestId(), transport.purchaseId,
                transport.poolId)));
        assertEquals(ResourcePoolModels.PurchaseState.ACTIVE, original.join().state());
        assertTrue(controller.checkout(submitted).failure() != null);
        assertEquals(1, transport.posts);
        ResourcePoolController.PurchaseIntent next = controller.beginPurchase(offer);
        assertFalse(submitted.purchaseRequestId().equals(next.purchaseRequestId()));
    }

    @Test
    void pendingAndReviewOwnerStatesKeepTheSubmittedOfferBlocked() {
        for (ResourcePoolModels.PurchaseState state : List.of(ResourcePoolModels.PurchaseState.PENDING,
                ResourcePoolModels.PurchaseState.NEEDS_REVIEW)) {
            PurchaseTransport transport = new PurchaseTransport();
            ResourcePoolController controller = controller(new ResourcePoolClient(transport),
                    new AtomicReference<>("account"));
            controller.refresh();
            ResourcePoolModels.Offer offer = controller.snapshot().offers().getFirst();
            controller.checkout(controller.beginPurchase(offer)).join();
            transport.state = state;
            controller.refresh();

            assertThrows(IllegalStateException.class, () -> controller.beginPurchase(offer));
        }
    }

    @Test
    void nonMatchingTerminalOwnerPurchaseCannotReleaseTheLocalIntent() {
        PurchaseTransport wrongRequest = new PurchaseTransport();
        ResourcePoolController requestController = controller(new ResourcePoolClient(wrongRequest),
                new AtomicReference<>("account"));
        requestController.refresh();
        ResourcePoolModels.Offer requestOffer = requestController.snapshot().offers().getFirst();
        requestController.checkout(requestController.beginPurchase(requestOffer)).join();
        wrongRequest.state = ResourcePoolModels.PurchaseState.ACTIVE;
        wrongRequest.listedRequestId = UUID.randomUUID();

        requestController.refresh();

        assertThrows(IllegalStateException.class, () -> requestController.beginPurchase(requestOffer));

        PurchaseTransport wrongOffer = new PurchaseTransport();
        ResourcePoolController offerController = controller(new ResourcePoolClient(wrongOffer),
                new AtomicReference<>("account"));
        offerController.refresh();
        ResourcePoolModels.Offer offer = offerController.snapshot().offers().getFirst();
        ResourcePoolController.PurchaseIntent submitted = offerController.beginPurchase(offer);
        offerController.checkout(submitted).join();
        wrongOffer.state = ResourcePoolModels.PurchaseState.ACTIVE;
        wrongOffer.listedOfferId = "another-offer";

        offerController.refresh();

        assertTrue(offerController.snapshot().message().contains("Offer Identity Does Not Match"));
        assertThrows(IllegalStateException.class, () -> offerController.beginPurchase(offer));
    }

    @Test
    void absentOwnerStatusAfterUnknownCheckoutNeverCreatesAnotherPost() {
        int[] posts = {0};
        ResourcePoolClient client = new ResourcePoolClient((method, path, body) -> {
            if (path.equals("/resource-pools?page=0&size=25")) return Async.completed(emptyPage());
            if (path.equals("/billing/resource-pools/offers")) return Async.completed(offerJson());
            if (path.startsWith("/billing/resource-pools/purchases?")) return Async.completed(emptyPage());
            if (method.equals("POST") && path.equals("/billing/resource-pools/checkout")) {
                posts[0]++;
                return Async.failed(new IllegalStateException("Response Lost"));
            }
            if (method.equals("GET") && path.startsWith("/billing/resource-pools/purchases/")) {
                return Async.failed(new IllegalStateException("Not Found"));
            }
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        });
        ResourcePoolController controller = controller(client, new AtomicReference<>("account"));
        controller.refresh();
        ResourcePoolModels.Offer offer = controller.snapshot().offers().getFirst();
        ResourcePoolController.PurchaseIntent intent = controller.beginPurchase(offer);

        Async<ResourcePoolController.PurchaseResult> first = controller.checkout(intent);
        Async<ResourcePoolController.PurchaseResult> replay = controller.checkout(intent);

        assertSame(first, replay);
        assertTrue(first.isDone());
        assertTrue(first.failure() != null);
        assertEquals(1, posts[0]);
        assertThrows(IllegalStateException.class, () -> controller.beginPurchase(offer));
    }

    @Test
    void accountSwitchFencesMutationBeforeItsSinglePost() {
        AtomicReference<String> account = new AtomicReference<>("first");
        Async<String> refreshed = Async.pending();
        int[] assignments = {0};
        UUID poolId = UUID.randomUUID();
        ResourcePoolClient client = new ResourcePoolClient((method, path, body) -> {
            if (path.endsWith("/refresh")) return refreshed;
            if (path.endsWith("/assign")) {
                assignments[0]++;
                return Async.failed(new AssertionError("Assignment Must Be Fenced"));
            }
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        });
        ResourcePoolController controller = controller(client, account);
        Async<ResourcePoolModels.Progress> result = controller.assign(
                allocation(poolId, ResourcePoolModels.AllocationState.DISABLED, null), "2048", "200", "10240");

        account.set("second");
        refreshed.complete(allocationJson(poolId));

        assertTrue(result.isDone());
        assertTrue(ResourcePoolController.message(result.failure()).contains("Account Changed"));
        assertEquals(0, assignments[0]);
    }

    @Test
    void settledAssignmentAllowsANewAssignmentWithANewRequestIdentity() {
        UUID poolId = UUID.randomUUID();
        List<UUID> posts = new ArrayList<>();
        boolean[] settled = {false};
        ResourcePoolClient client = new ResourcePoolClient((method, path, body) -> {
            if (path.endsWith("/refresh")) return Async.completed(allocationJson(poolId));
            if (method.equals("POST") && path.endsWith("/assign")) {
                UUID requestId = UUID.fromString(field(body, "requestId"));
                posts.add(requestId);
                String operation = operationJson(requestId, field(body, "ramMiB"), field(body, "cpuQuotaPercent"),
                        field(body, "diskMiB"), ResourcePoolModels.OperationState.PENDING);
                return Async.completed("{\"pool\":" + poolJson(poolId) + ",\"allocation\":" + allocationJson(poolId)
                        + ",\"operation\":" + operation + ",\"hosting\":null,\"replayed\":false}");
            }
            if (path.startsWith("/resource-pools/") && path.contains("/operations/")) {
                UUID requestId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
                return Async.completed("{\"operation\":" + operationJson(requestId, "2048", "200", "10240",
                        settled[0] ? ResourcePoolModels.OperationState.SETTLED : ResourcePoolModels.OperationState.PENDING)
                        + ",\"hosting\":null}");
            }
            if (path.equals("/resource-pools?page=0&size=25")) return Async.completed(page(poolJson(poolId)));
            if (path.contains("/drafts?")) return Async.completed(emptyPage());
            if (path.contains("/allocations?")) return Async.completed(page(allocationJson(poolId)));
            if (path.equals("/billing/resource-pools/offers")) return Async.completed("[]");
            if (path.startsWith("/billing/resource-pools/purchases?")) return Async.completed(emptyPage());
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        });
        ResourcePoolController controller = controller(client, new AtomicReference<>("account"));
        ResourcePoolModels.Allocation server = allocation(poolId, ResourcePoolModels.AllocationState.DISABLED, null);
        controller.refresh();

        assertEquals(ResourcePoolModels.OperationState.PENDING,
                controller.assign(server, "2048", "200", "10240").join().operation().state());
        assertTrue(controller.assign(server, "3072", "300", "10240").failure() != null);
        assertEquals(1, posts.size());

        settled[0] = true;
        controller.refresh();
        assertEquals(ResourcePoolModels.OperationState.PENDING,
                controller.assign(server, "3072", "300", "10240").join().operation().state());
        assertEquals(2, posts.size());
        assertFalse(posts.getFirst().equals(posts.getLast()));
    }

    @Test
    void pendingPurchasePollingStopsAtTheBound() {
        PollScheduler scheduler = new PollScheduler();
        int[] purchaseReads = {0};
        ResourcePoolClient client = pollingClient(purchaseReads);
        ResourcePoolController controller = new ResourcePoolController(client, scheduler, () -> "account");

        controller.refresh();
        for (int index = 0; index < 120; index++) {
            assertTrue(scheduler.runPoll());
        }

        assertEquals(121, purchaseReads[0]);
        assertTrue(controller.snapshot().message().contains("Automatic Refresh Paused"));
        assertFalse(scheduler.runPoll());
    }

    @Test
    void unchangedPendingPollDoesNotNotifyTheScreen() {
        PollScheduler scheduler = new PollScheduler();
        int[] purchaseReads = {0};
        ResourcePoolController controller = new ResourcePoolController(pollingClient(purchaseReads), scheduler,
                () -> "account");
        int[] notifications = {0};
        controller.listen(ignored -> notifications[0]++);

        controller.refresh();
        int beforePoll = notifications[0];
        long generation = controller.snapshot().generation();
        assertTrue(scheduler.runPoll());

        assertEquals(2, purchaseReads[0]);
        assertEquals(beforePoll, notifications[0]);
        assertTrue(controller.snapshot().generation() > generation);
    }

    @Test
    void disposalCancelsPendingPolling() {
        PollScheduler scheduler = new PollScheduler();
        int[] purchaseReads = {0};
        ResourcePoolController controller = new ResourcePoolController(pollingClient(purchaseReads), scheduler,
                () -> "account");
        controller.refresh();

        controller.dispose();

        assertFalse(scheduler.runPoll());
        assertEquals(1, purchaseReads[0]);
    }

    private static ResourcePoolController controller(ResourcePoolClient client, AtomicReference<String> account) {
        return new ResourcePoolController(client, new HoldingScheduler(), account::get);
    }

    private static ResourcePoolClient pollingClient(int[] purchaseReads) {
        UUID requestId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        UUID poolId = UUID.randomUUID();
        return new ResourcePoolClient((method, path, body) -> {
            if (path.equals("/resource-pools?page=0&size=25")) return Async.completed(emptyPage());
            if (path.equals("/billing/resource-pools/offers")) return Async.completed("[]");
            if (path.startsWith("/billing/resource-pools/purchases?")) {
                purchaseReads[0]++;
                return Async.completed(page(purchaseJson(requestId, purchaseId, poolId,
                        ResourcePoolModels.PurchaseState.PENDING)));
            }
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        });
    }

    private static ResourcePoolController.Snapshot snapshot(ResourcePoolModels.Pool pool,
                                                              List<ResourcePoolModels.Draft> drafts,
                                                              List<ResourcePoolModels.Allocation> allocations,
                                                              Map<UUID, ResourcePoolModels.Progress> progress) {
        return new ResourcePoolController.Snapshot("account",
                List.of(new ResourcePoolController.PoolView(pool, drafts, allocations, progress)), false, "", 1);
    }

    private static ResourcePoolModels.Pool pool() {
        ResourcePoolModels.Resources zero = new ResourcePoolModels.Resources("0", "0", "0", "0");
        return new ResourcePoolModels.Pool(UUID.randomUUID(), new ResourcePoolModels.Domain("local", "shared"),
                new ResourcePoolModels.Balance(zero, zero, zero, zero));
    }

    private static ResourcePoolModels.Draft draft(UUID poolId, ResourcePoolModels.DraftState state,
                                                   UUID activationRequestId) {
        return new ResourcePoolModels.Draft(UUID.randomUUID(), null, poolId,
                new ResourcePoolModels.Domain("local", "shared"),
                new ResourcePoolModels.DraftMetadata("Server", "game", "profile", Map.of(), Map.of()), "1", state,
                activationRequestId, null, null, null, null, null, null);
    }

    private static ResourcePoolModels.Allocation allocation(UUID poolId, ResourcePoolModels.AllocationState state,
                                                             UUID requestId) {
        ResourcePoolModels.Compute zero = new ResourcePoolModels.Compute("0", "0");
        return new ResourcePoolModels.Allocation("server", poolId, "node", "1", zero, zero, zero,
                new ResourcePoolModels.Storage("0", "0"), state, requestId, null);
    }

    private static ResourcePoolModels.Progress progress(ResourcePoolModels.OperationState state) {
        UUID requestId = UUID.randomUUID();
        ResourcePoolModels.Compute zero = new ResourcePoolModels.Compute("0", "0");
        return new ResourcePoolModels.Progress(new ResourcePoolModels.Operation(requestId, "server", "1",
                ResourcePoolModels.Action.ASSIGN, zero, zero, new ResourcePoolModels.Storage("0", "0"), state,
                null, null, "node"), null);
    }

    private static ResourcePoolModels.PurchaseStatus purchase(ResourcePoolModels.PurchaseState state) {
        return new ResourcePoolModels.PurchaseStatus(UUID.randomUUID(), UUID.randomUUID(), "offer", UUID.randomUUID(),
                state, null, null, null, "2026-09-07T00:00:00Z", "2026-09-07T00:00:00Z");
    }

    private static String emptyPage() {
        return "{\"items\":[],\"page\":0,\"size\":25,\"hasMore\":false}";
    }

    private static String page(String item) {
        return "{\"items\":[" + item + "],\"page\":0,\"size\":25,\"hasMore\":false}";
    }

    private static String page(int page, boolean hasMore, String... items) {
        return page(page, hasMore, List.of(items));
    }

    private static String page(int page, boolean hasMore, List<String> items) {
        return "{\"items\":[" + String.join(",", items) + "],\"page\":" + page
                + ",\"size\":25,\"hasMore\":" + hasMore + "}";
    }

    private static String offerJson() {
        return "[{\"id\":\"offer\",\"label\":\"Starter\",\"planId\":\"plan\",\"planName\":\"Starter Plan\","
                + "\"location\":\"riyadh\",\"locationLabel\":\"Riyadh\",\"cpuClass\":\"shared\","
                + "\"cpuClassLabel\":\"Shared CPU\",\"ramMiB\":\"2048\",\"cpuQuotaPercent\":\"200\","
                + "\"diskMiB\":\"10240\",\"backupMiB\":\"0\",\"priceCents\":\"1000\","
                + "\"priceCurrency\":\"USD\",\"billingPeriodLabel\":\"Monthly\"}]";
    }

    private static String purchaseJson(UUID requestId, UUID purchaseId, UUID poolId,
                                       ResourcePoolModels.PurchaseState state) {
        return purchaseJson(requestId, purchaseId, poolId, "offer", state);
    }

    private static String purchaseJson(UUID requestId, UUID purchaseId, UUID poolId, String offerId,
                                       ResourcePoolModels.PurchaseState state) {
        return "{\"purchaseRequestId\":\"" + requestId + "\",\"purchaseId\":\"" + purchaseId
                + "\",\"offerId\":\"" + offerId + "\",\"poolId\":\"" + poolId + "\",\"status\":\"" + state
                + "\",\"checkoutUrl\":null,\"reviewReason\":null,\"failureReason\":null,"
                + "\"createdAt\":\"2026-09-07T00:00:00Z\",\"updatedAt\":\"2026-09-07T00:00:00Z\"}";
    }

    private static String allocationJson(UUID poolId) {
        return allocationJson(poolId, "server");
    }

    private static String allocationJson(UUID poolId, String serverId) {
        return "{\"serverId\":\"" + serverId + "\",\"poolId\":\"" + poolId + "\",\"nodeId\":\"node\","
                + "\"revision\":\"1\",\"desired\":{\"ramMiB\":\"0\",\"cpuQuotaPercent\":\"0\"},"
                + "\"reserved\":{\"ramMiB\":\"0\",\"cpuQuotaPercent\":\"0\"},"
                + "\"effective\":{\"ramMiB\":\"0\",\"cpuQuotaPercent\":\"0\"},"
                + "\"retained\":{\"diskMiB\":\"0\",\"backupMiB\":\"0\"},\"state\":\"DISABLED\","
                + "\"currentRequestId\":null,\"providerRevision\":\"2\"}";
    }

    private static String operationJson(UUID requestId, String ram, String cpu, String disk,
                                        ResourcePoolModels.OperationState state) {
        return "{\"requestId\":\"" + requestId + "\",\"serverId\":\"server\",\"allocationRevision\":\"1\","
                + "\"action\":\"ASSIGN\",\"desired\":{\"ramMiB\":\"" + ram + "\",\"cpuQuotaPercent\":\"" + cpu + "\"},"
                + "\"reserved\":{\"ramMiB\":\"" + ram + "\",\"cpuQuotaPercent\":\"" + cpu + "\"},"
                + "\"retained\":{\"diskMiB\":\"" + disk + "\",\"backupMiB\":\"0\"},\"state\":\"" + state + "\","
                + "\"hostingRevision\":null,\"providerRevision\":\"2\",\"nodeId\":\"node\"}";
    }

    private static String poolJson(UUID poolId) {
        String resources = "{\"ramMiB\":\"0\",\"cpuQuotaPercent\":\"0\",\"diskMiB\":\"0\",\"backupMiB\":\"0\"}";
        return "{\"id\":\"" + poolId + "\",\"domain\":{\"location\":\"local\",\"cpuClass\":\"shared\"},"
                + "\"balance\":{\"entitled\":" + resources + ",\"committed\":" + resources
                + ",\"available\":" + resources + ",\"deficit\":" + resources + "}}";
    }

    private static String draftJson(UUID poolId, UUID draftId, String name) {
        return "{\"id\":\"" + draftId + "\",\"serverId\":null,\"poolId\":\"" + poolId
                + "\",\"domain\":{\"location\":\"local\",\"cpuClass\":\"shared\"},\"metadata\":{"
                + "\"name\":\"" + name + "\",\"gameId\":\"game\",\"profileId\":\"profile\","
                + "\"settings\":{},\"initialFiles\":{}},\"revision\":\"1\",\"state\":\"DRAFT\","
                + "\"activationRequestId\":null,\"nodeId\":null,\"installer\":null,\"runtime\":null,"
                + "\"reserved\":null,\"retained\":null,\"reason\":null}";
    }

    private static String checkoutJson(UUID requestId, UUID purchaseId, UUID poolId) {
        return "{\"url\":\"https://whop.com/checkout/resource\",\"purchaseRequestId\":\"" + requestId
                + "\",\"purchaseId\":\"" + purchaseId + "\",\"offerId\":\"offer\",\"poolId\":\""
                + poolId + "\",\"status\":\"PENDING\"}";
    }

    private static int pageOf(String path) {
        int start = path.indexOf("page=") + 5;
        int end = path.indexOf('&', start);
        return Integer.parseInt(path.substring(start, end < 0 ? path.length() : end));
    }

    private static String field(String json, String name) {
        String prefix = "\"" + name + "\":\"";
        int start = json.indexOf(prefix) + prefix.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static final class PagingTransport implements ResourcePoolClient.Transport {
        private final List<UUID> pools = new ArrayList<>();
        private final List<UUID> drafts = new ArrayList<>();
        private final List<UUID> purchaseRequests = new ArrayList<>();
        private final List<UUID> purchases = new ArrayList<>();

        private PagingTransport() {
            for (int index = 0; index < 26; index++) {
                pools.add(UUID.randomUUID());
                drafts.add(UUID.randomUUID());
                purchaseRequests.add(UUID.randomUUID());
                purchases.add(UUID.randomUUID());
            }
        }

        @Override
        public Async<String> request(String method, String path, String body) {
            int page = path.contains("page=") ? pageOf(path) : 0;
            if (path.startsWith("/billing/resource-pools/purchases?")) {
                int start = page * 25;
                List<String> items = new ArrayList<>();
                for (int index = start; index < Math.min(start + 25, purchases.size()); index++) {
                    items.add(purchaseJson(purchaseRequests.get(index), purchases.get(index), pools.getLast(),
                            ResourcePoolModels.PurchaseState.CANCELLED));
                }
                return Async.completed(page(page, start + 25 < purchases.size(), items));
            }
            if (path.equals("/billing/resource-pools/offers")) return Async.completed("[]");
            if (path.startsWith("/resource-pools?") && !path.contains("/drafts") && !path.contains("/allocations")) {
                int start = page * 25;
                List<String> items = new ArrayList<>();
                for (int index = start; index < Math.min(start + 25, pools.size()); index++) {
                    items.add(poolJson(pools.get(index)));
                }
                return Async.completed(page(page, start + 25 < pools.size(), items));
            }
            if (path.contains("/drafts?")) {
                UUID poolId = UUID.fromString(path.split("/")[2]);
                if (!poolId.equals(pools.getLast())) return Async.completed(page(page, false, List.of()));
                int start = page * 25;
                List<String> items = new ArrayList<>();
                for (int index = start; index < Math.min(start + 25, drafts.size()); index++) {
                    items.add(draftJson(poolId, drafts.get(index), "Draft " + index));
                }
                return Async.completed(page(page, start + 25 < drafts.size(), items));
            }
            if (path.contains("/allocations?")) {
                UUID poolId = UUID.fromString(path.split("/")[2]);
                if (!poolId.equals(pools.getLast())) return Async.completed(page(page, false, List.of()));
                int start = page * 25;
                List<String> items = new ArrayList<>();
                for (int index = start; index < Math.min(start + 25, 26); index++) {
                    items.add(allocationJson(poolId, "server-" + index));
                }
                return Async.completed(page(page, start + 25 < 26, items));
            }
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        }
    }

    private static final class ContinuationTransport implements ResourcePoolClient.Transport {
        private final UUID poolId = UUID.randomUUID();
        private final Async<String> continuation = Async.pending();

        @Override
        public Async<String> request(String method, String path, String body) {
            if (path.equals("/resource-pools?page=0&size=25")) {
                return Async.completed(page(0, true, poolJson(poolId)));
            }
            if (path.equals("/resource-pools?page=1&size=25")) return continuation;
            if (path.contains("/drafts?") || path.contains("/allocations?")) return Async.completed(emptyPage());
            if (path.equals("/billing/resource-pools/offers")) return Async.completed("[]");
            if (path.startsWith("/billing/resource-pools/purchases?")) return Async.completed(emptyPage());
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        }
    }

    private static final class PurchaseTransport implements ResourcePoolClient.Transport {
        private final UUID purchaseId = UUID.randomUUID();
        private final UUID poolId = UUID.randomUUID();
        private UUID requestId;
        private UUID listedRequestId;
        private String listedOfferId = "offer";
        private ResourcePoolModels.PurchaseState state = ResourcePoolModels.PurchaseState.PENDING;

        @Override
        public Async<String> request(String method, String path, String body) {
            if (path.equals("/resource-pools?page=0&size=25")) return Async.completed(emptyPage());
            if (path.equals("/billing/resource-pools/offers")) return Async.completed(offerJson());
            if (path.startsWith("/billing/resource-pools/purchases?")) {
                return requestId == null ? Async.completed(emptyPage())
                        : Async.completed(page(purchaseJson(listedRequestId == null ? requestId : listedRequestId,
                        purchaseId, poolId, listedOfferId, state)));
            }
            if (method.equals("POST") && path.equals("/billing/resource-pools/checkout")) {
                requestId = UUID.fromString(field(body, "purchaseRequestId"));
                return Async.completed(checkoutJson(requestId, purchaseId, poolId));
            }
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        }
    }

    private static final class PendingCheckoutTransport implements ResourcePoolClient.Transport {
        private final UUID purchaseId = UUID.randomUUID();
        private final UUID poolId = UUID.randomUUID();
        private final Async<String> checkout = Async.pending();
        private UUID requestId;
        private int posts;

        @Override
        public Async<String> request(String method, String path, String body) {
            if (path.equals("/resource-pools?page=0&size=25")) return Async.completed(emptyPage());
            if (path.equals("/billing/resource-pools/offers")) return Async.completed(offerJson());
            if (path.startsWith("/billing/resource-pools/purchases?")) {
                return requestId == null ? Async.completed(emptyPage()) : Async.completed(page(purchaseJson(
                        requestId, purchaseId, poolId, ResourcePoolModels.PurchaseState.ACTIVE)));
            }
            if (method.equals("POST") && path.equals("/billing/resource-pools/checkout")) {
                posts++;
                requestId = UUID.fromString(field(body, "purchaseRequestId"));
                return checkout;
            }
            return Async.failed(new AssertionError("Unexpected Request " + method + " " + path));
        }
    }

    private static final class HoldingScheduler implements TaskScheduler {
        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public ScheduledTask schedule(Runnable task, Duration delay) {
            return new ScheduledTask() {
                private boolean cancelled;

                @Override
                public boolean cancel() {
                    cancelled = true;
                    return true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }

        @Override
        public ScheduledTask scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
            return schedule(task, initialDelay);
        }
    }

    private static final class PollScheduler implements TaskScheduler {
        private final List<Scheduled> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public ScheduledTask schedule(Runnable task, Duration delay) {
            Scheduled scheduled = new Scheduled(task, delay);
            tasks.add(scheduled);
            return scheduled;
        }

        @Override
        public ScheduledTask scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
            return schedule(task, initialDelay);
        }

        private boolean runPoll() {
            Scheduled next = tasks.stream().filter(task -> !task.cancelled && task.delay.equals(Duration.ofSeconds(3)))
                    .findFirst().orElse(null);
            if (next == null) return false;
            next.cancelled = true;
            next.task.run();
            return true;
        }

        private static final class Scheduled implements ScheduledTask {
            private final Runnable task;
            private final Duration delay;
            private boolean cancelled;

            private Scheduled(Runnable task, Duration delay) {
                this.task = task;
                this.delay = delay;
            }

            @Override
            public boolean cancel() {
                cancelled = true;
                return true;
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        }
    }
}
