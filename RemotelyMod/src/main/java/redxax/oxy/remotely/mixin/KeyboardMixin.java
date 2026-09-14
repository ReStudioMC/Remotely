package redxax.oxy.remotely.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
//#if MC >= 1.21.9 || MC >= 26.1
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.adapters.ReScreenWrapper;
import redxax.oxy.remotely.config.Config;
import redxax.oxy.remotely.rematrix.mc.RematrixScreen;
import redxax.oxy.remotely.resync.bridge.ReSyncVanillaBridgeManager;
import redxax.oxy.remotely.util.InitializationManager;
import restudio.rescreen.platform.input.ReInputEventFactory;
import restudio.rescreen.platform.input.GlfwInputTranslator;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.ui.core.ScreenManager;

@Mixin(value = KeyboardHandler.class)
public class KeyboardMixin {

    @Unique
    private final Minecraft client = Minecraft.getInstance();

    @Unique
    private boolean remotely$toggleDown;

    @Unique
    private boolean remotely$skipToggleChar;

    @Unique
    private boolean remotely$resyncKeyDown;

    @Unique
    private long remotely$skipToggleCharUntil;

    @Unique
    private boolean remotely$shouldToggle(int key, int modifiers) {
        ReKeyEvent event = remotely$keyEvent(key, modifiers);
        return event.modifiers().alt() && !event.modifiers().shift() && !event.modifiers().control() && event.key() == ReKey.X;
    }

    @Unique
    private boolean remotely$shouldOpenReSync(int key, int modifiers) {
        ReKeyEvent event = remotely$keyEvent(key, modifiers);
        ReKey configuredKey = GlfwInputTranslator.key(Config.resyncKeyCode);
        return event.modifiers().alt() && !event.modifiers().shift() && !event.modifiers().control() && event.key() == configuredKey;
    }

    @Unique
    private boolean remotely$isToggleKey(int key) {
        return remotely$keyEvent(key, 0).key() == ReKey.X;
    }

    @Unique
    private ReKeyEvent remotely$keyEvent(int key, int modifiers) {
        return ReInputEventFactory.keyPressed(this, null, key, 0, modifiers, false);
    }

    @Unique
    private boolean remotely$isToggleChar(int codepoint) {
        return codepoint == 'x' || codepoint == 'X';
    }

    @Unique
    private boolean remotely$toggleScreen() {
        //#if MC >= 26.2
        if (client.gui.screen() instanceof RematrixScreen) {
            return ReScreenWrapper.toggleScreen();
        }
        if (ReScreenWrapper.toggleScreen()) {
            return true;
        }
        InitializationManager.ensureInitialized();
        if (RemotelyClient.INSTANCE == null) {
            return false;
        }
        RemotelyClient.INSTANCE.openServerManager(client.gui.screen());
        return true;
        //#else
        //$$ if (client.screen instanceof RematrixScreen) {
        //$$     return ReScreenWrapper.toggleScreen();
        //$$ }
        //$$ if (ReScreenWrapper.toggleScreen()) {
        //$$     return true;
        //$$ }
        //$$ InitializationManager.ensureInitialized();
        //$$ if (RemotelyClient.INSTANCE == null) {
        //$$     return false;
        //$$ }
        //$$ RemotelyClient.INSTANCE.openServerManager(client.screen);
        //$$ return true;
        //#endif
    }

    @Unique
    private void remotely$skipNextToggleChar() {
        remotely$skipToggleChar = true;
        remotely$skipToggleCharUntil = System.nanoTime() + 250_000_000L;
    }

