package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncCollaborationClientTest {
    @Test
    void distinguishesCursorMotionFromResourceActivityChanges() {
        ReSyncCollaborationClient client = new ReSyncCollaborationClient(new Gson(), "remotely-device");

        assertTrue(client.applySnapshot(snapshot("session", "remote-device", "flow", "welcome", 0.1, 0.2, 1L)));
        long initialRevision = client.activityRevision();
        assertFalse(client.applySnapshot(snapshot("session", "remote-device", "flow", "welcome", 0.8, 0.7, 2L)));
        assertEquals(initialRevision, client.activityRevision());
        assertEquals(2L, client.snapshot().getFirst().updatedAt());
        assertTrue(client.applySnapshot(snapshot("session", "remote-device", "gui", "menu", 0.8, 0.7, 3L)));
        assertTrue(client.activityRevision() > initialRevision);
    }

    @Test
    void doesNotTrustClientIdsWithoutASharedServerIdentity() {
        ReSyncCollaborationClient client = new ReSyncCollaborationClient(new Gson(), "remotely-device");
        client.applySnapshot(snapshot("mine", "theirs", "remotely-device", "flow", "welcome", 0.1, 0.2));

        assertFalse(client.isSelf(client.snapshot().getFirst()));
        assertFalse(client.isOwnSession("theirs"));

        client.applySnapshot(snapshot("theirs", "theirs", "remotely-device", "flow", "welcome", 0.1, 0.2));
        assertTrue(client.snapshot().isEmpty());
        assertTrue(client.isOwnSession("theirs"));
    }

    @Test
    void keepsDifferentSessionsVisibleForTheSameAccount() {
        ReSyncCollaborationClient client = new ReSyncCollaborationClient(new Gson(), "remotely-device");
        client.applySnapshot("""
            {"selfSessionId":"direct","collaborators":[
              {"sessionId":"direct","clientId":"remotely-device","identity":{"subjectId":"user","displayName":"Alex","avatar":"","source":"restudio"},"active":true},
              {"sessionId":"bridge","clientId":"bridge:player:remotely-device","identity":{"subjectId":"user","displayName":"Alex","avatar":"","source":"restudio"},"active":true},
              {"sessionId":"other","clientId":"other-device","identity":{"subjectId":"other","displayName":"Sam","avatar":"","source":"restudio"},"active":true}
            ]}
            """);

        assertFalse(client.isOwnSession("bridge"));
        assertEquals(List.of("bridge", "other"), client.snapshot().stream().map(ReSyncCollaborationClient.Presence::sessionId).toList());
    }

    @Test
    void usesTheServersCompleteOwnedSessionSetWithoutListingIt() {
        ReSyncCollaborationClient client = new ReSyncCollaborationClient(new Gson(), "remotely-device");
        client.applySnapshot("""
            {
              "selfSessionId":"direct",
              "selfIdentity":{"subjectId":"user","displayName":"Alex","avatar":"","source":"restudio"},
              "selfSessionIds":["direct","bridge"],
              "collaborators":[
                {"sessionId":"other","clientId":"other-device","identity":{"subjectId":"other","displayName":"Sam","avatar":"","source":"restudio"},"active":true}
              ]
            }
            """);

        assertTrue(client.isOwnSession("direct"));
        assertTrue(client.isOwnSession("bridge"));
        assertEquals(List.of("other"), client.snapshot().stream().map(ReSyncCollaborationClient.Presence::sessionId).toList());
    }

    @Test
    void ignoresOlderPresenceSnapshotsAndAcceptsNewConnectionRevisions() {
        ReSyncCollaborationClient client = new ReSyncCollaborationClient(new Gson(), "remotely-app");
        assertTrue(client.applySnapshot("""
            {"selfSessionId":"app","revision":5,"collaborators":[
              {"sessionId":"player","clientId":"bridge:player:remotely-mod","identity":{"subjectId":"player","displayName":"Alex","avatar":"","source":"minecraft"},"active":true}
            ]}
            """));
        assertFalse(client.applySnapshot("""
            {"selfSessionId":"app","revision":4,"collaborators":[]}
            """));
        assertEquals(List.of("player"), client.snapshot().stream().map(ReSyncCollaborationClient.Presence::sessionId).toList());

        client.connectionLost();
        assertTrue(client.applySnapshot("""
            {"selfSessionId":"app","revision":1,"collaborators":[
              {"sessionId":"player-next","clientId":"bridge:player:remotely-mod","identity":{"subjectId":"player","displayName":"Alex","avatar":"","source":"minecraft"},"active":true}
            ]}
            """));
        assertEquals(List.of("player-next"), client.snapshot().stream().map(ReSyncCollaborationClient.Presence::sessionId).toList());
    }

    @Test
    void doesNotInferResourceOwnershipFromAccountIdentity() {
        ReSyncCollaborationClient client = new ReSyncCollaborationClient(new Gson(), "remotely-device");
        client.identify(new ReSyncCollaborationClient.Identity("user", "Alex", "", "restudio"));
        ReSyncCollaborationClient.ResourceChange change = new ReSyncCollaborationClient.ResourceChange(
            "flow", "welcome", "bridge", new ReSyncCollaborationClient.Identity("user", "Alex", "", "minecraft"), 1L, false);

        assertFalse(client.isOwnChange(change));
    }

    @Test
    void dispatchesValidChatMessagesOnly() {
        ReSyncCollaborationClient client = new ReSyncCollaborationClient(new Gson(), "remotely-device");
        List<ReSyncCollaborationClient.Message> messages = new ArrayList<>();
        client.addMessageListener(messages::add);

        assertTrue(client.applyMessage("""
            {"id":"message","authorSessionId":"remote","author":{"subjectId":"user","displayName":"Alex","avatar":"","source":"restudio"},"resourceType":"flow","resourceId":"welcome","color":-1,"message":"Hello","sentAt":1}
            """));
        assertFalse(client.applyMessage("""
            {"id":"","authorSessionId":"remote","message":"Hello"}
            """));
        assertEquals(1, messages.size());
        assertEquals("Hello", messages.getFirst().message());
    }

    @Test
    void replaysRecentMessagesWhenTheStudioListenerAttachesLate() {
        ReSyncCollaborationClient client = new ReSyncCollaborationClient(new Gson(), "remotely-device");
        assertTrue(client.applyMessage("""
            {"id":"message","authorSessionId":"remote","author":{"subjectId":"user","displayName":"Alex","avatar":"","source":"restudio"},"resourceType":"flow","resourceId":"welcome","color":-1,"message":"Hello","sentAt":1}
            """));

        List<ReSyncCollaborationClient.Message> messages = new ArrayList<>();
        client.addMessageListener(messages::add);

        assertEquals(1, messages.size());
        assertEquals("Hello", messages.getFirst().message());
    }

    private String snapshot(String sessionId, String clientId, String type, String resourceId, double x, double y) {
        return snapshot("", sessionId, clientId, type, resourceId, x, y);
    }

    private String snapshot(String sessionId, String clientId, String type, String resourceId, double x, double y, long updatedAt) {
        return snapshot("", sessionId, clientId, type, resourceId, x, y, updatedAt);
    }

    private String snapshot(String selfSessionId, String sessionId, String clientId, String type, String resourceId, double x, double y) {
        return snapshot(selfSessionId, sessionId, clientId, type, resourceId, x, y, 1L);
    }

    private String snapshot(String selfSessionId, String sessionId, String clientId, String type, String resourceId,
                            double x, double y, long updatedAt) {
        return """
            {"selfSessionId":"%s","collaborators":[{"sessionId":"%s","clientId":"%s","identity":{"subjectId":"user","displayName":"Alex","avatar":"","source":"restudio"},"resourceType":"%s","resourceId":"%s","viewId":"Studio","x":%s,"y":%s,"active":true,"color":-1,"updatedAt":%s}]}
            """.formatted(selfSessionId, sessionId, clientId, type, resourceId, x, y, updatedAt);
    }
}
