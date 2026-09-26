package redxax.oxy.remotely.flow.ui.studio;

public final class ReSyncNaming {
    private ReSyncNaming() {
    }

    public static String camelCase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        boolean capitalize = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean letter = current >= 'a' && current <= 'z' || current >= 'A' && current <= 'Z';
            boolean digit = current >= '0' && current <= '9';
            if (!letter && !digit) {
                capitalize = !result.isEmpty();
            } else if (result.isEmpty()) {
                if (letter) {
                    result.append(Character.toLowerCase(current));
                } else {
                    result.append(current);
                }
            } else {
                result.append(capitalize ? Character.toUpperCase(current) : current);
                capitalize = false;
            }
        }
        return result.toString();
    }

    public static String copyId(String source) {
        String base = camelCase(source);
        return base.isEmpty() ? "newCopy" : base + "Copy";
    }
}
