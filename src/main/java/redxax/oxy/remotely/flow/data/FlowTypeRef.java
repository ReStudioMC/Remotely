package redxax.oxy.remotely.flow.data;

import restudio.resync.flow.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FlowTypeRef {
    private String typeId;
    private List<FlowTypeRef> arguments;

    public FlowTypeRef() {
        this("any", List.of());
    }

    public FlowTypeRef(String typeId, List<FlowTypeRef> arguments) {
        this.typeId = typeId != null && !typeId.isBlank() ? typeId.strip().toLowerCase() : "any";
        this.arguments = arguments != null ? List.copyOf(arguments) : List.of();
    }

    public String getTypeId() {
        return typeId;
    }

    public List<FlowTypeRef> getArguments() {
        return arguments;
    }

    public boolean isResolved() {
        if (isTypeVariable()) {
            return true;
        }
        if ("resource_reference".equals(typeId)) {
            return FlowDataType.fromString(typeId).isResolved() && arguments.size() <= 1
                && (arguments.isEmpty() || arguments.getFirst().arguments.isEmpty()
                    && arguments.getFirst().isResourceIdentity());
        }
        return FlowDataType.fromString(typeId).isResolved() && arguments.stream().allMatch(FlowTypeRef::isResolved);
    }

    private boolean isResourceIdentity() {
        int separator = typeId.indexOf(':');
        if (separator < 0) {
            return FlowDataType.fromString(typeId).isResolved();
        }
        try {
            TypeReference.of(typeId.substring(0, separator), typeId.substring(separator + 1));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean isAssignableFrom(FlowTypeRef source) {
        if (source == null) {
            return false;
        }
        if (isTypeVariable() || source.isTypeVariable()) {
            return true;
        }
        if (!FlowDataType.fromString(typeId).isAssignableFrom(FlowDataType.fromString(source.typeId))) {
            return false;
        }
        if ("resource_reference".equals(typeId)) {
            return arguments.isEmpty() || source.arguments.size() == 1
                && arguments.getFirst().typeId.equalsIgnoreCase(source.arguments.getFirst().typeId);
        }
        if (arguments.isEmpty()) {
            return true;
        }
        if (arguments.size() != source.arguments.size()) {
            return false;
        }
        for (int index = 0; index < arguments.size(); index++) {
            if (!arguments.get(index).isAssignableFrom(source.arguments.get(index))) {
                return false;
            }
        }
        return true;
    }

    public boolean isTypeVariable() {
        return typeId.startsWith("type:") && typeId.length() > "type:".length() && arguments.isEmpty();
    }

    public String getTypeVariableName() {
        return isTypeVariable() ? typeId.substring("type:".length()) : "";
    }

    public FlowTypeRef normalizedGenerics() {
        List<FlowTypeRef> normalizedArguments = arguments.stream().map(FlowTypeRef::normalizedGenerics).toList();
        if (normalizedArguments.isEmpty()) {
            if (List.of("list", "set", "queue", "stack", "optional", "result", "job_reference").contains(typeId)) {
                return new FlowTypeRef(typeId, List.of(simple("any")));
            }
            if ("map".equals(typeId)) {
                return new FlowTypeRef(typeId, List.of(simple("any"), simple("any")));
            }
        }
        return normalizedArguments.equals(arguments) ? this : new FlowTypeRef(typeId, normalizedArguments);
    }

    public static FlowTypeRef simple(String typeId) {
        return new FlowTypeRef(typeId, List.of());
    }

    public static FlowTypeRef parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return simple("any");
        }
        Parser parser = new Parser(expression);
        FlowTypeRef type = parser.parseType();
        parser.requireEnd();
        return type;
    }

    @Override
    public String toString() {
        if (arguments.isEmpty()) {
            return typeId;
        }
        return typeId + "<" + String.join(",", arguments.stream().map(FlowTypeRef::toString).toList()) + ">";
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof FlowTypeRef type && typeId.equals(type.typeId) && arguments.equals(type.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeId, arguments);
    }

    private static final class Parser {
        private final String value;
        private int offset;

        private Parser(String value) {
            this.value = value;
        }

        private FlowTypeRef parseType() {
            skipWhitespace();
            int start = offset;
            while (offset < value.length() && isIdentifierCharacter(value.charAt(offset))) {
                offset++;
            }
            if (start == offset) {
                throw new IllegalArgumentException("Expected type identifier at " + offset + " in " + value);
            }
            String id = value.substring(start, offset).toLowerCase();
            skipWhitespace();
            List<FlowTypeRef> arguments = new ArrayList<>();
            if (offset < value.length() && value.charAt(offset) == '<') {
                offset++;
                do {
                    arguments.add(parseType());
                    skipWhitespace();
                    if (offset < value.length() && value.charAt(offset) == ',') {
                        offset++;
                    } else {
                        break;
                    }
                } while (true);
                skipWhitespace();
                if (offset >= value.length() || value.charAt(offset) != '>') {
                    throw new IllegalArgumentException("Unclosed type arguments in " + value);
                }
                offset++;
            }
            return new FlowTypeRef(id, arguments);
        }

        private void requireEnd() {
            skipWhitespace();
            if (offset != value.length()) {
                throw new IllegalArgumentException("Unexpected type expression at " + offset + " in " + value);
            }
        }

        private void skipWhitespace() {
            while (offset < value.length() && Character.isWhitespace(value.charAt(offset))) {
                offset++;
            }
        }

        private boolean isIdentifierCharacter(char character) {
            return Character.isLetterOrDigit(character) || character == '_' || character == ':' || character == '.' || character == '-';
        }
    }
}
