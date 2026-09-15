package redxax.oxy.remotely.rematrix.mc;

import net.minecraft.client.Minecraft;
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#endif
//#if MC >= 1.20.1 && MC < 26.1
//$$ import net.minecraft.client.gui.GuiGraphics;
//#endif
import net.minecraft.client.gui.screens.Screen;
//#if MC >= 26.1
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//#endif
//#if MC >= 1.21.9 && MC < 26.1
//$$ import net.minecraft.client.input.CharacterEvent;
//$$ import net.minecraft.client.input.KeyEvent;
//$$ import net.minecraft.client.input.MouseButtonEvent;
//#endif
import net.minecraft.network.chat.Component;
//#if MC >= 26.2 && MC < 26.3
//$$ import org.lwjgl.glfw.GLFW;
//#endif
import redxax.oxy.remotely.adapters.MinecraftDrawContextAdapter;
import redxax.oxy.remotely.host.MinecraftMouseInput;
import restudio.rescreen.config.Config;
import restudio.rescreen.platform.input.ReDropEvent;
import restudio.rescreen.platform.input.ReInputEventFactory;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.render.Render;
import restudio.rescreen.ui.core.ScreenManager;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

public class RematrixScreen extends Screen {
    private static restudio.rescreen.ui.core.Screen suspendedScreen;
    private static Screen suspendedMinecraftScreen;
    private static boolean shortcutHide;
    private static boolean userClose;
    private static boolean restoreQueued;
    private final restudio.rescreen.ui.core.Screen libScreen;
    private final ScreenManager sm = ScreenManager.getInstance();
    private long lastFrameTime;

    public RematrixScreen(restudio.rescreen.ui.core.Screen libScreen) {
        super(Component.literal("ReScreen " + libScreen.getClass().getSimpleName()));
        this.libScreen = libScreen;
        suspendedScreen = libScreen;
    }

    //#if MC >= 26.3
    @Override
    public void added() {
        super.added();
        Minecraft.getInstance().textInputManager().startTextInput(this);
    }
    //#endif

    @Override
    protected void init() {
        super.init();
        lastFrameTime = System.nanoTime();
        //#if MC >= 1.21.9 || MC >= 26.1
        long handle = Minecraft.getInstance().getWindow().handle();
        //#else
        //$$ long handle = Minecraft.getInstance().getWindow().getWindow();
        //#endif
        sm.setWindowHandle(handle);
        Config.animScaleFactor = Config.targetScaleFactor = Config.configManager.getGuiScale();
        try {
            Field f = restudio.rescreen.Main.class.getDeclaredField("window");
            f.setAccessible(true);
            f.setLong(null, handle);
        } catch (Throwable ignored) {}
        int windowWidth = Minecraft.getInstance().getWindow().getWidth();
        int windowHeight = Minecraft.getInstance().getWindow().getHeight();
        sm.updateDimensions(windowWidth, windowHeight);
        float mcScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        float persistedScale = Config.configManager != null ? Config.configManager.getGuiScale() : mcScale;
        sm.setGuiScale(persistedScale);
        boolean shouldSetScreen = true;
        restudio.rescreen.ui.core.Screen current = sm.getCurrentScreen();
        if (current != null && libScreen != null) {
            if (current == libScreen) {
                shouldSetScreen = false;
            }
        }
        if (shouldSetScreen) {
            sm.setScreen(libScreen);
        }
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        sm.filesDropped(new ReDropEvent(this, libScreen, System.nanoTime(), sm.inputState().modifiers(), paths));
    }

