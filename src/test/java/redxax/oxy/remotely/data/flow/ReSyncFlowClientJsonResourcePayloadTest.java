package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReSyncFlowClientJsonResourcePayloadTest {
    @Test
    void typedResourceSaveAcceptsValidNonCanonicalJsonObjects() {
        JsonObject resource = new JsonObject();
        resource.addProperty("id", "main");
        resource.addProperty("displayName", "Main MOTD");
        String serialized = ReSyncResourceType.MOTD_PROFILE.serialize(resource);

        assertThrows(IllegalArgumentException.class, () -> JsonValue.parse(serialized));

        Map<String, Object> payload = ReSyncFlowClient.jsonObjectPayload(serialized);
        assertEquals("main", payload.get("id"));
        assertEquals("Main MOTD", payload.get("displayName"));
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        assertEquals("{\"displayName\":\"Main MOTD\",\"id\":\"main\"}",
            ResourcePayloadCodecs.json().canonicalInput(canonical.value()));
    }

    @Test
    void typedResourceSaveStillRejectsNonObjectJson() {
        assertThrows(IllegalArgumentException.class, () -> ReSyncFlowClient.jsonObjectPayload("[\"main\"]"));
    }

    @Test
    void resourcePreparationWorkerIsOrderedAndOffCallerThread() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient("78787878-7878-4787-8787-787878787878",
            null, null, null, null);
        String callerThread = Thread.currentThread().getName();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        List<String> workers = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        try {
            assertTrue(client.submitResourcePreparation(() -> {
                workers.add(Thread.currentThread().getName());
                order.add("first");
                firstStarted.countDown();
                await(releaseFirst);
                completed.countDown();
            }));
            assertTrue(client.submitResourcePreparation(() -> {
                workers.add(Thread.currentThread().getName());
                order.add("second");
                completed.countDown();
            }));
            assertTrue(firstStarted.await(2L, TimeUnit.SECONDS));
            assertEquals(List.of("first"), order);
            releaseFirst.countDown();
            assertTrue(completed.await(2L, TimeUnit.SECONDS));
            assertEquals(List.of("first", "second"), order);
            assertEquals(workers.getFirst(), workers.getLast());
            assertNotEquals(callerThread, workers.getFirst());
            assertEquals("ReSyncFlow-ResourcePreparation", workers.getFirst());
        } finally {
            releaseFirst.countDown();
            client.shutdown();
        }
    }

    @Test
    void projectMetadataAcknowledgementRequiresAnExactQueuedSave() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient("79797979-7979-4797-8797-797979797979",
            null, null, null, null);
        try {
            Method pending = ReSyncFlowClient.class.getDeclaredMethod("legacyResourceSavePending",
                ReSyncResourceType.class, String.class, String.class);
            pending.setAccessible(true);

            assertFalse((boolean) pending.invoke(client, ReSyncResourceType.PROJECT_METADATA, "project", ""));
            assertFalse((boolean) pending.invoke(client, ReSyncResourceType.PROJECT_METADATA, "project",
                "89898989-8989-4898-8898-898989898989"));
        } finally {
            client.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
