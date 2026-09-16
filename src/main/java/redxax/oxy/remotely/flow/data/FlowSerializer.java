package redxax.oxy.remotely.flow.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FlowSerializer {
    private static final Set<String> GRAPH_PROPERTIES = Set.of("id", "enabled", "version", "nodes", "connections", "localVariables", "function", "functionOwner", "functionNamespace", "functionVersion", "functionDescription", "functionInputs", "functionOutputs", "editorPassthroughs", "contentProperties", "resourceType", "resourceRevision", "resourceHash", "resourceMutationId", "assetFormatVersion", "assetRevision", "assetHash", "assetMutationId");
    private static final Set<String> NODE_PROPERTIES = Set.of("type", "version", "x", "y", "inputValues");
    private static final Set<String> CONNECTION_PROPERTIES = Set.of("sourceNodeId", "sourcePin", "sourcePinId", "sourcePinDisplayName", "targetNodeId", "targetPin", "targetPinId", "targetPinDisplayName", "editorSourceNodeId", "editorSourcePin", "editorSourcePinId", "editorSourcePinDisplayName");
    private static final Set<String> PARAMETER_PROPERTIES = Set.of("parameterId", "name", "displayName", "type", "typeRef", "widget", "optionsSource", "defaultValue");
    private static final Set<String> PASSTHROUGH_PROPERTIES = Set.of("nodeId", "inputPin", "inputPinId", "inputPinDisplayName");
    private static final Set<String> TYPE_REF_PROPERTIES = Set.of("typeId", "arguments");

    static Set<String> graphProperties() {
        return GRAPH_PROPERTIES;
    }

    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public static String serialize(FlowGraph graph) {
        JsonObject object = toJsonObject(graph);
        return gson.toJson(object);
    }

    public static JsonObject toJsonObject(FlowGraph graph) {
        return toJsonObject(graph, true);
    }

    public static JsonObject toSnapshotJsonObject(FlowGraph graph) {
        JsonObject raw = toJsonObject(graph, false);
        FlowGraph detached = deserialize(raw);
        return toJsonObject(detached, true);
    }

    private static JsonObject toJsonObject(FlowGraph graph, boolean adaptLegacy) {
        if (adaptLegacy) {
            adaptLegacyFunctionParameterIds(graph);
        }
        JsonObject object = gson.toJsonTree(graph).getAsJsonObject();
        mergeOpaque(object, graph.peekOpaqueProperties());
        writeNodeProperties(graph, object);
        writeConnectionProperties(graph, object, adaptLegacy);
        writeParameterProperties(graph, graph.getFunctionInputs(), object.getAsJsonArray("functionInputs"), "input");
        writeParameterProperties(graph, graph.getFunctionOutputs(), object.getAsJsonArray("functionOutputs"), "output");
        writePassthroughProperties(graph, object, adaptLegacy);
        return object;
    }

    public static FlowGraph deserialize(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return deserialize(object);
    }

    public static FlowGraph deserialize(JsonObject object) {
        FlowGraph graph = gson.fromJson(object, FlowGraph.class);
        graph.setOpaqueProperties(unknownProperties(object, GRAPH_PROPERTIES));
        readNodeProperties(graph, object);
        readConnectionProperties(graph, object);
        readParameterProperties(graph.getFunctionInputs(), object.getAsJsonArray("functionInputs"), "input");
        readParameterProperties(graph.getFunctionOutputs(), object.getAsJsonArray("functionOutputs"), "output");
        readPassthroughProperties(graph, object);
        adaptLegacyFunctionParameterIds(graph);
        return graph;
    }

    public static FlowGraph deserializeFlow(String json) {
        return deserialize(json);
    }

    public static String serializeGui(GuiDefinition gui) {
        return gson.toJson(gui);
    }

    public static GuiDefinition deserializeGui(String json) {
        return gson.fromJson(json, GuiDefinition.class);
    }

    public static String serializeScoreboard(ScoreboardDefinition scoreboard) {
        return gson.toJson(scoreboard);
    }

    public static ScoreboardDefinition deserializeScoreboard(String json) {
        return gson.fromJson(json, ScoreboardDefinition.class);
    }

    public static String serializeTab(TabDefinition tab) {
        return gson.toJson(tab);
    }

    public static TabDefinition deserializeTab(String json) {
        return gson.fromJson(json, TabDefinition.class);
    }

    public static String serializeCustomContent(CustomContentDefinition content) {
        JsonObject object = gson.toJsonTree(content).getAsJsonObject();
        if (content.getGraph() != null) {
            object.add("graph", toJsonObject(content.getGraph()));
        }
        return gson.toJson(object);
    }

    public static CustomContentDefinition deserializeCustomContent(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        CustomContentDefinition content = gson.fromJson(object, CustomContentDefinition.class);
        JsonElement graph = object.get("graph");
        if (graph != null && graph.isJsonObject()) {
            content.setGraph(deserialize(graph.getAsJsonObject()));
        }
        return content;
    }

    private static void writeNodeProperties(FlowGraph graph, JsonObject object) {
        JsonObject nodes = object.getAsJsonObject("nodes");
        if (nodes == null || graph.getNodes() == null) {
            return;
        }
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            JsonElement value = nodes.get(entry.getKey());
            if (node != null && value != null && value.isJsonObject()) {
                mergeOpaque(value.getAsJsonObject(), node.peekOpaqueProperties());
            }
        }
    }

    private static void writeConnectionProperties(FlowGraph graph, JsonObject object, boolean adaptLegacy) {
        JsonArray values = object.getAsJsonArray("connections");
        if (values == null || graph.getConnections() == null) {
            return;
        }
        List<FlowConnection> connections = graph.getConnections();
        for (int index = 0; index < connections.size() && index < values.size(); index++) {
            FlowConnection connection = connections.get(index);
            JsonElement value = values.get(index);
            if (connection == null || !value.isJsonObject()) {
                continue;
            }
            if (adaptLegacy) {
                connection.adaptLegacyIdentity();
            }
            JsonObject output = value.getAsJsonObject();
            add(output, "sourcePinId", connection.getSourcePinId());
            add(output, "targetPinId", connection.getTargetPinId());
            add(output, "editorSourcePinId", connection.getEditorSourcePinId());
            mergeOpaque(output, connection.peekOpaqueProperties());
        }
    }

    private static void writeParameterProperties(FlowGraph graph, List<FlowGraph.FunctionParameter> parameters, JsonArray values,
                                                 String scope) {
        if (parameters == null || values == null) {
            return;
        }
        for (int index = 0; index < parameters.size() && index < values.size(); index++) {
            FlowGraph.FunctionParameter parameter = parameters.get(index);
            JsonElement value = values.get(index);
            if (parameter == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject output = value.getAsJsonObject();
            String parameterId = parameter.getParameterId();
            if ((parameterId == null || parameterId.isBlank()) && graph.getId() != null && !graph.getId().isBlank()) {
                parameterId = FlowGraph.FunctionParameter.legacyParameterId(graph.getId(), scope, index);
            }
            add(output, "parameterId", parameterId);
            mergeOpaque(output, parameter.peekOpaqueProperties());
            mergeParameterNestedOpaque(output, parameter.peekOpaqueNestedProperties());
        }
    }

    private static void writePassthroughProperties(FlowGraph graph, JsonObject object, boolean adaptLegacy) {
        JsonArray values = object.getAsJsonArray("editorPassthroughs");
        if (values == null || graph.peekEditorPassthroughs() == null) {
            return;
        }
        List<FlowGraph.EditorPassthrough> passthroughs = graph.peekEditorPassthroughs();
        for (int index = 0; index < passthroughs.size() && index < values.size(); index++) {
            FlowGraph.EditorPassthrough passthrough = passthroughs.get(index);
            JsonElement value = values.get(index);
            if (passthrough == null || !value.isJsonObject()) {
                continue;
            }
            if (adaptLegacy) {
                passthrough.adaptLegacyIdentity();
            }
            JsonObject output = value.getAsJsonObject();
            add(output, "inputPinId", passthrough.getInputPinId());
            mergeOpaque(output, passthrough.peekOpaqueProperties());
        }
    }

    private static void readNodeProperties(FlowGraph graph, JsonObject object) {
        JsonObject nodes = object.getAsJsonObject("nodes");
        if (nodes == null || graph.getNodes() == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            FlowNode node = graph.getNodes().get(entry.getKey());
            if (node != null && entry.getValue().isJsonObject()) {
                node.setOpaqueProperties(unknownProperties(entry.getValue().getAsJsonObject(), NODE_PROPERTIES));
            }
        }
    }

    private static void readConnectionProperties(FlowGraph graph, JsonObject object) {
        JsonArray values = object.getAsJsonArray("connections");
        if (values == null || graph.getConnections() == null) {
            return;
        }
        List<FlowConnection> connections = graph.getConnections();
        for (int index = 0; index < connections.size() && index < values.size(); index++) {
            FlowConnection connection = connections.get(index);
            JsonElement value = values.get(index);
            if (connection != null && value.isJsonObject()) {
                connection.adaptLegacyIdentity();
                connection.setOpaqueProperties(unknownProperties(value.getAsJsonObject(), CONNECTION_PROPERTIES));
            }
        }
    }

    private static void readParameterProperties(List<FlowGraph.FunctionParameter> parameters, JsonArray values, String scope) {
        if (parameters == null || values == null) {
            return;
        }
        for (int index = 0; index < parameters.size() && index < values.size(); index++) {
            FlowGraph.FunctionParameter parameter = parameters.get(index);
            JsonElement value = values.get(index);
            if (parameter == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject source = value.getAsJsonObject();
            if (source.has("parameterId") && !source.get("parameterId").isJsonNull()) {
                parameter.setParameterId(source.get("parameterId").getAsString());
            } else {
                parameter.setParameterId(null);
            }
            parameter.setOpaqueProperties(unknownProperties(source, PARAMETER_PROPERTIES));
            Map<String, JsonElement> nested = new LinkedHashMap<>();
            if (source.has("typeRef") && source.get("typeRef").isJsonObject()) {
                nested.put("typeRef", source.get("typeRef").deepCopy());
            }
            parameter.setOpaqueNestedProperties(nested);
        }
    }

    private static void readPassthroughProperties(FlowGraph graph, JsonObject object) {
        JsonArray values = object.getAsJsonArray("editorPassthroughs");
        if (values == null || graph.getEditorPassthroughs() == null) {
            return;
        }
        List<FlowGraph.EditorPassthrough> passthroughs = graph.getEditorPassthroughs();
        for (int index = 0; index < passthroughs.size() && index < values.size(); index++) {
            FlowGraph.EditorPassthrough passthrough = passthroughs.get(index);
            JsonElement value = values.get(index);
            if (passthrough != null && value.isJsonObject()) {
                passthrough.adaptLegacyIdentity();
                passthrough.setOpaqueProperties(unknownProperties(value.getAsJsonObject(), PASSTHROUGH_PROPERTIES));
            }
        }
    }

    private static Map<String, JsonElement> unknownProperties(JsonObject object, Set<String> known) {
        Map<String, JsonElement> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!known.contains(entry.getKey()) && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return result;
    }

    private static void mergeOpaque(JsonObject output, Map<String, JsonElement> opaque) {
        if (opaque == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : opaque.entrySet()) {
            if (!output.has(entry.getKey()) && entry.getValue() != null) {
                output.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    private static void mergeParameterNestedOpaque(JsonObject output, Map<String, JsonElement> nested) {
        JsonElement rawTypeRef = nested != null ? nested.get("typeRef") : null;
        JsonElement currentTypeRef = output.get("typeRef");
        if (rawTypeRef == null || !rawTypeRef.isJsonObject() || currentTypeRef == null || !currentTypeRef.isJsonObject()) {
            return;
        }
        JsonObject raw = rawTypeRef.getAsJsonObject();
        JsonObject current = currentTypeRef.getAsJsonObject();
        mergeUnknownObject(current, raw, TYPE_REF_PROPERTIES);
        JsonArray rawArguments = raw.getAsJsonArray("arguments");
        JsonArray currentArguments = current.getAsJsonArray("arguments");
        if (rawArguments == null || currentArguments == null) {
            return;
        }
        for (int index = 0; index < rawArguments.size() && index < currentArguments.size(); index++) {
            JsonElement rawArgument = rawArguments.get(index);
            JsonElement currentArgument = currentArguments.get(index);
            if (rawArgument.isJsonObject() && currentArgument.isJsonObject()) {
                mergeUnknownTypeRef(currentArgument.getAsJsonObject(), rawArgument.getAsJsonObject());
            }
        }
    }

    private static void mergeUnknownTypeRef(JsonObject output, JsonObject source) {
        mergeUnknownObject(output, source, TYPE_REF_PROPERTIES);
        JsonArray sourceArguments = source.getAsJsonArray("arguments");
        JsonArray outputArguments = output.getAsJsonArray("arguments");
        if (sourceArguments == null || outputArguments == null) {
            return;
        }
        for (int index = 0; index < sourceArguments.size() && index < outputArguments.size(); index++) {
            JsonElement sourceArgument = sourceArguments.get(index);
            JsonElement outputArgument = outputArguments.get(index);
            if (sourceArgument.isJsonObject() && outputArgument.isJsonObject()) {
                mergeUnknownTypeRef(outputArgument.getAsJsonObject(), sourceArgument.getAsJsonObject());
            }
        }
    }

    private static void adaptLegacyFunctionParameterIds(FlowGraph graph) {
        if (graph == null || !hasLegacyFunctionParameterIds(graph.getFunctionInputs())
            && !hasLegacyFunctionParameterIds(graph.getFunctionOutputs())) {
            return;
        }
        graph.adaptLegacyFunctionParameterIds();
    }

    private static boolean hasLegacyFunctionParameterIds(List<FlowGraph.FunctionParameter> parameters) {
        if (parameters == null) {
            return false;
        }
        return parameters.stream().anyMatch(parameter -> parameter != null && parameter.usesLegacyIdentity());
    }

    private static void mergeUnknownObject(JsonObject output, JsonObject source, Set<String> known) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!known.contains(entry.getKey()) && !output.has(entry.getKey()) && entry.getValue() != null) {
                output.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    private static void add(JsonObject object, String name, String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(name, value);
        }
    }

}
