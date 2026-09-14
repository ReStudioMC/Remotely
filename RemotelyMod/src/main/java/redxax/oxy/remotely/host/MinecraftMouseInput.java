package redxax.oxy.remotely.host;

//#if MC >= 26.3
import com.mojang.blaze3d.platform.InputConstants;
import restudio.rescreen.platform.input.ReMouseButton;
//#endif

public final class MinecraftMouseInput {
    private MinecraftMouseInput() {
    }

    public static int button(int button) {
        //#if MC >= 26.3
        if (button == InputConstants.MOUSE_BUTTON_LEFT) return ReMouseButton.LEFT.code();
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) return ReMouseButton.RIGHT.code();
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) return ReMouseButton.MIDDLE.code();
        if (button == InputConstants.MOUSE_BUTTON_4) return ReMouseButton.BACK.code();
        if (button == InputConstants.MOUSE_BUTTON_5) return ReMouseButton.FORWARD.code();
        //#endif
        return button;
    }
}
