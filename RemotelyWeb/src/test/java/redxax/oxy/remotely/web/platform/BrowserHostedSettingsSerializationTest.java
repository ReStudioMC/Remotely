package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserHostedSettingsSerializationTest {
    @Test
    void permissionCatalogRetainsCategoriesKeysAndDescriptions() {
        var permissions = BrowserRemotelyServerApi.systemPermissions(BrowserJson.object("{\"permissions\":{\"control\":{\"description\":\"Server control\",\"keys\":{\"console\":\"Send commands\"}}}}"));

        assertEquals("Server control", permissions.permissions.get("control").description);
        assertEquals("Send commands", permissions.permissions.get("control").keys.get("console"));
    }

    @Test
    void userSearchRetainsCanonicalIdentity() {
        var user = BrowserRemotelyServerApi.userInfo(BrowserJson.object("{\"id\":\"user-id\",\"username\":\"alex\",\"displayName\":\"Alex\",\"avatarUrl\":\"https://cdn/avatar.png\"}"));

        assertEquals("user-id", user.id);
        assertEquals("alex", user.username);
        assertEquals("Alex", user.displayName);
        assertEquals("https://cdn/avatar.png", user.avatarUrl);
    }

    @Test
    void backupOperationPayloadsRetainTerminalProofAndDiscoveryIdentity() {
        String digest = "a".repeat(64);
        var job = BrowserRemotelyServerApi.capabilityJob(BrowserJson.object("""
                {"id":"request-1","serverId":"server-1","operation":"backup.restore","status":"COMPLETED","progress":100,
                "result":{"outcome":"verified","requestId":"request-1","backupId":"backup-1","providerState":"succeeded","active":false,"truncate":true,"restoreStartup":true,
                "createdAt":100,"updatedAt":200,"settledAt":200,"digestVersion":1,"canonicalDigest":"%s"},"error":null,"createdAt":"start","completedAt":"end"}
                """.formatted(digest)));
        var creation = BrowserRemotelyServerApi.backupCreateObservation(BrowserJson.object("""
                {"requestId":"request-2","backupId":"backup-2","state":"succeeded","backup":{"uuid":"backup-2","name":"Snapshot"},"replaySafe":true}
                """));
        var discovery = BrowserRemotelyServerApi.backupRestoreDiscovery(BrowserJson.object("""
                {"requestId":"request-1","backupId":"backup-1","state":"pending","active":true,"truncate":false,"restoreStartup":false,"createdAt":100,"updatedAt":150}
                """));

        assertEquals("backup.restore", job.operation);
        assertEquals(digest, job.result.canonicalDigest);
        assertEquals(false, job.result.active);
        assertEquals(true, job.result.truncate);
        assertEquals(true, job.result.restoreStartup);
        assertEquals(Long.valueOf(100L), job.result.createdAt);
        assertEquals(Long.valueOf(200L), job.result.updatedAt);
        assertEquals(Long.valueOf(200L), job.result.settledAt);
        assertEquals("backup-2", creation.backup.uuid);
        assertEquals(true, creation.replaySafe);
        assertEquals("pending", discovery.state);
        assertEquals(true, discovery.active);
        assertEquals(false, discovery.truncate);
        assertEquals(false, discovery.restoreStartup);
        assertEquals(Long.valueOf(100L), discovery.createdAt);
        assertEquals(Long.valueOf(150L), discovery.updatedAt);
    }

    @Test
    void browserStorageSurfacesReadQuotaAndRemovalFailuresAndExposesReadbackMismatch() {
        TestStorage unreadable = new TestStorage();
        unreadable.readAllowed = false;
        BrowserRemotelyConfigStore unreadableStore = new BrowserRemotelyConfigStore(unreadable);
        assertThrows(IllegalStateException.class, () -> unreadableStore.get("backup.pending", "missing"));

        TestStorage quota = new TestStorage();
        quota.writeAllowed = false;
        BrowserRemotelyConfigStore quotaStore = new BrowserRemotelyConfigStore(quota);
        assertThrows(IllegalStateException.class, () -> quotaStore.set("backup.pending", "value"));

        TestStorage removal = new TestStorage();
        removal.eraseAllowed = false;
        BrowserRemotelyConfigStore removalStore = new BrowserRemotelyConfigStore(removal);
        assertThrows(IllegalStateException.class, () -> removalStore.remove("backup.pending"));

        TestStorage discarded = new TestStorage();
        discarded.discardWrites = true;
        BrowserRemotelyConfigStore readbackStore = new BrowserRemotelyConfigStore(discarded);
        readbackStore.set("backup.pending", "value");
        assertEquals("missing", readbackStore.get("backup.pending", "missing"));
    }

    private static final class TestStorage implements BrowserRemotelyConfigStore.Storage {
        private final Map<String, String> values = new LinkedHashMap<>();
        private boolean readAllowed = true;
        private boolean writeAllowed = true;
        private boolean eraseAllowed = true;
        private boolean discardWrites;

        @Override
        public String read(String key) {
            if (!readAllowed) throw new IllegalStateException("read failed");
            return values.get(key);
        }

        @Override
        public boolean write(String key, String value) {
            if (!writeAllowed) return false;
            if (!discardWrites) values.put(key, value);
            return true;
        }

        @Override
        public boolean erase(String key) {
            if (!eraseAllowed) return false;
            values.remove(key);
            return true;
        }
    }
}
