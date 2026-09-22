package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowJson;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.data.TabDefinition;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

public enum ReSyncResourceType {

    FLOW(
            item -> serializeGraph(item, "flow"), json -> deserializeGraph(json, "flow"),
            (item, newId) -> ((FlowGraph) item).setId(newId),
            item -> ((FlowGraph) item).getId(),
            item -> ((FlowGraph) item).getId()
    ),

    FUNCTION(
            item -> serializeGraph(item, "function"), json -> deserializeGraph(json, "function"),
            (item, newId) -> ((FlowGraph) item).setId(newId),
            item -> ((FlowGraph) item).getId(),
            item -> ((FlowGraph) item).getId()
    ),

    COMMAND(
            item -> serializeGraph(item, "command"), json -> deserializeGraph(json, "command"),
            (item, newId) -> ((FlowGraph) item).setId(newId),
            item -> ((FlowGraph) item).getId(),
            item -> ((FlowGraph) item).getId()
    ),

    GUI(
            item -> FlowJson.write(FlowJson.gui((GuiDefinition) item)), json -> FlowJson.gui(FlowJson.parse(json).getAsJsonObject()),
            (item, newId) -> {
                GuiDefinition gui = (GuiDefinition) item;
                String oldId = gui.getId();
                gui.setId(newId);
                if (gui.getTitle() == null || gui.getTitle().isBlank() || gui.getTitle().equals(oldId)) {
                    gui.setTitle(newId);
                }
            },
            item -> ((GuiDefinition) item).getId(),
            item -> {
                GuiDefinition gui = (GuiDefinition) item;
                return gui.getTitle() != null ? gui.getTitle() : gui.getId();
            }
    ),

    SCOREBOARD(
            item -> FlowJson.write(FlowJson.scoreboard((ScoreboardDefinition) item)), json -> FlowJson.scoreboard(FlowJson.parse(json).getAsJsonObject()),
            (item, newId) -> {
                ScoreboardDefinition sb = (ScoreboardDefinition) item;
                String oldId = sb.getId();
                sb.setId(newId);
                if (sb.getObjectiveId() == null || sb.getObjectiveId().isBlank() || sb.getObjectiveId().equals(oldId)) {
                    sb.setObjectiveId(newId);
                }
                if (sb.getTitle() == null || sb.getTitle().isBlank() || sb.getTitle().equals(oldId)) {
                    sb.setTitle(newId);
                }
            },
            item -> ((ScoreboardDefinition) item).getId(),
            item -> {
                ScoreboardDefinition sb = (ScoreboardDefinition) item;
                return sb.getTitle() != null ? sb.getTitle() : sb.getId();
            }
    ),

    TAB(
            item -> FlowJson.write(FlowJson.tab((TabDefinition) item)), json -> FlowJson.tab(FlowJson.parse(json).getAsJsonObject()),
            (item, newId) -> ((TabDefinition) item).setId(newId),
            item -> ((TabDefinition) item).getId(),
            item -> ((TabDefinition) item).getId()
    ),

    CUSTOM_CONTENT(
            item -> FlowJson.write(FlowJson.customContent((CustomContentDefinition) item)), json -> FlowJson.customContent(FlowJson.parse(json).getAsJsonObject()),
            (item, newId) -> ((CustomContentDefinition) item).setId(newId),
            item -> ((CustomContentDefinition) item).getId(),
            item -> {
                CustomContentDefinition content = (CustomContentDefinition) item;
                return content.getDisplayName() != null ? content.getDisplayName() : content.getId();
            }
    ),

    PROJECT_METADATA(
            item -> FlowJson.write(FlowJson.projectMetadata(item instanceof ProjectMetadataSnapshot snapshot ? snapshot.materialize() : (ReSyncProjectMetadata) item)),
            json -> FlowJson.projectMetadata(FlowJson.parse(json).getAsJsonObject()),
            (item, newId) -> ((ReSyncProjectMetadata) item).setServerId(newId),
            item -> item instanceof ProjectMetadataSnapshot snapshot ? snapshot.serverId() : ((ReSyncProjectMetadata) item).getServerId() == null || ((ReSyncProjectMetadata) item).getServerId().isBlank() ? "project" : ((ReSyncProjectMetadata) item).getServerId(),
            item -> "Project"
    ),

