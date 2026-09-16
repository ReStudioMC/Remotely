package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FunctionReferenceAnalyzerTest {
    @Test
    void findsAndRefactorsGraphCallers() {
        FlowGraph graph = new FlowGraph();
        graph.setNodes(Map.of(
            "caller", new FlowNode("custom_function:library:old", 0, 0, Map.of()),
            "other", new FlowNode("message.send", 0, 0, Map.of())
        ));

        assertEquals(1, FunctionReferenceAnalyzer.findGraphReferences(graph, "library:old").size());
        assertEquals(1, FunctionReferenceAnalyzer.replaceGraphReferences(graph, "library:old", "library:new"));
        assertEquals("custom_function:library:new", graph.getNodes().get("caller").getType());
    }

    @Test
    void findsAndRefactorsNestedDesignerBindings() {
        JsonObject call = new JsonObject();
        call.addProperty("functionId", "library:old");
        JsonArray actions = new JsonArray();
        actions.add(call);
        JsonObject resource = new JsonObject();
        resource.add("actions", actions);

        assertEquals("$.actions[0].functionId", FunctionReferenceAnalyzer.findJsonReferences(resource, "library:old").getFirst());
        assertEquals(1, FunctionReferenceAnalyzer.replaceJsonReferences(resource, "library:old", "library:new"));
        assertEquals("library:new", call.get("functionId").getAsString());
    }

    @Test
    void changingAFunctionSignatureRemovesOnlyStaleCallerBindings() {
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new HashMap<>(Map.of("caller", new FlowNode("custom_function:library:target", 0, 0,
            new HashMap<>(Map.of("function-input-kept", 1, "function-input-removed", 2))))));
        graph.setConnections(new ArrayList<>(List.of(
            FlowConnection.stable("source", "value", "caller", "function-input-kept"),
            FlowConnection.stable("source", "value", "caller", "function-input-removed"),
            FlowConnection.stable("caller", "function-output-kept-result", "target", "value"),
            FlowConnection.stable("caller", "function-output-removed-result", "target", "other")
        )));

        assertEquals(3, FunctionReferenceAnalyzer.reconcileGraphCallers(graph, "library:target",
            Set.of("function-input-kept"), Set.of("function-output-kept-result")));
        assertEquals(Map.of("function-input-kept", 1), graph.getNodes().get("caller").getInputValues());
        assertEquals(2, graph.getConnections().size());
    }

    @Test
    void canonicalPinsUseStableIdsEvenWhenDisplayNamesChange() {
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new HashMap<>(Map.of("caller", new FlowNode("custom_function:library:target", 0, 0,
            new HashMap<>(Map.of("function-input-stable-id", 1))))));
        FlowConnection input = FlowConnection.stable("source", "value", "caller", "function-input-stable-id");
        input.setTargetPinDisplayName("Friendly Input");
        graph.setConnections(new ArrayList<>(List.of(input)));

        assertEquals(0, FunctionReferenceAnalyzer.reconcileGraphCallers(graph, "library:target",
            Set.of("function-input-stable-id"), Set.of()));
        assertEquals("function-input-stable-id", graph.getConnections().getFirst().getTargetPinId());
    }

    @Test
    void rawPinsAreHandledOnlyByExplicitLegacyAdapter() {
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new HashMap<>(Map.of("caller", new FlowNode("custom_function:library:target", 0, 0,
            new HashMap<>(Map.of("legacy-input", 1))))));
        graph.setConnections(new ArrayList<>(List.of(
            FlowConnection.legacy("source", "value", "caller", "legacy-input")
        )));

        assertEquals(2, FunctionReferenceAnalyzer.reconcileGraphCallers(graph, "library:target",
            Set.of("legacy-input"), Set.of()));

        graph.getNodes().get("caller").setInputValues(new HashMap<>(Map.of("legacy-input", 1)));
        graph.setConnections(new ArrayList<>(List.of(
            FlowConnection.legacy("source", "value", "caller", "legacy-input")
        )));
        assertEquals(0, FunctionReferenceAnalyzer.reconcileLegacyGraphCallers(graph, "library:target",
            Set.of("legacy-input"), Set.of()));
    }

    @Test
    void legacyParameterIdsAdaptBeforeStableCallerReconciliation() {
        FlowManager manager = new FlowManager(null, null);
        try {
            String serverId = "legacy-signature-server";
            installLegacyReadAuthority(manager, serverId);
            FlowGraph function = new FlowGraph();
            function.setId("library:target");
            function.setFunction(true);
            function.setResourceType(ReSyncResourceType.FUNCTION.typeId());
            FlowGraph.FunctionParameter legacyInput = new FlowGraph.FunctionParameter("legacy-input", FlowDataType.STRING);
            FlowGraph.FunctionParameter blankOutput = new FlowGraph.FunctionParameter("legacy-output", FlowDataType.STRING);
            blankOutput.setParameterId(" ");
            function.setFunctionInputs(new ArrayList<>(List.of(legacyInput)));
            function.setFunctionOutputs(new ArrayList<>(List.of(blankOutput)));
            function.adaptLegacyFunctionParameterIds();
            String inputPin = FunctionReferenceAnalyzer.canonicalInputPin(legacyInput.getParameterId());
            String outputPin = FunctionReferenceAnalyzer.canonicalOutputPin(blankOutput.getParameterId());

            FlowGraph caller = new FlowGraph();
            caller.setId("caller");
            caller.setResourceType(ReSyncResourceType.FLOW.typeId());
            caller.setNodes(new HashMap<>(Map.of("call", new FlowNode("custom_function:library:target", 0, 0,
                new HashMap<>(Map.of(inputPin, 1.0))))));
            caller.setConnections(new ArrayList<>(List.of(
                FlowConnection.stable("source", "value", "call", inputPin),
                FlowConnection.stable("call", outputPin, "target", "value")
            )));

            manager.cacheFlow(serverId, caller);
            manager.cacheFlow(serverId, function);

            FlowGraph resultingCaller = manager.getGraph(serverId, ReSyncResourceType.FLOW, caller.getId());
            assertNotNull(resultingCaller);
            assertNotNull(legacyInput.getParameterId());
            assertNotNull(blankOutput.getParameterId());
            assertEquals(Map.of(inputPin, 1.0), resultingCaller.getNodes().get("call").getInputValues());
            assertEquals(2, resultingCaller.getConnections().size());
            assertEquals(inputPin, resultingCaller.getConnections().getFirst().getTargetPinId());
            assertEquals(outputPin, resultingCaller.getConnections().getLast().getSourcePinId());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void stableParameterIdsReconcilePrefixedFunctionPins() {
        FlowManager manager = new FlowManager(null, null);
        try {
            String serverId = "stable-signature-server";
            String kept = "11111111-1111-4111-8111-111111111111";
            String removed = "22222222-2222-4222-8222-222222222222";
            String keptInput = FunctionReferenceAnalyzer.canonicalInputPin(kept);
            String removedInput = FunctionReferenceAnalyzer.canonicalInputPin(removed);
            String keptOutput = FunctionReferenceAnalyzer.canonicalOutputPin(kept);
            String removedOutput = FunctionReferenceAnalyzer.canonicalOutputPin(removed);
            installLegacyReadAuthority(manager, serverId);
            FlowGraph caller = new FlowGraph();
            caller.setId("caller");
            caller.setResourceType(ReSyncResourceType.FLOW.typeId());
            caller.setNodes(new HashMap<>(Map.of("call", new FlowNode("custom_function:library:target", 0, 0,
                new HashMap<>(Map.of(keptInput, 1.0, removedInput, 2.0))))));
            caller.setConnections(new ArrayList<>(List.of(
                FlowConnection.stable("source", "value", "call", keptInput),
                FlowConnection.stable("source", "value", "call", removedInput),
                FlowConnection.stable("call", keptOutput, "target", "value"),
                FlowConnection.stable("call", removedOutput, "target", "other")
            )));

            FlowGraph function = new FlowGraph();
            function.setId("library:target");
            function.setFunction(true);
            function.setResourceType(ReSyncResourceType.FUNCTION.typeId());
            function.setFunctionInputs(new ArrayList<>(List.of(
                FlowGraph.FunctionParameter.stable(kept, "Friendly Input", FlowDataType.STRING))));
            function.setFunctionOutputs(new ArrayList<>(List.of(
                FlowGraph.FunctionParameter.stable(kept, "Friendly Output", FlowDataType.STRING))));

            manager.cacheFlow(serverId, caller);
            manager.cacheFlow(serverId, function);

            FlowGraph resultingDraft = manager.getGraph(serverId, ReSyncResourceType.FLOW, caller.getId());
            assertNotNull(resultingDraft);
            assertEquals(Map.of(keptInput, 1.0), resultingDraft.getNodes().get("call").getInputValues());
            assertEquals(2, resultingDraft.getConnections().size());
            assertEquals(keptInput, resultingDraft.getConnections().getFirst().getTargetPinId());
            assertEquals(keptOutput, resultingDraft.getConnections().getLast().getSourcePinId());
        } finally {
            manager.shutdown();
        }
    }

    private static void installLegacyReadAuthority(FlowManager manager, String serverId) {
        try {
            ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
            ReSyncFlowClient client = new ReSyncFlowClient(serverId, transport, null);
            FlowManagerTestConnection.install(manager, serverId, client, transport);
            set(client, "legacyCompatibilityProven", true);
            set(client, "typedCatalogAuthorityAdvertised", false);
            set(client, "catalogAuthority", ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void set(ReSyncFlowClient client, String name, Object value) throws ReflectiveOperationException {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }
}
