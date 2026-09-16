package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Gson;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.UuidIdentity;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.CustomContentGraphAdapter;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.data.FlowVariable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CoreGraphAuthoringAdapter {
    private static final Gson GSON = new Gson();
    private static final int MAX_INITIAL_REPEATABLE_ELEMENTS = 1024;
    private static final Set<String> GRAPH_FIELDS = Set.of("schemaVersion", "resource", "revision", "catalogBinding",
        "requiredCapabilities", "nodes", "connections", "passthroughs", "variables", "functions");
    private static final Set<String> NODE_FIELDS = Set.of("instanceId", "definition", "definitionVersion", "modeId", "values",
        "inspector", "branches", "repeatables", "inspectorState", "position");
    private static final Set<String> CONNECTION_FIELDS = Set.of("connectionId", "source", "target", "sourceElementId",
        "sourceBranchId", "targetElementId", "targetBranchId");
    private static final Set<String> ENDPOINT_FIELDS = Set.of("nodeId", "pinId", "elementId", "branchId");
    private static final Set<String> SOURCE_FIELDS = Set.of("signature", "graph");
    private static final Set<String> SIGNATURE_FIELDS = Set.of("function", "revision", "inputs", "outputs");
    private static final Set<String> PARAMETER_FIELDS = Set.of("id", "type", "required", "defaultValue");
    private static final Set<String> TYPED_VALUE_FIELDS = Set.of("state", "type", "variantId", "value", "locator");

    public record Context(ReSyncResourceType type, ServerResourceLocator resource, CatalogBinding binding,
                          CatalogVersion schemaVersion, Collection<CatalogCachePublication.Entry> catalogEntries,
                          long revision, boolean requireActiveCatalog) {
        public Context {
            type = Objects.requireNonNull(type, "Graph resource type is required");
            if (!type.isGraph() && type != ReSyncResourceType.CUSTOM_CONTENT) {
                throw new IllegalArgumentException("A graph resource type is required");
            }
            resource = Objects.requireNonNull(resource, "Graph resource is required");
            if (ReSyncResourceType.byTypeId(resource.resourceType().value()) != type) {
                throw new IllegalArgumentException("Graph resource type does not match the requested type");
            }
            binding = Objects.requireNonNull(binding, "Catalog binding is required");
            schemaVersion = schemaVersion != null ? schemaVersion : new CatalogVersion(1, 0);
            if (revision < 0L) {
                throw new IllegalArgumentException("Graph revision cannot be negative");
            }
            catalogEntries = catalogEntries == null ? List.of() : List.copyOf(catalogEntries);
            Map<ContractRef<NodeId>, CatalogCachePublication.Entry> unique = new LinkedHashMap<>();
            for (CatalogCachePublication.Entry entry : catalogEntries) {
                if (entry == null || unique.putIfAbsent(entry.definitionKey(), entry) != null) {
                    throw new IllegalArgumentException("Catalog entries must have unique typed identities");
                }
            }
        }

        public Context(ReSyncResourceType type, ServerResourceLocator resource, CatalogBinding binding,
                       Collection<CatalogCachePublication.Entry> catalogEntries, long revision,
                       boolean requireActiveCatalog) {
            this(type, resource, binding, new CatalogVersion(1, 0), catalogEntries, revision, requireActiveCatalog);
        }

        public static Context strict(ReSyncResourceType type, ServerResourceLocator resource, CatalogBinding binding,
                                     CatalogVersion schemaVersion, Collection<CatalogCachePublication.Entry> entries,
                                     long revision) {
            return new Context(type, resource, binding, schemaVersion, entries, revision, true);
        }

        public static Context permissive(ReSyncResourceType type, ServerResourceLocator resource, CatalogBinding binding,
                                         CatalogVersion schemaVersion, long revision) {
            return new Context(type, resource, binding, schemaVersion, List.of(), revision, false);
        }

        private Optional<CatalogCachePublication.Entry> entry(ContractRef<NodeId> reference) {
            return catalogEntries.stream().filter(entry -> entry.definitionKey().equals(reference)).findFirst();
        }
    }

    public record Result(boolean lossless, GraphDocument graphDocument, FunctionSourceDocument functionSourceDocument,
                         String reason) {
        public Result {
            if (lossless && ((graphDocument == null) == (functionSourceDocument == null))) {
                throw new IllegalArgumentException("A successful graph conversion must carry one document");
            }
            reason = reason == null ? "" : reason;
        }

        public static Result accepted(GraphDocument graph) {
            return new Result(true, graph, null, "");
        }

        public static Result accepted(FunctionSourceDocument source) {
            return new Result(true, null, source, "");
        }

        public static Result rejected(String reason) {
            return new Result(false, null, null, reason);
        }

        public Object payload() {
            return functionSourceDocument != null ? functionSourceDocument : graphDocument;
        }
    }

    public Result convert(FlowGraph graph, Context context) {
        return convert(graph, (JsonObject) null, context);
    }

    public GraphNode createNode(NodeInstanceId identity, FlowNode node, Context context) {
        Objects.requireNonNull(identity, "Node identity is required");
        Objects.requireNonNull(node, "Flow node is required");
        Objects.requireNonNull(context, "Graph authoring context is required");
        ContractRef<NodeId> definition = nodeReference(node.getType());
        CatalogCachePublication.Entry catalogEntry = requireCatalog(definition, context);
        JsonObject graph = newGraph(context);
        graph.getAsJsonArray("nodes").add(newNode(identity.canonicalText(), node, definition, catalogEntry, context));
        GraphDocument decoded = GraphDocumentCodec.INSTANCE.decode(CanonicalCodec.decodePermissive(graph.toString()));
        if (decoded.nodes().size() != 1 || !decoded.nodes().getFirst().instanceId().equals(identity)) {
            throw new IllegalArgumentException("The Core node could not be created with its exact identity.");
        }
        return decoded.nodes().getFirst();
    }

    public Result convert(FlowGraph graph, JsonObject authoritativePayload, Context context) {
        if (graph == null || context == null) {
            return Result.rejected("The Flow graph authoring context is incomplete.");
        }
        try {
            requireGraphIdentity(graph, context);
            JsonObject sourcePayload = authoritativePayload == null ? null : authoritativePayload.deepCopy();
            JsonObject graphObject = sourcePayload == null ? newGraph(context) : graphObject(sourcePayload, context.type());
            if (graphObject == null) {
                return Result.rejected("The authoritative Core payload has no graph document.");
            }
            validateTemplate(graphObject, context);
            patchGraphIdentity(graphObject, context);
            patchNodes(graphObject, graph, context);
            patchConnections(graphObject, graph, context);
            patchPassthroughs(graphObject, graph);
            patchVariables(graphObject, graph, context);
            if (context.type() == ReSyncResourceType.CUSTOM_CONTENT) {
                Set<String> known = new HashSet<>(GRAPH_FIELDS);
                known.add(CustomContentCoreEditor.CORE_GRAPH);
                mergeUnknown(graphObject, graph.getOpaqueProperties(), known);
                graphObject.add(CustomContentCoreEditor.CONTENT_PROPERTIES, GSON.toJsonTree(graph.getContentProperties()));
            }
            JsonValue graphValue = CanonicalCodec.decodePermissive(graphObject.toString());
            if (context.type() == ReSyncResourceType.FUNCTION) {
                JsonObject sourceObject = sourcePayload == null ? new JsonObject() : sourcePayload;
                sourceObject.add("graph", graphObject);
                patchSignature(sourceObject, graph, context);
                JsonValue sourceValue = CanonicalCodec.decodePermissive(sourceObject.toString());
                return Result.accepted(FunctionSourceDocumentCodec.INSTANCE.decode(sourceValue));
            }
            return Result.accepted(GraphDocumentCodec.INSTANCE.decode(graphValue));
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            return Result.rejected(message == null || message.isBlank()
                ? "The Flow graph cannot be represented as a typed Core document." : message);
        }
    }

    public Result convert(FlowGraph graph, CoreGraphUiProjection.Baseline baseline, Context context) {
        if (baseline == null) {
            return convert(graph, context);
        }
        if (context == null) {
            return Result.rejected("The Flow graph authoring context is incomplete.");
        }
        if (baseline.key().type() != context.type() || !baseline.key().id().equals(graph != null ? graph.getId() : null)) {
            return Result.rejected("The Flow graph identity does not match its Core baseline.");
        }
        return convert(graph, baseline.corePayload(), context);
    }

    private void requireGraphIdentity(FlowGraph graph, Context context) {
        if (graph.getId() == null || graph.getId().isBlank()) {
            throw new IllegalArgumentException("A stable Flow graph ID is required.");
        }
        if (!context.resource().id().equals(graph.getId())) {
            throw new IllegalArgumentException("The Flow graph ID does not match the Core resource.");
        }
        if (!context.type().typeId().equalsIgnoreCase(graph.getResourceType())) {
            throw new IllegalArgumentException("The Flow graph resource type does not match the Core resource.");
        }
        if ((context.type() == ReSyncResourceType.FUNCTION) != graph.isFunction()) {
            throw new IllegalArgumentException("The Flow graph function kind does not match the Core resource.");
        }
    }

    private JsonObject newGraph(Context context) {
        JsonObject graph = new JsonObject();
        graph.add("schemaVersion", object(Map.of("generation", context.schemaVersion().generation(),
            "minor", context.schemaVersion().minor())));
        graph.add("resource", GSON.toJsonTree(context.resource().canonicalValue()));
        graph.addProperty("revision", context.revision());
        graph.add("catalogBinding", binding(context.binding()));
        graph.add("requiredCapabilities", new JsonArray());
        graph.add("nodes", new JsonArray());
        graph.add("connections", new JsonArray());
        return graph;
    }

    private void validateTemplate(JsonObject graph, Context context) {
        JsonObject resource = object(graph, "resource");
        if (resource == null || !context.resource().canonicalValue().equals(CanonicalCodec.decodePermissive(resource.toString()).toJava())) {
            throw new IllegalArgumentException("The Core graph resource identity is not authoritative.");
        }
        JsonObject binding = object(graph, "catalogBinding");
        if (binding == null || !context.binding().canonicalText().equals(bindingText(binding))) {
            throw new IllegalArgumentException("The Core graph catalog binding is not authoritative.");
        }
        if (context.revision() != longValue(graph, "revision", -1L)) {
            throw new IllegalArgumentException("The Core graph revision is not authoritative.");
        }
        if (graph.get("nodes") == null || !graph.get("nodes").isJsonArray()
            || graph.get("connections") == null || !graph.get("connections").isJsonArray()) {
            throw new IllegalArgumentException("The Core graph topology shape is unsupported.");
        }
    }

    private void patchGraphIdentity(JsonObject graph, Context context) {
        graph.addProperty("revision", context.revision());
        graph.add("catalogBinding", binding(context.binding()));
        graph.add("schemaVersion", object(Map.of("generation", context.schemaVersion().generation(),
            "minor", context.schemaVersion().minor())));
    }

    private void patchNodes(JsonObject graph, FlowGraph current, Context context) {
        JsonArray baselineNodes = graph.getAsJsonArray("nodes");
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonElement value : baselineNodes) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Core graph nodes must be objects.");
            }
            JsonObject node = value.getAsJsonObject();
            String id = text(node, "instanceId");
            if (id == null || byId.put(id, node) != null) {
                throw new IllegalArgumentException("Core graph node identities are not unique.");
            }
        }
        Map<String, FlowNode> currentNodes = current.getNodes() == null ? Map.of() : current.getNodes();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, FlowNode> entry : currentNodes.entrySet()) {
            String id = requireCanonicalUuid(entry.getKey(), "node instance ID");
            if (!seen.add(id) || entry.getValue() == null) {
                throw new IllegalArgumentException("Flow graph node identities are not unique.");
            }
            FlowNode node = entry.getValue();
            ContractRef<NodeId> definition = nodeReference(node.getType());
            CatalogCachePublication.Entry catalogEntry = requireCatalog(definition, context);
            JsonObject target = byId.get(id);
            if (target == null) {
                target = newNode(id, node, definition, catalogEntry, context);
            } else {
                patchExistingNode(target, node, definition, catalogEntry, context);
            }
            byId.put(id, target);
        }
        JsonArray output = new JsonArray();
        for (String id : seen.stream().sorted().toList()) {
            output.add(byId.get(id));
        }
        graph.add("nodes", output);
    }

    private JsonObject newNode(String id, FlowNode node, ContractRef<NodeId> definition,
                               CatalogCachePublication.Entry catalogEntry, Context context) {
        JsonObject output = new JsonObject();
        output.addProperty("instanceId", id);
        output.add("definition", GSON.toJsonTree(definition.canonicalValue()));
        int version = catalogEntry != null ? descriptorSchemaVersion(catalogEntry)
            : node.getVersion() > 0 ? node.getVersion() : 1;
        output.addProperty("definitionVersion", version);
        output.add("values", encodeNewValues(contentPinValues(node, context), catalogEntry, context.resource().serverId()));
        output.add("inspector", descriptorInspectorDefaults(catalogEntry));
        output.add("branches", new JsonArray());
        output.add("repeatables", descriptorRepeatableDefaults(catalogEntry));
        output.add("inspectorState", inspectorState());
        output.add("position", position(node));
        mergeUnknown(output, node.getOpaqueProperties(), NODE_FIELDS);
        patchContentInputs(output, node, context);
        return output;
    }

    private int descriptorSchemaVersion(CatalogCachePublication.Entry entry) {
        JsonElement value = parseDescriptor(entry).get("schemaVersion");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("The active catalog descriptor has no node schema version.");
        }
        int version = value.getAsInt();
        if (version < 1) {
            throw new IllegalArgumentException("The active catalog descriptor has an invalid node schema version.");
        }
        return version;
    }

    private void patchExistingNode(JsonObject target, FlowNode node, ContractRef<NodeId> definition,
                                   CatalogCachePublication.Entry catalogEntry, Context context) {
        String expectedType = definitionText(object(target, "definition"));
        if (!definition.canonicalText().equals(expectedType)) {
            throw new IllegalArgumentException("Replacing a Core node definition would discard typed node state.");
        }
        target.addProperty("definitionVersion", node.getVersion() > 0 ? node.getVersion() : 1);
        target.add("position", position(node));
        target.add("values", encodeValues(contentPinValues(node, context), object(target, "values"), catalogEntry,
            context.resource().serverId()));
        mergeUnknown(target, node.getOpaqueProperties(), NODE_FIELDS);
        patchContentInputs(target, node, context);
    }

    private Map<String, Object> contentPinValues(FlowNode node, Context context) {
        if (context.type() != ReSyncResourceType.CUSTOM_CONTENT || CustomContentGraphAdapter.typeFromNode(node.getType()) == null) {
            return node.getInputValues();
        }
        Map<String, Object> values = new LinkedHashMap<>(node.getInputValues());
        values.remove(CustomContentGraphAdapter.FLOW_BRANCHES_KEY);
        values.remove("armor_slot");
        return values;
    }

    private void patchContentInputs(JsonObject target, FlowNode node, Context context) {
        if (context.type() != ReSyncResourceType.CUSTOM_CONTENT || CustomContentGraphAdapter.typeFromNode(node.getType()) == null) {
            return;
        }
        JsonObject inputs = new JsonObject();
        for (String key : List.of(CustomContentGraphAdapter.FLOW_BRANCHES_KEY, "armor_slot")) {
            if (node.getInputValues().containsKey(key)) {
                inputs.add(key, GSON.toJsonTree(node.getInputValues().get(key)));
            }
        }
        target.add(CustomContentCoreEditor.CONTENT_INPUTS, inputs);
    }

    private JsonObject encodeValues(Map<String, Object> values, JsonObject baselineValues,
                                     CatalogCachePublication.Entry catalogEntry, ServerId serverId) {
        Map<String, JsonObject> descriptorTypes = descriptorPinTypes(catalogEntry);
        Map<String, Object> current = values == null ? Map.of() : values;
        JsonObject output = new JsonObject();
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Core pin identities must not be blank.");
            }
            JsonObject baselinePin = baselineValues == null ? null : object(baselineValues, entry.getKey());
            JsonObject typedBaseline = baselinePin == null ? null : object(baselinePin, "value");
            JsonObject type = typedBaseline == null ? descriptorTypes.get(entry.getKey()) : object(typedBaseline, "type");
            if (type == null) {
                throw new IllegalArgumentException("The active catalog has no typed definition for pin " + entry.getKey());
            }
            if (typedBaseline != null && descriptorTypes.containsKey(entry.getKey())
                && !sameJson(type, descriptorTypes.get(entry.getKey()))) {
                throw new IllegalArgumentException("The active catalog pin type does not match the Core document.");
            }
            JsonObject typed = typedBaseline == null ? typedValue(type, entry.getValue(), serverId)
                : patchTypedValue(typedBaseline, entry.getValue(), serverId);
            JsonObject pin = baselinePin == null ? new JsonObject() : baselinePin.deepCopy();
            pin.add("value", typed);
            output.add(entry.getKey(), pin);
        }
        return output;
    }

    private JsonObject encodeNewValues(Map<String, Object> values, CatalogCachePublication.Entry catalogEntry,
                                       ServerId serverId) {
        JsonObject output = descriptorPinDefaults(catalogEntry);
        JsonObject explicit = encodeValues(values, output, catalogEntry, serverId);
        explicit.entrySet().forEach(entry -> output.add(entry.getKey(), entry.getValue()));
        return output;
    }

    private JsonObject descriptorPinDefaults(CatalogCachePublication.Entry entry) {
        JsonObject output = new JsonObject();
        if (entry == null) {
            return output;
        }
        JsonArray pins = parseDescriptor(entry).getAsJsonArray("pins");
        if (pins == null) {
            return output;
        }
        for (JsonElement value : pins) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("The active catalog contains an invalid pin descriptor.");
            }
            JsonObject pin = value.getAsJsonObject();
            if (!"input".equalsIgnoreCase(text(pin, "direction"))) {
                continue;
            }
            JsonObject repeatable = object(pin, "repeatable");
            if (repeatable != null && repeatable.has("enabled") && repeatable.get("enabled").getAsBoolean()) {
                continue;
            }
            JsonElement defaultValue = declaredDefault(pin);
            if (defaultValue == null) {
                continue;
            }
            String id = text(pin, "id");
            JsonElement type = pin.get("type");
            if (id == null || id.isBlank() || type == null || output.has(id)) {
                throw new IllegalArgumentException("The active catalog pin default has no unique typed identity.");
            }
            JsonObject pinValue = new JsonObject();
            pinValue.add("value", descriptorDefault(type, defaultValue));
            output.add(id, pinValue);
        }
        return output;
    }

    private JsonArray descriptorRepeatableDefaults(CatalogCachePublication.Entry entry) {
        JsonArray output = new JsonArray();
        if (entry == null) {
            return output;
        }
        JsonObject descriptor = parseDescriptor(entry);
        JsonArray groups = descriptor.getAsJsonArray("repeatables");
        if (groups == null) {
            return output;
        }
        JsonArray pins = descriptor.getAsJsonArray("pins");
        Map<String, JsonObject> pinsById = new LinkedHashMap<>();
        if (pins != null) {
            for (JsonElement pin : pins) {
                JsonObject value = pin.getAsJsonObject();
                pinsById.put(text(value, "id"), value);
            }
        }
        int remaining = MAX_INITIAL_REPEATABLE_ELEMENTS;
        Set<String> identities = new HashSet<>();
        for (JsonElement value : groups) {
            JsonObject group = value.getAsJsonObject();
            String id = RepeatableGroupId.of(text(group, "id")).canonicalText();
            int minimum = group.get("minimum").getAsBigDecimal().intValueExact();
            int maximum = group.get("maximum").getAsBigDecimal().intValueExact();
            if (!identities.add(id) || minimum < 0 || maximum < minimum || minimum > remaining) {
                throw new IllegalArgumentException("The active catalog repeatable defaults exceed valid creation bounds.");
            }
            remaining -= minimum;
            JsonObject defaults = new JsonObject();
            JsonArray members = group.getAsJsonArray("members");
            if (minimum > 0 && (members == null || members.isEmpty())) {
                throw new IllegalArgumentException("The active catalog repeatable group has no typed member identities.");
            }
            if (members != null) {
                for (JsonElement memberValue : members) {
                    JsonObject member = memberValue.getAsJsonObject();
                    String pinId = PinId.of(text(member, "pinId")).canonicalText();
                    JsonObject pin = pinsById.get(pinId);
                    JsonObject repeatable = pin == null ? null : object(pin, "repeatable");
                    if (pin == null || repeatable == null || !repeatable.get("enabled").getAsBoolean()
                        || !id.equals(text(repeatable, "groupId"))
                        || !Objects.equals(text(pin, "direction"), text(member, "direction"))
                        || !Objects.equals(pin.get("type"), member.get("type"))) {
                        throw new IllegalArgumentException("The active catalog repeatable member does not match its declared pin.");
                    }
                    JsonElement defaultValue = declaredDefault(pin);
                    if ("input".equalsIgnoreCase(text(pin, "direction")) && defaultValue != null) {
                        JsonObject pinValue = new JsonObject();
                        pinValue.add("value", descriptorDefault(pin.get("type"), defaultValue));
                        defaults.add(pinId, pinValue);
                    }
                }
            }
            JsonArray elements = new JsonArray();
            for (int index = 0; index < minimum; index++) {
                JsonObject element = new JsonObject();
                element.addProperty("elementId", RepeatableElementId.interactive().canonicalText());
                element.add("values", defaults.deepCopy());
                elements.add(element);
            }
            JsonObject binding = new JsonObject();
            binding.addProperty("groupId", id);
            binding.addProperty("ordered", group.get("ordered").getAsBoolean());
            binding.add("elements", elements);
            output.add(binding);
        }
        return output;
    }

    private JsonObject descriptorInspectorDefaults(CatalogCachePublication.Entry entry) {
        JsonObject output = new JsonObject();
        if (entry == null) {
            return output;
        }
        JsonObject inspector = object(parseDescriptor(entry), "inspector");
        JsonArray sections = inspector != null ? inspector.getAsJsonArray("sections") : null;
        if (sections == null) {
            return output;
        }
        for (JsonElement sectionValue : sections) {
            JsonObject section = sectionValue != null && sectionValue.isJsonObject() ? sectionValue.getAsJsonObject() : null;
            JsonArray rows = section != null ? section.getAsJsonArray("rows") : null;
            if (rows == null) {
                continue;
            }
            for (JsonElement rowValue : rows) {
                JsonObject row = rowValue != null && rowValue.isJsonObject() ? rowValue.getAsJsonObject() : null;
                addInspectorDefaults(row != null ? row.getAsJsonArray("fields") : null, output);
            }
        }
        return output;
    }

    private void addInspectorDefaults(JsonArray fields, JsonObject output) {
        if (fields == null) {
            return;
        }
        for (JsonElement fieldValue : fields) {
            if (!fieldValue.isJsonObject()) {
                throw new IllegalArgumentException("The active catalog contains an invalid inspector field.");
            }
            JsonObject field = fieldValue.getAsJsonObject();
            String id = text(field, "id");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("The active catalog inspector field has no stable identity.");
            }
            JsonElement defaultValue = declaredDefault(field);
            if (defaultValue != null) {
                JsonElement type = field.has("valueType") ? field.get("valueType") : field.get("type");
                if (type == null || output.has(id)) {
                    throw new IllegalArgumentException("The active catalog inspector default has no unique typed identity.");
                }
                output.add(id, descriptorDefault(type, defaultValue));
            }
            addInspectorDefaults(field.getAsJsonArray("children"), output);
            addInspectorDefaults(field.getAsJsonArray("fields"), output);
            JsonObject element = object(field, "element");
            if (element != null) {
                JsonArray elements = new JsonArray();
                elements.add(element);
                addInspectorDefaults(elements, output);
            }
        }
    }

    private JsonElement declaredDefault(JsonObject descriptor) {
        return descriptor.has("default") ? descriptor.get("default")
            : descriptor.has("defaultValue") ? descriptor.get("defaultValue") : null;
    }

    private JsonObject descriptorDefault(JsonElement declaredType, JsonElement defaultValue) {
        JsonObject type = coreType(declaredType);
        if (defaultValue != null && defaultValue.isJsonObject()) {
            JsonObject typed = defaultValue.getAsJsonObject();
            String state = text(typed, "state");
            JsonElement typedType = typed.get("type");
            if (state != null ^ typedType != null) {
                throw new IllegalArgumentException("The active catalog typed default is incomplete.");
            }
            if (state != null && typedType != null) {
                if (!sameJson(type, coreType(typedType))) {
                    throw new IllegalArgumentException("The active catalog default type does not match its field type.");
                }
                JsonObject output = typed.deepCopy();
                output.add("type", type);
                switch (state) {
                    case "absent" -> {
                        if (output.has("value") || output.has("locator") || output.has("variantId")) {
                            throw new IllegalArgumentException("The active catalog absent default is invalid.");
                        }
                    }
                    case "value" -> {
                        if (!output.has("value") || output.get("value").isJsonNull() || output.has("locator")) {
                            throw new IllegalArgumentException("The active catalog value default is invalid.");
                        }
                    }
                    case "null" -> {
                        if (output.has("value") || output.has("locator") || output.has("variantId")) {
                            throw new IllegalArgumentException("The active catalog null default is invalid.");
                        }
                    }
                    case "locator" -> {
                        if (!resourceDefaultType(type, output) || object(output, "locator") == null || output.has("value")) {
                            throw new IllegalArgumentException("The active catalog locator default is invalid.");
                        }
                    }
                    case "opaque" -> {
                        if (!output.has("value") || output.has("locator")) {
                            throw new IllegalArgumentException("The active catalog opaque default is invalid.");
                        }
                    }
                    default -> throw new IllegalArgumentException("The active catalog default state is unsupported.");
                }
                return output;
            }
        }
        return typedValue(type, defaultValue);
    }

    private JsonObject patchTypedValue(JsonObject baseline, Object value, ServerId serverId) {
        JsonObject output = baseline.deepCopy();
        JsonElement projected = projectedValue(baseline);
        JsonElement current = GSON.toJsonTree(value);
        if (sameJson(projected, current)) {
            return output;
        }
        if (value == null) {
            output.remove("value");
            output.remove("locator");
            output.remove("variantId");
            output.addProperty("state", "null");
            return output;
        }
        JsonObject type = object(baseline, "type");
        if (isResourceType(type)) {
            JsonObject locator = canonicalLocator(type, current, serverId);
            output.remove("value");
            output.remove("locator");
            output.remove("variantId");
            output.addProperty("state", "locator");
            output.add("locator", locator);
            return output;
        }
        output.remove("value");
        output.remove("locator");
        output.remove("variantId");
        String state = text(baseline, "state");
        if ("opaque".equals(state)) {
            output.addProperty("state", "opaque");
            output.add("value", current);
        } else if ("locator".equals(state) && current.isJsonObject()) {
            output.addProperty("state", "locator");
            output.add("locator", current);
        } else if ("value".equals(state) || "absent".equals(state) || "null".equals(state)) {
            output.addProperty("state", "value");
            output.add("value", current);
        } else {
            throw new IllegalArgumentException("An absent typed pin cannot be assigned new material without a typed catalog value.");
        }
        return output;
    }

    private JsonObject typedValue(JsonObject type, Object value) {
        return typedValue(type, value == null ? JsonNull.INSTANCE : GSON.toJsonTree(value), null);
    }

    private JsonObject typedValue(JsonObject type, JsonElement material) {
        return typedValue(type, material, null);
    }

    private JsonObject typedValue(JsonObject type, Object value, ServerId serverId) {
        return typedValue(type, value == null ? JsonNull.INSTANCE : GSON.toJsonTree(value), serverId);
    }

    private JsonObject typedValue(JsonObject type, JsonElement material, ServerId serverId) {
        JsonObject output = new JsonObject();
        output.add("type", type.deepCopy());
        if (material == null || material.isJsonNull()) {
            output.addProperty("state", "null");
            return output;
        }
        if (isResourceType(type)) {
            output.addProperty("state", "locator");
            output.add("locator", canonicalLocator(type, material, serverId));
        } else {
            output.addProperty("state", "value");
            output.add("value", material);
        }
        return output;
    }

    private void patchConnections(JsonObject graph, FlowGraph current, Context context) {
        JsonArray baselineConnections = graph.getAsJsonArray("connections");
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonElement value : baselineConnections) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Core graph connections must be objects.");
            }
            JsonObject connection = value.getAsJsonObject();
            String id = text(connection, "connectionId");
            if (id == null || byId.put(id, connection) != null) {
                throw new IllegalArgumentException("Core graph connection identities are not unique.");
            }
            requireCanonicalUuid(id, "connection ID");
        }
        List<FlowConnection> currentConnections = current.getConnections() == null ? List.of() : current.getConnections();
        Map<String, Integer> ordinals = new HashMap<>();
        List<JsonObject> connections = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FlowConnection currentConnection : currentConnections) {
            if (currentConnection == null) {
                throw new IllegalArgumentException("Core graph connections cannot contain null values.");
            }
            String sourceNode = requireCanonicalUuid(currentConnection.getSourceNodeId(), "source node ID");
            String targetNode = requireCanonicalUuid(currentConnection.getTargetNodeId(), "target node ID");
            String sourcePin = requirePin(currentConnection.getSourcePinId(), "source pin ID");
            String targetPin = requirePin(currentConnection.getTargetPinId(), "target pin ID");
            String endpointKey = sourceNode + "|" + sourcePin + "|" + targetNode + "|" + targetPin;
            int ordinal = ordinals.merge(endpointKey, 1, Integer::sum) - 1;
            String id = text(currentConnection.getOpaqueProperties(), "connectionId");
            if (id == null || id.isBlank()) {
                id = ConnectionId.deterministic(connectionNamespace(context.resource()), endpointKey + "|" + ordinal).canonicalText();
            } else {
                id = requireCanonicalUuid(id, "connection ID");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Flow graph connection identities are not unique.");
            }
            JsonObject connection = byId.get(id);
            if (connection == null) {
                connection = new JsonObject();
                connection.addProperty("connectionId", id);
                connection.add("source", new JsonObject());
                connection.add("target", new JsonObject());
            } else {
                connection = connection.deepCopy();
            }
            patchEndpoint(objectRequired(connection, "source"), currentConnection, true, sourceNode, sourcePin);
            patchEndpoint(objectRequired(connection, "target"), currentConnection, false, targetNode, targetPin);
            mergeUnknown(connection, currentConnection.getOpaqueProperties(), CONNECTION_FIELDS);
            connections.add(connection);
        }
        JsonArray output = new JsonArray();
        connections.stream().sorted(Comparator.comparing(connection -> text(connection, "connectionId"))).forEach(output::add);
        graph.add("connections", output);
    }

    private void patchEndpoint(JsonObject endpoint, FlowConnection connection, boolean source,
                               String nodeId, String pinId) {
        endpoint.addProperty("nodeId", nodeId);
        endpoint.addProperty("pinId", pinId);
        String elementKey = source ? "sourceElementId" : "targetElementId";
        String branchKey = source ? "sourceBranchId" : "targetBranchId";
        JsonElement element = connection.getOpaqueProperties().get(elementKey);
        JsonElement branch = connection.getOpaqueProperties().get(branchKey);
        if (element != null && !element.isJsonNull()) {
            endpoint.add("elementId", element.deepCopy());
        }
        if (branch != null && !branch.isJsonNull()) {
            endpoint.add("branchId", branch.deepCopy());
        }
    }

    private void patchPassthroughs(JsonObject graph, FlowGraph current) {
        List<FlowGraph.EditorPassthrough> currentPassthroughs = current.getEditorPassthroughs() == null
            ? List.of() : current.getEditorPassthroughs();
        if (currentPassthroughs.isEmpty()) {
            graph.remove("passthroughs");
            return;
        }
        JsonArray baseline = graph.getAsJsonArray("passthroughs");
        Map<String, JsonObject> baselineByIdentity = new LinkedHashMap<>();
        if (baseline != null) {
            for (JsonElement value : baseline) {
                if (!value.isJsonObject()) {
                    throw new IllegalArgumentException("Core graph passthroughs must be objects.");
                }
                JsonObject passthrough = value.getAsJsonObject();
                String nodeId = text(passthrough, "nodeId");
                String inputPin = text(passthrough, "inputPin");
                if (nodeId == null || inputPin == null
                    || baselineByIdentity.put(nodeId + "\u0000" + inputPin, passthrough) != null) {
                    throw new IllegalArgumentException("Core graph passthrough identities are not unique.");
                }
            }
        }
        Map<String, List<String>> connectionIds = new LinkedHashMap<>();
        for (FlowConnection connection : current.getConnections() == null ? List.<FlowConnection>of() : current.getConnections()) {
            String editorNode = connection.getEditorSourceNodeId();
            String editorPin = connection.getEditorSourcePin();
            if (editorNode == null || editorNode.isBlank() || editorPin == null
                || !editorPin.startsWith("__passthrough:")) {
                continue;
            }
            String connectionId = text(connection.getOpaqueProperties(), "connectionId");
            if (connectionId == null || connectionId.isBlank()) {
                throw new IllegalArgumentException("Core passthrough connections require stable connection IDs.");
            }
            String inputPin = editorPin.substring("__passthrough:".length());
            connectionIds.computeIfAbsent(editorNode + "\u0000" + inputPin, ignored -> new ArrayList<>()).add(connectionId);
        }
        JsonArray output = new JsonArray();
        Set<String> identities = new LinkedHashSet<>();
        for (FlowGraph.EditorPassthrough value : currentPassthroughs) {
            if (value == null || value.getNodeId() == null || value.getNodeId().isBlank()
                || value.getInputPinId() == null || value.getInputPinId().isBlank()) {
                throw new IllegalArgumentException("Core passthrough identities are required.");
            }
            String identity = value.getNodeId() + "\u0000" + value.getInputPinId();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("Flow graph passthrough identities are not unique.");
            }
            JsonObject passthrough = baselineByIdentity.get(identity);
            passthrough = passthrough == null ? new JsonObject() : passthrough.deepCopy();
            passthrough.addProperty("nodeId", value.getNodeId());
            passthrough.addProperty("inputPin", value.getInputPinId());
            List<String> ids = connectionIds.getOrDefault(identity, List.of());
            if (ids.isEmpty()) {
                passthrough.remove("connectionIds");
            } else {
                JsonArray references = new JsonArray();
                ids.stream().sorted().forEach(references::add);
                passthrough.add("connectionIds", references);
            }
            output.add(passthrough);
        }
        graph.add("passthroughs", output);
    }

    private void patchVariables(JsonObject graph, FlowGraph current, Context context) {
        List<FlowVariable> variables = current.getLocalVariables() == null ? List.of() : current.getLocalVariables();
        if (variables.isEmpty()) {
            graph.remove("variables");
            return;
        }
        JsonArray baseline = graph.getAsJsonArray("variables");
        Map<String, JsonObject> baselineByName = new LinkedHashMap<>();
        if (baseline != null) {
            for (JsonElement value : baseline) {
                if (!value.isJsonObject()) {
                    throw new IllegalArgumentException("Core graph variables must be objects.");
                }
                JsonObject variable = value.getAsJsonObject();
                String name = text(variable, "name");
                if (name == null || baselineByName.put(name, variable) != null) {
                    throw new IllegalArgumentException("Core graph variable identities are not unique.");
                }
            }
        }
        List<JsonObject> converted = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (FlowVariable variable : variables) {
            if (variable == null || variable.getName() == null || variable.getName().isBlank() || !names.add(variable.getName())) {
                throw new IllegalArgumentException("Flow graph variable names must be stable and unique.");
            }
            JsonObject prior = baselineByName.get(variable.getName());
            JsonObject value = prior == null ? new JsonObject() : prior.deepCopy();
            String variableId = prior == null ? UuidIdentity.deterministic("variable", context.resource().canonicalText()
                + "|" + variable.getName()).toString() : text(prior, "variableId");
            value.addProperty("variableId", requireCanonicalUuid(variableId, "variable ID"));
            value.addProperty("name", variable.getName());
            JsonObject type = prior == null ? flowType(variable.getType()) : object(prior, "type");
            if (type == null) {
                throw new IllegalArgumentException("The Flow graph variable has no typed Core type.");
            }
            value.add("type", type.deepCopy());
            if (variable.getInitialValue() != null) {
                value.add("value", typedValue(type, variable.getInitialValue()));
            } else {
                value.remove("value");
            }
            value.addProperty("scope", variable.getScope());
            value.addProperty("lifetime", variable.getLifetime());
            value.addProperty("owner", variable.getOwner());
            value.addProperty("absencePolicy", variable.getAbsencePolicy());
            value.addProperty("concurrencyPolicy", variable.getConcurrencyPolicy());
            converted.add(value);
        }
        JsonArray output = new JsonArray();
        converted.stream().sorted(Comparator.comparing(value -> UUID.fromString(text(value, "variableId")))).forEach(output::add);
        graph.add("variables", output);
    }

    private void patchSignature(JsonObject source, FlowGraph graph, Context context) {
        JsonObject signature = object(source, "signature");
        if (signature == null) {
            signature = new JsonObject();
            source.add("signature", signature);
        } else {
            signature = signature.deepCopy();
            source.add("signature", signature);
        }
        signature.add("function", GSON.toJsonTree(context.resource().canonicalValue()));
        signature.addProperty("revision", context.revision());
        JsonArray priorInputs = signature.getAsJsonArray("inputs");
        JsonArray priorOutputs = signature.getAsJsonArray("outputs");
        signature.add("inputs", parameters(graph.getFunctionInputs(), priorInputs, context));
        signature.add("outputs", parameters(graph.getFunctionOutputs(), priorOutputs, context));
    }

    private JsonArray parameters(List<FlowGraph.FunctionParameter> current, JsonArray prior, Context context) {
        List<FlowGraph.FunctionParameter> values = current == null ? List.of() : current;
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        if (prior != null) {
            for (JsonElement value : prior) {
                if (!value.isJsonObject()) {
                    throw new IllegalArgumentException("Function parameters must be objects.");
                }
                JsonObject parameter = value.getAsJsonObject();
                String id = text(parameter, "id");
                if (id == null || byId.put(id, parameter) != null) {
                    throw new IllegalArgumentException("Function parameter identities are not unique.");
                }
            }
        }
        JsonArray output = new JsonArray();
        for (FlowGraph.FunctionParameter parameter : values) {
            if (parameter == null) {
                throw new IllegalArgumentException("Function parameters cannot be null.");
            }
            String id = parameter.getParameterId();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Function parameters require stable IDs.");
            }
            id = FunctionParameterId.parseCanonicalText(id).canonicalText();
            JsonObject value = byId.get(id) == null ? new JsonObject() : byId.get(id).deepCopy();
            value.addProperty("id", id);
            String name = parameter.getDisplayName();
            if (name != null && !name.isBlank()) {
                value.addProperty("name", name);
            }
            if (parameter.getWidget() != null && !parameter.getWidget().isBlank()) {
                value.addProperty("widget", parameter.getWidget());
            }
            if (parameter.getOptionsSource() != null && !parameter.getOptionsSource().isBlank()) {
                value.addProperty("optionsSource", parameter.getOptionsSource());
            }
            JsonObject type = flowType(parameter.getTypeRef());
            value.add("type", type);
            value.addProperty("required", priorRequired(byId.get(id)));
            String defaultText = parameter.getDefaultValue();
            if (defaultText != null && !defaultText.isBlank()) {
                JsonElement defaultMaterial;
                try {
                    defaultMaterial = JsonParser.parseString(defaultText);
                } catch (RuntimeException exception) {
                    defaultMaterial = new JsonPrimitive(defaultText);
                }
                value.add("defaultValue", typedValue(type, jsonObject(defaultMaterial)));
            } else if (byId.get(id) == null) {
                value.remove("defaultValue");
            }
            if (!value.has("required")) {
                value.addProperty("required", true);
            }
            output.add(value);
        }
        return output;
    }

    private boolean priorRequired(JsonObject prior) {
        if (prior == null || !prior.has("required") || !prior.get("required").isJsonPrimitive()) {
            return true;
        }
        return prior.get("required").getAsBoolean();
    }

    private Map<String, JsonObject> descriptorPinTypes(CatalogCachePublication.Entry entry) {
        if (entry == null) {
            return Map.of();
        }
        JsonObject descriptor = parseDescriptor(entry);
        JsonArray pins = descriptor.getAsJsonArray("pins");
        if (pins == null) {
            return Map.of();
        }
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement value : pins) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("The active catalog contains an invalid pin descriptor.");
            }
            JsonObject pin = value.getAsJsonObject();
            String id = text(pin, "id");
            JsonElement rawType = pin.get("type");
            if (id == null || id.isBlank() || rawType == null) {
                throw new IllegalArgumentException("The active catalog pin has no stable typed identity.");
            }
            result.put(id, coreType(rawType));
        }
        return result;
    }

    private JsonObject parseDescriptor(CatalogCachePublication.Entry entry) {
        try {
            JsonObject descriptor = JsonParser.parseString(entry.data().canonicalText()).getAsJsonObject();
            if (!entry.definitionKey().id().value().equals(text(descriptor, "id"))) {
                throw new IllegalArgumentException("The active catalog descriptor identity does not match its typed key.");
            }
            return descriptor;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("The active catalog descriptor is not a typed object.", exception);
        }
    }

    private CatalogCachePublication.Entry requireCatalog(ContractRef<NodeId> definition, Context context) {
        if (!context.requireActiveCatalog()) {
            return null;
        }
        CatalogCachePublication.Entry entry = context.entry(definition).orElse(null);
        if (entry == null || entry.tombstone() || entry.opaque() || entry.state() != CatalogCacheState.ACTIVE) {
            throw new IllegalArgumentException("The Flow node is not present in the acknowledged active catalog: "
                + definition.canonicalText());
        }
        return entry;
    }

    private JsonObject flowType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Flow variable type is required.");
        }
        return flowType(FlowTypeRef.parse(value));
    }

    private JsonObject flowType(FlowTypeRef value) {
        if (value == null || !value.isResolved()) {
            throw new IllegalArgumentException("Flow type is not resolved by the active catalog.");
        }
        String id = value.getTypeId().toLowerCase(Locale.ROOT);
        List<FlowTypeRef> arguments = value.getArguments();
        return switch (id) {
            case "optional" -> unaryType("optional", arguments, value);
            case "list", "set", "queue", "stack" -> unaryType("list", arguments, value);
            case "map" -> binaryType("map", arguments, value);
            case "result" -> binaryNamedType("result", arguments, value);
            case "tuple" -> tupleType(arguments, value);
            case "resource_reference" -> namedType("builtin", id, arguments.stream().map(this::flowType).toList());
            default -> {
                int separator = id.indexOf(':');
                String owner = separator > 0 ? id.substring(0, separator) : "builtin";
                String local = separator > 0 ? id.substring(separator + 1) : id;
                yield namedType(owner, local, arguments.stream().map(this::flowType).toList());
            }
        };
    }

    private JsonObject coreType(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            throw new IllegalArgumentException("Catalog type expression is missing.");
        }
        if (raw.isJsonPrimitive()) {
            return namedType("builtin", raw.getAsString().toLowerCase(Locale.ROOT), List.of());
        }
        if (!raw.isJsonObject()) {
            throw new IllegalArgumentException("Catalog type expression is unsupported.");
        }
        JsonObject object = raw.getAsJsonObject();
        String kind = text(object, "kind");
        if (kind == null || kind.isBlank()) {
            if (object.has("ownerId") && object.has("localId")) {
                return namedType(text(object, "ownerId"), text(object, "localId"), List.of());
            }
            throw new IllegalArgumentException("Catalog type expression has no kind.");
        }
        String normalized = kind.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "named" -> namedTypeFromObject(object);
            case "optional" -> wrapperType("optional", object, "element");
            case "list" -> wrapperType("list", object, "element");
            case "map" -> mapType(object);
            case "tuple" -> tupleType(object);
            case "result" -> resultType(object);
            case "resource" -> resourceType(object);
            case "union" -> unionType(object);
            case "opaque" -> opaqueType(object);
            case "text", "string", "number", "integer", "float", "double", "boolean", "bool", "uuid", "execution", "json", "json_object" ->
                namedType("builtin", "text".equals(normalized) ? "string" : "bool".equals(normalized) ? "boolean"
                    : "json".equals(normalized) ? "json_object" : normalized, List.of());
            default -> throw new IllegalArgumentException("Catalog type expression kind is unsupported: " + kind);
        };
    }

    private JsonObject namedTypeFromObject(JsonObject source) {
        JsonObject reference = object(source, "type");
        if (reference == null) {
            throw new IllegalArgumentException("Named catalog type has no reference.");
        }
        requireTypeReference(reference);
        JsonArray arguments = source.getAsJsonArray("arguments");
        JsonArray converted = new JsonArray();
        if (arguments != null) {
            for (JsonElement value : arguments) {
                converted.add(coreType(value));
            }
        }
        JsonObject result = source.deepCopy();
        result.add("type", reference.deepCopy());
        result.add("arguments", converted);
        return result;
    }

    private JsonObject wrapperType(String kind, JsonObject source, String field) {
        JsonObject element = object(source, field);
        if (element == null) {
            throw new IllegalArgumentException("Catalog type wrapper has no element.");
        }
        JsonObject result = source.deepCopy();
        result.addProperty("kind", kind);
        result.add(field, coreType(element));
        return result;
    }

    private JsonObject mapType(JsonObject source) {
        JsonObject key = object(source, "key");
        JsonObject value = object(source, "value");
        if (key == null || value == null) {
            throw new IllegalArgumentException("Catalog map type has no key or value.");
        }
        JsonObject result = source.deepCopy();
        result.addProperty("kind", "map");
        result.add("key", coreType(key));
        result.add("value", coreType(value));
        return result;
    }

    private JsonObject tupleType(JsonObject source) {
        JsonArray values = source.getAsJsonArray("elements");
        if (values == null) {
            throw new IllegalArgumentException("Catalog tuple type has no elements.");
        }
        JsonObject result = source.deepCopy();
        result.addProperty("kind", "tuple");
        JsonArray elements = new JsonArray();
        for (JsonElement value : values) {
            elements.add(coreType(value));
        }
        result.add("elements", elements);
        return result;
    }

    private JsonObject resultType(JsonObject source) {
        JsonObject success = object(source, "success");
        JsonObject failure = object(source, "failure");
        if (success == null || failure == null) {
            throw new IllegalArgumentException("Catalog result type has no branches.");
        }
        JsonObject result = source.deepCopy();
        result.addProperty("kind", "result");
        result.add("success", coreType(success));
        result.add("failure", coreType(failure));
        return result;
    }

    private JsonObject resourceType(JsonObject source) {
        JsonObject type = object(source, "resourceType");
        if (type == null) {
            throw new IllegalArgumentException("Catalog resource type has no resource reference.");
        }
        requireTypeReference(type);
        JsonObject result = source.deepCopy();
        result.addProperty("kind", "resource");
        result.add("resourceType", type.deepCopy());
        return result;
    }

    private JsonObject unionType(JsonObject source) {
        JsonArray values = source.getAsJsonArray("variants");
        if (values == null) {
            throw new IllegalArgumentException("Catalog union type has no variants.");
        }
        JsonObject result = source.deepCopy();
        result.addProperty("kind", "union");
        JsonArray variants = new JsonArray();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Catalog union variants must be objects.");
            }
            JsonObject variant = value.getAsJsonObject().deepCopy();
            JsonObject type = object(variant, "type");
            if (type == null) {
                throw new IllegalArgumentException("Catalog union variant has no type.");
            }
            variant.add("type", coreType(type));
            variants.add(variant);
        }
        result.add("variants", variants);
        return result;
    }

    private JsonObject opaqueType(JsonObject source) {
        JsonObject reference = object(source, "type");
        if (reference == null) {
            throw new IllegalArgumentException("Catalog opaque type has no reference.");
        }
        requireTypeReference(reference);
        JsonObject result = source.deepCopy();
        result.addProperty("kind", "opaque");
        result.add("type", reference.deepCopy());
        return result;
    }

    private void requireTypeReference(JsonObject reference) {
        String owner = text(reference, "ownerId");
        String local = text(reference, "localId");
        if (owner == null || owner.isBlank() || local == null || local.isBlank()) {
            throw new IllegalArgumentException("Catalog type reference is incomplete.");
        }
        TypeReference.of(owner, local);
    }

    private JsonObject unaryType(String kind, List<FlowTypeRef> arguments, FlowTypeRef source) {
        if (arguments.size() != 1) {
            throw new IllegalArgumentException("Flow type " + source.getTypeId() + " requires one type argument.");
        }
        JsonObject result = new JsonObject();
        result.addProperty("kind", kind);
        result.add("element", flowType(arguments.getFirst()));
        return result;
    }

    private JsonObject binaryType(String kind, List<FlowTypeRef> arguments, FlowTypeRef source) {
        if (arguments.size() != 2) {
            throw new IllegalArgumentException("Flow type " + source.getTypeId() + " requires two type arguments.");
        }
        JsonObject result = new JsonObject();
        result.addProperty("kind", kind);
        result.add("key", flowType(arguments.getFirst()));
        result.add("value", flowType(arguments.get(1)));
        return result;
    }

    private JsonObject binaryNamedType(String kind, List<FlowTypeRef> arguments, FlowTypeRef source) {
        if (arguments.size() != 2) {
            throw new IllegalArgumentException("Flow type " + source.getTypeId() + " requires two type arguments.");
        }
        JsonObject result = namedType("builtin", kind, arguments.stream().map(this::flowType).toList());
        return result;
    }

    private JsonObject tupleType(List<FlowTypeRef> arguments, FlowTypeRef source) {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("Flow tuple type requires elements.");
        }
        JsonObject result = new JsonObject();
        result.addProperty("kind", "tuple");
        JsonArray elements = new JsonArray();
        arguments.forEach(value -> elements.add(flowType(value)));
        result.add("elements", elements);
        return result;
    }

    private JsonObject namedType(String owner, String local, List<JsonObject> arguments) {
        if (owner == null || owner.isBlank() || local == null || local.isBlank()) {
            throw new IllegalArgumentException("Typed references require owner and local IDs.");
        }
        JsonObject result = new JsonObject();
        result.addProperty("kind", "named");
        result.add("type", object(Map.of("ownerId", owner, "localId", local)));
        JsonArray values = new JsonArray();
        arguments.forEach(values::add);
        result.add("arguments", values);
        return result;
    }

    private ContractRef<NodeId> nodeReference(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Flow node type is required.");
        }
        int separator = type.indexOf(':');
        if (separator <= 0 || separator == type.length() - 1 || type.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("Flow node types must use an owner and local ID.");
        }
        return ContractRef.of(new OwnerId(type.substring(0, separator)), new NodeId(type.substring(separator + 1)));
    }

    private JsonObject graphObject(JsonObject payload, ReSyncResourceType type) {
        return type == ReSyncResourceType.FUNCTION ? object(payload, "graph") : payload;
    }

    private void mergeUnknown(JsonObject target, Map<String, JsonElement> unknown, Set<String> known) {
        if (unknown == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : unknown.entrySet()) {
            if (entry.getKey() == null || known.contains(entry.getKey())) {
                continue;
            }
            target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }

    private JsonObject inspectorState() {
        JsonObject state = new JsonObject();
        state.addProperty("kind", "inspector-state");
        state.addProperty("state", "clean");
        state.add("fields", new JsonObject());
        state.addProperty("fallback", "editable");
        return state;
    }

    private JsonObject position(FlowNode node) {
        if (!Double.isFinite(node.getX()) || !Double.isFinite(node.getY())) {
            throw new IllegalArgumentException("Flow node positions must be finite.");
        }
        return object(Map.of("x", node.getX(), "y", node.getY()));
    }

    private String definitionText(JsonObject definition) {
        if (definition == null) {
            return "";
        }
        String owner = text(definition, "ownerId");
        String local = text(definition, "localId");
        return owner == null || local == null ? "" : owner + "/" + local;
    }

    private String bindingText(JsonObject binding) {
        String generation = text(binding, "generation");
        String checksum = text(binding, "catalogChecksum");
        String manifest = text(binding, "bindingManifestHash");
        return generation == null || checksum == null || manifest == null ? "" : generation + "|" + checksum + "|" + manifest;
    }

    private JsonObject binding(CatalogBinding binding) {
        return object(Map.of("generation", binding.generation(), "catalogChecksum", binding.catalogChecksum().canonicalText(),
            "bindingManifestHash", binding.bindingManifestHash().canonicalText()));
    }

    private static JsonObject object(Map<String, ?> values) {
        return GSON.toJsonTree(values).getAsJsonObject();
    }

    private static JsonObject object(JsonObject source, String field) {
        JsonElement value = source == null ? null : source.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonObject objectRequired(JsonObject source, String field) {
        JsonObject value = object(source, field);
        if (value == null) {
            throw new IllegalArgumentException("Core graph endpoint is missing " + field + ".");
        }
        return value;
    }

    private static String text(JsonObject source, String field) {
        JsonElement value = source == null ? null : source.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static String text(Map<String, JsonElement> source, String field) {
        JsonElement value = source == null ? null : source.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static long longValue(JsonObject source, String field, long fallback) {
        JsonElement value = source == null ? null : source.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String requireCanonicalUuid(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A stable " + name + " is required.");
        }
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException("The " + name + " is not canonical.");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The " + name + " is not a canonical UUID.", exception);
        }
    }

    private static String requirePin(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("A stable " + name + " is required.");
        }
        new PinId(value);
        return value;
    }

    private static UUID connectionNamespace(ServerResourceLocator resource) {
        return UuidIdentity.deterministic("connection-namespace", resource.canonicalText());
    }

    private static boolean sameJson(JsonElement first, JsonElement second) {
        if (first == null || second == null) {
            return first == null && second == null;
        }
        try {
            return CanonicalCodec.decodePermissive(first.toString()).canonicalText()
                .equals(CanonicalCodec.decodePermissive(second.toString()).canonicalText());
        } catch (RuntimeException exception) {
            return first.equals(second);
        }
    }

    private static JsonElement projectedValue(JsonObject typed) {
        String state = text(typed, "state");
        if ("value".equals(state) || "opaque".equals(state)) {
            return typed.get("value") == null ? JsonNull.INSTANCE : typed.get("value");
        }
        if ("locator".equals(state)) {
            return typed.get("locator") == null ? JsonNull.INSTANCE : typed.get("locator");
        }
        return JsonNull.INSTANCE;
    }

    private static boolean isResourceType(JsonObject type) {
        return type != null && "resource".equalsIgnoreCase(text(type, "kind"));
    }

    private static boolean resourceDefaultType(JsonObject type, JsonObject value) {
        JsonObject selected = type;
        if (type != null && "union".equalsIgnoreCase(text(type, "kind"))) {
            String variantId = text(value, "variantId");
            JsonArray variants = type.getAsJsonArray("variants");
            selected = null;
            if (variantId != null && variants != null) {
                for (JsonElement variantValue : variants) {
                    JsonObject variant = variantValue != null && variantValue.isJsonObject()
                        ? variantValue.getAsJsonObject() : null;
                    if (variant != null && variantId.equals(text(variant, "variantId"))) {
                        selected = object(variant, "type");
                        break;
                    }
                }
            }
        }
        return resourceLikeType(selected);
    }

    private static boolean resourceLikeType(JsonObject type) {
        if (isResourceType(type)) {
            return true;
        }
        return type != null && "optional".equalsIgnoreCase(text(type, "kind"))
            && resourceLikeType(object(type, "element"));
    }

    private static JsonObject canonicalLocator(JsonObject type, JsonElement value, ServerId serverId) {
        JsonObject resourceType = object(type, "resourceType");
        if (!isResourceType(type) || resourceType == null || value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("A resource pin requires a complete canonical locator.");
        }
        try {
            TypeReference expected = TypeReference.of(text(resourceType, "ownerId"), text(resourceType, "localId"));
            ServerResourceLocator locator = IdentityCodec.decodeLocator(CanonicalCodec.decodePermissive(value.toString()));
            if (serverId != null && !serverId.equals(locator.serverId())) {
                throw new IllegalArgumentException("The resource locator server does not match the Core graph server.");
            }
            if (!expected.ownerId().equals(locator.type().owner().canonicalText())
                || !expected.localId().equals(locator.type().id().canonicalText())) {
                throw new IllegalArgumentException("The resource locator type does not match the pin type.");
            }
            return GSON.toJsonTree(locator.canonicalValue()).getAsJsonObject();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("A resource pin requires a complete canonical locator.", exception);
        }
    }

    private static JsonElement jsonObject(JsonElement value) {
        return value == null ? JsonNull.INSTANCE : value;
    }
}
