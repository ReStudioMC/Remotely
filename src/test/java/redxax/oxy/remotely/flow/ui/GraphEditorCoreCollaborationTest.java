package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.ReSyncWorkspaceClient;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.ui.studio.ScreenBackedStudioView;
import redxax.oxy.remotely.flow.ui.studio.StudioDocument;
import redxax.oxy.remotely.flow.ui.studio.StudioViewportState;
import redxax.oxy.remotely.test.TestDrawContext;
import redxax.oxy.remotely.ui.collaboration.DesignerCollaborationAuthority;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorCoreCollaborationTest {
    private static final ServerId SERVER = ServerId.deterministic("collaboration-ui");
    private static final OwnerId OWNER = OwnerId.of("test");
    private static final NodeInstanceId NODE = NodeInstanceId.deterministic("collaboration-node");
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("flow")), "shared-flow");

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void coreUsesGraphAwareCursorsAndRemoteDragNeverChangesItsDocument() throws Exception {
        CoreGraphEditorSession session = new CoreGraphEditorSession(document());
        Editor editor = new Editor(session);
        try {
            editor.awaitWidget();
            assertTrue(editor.workspaceCursors());
            editor.awareness(drag(200, 140, 1));
            editor.animate();
            assertEquals(200, editor.widget().getX());
            assertEquals(140, editor.widget().getY());
            assertEquals(25, editor.graph.getNodes().get(NODE.canonicalText()).getX());
            assertEquals(25, session.graphDocument().nodes().getFirst().x());
            assertFalse(session.isDirty());

            editor.awareness(new ReSyncWorkspaceClient.Awareness("flow", RESOURCE.id(), "peer", null, new JsonObject(), 2));
            editor.animate();
            assertEquals(25, editor.widget().getX());
            assertEquals(40, editor.widget().getY());
            assertFalse(session.isDirty());
        } finally {
            editor.removed();
        }
    }

    @Test
    void localDragWinsOverRemotePreviewAndReconnectDropsTheDepartedPeer() throws Exception {
        Editor editor = new Editor(new CoreGraphEditorSession(document()));
        try {
            editor.awaitWidget();
            editor.selectLocal();
            field("movingSelectedNodes").setBoolean(editor, true);
            editor.widget().setPosition(80, 90);
            editor.awareness(drag(200, 140, 1));
            editor.animate();
            assertEquals(80, editor.widget().getX());
            assertEquals(90, editor.widget().getY());

            field("movingSelectedNodes").setBoolean(editor, false);
            editor.animate();
            assertEquals(200, editor.widget().getX());
            long generation = field("workspacePublicationGeneration").getLong(editor);
            invoke(editor, "offerWorkspaceAwarenessSnapshot", new Class<?>[] {List.class, long.class}, List.of(), generation);
            invoke(editor, "drainWorkspaceAwareness", new Class<?>[0]);
            editor.animate();
            assertEquals(25, editor.widget().getX());
            assertEquals(40, editor.widget().getY());

            invoke(editor, "offerWorkspaceAwareness", new Class<?>[] {ReSyncWorkspaceClient.Awareness.class, long.class},
                drag(500, 600, 2), generation - 1);
            invoke(editor, "drainWorkspaceAwareness", new Class<?>[0]);
            editor.animate();
            assertEquals(25, editor.widget().getX());
        } finally {
            editor.removed();
        }
    }

    @Test
    void snapshotAndSeveralPeerUpdatesApplyInOrderWithoutLosingTheNewestState() throws Exception {
        Editor editor = new Editor(new CoreGraphEditorSession(document()));
        try {
            long generation = field("workspacePublicationGeneration").getLong(editor);
            ReSyncWorkspaceClient.Awareness initial = drag(50, 60, 1);
            ReSyncWorkspaceClient.Awareness latest = drag(100, 120, 2);
            ReSyncWorkspaceClient.Awareness second = new ReSyncWorkspaceClient.Awareness(
                "flow", RESOURCE.id(), "other-peer", null, drag(300, 400, 3).state(), 3);
            invoke(editor, "offerWorkspaceAwarenessSnapshot", new Class<?>[] {List.class, long.class}, List.of(initial), generation);
            invoke(editor, "offerWorkspaceAwareness", new Class<?>[] {ReSyncWorkspaceClient.Awareness.class, long.class}, latest, generation);
            invoke(editor, "offerWorkspaceAwareness", new Class<?>[] {ReSyncWorkspaceClient.Awareness.class, long.class}, second, generation);
            invoke(editor, "drainWorkspaceAwareness", new Class<?>[] {long.class, int.class}, Long.MAX_VALUE, 32);
            assertEquals(latest, editor.peers().get("peer"));
            assertEquals(second, editor.peers().get("other-peer"));

            invoke(editor, "offerWorkspaceAwarenessSnapshot", new Class<?>[] {List.class, long.class}, List.of(latest), generation);
            invoke(editor, "drainWorkspaceAwareness", new Class<?>[] {long.class, int.class}, Long.MAX_VALUE, 32);
            assertEquals(Map.of("peer", latest), editor.peers());
        } finally {
            editor.removed();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicationsArrivingWhilePresentationRunsRemainTogetherForTheNextFrame() throws Exception {
        Editor editor = new Editor(new CoreGraphEditorSession(document()));
        try {
            long generation = field("workspacePublicationGeneration").getLong(editor);
            ReSyncWorkspaceClient.Awareness initial = drag(50, 60, 1);
            ReSyncWorkspaceClient.Awareness latest = drag(100, 120, 2);
            Map<String, Runnable> pending = (Map<String, Runnable>) field("workspaceAwarenessUpdates").get(editor);
            pending.put("delivery", () -> {
                try {
                    invoke(editor, "offerWorkspaceAwarenessSnapshot", new Class<?>[] {List.class, long.class}, List.of(initial), generation);
                    invoke(editor, "offerWorkspaceAwareness", new Class<?>[] {ReSyncWorkspaceClient.Awareness.class, long.class}, latest, generation);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            invoke(editor, "drainWorkspaceAwareness", new Class<?>[] {long.class, int.class}, Long.MAX_VALUE, 32);
            assertTrue(editor.peers().isEmpty());
            invoke(editor, "drainWorkspaceAwareness", new Class<?>[] {long.class, int.class}, Long.MAX_VALUE, 32);
            assertEquals(Map.of("peer", latest), editor.peers());
            invoke(editor, "drainWorkspaceAwareness", new Class<?>[] {long.class, int.class}, Long.MAX_VALUE, 32);
            assertEquals(Map.of("peer", latest), editor.peers());
        } finally {
            editor.removed();
        }
    }

    @Test
    void positionCaptureAndTabRetentionExcludeRemoteDragPreviews() throws Exception {
        CoreGraphEditorSession session = new CoreGraphEditorSession(document());
        Editor editor = new Editor(session);
        try {
            editor.awaitWidget();
            editor.awareness(drag(200, 140, 1));
            editor.animate();
            editor.syncNodePositions();
            assertEquals(25, session.graphDocument().nodes().getFirst().x());
            assertFalse(session.isDirty());
            editor.beforeStudioDocumentSelection();
            assertEquals(25, editor.widget().getX());
            assertEquals(40, editor.widget().getY());
            assertFalse(session.isDirty());
        } finally {
            editor.removed();
        }
    }

    @Test
    void graphNodesParticipateInCollaborationPathsWithoutTakingScreenPointerCapture() throws Exception {
        Editor editor = new Editor(new CoreGraphEditorSession(document()));
        try {
            editor.awaitWidget();

            assertTrue(editor.getScreenWidgetRoots().contains(editor.widget()));
            assertFalse(editor.getCaptureWidgetRoots().contains(editor.widget()));
            assertFalse(DesignerCollaborationAuthority.path(editor, editor.widget()).isEmpty());
        } finally {
            editor.removed();
        }
    }

    @Test
    void switchingDesignersRemovesMirroredSelectorsFromTheirOriginalScreen() throws Exception {
        Editor editor = new Editor(new CoreGraphEditorSession(document()));
        Screen previous = new Screen();
        Screen next = new Screen();
        try {
            editor.designer(previous);
            invoke(editor, "prepareWorkspaceCollaborationView", new Class<?>[0]);
            var picker = new ItemSelectorWidget.CollaborationState(160, 120, "stone", "Blocks", "No Matches", false,
                0f, 24, List.of());
            JsonObject selector = new JsonObject();
            selector.add("state", DesignerCollaborationAuthority.encodeState(picker));
            selector.addProperty("screenX", 0.2);
            selector.addProperty("screenY", 0.2);
            JsonObject state = new JsonObject();
            state.add("selector", selector);
            editor.awareness(new ReSyncWorkspaceClient.Awareness("flow", RESOURCE.id(), "peer", null, state, 1));
            invoke(editor, "renderWorkspaceSelectors", new Class<?>[] {IDrawContext.class}, new TestDrawContext());
            assertFalse(previous.getTransientWidgets().isEmpty());
            editor.designer(next);
            invoke(editor, "clearWorkspaceState", new Class<?>[0]);
            assertTrue(previous.getTransientWidgets().isEmpty());
            assertTrue(next.getTransientWidgets().isEmpty());
        } finally {
            editor.removed();
        }
    }

    private static ReSyncWorkspaceClient.Awareness drag(int x, int y, long updatedAt) {
        JsonArray position = new JsonArray();
        position.add(x);
        position.add(y);
        JsonObject positions = new JsonObject();
        positions.add(NODE.canonicalText(), position);
        JsonObject state = new JsonObject();
        state.add("nodePositions", positions);
        return new ReSyncWorkspaceClient.Awareness("flow", RESOURCE.id(), "peer", null, state, updatedAt);
    }

    private static GraphDocument document() {
        GraphNode node = new GraphNode(NODE, ContractRef.of(OWNER, NodeId.of("visible")), 1, null, Map.of(), Map.of(),
            List.of(), List.of(), InspectorState.empty(), 25, 40, OpaqueData.empty());
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, 1,
            new CatalogBinding(1, "1".repeat(64), "2".repeat(64)), Set.of(), List.of(node), List.of(),
            List.of(), List.of(), OpaqueData.empty());
    }

    private static Field field(String name) throws Exception {
        Field field = GraphEditorScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(Editor editor, String name, Class<?>[] signature, Object... arguments) throws Exception {
        Method method = GraphEditorScreen.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        return method.invoke(editor, arguments);
    }

    private static final class Editor extends GraphEditorScreen {
        private Editor(CoreGraphEditorSession session) throws Exception {
            super(emptyGraph(), SERVER.canonicalText(), new Screen());
            width = 960;
            height = 540;
            setCoreGraphEditorSession(session);
            field("workspaceType").set(this, "flow");
            field("workspaceResourceId").set(this, RESOURCE.id());
        }

        private void designer(Screen screen) {
            studioMode = true;
            activeStudioDocument = new StudioDocument("flow", RESOURCE.id(), "Shared Flow", null,
                new ScreenBackedStudioView(this, screen), new StudioViewportState());
        }

        private void awaitWidget() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (widget() == null && System.nanoTime() < deadline) {
                tick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertNotNull(widget());
        }

        private FlowNodeWidget widget() {
            return widgetCache.get(NODE.canonicalText());
        }

        @SuppressWarnings("unchecked")
        private Map<String, ReSyncWorkspaceClient.Awareness> peers() throws Exception {
            return (Map<String, ReSyncWorkspaceClient.Awareness>) field("workspaceAwareness").get(this);
        }

        private boolean workspaceCursors() {
            return rendersWorkspaceCursors();
        }

        @SuppressWarnings("unchecked")
        private void selectLocal() throws Exception {
            ((Set<String>) field("selectedNodeIds").get(this)).add(NODE.canonicalText());
        }

        private void awareness(ReSyncWorkspaceClient.Awareness awareness) throws Exception {
            invoke(this, "applyWorkspaceAwareness", new Class<?>[] {ReSyncWorkspaceClient.Awareness.class}, awareness);
        }

        private void animate() throws Exception {
            invoke(this, "applyWorkspaceDragPositions", new Class<?>[] {float.class}, 1F);
        }

        private static FlowGraph emptyGraph() {
            FlowGraph graph = new FlowGraph();
            graph.setId(RESOURCE.id());
            graph.setResourceType("flow");
            return graph;
        }
    }
}
