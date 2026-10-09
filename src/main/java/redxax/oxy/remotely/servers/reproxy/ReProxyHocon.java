package redxax.oxy.remotely.servers.reproxy;

import redxax.oxy.remotely.network.config.NetworkConfigurationAdapter;
import restudio.rescreen.util.JsonTreeParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReProxyHocon implements NetworkConfigurationAdapter {
    private record Line(String text, String ending) {
    }

    private record Entry(int line, String prefix, String value, String suffix) {
    }

    private static final Pattern ASSIGNMENT = Pattern.compile("^(\\s*(?:\"([A-Za-z0-9_.-]+)\"|([A-Za-z0-9_.-]+))\\s*[:=]\\s*)(.*)$");
    private static final Pattern OBJECT = Pattern.compile("^\\s*(?:\"([A-Za-z0-9_.-]+)\"|([A-Za-z0-9_.-]+))\\s*\\{.*$");

    static int line(String content, String key) {
        requireKey(key);
        Entry entry = entries(lines(content)).get(key);
        return entry == null ? 0 : entry.line();
    }

    @Override
    public Reader prepare(String content) {
        Map<String, Entry> entries = entries(lines(content));
        return new Reader() {
            @Override
            public String read(String key) {
                requireKey(key);
                Entry entry = entries.get(key);
                if (entry != null) requireValue(entry.value());
                return entry == null ? "" : entry.value();
            }

            @Override
            public boolean contains(String key) {
                requireKey(key);
                return entries.containsKey(key);
            }
        };
    }

    @Override
    public String read(String content, String key) {
        return prepare(content).read(key);
    }

    @Override
    public boolean contains(String content, String key) {
        return prepare(content).contains(key);
    }

    @Override
    public String apply(String content, String key, String value) {
        requireKey(key);
        requireValue(value);
        List<Line> lines = lines(content);
        Map<String, Entry> entries = entries(lines);
        Entry entry = entries.get(key);
        if (entry != null) {
            requireValue(entry.value());
            Line line = lines.get(entry.line());
            lines.set(entry.line(), new Line(entry.prefix() + value + entry.suffix(), line.ending()));
        } else {
            String ending = lines.stream().map(Line::ending).filter(text -> !text.isEmpty()).findFirst().orElse("\n");
            int insert = lines.size();
            if (wrapped(lines)) {
                while (insert > 0 && (lines.get(insert - 1).text().isBlank() || lines.get(insert - 1).text().stripLeading().startsWith("#")
                        || lines.get(insert - 1).text().stripLeading().startsWith("//"))) insert--;
                if (insert == 0 || !lines.get(insert - 1).text().strip().matches("\\}\\s*(?:(?:#|//).*)?")) throw invalid();
                insert--;
            } else if (!lines.isEmpty() && lines.getLast().text().isEmpty()) {
                insert--;
            }
            if (insert > 0 && lines.get(insert - 1).ending().isEmpty()) {
                Line previous = lines.get(insert - 1);
                lines.set(insert - 1, new Line(previous.text(), ending));
            }
            lines.add(insert, new Line(key + ": " + value, insert < lines.size() ? ending : ""));
        }
        return join(lines);
    }

    @Override
    public String remove(String content, String key) {
        requireKey(key);
        List<Line> lines = lines(content);
        Entry entry = entries(lines).get(key);
        if (entry != null) {
            requireValue(entry.value());
            Line line = lines.get(entry.line());
            String suffix = entry.suffix().stripLeading();
            if (suffix.startsWith(",")) suffix = suffix.substring(1).stripLeading();
            if (suffix.startsWith("#") || suffix.startsWith("//")) lines.set(entry.line(), new Line(suffix, line.ending()));
            else lines.remove(entry.line());
        }
        return join(lines);
    }

    private static Map<String, Entry> entries(List<Line> lines) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        int root = wrapped(lines) ? 1 : 0;
        int depth = 0;
        boolean quoted = false;
        boolean triple = false;
        boolean block = false;
        boolean escaped = false;
        for (int index = 0; index < lines.size(); index++) {
            String text = lines.get(index).text();
            if (depth == root && !quoted && !triple && !block) {
                Matcher assignment = ASSIGNMENT.matcher(text);
                if (assignment.matches()) {
                    String key = assignment.group(2) == null ? assignment.group(3) : assignment.group(2);
                    boolean nested = assignment.group(3) != null && key.contains(".");
                    if (nested) key = key.substring(0, key.indexOf('.'));
                    String remainder = assignment.group(4);
                    int end = scalarEnd(remainder);
                    String value = end < 0 ? "" : remainder.substring(0, end);
                    if (end < 0) end = remainder.length();
                    String suffix = remainder.substring(end);
                    String following = suffix.stripLeading();
                    if (following.startsWith(",")) following = following.substring(1).stripLeading();
                    if (nested || !following.isEmpty() && !following.startsWith("#") && !following.startsWith("//")) value = "";
                    admit(entries, key, new Entry(index, assignment.group(1), value, suffix));
                } else if (text.stripLeading().startsWith("include ") || text.stripLeading().startsWith("include(")) {
                    throw new IllegalArgumentException("Use Direct Root Scalar Settings In The HOCON Configuration");
                } else {
                    Matcher object = OBJECT.matcher(text);
                    if (object.matches()) {
                        String key = object.group(1) == null ? object.group(2) : object.group(1);
                        if (object.group(2) != null && key.contains(".")) key = key.substring(0, key.indexOf('.'));
                        admit(entries, key, new Entry(index, "", "", ""));
                    }
                }
            }
            for (int cursor = 0; cursor < text.length(); cursor++) {
                char character = text.charAt(cursor);
                if (block) {
                    if (character == '*' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '/') {
                        block = false;
                        cursor++;
                    }
                    continue;
                }
                if (triple) {
                    if (text.startsWith("\"\"\"", cursor)) {
                        triple = false;
                        cursor += 2;
                    }
                    continue;
                }
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (character == '\\') escaped = true;
                    else if (character == '"') quoted = false;
                    continue;
                }
                if (character == '#' || character == '/' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '/') break;
                if (character == '/' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '*') {
                    block = true;
                    cursor++;
                } else if (text.startsWith("\"\"\"", cursor)) {
                    triple = true;
                    cursor += 2;
                } else if (character == '"') quoted = true;
                else if (character == '{' || character == '[') depth++;
                else if (character == '}' || character == ']') depth--;
                if (depth < 0) throw invalid();
            }
        }
        if (depth != 0 || quoted || triple || block) throw invalid();
        return entries;
    }

    private static void admit(Map<String, Entry> entries, String key, Entry entry) {
        Entry previous = entries.putIfAbsent(key, entry);
        if (previous != null) entries.put(key, new Entry(previous.line(), previous.prefix(), "", previous.suffix()));
    }

    private static boolean wrapped(List<Line> lines) {
        boolean block = false;
        for (Line line : lines) {
            String text = line.text();
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (block) {
                    if (character == '*' && index + 1 < text.length() && text.charAt(index + 1) == '/') {
                        block = false;
                        index++;
                    }
                    continue;
                }
                if (Character.isWhitespace(character)) continue;
                if (character == '#' || text.startsWith("//", index)) break;
                if (text.startsWith("/*", index)) {
                    block = true;
                    index++;
                    continue;
                }
                if (character != '{') return false;
                if (!text.substring(index + 1).stripLeading().matches("(?:(?:#|//).*)?")) throw invalid();
                return true;
            }
        }
        return false;
    }

    private static List<Line> lines(String content) {
        String text = content == null ? "" : content;
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character != '\n' && character != '\r') continue;
            int end = index + 1;
            if (character == '\r' && end < text.length() && text.charAt(end) == '\n') end++;
            lines.add(new Line(text.substring(start, index), text.substring(index, end)));
            start = end;
            index = end - 1;
        }
        lines.add(new Line(text.substring(start), ""));
        return lines;
    }

    private static String join(List<Line> lines) {
        StringBuilder result = new StringBuilder();
        for (Line line : lines) result.append(line.text()).append(line.ending());
        return result.toString();
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException("HOCON Connections Require Direct Root Scalar Settings");
    }

    private static void requireValue(String value) {
        if (value == null || value.isEmpty()) throw invalid();
        if (value.startsWith("\"")) {
            if (!JsonTreeParser.parse(value).isJsonPrimitive()) throw invalid();
        } else if (!value.matches("[A-Za-z0-9_./:+-]+") || value.contains("//")) throw invalid();
    }

    private static int scalarEnd(String value) {
        if (value.isEmpty() || value.startsWith("\"\"\"")) return -1;
        if (value.charAt(0) == '"') {
            boolean escaped = false;
            for (int index = 1; index < value.length(); index++) {
                char character = value.charAt(index);
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') return index + 1;
            }
            return -1;
        }
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end)) && value.charAt(end) != ',' && value.charAt(end) != '#'
                && !(value.charAt(end) == '/' && end + 1 < value.length() && value.charAt(end + 1) == '/')) end++;
        return end > 0 && value.substring(0, end).matches("[A-Za-z0-9_./:+-]+") ? end : -1;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Use A Direct Scalar Value In The HOCON Configuration");
    }
}