    //#if MC >= 26.1
    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float tickDelta) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, tickDelta);

        long now = System.nanoTime();
        float deltaSeconds = (float) ((now - lastFrameTime) / 1_000_000_000.0);
        lastFrameTime = now;

        if (deltaSeconds > 0.1f) deltaSeconds = 0.1f;
        if (deltaSeconds < 0.0f) deltaSeconds = 0.016f;

        int windowWidth = Minecraft.getInstance().getWindow().getWidth();
        int windowHeight = Minecraft.getInstance().getWindow().getHeight();
        sm.updateDimensions(windowWidth, windowHeight);
        float mcScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        float reScale = sm.getGuiScale();
        if (mcScale == 0 || reScale == 0) return;
        float renderScale = reScale / mcScale;
        float mouseScale = mcScale / reScale;

        RematrixContext ctx = new RematrixContext(guiGraphics, renderScale);
        MinecraftDrawContextAdapter libCtx = new MinecraftDrawContextAdapter(ctx);

        //#if MC >= 26.2 && MC < 26.3
        //$$ long previousContext = GLFW.glfwGetCurrentContext();
        //$$ long windowHandle = Minecraft.getInstance().getWindow().handle();
        //$$ if (previousContext != windowHandle) {
        //$$     GLFW.glfwMakeContextCurrent(windowHandle);
        //$$ }
        //#endif

        var pose = guiGraphics.pose();
        pose.pushMatrix();
        pose.scale(renderScale, renderScale);
        sm.render(libCtx, (int) (mouseX * mouseScale), (int) (mouseY * mouseScale), deltaSeconds);
        sm.processTasks();
        Config.deltaTime = deltaSeconds;
        Render.animatedScaling();
        pose.popMatrix();

        //#if MC >= 26.2 && MC < 26.3
        //$$ if (previousContext != windowHandle) {
        //$$     GLFW.glfwMakeContextCurrent(previousContext);
        //$$ }
        //#endif
    }
    //#endif

    //#if MC >= 1.20.1 && MC < 26.1
    //#if MC >= 1.20.6
    //$$ @Override
    //$$ public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float tickDelta) {
    //$$     if (Minecraft.getInstance().level == null) {
    //$$         super.renderBackground(guiGraphics, mouseX, mouseY, tickDelta);
    //$$     }
    //$$ }
    //#else
    //$$ @Override
    //$$ public void renderBackground(GuiGraphics guiGraphics) {
    //$$     if (Minecraft.getInstance().level == null) {
    //$$         super.renderBackground(guiGraphics);
    //$$     }
    //$$ }
    //#endif
    //$$
    //$$ @Override
    //$$ public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float tickDelta) {
        //#if MC >= 1.20.1 && MC < 1.20.6
        //$$ this.renderBackground(guiGraphics);
        //#endif
    //$$     super.render(guiGraphics, mouseX, mouseY, tickDelta);
    //$$
    //$$     long now = System.nanoTime();
    //$$     float deltaSeconds = (float) ((now - lastFrameTime) / 1_000_000_000.0);
    //$$     lastFrameTime = now;
    //$$
    //$$     if (deltaSeconds > 0.1f) deltaSeconds = 0.1f;
    //$$     if (deltaSeconds < 0.0f) deltaSeconds = 0.016f;
    //$$
    //$$     int windowWidth = Minecraft.getInstance().getWindow().getWidth();
    //$$     int windowHeight = Minecraft.getInstance().getWindow().getHeight();
    //$$     sm.updateDimensions(windowWidth, windowHeight);
    //$$     float mcScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
    //$$     float reScale = sm.getGuiScale();
    //$$     if (mcScale == 0 || reScale == 0) return;
    //$$     float renderScale = reScale / mcScale;
    //$$     float mouseScale = mcScale / reScale;
    //$$
    //$$     RematrixContext ctx = new RematrixContext(guiGraphics, renderScale);
    //$$     MinecraftDrawContextAdapter libCtx = new MinecraftDrawContextAdapter(ctx);
    //$$
    //$$
        //#if MC >= 1.21.9 || MC >= 26.1
        //$$ var pose = guiGraphics.pose();
        //$$ pose.pushMatrix();
        //$$ pose.scale(renderScale, renderScale);
        //#endif
        //#if MC >= 1.20.1 && MC < 1.21.6 && MC < 26.1
        //$$ var pose = guiGraphics.pose();
        //$$ pose.pushPose();
        //$$ pose.scale(renderScale, renderScale, 1f);
        //#endif
        //#if MC >= 1.21.6 && MC < 1.21.9 && MC < 26.1
        //$$ var pose = guiGraphics.pose();
        //$$ pose.pushMatrix();
        //$$ pose.scale(renderScale, renderScale);
        //#endif
    //$$     sm.render(libCtx, (int) (mouseX * mouseScale), (int) (mouseY * mouseScale), deltaSeconds);
    //$$     sm.processTasks();
    //$$     Config.deltaTime = deltaSeconds;
    //$$     Render.animatedScaling();
        //#if MC >= 1.21.9 || MC >= 26.1
        //$$ pose.popMatrix();
        //#endif
        //#if MC >= 1.20.1 && MC < 1.21.6 && MC < 26.1
        //$$ pose.popPose();
        //#endif
        //#if MC >= 1.21.6 && MC < 1.21.9 && MC < 26.1
        //$$ pose.popMatrix();
        //#endif
    //$$ }
    //#endif

    //#if MC < 1.20.1
    //$$ @Override
    //$$ public void render(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float tickDelta) {
    //$$     this.renderBackground(poseStack, mouseX, mouseY, tickDelta);
    //$$     super.render(poseStack, mouseX, mouseY, tickDelta);
    //$$
    //$$     long now = System.nanoTime();
    //$$     float deltaSeconds = (float) ((now - lastFrameTime) / 1_000_000_000.0);
    //$$     lastFrameTime = now;
    //$$
    //$$     if (deltaSeconds > 0.1f) deltaSeconds = 0.1f;
    //$$     if (deltaSeconds < 0.0f) deltaSeconds = 0.016f;
    //$$
    //$$     int windowWidth = Minecraft.getInstance().getWindow().getWidth();
    //$$     int windowHeight = Minecraft.getInstance().getWindow().getHeight();
    //$$     sm.updateDimensions(windowWidth, windowHeight);
    //$$     float mcScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
    //$$     float reScale = sm.getGuiScale();
    //$$     if (mcScale == 0 || reScale == 0) return;
    //$$     float renderScale = reScale / mcScale;
    //$$     float mouseScale = mcScale / reScale;
    //$$
    //$$     RematrixMcContext ctx = new RematrixMcContext(poseStack, renderScale);
    //$$     MinecraftDrawContextAdapter libCtx = new MinecraftDrawContextAdapter(ctx);
    //$$
    //$$     poseStack.pushPose();
    //$$     poseStack.scale(renderScale, renderScale, 1f);
    //$$     sm.render(libCtx, (int) (mouseX * mouseScale), (int) (mouseY * mouseScale), deltaSeconds);
    //$$     sm.processTasks();
    //$$     poseStack.popPose();
    //$$ }
    //#endif

    //#if MC >= 1.21.9 || MC >= 26.1
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        double sf = getInputScale();
        boolean handled = sm.mouseClicked(ReInputEventFactory.mouseEvent(sm, sm.getCurrentScreen(), ReMouseEvent.Action.PRESSED, event.x() * sf, event.y() * sf, MinecraftMouseInput.button(event.button()), currentModifiers(), 0, 0));
        return handled || super.mouseClicked(event, bl);
    }
    //#else
    //$$ @Override
    //$$ public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //$$     double sf = getInputScale();
    //$$     boolean handled = sm.mouseClicked(ReInputEventFactory.mouseEvent(sm, sm.getCurrentScreen(), ReMouseEvent.Action.PRESSED, mouseX * sf, mouseY * sf, MinecraftMouseInput.button(button), currentModifiers(), 0, 0));
    //$$     return handled || super.mouseClicked(mouseX, mouseY, button);
    //$$ }
    //#endif

    //#if MC >= 1.21.9 || MC >= 26.1
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double sf = getInputScale();
        boolean handled = sm.mouseReleased(ReInputEventFactory.mouseEvent(sm, sm.getCurrentScreen(), ReMouseEvent.Action.RELEASED, event.x() * sf, event.y() * sf, MinecraftMouseInput.button(event.button()), currentModifiers(), 0, 0));
        return handled || super.mouseReleased(event);
    }
    //#else
    //$$ @Override
    //$$ public boolean mouseReleased(double mouseX, double mouseY, int button) {
    //$$     double sf = getInputScale();
    //$$     boolean handled = sm.mouseReleased(ReInputEventFactory.mouseEvent(sm, sm.getCurrentScreen(), ReMouseEvent.Action.RELEASED, mouseX * sf, mouseY * sf, MinecraftMouseInput.button(button), currentModifiers(), 0, 0));
    //$$     return handled || super.mouseReleased(mouseX, mouseY, button);
    //$$ }
    //#endif

    //#if MC >= 1.21.9 || MC >= 26.1
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        double sf = getInputScale();
        boolean handled = sm.mouseDragged(ReInputEventFactory.mouseEvent(sm, sm.getCurrentScreen(), ReMouseEvent.Action.DRAGGED, event.x() * sf, event.y() * sf, MinecraftMouseInput.button(event.button()), currentModifiers(), deltaX * sf, deltaY * sf));
        return handled || super.mouseDragged(event, deltaX, deltaY);
    }
    //#else
    //$$ @Override
    //$$ public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
    //$$     double sf = getInputScale();
    //$$     boolean handled = sm.mouseDragged(ReInputEventFactory.mouseEvent(sm, sm.getCurrentScreen(), ReMouseEvent.Action.DRAGGED, mouseX * sf, mouseY * sf, MinecraftMouseInput.button(button), currentModifiers(), deltaX * sf, deltaY * sf));
    //$$     return handled || super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    //$$ }
    //#endif

    //#if MC >= 1.20.3
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (scaleScroll(verticalAmount)) {
            return true;
        }
        double sf = getInputScale();
        boolean handled = sm.mouseScrolled(ReInputEventFactory.scrollEvent(sm, sm.getCurrentScreen(), mouseX * sf, mouseY * sf, horizontalAmount, verticalAmount, currentModifiers()));
        return handled || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
    //#endif
    //#if MC < 1.20.3
    //$$ @Override
    //$$ public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
    //$$     if (scaleScroll(amount)) {
    //$$         return true;
    //$$     }
    //$$     double sf = getInputScale();
    //$$     boolean handled = sm.mouseScrolled(ReInputEventFactory.scrollEvent(sm, sm.getCurrentScreen(), mouseX * sf, mouseY * sf, 0.0, amount, currentModifiers()));
    //$$     return handled || super.mouseScrolled(mouseX, mouseY, amount);
    //$$ }
    //#endif

    //#if MC >= 1.21.9 || MC >= 26.1
    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        //#if MC >= 26.3
        ReKeyEvent event = ReInputEventFactory.keyPressed(sm, sm.getCurrentScreen(), keyEvent.key(), keyEvent.keycode(), keyEvent.modifiers(), false);
        //#else
        //$$ ReKeyEvent event = ReInputEventFactory.keyPressed(sm, sm.getCurrentScreen(), keyEvent.key(), keyEvent.scancode(), keyEvent.modifiers(), false);
        //#endif
        if (scaleScroll(keyEvent.key(), keyEvent.modifiers())) {
            return true;
        }
        boolean handled = sm.keyPressedBeforeHostFallback(event);
        if (handled) {
            return true;
        }
        if (event.key() == ReKey.ESCAPE && Config.desktopMode) {
            closeDesktopSuperScreen();
            return true;
        }
        if (event.key() == ReKey.ESCAPE) {
            return true;
        }
        return super.keyPressed(keyEvent);
    }
    //#else
    //$$ @Override
    //$$ public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //$$     if (scaleScroll(keyCode, modifiers)) {
    //$$         return true;
    //$$     }
    //$$     ReKeyEvent event = ReInputEventFactory.keyPressed(sm, sm.getCurrentScreen(), keyCode, scanCode, modifiers, false);
    //$$     boolean handled = sm.keyPressedBeforeHostFallback(event);
    //$$     if (handled) {
    //$$         return true;
    //$$     }
    //$$     if (event.key() == ReKey.ESCAPE && Config.desktopMode) {
    //$$         closeDesktopSuperScreen();
    //$$         return true;
    //$$     }
    //$$     if (event.key() == ReKey.ESCAPE) {
    //$$         return true;
    //$$     }
    //$$     return super.keyPressed(keyCode, scanCode, modifiers);
    //$$ }
    //#endif

    //#if MC >= 26.1
    @Override
    public boolean charTyped(CharacterEvent characterEvent) {
        int codepoint = characterEvent.codepoint();
        boolean handled = sm.textInput(ReInputEventFactory.textInput(sm, sm.getCurrentScreen(), codepoint, currentModifiers()));
        return handled || super.charTyped(characterEvent);
    }
    //#elseif MC >= 1.21.9
    //$$ @Override
    //$$ public boolean charTyped(CharacterEvent characterEvent) {
    //$$     int codepoint = characterEvent.codepoint();
    //$$     boolean handled = sm.textInput(ReInputEventFactory.textInput(sm, sm.getCurrentScreen(), codepoint, currentModifiers()));
    //$$     return handled || super.charTyped(characterEvent);
    //$$ }
    //#else
    //$$ @Override
    //$$ public boolean charTyped(char chr, int modifiers) {
    //$$     boolean handled = sm.textInput(ReInputEventFactory.textInput(sm, sm.getCurrentScreen(), chr, modifiers));
    //$$     return handled || super.charTyped(chr, modifiers);
    //$$ }
    //#endif

    //#if MC >= 1.21.9 || MC >= 26.1
    @Override
    public boolean keyReleased(KeyEvent keyEvent) {
        //#if MC >= 26.3
        boolean handled = sm.keyReleased(ReInputEventFactory.keyReleased(sm, sm.getCurrentScreen(), keyEvent.key(), keyEvent.keycode(), keyEvent.modifiers()));
        //#else
        //$$ boolean handled = sm.keyReleased(ReInputEventFactory.keyReleased(sm, sm.getCurrentScreen(), keyEvent.key(), keyEvent.scancode(), keyEvent.modifiers()));
        //#endif
        return handled || super.keyReleased(keyEvent);
    }
    //#else
    //$$ @Override
    //$$ public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
    //$$     boolean handled = sm.keyReleased(ReInputEventFactory.keyReleased(sm, sm.getCurrentScreen(), keyCode, scanCode, modifiers));
    //$$     return handled || super.keyReleased(keyCode, scanCode, modifiers);
    //$$ }
    //#endif

    @Override
    public void onClose() {
        closeExplicitly();
        super.onClose();
    }

    @Override
    public void removed() {
        super.removed();
        if (shortcutHide) {
            shortcutHide = false;
            return;
        }
        if (!userClose) {
            reopenAfterMinecraftClose();
            return;
        }
        restudio.rescreen.ui.core.Screen current = sm.getCurrentScreen();
        if (current != null) current.removed();
        sm.setScreen(null);
        suspendedScreen = null;
        userClose = false;
        restoreQueued = false;
    }

    public void onDisplayed() {
    }

    public restudio.rescreen.ui.core.Screen getScreen() {
        return libScreen;
    }

    public static boolean toggleScreen() {
        Minecraft mc = Minecraft.getInstance();
        //#if MC >= 26.2
        if (mc.gui.screen() instanceof RematrixScreen wrapper) {
            ScreenManager sm = ScreenManager.getInstance();
            var target = Config.desktopMode ? sm.getDesktopSuperScreen() : sm.getCurrentScreen();
            if (target == null) target = sm.getCurrentScreen();
            suspendedScreen = target != null ? target : wrapper.getScreen();
            Screen restoreScreen = suspendedMinecraftScreen;
            suspendedMinecraftScreen = null;
            shortcutHide = true;
            mc.gui.setScreen(restoreScreen);
            return true;
        }
        if (suspendedScreen == null) return false;
        suspendedMinecraftScreen = mc.gui.screen();
        mc.gui.setScreen(new RematrixScreen(suspendedScreen));
        return true;
        //#else
        //$$ if (mc.screen instanceof RematrixScreen wrapper) {
        //$$     ScreenManager sm = ScreenManager.getInstance();
        //$$     var target = Config.desktopMode ? sm.getDesktopSuperScreen() : sm.getCurrentScreen();
        //$$     if (target == null) target = sm.getCurrentScreen();
        //$$     suspendedScreen = target != null ? target : wrapper.getScreen();
        //$$     Screen restoreScreen = suspendedMinecraftScreen;
        //$$     suspendedMinecraftScreen = null;
        //$$     shortcutHide = true;
        //$$     mc.setScreen(restoreScreen);
        //$$     return true;
        //$$ }
        //$$ if (suspendedScreen == null) return false;
        //$$ suspendedMinecraftScreen = mc.screen;
        //$$ mc.setScreen(new RematrixScreen(suspendedScreen));
        //$$ return true;
        //#endif
    }

    public static void closeExplicitly() {
        Minecraft mc = Minecraft.getInstance();
        //#if MC >= 26.2
        userClose = mc.gui.screen() instanceof RematrixScreen;
        shortcutHide = false;
        //#else
        //$$ userClose = mc.screen instanceof RematrixScreen;
        //$$ shortcutHide = false;
        //#endif
    }

    public static boolean shouldBlockMinecraftClose() {
        return !userClose && !shortcutHide;
    }

    public static void rememberMinecraftScreen(Screen screen) {
        suspendedMinecraftScreen = screen;
    }

    public static Screen consumeRememberedMinecraftScreen(Screen fallback) {
        Screen screen = suspendedMinecraftScreen;
        suspendedMinecraftScreen = null;
        return screen != null ? screen : fallback;
    }

    private void closeDesktopSuperScreen() {
        restudio.rescreen.ui.core.Screen superScreen = sm.getDesktopSuperScreen();
        if (superScreen != null) {
            superScreen.close();
        }
    }

    private boolean scaleScroll(double verticalAmount) {
        if (!isControlDown()) {
            return false;
        }
        Config.targetScaleFactor = Math.max(1f, Math.min(4f, Config.targetScaleFactor + (verticalAmount > 0 ? 1f : -1f)));
        Config.globalScaleFactor = Config.targetScaleFactor;
        return true;
    }

    private boolean scaleScroll(int keyCode, int modifiers) {
        ReKeyEvent event = ReInputEventFactory.keyPressed(this, libScreen, keyCode, 0, modifiers, false);
        return scaleScroll(event.key(), event.modifiers());
    }

    private boolean scaleScroll(ReKey key, ReModifierState modifiers) {
        if (!modifiers.control()) {
            return false;
        }
        if (key == ReKey.KP_ADD || key == ReKey.EQUAL) {
            Config.targetScaleFactor = Math.max(1f, Math.min(4f, Config.targetScaleFactor + 1f));
            Config.globalScaleFactor = Config.targetScaleFactor;
            return true;
        }
        if (key == ReKey.KP_SUBTRACT || key == ReKey.MINUS) {
            Config.targetScaleFactor = Math.max(1f, Math.min(4f, Config.targetScaleFactor - 1f));
            Config.globalScaleFactor = Config.targetScaleFactor;
            return true;
        }
        return false;
    }

    private boolean isControlDown() {
        return sm.inputState().modifiers().control();
    }

    private int currentModifiers() {
        return ReInputEventFactory.nativeModifiers(sm.inputState().modifiers());
    }

    private void reopenAfterMinecraftClose() {
        suspendedScreen = libScreen;
        if (restoreQueued) return;
        restoreQueued = true;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            restoreQueued = false;
            if (!shouldBlockMinecraftClose()) return;
            //#if MC >= 26.2
            if (mc.gui.screen() instanceof RematrixScreen) return;
            mc.gui.setScreen(this);
            //#else
            //$$ if (mc.screen instanceof RematrixScreen) return;
            //$$ mc.setScreen(this);
            //#endif
        });
    }

    private double getInputScale() {
        return RematrixScale.managerInputScale(Minecraft.getInstance());
    }
}
