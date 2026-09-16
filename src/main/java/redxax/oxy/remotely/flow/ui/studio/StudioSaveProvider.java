package redxax.oxy.remotely.flow.ui.studio;

import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;

public interface StudioSaveProvider {
    boolean requestStudioSave();

    default boolean handleStudioSaveShortcut(ReKeyEvent event) {
        if (!isStudioSaveShortcut(event)) {
            return false;
        }
        requestStudioSave();
        return true;
    }

    static boolean isStudioSaveShortcut(ReKeyEvent event) {
        return event != null && event.key() == ReKey.S
            && (event.modifiers().control() || event.modifiers().superKey());
    }
}
