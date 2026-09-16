package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphAuthoringAdapterCanonicalTest {
    private static final ServerId SERVER = ServerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId RESOURCE_OWNER = OwnerId.of("restudio.test");
    private static final OwnerId NODE_OWNER = OwnerId.of("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final NodeInstanceId NODE_INSTANCE = NodeInstanceId.of(
        UUID.fromString("22222222-2222-4222-8222-222222222222"));

    @Test
    void createsAbilityNodesThroughCanonicalDecoder() {
        List<String> nodeIds = List.of("ability_cooldown_remaining", "ability_set_cooldown", "ability_reflect_damage");
        List<CatalogCachePublication.Entry> entries = new ArrayList<>();
        for (String nodeId : nodeIds) {
            entries.add(CatalogCachePublication.Entry.present(ContractRef.of(NODE_OWNER, NodeId.of(nodeId)), 1,
                CatalogCacheState.ACTIVE, Set.of(), false, descriptor(nodeId, Map.of())));
        }
        CoreGraphAuthoringAdapter.Context context = CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.FLOW,
            resource("ability"), BINDING, new CatalogVersion(1, 0), entries, 0);
        CoreGraphAuthoringAdapter adapter = new CoreGraphAuthoringAdapter();

        for (String nodeId : nodeIds) {
            GraphNode node = adapter.createNode(NODE_INSTANCE,
                new FlowNode(NODE_OWNER.canonicalText() + ":" + nodeId, 4, 8, Map.of()), context);
            assertEquals(nodeId, node.definition().id().canonicalText());
            assertTrue(node.values().isEmpty());
        }
    }

    @Test
    void encodesMissingPinsWithThePublishedDescriptorType() {
        String nodeId = "ability_set_cooldown";
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(
            ContractRef.of(NODE_OWNER, NodeId.of(nodeId)), 1, CatalogCacheState.ACTIVE, Set.of(), false,
            descriptor(nodeId, Map.of("ticks", "number")));
        CoreGraphAuthoringAdapter.Context context = CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.FLOW,
            resource("ability"), BINDING, new CatalogVersion(1, 0), List.of(entry), 0);
        FlowGraph graph = new FlowGraph("ability",
            Map.of(NODE_INSTANCE.canonicalText(), new FlowNode(NODE_OWNER.canonicalText() + ":" + nodeId, 0, 0,
                Map.of("ticks", 40))), List.of(), List.of());
        graph.setResourceType("flow");
        GraphNode node = new CoreGraphAuthoringAdapter().convert(graph, context).graphDocument().nodes().getFirst();

        assertEquals("value", node.values().get(PinId.of("ticks")).value().state().wireName());
        assertEquals(0, new BigDecimal("40").compareTo(
            new BigDecimal(node.values().get(PinId.of("ticks")).value().value().toString())));
    }

    @Test
    void turnsAnAbsentTypedPinIntoAnEditedValue() {
        String nodeId = "ability_set_cooldown";
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(
            ContractRef.of(NODE_OWNER, NodeId.of(nodeId)), 1, CatalogCacheState.ACTIVE, Set.of(), false,
            descriptor(nodeId, Map.of("ticks", "number")));
        CoreGraphAuthoringAdapter.Context context = CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.FLOW,
            resource("ability"), BINDING, new CatalogVersion(1, 0), List.of(entry), 0);
        TypeExpr type = TypeExpr.named(TypeReference.of("builtin", "number"));
        GraphNode baselineNode = new GraphNode(NODE_INSTANCE, ContractRef.of(NODE_OWNER, NodeId.of(nodeId)), 2,
            Map.of(PinId.of("ticks"), new PinValue(PinId.of("ticks"), TypedValue.absent(type))));
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), resource("ability"), 0, BINDING,
            Set.of(), List.of(baselineNode), List.of(), List.of(), OpaqueData.empty());
        String baselineJson = GraphDocumentCodec.INSTANCE.encodeText(baseline);
        FlowGraph graph = new FlowGraph("ability",
            Map.of(NODE_INSTANCE.canonicalText(), new FlowNode(NODE_OWNER.canonicalText() + ":" + nodeId, 0, 0,
                Map.of("ticks", 40))), List.of(), List.of());
        graph.setResourceType("flow");
        CoreGraphAuthoringAdapter.Result result = new CoreGraphAuthoringAdapter().convert(graph,
            JsonParser.parseString(baselineJson).getAsJsonObject(), context);

        assertTrue(result.lossless(), result.reason());
        GraphNode node = result.graphDocument().nodes().getFirst();
        assertEquals("value", node.values().get(PinId.of("ticks")).value().state().wireName());
        assertEquals(0, new BigDecimal("40").compareTo(
            new BigDecimal(node.values().get(PinId.of("ticks")).value().value().toString())));
    }

    @Test
    void conversionKeepsCallerConnectionProjectionIdentity() {
        NodeInstanceId targetInstance = NodeInstanceId.of(
            UUID.fromString("33333333-3333-4333-8333-333333333333"));
        String nodeId = "connection_node";
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(
            ContractRef.of(NODE_OWNER, NodeId.of(nodeId)), 1, CatalogCacheState.ACTIVE, Set.of(), false,
            descriptor(nodeId, Map.of("in", "number", "out", "number")));
        CoreGraphAuthoringAdapter.Context context = CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.FLOW,
            resource("connection"), BINDING, new CatalogVersion(1, 0), List.of(entry), 0);
        FlowConnection connection = FlowConnection.stable(NODE_INSTANCE.canonicalText(), "out",
            targetInstance.canonicalText(), "in");
        Map<String, JsonElement> projectionIdentity = new LinkedHashMap<>();
        projectionIdentity.put("sourceElementId", new JsonPrimitive("source-element"));
        projectionIdentity.put("sourceBranchId", new JsonPrimitive("source-branch"));
        projectionIdentity.put("targetElementId", new JsonPrimitive("target-element"));
        projectionIdentity.put("targetBranchId", new JsonPrimitive("target-branch"));
        projectionIdentity.put("custom", new JsonPrimitive("preserved"));
        connection.setOpaqueProperties(projectionIdentity);
        FlowGraph graph = new FlowGraph("connection", Map.of(
            NODE_INSTANCE.canonicalText(), new FlowNode(NODE_OWNER.canonicalText() + ":" + nodeId, 0, 0, Map.of()),
            targetInstance.canonicalText(), new FlowNode(NODE_OWNER.canonicalText() + ":" + nodeId, 0, 0, Map.of())),
            List.of(connection), List.of());
        graph.setResourceType("flow");

        new CoreGraphAuthoringAdapter().convert(graph, context);

        assertEquals(projectionIdentity, connection.getOpaqueProperties());
    }

    @Test
    void newNodesReceiveExactTypedPinAndNestedInspectorDefaultsOnlyAtCreation() {
        String nodeId = "typed_defaults";
        Map<String, Object> stringType = Map.of("arguments", List.of(), "kind", "named",
            "type", Map.of("localId", "string", "ownerId", "builtin",
                "referenceFuture", Map.of("keep", true)),
            "typeFuture", List.of("keep"));
        Map<String, Object> numberType = Map.of("arguments", List.of(), "kind", "named",
            "type", Map.of("localId", "number", "ownerId", "builtin", "referenceFuture", "keep"));
        Map<String, Object> opaqueType = Map.of("kind", "opaque", "raw", true,
            "type", Map.of("localId", "future", "ownerId", "extension.generic"));
        Map<String, Object> unionType = Map.of("kind", "union", "variants", List.of(
            Map.of("variantId", "future", "type", opaqueType),
            Map.of("variantId", "text", "type", stringType)));
        Map<String, Object> sceneType = Map.of("kind", "resource",
            "resourceType", Map.of("ownerId", "extension.generic", "localId", "scene"));
        Map<String, Object> resourceUnionType = Map.of("kind", "union", "variants", List.of(
            Map.of("variantId", "scene", "type", sceneType),
            Map.of("variantId", "text", "type", stringType)));
        Map<String, Object> countDefault = new LinkedHashMap<>();
        countDefault.put("state", "value");
        countDefault.put("type", numberType);
        countDefault.put("value", new BigDecimal("3.50"));
        countDefault.put("pinFuture", Map.of("kept", true));
        Map<String, Object> labelDefault = new LinkedHashMap<>();
        labelDefault.put("state", "value");
        labelDefault.put("type", stringType);
        labelDefault.put("value", "Default Label");
        labelDefault.put("fieldFuture", List.of("kept"));
        Map<String, Object> emptyDefault = new LinkedHashMap<>();
        emptyDefault.put("state", "null");
        emptyDefault.put("type", stringType);
        emptyDefault.put("nullFuture", "kept");
        Map<String, Object> absentDefault = new LinkedHashMap<>();
        absentDefault.put("state", "absent");
        absentDefault.put("type", stringType);
        absentDefault.put("absentFuture", Map.of("kept", true));
        Map<String, Object> opaqueDefault = new LinkedHashMap<>();
        opaqueDefault.put("state", "opaque");
        opaqueDefault.put("type", unionType);
        opaqueDefault.put("variantId", "future");
        opaqueDefault.put("value", Map.of("raw", List.of("exact", true)));
        opaqueDefault.put("opaqueFuture", List.of(Map.of("kept", true)));
        Map<String, Object> locatorDefault = new LinkedHashMap<>();
        locatorDefault.put("state", "locator");
        locatorDefault.put("type", resourceUnionType);
        locatorDefault.put("variantId", "scene");
        locatorDefault.put("locator", new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("extension.generic"), ResourceTypeId.of("scene")), "spawn").canonicalValue());
        locatorDefault.put("locatorFuture", Map.of("kept", true));
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("id", nodeId);
        descriptor.put("schemaVersion", 2);
        descriptor.put("pins", List.of(
            Map.of("id", "count", "direction", "input", "type", numberType, "default", countDefault),
            Map.of("id", "missing", "direction", "input", "type", stringType, "default", absentDefault),
            Map.of("id", "raw", "direction", "input", "type", unionType, "default", opaqueDefault),
            Map.of("id", "scene", "direction", "input", "type", resourceUnionType, "default", locatorDefault)));
        descriptor.put("inspector", Map.of("sections", List.of(Map.of("rows", List.of(Map.of("fields", List.of(
            Map.of("id", "group", "type", stringType, "children", List.of(
                Map.of("id", "label", "type", stringType, "defaultValue", labelDefault),
                Map.of("id", "missing", "type", stringType, "default", absentDefault),
                Map.of("id", "raw", "type", unionType, "defaultValue", opaqueDefault))),
            Map.of("id", "items", "type", stringType, "element",
                Map.of("id", "entry", "type", stringType, "default", emptyDefault)))))))));
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(
            ContractRef.of(NODE_OWNER, NodeId.of(nodeId)), 1, CatalogCacheState.ACTIVE, Set.of(), false,
            new CatalogCacheOpaque(CanonicalJson.canonicalBytes(descriptor)));
        CoreGraphAuthoringAdapter.Context context = CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.FLOW,
            resource("defaults"), BINDING, new CatalogVersion(1, 0), List.of(entry), 0);
        CoreGraphAuthoringAdapter adapter = new CoreGraphAuthoringAdapter();

        GraphNode created = adapter.createNode(NODE_INSTANCE,
            new FlowNode(NODE_OWNER.canonicalText() + ":" + nodeId, 4, 8, Map.of()), context);

        TypedValue count = created.values().get(PinId.of("count")).value();
        assertEquals(TypedValue.State.VALUE, count.state());
        assertEquals(0, new BigDecimal("3.50").compareTo(new BigDecimal(count.value().toString())));
        assertEquals(Map.of("kept", true), count.unknown().get("pinFuture"));
        assertEquals(numberType, count.type().canonicalValue());
        TypedValue missing = created.values().get(PinId.of("missing")).value();
        assertEquals(TypedValue.State.ABSENT, missing.state());
        assertEquals(Map.of("kept", true), missing.unknown().get("absentFuture"));
        TypedValue raw = created.values().get(PinId.of("raw")).value();
        assertEquals(TypedValue.State.OPAQUE, raw.state());
        assertEquals("future", raw.variantId());
        assertEquals(Map.of("raw", List.of("exact", true)), raw.value());
        assertEquals(List.of(Map.of("kept", true)), raw.unknown().get("opaqueFuture"));
        TypedValue scene = created.values().get(PinId.of("scene")).value();
        assertEquals(TypedValue.State.LOCATOR, scene.state());
        assertEquals("scene", scene.variantId());
        assertEquals("spawn", scene.locator().id());
        assertEquals(Map.of("kept", true), scene.unknown().get("locatorFuture"));
        TypedValue label = created.inspectorFields().get(InspectorFieldId.of("label"));
        assertEquals("Default Label", label.value());
        assertEquals(List.of("kept"), label.unknown().get("fieldFuture"));
        assertEquals(stringType, label.type().canonicalValue());
        TypedValue empty = created.inspectorFields().get(InspectorFieldId.of("entry"));
        assertEquals(TypedValue.State.NULL, empty.state());
        assertEquals("kept", empty.unknown().get("nullFuture"));
        TypedValue missingField = created.inspectorFields().get(InspectorFieldId.of("missing"));
        assertEquals(missing, missingField);
        TypedValue rawField = created.inspectorFields().get(InspectorFieldId.of("raw"));
        assertEquals(raw, rawField);

        GraphDocument createdDocument = new GraphDocument(new CatalogVersion(1, 0), resource("defaults"), 0, BINDING,
            Set.of(), List.of(created), List.of(), List.of(), OpaqueData.empty());
        String createdJson = GraphDocumentCodec.INSTANCE.encodeText(createdDocument);
        GraphDocument reopened = GraphDocumentCodec.INSTANCE.decode(CanonicalCodec.decodePermissive(createdJson));
        assertEquals(createdJson, GraphDocumentCodec.INSTANCE.encodeText(reopened));
        assertEquals(created.values(), reopened.nodes().getFirst().values());
        assertEquals(created.inspectorFields(), reopened.nodes().getFirst().inspectorFields());

        FlowGraph existingGraph = new FlowGraph("defaults", Map.of(NODE_INSTANCE.canonicalText(),
            new FlowNode(NODE_OWNER.canonicalText() + ":" + nodeId, 4, 8, Map.of())), List.of(), List.of());
        existingGraph.setResourceType("flow");
        GraphNode existingNode = new GraphNode(NODE_INSTANCE, ContractRef.of(NODE_OWNER, NodeId.of(nodeId)), 2,
            Map.of());
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), resource("defaults"), 0, BINDING,
            Set.of(), List.of(existingNode), List.of(), List.of(), OpaqueData.empty());
        CoreGraphAuthoringAdapter.Result existing = adapter.convert(existingGraph,
            JsonParser.parseString(GraphDocumentCodec.INSTANCE.encodeText(baseline)).getAsJsonObject(), context);

        assertTrue(existing.lossless(), existing.reason());
        assertTrue(existing.graphDocument().nodes().getFirst().values().isEmpty());
        assertTrue(existing.graphDocument().nodes().getFirst().inspectorFields().isEmpty());
    }

    private static CatalogCacheOpaque descriptor(String nodeId, Map<String, String> pins) {
        List<Map<String, Object>> values = new ArrayList<>();
        pins.forEach((pinId, type) -> values.add(Map.of("id", pinId, "type", namedType(type))));
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("id", nodeId);
        descriptor.put("schemaVersion", 2);
        descriptor.put("pins", values);
        return new CatalogCacheOpaque(CanonicalJson.canonicalBytes(descriptor));
    }

    private static Map<String, Object> namedType(String localId) {
        return Map.of("arguments", List.of(), "kind", "named", "type",
            Map.of("localId", localId, "ownerId", "builtin"));
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(RESOURCE_OWNER, ResourceTypeId.of("flow")), id);
    }
}
