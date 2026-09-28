package redxax.oxy.remotely.network.config;

import redxax.oxy.remotely.util.TextLines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TomlConfigurationAdapter implements NetworkConfigurationAdapter {
    private final StructuredDocumentParser structuredParser;

    public TomlConfigurationAdapter(StructuredDocumentParser structuredParser) {
        this.structuredParser = Objects.requireNonNull(structuredParser, "structuredParser");
    }
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([^]]+)]\\s*(?:#.*)?$");

    @Override
    public Reader prepare(String content) {
        List<String> lines = lines(content);
        int[] sectionEnds = new int[lines.size()];
        int nextSection = lines.size();
        for (int index = lines.size() - 1; index >= 0; index--) {
            sectionEnds[index] = nextSection;
            if (SECTION.matcher(lines.get(index)).matches()) nextSection = index;
        }
        Map<String, Map<String, String>> sections = new HashMap<>();
        HashSet<String> seen = new HashSet<>();
        String section = "";
        boolean first = true;
        sections.put(section, new HashMap<>());
        for (int index = 0; index < lines.size(); index++) {
            Matcher header = SECTION.matcher(lines.get(index));
            if (header.matches()) {
                section = unquote(header.group(1).trim());
                first = seen.add(section);
                if (first) sections.put(section, new HashMap<>());
                continue;
            }
            if (!first) continue;
            Assignment assignment = parseAssignment(lines.get(index));
            if (assignment == null) continue;
            int end = valueEnd(lines, index, sectionEnds[index], assignment.value());
            StringBuilder value = new StringBuilder(assignment.value());
            for (int continuation = index + 1; continuation <= end; continuation++) {
                value.append(' ').append(lines.get(continuation).trim());
            }
            sections.get(section).putIfAbsent(assignment.key(), value.toString());
            index = end;
        }
        return new Reader() {
            @Override
            public String read(String key) {
                KeyLocation location = locate(key);
                Map<String, String> entries = sections.get(location.section());
                if (location.key().equals("*")) return readSectionMap(lines, bounds(lines, location.section()));
                return entries == null ? "" : entries.getOrDefault(location.key(), "");
            }

            @Override
            public boolean contains(String key) {
                KeyLocation location = locate(key);
                Map<String, String> entries = sections.get(location.section());
                return entries != null && (location.key().equals("*") ? !entries.isEmpty() : entries.containsKey(location.key()));
            }
        };
    }

    @Override
    public String read(String content, String key) {
        KeyLocation location = locate(key);
        List<String> lines = lines(content);
        Bounds bounds = bounds(lines, location.section());
        if (location.key().equals("*")) {
            return readSectionMap(lines, bounds);
        }
        for (int index = bounds.start(); index < bounds.end(); index++) {
            Assignment assignment = parseAssignment(lines.get(index));
            if (assignment != null && assignment.key().equals(location.key())) {
                int valueEnd = valueEnd(lines, index, bounds.end(), assignment.value());
                StringBuilder value = new StringBuilder(assignment.value());
                for (int continuation = index + 1; continuation <= valueEnd; continuation++) {
                    value.append(' ').append(lines.get(continuation).trim());
                }
                return value.toString();
            }
        }
        return "";
    }

    @Override
    public boolean contains(String content, String key) {
        KeyLocation location = locate(key);
        List<String> lines = lines(content);
        Bounds bounds = bounds(lines, location.section());
        if (location.key().equals("*")) {
            return bounds.exists() && lines.subList(bounds.start(), bounds.end()).stream().anyMatch(line -> parseAssignment(line) != null);
        }
        for (int index = bounds.start(); index < bounds.end(); index++) {
            Assignment assignment = parseAssignment(lines.get(index));
            if (assignment != null && assignment.key().equals(location.key())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String apply(String content, String key, String value) {
        String ending = lineEnding(content);
        List<String> lines = new ArrayList<>(lines(content));
        KeyLocation location = locate(key);
        Bounds bounds = bounds(lines, location.section());
        if (location.key().equals("*")) {
            replaceSection(lines, location.section(), bounds, tomlMap(value));
            return join(lines, ending);
        }
        String renderedKey = renderKey(location.key());
        for (int index = bounds.start(); index < bounds.end(); index++) {
            Assignment assignment = parseAssignment(lines.get(index));
            if (assignment != null && assignment.key().equals(location.key())) {
                int valueEnd = valueEnd(lines, index, bounds.end(), assignment.value());
                for (int continuation = valueEnd; continuation > index; continuation--) {
                    lines.remove(continuation);
                }
                lines.set(index, assignment.indent() + renderedKey + " = " + value + assignment.suffix());
                return join(lines, ending);
            }
        }
        if (location.section().isBlank()) {
            int insertAt = firstSection(lines);
            lines.add(insertAt, renderedKey + " = " + value);
            return join(lines, ending);
        }
        if (bounds.exists()) {
            lines.add(bounds.end(), renderedKey + " = " + value);
            return join(lines, ending);
        }
        trimTrailingEmpty(lines);
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.add("[" + location.section() + "]");
        lines.add(renderedKey + " = " + value);
        return join(lines, ending) + ending;
    }

    @Override
    public String remove(String content, String key) {
        String ending = lineEnding(content);
        List<String> lines = new ArrayList<>(lines(content));
        KeyLocation location = locate(key);
        Bounds bounds = bounds(lines, location.section());
        if (location.key().equals("*")) {
            removeSectionAssignments(lines, bounds);
            ensureSection(lines, location.section(), bounds.exists());
            return join(lines, ending);
        }
        for (int index = bounds.start(); index < bounds.end(); index++) {
            Assignment assignment = parseAssignment(lines.get(index));
            if (assignment != null && assignment.key().equals(location.key())) {
                int valueEnd = valueEnd(lines, index, bounds.end(), assignment.value());
                for (int removeIndex = valueEnd; removeIndex >= index; removeIndex--) {
                    lines.remove(removeIndex);
                }
                return join(lines, ending);
            }
        }
        return join(lines, ending);
    }

    private String readSectionMap(List<String> lines, Bounds bounds) {
        if (!bounds.exists()) {
            return "";
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (int index = bounds.start(); index < bounds.end(); index++) {
            Assignment assignment = parseAssignment(lines.get(index));
            if (assignment == null) {
                continue;
            }
            int valueEnd = valueEnd(lines, index, bounds.end(), assignment.value());
            StringBuilder value = new StringBuilder(assignment.value());
            for (int continuation = index + 1; continuation <= valueEnd; continuation++) {
                value.append(' ').append(lines.get(continuation).trim());
            }
            values.put(assignment.key(), value.toString());
            index = valueEnd;
        }
        if (values.isEmpty()) {
            return "{}";
        }
        return "{" + values.entrySet().stream()
                .map(entry -> yamlKey(entry.getKey()) + ": " + entry.getValue())
                .reduce((left, right) -> left + ", " + right).orElse("") + "}";
    }

    private void replaceSection(List<String> lines, String section, Bounds bounds, Map<Object, Object> values) {
        if (!bounds.exists()) {
            trimTrailingEmpty(lines);
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("[" + section + "]");
            appendAssignments(lines, values);
            return;
        }
        LinkedHashSet<String> existing = new LinkedHashSet<>();
        List<AssignmentLocation> assignments = new ArrayList<>();
        for (int index = bounds.start(); index < bounds.end(); index++) {
            Assignment assignment = parseAssignment(lines.get(index));
            if (assignment == null) {
                continue;
            }
            int valueEnd = valueEnd(lines, index, bounds.end(), assignment.value());
            assignments.add(new AssignmentLocation(index, valueEnd, assignment));
            index = valueEnd;
        }
        for (int assignmentIndex = assignments.size() - 1; assignmentIndex >= 0; assignmentIndex--) {
            AssignmentLocation assignment = assignments.get(assignmentIndex);
            String key = assignment.assignment().key();
            if (!values.containsKey(key)) {
                for (int index = assignment.end(); index >= assignment.start(); index--) {
                    lines.remove(index);
                }
                continue;
            }
            existing.add(key);
            for (int index = assignment.end(); index > assignment.start(); index--) {
                lines.remove(index);
            }
            lines.set(assignment.start(), assignment.assignment().indent() + renderKey(key) + " = " + tomlValue(values.get(key)) + assignment.assignment().suffix());
        }
        Bounds updated = bounds(lines, section);
        int insertAt = updated.end();
        LinkedHashMap<Object, Object> missing = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!existing.contains(String.valueOf(key))) {
                missing.put(key, value);
            }
        });
        lines.addAll(insertAt, renderedAssignments(missing));
    }

    private void appendAssignments(List<String> lines, Map<Object, Object> values) {
        lines.addAll(renderedAssignments(values));
    }

    private List<String> renderedAssignments(Map<Object, Object> values) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : values.entrySet()) {
            lines.add(renderKey(String.valueOf(entry.getKey())) + " = " + tomlValue(entry.getValue()));
        }
        return lines;
    }

    private Map<Object, Object> tomlMap(String value) {
        Object parsed;
        try {
            parsed = structuredParser.parse(value == null || value.isBlank() ? "{}" : value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Expected a TOML section map", exception);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a TOML section map");
        }
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        map.forEach(result::put);
        return result;
    }

    private String tomlValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("TOML does not support null values");
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return "{" + map.entrySet().stream()
                    .map(entry -> renderKey(String.valueOf(entry.getKey())) + " = " + tomlValue(entry.getValue()))
                    .reduce((left, right) -> left + ", " + right).orElse("") + "}";
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            iterable.forEach(item -> values.add(tomlValue(item)));
            return "[" + String.join(", ", values) + "]";
        }
        String text = value.toString();
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String yamlKey(String value) {
        return value.matches("[A-Za-z0-9_-]+") ? value : "'" + value.replace("'", "''") + "'";
    }

    private void removeSectionAssignments(List<String> lines, Bounds bounds) {
        List<int[]> ranges = new ArrayList<>();
        for (int index = bounds.start(); index < bounds.end(); index++) {
            Assignment assignment = parseAssignment(lines.get(index));
            if (assignment == null) {
                continue;
            }
            int valueEnd = valueEnd(lines, index, bounds.end(), assignment.value());
            ranges.add(new int[]{index, valueEnd});
            index = valueEnd;
        }
        for (int rangeIndex = ranges.size() - 1; rangeIndex >= 0; rangeIndex--) {
            int[] range = ranges.get(rangeIndex);
            for (int removeIndex = range[1]; removeIndex >= range[0]; removeIndex--) {
                lines.remove(removeIndex);
            }
        }
    }

    private void ensureSection(List<String> lines, String section, boolean exists) {
        if (exists || section.isBlank()) return;
        trimTrailingEmpty(lines);
        if (!lines.isEmpty()) lines.add("");
        lines.add("[" + section + "]");
        lines.add("");
    }

    private int valueEnd(List<String> lines, int start, int boundEnd, String firstValue) {
        int depth = arrayDepth(firstValue);
        int end = start;
        while (depth > 0 && ++end < boundEnd) {
            depth += arrayDepth(lines.get(end));
        }
        return Math.min(end, boundEnd - 1);
    }

    private int arrayDepth(String value) {
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        for (char character : value.toCharArray()) {
            if (escaped) {
                escaped = false;
            } else if (character == '\\' && quoted && quote == '"') {
                escaped = true;
            } else if (quoted && character == quote) {
                quoted = !quoted;
            } else if (!quoted && (character == '"' || character == '\'')) {
                quoted = true;
                quote = character;
            } else if (!quoted && character == '#') {
                break;
            } else if (!quoted && character == '[') {
                depth++;
            } else if (!quoted && character == ']') {
                depth--;
            }
        }
        return depth;
    }

    private Bounds bounds(List<String> lines, String section) {
        if (section.isBlank()) {
            return new Bounds(0, firstSection(lines), true);
        }
        int start = -1;
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = SECTION.matcher(lines.get(index));
            if (!matcher.matches()) {
                continue;
            }
            if (start >= 0) {
                return new Bounds(start, index, true);
            }
            if (unquote(matcher.group(1).trim()).equals(section)) {
                start = index + 1;
            }
        }
        return start >= 0 ? new Bounds(start, lines.size(), true) : new Bounds(lines.size(), lines.size(), false);
    }

    private int firstSection(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (SECTION.matcher(lines.get(index)).matches()) {
                return index;
            }
        }
        return lines.size();
    }

    private Assignment parseAssignment(String line) {
        int keyStart = 0;
        while (keyStart < line.length() && Character.isWhitespace(line.charAt(keyStart))) {
            keyStart++;
        }
        if (keyStart >= line.length() || line.charAt(keyStart) == '#') {
            return null;
        }
        int keyEnd;
        char first = line.charAt(keyStart);
        if (first == '"' || first == '\'') {
            keyEnd = quotedEnd(line, keyStart, first);
            if (keyEnd < 0) {
                return null;
            }
            keyEnd++;
        } else {
            keyEnd = keyStart;
            while (keyEnd < line.length() && !Character.isWhitespace(line.charAt(keyEnd)) && line.charAt(keyEnd) != '=') {
                keyEnd++;
            }
        }
        int equals = keyEnd;
        while (equals < line.length() && Character.isWhitespace(line.charAt(equals))) {
            equals++;
        }
        if (equals >= line.length() || line.charAt(equals) != '=') {
            return null;
        }
        int valueStart = equals + 1;
        int commentStart = commentStart(line, valueStart);
        int valueEnd = commentStart < 0 ? line.length() : commentStart;
        String suffix = "";
        if (commentStart >= 0) {
            int suffixStart = commentStart;
            while (suffixStart > valueStart && Character.isWhitespace(line.charAt(suffixStart - 1))) {
                suffixStart--;
            }
            suffix = line.substring(suffixStart);
        }
        return new Assignment(line.substring(0, keyStart), unquote(line.substring(keyStart, keyEnd)), line.substring(valueStart, valueEnd).trim(), suffix);
    }

    private int quotedEnd(String value, int start, char quote) {
        boolean escaped = false;
        for (int index = start + 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quote == '"' && character == '\\') {
                escaped = true;
            } else if (character == quote) {
                return index;
            }
        }
        return -1;
    }

    private int commentStart(String value, int start) {
        char quote = 0;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (quote == '"' && character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (character == '#') {
                return index;
            }
        }
        return -1;
    }

    private KeyLocation locate(String key) {
        int separator = key.indexOf('.');
        if (separator < 0) {
            return new KeyLocation("", key);
        }
        return new KeyLocation(key.substring(0, separator), key.substring(separator + 1));
    }

    private String renderKey(String key) {
        if (key.matches("[A-Za-z0-9_-]+")) {
            return key;
        }
        return "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private List<String> lines(String content) {
        return TextLines.split(content);
    }

    private String lineEnding(String content) {
        return content != null && content.contains("\r\n") ? "\r\n" : "\n";
    }

    private String join(List<String> lines, String ending) {
        return String.join(ending, lines);
    }

    private void trimTrailingEmpty(List<String> lines) {
        while (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
    }

    private record KeyLocation(String section, String key) {
    }

    private record Bounds(int start, int end, boolean exists) {
    }

    private record Assignment(String indent, String key, String value, String suffix) {
    }

    private record AssignmentLocation(int start, int end, Assignment assignment) {
    }
}
