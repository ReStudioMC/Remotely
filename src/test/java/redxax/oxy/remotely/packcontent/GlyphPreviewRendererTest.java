package redxax.oxy.remotely.packcontent;

import com.jediterm.terminal.util.CharUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphPreviewRendererTest {
    @Test
    void consumesTerminalDoubleWidthTrailerWithGlyph() {
        String text = "ꐓ" + CharUtils.DWC;

        assertEquals(2, GlyphPreviewRenderer.terminalTokenEnd(text, 0, 1));
    }

    @Test
    void preservesPrivateUseCharacterAfterGlyphTag() {
        String text = "<g:logo>" + CharUtils.DWC;

        assertEquals(8, GlyphPreviewRenderer.terminalTokenEnd(text, 0, 8));
    }

    @Test
    void leavesUnmatchedPrivateUseCharactersAlone() {
        String text = "plain" + CharUtils.DWC;

        assertEquals(text, GlyphPreviewRenderer.terminalPlainText(text));
    }

    @Test
    void suppressesRawUnicodeGlyphPreviewOnlyInFileEditor() {
        GlyphTagMatch rawGlyph = new GlyphTagMatch("nexo", "logo", 0, 1, null, null, 0);

        assertFalse(GlyphPreviewRenderer.rendersPreview("ꐓ", rawGlyph, GlyphPreviewRenderer.PreviewSurface.FILE_EDITOR));
        assertTrue(GlyphPreviewRenderer.rendersPreview("ꐓ", rawGlyph, GlyphPreviewRenderer.PreviewSurface.TERMINAL));
    }

    @Test
    void suppressesRawUnicodeGlyphPreviewInYamlCharEntryOnlyInFileEditor() {
        String line = "  char: 'ꐓ'";
        GlyphTagMatch rawGlyph = new GlyphTagMatch("nexo", "logo", 9, 10, null, null, 0);

        assertFalse(GlyphPreviewRenderer.rendersPreview(line, rawGlyph, GlyphPreviewRenderer.PreviewSurface.FILE_EDITOR));
        assertTrue(GlyphPreviewRenderer.rendersPreview(line, rawGlyph, GlyphPreviewRenderer.PreviewSurface.TERMINAL));
    }

    @Test
    void preservesTagPreviewsInFileEditorAndTerminal() {
        GlyphTagMatch glyphTag = new GlyphTagMatch("nexo", "logo", 0, 8, null, null, 0);

        assertTrue(GlyphPreviewRenderer.rendersPreview("<g:logo>", glyphTag, GlyphPreviewRenderer.PreviewSurface.FILE_EDITOR));
        assertTrue(GlyphPreviewRenderer.rendersPreview("<g:logo>", glyphTag, GlyphPreviewRenderer.PreviewSurface.TERMINAL));
    }

    @Test
    void recognizesOnlyTopLevelGlyphConfigEntries() {
        assertEquals("test_icon", GlyphPreviewRenderer.glyphConfigId("test_icon:"));
        assertEquals("test_icon", GlyphPreviewRenderer.glyphConfigId("test_icon:  "));
        assertNull(GlyphPreviewRenderer.glyphConfigId("  char: 'ꐓ'"));
        assertNull(GlyphPreviewRenderer.glyphConfigId("  test_icon:"));
    }

    @Test
    void recognizesYamlWithoutDependingOnGlyphsDirectoryName() {
        assertTrue(GlyphPreviewRenderer.isYamlFile("test.yml", "yaml"));
        assertTrue(GlyphPreviewRenderer.isYamlFile("configs\\icons.YAML", "YAML"));
        assertFalse(GlyphPreviewRenderer.isYamlFile("test.json", "yaml"));
        assertFalse(GlyphPreviewRenderer.isYamlFile("test.yml", "json"));
    }

    @Test
    void matchesResolvedGlyphSourceToAbsoluteOrRelativeEditorPath() {
        assertTrue(GlyphPreviewRenderer.sourceMatchesFile(
                "/plugins/Nexo/glyphs/test.yml", "/plugins/Nexo/glyphs/test.yml"));
        assertTrue(GlyphPreviewRenderer.sourceMatchesFile(
                "plugins\\Nexo\\glyphs\\test.yml", "/plugins/Nexo/glyphs/test.yml"));
        assertTrue(GlyphPreviewRenderer.sourceMatchesFile(
                "/servers/demo/plugins/Nexo/glyphs/test.yml", "glyphs/test.yml"));
        assertTrue(GlyphPreviewRenderer.sourceMatchesFile(
                "/servers/demo/config/../glyphs/test.yml", "./glyphs/test.yml"));
        assertTrue(GlyphPreviewRenderer.sourceMatchesFile(
                "C:\\Servers\\Demo\\glyphs\\TEST.yml", "c:/servers/demo/glyphs/test.yml"));
        assertFalse(GlyphPreviewRenderer.sourceMatchesFile(
                "/plugins/Nexo/glyphs/test.yml", "/plugins/Nexo/glyphs/other.yml"));
    }

}
