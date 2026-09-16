package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioHistoryTest {
    @Test
    void undoKeepsChangesRebasedFromOtherCollaborators() {
        AtomicInteger value = new AtomicInteger();
        StudioScreen.History<Integer> history = new StudioScreen.History<>(value::get, value::set, 20);
        history.capture();
        value.set(1);

        value.addAndGet(10);
        history.rebase(previous -> previous + 10);

        assertTrue(history.undo());
        assertEquals(10, value.get());
        assertTrue(history.redo());
        assertEquals(11, value.get());
    }

    @Test
    void branchingBeforeTheSavedPointRemainsDirty() {
        AtomicInteger value = new AtomicInteger();
        StudioScreen.History<Integer> history = new StudioScreen.History<>(value::get, value::set, 20);
        history.clear();
        history.capture();
        value.set(1);
        history.markSaved();

        assertFalse(history.isDirty());
        assertTrue(history.undo());
        history.capture();
        value.set(2);

        assertTrue(history.isDirty());
    }

    @Test
    void discardKeepsChangesFromOtherCollaborators() {
        AtomicInteger value = new AtomicInteger();
        StudioScreen.History<Integer> history = new StudioScreen.History<>(value::get, value::set, 20);
        history.clear();
        history.capture();
        value.set(1);

        value.addAndGet(10);
        history.rebase(previous -> previous + 10);
        history.discardChanges();

        assertEquals(10, value.get());
        assertFalse(history.isDirty());
    }

    @Test
    void saveAcknowledgementKeepsNewerLocalEditsDirty() {
        AtomicInteger value = new AtomicInteger();
        StudioScreen.History<Integer> history = new StudioScreen.History<>(value::get, value::set, 20);
        history.clear();
        history.capture();
        value.set(1);
        history.markSaving(4L);
        history.capture();
        value.set(2);

        history.markSaved(4L);

        assertTrue(history.isDirty());
        history.discardChanges();
        assertEquals(1, value.get());
    }

    @Test
    void savePointKeepsChangesFromOtherCollaborators() {
        AtomicInteger value = new AtomicInteger();
        StudioScreen.History<Integer> history = new StudioScreen.History<>(value::get, value::set, 20);
        history.clear();
        history.capture();
        value.set(1);
        history.markSaving(7L);
        value.addAndGet(10);
        history.rebase(previous -> previous + 10);

        history.markSaved(7L);
        history.capture();
        value.set(12);
        history.discardChanges();

        assertEquals(11, value.get());
    }

    @Test
    void unknownSaveAcknowledgementDoesNotMarkCurrentChangesSaved() {
        AtomicInteger value = new AtomicInteger();
        StudioScreen.History<Integer> history = new StudioScreen.History<>(value::get, value::set, 20);
        history.clear();
        history.capture();
        value.set(1);

        history.markSaved(99L);

        assertTrue(history.isDirty());
        history.discardChanges();
        assertEquals(0, value.get());
    }

    @Test
    void semanticDirtyStateTracksUncapturedWidgetChangesAndIgnoresNoOps() {
        AtomicInteger value = new AtomicInteger();
        StudioScreen.History<Integer> history = new StudioScreen.History<>(value::get, value::set, 20);
        history.clear();
        history.capture();

        assertFalse(history.isDirty(Integer::equals));

        value.set(1);

        assertTrue(history.isDirty(Integer::equals));
        history.markSaved();
        assertFalse(history.isDirty(Integer::equals));
    }

    @Test
    void preparedRestoreFailureDoesNotAdvanceUndoOrRedoHistory() {
        AtomicInteger value = new AtomicInteger();
        StudioScreen.History<Integer> history = new StudioScreen.History<>(value::get, value::set, 20);
        history.clear();
        history.capture();
        value.set(1);
        Integer undo = history.peekUndo();

        assertThrows(IllegalStateException.class, () -> history.undoPrepared(undo, restored -> {
            value.set(restored);
            throw new IllegalStateException("restore failed");
        }));
        assertTrue(history.canUndo());
        assertFalse(history.canRedo());
        assertEquals(undo, history.peekUndo());

        value.set(1);
        assertTrue(history.undoPrepared(undo, value::set));
        assertEquals(0, value.get());
        Integer redo = history.peekRedo();

        assertThrows(IllegalStateException.class, () -> history.redoPrepared(redo, restored -> {
            value.set(restored);
            throw new IllegalStateException("restore failed");
        }));
        assertFalse(history.canUndo());
        assertTrue(history.canRedo());
        assertEquals(redo, history.peekRedo());

        value.set(0);
        assertTrue(history.redoPrepared(redo, value::set));
        assertEquals(1, value.get());
    }
}
