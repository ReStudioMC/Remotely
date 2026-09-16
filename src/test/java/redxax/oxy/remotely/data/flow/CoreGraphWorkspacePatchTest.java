package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
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
import restudio.resync.flow.workspace.GraphWorkspacePatchEngine;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreGraphWorkspacePatchTest {
    private static final ServerId SERVER = ServerId.deterministic("workspace-patch-test");
    private static final OwnerId OWNER = OwnerId.of("test");
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(
        SERVER, ContractRef.of(OWNER, ResourceTypeId.of("flow")), "workspace");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "0".repeat(64), "1".repeat(64));

    @Test
    void diffsUseStableIdentityPointersAndPreserveUnknownFields() {
        GraphDocument before = graph(4, List.of(node("main", 10, OpaqueData.of(Map.of("nodeFuture", Map.of("keep", true))), List.of())),
            OpaqueData.of(Map.of("graphFuture", Map.of("keep", "yes"))));
        GraphDocument after = graph(4, List.of(node("main", 22, OpaqueData.of(Map.of("nodeFuture", Map.of("keep", true))), List.of())),
            OpaqueData.of(Map.of("graphFuture", Map.of("keep", "yes"))));

        List<WorkspacePatch<JsonValue>> patches = CoreGraphWorkspacePatch.diff(before, after);
        String nodeId = NodeInstanceId.deterministic("node:main").canonicalText();

        assertEquals(1, patches.size());
        assertEquals("/nodes/@" + nodeId + "/position/x", patches.getFirst().path());
        assertTrue(patches.stream().noneMatch(patch -> CoreGraphWorkspacePatch.containsNumericPointerSegment(patch.path())));
        GraphDocument applied = CoreGraphWorkspacePatch.apply(before, patches);
        assertEquals(after.canonicalJson(), applied.canonicalJson());
        assertEquals(after.canonicalJson(), new GraphWorkspacePatchEngine().apply(before, patches).canonicalJson());
        assertTrue(applied.canonicalJson().contains("graphFuture"));
        assertTrue(applied.canonicalJson().contains("nodeFuture"));
    }

    @Test
    void repeatableElementChangesUseStableNestedPointers() {
        RepeatableElementId elementId = RepeatableElementId.deterministic("element");
        RepeatableBinding beforeRepeatable = repeatable(elementId, "old");
        RepeatableBinding afterRepeatable = repeatable(elementId, "new");
        GraphDocument before = graph(1, List.of(node("main", 0, OpaqueData.empty(), List.of(beforeRepeatable))), OpaqueData.empty());
        GraphDocument after = graph(1, List.of(node("main", 0, OpaqueData.empty(), List.of(afterRepeatable))), OpaqueData.empty());

        List<WorkspacePatch<JsonValue>> patches = CoreGraphWorkspacePatch.diff(before, after);

        assertTrue(patches.stream().anyMatch(patch -> patch.path().contains("/@group/elements/@" + elementId.canonicalText())));
        assertTrue(patches.stream().noneMatch(patch -> CoreGraphWorkspacePatch.containsNumericPointerSegment(patch.path())));
        assertEquals(after.canonicalJson(), CoreGraphWorkspacePatch.apply(before, patches).canonicalJson());
    }

    @Test
    void standaloneRepeatableBindingSelectorRemovalHasLocalServerParity() {
        RepeatableElement element = element("selector-removal", "keep", "value");
        GraphDocument before = repeatableGraph(element);
        String path = repeatableElementsPath().replace("/elements", "");
        List<WorkspacePatch<JsonValue>> patches = List.of(
            new WorkspacePatch<>(CoreGraphWorkspacePatch.REMOVE, path, JsonValue.nullValue()));

        GraphDocument local = CoreGraphWorkspacePatch.apply(before, patches);
        GraphDocument server = new GraphWorkspacePatchEngine().apply(before, patches);

        assertTrue(local.nodes().getFirst().repeatables().isEmpty());
        assertEquals(local.canonicalJson(), server.canonicalJson());
    }

    @Test
    void invalidBatchDoesNotMutateTheBaseGraph() {
        GraphDocument base = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of())), OpaqueData.empty());
        String original = base.canonicalJson();
        String nodeId = NodeInstanceId.deterministic("node:main").canonicalText();
        List<WorkspacePatch<JsonValue>> patches = List.of(
            new WorkspacePatch<>("set", "/nodes/@" + nodeId + "/position/x", JsonValue.of(42)),
            new WorkspacePatch<>("set", "/nodes/0/position/x", JsonValue.of(7)));

        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base, patches));
        assertEquals(original, base.canonicalJson());
    }

    @Test
    void opaqueArraysRemainAtomicAndNumericObjectKeysRemainEditable() {
        LinkedHashMap<String, Object> beforeUnknown = new LinkedHashMap<>();
        beforeUnknown.put("0", "before");
        beforeUnknown.put("futureList", List.of(Map.of("instanceId", "not-a-node", "value", "before")));
        LinkedHashMap<String, Object> afterUnknown = new LinkedHashMap<>();
        afterUnknown.put("0", "after");
        afterUnknown.put("futureList", List.of(Map.of("instanceId", "not-a-node", "value", "after")));
        GraphDocument before = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of())), OpaqueData.of(beforeUnknown));
        GraphDocument after = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of())), OpaqueData.of(afterUnknown));

        List<WorkspacePatch<JsonValue>> patches = CoreGraphWorkspacePatch.diff(before, after);

        assertTrue(patches.stream().anyMatch(patch -> "/0".equals(patch.path())));
        assertTrue(patches.stream().anyMatch(patch -> "/futureList".equals(patch.path())));
        assertFalse(patches.stream().anyMatch(patch -> patch.path().contains("@not-a-node")));
        assertEquals(after.canonicalJson(), CoreGraphWorkspacePatch.apply(before, patches).canonicalJson());
    }

    @Test
    void revisionAndManagedNodeDefinitionChangesAreRejected() {
        GraphDocument base = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument newer = graph(3, List.of(node("main", 10, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphNode changedDefinition = new GraphNode(NodeInstanceId.deterministic("node:main"),
            ContractRef.of(OwnerId.of("other"), NodeId.of("other-node")), 1, null, Map.of(), Map.of(), List.of(), List.of(),
            InspectorState.empty(), 10, 3, OpaqueData.empty());

        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.diff(base, newer));
        assertThrows(IllegalArgumentException.class,
            () -> CoreGraphWorkspacePatch.diff(base, graph(2, List.of(changedDefinition), OpaqueData.empty())));

        GraphDocument changed = graph(2, List.of(changedDefinition), OpaqueData.empty());
        JsonValue changedNodes = GraphDocumentCodec.INSTANCE.encode(changed).value("nodes");
        JsonValue changedNode = ((JsonValue.JsonArray) changedNodes).values().getFirst();
        String nodePath = "/nodes/@" + NodeInstanceId.deterministic("node:main").canonicalText();
        JsonValue originalNode = ((JsonValue.JsonArray) GraphDocumentCodec.INSTANCE.encode(base).value("nodes")).values().getFirst();

        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base,
            List.of(new WorkspacePatch<>("set", nodePath, changedNode))));
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base,
            List.of(new WorkspacePatch<>("set", "/nodes", changedNodes))));
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base, List.of(
            new WorkspacePatch<>("array_remove", "/nodes", originalNode),
            new WorkspacePatch<>("array_add", "/nodes", changedNode))));
    }

    @Test
    void orderedRepeatableReorderHasLocalServerParityAndPreservesMemberData() {
        RepeatableElement first = element("first", "one", "local-one");
        RepeatableElement second = element("second", "two", "local-two");
        RepeatableBinding beforeBinding = new RepeatableBinding(new RepeatableGroupId("group"), true, List.of(first, second));
        RepeatableBinding afterBinding = new RepeatableBinding(new RepeatableGroupId("group"), true, List.of(second, first));
        GraphDocument before = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of(beforeBinding))), OpaqueData.empty());
        GraphDocument after = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of(afterBinding))), OpaqueData.empty());

        List<WorkspacePatch<JsonValue>> patches = CoreGraphWorkspacePatch.diff(before, after);
        GraphDocument local = CoreGraphWorkspacePatch.apply(before, patches);
        GraphDocument server = new GraphWorkspacePatchEngine().apply(before, patches);

        assertEquals(1, patches.size());
        assertEquals(CoreGraphWorkspacePatch.ARRAY_REORDER, patches.getFirst().op());
        JsonObject value = (JsonObject) patches.getFirst().value();
        assertEquals(List.of(first.elementId().canonicalText(), second.elementId().canonicalText()), ids(value, "expected"));
        assertEquals(List.of(second.elementId().canonicalText(), first.elementId().canonicalText()), ids(value, "order"));
        assertEquals(after.canonicalJson(), local.canonicalJson());
        assertEquals(after.canonicalJson(), server.canonicalJson());
        assertEquals("two", local.nodes().getFirst().repeatables().getFirst().elements().getFirst().unknown().get("marker"));
        assertEquals("local-two", local.nodes().getFirst().repeatables().getFirst().elements().getFirst()
            .values().get(PinId.of("value")).value().value());
    }

    @Test
    void reorderFollowsMembershipAndFieldPatchesAndRebaseKeepsConcurrentMemberEdits() {
        RepeatableElement first = element("first", "one", "one");
        RepeatableElement second = element("second", "two", "two");
        RepeatableElement third = element("third", "three", "three");
        RepeatableElement changedSecond = element("second", "changed", "local");
        GraphDocument before = repeatableGraph(first, second);
        GraphDocument desired = repeatableGraph(third, changedSecond);

        List<WorkspacePatch<JsonValue>> patches = CoreGraphWorkspacePatch.diff(before, desired);

        assertEquals(CoreGraphWorkspacePatch.ARRAY_REORDER, patches.getLast().op());
        JsonObject value = (JsonObject) patches.getLast().value();
        assertEquals(List.of(second.elementId().canonicalText(), third.elementId().canonicalText()), ids(value, "expected"));
        assertEquals(List.of(third.elementId().canonicalText(), second.elementId().canonicalText()), ids(value, "order"));
        assertTrue(patches.subList(0, patches.size() - 1).stream()
            .noneMatch(patch -> CoreGraphWorkspacePatch.ARRAY_REORDER.equals(patch.op())));
        assertEquals(desired.canonicalJson(), CoreGraphWorkspacePatch.apply(before, patches).canonicalJson());
        assertEquals(desired.canonicalJson(), new GraphWorkspacePatchEngine().apply(before, patches).canonicalJson());

        GraphDocument reordered = repeatableGraph(second, first);
        GraphDocument concurrent = repeatableGraph(first, element("second", "remote", "remote"));
        GraphDocument rebased = CoreGraphWorkspacePatch.rebase(before, reordered, concurrent);
        RepeatableElement preserved = rebased.nodes().getFirst().repeatables().getFirst().elements().getFirst();
        assertEquals(second.elementId(), preserved.elementId());
        assertEquals("remote", preserved.unknown().get("marker"));
        assertEquals("remote", preserved.values().get(PinId.of("value")).value().value());
        assertEquals(reordered.canonicalJson(), CoreGraphWorkspacePatch.rebase(before, reordered, reordered).canonicalJson());
    }

    @Test
    void reorderRejectsDivergentOrdersMembershipMalformedPayloadsAndBypasses() {
        RepeatableElement first = element("first", "one", "one");
        RepeatableElement second = element("second", "two", "two");
        RepeatableElement third = element("third", "three", "three");
        GraphDocument base = repeatableGraph(first, second);
        String path = repeatableElementsPath();
        WorkspacePatch<JsonValue> reorder = reorderPatch(path, List.of(first.elementId(), second.elementId()),
            List.of(second.elementId(), first.elementId()));

        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(
            repeatableGraph(second, first), List.of(reorder)));
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(
            repeatableGraph(first, second, third), List.of(reorder)));
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base, List.of(
            new WorkspacePatch<>(CoreGraphWorkspacePatch.ARRAY_REORDER, path,
                reorderValue(List.of(first.elementId(), first.elementId()), List.of(first.elementId(), second.elementId()))))));
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base, List.of(
            new WorkspacePatch<>(CoreGraphWorkspacePatch.ARRAY_REORDER, "/nodes/@bad/repeatables/@group/elements", reorder.value()))));
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base, List.of(
            new WorkspacePatch<>(CoreGraphWorkspacePatch.ARRAY_REORDER,
                path.replace("@group", "@missing"), reorder.value()))));

        JsonObject encoded = GraphDocumentCodec.INSTANCE.encode(base);
        JsonArray nodes = (JsonArray) encoded.value("nodes");
        JsonArray repeatables = (JsonArray) ((JsonObject) nodes.values().getFirst()).value("repeatables");
        JsonValue member = ((JsonArray) ((JsonObject) repeatables.values().getFirst()).value("elements")).values().getFirst();
        WorkspacePatch<JsonValue> restoreAfterBypass = reorderPatch(path,
            List.of(second.elementId(), first.elementId()), List.of(first.elementId(), second.elementId()));
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base, List.of(
            new WorkspacePatch<>(CoreGraphWorkspacePatch.ARRAY_REMOVE, path, member),
            new WorkspacePatch<>(CoreGraphWorkspacePatch.ARRAY_ADD, path, member), restoreAfterBypass)));
        JsonValue nodeValue = nodes.values().getFirst();
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base, List.of(
            new WorkspacePatch<>(CoreGraphWorkspacePatch.ARRAY_REMOVE, "/nodes", nodeValue),
            new WorkspacePatch<>(CoreGraphWorkspacePatch.ARRAY_ADD, "/nodes", nodeValue), reorder)));
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base, List.of(
            new WorkspacePatch<>(CoreGraphWorkspacePatch.SET, path.replace("/elements", "/ordered"), JsonValue.of(false)), reorder)));
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(base, List.of(
            new WorkspacePatch<>(CoreGraphWorkspacePatch.SET, path.replace("/elements", "/ordered"), JsonValue.of(false)),
            new WorkspacePatch<>(CoreGraphWorkspacePatch.SET,
                path + "/@" + first.elementId().canonicalText() + "/future", JsonValue.of(true)))));

        GraphDocument unordered = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of(
            new RepeatableBinding(RepeatableGroupId.of("group"), false, List.of(first, second))))), OpaqueData.empty());
        assertThrows(IllegalArgumentException.class, () -> CoreGraphWorkspacePatch.apply(unordered, List.of(reorder)));
    }

    @Test
    void orderedIdentityCollectionSupportsSuffixAppend() {
        RepeatableElement first = new RepeatableElement(RepeatableElementId.deterministic("first"), Map.of(), OpaqueData.empty());
        RepeatableElement second = new RepeatableElement(RepeatableElementId.deterministic("second"), Map.of(), OpaqueData.empty());
        RepeatableBinding beforeBinding = new RepeatableBinding(new RepeatableGroupId("group"), true, List.of(first));
        RepeatableBinding afterBinding = new RepeatableBinding(new RepeatableGroupId("group"), true, List.of(first, second));
        GraphDocument before = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of(beforeBinding))), OpaqueData.empty());
        GraphDocument after = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of(afterBinding))), OpaqueData.empty());

        List<WorkspacePatch<JsonValue>> patches = CoreGraphWorkspacePatch.diff(before, after);

        assertEquals(1, patches.size());
        assertEquals("array_add", patches.getFirst().op());
        assertEquals(after.canonicalJson(), CoreGraphWorkspacePatch.apply(before, patches).canonicalJson());
        assertEquals(after.canonicalJson(), new GraphWorkspacePatchEngine().apply(before, patches).canonicalJson());
    }

    @Test
    void unorderedConditionalElementsAllowCanonicalRemoveAndAdd() {
        RepeatableElement first = new RepeatableElement(RepeatableElementId.deterministic("first"), Map.of(), OpaqueData.empty());
        RepeatableElement second = new RepeatableElement(RepeatableElementId.deterministic("second"), Map.of(), OpaqueData.empty());
        RepeatableBinding binding = new RepeatableBinding(new RepeatableGroupId("group"), false, List.of(first, second));
        GraphDocument base = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of(binding))), OpaqueData.empty());
        JsonArray nodes = (JsonArray) GraphDocumentCodec.INSTANCE.encode(base).value("nodes");
        JsonArray repeatables = (JsonArray) ((JsonObject) nodes.values().getFirst()).value("repeatables");
        JsonArray elements = (JsonArray) ((JsonObject) repeatables.values().getFirst()).value("elements");
        JsonValue member = elements.values().getFirst();
        String path = "/nodes/@" + NodeInstanceId.deterministic("node:main").canonicalText() + "/repeatables/@group/elements";
        List<WorkspacePatch<JsonValue>> patches = List.of(
            new WorkspacePatch<>("array_remove", path, member),
            new WorkspacePatch<>("array_add", path, member));

        assertEquals(base.canonicalJson(), CoreGraphWorkspacePatch.apply(base, patches).canonicalJson());
        assertEquals(base.canonicalJson(), new GraphWorkspacePatchEngine().apply(base, patches).canonicalJson());
    }

    @Test
    void optionalKnownArraysUseStableMemberOperationsFromEmpty() {
        GraphVariable variable = new GraphVariable(UUID.fromString("11111111-1111-1111-1111-111111111111"), "value",
            TypeExpr.named(TypeReference.of("test", "string")));
        GraphDocument empty = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument populated = graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of())), List.of(variable), OpaqueData.empty());

        List<WorkspacePatch<JsonValue>> added = CoreGraphWorkspacePatch.diff(empty, populated);
        List<WorkspacePatch<JsonValue>> removed = CoreGraphWorkspacePatch.diff(populated, empty);

        assertEquals("array_add", added.getFirst().op());
        assertEquals("/variables", added.getFirst().path());
        assertEquals(populated.canonicalJson(), CoreGraphWorkspacePatch.apply(empty, added).canonicalJson());
        assertEquals(populated.canonicalJson(), new GraphWorkspacePatchEngine().apply(empty, added).canonicalJson());
        assertEquals("array_remove", removed.getFirst().op());
        assertEquals(empty.canonicalJson(), CoreGraphWorkspacePatch.apply(populated, removed).canonicalJson());
        assertEquals(empty.canonicalJson(), new GraphWorkspacePatchEngine().apply(populated, removed).canonicalJson());
    }

    @Test
    void rebaseMergesIndependentChangesAndRejectsChangedRemovalTargets() {
        GraphNode originalNode = node("main", 10, 3, OpaqueData.empty(), List.of());
        GraphDocument original = graph(4, List.of(originalNode, node("remove", 0, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument desired = graph(4, List.of(node("main", 20, 3, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument latest = graph(5, List.of(node("main", 10, 9, OpaqueData.empty(), List.of()),
            node("remove", 4, OpaqueData.empty(), List.of())), OpaqueData.of(Map.of("remoteFuture", true)));

        assertThrows(IllegalStateException.class, () -> CoreGraphWorkspacePatch.rebase(original, desired, latest));

        GraphDocument localPosition = graph(4, List.of(node("main", 20, 3, OpaqueData.empty(), List.of()),
            node("remove", 0, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument remotePosition = graph(5, List.of(node("main", 10, 9, OpaqueData.empty(), List.of()),
            node("remove", 0, OpaqueData.empty(), List.of())), OpaqueData.of(Map.of("remoteFuture", true)));
        GraphDocument merged = CoreGraphWorkspacePatch.rebase(original, localPosition, remotePosition);

        assertEquals(20, findNode(merged, "main").x());
        assertEquals(9, findNode(merged, "main").y());
        assertTrue(merged.canonicalJson().contains("remoteFuture"));
    }

    @Test
    void pendingOperationsSurviveReconnectRestoreAndSequenceAwareRebase() {
        GraphDocument base = graph(4, List.of(node("main", 10, 3, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument firstDesired = graph(4, List.of(node("main", 20, 3, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument secondDesired = graph(4, List.of(node("main", 20, 8, OpaqueData.empty(), List.of())), OpaqueData.empty());
        CoreGraphWorkspacePendingOperations pending = new CoreGraphWorkspacePendingOperations();

        pending.enqueue("operation-1", 10, base, CoreGraphWorkspacePatch.diff(base, firstDesired));
        pending.enqueue("operation-2", 11, firstDesired, CoreGraphWorkspacePatch.diff(firstDesired, secondDesired));
        assertEquals(2, pending.reconnect().size());
        CoreGraphWorkspacePendingOperations restored = new CoreGraphWorkspacePendingOperations(pending.reconnect());
        GraphDocument latest = graph(5, List.of(node("main", 10, 3, OpaqueData.empty(), List.of())),
            OpaqueData.of(Map.of("remoteFuture", "preserved")));
        List<CoreGraphWorkspacePendingOperations.PendingOperation> rebased = restored.rebase(30, latest);

        assertEquals(2, rebased.size());
        assertEquals(30, rebased.getFirst().baseSequence());
        assertEquals(31, rebased.get(1).baseSequence());
        assertEquals(20, findNode(rebased.get(1).desired(), "main").x());
        assertEquals(8, findNode(rebased.get(1).desired(), "main").y());
        assertTrue(rebased.get(1).desired().canonicalJson().contains("remoteFuture"));
        assertTrue(restored.acknowledge("operation-1"));
        assertTrue(restored.acknowledge("operation-2"));
        assertTrue(restored.pending().isEmpty());
    }

    @Test
    void rebaseDropsAlreadyAppliedOperationsWithoutConsumingAnotherSequence() {
        GraphDocument base = graph(4, List.of(node("main", 10, 3, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument firstDesired = graph(4, List.of(node("main", 20, 3, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument secondDesired = graph(4, List.of(node("main", 20, 8, OpaqueData.empty(), List.of())), OpaqueData.empty());
        CoreGraphWorkspacePendingOperations pending = new CoreGraphWorkspacePendingOperations();
        pending.enqueue("operation-1", 10, base, CoreGraphWorkspacePatch.diff(base, firstDesired));
        pending.enqueue("operation-2", 11, firstDesired, CoreGraphWorkspacePatch.diff(firstDesired, secondDesired));
        GraphDocument latest = graph(5, List.of(node("main", 20, 3, OpaqueData.empty(), List.of())), OpaqueData.empty());

        List<CoreGraphWorkspacePendingOperations.PendingOperation> rebased = pending.rebase(11, latest);

        assertEquals(1, rebased.size());
        assertEquals("operation-2", rebased.getFirst().operationId());
        assertEquals(11, rebased.getFirst().baseSequence());
        assertThrows(IllegalArgumentException.class, () -> pending.enqueue("broken", 13, latest,
            CoreGraphWorkspacePatch.diff(latest,
                graph(5, List.of(node("main", 20, 9, OpaqueData.empty(), List.of())), OpaqueData.empty()))));
    }

    @Test
    void rebaseDropsLostEchoRemovesAndArrayRemovesAlreadyReflectedByTheSnapshot() {
        GraphVariable variable = new GraphVariable(UUID.fromString("22222222-2222-2222-2222-222222222222"), "value",
            TypeExpr.named(TypeReference.of("test", "string")));
        GraphDocument base = graph(4, List.of(node("remove", 10, OpaqueData.empty(), List.of())), List.of(variable), OpaqueData.empty());
        GraphDocument afterNodeRemove = graph(4, List.of(), List.of(variable), OpaqueData.empty());
        GraphDocument afterVariableRemove = graph(4, List.of(), List.of(), OpaqueData.empty());
        GraphDocument latest = graph(5, List.of(), List.of(), OpaqueData.of(Map.of("remoteFuture", true)));
        CoreGraphWorkspacePendingOperations pending = new CoreGraphWorkspacePendingOperations();

        pending.enqueue("remove-node", 10, base, CoreGraphWorkspacePatch.diff(base, afterNodeRemove));
        pending.enqueue("remove-variable", 11, afterNodeRemove, CoreGraphWorkspacePatch.diff(afterNodeRemove, afterVariableRemove));

        assertTrue(pending.rebase(12, latest).isEmpty());
        assertTrue(pending.pending().isEmpty());
    }

    @Test
    void rebaseDoesNotAdvancePastTheLastEmittedOperation() {
        GraphDocument base = graph(4, List.of(node("main", 10, 3, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument firstDesired = graph(4, List.of(node("main", 20, 3, OpaqueData.empty(), List.of())), OpaqueData.empty());
        GraphDocument secondDesired = graph(4, List.of(node("main", 20, 8, OpaqueData.empty(), List.of())), OpaqueData.empty());
        CoreGraphWorkspacePendingOperations pending = new CoreGraphWorkspacePendingOperations();
        pending.enqueue("operation-1", 10, base, CoreGraphWorkspacePatch.diff(base, firstDesired));
        pending.enqueue("operation-2", 11, firstDesired, CoreGraphWorkspacePatch.diff(firstDesired, secondDesired));
        GraphDocument latest = graph(5, List.of(node("main", 10, 8, OpaqueData.empty(), List.of())), OpaqueData.empty());

        List<CoreGraphWorkspacePendingOperations.PendingOperation> rebased = pending.rebase(Long.MAX_VALUE, latest);

        assertEquals(1, rebased.size());
        assertEquals("operation-1", rebased.getFirst().operationId());
        assertEquals(Long.MAX_VALUE, rebased.getFirst().baseSequence());
    }

    private static GraphDocument graph(long revision, List<GraphNode> nodes, OpaqueData unknown) {
        return graph(revision, nodes, List.of(), unknown);
    }

    private static GraphDocument graph(long revision, List<GraphNode> nodes, List<GraphVariable> variables, OpaqueData unknown) {
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, revision, BINDING, Set.of(), nodes, List.of(), variables, List.of(), unknown);
    }

    private static GraphNode node(String name, double x, OpaqueData unknown, List<RepeatableBinding> repeatables) {
        return node(name, x, 3, unknown, repeatables);
    }

    private static GraphNode node(String name, double x, double y, OpaqueData unknown, List<RepeatableBinding> repeatables) {
        return new GraphNode(NodeInstanceId.deterministic("node:" + name), ContractRef.of(OWNER, NodeId.of("test-node")), 1,
            null, Map.of(), Map.of(), List.of(), repeatables, InspectorState.empty(), x, y, unknown);
    }

    private static RepeatableBinding repeatable(RepeatableElementId elementId, String marker) {
        return new RepeatableBinding(new RepeatableGroupId("group"), true,
            List.of(new RepeatableElement(elementId, Map.of(), OpaqueData.of(Map.of("elementFuture", marker)))));
    }

    private static RepeatableElement element(String seed, String marker, String value) {
        PinId pinId = PinId.of("value");
        return new RepeatableElement(RepeatableElementId.deterministic(seed),
            Map.of(pinId, new PinValue(pinId, TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "string")), value))),
            OpaqueData.of(Map.of("marker", marker)));
    }

    private static GraphDocument repeatableGraph(RepeatableElement... elements) {
        RepeatableBinding binding = new RepeatableBinding(RepeatableGroupId.of("group"), true, List.of(elements));
        return graph(2, List.of(node("main", 10, OpaqueData.empty(), List.of(binding))), OpaqueData.empty());
    }

    private static String repeatableElementsPath() {
        return "/nodes/@" + NodeInstanceId.deterministic("node:main").canonicalText() + "/repeatables/@group/elements";
    }

    private static WorkspacePatch<JsonValue> reorderPatch(String path, List<RepeatableElementId> expected,
                                                           List<RepeatableElementId> order) {
        return new WorkspacePatch<>(CoreGraphWorkspacePatch.ARRAY_REORDER, path, reorderValue(expected, order));
    }

    private static JsonObject reorderValue(List<RepeatableElementId> expected, List<RepeatableElementId> order) {
        return JsonValue.object(Map.of(
            "expected", JsonValue.array(expected.stream().map(id -> JsonValue.of(id.canonicalText())).toList()),
            "order", JsonValue.array(order.stream().map(id -> JsonValue.of(id.canonicalText())).toList())));
    }

    private static List<String> ids(JsonObject value, String field) {
        return ((JsonArray) value.value(field)).values().stream()
            .map(identity -> ((JsonValue.JsonString) identity).value()).toList();
    }

    private static GraphNode findNode(GraphDocument graph, String name) {
        NodeInstanceId id = NodeInstanceId.deterministic("node:" + name);
        return graph.nodes().stream().filter(node -> id.equals(node.instanceId())).findFirst().orElseThrow();
    }
}
