package redxax.oxy.remotely.flow.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowSerializerUnknownFieldRoundTripTest {
    @Test
    void recordsCurrentRecursiveUnknownFieldRoundTrip() throws Exception {
        JsonObject source = json("fixtures/flow/unknown-round-trip-source.json");
        JsonObject baseline = json("fixtures/flow/unknown-round-trip-contract.json");
        JsonObject expected = json("fixtures/flow/" + baseline.get("canonicalOutput").getAsString());
        FlowGraph graph = FlowSerializer.deserialize(source);
        JsonObject output = FlowSerializer.toJsonObject(graph);

        List<String> preserved = new ArrayList<>(strings(baseline, "preserved"));
        preserved.addAll(strings(baseline, "discarded"));
        for (String path : preserved) {
            assertEquals(valueAt(source, path), valueAt(output, path), path);
        }
        for (String path : strings(baseline, "normalized")) {
            assertTrue(!canonical(valueAt(source, path)).equals(canonical(valueAt(output, path))), path);
            assertEquals(valueAt(expected, path), valueAt(output, path), path);
        }

        assertEquals("out", valueAt(output, "/connections/0/sourcePinId").getAsString());
        assertEquals("in", valueAt(output, "/connections/0/targetPinId").getAsString());
        assertTrue(valueAt(output, "/functionInputs/0/parameterId").getAsString().length() > 0);
        assertTrue(valueAt(output, "/functionOutputs/0/parameterId").getAsString().length() > 0);
        assertEquals("known", valueAt(output, "/editorPassthroughs/0/inputPinId").getAsString());

        JsonObject roundTrip = FlowSerializer.toJsonObject(FlowSerializer.deserialize(output));
        for (String path : preserved) {
            assertEquals(valueAt(output, path), valueAt(roundTrip, path), path);
        }
        assertEquals(valueAt(output, "/functionInputs/0/parameterId"), valueAt(roundTrip, "/functionInputs/0/parameterId"));
        assertEquals(valueAt(output, "/functionOutputs/0/parameterId"), valueAt(roundTrip, "/functionOutputs/0/parameterId"));
    }

    @Test
    void keepsStableIdentitySeparateFromLegacyLabels() {
        FlowGraph graph = new FlowGraph();
        FlowGraph.FunctionParameter parameter = new FlowGraph.FunctionParameter("parameter-stable", "Legacy Name", FlowDataType.STRING);
        parameter.setDisplayName("Input Label");
        graph.setFunctionInputs(List.of(parameter));

        FlowConnection connection = FlowConnection.stable("source", "source-pin-stable", "target", "target-pin-stable");
        connection.setSourcePinDisplayName("Source Label");
        connection.setTargetPinDisplayName("Target Label");
        graph.getConnections().add(connection);
        FlowGraph.EditorPassthrough passthrough = FlowGraph.EditorPassthrough.stable("target", "input-pin-stable");
        passthrough.setInputPinDisplayName("Input Label");
        graph.setEditorPassthroughs(List.of(passthrough));

        JsonObject output = FlowSerializer.toJsonObject(graph);
        assertEquals("source-pin-stable", valueAt(output, "/connections/0/sourcePinId").getAsString());
        assertEquals("Source Label", valueAt(output, "/connections/0/sourcePinDisplayName").getAsString());
        assertEquals("parameter-stable", valueAt(output, "/functionInputs/0/parameterId").getAsString());
        assertEquals("Legacy Name", valueAt(output, "/functionInputs/0/name").getAsString());
        assertEquals("Input Label", valueAt(output, "/functionInputs/0/displayName").getAsString());
        assertEquals("input-pin-stable", valueAt(output, "/editorPassthroughs/0/inputPinId").getAsString());

        JsonObject roundTrip = FlowSerializer.toJsonObject(FlowSerializer.deserialize(output));
        for (String path : List.of("/connections/0/sourcePinId", "/connections/0/sourcePinDisplayName",
            "/connections/0/targetPinId", "/connections/0/targetPinDisplayName", "/functionInputs/0/parameterId",
            "/functionInputs/0/name", "/functionInputs/0/displayName", "/editorPassthroughs/0/inputPinId",
            "/editorPassthroughs/0/inputPinDisplayName")) {
            assertEquals(valueAt(output, path), valueAt(roundTrip, path), path);
        }
    }

    @Test
    void adaptsLegacyBoundaryConnectionsToDeterministicFunctionPinIds() {
        FlowGraph graph = new FlowGraph();
        graph.setId("legacy-boundary");
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(FlowGraph.FunctionParameter.legacy("permissions", FlowDataType.LIST)));
        graph.setFunctionOutputs(List.of(FlowGraph.FunctionParameter.legacy("lookup", FlowDataType.MAP)));
        graph.getNodes().put("start", new FlowNode("function.start", 0, 0, Map.of()));
        graph.getNodes().put("consumer", new FlowNode("consumer", 0, 0, Map.of()));
        graph.getNodes().put("producer", new FlowNode("producer", 0, 0, Map.of()));
        graph.getNodes().put("end", new FlowNode("function.end", 0, 0, Map.of()));
        graph.getConnections().add(FlowConnection.legacy("start", "permissions", "consumer", "permissions"));
        graph.getConnections().add(FlowConnection.legacy("producer", "lookup", "end", "lookup"));

        graph.adaptLegacyFunctionParameterIds();

        String inputId = FlowGraph.FunctionParameter.legacyParameterId("legacy-boundary", "input", 0);
        String outputId = FlowGraph.FunctionParameter.legacyParameterId("legacy-boundary", "output", 0);
        assertEquals(inputId, graph.getFunctionInputs().getFirst().getParameterId());
        assertEquals(outputId, graph.getFunctionOutputs().getFirst().getParameterId());
        assertEquals("function-output-" + inputId, graph.getConnections().getFirst().getSourcePinId());
        assertEquals("function-input-" + outputId, graph.getConnections().get(1).getTargetPinId());

        JsonObject first = FlowSerializer.toJsonObject(graph);
        JsonObject second = FlowSerializer.toJsonObject(FlowSerializer.deserialize(first));
        assertEquals(first, second);
        assertEquals("function-output-" + inputId, valueAt(second, "/connections/0/sourcePinId").getAsString());
        assertEquals("function-input-" + outputId, valueAt(second, "/connections/1/targetPinId").getAsString());
    }

    @Test
    void deserializationPrefersStableIdsOverConflictingLegacyBoundaryFields() {
        JsonObject source = JsonParser.parseString("""
            {
              "id":"stable-boundary",
              "connections":[{
                "sourceNodeId":"source",
                "sourcePin":"legacy-source",
                "sourcePinId":"stable-source",
                "targetNodeId":"target",
                "targetPin":"legacy-target",
                "targetPinId":"stable-target",
                "editorSourcePin":"legacy-editor",
                "editorSourcePinId":"stable-editor"
              }],
              "editorPassthroughs":[{
                "nodeId":"target",
                "inputPin":"legacy-input",
                "inputPinId":"stable-input"
              }]
            }
            """).getAsJsonObject();

        FlowGraph graph = FlowSerializer.deserialize(source);
        FlowConnection connection = graph.getConnections().getFirst();
        FlowGraph.EditorPassthrough passthrough = graph.getEditorPassthroughs().getFirst();

        assertEquals("stable-source", connection.getSourcePin());
        assertEquals("stable-target", connection.getTargetPin());
        assertEquals("stable-editor", connection.getEditorSourcePin());
        assertEquals("stable-editor", connection.getEditorSourcePinId());
        assertEquals("stable-input", passthrough.getInputPin());
        assertEquals("stable-input", passthrough.getInputPinId());

        JsonObject first = FlowSerializer.toJsonObject(graph);
        JsonObject second = FlowSerializer.toJsonObject(FlowSerializer.deserialize(first));
        assertEquals(first, second);
        assertEquals("stable-editor", valueAt(second, "/connections/0/editorSourcePinId").getAsString());
        assertEquals("stable-input", valueAt(second, "/editorPassthroughs/0/inputPinId").getAsString());
    }

    @Test
    void legacyPinSettersDoNotOverwriteStableIds() {
        FlowConnection connection = FlowConnection.stable("source", "stable-source", "target", "stable-target");
        connection.setEditorSourcePinId("stable-editor");
        connection.setSourcePin("legacy-source");
        connection.setTargetPin("legacy-target");
        connection.setEditorSourcePin("legacy-editor");

        FlowGraph.EditorPassthrough passthrough = FlowGraph.EditorPassthrough.stable("target", "stable-input");
        passthrough.setInputPin("legacy-input");

        assertEquals("stable-source", connection.getSourcePin());
        assertEquals("stable-target", connection.getTargetPin());
        assertEquals("stable-editor", connection.getEditorSourcePin());
        assertEquals("stable-editor", connection.getEditorSourcePinId());
        assertEquals("stable-input", passthrough.getInputPin());
        assertEquals("stable-input", passthrough.getInputPinId());
    }

    private JsonObject json(String path) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertTrue(stream != null, () -> "Missing fixture: " + path);
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private List<String> strings(JsonObject object, String name) {
        return object.getAsJsonArray(name).asList().stream().map(value -> value.getAsString()).toList();
    }

    private com.google.gson.JsonElement valueAt(JsonObject object, String path) {
        com.google.gson.JsonElement value = object;
        for (String segment : path.substring(1).split("/")) {
            if (value == null) {
                return null;
            }
            value = value.isJsonArray() ? value.getAsJsonArray().get(Integer.parseInt(segment)) : value.getAsJsonObject().get(segment);
        }
        return value;
    }

    private String canonical(JsonElement value) {
        if (value.isJsonArray()) {
            return "[" + value.getAsJsonArray().asList().stream().map(this::canonical).collect(java.util.stream.Collectors.joining(",")) + "]";
        }
        if (!value.isJsonObject()) {
            return value.toString();
        }
        Map<String, JsonElement> fields = new TreeMap<>();
        value.getAsJsonObject().entrySet().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
        return "{" + fields.entrySet().stream().map(entry -> JsonParser.parseString('"' + entry.getKey() + '"').toString() + ":" + canonical(entry.getValue()))
            .collect(java.util.stream.Collectors.joining(",")) + "}";
    }
}
