package redxax.oxy.remotely.flow.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import restudio.rescreen.util.JsonTreeParser;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.flow.sync.NodeRegistryRequest;
import redxax.oxy.remotely.flow.sync.OptionCatalogSnapshot;

public final class FlowJson {
    public static JsonElement parse(String json) {
        return JsonTreeParser.parse(json);
    }

    public static String write(JsonElement value) {
        return JsonTreeParser.write(value);
    }

    public static JsonObject graph(FlowGraph value) {
        FlowGraph graph = value == null ? new FlowGraph() : value;
        JsonObject json = new JsonObject();
        put(json, "id", graph.getId()); put(json, "enabled", graph.isEnabled()); put(json, "version", graph.getVersion());
        JsonObject nodes = new JsonObject(); graph.getNodes().forEach((id, node) -> nodes.add(id, node(node))); json.add("nodes", nodes);
        JsonArray connections = new JsonArray(); graph.getConnections().forEach(connection -> connections.add(connection(connection))); json.add("connections", connections);
        JsonArray variables = new JsonArray(); graph.getLocalVariables().forEach(variable -> variables.add(variable(variable))); json.add("localVariables", variables);
        put(json, "function", graph.isFunction()); put(json, "functionOwner", graph.getFunctionOwner()); put(json, "functionNamespace", graph.getFunctionNamespace());
        put(json, "functionVersion", graph.getFunctionVersion()); put(json, "functionDescription", graph.getFunctionDescription());
        JsonArray inputs = new JsonArray(); graph.getFunctionInputs().forEach(parameter -> inputs.add(parameter(parameter))); json.add("functionInputs", inputs);
        JsonArray outputs = new JsonArray(); graph.getFunctionOutputs().forEach(parameter -> outputs.add(parameter(parameter))); json.add("functionOutputs", outputs);
        JsonArray passthroughs = new JsonArray(); graph.getEditorPassthroughs().forEach(value1 -> passthroughs.add(passthrough(value1))); json.add("editorPassthroughs", passthroughs);
        json.add("contentProperties", value(graph.getContentProperties())); put(json, "resourceType", graph.getResourceType());
        put(json, "resourceRevision", graph.getResourceRevision()); put(json, "resourceHash", graph.getResourceHash()); put(json, "resourceMutationId", graph.getResourceMutationId());
        graph.getOpaqueProperties().forEach((key, opaque) -> { if (!json.has(key) && opaque != null) json.add(key, opaque.deepCopy()); });
        return json;
    }

    public static FlowGraph graph(JsonObject json) {
        FlowGraph graph = new FlowGraph();
        if (json == null) return graph;
        graph.setId(string(json, "id", graph.getId())); graph.setEnabled(bool(json, "enabled", true)); graph.setVersion(integer(json, "version", graph.getVersion()));
        Map<String, FlowNode> nodes = new LinkedHashMap<>(); JsonObject encodedNodes = object(json, "nodes");
        if (encodedNodes != null) encodedNodes.entrySet().forEach(entry -> { if (entry.getValue().isJsonObject()) nodes.put(entry.getKey(), node(entry.getValue().getAsJsonObject())); });
        graph.setNodes(nodes);
        List<FlowConnection> connections = new ArrayList<>(); array(json, "connections").forEach(value -> { if (value.isJsonObject()) connections.add(connection(value.getAsJsonObject())); }); graph.setConnections(connections);
        List<FlowVariable> variables = new ArrayList<>(); array(json, "localVariables").forEach(value -> { if (value.isJsonObject()) variables.add(variable(value.getAsJsonObject())); }); graph.setLocalVariables(variables);
        graph.setFunction(bool(json, "function", false)); graph.setFunctionOwner(string(json, "functionOwner", "server")); graph.setFunctionNamespace(string(json, "functionNamespace", "local"));
        graph.setFunctionVersion(integer(json, "functionVersion", 1)); graph.setFunctionDescription(string(json, "functionDescription", ""));
        List<FlowGraph.FunctionParameter> inputs = new ArrayList<>(); array(json, "functionInputs").forEach(value -> { if (value.isJsonObject()) inputs.add(parameter(value.getAsJsonObject())); }); graph.setFunctionInputs(inputs);
        List<FlowGraph.FunctionParameter> outputs = new ArrayList<>(); array(json, "functionOutputs").forEach(value -> { if (value.isJsonObject()) outputs.add(parameter(value.getAsJsonObject())); }); graph.setFunctionOutputs(outputs);
        List<FlowGraph.EditorPassthrough> passthroughs = new ArrayList<>(); array(json, "editorPassthroughs").forEach(value -> { if (value.isJsonObject()) passthroughs.add(passthrough(value.getAsJsonObject())); }); graph.setEditorPassthroughs(passthroughs);
        graph.setContentProperties(map(json.get("contentProperties"))); graph.setResourceType(string(json, "resourceType", "")); graph.setResourceRevision(longValue(json, "resourceRevision", 0));
        graph.setResourceHash(string(json, "resourceHash", "")); graph.setResourceMutationId(string(json, "resourceMutationId", ""));
        Map<String, JsonElement> opaque = new LinkedHashMap<>();
        json.entrySet().forEach(entry -> { if (!FlowSerializer.graphProperties().contains(entry.getKey())) opaque.put(entry.getKey(), entry.getValue().deepCopy()); });
        graph.setOpaqueProperties(opaque);
        return graph;
    }