    CHAT(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    COMPONENT_BUILDER(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    MOTD_PROFILE(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    MESSAGE_RULE(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    RECIPE_DEFINITION(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    TEXT_TEMPLATE(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    ADVANCEMENT_TREE(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    DIALOG(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    TRADE_PROFILE(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    NPC_DEFINITION(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    LOOT_TABLE(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    VARIABLE_DEFINITION(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    TIMER_DEFINITION(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    ),

    SCHEDULE_DEFINITION(
            ReSyncResourceType::serializeJsonObject, ReSyncResourceType::deserializeJsonObject,
            ReSyncResourceType::renameJsonObject, ReSyncResourceType::jsonObjectId, ReSyncResourceType::jsonObjectName
    );

    @FunctionalInterface
    public interface Serializer {
        String serialize(Object item);
    }

    @FunctionalInterface
    public interface Deserializer {
        Object deserialize(String json);
    }

    @FunctionalInterface
    public interface IdExtractor {
        String extract(Object item);
    }

    private final byte requestByte;
    private final byte listRequestByte;
    private final byte dataResponseByte;
    private final byte listResponseByte;
    private final byte saveByte;
    private final byte deleteByte;
    private final byte saveAckByte;
    private final String typeId;
    private final String displayName;
    private final String defaultFolder;
    private final boolean enabled;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final BiConsumer<Object, String> renameApplier;
    private final IdExtractor idExtractor;
    private final IdExtractor nameExtractor;

    ReSyncResourceType(Serializer serializer, Deserializer deserializer,
                       BiConsumer<Object, String> renameApplier,
                       IdExtractor idExtractor, IdExtractor nameExtractor) {
        this.typeId = name().toLowerCase(Locale.ROOT);
        ReSyncProtocolContract.ResourceContract resource = ReSyncProtocolContract.resource(typeId);
        ReSyncProtocolContract.ResourceFlowPackets packets = resource != null ? resource.flowPackets() : null;
        this.requestByte = packets != null ? packets.request() : 0;
        this.listRequestByte = packets != null ? packets.listRequest() : 0;
        this.dataResponseByte = packets != null ? packets.data() : 0;
        this.listResponseByte = packets != null ? packets.list() : 0;
        this.saveByte = packets != null ? packets.save() : 0;
        this.deleteByte = packets != null ? packets.delete() : 0;
        this.saveAckByte = packets != null ? packets.saveAck() : 0;
        this.displayName = resource != null ? resource.displayName() : typeId;
        this.defaultFolder = resource != null ? resource.defaultFolder() : "Blueprints/Flows";
        this.enabled = resource != null;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.renameApplier = renameApplier;
        this.idExtractor = idExtractor;
        this.nameExtractor = nameExtractor;
    }

    public byte requestByte() { return requestByte; }
    public byte listRequestByte() { return listRequestByte; }
    public byte dataResponseByte() { return dataResponseByte; }
    public byte listResponseByte() { return listResponseByte; }
    public byte saveByte() { return saveByte; }
    public byte deleteByte() { return deleteByte; }
    public byte saveAckByte() { return saveAckByte; }
    public String typeId() { return typeId; }
    public String displayName() { return displayName; }
    public String defaultFolder() { return defaultFolder; }
    public boolean enabled() { return enabled; }

    public String serialize(Object item) { return serializer.serialize(item); }
    public Object deserialize(String json) { return deserializer.deserialize(json); }
    public void applyRename(Object item, String newId) { renameApplier.accept(item, newId); }
    public String extractId(Object item) { return idExtractor.extract(item); }
    public String extractName(Object item) { return nameExtractor.extract(item); }
    public boolean isGraph() { return this == FLOW || this == FUNCTION || this == COMMAND; }
    public boolean acceptsCorePayloadKind(String payloadKind) {
        return switch (payloadKind) {
            case "graph-document" -> this == FLOW || this == COMMAND;
            case "function-source" -> this == FUNCTION;
            default -> false;
        };
    }

    public static ReSyncResourceType byDataResponse(byte packetId) {
        for (ReSyncResourceType rt : values()) {
            if (rt.enabled && !rt.isGraph() && rt.dataResponseByte == packetId) return rt;
        }
        return null;
    }

    public static ReSyncResourceType byListResponse(byte packetId) {
        for (ReSyncResourceType rt : values()) {
            if (rt.enabled && !rt.isGraph() && rt.listResponseByte == packetId) return rt;
        }
        return null;
    }

    public static ReSyncResourceType bySaveAck(byte packetId) {
        for (ReSyncResourceType rt : values()) {
            if (rt.enabled && !rt.isGraph() && rt.saveAckByte == packetId) return rt;
        }
        return null;
    }

    public static boolean isLegacyGraphResponse(byte packetId) {
        for (ReSyncResourceType type : List.of(FLOW, FUNCTION, COMMAND)) {
            if (type.enabled && (type.dataResponseByte == packetId || type.listResponseByte == packetId
                || type.saveAckByte == packetId)) {
                return true;
            }
        }
        return false;
    }

    public static ReSyncResourceType byTypeId(String typeId) {
        for (ReSyncResourceType rt : values()) {
            if (rt.typeId.equals(typeId)) {
                return rt;
            }
        }
        return null;
    }

    public static String defaultFolderFor(String typeId) {
        ReSyncProtocolContract.ResourceContract resource = ReSyncProtocolContract.resource(typeId);
        return resource != null ? resource.defaultFolder() : "Blueprints/Flows";
    }

    private static String serializeGraph(Object item, String type) {
        FlowGraph graph = (FlowGraph) item;
        graph.setResourceType(type);
        graph.setFunction("function".equals(type));
        return FlowJson.write(FlowJson.graph(graph));
    }

    private static Object deserializeGraph(String json, String type) {
        FlowGraph graph = FlowJson.graph(FlowJson.parse(json).getAsJsonObject());
        if (graph != null) {
            graph.setResourceType(type);
            graph.setFunction("function".equals(type));
        }
        return graph;
    }

    private static String serializeJsonObject(Object item) {
        return new Gson().toJson(item);
    }

    private static Object deserializeJsonObject(String json) {
        return new Gson().fromJson(json, JsonObject.class);
    }

    private static void renameJsonObject(Object item, String newId) {
        if (item instanceof JsonObject json) {
            json.addProperty("id", newId);
        }
    }

    private static String jsonObjectId(Object item) {
        if (item instanceof JsonObject json && json.has("id") && !json.get("id").isJsonNull()) {
            return json.get("id").getAsString();
        }
        return "";
    }

    private static String jsonObjectName(Object item) {
        if (item instanceof JsonObject json) {
            if (json.has("displayName") && !json.get("displayName").isJsonNull()) {
                return json.get("displayName").getAsString();
            }
            if (json.has("name") && !json.get("name").isJsonNull()) {
                return json.get("name").getAsString();
            }
            if (json.has("id") && !json.get("id").isJsonNull()) {
                return json.get("id").getAsString();
            }
        }
        return "";
    }
}
