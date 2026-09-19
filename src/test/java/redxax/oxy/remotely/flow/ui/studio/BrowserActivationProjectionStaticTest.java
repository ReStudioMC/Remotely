package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserActivationProjectionStaticTest {
    @Test
    void cachedActivationReadCannotHydrateOrSend() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java"));
        String snapshot = method(source, "public Map<String, Boolean> cachedResourceActivationSnapshot(");
        String cachedRead = method(source, "private boolean cachedResourceEnabled(");

        assertTrue(snapshot.contains("return Map.copyOf(activations)"));
        assertTrue(cachedRead.indexOf("pendingActivations.get") < cachedRead.indexOf("coreGraphUiProjection.baseline"));
        assertTrue(cachedRead.contains("flowStore.get(serverId, type, id)"));
        assertTrue(cachedRead.contains("getBoolean(serverId, id, true"));
        assertFalse(cachedRead.contains("getGraph("));
        assertFalse(cachedRead.contains("hydrateCoreGraphProjection"));
        assertFalse(cachedRead.contains("requestResource"));
        assertFalse(cachedRead.contains("ensureSubscribedFlowClient"));
        assertFalse(cachedRead.contains("send"));
    }

    @Test
    void browserPublishesOneImmutableActivationSnapshotToTheUiRebuild() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java"));
        String schedule = method(source, "private void scheduleRebuild(long metadataStamp, RemotePath revealPath, int attempt)");
        String resourceSnapshots = method(source, "private List<BrowserResource> browserResources(");
        String assetSnapshot = method(source, "private AssetBrowserSnapshot assetBrowserSnapshot(");
        String iconSnapshots = method(source, "private Map<String, String> resourceIconPaths(");
        String providerPreparation = method(source, "static ReSyncProjectTreeProvider prepareTreeProvider(");
        String drainSubmit = method(source, "void submit(T request)");
        String drain = method(source, "private void drain()");
        String preparation = method(source, "private void prepareRebuild(BrowserRebuildRequest request)");
        String failureHandoff = method(source, "private void handleRebuildFailure(");
        String failureSettlement = method(source, "private void finishRebuildFailure(");
        String application = method(source, "private void applyPreparedRebuild(PreparedBrowserRebuild prepared)");
        String publication = method(source, "private void publishPreparedRebuild(PreparedBrowserRebuild prepared)");
        String disposal = method(source, "void dispose()");

        String activationSnapshot = "manager.cachedResourceActivationSnapshot(screen.studioServerId(), projectedResources)";
        String uiDispatch = "ScreenManager.getInstance().execute(() -> {";
        assertTrue(schedule.contains("rebuildDrain.submit(new BrowserRebuildRequest(generation, metadataStamp, collaborationRevision, revealPath,"));
        assertTrue(source.contains("private record BrowserRebuildRequest(long generation, long metadataStamp, long decorationRevision,"));
        assertTrue(source.contains("expandedPaths = List.copyOf(expandedPaths);"));
        assertTrue(source.contains("new LatestRequestDrain<>(BROWSER_HISTORY::execute, this::prepareRebuild,"));
        assertTrue(source.contains("this::handleRebuildFailure"));
        assertTrue(source.contains("private static final AsyncTaskWorker BROWSER_HISTORY = new AsyncTaskWorker(1, 1, 16)"));
        assertFalse(source.contains("ThreadPoolExecutor"));
        assertFalse(source.contains("new Thread("));
        assertTrue(drainSubmit.contains("pending = Objects.requireNonNull(request, \"Rebuild request is required\")"));
        assertTrue(drainSubmit.contains("dispatch = !draining"));
        assertTrue(drainSubmit.contains("if (!dispatch)"));
        assertTrue(drainSubmit.contains("executor.accept(this::drain)"));
        assertTrue(drain.contains("while (true)"));
        assertTrue(drain.contains("request = pending"));
        assertTrue(drain.contains("pending = null"));
        assertTrue(drain.contains("draining = false"));
        assertTrue(drain.contains("preparation.accept(request)"));
        assertTrue(preparation.contains("List<ReSyncProjectMetadata.ResourceEntry> projectedResources = screen.studioAllResources()"));
        assertTrue(preparation.contains(activationSnapshot));
        assertTrue(preparation.contains("List<BrowserResource> resources = browserResources(projectedResources, activations)"));
        assertTrue(preparation.indexOf("ReSyncProjectTreeProvider provider = prepareTreeProvider(projectRoot, folders, resources)")
            < preparation.indexOf(uiDispatch));
        assertTrue(preparation.contains("applyPreparedRebuild(prepared);"));
        assertTrue(preparation.contains("finishRebuildFailure(request.generation(), request.metadataStamp(), request.decorationRevision()"));
        assertTrue(resourceSnapshots.contains("resourceEnabled(resource, activations)"));
        assertTrue(resourceSnapshots.contains("return List.copyOf(snapshots)"));
        assertTrue(iconSnapshots.contains("return Map.copyOf(paths)"));
        assertTrue(assetSnapshot.contains("String.valueOf(resource.enabled())"));
        assertTrue(providerPreparation.contains("if (!resource.enabled())"));
        assertTrue(providerPreparation.contains("List.copyOf(entries)"));
        assertTrue(providerPreparation.contains("Map.copyOf(preparedEntries)"));
        assertTrue(failureHandoff.contains("ScreenManager.getInstance().execute(failure)"));
        assertTrue(failureHandoff.contains("failure.run()"));
        assertTrue(failureSettlement.contains("rebuildPublicationGate.publish(generation"));
        assertTrue(failureSettlement.contains("retryRebuild(attempt)"));
        assertTrue(failureSettlement.contains("scheduleRebuild(metadataStamp, revealPath, attempt + 1)"));
        assertTrue(failureSettlement.contains("failedDecorationRevision = decorationRevision"));
        assertTrue(application.contains("rebuildPublicationGate.current(prepared.generation())"));
        assertTrue(disposal.contains("disposed = true"));
        assertTrue(disposal.contains("rebuildPublicationGate.invalidate()"));
        assertTrue(publication.contains("resourceIconPaths = prepared.iconPaths()"));
        assertTrue(publication.contains("treeProvider = prepared.provider()"));
        assertTrue(publication.contains("mountPreparedTree(prepared.provider(), prepared.expandAll(), revealPath)"));
        assertFalse(assetSnapshot.contains("isResourceEnabled"));
        assertFalse(providerPreparation.contains("isResourceEnabled"));
        assertFalse(publication.contains("for ("));
        assertFalse(publication.contains(".rebuild("));
        assertFalse(publication.contains("putAll("));
        assertTrue(application.contains("prepared.metadataStamp() != scheduledProjectMetadataStamp"));
        assertTrue(application.contains("prepared.snapshot().collaborationRevision() != scheduledDecorationRevision"));
        assertTrue(application.contains("prepared.expandedTreeRevision() != expandedTreeRevision"));
        assertTrue(application.contains("rebuildPublicationGate.publish(prepared.generation()"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0);
        int body = source.indexOf('{', start);
        int depth = 0;
        for (int index = body; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new IllegalStateException("Method body is incomplete");
    }
}