    public static JsonObject gui(GuiDefinition value) {
        GuiDefinition gui = value == null ? new GuiDefinition() : value; JsonObject json = new JsonObject();
        put(json, "id", gui.getId()); put(json, "enabled", gui.isEnabled()); put(json, "title", gui.getTitle()); put(json, "rows", gui.getRows()); put(json, "extendToPlayerInventory", gui.isExtendToPlayerInventory());
        JsonArray elements = new JsonArray(); if (gui.getElements() != null) gui.getElements().forEach(element -> elements.add(guiElement(element))); json.add("elements", elements); return json;
    }

    public static GuiDefinition gui(JsonObject json) {
        GuiDefinition gui = new GuiDefinition(); if (json == null) return gui;
        gui.setId(string(json, "id", null)); gui.setEnabled(bool(json, "enabled", true)); gui.setTitle(string(json, "title", null)); gui.setRows(integer(json, "rows", 0)); gui.setExtendToPlayerInventory(bool(json, "extendToPlayerInventory", false));
        List<GuiElement> elements = new ArrayList<>(); array(json, "elements").forEach(value -> { if (value.isJsonObject()) elements.add(guiElement(value.getAsJsonObject())); }); gui.setElements(elements); return gui;
    }

    public static JsonObject scoreboard(ScoreboardDefinition value) {
        ScoreboardDefinition scoreboard = value == null ? new ScoreboardDefinition() : value; JsonObject json = new JsonObject();
        put(json, "id", scoreboard.getId()); put(json, "enabled", scoreboard.isEnabled()); put(json, "title", scoreboard.getTitle()); put(json, "objectiveId", scoreboard.getObjectiveId()); put(json, "displaySlot", scoreboard.getDisplaySlot()); json.add("lines", strings(scoreboard.getLines())); return json;
    }

    public static ScoreboardDefinition scoreboard(JsonObject json) {
        ScoreboardDefinition value = new ScoreboardDefinition(); if (json == null) return value;
        value.setId(string(json, "id", null)); value.setEnabled(bool(json, "enabled", true)); value.setTitle(string(json, "title", null)); value.setObjectiveId(string(json, "objectiveId", null)); value.setDisplaySlot(string(json, "displaySlot", ScoreboardDefinition.SLOT_SIDEBAR)); value.setLines(stringList(json, "lines")); return value;
    }

    public static JsonObject tab(TabDefinition value) {
        TabDefinition tab = value == null ? new TabDefinition() : value; JsonObject json = new JsonObject(); put(json, "id", tab.getId()); put(json, "enabled", tab.isEnabled()); put(json, "header", tab.getHeader()); put(json, "entryFormat", tab.getEntryFormat()); put(json, "footer", tab.getFooter()); return json;
    }

