package redxax.oxy.remotely.packcontent;

import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.instance.Instance;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmRemoteFileSystemProvider;
import restudio.rebase.ui.screens.editor.FileEditorScreen;
import restudio.rebase.ui.screens.explorer.FileExplorerScreen;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DesktopGlyphPreviewAccess implements GlyphPreviewAccess {
    private final Instance instance;
    private final FileSystemProvider fileSystem;
    private final Path workspaceRoot;
    private final NexoGlyphPreviewAccess access;

    public DesktopGlyphPreviewAccess(Instance instance, FileSystemProvider fileSystem, Path workspaceRoot) {
        this.instance = instance;
        this.fileSystem = fileSystem;
        this.workspaceRoot = workspaceRoot;
        RemoteFileSystemProvider remoteFiles = JvmRemoteFileSystemProvider.from(fileSystem);
        this.access = new NexoGlyphPreviewAccess(remoteFiles, RemotePath.of(workspaceRoot.toString()), new DesktopRuntime());
    }

    @Override
    public Async<Void> refresh() {
        return access.refresh();
    }

    @Override
    public List<Preview> resolveGlyphs(String text) {
        return access.resolveGlyphs(text);
    }

    @Override
    public Optional<Preview> resolveGlyph(String providerId, String glyphId, Integer index) {
        return access.resolveGlyph(providerId, glyphId, index);
    }

    @Override
    public Async<Image> loadImage(GlyphDefinition glyph) {
        return access.loadImage(glyph);
    }

    @Override
    public Async<Image> loadImage(GlyphDefinition glyph, Integer index) {
        return access.loadImage(glyph, index);
    }

    @Override
    public String sourceName(GlyphDefinition glyph) {
        return access.sourceName(glyph);
    }

    @Override
    public boolean openSource(GlyphDefinition glyph) {
        return access.openSource(glyph);
    }

    @Override
    public boolean openAsset(GlyphDefinition glyph) {
        return access.openAsset(glyph);
    }

    private final class DesktopRuntime implements NexoGlyphPreviewAccess.Runtime {
        private final Map<String, List<GlyphPreviewFrame>> loadedFrames = BrowserSafeState.map();
        private final Map<String, Async<List<GlyphPreviewFrame>>> frameLoads = BrowserSafeState.map();

        @Override
        public Async<Image> loadImage(GlyphDefinition glyph, Integer index) {
            String key = key(glyph, index);
            List<GlyphPreviewFrame> cached = loadedFrames.get(key);
            if (cached != null) return Async.completed(image(cached));
            Async<List<GlyphPreviewFrame>> load = frameLoads.get(key);
            if (load == null) {
                load = AsyncTools.supply(TaskSchedulers.current(), () -> loadFrames(glyph, index));
                Async<List<GlyphPreviewFrame>> active = frameLoads.putIfAbsent(key, load);
                if (active != null) {
                    load.cancel();
                    load = active;
                } else {
                    Async<List<GlyphPreviewFrame>> started = load;
                    load.whenComplete((frames, failure) -> {
                        frameLoads.remove(key, started);
                        if (failure == null && frames != null) loadedFrames.put(key, frames);
                    });
                }
            }
            return load.thenApply(this::image);
        }

        @Override
        public List<GlyphPreviewFrame> frames(GlyphDefinition glyph, Integer index) {
            List<GlyphPreviewFrame> frames = loadedFrames.get(key(glyph, index));
            return frames == null ? List.of() : frames;
        }

        @Override
        public boolean openSource(RemotePath path) {
            if (path == null) return false;
            Path target = Path.of(path.asString());
            ScreenManager.getInstance().execute(() -> {
                Screen parent = ScreenManager.getInstance().getCurrentScreen();
                FileEditorScreen.canOpen(fileSystem, target).thenAccept(can -> ScreenManager.getInstance().execute(() -> {
                    if (can) {
                        ScreenManager.getInstance().setScreen(new FileEditorScreen(parent, instance, fileSystem,
                                workspaceRoot, target, DesktopRemotelyPaths.appDir().resolve("data")));
                    } else {
                        new Notification("Open Failed", target.getFileName().toString(), Notification.Type.ERROR);
                    }
                }));
            });
            return true;
        }

        @Override
        public boolean openAsset(RemotePath path) {
            if (path == null) return false;
            Path target = Path.of(path.asString());
            Path parent = target.getParent() == null ? target : target.getParent();
            ScreenManager.getInstance().execute(() -> ScreenManager.getInstance().setScreen(new FileExplorerScreen(
                    ScreenManager.getInstance().getCurrentScreen(), instance, parent,
                    DesktopRemotelyPaths.appDir().resolve("data"), false, fileSystem)));
            return true;
        }

        @Override
        public void close() {
            for (Async<List<GlyphPreviewFrame>> load : frameLoads.values()) load.cancel();
            frameLoads.clear();
            loadedFrames.clear();
        }

        private List<GlyphPreviewFrame> loadFrames(GlyphDefinition glyph, Integer index) {
            if (glyph == null || glyph.assetRef() == null) return List.of();
            NexoGlyphCatalog.Grid grid = NexoGlyphCatalog.grid(glyph, index);
            PackContentContext context = new PackContentContext(instance, fileSystem, workspaceRoot, workspaceRoot, Map.of());
            try {
                return new DesktopGlyphFrameLoader(context).load(glyph.assetRef(), grid.rows(), grid.columns(), index);
            } catch (Exception ignored) {
                return List.of();
            }
        }

        private Image image(List<GlyphPreviewFrame> frames) {
            if (frames == null || frames.isEmpty()) return null;
            GlyphPreviewFrame frame = frames.getFirst();
            return frame == null ? null : new Image(frame.image(), frame.width(), frame.height());
        }

        private String key(GlyphDefinition glyph, Integer index) {
            GlyphAssetRef asset = glyph == null ? null : glyph.assetRef();
            String path = asset == null ? "" : asset.logicalPath();
            return path + "|" + (index == null ? "all" : index);
        }
    }
}
