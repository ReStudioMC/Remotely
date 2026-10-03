package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.Async;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserCatalogRecoveryTest {
    @Test
    void alreadyFailedCatalogRetriesWithoutReopeningConfiguration() {
        Map<String, Async<?>> requests = new LinkedHashMap<>();
        String key = "loader:NEOFORGE:1.21.1";
        Async<List<String>> failed = BrowserServerScreenHost.residentCatalog(requests, key, () -> Async.failed(new IllegalStateException("Offline")));
        assertTrue(failed.isDone());
        assertFalse(requests.containsKey(key));

        Async<List<String>> recovered = BrowserServerScreenHost.residentCatalog(requests, key, () -> Async.completed(List.of("21.1.251", "21.1.99")));
        assertEquals(List.of("21.1.251", "21.1.99"), recovered.value());
        Async<List<String>> resident = BrowserServerScreenHost.residentCatalog(requests, key, () -> Async.failed(new AssertionError("Successful Catalog Must Remain Resident")));
        assertEquals(recovered.value(), resident.value());
    }

    @Test
    void failedOrCanceledSharedRequestCanRecover() {
        for (boolean cancel : new boolean[]{false, true}) {
            Map<String, Async<?>> requests = new LinkedHashMap<>();
            Async<List<String>> pending = Async.pending();
            Async<List<String>> consumer = BrowserServerScreenHost.residentCatalog(requests, "loader:FORGE:1.20.1", () -> pending);
            if (cancel) pending.cancel();
            else pending.fail(new IllegalStateException("Offline"));
            assertTrue(consumer.isDone());
            assertFalse(requests.containsKey("loader:FORGE:1.20.1"));

            Async<List<String>> recovered = BrowserServerScreenHost.residentCatalog(requests, "loader:FORGE:1.20.1", () -> Async.completed(List.of("47.4.23")));
            assertEquals(List.of("47.4.23"), recovered.value());
        }
    }

    @Test
    void cancelingOneConsumerKeepsSharedRequestAndOtherConsumersAlive() {
        Map<String, Async<?>> requests = new LinkedHashMap<>();
        Async<List<String>> pending = Async.pending();
        Async<List<String>> canceled = BrowserServerScreenHost.residentCatalog(requests, "loader:NEOFORGE:1.21.1", () -> pending);
        Async<List<String>> survivor = BrowserServerScreenHost.residentCatalog(requests, "loader:NEOFORGE:1.21.1", () -> Async.failed(new AssertionError("Pending Request Must Be Reused")));
        canceled.cancel();
        assertFalse(pending.isCancelled());
        pending.complete(List.of("21.1.251"));

        assertEquals(List.of("21.1.251"), survivor.value());
        Async<List<String>> resident = BrowserServerScreenHost.residentCatalog(requests, "loader:NEOFORGE:1.21.1", () -> Async.failed(new AssertionError("Completed Request Must Be Reused")));
        assertEquals(survivor.value(), resident.value());
    }
}