    @Unique
    private void remotely$clearSkipToggleCharIfExpired() {
        if (remotely$skipToggleChar && System.nanoTime() > remotely$skipToggleCharUntil) {
            remotely$skipToggleChar = false;
        }
    }

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    //#if MC >= 1.21.9 || MC >= 26.1
    private void onKey(long l, int i, KeyEvent keyEvent, CallbackInfo ci) {
        if (remotely$shouldToggle(keyEvent.key(), keyEvent.modifiers())) {
            if (i == InputConstants.PRESS) {
                remotely$toggleDown = remotely$toggleScreen();
                remotely$skipNextToggleChar();
            } else if (i == InputConstants.RELEASE) {
                remotely$toggleDown = false;
            }
            ci.cancel();
            return;
        }
        if (remotely$shouldOpenReSync(keyEvent.key(), keyEvent.modifiers())) {
            if (i == InputConstants.RELEASE) {
                remotely$resyncKeyDown = false;
                ci.cancel();
                return;
            }
            if (!remotely$resyncKeyDown) {
                remotely$resyncKeyDown = true;
                ReSyncVanillaBridgeManager.getInstance().openStudioFromKey();
            }
            ci.cancel();
            return;
        }
        if (remotely$toggleDown && remotely$isToggleKey(keyEvent.key())) {
            if (i == InputConstants.RELEASE) {
                remotely$toggleDown = false;
            }
            ci.cancel();
            return;
        }
        //#if MC >= 26.3
        if (remotely$handlePinnedWindowKey(i, keyEvent.key(), keyEvent.keycode(), keyEvent.modifiers())) {
        //#else
        //$$ if (remotely$handlePinnedWindowKey(i, keyEvent.key(), keyEvent.scancode(), keyEvent.modifiers())) {
        //#endif
            ci.cancel();
            return;
        }
        ReKeyEvent event = remotely$keyEvent(keyEvent.key(), keyEvent.modifiers());
        if (i == InputConstants.PRESS && event.modifiers().control() && event.key() == ReKey.B) {
            //#if MC >= 26.2
            if (client.gui.screen() == null) return;
            client.gui.screen().keyPressed(keyEvent);
            ci.cancel();
            //#else
            //$$ if (client.screen == null) return;
            //$$ client.screen.keyPressed(keyEvent);
            //$$ ci.cancel();
            //#endif
        }
    }
    //#else
    //$$ private void onKey(long l, int i, int j, int k, int m, CallbackInfo ci) {
    //$$     if (remotely$shouldToggle(i, m)) {
    //$$         if (k == InputConstants.PRESS) {
    //$$             remotely$toggleDown = remotely$toggleScreen();
    //$$             remotely$skipNextToggleChar();
    //$$         } else if (k == InputConstants.RELEASE) {
    //$$             remotely$toggleDown = false;
    //$$         }
    //$$         ci.cancel();
    //$$         return;
    //$$     }
    //$$     if (remotely$shouldOpenReSync(i, m)) {
    //$$         if (k == InputConstants.RELEASE) {
    //$$             remotely$resyncKeyDown = false;
    //$$             ci.cancel();
    //$$             return;
    //$$         }
    //$$         if (!remotely$resyncKeyDown) {
    //$$             remotely$resyncKeyDown = true;
    //$$             ReSyncVanillaBridgeManager.getInstance().openStudioFromKey();
    //$$         }
    //$$         ci.cancel();
    //$$         return;
    //$$     }
    //$$     if (remotely$toggleDown && remotely$isToggleKey(i)) {
    //$$         if (k == InputConstants.RELEASE) {
    //$$             remotely$toggleDown = false;
    //$$         }
    //$$         ci.cancel();
    //$$         return;
    //$$     }
    //$$     if (remotely$handlePinnedWindowKey(k, i, j, m)) {
    //$$         ci.cancel();
    //$$         return;
    //$$     }
    //$$     if (((m & InputConstants.MOD_CONTROL) != 0) && i == InputConstants.KEY_B && k == InputConstants.PRESS) {
    //$$         if (client.screen == null) return;
    //$$         client.screen.keyPressed(i, j, k);
    //$$         ci.cancel();
    //$$     }
    //$$ }
    //#endif

    @Unique
    private boolean remotely$handlePinnedWindowKey(int action, int key, int scanCode, int modifiers) {
        ScreenManager manager = ScreenManager.getInstance();
        if (action == InputConstants.RELEASE) {
            return manager.keyReleasedPinnedInGame(ReInputEventFactory.keyReleased(this, manager.getDesktopWindowsOverlay(), key, scanCode, modifiers));
        }
        if (action == InputConstants.PRESS || action == InputConstants.REPEAT) {
            return manager.keyPressedPinnedInGame(ReInputEventFactory.keyPressed(this, manager.getDesktopWindowsOverlay(), key, scanCode, modifiers, action == InputConstants.REPEAT));
        }
        return false;
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    //#if MC >= 1.21.9 || MC >= 26.1
    private void onChar(long l, CharacterEvent characterEvent, CallbackInfo ci) {
        remotely$clearSkipToggleCharIfExpired();
        if (remotely$skipToggleChar && remotely$isToggleChar(characterEvent.codepoint())) {
            remotely$skipToggleChar = false;
            ci.cancel();
        }
    }
    //#else
    //$$ private void onChar(long l, int i, int j, CallbackInfo ci) {
    //$$     remotely$clearSkipToggleCharIfExpired();
    //$$     if (remotely$skipToggleChar && remotely$isToggleChar(i)) {
    //$$         remotely$skipToggleChar = false;
    //$$         ci.cancel();
    //$$     }
    //$$ }
    //#endif

}