    public static TabDefinition tab(JsonObject json) {
        TabDefinition value = new TabDefinition(); if (json == null) return value; value.setId(string(json, "id", null)); value.setEnabled(bool(json, "enabled", true)); value.setHeader(string(json, "header", null)); value.setEntryFormat(string(json, "entryFormat", "%player%")); value.setFooter(string(json, "footer", null)); return value;
    }

    public static JsonObject customContent(CustomContentDefinition value) {
        CustomContentDefinition content = value == null ? new CustomContentDefinition() : value; JsonObject json = new JsonObject();
        put(json, "id", content.getId()); put(json, "enabled", content.isEnabled()); put(json, "flowId", content.getFlowId()); put(json, "type", content.getType()); put(json, "displayName", content.getDisplayName());
        put(json, "provider", content.getProvider()); put(json, "externalId", content.getExternalId()); put(json, "material", content.getMaterial()); put(json, "customModelData", content.getCustomModelData()); put(json, "armorSlot", content.getArmorSlot()); put(json, "version", content.getVersion());
        if (content.getGraph() != null) json.add("graph", graph(content.getGraph())); json.add("lore", strings(content.getLore())); json.add("tags", strings(content.getTags()));
        JsonArray abilities = new JsonArray(); content.getAbilities().forEach(ability -> abilities.add(ability(ability))); json.add("abilities", abilities); json.add("components", value(content.getComponents())); return json;
    }

    public static CustomContentDefinition customContent(JsonObject json) {
        CustomContentDefinition value = new CustomContentDefinition(); if (json == null) return value;
        value.setId(string(json, "id", null)); value.setEnabled(bool(json, "enabled", true)); value.setFlowId(string(json, "flowId", null)); value.setType(string(json, "type", null)); value.setDisplayName(string(json, "displayName", null));
        value.setProvider(string(json, "provider", "vanilla")); value.setExternalId(string(json, "externalId", "")); value.setMaterial(string(json, "material", "STICK"));
        value.setCustomModelData(json.has("customModelData") && !json.get("customModelData").isJsonNull() ? json.get("customModelData").getAsInt() : null); value.setArmorSlot(string(json, "armorSlot", "")); value.setVersion(integer(json, "version", 1));
        JsonObject encodedGraph = object(json, "graph"); value.setGraph(encodedGraph == null ? null : graph(encodedGraph)); value.setLore(stringList(json, "lore")); value.setTags(stringList(json, "tags"));
        List<CustomAbilityBinding> abilities = new ArrayList<>(); array(json, "abilities").forEach(item -> { if (item.isJsonObject()) abilities.add(ability(item.getAsJsonObject())); }); value.setAbilities(abilities); value.setComponents(map(json.get("components"))); return value;
    }

    public static JsonObject projectMetadata(ReSyncProjectMetadata value) {
        ReSyncProjectMetadata metadata = value == null ? new ReSyncProjectMetadata() : value; JsonObject json = new JsonObject(); put(json, "serverId", metadata.getServerId());
        JsonArray folders = new JsonArray(); metadata.getFolders().forEach(folder -> { JsonObject item = new JsonObject(); put(item, "path", folder.getPath()); put(item, "parentPath", folder.getParentPath()); put(item, "name", folder.getName()); put(item, "sortOrder", folder.getSortOrder()); put(item, "collapsed", folder.isCollapsed()); folders.add(item); }); json.add("folders", folders);
        JsonArray resources = new JsonArray(); metadata.getResources().forEach(resource -> { JsonObject item = new JsonObject(); put(item, "type", resource.getType()); put(item, "id", resource.getId()); put(item, "displayName", resource.getDisplayName()); put(item, "path", resource.getPath()); put(item, "sortOrder", resource.getSortOrder()); resources.add(item); }); json.add("resources", resources);
        JsonArray bundles = new JsonArray(); metadata.getInstalledBundles().forEach(bundle -> { JsonObject item = new JsonObject(); put(item, "marketplaceSlug", bundle.getMarketplaceSlug()); put(item, "listingSlug", bundle.getListingSlug()); put(item, "title", bundle.getTitle()); put(item, "versionId", bundle.getVersionId()); put(item, "version", bundle.getVersion()); put(item, "rootPath", bundle.getRootPath()); put(item, "iconMediaId", bundle.getIconMediaId()); put(item, "enabled", bundle.isEnabled()); item.add("resourceKeys", strings(bundle.getResourceKeys())); bundles.add(item); }); json.add("installedBundles", bundles);
        JsonArray documents = new JsonArray(); metadata.getOpenDocuments().forEach(document -> { JsonObject item = new JsonObject(); put(item, "type", document.getType()); put(item, "id", document.getId()); put(item, "displayName", document.getDisplayName()); put(item, "active", document.isActive()); documents.add(item); }); json.add("openDocuments", documents); put(json, "selectedResourceKey", metadata.getSelectedResourceKey()); return json;
    }

