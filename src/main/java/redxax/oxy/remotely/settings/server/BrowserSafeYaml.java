package redxax.oxy.remotely.settings.server;

import redxax.oxy.remotely.util.TextLines;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class BrowserSafeYaml {
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(?:\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern EXPONENT = Pattern.compile("[-+]?\\d+[eE][-+]?\\d+");
    private BrowserSafeYaml() {
    }

    public static Object parse(String source) {
        List<Line> lines = new ArrayList<>();
        List<String> sourceLines = TextLines.split(source);
        for (int number = 0; number < sourceLines.size(); number++) {
            String raw = sourceLines.get(number);
            int indent = indent(raw);
            String content = comment(raw.substring(indent)).trim();
            if (!content.isEmpty() && !content.equals("---") && !content.equals("...")) lines.add(new Line(indent, content, number + 1));
        }
        if (lines.isEmpty()) return null;
        Parser parser = new Parser(lines);
        Object value = parser.block(lines.getFirst().indent());
        if (parser.index < lines.size()) throw parser.error("Unexpected content", lines.get(parser.index));
        return value;
    }

    private static int indent(String value) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == ' ') count++;
        if (count < value.length() && value.charAt(count) == '\t') throw new IllegalArgumentException("YAML tabs are unsupported");
        return count;
    }

    private static String comment(String value) {
        boolean single = false;
        boolean doubled = false;
        boolean escaped = false;
        int square = 0;
        int curly = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (doubled) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') doubled = false;
            } else if (single) {
                if (character == '\'' && index + 1 < value.length() && value.charAt(index + 1) == '\'') index++;
                else if (character == '\'') single = false;
            } else if (character == '\'') single = true;
            else if (character == '"') doubled = true;
            else if (character == '[') square++;
            else if (character == ']') square--;
            else if (character == '{') curly++;
            else if (character == '}') curly--;
            else if (character == '#' && square == 0 && curly == 0 && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) return value.substring(0, index);
        }
        return value;
    }

    private static int separator(String value, char expected) {
        boolean single = false;
        boolean doubled = false;
        boolean escaped = false;
        int square = 0;
        int curly = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (doubled) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') doubled = false;
                continue;
            }
            if (single) {
                if (character == '\'' && index + 1 < value.length() && value.charAt(index + 1) == '\'') index++;
                else if (character == '\'') single = false;
                continue;
            }
            if (character == '\'') single = true;
            else if (character == '"') doubled = true;
            else if (character == '[') square++;
            else if (character == ']') square--;
            else if (character == '{') curly++;
            else if (character == '}') curly--;
            else if (character == expected && square == 0 && curly == 0) return index;
        }
        return -1;
    }

    private static Object scalar(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) return "";
        if (value.startsWith("[") && value.endsWith("]")) return flowList(value.substring(1, value.length() - 1));
        if (value.startsWith("{") && value.endsWith("}")) return flowMap(value.substring(1, value.length() - 1));
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) return value.substring(1, value.length() - 1).replace("''", "'");
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return doubleQuoted(value.substring(1, value.length() - 1));
        if (value.equals("~") || value.equalsIgnoreCase("null")) return null;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        char first = value.charAt(0);
        if (first != '+' && first != '-' && first != '.' && (first < '0' || first > '9')) return value;
        String number = value.replace("_", "");
        try {
            if (INTEGER.matcher(number).matches()) return integer(number);
            if (DECIMAL.matcher(number).matches() || EXPONENT.matcher(number).matches()) return new BigDecimal(number);
        } catch (NumberFormatException ignored) {
        }
        return value;
    }

    private static Number integer(String value) {
        BigInteger parsed = new BigInteger(value);
        if (parsed.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0 && parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
            return parsed.intValue();
        }
        if (parsed.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0 && parsed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
            return parsed.longValue();
        }
        return parsed;
    }

    private static String doubleQuoted(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!escaped && character == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                result.append(switch (character) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> character;
                });
                escaped = false;
            } else result.append(character);
        }
        if (escaped) result.append('\\');
        return result.toString();
    }

    private static List<Object> flowList(String content) {
        if (content.isBlank()) return new ArrayList<>();
        List<Object> result = new ArrayList<>();
        for (String item : split(content)) result.add(scalar(item));
        return result;
    }

    private static Map<String, Object> flowMap(String content) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (content.isBlank()) return result;
        for (String item : split(content)) {
            int separator = separator(item, ':');
            if (separator < 0) throw new IllegalArgumentException("Expected a YAML map entry");
            put(result, String.valueOf(scalar(item.substring(0, separator))), scalar(item.substring(separator + 1)));
        }
        return result;
    }

    private static List<String> split(String content) {
        List<String> values = new ArrayList<>();
        int start = 0;
        while (start <= content.length()) {
            int separator = separator(content.substring(start), ',');
            if (separator < 0) {
                values.add(content.substring(start).trim());
                break;
            }
            values.add(content.substring(start, start + separator).trim());
            start += separator + 1;
        }
        return values;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (map.containsKey(key)) throw new IllegalArgumentException("Duplicate YAML key: " + key);
        map.put(key, value);
    }

    private record Line(int indent, String content, int number) {
    }

    private static final class Parser {
        private final List<Line> lines;
        private int index;

        private Parser(List<Line> lines) {
            this.lines = lines;
        }

        private Object block(int indent) {
            if (index >= lines.size()) return null;
            Line line = lines.get(index);
            if (line.content().startsWith("[") || line.content().startsWith("{")) {
                index++;
                return scalar(line.content());
            }
            return line.content().equals("-") || line.content().startsWith("- ") ? sequence(indent) : mapping(indent);
        }

        private List<Object> sequence(int indent) {
            List<Object> values = new ArrayList<>();
            while (index < lines.size()) {
                Line line = lines.get(index);
                if (line.indent() < indent) break;
                if (line.indent() != indent || !(line.content().equals("-") || line.content().startsWith("- "))) throw error("Expected a YAML list item", line);
                String item = line.content().length() == 1 ? "" : line.content().substring(2).trim();
                index++;
                if (item.isEmpty()) {
                    values.add(child(indent, line));
                    continue;
                }
                int separator = separator(item, ':');
                if (separator < 0) {
                    values.add(scalar(item));
                    continue;
                }
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                String key = String.valueOf(scalar(item.substring(0, separator)));
                String rawValue = item.substring(separator + 1).trim();
                put(map, key, rawValue.isEmpty() ? child(indent, line) : scalar(rawValue));
                while (index < lines.size() && lines.get(index).indent() > indent) {
                    Line nested = lines.get(index);
                    if (nested.content().equals("-") || nested.content().startsWith("- ")) throw error("Unexpected YAML list item", nested);
                    entry(map, nested.indent());
                }
                values.add(map);
            }
            return values;
        }

        private Map<String, Object> mapping(int indent) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            while (index < lines.size() && lines.get(index).indent() == indent && !lines.get(index).content().startsWith("- ")) entry(values, indent);
            return values;
        }

        private void entry(Map<String, Object> values, int indent) {
            Line line = lines.get(index);
            if (line.indent() != indent) throw error("Unexpected YAML indentation", line);
            int separator = separator(line.content(), ':');
            if (separator < 0) throw error("Expected a YAML map entry", line);
            String key = String.valueOf(scalar(line.content().substring(0, separator)));
            String rawValue = line.content().substring(separator + 1).trim();
            index++;
            put(values, key, rawValue.isEmpty() ? child(indent, line) : scalar(rawValue));
        }

        private Object child(int indent, Line parent) {
            if (index >= lines.size() || lines.get(index).indent() <= indent) return null;
            int childIndent = lines.get(index).indent();
            if (childIndent <= parent.indent()) throw error("Expected indented YAML content", lines.get(index));
            return block(childIndent);
        }

        private IllegalArgumentException error(String message, Line line) {
            return new IllegalArgumentException(message + " at line " + line.number());
        }
    }
}
