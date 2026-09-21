package redxax.oxy.remotely.flow.data;

import com.google.gson.annotations.JsonAdapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@JsonAdapter(FlowDataTypeAdapter.class)
public final class FlowDataType {
    private static final Map<String, FlowDataType> REGISTRY = new LinkedHashMap<>();

    public static final FlowDataType EXECUTION = new FlowDataType("execution", null, 0xFFFFFF, "Execution");
    public static final FlowDataType ANY = new FlowDataType("any", null, 0x808080, "Any");
    public static final FlowDataType STRING = new FlowDataType("string", null, 0xDA00FF, "String");
    public static final FlowDataType FUNCTION = new FlowDataType("function", STRING, 0xFFAA55, "Function");
    public static final FlowDataType FLOW_ID = new FlowDataType("flow_id", STRING, 0x7AA2F7, "Flow");
    public static final FlowDataType COMMAND_ID = new FlowDataType("command_id", STRING, 0x9C7BEF, "Command");
    public static final FlowDataType CUSTOM_CONTENT_ID = new FlowDataType("custom_content_id", STRING, 0x78D64B, "Custom Content");
    public static final FlowDataType GUI_ID = new FlowDataType("gui_id", STRING, 0x4FA3FF, "GUI");
    public static final FlowDataType SCOREBOARD_ID = new FlowDataType("scoreboard_id", STRING, 0x3E8BFF, "Scoreboard");
    public static final FlowDataType TAB_ID = new FlowDataType("tab_id", STRING, 0x61B5FF, "Tab List");
    public static final FlowDataType CHAT_ID = new FlowDataType("chat_id", STRING, 0x5CC8FF, "Chat Profile");
    public static final FlowDataType COMPONENT_BUILDER_ID = new FlowDataType("component_builder_id", STRING, 0x35D9C7, "Component Builder");
    public static final FlowDataType MOTD_PROFILE_ID = new FlowDataType("motd_profile_id", STRING, 0xE066FF, "MOTD Profile");
    public static final FlowDataType MESSAGE_RULE_ID = new FlowDataType("message_rule_id", STRING, 0xB96BFF, "Message Rule");
    public static final FlowDataType RECIPE_ID = new FlowDataType("recipe_id", STRING, 0x71C76F, "Recipe");
    public static final FlowDataType TEXT_TEMPLATE_ID = new FlowDataType("text_template_id", STRING, 0xE5A5FF, "Text Template");
    public static final FlowDataType ADVANCEMENT_TREE_ID = new FlowDataType("advancement_tree_id", STRING, 0xFFD34E, "Advancement Tree");
    public static final FlowDataType DIALOG_ID = new FlowDataType("dialog_id", STRING, 0xC274FF, "Dialog");
    public static final FlowDataType TRADE_PROFILE_ID = new FlowDataType("trade_profile_id", STRING, 0xD6A84B, "Trade Profile");
    public static final FlowDataType NPC_ID = new FlowDataType("npc_id", STRING, 0xBF7A4A, "NPC");
    public static final FlowDataType LOOT_TABLE_ID = new FlowDataType("loot_table_id", STRING, 0xE87948, "Loot Table");
    public static final FlowDataType WORLDGEN_ID = new FlowDataType("worldgen_id", STRING, 0x1DBBB7, "Worldgen Project");
    public static final FlowDataType FLOW_DEFINITION = new FlowDataType("flow_definition", null, 0x7AA2F7, "Flow");
    public static final FlowDataType FUNCTION_DEFINITION = new FlowDataType("function_definition", FLOW_DEFINITION, 0xFFAA55, "Function");
    public static final FlowDataType COMMAND_DEFINITION = new FlowDataType("command_definition", FLOW_DEFINITION, 0x9C7BEF, "Command");
    public static final FlowDataType NUMBER = new FlowDataType("number", null, 0x00FF93, "Number");
    public static final FlowDataType INTEGER = new FlowDataType("integer", NUMBER, 0x00D982, "Integer");
    public static final FlowDataType FLOAT = new FlowDataType("float", NUMBER, 0x00BFFF, "Float");
    public static final FlowDataType INSTANT = new FlowDataType("instant", NUMBER, 0x37C8FF, "Instant");
    public static final FlowDataType DURATION = new FlowDataType("duration", NUMBER, 0x45B7E8, "Duration");
    public static final FlowDataType BOOLEAN = new FlowDataType("boolean", null, 0xD20000, "Boolean");
    public static final FlowDataType ENTITY = new FlowDataType("entity", null, 0x8B4513, "Entity");
    public static final FlowDataType LIVING_ENTITY = new FlowDataType("living_entity", ENTITY, 0xA0522D, "Living Entity");
    public static final FlowDataType PLAYER = new FlowDataType("player", LIVING_ENTITY, 0x0066FF, "Player");
    public static final FlowDataType MATERIAL = new FlowDataType("material", null, 0x00AA00, "Material");
    public static final FlowDataType BLOCK = new FlowDataType("block", null, 0x228B22, "Block");
    public static final FlowDataType ITEM = new FlowDataType("item", MATERIAL, 0x32CD32, "Item");
    public static final FlowDataType ITEMSTACK = new FlowDataType("itemstack", ITEM, 0x32CD32, "Item Stack");
    public static final FlowDataType WORLD = new FlowDataType("world", null, 0x00CED1, "World");
    public static final FlowDataType BIOME = new FlowDataType("biome", null, 0x20B2AA, "Biome");
    public static final FlowDataType VECTOR = new FlowDataType("vector", null, 0x7FFFD4, "Vector");
    public static final FlowDataType LOCATION = new FlowDataType("location", VECTOR, 0xFFA500, "Location");
    public static final FlowDataType VECTOR2 = new FlowDataType("vector2", VECTOR, 0x7FFFD4, "Vector2");
    public static final FlowDataType VECTOR3 = new FlowDataType("vector3", VECTOR, 0x40E0D0, "Vector3");
    public static final FlowDataType SEED = new FlowDataType("seed", NUMBER, 0xFFD700, "Seed");
    public static final FlowDataType COLOR = new FlowDataType("color", null, 0xFF66CC, "Color");
    public static final FlowDataType RGB_COLOR = new FlowDataType("rgb_color", COLOR, 0xFF66CC, "RGB Color");
    public static final FlowDataType NAMED_TEXT_COLOR = new FlowDataType("named_text_color", null, 0xFFD166, "Named Text Color");
    public static final FlowDataType UUID = new FlowDataType("uuid", null, 0x708090, "UUID");
    public static final FlowDataType GAMEMODE = new FlowDataType("gamemode", null, 0x4169E1, "Gamemode");
    public static final FlowDataType DIFFICULTY = new FlowDataType("difficulty", null, 0xDC143C, "Difficulty");
    public static final FlowDataType ENTITY_TYPE = new FlowDataType("entity_type", null, 0xCD853F, "Entity Type");
    public static final FlowDataType ENCHANTMENT = new FlowDataType("enchantment", null, 0x9370DB, "Enchantment");
    public static final FlowDataType INVENTORY = new FlowDataType("inventory", null, 0x4682B4, "Inventory");
    public static final FlowDataType POTION_EFFECT = new FlowDataType("potion_effect", null, 0xFF1493, "Potion Effect");
    public static final FlowDataType SOUND = new FlowDataType("sound", null, 0xFFB347, "Sound");
    public static final FlowDataType ADVANCEMENT = new FlowDataType("advancement", null, 0xFFD700, "Advancement");
    public static final FlowDataType PERMISSION_GROUP = new FlowDataType("permission_group", null, 0x6A5ACD, "Permission Group");
    public static final FlowDataType SCOREBOARD = new FlowDataType("scoreboard", null, 0x1E90FF, "Scoreboard");
    public static final FlowDataType TEAM = new FlowDataType("team", null, 0x00BFFF, "Team");
    public static final FlowDataType REGION = new FlowDataType("region", null, 0x9ACD32, "Region");
    public static final FlowDataType COMPONENT = new FlowDataType("component", STRING, 0xE066FF, "Component");
    public static final FlowDataType PERMISSION = new FlowDataType("permission", null, 0xB96BFF, "Permission");
    public static final FlowDataType RESOURCE_REFERENCE = new FlowDataType("resource_reference", null, 0x5CC8FF, "Resource");
    public static final FlowDataType VARIABLE_REFERENCE = new FlowDataType("variable_reference", RESOURCE_REFERENCE, 0x5CC8FF, "Variable");
    public static final FlowDataType TIMER_REFERENCE = new FlowDataType("timer_reference", RESOURCE_REFERENCE, 0x45B7E8, "Timer");
    public static final FlowDataType SCHEDULE_REFERENCE = new FlowDataType("schedule_reference", RESOURCE_REFERENCE, 0x7AA2F7, "Schedule");
    public static final FlowDataType RESULT = new FlowDataType("result", null, 0x55D68A, "Result");
    public static final FlowDataType JOB_REFERENCE = new FlowDataType("job_reference", null, 0xFFB347, "Job");
    public static final FlowDataType SCHEDULED_TASK = new FlowDataType("scheduled_task", null, 0x7AA2F7, "Scheduled Task");
    public static final FlowDataType GUI_DEFINITION = new FlowDataType("gui_definition", null, 0x4FA3FF, "GUI");
    public static final FlowDataType SCOREBOARD_DEFINITION = new FlowDataType("scoreboard_definition", null, 0x3E8BFF, "Scoreboard");
    public static final FlowDataType TAB_DEFINITION = new FlowDataType("tab_definition", null, 0x61B5FF, "Tab List");
    public static final FlowDataType CUSTOM_CONTENT_DEFINITION = new FlowDataType("custom_content_definition", null, 0x78D64B, "Custom Content Definition");
    public static final FlowDataType CHAT_PROFILE = new FlowDataType("chat_profile", null, 0x5CC8FF, "Chat Profile");
    public static final FlowDataType MOTD_PROFILE = new FlowDataType("motd_profile", null, 0xE066FF, "MOTD Profile");
    public static final FlowDataType MESSAGE_RULE = new FlowDataType("message_rule", null, 0xB96BFF, "Message Rule");
    public static final FlowDataType TEXT_TEMPLATE = new FlowDataType("text_template", null, 0xE5A5FF, "Text Template");
    public static final FlowDataType DIALOG_DEFINITION = new FlowDataType("dialog_definition", null, 0xC274FF, "Dialog");
    public static final FlowDataType TRADE_PROFILE = new FlowDataType("trade_profile", null, 0xD6A84B, "Trade Profile");
    public static final FlowDataType TRADE_DEFINITION = new FlowDataType("trade_definition", null, 0xE2B95E, "Trade Definition");
    public static final FlowDataType LOOT_TABLE_DEFINITION = new FlowDataType("loot_table_definition", null, 0xE87948, "Loot Table Definition");
    public static final FlowDataType LOOT_POOL_DEFINITION = new FlowDataType("loot_pool_definition", null, 0xD9683C, "Loot Pool Definition");
    public static final FlowDataType LOOT_ENTRY_DEFINITION = new FlowDataType("loot_entry_definition", null, 0xC95A34, "Loot Entry Definition");
    public static final FlowDataType NPC_DEFINITION = new FlowDataType("npc_definition", null, 0xBF7A4A, "NPC");
    public static final FlowDataType ADVANCEMENT_TREE_DEFINITION = new FlowDataType("advancement_tree_definition", null, 0xFFD34E, "Advancement Tree Definition");
    public static final FlowDataType RECIPE_DEFINITION = new FlowDataType("recipe_definition", null, 0x71C76F, "Recipe Definition");
    public static final FlowDataType RECIPE_INGREDIENT_DEFINITION = new FlowDataType("recipe_ingredient_definition", null, 0x62B861, "Recipe Ingredient Definition");
    public static final FlowDataType PERMISSION_TRACK = new FlowDataType("permission_track", RESOURCE_REFERENCE, 0x765BC4, "Permission Track");
    public static final FlowDataType PERMISSION_CONTEXT = new FlowDataType("permission_context", null, 0x8A68D6, "Permission Context");
    public static final FlowDataType TEXT_DECORATION = new FlowDataType("text_decoration", null, 0xE5A5FF, "Text Decoration");
    public static final FlowDataType FORMATTING_POLICY = new FlowDataType("formatting_policy", null, 0xCA82E8, "Formatting Policy");
    public static final FlowDataType ITEM_DEFINITION = new FlowDataType("item_definition", null, 0x70C95E, "Item Definition");
    public static final FlowDataType RECIPE_CONDITION = new FlowDataType("recipe_condition", null, 0x57A95A, "Recipe Condition");
    public static final FlowDataType GUI_SESSION = new FlowDataType("gui_session", RESOURCE_REFERENCE, 0x348CDD, "Open GUI");
    public static final FlowDataType GUI_ELEMENT = new FlowDataType("gui_element", null, 0x69B8FF, "GUI Element");
    public static final FlowDataType GUI_EVENT = new FlowDataType("gui_event", null, 0x2F78BE, "GUI Event");
    public static final FlowDataType DIALOG_RESULT = new FlowDataType("dialog_result", null, 0xB25FE6, "Dialog Result");
    public static final FlowDataType DIALOG_EVENT = new FlowDataType("dialog_event", null, 0x9F4CD5, "Dialog Event");
    public static final FlowDataType SIDEBAR_SESSION = new FlowDataType("sidebar_session", RESOURCE_REFERENCE, 0x3479D3, "Active Scoreboard");
    public static final FlowDataType SCOREBOARD_LINE = new FlowDataType("scoreboard_line", null, 0x4A9AF0, "Scoreboard Line");
    public static final FlowDataType DISPLAY_SLOT = new FlowDataType("display_slot", null, 0x1775D1, "Display Slot");
    public static final FlowDataType TAB_APPLICATION = new FlowDataType("tab_application", RESOURCE_REFERENCE, 0x4AA4EE, "Tab Application");
    public static final FlowDataType NPC_HANDLE = new FlowDataType("npc_handle", null, 0xA96437, "Active NPC");
    public static final FlowDataType NPC_EVENT = new FlowDataType("npc_event", null, 0x99542C, "NPC Event");
    public static final FlowDataType MERCHANT = new FlowDataType("merchant", RESOURCE_REFERENCE, 0xC69845, "Merchant");
    public static final FlowDataType TRADE_SESSION = new FlowDataType("trade_session", RESOURCE_REFERENCE, 0xD4A64A, "Trade Session");
    public static final FlowDataType LOOT_CONTEXT = new FlowDataType("loot_context", null, 0xC95531, "Loot Context");
    public static final FlowDataType GENERATED_LOOT = new FlowDataType("generated_loot", null, 0xB94A29, "Generated Loot");
    public static final FlowDataType ADVANCEMENT_CRITERION = new FlowDataType("advancement_criterion", null, 0xE6BD35, "Advancement Criterion");
    public static final FlowDataType ADVANCEMENT_PROGRESS = new FlowDataType("advancement_progress", null, 0xD9A820, "Advancement Progress");
    public static final FlowDataType PLACED_CONTENT = new FlowDataType("placed_content", RESOURCE_REFERENCE, 0x5EB63E, "Placed Content");
    public static final FlowDataType ENTITY_DATA = new FlowDataType("entity_data", null, 0x9B6847, "Entity Data");
    public static final FlowDataType ITEM_ATTRIBUTE = new FlowDataType("item_attribute", null, 0x5FCB82, "Item Attribute");
    public static final FlowDataType ITEM_COMPONENT = new FlowDataType("item_component", null, 0x4CBA73, "Item Component");
    public static final FlowDataType ITEM_COMPONENTS = new FlowDataType("item_components", null, 0x55C77C, "Item Components");
    public static final FlowDataType ITEM_COMPONENT_LIST = new FlowDataType("item_component_list", null, 0x63D18A, "Item Component List");
    public static final FlowDataType ITEM_MODIFIER = new FlowDataType("item_modifier", null, 0x42A766, "Item Modifier");
    public static final FlowDataType RUNTIME_DATA_ENTRY = new FlowDataType("runtime_data_entry", null, 0x327A8F, "Runtime Data Entry");
    public static final FlowDataType RUNTIME_DATA_CATEGORY = new FlowDataType("runtime_data_category", null, 0x3B8EA3, "Runtime Data Category");
    public static final FlowDataType SCHEMA_VALUE = new FlowDataType("schema_value", null, 0x38975B, "Schema Value");
    public static final FlowDataType STRUCTURE = new FlowDataType("structure", RESOURCE_REFERENCE, 0xA68962, "Structure");
    public static final FlowDataType WORLDGEN_PROJECT = new FlowDataType("worldgen_project", RESOURCE_REFERENCE, 0x1DBBB7, "Worldgen Project");
    public static final FlowDataType WORLDGEN_JOB = new FlowDataType("worldgen_job", JOB_REFERENCE, 0xE89B32, "Worldgen Job");
    public static final FlowDataType WORLDGEN_FEATURE = new FlowDataType("worldgen_feature", STRING, 0x5CAD4A, "Worldgen Feature");
    public static final FlowDataType WORLDGEN_FEATURES = new FlowDataType("worldgen_features", STRING, 0x73C95C, "Worldgen Features");
    public static final FlowDataType WORLDGEN_STRUCTURES = new FlowDataType("worldgen_structures", STRING, 0xC49A5A, "Worldgen Structures");
    public static final FlowDataType WORLDGEN_SPAWNS = new FlowDataType("worldgen_spawns", STRING, 0xD06E57, "Worldgen Spawns");
    public static final FlowDataType PLAYER_IDENTITY = new FlowDataType("player_identity", RESOURCE_REFERENCE, 0x287EE8, "Player Identity");
    public static final FlowDataType OFFLINE_PLAYER_DOSSIER = new FlowDataType("offline_player_dossier", null, 0x3569B7, "Offline Player Dossier");
    public static final FlowDataType TRACKED_PLAYER_STATE = new FlowDataType("tracked_player_state", null, 0x22599D, "Tracked Player State");
    public static final FlowDataType NETWORK_NODE = new FlowDataType("network_node", RESOURCE_REFERENCE, 0x3F73D6, "Network Node");
    public static final FlowDataType NETWORK_ROUTE = new FlowDataType("network_route", RESOURCE_REFERENCE, 0x4F83E6, "Network Route");
    public static final FlowDataType NETWORK_SCOPE = new FlowDataType("network_scope", null, 0x5D91F1, "Network Scope");
    public static final FlowDataType NETWORK_VARIABLE = new FlowDataType("network_variable", null, 0x6A9DF3, "Network Variable");
    public static final FlowDataType NETWORK_SNAPSHOT = new FlowDataType("network_snapshot", null, 0x557FC7, "Network Snapshot");
    public static final FlowDataType NETWORK_TRANSFER_RESULT = new FlowDataType("network_transfer_result", null, 0x436CAD, "Network Transfer Result");
    public static final FlowDataType HTTP_RESPONSE = new FlowDataType("http_response", null, 0x4E9ECF, "HTTP Response");
    public static final FlowDataType OPTIONAL = new FlowDataType("optional", null, 0xA0A0A0, "Optional");
    public static final FlowDataType JSON_OBJECT = new FlowDataType("json_object", null, 0x4B0082, "JSON Object");
    public static final FlowDataType LIST = new FlowDataType("list", null, 0xFF69B4, "List");
    public static final FlowDataType MAP = new FlowDataType("map", null, 0x9932CC, "Map");
    public static final FlowDataType SET = new FlowDataType("set", null, 0xFF4500, "Set");
    public static final FlowDataType QUEUE = new FlowDataType("queue", null, 0x2E8B57, "Queue");
    public static final FlowDataType STACK = new FlowDataType("stack", null, 0x4682B4, "Stack");

