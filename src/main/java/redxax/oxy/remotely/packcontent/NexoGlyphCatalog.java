package redxax.oxy.remotely.packcontent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NexoGlyphCatalog {
    private static final String PROVIDER_ID = "nexo";
    private final Map<String, GlyphDefinition> glyphs = new LinkedHashMap<>();

    public synchronized void clear() {
        glyphs.clear();
    }

    public synchronized void replace(Map<String, GlyphDefinition> next) {
        glyphs.clear();
        if (next != null) glyphs.putAll(next);
    }

    public synchronized void put(GlyphDefinition glyph) {
        if (glyph != null && glyph.id() != null && !glyph.id().isBlank()) glyphs.put(glyph.id(), glyph);
    }

    public synchronized GlyphDefinition get(String id) {
        return id == null ? null : glyphs.get(id);
    }

    public synchronized Map<String, GlyphDefinition> glyphs() {
        return Map.copyOf(glyphs);
    }

    public synchronized List<GlyphTagMatch> parse(String text) {
        List<GlyphTagMatch> matches = NexoGlyphText.parse(PROVIDER_ID, text, glyphs.values());
        for (int index = 0; index < matches.size(); index++) {
            GlyphTagMatch match = matches.get(index);
            if (match.indexStart() != null) continue;
            GlyphDefinition glyph = materialized(match.glyphId());
            if (glyph == null || glyph.index() == null) continue;
            matches.set(index, new GlyphTagMatch(match.providerId(), match.glyphId(), match.start(), match.end(),
                    glyph.index(), glyph.index(), match.shift()));
        }
        return matches;
    }

    public synchronized GlyphDefinition materialized(String id) {
        return materialized(get(id));
    }

    public synchronized GlyphDefinition materialized(GlyphDefinition glyph) {
        GlyphDefinition target = resolved(glyph);
        if (glyph == null || target == null || target.assetRef() == null) return null;
        if (glyph == target) return glyph;
        return new GlyphDefinition(glyph.providerId(), glyph.id(), glyph.sourceFile(), target.assetRef(),
                glyph.ascent() != 0 ? glyph.ascent() : target.ascent(), glyph.height() != 0 ? glyph.height() : target.height(),
                glyph.font() != null ? glyph.font() : target.font(), target.rows(), target.columns(), null,
                glyph.index(), glyph.offset(), target.frameCount(), glyph.raw(), target.frames());
    }

    public synchronized GlyphDefinition resolved(GlyphDefinition glyph) {
        GlyphDefinition current = glyph;
        for (int depth = 0; current != null && current.isReference() && depth < 16; depth++) current = glyphs.get(current.reference());
        return current != null && current.isReference() ? null : current;
    }

    public static GlyphDefinition definition(String id, String source, GlyphAssetRef asset, Map<String, Object> raw) {
        return new GlyphDefinition(PROVIDER_ID, id, source, asset, integer(raw, "ascent", 0), integer(raw, "height", 0),
                string(raw, "font"), integer(raw, "rows", 1), integer(raw, "columns", 1), string(raw, "reference"),
                index(raw == null ? null : raw.get("index")), integer(raw, "offset", 0), integer(raw, "frame_count", 0), raw, List.of());
    }

    public static Grid grid(GlyphDefinition glyph, Integer requestedIndex) {
        if (glyph == null) return new Grid(1, 1);
        if (requestedIndex == null || glyph.isMultiBitmap()) return new Grid(glyph.rows(), glyph.columns());
        List<String> assigned = NexoGlyphText.charRows(NexoGlyphText.characters(glyph));
        int cells = assigned.stream().mapToInt(row -> row.codePointCount(0, row.length())).sum();
        if (cells <= 1) return new Grid(glyph.rows(), glyph.columns());
        int columns = assigned.stream().mapToInt(row -> row.codePointCount(0, row.length())).max().orElse(1);
        return new Grid(Math.max(1, assigned.size()), Math.max(1, columns));
    }

    private static String string(Map<String, Object> raw, String key) {
        Object value = raw == null ? null : raw.get(key);
        return value == null ? null : value.toString();
    }

    private static int integer(Map<String, Object> raw, String key, int fallback) {
        Object value = raw == null ? null : raw.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Integer index(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return Math.max(0, number.intValue() - 1);
        try {
            return Math.max(0, Integer.parseInt(value.toString().trim()) - 1);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Grid(int rows, int columns) {
        public Grid {
            rows = Math.max(1, rows);
            columns = Math.max(1, columns);
        }
    }
}
