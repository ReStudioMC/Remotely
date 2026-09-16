package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.ReSyncCollaborationClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioScreenResourceChangeNotificationTest {
    @Test
    void ignoresSystemResourceChangesWithoutAnAuthorSession() {
        ReSyncCollaborationClient collaboration = new ReSyncCollaborationClient(new Gson(), "client");
        ReSyncCollaborationClient.ResourceChange change = new ReSyncCollaborationClient.ResourceChange(
            "gui", "menu", "", null, System.currentTimeMillis(), false);
        collaboration.applyResourceChange(change);

        assertFalse(StudioScreen.shouldRenderRemoteResourceChange(collaboration, change, System.currentTimeMillis()));
        assertNotNull(collaboration.resourceChange("gui", "menu"));
    }

    @Test
    void showsRecentAuthoredRemoteChanges() {
        ReSyncCollaborationClient collaboration = new ReSyncCollaborationClient(new Gson(), "client");
        ReSyncCollaborationClient.ResourceChange change = new ReSyncCollaborationClient.ResourceChange(
            "gui", "menu", "remote-session", identity("remote"), System.currentTimeMillis() - 100L, false);

        assertTrue(StudioScreen.shouldRenderRemoteResourceChange(collaboration, change, System.currentTimeMillis()));
    }

    @Test
    void suppressesLinkedOwnSessionEchoes() {
        ReSyncCollaborationClient collaboration = new ReSyncCollaborationClient(new Gson(), "client");
        collaboration.applySnapshot("""
            {"selfSessionId":"direct","selfSessionIds":["direct","bridge"],"collaborators":[]}
            """);
        assertTrue(collaboration.isOwnSession("bridge"));
        ReSyncCollaborationClient.ResourceChange change = new ReSyncCollaborationClient.ResourceChange(
            "gui", "menu", "bridge", identity("client"), System.currentTimeMillis() - 100L, false);

        assertFalse(StudioScreen.shouldRenderRemoteResourceChange(collaboration, change, System.currentTimeMillis()));
    }

    @Test
    void suppressesExpiredAuthoredChanges() {
        ReSyncCollaborationClient collaboration = new ReSyncCollaborationClient(new Gson(), "client");
        ReSyncCollaborationClient.ResourceChange change = new ReSyncCollaborationClient.ResourceChange(
            "gui", "menu", "remote-session", identity("remote"), System.currentTimeMillis() - 8001L, false);

        assertFalse(StudioScreen.shouldRenderRemoteResourceChange(collaboration, change, System.currentTimeMillis()));
    }

    private static ReSyncCollaborationClient.Identity identity(String subjectId) {
        return new ReSyncCollaborationClient.Identity(subjectId, subjectId, "", "restudio");
    }
}
