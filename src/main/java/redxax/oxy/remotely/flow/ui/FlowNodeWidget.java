package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncTypedInteractionProjection;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.graph.PinValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map;

public class FlowNodeWidget extends NodeWidget {
    private final FlowNode flowNode;
    private final FlowGraph flowGraph;
    private final FunctionBoundaryCatalog boundaryCatalog;

    public FlowNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId) {
        this(x, y, node, graph, nodeId, null, null, null, FunctionBoundaryCatalog.unavailable());
    }

    public FlowNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId) {
        this(x, y, node, graph, nodeId, serverId, null, null, boundaryCatalogForServer(serverId));
    }

    public FlowNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose) {
        this(x, y, node, graph, nodeId, serverId, onClose, null, boundaryCatalogForServer(serverId));
    }

    public FlowNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose, Runnable onMutation) {
        this(x, y, node, graph, nodeId, serverId, onClose, onMutation, boundaryCatalogForServer(serverId));
    }

    public FlowNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose,
                          Runnable onMutation, FunctionBoundaryCatalog boundaryCatalog) {
        this(x, y, node, graph, nodeId, serverId, onClose, onMutation, boundaryCatalog, null, false);
    }

    public FlowNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose,
                          Runnable onMutation, FunctionBoundaryCatalog boundaryCatalog, NodeDefinition definitionOverride,
                          boolean definitionReadOnly) {
        this(x, y, node, graph, nodeId, serverId, onClose, onMutation, boundaryCatalog, definitionOverride,
            definitionReadOnly, false);
    }

    public FlowNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose,
                          Runnable onMutation, FunctionBoundaryCatalog boundaryCatalog, NodeDefinition definitionOverride,
                          boolean definitionReadOnly, boolean definitionLookupBlocked) {
        this(x, y, node, graph, nodeId, serverId, onClose, onMutation, boundaryCatalog, definitionOverride,
            definitionReadOnly, definitionLookupBlocked, null);
    }

    public FlowNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose,
                          Runnable onMutation, FunctionBoundaryCatalog boundaryCatalog, NodeDefinition definitionOverride,
                          boolean definitionReadOnly, boolean definitionLookupBlocked,
                          NodeValueMutationHandler nodeValueMutationHandler) {
        this(x, y, node, graph, nodeId, serverId, onClose, onMutation, boundaryCatalog, definitionOverride,
            definitionReadOnly, definitionLookupBlocked, nodeValueMutationHandler, null, Map.of());
    }

    public FlowNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId, Runnable onClose,
                          Runnable onMutation, FunctionBoundaryCatalog boundaryCatalog, NodeDefinition definitionOverride,
                          boolean definitionReadOnly, boolean definitionLookupBlocked,
                          NodeValueMutationHandler nodeValueMutationHandler, ServerResourceLocator coreResource,
                          Map<PinId, PinValue> corePinValues) {
        super(x, y, node, graph, nodeId, serverId, onClose, onMutation, definitionOverride, definitionReadOnly,
            definitionLookupBlocked, nodeValueMutationHandler, coreResource, corePinValues);
        this.flowNode = node;
        this.flowGraph = graph;
        this.boundaryCatalog = boundaryCatalog != null ? boundaryCatalog
            : serverId != null ? boundaryCatalogForServer(serverId) : FunctionBoundaryCatalog.unavailable();
        refreshFunctionParameterButton();
        if (this.boundaryCatalog.intent(node) != null) {
            refreshInputWidgets();
        }
    }

    @Override
    protected FunctionBoundaryCatalog functionBoundaryCatalog() {
        FunctionBoundaryCatalog catalog = boundaryCatalog;
        return catalog != null ? catalog : super.functionBoundaryCatalog();
    }

    @Override
    public FlowTypeRef getPinTypeRef(String pinName, boolean isInput) {
        FlowTypeRef functionType = functionParameterTypeRef(pinName, isInput);
        if (functionType != null) {
            return functionType;
        }
        FlowTypeRef flowType = boundaryCatalog.flowPinType(flowNode, pinName, isInput);
        return flowType != null ? flowType : super.getPinTypeRef(pinName, isInput);
    }

    public boolean isBoundaryReadOnly() {
        return boundaryCatalog.isReadOnly();
    }

    public boolean isEditorReadOnly() {
        return !hasLoadedDefinition() || isDefinitionReadOnly() || isDefinitionLookupBlocked()
            || !isCoreWidget() && isFunctionStartOrEnd() && isBoundaryReadOnly();
    }

    public boolean isEditable() {
        return !isEditorReadOnly();
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (isEditorReadOnly()) {
            if (mouseClickedCloseButton(event) || mouseClickedInspectorButton(event)) {
                return true;
            }
            return true;
        }
        return super.mouseClicked(event);
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        return isEditorReadOnly() || super.mouseDragged(event);
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        return isEditorReadOnly() || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        return isEditorReadOnly() || super.mouseScrolled(event);
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        return isEditorReadOnly() || super.textInput(event);
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        return isEditorReadOnly() || super.keyPressed(event);
    }

    @Override
    public boolean assignLiteralInput(String pinName, String value) {
        return !isEditorReadOnly() && super.assignLiteralInput(pinName, value);
    }

    @Override
    public boolean removeOptionalInputPin(String pinName) {
        return !isEditorReadOnly() && super.removeOptionalInputPin(pinName);
    }

    @Override
    public void showParamContextMenu() {
        if (isCoreWidget()) {
            if (ScreenManager.getInstance().getCurrentScreen() instanceof GraphEditorScreen editor) {
                editor.showFunctionNodeContextMenu(this);
            } else {
                functionSignatureUnavailable();
            }
            return;
        }
        if (!isEditorReadOnly()) {
            super.showParamContextMenu();
        }
    }

    @Override
    public void showAddFunctionParameterPopup() {
        if (isCoreWidget()) {
            if (ScreenManager.getInstance().getCurrentScreen() instanceof GraphEditorScreen editor) {
                editor.showAddCoreFunctionParameterPopup(this);
            } else {
                functionSignatureUnavailable();
            }
            return;
        }
        if (!isEditorReadOnly()) {
            super.showAddFunctionParameterPopup();
        }
    }

    @Override
    public void removeFunctionParameter(String name) {
        if (isCoreWidget()) {
            if (ScreenManager.getInstance().getCurrentScreen() instanceof GraphEditorScreen editor) {
                editor.removeCoreFunctionParameter(this, name);
            } else {
                functionSignatureUnavailable();
            }
            return;
        }
        if (!isEditorReadOnly()) {
            super.removeFunctionParameter(name);
        }
    }

    private void functionSignatureUnavailable() {
        new Notification("Core Graph", "Function Signature Unavailable", Notification.Type.ERROR);
    }

    private FlowTypeRef functionParameterTypeRef(String pinName, boolean isInput) {
        return resolveFunctionParameterType(flowNode, flowGraph, pinName, isInput, boundaryCatalog);
    }

    static FlowTypeRef resolveFunctionParameterType(FlowNode flowNode, FlowGraph flowGraph, String pinName, boolean isInput) {
        return null;
    }

    static FlowTypeRef resolveFunctionParameterType(FlowNode flowNode, FlowGraph flowGraph, String pinName, boolean isInput,
                                                    FunctionBoundaryCatalog boundaryCatalog) {
        if (flowNode == null || flowGraph == null || pinName == null || boundaryCatalog == null) {
            return null;
        }
        return boundaryCatalog.parameterType(flowNode, flowGraph, pinName, isInput);
    }

    static String resolveFunctionFlowPin(FlowNode flowNode, boolean isInput) {
        return null;
    }

    static String resolveFunctionFlowPin(FlowNode flowNode, FunctionBoundaryCatalog boundaryCatalog, boolean isInput) {
        return boundaryCatalog != null ? boundaryCatalog.flowPin(flowNode, isInput) : null;
    }

    public static FunctionBoundaryCatalog boundaryCatalogForServer(String serverId) {
        FlowManager manager = FlowManager.getInstance();
        ReSyncFlowClient client = manager != null && serverId != null
            ? manager.existingFlowClient(serverId) : null;
        if (client == null) {
            return FunctionBoundaryCatalog.unavailable();
        }
        if (client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
            return ReSyncTypedInteractionProjection.from(client)
                .map(FlowNodeWidget::fromTypedProjection)
                .orElseGet(FunctionBoundaryCatalog::unavailable);
        }
        if (client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY) {
            return FunctionBoundaryCatalog.forServer(serverId);
        }
        return FunctionBoundaryCatalog.unavailable();
    }

    private static String text(Object value) {
        return value != null ? value.toString().trim() : "";
    }

    static ContractRef<NodeId> typedNodeIdentity(String owner, String nodeReference) {
        if (owner == null || nodeReference == null || owner.isBlank() || nodeReference.isBlank()
            || !owner.equals(owner.strip()) || !nodeReference.equals(nodeReference.strip())) {
            return null;
        }
        String normalizedOwner = owner;
        String normalizedNodeId = nodeReference;
        int separator = normalizedNodeId.indexOf(':');
        if (separator >= 0) {
            if (separator == 0 || separator == normalizedNodeId.length() - 1
                || normalizedNodeId.indexOf(':', separator + 1) >= 0
                || !normalizedOwner.equals(normalizedNodeId.substring(0, separator))) {
                return null;
            }
            normalizedNodeId = normalizedNodeId.substring(separator + 1);
        }
        if (normalizedOwner.isBlank() || normalizedNodeId.isBlank()) {
            return null;
        }
        try {
            return ContractRef.of(new OwnerId(normalizedOwner), new NodeId(normalizedNodeId));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static ContractRef<NodeId> typedNodeIdentity(String nodeReference) {
        if (nodeReference == null || nodeReference.isBlank() || !nodeReference.equals(nodeReference.strip())) {
            return null;
        }
        int separator = nodeReference.indexOf(':');
        if (separator <= 0 || separator == nodeReference.length() - 1
            || nodeReference.indexOf(':', separator + 1) >= 0) {
            return null;
        }
        return typedNodeIdentity(nodeReference.substring(0, separator), nodeReference.substring(separator + 1));
    }

    static boolean isBuiltinFunctionStartType(String nodeReference) {
        ContractRef<NodeId> identity = typedNodeIdentity(nodeReference);
        return identity != null && "builtin".equals(identity.owner().canonicalText())
            && "function.start".equals(identity.id().canonicalText());
    }

    static boolean isBuiltinFunctionEndType(String nodeReference) {
        ContractRef<NodeId> identity = typedNodeIdentity(nodeReference);
        return identity != null && "builtin".equals(identity.owner().canonicalText())
            && "function.end".equals(identity.id().canonicalText());
    }

    public static FunctionBoundaryCatalog fromTypedProjection(ReSyncTypedInteractionProjection projection) {
        if (projection == null) {
            return FunctionBoundaryCatalog.unavailable();
        }
        List<FunctionBoundaryIntent> inputs = new ArrayList<>();
        List<FunctionBoundaryIntent> outputs = new ArrayList<>();
        for (ReSyncTypedInteractionProjection.FunctionBoundary boundary : projection.functionBoundaries().values()) {
            List<FunctionParameterPin> parameters = boundary.parameterPins().stream()
                .map(value -> new FunctionParameterPin(value.id(), value.name(), parseType(value.typeRef())))
                .toList();
            FunctionBoundaryIntent intent = new FunctionBoundaryIntent(
                boundary.role() == ReSyncTypedInteractionProjection.FunctionBoundaryRole.INPUTS
                    ? FunctionBoundaryRole.INPUTS : FunctionBoundaryRole.OUTPUTS,
                boundary.nodeIdentity(), boundary.flowPin(), parameters);
            if (intent.role() == FunctionBoundaryRole.INPUTS) {
                inputs.add(intent);
            } else {
                outputs.add(intent);
            }
        }
        return FunctionBoundaryCatalog.of(FunctionBoundaryCatalog.unique(inputs), FunctionBoundaryCatalog.unique(outputs));
    }

    private static FlowTypeRef parseType(String value) {
        try {
            return FlowTypeRef.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static String canonicalNodeReference(ContractRef<NodeId> identity) {
        return identity == null ? "" : identity.owner().canonicalText() + ":" + identity.id().canonicalText();
    }

    public enum FunctionBoundaryRole {
        INPUTS,
        OUTPUTS
    }

    public record FunctionParameterPin(String id, String name, FlowTypeRef typeRef) {
        public FunctionParameterPin {
            id = text(id);
            name = text(name);
        }

        boolean isUsable() {
            return (!id.isBlank() || !name.isBlank()) && typeRef != null;
        }

        boolean matches(String pinName) {
            return matches(pinName, true);
        }

        boolean matches(String pinName, boolean allowNameFallback) {
            if (pinName == null || !isUsable()) {
                return false;
            }
            return !id.isBlank() ? id.equals(pinName) : allowNameFallback && name.equals(pinName);
        }

        String canonicalName() {
            return !id.isBlank() ? id : name;
        }
    }

    public record FunctionBoundaryIntent(FunctionBoundaryRole role, String nodeReference, String flowPin,
                                          List<FunctionParameterPin> parameterPins,
                                          ContractRef<NodeId> nodeIdentity) {
        public FunctionBoundaryIntent {
            nodeReference = nodeReference != null ? nodeReference : "";
            flowPin = text(flowPin);
            parameterPins = cleanParameters(parameterPins);
            if (nodeIdentity != null) {
                nodeReference = canonicalNodeReference(nodeIdentity);
            }
        }

        public FunctionBoundaryIntent(FunctionBoundaryRole role, String nodeReference, String flowPin,
                                      List<FunctionParameterPin> parameterPins) {
            this(role, nodeReference, flowPin, parameterPins, null);
        }

        public FunctionBoundaryIntent(FunctionBoundaryRole role, String nodeReference, String flowPin) {
            this(role, nodeReference, flowPin, List.of());
        }

        public FunctionBoundaryIntent(FunctionBoundaryRole role, ContractRef<NodeId> nodeIdentity,
                                      String flowPin, List<FunctionParameterPin> parameterPins) {
            this(role, nodeIdentity != null ? nodeIdentity.id().canonicalText() : "", flowPin, parameterPins, nodeIdentity);
        }

        boolean isUsable() {
            return role != null && !nodeReference.isBlank() && nodeReference.equals(nodeReference.strip())
                && !flowPin.isBlank() && uniqueParameters();
        }

        boolean hasTypedIdentity() {
            return nodeIdentity != null && nodeReference.equals(canonicalNodeReference(nodeIdentity));
        }

        boolean matches(String nodeType) {
            return isUsable() && nodeType != null && (nodeIdentity != null
                ? canonicalNodeReference(nodeIdentity).equals(nodeType) : nodeReference.equals(nodeType));
        }

        boolean flowPin(String pinName, boolean input) {
            return isUsable() && isFlowDirection(input) && normalizeFlowPin(pinName) != null;
        }

        String normalizeFlowPin(String pinName) {
            if (!isUsable() || pinName == null) {
                return null;
            }
            if (flowPin.equals(pinName)) {
                return flowPin;
            }
            return null;
        }

        String normalizeParameterPin(String pinName) {
            if (!isUsable() || pinName == null || normalizeFlowPin(pinName) != null) {
                return null;
            }
            List<FunctionParameterPin> matches = parameterPins.stream()
                .filter(pin -> pin.matches(pinName, !hasTypedIdentity())).toList();
            return matches.size() == 1 && matches.getFirst().isUsable() ? matches.getFirst().canonicalName() : null;
        }

        String normalizeParameterPin(FlowGraph graph, String pinName) {
            String declared = normalizeParameterPin(pinName);
            if (declared != null) {
                return declared;
            }
            if (!isUsable() || graph == null || pinName == null || normalizeFlowPin(pinName) != null
                || !parameterPins.isEmpty()) {
                return null;
            }
            List<FlowGraph.FunctionParameter> parameters = role == FunctionBoundaryRole.INPUTS
                ? graph.getFunctionInputs() : graph.getFunctionOutputs();
            if (parameters == null) {
                return null;
            }
            List<FlowGraph.FunctionParameter> matches = parameters.stream()
                .filter(parameter -> parameter != null && pinName.equals(parameter.getParameterId()))
                .toList();
            if (matches.size() == 1) {
                return matches.getFirst().getParameterId();
            }
            List<FlowGraph.FunctionParameter> legacyMatches = parameters.stream()
                .filter(parameter -> parameter != null && parameter.isLegacyIdentity() && pinName.equals(parameter.getName()))
                .toList();
            if (legacyMatches.size() != 1) {
                return null;
            }
            String parameterId = legacyMatches.getFirst().getParameterId();
            return parameterId != null && !parameterId.isBlank() ? parameterId : legacyMatches.getFirst().getName();
        }

        FlowTypeRef parameterType(FlowGraph graph, String pinName, boolean input) {
            if (!isUsable() || !isFlowDirection(input) || pinName == null || normalizeFlowPin(pinName) != null) {
                return null;
            }
            List<FunctionParameterPin> declared = parameterPins.stream()
                .filter(pin -> pin.matches(pinName, !hasTypedIdentity())).toList();
            if (declared.size() == 1 && declared.getFirst().typeRef() != null) {
                return declared.getFirst().typeRef();
            }
            if (!parameterPins.isEmpty()) {
                return null;
            }
            List<FlowGraph.FunctionParameter> parameters = role == FunctionBoundaryRole.INPUTS
                ? graph != null ? graph.getFunctionInputs() : null
                : graph != null ? graph.getFunctionOutputs() : null;
            if (parameters == null) {
                return null;
            }
            List<FlowGraph.FunctionParameter> matches = parameters.stream()
                .filter(parameter -> parameter != null && pinName.equals(parameter.getParameterId()))
                .toList();
            if (matches.size() == 1) {
                return matches.getFirst().getTypeRef();
            }
            List<FlowGraph.FunctionParameter> legacyMatches = parameters.stream()
                .filter(parameter -> parameter != null && parameter.isLegacyIdentity() && pinName.equals(parameter.getName()))
                .toList();
            return legacyMatches.size() == 1 ? legacyMatches.getFirst().getTypeRef() : null;
        }

        boolean isFlowDirection(boolean input) {
            return role == FunctionBoundaryRole.INPUTS && !input || role == FunctionBoundaryRole.OUTPUTS && input;
        }

        private boolean uniqueParameters() {
            Set<String> names = new HashSet<>();
            for (FunctionParameterPin pin : parameterPins) {
                if (pin == null || !pin.isUsable() || !names.add(pin.canonicalName())) {
                    return false;
                }
            }
            return true;
        }

        private static List<FunctionParameterPin> cleanParameters(List<FunctionParameterPin> pins) {
            if (pins == null || pins.isEmpty()) {
                return List.of();
            }
            return pins.stream().filter(Objects::nonNull).toList();
        }

    }

    public static final class FunctionBoundaryCatalog {
        private static final Map<String, CatalogPublication> SERVER_CATALOGS = BrowserSafeState.map();
        private final FunctionBoundaryIntent inputBoundary;
        private final FunctionBoundaryIntent outputBoundary;
        private final long authorityGeneration;
        private final String authorityChecksum;

        public FunctionBoundaryCatalog(FunctionBoundaryIntent inputBoundary, FunctionBoundaryIntent outputBoundary) {
            this(inputBoundary, outputBoundary, 0L, "");
        }

        private FunctionBoundaryCatalog(FunctionBoundaryIntent inputBoundary, FunctionBoundaryIntent outputBoundary,
                                        long authorityGeneration, String authorityChecksum) {
            this.inputBoundary = inputBoundary != null && inputBoundary.role() == FunctionBoundaryRole.INPUTS ? inputBoundary : null;
            this.outputBoundary = outputBoundary != null && outputBoundary.role() == FunctionBoundaryRole.OUTPUTS ? outputBoundary : null;
            this.authorityGeneration = Math.max(0L, authorityGeneration);
            this.authorityChecksum = text(authorityChecksum);
        }

        public static FunctionBoundaryCatalog of(FunctionBoundaryIntent inputBoundary, FunctionBoundaryIntent outputBoundary) {
            return new FunctionBoundaryCatalog(inputBoundary, outputBoundary);
        }

        public static FunctionBoundaryCatalog unavailable() {
            return new FunctionBoundaryCatalog(null, null);
        }

        public static FunctionBoundaryCatalog forServer(String serverId) {
            return resolve(serverId);
        }

        public static FunctionBoundaryCatalog resolve(String serverId) {
            String key = serverKey(serverId);
            if (key == null) {
                return unavailable();
            }
            synchronized (ReSyncResourceDropCapabilities.publicationLock()) {
                CatalogPublication publication = SERVER_CATALOGS.get(key);
                return publication != null ? publication.catalog() : unavailable();
            }
        }

        public static FunctionBoundaryCatalog resolveForServer(String serverId) {
            return resolve(serverId);
        }

        public static boolean publish(String serverId, long generation, String checksum,
                                      FunctionBoundaryCatalog catalog) {
            return false;
        }

        static void activate(String serverId, FunctionBoundaryCatalog catalog) {
            String key = serverKey(serverId);
            if (key == null || catalog == null) {
                throw new IllegalArgumentException("function boundary publication identity is required");
            }
            SERVER_CATALOGS.put(key, new CatalogPublication(catalog.generation(), catalog.checksum(), catalog));
        }

        static void deactivate(String serverId) {
            String key = serverKey(serverId);
            if (key != null) {
                SERVER_CATALOGS.remove(key);
            }
        }

        public static boolean publishDefinitions(String serverId, long generation, String checksum,
                                                  Collection<NodeDefinition> definitions) {
            return publish(serverId, generation, checksum, fromDefinitions(definitions));
        }

        public static boolean publishDefinitions(String serverId, long generation, String checksum,
                                                  Map<String, NodeDefinition> definitions) {
            return publishDefinitions(serverId, generation, checksum, definitions != null ? definitions.values() : null);
        }

        public static boolean publish(String serverId, FunctionBoundaryCatalog catalog,
                                      long generation, String checksum) {
            return publish(serverId, generation, checksum, catalog);
        }

        public static boolean remove(String serverId, long generation, String checksum) {
            String key = serverKey(serverId);
            String normalizedChecksum = text(checksum);
            if (key == null || generation < 0L || normalizedChecksum.isBlank()) {
                return false;
            }
            synchronized (ReSyncResourceDropCapabilities.publicationLock()) {
                CatalogPublication current = SERVER_CATALOGS.get(key);
                if (current == null || current.generation() != generation || !normalizedChecksum.equals(current.checksum())) {
                    return false;
                }
                SERVER_CATALOGS.remove(key);
                ReSyncResourceDropCapabilities.clearPublishedDropState(key);
                return true;
            }
        }

        public static boolean remove(String serverId) {
            String key = serverKey(serverId);
            synchronized (ReSyncResourceDropCapabilities.publicationLock()) {
                if (key == null) {
                    return false;
                }
                boolean removed = SERVER_CATALOGS.remove(key) != null;
                ReSyncResourceDropCapabilities.clearPublishedDropState(key);
                return removed;
            }
        }

        public static FunctionBoundaryCatalog fromDefinitions(Collection<NodeDefinition> definitions) {
            if (definitions == null || definitions.isEmpty()) {
                return unavailable();
            }
            List<FunctionBoundaryIntent> inputs = new ArrayList<>();
            List<FunctionBoundaryIntent> outputs = new ArrayList<>();
            for (NodeDefinition definition : definitions) {
                FunctionBoundaryIntent intent = fromDefinition(definition);
                if (intent == null) {
                    continue;
                }
                if (intent.role() == FunctionBoundaryRole.INPUTS) {
                    inputs.add(intent);
                } else if (intent.role() == FunctionBoundaryRole.OUTPUTS) {
                    outputs.add(intent);
                }
            }
            return new FunctionBoundaryCatalog(unique(inputs), unique(outputs));
        }

        public boolean isComplete() {
            return inputBoundary != null && inputBoundary.isUsable() && outputBoundary != null && outputBoundary.isUsable();
        }

        public boolean isReadOnly() {
            return !isComplete();
        }

        public long generation() {
            return authorityGeneration;
        }

        public String checksum() {
            return authorityChecksum;
        }

        public boolean isAuthoritative() {
            return !authorityChecksum.isBlank();
        }

        public boolean isProjectionAvailable() {
            return isComplete();
        }

        public boolean isTypedProjectionAvailable() {
            return isComplete() && inputBoundary.hasTypedIdentity() && outputBoundary.hasTypedIdentity();
        }

        public FunctionBoundaryIntent intent(FlowNode node) {
            if (node == null) {
                return null;
            }
            boolean inputs = inputBoundary != null && inputBoundary.matches(node.getType());
            boolean outputs = outputBoundary != null && outputBoundary.matches(node.getType());
            return inputs == outputs ? null : inputs ? inputBoundary : outputBoundary;
        }

        public FunctionBoundaryIntent intent(FunctionBoundaryRole role) {
            return role == FunctionBoundaryRole.INPUTS ? inputBoundary : role == FunctionBoundaryRole.OUTPUTS ? outputBoundary : null;
        }

        public String nodeReference(FunctionBoundaryRole role) {
            FunctionBoundaryIntent intent = intent(role);
            return intent != null && intent.isUsable() ? intent.nodeReference() : null;
        }

        public String flowPin(FlowNode node, boolean input) {
            FunctionBoundaryIntent intent = intent(node);
            return intent != null && intent.isFlowDirection(input) ? intent.flowPin() : null;
        }

        public FlowTypeRef flowPinType(FlowNode node, String pinName, boolean input) {
            FunctionBoundaryIntent intent = intent(node);
            return intent != null && intent.flowPin(pinName, input) ? FlowTypeRef.simple("execution") : null;
        }

        public FlowTypeRef parameterType(FlowNode node, FlowGraph graph, String pinName, boolean input) {
            FunctionBoundaryIntent intent = intent(node);
            return intent != null ? intent.parameterType(graph, pinName, input) : null;
        }

        public String normalizeConnectionPin(FlowGraph graph, FunctionBoundaryRole role, String pinName) {
            FunctionBoundaryIntent intent = intent(role);
            if (intent == null || !intent.isUsable() || pinName == null) {
                return null;
            }
            String flowPin = intent.normalizeFlowPin(pinName);
            if (flowPin != null) {
                return flowPin;
            }
            return intent.normalizeParameterPin(graph, pinName);
        }

        public String nodeId(FlowGraph graph, FunctionBoundaryRole role) {
            FunctionBoundaryIntent boundary = intent(role);
            if (graph == null || graph.getNodes() == null || boundary == null || !boundary.isUsable()) {
                return null;
            }
            List<String> matches = graph.getNodes().entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null
                    && boundary.matches(entry.getValue().getType()))
                .map(Map.Entry::getKey)
                .distinct()
                .toList();
            return matches.size() == 1 ? matches.getFirst() : null;
        }

        public boolean hasAmbiguousNode(FlowGraph graph, FunctionBoundaryRole role) {
            FunctionBoundaryIntent boundary = intent(role);
            if (graph == null || graph.getNodes() == null || boundary == null || !boundary.isUsable()) {
                return false;
            }
            return graph.getNodes().entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null
                    && boundary.matches(entry.getValue().getType()))
                .map(Map.Entry::getKey)
                .distinct()
                .count() > 1;
        }

        public FunctionBoundaryCatalog complete() {
            return this;
        }

        FunctionBoundaryCatalog withAuthority(long generation, String checksum) {
            return new FunctionBoundaryCatalog(inputBoundary, outputBoundary, generation, checksum);
        }

        private static String serverKey(String serverId) {
            String key = text(serverId);
            return key.isBlank() ? null : key;
        }

        private record CatalogPublication(long generation, String checksum, FunctionBoundaryCatalog catalog) {
        }

        private static FunctionBoundaryIntent unique(List<FunctionBoundaryIntent> intents) {
            List<FunctionBoundaryIntent> unique = intents.stream().distinct().toList();
            return unique.size() == 1 ? unique.getFirst() : null;
        }

        private static FunctionBoundaryIntent fromDefinition(NodeDefinition definition) {
            if (definition == null) {
                return null;
            }
            Map<String, Object> config = definition.getHandlerConfig();
            Map<?, ?> boundary = map(config != null ? config.get("functionBoundary") : null);
            String roleValue = text(boundary, "role");
            FunctionBoundaryRole role = role(roleValue);
            if (role == null) {
                return null;
            }
            String nodeReference = text(boundary, "nodeReference");
            if (nodeReference.isBlank()) {
                nodeReference = text(definition.getCanonicalId());
            }
            if (nodeReference.isBlank()) {
                nodeReference = text(definition.getId());
            }
            List<NodeDefinition.PinDefinition> pins = role == FunctionBoundaryRole.INPUTS ? definition.getOutputs() : definition.getInputs();
            String configuredFlowPin = text(boundary, "flowPin");
            if (!configuredFlowPin.isBlank() && (pins == null || pins.stream().noneMatch(pin -> pin != null && pin.getId() != null
                && configuredFlowPin.equals(pin.getId().value())))) {
                return null;
            }
            String flowPin = configuredFlowPin;
            if (flowPin.isBlank()) {
                flowPin = uniqueFlowPin(pins);
            }
            if (nodeReference.isBlank() || flowPin.isBlank()) {
                return null;
            }
            List<FunctionParameterPin> parameters = pins == null ? List.of() : pins.stream()
                .filter(pin -> pin != null && pin.getType() != NodeDefinition.PinType.FLOW && pin.getId() != null
                    && !pin.getId().value().isBlank())
                .map(pin -> new FunctionParameterPin(pin.getId().value(), pin.getDisplayName(), pin.getTypeRef()))
                .toList();
            if (parameters.stream().map(FunctionParameterPin::canonicalName).distinct().count() != parameters.size()) {
                return null;
            }
            String owner = text(definition.getOwner());
            if (owner.isBlank()) {
                return null;
            }
            ContractRef<NodeId> identity = typedNodeIdentity(owner, nodeReference);
            return identity != null ? new FunctionBoundaryIntent(role, identity, flowPin, parameters) : null;
        }

        private static String uniqueFlowPin(List<NodeDefinition.PinDefinition> pins) {
            if (pins == null) {
                return null;
            }
            List<String> names = pins.stream()
                .filter(pin -> pin != null && pin.getType() == NodeDefinition.PinType.FLOW)
                .map(pin -> pin.getId() != null ? pin.getId().value() : "")
                .map(FlowNodeWidget::text)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
            return names.size() == 1 ? names.getFirst() : null;
        }

        private static FunctionBoundaryRole role(String value) {
            if (value == null) {
                return null;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "input", "inputs" -> FunctionBoundaryRole.INPUTS;
                case "output", "outputs" -> FunctionBoundaryRole.OUTPUTS;
                default -> null;
            };
        }

        private static Map<?, ?> map(Object value) {
            return value instanceof Map<?, ?> values ? values : Map.of();
        }

        private static String text(Map<?, ?> values, String key) {
            return text(values.get(key));
        }

        private static String text(Object value) {
            return FlowNodeWidget.text(value);
        }

    }

}
