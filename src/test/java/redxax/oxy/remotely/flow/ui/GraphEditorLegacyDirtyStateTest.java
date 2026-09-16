package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorLegacyDirtyStateTest {
    @Test
    void noMoveReleaseAndSelectionOnlyCaptureRemainClean() {
        AtomicReference<LegacyGraphState> state = new AtomicReference<>(snapshot(graph(10), Set.of()));
        StudioScreen.History<LegacyGraphState> history = history(state);
        history.clear();

        history.capture();
        state.set(snapshot(graph(10), Set.of("node")));

        assertFalse(isDirty(history));
    }

    @Test
    void rejectedLiteralAndDropCapturesRemainClean() {
        AtomicReference<LegacyGraphState> state = new AtomicReference<>(snapshot(graph(10), Set.of()));
        StudioScreen.History<LegacyGraphState> history = history(state);
        history.clear();

        history.capture();
        history.capture();

        assertFalse(isDirty(history));
    }

    @Test
    void realEditUndoAndSaveTrackSemanticCleanliness() {
        AtomicReference<LegacyGraphState> state = new AtomicReference<>(snapshot(graph(10), Set.of()));
        StudioScreen.History<LegacyGraphState> history = history(state);
        history.clear();
        history.capture();

        state.set(snapshot(graph(25), Set.of("node")));
        assertTrue(isDirty(history));

        assertTrue(history.undo());
        assertFalse(isDirty(history));

        history.capture();
        state.set(snapshot(graph(40), Set.of()));
        assertTrue(isDirty(history));
        history.markSaving(7L);
        history.markSaved(7L);
        assertFalse(isDirty(history));
    }

    @Test
    void serverManagedGraphIdentityDoesNotDirtyEditableState() {
        JsonObject saved = graph(10);
        JsonObject current = saved.deepCopy();
        current.addProperty("resourceRevision", 12L);
        current.addProperty("resourceHash", "next");
        current.addProperty("resourceMutationId", "mutation");

        assertTrue(GraphEditorScreen.editableGraphDocumentsEqual(current, saved));
    }

    private static StudioScreen.History<LegacyGraphState> history(AtomicReference<LegacyGraphState> state) {
        return new StudioScreen.History<>(() -> snapshot(state.get().document(), state.get().selection()), state::set, 20);
    }

    private static boolean isDirty(StudioScreen.History<LegacyGraphState> history) {
        return history.isDirty((current, saved) ->
            GraphEditorScreen.editableGraphDocumentsEqual(current.document(), saved.document()));
    }

    private static LegacyGraphState snapshot(JsonObject document, Set<String> selection) {
        return new LegacyGraphState(document.deepCopy(), Set.copyOf(selection));
    }

    private static JsonObject graph(int x) {
        JsonObject document = new JsonObject();
        document.addProperty("id", "flow");
        document.addProperty("resourceRevision", 1L);
        JsonObject node = new JsonObject();
        node.addProperty("id", "node");
        node.addProperty("x", x);
        node.addProperty("y", 20);
        JsonArray nodes = new JsonArray();
        nodes.add(node);
        document.add("nodes", nodes);
        document.add("connections", new JsonArray());
        return document;
    }

    private record LegacyGraphState(JsonObject document, Set<String> selection) {
    }
}
