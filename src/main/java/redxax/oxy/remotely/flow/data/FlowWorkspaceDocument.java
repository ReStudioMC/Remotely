package redxax.oxy.remotely.flow.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class FlowWorkspaceDocument {
    private static final int MAX_PATCHES = 512;
    private static final Set<String> STABLE_ARRAYS = Set.of(
        "connections", "functionInputs", "functionOutputs", "editorPassthroughs");
    private static final Set<String> ORDER_SENSITIVE_STABLE_ARRAYS = Set.of("functionInputs", "functionOutputs");
    private static final Set<String> SERVER_MANAGED_ROOTS = Set.of(
        "id", "worldName", "flowId", "function", "resourceType", "resourceRevision", "resourceHash",
        "resourceMutationId", "enabled");

    private FlowWorkspaceDocument() {
    }

    public static JsonObject fromGraph(FlowGraph graph) {
        return FlowSerializer.toJsonObject(graph);
    }

    public static JsonObject snapshot(FlowGraph graph) {
        return FlowSerializer.toSnapshotJsonObject(graph);
    }

    public static List<WorkspacePatch<JsonElement>> diff(JsonObject before, JsonObject after) {
        ArrayList<WorkspacePatch<JsonElement>> patches = new ArrayList<>();
        diff("", before, after, patches);
        if (patches.size() <= MAX_PATCHES) {
            return List.copyOf(patches);
        }
        patches.clear();
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            JsonElement previous = before.get(key);
            JsonElement next = after.get(key);
            if (equals(previous, next)) {
                continue;
            }
            String path = "/" + escape(key);
            if (next != null && next.isJsonArray() && STABLE_ARRAYS.contains(key)
                && previous != null && previous.isJsonArray()) {
                ArrayList<WorkspacePatch<JsonElement>> stablePatches = new ArrayList<>();
                diffStableArray(path, key, previous.getAsJsonArray(), next.getAsJsonArray(), stablePatches);
                if (patches.size() + stablePatches.size() <= MAX_PATCHES) {
                    patches.addAll(stablePatches);
                } else {
                    patches.add(new WorkspacePatch<>("set", path, next.deepCopy()));
                }
            } else {
                patches.add(next == null ? new WorkspacePatch<>("remove", path, JsonNull.INSTANCE)
                    : new WorkspacePatch<>("set", path, next.deepCopy()));
            }
        }
        if (patches.size() > MAX_PATCHES) {
            throw new IllegalArgumentException("Workspace patch batch exceeds " + MAX_PATCHES + " operations");
        }
        return List.copyOf(patches);
    }

    public static JsonObject editableWorkspace(JsonObject document) {
        JsonObject editable = document != null ? document.deepCopy() : new JsonObject();
        SERVER_MANAGED_ROOTS.forEach(editable::remove);
        return editable;
    }

    public static List<WorkspacePatch<JsonElement>> diffEditableWorkspace(JsonObject before, JsonObject after) {
        return diff(editableWorkspace(before), editableWorkspace(after));
    }

    public static JsonObject rebase(JsonObject originalBase, JsonObject desired, JsonObject latest) {
        Objects.requireNonNull(originalBase, "Original workspace document is required");
        Objects.requireNonNull(desired, "Desired workspace document is required");
        Objects.requireNonNull(latest, "Latest workspace document is required");
        JsonObject candidate = latest.deepCopy();
        for (WorkspacePatch<JsonElement> patch : diffEditableWorkspace(originalBase, desired)) {
            applyRebased(originalBase, candidate, patch);
        }
        return candidate;
    }

    public static JsonObject reapply(JsonObject desired, JsonObject latest) {
        Objects.requireNonNull(desired, "Desired workspace document is required");
        Objects.requireNonNull(latest, "Latest workspace document is required");
        JsonObject candidate = latest.deepCopy();
        JsonObject editable = editableWorkspace(desired);
        Set<String> keys = new TreeSet<>();
        keys.addAll(editable.keySet());
        editableWorkspace(latest).keySet().forEach(keys::add);
        for (String key : keys) {
            JsonElement value = editable.get(key);
            if (value == null) {
                candidate.remove(key);
            } else {
                candidate.add(key, value.deepCopy());
            }
        }
        return candidate;
    }

    public static JsonObject reapply(JsonObject originalBase, JsonObject desired, JsonObject latest) {
        Objects.requireNonNull(originalBase, "Original workspace document is required");
        return reapply(desired, latest);
    }

    public static void apply(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        Objects.requireNonNull(document, "Workspace document is required");
        Objects.requireNonNull(patches, "Workspace patches are required");
        JsonObject candidate = document.deepCopy();
        for (WorkspacePatch<JsonElement> patch : patches) {
            applyOne(candidate, patch);
        }
        document.entrySet().clear();
        candidate.entrySet().forEach(entry -> document.add(entry.getKey(), entry.getValue().deepCopy()));
    }

    private static void diff(String path, JsonElement before, JsonElement after, List<WorkspacePatch<JsonElement>> patches) {
        if (equals(before, after)) {
            return;
        }
        if (before != null && after != null && before.isJsonObject() && after.isJsonObject()) {
            Set<String> keys = new TreeSet<>();
            keys.addAll(before.getAsJsonObject().keySet());
            keys.addAll(after.getAsJsonObject().keySet());
            for (String key : keys) {
                diff(path + "/" + escape(key), before.getAsJsonObject().get(key), after.getAsJsonObject().get(key), patches);
            }
            return;
        }
        if (before != null && after != null && before.isJsonArray() && after.isJsonArray()) {
            String field = path.startsWith("/") ? path.substring(1) : path;
            if (STABLE_ARRAYS.contains(field)) {
                diffStableArray(path, field, before.getAsJsonArray(), after.getAsJsonArray(), patches);
                return;
            }
        }
        if (before != null && after != null && before.isJsonArray() && after.isJsonArray() && "/connections".equals(path)) {
            diffConnections(path, before.getAsJsonArray(), after.getAsJsonArray(), patches);
            return;
        }
        if (after == null) {
            patches.add(new WorkspacePatch<>("remove", path, JsonNull.INSTANCE));
        } else {
            patches.add(new WorkspacePatch<>("set", path, after.deepCopy()));
        }
    }

    private static void diffConnections(String path, JsonArray before, JsonArray after, List<WorkspacePatch<JsonElement>> patches) {
        ArrayList<JsonElement> remaining = new ArrayList<>();
        before.forEach(value -> remaining.add(value.deepCopy()));
        for (JsonElement value : after) {
            int index = indexOf(remaining, value);
            if (index >= 0) {
                remaining.remove(index);
            } else {
                patches.add(new WorkspacePatch<>("array_add", path, value.deepCopy()));
            }
        }
        for (JsonElement value : remaining) {
            patches.add(new WorkspacePatch<>("array_remove", path, value));
        }
    }

    private static void diffStableArray(String path, String field, JsonArray before, JsonArray after,
                                        List<WorkspacePatch<JsonElement>> patches) {
        Map<String, JsonObject> previous = keyedObjects(field, before);
        Map<String, JsonObject> next = keyedObjects(field, after);
        if (previous == null || next == null) {
            if ("connections".equals(field) && allWithoutStableIdentity(before) && allWithoutStableIdentity(after)) {
                diffConnections(path, before, after, patches);
            } else {
                diffLegacyIdentityArray(path, before, after, patches);
            }
            return;
        }
        if (ORDER_SENSITIVE_STABLE_ARRAYS.contains(field)
            && !List.copyOf(previous.keySet()).equals(List.copyOf(next.keySet()))) {
            patches.add(new WorkspacePatch<>("set", path, after.deepCopy()));
            return;
        }
        Set<String> identities = new TreeSet<>();
        identities.addAll(previous.keySet());
        identities.addAll(next.keySet());
        for (String identity : identities) {
            JsonObject oldValue = previous.get(identity);
            JsonObject newValue = next.get(identity);
            if (oldValue == null) {
                patches.add(new WorkspacePatch<>("array_add", path, newValue.deepCopy()));
                continue;
            }
            if (newValue == null) {
                patches.add(new WorkspacePatch<>("array_remove", path, oldValue.deepCopy()));
                continue;
            }
            diff(path + "/@" + escape(identity), oldValue, newValue, patches);
        }
    }

    private static void diffLegacyIdentityArray(String path, JsonArray before, JsonArray after,
                                                List<WorkspacePatch<JsonElement>> patches) {
        if (!before.equals(after)) {
            patches.add(new WorkspacePatch<>("set", path, after.deepCopy()));
        }
    }

    private static Map<String, JsonObject> keyedObjects(String field, JsonArray values) {
        LinkedHashMap<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) {
                return null;
            }
            String identity = stableIdentity(field, value.getAsJsonObject());
            if (identity.isBlank() || result.put(identity, value.getAsJsonObject()) != null) {
                return null;
            }
        }
        return result;
    }

    private static boolean allWithoutStableIdentity(JsonArray values) {
        for (JsonElement value : values) {
            if (!value.isJsonObject() || !stableIdentity("", value.getAsJsonObject()).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static void applyOne(JsonObject document, WorkspacePatch<JsonElement> patch) {
        if (patch == null || patch.path() == null || !patch.path().startsWith("/")) {
            throw new IllegalArgumentException("Invalid workspace patch");
        }
        List<String> segments = segments(patch.path());
        if (segments.isEmpty() || segments.getFirst().isBlank()) {
            throw new IllegalArgumentException("Invalid workspace patch path");
        }
        JsonElement parent = parent(document, segments);
        String leaf = segments.getLast();
        switch (patch.op()) {
            case "set" -> set(parent, leaf, patch.value());
            case "remove" -> remove(parent, leaf);
            case "array_add" -> addArrayValue(array(parent, leaf), leaf, patch.value());
            case "array_remove" -> removeArrayValue(array(parent, leaf), leaf, patch.value());
            default -> throw new IllegalArgumentException("Invalid workspace patch operation");
        }
    }

    private static JsonElement parent(JsonObject document, List<String> path) {
        JsonElement current = document;
        for (int index = 0; index < path.size() - 1; index++) {
            String segment = path.get(index);
            if (current.isJsonObject()) {
                JsonObject object = current.getAsJsonObject();
                JsonElement next = object.get(segment);
                if (next == null || next.isJsonNull()) {
                    throw new IllegalArgumentException("Workspace patch path does not exist: " + segment);
                }
                current = next;
            } else if (current.isJsonArray()) {
                int arrayIndex = arrayIndex(current.getAsJsonArray(), segment);
                if (arrayIndex < 0 || arrayIndex >= current.getAsJsonArray().size()) {
                    throw new IllegalArgumentException("Workspace array identity does not exist: " + segment);
                }
                current = current.getAsJsonArray().get(arrayIndex);
            } else {
                throw new IllegalArgumentException("Workspace patch path traverses a scalar value");
            }
        }
        return current;
    }

    private static void set(JsonElement parent, String leaf, JsonElement value) {
        if (value == null) {
            throw new IllegalArgumentException("set patches require a value");
        }
        if (parent.isJsonObject()) {
            parent.getAsJsonObject().add(leaf, copy(value));
        } else if (parent.isJsonArray()) {
            int index = arrayIndex(parent.getAsJsonArray(), leaf);
            if (index >= 0 && index < parent.getAsJsonArray().size()) {
                parent.getAsJsonArray().set(index, copy(value));
                return;
            }
            throw new IllegalArgumentException("Workspace array identity does not exist: " + leaf);
        } else {
            throw new IllegalArgumentException("Workspace patch parent is not a container");
        }
    }

    private static void remove(JsonElement parent, String leaf) {
        if (parent.isJsonObject()) {
            if (parent.getAsJsonObject().remove(leaf) == null) {
                throw new IllegalArgumentException("Workspace patch path does not exist: " + leaf);
            }
        } else if (parent.isJsonArray()) {
            int index = arrayIndex(parent.getAsJsonArray(), leaf);
            if (index >= 0 && index < parent.getAsJsonArray().size()) {
                parent.getAsJsonArray().remove(index);
                return;
            }
            throw new IllegalArgumentException("Workspace array identity does not exist: " + leaf);
        } else {
            throw new IllegalArgumentException("Workspace patch parent is not a container");
        }
    }

    private static JsonArray array(JsonElement parent, String leaf) {
        JsonElement value;
        if (parent.isJsonObject()) {
            value = parent.getAsJsonObject().get(leaf);
        } else if (parent.isJsonArray()) {
            int index = arrayIndex(parent.getAsJsonArray(), leaf);
            value = index >= 0 && index < parent.getAsJsonArray().size() ? parent.getAsJsonArray().get(index) : null;
        } else {
            value = null;
        }
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Workspace patch path is not an array: " + leaf);
        }
        return value.getAsJsonArray();
    }

    private static void addArrayValue(JsonArray array, String field, JsonElement value) {
        if (value == null) {
            throw new IllegalArgumentException("array_add patches require a value");
        }
        JsonElement next = copy(value);
        String identity = next.isJsonObject() ? stableIdentity(field, next.getAsJsonObject()) : "";
        for (JsonElement existing : array) {
            if (existing.equals(next)) {
                return;
            }
            if (!identity.isBlank() && existing.isJsonObject()
                && identity.equals(stableIdentity(field, existing.getAsJsonObject()))) {
                throw new IllegalArgumentException("array_add conflicts with an existing stable identity: " + identity);
            }
        }
        array.add(next);
    }

    private static void removeArrayValue(JsonArray array, String field, JsonElement value) {
        if (value == null) {
            throw new IllegalArgumentException("array_remove patches require a value");
        }
        if (value != null && value.isJsonObject()) {
            String identity = stableIdentity(field, value.getAsJsonObject());
            if (!identity.isBlank()) {
                for (int index = 0; index < array.size(); index++) {
                    JsonElement existing = array.get(index);
                    if (existing.isJsonObject() && identity.equals(stableIdentity(field, existing.getAsJsonObject()))) {
                        if (!existing.equals(value)) {
                            throw new IllegalArgumentException("array_remove conflicts with the current stable identity value: " + identity);
                        }
                        array.remove(index);
                        return;
                    }
                }
                throw new IllegalArgumentException("array_remove stable identity does not exist: " + identity);
            }
        }
        for (int index = array.size() - 1; index >= 0; index--) {
            if (array.get(index).equals(value)) {
                array.remove(index);
                return;
            }
        }
        throw new IllegalArgumentException("array_remove value does not exist");
    }

    private static int indexOf(List<JsonElement> values, JsonElement target) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).equals(target)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean equals(JsonElement first, JsonElement second) {
        return first == second || first != null && first.equals(second);
    }

    private static List<String> segments(String path) {
        String[] raw = path.substring(1).split("/", -1);
        ArrayList<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            segments.add(segment.replace("~1", "/").replace("~0", "~"));
        }
        return segments;
    }

    private static int arrayIndex(JsonArray array, String segment) {
        if (segment.startsWith("@")) {
            String identity = segment.substring(1);
            for (int index = 0; index < array.size(); index++) {
                JsonElement value = array.get(index);
                if (value.isJsonObject() && identity.equals(stableIdentity("", value.getAsJsonObject()))) {
                    return index;
                }
            }
            return -1;
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static void applyRebased(JsonObject original, JsonObject candidate, WorkspacePatch<JsonElement> patch) {
        List<String> path = segments(patch.path());
        switch (patch.op()) {
            case "set" -> {
                Lookup expected = lookup(original, path);
                Lookup current = lookup(candidate, path);
                if (current.matches(patch.value())) {
                    return;
                }
                if (!current.same(expected)) {
                    throw conflict(patch);
                }
            }
            case "remove" -> {
                Lookup current = lookup(candidate, path);
                if (!current.found()) {
                    return;
                }
                if (!current.same(lookup(original, path))) {
                    throw conflict(patch);
                }
            }
            case "array_add" -> {
                Lookup current = arrayMember(candidate, path, patch.value());
                if (current.matches(patch.value())) {
                    return;
                }
                if (current.found()) {
                    throw conflict(patch);
                }
                if (!lookup(candidate, path).found()) {
                    ensureArray(candidate, path);
                }
            }
            case "array_remove" -> {
                Lookup current = arrayMember(candidate, path, patch.value());
                if (!current.found()) {
                    return;
                }
                if (!current.matches(patch.value())) {
                    throw conflict(patch);
                }
            }
            default -> throw new IllegalArgumentException("Invalid workspace patch operation");
        }
        applyOne(candidate, patch);
    }

    private static void ensureArray(JsonObject document, List<String> path) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Workspace array path is empty");
        }
        Lookup parent = lookup(document, path.subList(0, path.size() - 1));
        if (!parent.found() || parent.value() == null || !parent.value().isJsonObject()) {
            throw conflict("/" + String.join("/", path));
        }
        parent.value().getAsJsonObject().add(path.getLast(), new JsonArray());
    }

    private static Lookup arrayMember(JsonObject document, List<String> path, JsonElement value) {
        JsonElement target = lookup(document, path).value();
        if (target == null) {
            return Lookup.missing();
        }
        if (!target.isJsonArray()) {
            throw new IllegalArgumentException("Workspace patch path is not an array: " + path);
        }
        String field = path.getLast();
        String identity = value != null && value.isJsonObject() ? stableIdentity(field, value.getAsJsonObject()) : "";
        JsonArray array = target.getAsJsonArray();
        for (JsonElement member : array) {
            if (!identity.isBlank() && member.isJsonObject()
                && identity.equals(stableIdentity(field, member.getAsJsonObject()))) {
                return new Lookup(true, member);
            }
            if (identity.isBlank() && equals(member, value)) {
                return new Lookup(true, member);
            }
        }
        return Lookup.missing();
    }

    private static Lookup lookup(JsonObject document, List<String> path) {
        JsonElement current = document;
        for (String segment : path) {
            if (current == null || current.isJsonNull()) {
                return Lookup.missing();
            }
            if (current.isJsonObject()) {
                JsonObject object = current.getAsJsonObject();
                if (!object.has(segment)) {
                    return Lookup.missing();
                }
                current = object.get(segment);
                continue;
            }
            if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                int index = arrayIndex(array, segment);
                if (index < 0 || index >= array.size()) {
                    return Lookup.missing();
                }
                current = array.get(index);
                continue;
            }
            return Lookup.missing();
        }
        return new Lookup(true, current);
    }

    private static RebaseConflictException conflict(WorkspacePatch<JsonElement> patch) {
        return new RebaseConflictException(patch.path());
    }

    private static RebaseConflictException conflict(String path) {
        return new RebaseConflictException(path);
    }

    private static String stableIdentity(String field, JsonObject value) {
        String explicit = text(value, "connectionId");
        if (!explicit.isBlank() && ("connections".equals(field) || value.has("sourceNodeId"))) {
            return "connection:" + explicit;
        }
        explicit = text(value, "parameterId");
        if (!explicit.isBlank() && (field.isBlank() || field.startsWith("function"))) {
            return "parameter:" + explicit;
        }
        explicit = text(value, "passthroughId");
        if (!explicit.isBlank() && (field.isBlank() || "editorPassthroughs".equals(field))) {
            return "passthrough:" + explicit;
        }
        if (("connections".equals(field) || field.isBlank())
            && !text(value, "sourceNodeId").isBlank() && !text(value, "sourcePinId").isBlank()
            && !text(value, "targetNodeId").isBlank() && !text(value, "targetPinId").isBlank()) {
            return "connection:" + join(value, "sourceNodeId", "sourcePinId", "targetNodeId", "targetPinId");
        }
        if (("editorPassthroughs".equals(field) || field.isBlank())
            && !text(value, "nodeId").isBlank() && !text(value, "inputPinId").isBlank()) {
            return "passthrough:" + join(value, "nodeId", "inputPinId");
        }
        return "";
    }

    private static String text(JsonObject value, String field) {
        JsonElement element = value.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static String join(JsonObject value, String... fields) {
        StringBuilder result = new StringBuilder();
        for (String field : fields) {
            if (result.length() > 0) {
                result.append('|');
            }
            result.append(text(value, field));
        }
        return result.toString();
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static JsonElement copy(JsonElement value) {
        return value != null ? value.deepCopy() : JsonNull.INSTANCE;
    }

    public static final class RebaseConflictException extends IllegalStateException {
        private final String path;

        private RebaseConflictException(String path) {
            super("Workspace rebase conflict at " + path);
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    private record Lookup(boolean found, JsonElement value) {
        private static Lookup missing() {
            return new Lookup(false, null);
        }

        private boolean same(Lookup other) {
            return found == other.found && (!found || FlowWorkspaceDocument.equals(value, other.value));
        }

        private boolean matches(JsonElement expected) {
            return found && FlowWorkspaceDocument.equals(value, expected);
        }
    }
}
