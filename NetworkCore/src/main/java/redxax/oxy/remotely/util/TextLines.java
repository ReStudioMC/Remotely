package redxax.oxy.remotely.util;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class TextLines {
    private TextLines() {
    }

    public static List<String> split(String value) {
        String source = value == null ? "" : value;
        List<String> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < source.length()) {
            int separatorLength = lineSeparatorLength(source, index);
            if (separatorLength == 0) {
                index++;
                continue;
            }
            lines.add(source.substring(start, index));
            index += separatorLength;
            start = index;
        }
        lines.add(source.substring(start));
        return lines;
    }

    public static Stream<String> stream(String value) {
        if (value == null || value.isEmpty()) return Stream.empty();
        List<String> lines = split(value);
        if (lineSeparatorLength(value, value.length() - 1) != 0) lines.removeLast();
        return lines.stream();
    }

    private static int lineSeparatorLength(String value, int index) {
        char character = value.charAt(index);
        if (character == '\r') {
            return index + 1 < value.length() && value.charAt(index + 1) == '\n' ? 2 : 1;
        }
        return character == '\n' || character == 0x000B || character == 0x000C || character == 0x0085
                || character == 0x2028 || character == 0x2029 ? 1 : 0;
    }
}
