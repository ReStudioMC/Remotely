package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.ui.FlowEditorScreen;
import redxax.oxy.remotely.flow.ui.FlowNodeWidget;
import redxax.oxy.remotely.flow.ui.GraphEditorScreen;
import redxax.oxy.remotely.flow.ui.NodeWidget;
import redxax.oxy.remotely.flow.ui.studio.ScreenBackedStudioView;
import redxax.oxy.remotely.flow.ui.studio.StudioDocument;
import redxax.oxy.remotely.test.TestDrawContext;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ProtocolEnvelope;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAuthoringRecoveryAcceptanceTest {
    private static final ServerId SERVER = ServerId.deterministic("core-authoring-recovery-acceptance");
    private static final String SERVER_ID = SERVER.canonicalText();
    private static final OwnerId PROTOCOL_OWNER = OwnerId.of("restudio.resync");
    private static final OwnerId NODE_OWNER = OwnerId.of("restudio.resync");
    private static final NodeId SOURCE_NODE = NodeId.of("branch.all");
    private static final NodeId TARGET_NODE = NodeId.of("break.loop");
    private static final NodeId STRING_NODE = NodeId.of("literal.text");
    private static final PinId STRING_PIN = PinId.of("message");
    private static final ContractRef<CapabilityId> STRING_EDITOR = ContractRef.of(NODE_OWNER, CapabilityId.of("generic-editor"));
    private static final String DESCRIPTOR_FIXTURE =
        "/redxax/oxy/remotely/data/flow/production-flow-control-node-descriptors.json";
    private static final CatalogBinding BINDING = new CatalogBinding(1L, "5".repeat(64), "6".repeat(64));

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void commandFlowAndFunctionTypeDeleteSaveAndReopenThroughRealWidgetsAndFrames() throws Exception {
        Probe probe = new Probe();
        CatalogCachePublication publication = scalarPublication(true);
        try (AcceptanceHarness harness = connect(probe, cache("scalar-editor"), publication)) {
            AuthoringProtocolPeer peer = new AuthoringProtocolPeer(SERVER, harness.transport());
            for (ReSyncResourceType type : List.of(ReSyncResourceType.COMMAND, ReSyncResourceType.FLOW, ReSyncResourceType.FUNCTION)) {
                String id = "scalar-" + type.typeId();
                NodeInstanceId scalarId = NodeInstanceId.deterministic(id + ":scalar");
                GraphNode scalar = new GraphNode(scalarId, ContractRef.of(NODE_OWNER, STRING_NODE), 1, null,
                    Map.of(), Map.of(), List.of(), List.of(), InspectorState.empty(), 220, 180, OpaqueData.empty());
                peer.seed(type, id, payload(type, id, 1L, List.of(scalar)), 1L, ResourceActivationState.ACTIVE);
                load(harness, peer, type, id);
                CoreGraphEditorSession session = scalarSession(harness, type, id);
                assertTrue(probe.manager.bind(session));
                StudioHost studio = new StudioHost();
                AcceptanceEditor editor = new AcceptanceEditor(type, id, harness.client(), studio);
                ScreenManager screenManager = ScreenManager.getInstance();
                ScreenManager.Snapshot screenSnapshot = screenManager.snapshot();
                try {
                    screenManager.setScreen(studio);
                    studio.width = 900;
                    studio.height = 600;
                    assertSame(studio, screenManager.getCurrentScreen());
                    studio.mount(type, id, editor, session);
                    studio.awaitCore(editor, probe.manager, session);
                    editor.focusValue(scalarId);
                    for (char character : "Hel".toCharArray()) {
                        String before = editor.valueText(scalarId);
                        editor.typeValue(scalarId, character);
                        String expected = before + character;
                        studio.await(() -> expected.equals(scalarValue(session, scalarId)) && editor.isCoreEditorReady());
                    }
                    editor.typeValue(scalarId, 'l');
                    editor.typeValue(scalarId, 'o');
                    studio.await(() -> "Hello".equals(scalarValue(session, scalarId)) && editor.isCoreEditorReady());
                    editor.backspaceValue(scalarId);
                    studio.await(() -> "Hell".equals(scalarValue(session, scalarId)) && editor.isCoreEditorReady());
                    assertEquals("Hell", editor.valueText(scalarId));
                    assertTrue(session.canUndo());
                    editor.toolbarSave();
                    awaitRevision(harness, peer, harness.client(), type, id, 2L);
                    assertEquals("Hell", graph(peer.authoritativePayload(type, id)).nodes().getFirst()
                        .values().get(STRING_PIN).value().value());
                    assertFalse(session.dirty());
                } finally {
                    try {
                        if (screenManager.getCurrentScreen() != studio) {
                            studio.removed();
                        }
                    } finally {
                        screenManager.restore(screenSnapshot);
                    }
                }

                load(harness, peer, type, id);
                CoreGraphEditorSession reopened = scalarSession(harness, type, id);
                assertTrue(probe.manager.bind(reopened));
                StudioHost reopenedStudio = new StudioHost();
                AcceptanceEditor reopenedEditor = new AcceptanceEditor(type, id, harness.client(), reopenedStudio);
                try {
                    reopenedStudio.mount(type, id, reopenedEditor, reopened);
                    reopenedStudio.awaitCore(reopenedEditor, probe.manager, reopened);
                    assertEquals("Hell", scalarValue(reopened, scalarId));
                    assertEquals("Hell", reopenedEditor.valueText(scalarId));
                    reopenedEditor.deleteNodeThroughWidget(scalarId);
                    reopenedStudio.await(() -> {
                        if (!graph(reopened).nodes().isEmpty()) {
                            reopenedEditor.dirtyGeometry(scalarId);
                        }
                        return graph(reopened).nodes().isEmpty() && reopenedEditor.isCoreEditorReady();
                    });
                    for (int frame = 0; frame < 8; frame++) {
                        reopenedEditor.dirtyGeometry(scalarId);
                        reopenedStudio.renderHandler(new TestDrawContext(), 0, 0, 0F);
                        reopenedEditor.assertEmptyTopology();
                    }
                    reopenedEditor.toolbarSave();
                    awaitRevision(harness, peer, harness.client(), type, id, 3L);
                    assertTrue(graph(peer.authoritativePayload(type, id)).nodes().isEmpty());
                    assertTrue(graph(peer.authoritativePayload(type, id)).connections().isEmpty());
                } finally {
                    reopenedStudio.removed();
                }
                load(harness, peer, type, id);
                assertTrue(graph(harness.client().coreGraphResourceCache().state(resource(type, id)).orElseThrow()).nodes().isEmpty());
            }
        } finally {
            probe.close();
        }
    }

    @Test
    void scalarNodeWithoutADeclaredEditorStaysReadOnly() throws Exception {
        Probe probe = new Probe();
        CatalogCachePublication publication = scalarPublication(false);
        try (AcceptanceHarness harness = connect(probe, cache("scalar-read-only"), publication)) {
            AuthoringProtocolPeer peer = new AuthoringProtocolPeer(SERVER, harness.transport());
            String id = "missing-editor";
            NodeInstanceId scalarId = NodeInstanceId.deterministic(id);
            GraphNode scalar = new GraphNode(scalarId, ContractRef.of(NODE_OWNER, STRING_NODE), 1, null,
                Map.of(), Map.of(), List.of(), List.of(), InspectorState.empty(), 220, 180, OpaqueData.empty());
            peer.seed(ReSyncResourceType.FLOW, id, payload(ReSyncResourceType.FLOW, id, 1L, List.of(scalar)), 1L,
                ResourceActivationState.ACTIVE);
            load(harness, peer, ReSyncResourceType.FLOW, id);
            CoreGraphEditorSession session = scalarSession(harness, ReSyncResourceType.FLOW, id);
            assertTrue(probe.manager.bind(session));
            StudioHost studio = new StudioHost();
            AcceptanceEditor editor = new AcceptanceEditor(ReSyncResourceType.FLOW, id, harness.client(), studio);
            try {
                studio.mount(ReSyncResourceType.FLOW, id, editor, session);
                studio.awaitCore(editor, probe.manager, session);
                assertFalse(editor.contentAllowed(scalarId));
                assertTrue(editor.widget(scalarId.canonicalText()).getDefinitionResolutionCause().startsWith("read_only:"));
                assertFalse(session.dirty());
                assertTrue(graph(session).nodes().getFirst().values().isEmpty());
            } finally {
                studio.removed();
            }
        } finally {
            probe.close();
        }
    }

    @Test
    void commandFlowAndFunctionAddRenderAndSaveThroughRealEditorEntryPoints() throws Exception {
        Probe probe = new Probe();
        CatalogCachePublication publication = publication();
        ReSyncCatalogPublicationCache cache = cache("editor");
        try (AcceptanceHarness harness = connect(probe, cache, publication)) {
            AuthoringProtocolPeer peer = new AuthoringProtocolPeer(SERVER, harness.transport());

            for (ReSyncResourceType type : List.of(
                ReSyncResourceType.COMMAND, ReSyncResourceType.FLOW, ReSyncResourceType.FUNCTION)) {
                String id = "visible-" + type.typeId();
                peer.seed(type, id, payload(type, id, 1L, List.of()), 1L, ResourceActivationState.ACTIVE);
                load(harness, peer, type, id);

                CoreGraphEditorSession session = session(
                    harness.client().coreGraphResourceCache().state(resource(type, id)).orElseThrow(),
                    harness.client().activeCatalogAuthoringChecksum().orElseThrow());
                assertTrue(probe.manager.bind(session));
                StudioHost studio = new StudioHost();
                AcceptanceEditor editor = new AcceptanceEditor(type, id, harness.client(), studio);
                NodeInstanceId sourceId;
                NodeInstanceId targetId;
                GraphConnection connection;
                try {
                    studio.mount(type, id, editor, session);
                    studio.awaitCore(editor, probe.manager, session);
                    studio.addNode(editor, session, "Branch All", 1);
                    studio.addNode(editor, session, "Break", 2);
                    GraphNode source = requireNode(graph(session), SOURCE_NODE);
                    GraphNode target = requireNode(graph(session), TARGET_NODE);
                    sourceId = source.instanceId();
                    targetId = target.instanceId();
                    session.setNodePosition(sourceId, 120, 140);
                    session.setNodePosition(targetId, 720, 360);
                    connection = new GraphConnection(ConnectionId.deterministic(id + ":flow-wire"),
                        new GraphEndpoint(sourceId, PinId.of("branch_0")),
                        new GraphEndpoint(targetId, PinId.of("flow")));
                    session.addConnection(connection);
                    studio.await(() -> editor.renderedTopology(sourceId, targetId));
                    studio.await(editor::viewportFitted);
                    assertTopology(graph(session), sourceId, targetId, connection.connectionId());

                    if (type == ReSyncResourceType.COMMAND) {
                        editor.toolbarSave();
                    } else {
                        assertTrue(studio.keyPressed(controlSave(studio)));
                    }
                    awaitRevision(harness, peer, harness.client(), type, id, 2L);

                    GraphResourceState clientState = harness.client().coreGraphResourceCache()
                        .state(resource(type, id)).orElseThrow();
                    assertEquals(2L, clientState.revision());
                    assertEquals(2L, peer.authoritative(type, id).revision());
                    assertTopology(graph(clientState), sourceId, targetId, connection.connectionId());
                    assertTopology(graph(peer.authoritativePayload(type, id)), sourceId, targetId,
                        connection.connectionId());
                    assertFalse(session.dirty());
                } finally {
                    studio.removed();
                }

                load(harness, peer, type, id);
                CoreGraphEditorSession reopenedSession = session(
                    harness.client().coreGraphResourceCache().state(resource(type, id)).orElseThrow(),
                    harness.client().activeCatalogAuthoringChecksum().orElseThrow());
                assertTrue(probe.manager.bind(reopenedSession));
                StudioHost reopenedStudio = new StudioHost();
                AcceptanceEditor reopenedEditor = new AcceptanceEditor(type, id, harness.client(), reopenedStudio);
                try {
                    reopenedStudio.mount(type, id, reopenedEditor, reopenedSession);
                    reopenedStudio.awaitCore(reopenedEditor, probe.manager, reopenedSession);
                    reopenedStudio.await(() -> reopenedEditor.renderedTopology(sourceId, targetId));
                    reopenedStudio.await(reopenedEditor::viewportFitted);
                    assertTopology(graph(reopenedSession), sourceId, targetId, connection.connectionId());
                    FlowNodeWidget deletedWidget = reopenedEditor.widget(targetId.canonicalText());
                    reopenedEditor.deleteNodeThroughWidget(targetId);
                    reopenedStudio.await(() -> graph(reopenedSession).nodes().size() == 1
                        && graph(reopenedSession).connections().isEmpty() && reopenedEditor.isCoreEditorReady());
                    assertFalse(reopenedEditor.indexed(deletedWidget));
                } finally {
                    reopenedStudio.removed();
                }
            }
        } finally {
            probe.close();
        }
    }

    @Test
    void staleDisableAppliesCurrentDocumentThenRetriesAgainstItsRevision() throws Exception {
        Probe probe = new Probe();
        CatalogCachePublication publication = publication();
        try (AcceptanceHarness harness = connect(probe, cache("activation"), publication)) {
            AuthoringProtocolPeer peer = new AuthoringProtocolPeer(SERVER, harness.transport());
            String id = "stale-disable";
            peer.seed(ReSyncResourceType.FLOW, id, payload(ReSyncResourceType.FLOW, id, 1L, List.of()), 1L,
                ResourceActivationState.ACTIVE);
            load(harness, peer, ReSyncResourceType.FLOW, id);

            harness.client().sendResourceActivation(ReSyncResourceType.FLOW, id, false, "disable:first");
            peer.advance(ReSyncResourceType.FLOW, id, ResourceActivationState.ACTIVE);
            assertTrue(peer.pump() > 0);
            harness.drain();

            GraphResourceState conflictCurrent = harness.client().coreGraphResourceCache()
                .state(resource(ReSyncResourceType.FLOW, id)).orElseThrow();
            assertEquals(2L, conflictCurrent.revision());
            assertEquals(ResourceActivationState.ACTIVE, conflictCurrent.activationState());

            harness.client().sendResourceActivation(ReSyncResourceType.FLOW, id, false, "disable:retry");
            assertTrue(peer.pump() > 0);
            harness.drain();

            GraphResourceState settled = harness.client().coreGraphResourceCache()
                .state(resource(ReSyncResourceType.FLOW, id)).orElseThrow();
            assertEquals(3L, settled.revision());
            assertEquals(ResourceActivationState.INACTIVE, settled.activationState());
            assertEquals(ResourceActivationState.INACTIVE,
                peer.authoritative(ReSyncResourceType.FLOW, id).activationState());
        } finally {
            probe.close();
        }
    }

    @Test
    void toolbarSaveTicketSettlesAfterLostAckAndReconnectReplay() throws Exception {
        Probe probe = new Probe();
        CatalogCachePublication publication = publication();
        try (AcceptanceHarness harness = connect(probe, cache("lost-ack"), publication)) {
            AuthoringProtocolPeer peer = new AuthoringProtocolPeer(SERVER, harness.transport());
            String id = "lost-save-callback";
            peer.seed(ReSyncResourceType.COMMAND, id, payload(ReSyncResourceType.COMMAND, id, 1L, List.of()), 1L,
                ResourceActivationState.ACTIVE);
            load(harness, peer, ReSyncResourceType.COMMAND, id);
            CoreGraphEditorSession session = session(harness.client().coreGraphResourceCache()
                .state(resource(ReSyncResourceType.COMMAND, id)).orElseThrow(),
                harness.client().activeCatalogAuthoringChecksum().orElseThrow());
            session.addNode(node("lost-callback-node"));
            assertTrue(probe.manager.bind(session));
            StudioHost studio = new StudioHost();
            AcceptanceEditor editor = new AcceptanceEditor(ReSyncResourceType.COMMAND, id, harness.client(), studio);
            try {
                AtomicReference<DesignerSaveNotifications.SaveTicket> originalTicket = new AtomicReference<>();
                studio.mount(ReSyncResourceType.COMMAND, id, editor, session);
                studio.awaitCore(editor, probe.manager, session);
                editor.setCoreGraphSaveHandler((savedSession, ticket) -> {
                    originalTicket.compareAndSet(null, ticket);
                    return probe.manager.saveCoreGraph(SERVER_ID, ReSyncResourceType.COMMAND, savedSession, ticket);
                });
                peer.dropNextResponse(ResourceOperationKind.SAVE, ReSyncResourceType.COMMAND, id);
                editor.toolbarSave();
                DesignerSaveNotifications.SaveTicket ticket = originalTicket.get();
                assertNotNull(ticket);
                AtomicReference<Boolean> ticketSaved = new AtomicReference<>();
                ticket.whenFinished((saved, updatesState) -> ticketSaved.set(saved));

                awaitPeerSave(harness, peer, ReSyncResourceType.COMMAND, id, 2L);
                ProtocolEnvelope<Map<String, Object>> original = harness.transport()
                    .requireRequest(ResourceOperationKind.SAVE, id);
                assertEquals(ticket.requestId(), original.requestId().toString());
                assertEquals(ticket.mutationId(), original.mutationId().toString());
                assertEquals(2L, peer.authoritative(ReSyncResourceType.COMMAND, id).revision());

                harness.transport().close();
                harness.drain();
                assertTrue(DesignerSaveNotifications.isPending(ticket));
                assertNull(ticketSaved.get());

                reopen(harness.transport());
                establish(probe, harness.client(), harness.transport(), publication);
                awaitTicketSettlement(harness, peer, ticketSaved);

                ProtocolEnvelope<Map<String, Object>> replay = harness.transport()
                    .requireRequest(ResourceOperationKind.SAVE, id);
                assertEquals(original.requestId(), replay.requestId());
                assertEquals(original.correlationId(), replay.correlationId());
                assertEquals(original.traceId(), replay.traceId());
                assertEquals(original.mutationId(), replay.mutationId());
                assertEquals(original.revision(), replay.revision());
                assertEquals(original.payloadHash(), replay.payloadHash());
                assertEquals(original.body(), replay.body());
                assertEquals(2, peer.handledCount(ResourceOperationKind.SAVE, ReSyncResourceType.COMMAND, id));
                assertEquals(2L, peer.authoritative(ReSyncResourceType.COMMAND, id).revision());
                assertEquals(Boolean.TRUE, ticketSaved.get());
                assertFalse(DesignerSaveNotifications.isPending(ticket));
                assertFalse(session.dirty());
            } finally {
                studio.removed();
            }
        } finally {
            probe.close();
        }
    }

    @Test
    void revisionDivergentCoreAckCannotReplaceAuthoritativeClientState() throws Exception {
        Probe probe = new Probe();
        CatalogCachePublication publication = publication();
        try (AcceptanceHarness harness = connect(probe, cache("divergent"), publication)) {
            AuthoringProtocolPeer peer = new AuthoringProtocolPeer(SERVER, harness.transport());
            String id = "divergent-command";
            peer.seed(ReSyncResourceType.COMMAND, id, payload(ReSyncResourceType.COMMAND, id, 1L, List.of()), 1L,
                ResourceActivationState.ACTIVE);
            load(harness, peer, ReSyncResourceType.COMMAND, id);
            CoreGraphEditorSession session = session(harness.client().coreGraphResourceCache()
                .state(resource(ReSyncResourceType.COMMAND, id)).orElseThrow(),
                harness.client().activeCatalogAuthoringChecksum().orElseThrow());
            session.addNode(node("divergent-node"));
            assertTrue(probe.manager.bind(session));

            assertTrue(probe.manager.saveCoreGraph(SERVER_ID, ReSyncResourceType.COMMAND, session));
            peer.divergeNextResponse();
            awaitPeerSave(harness, peer, ReSyncResourceType.COMMAND, id, 1L);
            harness.drain();

            GraphResourceState retained = harness.client().coreGraphResourceCache()
                .state(resource(ReSyncResourceType.COMMAND, id)).orElseThrow();
            assertEquals(1L, retained.revision());
            assertTrue(retained.graphDocument().nodes().isEmpty());
            assertEquals(1L, peer.authoritative(ReSyncResourceType.COMMAND, id).revision());
            assertTrue(session.dirty());
        } finally {
            probe.close();
        }
    }

    private ReSyncCatalogPublicationCache cache(String name) {
        return new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve(name + "-catalog.json")));
    }

    private static AcceptanceHarness connect(Probe probe, ReSyncCatalogPublicationCache cache,
                                             CatalogCachePublication publication) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, probe, cache);
        return establish(probe, client, transport, publication);
    }

    private static AcceptanceHarness establish(Probe probe, ReSyncFlowClient client,
                                               ScriptedReSyncTransport transport,
                                               CatalogCachePublication publication) throws Exception {
        FlowManagerTestConnection.installCurrent(probe.manager, SERVER_ID, client, transport);
        probe.manager.useClient(client);
        client.connect().join();
        JsonObject capabilities = protocolCapabilities(publication);
        transport.receiveHandshake(capabilities.toString());
        transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication), 2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            ReSyncFlowClientTestHarness.drain(client);
            if (client.connectionState() == ReSyncFlowClient.ConnectionState.CONNECTED
                && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
                && client.typedInteractionProjection().isPresent()
                && capabilities.equals(probe.manager.getServerCapabilities(SERVER_ID))) {
                return new AcceptanceHarness(client, transport, capabilities, publication.key());
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        String diagnostic = establishmentDiagnostic(client);
        assertEquals(capabilities, probe.manager.getServerCapabilities(SERVER_ID),
            "The negotiated Core resource contract was not published to the active connection: " + diagnostic);
        assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority(),
            () -> client.catalogAuthorityDiagnostic().orElse("Typed catalog authority was not established") + ": " + diagnostic);
        assertTrue(client.typedInteractionProjection().isPresent(),
            "The typed interaction projection was not established: " + diagnostic);
        throw new AssertionError("The negotiated Core authoring connection did not settle: " + diagnostic);
    }

    private static String establishmentDiagnostic(ReSyncFlowClient client) {
        StringBuilder result = new StringBuilder("connection=").append(client.connectionState())
            .append(",handshake=").append(client.handshakeObservation());
        try {
            for (String name : List.of("lastTransportFailureDiagnostic", "lastRetirementDiagnostic")) {
                result.append(',').append(name).append('=').append(clientField(client, name));
            }
            synchronized (clientField(client, "outboundLock")) {
                for (String name : List.of("startupSubscriptionGeneration", "pendingStartupCompletionGeneration",
                    "completingStartupGeneration", "completedStartupGeneration", "drainingPendingSends")) {
                    result.append(',').append(name).append('=').append(clientField(client, name));
                }
                result.append(",pendingSendCount=").append(((Set<?>) clientField(client, "pendingSends")).size());
            }
        } catch (ReflectiveOperationException exception) {
            result.append(",diagnosticFailure=").append(exception.getClass().getSimpleName());
        }
        return result.toString();
    }

    private static Object clientField(ReSyncFlowClient client, String name) throws ReflectiveOperationException {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(client);
    }

    private static void load(AcceptanceHarness harness, AuthoringProtocolPeer peer,
                             ReSyncResourceType type, String id) throws Exception {
        int handledBefore = peer.handledCount(ResourceOperationKind.LOAD, type, id);
        String before = harness.diagnostic(peer, type, id);
        assertTrue(harness.client().isConnectedState(), before);
        assertTrue(harness.client().activeTransportGeneration() >= 0, before);
        assertTrue(supportsCoreLoad(harness.capabilities()), before);
        assertEquals(harness.publicationKey(), harness.client().catalogPublicationProjection().acknowledgedKey().orElse(null),
            before);
        assertEquals(harness.publicationKey(), harness.client().catalogPublicationProjection().active()
            .map(snapshot -> snapshot.publication().key()).orElse(null), before);
        assertTrue(harness.client().typedInteractionProjection().isPresent(), before);
        assertEquals(1L, harness.client().resourceRevisionReconciler().authorityEpoch(SERVER_ID), before);
        assertEquals(1L, WorldGenManager.getInstance().authorityEpoch(SERVER_ID), before);
        harness.client().requestResource(type, id, false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            peer.pump();
            harness.drain();
            if (peer.handledCount(ResourceOperationKind.LOAD, type, id) > handledBefore
                && harness.client().coreGraphResourceCache().state(resource(type, id)).isPresent()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertTrue(peer.handledCount(ResourceOperationKind.LOAD, type, id) > handledBefore
                && harness.client().coreGraphResourceCache().state(resource(type, id)).isPresent(),
            () -> "The authoritative Core load did not settle after startup page hydration: "
                + harness.diagnostic(peer, type, id));
    }

    private static boolean supportsCoreLoad(JsonObject capabilities) {
        if (capabilities == null || !capabilities.has("protocolEnvelope")
            || !capabilities.get("protocolEnvelope").isJsonObject()) {
            return false;
        }
        JsonObject protocol = capabilities.getAsJsonObject("protocolEnvelope");
        if (!protocol.has("supported") || !protocol.get("supported").getAsBoolean()
            || !protocol.has("resourceOperations") || !protocol.get("resourceOperations").isJsonObject()
            || !protocol.has("genericResourceContract") || !protocol.get("genericResourceContract").isJsonObject()) {
            return false;
        }
        JsonObject contract = protocol.getAsJsonObject("genericResourceContract");
        if (!contract.has("version") || !contract.get("version").isJsonObject()) {
            return false;
        }
        JsonObject version = contract.getAsJsonObject("version");
        if (!version.has("generation") || version.get("generation").getAsInt() < 1) {
            return false;
        }
        JsonArray read = protocol.getAsJsonObject("resourceOperations").getAsJsonArray("read");
        if (read == null) {
            return false;
        }
        for (int index = 0; index < read.size(); index++) {
            if (read.get(index).isJsonPrimitive() && "load".equals(read.get(index).getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static void awaitRevision(AcceptanceHarness harness, AuthoringProtocolPeer peer,
                                      ReSyncFlowClient client, ReSyncResourceType type, String id,
                                      long revision) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            harness.drain();
            peer.pump();
            harness.drain();
            if (client.coreGraphResourceCache().state(resource(type, id))
                .map(state -> state.revision() == revision).orElse(false)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertEquals(revision, client.coreGraphResourceCache().state(resource(type, id)).orElseThrow().revision());
    }

    private static void awaitPeerSave(AcceptanceHarness harness, AuthoringProtocolPeer peer,
                                      ReSyncResourceType type, String id, long authoritativeRevision) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            harness.drain();
            peer.pump();
            if (peer.handledCount(ResourceOperationKind.SAVE, type, id) > 0
                && peer.authoritative(type, id).revision() == authoritativeRevision) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        peer.pump();
        assertTrue(peer.handledCount(ResourceOperationKind.SAVE, type, id) > 0,
            "The Core save request was never handled");
        assertEquals(authoritativeRevision, peer.authoritative(type, id).revision(),
            "The Core save request did not reach the expected authoritative revision");
    }

    private static void reopen(ScriptedReSyncTransport transport) throws Exception {
        Field open = ScriptedReSyncTransport.class.getDeclaredField("open");
        open.setAccessible(true);
        open.setBoolean(transport, true);
    }

    private static void awaitTicketSettlement(AcceptanceHarness harness, AuthoringProtocolPeer peer,
                                              AtomicReference<Boolean> ticketSaved) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            harness.drain();
            peer.pump();
            harness.drain();
            if (ticketSaved.get() != null) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertEquals(Boolean.TRUE, ticketSaved.get(), "The original Core save ticket did not settle after reconnect");
    }

    private static CoreGraphEditorSession session(GraphResourceState state, ContentHash authoringChecksum) {
        return state.functionSourceDocument() != null
            ? new CoreGraphEditorSession(state.functionSourceDocument(), authoringChecksum, Set.of())
            : new CoreGraphEditorSession(state.graphDocument(), authoringChecksum, Set.of());
    }

    private static CoreGraphEditorSession scalarSession(AcceptanceHarness harness, ReSyncResourceType type, String id) {
        GraphResourceState state = harness.client().coreGraphResourceCache().state(resource(type, id)).orElseThrow();
        ContentHash checksum = harness.client().activeCatalogAuthoringChecksum().orElseThrow();
        return state.functionSourceDocument() != null
            ? new CoreGraphEditorSession(state.functionSourceDocument(), checksum, Set.of(STRING_EDITOR), Set.of(STRING_EDITOR))
            : new CoreGraphEditorSession(state.graphDocument(), checksum, Set.of(STRING_EDITOR), Set.of(STRING_EDITOR));
    }

    private static Object scalarValue(CoreGraphEditorSession session, NodeInstanceId id) {
        GraphNode node = graph(session).nodes().stream().filter(value -> value.instanceId().equals(id)).findFirst().orElseThrow();
        return node.values().containsKey(STRING_PIN) ? node.values().get(STRING_PIN).value().value() : null;
    }

    private static GraphDocument graph(CoreGraphEditorSession session) {
        return session.isFunction() ? session.functionSourceDocument().graph() : session.graphDocument();
    }

    private static GraphDocument graph(GraphResourceState state) {
        return state.functionSourceDocument() != null ? state.functionSourceDocument().graph() : state.graphDocument();
    }

    private static GraphDocument graph(Object payload) {
        return payload instanceof FunctionSourceDocument source ? source.graph() : (GraphDocument) payload;
    }

    private static GraphNode requireNode(GraphDocument graph, NodeId definition) {
        return graph.nodes().stream()
            .filter(node -> NODE_OWNER.equals(node.definition().owner()) && definition.equals(node.definition().id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing production descriptor-backed node: " + definition));
    }

    private static void assertTopology(GraphDocument graph, NodeInstanceId sourceId, NodeInstanceId targetId,
                                       ConnectionId connectionId) {
        assertEquals(2, graph.nodes().size());
        assertEquals(Set.of(SOURCE_NODE, TARGET_NODE), graph.nodes().stream()
            .map(node -> node.definition().id()).collect(Collectors.toSet()));
        assertEquals(Set.of(NODE_OWNER), graph.nodes().stream()
            .map(node -> node.definition().owner()).collect(Collectors.toSet()));
        assertEquals(1, graph.connections().size());
        GraphConnection connection = graph.connections().getFirst();
        assertEquals(connectionId, connection.connectionId());
        assertEquals(sourceId, connection.source().nodeId());
        assertEquals(PinId.of("branch_0"), connection.source().pinId());
        assertEquals(targetId, connection.target().nodeId());
        assertEquals(PinId.of("flow"), connection.target().pinId());
    }

    private static Object payload(ReSyncResourceType type, String id, long revision, List<GraphNode> nodes) {
        GraphDocument graph = graph(type, id, revision, nodes);
        if (type != ReSyncResourceType.FUNCTION) {
            return graph;
        }
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(graph.resource()),
            new FunctionRevision(revision), List.of(), List.of(), Map.of());
        return new FunctionSourceDocument(signature, graph, OpaqueData.empty());
    }

    private static GraphDocument graph(ReSyncResourceType type, String id, long revision, List<GraphNode> nodes) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), revision, BINDING, Set.of(), nodes,
            List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private static GraphNode node(String id) {
        return new GraphNode(NodeInstanceId.deterministic(id), ContractRef.of(NODE_OWNER, SOURCE_NODE),
            2, null, Map.of(), Map.of(), List.of(), List.of(), InspectorState.empty(), 5000, 3000,
            OpaqueData.empty());
    }

    private static ServerResourceLocator resource(ReSyncResourceType type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(PROTOCOL_OWNER, ResourceTypeId.of(type.typeId())), id);
    }

    private static CatalogCachePublication publication() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES,
                true, true, CatalogCacheState.ACTIVE, List.of()));
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), sections, Set.of());
        List<CatalogCachePublication.Entry> nodes = productionNodeDescriptors().stream()
            .map(CoreAuthoringRecoveryAcceptanceTest::publicationEntry)
            .toList();
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, BINDING, 1L, nodes,
            authoring, Map.of());
    }

    private static CatalogCachePublication scalarPublication(boolean declaredEditor) {
        CatalogAuthoringPublication.Entry capability = new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.CAPABILITIES, STRING_EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(), Set.of(CatalogAuthoringPublication.Section.CAPABILITIES), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(Map.of("id", "generic-editor"))));
        CatalogAuthoringPublication.Entry editor = new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.EDITORS, STRING_EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(STRING_EDITOR), Set.of(CatalogAuthoringPublication.Section.EDITORS,
                CatalogAuthoringPublication.Section.CAPABILITIES), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(Map.of("id", "generic-editor"))));
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS,
                true, true, CatalogCacheState.ACTIVE, List.of(editor)),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES,
                true, true, CatalogCacheState.ACTIVE, List.of(capability)));
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), sections, Set.of(STRING_EDITOR));
        JsonObject descriptor = JsonParser.parseString("""
            {"id":"literal.text","displayName":"String Value","schemaVersion":1,"kind":"node","category":"UTILITY",
             "pins":[{"id":"message","displayName":"Message","direction":"input","requirement":"required",
               "type":{"kind":"named","type":{"ownerId":"builtin","localId":"string"},"arguments":[]},
               "editor":{"ownerId":"restudio.resync","localId":"generic-editor"}}],
             "inspector":{"intent":"generic","sections":[]}}
            """).getAsJsonObject();
        if (!declaredEditor) {
            descriptor.getAsJsonArray("pins").get(0).getAsJsonObject().remove("editor");
        }
        CatalogCachePublication.Entry scalar = CatalogCachePublication.Entry.present(ContractRef.of(NODE_OWNER, STRING_NODE),
            1L, CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalizeJson(descriptor.toString().getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8)));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current()), BINDING, 1L,
            List.of(scalar), authoring, Map.of());
    }

    private static CatalogCachePublication.Entry publicationEntry(JsonObject authored) {
        String owner = authored.get("owner").getAsString();
        String id = authored.get("id").getAsString();
        JsonObject descriptor = authored.deepCopy();
        descriptor.addProperty("kind", "node");
        JsonArray pins = new JsonArray();
        appendPins(pins, authored.getAsJsonArray("inputs"));
        appendPins(pins, authored.getAsJsonArray("outputs"));
        descriptor.remove("inputs");
        descriptor.remove("outputs");
        descriptor.add("pins", pins);
        JsonObject inspector = new JsonObject();
        inspector.addProperty("intent", authored.get("inspectorIntent").getAsString());
        inspector.add("sections", new JsonArray());
        descriptor.add("inspector", inspector);
        JsonObject metadata = new JsonObject();
        metadata.add("authoredSource", authored.deepCopy());
        descriptor.add("metadata", metadata);
        byte[] canonicalDescriptor = CanonicalJson.canonicalizeJson(
            descriptor.toString().getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        return CatalogCachePublication.Entry.present(
            ContractRef.of(OwnerId.of(owner), NodeId.of(id)), 1L, CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(canonicalDescriptor));
    }

    private static void appendPins(JsonArray target, JsonArray authoredPins) {
        for (JsonElement value : authoredPins) {
            JsonObject pin = value.getAsJsonObject().deepCopy();
            String dataType = pin.remove("dataType").getAsString();
            if (!"execution".equals(dataType)) {
                throw new IllegalStateException("The acceptance fixture requires execution-only production nodes");
            }
            JsonObject reference = new JsonObject();
            reference.addProperty("ownerId", "builtin");
            reference.addProperty("localId", dataType);
            JsonObject type = new JsonObject();
            type.addProperty("kind", "named");
            type.add("type", reference);
            type.add("arguments", new JsonArray());
            pin.addProperty("kind", "pin");
            pin.add("type", type);
            JsonObject editor = new JsonObject();
            editor.addProperty("ownerId", "restudio.resync");
            editor.addProperty("localId", "generic-editor");
            pin.add("editor", editor);
            target.add(pin);
        }
    }

    private static List<JsonObject> productionNodeDescriptors() {
        try (InputStream stream = Objects.requireNonNull(
            CoreAuthoringRecoveryAcceptanceTest.class.getResourceAsStream(DESCRIPTOR_FIXTURE),
            "Production descriptor fixture is unavailable");
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonArray values = JsonParser.parseReader(reader).getAsJsonArray();
            List<JsonObject> descriptors = new ArrayList<>();
            Set<NodeId> expected = Set.of(SOURCE_NODE, TARGET_NODE);
            for (JsonElement value : values) {
                JsonObject descriptor = value.getAsJsonObject();
                NodeId id = NodeId.of(descriptor.get("id").getAsString());
                if (expected.contains(id)) {
                    descriptors.add(descriptor.deepCopy());
                }
            }
            Set<NodeId> loaded = descriptors.stream()
                .map(value -> NodeId.of(value.get("id").getAsString()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!loaded.equals(expected)) {
                throw new IllegalStateException("Production descriptor fixture is missing " + expected);
            }
            return List.copyOf(descriptors);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load production descriptor fixture", exception);
        }
    }

    private static JsonObject protocolCapabilities(CatalogCachePublication publication) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", SERVER_ID);
        root.addProperty("authorityEpoch", 1L);
        root.addProperty("catalogPublicationKey", publication.key().canonicalText());
        JsonObject protocol = new JsonObject();
        protocol.addProperty("supported", true);
        JsonObject authority = new JsonObject();
        authority.addProperty("supported", true);
        authority.addProperty("durable", true);
        protocol.add("mutationAuthority", authority);
        JsonObject operations = new JsonObject();
        JsonArray read = new JsonArray();
        read.add("load");
        read.add("list");
        read.add("query");
        operations.add("read", read);
        JsonArray mutate = new JsonArray();
        mutate.add("create");
        mutate.add("save");
        mutate.add("activate");
        operations.add("mutate", mutate);
        protocol.add("resourceOperations", operations);
        JsonObject contract = new JsonObject();
        JsonObject version = new JsonObject();
        version.addProperty("generation", 1);
        version.addProperty("minor", 2);
        contract.add("version", version);
        JsonArray capabilities = new JsonArray();
        capabilities.add("restudio.resync/resource_activation");
        capabilities.add("restudio.resync/resource_create_presentation");
        contract.add("capabilities", capabilities);
        protocol.add("genericResourceContract", contract);
        root.add("protocolEnvelope", protocol);
        return root;
    }

    private static ReKeyEvent controlSave(Object source) {
        return new ReKeyEvent(source, source, 0L,
            new ReModifierState(false, true, false, false, false, false), ReKeyEvent.Action.PRESSED,
            ReKey.S, 0, 0, ReKeyLocation.STANDARD, false);
    }

    private static ReKeyEvent key(Object source, ReKey key) {
        return new ReKeyEvent(source, source, 0L, ReModifierState.none(), ReKeyEvent.Action.PRESSED,
            key, 0, 0, ReKeyLocation.STANDARD, false);
    }

    private static ReTextInputEvent text(Object source, char character) {
        return new ReTextInputEvent(source, source, 0L, ReModifierState.none(), String.valueOf(character), character);
    }

    private static ReMouseEvent mouse(Object source, ReMouseEvent.Action action, double x, double y) {
        return new ReMouseEvent(source, source, 0L, ReModifierState.none(), action, x, y, 0, 0,
            ReMouseButton.LEFT, 0, 1);
    }

    private static final class AcceptanceEditor extends GraphEditorScreen {
        private final ReSyncFlowClient client;
        private StudioDocument expectedDocument;
        private Runnable toolbarSave;
        private int tickCount;
        private int renderCount;

        private AcceptanceEditor(ReSyncResourceType type, String id, ReSyncFlowClient client, Screen parent) {
            super(emptyGraph(type, id), SERVER_ID, parent);
            this.client = client;
            width = 900;
            height = 600;
            studioMode = true;
        }

        private void bindCoreDocument(ReSyncResourceType type, String id, CoreGraphEditorSession session) {
            openStudioCoreDocument(type.typeId(), id, id, session);
            expectedDocument = activeStudioDocument;
        }

        private void openVisibleNodeMenu() {
            showAllNodesMenu(200, 160);
        }

        private boolean selectorReady() throws Exception {
            ItemSelectorWidget selector = selector();
            return selector != null && !selector.isVirtualLoading();
        }

        private boolean selectorSettled(String query) throws Exception {
            ItemSelectorWidget selector = selector();
            if (selector == null || selector.isVirtualLoading() || selector.isVirtualSaturated()
                || !query.equals(selector.getSearchText())) {
                return false;
            }
            return selector.collaborationState().items().stream()
                .anyMatch(item -> !item.section() && query.equals(item.label()));
        }

        private String selectorDiagnostic(String query) throws Exception {
            ItemSelectorWidget selector = selector();
            if (selector == null) {
                return "selector=missing,query=" + query;
            }
            ItemSelectorWidget.CollaborationState state = selector.collaborationState();
            return "query=" + query + ",actualQuery=" + selector.getSearchText()
                + ",loading=" + selector.isVirtualLoading() + ",saturated=" + selector.isVirtualSaturated()
                + ",items=" + state.items().stream()
                    .map(item -> (item.section() ? "section:" : "item:") + item.label()).toList();
        }

        private boolean renderedTopology(NodeInstanceId sourceId, NodeInstanceId targetId) throws Exception {
            FlowNodeWidget source = widget(sourceId.canonicalText());
            FlowNodeWidget target = widget(targetId.canonicalText());
            Object index = renderIndex();
            return source != null && target != null && index != null && indexed(source) && indexed(target)
                && indexedWireCount(index) == 1 && isCoreEditorReady();
        }

        private boolean viewportFitted() throws Exception {
            Object index = renderIndex();
            if (index == null || (boolean) field("initialViewportFitPending")) {
                return false;
            }
            Field bounds = index.getClass().getDeclaredField("worldBounds");
            bounds.setAccessible(true);
            float zoom = (float) field("targetZoomLevel");
            float x = (float) field("targetPanX");
            float y = (float) field("targetPanY");
            return bounds.get(index) != null && Float.isFinite(zoom) && zoom > 0.0F
                && Float.isFinite(x) && Float.isFinite(y);
        }

        private void toolbarSave() {
            assertNotNull(toolbarSave);
            toolbarSave.run();
        }

        private TextInputWidget valueInput(NodeInstanceId id) throws Exception {
            Field field = NodeWidget.class.getDeclaredField("inputWidgets");
            field.setAccessible(true);
            TextInputWidget input = (TextInputWidget) ((Map<?, ?>) field.get(widget(id.canonicalText())))
                .get(STRING_PIN.canonicalText());
            assertNotNull(input);
            return input;
        }

        private void focusValue(NodeInstanceId id) throws Exception {
            FlowNodeWidget node = widget(id.canonicalText());
            TextInputWidget input = valueInput(id);
            assertTrue(Widget.dispatchMouseClicked(node, mouse(node, ReMouseEvent.Action.PRESSED,
                input.getX() + input.getWidth() - 2, input.getY() + input.getHeight() / 2)));
            Widget.dispatchMouseReleased(node, mouse(node, ReMouseEvent.Action.RELEASED,
                input.getX() + input.getWidth() - 2, input.getY() + input.getHeight() / 2));
            assertTrue(input.isFocused());
            assertTrue(Widget.dispatchKeyPressed(node, key(node, ReKey.END)));
        }

        private String valueText(NodeInstanceId id) throws Exception {
            return valueInput(id).getText();
        }

        private void typeValue(NodeInstanceId id, char character) throws Exception {
            FlowNodeWidget node = widget(id.canonicalText());
            Map<String, Object> before = Map.copyOf(graph.getNodes().get(id.canonicalText()).getInputValues());
            assertTrue(Widget.dispatchTextInput(node, text(node, character)));
            assertEquals(before, graph.getNodes().get(id.canonicalText()).getInputValues());
        }

        private void backspaceValue(NodeInstanceId id) throws Exception {
            FlowNodeWidget node = widget(id.canonicalText());
            Map<String, Object> before = Map.copyOf(graph.getNodes().get(id.canonicalText()).getInputValues());
            assertTrue(Widget.dispatchKeyPressed(node, key(node, ReKey.BACKSPACE)));
            assertEquals(before, graph.getNodes().get(id.canonicalText()).getInputValues());
        }

        private void deleteNodeThroughWidget(NodeInstanceId id) throws Exception {
            FlowNodeWidget node = widget(id.canonicalText());
            Field field = NodeWidget.class.getDeclaredField("closeButton");
            field.setAccessible(true);
            Widget button = (Widget) field.get(node);
            dirtyGeometry(id);
            assertTrue(Widget.dispatchMouseClicked(node, mouse(node, ReMouseEvent.Action.PRESSED,
                button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2)));
        }

        @SuppressWarnings("unchecked")
        private void dirtyGeometry(NodeInstanceId id) throws Exception {
            ((Set<String>) field("graphRenderGeometryDirtyNodeIds")).add(id.canonicalText());
        }

        private boolean contentAllowed(NodeInstanceId id) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("coreCapabilityAllowed", CoreGraphEditorSession.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(this, coreGraphEditorSession(), id.canonicalText());
        }

        private void assertEmptyTopology() throws Exception {
            assertTrue(graph.getNodes().isEmpty());
            assertTrue(((Map<?, ?>) field("widgetCache")).isEmpty());
            Object index = renderIndex();
            assertNotNull(index);
            for (String name : List.of("nodeBounds", "nodeCells", "nodeOrder", "groupsByConnection")) {
                Field field = index.getClass().getDeclaredField(name);
                field.setAccessible(true);
                assertTrue(((Map<?, ?>) field.get(index)).isEmpty(), name);
            }
            Class<?> boundsType = Class.forName(GraphEditorScreen.class.getName() + "$WorldBounds");
            var boundsConstructor = boundsType.getDeclaredConstructor(double.class, double.class, double.class, double.class);
            boundsConstructor.setAccessible(true);
            Method visible = GraphEditorScreen.class.getDeclaredMethod("visibleGraphNodes", boundsType);
            visible.setAccessible(true);
            assertTrue(((List<?>) visible.invoke(this,
                boundsConstructor.newInstance(-10_000D, -10_000D, 10_000D, 10_000D))).isEmpty());
        }

        @SuppressWarnings("unchecked")
        private FlowNodeWidget widget(String id) throws Exception {
            return ((Map<String, FlowNodeWidget>) field("widgetCache")).get(id);
        }

        private Object renderIndex() throws Exception {
            return field("graphRenderIndex");
        }

        private boolean indexed(FlowNodeWidget widget) throws Exception {
            Object index = renderIndex();
            if (index == null) {
                return false;
            }
            Field bounds = index.getClass().getDeclaredField("nodeBounds");
            bounds.setAccessible(true);
            return ((Map<?, ?>) bounds.get(index)).containsKey(widget);
        }

        private int indexedWireCount(Object index) throws Exception {
            Field groups = index.getClass().getDeclaredField("groupsByConnection");
            groups.setAccessible(true);
            return ((Map<?, ?>) groups.get(index)).size();
        }

        private ItemSelectorWidget selector() throws Exception {
            return (ItemSelectorWidget) field("nodeItemSelector");
        }

        private Object field(String name) throws Exception {
            Class<?> type = getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(this);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        }

        private String readinessDiagnostic(AcceptanceManager expectedManager, CoreGraphEditorSession expectedSession,
                                           StudioHost host) {
            StudioDocument.EditorReadiness readiness = coreEditorReadiness();
            FlowManager activeManager = FlowManager.getInstance();
            CoreGraphUiProjection.ProjectionResult projection = null;
            int widgets = -1;
            try {
                projection = (CoreGraphUiProjection.ProjectionResult) field("coreProjectionResult");
                widgets = ((Map<?, ?>) field("widgetCache")).size();
            } catch (Exception exception) {
                return "readinessDiagnosticFailure=" + exception.getClass().getSimpleName()
                    + ",readiness=" + readiness + ",hostTicks=" + host.tickCount + ",hostRenders=" + host.renderCount
                    + ",editorTicks=" + tickCount + ",editorRenders=" + renderCount;
            }
            ReSyncResourceType type = ReSyncResourceType.byTypeId(expectedSession.resource().resourceType().value());
            boolean managerSessionCurrent = activeManager != null && type != null
                && activeManager.isCurrentCoreGraphEditorSession(SERVER_ID, type, expectedSession.resource().id(),
                    expectedSession);
            int sourceNodes = graph(expectedSession).nodes().size();
            int graphNodes = graph != null && graph.getNodes() != null ? graph.getNodes().size() : -1;
            int projectedNodes = projection != null ? projection.projectedNodeCount() : -1;
            return "readiness=" + readiness
                + ",managerIdentity=" + (activeManager == expectedManager)
                + ",managerSessionCurrent=" + managerSessionCurrent
                + ",activeDocumentIdentity=" + (activeStudioDocument == expectedDocument)
                + ",activeDocumentSessionIdentity=" + (activeStudioDocument != null
                    && activeStudioDocument.coreSession() == expectedSession)
                + ",editorSessionIdentity=" + (coreGraphEditorSession() == expectedSession)
                + ",sourceNodes=" + sourceNodes
                + ",graphNodes=" + graphNodes
                + ",projectedNodes=" + projectedNodes
                + ",projectionComplete=" + (projection != null && projection.complete())
                + ",widgets=" + widgets
                + ",hostTicks=" + host.tickCount
                + ",hostRenders=" + host.renderCount
                + ",editorTicks=" + tickCount
                + ",editorRenders=" + renderCount;
        }

        @Override
        public void tick() {
            tickCount++;
            super.tick();
        }

        @Override
        public void renderHandler(IDrawContext context, int mouseX, int mouseY, float delta) {
            renderCount++;
            super.renderHandler(context, mouseX, mouseY, delta);
        }

        @Override
        protected SquareButtonWidget headerButton(String icon, String hint,
                                                                                Runnable action) {
            if ("Save".equals(hint)) {
                toolbarSave = action;
            }
            return super.headerButton(icon, hint, action);
        }

        @Override
        protected ReSyncFlowClient typedCatalogClient() {
            return client;
        }

        private static FlowGraph emptyGraph(ReSyncResourceType type, String id) {
            FlowGraph graph = new FlowGraph();
            graph.setId(id);
            graph.setResourceType(type.typeId());
            return graph;
        }
    }

    private static final class StudioHost extends GraphEditorScreen {
        private AcceptanceEditor editor;
        private int tickCount;
        private int renderCount;

        private StudioHost() {
            super(new FlowGraph(), SERVER_ID, new Screen());
            width = 900;
            height = 600;
            studioMode = true;
        }

        private void mount(ReSyncResourceType type, String id, AcceptanceEditor editor,
                           CoreGraphEditorSession session) {
            this.editor = editor;
            openStudioViewDocument(ReSyncResourceType.CUSTOM_CONTENT.typeId(), "nested-" + id,
                type.typeId() + ":" + id, null, new ScreenBackedStudioView(this, editor),
                false, false);
            editor.bindCoreDocument(type, id, session);
        }

        private void addNode(AcceptanceEditor editor, CoreGraphEditorSession session, String query,
                             int expectedCount) throws Exception {
            editor.openVisibleNodeMenu();
            await(editor::selectorReady);
            for (char character : query.toCharArray()) {
                assertTrue(textInput(text(this, character)));
            }
            try {
                await(() -> editor.selectorSettled(query));
            } catch (AssertionError error) {
                throw new AssertionError("The production catalog selector did not settle: "
                    + editor.selectorDiagnostic(query), error);
            }
            assertTrue(keyPressed(key(this, ReKey.DOWN)));
            assertTrue(keyPressed(key(this, ReKey.ENTER)));
            await(() -> graph(session).nodes().size() == expectedCount);
        }

        private void await(CheckedCondition condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (System.nanoTime() < deadline) {
                tick();
                renderHandler(new TestDrawContext(), 0, 0, 0.0F);
                if (condition.test()) {
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertTrue(condition.test(), "The nested Studio frame lifecycle did not publish Core state");
        }

        private void awaitCore(AcceptanceEditor editor, AcceptanceManager manager,
                               CoreGraphEditorSession session) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (System.nanoTime() < deadline) {
                tick();
                renderHandler(new TestDrawContext(), 0, 0, 0.0F);
                if (editor.isCoreEditorReady()) {
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertTrue(editor.isCoreEditorReady(), () ->
                "The nested Studio frame lifecycle did not publish Core readiness: "
                    + editor.readinessDiagnostic(manager, session, this));
        }

        @Override
        public void tick() {
            tickCount++;
            super.tick();
        }

        @Override
        public void renderHandler(IDrawContext context, int mouseX, int mouseY, float delta) {
            renderCount++;
            super.renderHandler(context, mouseX, mouseY, delta);
        }

        @Override
        protected String studioServerId() {
            return SERVER_ID;
        }

        @Override
        public void removed() {
            if (editor != null) {
                editor.removed();
                editor = null;
            }
            super.removed();
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean test() throws Exception;
    }

    private static final class Probe extends RemotelyClient {
        private final AcceptanceManager manager;

        private Probe() {
            super(null);
            manager = new AcceptanceManager(this);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
        }
    }

    private static final class AcceptanceManager extends FlowManager {
        private ReSyncFlowClient client;

        private AcceptanceManager(RemotelyClient owner) {
            super(owner, null);
        }

        private void useClient(ReSyncFlowClient client) {
            this.client = client;
        }

        private boolean bind(CoreGraphEditorSession session) {
            return openCoreGraphSession(session, session.resource().id(), "");
        }

        @Override
        public ReSyncFlowClient existingFlowClient(String serverId) {
            return client;
        }

        @Override
        public void openStudioDocument(String serverId, String documentKey, Consumer<FlowEditorScreen> opener) {
        }
    }

    private static final class AcceptanceHarness implements AutoCloseable {
        private final ReSyncFlowClient client;
        private final ScriptedReSyncTransport transport;
        private final JsonObject capabilities;
        private final CatalogCacheKey publicationKey;

        private AcceptanceHarness(ReSyncFlowClient client, ScriptedReSyncTransport transport,
                                  JsonObject capabilities, CatalogCacheKey publicationKey) {
            this.client = client;
            this.transport = transport;
            this.capabilities = capabilities;
            this.publicationKey = publicationKey;
        }

        private ReSyncFlowClient client() {
            return client;
        }

        private ScriptedReSyncTransport transport() {
            return transport;
        }

        private JsonObject capabilities() {
            return capabilities;
        }

        private CatalogCacheKey publicationKey() {
            return publicationKey;
        }

        private String diagnostic(AuthoringProtocolPeer peer, ReSyncResourceType type, String id) {
            return "connection=" + client.connectionState()
                + ",generation=" + client.activeTransportGeneration()
                + ",catalogAuthority=" + client.catalogAuthority()
                + ",acknowledgedCatalog=" + client.catalogPublicationProjection().acknowledgedKey().orElse(null)
                + ",activeCatalog=" + client.catalogPublicationProjection().active()
                    .map(snapshot -> snapshot.publication().key()).orElse(null)
                + ",interactionReady=" + client.typedInteractionProjection().isPresent()
                + ",resourceEpoch=" + client.resourceRevisionReconciler().authorityEpoch(SERVER_ID)
                + ",worldGenEpoch=" + WorldGenManager.getInstance().authorityEpoch(SERVER_ID)
                + ",loadAdvertised=" + supportsCoreLoad(capabilities)
                + ",handledLoads=" + peer.handledCount(ResourceOperationKind.LOAD, type, id)
                + ",cachePresent=" + client.coreGraphResourceCache().state(resource(type, id)).isPresent()
                + ",outbound=" + peer.outboundOperations();
        }

        private void drain() throws Exception {
            ReSyncFlowClientTestHarness.drain(client);
        }

        @Override
        public void close() {
            ReSyncFlowClientTestHarness.closeClient(client);
        }
    }
}