    public static ReSyncProjectMetadata projectMetadata(JsonObject json) {
        ReSyncProjectMetadata metadata = new ReSyncProjectMetadata(string(json, "serverId", null));
        List<ReSyncProjectMetadata.FolderEntry> folders = new ArrayList<>(); array(json, "folders").forEach(value -> { if (!value.isJsonObject()) return; JsonObject item = value.getAsJsonObject(); var folder = new ReSyncProjectMetadata.FolderEntry(); folder.setPath(string(item, "path", "")); folder.setParentPath(string(item, "parentPath", "")); folder.setName(string(item, "name", "")); folder.setSortOrder(integer(item, "sortOrder", 0)); folder.setCollapsed(bool(item, "collapsed", false)); folders.add(folder); }); metadata.setFolders(folders);
        List<ReSyncProjectMetadata.ResourceEntry> resources = new ArrayList<>(); array(json, "resources").forEach(value -> { if (!value.isJsonObject()) return; JsonObject item = value.getAsJsonObject(); var resource = new ReSyncProjectMetadata.ResourceEntry(); resource.setType(string(item, "type", "")); resource.setId(string(item, "id", "")); resource.setDisplayName(string(item, "displayName", "")); resource.setPath(string(item, "path", "")); resource.setSortOrder(integer(item, "sortOrder", 0)); resources.add(resource); }); metadata.setResources(resources);
        List<ReSyncProjectMetadata.InstalledBundleEntry> bundles = new ArrayList<>(); array(json, "installedBundles").forEach(value -> { if (!value.isJsonObject()) return; JsonObject item = value.getAsJsonObject(); var bundle = new ReSyncProjectMetadata.InstalledBundleEntry(); bundle.setMarketplaceSlug(string(item, "marketplaceSlug", "")); bundle.setListingSlug(string(item, "listingSlug", "")); bundle.setTitle(string(item, "title", "")); bundle.setVersionId(string(item, "versionId", "")); bundle.setVersion(string(item, "version", "")); bundle.setRootPath(string(item, "rootPath", "")); bundle.setIconMediaId(string(item, "iconMediaId", "")); bundle.setEnabled(bool(item, "enabled", true)); bundle.setResourceKeys(stringList(item, "resourceKeys")); bundles.add(bundle); }); metadata.setInstalledBundles(bundles);
        List<ReSyncProjectMetadata.OpenDocumentEntry> documents = new ArrayList<>(); array(json, "openDocuments").forEach(value -> { if (!value.isJsonObject()) return; JsonObject item = value.getAsJsonObject(); var document = new ReSyncProjectMetadata.OpenDocumentEntry(); document.setType(string(item, "type", "")); document.setId(string(item, "id", "")); document.setDisplayName(string(item, "displayName", "")); document.setActive(bool(item, "active", false)); documents.add(document); }); metadata.setOpenDocuments(documents); metadata.setSelectedResourceKey(string(json, "selectedResourceKey", "")); return metadata;
    }

    public static JsonObject nodeRegistryRequest(NodeRegistryRequest value) {
        JsonObject json = new JsonObject(); put(json, "contractVersion", value.getContractVersion()); put(json, "registryChecksum", value.getRegistryChecksum()); json.add("pluginChecksums", FlowJson.value(value.getPluginChecksums())); return json;
    }

