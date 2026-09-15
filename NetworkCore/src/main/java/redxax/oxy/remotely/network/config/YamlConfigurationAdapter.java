package redxax.oxy.remotely.network.config;

import redxax.oxy.remotely.util.TextLines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class YamlConfigurationAdapter implements NetworkConfigurationAdapter {
    private final StructuredDocumentParser structuredParser;

    public YamlConfigurationAdapter(StructuredDocumentParser structuredParser) {
        this.structuredParser = Objects.requireNonNull(structuredParser, "structuredParser");
    }
    @Override
    public String read(String content, String key) {
        List<String> lines = lines(content);
        String[] segments = segments(key);
        if (isWildcard(segments)) {
            return readWildcard(lines, segments);
        }
        int start = 0;
        int end = lines.size();
        int indent = 0;
        for (int depth = 0; depth < segments.length; depth++) {
            int lineIndex = find(lines, segments[depth], start, end, indent);
            if (lineIndex < 0) {
                return "";
            }
            String mappingValue = mappingValue(lines.get(lineIndex), indent);
            if (depth == segments.length - 1) {
                String scalar = scalarWithoutComment(mappingValue);
                if (!scalar.isBlank()) {
                    return decodeScalar(scalar);
                }
                Bounds child = childBounds(lines, lineIndex, end, indent);
                return blockValue(lines, child);
            }
            if (!scalarWithoutComment(mappingValue).isBlank()) {
                return "";
            }
            Bounds child = childBounds(lines, lineIndex, end, indent);
            start = child.start();
            end = child.end();
            indent = child.indent();
        }
        return "";
    }

    @Override
    public boolean contains(String content, String key) {
        List<String> lines = lines(content);
        String[] segments = segments(key);
        if (isWildcard(segments)) {
            return containsWildcard(lines, segments);
        }
        int start = 0;
        int end = lines.size();
        int indent = 0;
        for (int depth = 0; depth < segments.length; depth++) {
            int lineIndex = find(lines, segments[depth], start, end, indent);
            if (lineIndex < 0) {
                return false;
            }
            if (depth == segments.length - 1) {
                return true;
            }
            if (!scalarWithoutComment(mappingValue(lines.get(lineIndex), indent)).isBlank()) {
                return false;
            }
            Bounds child = childBounds(lines, lineIndex, end, indent);
            start = child.start();
            end = child.end();
            indent = child.indent();
        }
        return false;
    }

    @Override
    public String apply(String content, String key, String value) {
        String ending = lineEnding(content);
        List<String> lines = new ArrayList<>(lines(content));
        String[] segments = segments(key);
        if (isWildcard(segments)) {
            applyWildcard(lines, segments, value);
            return String.join(ending, lines);
        }
        apply(lines, segments, 0, 0, lines.size(), 0, value);
        return String.join(ending, lines);
    }

    @Override
    public String remove(String content, String key) {
        String ending = lineEnding(content);
        List<String> lines = new ArrayList<>(lines(content));
        String[] segments = segments(key);
        if (isWildcard(segments)) {
            removeWildcard(lines, segments);
            return String.join(ending, lines);
        }
        int start = 0;
        int end = lines.size();
        int indent = 0;
        for (int depth = 0; depth < segments.length; depth++) {
            int lineIndex = find(lines, segments[depth], start, end, indent);
            if (lineIndex < 0) {
                return content == null ? "" : content;
            }
            String mappingValue = mappingValue(lines.get(lineIndex), indent);
            if (depth == segments.length - 1) {
                String scalar = scalarWithoutComment(mappingValue);
                int removeEnd = lineIndex + 1;
                if (scalar.isBlank()) {
                    removeEnd = childBounds(lines, lineIndex, end, indent).end();
                }
                for (int removeIndex = removeEnd - 1; removeIndex >= lineIndex; removeIndex--) {
                    lines.remove(removeIndex);
                }
                return String.join(ending, lines);
            }
            if (!scalarWithoutComment(mappingValue).isBlank()) {
                throw new IllegalArgumentException("YAML path " + key + " crosses a scalar at " + segments[depth]);
            }
            Bounds child = childBounds(lines, lineIndex, end, indent);
            start = child.start();
            end = child.end();
            indent = child.indent();
        }
        return String.join(ending, lines);
    }

    private String readWildcard(List<String> lines, String[] segments) {
        ParentLocation parent = locateParent(lines, segments);
        if (parent == null) {
            return "";
        }
        return selectedChildren(lines, parent.childBounds(), parent.indent(), true);
    }

    private boolean containsWildcard(List<String> lines, String[] segments) {
        ParentLocation parent = locateParent(lines, segments);
        if (parent == null) {
            return false;
        }
        return hasSelectedChild(lines, parent.childBounds(), parent.indent());
    }

    private void applyWildcard(List<String> lines, String[] segments, String value) {
        ParentLocation parent = locateParent(lines, segments);
        Map<Object, Object> desired = yamlMap(value);
        desired.remove("default");
        if (parent == null) {
            String[] parentSegments = Arrays.copyOf(segments, segments.length - 1);
            apply(lines, parentSegments, 0, 0, lines.size(), 0, mapBlock(desired));
            return;
        }
        Bounds bounds = parent.childBounds();
        List<int[]> removeRanges = new ArrayList<>();
        for (int index = bounds.start(); index < bounds.end();) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#") || indentation(line) != parent.indent()) {
                index++;
                continue;
            }
            int end = subtreeEnd(lines, index, bounds.end(), parent.indent());
            String name = mappingName(line, parent.indent());
            if (!"default".equals(name)) {
                removeRanges.add(new int[]{index, end});
            }
            index = end;
        }
        for (int range = removeRanges.size() - 1; range >= 0; range--) {
            int[] current = removeRanges.get(range);
            for (int index = current[1] - 1; index >= current[0]; index--) {
                lines.remove(index);
            }
        }
        ParentLocation updated = parentBounds(lines, segments);
        int insertAt = updated.childBounds().end();
        List<String> additions = mapLines(desired, parent.indent());
        lines.addAll(insertAt, additions);
    }

    private void removeWildcard(List<String> lines, String[] segments) {
        ParentLocation parent = locateParent(lines, segments);
        if (parent == null) {
            return;
        }
        Bounds bounds = parent.childBounds();
        List<int[]> removeRanges = new ArrayList<>();
        for (int index = bounds.start(); index < bounds.end();) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#") || indentation(line) != parent.indent()) {
                index++;
                continue;
            }
            int end = subtreeEnd(lines, index, bounds.end(), parent.indent());
            if (!"default".equals(mappingName(line, parent.indent()))) {
                removeRanges.add(new int[]{index, end});
            }
            index = end;
        }
        for (int range = removeRanges.size() - 1; range >= 0; range--) {
            int[] current = removeRanges.get(range);
            for (int index = current[1] - 1; index >= current[0]; index--) {
                lines.remove(index);
            }
        }
    }

    private void apply(List<String> lines, String[] segments, int depth, int start, int end, int indent, String value) {
        int lineIndex = find(lines, segments[depth], start, end, indent);
        if (lineIndex < 0) {
            int insertAt = insertionIndex(lines, start, end);
            List<String> addition = new ArrayList<>();
            for (int remaining = depth; remaining < segments.length; remaining++) {
                int currentIndent = indent + (remaining - depth) * 2;
                if (remaining == segments.length - 1) {
                    addition.addAll(renderMapping(segments[remaining], currentIndent, value, ""));
                } else {
                    addition.add(" ".repeat(currentIndent) + segments[remaining] + ":");
                }
            }
            lines.addAll(insertAt, addition);
            return;
        }
        String mappingValue = mappingValue(lines.get(lineIndex), indent);
        if (depth == segments.length - 1) {
            String suffix = commentSuffix(mappingValue);
            String scalar = scalarWithoutComment(mappingValue);
            int replaceEnd = lineIndex + 1;
            if (scalar.isBlank()) {
                replaceEnd = childBounds(lines, lineIndex, end, indent).end();
            }
            for (int removeIndex = replaceEnd - 1; removeIndex > lineIndex; removeIndex--) {
                lines.remove(removeIndex);
            }
            lines.remove(lineIndex);
            lines.addAll(lineIndex, renderMapping(segments[depth], indent, value, suffix));
            return;
        }
        if (!scalarWithoutComment(mappingValue).isBlank()) {
            throw new IllegalArgumentException("YAML path " + String.join(".", segments) + " crosses a scalar at " + segments[depth]);
        }
        Bounds child = childBounds(lines, lineIndex, end, indent);
        apply(lines, segments, depth + 1, child.start(), child.end(), child.indent(), value);
    }

    private Bounds childBounds(List<String> lines, int parentIndex, int parentEnd, int parentIndent) {
        int start = parentIndex + 1;
        int end = parentEnd;
        int indent = parentIndent + 2;
        int firstChild = -1;
        for (int index = start; index < parentEnd; index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int lineIndent = indentation(line);
            if (lineIndent <= parentIndent) {
                return new Bounds(start, index, indent);
            }
            indent = lineIndent;
            firstChild = index;
            break;
        }
        if (firstChild < 0) {
            return new Bounds(start, end, indent);
        }
        for (int index = firstChild + 1; index < parentEnd; index++) {
            String line = lines.get(index);
            if (!line.isBlank() && !line.stripLeading().startsWith("#") && indentation(line) <= parentIndent) {
                end = index;
                break;
            }
        }
        return new Bounds(start, end, indent);
    }

    private ParentLocation locateParent(List<String> lines, String[] segments) {
        if (segments.length < 2) {
            return null;
        }
        return parentBounds(lines, segments);
    }

    private ParentLocation parentBounds(List<String> lines, String[] segments) {
        int start = 0;
        int end = lines.size();
        int indent = 0;
        for (int depth = 0; depth < segments.length - 1; depth++) {
            int lineIndex = find(lines, segments[depth], start, end, indent);
            if (lineIndex < 0) {
                return null;
            }
            String mappingValue = mappingValue(lines.get(lineIndex), indent);
            if (!scalarWithoutComment(mappingValue).isBlank()) {
                return null;
            }
            Bounds child = childBounds(lines, lineIndex, end, indent);
            if (depth == segments.length - 2) {
                return new ParentLocation(lineIndex, child, child.indent());
            }
            start = child.start();
            end = child.end();
            indent = child.indent();
        }
        return null;
    }

    private String selectedChildren(List<String> lines, Bounds bounds, int indent, boolean excludeDefault) {
        List<String> selected = new ArrayList<>();
        for (int index = bounds.start(); index < bounds.end();) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#") || indentation(line) != indent) {
                index++;
                continue;
            }
            int end = subtreeEnd(lines, index, bounds.end(), indent);
            if (!excludeDefault || !"default".equals(mappingName(line, indent))) {
                for (int selectedIndex = index; selectedIndex < end; selectedIndex++) {
                    String selectedLine = lines.get(selectedIndex);
                    selected.add(selectedLine.isBlank() ? "" : selectedLine.substring(Math.min(indent, indentation(selectedLine))));
                }
            }
            index = end;
        }
        while (!selected.isEmpty() && selected.getFirst().isBlank()) selected.removeFirst();
        while (!selected.isEmpty() && selected.getLast().isBlank()) selected.removeLast();
        return String.join("\n", selected);
    }

    private boolean hasSelectedChild(List<String> lines, Bounds bounds, int indent) {
        for (int index = bounds.start(); index < bounds.end();) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#") || indentation(line) != indent) {
                index++;
                continue;
            }
            int end = subtreeEnd(lines, index, bounds.end(), indent);
            if (!"default".equals(mappingName(line, indent))) {
                return true;
            }
            index = end;
        }
        return false;
    }

    private int subtreeEnd(List<String> lines, int start, int boundEnd, int indent) {
        for (int index = start + 1; index < boundEnd; index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (indentation(line) <= indent) {
                return index;
            }
        }
        return boundEnd;
    }

    private String mappingName(String line, int indent) {
        String value = line.substring(Math.min(indent, line.length())).trim();
        int separator = mappingSeparator(value);
        if (separator < 0) {
            return "";
        }
        String name = value.substring(0, separator).trim();
        if (name.length() >= 2 && name.startsWith("'") && name.endsWith("'")) {
            return name.substring(1, name.length() - 1).replace("''", "'");
        }
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            return name.substring(1, name.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return name;
    }

    private String mappingValue(String line, int indent) {
        String value = line.substring(Math.min(indent, line.length())).trim();
        int separator = mappingSeparator(value);
        return separator < 0 ? "" : value.substring(separator + 1).trim();
    }

    private int mappingSeparator(String value) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (singleQuoted) {
                if (character == '\'' && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    index++;
                } else if (character == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                if (character == '\\') {
                    index++;
                } else if (character == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (character == '\'') {
                singleQuoted = true;
            } else if (character == '"') {
                doubleQuoted = true;
            } else if (character == ':' && (index + 1 == value.length() || Character.isWhitespace(value.charAt(index + 1)))) {
                return index;
            }
        }
        return -1;
    }

    private Map<Object, Object> yamlMap(String value) {
        Object parsed;
        try {
            parsed = structuredParser.parse(value == null || value.isBlank() ? "{}" : value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Expected a YAML map", exception);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a YAML map");
        }
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        map.forEach(result::put);
        return result;
    }

    private String mapBlock(Map<Object, Object> map) {
        if (map.isEmpty()) {
            return "{}";
        }
        return String.join("\n", mapLines(map, 0));
    }

    private List<String> mapLines(Map<Object, Object> map, int indent) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            String key = yamlKey(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?> nested) {
                lines.add(" ".repeat(indent) + key + ":");
                LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
                nested.forEach(copy::put);
                lines.addAll(mapLines(copy, indent + 2));
            } else {
                lines.add(" ".repeat(indent) + key + ": " + yamlValue(entry.getValue()));
            }
        }
        return lines;
    }

    private String yamlKey(Object value) {
        String key = value == null ? "null" : value.toString();
        return key.matches("[A-Za-z0-9_-]+") ? key : "'" + key.replace("'", "''") + "'";
    }

    private String yamlValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
            map.forEach(copy::put);
            return "{" + copy.entrySet().stream().map(entry -> yamlKey(entry.getKey()) + ": " + yamlValue(entry.getValue())).reduce((left, right) -> left + ", " + right).orElse("") + "}";
        }
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            values.forEach(item -> items.add(yamlValue(item)));
            return "[" + String.join(", ", items) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return formatScalar(value.toString());
    }

    private String[] segments(String key) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        char[] characters = key == null ? new char[0] : key.toCharArray();
        for (char character : characters) {
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '.') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped) current.append('\\');
        values.add(current.toString());
        return values.toArray(String[]::new);
    }

    private boolean isWildcard(String[] segments) {
        return segments.length > 0 && "*".equals(segments[segments.length - 1]);
    }

    private int find(List<String> lines, String key, int start, int end, int indent) {
        for (int index = start; index < end; index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#") || indentation(line) != indent) {
                continue;
            }
            if (key.equals(mappingName(line, indent))) {
                return index;
            }
        }
        return -1;
    }

    private int insertionIndex(List<String> lines, int start, int end) {
        int index = end;
        while (index > start && lines.get(index - 1).isBlank()) {
            index--;
        }
        return index;
    }

    private int indentation(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    private String blockValue(List<String> lines, Bounds bounds) {
        if (bounds.start() >= bounds.end()) {
            return "";
        }
        int baseIndent = bounds.indent();
        List<String> valueLines = new ArrayList<>();
        for (int index = bounds.start(); index < bounds.end(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                valueLines.add("");
            } else {
                int offset = Math.min(baseIndent, indentation(line));
                valueLines.add(line.substring(offset));
            }
        }
        while (!valueLines.isEmpty() && valueLines.getFirst().isBlank()) valueLines.removeFirst();
        while (!valueLines.isEmpty() && valueLines.getLast().isBlank()) valueLines.removeLast();
        return String.join("\n", valueLines);
    }

    private List<String> renderMapping(String key, int indent, String value, String suffix) {
        String resolved = value == null ? "" : value.strip();
        if (!resolved.contains("\n")) {
            return List.of(" ".repeat(indent) + key + ": " + formatScalar(resolved) + suffix);
        }
        List<String> result = new ArrayList<>();
        result.add(" ".repeat(indent) + key + ":" + suffix);
        List<String> valueLines = TextLines.split(resolved);
        int baseIndent = Integer.MAX_VALUE;
        for (String line : valueLines) {
            if (!line.isBlank()) baseIndent = Math.min(baseIndent, indentation(line));
        }
        if (baseIndent == Integer.MAX_VALUE) baseIndent = 0;
        for (String line : valueLines) {
            if (line.isBlank()) {
                result.add("");
            } else {
                int offset = Math.min(baseIndent, indentation(line));
                result.add(" ".repeat(indent + 2) + line.substring(offset));
            }
        }
        return result;
    }

    private String scalarWithoutComment(String value) {
        String trimmed = value == null ? "" : value.trim();
        int comment = commentIndex(trimmed);
        return (comment < 0 ? trimmed : trimmed.substring(0, comment)).trim();
    }

    private String commentSuffix(String value) {
        String trimmed = value == null ? "" : value.trim();
        int comment = commentIndex(trimmed);
        return comment < 0 ? "" : " " + trimmed.substring(comment).trim();
    }

    private int commentIndex(String value) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'' && !doubleQuoted) singleQuoted = !singleQuoted;
            if (character == '"' && !singleQuoted && (index == 0 || value.charAt(index - 1) != '\\')) doubleQuoted = !doubleQuoted;
            if (character == '#' && !singleQuoted && !doubleQuoted && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) return index;
        }
        return -1;
    }

    private String formatScalar(String value) {
        String resolved = value == null ? "" : value;
        if (resolved.matches("(?i:true|false|null|~|-?\\d+(?:\\.\\d+)?)") || resolved.startsWith("[") || resolved.startsWith("{") || resolved.startsWith("\"") || resolved.startsWith("'")) {
            return resolved;
        }
        return "'" + resolved.replace("'", "''") + "'";
    }

    private String decodeScalar(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private List<String> lines(String content) {
        return TextLines.split(content);
    }

    private String lineEnding(String content) {
        return content != null && content.contains("\r\n") ? "\r\n" : "\n";
    }

    private record Bounds(int start, int end, int indent) {
    }

    private record ParentLocation(int lineIndex, Bounds childBounds, int indent) {
    }
}
