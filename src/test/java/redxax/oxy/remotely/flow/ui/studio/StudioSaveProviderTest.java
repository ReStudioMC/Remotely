package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioSaveProviderTest {
    @Test
    void controlAndPlatformSaveShortcutsInvokeTheToolbarSaveContractExactlyOnce() {
        SaveView view = new SaveView();

        assertTrue(view.handleStudioSaveShortcut(key(ReKey.S, true, false)));
        assertEquals(1, view.requests);
        assertTrue(view.handleStudioSaveShortcut(key(ReKey.S, false, true)));
        assertEquals(2, view.requests);
    }

    @Test
    void unrelatedKeysDoNotInvokeSaveAndDirectStudioViewsUseTheSameProvider() {
        SaveView view = new SaveView();

        assertFalse(view.handleStudioSaveShortcut(key(ReKey.A, true, false)));
        assertFalse(view.handleStudioSaveShortcut(key(ReKey.S, false, false)));
        assertEquals(0, view.requests);
        assertTrue(view.requestSave());
        assertEquals(1, view.requests);
    }

    private static ReKeyEvent key(ReKey key, boolean control, boolean superKey) {
        return new ReKeyEvent(StudioSaveProviderTest.class, StudioSaveProviderTest.class, 1L,
            new ReModifierState(false, control, false, superKey, false, false), ReKeyEvent.Action.PRESSED,
            key, 0, 0, ReKeyLocation.STANDARD, false);
    }

    private static final class SaveView implements ReSyncStudioView, StudioSaveProvider {
        private int requests;

        @Override
        public boolean requestStudioSave() {
            requests++;
            return true;
        }

        @Override
        public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        }
    }
}
