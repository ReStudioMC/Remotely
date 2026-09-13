package redxax.oxy.remotely.packcontent;

import org.junit.jupiter.api.Test;
import restudio.rebase.backend.RemotePath;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexoGlyphLoaderTest {
    @Test
    void keepsValidGlyphsWhenAnotherYamlCannotBeRead() {
        FakeFiles files = catalogFiles();
        files.file("/plugins/Nexo/glyphs/broken.yml", Async.failed(new IllegalStateException("Unreadable")));

        NexoGlyphLoader.Result result = NexoGlyphLoader.load(files, RemotePath.root()).join();

        NexoGlyphCatalog catalog = catalog(result);
        GlyphDefinition glyph = catalog.materialized("test_icon");
        assertEquals("/plugins/Nexo/pack/assets/custom/textures/icon.png", glyph.assetRef().resolvedPath());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    void keepsReferenceIndexInConfigAndTagPreviewMatches() {
        FakeFiles files = catalogFiles();
        files.file("/plugins/Nexo/glyphs/icons.yml", Async.completed("test_icon:\n  texture: icon\n  char: 'ꐓ'\n  height: 8\ntest_alias:\n  reference: test_icon\n  index: 3\n"));

        NexoGlyphCatalog catalog = catalog(NexoGlyphLoader.load(files, RemotePath.root()).join());

        GlyphDefinition alias = catalog.materialized("test_alias");
        assertEquals(Integer.valueOf(2), alias.index());
        assertEquals(Integer.valueOf(2), catalog.parse("<g:test_alias>").getFirst().indexStart());
    }

    @Test
    void refreshKeepsLoadedGlyphsWhenRemoteAssetLookupFails() {
        FakeFiles files = catalogFiles();
        long[] now = {1L};
        NexoGlyphPreviewAccess access = new NexoGlyphPreviewAccess(files, RemotePath.root(), new RecordingRuntime(), () -> now[0]);
        access.refresh().join();
        assertEquals(1, access.resolveGlyphs("ꐓ").size());

        files.failList("/plugins/Nexo/pack/assets/minecraft/textures", new IllegalStateException("Unavailable"));
        now[0] = 3_001L;
        access.refresh().join();

        GlyphDefinition glyph = access.resolveGlyphs("ꐓ").getFirst().glyph();
        assertEquals("/plugins/Nexo/pack/assets/custom/textures/icon.png", glyph.assetRef().resolvedPath());
    }

    @Test
    void refreshKeepsLoadedGlyphsWhenRemoteDirectoryLookupFails() {
        FakeFiles files = catalogFiles();
        long[] now = {1L};
        NexoGlyphPreviewAccess access = new NexoGlyphPreviewAccess(files, RemotePath.root(), new RecordingRuntime(), () -> now[0]);
        access.refresh().join();

        files.failList("/plugins/Nexo/glyphs", new IllegalStateException("Unavailable"));
        now[0] = 3_001L;

        assertThrows(IllegalStateException.class, () -> access.refresh().join());
        assertEquals(1, access.resolveGlyphs("ꐓ").size());
    }

    @Test
    void ignoresRefreshCompletionAfterClose() {
        FakeFiles files = catalogFiles();
        Async<String> delayed = Async.pending();
        files.file("/plugins/Nexo/glyphs/icons.yml", delayed);
        NexoGlyphPreviewAccess access = new NexoGlyphPreviewAccess(files, RemotePath.root(), new RecordingRuntime(), Clock.system());

        Async<Void> refresh = access.refresh();
        access.close();
        delayed.complete("test_icon:\n  texture: icon\n  char: 'ꐓ'\n  height: 8\n");
        refresh.join();

        assertTrue(access.resolveGlyphs("ꐓ").isEmpty());
    }

    @Test
    void acceptsTexturePrefixWithoutDuplicatingIt() {
        FakeFiles files = catalogFiles();
        files.file("/plugins/Nexo/glyphs/icons.yml", Async.completed("test_icon:\n  texture: custom:textures/icon\n  char: 'ꐓ'\n  height: 8\n"));

        NexoGlyphCatalog catalog = catalog(NexoGlyphLoader.load(files, RemotePath.root()).join());

        assertEquals("/plugins/Nexo/pack/assets/custom/textures/icon.png",
                catalog.materialized("test_icon").assetRef().resolvedPath());
    }

    @Test
    void sharedAccessRoutesSourceAndAssetActionsThroughRuntime() {
        FakeFiles files = catalogFiles();
        RecordingRuntime runtime = new RecordingRuntime();
        NexoGlyphPreviewAccess access = new NexoGlyphPreviewAccess(files, RemotePath.root(), runtime, Clock.system());
        access.refresh().join();
        GlyphDefinition glyph = access.resolveGlyph("nexo", "test_icon", null).orElseThrow().glyph();

        assertTrue(access.openSource(glyph));
        assertTrue(access.openAsset(glyph));
        assertEquals(RemotePath.of("/plugins/Nexo/glyphs/icons.yml"), runtime.source);
        assertEquals(RemotePath.of("/plugins/Nexo/pack/assets/custom/textures/icon.png"), runtime.asset);
    }

    @Test
    void listsEachAssetDirectoryOncePerCatalogLoad() {
        FakeFiles files = catalogFiles();
        files.file("/plugins/Nexo/glyphs/icons.yml", Async.completed("first:\n  texture: icon\nsecond:\n  texture: icon\n"));

        NexoGlyphLoader.load(files, RemotePath.root()).join();

        assertEquals(1, files.listCount("/plugins/Nexo/pack/assets/minecraft/textures"));
        assertEquals(1, files.listCount("/plugins/Nexo/pack/assets/nexo/textures"));
        assertEquals(1, files.listCount("/plugins/Nexo/pack/assets/custom/textures"));
    }

    private static NexoGlyphCatalog catalog(NexoGlyphLoader.Result result) {
        NexoGlyphCatalog catalog = new NexoGlyphCatalog();
        catalog.replace(result.glyphs());
        return catalog;
    }

    private static FakeFiles catalogFiles() {
        FakeFiles files = new FakeFiles();
        files.directory("/", "/plugins");
        files.directory("/plugins", "/plugins/Nexo");
        files.directory("/plugins/Nexo", "/plugins/Nexo/glyphs", "/plugins/Nexo/pack");
        files.directory("/plugins/Nexo/glyphs", "/plugins/Nexo/glyphs/icons.yml", "/plugins/Nexo/glyphs/broken.yml");
        files.directory("/plugins/Nexo/pack/assets", "/plugins/Nexo/pack/assets/custom");
        files.directory("/plugins/Nexo/pack/assets/minecraft/textures");
        files.directory("/plugins/Nexo/pack/assets/nexo/textures");
        files.directory("/plugins/Nexo/pack/assets/custom/textures", "/plugins/Nexo/pack/assets/custom/textures/icon.png");
        files.file("/plugins/Nexo/glyphs/icons.yml", Async.completed("test_icon:\n  texture: icon\n  char: 'ꐓ'\n  height: 8\n"));
        files.file("/plugins/Nexo/glyphs/broken.yml", Async.completed(""));
        files.file("/plugins/Nexo/pack/assets/custom/textures/icon.png", Async.completed(""));
        return files;
    }

    private static final class FakeFiles implements NexoGlyphLoader.Files {
        private final Map<RemotePath, List<NexoGlyphLoader.Entry>> directories = new LinkedHashMap<>();
        private final Map<RemotePath, Async<String>> files = new LinkedHashMap<>();
        private final Map<RemotePath, Integer> lists = new LinkedHashMap<>();
        private final Map<RemotePath, Throwable> listFailures = new LinkedHashMap<>();

        private void directory(String path, String... children) {
            RemotePath directory = RemotePath.of(path);
            directories.put(directory, Arrays.stream(children).map(RemotePath::of)
                    .map(child -> new NexoGlyphLoader.Entry(child, !child.fileName().contains(".")))
                    .toList());
        }

        private void file(String path, Async<String> content) {
            files.put(RemotePath.of(path), content);
        }

        private int listCount(String path) {
            return lists.getOrDefault(RemotePath.of(path), 0);
        }

        private void failList(String path, Throwable failure) {
            listFailures.put(RemotePath.of(path), failure);
        }

        @Override
        public Async<List<NexoGlyphLoader.Entry>> list(RemotePath path) {
            lists.put(path, lists.getOrDefault(path, 0) + 1);
            Throwable failure = listFailures.get(path);
            if (failure != null) return Async.failed(failure);
            List<NexoGlyphLoader.Entry> entries = directories.get(path);
            return entries == null ? Async.failed(new IllegalArgumentException("Missing " + path)) : Async.completed(entries);
        }

        @Override
        public Async<String> read(RemotePath path) {
            Async<String> content = files.get(path);
            return content == null ? Async.failed(new IllegalArgumentException("Missing " + path)) : content;
        }

        @Override
        public Async<Boolean> exists(RemotePath path) {
            return Async.completed(directories.containsKey(path) || files.containsKey(path));
        }
    }

    private static final class RecordingRuntime implements NexoGlyphPreviewAccess.Runtime {
        private RemotePath source;
        private RemotePath asset;

        @Override
        public Async<GlyphPreviewAccess.Image> loadImage(GlyphDefinition glyph, Integer index) {
            return Async.completed(null);
        }

        @Override
        public boolean openSource(RemotePath path) {
            source = path;
            return true;
        }

        @Override
        public boolean openAsset(RemotePath path) {
            asset = path;
            return true;
        }
    }
}