    public static OptionCatalogSnapshot optionCatalog(JsonObject json) {
        OptionCatalogSnapshot snapshot = new OptionCatalogSnapshot(); snapshot.setVersion(integer(json, "version", OptionCatalogSnapshot.CURRENT_VERSION)); snapshot.setServerId(string(json, "serverId", "")); snapshot.setOwnerId(string(json, "ownerId", "")); snapshot.setResourceTypeId(string(json, "resourceTypeId", "")); snapshot.setSourceId(string(json, "sourceId", "")); snapshot.setContextKey(string(json, "contextKey", "")); snapshot.setRevision(string(json, "revision", "")); snapshot.setSequence(longValue(json, "sequence", 0)); snapshot.setValues(stringList(json, "values"));
        List<OptionCatalogItem> items = new ArrayList<>(); array(json, "items").forEach(value -> { if (!value.isJsonObject()) return; JsonObject encoded = value.getAsJsonObject(); OptionCatalogItem item = new OptionCatalogItem(); String resourceServerId = string(encoded, "resourceServerId", ""); String resourceOwnerId = string(encoded, "resourceOwnerId", ""); String resourceTypeId = string(encoded, "resourceTypeId", ""); String resourceId = string(encoded, "resourceId", ""); if (!resourceServerId.isBlank() || !resourceOwnerId.isBlank() || !resourceTypeId.isBlank() || !resourceId.isBlank()) item.setResource(new ServerResourceLocator(ServerId.parseCanonicalText(resourceServerId), ContractRef.of(OwnerId.of(resourceOwnerId), ResourceTypeId.of(resourceTypeId)), resourceId)); else item.setValue(string(encoded, "value", "")); item.setLabel(string(encoded, "label", item.getValue())); item.setDescription(string(encoded, "description", "")); item.setIcon(string(encoded, "icon", "")); item.setGroup(string(encoded, "group", "")); item.setMetadata(map(encoded.get("metadata"))); items.add(item); }); snapshot.setItems(items); snapshot.setStatus(string(json, "status", "available")); snapshot.setDiagnostic(string(json, "diagnostic", "")); return snapshot;
    }

    public static JsonElement value(Object value) {
        if (value == null) return JsonNull.INSTANCE; if (value instanceof JsonElement json) return json.deepCopy(); if (value instanceof String text) return new JsonPrimitive(text); if (value instanceof Number number) return new JsonPrimitive(number); if (value instanceof Boolean bool) return new JsonPrimitive(bool);
        if (value instanceof Map<?, ?> map) { JsonObject json = new JsonObject(); map.forEach((key, item) -> json.add(text(key), value(item))); return json; }
        if (value instanceof Iterable<?> iterable) { JsonArray json = new JsonArray(); iterable.forEach(item -> json.add(value(item))); return json; }
        if (value instanceof FlowGraph graph) return graph(graph); if (value instanceof GuiDefinition gui) return gui(gui); if (value instanceof ScoreboardDefinition scoreboard) return scoreboard(scoreboard); if (value instanceof TabDefinition tab) return tab(tab); if (value instanceof CustomContentDefinition content) return customContent(content);
        if (value instanceof FlowResourceReference reference) return new JsonPrimitive(reference.getId() != null ? reference.getId() : "");
        if (value instanceof FlowTypeRef typeRef) return new JsonPrimitive(typeRef.toString());
        if (value instanceof Enum<?> enumValue) return new JsonPrimitive(enumValue.name());
        return new JsonPrimitive("");
    }

    public static Object value(JsonElement value) {
        if (value == null || value.isJsonNull()) return null; if (value.isJsonObject()) return map(value); if (value.isJsonArray()) { List<Object> values = new ArrayList<>(); value.getAsJsonArray().forEach(item -> values.add(value(item))); return values; }
        JsonPrimitive primitive = value.getAsJsonPrimitive(); if (primitive.isBoolean()) return primitive.getAsBoolean(); if (primitive.isNumber()) return primitive.getAsDouble(); return primitive.getAsString();
    }

