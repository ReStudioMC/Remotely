package redxax.oxy.remotely.packcontent;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rebase.backend.RemotePath;
import restudio.rescreen.platform.Async;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class NexoGlyphLoader {
    private static final List<String> PREFERRED_NAMESPACES = List.of("minecraft", "nexo");

    private NexoGlyphLoader() {
    }

    public static Async<Optional<RemotePath>> detectRoot(Files files, RemotePath workspaceRoot) {
        if (files == null || workspaceRoot == null) return Async.completed(Optional.empty());
        Files source = cached(files);
        RemotePath primary = workspaceRoot.resolve("plugins").resolve("Nexo");
        return exists(source, primary.resolve("glyphs")).thenCompose(primaryExists -> primaryExists
                ? Async.completed(Optional.of(primary))
                : exists(source, workspaceRoot.resolve("glyphs")).thenApply(rootExists -> rootExists
                        ? Optional.of(workspaceRoot)
                        : Optional.empty()));
    }

    public static Async<Result> load(Files files, RemotePath workspaceRoot) {
        if (files == null || workspaceRoot == null) return Async.completed(Result.empty());
        Files source = cached(files);
        return detectRoot(source, workspaceRoot).thenCompose(root -> root.isEmpty()
                ? Async.completed(Result.empty())
                : loadRoot(source, root.get()));
    }

    public static Async<Result> loadRoot(Files files, RemotePath root) {
        if (files == null || root == null) return Async.completed(Result.empty());
        Files sourceFiles = cached(files);
        return walk(sourceFiles, root.resolve("glyphs")).thenCompose(paths -> {
            Map<String, GlyphDefinition> glyphs = new LinkedHashMap<>();
            List<PackContentDiagnostic> diagnostics = new ArrayList<>();
            Async<Void> loading = Async.completed(null);
            for (RemotePath path : paths) {
                String name = path.fileName().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".yml") && !name.endsWith(".yaml")) continue;
                loading = loading.thenCompose(ignored -> sourceFiles.read(path)
                        .thenCompose(content -> parse(sourceFiles, root, path, content, glyphs))
                        .exceptionally(failure -> {
                            diagnostics.add(new PackContentDiagnostic("nexo", path.asString(), message(failure)));
                            return null;
                        }));
            }
            return loading.thenApply(ignored -> new Result(root, glyphs, diagnostics));
        });
    }

    private static Async<Void> parse(Files files, RemotePath root, RemotePath source, String content,
                                     Map<String, GlyphDefinition> glyphs) {
        Async<Void> parsed = Async.completed(null);
        for (Map.Entry<String, Map<String, Object>> entry : NexoGlyphConfig.parse(content).entrySet()) {
            Map<String, Object> raw = entry.getValue();
            String texture = string(raw.get("texture"));
            String gif = string(raw.get("gif"));
            boolean animated = gif != null && !gif.isBlank();
            String value = animated ? gif : texture;
            parsed = parsed.thenCompose(ignored -> {
                Async<Optional<RemotePath>> asset = value == null || value.isBlank()
                        ? Async.completed(Optional.empty())
                        : resolveAsset(files, root, value, animated);
                return asset.thenAccept(path -> {
                    GlyphAssetRef reference = value == null || value.isBlank()
                            ? null
                            : new GlyphAssetRef(value, animated, path.map(RemotePath::asString).orElse(null));
                    GlyphDefinition glyph = NexoGlyphCatalog.definition(entry.getKey(), source.asString(), reference, raw);
                    glyphs.put(glyph.id(), glyph);
                });
            });
        }
        return parsed;
    }

    public static Async<Optional<RemotePath>> resolveAsset(Files files, RemotePath root, String asset, boolean gif) {
        if (files == null || root == null || asset == null || asset.isBlank()) return Async.completed(Optional.empty());
        Files source = cached(files);
        String normalized = asset.replace('\\', '/');
        int colon = normalized.indexOf(':');
        String namespace = colon >= 0 ? normalized.substring(0, colon) : null;
        String value = colon >= 0 ? normalized.substring(colon + 1) : normalized;
        String extension = gif ? ".gif" : ".png";
        if (!value.toLowerCase(Locale.ROOT).endsWith(extension)) value += extension;
        String assetPath = value;
        List<String> namespaces = namespace == null ? PREFERRED_NAMESPACES : List.of(namespace);
        return firstExisting(source, candidates(root, namespaces, assetPath), 0).thenCompose(found -> {
            if (found.isPresent() || namespace != null) return Async.completed(found);
            RemotePath assets = root.resolve("pack").resolve("assets");
            return source.list(assets).thenCompose(entries -> {
                List<String> discovered = new ArrayList<>();
                if (entries != null) {
                    for (Entry entry : entries) {
                        String name = entry == null || entry.path() == null ? "" : entry.path().fileName();
                        if (entry != null && entry.directory() && !name.isBlank() && !PREFERRED_NAMESPACES.contains(name)) {
                            discovered.add(name);
                        }
                    }
                }
                return firstExisting(source, candidates(root, discovered, assetPath), 0);
            });
        });
    }

    private static List<RemotePath> candidates(RemotePath root, List<String> namespaces, String asset) {
        List<RemotePath> result = new ArrayList<>();
        for (String namespace : namespaces) {
            RemotePath assetRoot = root.resolve("pack").resolve("assets").resolve(namespace);
            result.add(asset.startsWith("textures/") ? assetRoot.resolve(asset) : assetRoot.resolve("textures").resolve(asset));
        }
        return result;
    }

    private static Async<Optional<RemotePath>> firstExisting(Files files, List<RemotePath> candidates, int index) {
        if (index >= candidates.size()) return Async.completed(Optional.empty());
        RemotePath candidate = candidates.get(index);
        return exists(files, candidate).thenCompose(found -> found
                ? Async.completed(Optional.of(candidate))
                : firstExisting(files, candidates, index + 1));
    }

    private static Async<Boolean> exists(Files files, RemotePath path) {
        return files.exists(path);
    }

    private static Async<List<RemotePath>> walk(Files files, RemotePath root) {
        return files.list(root).thenCompose(entries -> {
            List<RemotePath> result = new ArrayList<>();
            Async<Void> children = Async.completed(null);
            if (entries != null) {
                for (Entry entry : entries) {
                    if (entry == null || entry.path() == null) continue;
                    if (entry.directory()) {
                        children = children.thenCompose(ignored -> walk(files, entry.path())
                                .thenAccept(result::addAll));
                    } else {
                        result.add(entry.path());
                    }
                }
            }
            return children.thenApply(ignored -> result);
        });
    }

    private static String message(Throwable failure) {
        if (failure == null) return "Pack Content Could Not Load";
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.toString()
                : failure.getMessage();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static Files cached(Files files) {
        return files instanceof CachedFiles ? files : new CachedFiles(files);
    }

    public interface Files {
        Async<List<Entry>> list(RemotePath path);

        Async<String> read(RemotePath path);

        Async<Boolean> exists(RemotePath path);
    }

    public record Entry(RemotePath path, boolean directory) {
    }

    public record Result(RemotePath root, Map<String, GlyphDefinition> glyphs, List<PackContentDiagnostic> diagnostics) {
        public Result {
            glyphs = glyphs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(glyphs));
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public static Result empty() {
            return new Result(null, Map.of(), List.of());
        }
    }

    private static final class CachedFiles implements Files {
        private final Files delegate;
        private final Map<RemotePath, Async<List<Entry>>> directories = BrowserSafeState.map();

        private CachedFiles(Files delegate) {
            this.delegate = delegate;
        }

        @Override
        public Async<List<Entry>> list(RemotePath path) {
            return directories.computeIfAbsent(path, key -> delegate.list(key)
                    .thenApply(entries -> entries == null ? List.of() : List.copyOf(entries)));
        }

        @Override
        public Async<String> read(RemotePath path) {
            return delegate.read(path);
        }

        @Override
        public Async<Boolean> exists(RemotePath path) {
            if (path == null || path.getParent() == null) return delegate.exists(path);
            return list(path.getParent()).thenApply(entries -> entries.stream()
                    .anyMatch(entry -> entry != null && path.equals(entry.path())));
        }
    }
}
