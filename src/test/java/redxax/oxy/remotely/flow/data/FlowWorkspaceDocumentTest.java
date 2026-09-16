package redxax.oxy.remotely.flow.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowWorkspaceDocumentTest {
    @Test
    void diffsIndependentNodeChangesWithoutReplacingTheGraph() {
        JsonObject before = object("""
            {
              "nodes": {
                "first": {"type": "log", "x": 10, "y": 20, "inputValues": {"message": "Before"}}
              },
              "connections": []
            }
            """);
        JsonObject after = object("""
            {
              "nodes": {
                "first": {"type": "log", "x": 80, "y": 20, "inputValues": {"message": "After"}},
                "second": {"type": "delay", "x": 140, "y": 20, "inputValues": {}}
              },
              "connections": []
            }
            """);

        List<WorkspacePatch<JsonElement>> patches = FlowWorkspaceDocument.diff(before, after);

        assertTrue(patches.stream().anyMatch(patch -> "/nodes/first/x".equals(patch.path())));
        assertTrue(patches.stream().anyMatch(patch -> "/nodes/first/inputValues/message".equals(patch.path())));
        assertTrue(patches.stream().anyMatch(patch -> "/nodes/second".equals(patch.path())));
        JsonObject applied = before.deepCopy();
        FlowWorkspaceDocument.apply(applied, patches);
        assertEquals(after, applied);
    }

    @Test
    void connectionChangesAreSetOperationsThatCompose() {
        JsonObject base = object("""
            {"nodes": {}, "connections": []}
            """);
        JsonObject first = object("""
            {"nodes": {}, "connections": [{"sourceNodeId":"a","sourcePin":"out","targetNodeId":"b","targetPin":"in"}]}
            """);
        JsonObject second = object("""
            {"nodes": {}, "connections": [{"sourceNodeId":"c","sourcePin":"out","targetNodeId":"d","targetPin":"in"}]}
            """);

        List<WorkspacePatch<JsonElement>> firstPatches = FlowWorkspaceDocument.diff(base, first);
        List<WorkspacePatch<JsonElement>> secondPatches = FlowWorkspaceDocument.diff(base, second);
        JsonObject merged = base.deepCopy();
        FlowWorkspaceDocument.apply(merged, firstPatches);
        FlowWorkspaceDocument.apply(merged, firstPatches);
        FlowWorkspaceDocument.apply(merged, secondPatches);

        assertEquals(2, merged.getAsJsonArray("connections").size());
        assertTrue(firstPatches.stream().allMatch(patch -> "array_add".equals(patch.op())));
        assertTrue(secondPatches.stream().allMatch(patch -> "array_add".equals(patch.op())));
    }

    @Test
    void editableGraphDiffIgnoresServerManagedActivationAndIdentity() {
        JsonObject before = object("""
            {"enabled":true,"resourceRevision":15,"resourceHash":"old","resourceMutationId":"one","nodes":{},"connections":[]}
            """);
        JsonObject after = object("""
            {"enabled":false,"resourceRevision":16,"resourceHash":"new","resourceMutationId":"two","nodes":{},"connections":[]}
            """);

        assertTrue(FlowWorkspaceDocument.diffEditableWorkspace(before, after).isEmpty());
        assertEquals(FlowWorkspaceDocument.editableWorkspace(before), FlowWorkspaceDocument.editableWorkspace(after));
    }

    @Test
    void discardDiffRemovesTheUnsavedNodeFromTheWorkspace() {
        JsonObject mutated = object("""
            {
              "nodes": {
                "start": {"type": "command", "x": 10, "y": 20, "inputValues": {}},
                "unsaved": {"type": "log", "x": 80, "y": 20, "inputValues": {}}
              },
              "connections": []
            }
            """);
        JsonObject saved = object("""
            {
              "nodes": {
                "start": {"type": "command", "x": 10, "y": 20, "inputValues": {}}
              },
              "connections": []
            }
            """);

        List<WorkspacePatch<JsonElement>> patches = FlowWorkspaceDocument.diffEditableWorkspace(mutated, saved);
        JsonObject applied = mutated.deepCopy();
        FlowWorkspaceDocument.apply(applied, patches);

        assertTrue(patches.stream().anyMatch(patch -> "remove".equals(patch.op()) && "/nodes/unsaved".equals(patch.path())));
        assertEquals(FlowWorkspaceDocument.editableWorkspace(saved), FlowWorkspaceDocument.editableWorkspace(applied));
    }

    @Test
    void malformedFunctionInputIdentityReplacesTheCollectionWithoutNumericPaths() {
        JsonObject before = object("""
            {"functionInputs":[{"name":"Before","type":"string"}]}
            """);
        JsonObject after = object("""
            {"functionInputs":[{"name":"After","type":"string"}]}
            """);

        List<WorkspacePatch<JsonElement>> patches = FlowWorkspaceDocument.diff(before, after);

        assertEquals(1, patches.size());
        assertEquals("/functionInputs", patches.getFirst().path());
        JsonObject applied = before.deepCopy();
        FlowWorkspaceDocument.apply(applied, patches);
        assertEquals(after, applied);
    }

    @Test
    void duplicateFunctionOutputIdentityReplacesTheCollectionWithoutNumericPaths() {
        JsonObject before = object("""
            {"functionOutputs":[
              {"parameterId":"duplicate","name":"First"},
              {"parameterId":"duplicate","name":"Second"}
            ]}
            """);
        JsonObject after = object("""
            {"functionOutputs":[
              {"parameterId":"duplicate","name":"Updated First"},
              {"parameterId":"duplicate","name":"Updated Second"}
            ]}
            """);

        List<WorkspacePatch<JsonElement>> patches = FlowWorkspaceDocument.diff(before, after);

        assertEquals(1, patches.size());
        assertEquals("/functionOutputs", patches.getFirst().path());
        JsonObject applied = before.deepCopy();
        FlowWorkspaceDocument.apply(applied, patches);
        assertEquals(after, applied);
    }

    @Test
    void missingPassthroughIdentityReplacesTheCollectionWithoutNumericPaths() {
        JsonObject before = object("""
            {"editorPassthroughs":[{"nodeId":"node","inputPin":"before"}]}
            """);
        JsonObject after = object("""
            {"editorPassthroughs":[{"nodeId":"node","inputPin":"after"}]}
            """);

        List<WorkspacePatch<JsonElement>> patches = FlowWorkspaceDocument.diff(before, after);

        assertEquals(1, patches.size());
        assertEquals("/editorPassthroughs", patches.getFirst().path());
        JsonObject applied = before.deepCopy();
        FlowWorkspaceDocument.apply(applied, patches);
        assertEquals(after, applied);
    }

    @Test
    void stableFunctionParameterReorderingReplacesTheOrderedCollection() {
        JsonObject before = object("""
            {"functionInputs":[
              {"parameterId":"first","name":"First"},
              {"parameterId":"second","name":"Second"}
            ]}
            """);
        JsonObject after = object("""
            {"functionInputs":[
              {"parameterId":"second","name":"Second"},
              {"parameterId":"first","name":"First"}
            ]}
            """);

        List<WorkspacePatch<JsonElement>> patches = FlowWorkspaceDocument.diff(before, after);
        JsonObject applied = before.deepCopy();
        FlowWorkspaceDocument.apply(applied, patches);

        assertEquals(1, patches.size());
        assertEquals("/functionInputs", patches.getFirst().path());
        assertEquals(after, applied);
    }

    @Test
    void invalidBatchLeavesTheLegacyProjectionUnchanged() {
        JsonObject document = object("""
            {"nodes":{"first":{"x":10}},"connections":[]}
            """);
        JsonObject original = document.deepCopy();
        List<WorkspacePatch<JsonElement>> patches = List.of(
            new WorkspacePatch<>("set", "/nodes/first/x", JsonParser.parseString("20")),
            new WorkspacePatch<>("set", "/connections/0/source", JsonParser.parseString("\"invalid\"")));

        assertThrows(IllegalArgumentException.class, () -> FlowWorkspaceDocument.apply(document, patches));
        assertEquals(original, document);
    }

    @Test
    void rebaseDropsACommittedLostEchoRemove() {
        JsonObject original = object("""
            {"nodes":{"remove":{"type":"log","x":10}},"connections":[]}
            """);
        JsonObject desired = object("""
            {"nodes":{},"connections":[]}
            """);
        JsonObject latest = desired.deepCopy();

        assertEquals(latest, FlowWorkspaceDocument.rebase(original, desired, latest));
    }

    @Test
    void rebaseTreatsCommittedArrayAndScalarEchoesAsAlreadyApplied() {
        JsonObject original = object("""
            {"nodes":{"first":{"type":"log","x":10}},"connections":[
              {"sourceNodeId":"a","sourcePin":"out","targetNodeId":"b","targetPin":"in"}
            ]}
            """);
        JsonObject desired = object("""
            {"nodes":{"first":{"type":"log","x":20}},"connections":[]}
            """);

        assertEquals(desired, FlowWorkspaceDocument.rebase(original, desired, desired));
    }

    @Test
    void rebasePreservesARejectedLocalAddWhenTheSnapshotDidNotApplyIt() {
        JsonObject original = object("""
            {"nodes":{},"connections":[]}
            """);
        JsonObject desired = object("""
            {"nodes":{"new":{"type":"log","x":10}},"connections":[]}
            """);
        JsonObject latest = object("""
            {"nodes":{"remote":{"type":"delay","x":20}},"connections":[]}
            """);

        JsonObject rebased = FlowWorkspaceDocument.rebase(original, desired, latest);

        assertEquals(desired.getAsJsonObject("nodes").get("new"), rebased.getAsJsonObject("nodes").get("new"));
        assertEquals(latest.getAsJsonObject("nodes").get("remote"), rebased.getAsJsonObject("nodes").get("remote"));
    }

    @Test
    void rebaseReportsTheStableConflictPath() {
        JsonObject original = object("""
            {"nodes":{"first":{"type":"log","x":10}},"connections":[]}
            """);
        JsonObject desired = object("""
            {"nodes":{"first":{"type":"log","x":20}},"connections":[]}
            """);
        JsonObject latest = object("""
            {"nodes":{"first":{"type":"log","x":30}},"connections":[]}
            """);

        FlowWorkspaceDocument.RebaseConflictException conflict = assertThrows(
            FlowWorkspaceDocument.RebaseConflictException.class,
            () -> FlowWorkspaceDocument.rebase(original, desired, latest));

        assertEquals("/nodes/first/x", conflict.path());
    }

    @Test
    void reapplyKeepsTheLocalEditableDraftAndAuthoritativeMetadata() {
        JsonObject desired = object("""
            {"resourceRevision":4,"resourceHash":"local","nodes":{"first":{"type":"log","x":20}},"connections":[]}
            """);
        JsonObject latest = object("""
            {"resourceRevision":5,"resourceHash":"remote","nodes":{"first":{"type":"delay","x":30}},"connections":[],"enabled":true}
            """);

        JsonObject reapplied = FlowWorkspaceDocument.reapply(desired, latest);

        assertEquals(5, reapplied.get("resourceRevision").getAsInt());
        assertEquals("remote", reapplied.get("resourceHash").getAsString());
        assertEquals(desired.getAsJsonObject("nodes"), reapplied.getAsJsonObject("nodes"));
        assertTrue(reapplied.get("enabled").getAsBoolean());
    }

    private JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
