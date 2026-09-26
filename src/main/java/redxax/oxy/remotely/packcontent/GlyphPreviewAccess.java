package redxax.oxy.remotely.packcontent;

import restudio.rescreen.platform.Async;
import restudio.rescreen.util.Identifier;

import java.util.List;
import java.util.Optional;

public interface GlyphPreviewAccess {
    record Preview(String providerName, GlyphDefinition glyph, GlyphTagMatch match, List<GlyphPreviewFrame> frames) {
        public Preview {
            frames = frames == null ? List.of() : List.copyOf(frames);
        }
    }

    record Image(Identifier id, int width, int height, int rows, int columns, int index) {
        public Image(Identifier id, int width, int height) {
            this(id, width, height, 1, 1, 0);
        }

        public Image {
            rows = Math.max(1, rows);
            columns = Math.max(1, columns);
            index = Math.clamp(index, 0, rows * columns - 1);
        }

        public boolean isRegion() {
            return rows > 1 || columns > 1;
        }
    }

    Async<Void> refresh();

    List<Preview> resolveGlyphs(String text);

    default long catalogRevision() {
        return -1;
    }

    Optional<Preview> resolveGlyph(String providerId, String glyphId, Integer index);

    Async<Image> loadImage(GlyphDefinition glyph);

    default Async<Image> loadImage(GlyphDefinition glyph, Integer index) {
        return loadImage(glyph);
    }

    String sourceName(GlyphDefinition glyph);

    default boolean openSource(GlyphDefinition glyph) {
        return false;
    }

    default boolean openAsset(GlyphDefinition glyph) {
        return false;
    }
}
