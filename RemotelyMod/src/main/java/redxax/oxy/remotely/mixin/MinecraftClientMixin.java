package redxax.oxy.remotely.mixin;

//#if MC >= 26.2 && MC < 26.3
import java.util.concurrent.CompletableFuture;
//#endif
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if MC >= 26.2 && MC < 26.3
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import redxax.oxy.remotely.host.MinecraftNativeGameAssets;
//#endif
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.quickserver.QuickServerManager;
import redxax.oxy.remotely.rematrix.mc.RematrixScreen;
import redxax.oxy.remotely.resync.bridge.ReSyncVanillaBridgeManager;
import redxax.oxy.remotely.servers.ReProxyManager;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        QuickServerManager.shutdownAll();
        RemotelyClient.INSTANCE.shutdownAllTerminals();
        ReProxyManager.stopAll();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        ReSyncVanillaBridgeManager.getInstance().tick();
        QuickServerManager.clientTick();
    }

    //#if MC >= 26.2 && MC < 26.3
    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
    private void onResourcePacksReloaded(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        cir.getReturnValue().thenRun(MinecraftNativeGameAssets::resourcesReloaded);
    }
    //#endif

    //#if MC < 26.2
    //$$ @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    //$$ private void onSetScreen(Screen screen, CallbackInfo ci) {
    //$$     Minecraft client = (Minecraft) (Object) this;
    //$$     if (client.screen instanceof RematrixScreen && !(screen instanceof RematrixScreen)) {
    //$$         if (RematrixScreen.shouldBlockMinecraftClose()) {
    //$$             RematrixScreen.rememberMinecraftScreen(screen);
    //$$             ci.cancel();
    //$$         }
    //$$     }
    //$$ }
    //#endif
}
