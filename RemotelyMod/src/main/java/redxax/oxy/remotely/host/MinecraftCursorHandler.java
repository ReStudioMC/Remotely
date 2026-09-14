package redxax.oxy.remotely.host;

import net.minecraft.client.Minecraft;
//#if MC >= 26.3
import org.lwjgl.sdl.SDLMouse;
//#else
//$$ import org.lwjgl.glfw.GLFW;
//#endif
import restudio.rescreen.platform.CursorHandler;
import restudio.rescreen.platform.input.ReMouseButton;

public final class MinecraftCursorHandler implements CursorHandler {
    private final Minecraft minecraft;

    public MinecraftCursorHandler(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public boolean windowActive() {
        return windowHandle() != 0 && !minecraft.getWindow().isIconified() && minecraft.getWindow().isFocused();
    }

    @Override
    public boolean pointerAvailable() {
        return windowActive() && !minecraft.mouseHandler.isMouseGrabbed();
    }

    @Override
    public boolean isButtonDown(ReMouseButton button) {
        //#if MC >= 26.3
        int mask = switch (button) {
            case LEFT -> SDLMouse.SDL_BUTTON_LMASK;
            case RIGHT -> SDLMouse.SDL_BUTTON_RMASK;
            case MIDDLE -> SDLMouse.SDL_BUTTON_MMASK;
            case BACK -> SDLMouse.SDL_BUTTON_X1MASK;
            case FORWARD -> SDLMouse.SDL_BUTTON_X2MASK;
            default -> 0;
        };
        return mask != 0 && (SDLMouse.SDL_GetMouseState(null, null) & mask) != 0;
        //#else
        //$$ int nativeButton = switch (button) {
        //$$     case LEFT -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
        //$$     case RIGHT -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        //$$     case MIDDLE -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
        //$$     case BACK -> GLFW.GLFW_MOUSE_BUTTON_4;
        //$$     case FORWARD -> GLFW.GLFW_MOUSE_BUTTON_5;
        //$$     default -> -1;
        //$$ };
        //$$ return nativeButton >= 0 && GLFW.glfwGetMouseButton(windowHandle(), nativeButton) == GLFW.GLFW_PRESS;
        //#endif
    }

    @Override
    public void hideNativeCursor() {
        //#if MC >= 26.3
        if (windowHandle() != 0 && SDLMouse.SDL_CursorVisible()) {
            SDLMouse.SDL_HideCursor();
        }
        //#else
        //$$ long window = windowHandle();
        //$$ if (window != 0 && GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_HIDDEN) {
        //$$     GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        //$$ }
        //#endif
    }

    @Override
    public boolean isNativeCursorHidden() {
        //#if MC >= 26.3
        return windowHandle() != 0 && !SDLMouse.SDL_CursorVisible();
        //#else
        //$$ long window = windowHandle();
        //$$ return window != 0 && GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_HIDDEN;
        //#endif
    }

    @Override
    public void showNativeCursor() {
        //#if MC >= 26.3
        if (windowHandle() != 0 && !SDLMouse.SDL_CursorVisible()) {
            SDLMouse.SDL_ShowCursor();
        }
        //#else
        //$$ long window = windowHandle();
        //$$ if (window != 0 && GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_HIDDEN) {
        //$$     GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        //$$ }
        //#endif
    }

    private long windowHandle() {
        //#if NEOFORGE && MC < 1.21.10
        //$$ return minecraft.getWindow().getWindow();
        //#elseif MC >= 1.21.6 || MC >= 26.1
        return minecraft.getWindow().handle();
        //#else
        //$$ return minecraft.getWindow().getWindow();
        //#endif
    }
}
