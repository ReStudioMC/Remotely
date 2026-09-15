package redxax.oxy.remotely.network.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PropertiesConfigurationAdapter implements NetworkConfigurationAdapter {
    @Override
    public String read(String content, String key) {
        List<PhysicalLine> lines = lines(content);
        List<PropertyEntry> matches = matchingEntries(lines, key);
        if (matches.isEmpty()) {
            return "";
        }
        return value(lines, matches.getLast()).trim();
    }

    @Override
    public boolean contains(String content, String key) {
        return !matchingEntries(lines(content), key).isEmpty();
    }

    @Override
    public String apply(String content, String key, String value) {
        Objects.requireNonNull(key, "key");
        List<PhysicalLine> lines = lines(content);
        List<PropertyEntry> matches = matchingEntries(lines, key);
        String replacement = value == null ? "" : value;
        if (!matches.isEmpty()) {
            PropertyEntry entry = matches.getLast();
            PhysicalLine line = lines.get(entry.startLine());
            String suffix = entry.continuation() ? "" : trailingWhitespace(line.text(), entry.valueStart());
            String prefix = line.text().substring(0, entry.valueStart());
            if (!entry.hasSeparator()) {
                prefix += "=";
            }
            String updated = prefix + replacement + suffix;
            lines.set(entry.startLine(), new PhysicalLine(updated, line.ending()));
            if (entry.continuation()) {
                lines.subList(entry.startLine() + 1, entry.endLineExclusive()).clear();
            }
            return join(lines);
        }
        append(lines, key + "=" + replacement);
        return join(lines);
    }

    @Override
    public String remove(String content, String key) {
        Objects.requireNonNull(key, "key");
        List<PhysicalLine> lines = lines(content);
        List<PropertyEntry> matches = matchingEntries(lines, key);
        for (int index = matches.size() - 1; index >= 0; index--) {
            PropertyEntry entry = matches.get(index);
            lines.subList(entry.startLine(), entry.endLineExclusive()).clear();
        }
        return join(lines);
    }

    private List<PropertyEntry> matchingEntries(List<PhysicalLine> lines, String key) {
        Objects.requireNonNull(key, "key");
        List<PropertyEntry> matches = new ArrayList<>();
        for (int index = 0; index < lines.size();) {
            PropertyEntry entry = parseEntry(lines, index);
            if (entry == null) {
                index++;
                continue;
            }
            if (key.equals(entry.key()) || key.equals(entry.rawKey())) {
                matches.add(entry);
            }
            index = entry.endLineExclusive();
        }
        return matches;
    }

    private PropertyEntry parseEntry(List<PhysicalLine> lines, int index) {
        String line = lines.get(index).text();
        int keyStart = 0;
        while (keyStart < line.length() && isWhitespace(line.charAt(keyStart))) {
            keyStart++;
        }
        if (keyStart >= line.length() || line.charAt(keyStart) == '#' || line.charAt(keyStart) == '!') {
            return null;
        }

        int cursor = keyStart;
        boolean escaped = false;
        while (cursor < line.length()) {
            char character = line.charAt(cursor);
            if (escaped) {
                escaped = false;
                cursor++;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                cursor++;
                continue;
            }
            if (character == '=' || character == ':' || isWhitespace(character)) {
                break;
            }
            cursor++;
        }

        int keyEnd = cursor;
        int valueStart = cursor;
        if (cursor < line.length()) {
            if (isWhitespace(line.charAt(cursor))) {
                while (cursor < line.length() && isWhitespace(line.charAt(cursor))) {
                    cursor++;
                }
                if (cursor < line.length() && (line.charAt(cursor) == '=' || line.charAt(cursor) == ':')) {
                    cursor++;
                    while (cursor < line.length() && isWhitespace(line.charAt(cursor))) {
                        cursor++;
                    }
                }
                valueStart = cursor;
            } else {
                cursor++;
                while (cursor < line.length() && isWhitespace(line.charAt(cursor))) {
                    cursor++;
                }
                valueStart = cursor;
            }
        }

        int endLineExclusive = index + 1;
        while (endLineExclusive < lines.size() && continues(lines.get(endLineExclusive - 1).text())) {
            endLineExclusive++;
        }
        String rawKey = line.substring(keyStart, keyEnd);
        return new PropertyEntry(index, endLineExclusive, valueStart, keyEnd < line.length(), rawKey, unescape(rawKey));
    }

    private String value(List<PhysicalLine> lines, PropertyEntry entry) {
        StringBuilder result = new StringBuilder(lines.get(entry.startLine()).text().substring(entry.valueStart()));
        for (int index = entry.startLine() + 1; index < entry.endLineExclusive(); index++) {
            if (result.length() > 0 && result.charAt(result.length() - 1) == '\\') {
                result.setLength(result.length() - 1);
            }
            String continuation = lines.get(index).text();
            int start = 0;
            while (start < continuation.length() && isWhitespace(continuation.charAt(start))) {
                start++;
            }
            result.append(continuation.substring(start));
        }
        return result.toString();
    }

    private String trailingWhitespace(String line, int valueStart) {
        int end = line.length();
        while (end > valueStart && isWhitespace(line.charAt(end - 1)) && !isEscaped(line, end - 1)) {
            end--;
        }
        return line.substring(end);
    }

    private boolean isEscaped(String line, int index) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= 0 && line.charAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private boolean continues(String line) {
        int backslashes = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\' || index + 1 >= value.length()) {
                result.append(character);
                continue;
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case 't' -> result.append('\t');
                case 'r' -> result.append('\r');
                case 'n' -> result.append('\n');
                case 'f' -> result.append('\f');
                case 'u' -> {
                    if (index + 4 < value.length()) {
                        int code = 0;
                        boolean valid = true;
                        for (int offset = 1; offset <= 4; offset++) {
                            int digit = Character.digit(value.charAt(index + offset), 16);
                            if (digit < 0) {
                                valid = false;
                                break;
                            }
                            code = code * 16 + digit;
                        }
                        if (valid) {
                            result.append((char) code);
                            index += 4;
                        } else {
                            result.append('u');
                        }
                    } else {
                        result.append('u');
                    }
                }
                default -> result.append(escaped);
            }
        }
        return result.toString();
    }

    private boolean isWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\f';
    }

    private List<PhysicalLine> lines(String content) {
        String source = content == null ? "" : content;
        List<PhysicalLine> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character != '\r' && character != '\n') {
                continue;
            }
            String ending;
            if (character == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') {
                ending = "\r\n";
                index++;
            } else {
                ending = String.valueOf(character);
            }
            lines.add(new PhysicalLine(source.substring(start, index + 1 - ending.length()), ending));
            start = index + 1;
        }
        if (start < source.length() || lines.isEmpty()) {
            lines.add(new PhysicalLine(source.substring(start), ""));
        }
        return lines;
    }

    private void append(List<PhysicalLine> lines, String text) {
        String ending = preferredEnding(lines);
        if (lines.size() == 1 && lines.getFirst().text().isEmpty() && lines.getFirst().ending().isEmpty()) {
            lines.set(0, new PhysicalLine(text, ending));
            return;
        }
        if (!lines.isEmpty() && lines.getLast().ending().isEmpty() && !lines.getLast().text().isEmpty()) {
            PhysicalLine last = lines.removeLast();
            lines.add(new PhysicalLine(last.text(), ending));
        }
        lines.add(new PhysicalLine(text, ending));
    }

    private String preferredEnding(List<PhysicalLine> lines) {
        for (PhysicalLine line : lines) {
            if (!line.ending().isEmpty()) {
                return line.ending();
            }
        }
        return "\n";
    }

    private String join(List<PhysicalLine> lines) {
        StringBuilder result = new StringBuilder();
        for (PhysicalLine line : lines) {
            result.append(line.text()).append(line.ending());
        }
        return result.toString();
    }

    private record PhysicalLine(String text, String ending) {
    }

    private record PropertyEntry(int startLine, int endLineExclusive, int valueStart, boolean hasSeparator, String rawKey, String key) {
        private boolean continuation() {
            return endLineExclusive > startLine + 1;
        }
    }
}
