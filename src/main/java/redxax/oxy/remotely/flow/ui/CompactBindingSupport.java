package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.CoreGraphUiProjection;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import restudio.rescreen.ui.widgets.CompactBindingWidget;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

final class CompactBindingSupport {
    static final List<String> ACTION_MODES = List.of("None", "Run Flow", "Run Function", "Run Command");
    static final List<String> PREDICATE_MODES = List.of("None", "Function");
    private static final long SELECTED_FUNCTION_CACHE_NANOS = ((250L) * 1_000_000L);
    private static final Map<SelectedFunctionKey, SelectedFunction> SELECTED_FUNCTIONS = BrowserSafeState.map();

    private CompactBindingSupport() {
    }

    static List<String> flowOptions(String serverId) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null) {
            return List.of("none");
        }
        List<String> options = new ArrayList<>();
        options.add("none");
        manager.getCachedGraphIdsForServer(serverId, ReSyncResourceType.FLOW).stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .forEach(options::add);
        return options;
    }

    static List<String> functionOptions(String serverId) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null) {
            return List.of("none");
        }
        List<String> options = new ArrayList<>();
        options.add("none");
        manager.getCachedGraphIdsForServer(serverId, ReSyncResourceType.FUNCTION).stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .forEach(options::add);
        return options;
    }

    static FlowGraph selectedFunction(String serverId, String functionId, FunctionShape shape) {
        if (functionId == null || functionId.isBlank() || "none".equalsIgnoreCase(functionId)) {
            return null;
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null) {
            return null;
        }
        SelectedFunctionKey key = new SelectedFunctionKey(serverId, functionId);
        long now = System.nanoTime();
        SelectedFunction cached = SELECTED_FUNCTIONS.get(key);
        if (cached != null && now - cached.loadedAtNanos() < SELECTED_FUNCTION_CACHE_NANOS) {
            return cached.function().isFunction() ? cached.function() : null;
        }
        FlowGraph function = manager.getGraph(serverId, ReSyncResourceType.FUNCTION, functionId);
        if (function == null) {
            SELECTED_FUNCTIONS.remove(key);
            return null;
        }
        SELECTED_FUNCTIONS.put(key, new SelectedFunction(function, now));
        return function.isFunction() ? function : null;
    }

    static FlowGraph normalizeFunction(String serverId, FlowGraph function, FunctionShape shape) {
        return normalizeFunction(serverId, function, shape, boundaryCatalog(serverId));
    }

    static boolean initializeFunction(String serverId, String functionId, FunctionShape shape) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null || serverId.isBlank() || functionId == null || functionId.isBlank()
            || shape == null) {
            return false;
        }
        SELECTED_FUNCTIONS.remove(new SelectedFunctionKey(serverId, functionId));
        CoreGraphEditorSession session = manager.coreGraphEditorSession(serverId, ReSyncResourceType.FUNCTION,
            functionId).orElse(null);
        if (session != null && session.isFunction()) {
            FunctionSignature signature = session.functionSourceDocument().signature();
            session.setFunctionSignature(new FunctionSignature(signature.function(), signature.revision(),
                coreParameters(functionId, "input", shape.inputs()),
                coreParameters(functionId, "output", shape.outputs()), signature.unknown()));
            if (!ensureCoreBoundaryConnection(session, boundaryCatalog(serverId), functionId)) {
                new Notification("Function", "Function Boundary Connection Unavailable", Notification.Type.ERROR);
                return false;
            }
            if (!manager.saveCoreGraph(serverId, ReSyncResourceType.FUNCTION, session)) {
                new Notification("Function", "Function Signature Save Failed", Notification.Type.ERROR);
                return false;
            }
            return true;
        }
        FlowGraph function = manager.getGraph(serverId, ReSyncResourceType.FUNCTION, functionId);
        if (function == null) {
            return false;
        }
        normalizeFunction(serverId, function, shape);
        manager.saveGraph(serverId, ReSyncResourceType.FUNCTION, function);
        return true;
    }

    static boolean ensureCoreBoundaryConnection(CoreGraphEditorSession session,
                                                FlowNodeWidget.FunctionBoundaryCatalog catalog,
                                                String functionId) {
        if (session == null || catalog == null || !catalog.isComplete() || functionId == null || functionId.isBlank()) {
            return false;
        }
        FlowNodeWidget.FunctionBoundaryIntent inputIntent = catalog.intent(FlowNodeWidget.FunctionBoundaryRole.INPUTS);
        FlowNodeWidget.FunctionBoundaryIntent outputIntent = catalog.intent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS);
        if (inputIntent == null || outputIntent == null || inputIntent.nodeIdentity() == null
            || outputIntent.nodeIdentity() == null || inputIntent.flowPin().isBlank() || outputIntent.flowPin().isBlank()) {
            return false;
        }
        GraphDocument document = session.functionSourceDocument().graph();
        GraphNode input = document.nodes().stream()
            .filter(node -> inputIntent.nodeIdentity().equals(node.definition()))
            .findFirst().orElse(null);
        GraphNode output = document.nodes().stream()
            .filter(node -> outputIntent.nodeIdentity().equals(node.definition()))
            .findFirst().orElse(null);
        if (input == null || output == null) {
            return false;
        }
        PinId inputPin = PinId.of(inputIntent.flowPin());
        PinId outputPin = PinId.of(outputIntent.flowPin());
        boolean inputConnected = document.connections().stream()
            .anyMatch(connection -> connection.source().nodeId().equals(input.instanceId())
                && connection.source().pinId().equals(inputPin));
        boolean outputConnected = document.connections().stream()
            .anyMatch(connection -> connection.target().nodeId().equals(output.instanceId())
                && connection.target().pinId().equals(outputPin));
        if (inputConnected || outputConnected) {
            return true;
        }
        session.addConnection(new GraphConnection(ConnectionId.deterministic(functionId + "\0boundary-flow"),
            new GraphEndpoint(input.instanceId(), inputPin), new GraphEndpoint(output.instanceId(), outputPin)));
        return true;
    }

    static FunctionParameterContract coreParameter(String name, FlowTypeRef type, String widget,
                                                   String optionsSource, String defaultValue) {
        return coreParameter(FunctionParameterId.interactive(), name, type, widget, optionsSource, defaultValue);
    }

    private static List<FunctionParameterContract> coreParameters(String functionId, String direction,
                                                                   List<FlowGraph.FunctionParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        List<FunctionParameterContract> result = new ArrayList<>(parameters.size());
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            FunctionParameterId id = FunctionParameterId.deterministic(functionId + '\0' + direction + '\0'
                + parameter.getName());
            result.add(coreParameter(id, parameter.getName(), parameter.getTypeRef(), parameter.getWidget(),
                parameter.getOptionsSource(), parameter.getDefaultValue()));
        }
        return List.copyOf(result);
    }

    private static FunctionParameterContract coreParameter(FunctionParameterId id, String name, FlowTypeRef type,
                                                            String widget, String optionsSource, String defaultValue) {
        FlowTypeRef exactType = type != null && type.isResolved() ? type : FlowTypeRef.simple("any");
        TypeExpr expression = CoreGraphUiProjection.descriptorType(exactType);
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("name", name);
        if (widget != null && !widget.isBlank()) {
            unknown.put("widget", widget);
        }
        if (optionsSource != null && !optionsSource.isBlank()) {
            unknown.put("optionsSource", optionsSource);
        }
        TypedValue value = coreDefault(expression, exactType, defaultValue);
        return new FunctionParameterContract(id, expression, value == null, value, unknown);
    }

    private static TypedValue coreDefault(TypeExpr type, FlowTypeRef typeRef, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String typeId = typeRef.getTypeId().toLowerCase(java.util.Locale.ROOT);
        Object material = switch (typeId) {
            case "string", "text" -> value;
            case "boolean", "bool" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("Boolean defaults must be true or false");
                }
                yield Boolean.parseBoolean(value);
            }
            case "integer" -> new BigInteger(value);
            case "number", "float", "double" -> new BigDecimal(value);
            default -> throw new IllegalArgumentException("Defaults are unavailable for " + typeRef);
        };
        return TypedValue.value(type, material);
    }

    static FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog(String serverId) {
        return FlowNodeWidget.boundaryCatalogForServer(serverId);
    }

    static FlowGraph normalizeFunction(String serverId, FlowGraph function, FunctionShape shape,
                                       FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog) {
        if (function == null) {
            return null;
        }
        FlowNodeWidget.FunctionBoundaryCatalog catalog = boundaryCatalog != null
            ? boundaryCatalog : FlowNodeWidget.FunctionBoundaryCatalog.unavailable();
        if (!catalog.isTypedProjectionAvailable()
            || catalog.hasAmbiguousNode(function, FlowNodeWidget.FunctionBoundaryRole.INPUTS)
            || catalog.hasAmbiguousNode(function, FlowNodeWidget.FunctionBoundaryRole.OUTPUTS)) {
            return function;
        }
        Map<String, FlowNode> existingNodes = function.getNodes();
        List<FlowConnection> existingConnections = function.getConnections();
        String startId = catalog.nodeId(function, FlowNodeWidget.FunctionBoundaryRole.INPUTS);
        String endId = catalog.nodeId(function, FlowNodeWidget.FunctionBoundaryRole.OUTPUTS);
        if (startId != null && startId.equals(endId)) {
            return function;
        }
        List<BoundaryConnectionMigration> migrations = migrateBoundaryConnections(function, catalog, startId, endId);
        if (migrations == null) {
            return function;
        }
        Map<String, FlowNode> nodes = existingNodes != null ? existingNodes : new HashMap<>();
        List<FlowConnection> connections = existingConnections != null ? existingConnections : new ArrayList<>();
        boolean changed = !function.isFunction() || existingNodes == null || existingConnections == null
            || function.getLocalVariables() == null || function.getFunctionInputs() == null || function.getFunctionOutputs() == null;
        if (startId == null) {
            startId = uniqueNodeId(nodes);
            nodes.put(startId, new FlowNode(catalog.nodeReference(FlowNodeWidget.FunctionBoundaryRole.INPUTS), 120, 120, new HashMap<>()));
            changed = true;
        }
        if (endId == null) {
            endId = uniqueNodeId(nodes);
            nodes.put(endId, new FlowNode(catalog.nodeReference(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS), 380, 120, new HashMap<>()));
            changed = true;
        }
        if (!migrations.isEmpty()) {
            for (BoundaryConnectionMigration migration : migrations) {
                migration.apply();
            }
            changed = true;
        }
        FlowNodeWidget.FunctionBoundaryIntent inputs = catalog.intent(FlowNodeWidget.FunctionBoundaryRole.INPUTS);
        FlowNodeWidget.FunctionBoundaryIntent outputs = catalog.intent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS);
        if (!hasBoundaryPath(nodes, connections, startId, endId)) {
            connections.add(new FlowConnection(startId, inputs.flowPin(), endId, outputs.flowPin()));
            changed = true;
        }
        if (!function.isFunction()) {
            function.setFunction(true);
        }
        if (existingNodes == null) {
            function.setNodes(nodes);
        }
        if (existingConnections == null) {
            function.setConnections(connections);
        }
        if (function.getLocalVariables() == null) {
            function.setLocalVariables(new ArrayList<>());
        }
        if (function.getFunctionInputs() == null) {
            function.setFunctionInputs(new ArrayList<>());
        }
        if (function.getFunctionOutputs() == null) {
            function.setFunctionOutputs(new ArrayList<>());
        }
        migrateBoundaryNodeType(nodes, startId, catalog.intent(FlowNodeWidget.FunctionBoundaryRole.INPUTS));
        migrateBoundaryNodeType(nodes, endId, catalog.intent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS));
        if (shape != null) {
            changed |= applyParameters(function.getFunctionInputs(), shape.inputs());
            changed |= applyParameters(function.getFunctionOutputs(), shape.outputs());
        }
        return function;
    }

    private static List<BoundaryConnectionMigration> migrateBoundaryConnections(FlowGraph function,
                                                                                  FlowNodeWidget.FunctionBoundaryCatalog catalog,
                                                                                  String startId, String endId) {
        List<FlowConnection> connections = function.getConnections();
        if (connections == null || connections.isEmpty() || startId == null && endId == null) {
            return List.of();
        }
        List<BoundaryConnectionMigration> migrations = new ArrayList<>();
        for (FlowConnection connection : connections) {
            if (connection == null) {
                return null;
            }
            if (startId != null && startId.equals(connection.getSourceNodeId())) {
                String normalized = catalog.normalizeConnectionPin(function, FlowNodeWidget.FunctionBoundaryRole.INPUTS, connection.getSourcePin());
                if (normalized == null) {
                    return null;
                }
                if (!normalized.equals(connection.getSourcePin())) {
                    migrations.add(new BoundaryConnectionMigration(connection, normalized, true));
                }
            }
            if (endId != null && endId.equals(connection.getTargetNodeId())) {
                String normalized = catalog.normalizeConnectionPin(function, FlowNodeWidget.FunctionBoundaryRole.OUTPUTS, connection.getTargetPin());
                if (normalized == null) {
                    return null;
                }
                if (!normalized.equals(connection.getTargetPin())) {
                    migrations.add(new BoundaryConnectionMigration(connection, normalized, false));
                }
            }
            if (endId != null && endId.equals(connection.getSourceNodeId()) || startId != null && startId.equals(connection.getTargetNodeId())) {
                return null;
            }
        }
        return migrations;
    }

    private static boolean hasBoundaryPath(Map<String, FlowNode> nodes, List<FlowConnection> connections,
                                           String startId, String endId) {
        if (nodes == null || connections == null || startId == null || endId == null
            || nodes.get(startId) == null || nodes.get(endId) == null) {
            return false;
        }
        Set<String> reachable = new HashSet<>();
        List<String> pending = new ArrayList<>();
        reachable.add(startId);
        pending.add(startId);
        for (int index = 0; index < pending.size(); index++) {
            String currentId = pending.get(index);
            if (endId.equals(currentId)) {
                return true;
            }
            for (FlowConnection connection : connections) {
                if (!isUsablePathConnection(nodes, connection) || !currentId.equals(connection.getSourceNodeId())) {
                    continue;
                }
                String targetId = connection.getTargetNodeId();
                if (reachable.add(targetId)) {
                    pending.add(targetId);
                }
            }
        }
        return false;
    }

    private static boolean isUsablePathConnection(Map<String, FlowNode> nodes, FlowConnection connection) {
        if (connection == null || connection.getSourceNodeId() == null || connection.getSourceNodeId().isBlank()
            || connection.getSourcePin() == null || connection.getSourcePin().isBlank()
            || connection.getTargetNodeId() == null || connection.getTargetNodeId().isBlank()
            || connection.getTargetPin() == null || connection.getTargetPin().isBlank()) {
            return false;
        }
        return nodes.get(connection.getSourceNodeId()) != null && nodes.get(connection.getTargetNodeId()) != null;
    }

    private static void migrateBoundaryNodeType(Map<String, FlowNode> nodes, String nodeId,
                                                FlowNodeWidget.FunctionBoundaryIntent intent) {
        if (nodes == null || nodeId == null || intent == null || intent.nodeReference() == null) {
            return;
        }
        FlowNode node = nodes.get(nodeId);
        if (node != null && !intent.nodeReference().equals(node.getType())) {
            node.setType(intent.nodeReference());
        }
    }

    private static String uniqueNodeId(Map<String, FlowNode> nodes) {
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (nodes.containsKey(id));
        return id;
    }

    private record BoundaryConnectionMigration(FlowConnection connection, String pin, boolean source) {
        private void apply() {
            if (source) {
                connection.setSourcePin(pin);
            } else {
                connection.setTargetPin(pin);
            }
        }
    }

    private record SelectedFunctionKey(String serverId, String functionId) {
    }

    private record SelectedFunction(FlowGraph function, long loadedAtNanos) {
    }

    static FunctionShape playerActionShape() {
        return new FunctionShape(List.of(new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER)), List.of());
    }

    static FunctionShape guiActionShape() {
        return new FunctionShape(List.of(
            new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER),
            new FlowGraph.FunctionParameter("item", FlowDataType.ITEM),
            new FlowGraph.FunctionParameter("slot", FlowDataType.NUMBER)
        ), List.of());
    }

    static FunctionShape recipeActionShape() {
        return new FunctionShape(List.of(
            new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER),
            new FlowGraph.FunctionParameter("item", FlowDataType.ITEM),
            new FlowGraph.FunctionParameter("source", FlowDataType.ITEM),
            new FlowGraph.FunctionParameter("recipe", FlowDataType.STRING)
        ), List.of());
    }

    static FunctionShape npcActionShape() {
        return new FunctionShape(List.of(
            new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER),
            new FlowGraph.FunctionParameter("entity", FlowDataType.ENTITY),
            new FlowGraph.FunctionParameter("location", FlowDataType.LOCATION),
            new FlowGraph.FunctionParameter("npc", FlowDataType.STRING),
            new FlowGraph.FunctionParameter("rightClick", FlowDataType.BOOLEAN),
            new FlowGraph.FunctionParameter("leftClick", FlowDataType.BOOLEAN),
            new FlowGraph.FunctionParameter("shifting", FlowDataType.BOOLEAN)
        ), List.of());
    }

    static FunctionShape tradeActionShape() {
        return new FunctionShape(List.of(
            new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER),
            new FlowGraph.FunctionParameter("entity", FlowDataType.ENTITY),
            new FlowGraph.FunctionParameter("tradedItem", FlowDataType.ITEM),
            new FlowGraph.FunctionParameter("success", FlowDataType.BOOLEAN),
            new FlowGraph.FunctionParameter("profile", FlowDataType.STRING)
        ), List.of());
    }

    static FunctionShape playerPredicateShape() {
        return new FunctionShape(
            List.of(new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER)),
            List.of(new FlowGraph.FunctionParameter("result", FlowDataType.BOOLEAN, "toggle", "", "false"))
        );
    }

    static FunctionShape recipePredicateShape() {
        return new FunctionShape(
            List.of(
                new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER),
                new FlowGraph.FunctionParameter("item", FlowDataType.ITEM),
                new FlowGraph.FunctionParameter("source", FlowDataType.ITEM),
                new FlowGraph.FunctionParameter("recipe", FlowDataType.STRING)
            ),
            List.of(new FlowGraph.FunctionParameter("result", FlowDataType.BOOLEAN, "toggle", "", "false"))
        );
    }

    static List<String> functionInputOptions(FlowGraph.FunctionParameter input, String context) {
        if (input == null) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        addOption(options, functionInputContextDefault(input, context));
        FlowDataType type = input.getType();
        if (type != null && FlowDataType.PLAYER.isAssignableFrom(type)) {
            addOption(options, "$player");
            addOption(options, "$event.player");
            addOption(options, "$click.player");
            addOption(options, "$recipe.player");
        }
        if (type != null && (FlowDataType.ITEM.isAssignableFrom(type) || FlowDataType.MATERIAL.isAssignableFrom(type))) {
            addOption(options, "$item");
            addOption(options, "$clickedItem");
            addOption(options, "$craftedItem");
            addOption(options, "$cookedItem");
            addOption(options, "$sourceItem");
            addOption(options, "$tradedItem");
            addOption(options, "$resultItem");
            addOption(options, "$event.item");
            addOption(options, "$event.output");
            addOption(options, "$event.source");
        }
        if (type != null && FlowDataType.LOCATION.isAssignableFrom(type)) {
            addOption(options, "$location");
            addOption(options, "$event.location");
        }
        if (type != null && FlowDataType.ENTITY.isAssignableFrom(type)) {
            addOption(options, "$event.entity");
            addOption(options, "$event.target");
            addOption(options, "$player");
        }
        if (type != null && FlowDataType.STRING.isAssignableFrom(type)) {
            addOption(options, "$npcId");
            addOption(options, "$profileId");
            addOption(options, "$hook");
            addOption(options, "$recipe");
            addOption(options, "$world");
            addOption(options, "$permission");
            addOption(options, "$event.id");
        }
        if (type != null && FlowDataType.NUMBER.isAssignableFrom(type)) {
            addOption(options, "$slot");
            addOption(options, "$amount");
            addOption(options, "$event.slot");
        }
        if (type != null && FlowDataType.BOOLEAN.isAssignableFrom(type)) {
            addOption(options, "true");
            addOption(options, "false");
            addOption(options, "$success");
            addOption(options, "$rightClick");
            addOption(options, "$leftClick");
            addOption(options, "$shifting");
            addOption(options, "$sneaking");
            addOption(options, "$shiftClick");
        }
        return options;
    }

    static List<CompactBindingWidget.BindingChoice> functionInputChoices(String serverId, FlowGraph.FunctionParameter input, List<String> fallbackOptions) {
        List<CompactBindingWidget.BindingChoice> choices = new ArrayList<>();
        Set<String> values = new HashSet<>();
        String source = input != null ? input.getOptionsSource() : null;
        if (source != null && !source.isBlank()) {
            for (OptionCatalogItem item : OptionCatalogCache.getInstance().getItems(serverId, source)) {
                if (item == null || item.getValue() == null || item.getValue().isBlank() || !values.add(item.getValue())) {
                    continue;
                }
                Object aliases = item.getMetadata().get("aliases");
                String searchTerms = String.join(" ", item.getValue(), item.getLabel(), item.getDescription(), item.getGroup(),
                    aliases != null ? aliases.toString() : "");
                choices.add(new CompactBindingWidget.BindingChoice(item.getValue(), item.getLabel(), item.getDescription(), item.getIcon(),
                    item.getGroup(), searchTerms));
            }
        }
        if (fallbackOptions != null) {
            for (String value : fallbackOptions) {
                if (value == null || value.isBlank() || !values.add(value)) {
                    continue;
                }
                String group = value.startsWith("$") ? "Context" : "Values";
                choices.add(new CompactBindingWidget.BindingChoice(value, value, "", "", group, value));
            }
        }
        return choices;
    }

    static String functionInputContextDefault(FlowGraph.FunctionParameter input, String context) {
        if (input == null || input.getType() == null) {
            return "";
        }
        FlowDataType type = input.getType();
        String name = input.getName() != null ? input.getName().toLowerCase() : "";
        String scope = context != null ? context.toLowerCase() : "";
        if (FlowDataType.BOOLEAN.isAssignableFrom(type)) {
            if (name.contains("right")) {
                return "$rightClick";
            }
            if (name.contains("left")) {
                return "$leftClick";
            }
            if (name.contains("shift") || name.contains("sneak")) {
                return "$shifting";
            }
            if (scope.contains("trade") || name.contains("success")) {
                return "$success";
            }
            return "false";
        }
        if (FlowDataType.PLAYER.isAssignableFrom(type)) {
            return "$player";
        }
        if (FlowDataType.ITEM.isAssignableFrom(type) || FlowDataType.MATERIAL.isAssignableFrom(type)) {
            if (scope.contains("trade") || name.contains("trade")) {
                return name.contains("result") || name.contains("output") ? "$resultItem" : "$tradedItem";
            }
            if (scope.contains("gui") || name.contains("click")) {
                return "$clickedItem";
            }
            if (scope.contains("cook")) {
                return name.contains("source") || name.contains("input") || name.contains("ingredient") ? "$sourceItem" : "$cookedItem";
            }
            if (scope.contains("recipe") || scope.contains("craft")) {
                return name.contains("source") || name.contains("input") || name.contains("ingredient") ? "$sourceItem" : "$craftedItem";
            }
            return "$item";
        }
        if (FlowDataType.ENTITY.isAssignableFrom(type)) {
            return "$event.entity";
        }
        if (FlowDataType.LOCATION.isAssignableFrom(type)) {
            return "$location";
        }
        if (FlowDataType.NUMBER.isAssignableFrom(type)) {
            if (scope.contains("gui") || name.contains("slot")) {
                return "$slot";
            }
            if (name.contains("amount")) {
                return "$amount";
            }
        }
        if (FlowDataType.STRING.isAssignableFrom(type)) {
            if (scope.contains("npc") || name.contains("npc")) {
                return "$npcId";
            }
            if (scope.contains("trade") || name.contains("profile")) {
                return "$profileId";
            }
            if (scope.contains("recipe") || name.contains("recipe")) {
                return "$recipe";
            }
            if (name.contains("world")) {
                return "$world";
            }
        }
        return "";
    }

    private static void addOption(List<String> options, String option) {
        if (option != null && !option.isBlank() && !options.contains(option)) {
            options.add(option);
        }
    }

    private static boolean applyParameters(List<FlowGraph.FunctionParameter> target, List<FlowGraph.FunctionParameter> required) {
        if (target == null || required == null) {
            return false;
        }
        boolean changed = false;
        for (FlowGraph.FunctionParameter parameter : required) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            List<FlowGraph.FunctionParameter> matches = target.stream()
                .filter(value -> value != null && parameter.getName().equals(value.getName()))
                .toList();
            if (matches.size() > 1) {
                continue;
            }
            FlowGraph.FunctionParameter existing = matches.isEmpty() ? null : matches.getFirst();
            if (existing == null) {
                target.add(new FlowGraph.FunctionParameter(parameter.getName(), parameter.getType(), parameter.getWidget(), parameter.getOptionsSource(), parameter.getDefaultValue()));
                changed = true;
                continue;
            }
            if (parameter.getType() != null && !parameter.getType().equals(existing.getType())) {
                existing.setType(parameter.getType());
                changed = true;
            }
            if (parameter.getWidget() != null && !parameter.getWidget().isBlank() && !parameter.getWidget().equals(existing.getWidget())) {
                existing.setWidget(parameter.getWidget());
                changed = true;
            }
            if (parameter.getOptionsSource() != null && !parameter.getOptionsSource().isBlank() && !parameter.getOptionsSource().equals(existing.getOptionsSource())) {
                existing.setOptionsSource(parameter.getOptionsSource());
                changed = true;
            }
            if (parameter.getDefaultValue() != null && !parameter.getDefaultValue().isBlank() && !parameter.getDefaultValue().equals(existing.getDefaultValue())) {
                existing.setDefaultValue(parameter.getDefaultValue());
                changed = true;
            }
        }
        return changed;
    }

    record FunctionShape(List<FlowGraph.FunctionParameter> inputs, List<FlowGraph.FunctionParameter> outputs) {
    }
}