    private final String id;
    private final FlowDataType parent;
    private final int color;
    private final String displayName;
    private final boolean canStringify;
    private final String owner;
    private final boolean resolved;

    private FlowDataType(String id, FlowDataType parent, int color, String displayName) {
        this(id, parent, color, displayName, !"execution".equals(id) && !"any".equals(id));
    }

    private FlowDataType(String id, FlowDataType parent, int color, String displayName, boolean canStringify) {
        this(id, parent, color, displayName, canStringify, "builtin", true, true);
    }

    private FlowDataType(String id, FlowDataType parent, int color, String displayName, boolean canStringify,
                         String owner, boolean resolved, boolean register) {
        this.id = id;
        this.parent = parent;
        this.color = color;
        this.displayName = displayName;
        this.canStringify = canStringify;
        this.owner = owner;
        this.resolved = resolved;
        if (register) {
            REGISTRY.put(id, this);
        }
    }

    static {
        REGISTRY.put("flow", EXECUTION);
    }

    public String getId() {
        return id;
    }

    public String getCanonicalId() {
        if (id != null && id.contains(":")) {
            return id;
        }
        String namespace = owner == null || owner.isBlank() || "builtin".equals(owner) ? "resync" : owner.toLowerCase(Locale.ROOT);
        return namespace + ":" + id;
    }

