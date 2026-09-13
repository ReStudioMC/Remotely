package redxax.oxy.remotely.packcontent;

import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rescreen.platform.Async;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class NexoContentProvider extends AbstractPackContentProvider implements GlyphContentProvider, PackAssetProvider {
    private final NexoGlyphCatalog catalog = new NexoGlyphCatalog();
    private final Map<String, List<GlyphPreviewFrame>> frameVariants = BrowserSafeState.map();
    private final Map<String, Async<List<GlyphPreviewFrame>>> frameLoads = BrowserSafeState.map();
    private Path root;

    @Override
    public String id() {
        return "nexo";
    }

    @Override
    public String displayName() {
        return "Nexo";
    }

    @Override
    public Async<Optional<Path>> detectRoot(PackContentContext context) {
        NexoGlyphLoader.Files files = files(context.fileSystem());
        return NexoGlyphLoader.detectRoot(files, RemotePath.of(context.workspaceRoot().toString()))
                .thenApply(path -> path.map(value -> Path.of(value.asString())));
    }

    @Override
    public Async<Void> refresh(PackContentContext context) {
        root = context.providerRoot();
        catalog.clear();
        frameVariants.clear();
        frameLoads.clear();
        diagnostics.clear();
        return NexoGlyphLoader.loadRoot(files(context.fileSystem()), RemotePath.of(context.providerRoot().toString())).thenAccept(result -> {
            if (result.root() != null) root = Path.of(result.root().asString());
            diagnostics.addAll(result.diagnostics());
            for (GlyphDefinition glyph : result.glyphs().values()) {
                catalog.put(glyph.isReference() ? glyph : pendingWithFrames(context, glyph));
            }
        });
    }

    @Override
    public Map<String, GlyphDefinition> glyphs() {
        return catalog.glyphs();
    }

    @Override
    public List<GlyphTagMatch> parseGlyphTags(String text) {
        return catalog.parse(text);
    }

    GlyphDefinition materialized(GlyphDefinition glyph) {
        return catalog.materialized(glyph);
    }

    List<GlyphTagMatch> parseRawGlyphChars(String text, Iterable<GlyphDefinition> definitions, List<GlyphTagMatch> excluded) {
        return NexoGlyphText.parseRaw(id(), text, definitions, excluded);
    }

    @Override
    public Async<Optional<Path>> resolvePackAsset(PackContentContext context, String asset, boolean gif) {
        if (root == null || asset == null || asset.isBlank()) {
            return Async.completed(Optional.empty());
        }
        return NexoGlyphLoader.resolveAsset(files(context.fileSystem()), RemotePath.of(root.toString()), asset, gif)
                .thenApply(path -> path.map(value -> Path.of(value.asString())));
    }

    private GlyphDefinition pendingWithFrames(PackContentContext context, GlyphDefinition glyph) {
        if (isRemoteProvider(context.fileSystem())) {
            int frameCount = glyph.frameCount() > 0 ? glyph.frameCount() : Math.max(1, glyph.rows() * glyph.columns());
            return new GlyphDefinition(glyph.providerId(), glyph.id(), glyph.sourceFile(), glyph.assetRef(), glyph.ascent(), glyph.height(), glyph.font(), glyph.rows(), glyph.columns(), glyph.reference(), glyph.index(), glyph.offset(), frameCount, glyph.raw(), List.of());
        }
        List<GlyphPreviewFrame> frames = loadFrames(context, glyph.assetRef(), glyph.rows(), glyph.columns(), null);
        int frameCount = glyph.frameCount() > 0 ? glyph.frameCount() : frames.size();
        return new GlyphDefinition(glyph.providerId(), glyph.id(), glyph.sourceFile(), glyph.assetRef(), glyph.ascent(), glyph.height(), glyph.font(), glyph.rows(), glyph.columns(), glyph.reference(), glyph.index(), glyph.offset(), frameCount, glyph.raw(), frames);
    }

    private boolean isRemoteProvider(FileSystemProvider provider) {
        String type = provider != null ? provider.getMetadata("type") : null;
        return type != null && !"LOCAL".equalsIgnoreCase(type);
    }

    public List<GlyphPreviewFrame> framesFor(PackContentContext context, GlyphDefinition glyph, Integer requestedIndex) {
        return framesFor(context, glyph, requestedIndex, true);
    }

    public List<GlyphPreviewFrame> framesFor(PackContentContext context, GlyphDefinition glyph, Integer requestedIndex, boolean allowLoad) {
        if (glyph == null) {
            return List.of();
        }
        if (glyph.isReference()) {
            GlyphDefinition referenced = catalog.get(glyph.reference());
            Integer index = glyph.index() != null ? glyph.index() : requestedIndex;
            return framesFor(context, referenced, index, allowLoad);
        }
        if (requestedIndex == null && !glyph.frames().isEmpty()) {
            return glyph.frames();
        }
        NexoGlyphCatalog.Grid grid = NexoGlyphCatalog.grid(glyph, requestedIndex);
        String key = glyph.id() + "|" + refKey(glyph.assetRef()) + "|" + (requestedIndex != null ? requestedIndex : "all");
        List<GlyphPreviewFrame> cached = frameVariants.get(key);
        if (cached != null) {
            return cached;
        }
        if (!allowLoad) {
            if (context != null && isRemoteProvider(context.fileSystem())) return List.of();
            frameLoads.computeIfAbsent(key, ignored -> AsyncTools.supply(TaskSchedulers.current(), () -> {
                List<GlyphPreviewFrame> frames = loadFrames(context, glyph.assetRef(), grid.rows(), grid.columns(), requestedIndex);
                frameVariants.put(key, frames);
                return frames;
            }).whenComplete((frames, e) -> frameLoads.remove(key)));
            return List.of();
        }
        List<GlyphPreviewFrame> frames = loadFrames(context, glyph.assetRef(), grid.rows(), grid.columns(), requestedIndex);
        frameVariants.put(key, frames);
        return frames;
    }

    private String refKey(GlyphAssetRef ref) {
        return ref != null && ref.resolvedPath() != null ? ref.resolvedPath() : "";
    }

    private List<GlyphPreviewFrame> loadFrames(PackContentContext context, GlyphAssetRef ref, int rows, int columns, Integer requestedIndex) {
        try {
            return new DesktopGlyphFrameLoader(context).load(ref, rows, columns, requestedIndex);
        } catch (Exception e) {
            diagnostics.add(new PackContentDiagnostic(id(), ref == null ? "" : ref.resolvedPath(), e.getMessage()));
            return List.of();
        }
    }

    private NexoGlyphLoader.Files files(FileSystemProvider provider) {
        return new NexoGlyphLoader.Files() {
            @Override
            public Async<List<NexoGlyphLoader.Entry>> list(RemotePath path) {
                return JvmAsyncBridge.fromFuture(provider.ls(Path.of(path.asString()))).thenApply(entries -> entries == null
                        ? List.of()
                        : entries.stream().map(entry -> new NexoGlyphLoader.Entry(RemotePath.of(entry.path.toString()), entry.isDirectory)).toList());
            }

            @Override
            public Async<String> read(RemotePath path) {
                return JvmAsyncBridge.fromFuture(provider.read(Path.of(path.asString())));
            }

            @Override
            public Async<Boolean> exists(RemotePath path) {
                return JvmAsyncBridge.fromFuture(provider.exists(Path.of(path.asString())));
            }
        };
    }

}
