package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorSaveAuthorityFenceTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void authorityRotationRejectsLegacySnapshotAndLeavesDraftRetryable() {
        Object manager = new Object();
        Object client = new Object();
        GraphEditorScreen.GraphSaveAuthorityToken legacy = new GraphEditorScreen.GraphSaveAuthorityToken(
            GraphEditorScreen.GraphSaveAuthorityMode.LEGACY, "server", "flow", "draft", manager, client,
            "authority=LEGACY_COMPATIBILITY", null, -1L, "immutable-snapshot");
        GraphEditorScreen.GraphSaveAuthorityToken core = new GraphEditorScreen.GraphSaveAuthorityToken(
            GraphEditorScreen.GraphSaveAuthorityMode.CORE, "server", "flow", "draft", manager, client,
            "authority=TYPED_RECONCILIATION", new Object(), 4L, "immutable-snapshot");
        AtomicReference<GraphEditorScreen.GraphSaveAuthorityToken> current = new AtomicReference<>(legacy);
        AtomicBoolean dirty = new AtomicBoolean(true);
        AtomicBoolean legacyDispatched = new AtomicBoolean();

        current.set(core);
        Optional<Boolean> rejected = GraphEditorScreen.dispatchAuthorityFencedSave(legacy, current::get, () -> {
            legacyDispatched.set(true);
            dirty.set(false);
            return true;
        });

        assertTrue(rejected.isEmpty());
        assertFalse(legacyDispatched.get());
        assertTrue(dirty.get());

        current.set(legacy);
        Optional<Boolean> retried = GraphEditorScreen.dispatchAuthorityFencedSave(legacy, current::get, () -> {
            legacyDispatched.set(true);
            dirty.set(false);
            return true;
        });

        assertEquals(Optional.of(true), retried);
        assertTrue(legacyDispatched.get());
        assertFalse(dirty.get());
    }

    @Test
    void toolbarAndControlSaveUseTheSameSaveRoute() {
        SaveRouteEditor editor = new SaveRouteEditor();

        editor.toolbarSave();
        assertTrue(editor.keyPressed(new ReKeyEvent(editor, editor, 1L,
            new ReModifierState(false, true, false, false, false, false), ReKeyEvent.Action.PRESSED,
            ReKey.S, 0, 0, ReKeyLocation.STANDARD, false)));

        assertEquals(2, editor.saveCount);
        editor.removed();
    }

    private static final class SaveRouteEditor extends GraphEditorScreen {
        private int saveCount;

        private SaveRouteEditor() {
            super(graph(), "server", new Screen());
        }

        private void toolbarSave() {
            assertTrue(requestStudioSave());
        }

        @Override
        protected void saveGraph() {
            saveCount++;
        }

        private static FlowGraph graph() {
            FlowGraph graph = new FlowGraph();
            graph.setId("draft");
            graph.setResourceType("flow");
            return graph;
        }
    }
}
