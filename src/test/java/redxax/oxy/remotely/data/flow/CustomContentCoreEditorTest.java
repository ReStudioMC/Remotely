package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.CustomContentGraphAdapter;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.data.FlowVariable;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ModeId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentCoreEditorTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ServerId SERVER = ServerId.deterministic("custom-content-core-editor");
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("custom_content")), "item");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "a".repeat(64), "b".repeat(64));
    private static final NodeInstanceId ROOT = NodeInstanceId.deterministic("content-root");
    private static final NodeInstanceId ACTION = NodeInstanceId.deterministic("content-action");
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));

    @Test
    void acknowledgedAggregateOpensMigratesMovesConnectsAndReopensWithoutLosingTypedState() {
        CustomContentDefinition content = content();
        CoreGraphEditorSession session = open(content, 4);
        assertFalse(session.dirty());
        assertEquals("custom_content", session.resource().resourceType().value());
        assertEquals(2, session.graphDocument().nodes().stream().filter(node -> node.instanceId().equals(ROOT)).findFirst().orElseThrow().definitionVersion());
        assertEquals("output_content_id", session.graphDocument().connections().getFirst().source().pinId().canonicalText());
        assertFalse(session.graphDocument().nodes().stream().filter(node -> node.instanceId().equals(ROOT)).findFirst().orElseThrow()
            .values().keySet().stream().anyMatch(pin -> pin.canonicalText().equals("__flow_branches")));

        session.setNodePosition(ROOT, 240, 360);
        session.setNodeValue(ROOT, PinId.of("name"), TypedValue.value(STRING, "Edited"));
        session.setNodeModeId(ACTION, ModeId.of("expanded"));
        session.setNodeInspectorField(ACTION, InspectorFieldId.of("label"), TypedValue.value(STRING, "Inspector"));
        session.setNodeRepeatables(ACTION, List.of(new RepeatableBinding(RepeatableGroupId.of("items"), true,
            List.of(new RepeatableElement(RepeatableElementId.deterministic("entry"), Map.of(), OpaqueData.of(Map.of("future", true)))),
            OpaqueData.empty())));
        session.addVariable(new GraphVariable(NodeInstanceId.deterministic("variable").value(), "local", STRING,
            TypedValue.value(STRING, "kept"), OpaqueData.empty()));
        session.addConnection(new GraphConnection(ConnectionId.deterministic("event-connection"),
            new GraphEndpoint(ROOT, PinId.of("use")), new GraphEndpoint(ACTION, PinId.of("execute"))));
        assertTrue(session.dirty());

        CustomContentDefinition saved = CustomContentCoreEditor.materialize(session, content, publication(), entries());
        CustomContentDefinition wire = FlowSerializer.deserializeCustomContent(FlowSerializer.serializeCustomContent(saved));
        assertNotNull(wire.getGraph().getOpaqueProperties().get(CustomContentCoreEditor.CORE_GRAPH));
        assertEquals("item", wire.getId());
        assertEquals(content.getFlowId(), wire.getFlowId());
        assertEquals("nexo", wire.getProvider());
        assertEquals(Map.of("custom:name", "kept"), wire.getComponents());
        assertEquals(7, wire.getVersion());
        assertEquals(List.of("use"), CustomContentGraphAdapter.getEnabledTriggerBranches(wire.getGraph()));
        assertEquals("kept", wire.getGraph().getContentProperties().get("custom"));
        assertTrue(wire.getAbilities().stream().allMatch(ability -> wire.getFlowId().equals(ability.getFlowId())));

        CoreGraphEditorSession reopened = open(wire, 5);
        GraphNode root = reopened.graphDocument().nodes().stream().filter(node -> node.instanceId().equals(ROOT)).findFirst().orElseThrow();
        GraphNode action = reopened.graphDocument().nodes().stream().filter(node -> node.instanceId().equals(ACTION)).findFirst().orElseThrow();
        assertEquals(240, root.x());
        assertEquals(360, root.y());
        assertEquals("Edited", root.values().get(PinId.of("name")).value().value());
        assertEquals(ModeId.of("expanded"), action.modeId());
        assertEquals("Inspector", action.inspectorFields().get(InspectorFieldId.of("label")).value());
        assertEquals(1, action.repeatables().size());
        assertEquals(1, reopened.graphDocument().variables().size());
        assertEquals(2, reopened.graphDocument().connections().size());
        assertFalse(reopened.dirty());
    }

    @Test
    void legacyCollectionOrderDoesNotChangeCanonicalCoreDocument() {
        String expected = null;
        for (int order = 0; order < 8; order++) {
            CustomContentDefinition content = content();
            FlowGraph graph = content.getGraph();
            List<String> nodeIds = new ArrayList<>(graph.getNodes().keySet());
            Collections.sort(nodeIds);
            if ((order & 1) != 0) Collections.reverse(nodeIds);
            Map<String, FlowNode> nodes = new LinkedHashMap<>();
            nodeIds.forEach(id -> nodes.put(id, graph.getNodes().get(id)));
            graph.setNodes(nodes);
            graph.getConnections().add(new FlowConnection(ROOT.canonicalText(), "use", ACTION.canonicalText(), "execute"));
            if ((order & 2) != 0) Collections.reverse(graph.getConnections());
            graph.getLocalVariables().add(new FlowVariable("first", "string", "one"));
            graph.getLocalVariables().add(new FlowVariable("second", "string", "two"));
            if ((order & 4) != 0) Collections.reverse(graph.getLocalVariables());

            String canonical = open(content, 4).canonicalPayloadJson();
            if (expected == null) expected = canonical;
            assertEquals(expected, canonical, "Collection Order " + order);
        }
    }

    @Test
    void legacyConnectionAndVariableIdentitiesUseStableVersionFiveUuids() {
        CustomContentDefinition content = content();
        content.getGraph().getLocalVariables().add(new FlowVariable("legacy", "string", "kept"));
        GraphDocument first = open(content, 4).graphDocument();
        GraphDocument second = open(content, 4).graphDocument();
        assertEquals(5, first.connections().getFirst().connectionId().value().version());
        assertEquals(5, first.variables().getFirst().variableId().version());
        assertEquals(first.connections().getFirst().connectionId(), second.connections().getFirst().connectionId());
        assertEquals(first.variables().getFirst().variableId(), second.variables().getFirst().variableId());
    }

    @Test
    void failedSaveRetainsDraftAndMatchingAcknowledgmentPreservesLaterEdits() {
        CustomContentDefinition content = content();
        CoreGraphEditorSession session = open(content, 4);
        GraphDocument baseline = session.graphDocument();
        session.setNodeValue(ROOT, PinId.of("name"), TypedValue.value(STRING, "Submitted"));
        GraphDocument submitted = session.graphDocument();
        CoreGraphEditorSession failed = CustomContentCoreEditor.rebase(open(content, 4), baseline, submitted, null);
        assertTrue(failed.dirty());
        assertEquals("Submitted", name(failed));

        CustomContentDefinition saved = CustomContentCoreEditor.materialize(session, content, publication(), entries());
        session.setNodeValue(ROOT, PinId.of("name"), TypedValue.value(STRING, "After Save"));
        CoreGraphEditorSession acknowledged = CustomContentCoreEditor.rebase(open(saved, 5), baseline,
            session.graphDocument(), submitted);
        assertEquals(5, acknowledged.baselineRevision());
        assertEquals("After Save", name(acknowledged));
        assertTrue(acknowledged.dirty());
        CustomContentDefinition savedAgain = CustomContentCoreEditor.materialize(acknowledged, saved, publication(), entries());
        CoreGraphEditorSession clean = CustomContentCoreEditor.rebase(open(savedAgain, 6), acknowledged.baselineGraphDocument(),
            acknowledged.graphDocument(), acknowledged.graphDocument());
        assertFalse(clean.dirty());
        assertEquals("After Save", name(clean));
    }

    @Test
    void invalidEmbeddedCoreDoesNotFallBackToTheCompatibilityGraph() {
        CustomContentDefinition content = content();
        content.getGraph().getOpaqueProperties().put(CustomContentCoreEditor.CORE_GRAPH, JsonParser.parseString("{\"invalid\":true}"));
        assertThrows(IllegalArgumentException.class, () -> open(content, 4));
        assertEquals(2, content.getGraph().getNodes().size());
    }

    @Test
    void acknowledgedSaveThenNewerAuthorityRebasesOnlyEditsMadeAfterSubmission() {
        CustomContentDefinition content = content();
        CoreGraphEditorSession local = open(content, 4);
        GraphDocument baseline = local.graphDocument();
        local.setNodeValue(ROOT, PinId.of("name"), TypedValue.value(STRING, "Submitted"));
        GraphDocument submitted = local.graphDocument();
        CustomContentDefinition saved = CustomContentCoreEditor.materialize(local, content, publication(), entries());
        CoreGraphEditorSession remote = open(saved, 5);
        remote.setNodeValue(ROOT, PinId.of("name"), TypedValue.value(STRING, "Newer Remote"));
        CoreGraphEditorSession latest = open(CustomContentCoreEditor.materialize(remote, saved, publication(), entries()), 6);
        local.setNodePosition(ROOT, 400, 500);

        CoreGraphEditorSession merged = CustomContentCoreEditor.rebase(latest, baseline, local.graphDocument(), submitted);

        assertEquals("Newer Remote", name(merged));
        assertEquals(400, merged.graphDocument().nodes().stream().filter(node -> node.instanceId().equals(ROOT)).findFirst().orElseThrow().x());
        assertTrue(merged.dirty());
    }

    @Test
    void changedCatalogBindingCannotReinterpretEmbeddedNodesWithUnchangedVersions() {
        CustomContentDefinition content = content();
        CoreGraphEditorSession session = open(content, 4);
        CustomContentDefinition saved = CustomContentCoreEditor.materialize(session, content, publication(), entries());
        CatalogAuthoringPublication current = publication();
        CatalogAuthoringPublication changed = new CatalogAuthoringPublication(new CatalogBinding(2, "c".repeat(64), "d".repeat(64)),
            current.contractVersion(), current.projectionVersion(), current.sections(), current.advertisedEditCapabilities());
        List<CatalogCachePublication.Entry> changedEntries = new ArrayList<>(entries());
        changedEntries.set(1, entry("action", 1, List.of(pin("value", "input", "number"), pin("execute", "input", "execution")), Map.of()));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> CustomContentCoreEditor.prepare(saved, RESOURCE, 5, changed, changedEntries));

        assertTrue(failure.getMessage().contains("Server Catalog Migration"));
        assertFalse(session.dirty());
    }

    @Test
    void forwardCatalogWithUnchangedUsedContractsReopensAndCanBeSavedAgain() {
        CustomContentDefinition content = content();
        CoreGraphEditorSession session = open(content, 4);
        CustomContentDefinition saved = CustomContentCoreEditor.materialize(session, content, publication(), entries());
        CatalogAuthoringPublication current = publication();
        CatalogBinding binding = new CatalogBinding(2, "c".repeat(64), "d".repeat(64));
        CatalogAuthoringPublication changed = new CatalogAuthoringPublication(binding, current.contractVersion(),
            current.projectionVersion(), current.sections(), current.advertisedEditCapabilities());
        List<CatalogCachePublication.Entry> changedEntries = new ArrayList<>(entries());
        changedEntries.add(entry("unrelated", 1, List.of(pin("unused", "input", "number")), Map.of()));

        CoreGraphEditorSession reopened = CustomContentCoreEditor.prepare(saved, RESOURCE, 5, changed, changedEntries);
        assertEquals(binding, reopened.catalogBinding());
        assertFalse(reopened.dirty());
        reopened.setNodeValue(ROOT, PinId.of("name"), TypedValue.value(STRING, "After Catalog Refresh"));
        CustomContentDefinition resaved = CustomContentCoreEditor.materialize(reopened, saved, changed, changedEntries);
        CoreGraphEditorSession next = CustomContentCoreEditor.prepare(resaved, RESOURCE, 6, changed, changedEntries);
        assertEquals("After Catalog Refresh", name(next));
        assertFalse(next.dirty());
    }

    @Test
    void changedBindingWithoutEvidenceAndSameGenerationHashChangesAreRejected() {
        CustomContentDefinition content = content();
        CoreGraphEditorSession session = open(content, 4);
        CustomContentDefinition saved = CustomContentCoreEditor.materialize(session, content, publication(), entries());
        CatalogAuthoringPublication current = publication();
        CatalogAuthoringPublication sameGeneration = new CatalogAuthoringPublication(new CatalogBinding(1, "c".repeat(64), "d".repeat(64)),
            current.contractVersion(), current.projectionVersion(), current.sections(), current.advertisedEditCapabilities());
        assertThrows(IllegalArgumentException.class,
            () -> CustomContentCoreEditor.prepare(saved, RESOURCE, 5, sameGeneration, entries()));
        saved.getGraph().getOpaqueProperties().get(CustomContentCoreEditor.CORE_GRAPH).getAsJsonObject()
            .remove(CustomContentCoreEditor.CATALOG_COMPATIBILITY);
        CatalogAuthoringPublication forward = new CatalogAuthoringPublication(new CatalogBinding(2, "c".repeat(64), "d".repeat(64)),
            current.contractVersion(), current.projectionVersion(), current.sections(), current.advertisedEditCapabilities());
        assertThrows(IllegalArgumentException.class,
            () -> CustomContentCoreEditor.prepare(saved, RESOURCE, 5, forward, entries()));
        assertFalse(open(saved, 5).dirty());
    }

    @Test
    void newerRemoteChangesConflictWithoutDiscardingTheLocalDraft() {
        CustomContentDefinition content = content();
        CoreGraphEditorSession local = open(content, 4);
        GraphDocument baseline = local.graphDocument();
        local.setNodeValue(ROOT, PinId.of("name"), TypedValue.value(STRING, "Local"));
        CoreGraphEditorSession remote = open(content, 4);
        remote.setNodeValue(ROOT, PinId.of("name"), TypedValue.value(STRING, "Remote"));
        CoreGraphEditorSession latest = open(CustomContentCoreEditor.materialize(remote, content, publication(), entries()), 5);
        assertThrows(IllegalStateException.class, () -> CustomContentCoreEditor.rebase(latest, baseline, local.graphDocument(), null));
        assertEquals("Local", name(local));
        assertTrue(local.dirty());
    }

    @Test
    void authorityStampChangesWhenReconciliationCommitsAfterCachePreparation() {
        ReSyncResourceRevisionReconciler reconciler = new ReSyncResourceRevisionReconciler();
        JsonObject payload = JsonParser.parseString(FlowSerializer.serializeCustomContent(content())).getAsJsonObject();
        reconciler.apply(new ReSyncResourceRevisionReconciler.ResourceResult(SERVER.canonicalText(), "custom_content", "item",
            4, "first", "hash-first", false, payload, 1));
        ReSyncResourceRevisionReconciler.Stamp before = reconciler.stamp(SERVER.canonicalText(), "custom_content", "item");
        ReSyncResourceRevisionReconciler.Admission pending = reconciler.prepare(new ReSyncResourceRevisionReconciler.ResourceResult(
            SERVER.canonicalText(), "custom_content", "item", 5, "second", "hash-second", false, payload, 1));
        assertEquals(before, reconciler.stamp(SERVER.canonicalText(), "custom_content", "item"));
        reconciler.commit(pending);
        ReSyncResourceRevisionReconciler.Stamp after = reconciler.stamp(SERVER.canonicalText(), "custom_content", "item");
        assertNotEquals(before, after);
        assertEquals(5, after.revision());
        assertEquals("second", after.mutationId());
    }

    private static String name(CoreGraphEditorSession session) {
        return (String) session.graphDocument().nodes().stream().filter(node -> node.instanceId().equals(ROOT)).findFirst().orElseThrow()
            .values().get(PinId.of("name")).value().value();
    }

    private static CoreGraphEditorSession open(CustomContentDefinition content, long revision) {
        return CustomContentCoreEditor.prepare(content, RESOURCE, revision, publication(), entries());
    }

    private static CustomContentDefinition content() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("content_id", "item");
        inputs.put("name", "Before");
        inputs.put("provider", "nexo");
        inputs.put("external_id", "weapon");
        inputs.put("material", "STICK");
        inputs.put("components", Map.of("custom:name", "kept"));
        inputs.put(CustomContentGraphAdapter.FLOW_BRANCHES_KEY, List.of("use"));
        FlowGraph graph = new FlowGraph();
        graph.setId("content.item.item");
        graph.setNodes(new LinkedHashMap<>(Map.of(ROOT.canonicalText(), new FlowNode("custom_content.item", 12, 24, inputs),
            ACTION.canonicalText(), new FlowNode("action", 160, 24, Map.of("value", "payload")))));
        graph.getConnections().add(new FlowConnection(ROOT.canonicalText(), "content_id", ACTION.canonicalText(), "value"));
        graph.getContentProperties().put("custom", "kept");
        CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
        content.setVersion(7);
        return content;
    }

    private static CatalogAuthoringPublication publication() {
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES, true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS, true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS, true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES, true, true, CatalogCacheState.ACTIVE, List.of()));
        return new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0), CatalogProjectionVersion.current(), sections, Set.of());
    }

    private static List<CatalogCachePublication.Entry> entries() {
        List<Map<String, Object>> inputs = new ArrayList<>();
        for (String pin : List.of("content_id", "name", "provider", "external_id", "material", "components")) {
            inputs.add(pin(pin, "input", pin.equals("components") ? "any" : "string"));
        }
        inputs.add(pin("output_content_id", "output", "string"));
        inputs.add(pin("use", "output", "execution"));
        List<Map<String, Object>> mapping = new ArrayList<>();
        for (String pin : List.of("content_id", "name", "provider", "external_id", "material", "components")) {
            mapping.add(Map.of("direction", "input", "source", pin, "target", pin));
        }
        mapping.add(Map.of("direction", "output", "source", "content_id", "target", "output_content_id"));
        mapping.add(Map.of("direction", "output", "source", "use", "target", "use"));
        Map<String, Object> migration = Map.of("sourceSchemaVersion", 1, "targetSchemaVersion", 2, "complete", true, "pins", mapping);
        return List.of(entry("custom_content.item", 2, inputs, Map.of("authoredSource", Map.of("migrationMapping", migration))),
            entry("action", 1, List.of(pin("value", "input", "string"), pin("execute", "input", "execution")), Map.of()));
    }

    private static Map<String, Object> pin(String id, String direction, String type) {
        return Map.of("id", id, "direction", direction, "type", Map.of("kind", "named", "arguments", List.of(),
            "type", Map.of("ownerId", "builtin", "localId", type)));
    }

    private static CatalogCachePublication.Entry entry(String id, int version, List<Map<String, Object>> pins, Map<String, Object> metadata) {
        return CatalogCachePublication.Entry.present(ContractRef.of(OWNER, NodeId.of(id)), 1, CatalogCacheState.ACTIVE, Set.of(), false,
            new CatalogCacheOpaque(CanonicalJson.canonicalBytes(Map.of("id", id, "schemaVersion", version, "pins", pins, "metadata", metadata))));
    }
}
