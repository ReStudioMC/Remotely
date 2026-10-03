package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserDemoLeaseTest {
    @Test
    void preservesExactGenerationAndUsesTheEarlierDeadline() {
        BrowserLaunchSession.DemoLease lease = BrowserLaunchSession.DemoLease.from(response("9007199254740993", "2030-01-01T00:05:00Z"));

        assertEquals("9007199254740993", lease.generation());
        assertEquals("2030-01-01T00:05:00Z", lease.deadline());
        assertTrue(lease.matches(response("9007199254740993", "2030-01-01T00:05:00Z")));
        assertFalse(lease.matches(response("9007199254740992", "2030-01-01T00:05:00Z")));
        assertEquals("2030-01-01T01:00:00Z", BrowserLaunchSession.DemoLease.from(
                response("1", "2030-01-01T02:00:00Z")).deadline());
    }

    @Test
    void lateAcknowledgementCannotShortenIdleDeadlineOrReplaceFilePolicy() {
        BrowserLaunchSession.DemoLease current = BrowserLaunchSession.DemoLease.from(response("1", "2030-01-01T00:10:00Z"));
        JsonObject old = response("1", "2030-01-01T00:05:00Z");
        old.remove("editablePaths");

        BrowserLaunchSession.DemoLease updated = current.updated(BrowserLaunchSession.DemoLease.from(old), false);

        assertEquals("2030-01-01T00:10:00Z", updated.idleExpiresAt());
        assertEquals(Set.of("/server.properties"), updated.editablePaths());
        assertThrows(UnsupportedOperationException.class, () -> updated.editablePaths().add("/secret"));
    }

    @Test
    void rejectsAReplacementLeaseAndMalformedDeadlines() {
        BrowserLaunchSession.DemoLease current = BrowserLaunchSession.DemoLease.from(response("1", "2030-01-01T00:10:00Z"));
        BrowserLaunchSession.DemoLease replacement = BrowserLaunchSession.DemoLease.from(response("2", "2030-01-01T00:10:00Z"));

        assertThrows(IllegalArgumentException.class, () -> current.updated(replacement, false));
        assertThrows(RuntimeException.class, () -> BrowserLaunchSession.DemoLease.from(response("1", "invalid")));
    }

    @Test
    void newerConfigurationCanShortenTheDeadlineWhileOldConfigurationCannotReplacePolicy() {
        JsonObject original = response("1", "2030-01-01T00:10:00Z");
        original.addProperty("configurationRevision", "5");
        BrowserLaunchSession.DemoLease current = BrowserLaunchSession.DemoLease.from(original);
        JsonObject changed = response("1", "2030-01-01T00:02:00Z");
        changed.addProperty("configurationRevision", "6");
        changed.remove("editablePaths");

        BrowserLaunchSession.DemoLease updated = current.updated(BrowserLaunchSession.DemoLease.from(changed), false);

        assertEquals("2030-01-01T00:02:00Z", updated.deadline());
        assertEquals("6", updated.configurationRevision());
        assertEquals(Set.of("/server.properties"), updated.editablePaths());
        original.addProperty("idleExpiresAt", "2030-01-01T00:30:00Z");
        original.remove("editablePaths");
        assertSame(updated, updated.updated(BrowserLaunchSession.DemoLease.from(original), true));
        changed.addProperty("idleExpiresAt", "2030-01-01T00:01:00Z");
        assertEquals("2030-01-01T00:02:00Z", updated.updated(BrowserLaunchSession.DemoLease.from(changed), false).deadline());
    }

    @Test
    void onlyAcceptsCanonicalPositiveGenerationsAndNonnegativeConfigurationRevisions() {
        for (String generation : new String[]{"0", "-1", "+1", "01", " 1", "1.0", "9223372036854775808"}) {
            assertThrows(IllegalArgumentException.class, () -> BrowserLaunchSession.DemoLease.from(response(generation, "2030-01-01T00:05:00Z")));
        }
        for (String revision : new String[]{"-1", "+0", "00", " 0", "0.0", "9223372036854775808"}) {
            JsonObject invalid = response("1", "2030-01-01T00:05:00Z");
            invalid.addProperty("configurationRevision", revision);
            assertThrows(IllegalArgumentException.class, () -> BrowserLaunchSession.DemoLease.from(invalid));
        }
        assertEquals("0", BrowserLaunchSession.DemoLease.from(response("1", "2030-01-01T00:05:00Z")).configurationRevision());
    }

    @Test
    void resettingIsAnAcknowledgedTerminalState() {
        assertTrue(BrowserLaunchSession.demoTerminalStatus("RESETTING"));
        assertTrue(BrowserLaunchSession.demoTerminalStatus("EXPIRING"));
        assertTrue(BrowserLaunchSession.demoTerminalStatus("ENDED"));
        assertFalse(BrowserLaunchSession.demoTerminalStatus("ACTIVE"));
        assertFalse(BrowserLaunchSession.demoTerminalStatus("UNAVAILABLE"));
    }

    @Test
    void demoAuthorityChangesAcrossLeasesButNotTicketRenewal() {
        BrowserLaunchSession.Metadata first = metadata("lease-1", "ticket-1");

        assertTrue(first.sameAuthority(metadata("lease-1", "ticket-2")));
        assertFalse(first.sameAuthority(metadata("lease-2", "ticket-2")));
    }

    private static BrowserLaunchSession.Metadata metadata(String leaseId, String ticket) {
        return new BrowserLaunchSession.Metadata(leaseId, ticket, "browser", Set.of("remotely.demo"), "node", "expiry",
                "owner", "", "", "", "", "");
    }

    private static JsonObject response(String generation, String idle) {
        JsonObject response = BrowserJson.object("""
                {"leaseId":"lease","generation":"1","configurationRevision":"0","leaseExpiresAt":"2030-01-01T01:00:00Z",
                 "idleExpiresAt":"2030-01-01T00:05:00Z","status":"ACTIVE","editablePaths":["server.properties"]}
                """);
        response.addProperty("generation", generation);
        response.addProperty("idleExpiresAt", idle);
        return response;
    }
}
