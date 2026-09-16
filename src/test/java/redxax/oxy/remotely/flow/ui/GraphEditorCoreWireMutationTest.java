package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorCoreWireMutationTest {
    private static final Path SOURCE = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java");
    private static final Path FLOW_WIDGET = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/FlowNodeWidget.java");

    @Test
    void emptyCoreEditCapabilitiesRemainValid() throws IOException {
        String source = Files.readString(SOURCE);

        assertFalse(source.contains("if (editCapabilities.isEmpty())"));
        assertFalse(source.contains("if (publication.advertisedEditCapabilities().isEmpty())"));
        assertTrue(source.contains("publication.advertisedEditCapabilities().containsAll(editCapabilities)"));
        int capability = source.indexOf("private boolean coreCapabilityAllowed(CoreGraphEditorSession session, String nodeId)");
        int multipleNodes = source.indexOf("private boolean coreCapabilityAllowed(String... nodeIds)", capability);

        assertTrue(capability >= 0 && multipleNodes > capability);
        String capabilitySource = source.substring(capability, multipleNodes);
        assertTrue(capabilitySource.contains("if (session == null || !coreCapabilityAllowed(session))"));
        assertTrue(capabilitySource.contains("projection.descriptor(node.definition())"));
        assertTrue(capabilitySource.contains("descriptor != null && !descriptor.readOnly()"));
        assertTrue(capabilitySource.contains("descriptor.fields().stream().allMatch(field ->"));
        assertTrue(capabilitySource.contains("coreCapabilityReference(field.capability())"));
        assertTrue(capabilitySource.contains("field.editable() && capability != null && session.editCapabilities().contains(capability)"));
    }

    @Test
    void coreWireCreationUsesTypedMutationAndNodeAutoWire() throws IOException {
        String source = Files.readString(SOURCE);
        int start = source.indexOf("private void startWireDrag");
        int passthrough = source.indexOf("private void toggleInputPassthrough", start);
        int target = source.indexOf("private boolean connectWireTarget");
        int fuzzy = source.indexOf("private FuzzyWireTarget findFuzzyWireTarget", target);

        assertTrue(start >= 0 && passthrough > start);
        assertTrue(target >= 0 && fuzzy > target);
        assertFalse(source.substring(start, passthrough).contains("coreOperationUnavailable(\"Wire Connection\")"));
        String targetSource = source.substring(target, fuzzy);
        assertTrue(targetSource.contains("connectCoreWireTarget(targetWidget, targetNodeId, targetPin, targetIsInput)"));
        assertTrue(targetSource.contains("private boolean connectCoreWireTarget"));
        assertTrue(targetSource.contains("current.setConnections(connections)"));
        assertTrue(source.contains("String autoWireTargetNodeId = pendingInput ? pendingNodeId : identity.canonicalText();"));
        assertTrue(source.contains("createdCoreEndpoint(node, createdDefinition, autoWirePin)"));
        assertTrue(source.contains("coreEndpoint(null, pendingNodeId, pendingPin, !pendingInput)"));
        assertTrue(source.contains("nodes.add(createdNode)"));
    }

    @Test
    void incompatibleDirectPinDropDoesNotFallThroughToAnotherPin() throws IOException {
        String source = Files.readString(SOURCE);
        int start = source.indexOf("private void tryCompleteWire");
        int end = source.indexOf("private record LegacyWireCommand", start);

        assertTrue(start >= 0 && end > start);
        String completion = source.substring(start, end);
        assertTrue(completion.contains("boolean pinTargeted = false;"));
        assertTrue(completion.contains("if (!dragState.sourceIsInput && onInput)"));
        assertTrue(completion.contains("if (dragState.sourceIsInput && onOutput)"));
        assertTrue(completion.contains("if (!connected && !pinTargeted)"));
        assertTrue(completion.contains("if (!connected && !pinTargeted && dragPinWidget != null)"));
    }

    @Test
    void repeatableWiresKeepElementIdentityAcrossHitTestingRenderingAndDisconnect() throws IOException {
        String source = Files.readString(SOURCE);
        int endpointMatch = source.indexOf("private boolean coreConnectionEndpointMatches");
        int endpoint = source.indexOf("private GraphEndpoint coreEndpoint", endpointMatch);
        int refreshCatalog = source.indexOf("public static void refreshNodeCatalogForServer", endpoint);
        int viewPin = source.indexOf("private String coreConnectionViewPin");
        int pinPoint = source.indexOf("private PinPoint pinPoint", viewPin);
        int contextMenu = source.indexOf("private boolean showCoreRepeatableContextMenu");
        int connect = source.indexOf("private boolean connectCoreWireTarget", contextMenu);

        assertTrue(endpointMatch >= 0 && endpoint > endpointMatch && refreshCatalog > endpoint);
        String endpointSource = source.substring(endpointMatch, refreshCatalog);
        assertTrue(endpointSource.contains("widget.corePinEndpoint(pinName)"));
        assertTrue(endpointSource.contains("Objects.equals(endpoint.elementId(), elementId)"));
        assertTrue(endpointSource.contains("new GraphEndpoint(node, pin, element, branch)"));
        assertTrue(viewPin >= 0 && pinPoint > viewPin);
        String viewPinSource = source.substring(viewPin, pinPoint);
        assertTrue(viewPinSource.contains("coreEndpointElement(connection, source)"));
        assertTrue(viewPinSource.contains("widget.coreViewPin(pinId, elementId)"));
        assertTrue(source.contains("coreConnectionViewPin(sourceWidget, editorPin, connection, true)"));
        assertTrue(source.contains("coreConnectionViewPin(targetWidget, connection.getTargetPin(), connection, false)"));
        assertTrue(contextMenu >= 0 && connect > contextMenu);
        assertTrue(source.substring(contextMenu, connect).contains("Disconnect Pin"));
    }

    @Test
    void coreFunctionBoundaryKeepsOrdinaryControlsEditableAndBoundsSignatureEditing() throws IOException {
        String editor = Files.readString(SOURCE);
        String widget = Files.readString(FLOW_WIDGET);

        assertTrue(editor.contains("(!boundaryNode || coreDocument || !boundaryCatalog.isReadOnly())"));
        assertFalse(editor.contains("if (coreDocument && !repeatables.available())"));
        assertTrue(editor.contains("editable && repeatables.available() ? this::handleCoreRepeatableMutation : null"));
        assertTrue(widget.contains("!isCoreWidget() && isFunctionStartOrEnd() && isBoundaryReadOnly()"));
        assertTrue(widget.contains("functionSignatureUnavailable();"));
        assertTrue(widget.contains("new Notification(\"Core Graph\", \"Function Signature Unavailable\""));
    }

    @Test
    void coreProjectionFailurePreservesTheVisibleGraph() throws IOException {
        String source = Files.readString(SOURCE);
        int worker = source.indexOf("private void buildCoreProjections()");
        int apply = source.indexOf("private void applyCoreProjectionBuild()", worker);
        int fence = source.indexOf("static boolean coreProjectionPublicationMatches", apply);
        int commit = source.indexOf("private boolean commitProjectedCoreMutation", fence);

        assertTrue(worker >= 0 && apply > worker && fence > apply && commit > fence);
        String workerSource = source.substring(worker, apply);
        String applySource = source.substring(apply, fence);
        String fenceSource = source.substring(fence, commit);

        assertTrue(workerSource.contains("projectEditorSnapshot(request.snapshot())"));
        assertTrue(applySource.contains("while ((build = coreProjectionBuildResults.poll()) != null)"));
        assertTrue(applySource.contains("if (coreMutationCommitPending != mutation"));
        assertTrue(applySource.contains("settleCoreMutation(false, mutation, null)"));
        assertTrue(applySource.contains("settleCoreMutation(true, mutation, build.projection())"));
        assertTrue(applySource.contains("coreProjectionPublicationMatches(request, session, coreProjectionRequestGeneration"));
        assertFalse(applySource.contains("graph = studioEmptyGraph"));
        assertTrue(fenceSource.contains("request.generation() == currentGeneration"));
        assertTrue(fenceSource.contains("request.snapshot() == requestedSnapshot"));
        assertTrue(fenceSource.contains("request.snapshot().currentFor(session)"));
        assertTrue(fenceSource.contains("request.mutation().baseline().currentFor(session)"));
        assertTrue(fenceSource.contains("request.snapshot().currentFor(request.mutation().candidate())"));
    }

    @Test
    void coreAndDesignerInputBypassLegacyWorkspaceFreezes() throws IOException {
        String source = Files.readString(SOURCE);
        int defer = source.indexOf("private boolean deferWorkspaceMutation(Runnable mutation)");
        int backpressure = source.indexOf("private void notifyWorkspaceMutationBackpressure()", defer);
        String deferSource = source.substring(defer, backpressure);

        assertTrue(deferSource.contains("isActiveCoreStudioDocument() || activeStudioView() != null"));
        assertTrue(deferSource.indexOf("isActiveCoreStudioDocument() || activeStudioView() != null")
            < deferSource.indexOf("workspaceSnapshotPending()"));
    }
}
