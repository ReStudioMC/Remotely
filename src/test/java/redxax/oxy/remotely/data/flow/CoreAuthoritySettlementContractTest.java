package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreAuthoritySettlementContractTest {
    private static final Path FLOW_MANAGER = Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java");
    private static final Path FLOW_CLIENT = Path.of("src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java");

    @Test
    void coreListCompletionPublishesOnlyLiveGenerationOwnedMembership() throws IOException {
        String source = source(FLOW_MANAGER);
        String attach = methodBody(source, "private boolean attachCoreGraphListener(");
        String complete = methodBody(source, "private void onCoreGraphListComplete(");

        assertTrue(attach.contains("flowClient.subscribeCoreGraphList("));
        assertTrue(complete.contains("!snapshot.authoritative()"));
        assertTrue(complete.contains("ownsCoreGraphOwnerToken(owner)"));
        assertTrue(complete.contains("snapshot.liveResources()"));
        assertTrue(complete.contains("publishTypedMembership(token, snapshot.type(), liveIds)"));
        assertTrue(complete.contains("projectMetadataStamp(owner.serverId()) == before"));
        assertFalse(complete.contains("snapshot.resources().stream()"));
    }

    @Test
    void readOnlyOpenSettlesAsPreciseGenerationBoundIncompatibility() throws IOException {
        String source = source(FLOW_CLIENT);
        String consume = methodBody(source, "private void consumeCoreOpenIntent(");
        String request = methodBody(source, "public void requestResource(ReSyncResourceType type, String id, boolean openWhenReceived)");
        String current = methodBody(source, "private CoreOpenIncompatibility currentCoreOpenIncompatibility(");

        assertTrue(consume.contains("if (projection.readOnly())"));
        assertTrue(consume.contains("projection.rejectionReason()"));
        assertTrue(consume.contains("removeOpenIntentIfGeneration("));
        assertTrue(consume.contains("notifyCoreGraphOpenIncompatibility("));
        assertTrue(request.contains("currentCoreOpenIncompatibility(type, id) != null"));
        assertTrue(current.contains("incompatibility.transportGeneration() != currentOpenIntentGeneration()"));
        assertTrue(current.contains("activeAuthoringSnapshot()"));
    }

    @Test
    void studioActivationPeekIsTypedAndSideEffectFree() throws IOException {
        String manager = source(FLOW_MANAGER);
        String peek = methodBody(manager, "public ReSyncFlowClient.CoreGraphActivationOutcome peekCoreGraphActivation(");
        String session = methodBody(manager, "public Optional<CoreGraphEditorSession> peekCoreGraphEditorSession(");
        String hydrate = methodBody(manager, "public boolean requestCoreGraphHydration(");
        String client = source(FLOW_CLIENT);

        assertTrue(client.contains("PENDING,\n        LIVE,\n        READ_ONLY,\n        INCOMPATIBLE,\n        MISSING"));
        assertTrue(peek.contains("flowClient.peekCoreGraphActivation(type, id)"));
        assertFalse(peek.contains("hydrateCoreGraphProjection("));
        assertFalse(session.contains("hydrateCoreGraphProjection("));
        assertFalse(session.contains("reconcileCoreGraphSession("));
        assertTrue(hydrate.contains("hydrateCoreGraphProjection(serverId, type, id)"));
    }

    @Test
    void coreDeleteUsesAuthoritativeIdentityWithoutEditorPermissionAndPropagatesDispatch() throws IOException {
        String manager = methodBody(source(FLOW_MANAGER),
            "public boolean deleteGraph(String serverId, ReSyncResourceType type, String flowId)");
        String client = methodBody(source(FLOW_CLIENT),
            "private boolean sendCoreGraphDelete(ReSyncResourceType type, String id)");

        assertTrue(manager.contains("if (baseline == null)"));
        assertFalse(manager.contains("baseline.canSave()"));
        assertTrue(manager.contains("return coreFlowClient.sendCoreResourceDelete(type, flowId)"));
        assertTrue(client.contains("long expectedRevision = authoritativeResourceRevision(type, id)"));
        assertTrue(client.contains("new ResourceDeleteRequest(resource, expectedRevision, mutationId)"));
        assertTrue(client.contains("graphResources().readOnly(resource)"));
    }

    @Test
    void duplicateRediscoverySettlesLocallyWithoutGlobalInvalidationAndTombstonesCleanUp() throws IOException {
        String source = source(FLOW_MANAGER);
        String transition = methodBody(source, "private void onCoreGraphResourceTransition(");
        String confirm = methodBody(source, "private void confirmResourceDeletedNow(");

        assertTrue(transition.indexOf("source.coreGraphSessionProjected(transition.resource())")
            < transition.indexOf("transition.status() == GraphResourceCache.Status.DUPLICATE"));
        assertTrue(transition.contains("authorityBefore.equals(coreGraphUiProjection.snapshot("));
        assertTrue(transition.contains("&& stableAuthorityAndPresentation"));
        assertTrue(transition.indexOf("transition.status() == GraphResourceCache.Status.DUPLICATE")
            < transition.indexOf("invalidateProjectCatalog(serverId)"));
        assertTrue(transition.contains("confirmResourceDeletedNow(serverId, transition.type(), transition.resource().id())"));
        assertTrue(confirm.contains("recordTypedMembershipTombstone(serverId, type, id)"));
        assertTrue(confirm.contains("flowStore.remove(serverId, type, id)"));
        assertTrue(confirm.contains("coreGraphEditorSessions.remove(sessionKey, session)"));
        assertTrue(confirm.contains("!removedMetadata && !membershipChanged && !removedSession"));
    }

    private static String source(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open, index + 1);
            }
        }
        throw new IllegalStateException(signature);
    }
}
