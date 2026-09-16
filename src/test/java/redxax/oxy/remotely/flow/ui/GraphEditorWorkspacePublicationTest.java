package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.flow.data.FlowWorkspaceDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorWorkspacePublicationTest {
    @Test
    void immediateAndPeriodicWorkspacePublicationRequireCatalogAuthority() {
        assertFalse(GraphEditorScreen.allowsCatalogWorkspacePublication(
            ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION));
        assertFalse(GraphEditorScreen.allowsCatalogWorkspacePublication(
            ReSyncFlowClient.CatalogAuthority.UNAVAILABLE));
        assertTrue(GraphEditorScreen.allowsCatalogWorkspacePublication(
            ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION));
        assertFalse(GraphEditorScreen.allowsCatalogWorkspacePublication(
            ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY));
    }

    @Test
    void rejectedPublicationDoesNotRegisterPendingOperation() {
        Map<String, String> pending = new LinkedHashMap<>();

        assertDoesNotThrow(() -> assertFalse(GraphEditorScreen.registerWorkspacePublication(pending, () -> null, id -> id)));
        assertTrue(pending.isEmpty());

        assertDoesNotThrow(() -> assertFalse(GraphEditorScreen.registerWorkspacePublication(
            pending, () -> {
                throw new IllegalStateException("rejected");
            }, id -> id)));
        assertTrue(pending.isEmpty());
    }

    @Test
    void workspacePublicationRunsOnlyForAChangedMutationVersionAfterTheDebounce() {
        assertFalse(GraphEditorScreen.workspacePublicationDue(4L, 4L, 10_000L, 0L));
        assertFalse(GraphEditorScreen.workspacePublicationDue(5L, 4L, 10_034L, 10_000L));
        assertTrue(GraphEditorScreen.workspacePublicationDue(5L, 4L, 10_035L, 10_000L));
        assertTrue(GraphEditorScreen.workspacePublicationDue(7L, 5L, 10_100L, 10_035L));
    }

    @Test
    void workspacePublicationFreezesGraphOwnershipAndBoundsRenderDelivery() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        int currentDocumentStart = source.indexOf("private JsonObject currentWorkspaceDocument()");
        int safeDocumentStart = source.indexOf("private JsonObject safeCurrentWorkspaceDocument()", currentDocumentStart);
        String currentDocument = source.substring(currentDocumentStart, safeDocumentStart);

        assertTrue(source.contains("new AsyncTaskWorker(1, 2, 16)"));
        assertTrue(source.contains("workspaceSnapshotFrozen = true;"));
        assertTrue(source.contains("FlowWorkspaceDocument.snapshot(job.graph())"));
        assertTrue(source.contains("offerWorkspaceSnapshotResult(result);"));
        assertTrue(source.contains("return new GraphSnapshot(stable.document(), selection);"));
        assertTrue(source.contains("stable.mutationVersion() == workspaceMutationVersion"));
        assertTrue(source.contains("handled < WORKSPACE_UPDATE_FRAME_LIMIT && System.nanoTime() < deadline"));
        assertTrue(source.contains("workspaceUpdates.size() >= WORKSPACE_UPDATE_LIMIT"));
        assertFalse(currentDocument.contains("FlowWorkspaceDocument.fromGraph"));
        assertFalse(currentDocument.contains("collaborationDocument()"));
        assertTrue(source.contains("return history != null && history.isDirty((current, saved) ->"));
        assertTrue(source.contains("workspaceGraphSaveRequest = new WorkspaceGraphSaveRequest"));
        assertTrue(source.contains("protected record SaveTicketIdentity("));
        assertTrue(source.contains("private DesignerSaveNotifications.SaveTicket startTicket()"));
        assertTrue(source.contains("ticketIdentity.type(), ticketIdentity.id(), ticketIdentity.name()"));
        assertTrue(source.contains("DesignerSaveNotifications.SaveTicket ticket = request.startTicket();"));
        assertTrue(source.contains("request.manager().saveGraph("));
        assertTrue(source.contains("DesignerSaveNotifications.failExact(job.saveRequest().ticket(), saveIssue);"));
        assertFalse(source.contains("DesignerSaveNotifications.start("));
        assertTrue(source.contains("scheduleWorkspaceSnapshotRetry(fence);"));
        assertTrue(source.contains("retryWorkspaceSnapshotCapture();"));
        assertTrue(source.contains("protected final boolean submitFrozenWorkspaceGraph(Function<FlowGraph, Boolean> action"));
        assertTrue(source.contains("protected final boolean deferFrozenWorkspaceMutation(Runnable mutation)"));
        assertTrue(source.contains("job.frozenRequest().action().apply(snapshot.materializeGraph())"));
        assertFalse(source.contains("protected void onSave() {\n        syncNodePositions();"));
        assertFalse(source.contains("project.setTerrainGraph(WorldGenManager.getInstance().toWorldGenGraph(studioServerId(), graph));"));
    }

    @Test
    void workspaceAwarenessGatesTraversalAndSerializesOnTheBoundedWorker() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        String authority = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/ui/collaboration/DesignerCollaborationAuthority.java"));
        int publicationStart = source.indexOf("private void publishWorkspaceAwareness(ReSyncFlowClient client, int mouseX");
        int graphAwarenessStart = source.indexOf("private void addGraphWorkspaceAwareness", publicationStart);
        String publication = source.substring(publicationStart, graphAwarenessStart);

        assertTrue(publication.indexOf("workspaceAwarenessPublicationInFlight")
            < publication.indexOf("DesignerCollaborationAuthority.widgetStates(screen)"));
        assertTrue(source.contains("workspaceWidgetStatesVersion != workspaceAwarenessSurfaceVersion"));
        assertTrue(source.contains("WORKSPACE_PUBLICATIONS.execute(() -> prepareWorkspaceAwarenessPublication(job))"));
        assertTrue(source.contains("String signature = GSON.toJson(job.state());"));
        assertTrue(authority.contains("screen.collaborationPointer(mouseX, mouseY, excluded)"));
        assertTrue(source.contains("DesignerCollaborationAuthority.cachedFocus(screen)"));
        assertTrue(source.contains("DesignerCollaborationAuthority.cachedPath(screen, focused)"));
    }

    @Test
    void studioLifecycleIoUsesABoundedPortableWorker() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        String worker = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/AsyncTaskWorker.java"));

        assertFalse(source.contains("CompletableFuture.runAsync"));
        assertFalse(source.contains("ThreadPoolExecutor"));
        assertFalse(source.contains("new Thread("));
        assertTrue(source.contains("new AsyncTaskWorker(1, 1, 4)"));
        assertTrue(source.contains("private boolean submitLifecycleTask(Runnable task)"));
        assertTrue(source.contains("generation == lifecycleTaskGeneration"));
        assertTrue(source.contains("lifecycleTasks.close();"));
        assertTrue(worker.contains("Async.supplyAsync"));
        assertTrue(worker.contains("pending.size() < capacity"));
        assertTrue(worker.contains("cancellations.forEach(Async::cancel)"));
    }

    @Test
    void graphProjectionWorkersUsePortableSchedulingAndRetainStaleFences() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("private static final AsyncTaskWorker GRAPH_RENDER_INDEXES = new AsyncTaskWorker(1, 2, 16)"));
        assertTrue(source.contains("private static final AsyncTaskWorker CORE_GRAPH_PROJECTIONS = new AsyncTaskWorker(1, 2, 16)"));
        assertTrue(source.contains("CORE_GRAPH_PROJECTIONS.execute(this::buildCoreProjections)"));
        assertTrue(source.contains("GRAPH_RENDER_INDEXES.execute(this::drainGraphRenderBuilds)"));
        assertTrue(source.contains("request.generation() == currentGeneration && request.snapshot() == requestedSnapshot"));
        assertTrue(source.contains("graphRenderBuildMatches(graph, graphRenderMutationVersion, graphRenderTopologyRevision.get()"));
    }

    @Test
    void identicalSnapshotReturnsBeforeApplyingTheCurrentProjection() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("if (!result.changed())"));
        assertTrue(source.contains("merged == null || merged.equals(current)"));
        JsonObject document = object("{\"nodes\":{},\"connections\":[]}");
        assertTrue(FlowWorkspaceDocument.diff(document, document.deepCopy()).isEmpty());
    }

    @Test
    void authoritativeSnapshotsWaitForAnExactStableLocalProjection() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("deferredWorkspaceSnapshot = snapshot;"));
        assertTrue(source.contains("if (deferred == null || snapshot.sequence() >= deferred.sequence())"));
        assertTrue(source.contains("stable.mutationVersion() != owner.workspaceMutationVersion()"));
        assertTrue(source.contains("return new WorkspaceInput(sequence, () -> applyWorkspaceSnapshot(snapshot), false);"));
        assertTrue(source.contains("deferredWorkspaceSnapshot != null"));
    }

    @Test
    void deferredMutationSaturationPreservesTheTriggerAndCancelsPendingDispatch() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("private static final int WORKSPACE_DEFERRED_MUTATION_LIMIT = 129;"));
        assertTrue(source.contains("deferredWorkspaceOverflowMutation = mutation;"));
        assertTrue(source.contains("workspaceFrozenLeaseCancellation++;"));
        assertTrue(source.contains("Runnable overflow = deferredWorkspaceOverflowMutation;"));
        assertTrue(source.contains("deferredWorkspaceOverflowMutation = null;"));
        assertTrue(source.contains("job.cancellation() == workspaceFrozenLeaseCancellation"));
        assertTrue(source.contains("job.cancellation() != workspaceFrozenLeaseCancellation"));
    }

    @Test
    void conflictActionsUseTheSafeDocumentAndKeepTheDraftWhenTheEditorDisappears() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("public boolean reapplyWorkspaceConflict()"));
        assertTrue(source.contains("public boolean rebaseWorkspaceConflict()"));
        assertTrue(source.contains("public boolean discardWorkspaceConflict()"));
        assertTrue(source.contains("JsonObject current = safeCurrentWorkspaceDocument();"));
        assertTrue(source.contains("if (current == null) {\n            return false;\n        }"));
        assertTrue(source.contains("private boolean finishWorkspaceConflict"));
        assertTrue(source.contains("if (owner == null)"));
    }

    @Test
    void closedEditorsUnregisterAndIgnoreQueuedWorkspaceCallbacks() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("public void removed()"));
        assertTrue(source.contains("closeLifecycle();"));
        assertTrue(source.contains("super.removed();"));
        assertTrue(source.contains("offerWorkspaceUpdate(WorkspaceUpdateKind.SNAPSHOT"));
        assertTrue(source.contains("clearWorkspaceUpdates();\n        OPEN_SCREENS.remove(this);"));
        assertTrue(source.contains("CLOSING_LIVE_SCREENS.remove(serverId);"));
        assertTrue(source.contains("joinedWorkspaceListener = workspaceListener(workspacePublicationGeneration, workspaceType, workspaceResourceId);"));
        assertTrue(source.contains("generation == workspacePublicationGeneration && Objects.equals(type, workspaceType)"));
        assertTrue(source.contains("current.leaveWorkspace(workspaceType, workspaceResourceId, listener);"));
    }

    @Test
    void fullEditorCloseLetsTheManagerRemoveTheRegisteredScreen() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        int closeStart = source.indexOf("public void close()");
        int managerRequest = source.indexOf("manager.requestCloseLiveStudioSuperScreen(serverId);", closeStart);
        int lifecycleClose = source.indexOf("closeLifecycle();", closeStart);

        assertTrue(closeStart >= 0);
        assertTrue(managerRequest > closeStart);
        assertTrue(lifecycleClose > managerRequest);
        assertTrue(source.contains("CLOSING_LIVE_SCREENS.put(serverId, new WeakReference<>(this));"));
    }

    @Test
    void conflictResolutionDocumentPreservesAuthoritativeMetadataForReapply() {
        JsonObject draft = object("""
            {"resourceRevision":4,"resourceHash":"local","nodes":{"draft":{"type":"log"}},"connections":[]}
            """);
        JsonObject authoritative = object("""
            {"resourceRevision":5,"resourceHash":"remote","nodes":{"remote":{"type":"delay"}},"connections":[],"enabled":true}
            """);

        JsonObject resolved = FlowWorkspaceDocument.reapply(draft, authoritative);

        assertEquals(5, resolved.get("resourceRevision").getAsInt());
        assertEquals("remote", resolved.get("resourceHash").getAsString());
        assertEquals(draft.getAsJsonObject("nodes"), resolved.getAsJsonObject("nodes"));
        assertTrue(resolved.get("enabled").getAsBoolean());
    }

    @Test
    void workspaceOperationConflictRecoveryKeepsThePatchedAuthority() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("JsonObject authoritative = workspaceDocument.deepCopy();"));
        assertTrue(source.contains("FlowWorkspaceDocument.apply(authoritative, patches);"));
        assertTrue(source.contains("reconcileWorkspacePendingOperations(authoritative, request.sequence(), sourceOperations)"));
        assertTrue(source.contains("} catch (RuntimeException | Error exception) {\n            requestWorkspaceSnapshot();"));
    }

    @Test
    void reconnectRepublishesRebasedPendingWorkspaceOperations() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("private boolean workspacePendingRepublish;"));
        assertTrue(source.contains("replaceWorkspacePendingOperations(result.pendingOperations())"));
        assertTrue(source.contains("workspacePendingRepublish = !workspacePendingOperations.isEmpty();"));
        assertTrue(source.contains("if (connected && workspacePendingRepublish && !workspaceType.isBlank()\n            && !workspacePendingRepublishAwaitingSnapshot\n            && catalogAuthorityAllowsWorkspacePublication())"));
        assertTrue(source.contains("private boolean republishWorkspacePendingOperations(ReSyncFlowClient client)"));
        assertTrue(source.contains("private static final int WORKSPACE_PENDING_OPERATION_LIMIT = 1;"));
        assertTrue(source.contains("private static final long WORKSPACE_PENDING_RETAINED_BYTE_LIMIT"));
        assertTrue(source.contains("private PendingWorkspaceOperation workspacePendingRepublishQueued;"));
        assertTrue(source.contains("new Notification(\"Workspace\", \"Changes Waiting To Sync\", Notification.Type.WARN);"));
        assertTrue(source.contains("retainedBytes > job.retainedByteBudget()"));
        assertTrue(source.contains("workspacePendingNextBaseSequence >= 0L"));
        assertFalse(source.contains("workspacePendingOperations.values().stream().reduce"));
        assertTrue(source.contains("private void clearWorkspacePendingOperations() {\n        workspacePendingOperations.clear();"));
        assertFalse(source.contains("private void clearWorkspacePendingOperations() {\n        clearWorkspacePendingOperations();"));
        assertTrue(source.contains("if (workspacePendingRepublishInFlight != null) {\n            workspacePendingRepublish = true;\n            return true;"));
        assertTrue(source.contains("PendingWorkspaceOperation operation = workspacePendingRepublishQueued;"));
        assertTrue(source.contains("workspacePendingRepublishQueued = null;\n        PendingWorkspaceOperation republished"));
        assertTrue(source.contains("workspacePendingRepublishInFlight = republished;"));
        assertTrue(source.contains("PendingWorkspaceOperation current = workspacePendingOperations.get(workspacePendingRepublishInFlight.operationId());"));
        assertTrue(source.contains("if (current == null || !current.equals(workspacePendingRepublishInFlight)) {\n                workspacePendingRepublishInFlight = null;"));
        assertTrue(source.contains("workspacePendingRepublishInFlight.patches().equals(operation.patches())"));
        assertTrue(source.contains("if (result.replayEcho()) {\n                    workspacePendingRepublishInFlight = null;"));
        assertTrue(source.contains("if (!connected && workspaceConnectionWasConnected) {\n            invalidateWorkspacePendingRepublishOnDisconnect();"));
        assertTrue(source.contains("workspacePendingRepublishAwaitingSnapshot = true;\n        refreshWorkspacePendingRepublishQueue(true);"));
        assertTrue(source.contains("private void invalidateWorkspacePendingRepublishOnDisconnect()"));
        assertTrue(source.contains("private void invalidateWorkspacePendingRepublishForSnapshot() {\n        invalidateWorkspacePendingRepublish(false);"));
        assertTrue(source.contains("private void invalidateWorkspacePendingRepublishForSnapshot()"));
        assertTrue(source.contains("if (\"Disconnected\".equals(reason)) {\n            workspaceConnectionWasConnected = false;\n            invalidateWorkspacePendingRepublishOnDisconnect();"));
        assertTrue(source.contains("if (workspacePendingRepublishInFlight != null) {\n            invalidateWorkspacePendingRepublishOnDisconnect();\n        }\n        requestWorkspaceSnapshot();"));
        assertTrue(source.contains("new PendingWorkspaceOperation(operationId, operation.baseSequence(),\n            operation.base(), operation.patches(), operation.desired(), operation.retainedBytes())"));
    }

    @Test
    void pasteAndHistoryMutationEntryPointsRequireCatalogAuthority() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("if (!clipboard.nodes.isEmpty() && catalogAuthorityAllowsInteraction())"));
        assertTrue(source.contains("if (!catalogAuthorityAllowsInteraction() || clipboard.nodes.isEmpty())"));
        assertTrue(source.contains("hasTypedCatalogProjection() && !typedNodeTypeEditable(copied.type)"));
        assertTrue(source.contains("if (catalogAuthorityAllowsInteraction() && handleStudioHistoryShortcut(event))"));
        assertTrue(source.contains("private void restoreSnapshot(GraphSnapshot snapshot) {\n        if (!catalogAuthorityAllowsInteraction())"));
        assertTrue(source.contains("private void restoreSnapshot(FlowGraph target, GraphSnapshot snapshot) {\n        if (!catalogAuthorityAllowsInteraction())"));
    }

    private JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
