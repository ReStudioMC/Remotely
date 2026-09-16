package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AggregateCreationReplayTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("ca5ca5ca-5ca5-4ca5-8ca5-ca5ca5ca5ca5"));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");
    private static final UUID MUTATION = UUID.fromString("d15d15d1-5d15-4d15-8d15-d15d15d15d15");
    private static final UUID REQUEST = UUID.fromString("e26e26e2-6e26-4e26-8e26-e26e26e26e26");

    @Test
    void metadataReplayRequiresExactCurrentAuthorityIdentityAndPayload(@TempDir Path temporaryDirectory) throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), new ScriptedReSyncTransport(), null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("catalog-publication-cache.json"))));
        try {
            ResourceDocument<Map<String, Object>> document = metadataDocument(4L, MUTATION, "Created");
            JsonObject payload = new Gson().toJsonTree(document.payload()).getAsJsonObject();
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 3L);
            ReSyncResourceRevisionReconciler.Decision applied = client.resourceRevisionReconciler().apply(
                new ReSyncResourceRevisionReconciler.ResourceResult(SERVER.canonicalText(),
                    ReSyncResourceType.PROJECT_METADATA.typeId(), SERVER.canonicalText(), document.revision(),
                    document.mutationId().toString(), document.payloadHash().canonicalText(), false, payload, 3L));
            assertEquals(ReSyncResourceRevisionReconciler.Outcome.APPLIED, applied.outcome());

            Method exact = ReSyncFlowClient.class.getDeclaredMethod("exactAuthoritativeResourceDocument",
                ReSyncResourceType.class, ResourceDocument.class, long.class);
            exact.setAccessible(true);
            assertTrue((Boolean) exact.invoke(client, ReSyncResourceType.PROJECT_METADATA, document, 3L));
            assertFalse((Boolean) exact.invoke(client, ReSyncResourceType.PROJECT_METADATA,
                metadataDocument(4L, UUID.fromString("f37f37f3-7f37-4f37-8f37-f37f37f37f37"), "Created"), 3L));
            assertFalse((Boolean) exact.invoke(client, ReSyncResourceType.PROJECT_METADATA,
                metadataDocument(4L, MUTATION, "Changed"), 3L));
            assertFalse((Boolean) exact.invoke(client, ReSyncResourceType.PROJECT_METADATA, document, 4L));
        } finally {
            ReSyncFlowClientTestHarness.closeClient(client);
        }
    }

    @Test
    void aggregateCoreFailureDetachesTheStableResumableMutationForReplay() {
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startSilentResumableExact(
            SERVER.canonicalText(), ReSyncResourceType.FLOW, "callback-loss", "Flow", REQUEST, MUTATION);
        assertNotNull(ticket);
        assertTrue(DesignerSaveNotifications.isPending(ticket));
        assertEquals(REQUEST.toString(), DesignerSaveNotifications.detachResumableMutation(SERVER.canonicalText(),
            ReSyncResourceType.FLOW, "callback-loss", MUTATION.toString()));
        assertFalse(DesignerSaveNotifications.isPending(ticket));
    }

    private static ResourceDocument<Map<String, Object>> metadataDocument(long revision, UUID mutation,
                                                                           String displayName) {
        ServerResourceLocator locator = new ServerResourceLocator(SERVER,
            ContractRef.of(OWNER, ResourceTypeId.of(ReSyncResourceType.PROJECT_METADATA.typeId())),
            SERVER.canonicalText());
        Map<String, Object> payload = Map.of(
            "serverId", SERVER.canonicalText(),
            "folders", List.of(),
            "resources", List.of(Map.of("type", "flow", "id", "callback-loss", "displayName", displayName,
                "path", "Blueprints/callback-loss.json", "sortOrder", 0)),
            "installedBundles", List.of(),
            "openDocuments", List.of(),
            "selectedResourceKey", ""
        );
        return ResourceDocument.live(locator, revision, mutation, ResourcePayloadCodecs.json().canonicalize(payload),
            ResourceActivationState.ACTIVE, "server");
    }
}
