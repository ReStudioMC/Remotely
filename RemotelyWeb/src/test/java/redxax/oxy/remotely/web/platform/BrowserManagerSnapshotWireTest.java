package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserManagerSnapshotWireTest {
    @Test
    void decodesOneManagerSnapshotWithoutAdmittingUnlistedServerState() {
        BrowserRemotelyServerApi.ManagerSnapshot snapshot = BrowserRemotelyServerApi.managerSnapshot(BrowserJson.object("""
                {"servers":[{"identifier":"server","name":"Server"}],
                 "networks":[{"id":"network","name":"Network","members":["server"]}],
                 "statuses":{"server":{"currentState":"running","suspended":false,"installing":false},
                             "hidden":{"currentState":"running"}}}
                """));

        assertEquals("server", snapshot.instances().getFirst().identifier);
        assertEquals("network", snapshot.networks().getFirst().id());
        assertEquals("running", snapshot.statuses().get("server").currentState);
        assertFalse(snapshot.statuses().get("server").installing);
        assertFalse(snapshot.statuses().containsKey("hidden"));
    }

    @Test
    void rejectsAnIncompleteManagerSnapshot() {
        assertThrows(IllegalArgumentException.class, () -> BrowserRemotelyServerApi.managerSnapshot(
                BrowserJson.object("{\"servers\":[],\"networks\":[]}")));
    }

    @Test
    void keepsLastKnownStateThroughOneFailedStatusReadAndDropsRemovedServers() {
        BrowserRemotelyServerApi.ManagerSnapshot known = BrowserRemotelyServerApi.managerSnapshot(BrowserJson.object("""
                {"servers":[{"identifier":"server"}],"networks":[],
                 "statuses":{"server":{"currentState":"running"}}}
                """));
        BrowserRemotelyServerApi.ManagerSnapshot missing = BrowserRemotelyServerApi.managerSnapshot(BrowserJson.object("""
                {"servers":[{"identifier":"server"}],"networks":[],"statuses":{}}
                """));
        BrowserRemotelyServerApi.ManagerSnapshot removed = BrowserRemotelyServerApi.managerSnapshot(BrowserJson.object("""
                {"servers":[],"networks":[],"statuses":{}}
                """));

        BrowserRemotelyServerApi.ManagerSnapshot retained = BrowserRemotelyServerApi.retainManagerStatuses(known, missing);
        assertEquals("running", retained.statuses().get("server").currentState);
        assertTrue(BrowserRemotelyServerApi.retainManagerStatuses(retained, removed).statuses().isEmpty());
    }
}
