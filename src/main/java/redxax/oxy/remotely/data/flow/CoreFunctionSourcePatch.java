package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class CoreFunctionSourcePatch {
    public static final String SET = "set";
    public static final String REMOVE = "remove";
    public static final String ARRAY_ADD = "array_add";
    public static final String ARRAY_REMOVE = "array_remove";
    private static final int MAX_PATCHES = 512;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_PATH_SEGMENTS = 64;
    private static final int MAX_VALUE_BYTES = 1_048_576;

    private CoreFunctionSourcePatch() {
    }

    public record Patch(
        List<WorkspacePatch<JsonValue>> signaturePatches,
        List<WorkspacePatch<JsonValue>> graphPatches
    ) {
        public Patch {
            signaturePatches = immutablePatches(signaturePatches, "Signature patches");
            graphPatches = immutablePatches(graphPatches, "Graph patches");
            if (signaturePatches.size() + graphPatches.size() > MAX_PATCHES) {
                throw new IllegalArgumentException("Function source patch batch exceeds " + MAX_PATCHES + " operations");
            }
        }

        public Patch(List<WorkspacePatch<JsonValue>> signaturePatches) {
            this(signaturePatches, List.of());
        }

        public List<WorkspacePatch<JsonValue>> signature() {
            return signaturePatches;
        }

        public List<WorkspacePatch<JsonValue>> graph() {
            return graphPatches;
        }

        public boolean isEmpty() {
            return signaturePatches.isEmpty() && graphPatches.isEmpty();
        }

        private static List<WorkspacePatch<JsonValue>> immutablePatches(List<WorkspacePatch<JsonValue>> patches, String label) {
            Objects.requireNonNull(patches, label + " are required");
            ArrayList<WorkspacePatch<JsonValue>> copy = new ArrayList<>(patches.size());
            patches.forEach(patch -> copy.add(Objects.requireNonNull(patch, label + " cannot contain null patches")));
            return List.copyOf(copy);
        }
    }

    public static Patch diff(FunctionSourceDocument before, FunctionSourceDocument after) {
        Objects.requireNonNull(before, "Before function source is required");
        Objects.requireNonNull(after, "After function source is required");
        requireCompatibleSource(before, after);
        if (!same(JsonValue.fromJava(before.unknown().fields()), JsonValue.fromJava(after.unknown().fields()))) {
            throw new IllegalArgumentException("Function source unknown data cannot change through source patches");
        }
        JsonValue.JsonObject previousSignature = FunctionSourceDocumentCodec.INSTANCE.encodeSignature(before.signature());
        JsonValue.JsonObject nextSignature = FunctionSourceDocumentCodec.INSTANCE.encodeSignature(after.signature());
        ArrayList<WorkspacePatch<JsonValue>> signaturePatches = new ArrayList<>();
        diffValue("/signature", previousSignature, nextSignature, signaturePatches);
        List<WorkspacePatch<JsonValue>> graphPatches = CoreGraphWorkspacePatch.diff(before.graph(), after.graph());
        Patch result = new Patch(signaturePatches, graphPatches);
        FunctionSourceDocument applied = apply(before, result);
        if (!sameEncoded(applied, after)) {
            throw new IllegalArgumentException("Function source patch batch cannot reproduce the requested source exactly");
        }
        return result;
    }

    public static FunctionSourceDocument apply(FunctionSourceDocument base, Patch patch) {
        Objects.requireNonNull(base, "Base function source is required");
        Objects.requireNonNull(patch, "Function source patch is required");
        if (patch.isEmpty()) {
            return base;
        }
        validateSignaturePatches(patch.signaturePatches());
        GraphDocument graph = patch.graphPatches().isEmpty()
            ? base.graph()
            : CoreGraphWorkspacePatch.apply(base.graph(), patch.graphPatches());
        JsonValue.JsonObject encoded = FunctionSourceDocumentCodec.INSTANCE.encode(base);
        Object mutable = mutableCopy(encoded);
        for (WorkspacePatch<JsonValue> sourcePatch : patch.signaturePatches()) {
            applyOne(mutable, sourcePatch);
        }
        if (!(mutable instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Function source patch root must remain an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rawMap;
        root.put("graph", mutableCopy(GraphDocumentCodec.INSTANCE.encode(graph)));
        return decodeLossless(mutable);
    }

    public static FunctionSourceDocument rebase(FunctionSourceDocument originalBase,
                                                  FunctionSourceDocument desired,
                                                  FunctionSourceDocument latest) {
        Objects.requireNonNull(originalBase, "Original function source is required");
        Objects.requireNonNull(desired, "Desired function source is required");
        Objects.requireNonNull(latest, "Latest function source is required");
        requireCompatibleSource(originalBase, desired);
        requireCompatibleLatest(originalBase, latest);
        Patch patch = diff(originalBase, desired);
        GraphDocument graph = CoreGraphWorkspacePatch.rebase(originalBase.graph(), desired.graph(), latest.graph());
        JsonValue.JsonObject originalEncoded = FunctionSourceDocumentCodec.INSTANCE.encode(originalBase);
        Object original = mutableCopy(originalEncoded);
        Object candidate = mutableCopy(FunctionSourceDocumentCodec.INSTANCE.encode(latest));
        for (WorkspacePatch<JsonValue> sourcePatch : patch.signaturePatches()) {
            applyRebased(original, candidate, sourcePatch);
        }
        if (!(candidate instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Rebased function source root must remain an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rawMap;
        root.put("graph", mutableCopy(GraphDocumentCodec.INSTANCE.encode(graph)));
        return decodeLossless(candidate);
    }

    public static void validate(FunctionSourceDocument base, Patch patch) {
        apply(base, patch);
    }

    private static void requireCompatibleSource(FunctionSourceDocument before, FunctionSourceDocument after) {
        requireSameFunction(before.signature(), after.signature());
        if (before.signature().revision().value() != after.signature().revision().value()) {
            throw new IllegalArgumentException("Function source revision cannot change through source patches");
        }
        if (!before.graph().schemaVersion().equals(after.graph().schemaVersion())) {
            throw new IllegalArgumentException("Function source graph schema cannot change through source patches");
        }
        if (!before.graph().resource().equals(after.graph().resource())) {
            throw new IllegalArgumentException("Function source graph resource cannot change through source patches");
        }
        if (!before.graph().catalogBinding().equals(after.graph().catalogBinding())) {
            throw new IllegalArgumentException("Function source catalog binding cannot change through source patches");
        }
    }

    private static void requireCompatibleLatest(FunctionSourceDocument originalBase, FunctionSourceDocument latest) {
        requireSameFunction(originalBase.signature(), latest.signature());
        if (!originalBase.graph().schemaVersion().equals(latest.graph().schemaVersion())) {
            throw new IllegalArgumentException("Latest function source graph schema does not match the original source");
        }
        if (!originalBase.graph().resource().equals(latest.graph().resource())) {
            throw new IllegalArgumentException("Latest function source graph resource does not match the original source");
        }
    }

    private static void requireSameFunction(FunctionSignature before, FunctionSignature after) {
        String previous = IdentityCodec.encodeLocator(before.function().resource()).canonicalText();
        String next = IdentityCodec.encodeLocator(after.function().resource()).canonicalText();
        if (!previous.equals(next)) {
            throw new IllegalArgumentException("Function source function locator cannot change through source patches");
        }
    }

    private static void diffValue(String path, JsonValue before, JsonValue after,
                                  List<WorkspacePatch<JsonValue>> patches) {
        if (same(before, after)) {
            return;
        }
        if (before instanceof JsonValue.JsonObject previous && after instanceof JsonValue.JsonObject next) {
            TreeSet<String> keys = new TreeSet<>();
            keys.addAll(previous.fields().keySet());
            keys.addAll(next.fields().keySet());
            for (String key : keys) {
                if ("/signature".equals(path) && ("function".equals(key) || "revision".equals(key))) {
                    if (!same(previous.value(key), next.value(key))) {
                        throw new IllegalArgumentException("Function signature identity cannot change through source patches: " + key);
                    }
                    continue;
                }
                JsonValue oldValue = previous.value(key);
                JsonValue newValue = next.value(key);
                String childPath = path + "/" + escape(key);
                if (oldValue == null && newValue instanceof JsonValue.JsonArray addedArray && identityArray(pointerSegments(childPath)) != null) {
                    diffArray(childPath, List.of(), addedArray.values(), patches);
                } else if (newValue == null && oldValue instanceof JsonValue.JsonArray removedArray && identityArray(pointerSegments(childPath)) != null) {
                    diffArray(childPath, removedArray.values(), List.of(), patches);
                } else if (oldValue == null) {
                    patches.add(new WorkspacePatch<>(SET, childPath, Objects.requireNonNull(newValue)));
                } else if (newValue == null) {
                    patches.add(new WorkspacePatch<>(REMOVE, childPath, JsonValue.nullValue()));
                } else {
                    diffValue(childPath, oldValue, newValue, patches);
                }
            }
            return;
        }
        if (before instanceof JsonValue.JsonArray previous && after instanceof JsonValue.JsonArray next) {
            diffArray(path, previous.values(), next.values(), patches);
            return;
        }
        if (after == null) {
            patches.add(new WorkspacePatch<>(REMOVE, path, JsonValue.nullValue()));
        } else {
            patches.add(new WorkspacePatch<>(SET, path, after));
        }
    }

    private static void diffArray(String path, List<JsonValue> before, List<JsonValue> after,
                                  List<WorkspacePatch<JsonValue>> patches) {
        IdentityArray identities = identityArray(pointerSegments(path));
        if (identities == null) {
            if (!same(JsonValue.array(before), JsonValue.array(after))) {
                patches.add(new WorkspacePatch<>(SET, path, JsonValue.array(after)));
            }
            return;
        }
        Map<String, JsonValue> previous = stableValues(identities, before);
        Map<String, JsonValue> next = stableValues(identities, after);
        if (previous == null || next == null) {
            throw new IllegalArgumentException("Function signature parameter collection contains a missing or duplicate ID: " + path);
        }
        if (!appendOnlyOrder(previous, next)) {
            throw new IllegalArgumentException("Function signature parameter collections cannot be reordered through source patches: " + path);
        }
        TreeSet<String> members = new TreeSet<>();
        members.addAll(previous.keySet());
        members.addAll(next.keySet());
        for (String identity : members) {
            JsonValue oldValue = previous.get(identity);
            JsonValue newValue = next.get(identity);
            if (oldValue != null && newValue == null) {
                patches.add(new WorkspacePatch<>(ARRAY_REMOVE, path, oldValue));
            } else if (oldValue != null) {
                diffValue(path + "/@" + escape(identity), oldValue, newValue, patches);
            }
        }
        next.keySet().stream().filter(identity -> !previous.containsKey(identity))
            .forEach(identity -> patches.add(new WorkspacePatch<>(ARRAY_ADD, path, next.get(identity))));
    }

    private static boolean appendOnlyOrder(Map<String, JsonValue> before, Map<String, JsonValue> after) {
        List<String> retained = before.keySet().stream().filter(after::containsKey).toList();
        ArrayList<String> expected = new ArrayList<>(retained);
        after.keySet().stream().filter(identity -> !before.containsKey(identity)).forEach(expected::add);
        return expected.equals(List.copyOf(after.keySet()));
    }

    private static Map<String, JsonValue> stableValues(IdentityArray identities, List<JsonValue> values) {
        LinkedHashMap<String, JsonValue> result = new LinkedHashMap<>();
        for (JsonValue value : values) {
            String identity = stableIdentity(identities, value);
            if (identity == null || result.put(identity, value) != null) {
                return null;
            }
        }
        return result;
    }

    private static String stableIdentity(IdentityArray identities, JsonValue value) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            return null;
        }
        JsonValue identity = object.value(identities.field());
        return identity instanceof JsonValue.JsonString string ? string.value() : null;
    }

    private static void validateSignaturePatches(List<WorkspacePatch<JsonValue>> patches) {
        for (WorkspacePatch<JsonValue> patch : patches) {
            List<String> path = pointerSegments(patch.path());
            if (path.size() < 2 || !"signature".equals(path.getFirst())) {
                throw new IllegalArgumentException("Function signature patch paths must be rooted at /signature");
            }
            if ("function".equals(path.get(1)) || "revision".equals(path.get(1))) {
                throw new IllegalArgumentException("Function signature identity fields cannot be patched");
            }
            requireEditablePath(path);
            String operation = Objects.requireNonNull(patch.op(), "Patch operation is required");
            if (!operation.equals(operation.trim())) {
                throw new IllegalArgumentException("Function source patch operation must be normalized");
            }
            if (patch.value() != null && patch.value().canonicalBytes().length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("Function source patch value is too large");
            }
            if (!SET.equals(operation) && !REMOVE.equals(operation) && !ARRAY_ADD.equals(operation)
                && !ARRAY_REMOVE.equals(operation)) {
                throw new IllegalArgumentException("Unknown function source patch operation: " + operation);
            }
            if (REMOVE.equals(operation) && patch.value() != null && !(patch.value() instanceof JsonValue.JsonNull)) {
                throw new IllegalArgumentException("remove patches cannot carry a value");
            }
            if (REMOVE.equals(operation) && isParameterSelector(path)) {
                throw new IllegalArgumentException("Signature parameter removal requires an exact array_remove value");
            }
            if ((ARRAY_ADD.equals(operation) || ARRAY_REMOVE.equals(operation)) && patch.value() == null) {
                throw new IllegalArgumentException("Array patches require a value");
            }
            if ((SET.equals(operation) || REMOVE.equals(operation)) && identityArray(path) != null) {
                throw new IllegalArgumentException("Signature parameter arrays must be edited through stable member selectors");
            }
            if ((ARRAY_ADD.equals(operation) || ARRAY_REMOVE.equals(operation)) && identityArray(path) == null) {
                throw new IllegalArgumentException("Signature array patches require /signature/inputs or /signature/outputs");
            }
        }
    }

    private static void requireEditablePath(List<String> path) {
        for (int index = 1; index < path.size(); index++) {
            if (!path.get(index).startsWith("@")) {
                continue;
            }
            if (index + 1 < path.size() && "id".equals(path.get(index + 1))) {
                throw new IllegalArgumentException("Function parameter IDs cannot be patched");
            }
            if (index != 2 || !("inputs".equals(path.get(1)) || "outputs".equals(path.get(1)))) {
                throw new IllegalArgumentException("Function signature paths contain an unsupported stable selector");
            }
        }
    }

    private static boolean isParameterSelector(List<String> path) {
        return path.size() == 3 && identityArray(path.subList(0, 2)) != null && path.getLast().startsWith("@");
    }

    private static void applyOne(Object root, WorkspacePatch<JsonValue> patch) {
        List<String> path = pointerSegments(patch.path());
        String operation = Objects.requireNonNull(patch.op(), "Patch operation is required");
        JsonValue value = patch.value();
        if (SET.equals(operation)) {
            set(root, path, Objects.requireNonNull(value, "set patches require a value"));
        } else if (REMOVE.equals(operation)) {
            remove(root, path);
        } else if (ARRAY_ADD.equals(operation)) {
            addArray(root, path, Objects.requireNonNull(value, "array_add patches require a value"));
        } else if (ARRAY_REMOVE.equals(operation)) {
            removeArray(root, path, Objects.requireNonNull(value, "array_remove patches require a value"));
        } else {
            throw new IllegalArgumentException("Unknown function source patch operation: " + operation);
        }
    }

    private static void set(Object root, List<String> path, JsonValue value) {
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
            int index = findArrayIndex(list, parent.leaf(), path.subList(0, path.size() - 1));
            requireStableIdentity(path.subList(0, path.size() - 1), parent.leaf(), mutableValue);
            list.set(index, mutableValue);
            return;
        }
        throw new IllegalArgumentException("Function source patch parent is not a container");
    }

    private static void remove(Object root, List<String> path) {
        Parent parent = parent(root, path);
        if (parent.container() instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (!map.containsKey(parent.leaf())) {
                throw new IllegalArgumentException("Function source patch path does not exist: " + format(path));
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
        throw new IllegalArgumentException("Function source patch parent is not a container");
    }

    private static void addArray(Object root, List<String> path, JsonValue value) {
        IdentityArray identities = requireIdentityArray(path);
        Lookup lookup = lookup(root, path);
        Object target;
        if (lookup.found()) {
            target = lookup.value();
        } else {
            Parent parent = parent(root, path);
            if (!(parent.container() instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("array_add path is not an array: " + format(path));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            ArrayList<Object> created = new ArrayList<>();
            map.put(parent.leaf(), created);
            target = created;
        }
        if (!(target instanceof List<?> rawList)) {
            throw new IllegalArgumentException("array_add path is not an array: " + format(path));
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) rawList;
        Object member = mutableCopy(value);
        String identity = stableIdentity(identities, JsonValue.fromJava(member));
        if (identity == null) {
            throw new IllegalArgumentException("array_add values require a function parameter ID");
        }
        if (list.stream().map(item -> stableIdentity(identities, JsonValue.fromJava(item))).anyMatch(identity::equals)) {
            throw new IllegalArgumentException("array_add would duplicate function parameter ID: " + identity);
        }
        list.add(member);
    }

    private static void removeArray(Object root, List<String> path, JsonValue value) {
        IdentityArray identities = requireIdentityArray(path);
        Object target = resolve(root, path);
        if (!(target instanceof List<?> rawList)) {
            throw new IllegalArgumentException("array_remove path is not an array: " + format(path));
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) rawList;
        String identity = stableIdentity(identities, value);
        if (identity == null) {
            throw new IllegalArgumentException("array_remove values require a function parameter ID");
        }
        int index = findArrayIndex(list, "@" + identity, path);
        if (!same(JsonValue.fromJava(list.get(index)), value)) {
            throw new IllegalArgumentException("array_remove conflicts with the current parameter value: " + identity);
        }
        list.remove(index);
    }

    private static int findArrayIndex(List<?> values, String selector, List<String> collectionPath) {
        if (!selector.startsWith("@") || selector.length() == 1) {
            throw new IllegalArgumentException("Function source arrays require stable selectors: " + selector);
        }
        IdentityArray identities = requireIdentityArray(collectionPath);
        String expected = selector.substring(1);
        for (int index = 0; index < values.size(); index++) {
            if (expected.equals(stableIdentity(identities, JsonValue.fromJava(values.get(index))))) {
                return index;
            }
        }
        throw new IllegalArgumentException("Function source parameter does not exist: " + selector);
    }

    private static void requireStableIdentity(List<String> collectionPath, String selector, Object value) {
        IdentityArray identities = requireIdentityArray(collectionPath);
        String expected = selector.substring(1);
        String actual = stableIdentity(identities, JsonValue.fromJava(value));
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Function parameter identity cannot change through a patch");
        }
    }

    private static IdentityArray requireIdentityArray(List<String> path) {
        IdentityArray identities = identityArray(path);
        if (identities == null) {
            throw new IllegalArgumentException("Function source array operation requires /signature/inputs or /signature/outputs: " + format(path));
        }
        return identities;
    }

    private static Object resolve(Object root, List<String> path) {
        Lookup lookup = lookup(root, path);
        if (!lookup.found()) {
            throw new IllegalArgumentException("Function source patch path does not exist: " + format(path));
        }
        return lookup.value();
    }

    private static Parent parent(Object root, List<String> path) {
        if (path.size() < 2) {
            throw new IllegalArgumentException("Function source patch path must identify a field: " + format(path));
        }
        List<String> parentPath = path.subList(0, path.size() - 1);
        Lookup parent = lookup(root, parentPath);
        if (!parent.found()) {
            throw new IllegalArgumentException("Function source patch parent does not exist: " + format(parentPath));
        }
        return new Parent(parent.value(), path.getLast());
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
                IdentityArray identities = identityArray(path.subList(0, index));
                if (identities == null || !segment.startsWith("@")) {
                    throw new IllegalArgumentException("Function source arrays require stable selectors: " + segment);
                }
                String expected = segment.substring(1);
                Object found = null;
                for (Object value : values) {
                    if (expected.equals(stableIdentity(identities, JsonValue.fromJava(value)))) {
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
            default -> throw new IllegalArgumentException("Unknown function source patch operation: " + patch.op());
        }
        applyOne(candidate, patch);
    }

    private static Lookup arrayMember(Object root, List<String> path, JsonValue value) {
        IdentityArray identities = requireIdentityArray(path);
        String expected = stableIdentity(identities, value);
        if (expected == null) {
            throw new IllegalArgumentException("Function source array patches require a function parameter ID");
        }
        Lookup array = lookup(root, path);
        if (!array.found()) {
            return Lookup.missing();
        }
        if (!(array.value() instanceof List<?> values)) {
            throw new IllegalArgumentException("Function source patch path is not an array: " + format(path));
        }
        for (Object member : values) {
            if (expected.equals(stableIdentity(identities, JsonValue.fromJava(member)))) {
                return new Lookup(true, member);
            }
        }
        return Lookup.missing();
    }

    private static List<String> pointerSegments(String path) {
        Objects.requireNonNull(path, "Function source patch path is required");
        if (path.isEmpty() || path.length() > MAX_PATH_LENGTH || !path.startsWith("/")) {
            throw new IllegalArgumentException("Function source patch path must be a bounded JSON Pointer");
        }
        String[] raw = path.substring(1).split("/", -1);
        if (raw.length == 0 || raw.length > MAX_PATH_SEGMENTS) {
            throw new IllegalArgumentException("Function source patch path has an invalid depth");
        }
        ArrayList<String> result = new ArrayList<>(raw.length);
        for (String segment : raw) {
            String decoded = unescape(segment);
            if (decoded.isEmpty()) {
                throw new IllegalArgumentException("Function source patch paths cannot contain empty segments");
            }
            result.add(decoded);
        }
        return List.copyOf(result);
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
        return path.stream().map(CoreFunctionSourcePatch::escape).collect(Collectors.joining("/", "/", ""));
    }

    private static boolean same(JsonValue left, JsonValue right) {
        return left == null ? right == null : right != null && left.canonicalText().equals(right.canonicalText());
    }

    private static boolean sameEncoded(FunctionSourceDocument left, FunctionSourceDocument right) {
        return FunctionSourceDocumentCodec.INSTANCE.encode(left).canonicalText()
            .equals(FunctionSourceDocumentCodec.INSTANCE.encode(right).canonicalText());
    }

    private static Object mutableCopy(JsonValue value) {
        if (value instanceof JsonValue.JsonObject object) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            object.fields().forEach((key, member) -> result.put(key, mutableCopy(member)));
            return result;
        }
        if (value instanceof JsonValue.JsonArray array) {
            ArrayList<Object> result = new ArrayList<>(array.values().size());
            array.values().forEach(member -> result.add(mutableCopy(member)));
            return result;
        }
        return value.toJava();
    }

    private static FunctionSourceDocument decodeLossless(Object value) {
        JsonValue canonical = JsonValue.fromJava(value);
        if (!(canonical instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("Patched function source must remain an object");
        }
        FunctionSourceDocument decoded = FunctionSourceDocumentCodec.INSTANCE.decode(object);
        if (!same(object, FunctionSourceDocumentCodec.INSTANCE.encode(decoded))) {
            throw new IllegalArgumentException("Patched function source cannot cross the codec boundary losslessly");
        }
        return decoded;
    }

    private static IdentityArray identityArray(List<String> path) {
        if (path.equals(List.of("signature", "inputs")) || path.equals(List.of("signature", "outputs"))) {
            return new IdentityArray("id");
        }
        return null;
    }

    private static IllegalStateException conflict(WorkspacePatch<JsonValue> patch) {
        return new IllegalStateException("Function source rebase conflict at " + patch.path());
    }

    private record IdentityArray(String field) {
    }

    private record Parent(Object container, String leaf) {
    }

    private record Lookup(boolean found, Object value) {
        private static Lookup missing() {
            return new Lookup(false, null);
        }

        private boolean same(Lookup other) {
            return found == other.found && (!found || CoreFunctionSourcePatch.same(
                JsonValue.fromJava(value), JsonValue.fromJava(other.value)));
        }

        private boolean matches(JsonValue expected) {
            return found && expected != null && CoreFunctionSourcePatch.same(JsonValue.fromJava(value), expected);
        }
    }
}
