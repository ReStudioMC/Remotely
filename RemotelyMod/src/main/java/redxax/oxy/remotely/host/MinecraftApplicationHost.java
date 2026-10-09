package redxax.oxy.remotely.host;

import net.minecraft.client.Minecraft;
//#if MC >= 1.21.11 || MC >= 26.1
import static net.minecraft.resources.Identifier.fromNamespaceAndPath;
//#endif
//#if MC < 1.21.11 && MC < 26.1
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.adapters.MinecraftTextRendererAdapter;
import redxax.oxy.remotely.adapters.ReScreenWrapper;
import redxax.oxy.remotely.rematrix.mc.RematrixScreen;
import restudio.rescreen.Main;
import restudio.rescreen.config.Config;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.platform.ClipboardHandler;
import restudio.rescreen.platform.CursorHandler;
import restudio.rescreen.platform.HostActionHandler;
import restudio.rescreen.platform.GalleryMediaProvider;
import restudio.rescreen.platform.desktop.DesktopGalleryMediaProvider;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.platform.MarkdownMedia;
import restudio.rescreen.platform.ReScreenRuntime;
import restudio.rescreen.platform.ScreenResourceHandler;
import restudio.rescreen.platform.assets.ImageAssetRegistry;
import restudio.rescreen.platform.desktop.DesktopImageAssetRegistry;
import restudio.rescreen.platform.desktop.DesktopMarkdownMedia;
//#if MC < 26.3
import restudio.rescreen.platform.input.GlfwInputMapper;
//#endif
import restudio.rescreen.platform.input.NativeInputMapper;
import restudio.rescreen.platform.input.ReInputEventFactory;
import restudio.rescreen.platform.input.ReInputState;
//#if MC < 26.3
import restudio.rescreen.platform.lwjgl.GlfwInputState;
//#endif
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.ResourceManager;
import restudio.rescreen.util.UiTasks;

import java.lang.reflect.Field;

public class MinecraftApplicationHost extends ReScreenApplicationHost {
    private final Minecraft mc = Minecraft.getInstance();
    private final MinecraftGameAssets gameAssets = new MinecraftNativeGameAssets();

    public MinecraftApplicationHost() {
        //#if MC >= 26.3
        ReInputEventFactory.setNativeMapper(new MinecraftSdlInputMapper());
        //#else
        //$$ ReInputEventFactory.setNativeMapper(new GlfwInputMapper());
        //#endif
        UiTasks.setUiThreadChecker(mc::isSameThread);
        ScreenManager.getInstance().installRuntime(new MinecraftReScreenRuntime());
    }

    @Override
    public boolean supportsDesktopIntegrations() {
        return true;
    }

    private long windowHandle() {
        //#if NEOFORGE && MC < 1.21.10
        //$$ return Minecraft.getInstance().getWindow().getWindow();
        //#else
        return Minecraft.getInstance().getWindow().handle();
        //#endif
    }

    private final class MinecraftReScreenRuntime implements ReScreenRuntime {
        private final ImageAssetRegistry imageAssets = new DesktopImageAssetRegistry(ResourceManager.getInstance());
        private final MarkdownMedia markdownMedia = new DesktopMarkdownMedia();
        private final ClipboardHandler clipboardHandler = new ClipboardHandler() {
            @Override
            public void setClipboard(String text) {
                mc.keyboardHandler.setClipboard(text);
            }

            @Override
            public String getClipboard() {
                return mc.keyboardHandler.getClipboard();
            }
        };
        private final HostActionHandler hostActionHandler = new MinecraftHostActionHandler();
        private final CursorHandler cursorHandler = new MinecraftCursorHandler(mc);
        //#if MC >= 26.3
        private final MinecraftSdlInputMapper nativeInputMapper = new MinecraftSdlInputMapper();
        private final ReInputState inputState = new MinecraftSdlInputState(mc, nativeInputMapper);
        //#else
        //$$ private final NativeInputMapper nativeInputMapper = new GlfwInputMapper();
        //$$ private final ReInputState inputState = new GlfwInputState(MinecraftApplicationHost.this::windowHandle);
        //#endif

        @Override
        public ImageAssetRegistry imageAssets() {
            return imageAssets;
        }

        @Override
        public GalleryMediaProvider galleryMedia() {
            return DesktopGalleryMediaProvider.INSTANCE;
        }

        @Override
        public MarkdownMedia markdownMedia() {
            return markdownMedia;
        }

        @Override
        public ClipboardHandler clipboardHandler() {
            return clipboardHandler;
        }

        @Override
        public HostActionHandler hostActions() {
            return hostActionHandler;
        }

        @Override
        public CursorHandler cursorHandler() {
            return cursorHandler;
        }

        @Override
        public ITextRenderer textRenderer() {
            return TextRenderer.getTr();
        }

        @Override
        public ReInputState inputState() {
            return inputState;
        }

        @Override
        public NativeInputMapper nativeInputMapper() {
            return nativeInputMapper;
        }

        @Override
        public ScreenResourceHandler screenResourceHandler() {
            return ScreenResourceHandler.NONE;
        }

        @Override
        public boolean supportsDesktopIntegrations() {
            return false;
        }

        @Override
        public boolean supportsWallpaper() {
            return false;
        }

        @Override
        public boolean hasMinecraftPanoramaBackground() {
            return true;
        }

