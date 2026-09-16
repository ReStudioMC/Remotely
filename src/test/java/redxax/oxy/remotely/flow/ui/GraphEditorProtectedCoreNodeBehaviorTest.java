package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.ui.studio.StudioDocument;
import redxax.oxy.remotely.flow.ui.studio.StudioViewportState;
import restudio.rescreen.ui.core.Screen;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorProtectedCoreNodeBehaviorTest {
    private static final ServerId SERVER = ServerId.deterministic("protected-command-node");
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "1".repeat(64), "2".repeat(64));
    private static final ServerResourceLocator COMMAND = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("command")), "retained-command");
    private static final NodeInstanceId START = NodeInstanceId.deterministic("retained-command-start");

    @Test
    void retainedCommandStartRemainsMovableAndWireableButNotDeletableAcrossStudioRebind() throws Exception {
        CoreGraphEditorSession session = new CoreGraphEditorSession(commandDocument());
        EditorHarness editor = new EditorHarness();
        try {
            assertTrue(editor.protectedNode(session, START.canonicalText()));

            editor.bindStaleFlowDocument();

            assertTrue(editor.protectedNode(session, START.canonicalText()));
            assertTrue(editor.accessAllowed(session, ReSyncResourceType.FLOW, "MOVE", START.canonicalText()));
            assertTrue(editor.accessAllowed(session, ReSyncResourceType.FLOW, "STRUCTURE", START.canonicalText()));
            assertFalse(editor.accessAllowed(session, ReSyncResourceType.FLOW, "DELETE", START.canonicalText()));
        } finally {
            editor.removed();
        }
    }

    private static GraphDocument commandDocument() {
        GraphNode start = new GraphNode(START, CommandGraphContract.CANONICAL_START, 1, null, Map.of(), Map.of(),
            List.of(), List.of(), InspectorState.empty(), 120, 120, OpaqueData.empty());
        return new GraphDocument(new CatalogVersion(1, 0), COMMAND, 4, BINDING, Set.of(), List.of(start), List.of(),
            List.of(), List.of(), OpaqueData.empty());
    }

    private static final class EditorHarness extends GraphEditorScreen {
        private EditorHarness() {
            super(emptyGraph(), SERVER.canonicalText(), new Screen());
        }

        private void bindStaleFlowDocument() {
            FlowGraph stale = emptyGraph();
            stale.setId("other-flow");
            activeStudioDocument = new StudioDocument("flow", "other-flow", "Other Flow", stale, null,
                new StudioViewportState());
        }

        private boolean protectedNode(CoreGraphEditorSession session, String nodeId) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("isProtectedCoreNode",
                CoreGraphEditorSession.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(this, session, nodeId);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private boolean accessAllowed(CoreGraphEditorSession session, ReSyncResourceType staleType, String accessName,
                                      String nodeId) throws Exception {
            Class accessType = Arrays.stream(GraphEditorScreen.class.getDeclaredClasses())
                .filter(type -> "CoreMutationAccess".equals(type.getSimpleName()))
                .findFirst()
                .orElseThrow();
            Method method = GraphEditorScreen.class.getDeclaredMethod("coreMutationAccessAllowed",
                CoreGraphEditorSession.class, ReSyncResourceType.class, accessType, List.class);
            method.setAccessible(true);
            Object access = Enum.valueOf(accessType, accessName);
            return (boolean) method.invoke(this, session, staleType, access, List.of(nodeId));
        }

        private static FlowGraph emptyGraph() {
            FlowGraph graph = new FlowGraph();
            graph.setId("empty");
            graph.setResourceType("flow");
            return graph;
        }
    }
}
