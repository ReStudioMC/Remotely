package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ProtocolEnvelope;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerCoreSaveSettlementTest {
    private static final ServerId SERVER = ServerId.deterministic("flow-manager-core-save-settlement");
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "0".repeat(64), "1".repeat(64));
    private static final NodeInstanceId NODE = NodeInstanceId.deterministic("node:save-settlement");
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("flow")), "save-settlement");

    @Test
    void recreatedRevisionZeroTemplateSettlesAtTheNextDurableRevision() throws Exception {
        GraphDocument baseline = graph(0L, 0.0, 0.0);
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        ContentHash submittedHash = session.checksum();
        GraphDocument authoritative = withRevision(baseline, 3L);

        FlowManager.CoreGraphSaveSettlement settlement = settle(session, authoritative, 0L, 3L, submittedHash);

        assertEquals(FlowManager.CoreGraphSaveSettlement.SETTLED, settlement);
        assertEquals(3L, session.baselineRevision());
        assertEquals(authoritative.checksum(), session.checksum());
        assertFalse(session.isDirty());
    }

    @Test
    void exactSubmittedSessionMarksTheAuthoritativeRevisionSaved() throws Exception {
        GraphDocument baseline = graph(1L, 0.0, 0.0);
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        session.setNodePosition(NODE, 12.0, 24.0);
        ContentHash submittedHash = session.checksum();
        GraphDocument authoritative = withRevision(session.graphDocument(), 2L);

        FlowManager.CoreGraphSaveSettlement settlement = settle(session, authoritative, 1L, 2L, submittedHash);

        assertEquals(FlowManager.CoreGraphSaveSettlement.SETTLED, settlement);
        assertTrue(settlement.editorUpdated());
        assertEquals(2L, session.baselineRevision());
        assertEquals(authoritative.checksum(), session.checksum());
        assertFalse(session.isDirty());
    }

    @Test
    void newerLocalEditsRebaseOverTheAcknowledgedSaveWithoutClearingDirtyState() throws Exception {
        GraphDocument submitted = graph(1L, 0.0, 0.0);
        CoreGraphEditorSession session = new CoreGraphEditorSession(submitted);
        ContentHash submittedHash = session.checksum();
        session.setNodePosition(NODE, 32.0, 48.0);
        GraphDocument authoritative = withRevision(submitted, 2L);

        FlowManager.CoreGraphSaveSettlement settlement = settle(session, authoritative, 1L, 2L, submittedHash);

        assertEquals(FlowManager.CoreGraphSaveSettlement.REBASED, settlement);
        assertTrue(settlement.settled());
        assertFalse(settlement.editorUpdated());
        assertEquals(2L, session.baselineRevision());
        assertTrue(session.isDirty());
        assertEquals(32.0, session.graphDocument().nodes().getFirst().x());
        assertEquals(48.0, session.graphDocument().nodes().getFirst().y());
    }

    @Test
    void alreadyHydratedAuthoritativeBaselineSettlesWithoutClearingLaterEdits() throws Exception {
        GraphDocument authoritative = graph(2L, 0.0, 0.0);
        CoreGraphEditorSession session = new CoreGraphEditorSession(authoritative);
        session.setNodePosition(NODE, 32.0, 48.0);

        FlowManager.CoreGraphSaveSettlement settlement = settle(session, authoritative, 1L, 2L,
            graph(1L, 0.0, 0.0).checksum());

        assertEquals(FlowManager.CoreGraphSaveSettlement.REBASED, settlement);
        assertFalse(settlement.editorUpdated());
        assertEquals(2L, session.baselineRevision());
        assertTrue(session.isDirty());
        assertEquals(32.0, session.graphDocument().nodes().getFirst().x());
    }

    @Test
    void cleanSessionThatDoesNotMatchTheSubmittedSnapshotRequiresHydration() throws Exception {
        GraphDocument baseline = graph(1L, 0.0, 0.0);
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        GraphDocument submitted = graph(1L, 12.0, 24.0);
        GraphDocument authoritative = withRevision(submitted, 2L);

        FlowManager.CoreGraphSaveSettlement settlement = settle(session, authoritative, 1L, 2L,
            submitted.checksum());

        assertEquals(FlowManager.CoreGraphSaveSettlement.REHYDRATION_REQUIRED, settlement);
        assertEquals(1L, session.baselineRevision());
        assertEquals(baseline.checksum(), session.checksum());
        assertFalse(session.isDirty());
    }

    @Test
    void currentRetainedEditorRequiresHydrationWithoutLosingItsDraft() throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null);
        FlowManager manager = new FlowManager(null, null);
        FlowManagerTestConnection.install(manager, SERVER.canonicalText(), client, transport);
        try {
            FlowManager.ServerConnectionToken token = manager.captureServerConnectionToken(SERVER.canonicalText(), client);
            GraphDocument submitted = graph(1L, 0.0, 0.0);
            CoreGraphEditorSession session = new CoreGraphEditorSession(submitted);
            session.setNodePosition(NODE, 32.0, 48.0);
            putSession(manager, session, token, false);
            GraphDocument authoritative = withRevision(submitted, 2L);

            FlowManager.CoreGraphSaveSettlement settlement = manager.settleCoreGraphEditorSessionSaveAuthoritative(
                token, result(submitted.checksum(), authoritative), submitted.checksum(), authoritative);

            assertEquals(FlowManager.CoreGraphSaveSettlement.REHYDRATION_REQUIRED, settlement);
            assertTrue(settlement.retryable());
            assertTrue(session.isDirty());
            assertEquals(32.0, session.graphDocument().nodes().getFirst().x());
            assertTrue(sessionStale(manager));
            assertEquals(1, pendingHydrationCount(manager));
        } finally {
            manager.shutdown();
            client.shutdown();
        }
    }

    @Test
    void acceptedAckWithoutAnOpenEditorSettlesWithoutClaimingAnEditorUpdate() throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null);
        FlowManager manager = new FlowManager(null, null);
        FlowManagerTestConnection.install(manager, SERVER.canonicalText(), client, transport);
        try {
            FlowManager.ServerConnectionToken token = manager.captureServerConnectionToken(SERVER.canonicalText(), client);
            GraphDocument submitted = graph(1L, 0.0, 0.0);
            GraphDocument authoritative = withRevision(submitted, 2L);

            FlowManager.CoreGraphSaveSettlement settlement = manager.settleCoreGraphEditorSessionSaveAuthoritative(
                token, result(submitted.checksum(), authoritative), submitted.checksum(), authoritative);

            assertEquals(FlowManager.CoreGraphSaveSettlement.NO_OPEN_SESSION, settlement);
            assertTrue(settlement.settled());
            assertFalse(settlement.editorUpdated());
            assertFalse(settlement.retryable());
        } finally {
            manager.shutdown();
            client.shutdown();
        }
    }

    @Test
    void payloadIdentityAndGenerationFencesRejectBeforeSessionSettlement() throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null);
        FlowManager manager = new FlowManager(null, null);
        FlowManagerTestConnection.install(manager, SERVER.canonicalText(), client, transport);
        try {
            FlowManager.ServerConnectionToken token = manager.captureServerConnectionToken(SERVER.canonicalText(), client);
            GraphDocument submitted = graph(1L, 0.0, 0.0);
            GraphDocument authoritative = withRevision(submitted, 2L);

            assertEquals(FlowManager.CoreGraphSaveSettlement.REJECTED,
                manager.settleCoreGraphEditorSessionSaveAuthoritative(token, result(submitted.checksum(), authoritative),
                    submitted.checksum(), withRevision(authoritative, 3L)));
            assertEquals(FlowManager.CoreGraphSaveSettlement.REJECTED,
                manager.settleCoreGraphEditorSessionSaveAuthoritative(new FlowManager.ServerConnectionToken(
                    SERVER.canonicalText(), client, token.generation() + 1L), result(submitted.checksum(), authoritative),
                    submitted.checksum(), authoritative));
        } finally {
            manager.shutdown();
            client.shutdown();
        }
    }

    private static FlowManager.CoreGraphSaveSettlement settle(CoreGraphEditorSession session,
                                                               GraphDocument authoritative,
                                                               long expectedRevision, long revision,
                                                               ContentHash submittedHash) throws Exception {
        Method method = FlowManager.class.getDeclaredMethod("settleCoreGraphEditorSession",
            CoreGraphEditorSession.class, Object.class, long.class, long.class, ContentHash.class);
        method.setAccessible(true);
        FlowManager manager = new FlowManager(null, null);
        try {
            return (FlowManager.CoreGraphSaveSettlement) method.invoke(manager, session, authoritative,
                expectedRevision, revision, submittedHash);
        } finally {
            manager.shutdown();
        }
    }

    private static ReSyncFlowClient.CoreGraphMutationResult result(ContentHash submittedHash,
                                                                    GraphDocument authoritative) {
        UUID mutationId = UUID.randomUUID();
        return new ReSyncFlowClient.CoreGraphMutationResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            mutationId, ResourceOperationKind.SAVE, RESOURCE, 1L, submittedHash, authoritative.revision(), mutationId,
            ContentHash.of("f".repeat(64)), ProtocolEnvelope.Status.OK, false);
    }

    private static GraphDocument graph(long revision, double x, double y) {
        GraphNode node = new GraphNode(NODE, ContractRef.of(OWNER, NodeId.of("test-node")), 1, null, Map.of(),
            Map.of(), List.of(), List.of(), InspectorState.empty(), x, y, OpaqueData.empty());
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, revision, BINDING, Set.of(), List.of(node),
            List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private static GraphDocument withRevision(GraphDocument source, long revision) {
        return new GraphDocument(source.schemaVersion(), source.resource(), revision, source.catalogBinding(),
            source.requiredCapabilities(), source.nodes(), source.connections(), source.variables(), source.functions(),
            source.unknown());
    }

    @SuppressWarnings("unchecked")
    private static void putSession(FlowManager manager, CoreGraphEditorSession session,
                                   FlowManager.ServerConnectionToken token, boolean stale) throws Exception {
        Field sessionsField = FlowManager.class.getDeclaredField("coreGraphEditorSessions");
        sessionsField.setAccessible(true);
        Map<Object, Object> sessions = (Map<Object, Object>) sessionsField.get(manager);
        sessions.put(sessionKey(), sessionState(session, token, stale));
    }

    @SuppressWarnings("unchecked")
    private static boolean sessionStale(FlowManager manager) throws Exception {
        Field sessionsField = FlowManager.class.getDeclaredField("coreGraphEditorSessions");
        sessionsField.setAccessible(true);
        Map<Object, Object> sessions = (Map<Object, Object>) sessionsField.get(manager);
        Object state = sessions.get(sessionKey());
        Method stale = state.getClass().getDeclaredMethod("stale");
        stale.setAccessible(true);
        return (Boolean) stale.invoke(state);
    }

    private static int pendingHydrationCount(FlowManager manager) throws Exception {
        Field field = FlowManager.class.getDeclaredField("coreGraphHydrations");
        field.setAccessible(true);
        return ((FlowManager.CoreGraphHydrationTracker<?>) field.get(manager)).size();
    }

    private static Object sessionKey() throws Exception {
        Class<?> type = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$CoreGraphSessionKey");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, ReSyncResourceType.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(SERVER.canonicalText(), ReSyncResourceType.FLOW, RESOURCE.id());
    }

    private static Object sessionState(CoreGraphEditorSession session, FlowManager.ServerConnectionToken token,
                                       boolean stale) throws Exception {
        Class<?> type = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$CoreGraphSessionState");
        Constructor<?> constructor = type.getDeclaredConstructor(CoreGraphEditorSession.class,
            FlowManager.ServerConnectionToken.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(session, token, stale);
    }
}
