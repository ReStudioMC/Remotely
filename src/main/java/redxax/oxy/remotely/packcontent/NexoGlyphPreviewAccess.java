package redxax.oxy.remotely.packcontent;

import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class NexoGlyphPreviewAccess implements GlyphPreviewAccess {
    private static final String PROVIDER_ID = "nexo";
    private static final long REFRESH_INTERVAL_MS = 2_500L;
    private final NexoGlyphCatalog catalog = new NexoGlyphCatalog();
    private final NexoGlyphLoader.Files files;
    private final RemotePath workspaceRoot;
    private final Runtime runtime;
    private final Clock clock;
    private Async<Void> activeRefresh;
    private long refreshedAt;
    private long generation = 1L;
    private boolean closed;

    public NexoGlyphPreviewAccess(RemoteFileSystemProvider files, RemotePath workspaceRoot, Runtime runtime) {
        this(files, workspaceRoot, runtime, Clock.system());
    }

    NexoGlyphPreviewAccess(RemoteFileSystemProvider files, RemotePath workspaceRoot, Runtime runtime, Clock clock) {
        this(new RemoteFiles(files), workspaceRoot, runtime, clock);
    }

    NexoGlyphPreviewAccess(NexoGlyphLoader.Files files, RemotePath workspaceRoot, Runtime runtime, Clock clock) {
        this.files = files;
        this.workspaceRoot = workspaceRoot == null ? RemotePath.root() : workspaceRoot;
        this.runtime = runtime;
        this.clock = clock == null ? Clock.system() : clock;
    }

    @Override
    public synchronized Async<Void> refresh() {
        if (closed) return Async.completed(null);
        if (activeRefresh != null) return activeRefresh;
        long now = clock.millis();
        if (refreshedAt > 0 && now - refreshedAt < REFRESH_INTERVAL_MS) return Async.completed(null);
        long refreshGeneration = generation;
        Async<Void> pending = Async.pending();
        activeRefresh = pending;
        NexoGlyphLoader.load(files, workspaceRoot)
                .whenComplete((result, failure) -> finishRefresh(pending, refreshGeneration, result, failure));
        return pending;
    }

    public synchronized void close() {
        closed = true;
        generation++;
        catalog.clear();
        if (runtime != null) runtime.close();
    }

    private synchronized void finishRefresh(Async<Void> pending, long refreshGeneration,
                                            NexoGlyphLoader.Result result, Throwable failure) {
        activeRefresh = null;
        if (failure == null && !closed && generation == refreshGeneration) {
            catalog.replace(mergeFailedSources(result));
            refreshedAt = clock.millis();
            pending.complete(null);
        } else if (failure == null || closed || generation != refreshGeneration) {
            pending.complete(null);
        } else {
            pending.completeExceptionally(failure);
        }
    }

    private Map<String, GlyphDefinition> mergeFailedSources(NexoGlyphLoader.Result result) {
        Map<String, GlyphDefinition> next = new LinkedHashMap<>(result.glyphs());
        Set<String> failedSources = new HashSet<>();
        for (PackContentDiagnostic diagnostic : result.diagnostics()) {
            if (diagnostic != null && diagnostic.sourceFile() != null && !diagnostic.sourceFile().isBlank()) {
                failedSources.add(diagnostic.sourceFile());
            }
        }
        if (failedSources.isEmpty()) return next;
        for (GlyphDefinition glyph : catalog.glyphs().values()) {
            if (glyph != null && failedSources.contains(glyph.sourceFile())) next.putIfAbsent(glyph.id(), glyph);
        }
        return next;
    }

    @Override
    public List<Preview> resolveGlyphs(String text) {
        List<Preview> result = new ArrayList<>();
        for (GlyphTagMatch match : catalog.parse(text)) {
            GlyphDefinition glyph = catalog.materialized(match.glyphId());
            if (glyph == null) continue;
            GlyphTagMatch selected = selectedMatch(match, glyph);
            result.add(new Preview("Nexo", glyph, selected, frames(glyph, selected.indexStart())));
        }
        return result;
    }

    @Override
    public Optional<Preview> resolveGlyph(String providerId, String glyphId, Integer index) {
        if (providerId != null && !PROVIDER_ID.equalsIgnoreCase(providerId)) return Optional.empty();
        GlyphDefinition glyph = catalog.materialized(glyphId);
        if (glyph == null) return Optional.empty();
        Integer selected = index != null ? index : glyph.index();
        GlyphTagMatch match = new GlyphTagMatch(PROVIDER_ID, glyphId, 0, glyphId.length(), selected, selected, 0);
        return Optional.of(new Preview("Nexo", glyph, match, frames(glyph, selected)));
    }

    @Override
    public Async<Image> loadImage(GlyphDefinition glyph) {
        return loadImage(glyph, null);
    }

    @Override
    public Async<Image> loadImage(GlyphDefinition glyph, Integer index) {
        if (runtime == null) return Async.completed(null);
        Integer selected = index != null ? index : glyph == null ? null : glyph.index();
        GlyphDefinition target = catalog.resolved(glyph);
        GlyphAssetRef asset = target == null ? null : target.assetRef();
        if (asset == null || asset.resolvedPath() == null || asset.resolvedPath().isBlank()) return Async.completed(null);
        return runtime.loadImage(target, selected);
    }

    @Override
    public String sourceName(GlyphDefinition glyph) {
        String source = glyph == null ? "" : glyph.sourceFile();
        int separator = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        return separator >= 0 ? source.substring(separator + 1) : source;
    }

    @Override
    public boolean openSource(GlyphDefinition glyph) {
        String source = glyph == null ? null : glyph.sourceFile();
        return runtime != null && source != null && !source.isBlank() && runtime.openSource(RemotePath.of(source));
    }

    @Override
    public boolean openAsset(GlyphDefinition glyph) {
        GlyphAssetRef asset = glyph == null ? null : glyph.assetRef();
        String path = asset == null ? null : asset.resolvedPath();
        return runtime != null && path != null && !path.isBlank() && runtime.openAsset(RemotePath.of(path));
    }

    private List<GlyphPreviewFrame> frames(GlyphDefinition glyph, Integer index) {
        if (runtime == null) return List.of();
        List<GlyphPreviewFrame> frames = runtime.frames(glyph, index);
        return frames == null ? List.of() : frames;
    }

    private GlyphTagMatch selectedMatch(GlyphTagMatch match, GlyphDefinition glyph) {
        if (match.indexStart() != null || glyph.index() == null) return match;
        return new GlyphTagMatch(match.providerId(), match.glyphId(), match.start(), match.end(), glyph.index(), glyph.index(), match.shift());
    }

    public interface Runtime {
        Async<Image> loadImage(GlyphDefinition glyph, Integer index);

        default List<GlyphPreviewFrame> frames(GlyphDefinition glyph, Integer index) {
            return List.of();
        }

        boolean openSource(RemotePath path);

        boolean openAsset(RemotePath path);

        default void close() {
        }
    }

    private record RemoteFiles(RemoteFileSystemProvider provider) implements NexoGlyphLoader.Files {
        @Override
        public Async<List<NexoGlyphLoader.Entry>> list(RemotePath path) {
            return provider.ls(path).thenApply(entries -> entries == null ? List.of() : entries.stream()
                    .map(entry -> new NexoGlyphLoader.Entry(entry.path, entry.isDirectory))
                    .toList());
        }

        @Override
        public Async<String> read(RemotePath path) {
            return provider.read(path);
        }

        @Override
        public Async<Boolean> exists(RemotePath path) {
            return provider.exists(path);
        }
    }
}
