package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphUiProjectionSafetyTest {
    @Test
    void editorProjectionRejectsUnboundedNodeCoordinates() {
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.deterministic("projection-safety"),
            ContractRef.of(OwnerId.of("test"), ResourceTypeId.of("flow")), "unbounded");
        GraphNode node = new GraphNode(NodeInstanceId.deterministic("unbounded-node"),
            ContractRef.of(OwnerId.of("test"), NodeId.of("node")), 1, null, java.util.Map.of(), java.util.Map.of(),
            List.of(), List.of(), InspectorState.empty(), 1_073_741_825D, 0D, OpaqueData.empty());
        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource, 1L,
            new CatalogBinding(1, "1".repeat(64), "2".repeat(64)), Set.of(), List.of(node), List.of(), List.of(),
            List.of(), OpaqueData.empty());

        CoreGraphUiProjection.ProjectionResult result = new CoreGraphUiProjection().projectEditorSession(
            new CoreGraphEditorSession(document));

        assertFalse(result.complete());
        assertNull(result.graph());
        assertTrue(result.rejectionReason().contains("outside render bounds"));
    }
}
