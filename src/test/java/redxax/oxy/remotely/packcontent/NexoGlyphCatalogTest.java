package redxax.oxy.remotely.packcontent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NexoGlyphCatalogTest {
    @Test
    void materializesReferenceMetadataAndOneBasedIndex() {
        GlyphAssetRef asset = new GlyphAssetRef("custom:faces", false, "pack/assets/custom/textures/faces.png");
        GlyphDefinition target = NexoGlyphCatalog.definition("faces", "glyphs/faces.yml", asset,
                Map.of("rows", 2, "columns", 2, "height", 16));
        GlyphDefinition reference = NexoGlyphCatalog.definition("third", "glyphs/faces.yml", null,
                Map.of("reference", "faces", "index", 3));
        NexoGlyphCatalog catalog = new NexoGlyphCatalog();
        catalog.put(target);
        catalog.put(reference);

        GlyphDefinition resolved = catalog.materialized("third");

        assertEquals(asset, resolved.assetRef());
        assertEquals(2, resolved.index());
        assertEquals(2, resolved.rows());
        assertEquals(2, resolved.columns());
    }

    @Test
    void rejectsReferenceCycles() {
        NexoGlyphCatalog catalog = new NexoGlyphCatalog();
        catalog.put(NexoGlyphCatalog.definition("one", "glyphs/test.yml", null, Map.of("reference", "two")));
        catalog.put(NexoGlyphCatalog.definition("two", "glyphs/test.yml", null, Map.of("reference", "one")));

        assertNull(catalog.materialized("one"));
    }

    @Test
    void infersRawCharacterAtlasGridByCodePoint() {
        String supplementary = new String(Character.toChars(0xF0001));
        GlyphDefinition glyph = NexoGlyphCatalog.definition("faces", "glyphs/faces.yml", null,
                Map.of("unicode", List.of("ꐓ" + supplementary, "ꐔꐕ")));

        assertEquals(new NexoGlyphCatalog.Grid(2, 2), NexoGlyphCatalog.grid(glyph, 3));
    }

    @Test
    void rawCharacterIndexFollowsCatalogMutation() {
        NexoGlyphCatalog catalog = new NexoGlyphCatalog();
        GlyphDefinition first = NexoGlyphCatalog.definition("first", "glyphs/test.yml", null, Map.of("unicode", "ꐓ"));
        GlyphDefinition second = NexoGlyphCatalog.definition("second", "glyphs/test.yml", null, Map.of("unicode", "ꐔ"));

        catalog.put(first);
        assertEquals("first", catalog.parse("ꐓ").getFirst().glyphId());
        catalog.replace(Map.of("second", second));
        assertEquals(List.of(), catalog.parse("ꐓ"));
        assertEquals("second", catalog.parse("ꐔ").getFirst().glyphId());
        catalog.clear();
        assertEquals(List.of(), catalog.parse("ꐔ"));
    }

    @Test
    void admittedGlyphMetadataCannotMutateTheRawIndex() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("unicode", "ꐓ");
        NexoGlyphCatalog catalog = new NexoGlyphCatalog();
        catalog.put(NexoGlyphCatalog.definition("first", "glyphs/test.yml", null, raw));

        raw.put("unicode", "ꐔ");

        assertEquals("first", catalog.parse("ꐓ").getFirst().glyphId());
        assertEquals(List.of(), catalog.parse("ꐔ"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.get("first").raw().put("unicode", "ꐔ"));
    }
}
