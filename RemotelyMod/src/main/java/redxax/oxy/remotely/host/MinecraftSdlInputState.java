//#if MC >= 26.3
package redxax.oxy.remotely.host;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import restudio.rescreen.platform.input.ReInputState;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReModifierState;

public final class MinecraftSdlInputState implements ReInputState {
    private final Minecraft minecraft;
    private final MinecraftSdlInputMapper mapper;

    public MinecraftSdlInputState(Minecraft minecraft, MinecraftSdlInputMapper mapper) {
        this.minecraft = minecraft;
        this.mapper = mapper;
    }

    @Override
    public boolean isKeyDown(ReKey key) {
        return isNativeKeyDown(mapper.nativeKey(key));
    }

    @Override
    public boolean isNativeKeyDown(int nativeKeyCode) {
        return nativeKeyCode >= 0 && InputConstants.isKeyDown(nativeKeyCode);
    }

    @Override
    public ReModifierState modifiers() {
        return new ReModifierState(
            isKeyDown(ReKey.LEFT_SHIFT) || isKeyDown(ReKey.RIGHT_SHIFT),
            isKeyDown(ReKey.LEFT_CONTROL) || isKeyDown(ReKey.RIGHT_CONTROL),
            isKeyDown(ReKey.LEFT_ALT) || isKeyDown(ReKey.RIGHT_ALT),
            isKeyDown(ReKey.LEFT_SUPER) || isKeyDown(ReKey.RIGHT_SUPER),
            false,
            false
        );
    }

    @Override
    public double mouseX() {
        return minecraft.mouseHandler.xpos();
    }

    @Override
    public double mouseY() {
        return minecraft.mouseHandler.ypos();
    }
}
//#endif
