package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class CoreGraphWorkspacePatch {
    public static final String SET = "set";
    public static final String REMOVE = "remove";
    public static final String ARRAY_ADD = "array_add";
    public static final String ARRAY_REMOVE = "array_remove";
    public static final String ARRAY_REORDER = "array_reorder";

    private static final int MAX_PATCHES = 512;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_PATH_SEGMENTS = 64;
    private static final int MAX_VALUE_BYTES = 1_048_576;
    private static final Set<String> IMMUTABLE_ROOT_FIELDS = Set.of(
        "schemaVersion", "resource", "revision", "catalogBinding");
    private static final Set<String> MANAGED_NODE_FIELDS = Set.of("instanceId", "definition", "definitionVersion");
    private static final Set<String> MANAGED_FUNCTION_FIELDS = Set.of("function", "revision");

    private CoreGraphWorkspacePatch() {
    }

    public static List<WorkspacePatch<JsonValue>> diff(GraphDocument before, GraphDocument after) {
        Objects.requireNonNull(before, "Before graph is required");
        Objects.requireNonNull(after, "After graph is required");
        requireCompatibleIdentity(before, after);
        if (before.revision() != after.revision()) {
            throw new IllegalArgumentException("Graph revision cannot change through workspace patches");
        }
        JsonObject previous = GraphDocumentCodec.INSTANCE.encode(before);
        JsonObject next = GraphDocumentCodec.INSTANCE.encode(after);
        ArrayList<WorkspacePatch<JsonValue>> patches = new ArrayList<>();
        Object previousRoot = mutableCopy(previous);
        Object nextRoot = mutableCopy(next);
        diffValue("", previous, next, previousRoot, nextRoot, patches);
        requirePatchBatch(patches);
        List<WorkspacePatch<JsonValue>> result = List.copyOf(patches);
        if (!result.isEmpty() && !apply(before, result).canonicalJson().equals(after.canonicalJson())) {
            throw new IllegalArgumentException("Workspace patch batch cannot reproduce the requested graph exactly");
        }
        return result;
    }

    public static GraphDocument apply(GraphDocument base, List<WorkspacePatch<JsonValue>> patches) {
        Objects.requireNonNull(base, "Base graph is required");
        Objects.requireNonNull(patches, "Patches are required");
        if (patches.isEmpty()) {
            throw new IllegalArgumentException("Workspace patch batch requires at least one operation");
        }
        requirePatchBatch(patches);
        JsonObject encoded = GraphDocumentCodec.INSTANCE.encode(base);
        Object original = mutableCopy(encoded);
        Object mutable = mutableCopy(encoded);
        requireSupportedBatch(mutable, patches);
        for (WorkspacePatch<JsonValue> patch : patches) {
            applyOne(mutable, patch);
        }
        validateOrderedTransitions(original, mutable, patches);
        validateOrderedSequences(original, mutable, patches);
        normalizeKnownArrays(mutable, mutable, List.of());
        removeCanonicalEmptyOptionals(mutable);
        requireManagedCollectionsPreserved(original, mutable);
        return decodeLossless(mutable);
    }

    public static GraphDocument rebase(GraphDocument originalBase, GraphDocument desired, GraphDocument latest) {
        Objects.requireNonNull(originalBase, "Original base graph is required");
        Objects.requireNonNull(desired, "Desired graph is required");
        Objects.requireNonNull(latest, "Latest graph is required");
        requireCompatibleResourceIdentity(originalBase, latest);
        List<WorkspacePatch<JsonValue>> patches = diff(originalBase, desired);
        Object original = mutableCopy(GraphDocumentCodec.INSTANCE.encode(originalBase));
        Object candidate = mutableCopy(GraphDocumentCodec.INSTANCE.encode(latest));
        for (WorkspacePatch<JsonValue> patch : patches) {
            applyRebased(original, candidate, patch);
        }
        normalizeKnownArrays(candidate, candidate, List.of());
        removeCanonicalEmptyOptionals(candidate);
        return decodeLossless(candidate);
    }

    public static void validate(GraphDocument base, List<WorkspacePatch<JsonValue>> patches) {
        apply(base, patches);
    }

    public static boolean containsNumericPointerSegment(String path) {
        return pointerSegments(path).stream().anyMatch(CoreGraphWorkspacePatch::numericSegment);
    }

    private static void requireCompatibleIdentity(GraphDocument before, GraphDocument after) {
        requireCompatibleResourceIdentity(before, after);
        if (!before.catalogBinding().equals(after.catalogBinding())) {
            throw new IllegalArgumentException("Graph catalog binding cannot change through workspace patches");
        }
    }

    private static void requireCompatibleResourceIdentity(GraphDocument before, GraphDocument after) {
        if (!before.schemaVersion().equals(after.schemaVersion())) {
            throw new IllegalArgumentException("Graph schema version cannot change through workspace patches");
        }
        if (!before.resource().equals(after.resource())) {
            throw new IllegalArgumentException("Graph resource cannot change through workspace patches");
        }
    }

    private static void diffValue(String path, JsonValue before, JsonValue after, Object beforeRoot, Object afterRoot,
                                  List<WorkspacePatch<JsonValue>> patches) {
        if (same(before, after)) {
            return;
        }
        if (before instanceof JsonObject previous && after instanceof JsonObject next) {
            TreeSet<String> keys = new TreeSet<>();
            keys.addAll(previous.fields().keySet());
            keys.addAll(next.fields().keySet());
            for (String key : keys) {
                if (path.isEmpty() && IMMUTABLE_ROOT_FIELDS.contains(key)) {
                    if (!"revision".equals(key) && !same(previous.value(key), next.value(key))) {
                        throw new IllegalArgumentException("Immutable graph field changed: " + key);
                    }
                    continue;
                }
                JsonValue oldValue = previous.value(key);
                JsonValue newValue = next.value(key);
                String childPath = path + "/" + escape(key);
                if (!same(oldValue, newValue)) {
                    requireEditablePath(pointerSegments(childPath));
                }
                IdentityArray childArray = identityArray(pointerSegments(childPath));
                if (oldValue == null && newValue instanceof JsonArray addedArray && childArray != null) {
                    diffArray(childPath, List.of(), addedArray.values(), beforeRoot, afterRoot, patches);
                } else if (newValue == null && oldValue instanceof JsonArray removedArray && childArray != null) {
                    diffArray(childPath, removedArray.values(), List.of(), beforeRoot, afterRoot, patches);
                } else if (oldValue == null) {
                    patches.add(new WorkspacePatch<>(SET, childPath, newValue));
                } else if (newValue == null) {
                    patches.add(new WorkspacePatch<>(REMOVE, childPath, JsonValue.nullValue()));
                } else {
                    diffValue(childPath, oldValue, newValue, beforeRoot, afterRoot, patches);
                }
            }
            return;
        }
        if (before instanceof JsonArray previous && after instanceof JsonArray next) {
            diffArray(path, previous.values(), next.values(), beforeRoot, afterRoot, patches);
            return;
        }
        if (after == null) {
            patches.add(new WorkspacePatch<>(REMOVE, path, JsonValue.nullValue()));
        } else {
            patches.add(new WorkspacePatch<>(SET, path, after));
        }
    }

    private static void diffArray(String path, List<JsonValue> before, List<JsonValue> after, Object beforeRoot, Object afterRoot,
                                  List<WorkspacePatch<JsonValue>> patches) {
        List<String> segments = pointerSegments(path);
        IdentityArray identityArray = identityArray(segments);
        if (identityArray == null) {
            if (!same(JsonValue.array(before), JsonValue.array(after))) {
                patches.add(new WorkspacePatch<>(SET, path, JsonValue.array(after)));
            }
            return;
        }
        Map<String, JsonValue> previous = stableValues(identityArray, before);
        Map<String, JsonValue> next = stableValues(identityArray, after);
        if (previous == null || next == null) {
            throw new IllegalArgumentException("Canonical identity array contains a missing or duplicate identity: " + path);
        }
        boolean beforeOrdered = orderSensitive(beforeRoot, segments, identityArray);
        boolean afterOrdered = orderSensitive(afterRoot, segments, identityArray);
        if (beforeOrdered != afterOrdered) {
            if (!same(JsonValue.array(before), JsonValue.array(after))) {
                throw new IllegalArgumentException("Changing ordered collection behavior and members in one workspace patch is unsupported: " + path);
            }
            if (!afterOrdered && !canonicalIdentityOrder(next)) {
                throw new IllegalArgumentException("Disabling ordered collection behavior requires canonical member order: " + path);
            }
        }
        List<String> expectedOrder = intermediateOrder(previous, next);
        List<String> desiredOrder = List.copyOf(next.keySet());
        boolean reordered = afterOrdered && !expectedOrder.equals(desiredOrder);
        if (reordered && !repeatableElementsPath(segments)) {
            throw new IllegalArgumentException("Ordered identity collection does not support reordering: " + path);
        }
        TreeSet<String> identities = new TreeSet<>();
        identities.addAll(previous.keySet());
        identities.addAll(next.keySet());
        for (String identity : identities) {
            JsonValue oldValue = previous.get(identity);
            JsonValue newValue = next.get(identity);
            if (oldValue != null && newValue == null) {
                patches.add(new WorkspacePatch<>(ARRAY_REMOVE, path, oldValue));
            } else if (oldValue != null) {
                diffValue(path + "/@" + escape(identity), oldValue, newValue, beforeRoot, afterRoot, patches);
            }
        }
        List<String> additions = afterOrdered ? next.keySet().stream().filter(identity -> !previous.containsKey(identity)).toList()
            : identities.stream().filter(identity -> !previous.containsKey(identity)).toList();
        for (String identity : additions) {
            patches.add(new WorkspacePatch<>(ARRAY_ADD, path, next.get(identity)));
        }
        if (reordered) {
            patches.add(new WorkspacePatch<>(ARRAY_REORDER, path, reorderValue(expectedOrder, desiredOrder)));
        }
    }

    private static List<String> intermediateOrder(Map<String, JsonValue> before, Map<String, JsonValue> after) {
        List<String> retained = before.keySet().stream().filter(after::containsKey).toList();
        List<String> expected = new ArrayList<>(retained);
        after.keySet().stream().filter(identity -> !before.containsKey(identity)).forEach(expected::add);
        return List.copyOf(expected);
    }

    private static JsonObject reorderValue(List<String> expected, List<String> order) {
        return JsonValue.object(Map.of(
            "expected", JsonValue.array(expected.stream().map(JsonValue::of).toList()),
            "order", JsonValue.array(order.stream().map(JsonValue::of).toList())));
    }

    private static boolean canonicalIdentityOrder(Map<String, JsonValue> values) {
        return List.copyOf(values.keySet()).equals(values.keySet().stream().sorted().toList());
    }

    private static Map<String, JsonValue> stableValues(IdentityArray identityArray, List<JsonValue> values) {
        LinkedHashMap<String, JsonValue> result = new LinkedHashMap<>();
        for (JsonValue value : values) {
            String identity = stableIdentity(identityArray, value);
            if (identity == null || result.put(identity, value) != null) {
                return null;
            }
        }
        return result;
    }

    private static String stableIdentity(IdentityArray identityArray, JsonValue value) {
        if (!(value instanceof JsonObject object)) {
            return null;
        }
        JsonValue identity = object.value(identityArray.field());
        String valueText;
        if (identityArray.objectIdentity()) {
            try {
                valueText = IdentityCodec.decodeLocator(identity).canonicalText();
            } catch (RuntimeException exception) {
                valueText = null;
            }
        } else {
            valueText = stringValue(identity);
        }
        return valueText;
    }

    private static String stringValue(JsonValue value) {
        return value instanceof JsonValue.JsonString string ? string.value() : null;
    }

    private static void applyOne(Object root, WorkspacePatch<JsonValue> patch) {
        Objects.requireNonNull(patch, "Patch is required");
        String operation = Objects.requireNonNull(patch.op(), "Patch operation is required");
        if (!operation.equals(operation.trim())) {
            throw new IllegalArgumentException("Workspace patch operation must be normalized");
        }
        List<String> segments = pointerSegments(patch.path());
        if (segments.isEmpty() || segments.getFirst().isBlank()) {
            throw new IllegalArgumentException("Workspace patch path must identify a field");
        }
        if (IMMUTABLE_ROOT_FIELDS.contains(segments.getFirst())) {
            throw new IllegalArgumentException("Workspace patch cannot mutate graph field: " + segments.getFirst());
        }
        requireEditablePath(segments);
        if ((SET.equals(operation) || REMOVE.equals(operation)) && identityArray(segments) != null) {
            throw new IllegalArgumentException("Known graph arrays must be edited through stable member selectors");
        }
        if (REMOVE.equals(operation) && patch.value() != null && !(patch.value() instanceof JsonValue.JsonNull)) {
            throw new IllegalArgumentException("remove patches cannot carry a value");
        }
        if (ARRAY_REORDER.equals(operation) && !repeatableElementsPath(segments)) {
            throw new IllegalArgumentException("array_reorder requires an ordered repeatable elements path");
        }
        switch (operation) {
            case SET -> set(root, segments, requiredValue(patch));
            case REMOVE -> remove(root, segments);
            case ARRAY_ADD -> addArray(root, segments, requiredValue(patch));
            case ARRAY_REMOVE -> removeArray(root, segments, requiredValue(patch));
            case ARRAY_REORDER -> reorderArray(root, segments, requiredValue(patch), false);
            default -> throw new IllegalArgumentException("Unknown workspace patch operation: " + operation);
        }
    }

    private static JsonValue requiredValue(WorkspacePatch<JsonValue> patch) {
        JsonValue value = Objects.requireNonNull(patch.value(), "Patch value is required for " + patch.op());
        if (value.canonicalBytes().length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Workspace patch value is too large");
        }
        return value;
    }

    private static void set(Object root, List<String> path, JsonValue value) {
        requireManagedValuePreserved(root, path, value);
        Parent parent = parent(root, path);
        Object mutableValue = mutableCopy(value);
        if (parent.container() instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            map.put(parent.leaf(), mutableValue);
            return;
        }
        if (parent.container() instanceof List<?> rawList) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) rawList;
            List<String> collectionPath = path.subList(0, path.size() - 1);
            int index = findArrayIndex(list, parent.leaf(), collectionPath);
            requireStableIdentity(collectionPath, parent.leaf(), mutableValue, "set");
            list.set(index, mutableValue);
            return;
        }
        throw new IllegalArgumentException("Workspace patch parent is not a container");
    }

    private static void remove(Object root, List<String> path) {
        Parent parent = parent(root, path);
        if (parent.container() instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (!map.containsKey(parent.leaf())) {
                throw new IllegalArgumentException("Workspace patch path does not exist: " + format(path));
            }
            map.remove(parent.leaf());
            return;
        }
        if (parent.container() instanceof List<?> rawList) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) rawList;
            list.remove(findArrayIndex(list, parent.leaf(), path.subList(0, path.size() - 1)));
            return;
        }
        throw new IllegalArgumentException("Workspace patch parent is not a container");
    }

    private static void addArray(Object root, List<String> path, JsonValue value) {
        IdentityArray identityArray = requireMutableIdentityArray(path);
        Lookup existing = lookup(root, path);
        Object target;
        if (existing.found()) {
            target = existing.value();
        } else {
            Parent holder = parent(root, path);
            if (!(holder.container() instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("array_add path is not an array: " + format(path));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            ArrayList<Object> created = new ArrayList<>();
            map.put(holder.leaf(), created);
            target = created;
        }
        if (!(target instanceof List<?> rawList)) {
            throw new IllegalArgumentException("array_add path is not an array: " + format(path));
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) rawList;
        Object member = mutableCopy(value);
        String identity = stableIdentity(identityArray, JsonValue.fromJava(member));
        if (identity == null) {
            throw new IllegalArgumentException("array_add values require a stable identity");
        }
        if (list.stream().map(item -> stableIdentity(identityArray, JsonValue.fromJava(item))).anyMatch(identity::equals)) {
            throw new IllegalArgumentException("array_add would duplicate stable identity: " + identity);
        }
        list.add(member);
        if (!orderSensitive(root, path, identityArray)) {
            list.sort(Comparator.comparing(item -> stableIdentity(identityArray, JsonValue.fromJava(item))));
        }
    }

    private static void removeArray(Object root, List<String> path, JsonValue value) {
        Object target = resolve(root, path);
        if (!(target instanceof List<?> rawList)) {
            throw new IllegalArgumentException("array_remove path is not an array: " + format(path));
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) rawList;
        IdentityArray identityArray = requireMutableIdentityArray(path);
        String identity = value == null ? null : stableIdentity(identityArray, value);
        if (identity == null) {
            throw new IllegalArgumentException("array_remove values require a stable identity");
        }
        int index = findArrayIndex(list, "@" + identity, path);
        if (!same(JsonValue.fromJava(list.get(index)), value)) {
            throw new IllegalArgumentException("array_remove conflicts with the current identity value: " + identity);
        }
        list.remove(index);
    }

    private static void reorderArray(Object root, List<String> path, JsonValue value, boolean rebase) {
        IdentityArray identityArray = requireMutableIdentityArray(path);
        if (!repeatableElementsPath(path) || !orderSensitive(root, path, identityArray)) {
            throw new IllegalArgumentException("array_reorder requires an ordered repeatable elements path");
        }
        Object target = resolve(root, path);
        if (!(target instanceof List<?> rawList)) {
            throw new IllegalArgumentException("array_reorder path is not an array: " + format(path));
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) rawList;
        Reorder reorder = reorder(value);
        List<String> current = list.stream()
            .map(member -> stableIdentity(identityArray, JsonValue.fromJava(member))).toList();
        if (current.stream().anyMatch(Objects::isNull) || Set.copyOf(current).size() != current.size()) {
            throw new IllegalArgumentException("array_reorder target contains invalid stable identities");
        }
        if (rebase && current.equals(reorder.order())) {
            return;
        }
        if (!current.equals(reorder.expected())) {
            throw new IllegalArgumentException("array_reorder conflicts with the current element order");
        }
        Map<String, Object> members = new LinkedHashMap<>();
        for (int index = 0; index < current.size(); index++) {
            members.put(current.get(index), list.get(index));
        }
        list.clear();
        reorder.order().forEach(identity -> list.add(members.get(identity)));
    }

    private static Reorder reorder(JsonValue value) {
        if (!(value instanceof JsonObject object) || !object.fields().keySet().equals(Set.of("expected", "order"))) {
            throw new IllegalArgumentException("array_reorder value requires exactly expected and order");
        }
        List<String> expected = reorderIds(object.value("expected"), "expected");
        List<String> order = reorderIds(object.value("order"), "order");
        if (expected.equals(order) || !Set.copyOf(expected).equals(Set.copyOf(order))) {
            throw new IllegalArgumentException("array_reorder order must be a different permutation of expected");
        }
        return new Reorder(expected, order);
    }

    private static List<String> reorderIds(JsonValue value, String field) {
        if (!(value instanceof JsonArray array)) {
            throw new IllegalArgumentException("array_reorder " + field + " must be an array");
        }
        List<String> identities = array.values().stream().map(member -> {
            if (!(member instanceof JsonValue.JsonString string)) {
                throw new IllegalArgumentException("array_reorder identities must be strings");
            }
            String identity = string.value();
            if (!RepeatableElementId.parseCanonicalText(identity).canonicalText().equals(identity)) {
                throw new IllegalArgumentException("array_reorder identities must be canonical");
            }
            return identity;
        }).toList();
        if (Set.copyOf(identities).size() != identities.size()) {
            throw new IllegalArgumentException("array_reorder identities must be unique");
        }
        return identities;
    }

    private static Parent parent(Object root, List<String> path) {
        Object current = root;
        for (int index = 0; index < path.size() - 1; index++) {
            current = child(current, path.get(index), path.subList(0, index));
        }
        return new Parent(current, path.getLast());
    }

    private static Object resolve(Object root, List<String> path) {
        Object current = root;
        for (int index = 0; index < path.size(); index++) {
            current = child(current, path.get(index), path.subList(0, index));
        }
        return current;
    }

    private static Object child(Object current, String segment, List<String> parentPath) {
        if (current instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (!map.containsKey(segment)) {
                throw new IllegalArgumentException("Workspace patch path does not exist: " + segment);
            }
            return map.get(segment);
        }
        if (current instanceof List<?> list) {
            return list.get(findArrayIndex(list, segment, parentPath));
        }
        throw new IllegalArgumentException("Workspace patch path traverses a scalar value");
    }

    private static int findArrayIndex(List<?> values, String segment, List<String> collectionPath) {
        if (!segment.startsWith("@") || segment.length() == 1) {
            throw new IllegalArgumentException("Array paths must use stable identities: " + segment);
        }
        IdentityArray identityArray = identityArray(collectionPath);
        if (identityArray == null) {
            throw new IllegalArgumentException("Array path does not identify a canonical identity collection: " + format(collectionPath));
        }
        String expected = segment.substring(1);
        for (int index = 0; index < values.size(); index++) {
            String identity = stableIdentity(identityArray, JsonValue.fromJava(values.get(index)));
            if (expected.equals(identity)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Stable array identity does not exist: " + expected);
    }

    private static void requireStableIdentity(List<String> collectionPath, String pathSegment, Object value, String operation) {
        IdentityArray identityArray = identityArray(collectionPath);
        String expected = pathSegment.startsWith("@") ? pathSegment.substring(1) : null;
        String actual = identityArray != null ? stableIdentity(identityArray, JsonValue.fromJava(value)) : null;
        if (expected == null || actual == null || !expected.equals(actual)) {
            throw new IllegalArgumentException(operation + " must preserve the stable array identity");
        }
    }

    private static List<String> pointerSegments(String path) {
        Objects.requireNonNull(path, "Patch path is required");
        if (path.length() > MAX_PATH_LENGTH || !path.startsWith("/")) {
            throw new IllegalArgumentException("Workspace patch path must be an absolute JSON Pointer: " + path);
        }
        if (path.length() == 1) {
            return List.of("");
        }
        String[] raw = path.substring(1).split("/", -1);
        if (raw.length > MAX_PATH_SEGMENTS) {
            throw new IllegalArgumentException("Workspace patch path depth exceeds " + MAX_PATH_SEGMENTS + " segments");
        }
        ArrayList<String> result = new ArrayList<>(raw.length);
        for (String segment : raw) {
            String decoded = unescape(segment);
            if (decoded.isEmpty()) {
                throw new IllegalArgumentException("Workspace patch path contains an empty segment");
            }
            result.add(decoded);
        }
        return List.copyOf(result);
    }

    private static boolean numericSegment(String segment) {
        return !segment.isEmpty() && segment.chars().allMatch(Character::isDigit);
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '~') {
                result.append(current);
                continue;
            }
            if (index + 1 >= value.length()) {
                throw new IllegalArgumentException("Malformed JSON Pointer escape: " + value);
            }
            char escaped = value.charAt(++index);
            if (escaped == '0') {
                result.append('~');
            } else if (escaped == '1') {
                result.append('/');
            } else {
                throw new IllegalArgumentException("Malformed JSON Pointer escape: " + value);
            }
        }
        return result.toString();
    }

    private static String format(List<String> path) {
        return path.stream().map(CoreGraphWorkspacePatch::escape).collect(Collectors.joining("/", "/", ""));
    }

    private static boolean same(JsonValue left, JsonValue right) {
        return left == null ? right == null : right != null && left.canonicalText().equals(right.canonicalText());
    }

    private static Object mutableCopy(JsonValue value) {
        if (value instanceof JsonObject object) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            object.fields().forEach((key, member) -> result.put(key, mutableCopy(member)));
            return result;
        }
        if (value instanceof JsonArray array) {
            return array.values().stream().map(CoreGraphWorkspacePatch::mutableCopy).collect(Collectors.toCollection(ArrayList::new));
        }
        return value.toJava();
    }

    private static GraphDocument decodeLossless(Object value) {
        JsonValue canonical = JsonValue.fromJava(value);
        if (!(canonical instanceof JsonObject object)) {
            throw new IllegalArgumentException("Patched graph document must remain an object");
        }
        GraphDocument decoded = GraphDocumentCodec.INSTANCE.decode(object);
        if (!same(object, GraphDocumentCodec.INSTANCE.encode(decoded))) {
            throw new IllegalArgumentException("Patched graph document cannot cross the graph codec boundary losslessly");
        }
        return decoded;
    }

    private static void normalizeKnownArrays(Object root, Object value, List<String> path) {
        if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            map.forEach((field, member) -> normalizeKnownArrays(root, member, append(path, field)));
            return;
        }
        if (!(value instanceof List<?> rawList)) {
            return;
        }
        IdentityArray identityArray = identityArray(path);
        if (identityArray == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) rawList;
        for (Object member : list) {
            String identity = stableIdentity(identityArray, JsonValue.fromJava(member));
            if (identity == null) {
                throw new IllegalArgumentException("Canonical identity array contains a missing identity: " + format(path));
            }
            normalizeKnownArrays(root, member, append(path, "@" + identity));
        }
        if (!orderSensitive(root, path, identityArray)) {
            list.sort(Comparator.comparing(member -> stableIdentity(identityArray, JsonValue.fromJava(member))));
        }
    }

    private static void removeCanonicalEmptyOptionals(Object root) {
        if (!(root instanceof Map<?, ?> rawMap)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) rawMap;
        for (String field : List.of("variables", "functions")) {
            if (map.get(field) instanceof List<?> values && values.isEmpty()) {
                map.remove(field);
            }
        }
    }

    private static List<String> append(List<String> path, String segment) {
        ArrayList<String> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(segment);
        return List.copyOf(result);
    }

    private static void requireEditablePath(List<String> path) {
        if (path.size() >= 3 && "nodes".equals(path.getFirst()) && stableSelector(path.get(1))
            && MANAGED_NODE_FIELDS.contains(path.get(2))) {
            throw new IllegalArgumentException("Workspace patch cannot mutate managed node field: " + path.get(2));
        }
        if (path.size() >= 3 && "functions".equals(path.getFirst()) && stableSelector(path.get(1))
            && MANAGED_FUNCTION_FIELDS.contains(path.get(2))) {
            throw new IllegalArgumentException("Workspace patch cannot mutate managed Function field: " + path.get(2));
        }
        for (int index = 1; index < path.size(); index++) {
            if (!path.get(index).startsWith("@")) {
                continue;
            }
            IdentityArray identityArray = identityArray(path.subList(0, index));
            if (identityArray != null && index + 1 < path.size() && identityArray.field().equals(path.get(index + 1))) {
                throw new IllegalArgumentException("Workspace patch cannot mutate stable identity field: " + identityArray.field());
            }
        }
    }

    private static void requireManagedValuePreserved(Object root, List<String> path, JsonValue value) {
        Set<String> managedFields = managedFields(path);
        if (managedFields != null) {
            Lookup existing = lookup(root, path);
            if (existing.found()) {
                requireFieldsPreserved(JsonValue.fromJava(existing.value()), value, managedFields);
            }
            return;
        }
        if (path.size() != 1 || !(value instanceof JsonArray replacement)) {
            return;
        }
        IdentityArray identityArray = identityArray(path);
        Set<String> collectionFields = managedCollectionFields(path.getFirst());
        if (identityArray == null || collectionFields == null) {
            return;
        }
        Lookup existing = lookup(root, path);
        if (!existing.found() || !(existing.value() instanceof List<?> currentValues)) {
            return;
        }
        Map<String, JsonValue> current = stableValues(identityArray,
            currentValues.stream().map(JsonValue::fromJava).toList());
        Map<String, JsonValue> next = stableValues(identityArray, replacement.values());
        if (current == null || next == null) {
            throw new IllegalArgumentException("Managed identity collection contains an invalid identity: " + format(path));
        }
        for (Map.Entry<String, JsonValue> entry : current.entrySet()) {
            JsonValue nextValue = next.get(entry.getKey());
            if (nextValue != null) {
                requireFieldsPreserved(entry.getValue(), nextValue, collectionFields);
            }
        }
    }

    private static void requireManagedCollectionsPreserved(Object original, Object candidate) {
        for (String field : List.of("nodes", "functions")) {
            Lookup replacement = lookup(candidate, List.of(field));
            if (replacement.found()) {
                requireManagedValuePreserved(original, List.of(field), JsonValue.fromJava(replacement.value()));
            }
        }
    }

    private static Set<String> managedFields(List<String> path) {
        if (path.size() == 2 && "nodes".equals(path.getFirst()) && stableSelector(path.get(1))) {
            return MANAGED_NODE_FIELDS;
        }
        if (path.size() == 2 && "functions".equals(path.getFirst()) && stableSelector(path.get(1))) {
            return MANAGED_FUNCTION_FIELDS;
        }
        return null;
    }

    private static Set<String> managedCollectionFields(String field) {
        return switch (field) {
            case "nodes" -> MANAGED_NODE_FIELDS;
            case "functions" -> MANAGED_FUNCTION_FIELDS;
            default -> null;
        };
    }

    private static void requireFieldsPreserved(JsonValue current, JsonValue replacement, Set<String> fields) {
        if (!(current instanceof JsonObject currentObject) || !(replacement instanceof JsonObject replacementObject)) {
            throw new IllegalArgumentException("Managed graph collection members must be objects");
        }
        for (String field : fields) {
            if (!same(currentObject.value(field), replacementObject.value(field))) {
                throw new IllegalArgumentException("Workspace patch cannot change managed field: " + field);
            }
        }
    }

    private static IdentityArray requireMutableIdentityArray(List<String> path) {
        IdentityArray identityArray = identityArray(path);
        if (identityArray == null) {
            throw new IllegalArgumentException("Workspace array operation requires a canonical identity collection: " + format(path));
        }
        return identityArray;
    }

    private static IdentityArray identityArray(List<String> path) {
        if (path.equals(List.of("nodes"))) {
            return new IdentityArray("instanceId", ArrayOrder.SORTED, false);
        }
        if (path.equals(List.of("connections"))) {
            return new IdentityArray("connectionId", ArrayOrder.SORTED, false);
        }
        if (path.equals(List.of("variables"))) {
            return new IdentityArray("variableId", ArrayOrder.SORTED, false);
        }
        if (path.equals(List.of("functions"))) {
            return new IdentityArray("function", ArrayOrder.SORTED, true);
        }
        if (matches(path, "nodes", "node", "branches")) {
            return new IdentityArray("branchId", ArrayOrder.ORDERED, false);
        }
        if (matches(path, "nodes", "node", "branches", "branch", "cases")) {
            return new IdentityArray("caseId", ArrayOrder.SORTED, false);
        }
        if (matches(path, "nodes", "node", "repeatables")) {
            return new IdentityArray("groupId", ArrayOrder.ORDERED, false);
        }
        if (matches(path, "nodes", "node", "repeatables", "repeatable", "elements")) {
            return new IdentityArray("elementId", ArrayOrder.CONDITIONAL, false);
        }
        if (matches(path, "functions", "function", "inputs") || matches(path, "functions", "function", "outputs")) {
            return new IdentityArray("parameterId", ArrayOrder.ORDERED, false);
        }
        return null;
    }

    private static boolean orderSensitive(Object root, List<String> path, IdentityArray identityArray) {
        if (identityArray.order() == ArrayOrder.ORDERED) {
            return true;
        }
        if (identityArray.order() == ArrayOrder.SORTED) {
            return false;
        }
        Lookup parent = lookup(root, path.subList(0, path.size() - 1));
        if (!parent.found() || !(parent.value() instanceof Map<?, ?> values) || !(values.get("ordered") instanceof Boolean ordered)) {
            throw new IllegalArgumentException("Conditional identity collection is missing its ordered state: " + format(path));
        }
        return ordered;
    }

    private static void requirePatchBatch(List<WorkspacePatch<JsonValue>> patches) {
        if (patches.size() > MAX_PATCHES) {
            throw new IllegalArgumentException("Workspace patch batch exceeds " + MAX_PATCHES + " operations");
        }
    }

    private static void requireSupportedBatch(Object root, List<WorkspacePatch<JsonValue>> patches) {
        Map<String, Set<String>> removed = new LinkedHashMap<>();
        for (WorkspacePatch<JsonValue> patch : patches) {
            if (REMOVE.equals(patch.op())) {
                List<String> memberPath = pointerSegments(patch.path());
                if (memberPath.size() < 2 || !stableSelector(memberPath.getLast())) {
                    continue;
                }
                List<String> collectionPath = memberPath.subList(0, memberPath.size() - 1);
                IdentityArray collection = identityArray(collectionPath);
                if (collection != null && orderSensitive(root, collectionPath, collection)) {
                    removed.computeIfAbsent(format(collectionPath), ignored -> new HashSet<>()).add(memberPath.getLast().substring(1));
                }
                continue;
            }
            if (!ARRAY_REMOVE.equals(patch.op()) && !ARRAY_ADD.equals(patch.op())) {
                continue;
            }
            List<String> path = pointerSegments(patch.path());
            IdentityArray identityArray = identityArray(path);
            if (identityArray == null || !orderSensitive(root, path, identityArray) || patch.value() == null) {
                continue;
            }
            String identity = stableIdentity(identityArray, patch.value());
            if (identity == null) {
                continue;
            }
            String collection = format(path);
            if (ARRAY_REMOVE.equals(patch.op())) {
                removed.computeIfAbsent(collection, ignored -> new HashSet<>()).add(identity);
            } else if (removed.getOrDefault(collection, Set.of()).contains(identity)) {
                throw new IllegalArgumentException("Ordered identity collections cannot be reordered without server move support: " + collection);
            }
        }
    }

    private static void validateOrderedSequences(Object before, Object after,
                                                 List<WorkspacePatch<JsonValue>> patches) {
        Map<String, RepeatableState> original = repeatables(before);
        Set<String> reordered = patches.stream().filter(patch -> ARRAY_REORDER.equals(patch.op()))
            .map(patch -> repeatableKey(pointerSegments(patch.path()))).collect(Collectors.toSet());
        repeatables(after).forEach((identity, staged) -> {
            RepeatableState current = original.get(identity);
            if (current == null || !staged.ordered()) {
                return;
            }
            Set<String> currentIds = Set.copyOf(current.elements());
            Set<String> stagedIds = Set.copyOf(staged.elements());
            List<String> currentCommon = current.elements().stream().filter(stagedIds::contains).toList();
            List<String> stagedCommon = staged.elements().stream().filter(currentIds::contains).toList();
            boolean invalid = !currentCommon.equals(stagedCommon);
            boolean added = false;
            for (String element : staged.elements()) {
                if (!currentIds.contains(element)) {
                    added = true;
                } else if (added) {
                    invalid = true;
                }
            }
            if (invalid && !reordered.contains(identity)) {
                throw new IllegalArgumentException("Ordered repeatable element order changed without array_reorder");
            }
        });
    }

    private static Map<String, RepeatableState> repeatables(Object root) {
        JsonValue canonical = JsonValue.fromJava(root);
        if (!(canonical instanceof JsonObject graph) || !(graph.value("nodes") instanceof JsonArray nodes)) {
            return Map.of();
        }
        Map<String, RepeatableState> result = new LinkedHashMap<>();
        IdentityArray nodeArray = identityArray(List.of("nodes"));
        IdentityArray bindingArray = identityArray(List.of("nodes", "@node", "repeatables"));
        IdentityArray elementArray = identityArray(List.of("nodes", "@node", "repeatables", "@group", "elements"));
        for (JsonValue nodeValue : nodes.values()) {
            String nodeId = stableIdentity(nodeArray, nodeValue);
            if (!(nodeValue instanceof JsonObject node) || !(node.value("repeatables") instanceof JsonArray bindings)) {
                continue;
            }
            for (JsonValue bindingValue : bindings.values()) {
                String groupId = stableIdentity(bindingArray, bindingValue);
                if (!(bindingValue instanceof JsonObject binding)) {
                    throw new IllegalArgumentException("Repeatable binding must be an object");
                }
                JsonValue orderedValue = binding.value("ordered");
                boolean ordered = orderedValue instanceof JsonValue.JsonBoolean booleanValue && booleanValue.value();
                List<String> elements = binding.value("elements") instanceof JsonArray members
                    ? members.values().stream().map(member -> stableIdentity(elementArray, member)).toList() : List.of();
                if (nodeId == null || groupId == null || elements.stream().anyMatch(Objects::isNull)
                    || result.put(nodeId + '\u0000' + groupId, new RepeatableState(ordered, elements)) != null) {
                    throw new IllegalArgumentException("Graph contains invalid repeatable identities");
                }
            }
        }
        return result;
    }

    private static void validateOrderedTransitions(Object before, Object after,
                                                   List<WorkspacePatch<JsonValue>> patches) {
        Map<List<String>, OrderedMutation> mutations = new LinkedHashMap<>();
        for (WorkspacePatch<JsonValue> patch : patches) {
            List<String> path = pointerSegments(patch.path());
            if (path.size() < 4 || !"nodes".equals(path.get(0)) || !stableSelector(path.get(1))
                || !"repeatables".equals(path.get(2)) || !stableSelector(path.get(3))) {
                continue;
            }
            List<String> target = List.copyOf(path.subList(0, 4));
            OrderedMutation mutation = mutations.computeIfAbsent(target, ignored -> new OrderedMutation());
            if (path.size() == 4) {
                if (SET.equals(patch.op())) {
                    mutation.orderedChanged = true;
                }
                continue;
            }
            if ("ordered".equals(path.get(4))) {
                mutation.orderedChanged = true;
            } else if ("elements".equals(path.get(4))) {
                mutation.elementChanged = true;
                if (path.size() == 5 && (ARRAY_ADD.equals(patch.op()) || ARRAY_REMOVE.equals(patch.op()))) {
                    IdentityArray elements = identityArray(path);
                    String identity = patch.value() != null ? stableIdentity(elements, patch.value()) : null;
                    if (identity != null) {
                        (ARRAY_ADD.equals(patch.op()) ? mutation.added : mutation.removed).add(identity);
                    }
                } else if (path.size() == 5 && ARRAY_REORDER.equals(patch.op())) {
                    mutation.reordered = true;
                } else if (path.size() == 6 && stableSelector(path.get(5)) && REMOVE.equals(patch.op())) {
                    mutation.removed.add(path.get(5).substring(1));
                }
            }
        }
        mutations.forEach((target, mutation) -> {
            if (mutation.orderedChanged && mutation.elementChanged) {
                throw new IllegalArgumentException("Ordered state cannot change with repeatable element mutations");
            }
            if (mutation.reordered && hasAncestorMutation(target, patches)) {
                throw new IllegalArgumentException("array_reorder cannot accompany repeatable ancestor replacement");
            }
            Set<String> repeated = new HashSet<>(mutation.added);
            repeated.retainAll(mutation.removed);
            if (!repeated.isEmpty() && (ordered(before, target) || ordered(after, target))) {
                throw new IllegalArgumentException("Ordered repeatable elements cannot remove and re-add the same identity");
            }
        });
    }

    private static boolean hasAncestorMutation(List<String> target, List<WorkspacePatch<JsonValue>> patches) {
        String nodeId = target.get(1).substring(1);
        String groupId = target.get(3).substring(1);
        for (WorkspacePatch<JsonValue> patch : patches) {
            List<String> path = pointerSegments(patch.path());
            if ((SET.equals(patch.op()) || REMOVE.equals(patch.op()))
                && (path.equals(target) || path.equals(target.subList(0, 2)))) {
                return true;
            }
            if ((ARRAY_ADD.equals(patch.op()) || ARRAY_REMOVE.equals(patch.op())) && patch.value() != null) {
                if (path.equals(List.of("nodes"))) {
                    String identity = stableIdentity(identityArray(path), patch.value());
                    if (nodeId.equals(identity)) {
                        return true;
                    }
                } else if (path.equals(target.subList(0, 3))) {
                    String identity = stableIdentity(identityArray(path), patch.value());
                    if (groupId.equals(identity)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean ordered(Object root, List<String> path) {
        Lookup repeatable = lookup(root, path);
        return repeatable.found() && repeatable.value() instanceof Map<?, ?> values
            && values.get("ordered") instanceof Boolean ordered && ordered;
    }

    private static String repeatableKey(List<String> path) {
        if (!repeatableElementsPath(path)) {
            throw new IllegalArgumentException("array_reorder requires an ordered repeatable elements path");
        }
        return path.get(1).substring(1) + '\u0000' + path.get(3).substring(1);
    }

    private static void applyRebased(Object original, Object candidate, WorkspacePatch<JsonValue> patch) {
        List<String> path = pointerSegments(patch.path());
        switch (patch.op()) {
            case SET -> {
                Lookup expected = lookup(original, path);
                Lookup current = lookup(candidate, path);
                if (current.matches(patch.value())) {
                    return;
                }
                if (!current.same(expected)) {
                    throw conflict(patch);
                }
            }
            case REMOVE -> {
                Lookup current = lookup(candidate, path);
                if (!current.found()) {
                    return;
                }
                if (!current.same(lookup(original, path))) {
                    throw conflict(patch);
                }
            }
            case ARRAY_ADD -> {
                Lookup current = arrayMember(candidate, path, patch.value());
                if (current.matches(patch.value())) {
                    return;
                }
                if (current.found()) {
                    throw conflict(patch);
                }
            }
            case ARRAY_REMOVE -> {
                Lookup current = arrayMember(candidate, path, patch.value());
                if (!current.found()) {
                    return;
                }
                if (!current.matches(patch.value())) {
                    throw conflict(patch);
                }
            }
            case ARRAY_REORDER -> {
                try {
                    reorderArray(candidate, path, requiredValue(patch), true);
                } catch (IllegalArgumentException exception) {
                    throw conflict(patch);
                }
                return;
            }
            default -> throw new IllegalArgumentException("Unknown workspace patch operation: " + patch.op());
        }
        applyOne(candidate, patch);
    }

    private static Lookup arrayMember(Object root, List<String> path, JsonValue value) {
        IdentityArray identityArray = identityArray(path);
        String expected = identityArray != null && value != null ? stableIdentity(identityArray, value) : null;
        if (expected == null) {
            throw new IllegalArgumentException("Workspace array patch requires a canonical stable identity");
        }
        Lookup array = lookup(root, path);
        if (!array.found()) {
            return Lookup.missing();
        }
        if (!(array.value() instanceof List<?> values)) {
            throw new IllegalArgumentException("Workspace patch path is not an array: " + format(path));
        }
        for (Object member : values) {
            if (expected.equals(stableIdentity(identityArray, JsonValue.fromJava(member)))) {
                return new Lookup(true, member);
            }
        }
        return Lookup.missing();
    }

    private static Lookup lookup(Object root, List<String> path) {
        Object current = root;
        for (int index = 0; index < path.size(); index++) {
            String segment = path.get(index);
            if (current instanceof Map<?, ?> rawMap) {
                if (!rawMap.containsKey(segment)) {
                    return Lookup.missing();
                }
                current = rawMap.get(segment);
                continue;
            }
            if (current instanceof List<?> values) {
                IdentityArray identityArray = identityArray(path.subList(0, index));
                if (identityArray == null || !segment.startsWith("@")) {
                    throw new IllegalArgumentException("Array paths must use canonical stable identities: " + segment);
                }
                String expected = segment.substring(1);
                Object found = null;
                for (Object value : values) {
                    if (expected.equals(stableIdentity(identityArray, JsonValue.fromJava(value)))) {
                        found = value;
                        break;
                    }
                }
                if (found == null) {
                    return Lookup.missing();
                }
                current = found;
                continue;
            }
            return Lookup.missing();
        }
        return new Lookup(true, current);
    }

    private static IllegalStateException conflict(WorkspacePatch<JsonValue> patch) {
        return new IllegalStateException("Workspace rebase conflict at " + patch.path());
    }

    private static boolean matches(List<String> path, String... shape) {
        if (path.size() != shape.length) {
            return false;
        }
        for (int index = 0; index < shape.length; index++) {
            String expected = shape[index];
            String actual = path.get(index);
            if (index % 2 == 1 && !stableSelector(actual)) {
                return false;
            }
            if (index % 2 == 0 && !expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean repeatableElementsPath(List<String> path) {
        return matches(path, "nodes", "node", "repeatables", "repeatable", "elements");
    }

    private static boolean stableSelector(String value) {
        return value.startsWith("@") && value.length() > 1;
    }

    private record Parent(Object container, String leaf) {
    }

    private record Reorder(List<String> expected, List<String> order) {
    }

    private record RepeatableState(boolean ordered, List<String> elements) {
    }

    private static final class OrderedMutation {
        private boolean orderedChanged;
        private boolean elementChanged;
        private boolean reordered;
        private final Set<String> added = new HashSet<>();
        private final Set<String> removed = new HashSet<>();
    }

    private enum ArrayOrder {
        SORTED,
        ORDERED,
        CONDITIONAL
    }

    private record IdentityArray(String field, ArrayOrder order, boolean objectIdentity) {
    }

    private record Lookup(boolean found, Object value) {
        private static Lookup missing() {
            return new Lookup(false, null);
        }

        private boolean same(Lookup other) {
            return found == other.found && (!found || CoreGraphWorkspacePatch.same(JsonValue.fromJava(value), JsonValue.fromJava(other.value)));
        }

        private boolean matches(JsonValue expected) {
            return found && expected != null && CoreGraphWorkspacePatch.same(JsonValue.fromJava(value), expected);
        }
    }
}
