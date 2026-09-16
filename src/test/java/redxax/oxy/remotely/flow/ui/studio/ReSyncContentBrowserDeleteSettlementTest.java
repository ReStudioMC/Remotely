package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.FlowManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReSyncContentBrowserDeleteSettlementTest {
    @Test
    void batchCommitContainsOnlyAuthoritativelyDeletedResources() {
        Map<String, CompletableFuture<FlowManager.ResourceDeleteResult>> settlements = new LinkedHashMap<>();
        settlements.put("flow\u0000saved", CompletableFuture.completedFuture(
            new FlowManager.ResourceDeleteResult("flow", "saved", true, "")));
        settlements.put("flow\u0000retained", CompletableFuture.completedFuture(
            new FlowManager.ResourceDeleteResult("flow", "retained", false, "Resource Delete Rejected")));

        assertEquals(Set.of("flow\u0000saved"), ReSyncContentBrowserWidget.settledDeleteKeys(settlements));
    }

    @Test
    void failedBatchRetainsEveryResource() {
        Map<String, CompletableFuture<FlowManager.ResourceDeleteResult>> settlements = Map.of(
            "gui\u0000menu", CompletableFuture.completedFuture(
                new FlowManager.ResourceDeleteResult("gui", "menu", false, "ReSync Offline")),
            "tab\u0000players", CompletableFuture.completedFuture(
                new FlowManager.ResourceDeleteResult("tab", "players", false, "Resource Delete Timed Out"))
        );

        assertEquals(Set.of(), ReSyncContentBrowserWidget.settledDeleteKeys(settlements));
    }

    @Test
    void exceptionalAndIncompleteSettlementsAreRetained() {
        CompletableFuture<FlowManager.ResourceDeleteResult> exceptional = new CompletableFuture<>();
        exceptional.completeExceptionally(new IllegalStateException("connection changed"));
        Map<String, CompletableFuture<FlowManager.ResourceDeleteResult>> settlements = Map.of(
            "gui\u0000menu", exceptional,
            "flow\u0000pending", new CompletableFuture<>()
        );

        assertEquals(Set.of(), ReSyncContentBrowserWidget.settledDeleteKeys(settlements));
    }
}
