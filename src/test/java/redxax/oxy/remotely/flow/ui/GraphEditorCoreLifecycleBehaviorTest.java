package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncCatalogAuthoringProjection;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationProjection;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationCache;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationReceiptHandler;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.data.flow.ReSyncTypedInteractionProjection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioView;
import redxax.oxy.remotely.flow.ui.studio.ScreenBackedStudioView;
import redxax.oxy.remotely.flow.ui.studio.StudioDocument;
import redxax.oxy.remotely.flow.ui.studio.StudioViewportState;
import redxax.oxy.remotely.test.TestDrawContext;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReLifecycleEvent;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
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
import restudio.resync.flow.type.TypedValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorCoreLifecycleBehaviorTest {
    private static final ServerId SERVER = ServerId.deterministic("graph-editor-lifecycle");
    private static final OwnerId OWNER = OwnerId.of("test");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "1".repeat(64), "2".repeat(64));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("flow")), "visible-flow");
    private static final NodeInstanceId NODE = NodeInstanceId.deterministic("visible-node");

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void renderLifecyclePublishesQueuedCoreProjectionAndWidgets() throws Exception {
        CoreGraphEditorSession session = new CoreGraphEditorSession(document(1L, List.of(node())));
        FrameEditor editor = new FrameEditor(session);
        try {
            editor.materializeThroughFrames(() -> editor.renderHandler(new TestDrawContext(), 450, 300, 0F));
            assertNotNull(editor.widget(NODE.canonicalText()));
            assertTrue(editor.lifecycleTicks > 0);
            assertEquals(editor.lifecycleTicks, editor.tickCalls);
            int ticks = editor.tickCalls;
            for (int frame = 0; frame < 3; frame++) {
                editor.renderHandler(new TestDrawContext(), 450, 300, 0F);
            }
            assertEquals(ticks + 3, editor.tickCalls);
            assertEquals(editor.lifecycleTicks, editor.tickCalls);
        } finally {
            editor.removed();
        }
    }

    @Test
    void nestedStudioGraphRendersAndTicksExactlyOncePerFrame() throws Exception {
        CoreGraphEditorSession session = new CoreGraphEditorSession(document(1L, List.of(node())));
        FrameEditor editor = new FrameEditor(session);
        FrameHost host = new FrameHost();
        ScreenBackedStudioView view = new ScreenBackedStudioView(host, editor);
        host.view = view;
        view.init();
        try {
            editor.materializeThroughFrames(() -> host.renderHandler(new TestDrawContext(), 450, 300, 0F));
            assertNotNull(editor.widget(NODE.canonicalText()));
            assertEquals(editor.lifecycleTicks, editor.tickCalls);
            int ticks = editor.tickCalls;
            for (int frame = 0; frame < 3; frame++) {
                host.renderHandler(new TestDrawContext(), 450, 300, 0F);
            }
            assertEquals(ticks + 3, editor.tickCalls);
            assertEquals(editor.lifecycleTicks, editor.tickCalls);
        } finally {
            host.view = null;
            editor.removed();
            host.removed();
        }
    }

    @Test
    void studioFramePreservesNonScreenViewTicks() {
        FrameHost host = new FrameHost();
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger renders = new AtomicInteger();
        host.view = new ReSyncStudioView() {
            @Override
            public void tick() {
                ticks.incrementAndGet();
            }

            @Override
            public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
                renders.incrementAndGet();
            }
        };
        try {
            for (int frame = 0; frame < 3; frame++) {
                host.renderHandler(new TestDrawContext(), 450, 300, 0F);
            }
            assertEquals(3, ticks.get());
            assertEquals(3, renders.get());
        } finally {
            host.view = null;
            host.removed();
        }
    }

    @Test
    void acceptedNodeProjectsToWidgetSettlesByExactAckAndFitsAfterReopen() throws Exception {
        CoreGraphEditorSession session = new CoreGraphEditorSession(document(1L, List.of()));
        session.addNode(node());
        assertTrue(session.dirty());

        EditorHarness first = new EditorHarness(session);
        first.materializeGraphState();
        FlowNodeWidget widget = first.widget(NODE.canonicalText());
        assertNotNull(widget);
        GraphEditorScreen.WorldBounds bounds = first.nodeBounds(widget);
        assertTrue(bounds.maxX() > bounds.minX());
        assertTrue(bounds.maxY() > bounds.minY());
        assertTrue(first.targetViewportBounds().intersects(bounds));

        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(SERVER.canonicalText(),
            ReSyncResourceType.FLOW, RESOURCE.id(), RESOURCE.id());
        assertNotNull(ticket);
        UUID requestId = UUID.randomUUID();
        UUID mutationId = UUID.randomUUID();
        assertTrue(DesignerSaveNotifications.attachRequestId(ticket, requestId.toString()));
        assertTrue(DesignerSaveNotifications.attachMutationId(ticket, mutationId.toString()));
        AtomicReference<Boolean> saved = new AtomicReference<>();
        ticket.whenFinished((success, ignored) -> saved.set(success));
        assertTrue(DesignerSaveNotifications.isPending(ticket));

        GraphDocument authoritative = document(2L, List.of(node()));
        session.markSaved(authoritative);
        assertNotNull(DesignerSaveNotifications.completeMutation(SERVER.canonicalText(), ReSyncResourceType.FLOW,
            RESOURCE.id(), mutationId.toString()));
        assertFalse(session.dirty());
        assertTrue(Boolean.TRUE.equals(saved.get()));
        first.removed();

        CoreGraphEditorSession reopenedSession = new CoreGraphEditorSession(authoritative);
        EditorHarness reopened = new EditorHarness(reopenedSession);
        reopened.materializeGraphState();
        FlowNodeWidget reopenedWidget = reopened.widget(NODE.canonicalText());
        assertNotNull(reopenedWidget);
        GraphEditorScreen.WorldBounds reopenedBounds = reopened.nodeBounds(reopenedWidget);
        assertTrue(reopened.targetViewportBounds().intersects(reopenedBounds));
        reopened.removed();
    }

    @Test
    void catalogMenuAddsProjectsRendersSavesAndReopensTheCoreNode(@TempDir Path tempDir) throws Exception {
        CatalogFixture catalog = catalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(document(1L, List.of()), catalog.authoringChecksum(),
            Set.of());
        CatalogManager manager = new CatalogManager(catalog.client(), session);
        try {
            CatalogEditor first = new CatalogEditor(session, catalog.client());
            first.openCatalogMenu();
            assertEquals(3, first.catalogDefinitionCount());
            first.awaitCatalogMenu();
            first.renderHandler(new TestDrawContext(), 210, 170, 0.0F);
            assertTrue(first.keyPressed(key(first, ReKey.DOWN)));
            assertTrue(first.keyPressed(enter(first)));
            first.awaitSessionNodes(1);
            assertEquals(1, session.graphDocument().nodes().size());

            first.materializeGraphState();
            GraphNode added = session.graphDocument().nodes().getFirst();
            FlowNodeWidget widget = first.widget(added.instanceId().canonicalText());
            assertNotNull(widget);
            assertTrue(first.targetViewportBounds().intersects(first.nodeBounds(widget)));
            first.renderHandler(new TestDrawContext(), 0, 0, 0.0F);

            AtomicReference<GraphDocument> saved = new AtomicReference<>();
            first.setCoreGraphSaveHandler((candidate, ticket) -> {
                saved.set(candidate.graphDocument());
                DesignerSaveNotifications.failExact(ticket, "Test Complete");
                return true;
            });
            first.saveCurrentGraph();
            assertNotNull(saved.get());
            assertEquals(added.instanceId(), saved.get().nodes().getFirst().instanceId());
            first.removed();

            CoreGraphEditorSession reopenedSession = new CoreGraphEditorSession(saved.get(), catalog.authoringChecksum(), Set.of());
            manager.use(reopenedSession);
            CatalogEditor reopened = new CatalogEditor(reopenedSession, catalog.client());
            reopened.materializeGraphState();
            FlowNodeWidget reopenedWidget = reopened.widget(added.instanceId().canonicalText());
            assertNotNull(reopenedWidget);
            assertTrue(reopened.targetViewportBounds().intersects(reopened.nodeBounds(reopenedWidget)));
            reopened.renderHandler(new TestDrawContext(), 0, 0, 0.0F);
            reopened.removed();
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void resourceDropCommitsOneCoreTransactionAndSavesTheExactLocator(@TempDir Path tempDir) throws Exception {
        CatalogFixture catalog = catalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(document(1L, List.of()), catalog.authoringChecksum(),
            Set.of());
        CatalogManager manager = new CatalogManager(catalog.client(), session);
        try {
            CatalogEditor editor = new CatalogEditor(session, catalog.client());
            editor.materializeGraphState();
            GraphDocument baseline = session.graphDocument();
            CoreGraphEditorSession.HistoryState baselineHistory = session.historyState();
            ServerResourceLocator foreign = new ServerResourceLocator(ServerId.deterministic("foreign-drop-server"),
                ContractRef.of(OwnerId.of("extension.resources"), ResourceTypeId.of("flow")), "saved-flow");

            assertNull(editor.addResourceNode(Map.of("resource", "saved-flow")));
            assertEquals(baseline, session.graphDocument());
            assertEquals(baselineHistory, session.historyState());
            editor.materializeGraphState();
            assertNull(editor.addResourceNode(Map.of("resource", foreign.canonicalValue())));
            assertEquals(baseline, session.graphDocument());
            assertEquals(baselineHistory, session.historyState());
            editor.materializeGraphState();

            assertTrue(editor.dropResource(new ReSyncResourceDragPayload("extension.resources:flow",
                "saved-flow", "Saved Flow", "")));
            editor.awaitSessionNodes(1);
            assertEquals(new CoreGraphEditorSession.HistoryState(2, 1, 1, 0, true), session.historyState());
            GraphNode added = session.graphDocument().nodes().getFirst();
            TypedValue resource = added.values().get(PinId.of("resource")).value();
            assertEquals(TypedValue.State.LOCATOR, resource.state());
            assertEquals(SERVER, resource.locator().serverId());
            assertEquals(OwnerId.of("extension.resources"), resource.locator().type().owner());
            assertEquals(ResourceTypeId.of("flow"), resource.locator().type().id());
            assertEquals("saved-flow", resource.locator().id());

            AtomicReference<GraphDocument> saved = new AtomicReference<>();
            editor.setCoreGraphSaveHandler((candidate, ticket) -> {
                saved.set(candidate.graphDocument());
                DesignerSaveNotifications.failExact(ticket, "Test Complete");
                return true;
            });
            editor.saveCurrentGraph();
            assertNotNull(saved.get());
            editor.removed();

            CoreGraphEditorSession reopenedSession = new CoreGraphEditorSession(saved.get(), catalog.authoringChecksum(),
                Set.of());
            manager.use(reopenedSession);
            CatalogEditor reopened = new CatalogEditor(reopenedSession, catalog.client());
            TypedValue reopenedResource = reopenedSession.graphDocument().nodes().getFirst().values()
                .get(PinId.of("resource")).value();
            assertEquals(resource, reopenedResource);
            reopened.materializeGraphState();
            assertNotNull(reopened.widget(added.instanceId().canonicalText()));
            reopened.removed();
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void productionCatalogDescriptorsPublishConnectedWidgetsWireAndViewport(@TempDir Path tempDir) throws Exception {
        CatalogFixture catalog = catalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(connectedDocument(), catalog.authoringChecksum(), Set.of());
        CatalogManager manager = new CatalogManager(catalog.client(), session);
        try {
            CatalogEditor editor = new CatalogEditor(session, catalog.client());
            editor.materializeGraphState();
            FlowNodeWidget source = editor.widget(NodeInstanceId.deterministic("catalog-source").canonicalText());
            FlowNodeWidget target = editor.widget(NodeInstanceId.deterministic("catalog-target").canonicalText());

            assertNotNull(source);
            assertNotNull(target);
            assertTrue(source.hasLoadedDefinition());
            assertTrue(target.hasLoadedDefinition());
            assertEquals(1, editor.publishedWireCount());
            assertTrue(editor.targetViewportBounds().intersects(editor.nodeBounds(source)));
            assertTrue(editor.targetViewportBounds().intersects(editor.nodeBounds(target)));
            editor.renderHandler(new TestDrawContext(), 450, 300, 0F);
            editor.removed();
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void canonicalCoreSaveDoesNotDependOnRenderIndexAvailability(@TempDir Path tempDir) throws Exception {
        CatalogFixture catalog = catalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(document(1L, List.of(node())),
            catalog.authoringChecksum(), Set.of());
        CatalogManager manager = new CatalogManager(catalog.client(), session);
        try {
            CatalogEditor editor = new CatalogEditor(session, catalog.client());
            editor.materializeGraphState();
            editor.invalidateRenderIndex();
            AtomicReference<GraphDocument> saved = new AtomicReference<>();
            editor.setCoreGraphSaveHandler((candidate, ticket) -> {
                saved.set(candidate.graphDocument());
                DesignerSaveNotifications.failExact(ticket, "Test Complete");
                return true;
            });

            editor.saveCurrentGraph();

            assertNotNull(saved.get());
            assertEquals(session.graphDocument(), saved.get());
            editor.removed();
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void coreUndoAndRedoProjectBeforeMovingTheLiveHistoryCursor(@TempDir Path tempDir) throws Exception {
        CatalogFixture catalog = catalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(document(1L, List.of()), catalog.authoringChecksum(),
            Set.of());
        session.addNode(node());
        CatalogManager manager = new CatalogManager(catalog.client(), session);
        try {
            CatalogEditor editor = new CatalogEditor(session, catalog.client());
            editor.materializeGraphState();
            assertEquals(new CoreGraphEditorSession.HistoryState(2, 1, 1, 0, true), session.historyState());

            assertTrue(editor.undoCore());
            editor.awaitSessionNodes(0);
            editor.materializeGraphState();
            assertEquals(new CoreGraphEditorSession.HistoryState(2, 0, 0, 1, false), session.historyState());

            assertTrue(editor.redoCore());
            editor.awaitSessionNodes(1);
            editor.materializeGraphState();
            assertEquals(new CoreGraphEditorSession.HistoryState(2, 1, 1, 0, true), session.historyState());
            editor.removed();
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void coreLayoutCommitsOneTypedMoveTransactionAndRemainsUndoable(@TempDir Path tempDir) throws Exception {
        CatalogFixture catalog = catalog(tempDir);
        GraphDocument baseline = connectedDocument();
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline, catalog.authoringChecksum(), Set.of());
        CatalogManager manager = new CatalogManager(catalog.client(), session);
        try {
            CatalogEditor editor = new CatalogEditor(session, catalog.client());
            editor.materializeGraphState();

            editor.organizeCore();
            editor.awaitHistorySize(2);

            assertEquals(new CoreGraphEditorSession.HistoryState(2, 1, 1, 0, true), session.historyState());
            assertFalse(session.graphDocument().equals(baseline));
            NodeInstanceId sourceId = NodeInstanceId.deterministic("catalog-source");
            NodeInstanceId targetId = NodeInstanceId.deterministic("catalog-target");
            GraphNode baselineSource = baseline.nodes().stream()
                .filter(node -> sourceId.equals(node.instanceId())).findFirst().orElseThrow();
            GraphNode arrangedSource = session.graphDocument().nodes().stream()
                .filter(node -> sourceId.equals(node.instanceId())).findFirst().orElseThrow();
            GraphNode baselineTarget = baseline.nodes().stream()
                .filter(node -> targetId.equals(node.instanceId())).findFirst().orElseThrow();
            GraphNode arrangedTarget = session.graphDocument().nodes().stream()
                .filter(node -> targetId.equals(node.instanceId())).findFirst().orElseThrow();
            assertEquals(baselineSource.x(), arrangedSource.x());
            assertEquals(baselineSource.y(), arrangedSource.y());
            assertFalse(baselineTarget.x() == arrangedTarget.x() && baselineTarget.y() == arrangedTarget.y());

            assertTrue(editor.undoCore());
            editor.awaitDocument(baseline);
            assertEquals(new CoreGraphEditorSession.HistoryState(2, 0, 0, 1, false), session.historyState());
            editor.removed();
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void saveQueuedBehindRejectedMutationSettlesAgainstTheUnchangedBaseline(@TempDir Path tempDir) throws Exception {
        CatalogFixture catalog = catalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(document(1L, List.of(node())),
            catalog.authoringChecksum(), Set.of());
        CatalogManager manager = new CatalogManager(catalog.client(), session);
        try {
            CatalogEditor editor = new CatalogEditor(session, catalog.client());
            editor.materializeGraphState();
            GraphDocument baseline = session.graphDocument();
            CoreGraphEditorSession.HistoryState history = session.historyState();
            AtomicReference<GraphDocument> saved = new AtomicReference<>();
            AtomicInteger saves = new AtomicInteger();
            editor.setCoreGraphSaveHandler((candidate, ticket) -> {
                saved.set(candidate.graphDocument());
                saves.incrementAndGet();
                DesignerSaveNotifications.failExact(ticket, "Test Complete");
                return true;
            });

            assertTrue(editor.rejectingMove());
            editor.saveCurrentGraph();
            editor.saveCurrentGraph();
            editor.awaitSaveCalls(saves, 2);

            assertEquals(baseline, session.graphDocument());
            assertEquals(history, session.historyState());
            assertEquals(baseline, saved.get());
            assertEquals(2, saves.get());
            editor.removed();
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    private static ReKeyEvent enter(Object source) {
        return key(source, ReKey.ENTER);
    }

    private static ReKeyEvent key(Object source, ReKey key) {
        return new ReKeyEvent(source, source, 0L, ReModifierState.none(), ReKeyEvent.Action.PRESSED, key,
            0, 0, ReKeyLocation.STANDARD, false);
    }

    private static CatalogFixture catalog(Path tempDir) throws ReflectiveOperationException {
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDir.resolve("catalog-publication-cache.json")));
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), new NoopTransport(), null, cache);
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
        CatalogCachePublication.Entry sourceEntry = CatalogCachePublication.Entry.present(
            ContractRef.of(OWNER, NodeId.of("visible")), 1L, CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalize(sourceDescriptor()).getBytes(StandardCharsets.UTF_8)));
        CatalogCachePublication.Entry targetEntry = CatalogCachePublication.Entry.present(
            ContractRef.of(OWNER, NodeId.of("receiver")), 1L, CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalize(targetDescriptor()).getBytes(StandardCharsets.UTF_8)));
        CatalogCachePublication.Entry resourceEntry = CatalogCachePublication.Entry.present(
            ContractRef.of(OWNER, NodeId.of("resource.consume")), 1L, CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalize(resourceDescriptor()).getBytes(StandardCharsets.UTF_8)));
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            BINDING, 1L, List.of(sourceEntry, targetEntry, resourceEntry), authoring, Map.of());
        CatalogCachePublicationCodec publicationCodec = new CatalogCachePublicationCodec();
        ReSyncCatalogPublicationProjection nodeProjection = client.catalogPublicationProjection();
        Field authoringField = ReSyncFlowClient.class.getDeclaredField("catalogAuthoringProjection");
        authoringField.setAccessible(true);
        ReSyncCatalogAuthoringProjection authoringProjection =
            (ReSyncCatalogAuthoringProjection) authoringField.get(client);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER,
            "graph-editor-lifecycle", nodeProjection, authoringProjection, null);
        handler.setAuthoringRequired(true);
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending("graph-editor-lifecycle",
            "graph-editor-lifecycle-owner", publication).dispatched().clientReceived(key, 1L).receipt().orElseThrow();
        assertTrue(handler.apply(receipt, publication, publicationCodec.encodeBytes(publication)).applied());
        ReSyncCatalogPublicationProjection.Snapshot nodeSnapshot = nodeProjection.active().orElseThrow();
        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(nodeSnapshot);
        Method publishInteraction = ReSyncFlowClient.class.getDeclaredMethod("publishTypedInteractionProjection",
            ReSyncCatalogPublicationProjection.Snapshot.class, ReSyncTypedInteractionProjection.class);
        publishInteraction.setAccessible(true);
        assertTrue((boolean) publishInteraction.invoke(client, nodeSnapshot, interaction));
        Field authority = ReSyncFlowClient.class.getDeclaredField("catalogAuthority");
        authority.setAccessible(true);
        authority.set(client, ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION);
        assertTrue(client.typedInteractionProjection().isPresent());
        return new CatalogFixture(client, CatalogCachePublicationCodec.authoringPublicationChecksum(authoring));
    }

    private static Map<String, Object> sourceDescriptor() {
        Map<String, Object> execution = Map.of("kind", "named",
            "type", Map.of("ownerId", "builtin", "localId", "execution"), "arguments", List.of());
        Map<String, Object> flow = Map.of("kind", "pin", "id", "flow", "displayName", "Flow",
            "direction", "output", "type", execution, "description", "Continues the flow", "resourceRole", "",
            "editor", Map.of("ownerId", "builtin", "localId", "generic-editor"));
        return Map.of("kind", "node", "id", "visible", "schemaVersion", 1, "displayName", "Visible", "description",
            "Visible Node", "domain", "flow", "family", "utility", "pins", List.of(flow), "metadata", Map.of(),
            "inspector", Map.of("intent", "none", "sections", List.of()));
    }

    private static Map<String, Object> targetDescriptor() {
        Map<String, Object> execution = Map.of("kind", "named",
            "type", Map.of("ownerId", "builtin", "localId", "execution"), "arguments", List.of());
        Map<String, Object> flow = Map.of("kind", "pin", "id", "flow", "displayName", "Flow",
            "direction", "input", "type", execution, "description", "Receives the flow", "resourceRole", "",
            "editor", Map.of("ownerId", "builtin", "localId", "generic-editor"));
        return Map.of("kind", "node", "id", "receiver", "schemaVersion", 1, "displayName", "Receiver", "description",
            "Receiver Node", "domain", "flow", "family", "utility", "pins", List.of(flow), "metadata", Map.of(),
            "inspector", Map.of("intent", "none", "sections", List.of()));
    }

    private static Map<String, Object> resourceDescriptor() {
        Map<String, Object> type = Map.of("kind", "resource",
            "resourceType", Map.of("ownerId", "extension.resources", "localId", "flow"));
        Map<String, Object> resource = Map.of("kind", "pin", "id", "resource", "displayName", "Resource",
            "direction", "input", "type", type, "description", "Flow reference", "resourceRole", "reference",
            "editor", Map.of("ownerId", "builtin", "localId", "generic-editor"));
        Map<String, Object> contribution = Map.of("resourceOwner", "extension.resources", "resourceType", "flow",
            "capability", "reference", "owner", OWNER.canonicalText(), "nodeId", "resource.consume",
            "inputPin", "resource", "referenceKind", "extension.resources:flow", "referenceOwner",
            "extension.resources", "priority", 1);
        return Map.of("kind", "node", "id", "resource.consume", "schemaVersion", 1, "displayName",
            "Resource Consumer", "description", "Consumes a Flow resource", "domain", "flow", "family",
            "utility", "pins", List.of(resource), "metadata", Map.of("dropContributions", List.of(contribution)),
            "inspector", Map.of("intent", "none", "sections", List.of()));
    }

    private static GraphDocument document(long revision, List<GraphNode> nodes) {
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, revision, BINDING, Set.of(), nodes, List.of(),
            List.of(), List.of(), OpaqueData.empty());
    }

    private static GraphNode node() {
        return new GraphNode(NODE, ContractRef.of(OWNER, NodeId.of("visible")), 1, null, Map.of(), Map.of(),
            List.of(), List.of(), InspectorState.empty(), 5000, 3000, OpaqueData.empty());
    }

    private static GraphDocument connectedDocument() {
        NodeInstanceId sourceId = NodeInstanceId.deterministic("catalog-source");
        NodeInstanceId targetId = NodeInstanceId.deterministic("catalog-target");
        GraphNode source = new GraphNode(sourceId, ContractRef.of(OWNER, NodeId.of("visible")), 1, null, Map.of(),
            Map.of(), List.of(), List.of(), InspectorState.empty(), 200, 240, OpaqueData.empty());
        GraphNode target = new GraphNode(targetId, ContractRef.of(OWNER, NodeId.of("receiver")), 1, null, Map.of(),
            Map.of(), List.of(), List.of(), InspectorState.empty(), 720, 240, OpaqueData.empty());
        GraphConnection connection = new GraphConnection(ConnectionId.deterministic("catalog-wire"),
            new GraphEndpoint(sourceId, PinId.of("flow")), new GraphEndpoint(targetId, PinId.of("flow")));
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, 1L, BINDING, Set.of(), List.of(source, target),
            List.of(connection), List.of(), List.of(), OpaqueData.empty());
    }

    private static class EditorHarness extends GraphEditorScreen {
        private final CoreGraphEditorSession session;
        protected int tickCalls;
        protected int lifecycleTicks;

        protected EditorHarness(CoreGraphEditorSession session) throws Exception {
            super(emptyGraph(), SERVER.canonicalText(), new Screen());
            this.session = session;
            width = 900;
            height = 600;
            workerGate().set(true);
            setCoreGraphEditorSession(session);
        }

        @Override
        public boolean lifecycle(ReLifecycleEvent event) {
            if (event.action() == ReLifecycleEvent.Action.TICK) {
                lifecycleTicks++;
            }
            return super.lifecycle(event);
        }

        @Override
        public void tick() {
            tickCalls++;
            super.tick();
        }

        protected void materializeThroughFrames(Runnable frame) throws Exception {
            workerGate().set(false);
            Set<String> expectedNodes = session.graphDocument().nodes().stream()
                .map(node -> node.instanceId().canonicalText()).collect(Collectors.toSet());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (System.nanoTime() < deadline) {
                frame.run();
                if (exactGraphOwnership(expectedNodes) && !booleanField("initialViewportFitPending")) {
                    break;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertEquals(expectedNodes, graph.getNodes().keySet());
            assertEquals(expectedNodes, widgetCache.keySet());
            assertTrue(exactGraphOwnership(expectedNodes));
            assertFalse(booleanField("initialViewportFitPending"));
        }

        protected void materializeGraphState() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            Set<String> expectedNodes = session.graphDocument().nodes().stream()
                .map(node -> node.instanceId().canonicalText()).collect(Collectors.toSet());
            while (System.nanoTime() < deadline) {
                tick();
                workerGate().set(true);
                invoke("scheduleGraphRenderIndexBuild");
                invoke("drainGraphRenderBuilds");
                invoke("ensureGraphRenderIndex");
                if (exactGraphOwnership(expectedNodes)) {
                    break;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertEquals(expectedNodes, graph.getNodes().keySet());
            assertEquals(expectedNodes, widgetCache.keySet());
            assertNotNull(field("graphRenderIndex"));
            assertTrue(exactGraphOwnership(expectedNodes));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (booleanField("initialViewportFitPending") && System.nanoTime() < deadline) {
                renderHandler(new TestDrawContext(), width / 2, height / 2, 0F);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertFalse(booleanField("initialViewportFitPending"));
        }

        @SuppressWarnings("unchecked")
        private boolean exactGraphOwnership(Set<String> expectedNodes) throws Exception {
            if (!graph.getNodes().keySet().equals(expectedNodes) || !widgetCache.keySet().equals(expectedNodes)
                || booleanField("nodeCatalogRefreshQueued") || field("catalogRefresh") != null) {
                return false;
            }
            Object topology = field("coreWidgetTopology");
            Object index = field("graphRenderIndex");
            if (topology == null || index == null) {
                return false;
            }
            Field topologyWidgets = topology.getClass().getDeclaredField("widgetsById");
            topologyWidgets.setAccessible(true);
            if (topologyWidgets.get(topology) != widgetCache) {
                return false;
            }
            Field indexGraph = index.getClass().getDeclaredField("graph");
            indexGraph.setAccessible(true);
            if (indexGraph.get(index) != graph) {
                return false;
            }
            Field indexBounds = index.getClass().getDeclaredField("nodeBounds");
            indexBounds.setAccessible(true);
            Map<FlowNodeWidget, GraphEditorScreen.WorldBounds> bounds =
                (Map<FlowNodeWidget, GraphEditorScreen.WorldBounds>) indexBounds.get(index);
            return bounds.size() == widgetCache.size() && widgetCache.values().stream().allMatch(bounds::containsKey);
        }

        protected void awaitSessionNodes(int count) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (session.graphDocument().nodes().size() != count && System.nanoTime() < deadline) {
                tick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertEquals(count, session.graphDocument().nodes().size());
        }

        protected void awaitSaveCalls(AtomicInteger saves, int count) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (saves.get() != count && System.nanoTime() < deadline) {
                tick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertEquals(count, saves.get());
        }

        protected void awaitHistorySize(int size) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (session.historyState().size() != size && System.nanoTime() < deadline) {
                tick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertEquals(size, session.historyState().size());
        }

        protected void awaitDocument(GraphDocument expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (!expected.equals(session.graphDocument()) && System.nanoTime() < deadline) {
                tick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertEquals(expected, session.graphDocument());
        }

        protected void awaitCatalogMenu() throws Exception {
            ItemSelectorWidget selector = (ItemSelectorWidget) field("nodeItemSelector");
            assertNotNull(selector);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (selector.isVirtualLoading() && System.nanoTime() < deadline) {
                selector.tick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertFalse(selector.isVirtualLoading());
        }

        @SuppressWarnings("unchecked")
        protected int catalogDefinitionCount() throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("typedCatalogDefinitions");
            method.setAccessible(true);
            return ((List<Object>) method.invoke(this)).size();
        }

        @SuppressWarnings("unchecked")
        protected FlowNodeWidget widget(String id) throws Exception {
            return ((Map<String, FlowNodeWidget>) field("widgetCache")).get(id);
        }

        @SuppressWarnings("unchecked")
        protected GraphEditorScreen.WorldBounds nodeBounds(FlowNodeWidget widget) throws Exception {
            Object index = field("graphRenderIndex");
            assertNotNull(index);
            Field bounds = index.getClass().getDeclaredField("nodeBounds");
            bounds.setAccessible(true);
            return ((Map<FlowNodeWidget, GraphEditorScreen.WorldBounds>) bounds.get(index)).get(widget);
        }

        protected GraphEditorScreen.WorldBounds targetViewportBounds() throws Exception {
            double[] first = targetScreenToWorld(0, 0);
            double[] second = targetScreenToWorld(width, height);
            return new GraphEditorScreen.WorldBounds(Math.min(first[0], second[0]), Math.min(first[1], second[1]),
                Math.max(first[0], second[0]), Math.max(first[1], second[1]));
        }

        protected int publishedWireCount() throws Exception {
            Object index = field("graphRenderIndex");
            assertNotNull(index);
            Field groups = index.getClass().getDeclaredField("groupsByConnection");
            groups.setAccessible(true);
            return ((Map<?, ?>) groups.get(index)).size();
        }

        protected void invalidateRenderIndex() throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField("graphRenderIndex");
            field.setAccessible(true);
            field.set(this, null);
        }

        private double[] targetScreenToWorld(double x, double y) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("targetScreenToWorld", double.class, double.class);
            method.setAccessible(true);
            return (double[]) method.invoke(this, x, y);
        }

        private AtomicBoolean workerGate() throws Exception {
            return (AtomicBoolean) field("graphRenderWorkerQueued");
        }

        private boolean booleanField(String name) throws Exception {
            return (boolean) field(name);
        }

        private Object field(String name) throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(this);
        }

        private Object invoke(String name) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(this);
        }

        private static FlowGraph emptyGraph() {
            FlowGraph graph = new FlowGraph();
            graph.setId(RESOURCE.id());
            graph.setResourceType(ReSyncResourceType.FLOW.typeId());
            return graph;
        }
    }

    private static final class FrameEditor extends EditorHarness {
        private FrameEditor(CoreGraphEditorSession session) throws Exception {
            super(session);
        }

        @Override
        public void init() {
        }

        @Override
        public void resize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class FrameHost extends GraphEditorScreen {
        private ReSyncStudioView view;

        private FrameHost() {
            super(new FlowGraph(), SERVER.canonicalText(), new Screen());
            width = 900;
            height = 600;
        }

        @Override
        protected ReSyncStudioView activeStudioView() {
            return view;
        }
    }

    private static final class CatalogEditor extends EditorHarness {
        private final ReSyncFlowClient client;

        private CatalogEditor(CoreGraphEditorSession session, ReSyncFlowClient client) throws Exception {
            super(session);
            this.client = client;
            studioMode = true;
            StudioDocument document = new StudioDocument("flow", RESOURCE.id(), "Visible Flow", null, session, null,
                new StudioViewportState());
            studioDocuments.add(document);
            activeStudioDocument = document;
            setCoreGraphEditorSession(session);
        }

        private void openCatalogMenu() {
            showAllNodesMenu(200, 160);
        }

        private void saveCurrentGraph() {
            saveGraph();
        }

        private boolean dropResource(ReSyncResourceDragPayload payload) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("dropStudioResource",
                ReSyncResourceDragPayload.class, double.class, double.class);
            method.setAccessible(true);
            return (boolean) method.invoke(this, payload, width / 2.0, height / 2.0);
        }

        private String addResourceNode(Map<String, Object> inputs) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("addNode", int.class, int.class, String.class,
                String.class, Map.class);
            method.setAccessible(true);
            return (String) method.invoke(this, 200, 160, OWNER.canonicalText() + ":resource.consume", null, inputs);
        }

        private boolean undoCore() {
            return undoActiveHistory();
        }

        private boolean redoCore() {
            return redoActiveHistory();
        }

        private void organizeCore() throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("organizeGraph");
            method.setAccessible(true);
            method.invoke(this);
        }

        private boolean rejectingMove() throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreGlobalMutation", String.class,
                Function.class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                current.setNodePosition(NODE, 2_000_000_000D, 240D);
                return true;
            };
            return (boolean) method.invoke(this, "Unsafe Move", mutation);
        }

        @Override
        protected ReSyncFlowClient typedCatalogClient() {
            return client;
        }
    }

    private record CatalogFixture(ReSyncFlowClient client, ContentHash authoringChecksum) {
    }

    private static final class CatalogManager extends FlowManager {
        private final ReSyncFlowClient client;
        private CoreGraphEditorSession session;

        private CatalogManager(ReSyncFlowClient client, CoreGraphEditorSession session) {
            super(null, null);
            this.client = client;
            this.session = session;
        }

        private void use(CoreGraphEditorSession session) {
            this.session = session;
        }

        @Override
        public ReSyncFlowClient existingFlowClient(String serverId) {
            return client;
        }

        @Override
        public boolean isFlowClientConnected(String serverId) {
            return true;
        }

        @Override
        public boolean isCurrentCoreGraphEditorSession(String serverId, ReSyncResourceType type, String id,
                                                       CoreGraphEditorSession candidate) {
            return candidate != null && candidate == session;
        }
    }

    private static final class NoopTransport implements ReSyncFrameTransport {
        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
        }

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }
}
