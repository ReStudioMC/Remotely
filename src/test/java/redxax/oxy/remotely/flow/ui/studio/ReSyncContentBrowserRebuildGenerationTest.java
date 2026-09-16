package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;
import restudio.rebase.backend.RemotePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncContentBrowserRebuildGenerationTest {
    private static final Path BROWSER = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java");

    @Test
    void automationTypesOpenTheirDesignerWithoutASecondContentBrowser() throws IOException {
        String browser = Files.readString(BROWSER);
        String studio = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java"));

        assertTrue(browser.contains("screen.openDefinitions(AutomationDefinitionDraft.VARIABLE)"));
        assertTrue(browser.contains("!AutomationDefinitionDraft.supports(resource.getType())"));
        assertFalse(browser.contains("boolean subresources"));
        assertFalse(studio.contains("ReSyncSubresourcesWidget"));
        assertFalse(studio.contains("showSubresources"));
        assertTrue(Files.notExists(Path.of(
            "src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncSubresourcesWidget.java")));
    }

    @Test
    void latestPreparedStateWinsWhenAnOlderUiPublicationRunsLast() {
        ReSyncContentBrowserWidget.RebuildPublicationGate gate = new ReSyncContentBrowserWidget.RebuildPublicationGate();
        ControllableExecutor worker = new ControllableExecutor();
        ControllableExecutor ui = new ControllableExecutor();
        AtomicReference<String> published = new AtomicReference<>();
        ReSyncContentBrowserWidget.LatestRequestDrain<GenerationValue> drain =
            new ReSyncContentBrowserWidget.LatestRequestDrain<>(worker::execute, request -> ui.execute(() ->
                gate.publish(request.generation(), () -> published.set(request.value()))), (request, exception) -> {
                    throw exception;
                });

        drain.submit(new GenerationValue(gate.next(), "A"));
        worker.runFirst();
        drain.submit(new GenerationValue(gate.next(), "B"));
        worker.runFirst();

        ui.runLast();
        ui.runFirst();

        assertEquals("B", published.get());
    }

    @Test
    void burstBeforeDrainStartsPreparesOnlyTheLatestRequest() {
        ControllableExecutor worker = new ControllableExecutor();
        List<String> prepared = new ArrayList<>();
        ReSyncContentBrowserWidget.LatestRequestDrain<String> drain =
            new ReSyncContentBrowserWidget.LatestRequestDrain<>(worker::execute, prepared::add, (request, exception) -> {
                throw exception;
            });

        for (int request = 0; request < 100; request++) {
            drain.submit("state-" + request);
        }

        assertEquals(1, worker.size());
        assertEquals(1, drain.pendingCount());
        assertFalse(drain.quiescent());
        worker.runFirst();
        assertEquals(List.of("state-99"), prepared);
        assertTrue(drain.quiescent());
    }

    @Test
    void activePreparationKeepsOnlyOneReplaceablePendingRequest() {
        ControllableExecutor worker = new ControllableExecutor();
        AtomicReference<ReSyncContentBrowserWidget.LatestRequestDrain<String>> reference = new AtomicReference<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        List<String> prepared = new ArrayList<>();
        ReSyncContentBrowserWidget.LatestRequestDrain<String> drain =
            new ReSyncContentBrowserWidget.LatestRequestDrain<>(worker::execute, request -> {
                maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    prepared.add(request);
                    if ("A".equals(request)) {
                        reference.get().submit("B");
                        assertEquals(1, reference.get().pendingCount());
                        reference.get().submit("C");
                        assertEquals(1, reference.get().pendingCount());
                    }
                } finally {
                    active.decrementAndGet();
                }
            }, (request, exception) -> {
                throw exception;
            });
        reference.set(drain);

        drain.submit("A");
        worker.runFirst();

        assertEquals(List.of("A", "C"), prepared);
        assertEquals(2, prepared.size());
        assertEquals(1, maximumActive.get());
        assertEquals(0, worker.size());
        assertTrue(drain.quiescent());
    }

    @Test
    void preparedRebuildCarriesAndChecksItsRequestGeneration() throws IOException {
        String source = Files.readString(BROWSER).replace("\r\n", "\n");

        assertTrue(source.contains("PreparedBrowserRebuild(long generation, long metadataStamp, RemotePath revealPath,"));
        assertTrue(source.contains("long generation = rebuildPublicationGate.next();"));
        assertTrue(source.contains("rebuildDrain.submit(new BrowserRebuildRequest(generation, metadataStamp,"));
        assertTrue(source.contains("private void prepareRebuild(BrowserRebuildRequest request)"));
        assertTrue(source.contains("rebuildPublicationGate.publish(prepared.generation(), () -> publishPreparedRebuild(prepared))"));
        assertTrue(source.contains("ReSyncProjectTreeProvider provider = prepareTreeProvider(projectRoot, folders, resources);"));
        assertTrue(source.contains("treeProvider = prepared.provider();"));
        assertTrue(source.contains("RemotePath revealPath = prepared.revealPath();"));
        String preparation = source.substring(source.indexOf("private void prepareRebuild"),
            source.indexOf("private void handleRebuildFailure"));
        int providerPreparation = preparation.indexOf("prepareTreeProvider(projectRoot, folders, resources)");
        int uiDispatch = preparation.indexOf("ScreenManager.getInstance().execute");
        assertTrue(providerPreparation >= 0);
        assertTrue(uiDispatch > providerPreparation);
        String publication = source.substring(source.indexOf("private void publishPreparedRebuild"),
            source.indexOf("private void rebuild(RemotePath revealPath)"));
        assertFalse(publication.contains(".rebuild("));
        assertFalse(publication.contains("getExpandedDirectories"));
        assertFalse(publication.contains("putAll("));
    }

    @Test
    void finalPublicationRetainsGenerationMetadataDecorationAndExpandedStateFences() throws IOException {
        String source = Files.readString(BROWSER).replace("\r\n", "\n");
        String application = source.substring(source.indexOf("private void applyPreparedRebuild"),
            source.indexOf("private void publishPreparedRebuild"));

        assertTrue(application.contains("!rebuildPublicationGate.current(prepared.generation())"));
        assertTrue(application.contains("prepared.metadataStamp() != manager.projectMetadataStamp(screen.studioServerId())"));
        assertTrue(application.contains("prepared.metadataStamp() != scheduledProjectMetadataStamp"));
        assertTrue(application.contains("prepared.snapshot().collaborationRevision() != scheduledDecorationRevision"));
        assertTrue(application.contains("prepared.expandedTreeRevision() != expandedTreeRevision"));
        assertTrue(application.contains("rebuildPublicationGate.publish(prepared.generation(), () -> publishPreparedRebuild(prepared))"));
    }

    @Test
    void currentGenerationManagerMissingUsesBoundedFailureRecovery() throws IOException {
        String source = Files.readString(BROWSER).replace("\r\n", "\n");
        String application = source.substring(source.indexOf("private void applyPreparedRebuild"),
            source.indexOf("private void publishPreparedRebuild"));
        String failure = source.substring(source.indexOf("private void finishRebuildFailure"),
            source.indexOf("private void applyPreparedRebuild"));

        int managerMissing = application.indexOf("\"manager_missing\".equals(rejectionReason)");
        int boundedFailure = application.indexOf("finishRebuildFailure(prepared.generation()", managerMissing);
        assertTrue(managerMissing >= 0);
        assertTrue(boundedFailure > managerMissing);
        assertTrue(application.substring(boundedFailure).contains("prepared.attempt()"));
        assertTrue(failure.contains("rebuildPublicationGate.publish(generation"));
        assertTrue(failure.contains("if (retryRebuild(attempt))"));
        assertTrue(failure.contains("scheduleRebuild(metadataStamp, revealPath, attempt + 1)"));
    }

    @Test
    void largeCatalogPublishesAsOneFencedPreparedProviderSwap() {
        RemotePath root = RemotePath.of("ReSync");
        int folderCount = 500;
        int resourcesPerFolder = 40;
        List<ReSyncContentBrowserWidget.BrowserFolder> folders = new ArrayList<>(folderCount);
        List<ReSyncContentBrowserWidget.BrowserResource> resources = new ArrayList<>(folderCount * resourcesPerFolder);
        for (int folderIndex = 0; folderIndex < folderCount; folderIndex++) {
            String folder = "Folder-" + folderIndex;
            folders.add(new ReSyncContentBrowserWidget.BrowserFolder(folder, "", folder, folderIndex, false));
            for (int resourceIndex = 0; resourceIndex < resourcesPerFolder; resourceIndex++) {
                String id = "resource-" + folderIndex + "-" + resourceIndex;
                resources.add(new ReSyncContentBrowserWidget.BrowserResource("flow", id, id,
                    folder + "/" + id + ".json", resourceIndex, "flow.png", resourceIndex % 7 != 0));
            }
        }

        ReSyncContentBrowserWidget.ReSyncProjectTreeProvider prepared =
            ReSyncContentBrowserWidget.prepareTreeProvider(root, List.copyOf(folders), List.copyOf(resources));
        assertEquals(folderCount + folderCount * resourcesPerFolder, prepared.entryCount());
        assertEquals(folderCount, prepared.ls(root).value().size());
        assertEquals(resourcesPerFolder, prepared.ls(root.resolve("Folder-287")).value().size());
        assertEquals("true", prepared.ls(root).value().getFirst().metadata.get("hasChildren"));
        assertEquals("danger", prepared.ls(root.resolve("Folder-287")).value().getFirst().metadata.get("metaAccent"));
        assertThrows(UnsupportedOperationException.class, () -> prepared.ls(root).value().clear());

        ReSyncContentBrowserWidget.RebuildPublicationGate gate = new ReSyncContentBrowserWidget.RebuildPublicationGate();
        long staleGeneration = gate.next();
        long currentGeneration = gate.next();
        AtomicReference<ReSyncContentBrowserWidget.ReSyncProjectTreeProvider> mounted = new AtomicReference<>();
        AtomicInteger mounts = new AtomicInteger();

        assertFalse(gate.publish(staleGeneration, () -> {
            mounts.incrementAndGet();
            mounted.set(prepared);
        }));
        assertTrue(gate.publish(currentGeneration, () -> {
            mounts.incrementAndGet();
            mounted.set(prepared);
        }));
        assertEquals(1, mounts.get());
        assertSame(prepared, mounted.get());
    }

    @Test
    void unchangedBoundsAndRepeatedInvalidationsCoalesceToOneLayout() {
        ReSyncContentBrowserWidget.BrowserLayoutGate gate = new ReSyncContentBrowserWidget.BrowserLayoutGate();

        assertTrue(gate.update(0, 38, 190, 640));
        assertTrue(gate.drain());
        assertFalse(gate.drain());
        assertFalse(gate.update(0, 38, 190, 640));
        assertFalse(gate.drain());

        assertTrue(gate.update(0, 38, 210, 640));
        assertTrue(gate.update(0, 38, 210, 720));
        gate.invalidate();
        gate.invalidate();
        assertTrue(gate.drain());
        assertFalse(gate.drain());
    }

    @Test
    void providerSwapRetainsTypedSelectionAndScrollButClearsTombstones() {
        RemotePath root = RemotePath.of("ReSync");
        ReSyncContentBrowserWidget.ReSyncProjectTreeProvider provider = ReSyncContentBrowserWidget.prepareTreeProvider(root,
            List.of(new ReSyncContentBrowserWidget.BrowserFolder("Kept", "", "Kept", 0, false)),
            List.of(new ReSyncContentBrowserWidget.BrowserResource("flow", "kept", "Kept", "Kept/kept.json", 0,
                "flow.png", true)));
        ReSyncContentBrowserWidget.BrowserSelectionState previous = new ReSyncContentBrowserWidget.BrowserSelectionState(
            Set.of("flow\u0000kept", "flow\u0000removed"), Set.of("Kept", "Removed"), false, 91.5F);

        ReSyncContentBrowserWidget.BrowserSelectionState retained =
            ReSyncContentBrowserWidget.retainSelection(previous, provider, root);

        assertEquals(Set.of("flow\u0000kept"), retained.resourceKeys());
        assertEquals(Set.of("Kept"), retained.folderPaths());
        assertEquals(91.5F, retained.scrollOffset());
        assertFalse(retained.projectRoot());
    }

    @Test
    void preparedSwapCapturesSelectionBeforeProviderReplacementAndRestoresScroll() throws IOException {
        String source = Files.readString(BROWSER).replace("\r\n", "\n");
        String publication = source.substring(source.indexOf("private void publishPreparedRebuild"),
            source.indexOf("private void rebuild(RemotePath revealPath)"));

        int capture = publication.indexOf("retainSelection(captureSelection(), prepared.provider(), projectRoot)");
        int provider = publication.indexOf("treeProvider = prepared.provider();");
        int workspace = publication.indexOf("browser.setWorkspace(projectRoot, treeProvider, prepared.expandAll(), expandedTreePaths);");
        int scroll = publication.indexOf("treeContainer.setScrollOffset(selection.scrollOffset());");
        assertTrue(capture >= 0);
        assertTrue(provider > capture);
        assertTrue(workspace > provider);
        assertTrue(scroll > workspace);
    }

    @Test
    void stalePreparationFailureCannotClearTheCurrentGeneration() {
        ReSyncContentBrowserWidget.RebuildPublicationGate gate = new ReSyncContentBrowserWidget.RebuildPublicationGate();
        long stale = gate.next();
        long current = gate.next();
        AtomicInteger cleared = new AtomicInteger();

        assertFalse(gate.publish(stale, cleared::incrementAndGet));
        assertEquals(0, cleared.get());
        assertTrue(gate.publish(current, cleared::incrementAndGet));
        assertEquals(1, cleared.get());
    }

    @Test
    void preparationRetriesAreBounded() {
        assertTrue(ReSyncContentBrowserWidget.retryRebuild(0));
        assertTrue(ReSyncContentBrowserWidget.retryRebuild(1));
        assertFalse(ReSyncContentBrowserWidget.retryRebuild(2));
    }

    @Test
    void workerAndSubmissionFailuresReachTheBoundedFailureOwner() {
        AtomicInteger failures = new AtomicInteger();
        ReSyncContentBrowserWidget.LatestRequestDrain<String> failedPreparation =
            new ReSyncContentBrowserWidget.LatestRequestDrain<>(Runnable::run, ignored -> {
                throw new IllegalStateException("prepare");
            }, (request, exception) -> failures.incrementAndGet());
        ReSyncContentBrowserWidget.LatestRequestDrain<String> rejectedSubmission =
            new ReSyncContentBrowserWidget.LatestRequestDrain<>(ignored -> {
                throw new RejectedExecutionException("worker");
            }, ignored -> {
                throw new AssertionError("Rejected task ran");
            }, (request, exception) -> failures.incrementAndGet());

        failedPreparation.submit("prepare");
        rejectedSubmission.submit("worker");

        assertEquals(2, failures.get());
        assertTrue(failedPreparation.quiescent());
        assertTrue(rejectedSubmission.quiescent());
    }

    @Test
    void createVisibilityWaitsForPublishedProviderMembershipAndReleasesOnce() throws IOException {
        RemotePath root = RemotePath.of("ReSync");
        ReSyncContentBrowserWidget.ReSyncProjectTreeProvider empty =
            ReSyncContentBrowserWidget.prepareTreeProvider(root, List.of(), List.of());
        ReSyncContentBrowserWidget.ReSyncProjectTreeProvider published =
            ReSyncContentBrowserWidget.prepareTreeProvider(root, List.of(), List.of(
                new ReSyncContentBrowserWidget.BrowserResource("flow", "created", "Created", "Flows/created.json",
                    0, "flow.png", true)));
        ReSyncContentBrowserWidget.CreationVisibilityGate<String> gate =
            new ReSyncContentBrowserWidget.CreationVisibilityGate<>();
        String key = "flow\u0000created";
        List<String> delivered = new ArrayList<>();

        assertTrue(gate.await(key, key));
        assertEquals(1, gate.pendingCount());
        gate.publish(empty::containsResourceKey, delivered::add);
        assertEquals(List.of(), delivered);
        gate.publish(published::containsResourceKey, delivered::add);
        assertEquals(List.of(key), delivered);
        assertEquals(0, gate.pendingCount());
        gate.publish(published::containsResourceKey, delivered::add);
        assertEquals(List.of(key), delivered);

        String source = Files.readString(BROWSER).replace("\r\n", "\n");
        String visibility = source.substring(source.indexOf("private void awaitCreatedResourceVisibility"),
            source.indexOf("private boolean createContentResource"));
        int membership = visibility.indexOf("pending -> !disposed && pending.visibleIn(provider)");
        int trace = visibility.indexOf("\"browser_create_visible\"");
        int oneShot = visibility.indexOf("pending.publication().publish(");
        int open = visibility.indexOf("() -> openCreatedResourceEditor(pending.result())", oneShot);
        int diagnostic = visibility.indexOf("() -> traceVisibleCreatedResource(pending, generation, metadataStamp)",
            oneShot);
        assertTrue(membership >= 0);
        assertTrue(trace > membership);
        assertTrue(oneShot > membership);
        assertTrue(open > oneShot);
        assertTrue(diagnostic > open);
        assertTrue(trace > diagnostic);
        assertFalse(visibility.substring(0, membership).contains("\"browser_create_visible\""));
        assertTrue(source.contains("provider.containsResourceKey(resourceKey)"));
        String settlement = source.substring(source.indexOf("private void traceCreateCallback(String expectedType"),
            source.indexOf("private BrowserSelection browserSelection"));
        assertTrue(settlement.contains("\"browser_create_callback\""));
        assertTrue(settlement.contains("\"expectedResult\", expectedResult"));
        assertFalse(settlement.contains("\"browser_create_visible\""));
    }

    @Test
    void throwingCreationPublisherRetainsFailedAndUnprocessedEntries() {
        ReSyncContentBrowserWidget.CreationVisibilityGate<String> gate =
            new ReSyncContentBrowserWidget.CreationVisibilityGate<>();
        List<String> attempts = new ArrayList<>();

        assertTrue(gate.await("first", "first"));
        assertTrue(gate.await("second", "second"));
        assertThrows(IllegalStateException.class, () -> gate.publish(ignored -> true, value -> {
            attempts.add(value);
            throw new IllegalStateException("publish");
        }));
        assertEquals(List.of("first"), attempts);
        assertEquals(2, gate.pendingCount());

        gate.publish(ignored -> true, attempts::add);
        assertEquals(List.of("first", "first", "second"), attempts);
        assertEquals(0, gate.pendingCount());
    }

    @Test
    void creationAcknowledgmentDoesNotRemoveAReplacementQueuedByThePublisher() {
        ReSyncContentBrowserWidget.CreationVisibilityGate<VisibilityValue> gate =
            new ReSyncContentBrowserWidget.CreationVisibilityGate<>();
        VisibilityValue first = new VisibilityValue("same");
        VisibilityValue replacement = new VisibilityValue("same");
        List<VisibilityValue> delivered = new ArrayList<>();

        assertTrue(gate.await("key", first));
        gate.publish(ignored -> true, value -> {
            delivered.add(value);
            gate.await("key", replacement);
        });

        assertEquals(1, delivered.size());
        assertSame(first, delivered.getFirst());
        assertEquals(1, gate.pendingCount());
        gate.publish(ignored -> true, delivered::add);
        assertEquals(2, delivered.size());
        assertSame(first, delivered.getFirst());
        assertSame(replacement, delivered.get(1));
        assertEquals(0, gate.pendingCount());
    }

    @Test
    void successfulOpenIsNotRetriedWhenTheDiagnosticThrows() {
        ReSyncContentBrowserWidget.OneShotCreationPublication publication =
            new ReSyncContentBrowserWidget.OneShotCreationPublication();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger diagnostics = new AtomicInteger();
        List<String> order = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> publication.publish(() -> {
            opens.incrementAndGet();
            order.add("open");
        }, () -> {
            diagnostics.incrementAndGet();
            order.add("diagnostic");
            throw new IllegalStateException("diagnostic");
        }));
        assertTrue(publication.openCompleted());
        publication.publish(opens::incrementAndGet, diagnostics::incrementAndGet);

        assertEquals(List.of("open", "diagnostic"), order);
        assertEquals(1, opens.get());
        assertEquals(1, diagnostics.get());
    }

    @Test
    void mutatingThrowingOpenerCannotOpenAgainOrEmitVisibleDiagnostic() {
        ReSyncContentBrowserWidget.OneShotCreationPublication publication =
            new ReSyncContentBrowserWidget.OneShotCreationPublication();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger diagnostics = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> publication.publish(() -> {
            opens.incrementAndGet();
            throw new IllegalStateException("open");
        }, diagnostics::incrementAndGet));
        assertFalse(publication.openCompleted());
        publication.publish(opens::incrementAndGet, diagnostics::incrementAndGet);

        assertEquals(1, opens.get());
        assertEquals(0, diagnostics.get());
    }

    @Test
    void lateCreationDeliveryAfterDisposalIsIgnored() throws IOException {
        ReSyncContentBrowserWidget.CreationVisibilityGate<String> gate =
            new ReSyncContentBrowserWidget.CreationVisibilityGate<>();
        AtomicInteger publications = new AtomicInteger();

        assertTrue(gate.await("flow\u0000before-dispose", "before-dispose"));
        assertEquals(1, gate.pendingCount());
        gate.close();

        assertFalse(gate.await("flow\u0000late", "late"));
        gate.publish(ignored -> true, ignored -> publications.incrementAndGet());
        assertEquals(0, gate.pendingCount());
        assertEquals(0, publications.get());

        String source = Files.readString(BROWSER).replace("\r\n", "\n");
        String visibility = source.substring(source.indexOf("private void awaitCreatedResourceVisibility"),
            source.indexOf("private boolean createContentResource"));
        assertTrue(visibility.contains("if (disposed || result == null"));
        assertTrue(source.contains("creationVisibilityGate.close();"));
    }

    @Test
    void disposalInvalidatesAQueuedPublication() {
        ReSyncContentBrowserWidget.RebuildPublicationGate gate = new ReSyncContentBrowserWidget.RebuildPublicationGate();
        long queued = gate.next();

        gate.invalidate();

        assertFalse(gate.publish(queued, () -> {
            throw new AssertionError("Disposed publication ran");
        }));
    }

    @Test
    void collaborationActivityAdvancesDecorationOnlyWhenActivityChanges() {
        assertEquals(8L, ReSyncContentBrowserWidget.nextDecorationRevision(4L, 5L, 7L));
        assertEquals(7L, ReSyncContentBrowserWidget.nextDecorationRevision(5L, 5L, 7L));
    }

    private record GenerationValue(long generation, String value) {
    }

    private record VisibilityValue(String value) {
    }

    private static final class ControllableExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private void runFirst() {
            tasks.removeFirst().run();
        }

        private void runLast() {
            tasks.removeLast().run();
        }

        private int size() {
            return tasks.size();
        }
    }
}
