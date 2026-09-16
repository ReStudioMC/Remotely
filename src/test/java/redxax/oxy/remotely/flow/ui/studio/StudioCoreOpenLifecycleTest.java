package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import restudio.rescreen.theme.ThemeManager;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StudioCoreOpenLifecycleTest {
    private static final ServerId SERVER = ServerId.deterministic("studio-core-open-lifecycle");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "0".repeat(64), "1".repeat(64));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OwnerId.of("test"), ResourceTypeId.of("flow")), "open");

    private CoreAuthorityManager manager;

    @BeforeEach
    void setUp() {
        ThemeManager.initBrowserDefaults();
        manager = new CoreAuthorityManager();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void unresolvedOpenRetriesAtCadenceBeforeDeadline() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 10_000L);

        screen.beginOpen();
        screen.advanceTo(10_999L);
        screen.drain();
        screen.advanceTo(11_000L);
        screen.drain();
        screen.advanceTo(11_999L);
        screen.drain();

        assertEquals(2, screen.requests.get());
        assertEquals(0, screen.failures.get());
        assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
    }

    @Test
    void deadlineStopsLoadsAndNotifiesExactlyOnce() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 20_000L);

        screen.beginOpen();
        screen.advanceTo(35_000L);
        screen.drain();
        screen.advanceTo(60_000L);
        screen.drain();

        assertEquals(1, screen.requests.get());
        assertEquals(1, screen.failures.get());
        assertEquals("The Core graph connection is unavailable.", screen.failureReason);
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
    }

    @Test
    void authoritativeSessionPreparesUntilExactEditorReadiness() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 30_000L);
        screen.retainActiveDocument();

        screen.beginOpen();
        assertEquals(ReSyncProjectMetadata.resourceKey("world", "retained"), screen.activeStudioDocumentKey());
        assertFalse(screen.coreStudioLoadingVisible());

        manager.session = new CoreGraphEditorSession(graph());
        screen.advanceTo(31_000L);
        screen.drain();

        assertEquals(1, screen.requests.get());
        assertEquals(1, screen.bound.get());
        assertEquals(1, screen.activations.get());
        assertEquals(0, screen.failures.get());
        assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
        assertFalse(screen.coreStudioLoadingVisible());

        screen.advanceTo(32_000L);
        screen.drain();

        assertEquals(1, screen.requests.get());
        assertEquals(1, screen.bound.get());
        assertEquals(1, screen.activations.get());
        assertEquals(0, screen.failures.get());
        assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));

        screen.ready = true;
        screen.drain();

        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
        assertFalse(screen.coreStudioLoadingVisible());
    }

    @Test
    void backgroundSessionArrivalBindsWithoutActivationOrOpenIntent() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 35_000L);
        manager.session = new CoreGraphEditorSession(graph());
        screen.interceptBindings = false;

        screen.bindWorkspaceCoreEditor(manager.session, "Open");

        assertEquals(0, screen.bound.get());
        assertEquals(0, screen.activations.get());
        assertEquals(0, screen.requests.get());
        assertEquals(List.of(false), manager.persistedActivations);
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
    }

    @Test
    void sameKeyRebindRetainsVisibleDocumentUntilPreparationSettles() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 36_000L);
        CoreGraphEditorSession retainedSession = new CoreGraphEditorSession(graph());
        StudioViewportState retainedViewport = screen.retainActiveCoreDocument(retainedSession);

        screen.beginOpen();
        manager.session = new CoreGraphEditorSession(graph());
        screen.drain();

        assertEquals(1, screen.activations.get());
        assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
        assertSame(retainedViewport, screen.activeStudioDocument.viewport());

        screen.terminal = true;
        screen.drain();

        assertEquals(1, screen.failures.get());
        assertSame(retainedSession, screen.activeStudioDocument.coreSession());
        assertSame(retainedViewport, screen.activeStudioDocument.viewport());
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
    }

    @Test
    void acceptedReadOnlyOutcomeTerminatesWithoutAnotherLoad() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 37_000L);

        screen.beginOpen();
        manager.activationState = ReSyncFlowClient.CoreGraphActivationState.READ_ONLY;
        manager.activationReason = "The Core graph is read-only in the active authoring publication.";
        screen.advanceTo(38_000L);
        screen.drain();
        screen.advanceTo(60_000L);
        screen.drain();

        assertEquals(1, screen.requests.get());
        assertEquals(1, screen.failures.get());
        assertEquals(manager.activationReason, screen.failureReason);
        assertEquals(manager.activationReason, screen.coreOpenTerminalReason("flow", RESOURCE.id()));
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
    }

    @Test
    void catalogBindingRolloverHydratesSilentlyWithoutReplacingVisibleDocument() {
        manager.activationState = ReSyncFlowClient.CoreGraphActivationState.INCOMPATIBLE;
        manager.activationReason = "The graph catalog binding does not match the active authoring publication.";
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 38_000L);
        screen.retainActiveDocument();

        screen.beginOpen();

        assertEquals(1, screen.requests.get());
        assertEquals(0, screen.failures.get());
        assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
        assertEquals(ReSyncProjectMetadata.resourceKey("world", "retained"), screen.activeStudioDocumentKey());
        assertFalse(screen.coreStudioLoadingVisible());

        screen.advanceTo(53_000L);
        screen.drain();

        assertEquals(0, screen.failures.get());
        assertEquals(manager.activationReason, screen.coreOpenTerminalReason("flow", RESOURCE.id()));
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
        assertEquals(ReSyncProjectMetadata.resourceKey("world", "retained"), screen.activeStudioDocumentKey());
    }

    @Test
    void missingOutcomeRemainsPreciseWithoutHydration() {
        manager.activationState = ReSyncFlowClient.CoreGraphActivationState.MISSING;
        manager.activationReason = "The Core graph was deleted.";
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 38_000L);

        screen.beginOpen();

        assertEquals(0, screen.requests.get());
        assertEquals(1, screen.failures.get());
        assertEquals(manager.activationReason, screen.failureReason);
        assertEquals(manager.activationReason, screen.coreOpenTerminalReason("flow", RESOURCE.id()));
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
    }

    @Test
    void unchangedActiveDocumentSelectionIsIdempotent() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 39_000L);
        screen.retainActiveDocument();

        screen.selectRetainedDocument();

        assertEquals(0, screen.selections.get());
        assertEquals(ReSyncProjectMetadata.resourceKey("world", "retained"), screen.activeStudioDocumentKey());
    }

    @Test
    void pendingCoreTabKeepsPreviouslyVisibleDocumentUntilFailure() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 40_000L);
        screen.retainActiveDocument();
        screen.retainInactiveCoreDocument();

        assertEquals(0, screen.requests.get());
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));

        screen.selectCoreDocument();

        assertEquals(1, screen.requests.get());
        assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
        assertEquals(ReSyncProjectMetadata.resourceKey("world", "retained"), screen.activeStudioDocumentKey());
        assertFalse(screen.coreStudioLoadingVisible());

        screen.advanceTo(55_000L);
        screen.drain();

        assertEquals(1, screen.failures.get());
        assertEquals(ReSyncProjectMetadata.resourceKey("world", "retained"), screen.activeStudioDocumentKey());
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
    }

    @Test
    void backgroundCoreArrivalPreservesVisibleDirtyLegacyDraft() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 41_000L);
        FlowGraph draft = screen.retainDirtyLegacyDraft();
        screen.interceptBindings = false;
        manager.session = new CoreGraphEditorSession(graph());

        screen.bindWorkspaceCoreEditor(manager.session, "Open");

        assertEquals(ReSyncProjectMetadata.resourceKey("flow", RESOURCE.id()), screen.activeStudioDocumentKey());
        assertTrue(screen.activeStudioDocument.graph() == draft);
        assertEquals("legacy_draft_dirty", screen.coreOpenTerminalReason("flow", RESOURCE.id()));
        assertEquals(0, screen.activations.get());
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
    }

    @Test
    void duplicateReconnectOpenDoesNotExtendOriginalDeadline() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 40_000L);

        screen.beginOpen();
        screen.advanceTo(50_000L);
        screen.beginOpen();
        screen.advanceTo(55_000L);
        screen.drain();

        assertEquals(2, screen.requests.get());
        assertEquals(1, screen.failures.get());
        assertEquals(0, screen.selections.get());
        assertFalse(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
    }

    @Test
    void returningToActiveTabDemotesPendingCoreActivationAndLateSessionCannotStealFocus() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 45_000L);
        screen.retainActiveDocument();
        screen.retainInactiveCoreDocument();

        screen.selectCoreDocument();
        assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));

        screen.selectRetainedDocument();
        assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));

        manager.session = new CoreGraphEditorSession(graph());
        screen.bindWorkspaceCoreEditor(manager.session, "Open");
        screen.drain();

        assertEquals(1, screen.bound.get());
        assertEquals(0, screen.activations.get());
        assertEquals(ReSyncProjectMetadata.resourceKey("world", "retained"), screen.activeStudioDocumentKey());
    }

    @Test
    void unrelatedPendingCoreIntentsRemainOwnedPerResource() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 46_000L);
        screen.retainActiveDocument();
        screen.retainInactiveCoreDocument();
        screen.retainInactiveCoreDocument("other");

        screen.selectCoreDocument();
        screen.selectCoreDocument("other");

        assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
        assertTrue(screen.hasPendingCoreOpenIntent("flow", "other"));
        assertEquals(ReSyncProjectMetadata.resourceKey("world", "retained"), screen.activeStudioDocumentKey());
        assertFalse(screen.coreStudioLoadingVisible());
    }

    @Test
    void backgroundSameKeyRebindPublishesReplacementWithoutAnotherSelection() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 47_000L);
        CoreGraphEditorSession previous = new CoreGraphEditorSession(graph());
        manager.session = previous;
        screen.retainActiveCoreDocument(previous);
        CoreGraphEditorSession replacement = new CoreGraphEditorSession(graph());
        manager.session = replacement;
        screen.interceptBindings = false;

        screen.bindWorkspaceCoreEditor(replacement, "Open");

        assertSame(replacement, screen.activeStudioDocument.coreSession());
        assertSame(replacement, screen.findStudioDocument(ReSyncProjectMetadata.resourceKey("flow", RESOURCE.id()))
            .coreSession());
        assertEquals(0, screen.selections.get());
        assertEquals(1, screen.projectionBindings.get());
        assertEquals(List.of(false), manager.persistedActivations);
    }

    @Test
    void backgroundDifferentKeyBindPreservesTheActiveDocument() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 47_500L);
        screen.retainActiveDocument();
        manager.session = new CoreGraphEditorSession(graph());
        screen.interceptBindings = false;

        screen.bindWorkspaceCoreEditor(manager.session, "Open");

        assertEquals(ReSyncProjectMetadata.resourceKey("world", "retained"), screen.activeStudioDocumentKey());
        assertSame(manager.session, screen.findStudioDocument(ReSyncProjectMetadata.resourceKey("flow", RESOURCE.id()))
            .coreSession());
        assertEquals(0, screen.selections.get());
        assertEquals(0, screen.projectionBindings.get());
        assertEquals(List.of(false), manager.persistedActivations);
    }

    @Test
    void explicitOpenActivatesAndPublishesExactlyOnce() {
        TestStudioScreen screen = new TestStudioScreen(SERVER.canonicalText(), 48_000L);
        screen.retainActiveDocument();
        manager.session = new CoreGraphEditorSession(graph());
        screen.interceptBindings = false;

        screen.openWorkspaceCoreEditor(manager.session, "Open", null);

        assertEquals(ReSyncProjectMetadata.resourceKey("flow", RESOURCE.id()), screen.activeStudioDocumentKey());
        assertSame(manager.session, screen.activeStudioDocument.coreSession());
        assertEquals(1, screen.selections.get());
        assertEquals(1, screen.projectionBindings.get());
        assertEquals(List.of(true), manager.persistedActivations);
    }

    private static GraphDocument graph() {
        return new GraphDocument(RESOURCE, 1L, BINDING, List.of(), List.of());
    }

    private static final class TestStudioScreen extends StudioScreen {
        private final String serverId;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger bound = new AtomicInteger();
        private final AtomicInteger activations = new AtomicInteger();
        private final AtomicInteger selections = new AtomicInteger();
        private final AtomicInteger projectionBindings = new AtomicInteger();
        private long now;
        private String failureReason = "";
        private boolean dirty;
        private boolean interceptBindings = true;
        private boolean ready;
        private boolean terminal;

        private TestStudioScreen(String serverId, long now) {
            this.serverId = serverId;
            this.now = now;
        }

        @Override
        protected String studioServerId() {
            return serverId;
        }

        @Override
        protected long coreOpenNow() {
            return now;
        }

        @Override
        protected void requestCoreOpenResource(FlowManager manager, ReSyncResourceType type, String id) {
            requests.incrementAndGet();
        }

        @Override
        protected void notifyCoreGraphOpenFailed(String reason) {
            failureReason = reason;
            failures.incrementAndGet();
        }

        @Override
        protected boolean bindStudioCoreDocument(String type, String id, String title, CoreGraphEditorSession session,
                                                 boolean activate) {
            if (!interceptBindings) {
                return super.bindStudioCoreDocument(type, id, title, session, activate);
            }
            bound.incrementAndGet();
            if (activate) {
                activations.incrementAndGet();
            }
            String key = ReSyncProjectMetadata.resourceKey(type, id);
            StudioDocument previous = findStudioDocument(key);
            StudioViewportState viewport = previous != null ? previous.viewport() : new StudioViewportState();
            StudioDocument document = new StudioDocument(type, id, title, null, session, null, viewport);
            if (previous != null) {
                studioDocuments.set(studioDocuments.indexOf(previous), document);
            } else {
                studioDocuments.add(document);
            }
            if (activate) {
                activeStudioDocument = document;
            }
            return true;
        }

        @Override
        protected boolean coreStudioDocumentReady(StudioDocument document) {
            return ready;
        }

        @Override
        protected boolean coreStudioDocumentTerminal(StudioDocument document) {
            return terminal;
        }

        @Override
        protected void beforeStudioDocumentSelection() {
            selections.incrementAndGet();
        }

        @Override
        protected void afterStudioDocumentSelected(StudioDocument document) {
            projectionBindings.incrementAndGet();
        }

        @Override
        public boolean hasUnsavedChanges() {
            return dirty;
        }

        private void beginOpen() {
            openStudioGraphDocument("flow", RESOURCE.id(), "Open", null);
        }

        private void advanceTo(long now) {
            this.now = now;
        }

        private void drain() {
            drainCoreOpenIntents();
        }

        private void retainActiveDocument() {
            StudioDocument document = new StudioDocument("world", "retained", "Retained", null, null,
                new StudioViewportState());
            studioDocuments.add(document);
            activeStudioDocument = document;
        }

        private void selectRetainedDocument() {
            selectStudioDocument(ReSyncProjectMetadata.resourceKey("world", "retained"));
        }

        private void retainInactiveCoreDocument() {
            retainInactiveCoreDocument(RESOURCE.id());
        }

        private void retainInactiveCoreDocument(String id) {
            studioDocuments.add(new StudioDocument("flow", id, "Open", null, null,
                new StudioViewportState()));
        }

        private void selectCoreDocument() {
            selectCoreDocument(RESOURCE.id());
        }

        private void selectCoreDocument(String id) {
            selectStudioDocument(ReSyncProjectMetadata.resourceKey("flow", id));
        }

        private FlowGraph retainDirtyLegacyDraft() {
            FlowGraph draft = new FlowGraph();
            draft.setId(RESOURCE.id());
            StudioDocument document = new StudioDocument("flow", RESOURCE.id(), "Open", draft, null,
                new StudioViewportState());
            studioDocuments.add(document);
            activeStudioDocument = document;
            dirty = true;
            return draft;
        }

        private StudioViewportState retainActiveCoreDocument(CoreGraphEditorSession session) {
            StudioViewportState viewport = new StudioViewportState();
            StudioDocument document = new StudioDocument("flow", RESOURCE.id(), "Open", null, session, null,
                viewport);
            studioDocuments.add(document);
            activeStudioDocument = document;
            return viewport;
        }
    }

    private static final class CoreAuthorityManager extends FlowManager {
        private CoreGraphEditorSession session;
        private ReSyncFlowClient.CoreGraphActivationState activationState =
            ReSyncFlowClient.CoreGraphActivationState.PENDING;
        private String activationReason = "The Core graph connection is unavailable.";
        private final List<Boolean> persistedActivations = new ArrayList<>();

        private CoreAuthorityManager() {
            super(null, null);
        }

        @Override
        public ReSyncFlowClient existingFlowClient(String serverId) {
            return null;
        }

        @Override
        public ReSyncFlowClient.CoreGraphActivationOutcome peekCoreGraphActivation(
            String serverId, ReSyncResourceType type, String id) {
            ReSyncFlowClient.CoreGraphActivationState state = session != null
                ? ReSyncFlowClient.CoreGraphActivationState.LIVE : activationState;
            return new ReSyncFlowClient.CoreGraphActivationOutcome(state,
                state == ReSyncFlowClient.CoreGraphActivationState.LIVE ? "" : activationReason,
                state == ReSyncFlowClient.CoreGraphActivationState.PENDING ? 0L : 1L);
        }

        @Override
        public Optional<CoreGraphEditorSession> peekCoreGraphEditorSession(
            String serverId, ReSyncResourceType type, String id) {
            return Optional.ofNullable(session);
        }

        @Override
        public void persistOpenProjectDocument(String serverId, String type, String id, String title,
                                               boolean activate) {
            persistedActivations.add(activate);
        }
    }
}
