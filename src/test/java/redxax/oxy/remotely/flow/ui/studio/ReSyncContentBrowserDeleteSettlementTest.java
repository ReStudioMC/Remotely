package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.FlowManager;
import restudio.rescreen.platform.Async;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReSyncContentBrowserDeleteSettlementTest {
    @Test
    void batchCommitContainsOnlyAuthoritativelyDeletedResources() {
        Map<String, Async<FlowManager.ResourceDeleteResult>> settlements = new LinkedHashMap<>();
        settlements.put("flow\u0000saved", Async.completed(
            new FlowManager.ResourceDeleteResult("flow", "saved", true, "")));
        settlements.put("flow\u0000retained", Async.completed(
            new FlowManager.ResourceDeleteResult("flow", "retained", false, "Resource Delete Rejected")));

        assertEquals(Set.of("flow\u0000saved"), ReSyncContentBrowserWidget.settledDeleteKeys(settlements));
    }

    @Test
    void failedBatchRetainsEveryResource() {
        Map<String, Async<FlowManager.ResourceDeleteResult>> settlements = Map.of(
            "gui\u0000menu", Async.completed(
                new FlowManager.ResourceDeleteResult("gui", "menu", false, "ReSync Offline")),
            "tab\u0000players", Async.completed(
                new FlowManager.ResourceDeleteResult("tab", "players", false, "Resource Delete Timed Out"))
        );

        assertEquals(Set.of(), ReSyncContentBrowserWidget.settledDeleteKeys(settlements));
    }

    @Test
    void exceptionalAndIncompleteSettlementsAreRetained() {
        Async<FlowManager.ResourceDeleteResult> exceptional = Async.pending();
        exceptional.fail(new IllegalStateException("connection changed"));
        Map<String, Async<FlowManager.ResourceDeleteResult>> settlements = Map.of(
            "gui\u0000menu", exceptional,
            "flow\u0000pending", Async.pending()
        );

        assertEquals(Set.of(), ReSyncContentBrowserWidget.settledDeleteKeys(settlements));
    }
}