    public static String string(JsonObject json, String key, String fallback) { try { JsonElement value = json == null ? null : json.get(key); return value != null && !value.isJsonNull() ? value.getAsString() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    public static int integer(JsonObject json, String key, int fallback) { try { JsonElement value = json == null ? null : json.get(key); return value != null && !value.isJsonNull() ? value.getAsInt() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    public static long longValue(JsonObject json, String key, long fallback) { try { JsonElement value = json == null ? null : json.get(key); return value != null && !value.isJsonNull() ? value.getAsLong() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    public static double decimal(JsonObject json, String key, double fallback) { try { JsonElement value = json == null ? null : json.get(key); return value != null && !value.isJsonNull() ? value.getAsDouble() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    public static boolean bool(JsonObject json, String key, boolean fallback) { try { JsonElement value = json == null ? null : json.get(key); return value != null && !value.isJsonNull() ? value.getAsBoolean() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    public static JsonObject object(JsonObject json, String key) { JsonElement value = json == null ? null : json.get(key); return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
    public static JsonArray array(JsonObject json, String key) { JsonElement value = json == null ? null : json.get(key); return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray(); }

    public static String text(Object value) {
        if (value == null) return "";
        if (value instanceof JsonElement json) return write(json);
        if (value instanceof String text) return text;
        if (value instanceof Number number) {
            if (number instanceof Byte || number instanceof Short || number instanceof Integer) return Integer.toString(number.intValue());
            if (number instanceof Long) return Long.toString(number.longValue());
            if (number instanceof Float) return Float.toString(number.floatValue());
            return Double.toString(number.doubleValue());
        }
        if (value instanceof Boolean bool) return bool ? "true" : "false";
        if (value instanceof Character character) return Character.toString(character);
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) return write(value(value));
        if (value instanceof FlowResourceReference reference) return reference.getId() != null ? reference.getId() : "";
        if (value instanceof FlowTypeRef typeRef) return typeRef.toString();
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        return "";
    }

    private static JsonObject node(FlowNode value) { JsonObject json = new JsonObject(); put(json, "type", value.getType()); put(json, "version", value.getVersion()); put(json, "x", value.getX()); put(json, "y", value.getY()); json.add("inputValues", FlowJson.value(value.getInputValues())); return json; }
    private static FlowNode node(JsonObject json) { FlowNode value = new FlowNode(); value.setType(string(json, "type", null)); value.setVersion(integer(json, "version", FlowNode.CURRENT_VERSION)); value.setX(decimal(json, "x", 0)); value.setY(decimal(json, "y", 0)); value.setInputValues(map(json.get("inputValues"))); return value; }
    private static JsonObject connection(FlowConnection value) { JsonObject json = new JsonObject(); put(json, "sourceNodeId", value.getSourceNodeId()); put(json, "sourcePin", value.getSourcePin()); put(json, "targetNodeId", value.getTargetNodeId()); put(json, "targetPin", value.getTargetPin()); put(json, "editorSourceNodeId", value.getEditorSourceNodeId()); put(json, "editorSourcePin", value.getEditorSourcePin()); return json; }
    private static FlowConnection connection(JsonObject json) { FlowConnection value = new FlowConnection(string(json, "sourceNodeId", null), string(json, "sourcePin", null), string(json, "targetNodeId", null), string(json, "targetPin", null)); value.setEditorSourceNodeId(string(json, "editorSourceNodeId", null)); value.setEditorSourcePin(string(json, "editorSourcePin", null)); return value; }
    private static JsonObject variable(FlowVariable value) { JsonObject json = new JsonObject(); put(json, "name", value.getName()); put(json, "type", value.getType()); json.add("initialValue", FlowJson.value(value.getInitialValue())); put(json, "scope", value.getScope()); put(json, "lifetime", value.getLifetime()); put(json, "owner", value.getOwner()); put(json, "absencePolicy", value.getAbsencePolicy()); put(json, "concurrencyPolicy", value.getConcurrencyPolicy()); return json; }
    private static FlowVariable variable(JsonObject json) { FlowVariable value = new FlowVariable(string(json, "name", null), string(json, "type", null), FlowJson.value(json.get("initialValue"))); value.setScope(string(json, "scope", "local")); value.setLifetime(string(json, "lifetime", "execution")); value.setOwner(string(json, "owner", "graph")); value.setAbsencePolicy(string(json, "absencePolicy", "use_default")); value.setConcurrencyPolicy(string(json, "concurrencyPolicy", "isolated")); return value; }
    private static JsonObject parameter(FlowGraph.FunctionParameter value) { JsonObject json = new JsonObject(); put(json, "name", value.getName()); put(json, "type", value.getType() == null ? null : value.getType().getId()); json.add("typeRef", typeRef(value.getTypeRef())); put(json, "widget", value.getWidget()); put(json, "optionsSource", value.getOptionsSource()); put(json, "defaultValue", value.getDefaultValue()); return json; }
    private static FlowGraph.FunctionParameter parameter(JsonObject json) { FlowGraph.FunctionParameter value = new FlowGraph.FunctionParameter(); value.setName(string(json, "name", "")); value.setType(FlowDataType.fromString(string(json, "type", "any"))); JsonObject ref = object(json, "typeRef"); if (ref != null) value.setTypeRef(typeRef(ref)); value.setWidget(string(json, "widget", "")); value.setOptionsSource(string(json, "optionsSource", "")); value.setDefaultValue(string(json, "defaultValue", "")); return value; }
    private static JsonObject typeRef(FlowTypeRef value) { FlowTypeRef ref = value == null ? FlowTypeRef.simple("any") : value; JsonObject json = new JsonObject(); put(json, "typeId", ref.getTypeId()); JsonArray arguments = new JsonArray(); ref.getArguments().forEach(argument -> arguments.add(typeRef(argument))); json.add("arguments", arguments); return json; }
    private static FlowTypeRef typeRef(JsonObject json) { List<FlowTypeRef> arguments = new ArrayList<>(); array(json, "arguments").forEach(value -> { if (value.isJsonObject()) arguments.add(typeRef(value.getAsJsonObject())); }); return new FlowTypeRef(string(json, "typeId", "any"), arguments); }
    private static JsonObject passthrough(FlowGraph.EditorPassthrough value) { JsonObject json = new JsonObject(); put(json, "nodeId", value.getNodeId()); put(json, "inputPin", value.getInputPin()); return json; }
    private static FlowGraph.EditorPassthrough passthrough(JsonObject json) { return new FlowGraph.EditorPassthrough(string(json, "nodeId", ""), string(json, "inputPin", "")); }

    private static JsonObject guiElement(GuiElement value) { JsonObject json = new JsonObject(); JsonArray slots = new JsonArray(); value.getSlots().forEach(slots::add); json.add("slots", slots); json.add("visual", visual(value.getVisual())); put(json, "flowId", value.getFlowId()); put(json, "openGuiId", value.getOpenGuiId()); put(json, "command", value.getCommand()); if (value.getAction() != null) json.add("action", value.getAction().deepCopy()); return json; }
    private static GuiElement guiElement(JsonObject json) { GuiElement value = new GuiElement(); List<Integer> slots = new ArrayList<>(); array(json, "slots").forEach(slot -> slots.add(slot.getAsInt())); value.setSlots(slots); JsonObject visual = object(json, "visual"); value.setVisual(visual == null ? new Visual() : visual(visual)); value.setFlowId(string(json, "flowId", null)); value.setOpenGuiId(string(json, "openGuiId", null)); value.setCommand(string(json, "command", null)); JsonElement action = json.get("action"); value.setAction(action != null && action.isJsonObject() ? action.getAsJsonObject().deepCopy() : null); return value; }
    private static JsonObject visual(Visual value) { Visual visual = value == null ? new Visual() : value; JsonObject json = new JsonObject(); put(json, "material", visual.getMaterial()); put(json, "modelData", visual.getModelData()); put(json, "presetReference", visual.getPresetReference()); json.add("lore", strings(visual.getLore())); put(json, "name", visual.getName()); return json; }
    private static Visual visual(JsonObject json) { Visual value = new Visual(); value.setMaterial(string(json, "material", null)); value.setModelData(json.has("modelData") && !json.get("modelData").isJsonNull() ? json.get("modelData").getAsInt() : null); value.setPresetReference(string(json, "presetReference", null)); value.setLore(stringList(json, "lore")); value.setName(string(json, "name", null)); return value; }

    private static JsonObject ability(CustomAbilityBinding value) { JsonObject json = new JsonObject(); put(json, "id", value.getId()); put(json, "trigger", value.getTrigger()); put(json, "flowId", value.getFlowId()); put(json, "enabled", value.isEnabled()); json.add("rule", rule(value.getRule())); return json; }
    private static CustomAbilityBinding ability(JsonObject json) { CustomAbilityBinding value = new CustomAbilityBinding(string(json, "id", null), string(json, "trigger", null), string(json, "flowId", null)); value.setEnabled(bool(json, "enabled", true)); JsonObject rule = object(json, "rule"); value.setRule(rule == null ? new CustomTriggerRule() : rule(rule)); return value; }
    private static JsonObject rule(CustomTriggerRule value) { CustomTriggerRule rule = value == null ? new CustomTriggerRule() : value; JsonObject json = new JsonObject(); put(json, "enabled", rule.isEnabled()); put(json, "priority", rule.getPriority()); put(json, "cooldownScope", rule.getCooldownScope()); put(json, "cooldownTicks", rule.getCooldownTicks()); put(json, "permission", rule.getPermission()); put(json, "cancelEvent", rule.isCancelEvent()); put(json, "consumeEvent", rule.isConsumeEvent()); put(json, "requireSneaking", rule.isRequireSneaking()); put(json, "requireOnGround", rule.isRequireOnGround()); put(json, "handFilter", rule.getHandFilter()); put(json, "targetFilter", rule.getTargetFilter()); json.add("allowedWorlds", strings(rule.getAllowedWorlds())); json.add("deniedWorlds", strings(rule.getDeniedWorlds())); put(json, "chancePercent", rule.getChancePercent()); put(json, "maxActivationsPerTick", rule.getMaxActivationsPerTick()); return json; }
    private static CustomTriggerRule rule(JsonObject json) { CustomTriggerRule value = new CustomTriggerRule(); value.setEnabled(bool(json, "enabled", true)); value.setPriority(integer(json, "priority", 0)); value.setCooldownScope(string(json, "cooldownScope", "player")); value.setCooldownTicks(integer(json, "cooldownTicks", 0)); value.setPermission(string(json, "permission", "")); value.setCancelEvent(bool(json, "cancelEvent", false)); value.setConsumeEvent(bool(json, "consumeEvent", false)); value.setRequireSneaking(bool(json, "requireSneaking", false)); value.setRequireOnGround(bool(json, "requireOnGround", false)); value.setHandFilter(string(json, "handFilter", "any")); value.setTargetFilter(string(json, "targetFilter", "any")); value.setAllowedWorlds(stringList(json, "allowedWorlds")); value.setDeniedWorlds(stringList(json, "deniedWorlds")); value.setChancePercent(decimal(json, "chancePercent", 100)); value.setMaxActivationsPerTick(integer(json, "maxActivationsPerTick", 0)); return value; }

    private static Map<String, Object> map(JsonElement value) { Map<String, Object> map = new LinkedHashMap<>(); if (value != null && value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry -> map.put(entry.getKey(), FlowJson.value(entry.getValue()))); return map; }
    private static JsonArray strings(List<String> values) { JsonArray array = new JsonArray(); if (values != null) values.forEach(array::add); return array; }
    public static List<String> stringList(JsonObject json, String key) { List<String> values = new ArrayList<>(); array(json, key).forEach(value -> { if (!value.isJsonNull()) values.add(value.getAsString()); }); return values; }
    private static void put(JsonObject json, String key, String value) { if (value != null) json.addProperty(key, value); }
    private static void put(JsonObject json, String key, Number value) { if (value != null) json.addProperty(key, value); }
    private static void put(JsonObject json, String key, Boolean value) { if (value != null) json.addProperty(key, value); }

    private FlowJson() {
    }
}
