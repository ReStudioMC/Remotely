package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.RemotelyClient;
import restudio.resync.flow.identity.ServerId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReSyncFlowClientResourceEventAdmissionTest {
    private static final String SERVER_ID = ServerId.deterministic("resource-event-admission").canonicalText();

    @Test
    void rejectsStaleResourceEventsBeforeCollaborationCacheAdmission() throws Exception {
        Probe probe = new Probe();
        Transport transport = new Transport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, probe);
        try {
            FlowManagerTestConnection.install(probe.manager, SERVER_ID, client, transport);
            enableLegacyReadAdmission(client);
            recordRevision(client, ReSyncResourceType.GUI, "menu", 5L);

            invokeResourceEvent(client, resourceEvent("gui", "menu", 4L, "remote-session"), false);

            assertNull(client.collaboration().resourceChange("gui", "menu"));
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void rejectsUnsupportedResourceEventsBeforeCollaborationCacheAdmission() throws Exception {
        Probe probe = new Probe();
        Transport transport = new Transport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, probe);
        try {
            FlowManagerTestConnection.install(probe.manager, SERVER_ID, client, transport);
            enableLegacyReadAdmission(client);

            invokeResourceEvent(client, resourceEvent("unsupported", "menu", 1L, "remote-session"), false);

            assertNull(client.collaboration().resourceChange("unsupported", "menu"));
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void admitsCurrentAuthoredResourceEventsAfterValidation() throws Exception {
        Probe probe = new Probe();
        Transport transport = new Transport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, probe);
        try {
            FlowManagerTestConnection.install(probe.manager, SERVER_ID, client, transport);
            enableLegacyReadAdmission(client);

            invokeResourceEvent(client, resourceEvent("gui", "menu", 1L, "remote-session"), false);

            ReSyncCollaborationClient.ResourceChange change = client.collaboration().resourceChange("gui", "menu");
            assertNotNull(change);
            assertEquals("remote-session", change.authorSessionId());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    private static void enableLegacyReadAdmission(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("legacyCompatibilityProven");
        field.setAccessible(true);
        field.setBoolean(client, true);
    }

    private static void recordRevision(ReSyncFlowClient client, ReSyncResourceType type, String id, long revision)
        throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("recordResourceRevision", ReSyncResourceType.class,
            String.class, long.class);
        method.setAccessible(true);
        method.invoke(client, type, id, revision);
    }

    private static void invokeResourceEvent(ReSyncFlowClient client, JsonObject event, boolean deleted) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("handleResourceEvent", ByteBuffer.class,
            boolean.class, int.class);
        method.setAccessible(true);
        method.invoke(client, ByteBuffer.wrap(new Gson().toJson(event).getBytes(StandardCharsets.UTF_8)), deleted, 1);
    }

    private static JsonObject resourceEvent(String type, String id, long revision, String authorSessionId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", id);
        payload.addProperty("title", id);
        payload.addProperty("rows", 1);
        payload.addProperty("resourceRevision", revision);

        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("resourceId", id);
        event.addProperty("payload", payload.toString());
        event.addProperty("authorSessionId", authorSessionId);
        JsonObject author = new JsonObject();
        author.addProperty("subjectId", "remote");
        author.addProperty("displayName", "Remote");
        author.addProperty("avatar", "");
        author.addProperty("source", "restudio");
        event.add("author", author);
        event.addProperty("changedAt", System.currentTimeMillis());
        event.addProperty("authorityEpoch", 0L);
        return event;
    }

    private static final class Probe extends RemotelyClient {
        private final FlowManager manager;

        private Probe() {
            super(null);
            manager = new FlowManager(this, null);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
        }
    }

    private static final class Transport implements ReSyncFrameTransport {
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