    public FlowDataType getParent() {
        return parent;
    }

    public int getColor() {
        return 0xFF000000 | color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isAssignableFrom(FlowDataType other) {
        if (this == ANY || this == other) return true;
        if (other == null) return false;
        if (other.parent != null && (equals(other.parent) || isAssignableFrom(other.parent))) return true;
        return false;
    }

    public boolean canConvertTo(FlowDataType target) {
        if (target == null) return false;
        if (this == ANY || target == ANY) return true;
        if (target.isAssignableFrom(this)) return true;
        if (target == STRING) return canStringify();
        return false;
    }

    public boolean canStringify() {
        return canStringify;
    }

    public String getOwner() {
        return owner;
    }

    public boolean isResolved() {
        return resolved;
    }

    public static List<FlowDataType> values() {
        return REGISTRY.values().stream().distinct().filter(type -> type != EXECUTION).toList();
    }

    public static FlowDataType fromString(String id) {
        if (id == null || id.isEmpty()) return ANY;
        String normalized = id.toLowerCase();
        FlowDataType type = REGISTRY.get(normalized);
        if (type != null) return type;
        String displayName = normalized.substring(normalized.lastIndexOf(':') + 1).replace('_', ' ');
        if (!displayName.isBlank()) {
            displayName = Character.toUpperCase(displayName.charAt(0)) + displayName.substring(1);
        }
        String owner = normalized.contains(":") ? normalized.substring(0, normalized.indexOf(':')) : "unresolved";
        return new FlowDataType(normalized, null, 0x808080, displayName, false, owner, false, false);
    }

    public static FlowDataType serverType(String id, String displayName, int color, FlowDataType parent, boolean canStringify, String owner) {
        if (id == null || id.isBlank()) {
            return ANY;
        }
        String normalized = id.toLowerCase();
        FlowDataType existing = REGISTRY.get(normalized);
        if (existing != null) {
            return existing;
        }
        String resolvedName = displayName != null && !displayName.isBlank() ? displayName : normalized;
        return new FlowDataType(normalized, parent, color, resolvedName, canStringify,
            owner != null && !owner.isBlank() ? owner : "server", true, false);
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FlowDataType)) return false;
        FlowDataType that = (FlowDataType) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
