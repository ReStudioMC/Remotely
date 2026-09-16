package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.ui.studio.StudioDocument;
import redxax.oxy.remotely.flow.ui.studio.StudioViewportState;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.cache.GraphDraft;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.protocol.AuthoringTemplateRequest;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerCoreEditorLifecycleTest {
    private static final ServerId SERVER = ServerId.deterministic("flow-manager-core-editor-lifecycle");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "0".repeat(64), "1".repeat(64));
    private static final CatalogBinding NEXT_BINDING = new CatalogBinding(2, "2".repeat(64), "3".repeat(64));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OwnerId.of("test"), ResourceTypeId.of("flow")), "lifecycle");

    @Test
    void staleCoreSessionIsRetainedForRecovery() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(1));
        Map<Object, Object> sessions = sessions(manager);
        Object key = sessionKey(RESOURCE.serverId().canonicalText(), ReSyncResourceType.FLOW, RESOURCE.id());
        Object state = sessionState(session, new FlowManager.ServerConnectionToken(
            RESOURCE.serverId().canonicalText(), null, 1L), false);
        sessions.put(key, state);

        invoke(manager, "markCoreGraphEditorSessionsStale", String.class, RESOURCE.serverId().canonicalText());

        Object retained = sessions.get(key);
        assertNotNull(retained);
        assertSame(session, invokeNoArgs(retained, "session"));
        assertTrue((Boolean) invokeNoArgs(retained, "stale"));
        assertFalse(session.isDirty());
        assertTrue(manager.isCoreGraphEditorSessionDirty(RESOURCE.serverId().canonicalText(), ReSyncResourceType.FLOW,
            RESOURCE.id(), session));
    }

    @Test
    void revisionZeroDiscardRemovesSessionAndTemplateDraft() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        GraphDocument document = graph(0);
        CoreGraphEditorSession session = new CoreGraphEditorSession(document);
        Map<Object, Object> sessions = sessions(manager);
        Object key = sessionKey(RESOURCE.serverId().canonicalText(), ReSyncResourceType.FLOW, RESOURCE.id());
        Object state = sessionState(session, new FlowManager.ServerConnectionToken(
            RESOURCE.serverId().canonicalText(), null, 1L), false);
        sessions.put(key, state);

        CoreGraphDocumentAuthoringAdapter adapter = manager.coreGraphDocumentAuthoring();
        Field draftsField = CoreGraphDocumentAuthoringAdapter.class.getDeclaredField("drafts");
        draftsField.setAccessible(true);
        Map<Object, GraphDraft> drafts = (Map<Object, GraphDraft>) draftsField.get(adapter);
        Class<?> identityType = Class.forName(
            "redxax.oxy.remotely.data.flow.CoreGraphDocumentAuthoringAdapter$Identity");
        var identityFrom = identityType.getDeclaredMethod("from", ServerResourceLocator.class);
        identityFrom.setAccessible(true);
        drafts.put(identityFrom.invoke(null, RESOURCE), new GraphDraft(RESOURCE, 0L, document));
        assertTrue(manager.coreGraphDraft(RESOURCE).isPresent());

        assertTrue(manager.discardCoreGraphSession(RESOURCE.serverId().canonicalText(), ReSyncResourceType.FLOW,
            RESOURCE.id()));
        assertFalse(sessions.containsKey(key));
        assertTrue(manager.coreGraphDraft(RESOURCE).isEmpty());
    }

    @Test
    void staleRetainedSessionCanDiscardItsLocalChanges() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(1));
        session.mutateGraph(current -> new GraphDocument(current.schemaVersion(), current.resource(),
            current.revision(), current.catalogBinding(), current.requiredCapabilities(), current.nodes(),
            current.connections(), current.variables(), current.functions(), OpaqueData.of(Map.of("draft", true))));
        Map<Object, Object> sessions = sessions(manager);
        Object key = sessionKey(RESOURCE.serverId().canonicalText(), ReSyncResourceType.FLOW, RESOURCE.id());
        sessions.put(key, sessionState(session, new FlowManager.ServerConnectionToken(
            RESOURCE.serverId().canonicalText(), null, 1L), true));

        assertTrue(session.isDirty());
        assertTrue(manager.ownsCoreGraphEditorSession(RESOURCE.serverId().canonicalText(), ReSyncResourceType.FLOW,
            RESOURCE.id(), session));
        assertTrue(manager.discardCoreGraphSession(RESOURCE.serverId().canonicalText(), ReSyncResourceType.FLOW,
            RESOURCE.id()));
        assertFalse(session.isDirty());
        assertTrue(session.graphDocument().unknown().isEmpty());
    }

    @Test
    void retainedDirtySessionRebasesOntoThePublishedCoreBaseline(@TempDir Path tempDirectory) throws Exception {
        CatalogCachePublication initial = publication(BINDING, 1L);
        CatalogCachePublication replacement = publication(NEXT_BINDING, 2L);
        FlowManager manager = new FlowManager(null, null);
        try (ReSyncFlowClientTestHarness harness = ReSyncFlowClientTestHarness.connect(SERVER, null, manager,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog.json"))), initial)) {
            ReSyncFlowClient client = harness.client();
            FlowManager.ServerConnectionToken token = manager.captureServerConnectionToken(SERVER.canonicalText(), client);
            GraphDocument original = graph(1L, BINDING, OpaqueData.empty());
            CoreGraphEditorSession session = new CoreGraphEditorSession(original, BINDING,
                CatalogCachePublicationCodec.authoringPublicationChecksum(initial.authoringPublication()), Set.of(), Set.of());
            session.mutateGraph(current -> graph(current.revision(), current.catalogBinding(),
                OpaqueData.of(Map.of("localDraft", true))));
            sessions(manager).put(sessionKey(SERVER.canonicalText(), ReSyncResourceType.FLOW, RESOURCE.id()),
                sessionState(session, token, false));
            coreProjection(manager).apply(ReSyncResourceType.FLOW, state(original), false, 1L);

            assertTrue(client.requestCatalogPublication(true));
            harness.transport().receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(replacement), 8);
            awaitCatalogBinding(harness, NEXT_BINDING);
            GraphDocument latest = graph(2L, NEXT_BINDING, OpaqueData.of(Map.of("remoteState", true)));
            coreProjection(manager).apply(ReSyncResourceType.FLOW, state(latest), false, 1L);

            assertTrue(manager.isCurrentCoreGraphEditorSession(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                RESOURCE.id(), session));
            assertEquals(NEXT_BINDING, session.catalogBinding());
            assertEquals(2L, session.baselineRevision());
            assertTrue(session.graphDocument().unknown().contains("localDraft"));
            assertTrue(session.graphDocument().unknown().contains("remoteState"));
            assertTrue(session.isDirty());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void rejectedTemplateDuringCatalogRolloverIsDeferredForRetry() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        try {
            Class<?> intentType = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$CoreTemplateIntent");
            Constructor<?> constructor = intentType.getDeclaredConstructor(ServerResourceLocator.class, String.class,
                String.class, FlowManager.CreationMetadataIntent.class, Consumer.class);
            constructor.setAccessible(true);
            Object intent = constructor.newInstance(RESOURCE, "Lifecycle", null, null, null);
            UUID requestId = UUID.randomUUID();
            Map<Object, Object> pending = mapField(manager, "pendingCoreTemplateIntents");
            Map<Object, Object> deferredIntents = mapField(manager, "deferredCoreTemplateIntents");
            pending.put(requestId, intent);
            var method = FlowManager.class.getDeclaredMethod("deferCoreTemplateRollover", UUID.class, intentType,
                AuthoringTemplateRequest.class, AuthoringTemplateResponse.class, String.class, boolean.class);
            method.setAccessible(true);

            assertTrue((Boolean) method.invoke(manager, requestId, intent, null, null,
                CoreGraphDocumentAuthoringAdapter.AUTHORING_TEMPLATE_STALE, true));
            assertFalse(pending.containsKey(requestId));
            Object deferred = deferredIntents.get(RESOURCE);
            assertNotNull(deferred);
            assertEquals(1, invokeNoArgs(deferred, "rolloverAttempts"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void studioDocumentRejectsAmbiguousOrMismatchedCoreState() {
        FlowGraph legacy = new FlowGraph(RESOURCE.id(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(1));

        StudioDocument document = new StudioDocument("flow", RESOURCE.id(), "Flow", legacy, null, null,
            new StudioViewportState());
        assertTrue(document.isLegacyGraphDocument());
        assertFalse(document.isCoreDocument());
        assertEquals("FLOW:" + RESOURCE.id(), document.key());

        assertThrows(IllegalArgumentException.class, () -> new StudioDocument("flow", RESOURCE.id(), "Flow", legacy,
            session, null, new StudioViewportState()));
        assertThrows(IllegalArgumentException.class, () -> new StudioDocument("flow", "other", "Flow", null,
            session, null, new StudioViewportState()));
    }

    @Test
    void coreSessionReconciliationDoesNotAcquireConnectionStateWhileHoldingSessionState() throws Exception {
        LockCheckingFlowManager manager = new LockCheckingFlowManager();
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(1));
        Map<Object, Object> sessions = sessions(manager);
        Object key = sessionKey(RESOURCE.serverId().canonicalText(), ReSyncResourceType.FLOW, RESOURCE.id());
        FlowManager.ServerConnectionToken token = new FlowManager.ServerConnectionToken(
            RESOURCE.serverId().canonicalText(), null, 1L);
        Object state = sessionState(session, token, false);
        manager.sessionState = sessions;

        synchronized (sessions) {
            invokeReconcile(manager, key, state, token);
        }

        assertFalse(manager.connectionStateAcquiredWhileHoldingSessionState.get());
    }

    private static GraphDocument graph(long revision) {
        return graph(revision, BINDING, OpaqueData.empty());
    }

    private static GraphDocument graph(long revision, CatalogBinding binding, OpaqueData unknown) {
        return new GraphDocument(GraphDocument.CURRENT_SCHEMA_VERSION, RESOURCE, revision, binding, Set.of(), List.of(),
            List.of(), List.of(), List.of(), unknown);
    }

    private static GraphResourceState state(GraphDocument graph) {
        return GraphResourceState.live(graph.resource(), graph.revision(), UUID.randomUUID(),
            ContentHash.of("4".repeat(64)), ContentHash.of("5".repeat(64)), graph,
            ResourceActivationState.ACTIVE);
    }

    private static CatalogCachePublication publication(CatalogBinding binding, long revision) {
        CatalogProjectionVersion version = CatalogProjectionVersion.current();
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(binding, new CatalogVersion(1, 0),
            version, List.of(
                authoringSection(CatalogAuthoringPublication.Section.TYPES),
                authoringSection(CatalogAuthoringPublication.Section.EDITORS),
                authoringSection(CatalogAuthoringPublication.Section.PREVIEWS),
                authoringSection(CatalogAuthoringPublication.Section.CAPABILITIES)), Set.of());
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            new CatalogCacheKey(SERVER, binding, version), binding, revision, List.of(), authoring, Map.of());
    }

    private static CatalogAuthoringPublication.SectionProjection authoringSection(
        CatalogAuthoringPublication.Section section) {
        return new CatalogAuthoringPublication.SectionProjection(section, true, true, CatalogCacheState.ACTIVE,
            List.of());
    }

    private static void awaitCatalogBinding(ReSyncFlowClientTestHarness harness, CatalogBinding binding)
        throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (System.nanoTime() < deadline) {
            harness.drain();
            if (harness.client().activeCatalogAuthoringPublication()
                .map(CatalogAuthoringPublication::binding).filter(binding::equals).isPresent()) {
                return;
            }
            Thread.onSpinWait();
        }
        assertEquals(binding, harness.client().activeCatalogAuthoringPublication().orElseThrow().binding());
    }

    private static CoreGraphUiProjection coreProjection(FlowManager manager) throws Exception {
        Field field = FlowManager.class.getDeclaredField("coreGraphUiProjection");
        field.setAccessible(true);
        return (CoreGraphUiProjection) field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> mapField(FlowManager manager, String name) throws Exception {
        Field field = FlowManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(manager);
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

    private static Object invoke(FlowManager manager, String name, Class<?> type, Object value) throws Exception {
        var method = FlowManager.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(manager, value);
    }

    private static Object invokeNoArgs(Object value, String name) throws Exception {
        var method = value.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }

    private static Object invokeReconcile(FlowManager manager, Object key, Object state,
                                          FlowManager.ServerConnectionToken token) throws Exception {
        var method = FlowManager.class.getDeclaredMethod("reconcileCoreGraphSession", key.getClass(), state.getClass(),
            FlowManager.ServerConnectionToken.class);
        method.setAccessible(true);
        return method.invoke(manager, key, state, token);
    }

    private static final class LockCheckingFlowManager extends FlowManager {
        private final AtomicBoolean connectionStateAcquiredWhileHoldingSessionState = new AtomicBoolean();
        private Map<Object, Object> sessionState;

        private LockCheckingFlowManager() {
            super(null, null);
        }

        @Override
        public boolean isCurrentServerConnection(ServerConnectionToken token) {
            if (sessionState != null && Thread.holdsLock(sessionState)) {
                connectionStateAcquiredWhileHoldingSessionState.set(true);
            }
            return true;
        }
    }
}
