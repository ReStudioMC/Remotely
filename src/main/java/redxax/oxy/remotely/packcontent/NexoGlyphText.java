package redxax.oxy.remotely.packcontent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NexoGlyphText {
    private static final Pattern GLYPH_TAG = Pattern.compile("<(glyph|g):([^>]+)>");
    private static final Pattern SHIFT_TAG = Pattern.compile("<shift:([-+]?\\d+)>");

    private NexoGlyphText() {
    }

    public static List<GlyphTagMatch> parse(String providerId, String text, Iterable<GlyphDefinition> definitions) {
        return parse(providerId, text, indexRawGlyphs(definitions));
    }

    static List<GlyphTagMatch> parse(String providerId, String text, Map<String, RawGlyph> indexed) {
        List<GlyphTagMatch> matches = new ArrayList<>();
        if (text == null || text.isEmpty()) return matches;
        Matcher matcher = GLYPH_TAG.matcher(text);
        while (matcher.find()) {
            String[] parts = matcher.group(2).split(":");
            if (parts.length == 0 || parts[0].isBlank()) continue;
            String glyphId = parts[0];
            int optionStart = 1;
            if (parts.length > 1 && (parts[0].equalsIgnoreCase(providerId) || parts[0].equalsIgnoreCase("glyph"))) {
                glyphId = parts[1];
                optionStart = 2;
            }
            IndexRange range = indexRange(parts, optionStart);
            matches.add(new GlyphTagMatch(providerId, glyphId, matcher.start(), matcher.end(), range.start(), range.end(),
                    adjacentShift(text, matcher.start(), matcher.end())));
        }
        matches.addAll(parseRaw(providerId, text, indexed, matches));
        matches.sort(Comparator.comparingInt(GlyphTagMatch::start).thenComparingInt(GlyphTagMatch::end));
        return matches;
    }

    static List<GlyphTagMatch> parseRaw(String providerId, String text, Iterable<GlyphDefinition> definitions,
                                        List<GlyphTagMatch> excluded) {
        return parseRaw(providerId, text, indexRawGlyphs(definitions), excluded);
    }

    static Map<String, RawGlyph> indexRawGlyphs(Iterable<GlyphDefinition> definitions) {
        Map<String, RawGlyph> indexed = new LinkedHashMap<>();
        if (definitions != null) for (GlyphDefinition glyph : definitions) indexRawGlyph(glyph, indexed);
        return Map.copyOf(indexed);
    }

    private static List<GlyphTagMatch> parseRaw(String providerId, String text, Map<String, RawGlyph> indexed,
                                                List<GlyphTagMatch> excluded) {
        if (text == null || text.isEmpty() || indexed == null || indexed.isEmpty()) return List.of();
        List<GlyphTagMatch> matches = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = start + Character.charCount(text.codePointAt(start));
            RawGlyph rawGlyph = indexed.get(text.substring(start, end));
            if (rawGlyph != null && !overlaps(start, end, excluded)) {
                matches.add(new GlyphTagMatch(providerId, rawGlyph.glyphId(), start, end, rawGlyph.index(), rawGlyph.index(),
                        adjacentShift(text, start, end)));
            }
            start = end;
        }
        return matches;
    }

    public static List<String> charRows(Object value) {
        if (value instanceof List<?> values) {
            List<String> rows = new ArrayList<>();
            for (Object entry : values) {
                if (entry != null && !entry.toString().isEmpty()) rows.add(decodeUnicode(entry.toString()));
            }
            return rows;
        }
        return value == null || value.toString().isEmpty() ? List.of() : List.of(decodeUnicode(value.toString()));
    }

    public static Object characters(GlyphDefinition glyph) {
        if (glyph == null || glyph.raw() == null) return null;
        for (String key : List.of("char", "chars", "unicode", "unicodes")) {
            Object value = glyph.raw().get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static void indexRawGlyph(GlyphDefinition glyph, Map<String, RawGlyph> indexed) {
        if (glyph == null || glyph.raw() == null) return;
        List<String> rows = charRows(characters(glyph));
        int cells = rows.stream().mapToInt(row -> row.codePointCount(0, row.length())).sum();
        int index = 0;
        for (String row : rows) {
            for (int offset = 0; offset < row.length();) {
                int end = offset + Character.charCount(row.codePointAt(offset));
                Integer requestedIndex = glyph.isMultiBitmap() || cells > 1 ? Integer.valueOf(index) : glyph.index();
                indexed.putIfAbsent(row.substring(offset, end), new RawGlyph(glyph.id(), requestedIndex));
                offset = end;
                index++;
            }
        }
    }

    private static IndexRange indexRange(String[] parts, int startIndex) {
        Integer start = null;
        Integer end = null;
        for (int i = startIndex; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.equalsIgnoreCase("colorable") || part.equalsIgnoreCase("c")
                    || part.equalsIgnoreCase("shadow") || part.equalsIgnoreCase("s")) {
                continue;
            }
            if (part.matches("\\d+\\.\\.\\d+")) {
                String[] range = part.split("\\.\\.");
                start = Math.max(0, Integer.parseInt(range[0]) - 1);
                end = Math.max(start, Integer.parseInt(range[1]) - 1);
                break;
            } else if (part.matches("\\d+")) {
                start = Math.max(0, Integer.parseInt(part) - 1);
                end = start;
                break;
            }
        }
        return new IndexRange(start, end);
    }

    private static int adjacentShift(String text, int start, int end) {
        Matcher before = SHIFT_TAG.matcher(text.substring(0, start));
        int shift = 0;
        while (before.find()) {
            if (before.end() == start) shift += Integer.parseInt(before.group(1));
        }
        Matcher after = SHIFT_TAG.matcher(text.substring(end));
        if (after.find() && after.start() == 0) shift += Integer.parseInt(after.group(1));
        return shift;
    }

    private static boolean overlaps(int start, int end, List<GlyphTagMatch> excluded) {
        if (excluded == null) return false;
        for (GlyphTagMatch match : excluded) {
            if (match != null && start < match.end() && end > match.start()) return true;
        }
        return false;
    }

    private static String decodeUnicode(String value) {
        if (value == null || value.indexOf('\\') < 0) return value;
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (character != '\\' || index + 1 >= value.length()) {
                decoded.append(character);
                index++;
                continue;
            }
            char marker = value.charAt(index + 1);
            if (marker == '\\') {
                decoded.append('\\');
                index += 2;
                continue;
            }
            int digits = marker == 'u' ? 4 : marker == 'U' ? 8 : 0;
            if (digits == 0 || index + 2 + digits > value.length()) {
                decoded.append(character);
                index++;
                continue;
            }
            int codePoint = 0;
            boolean valid = true;
            for (int offset = index + 2; offset < index + 2 + digits; offset++) {
                int digit = Character.digit(value.charAt(offset), 16);
                if (digit < 0) {
                    valid = false;
                    break;
                }
                codePoint = codePoint * 16 + digit;
            }
            if (!valid || !Character.isValidCodePoint(codePoint)) {
                decoded.append(character);
                index++;
                continue;
            }
            decoded.appendCodePoint(codePoint);
            index += 2 + digits;
        }
        return decoded.toString();
    }

    private record IndexRange(Integer start, Integer end) {
    }

    record RawGlyph(String glyphId, Integer index) {
    }
}
