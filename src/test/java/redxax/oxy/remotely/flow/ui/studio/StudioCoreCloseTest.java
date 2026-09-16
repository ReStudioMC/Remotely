package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.TabsManager;
import restudio.rescreen.theme.ThemeManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StudioCoreCloseTest {
    private static final ServerId SERVER = ServerId.deterministic("studio-core-close");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "0".repeat(64), "1".repeat(64));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OwnerId.of("test"), ResourceTypeId.of("flow")), "close");

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void staleCoreDocumentCannotBeDiscardedForClose() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph());
        Object key = sessionKey(RESOURCE.serverId().canonicalText(), ReSyncResourceType.FLOW, RESOURCE.id());
        Object state = sessionState(session, new FlowManager.ServerConnectionToken(
            RESOURCE.serverId().canonicalText(), null, 1L), true);
        sessions(manager).put(key, state);

        TestStudioScreen screen = new TestStudioScreen(RESOURCE.serverId().canonicalText());
        StudioDocument document = new StudioDocument("flow", RESOURCE.id(), "Close", null, session, null,
            new StudioViewportState());
        screen.studioDocuments.add(document);
        screen.pendingStudioTabDiscards.put(document.key(), System.currentTimeMillis() + 1_000L);
        TabsManager.Tab tab = new TabsManager.Tab("Close", new Container("close", 0, 0, 1, 1));
        tab.setData(document.key());

        assertFalse(screen.requestClose(tab));
        assertSame(session, invokeNoArgs(sessions(manager).get(key), "session"));
    }

    @Test
    void coreAuthorityNeverMountsLegacyGraphWhileSessionIsPending() {
        CoreAuthorityManager manager = new CoreAuthorityManager();
        try {
            TestStudioScreen screen = new TestStudioScreen(RESOURCE.serverId().canonicalText());
            FlowGraph graph = new FlowGraph(RESOURCE.id(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());

            screen.openGraph(graph);

            assertTrue(screen.studioDocuments.isEmpty());
            assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void unavailableAuthorityDoesNotMountCachedLegacyGraphWhileCoreIsPending() {
        CoreAuthorityManager manager = new CoreAuthorityManager();
        try {
            manager.coreRequired = false;
            TestStudioScreen screen = new TestStudioScreen(RESOURCE.serverId().canonicalText());
            FlowGraph graph = new FlowGraph(RESOURCE.id(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());

            screen.openGraph(graph);

            assertTrue(screen.studioDocuments.isEmpty());
            assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void dirtyTerminalLegacyDraftIsPreservedAcrossCoreAuthorityTransition() throws Exception {
        CoreAuthorityManager manager = new CoreAuthorityManager();
        try {
            CoreGraphEditorSession session = new CoreGraphEditorSession(graph());
            manager.session = session;
            TestStudioScreen screen = new TestStudioScreen(RESOURCE.serverId().canonicalText());
            FlowGraph legacy = new FlowGraph(RESOURCE.id(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
            StudioDocument document = new StudioDocument("flow", RESOURCE.id(), "Core", legacy, null, null,
                new StudioViewportState());
            screen.mountLegacy(document, true, true);

            screen.select(document.key());

            assertEquals(1, screen.studioDocuments.size());
            StudioDocument preserved = screen.studioDocuments.getFirst();
            assertSame(document, preserved);
            assertSame(legacy, preserved.graph());
            assertNull(preserved.coreSession());
            assertSame(document, screen.activeStudioDocument);
            assertTrue(screen.hasPendingCoreOpenIntent("flow", RESOURCE.id()));
            assertFalse(screen.coreStudioLoadingVisible());
            assertEquals(0, screen.closed.get());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void cleanTerminalLegacyCompatibilityCanUpgradeToCurrentCoreSession() throws Exception {
        CoreAuthorityManager manager = new CoreAuthorityManager();
        try {
            CoreGraphEditorSession session = new CoreGraphEditorSession(graph());
            manager.session = session;
            TestStudioScreen screen = new TestStudioScreen(RESOURCE.serverId().canonicalText());
            FlowGraph legacy = new FlowGraph(RESOURCE.id(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
            StudioDocument document = new StudioDocument("flow", RESOURCE.id(), "Core", legacy, null, null,
                new StudioViewportState());
            screen.mountLegacy(document, true, false);

            screen.select(document.key());

            assertEquals(1, screen.studioDocuments.size());
            StudioDocument upgraded = screen.studioDocuments.getFirst();
            assertSame(session, upgraded.coreSession());
            assertNull(upgraded.graph());
            assertEquals(1, screen.closed.get());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void authoritativeRemovalClosesThroughStudioAndSelectsDeterministicNeighbor() {
        TestStudioScreen screen = new TestStudioScreen(RESOURCE.serverId().canonicalText());
        TrackingView first = new TrackingView();
        TrackingView removed = new TrackingView();
        TrackingView last = new TrackingView();
        StudioDocument firstDocument = new StudioDocument("tab", "first", "First", null, first,
            new StudioViewportState());
        StudioDocument removedDocument = new StudioDocument("tab", "removed", "Removed", null, removed,
            new StudioViewportState());
        StudioDocument lastDocument = new StudioDocument("tab", "last", "Last", null, last,
            new StudioViewportState());
        screen.mountDocuments(firstDocument, removedDocument, lastDocument);

        screen.removeDocuments(Set.of(removedDocument.key()));

        assertEquals(List.of(firstDocument, lastDocument), screen.studioDocuments);
        assertSame(lastDocument, screen.activeStudioDocument);
        assertEquals(1, removed.deselected.get());
        assertEquals(1, removed.closed.get());
        assertEquals(1, last.selected.get());
        assertEquals(1, screen.closed.get());
        assertEquals(1, screen.persistedRemoved.get());
    }

    private static GraphDocument graph() {
        return new GraphDocument(RESOURCE, 1L, BINDING, List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> sessions(FlowManager manager) throws Exception {
        Field field = FlowManager.class.getDeclaredField("coreGraphEditorSessions");
        field.setAccessible(true);
        return (ConcurrentHashMap<Object, Object>) field.get(manager);
    }

    private static Object sessionKey(String serverId, ReSyncResourceType type, String id) throws Exception {
        Class<?> keyType = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$CoreGraphSessionKey");
        Constructor<?> constructor = keyType.getDeclaredConstructor(String.class, ReSyncResourceType.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(serverId, type, id);
    }

    private static Object sessionState(CoreGraphEditorSession session, FlowManager.ServerConnectionToken token,
                                       boolean stale) throws Exception {
        Class<?> stateType = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$CoreGraphSessionState");
        Constructor<?> constructor = stateType.getDeclaredConstructor(CoreGraphEditorSession.class,
            FlowManager.ServerConnectionToken.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(session, token, stale);
    }

    private static Object invokeNoArgs(Object value, String name) throws Exception {
        var method = value.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }

    private static final class TestStudioScreen extends StudioScreen {
        private final String serverId;
        private final AtomicInteger closed = new AtomicInteger();
        private final AtomicInteger persistedRemoved = new AtomicInteger();
        private boolean dirty;

        private TestStudioScreen(String serverId) {
            this.serverId = serverId;
        }

        @Override
        protected String studioServerId() {
            return serverId;
        }

        @Override
        protected void studioDocumentClosed(StudioDocument document) {
            closed.incrementAndGet();
        }

        @Override
        protected void removePersistedOpenStudioDocument(String type, String id) {
            persistedRemoved.incrementAndGet();
        }

        @Override
        public boolean hasUnsavedChanges() {
            return dirty;
        }

        private void openGraph(FlowGraph graph) {
            openStudioGraphDocument("flow", RESOURCE.id(), "Core", graph);
        }

        private void select(String key) {
            selectStudioDocument(key);
        }

        @SuppressWarnings("unchecked")
        private void mountLegacy(StudioDocument document, boolean terminalCompatibility, boolean dirty) throws Exception {
            studioDocuments.add(document);
            activeStudioDocument = document;
            this.dirty = dirty;
            if (terminalCompatibility) {
                Field field = StudioScreen.class.getDeclaredField("terminalLegacyCoreDocuments");
                field.setAccessible(true);
                ((Set<String>) field.get(this)).add(document.key());
            }
        }

        private boolean requestClose(TabsManager.Tab tab) throws Exception {
            Method method = StudioScreen.class.getDeclaredMethod("requestStudioTabClose", TabsManager.Tab.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(this, tab);
        }

        private void mountDocuments(StudioDocument first, StudioDocument active, StudioDocument last) {
            studioDocuments.add(first);
            studioDocuments.add(active);
            studioDocuments.add(last);
            activeStudioDocument = active;
        }

        private void removeDocuments(Set<String> keys) {
            removeStudioDocuments(keys);
        }
    }

    private static final class TrackingView implements ReSyncStudioView {
        private final AtomicInteger selected = new AtomicInteger();
        private final AtomicInteger deselected = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();

        @Override
        public void selected() {
            selected.incrementAndGet();
        }

        @Override
        public void deselected() {
            deselected.incrementAndGet();
        }

        @Override
        public void closed() {
            closed.incrementAndGet();
        }

        @Override
        public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        }
    }

    private static final class CoreAuthorityManager extends FlowManager {
        private CoreGraphEditorSession session;
        private boolean coreRequired = true;

        private CoreAuthorityManager() {
            super(null, null);
        }

        @Override
        public boolean coreGraphCreationRequired(String serverId, ReSyncResourceType type) {
            return coreRequired;
        }

        @Override
        public boolean isCoreGraphAuthoritative(String serverId, ReSyncResourceType type, String id) {
            return false;
        }

        @Override
        public ReSyncFlowClient existingFlowClient(String serverId) {
            return null;
        }

        @Override
        public ReSyncFlowClient ensureFlowClient(String serverId) {
            return null;
        }

        @Override
        public CompletableFuture<ReSyncFlowClient> ensureFlowClientAsync(String serverId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public ReSyncFlowClient.CoreGraphActivationOutcome peekCoreGraphActivation(
            String serverId, ReSyncResourceType type, String id) {
            ReSyncFlowClient.CoreGraphActivationState state = session != null
                ? ReSyncFlowClient.CoreGraphActivationState.LIVE : ReSyncFlowClient.CoreGraphActivationState.PENDING;
            return new ReSyncFlowClient.CoreGraphActivationOutcome(state,
                state == ReSyncFlowClient.CoreGraphActivationState.LIVE
                    ? "" : "The Core graph connection is unavailable.",
                state == ReSyncFlowClient.CoreGraphActivationState.LIVE ? 1L : 0L);
        }

        @Override
        public Optional<CoreGraphEditorSession> peekCoreGraphEditorSession(String serverId, ReSyncResourceType type,
                                                                           String id) {
            return Optional.ofNullable(session);
        }

        @Override
        public boolean isCurrentCoreGraphEditorSession(String serverId, ReSyncResourceType type, String id,
                                                       CoreGraphEditorSession candidate) {
            return candidate != null && candidate == session;
        }
    }
}
