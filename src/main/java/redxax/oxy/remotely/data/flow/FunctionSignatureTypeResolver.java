package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.ui.FlowNodeWidget;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class FunctionSignatureTypeResolver {
    private FunctionSignatureTypeResolver() {
    }

    public static int resolve(String serverId, FlowGraph graph) {
        FlowManager manager = FlowManager.getInstance();
        ReSyncFlowClient client = manager != null ? manager.existingFlowClient(serverId) : null;
        return client != null && ReSyncTypedCatalogConsumer.typedAuthorityAdvertised(client) ? resolve(client, graph) : 0;
    }

    static int resolve(ReSyncFlowClient client, FlowGraph graph) {
        if (client == null || client.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
            return 0;
        }
        Optional<ReSyncTypedInteractionProjection> projection = ReSyncTypedInteractionProjection.from(client);
        if (projection.isEmpty()) {
            return 0;
        }
        FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog = FlowNodeWidget.fromTypedProjection(projection.orElseThrow());
        if (!boundaryCatalog.isTypedProjectionAvailable()) {
            return 0;
        }
        return resolve(graph, projection.orElseThrow(), boundaryCatalog);
    }

    private static int resolve(FlowGraph graph, ReSyncTypedInteractionProjection projection,
                               FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog) {
        if (graph == null || !graph.isFunction() || graph.getConnections() == null || graph.getNodes() == null
            || projection == null || boundaryCatalog == null || !boundaryCatalog.isTypedProjectionAvailable()) {
            return 0;
        }
        int changes = 0;
        for (FlowConnection connection : graph.getConnections()) {
            if (connection == null) {
                continue;
            }
            FlowNode source = graph.getNodes().get(connection.getSourceNodeId());
            FlowNode target = graph.getNodes().get(connection.getTargetNodeId());
            FlowNodeWidget.FunctionBoundaryIntent sourceBoundary = boundaryCatalog.intent(source);
            FlowNodeWidget.FunctionBoundaryIntent targetBoundary = boundaryCatalog.intent(target);
            if (source != null && target != null && sourceBoundary != null
                && sourceBoundary.role() == FlowNodeWidget.FunctionBoundaryRole.INPUTS
                && !Objects.equals(boundaryCatalog.flowPin(source, false), connection.getSourcePinId())) {
                FlowTypeRef typeRef = projection.pinType(typedIdentity(target.getType()), connection.getTargetPinId(), true).orElse(null);
                changes += applyStableType(graph.getFunctionInputs(), connection.getSourcePinId(), typeRef);
            }
            if (source != null && target != null && targetBoundary != null
                && targetBoundary.role() == FlowNodeWidget.FunctionBoundaryRole.OUTPUTS
                && !Objects.equals(boundaryCatalog.flowPin(target, true), connection.getTargetPinId())) {
                FlowTypeRef typeRef = projection.pinType(typedIdentity(source.getType()), connection.getSourcePinId(), false).orElse(null);
                changes += applyStableType(graph.getFunctionOutputs(), connection.getTargetPinId(), typeRef);
            }
        }
        return changes;
    }

    private static ContractRef<NodeId> typedIdentity(String nodeType) {
        if (nodeType == null || nodeType.isBlank() || !nodeType.equals(nodeType.strip())) {
            return null;
        }
        int separator = nodeType.indexOf(':');
        if (separator <= 0 || separator == nodeType.length() - 1 || nodeType.indexOf(':', separator + 1) >= 0) {
            return null;
        }
        try {
            return ContractRef.of(new OwnerId(nodeType.substring(0, separator)), new NodeId(nodeType.substring(separator + 1)));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static String parameterIdentity(FlowGraph.FunctionParameter parameter) {
        return parameter != null ? canonicalParameterId(parameter.getParameterId()) : null;
    }

    private static int applyStableType(List<FlowGraph.FunctionParameter> parameters, String parameterId, FlowTypeRef typeRef) {
        if (parameters == null || parameterId == null || parameterId.isBlank() || typeRef == null) {
            return 0;
        }
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter == null || !parameterId.equals(canonicalParameterId(parameter.getParameterId()))) {
                continue;
            }
            FlowTypeRef currentType = parameter.getTypeRef();
            if (typeRef.equals(currentType) || !isImprecise(currentType) || isImprecise(typeRef)) {
                return 0;
            }
            parameter.setType(FlowDataType.fromString(typeRef.getTypeId()));
            parameter.setTypeRef(typeRef);
            return 1;
        }
        return 0;
    }

    private static String canonicalParameterId(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            return null;
        }
        try {
            return FunctionParameterId.parseCanonicalText(value).canonicalText();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean isImprecise(FlowTypeRef typeRef) {
        if (typeRef == null || "any".equals(typeRef.getTypeId())) {
            return true;
        }
        if (typeRef.getArguments().stream().anyMatch(FunctionSignatureTypeResolver::isImprecise)) {
            return true;
        }
        return typeRef.getArguments().isEmpty() && Set.of("list", "set", "map", "queue", "stack", "optional", "result", "job_reference", "resource_reference")
            .contains(typeRef.getTypeId());
    }
}
