package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BrowserSessionAuthorityTest {
    @Test
    void browserSessionsHaveDistinctStableClientIdentities() {
        var metadata = metadata("grant", "ticket", "subject", "audience", "node", Set.of("resync"));
        var first = new BrowserReSyncIdentityProvider(metadata);
        var second = new BrowserReSyncIdentityProvider(metadata);
        assertEquals(first.clientId("server"), first.clientId("server"));
        assertNotEquals(first.clientId("server"), second.clientId("server"));
        assertNotEquals(first.clientId("server"), first.clientId("other-server"));
    }

    @Test
    void renewalPreservesConnectionAuthority() {
        var original = metadata("grant-one", "ticket-one", "subject", "audience", "node", Set.of("resync"));
        var renewed = metadata("grant-two", "ticket-two", "subject", "audience", "node", Set.of("resync"));
        assertTrue(original.sameAuthority(renewed));
    }

    @Test
    void accountScopeAudienceAndNodeChangesReplaceAuthority() {
        var original = metadata("grant", "ticket", "subject", "audience", "node", Set.of("resync"));
        assertFalse(original.sameAuthority(metadata("grant", "ticket", "other", "audience", "node", Set.of("resync"))));
        assertFalse(original.sameAuthority(metadata("grant", "ticket", "subject", "other", "node", Set.of("resync"))));
        assertFalse(original.sameAuthority(metadata("grant", "ticket", "subject", "audience", "other", Set.of("resync"))));
        assertFalse(original.sameAuthority(metadata("grant", "ticket", "subject", "audience", "node", Set.of())));
        assertFalse(original.sameAuthority(null));
    }

    private BrowserLaunchSession.Metadata metadata(String grant, String ticket, String subject, String audience,
                                                   String node, Set<String> scopes) {
        return new BrowserLaunchSession.Metadata(grant, ticket, audience, scopes, node, "expiry", subject,
            "username", "name", "email", "avatar", "session");
    }
}
