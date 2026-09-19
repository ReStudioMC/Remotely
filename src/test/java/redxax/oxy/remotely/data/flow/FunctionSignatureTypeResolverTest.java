package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FunctionSignatureTypeResolverTest {
    private static final String INPUT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OUTPUT_ID = "22222222-2222-4222-8222-222222222222";
    private static final ContentHash BINDING_HASH = new ContentHash("f".repeat(64));

    @Test
    void typedBoundariesResolveOnlyStableParameterAndPinIdentities(@TempDir Path temporaryDirectory) throws Exception {
        try (ReSyncFlowClientTestHarness harness = client(temporaryDirectory, publication(true))) {
            ReSyncFlowClient client = harness.client();
            FlowGraph graph = typedFunctionGraph();

            assertEquals(2, FunctionSignatureTypeResolver.resolve(client, graph));
            assertEquals(FlowTypeRef.parse("list<string>"), graph.getFunctionInputs().getFirst().getTypeRef());
            assertEquals(FlowTypeRef.parse("map<string,json_object>"), graph.getFunctionOutputs().getFirst().getTypeRef());
            assertEquals(0, FunctionSignatureTypeResolver.resolve(client, graph));
        }
    }

    @Test
    void legacyBoundaryAndParameterAliasesRemainUntouched(@TempDir Path temporaryDirectory) throws Exception {
        try (ReSyncFlowClientTestHarness harness = client(temporaryDirectory, publication(true))) {
            ReSyncFlowClient client = harness.client();
            FlowGraph graph = typedFunctionGraph();
            graph.getNodes().get("inputs").setType("function.start");
            graph.getNodes().get("outputs").setType("function.end");
            graph.getFunctionInputs().clear();
            graph.getFunctionInputs().add(new FlowGraph.FunctionParameter(INPUT_ID, FlowDataType.ANY));
            graph.getFunctionOutputs().clear();
            graph.getFunctionOutputs().add(new FlowGraph.FunctionParameter(OUTPUT_ID, FlowDataType.ANY));
            FlowTypeRef originalInput = graph.getFunctionInputs().getFirst().getTypeRef();
            FlowTypeRef originalOutput = graph.getFunctionOutputs().getFirst().getTypeRef();

            assertEquals(0, FunctionSignatureTypeResolver.resolve(client, graph));
            assertEquals(originalInput, graph.getFunctionInputs().getFirst().getTypeRef());
            assertEquals(originalOutput, graph.getFunctionOutputs().getFirst().getTypeRef());
            assertNull(FunctionSignatureTypeResolver.parameterIdentity(graph.getFunctionInputs().getFirst()));
            assertNull(FunctionSignatureTypeResolver.parameterIdentity(graph.getFunctionOutputs().getFirst()));
        }
    }

    @Test
    void displayNamesCannotReplaceStableParameterIdentity(@TempDir Path temporaryDirectory) throws Exception {
        try (ReSyncFlowClientTestHarness harness = client(temporaryDirectory, publication(true))) {
            ReSyncFlowClient client = harness.client();
            FlowGraph graph = typedFunctionGraph();
            graph.getFunctionInputs().clear();
            graph.getFunctionInputs().add(FlowGraph.FunctionParameter.stable(
                "33333333-3333-4333-8333-333333333333", INPUT_ID, FlowDataType.ANY));

            assertEquals(1, FunctionSignatureTypeResolver.resolve(client, graph));
            assertEquals(FlowTypeRef.parse("any"), graph.getFunctionInputs().getFirst().getTypeRef());
            assertEquals(FlowTypeRef.parse("map<string,json_object>"), graph.getFunctionOutputs().getFirst().getTypeRef());
        }
    }

    @Test
    void incompleteBoundaryCapabilityFailsWithoutMutatingTheGraph(@TempDir Path temporaryDirectory) throws Exception {
        CatalogCachePublication publication = publication(false);
        try (ReSyncFlowClientTestHarness harness = ReSyncFlowClientTestHarness.connectReconciling(
            publication.key().serverId(), new ReSyncCatalogPublicationCache(DesktopReSyncStorage.fromKey(temporaryDirectory.resolve(UUID.randomUUID().toString() + ".json"))), publication)) {
            ReSyncFlowClient client = harness.client();
            FlowGraph graph = typedFunctionGraph();
            FlowTypeRef input = graph.getFunctionInputs().getFirst().getTypeRef();
            FlowTypeRef output = graph.getFunctionOutputs().getFirst().getTypeRef();

            assertEquals(0, FunctionSignatureTypeResolver.resolve(client, graph));
            assertEquals(input, graph.getFunctionInputs().getFirst().getTypeRef());
            assertEquals(output, graph.getFunctionOutputs().getFirst().getTypeRef());
        }
    }

    private ReSyncFlowClientTestHarness client(Path directory, CatalogCachePublication publication) throws Exception {
        return ReSyncFlowClientTestHarness.connect(publication.key().serverId(),
            new ReSyncCatalogPublicationCache(DesktopReSyncStorage.fromKey(directory.resolve(UUID.randomUUID().toString() + ".json"))), publication);
    }

    private CatalogCachePublication publication(boolean completeBoundary) {
        ServerId server = new ServerId(UUID.fromString("44444444-4444-4444-8444-444444444444"));
        CatalogCacheKey key = new CatalogCacheKey(server, completeBoundary ? 1 : 2,
            new ContentHash((completeBoundary ? "a" : "b").repeat(64)), BINDING_HASH,
            CatalogProjectionVersion.current());
        List<DescriptorEntry> entries = new ArrayList<>(List.of(
            descriptor("extension", "function.inputs", List.of(pin("run", "output", named("execution"))),
                boundary("inputs", "run", INPUT_ID, "list<string>")),
            descriptor("extension", "consumer", List.of(pin("consumer-input", "input", named("list", named("string")))), Map.of()),
            descriptor("extension", "producer", List.of(pin("producer-output", "output",
                named("map", named("string"), named("json_object")))), Map.of())));
        if (completeBoundary) {
            entries.add(descriptor("extension", "function.outputs", List.of(pin("finish", "input", named("execution"))),
                boundary("outputs", "finish", OUTPUT_ID, "map<string,json_object>")));
        }
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, key.catalogGeneration(), entries.stream()
            .map(entry -> CatalogCachePublication.Entry.present(entry.key(), key.catalogGeneration(), CatalogCacheState.ACTIVE,
                Set.of(), false, CatalogCacheOpaque.of(entry.canonical().getBytes(StandardCharsets.UTF_8))))
            .toList());
    }

    private FlowGraph typedFunctionGraph() {
        FlowGraph graph = new FlowGraph();
        graph.setFunction(true);
        graph.getFunctionInputs().add(FlowGraph.FunctionParameter.stable(INPUT_ID, "Renamed Input", FlowDataType.ANY));
        graph.getFunctionOutputs().add(FlowGraph.FunctionParameter.stable(OUTPUT_ID, "Renamed Output", FlowDataType.ANY));
        graph.getNodes().put("inputs", new FlowNode("extension:function.inputs", 0, 0, Map.of()));
        graph.getNodes().put("consumer", new FlowNode("extension:consumer", 0, 0, Map.of()));
        graph.getNodes().put("producer", new FlowNode("extension:producer", 0, 0, Map.of()));
        graph.getNodes().put("outputs", new FlowNode("extension:function.outputs", 0, 0, Map.of()));
        graph.getConnections().add(FlowConnection.stable("inputs", INPUT_ID, "consumer", "consumer-input"));
        graph.getConnections().add(FlowConnection.stable("producer", "producer-output", "outputs", OUTPUT_ID));
        return graph;
    }

    private DescriptorEntry descriptor(String owner, String id, List<Map<String, Object>> pins, Map<String, Object> boundary) {
        Map<String, Object> metadata = boundary.isEmpty() ? Map.of() : Map.of("functionBoundary", boundary);
        String canonical = CanonicalJson.canonicalize(Map.of("id", id, "displayName", id, "pins", pins, "metadata", metadata));
        return new DescriptorEntry(ContractRef.of(new OwnerId(owner), new NodeId(id)), canonical);
    }

    private Map<String, Object> boundary(String role, String flowPin, String parameterId, String typeRef) {
        return Map.of("role", role, "flowPin", flowPin, "parameterPins",
            List.of(Map.of("id", parameterId, "name", parameterId, "typeRef", typeRef)));
    }

    private Map<String, Object> pin(String id, String direction, Map<String, Object> type) {
        return Map.of("id", id, "direction", direction, "type", type);
    }

    @SafeVarargs
    private final Map<String, Object> named(String id, Map<String, Object>... arguments) {
        return Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", id), "arguments", List.of(arguments));
    }

    private record DescriptorEntry(ContractRef<NodeId> key, String canonical) {
    }

}
