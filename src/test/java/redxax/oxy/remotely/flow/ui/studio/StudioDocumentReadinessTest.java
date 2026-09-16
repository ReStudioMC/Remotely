package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioDocumentReadinessTest {
    @Test
    void editorReadyRequiresEveryPublishedSessionInvariant() {
        assertTrue(new StudioDocument.EditorReadiness(true, true, true, true, 4L, "topology").ready());
        assertFalse(new StudioDocument.EditorReadiness(false, true, true, true, 4L, "topology").ready());
        assertFalse(new StudioDocument.EditorReadiness(true, false, true, true, 4L, "topology").ready());
        assertFalse(new StudioDocument.EditorReadiness(true, true, false, true, 4L, "topology").ready());
        assertFalse(new StudioDocument.EditorReadiness(true, true, true, false, 4L, "topology").ready());
        assertFalse(new StudioDocument.EditorReadiness(true, true, true, true, 0L, "topology").ready());
        assertFalse(new StudioDocument.EditorReadiness(true, true, true, true, 4L, "").ready());
    }
}
