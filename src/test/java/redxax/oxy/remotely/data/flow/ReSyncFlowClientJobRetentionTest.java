package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.RemotelyClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientJobRetentionTest {

    @Test
    void activeJobsSurviveTerminalAgePruning() throws Exception {
        ClientFixture fixture = client();
        ReSyncFlowClient client = fixture.client();
        try {
            track(client, job("active", "running"));
            track(client, job("terminal", "cancelled"));

            invokePrune(client, System.currentTimeMillis() + 16 * 60 * 1000L);

            assertTrue(jobs(client).containsKey("active"));
            assertFalse(jobs(client).containsKey("terminal"));
            assertFalse(terminalNotifications(client).contains("terminal"));
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void multipleExpiredTerminalJobsArePrunedWithTheirReplayIdentities() throws Exception {
        ClientFixture fixture = client();
        ReSyncFlowClient client = fixture.client();
        try {
            track(client, job("expired-a", "cancelled"));
            track(client, job("expired-b", "cancelled"));
            track(client, job("active", "running"));

            invokePrune(client, System.currentTimeMillis() + 16 * 60 * 1000L);

            assertFalse(jobs(client).containsKey("expired-a"));
            assertFalse(jobs(client).containsKey("expired-b"));
            assertFalse(terminalNotifications(client).contains("expired-a"));
            assertFalse(terminalNotifications(client).contains("expired-b"));
            assertTrue(jobs(client).containsKey("active"));
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void terminalJobsAndReplayIdentitiesHaveDeterministicCountBounds() throws Exception {
        ClientFixture fixture = client();
        ReSyncFlowClient client = fixture.client();
        try {
            for (int index = 0; index < 513; index++) {
                track(client, job("terminal-" + index, "cancelled"));
            }

            assertEquals(512, jobs(client).size());
            assertEquals(513, terminalNotifications(client).size());
            track(client, job("terminal-0", "cancelled"));
            assertEquals(512, jobs(client).size());
            assertEquals(513, terminalNotifications(client).size());
        } finally {
            fixture.shutdown();
        }
    }

    private static ClientFixture client() throws Exception {
        Probe probe = new Probe();
        OpenTransport transport = new OpenTransport();
        ReSyncFlowClient client = new ReSyncFlowClient("server", transport, probe,
            new ReSyncCatalogPublicationCache());
        return new ClientFixture(client, probe.manager);
    }

    private static JsonObject job(String id, String status) {
        JsonObject job = new JsonObject();
        job.addProperty("jobId", id);
        job.addProperty("status", status);
        return job;
    }

    private static void track(ReSyncFlowClient client, JsonObject job) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("trackGenericJob", JsonObject.class);
        method.setAccessible(true);
        method.invoke(client, job);
    }

    private static void invokePrune(ReSyncFlowClient client, long now) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("pruneJobTrackingLocked", long.class);
        method.setAccessible(true);
        synchronized (lock(client)) {
            method.invoke(client, now);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, JsonObject> jobs(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("jobs");
        field.setAccessible(true);
        return (Map<String, JsonObject>) field.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> terminalNotifications(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("terminalJobNotifications");
        field.setAccessible(true);
        return (Set<String>) field.get(client);
    }

    private static Object lock(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("jobRetentionLock");
        field.setAccessible(true);
        return field.get(client);
    }

    private record ClientFixture(ReSyncFlowClient client, FlowManager manager) {
        private void shutdown() {
            client.shutdown();
            manager.shutdown();
        }
    }

    private static final class Probe extends RemotelyClient {
        private final FlowManager manager;

        private Probe() {
            super(null);
            manager = new FlowManager(this, null) {
                @Override
                public ServerConnectionToken captureServerConnectionToken(String serverId, ReSyncFlowClient source) {
                    return new ServerConnectionToken(serverId, source, 1L);
                }

                @Override
                public boolean isCurrentServerConnection(ServerConnectionToken token) {
                    return token != null && token.source() != null && token.generation() == 1L;
                }
            };
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }
    }

    private static final class OpenTransport implements ReSyncFrameTransport {
        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
        }

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }
}
