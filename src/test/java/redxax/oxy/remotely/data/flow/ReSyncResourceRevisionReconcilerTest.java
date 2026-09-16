package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.cache.GraphDraft;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncResourceRevisionReconcilerTest {
    @Test
    void acceptsNewerRevisionAndRejectsStaleReply() {
        ReSyncResourceRevisionReconciler reconciler = new ReSyncResourceRevisionReconciler();
        JsonObject payload = new JsonObject();
        payload.addProperty("id", "flow");

        ReSyncResourceRevisionReconciler.ResourceResult newer = result(2, "mutation-2", "hash-2", false, payload);
        ReSyncResourceRevisionReconciler.ResourceResult older = result(1, "mutation-1", "hash-1", false, new JsonObject());

        assertTrue(reconciler.apply(newer).accepted());
        assertEquals(ReSyncResourceRevisionReconciler.Outcome.STALE, reconciler.apply(older).outcome());
        assertEquals(2, reconciler.revision("server", "flow", "flow"));
        assertEquals(payload, reconciler.get("server", "flow", "flow").payload());
    }

    @Test
    void acceptsIdempotentDuplicateAndKeepsNewerTombstone() {
        ReSyncResourceRevisionReconciler reconciler = new ReSyncResourceRevisionReconciler();
        JsonObject payload = new JsonObject();
        payload.addProperty("id", "flow");
        ReSyncResourceRevisionReconciler.ResourceResult live = result(3, "mutation-3", "hash-3", false, payload);
        ReSyncResourceRevisionReconciler.ResourceResult tombstone = result(4, "mutation-4", "hash-4", true, null);

        assertEquals(ReSyncResourceRevisionReconciler.Outcome.APPLIED, reconciler.apply(live).outcome());
        assertEquals(ReSyncResourceRevisionReconciler.Outcome.DUPLICATE, reconciler.apply(result(3, "mutation-3", "hash-3", false, payload)).outcome());
        assertEquals(ReSyncResourceRevisionReconciler.Outcome.APPLIED, reconciler.apply(tombstone).outcome());
        assertTrue(reconciler.get("server", "flow", "flow").deleted());
    }

    @Test
    void acceptsLowerRevisionAfterAuthorityEpochTransitionAndRejectsOldEpoch() {
        ReSyncResourceRevisionReconciler reconciler = new ReSyncResourceRevisionReconciler();
        JsonObject restoredPayload = new JsonObject();
        restoredPayload.addProperty("id", "flow");

        assertEquals(ReSyncResourceRevisionReconciler.Outcome.APPLIED,
            reconciler.apply(result(9, "mutation-9", "hash-9", false, new JsonObject(), 4)).outcome());
        assertEquals(ReSyncResourceRevisionReconciler.Outcome.APPLIED,
            reconciler.apply(result(1, "mutation-1", "hash-1", false, restoredPayload, 5)).outcome());
        assertEquals(ReSyncResourceRevisionReconciler.Outcome.STALE_EPOCH,
            reconciler.apply(result(10, "mutation-10", "hash-10", false, new JsonObject(), 4)).outcome());
        assertEquals(1, reconciler.revision("server", "flow", "flow"));
        assertEquals(5, reconciler.authorityEpoch("server"));
    }

    @Test
    void exposesGraphDraftContractWithoutRawCacheMutation() {
        ReSyncResourceRevisionReconciler reconciler = new ReSyncResourceRevisionReconciler();
        ServerResourceLocator resource = new ServerResourceLocator(
            new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("flow")), "typed");
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1,
            new CatalogBinding(1, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64))),
            Set.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
        GraphResourceState state = GraphResourceState.live(resource, 1,
            UUID.fromString("66666666-6666-4666-8666-666666666666"), new ContentHash("c".repeat(64)),
            new ContentHash("d".repeat(64)), graph, ResourceActivationState.ACTIVE);

        GraphDraft draft = GraphDraft.from(state).withMutationId(
            UUID.fromString("77777777-7777-4777-8777-777777777777"));
        reconciler.putGraphDraft(draft);
        assertEquals(draft, reconciler.graphDraft(resource).orElseThrow());
        assertEquals(draft, reconciler.graphDrafts().get(resource));
        assertTrue(reconciler.observeAuthorityEpoch(resource.serverId().canonicalText(), 1).advanced());
        assertTrue(reconciler.observeAuthorityEpoch(resource.serverId().canonicalText(), 2).advanced());
        assertEquals(draft, reconciler.graphDraft(resource).orElseThrow());
        reconciler.clearServer(resource.serverId().canonicalText());
        assertEquals(2L, reconciler.authorityEpoch(resource.serverId().canonicalText()));
        assertTrue(reconciler.graphDrafts().isEmpty());
        assertTrue(reconciler.graphStates().isEmpty());
    }

    private static ReSyncResourceRevisionReconciler.ResourceResult result(long revision, String mutationId, String hash,
                                                                            boolean deleted, JsonObject payload) {
        return result(revision, mutationId, hash, deleted, payload, 1L);
    }

    private static ReSyncResourceRevisionReconciler.ResourceResult result(long revision, String mutationId, String hash,
                                                                            boolean deleted, JsonObject payload, long authorityEpoch) {
        return new ReSyncResourceRevisionReconciler.ResourceResult("server", "flow", "flow", revision, mutationId, hash, deleted, payload,
            authorityEpoch);
    }
}