        @Override
        public boolean windowFocused() {
            return cursorHandler.windowActive();
        }

        @Override
        public void execute(Runnable action) {
            if (action != null) {
                mc.execute(action);
            }
        }
    }

    @Override
    public void setScreen(Screen screen) {
        //#if MC >= 26.2
        if (screen == null) {
            RematrixScreen.closeExplicitly();
            mc.gui.setScreen(null);
            return;
        }
        if (Config.desktopMode) {
            ScreenManager sm = ScreenManager.getInstance();
            long handle = Minecraft.getInstance().getWindow().handle();
            sm.setWindowHandle(handle);
            try {
                Field f = Main.class.getDeclaredField("window");
                f.setAccessible(true);
                f.setLong(null, handle);
            } catch (Throwable ignored) {}
            if (mc.gui.screen() instanceof ReScreenWrapper) {
                sm.setScreen(screen);
                return;
            }
        }
        RematrixScreen.rememberMinecraftScreen(null);
        mc.gui.setScreen(new ReScreenWrapper(screen));
        //#else
        //$$ if (screen == null) {
        //$$     RematrixScreen.closeExplicitly();
        //$$     mc.setScreen(null);
        //$$     return;
        //$$ }
        //$$ if (Config.desktopMode) {
        //$$     ScreenManager sm = ScreenManager.getInstance();
            //#if NEOFORGE && MC < 1.21.10
            //$$ long handle = Minecraft.getInstance().getWindow().getWindow();
            //#else
            //$$ long handle = Minecraft.getInstance().getWindow().handle();
            //#endif
        //$$     sm.setWindowHandle(handle);
        //$$     try {
        //$$         Field f = Main.class.getDeclaredField("window");
        //$$         f.setAccessible(true);
        //$$         f.setLong(null, handle);
        //$$     } catch (Throwable ignored) {}
        //$$     if (mc.screen instanceof ReScreenWrapper) {
        //$$         sm.setScreen(screen);
        //$$         return;
        //$$     }
        //$$ }
        //$$ RematrixScreen.rememberMinecraftScreen(null);
        //$$ mc.setScreen(new ReScreenWrapper(screen));
        //#endif
    }

    @Override
    public Screen getCurrentScreen() {
        //#if MC >= 26.2
        if (mc.gui.screen() instanceof ReScreenWrapper wrapper) {
            return wrapper.getScreen();
        }
        return null;
        //#else
        //$$ if (mc.screen instanceof ReScreenWrapper wrapper) {
        //$$     return wrapper.getScreen();
        //$$ }
        //$$ return null;
        //#endif
    }

    @Override
    public void ensureTextRenderer() {
        if (RemotelyClient.tr != null) return;
        TextRenderer.setTextRendererAdapter(new MinecraftTextRendererAdapter());
        RemotelyClient.tr = TextRenderer.getTr();
    }

    @Override
    public MinecraftGameAssets getGameAssets() {
        return gameAssets;
    }

    @Override
    public Object getFontIdentifier(String namespace, String path) {
        String resolvedNamespace = (namespace == null || namespace.isBlank()) ? "minecraft" : namespace;
        String resolvedPath = path == null ? "" : path;
        //#if MC >= 1.21.11 || MC >= 26.1
        return fromNamespaceAndPath(resolvedNamespace, resolvedPath);
        //#endif
        //#if MC < 1.21.11 && MC >= 1.21.1
        //$$ return ResourceLocation.fromNamespaceAndPath(resolvedNamespace, resolvedPath);
        //#endif
        //#if MC < 1.21.1
        //$$ return new ResourceLocation(resolvedNamespace, resolvedPath);
        //#endif
    }

    @Override
    public void openParentScreen(Screen currentScreen, Object parent) {
        //#if MC >= 26.2
        if (parent instanceof net.minecraft.client.gui.screens.Screen) {
            RematrixScreen.closeExplicitly();
            mc.gui.setScreen(RematrixScreen.consumeRememberedMinecraftScreen((net.minecraft.client.gui.screens.Screen) parent));
        } else if (parent instanceof Screen) {
            setScreen((Screen) parent);
        } else {
            RematrixScreen.closeExplicitly();
            mc.gui.setScreen(RematrixScreen.consumeRememberedMinecraftScreen(null));
        }
        //#else
        //$$ if (parent instanceof net.minecraft.client.gui.screens.Screen) {
        //$$     RematrixScreen.closeExplicitly();
        //$$     mc.setScreen(RematrixScreen.consumeRememberedMinecraftScreen((net.minecraft.client.gui.screens.Screen) parent));
        //$$ } else if (parent instanceof Screen) {
        //$$     setScreen((Screen) parent);
        //$$ } else {
        //$$     RematrixScreen.closeExplicitly();
        //$$     mc.setScreen(RematrixScreen.consumeRememberedMinecraftScreen(null));
        //$$ }
        //#endif
    }

    @Override
    public boolean shouldCloseRootScreen() {
        return true;
    }

    @Override
    public String getGameVersion() {
        return Minecraft.getInstance().getLaunchedVersion();
    }

    @Override
    public void setClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    @Override
    public String getGameUserName() {
        return mc.getUser().getName();
    }

    @Override
    public String getGameUUID() {
        return mc.getUser().getProfileId().toString();
    }
}
