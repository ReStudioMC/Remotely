package redxax.oxy.remotely.flow.data;

import restudio.resync.flow.contract.CustomContentContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CustomContentGraphAdapter {
    public static final String ITEM_NODE = CustomContentContract.ITEM_NODE;
    public static final String BLOCK_NODE = CustomContentContract.BLOCK_NODE;
    public static final String ARMOR_NODE = CustomContentContract.ARMOR_NODE;
    public static final String PROJECTILE_NODE = CustomContentContract.PROJECTILE_NODE;
    public static final String FLOW_BRANCHES_KEY = CustomContentContract.FLOW_BRANCHES_KEY;

    private CustomContentGraphAdapter() {
    }

    public static FlowGraph createContentGraph(String id, String type, String displayName) {
        FlowGraph graph = new FlowGraph();
        String normalizedType = normalizeType(type);
        graph.setId(contentFlowId(normalizedType, id));
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("content_id", id);
        inputs.put("name", displayName == null || displayName.isBlank() ? defaultName(normalizedType) : displayName);
        inputs.put("provider", "vanilla");
        inputs.put("external_id", "");
        inputs.put("material", defaultMaterial(normalizedType));
        inputs.put("custom_model_data", "");
        inputs.put("components", new LinkedHashMap<>());
        inputs.put("lore", "");
        inputs.put("tags", "");
        if ("armor".equals(normalizedType)) {
            graph.getContentProperties().put("armor_slot", "chest");
        }
        if ("projectile".equals(normalizedType)) {
            graph.getContentProperties().put("projectile.entity_type", "ARROW");
            graph.getContentProperties().put("projectile.launch_source", "Automatic");
            graph.getContentProperties().put("projectile.speed", 2.4);
            graph.getContentProperties().put("projectile.damage", 0.0);
            graph.getContentProperties().put("projectile.gravity", true);
            graph.getContentProperties().put("projectile.glowing", false);
            graph.getContentProperties().put("projectile.consume_item", true);
            graph.getContentProperties().put("projectile.pickup", "Allowed");
            graph.getContentProperties().put("projectile.fire_sound", "");
            graph.getContentProperties().put("projectile.hit_sound", "");
            graph.getContentProperties().put("projectile.sound_volume", 1.0);
            graph.getContentProperties().put("projectile.sound_pitch", 1.0);
            graph.getContentProperties().put("projectile.remove_on_hit", false);
        }
        inputs.put("enabled", true);
        inputs.put("priority", 0);
        inputs.put("cooldown_scope", "player");
        inputs.put("cooldown_ticks", 0);
        inputs.put("permission", "");
        inputs.put("cancel_event", false);
        inputs.put("consume_event", false);
        inputs.put("require_sneaking", false);
        inputs.put("require_on_ground", false);
        if (!"armor".equals(normalizedType)) {
            inputs.put("hand_filter", "any");
        }
        inputs.put("target_filter", "any");
        inputs.put("allowed_worlds", "");
        inputs.put("denied_worlds", "");
        inputs.put("chance_percent", 100.0);
        inputs.put("max_activations_per_tick", 0);
        inputs.put(FLOW_BRANCHES_KEY, List.of(defaultBranch(normalizedType)));
        graph.getNodes().put(UUID.randomUUID().toString(), new FlowNode(nodeType(normalizedType), 120, 120, inputs));
        return graph;
    }

    public static boolean isContentGraph(FlowGraph graph) {
        return contentType(graph) != null;
    }

    public static String contentType(FlowGraph graph) {
        FlowNode node = findStartNode(graph);
        return node != null ? typeFromNode(node.getType()) : null;
    }

    public static FlowNode findStartNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return null;
        }
        Object storedId = graph.getContentProperties().get("content_id");
        String contentId = storedId != null ? storedId.toString() : "";
        if (contentId.isBlank()) {
            String graphId = graph.getId();
            if (graphId != null) {
                for (String type : List.of("item", "armor", "block", "projectile")) {
                    String prefix = "content." + type + ".";
                    if (graphId.startsWith(prefix)) {
                        contentId = graphId.substring(prefix.length());
                        break;
                    }
                }
            }
        }
        FlowNode match = null;
        FlowNode fallback = null;
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            if (node == null || typeFromNode(node.getType()) == null) {
                continue;
            }
            if (fallback == null) {
                fallback = node;
            }
            if (node.getInputValues() != null
                && contentId.equals(String.valueOf(node.getInputValues().getOrDefault("content_id", "")))) {
                match = node;
                break;
            }
        }
        return match != null ? match : fallback;
    }

    public static CustomContentDefinition toDefinition(FlowGraph graph) {
        String type = contentType(graph);
        FlowNode node = findStartNode(graph);
        if (graph == null || node == null || type == null) {
            return null;
        }
        Map<String, Object> inputs = node.getInputValues() != null ? node.getInputValues() : Map.of();
        Object legacyArmorSlot = inputs.get("armor_slot");
        if (legacyArmorSlot != null && "armor".equals(type) && !graph.getContentProperties().containsKey("armor_slot")) {
            graph.getContentProperties().put("armor_slot", legacyArmorSlot);
        }
        if (node.getInputValues() != null) {
            node.getInputValues().remove("armor_slot");
        }
        String id = text(inputs.get("content_id"), graph.getId());
        if (id.isBlank()) {
            id = graph.getId();
        }
        CustomContentDefinition definition = new CustomContentDefinition();
        definition.setId(id);
        definition.setEnabled(graph.isEnabled());
        definition.setFlowId(graph.getId());
        definition.setGraph(graph);
        definition.setType(type);
        definition.setDisplayName(text(inputs.get("name"), defaultName(type)));
        definition.setProvider(text(inputs.get("provider"), "vanilla"));
        definition.setExternalId(text(inputs.get("external_id"), ""));
        definition.setMaterial(normalizeMaterial(text(inputs.get("material"), defaultMaterial(type))));
        definition.setCustomModelData(nullableInt(inputs.get("custom_model_data")));
        definition.setComponents(map(inputs.get("components")));
        definition.setLore(csv(inputs.get("lore")));
        definition.setTags(csv(inputs.get("tags")));
        definition.setArmorSlot(text(getContentConfiguration(graph, "armor_slot", null), "armor".equals(type) ? "chest" : ""));
        definition.setAbilities(abilities(graph, node, type, id, inputs));
        return definition;
    }

    public static FlowNode findOrCreateStartNode(FlowGraph graph, String type, String displayName) {
        FlowNode node = findStartNode(graph);
        if (node != null) {
            return node;
        }
        if (graph == null) {
            return null;
        }
        FlowGraph template = createContentGraph(graph.getId(), type, displayName);
        FlowNode templateNode = findStartNode(template);
        if (templateNode != null) {
            graph.getNodes().put(UUID.randomUUID().toString(), templateNode);
        }
        return templateNode;
    }

    public static Object getContentProperty(FlowGraph graph, String key, Object fallback) {
        FlowNode node = findStartNode(graph);
        if (node == null || node.getInputValues() == null) {
            return fallback;
        }
        return node.getInputValues().getOrDefault(key, fallback);
    }

    public static void setContentProperty(FlowGraph graph, String key, Object value) {
        FlowNode node = findOrCreateStartNode(graph, contentType(graph), graph != null ? graph.getId() : "");
        if (node != null) {
            node.getInputValues().put(key, value);
        }
    }

    public static Object getContentConfiguration(FlowGraph graph, String key, Object fallback) {
        if (graph == null || key == null) {
            return fallback;
        }
        return graph.getContentProperties().getOrDefault(key, fallback);
    }

    public static void setContentConfiguration(FlowGraph graph, String key, Object value) {
        if (graph != null && key != null) {
            graph.getContentProperties().put(key, value);
        }
    }

    public static void removeContentConfiguration(FlowGraph graph, String key) {
        if (graph != null && key != null) {
            graph.getContentProperties().remove(key);
        }
    }

    public static List<String> getEnabledTriggerBranches(FlowGraph graph) {
        FlowNode node = findStartNode(graph);
        String type = contentType(graph);
        if (node == null || type == null) {
            return List.of();
        }
        return selectedBranches(graph, node, type, node.getInputValues() != null ? node.getInputValues() : Map.of());
    }

    public static void setEnabledTriggerBranches(FlowGraph graph, List<String> branches) {
        setContentProperty(graph, FLOW_BRANCHES_KEY, branches != null ? new ArrayList<>(branches) : new ArrayList<>());
    }

    public static String createOrOpenBranchFlowArea(FlowGraph graph, String trigger) {
        String pin = pinForTrigger(trigger);
        if (pin == null) {
            pin = trigger;
        }
        List<String> branches = new ArrayList<>(getEnabledTriggerBranches(graph));
        if (pin != null && !pin.isBlank() && !branches.contains(pin)) {
            branches.add(pin);
            setEnabledTriggerBranches(graph, branches);
        }
        return pin;
    }

    public static String displayName(FlowGraph graph) {
        FlowNode node = findStartNode(graph);
        if (node == null || node.getInputValues() == null) {
            return graph != null ? graph.getId() : "";
        }
        return text(node.getInputValues().get("name"), graph.getId());
    }

    public static String material(FlowGraph graph) {
        FlowNode node = findStartNode(graph);
        if (node == null || node.getInputValues() == null) {
            return "";
        }
        return text(node.getInputValues().get("material"), "");
    }

    public static String typeFromNode(String nodeType) {
        if (nodeType != null && nodeType.startsWith("restudio.resync:")) {
            nodeType = nodeType.substring("restudio.resync:".length());
        }
        return CustomContentContract.typeFromNode(nodeType);
    }

    public static String nodeType(String type) {
        return CustomContentContract.nodeType(type);
    }

    public static String contentFlowId(String type, String contentId) {
        return CustomContentContract.contentFlowId(type, contentId);
    }

    public static String triggerForPin(String type, String pin) {
        return CustomContentContract.triggerForPin(type, pin);
    }

    public static String pinForTrigger(String trigger) {
        return CustomContentContract.pinForTrigger(trigger);
    }

    public static List<TriggerDescriptor> triggersForType(String type) {
        return CustomContentContract.triggersForType(type).stream().map(trigger -> new TriggerDescriptor(trigger.pin(), trigger.trigger())).toList();
    }

    private static List<CustomAbilityBinding> abilities(FlowGraph graph, FlowNode node, String type, String contentId, Map<String, Object> inputs) {
        List<String> branches = selectedBranches(graph, node, type, inputs);
        CustomTriggerRule rule = rule(inputs);
        List<CustomAbilityBinding> bindings = new ArrayList<>();
        for (String branch : branches) {
            String trigger = triggerForPin(type, branch);
            if (trigger == null) {
                continue;
            }
            CustomAbilityBinding binding = new CustomAbilityBinding(contentId + "." + trigger.replace('.', '_'), trigger, graph.getId());
            binding.setRule(rule);
            binding.setEnabled(rule.isEnabled());
            bindings.add(binding);
        }
        return bindings;
    }

    private static List<String> selectedBranches(FlowGraph graph, FlowNode node, String type, Map<String, Object> inputs) {
        Object stored = inputs.get(FLOW_BRANCHES_KEY);
        List<String> branches = new ArrayList<>();
        boolean storedBranchesPresent = false;
        if (stored instanceof List<?> list) {
            storedBranchesPresent = true;
            for (Object entry : list) {
                if (entry != null) {
                    branches.add(entry.toString());
                }
            }
        }
        String nodeId = findNodeId(graph, node);
        if (nodeId != null && graph.getConnections() != null) {
            for (FlowConnection connection : graph.getConnections()) {
                if (nodeId.equals(connection.getSourceNodeId()) && triggerForPin(type, connection.getSourcePin()) != null && !branches.contains(connection.getSourcePin())) {
                    branches.add(connection.getSourcePin());
                }
            }
        }
        if (branches.isEmpty() && !storedBranchesPresent) {
            branches.add(defaultBranch(type));
        }
        return branches;
    }

    private static String findNodeId(FlowGraph graph, FlowNode node) {
        if (graph == null || graph.getNodes() == null || node == null) {
            return null;
        }
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static CustomTriggerRule rule(Map<String, Object> inputs) {
        CustomTriggerRule rule = new CustomTriggerRule();
        rule.setEnabled(bool(inputs.getOrDefault("enabled", true)));
        rule.setPriority(number(inputs.get("priority"), 0).intValue());
        rule.setCooldownScope(text(inputs.get("cooldown_scope"), "player"));
        rule.setCooldownTicks(number(inputs.get("cooldown_ticks"), 0).intValue());
        rule.setPermission(text(inputs.get("permission"), ""));
        rule.setCancelEvent(bool(inputs.get("cancel_event")));
        rule.setConsumeEvent(bool(inputs.get("consume_event")));
        rule.setRequireSneaking(bool(inputs.get("require_sneaking")));
        rule.setRequireOnGround(bool(inputs.get("require_on_ground")));
        rule.setHandFilter(text(inputs.get("hand_filter"), "any"));
        rule.setTargetFilter(text(inputs.get("target_filter"), "any"));
        rule.setAllowedWorlds(csv(inputs.get("allowed_worlds")));
        rule.setDeniedWorlds(csv(inputs.get("denied_worlds")));
        rule.setChancePercent(number(inputs.get("chance_percent"), 100.0).doubleValue());
        rule.setMaxActivationsPerTick(number(inputs.get("max_activations_per_tick"), 0).intValue());
        return rule;
    }

    private static List<String> csv(Object value) {
        String text = text(value, "");
        if (text.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : text.split("[,\\r\\n]+")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), normalizeJsonValue(entry.getValue()));
            }
        }
        return result;
    }

    private static Object normalizeJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map(map);
        }
        if (value instanceof List<?> list) {
            List<Object> values = new ArrayList<>();
            for (Object item : list) {
                values.add(normalizeJsonValue(item));
            }
            return values;
        }
        return value;
    }

    private static Integer nullableInt(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return number(value, 0).intValue();
    }

    private static Number number(Object value, Number fallback) {
        if (value instanceof Number number) {
            return number;
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String text(Object value, String fallback) {
        return value != null ? value.toString() : fallback;
    }

    private static String normalizeType(String type) {
        return CustomContentContract.normalizeType(type);
    }

    private static String defaultName(String type) {
        return switch (normalizeType(type)) {
            case "block" -> "New Block";
            case "armor" -> "New Armor";
            case "projectile" -> "New Projectile";
            default -> "New Item";
        };
    }

    private static String defaultMaterial(String type) {
        return switch (normalizeType(type)) {
            case "block" -> "STONE";
            case "armor" -> "IRON_CHESTPLATE";
            case "projectile" -> "ARROW";
            default -> "STICK";
        };
    }

    public static String defaultBranch(String type) {
        return switch (normalizeType(type)) {
            case "block" -> "interact";
            case "armor" -> "tick";
            case "projectile" -> "fire";
            default -> "use";
        };
    }

    private static String normalizeMaterial(String material) {
        String normalized = material == null ? "" : material.trim();
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return normalized.isBlank() ? "STICK" : normalized.toUpperCase(Locale.ROOT);
    }

    public record TriggerDescriptor(String pin, String trigger) {
    }
}
