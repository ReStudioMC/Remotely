package redxax.oxy.remotely.flow.data;

import com.google.gson.JsonElement;
import restudio.resync.flow.identity.FunctionParameterId;
import redxax.oxy.remotely.nodegraph.editor.GraphModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class FlowGraph implements GraphModel {
    public static final int CURRENT_VERSION = 2;
    private String id;
    private boolean enabled = true;
    private int version;
    private Map<String, FlowNode> nodes;
    private List<FlowConnection> connections;
    private List<FlowVariable> localVariables;
    private boolean function;
    private String functionOwner;
    private String functionNamespace;
    private int functionVersion;
    private String functionDescription;
    private List<FunctionParameter> functionInputs;
    private List<FunctionParameter> functionOutputs;
    private List<EditorPassthrough> editorPassthroughs;
    private Map<String, Object> contentProperties;
    private String resourceType;
    private long resourceRevision;
    private String resourceHash;
    private String resourceMutationId;
    private transient Map<String, JsonElement> opaqueProperties;

    public static class FunctionParameter {
        private String parameterId;
        private String name;
        private String displayName;
        private FlowDataType type;
        private FlowTypeRef typeRef;
        private String widget;
        private String optionsSource;
        private String defaultValue;
        private transient Map<String, JsonElement> opaqueProperties;
        private transient Map<String, JsonElement> opaqueNestedProperties;
        private transient boolean legacyIdentity;

        public FunctionParameter() {
            this(null, "", FlowDataType.ANY, "", "", "", true);
        }

        private FunctionParameter(String parameterId, String name, FlowDataType type, String widget,
                                  String optionsSource, String defaultValue, boolean legacyIdentity) {
            this.parameterId = parameterId;
            this.name = "";
            this.displayName = null;
            this.type = type != null ? type : FlowDataType.ANY;
            this.typeRef = FlowTypeRef.simple(this.type.getId()).normalizedGenerics();
            this.widget = widget != null ? widget : "";
            this.optionsSource = optionsSource != null ? optionsSource : "";
            this.defaultValue = defaultValue != null ? defaultValue : "";
            this.legacyIdentity = legacyIdentity;
            this.name = name != null ? name : "";
        }

        public FunctionParameter(String name, FlowDataType type) {
            this(null, name, type, "", "", "", true);
        }

        public FunctionParameter(String name, FlowDataType type, String widget, String optionsSource, String defaultValue) {
            this(null, name, type, widget, optionsSource, defaultValue, true);
        }

        public FunctionParameter(String parameterId, String name, FlowDataType type) {
            this(parameterId, name, type, "", "", "", false);
        }

        public FunctionParameter(String parameterId, String name, FlowDataType type, String widget,
                                  String optionsSource, String defaultValue) {
            this(parameterId, name, type, widget, optionsSource, defaultValue, false);
        }

        public static FunctionParameter stable(String parameterId, String displayName, FlowDataType type) {
            return new FunctionParameter(parameterId, displayName, type);
        }

        public static FunctionParameter legacy(String name, FlowDataType type) {
            return new FunctionParameter(name, type);
        }

        public static FunctionParameter fromLegacy(String name, FlowDataType type) {
            return legacy(name, type);
        }

        public String getParameterId() {
            return parameterId;
        }

        public void setParameterId(String parameterId) {
            this.parameterId = parameterId != null && !parameterId.isBlank() ? parameterId : null;
            this.legacyIdentity = this.parameterId == null;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDisplayName() {
            return displayName != null && !displayName.isBlank() ? displayName : name;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public FlowDataType getType() {
            return type;
        }

        public void setType(FlowDataType type) {
            this.type = type;
            this.typeRef = FlowTypeRef.simple(type != null ? type.getId() : FlowDataType.ANY.getId()).normalizedGenerics();
        }

        public FlowTypeRef getTypeRef() {
            return (typeRef != null ? typeRef : FlowTypeRef.simple(type != null ? type.getId() : FlowDataType.ANY.getId())).normalizedGenerics();
        }

        public void setTypeRef(FlowTypeRef typeRef) {
            this.typeRef = (typeRef != null ? typeRef : FlowTypeRef.simple(type != null ? type.getId() : FlowDataType.ANY.getId())).normalizedGenerics();
        }

        public String getWidget() {
            return widget;
        }

        public void setWidget(String widget) {
            this.widget = widget;
        }

        public String getOptionsSource() {
            return optionsSource;
        }

        public void setOptionsSource(String optionsSource) {
            this.optionsSource = optionsSource;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        boolean usesLegacyIdentity() {
            return legacyIdentity || parameterId == null || parameterId.isBlank();
        }

        public boolean isLegacyIdentity() {
            return usesLegacyIdentity();
        }

        void adaptLegacyIdentity(String parameterId) {
            if (usesLegacyIdentity() && parameterId != null && !parameterId.isBlank()) {
                this.parameterId = parameterId;
                this.legacyIdentity = false;
            }
        }

        static String legacyParameterId(String graphId, String direction, int index) {
            String normalizedDirection = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
            if (!"input".equals(normalizedDirection) && !"output".equals(normalizedDirection)) {
                throw new IllegalArgumentException("Function parameter direction must be input or output");
            }
            String name = "legacy-flow-graph" + '\u0000' + Objects.requireNonNull(graphId, "Flow graph ID is required")
                + '\u0000' + normalizedDirection + '\u0000' + index;
            return FunctionParameterId.deterministic(name).canonicalText();
        }

        public Map<String, JsonElement> getOpaqueProperties() {
            if (opaqueProperties == null) {
                opaqueProperties = new LinkedHashMap<>();
            }
            return opaqueProperties;
        }

        Map<String, JsonElement> peekOpaqueProperties() {
            return opaqueProperties;
        }

        Map<String, JsonElement> peekOpaqueNestedProperties() {
            return opaqueNestedProperties;
        }

        public void setOpaqueProperties(Map<String, JsonElement> opaqueProperties) {
            this.opaqueProperties = opaqueProperties != null ? new LinkedHashMap<>(opaqueProperties) : new LinkedHashMap<>();
        }

        Map<String, JsonElement> getOpaqueNestedProperties() {
            if (opaqueNestedProperties == null) {
                opaqueNestedProperties = new LinkedHashMap<>();
            }
            return opaqueNestedProperties;
        }

        void setOpaqueNestedProperties(Map<String, JsonElement> opaqueNestedProperties) {
            this.opaqueNestedProperties = opaqueNestedProperties != null ? new LinkedHashMap<>(opaqueNestedProperties) : new LinkedHashMap<>();
        }
    }

    public static final class LegacyFunctionParameterAdapter {
        private static final String INPUT_PIN_PREFIX = "function-input-";
        private static final String OUTPUT_PIN_PREFIX = "function-output-";
        private static final Set<String> FUNCTION_START_TYPES = Set.of(
            "function_start", "function.start", "function.function_start");
        private static final Set<String> FUNCTION_END_TYPES = Set.of(
            "function_end", "function.end", "function.function_end");

        private LegacyFunctionParameterAdapter() {
        }

        public static FlowGraph adapt(FlowGraph graph) {
            Objects.requireNonNull(graph, "Flow graph is required");
            String graphId = graph.getId();
            if (graphId == null || graphId.isBlank()) {
                throw new IllegalArgumentException("A stable Flow graph ID is required for legacy parameter adaptation");
            }
            Set<String> ids = new HashSet<>();
            ParameterAdaptation inputs = adaptParameters(graphId, "input", graph.getFunctionInputs(), ids);
            ParameterAdaptation outputs = adaptParameters(graphId, "output", graph.getFunctionOutputs(), ids);
            List<ConnectionRemap> remaps = planConnectionRemaps(graph, inputs.pinByName(), outputs.pinByName());
            inputs.assignGeneratedIds();
            outputs.assignGeneratedIds();
            graph.setFunctionInputs(inputs.parameters());
            graph.setFunctionOutputs(outputs.parameters());
            for (ConnectionRemap remap : remaps) {
                remap.apply();
            }
            return graph;
        }

        public static List<FunctionParameter> adapt(String graphId, String direction, List<FunctionParameter> parameters) {
            ParameterAdaptation adaptation = adaptParameters(graphId, direction, parameters, new HashSet<>());
            adaptation.assignGeneratedIds();
            return adaptation.parameters();
        }

        private static ParameterAdaptation adaptParameters(String graphId, String direction,
                                                           List<FunctionParameter> parameters, Set<String> ids) {
            if (parameters == null) {
                return new ParameterAdaptation(null, new LinkedHashMap<>(), new LinkedHashMap<>());
            }
            if (graphId == null || graphId.isBlank()) {
                throw new IllegalArgumentException("A stable Flow graph ID is required for legacy parameter adaptation");
            }
            String normalizedDirection = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
            if (!"input".equals(normalizedDirection) && !"output".equals(normalizedDirection)) {
                throw new IllegalArgumentException("Function parameter direction must be input or output");
            }
            Map<FunctionParameter, String> generatedIds = new LinkedHashMap<>();
            Map<String, String> pinByName = new LinkedHashMap<>();
            for (int index = 0; index < parameters.size(); index++) {
                FunctionParameter parameter = parameters.get(index);
                if (parameter == null) {
                    continue;
                }
                String id = parameter.getParameterId();
                if (parameter.usesLegacyIdentity()) {
                    if (id == null || id.isBlank()) {
                        id = FunctionParameter.legacyParameterId(graphId, normalizedDirection, index);
                    }
                    generatedIds.put(parameter, id);
                }
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("Function parameter ID is required");
                }
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("Duplicate function parameter ID: " + id);
                }
                String name = normalizeName(parameter.getName());
                if (!name.isBlank()) {
                    String pin = functionParameterPinId(normalizedDirection, id);
                    String previous = pinByName.putIfAbsent(name, pin);
                    if (previous != null && !previous.equals(pin)) {
                        throw new IllegalArgumentException("Duplicate function parameter name: " + name);
                    }
                }
            }
            return new ParameterAdaptation(parameters, generatedIds, pinByName);
        }

        private static List<ConnectionRemap> planConnectionRemaps(FlowGraph graph,
                                                                    Map<String, String> inputPins,
                                                                    Map<String, String> outputPins) {
            if (graph.getConnections() == null || graph.getConnections().isEmpty()) {
                return List.of();
            }
            List<ConnectionRemap> remaps = new ArrayList<>();
            for (FlowConnection connection : graph.getConnections()) {
                if (connection == null) {
                    continue;
                }
                FlowNode sourceNode = graph.getNodes() != null ? graph.getNodes().get(connection.getSourceNodeId()) : null;
                String sourcePin = remappedPin(connection.getSourcePinId(), inputPins, isFunctionStart(sourceNode), OUTPUT_PIN_PREFIX);
                if (sourcePin != null) {
                    remaps.add(ConnectionRemap.source(connection, sourcePin));
                }
                FlowNode targetNode = graph.getNodes() != null ? graph.getNodes().get(connection.getTargetNodeId()) : null;
                String targetPin = remappedPin(connection.getTargetPinId(), outputPins, isFunctionEnd(targetNode), INPUT_PIN_PREFIX);
                if (targetPin != null) {
                    remaps.add(ConnectionRemap.target(connection, targetPin));
                }
                if (sourcePin == null && sourceNode == null) {
                    String untypedSource = remappedPin(connection.getSourcePinId(), inputPins, false, OUTPUT_PIN_PREFIX);
                    if (untypedSource != null) {
                        remaps.add(ConnectionRemap.source(connection, untypedSource));
                    }
                }
                if (targetPin == null && targetNode == null) {
                    String untypedTarget = remappedPin(connection.getTargetPinId(), outputPins, false, INPUT_PIN_PREFIX);
                    if (untypedTarget != null) {
                        remaps.add(ConnectionRemap.target(connection, untypedTarget));
                    }
                }
            }
            return remaps;
        }

        private static String remappedPin(String rawPin, Map<String, String> pinsByName,
                                          boolean boundaryNode, String expectedPrefix) {
            String value = normalizeName(rawPin);
            if (value.isBlank()) {
                return null;
            }
            String name = value;
            boolean prefixed = false;
            if (value.startsWith(INPUT_PIN_PREFIX) || value.startsWith(OUTPUT_PIN_PREFIX)) {
                prefixed = true;
                if (!value.startsWith(expectedPrefix)) {
                    return null;
                }
                name = value.substring(expectedPrefix.length()).trim();
            } else if (!boundaryNode) {
                return null;
            }
            String replacement = pinsByName.get(name);
            if (replacement == null || replacement.equals(value)) {
                return null;
            }
            if (prefixed && replacement.equals(value)) {
                return null;
            }
            return replacement;
        }

        private static boolean isFunctionStart(FlowNode node) {
            return node != null && FUNCTION_START_TYPES.contains(normalizeName(node.getType()));
        }

        private static boolean isFunctionEnd(FlowNode node) {
            return node != null && FUNCTION_END_TYPES.contains(normalizeName(node.getType()));
        }

        private static String functionParameterPinId(String direction, String parameterId) {
            String identity = normalizeName(parameterId);
            if (identity.startsWith(INPUT_PIN_PREFIX)) {
                identity = identity.substring(INPUT_PIN_PREFIX.length()).trim();
            } else if (identity.startsWith(OUTPUT_PIN_PREFIX)) {
                identity = identity.substring(OUTPUT_PIN_PREFIX.length()).trim();
            }
            return ("input".equals(direction) ? OUTPUT_PIN_PREFIX : INPUT_PIN_PREFIX) + identity;
        }

        private static String normalizeName(String value) {
            return value == null ? "" : value.trim();
        }

        private record ParameterAdaptation(List<FunctionParameter> parameters,
                                           Map<FunctionParameter, String> generatedIds,
                                           Map<String, String> pinByName) {
            private void assignGeneratedIds() {
                for (Map.Entry<FunctionParameter, String> entry : generatedIds.entrySet()) {
                    entry.getKey().adaptLegacyIdentity(entry.getValue());
                }
            }
        }

        private record ConnectionRemap(FlowConnection connection, boolean source, String pin) {
            private static ConnectionRemap source(FlowConnection connection, String pin) {
                return new ConnectionRemap(connection, true, pin);
            }

            private static ConnectionRemap target(FlowConnection connection, String pin) {
                return new ConnectionRemap(connection, false, pin);
            }

            private void apply() {
                if (source) {
                    connection.setSourcePin(pin);
                    connection.setSourcePinId(pin);
                } else {
                    connection.setTargetPin(pin);
                    connection.setTargetPinId(pin);
                }
            }
        }
    }

    public FlowGraph adaptLegacyFunctionParameterIds() {
        LegacyFunctionParameterAdapter.adapt(this);
        return this;
    }

    public static class EditorPassthrough {
        private String nodeId;
        private String inputPin;
        private String inputPinId;
        private String inputPinDisplayName;
        private transient Map<String, JsonElement> opaqueProperties;

        public EditorPassthrough() {
            this.nodeId = "";
            this.inputPin = "";
            this.inputPinId = "";
        }

        public EditorPassthrough(String nodeId, String inputPin) {
            this.nodeId = nodeId;
            this.inputPin = inputPin;
            this.inputPinId = inputPin;
        }

        public static EditorPassthrough stable(String nodeId, String inputPinId) {
            EditorPassthrough passthrough = new EditorPassthrough();
            passthrough.nodeId = nodeId;
            passthrough.inputPin = null;
            passthrough.inputPinId = inputPinId;
            return passthrough;
        }

        public static EditorPassthrough legacy(String nodeId, String inputPin) {
            return new EditorPassthrough(nodeId, inputPin);
        }

        public static EditorPassthrough fromLegacy(String nodeId, String inputPin) {
            return legacy(nodeId, inputPin);
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getInputPin() {
            return inputPinId != null && !inputPinId.isBlank() ? inputPinId : inputPin;
        }

        public void setInputPin(String inputPin) {
            this.inputPin = inputPin;
            if (this.inputPinId == null || this.inputPinId.isBlank()) {
                this.inputPinId = inputPin;
            }
        }

        public String getInputPinId() {
            return inputPinId != null && !inputPinId.isBlank() ? inputPinId : inputPin;
        }

        public void setInputPinId(String inputPinId) {
            this.inputPinId = inputPinId;
        }

        public String getInputPinDisplayName() {
            return inputPinDisplayName != null ? inputPinDisplayName : getInputPin();
        }

        public void setInputPinDisplayName(String inputPinDisplayName) {
            this.inputPinDisplayName = inputPinDisplayName;
        }

        void adaptLegacyIdentity() {
            if (inputPinId == null || inputPinId.isBlank()) {
                inputPinId = inputPin;
            }
        }

        public Map<String, JsonElement> getOpaqueProperties() {
            if (opaqueProperties == null) {
                opaqueProperties = new LinkedHashMap<>();
            }
            return opaqueProperties;
        }

        Map<String, JsonElement> peekOpaqueProperties() {
            return opaqueProperties;
        }

        public void setOpaqueProperties(Map<String, JsonElement> opaqueProperties) {
            this.opaqueProperties = opaqueProperties != null ? new LinkedHashMap<>(opaqueProperties) : new LinkedHashMap<>();
        }
    }

    public FlowGraph() {
        this.id = UUID.randomUUID().toString();
        this.version = CURRENT_VERSION;
        this.nodes = new HashMap<>();
        this.connections = new ArrayList<>();
        this.localVariables = new ArrayList<>();
        this.function = false;
        this.functionOwner = "server";
        this.functionNamespace = "local";
        this.functionVersion = 1;
        this.functionDescription = "";
        this.functionInputs = new ArrayList<>();
        this.functionOutputs = new ArrayList<>();
        this.editorPassthroughs = new ArrayList<>();
        this.resourceType = "";
        this.resourceHash = "";
        this.resourceMutationId = "";
    }

    public FlowGraph(String id, Map<String, FlowNode> nodes, List<FlowConnection> connections, List<FlowVariable> localVariables) {
        this(id, nodes, connections, localVariables, false, new ArrayList<>(), new ArrayList<>());
    }

    public FlowGraph(String id, Map<String, FlowNode> nodes, List<FlowConnection> connections, List<FlowVariable> localVariables,
                     boolean function, List<FunctionParameter> functionInputs, List<FunctionParameter> functionOutputs) {
        this.id = id;
        this.version = CURRENT_VERSION;
        this.nodes = nodes != null ? nodes : new HashMap<>();
        this.connections = connections != null ? connections : new ArrayList<>();
        this.localVariables = localVariables != null ? localVariables : new ArrayList<>();
        this.function = function;
        this.functionOwner = "server";
        this.functionNamespace = "local";
        this.functionVersion = 1;
        this.functionDescription = "";
        this.functionInputs = functionInputs != null ? functionInputs : new ArrayList<>();
        this.functionOutputs = functionOutputs != null ? functionOutputs : new ArrayList<>();
        this.editorPassthroughs = new ArrayList<>();
        this.resourceType = "";
        this.resourceHash = "";
        this.resourceMutationId = "";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, FlowNode> getNodes() {
        return nodes;
    }

    public void setNodes(Map<String, FlowNode> nodes) {
        this.nodes = nodes;
    }

    public List<FlowConnection> getConnections() {
        return connections;
    }

    public void setConnections(List<FlowConnection> connections) {
        this.connections = connections;
    }

    public List<FlowVariable> getLocalVariables() {
        return localVariables;
    }

    public void setLocalVariables(List<FlowVariable> localVariables) {
        this.localVariables = localVariables;
    }

    public boolean isFunction() {
        return function;
    }

    public void setFunction(boolean function) {
        this.function = function;
    }

    public String getFunctionOwner() {
        return functionOwner != null && !functionOwner.isBlank() ? functionOwner : "server";
    }

    public void setFunctionOwner(String functionOwner) {
        this.functionOwner = functionOwner != null && !functionOwner.isBlank() ? functionOwner : "server";
    }

    public String getFunctionNamespace() {
        return functionNamespace != null && !functionNamespace.isBlank() ? functionNamespace : "local";
    }

    public void setFunctionNamespace(String functionNamespace) {
        this.functionNamespace = functionNamespace != null && !functionNamespace.isBlank() ? functionNamespace : "local";
    }

    public int getFunctionVersion() {
        return Math.max(1, functionVersion);
    }

    public void setFunctionVersion(int functionVersion) {
        this.functionVersion = Math.max(1, functionVersion);
    }

    public String getFunctionDescription() {
        return functionDescription != null ? functionDescription : "";
    }

    public void setFunctionDescription(String functionDescription) {
        this.functionDescription = functionDescription != null ? functionDescription : "";
    }

    public List<FunctionParameter> getFunctionInputs() {
        return functionInputs;
    }

    public void setFunctionInputs(List<FunctionParameter> functionInputs) {
        this.functionInputs = functionInputs;
    }

    public List<FunctionParameter> getFunctionOutputs() {
        return functionOutputs;
    }

    public void setFunctionOutputs(List<FunctionParameter> functionOutputs) {
        this.functionOutputs = functionOutputs;
    }

    public List<EditorPassthrough> getEditorPassthroughs() {
        if (editorPassthroughs == null) {
            editorPassthroughs = new ArrayList<>();
        }
        return editorPassthroughs;
    }

    List<EditorPassthrough> peekEditorPassthroughs() {
        return editorPassthroughs;
    }

    public void setEditorPassthroughs(List<EditorPassthrough> editorPassthroughs) {
        this.editorPassthroughs = editorPassthroughs != null ? editorPassthroughs : new ArrayList<>();
    }

    public Map<String, Object> getContentProperties() {
        if (contentProperties == null) {
            contentProperties = new HashMap<>();
        }
        return contentProperties;
    }

    public void setContentProperties(Map<String, Object> contentProperties) {
        this.contentProperties = contentProperties != null ? contentProperties : new HashMap<>();
    }

    public String getResourceType() {
        return resourceType == null ? "" : resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType == null ? "" : resourceType;
    }

    public long getResourceRevision() {
        return resourceRevision;
    }

    public void setResourceRevision(long resourceRevision) {
        this.resourceRevision = Math.max(0, resourceRevision);
    }

    public String getResourceHash() {
        return resourceHash == null ? "" : resourceHash;
    }

    public void setResourceHash(String resourceHash) {
        this.resourceHash = resourceHash == null ? "" : resourceHash;
    }

    public String getResourceMutationId() {
        return resourceMutationId == null ? "" : resourceMutationId;
    }

    public void setResourceMutationId(String resourceMutationId) {
        this.resourceMutationId = resourceMutationId == null ? "" : resourceMutationId;
    }

    public Map<String, JsonElement> getOpaqueProperties() {
        if (opaqueProperties == null) {
            opaqueProperties = new HashMap<>();
        }
        return opaqueProperties;
    }

    Map<String, JsonElement> peekOpaqueProperties() {
        return opaqueProperties;
    }

    public void setOpaqueProperties(Map<String, JsonElement> opaqueProperties) {
        this.opaqueProperties = opaqueProperties != null ? new HashMap<>(opaqueProperties) : new HashMap<>();
    }
}
