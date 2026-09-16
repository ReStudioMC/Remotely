package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.protocol.ReSyncProtocolContract.FlowContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ReSyncProtocolContract {
    public static final String FLOW_TRIGGER_UPDATE_CAPABILITY = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_CAPABILITY;
    public static final FlowContract FLOW_CONTRACT = ReSyncCoreProtocolContract.FLOW_CONTRACT;
    public static final int PROTOCOL_VERSION = ReSyncCoreProtocolContract.PROTOCOL_VERSION;
    public static final int MAX_ENCODED_FRAME_BYTES = ReSyncCoreProtocolContract.MAX_ENCODED_FRAME_BYTES;
    public static final int MAX_DECOMPRESSED_PAYLOAD_BYTES = ReSyncCoreProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES;
    public static final CatalogVersion GENERIC_RESOURCE_CONTRACT_VERSION = ReSyncCoreProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION;
    public static final ContractRef<CapabilityId> RESOURCE_ACTIVATION_CAPABILITY = ReSyncCoreProtocolContract.RESOURCE_ACTIVATION_CAPABILITY;
    public static final ContractRef<CapabilityId> RESOURCE_CREATE_PRESENTATION_CAPABILITY =
        ReSyncCoreProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY;
    public static final ContractRef<CapabilityId> OPTION_QUERIES_CAPABILITY =
        ReSyncCoreProtocolContract.OPTION_QUERIES_CAPABILITY;
    public static final String CHANNEL_FLOW = ReSyncCoreProtocolContract.CHANNEL_FLOW;
    public static final String CHANNEL_PLAYER_TRACKING = ReSyncCoreProtocolContract.CHANNEL_PLAYER_TRACKING;
    public static final String CHANNEL_WORLD_MANAGEMENT = ReSyncCoreProtocolContract.CHANNEL_WORLD_MANAGEMENT;
    public static final String CHANNEL_WORLDGEN = ReSyncCoreProtocolContract.CHANNEL_WORLDGEN;
    public static final short CHANNEL_CONTROL_ID = ReSyncCoreProtocolContract.CHANNEL_CONTROL_ID;
    public static final short CHANNEL_FLOW_ID = ReSyncCoreProtocolContract.CHANNEL_FLOW_ID;
    public static final short CHANNEL_PLAYER_TRACKING_ID = ReSyncCoreProtocolContract.CHANNEL_PLAYER_TRACKING_ID;
    public static final short CHANNEL_WORLD_MANAGEMENT_ID = ReSyncCoreProtocolContract.CHANNEL_WORLD_MANAGEMENT_ID;
    public static final short CHANNEL_WORLDGEN_ID = ReSyncCoreProtocolContract.CHANNEL_WORLDGEN_ID;

    public static final byte MESSAGE_HANDSHAKE_REQUEST = ReSyncCoreProtocolContract.MESSAGE_HANDSHAKE_REQUEST;
    public static final byte MESSAGE_HANDSHAKE_RESPONSE = ReSyncCoreProtocolContract.MESSAGE_HANDSHAKE_RESPONSE;
    public static final byte MESSAGE_SUBSCRIBE = ReSyncCoreProtocolContract.MESSAGE_SUBSCRIBE;
    public static final byte MESSAGE_UNSUBSCRIBE = ReSyncCoreProtocolContract.MESSAGE_UNSUBSCRIBE;
    public static final byte MESSAGE_DATA = ReSyncCoreProtocolContract.MESSAGE_DATA;
    public static final byte MESSAGE_HEARTBEAT = ReSyncCoreProtocolContract.MESSAGE_HEARTBEAT;
    public static final byte MESSAGE_ACK = ReSyncCoreProtocolContract.MESSAGE_ACK;
    public static final byte MESSAGE_ERROR = ReSyncCoreProtocolContract.MESSAGE_ERROR;
    public static final byte MESSAGE_CHANNEL_REGISTRY = ReSyncCoreProtocolContract.MESSAGE_CHANNEL_REGISTRY;
    public static final byte MESSAGE_PROTOCOL_ENVELOPE = ReSyncCoreProtocolContract.MESSAGE_PROTOCOL_ENVELOPE;
    public static final int CHANNEL_REGISTRY_VERSION = ReSyncCoreProtocolContract.CHANNEL_REGISTRY_VERSION;

    public static final byte FLOW_PACKET_REQUEST = ReSyncCoreProtocolContract.FLOW_PACKET_REQUEST;
    public static final byte FLOW_PACKET_DATA = ReSyncCoreProtocolContract.FLOW_PACKET_DATA;
    public static final byte FLOW_PACKET_SAVE = ReSyncCoreProtocolContract.FLOW_PACKET_SAVE;
    public static final byte FLOW_PACKET_GUI_STATE = ReSyncCoreProtocolContract.FLOW_PACKET_GUI_STATE;
    public static final byte FLOW_PACKET_ERROR = ReSyncCoreProtocolContract.FLOW_PACKET_ERROR;
    public static final byte FLOW_PACKET_TRIGGER_UPDATE = ReSyncCoreProtocolContract.FLOW_PACKET_TRIGGER_UPDATE;
    public static final int FLOW_TRIGGER_UPDATE_FORMAT_VERSION = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_FORMAT_VERSION;
    public static final String FLOW_TRIGGER_UPDATE_FORMAT_VERSION_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_FORMAT_VERSION_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_AUTHORITY_EPOCH_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_AUTHORITY_EPOCH_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_REQUEST_ID_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_REQUEST_ID_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_BINDINGS_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_BINDINGS_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_EPOCH_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_EPOCH_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_HASH_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_HASH_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_BINDING_HASH_DOMAIN = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_HASH_DOMAIN;
    public static final String FLOW_TRIGGER_UPDATE_BINDING_STATE_CAPABILITY = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_CAPABILITY;
    public static final String FLOW_TRIGGER_UPDATE_BINDING_STATE_EPOCH_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_EPOCH_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_BINDING_STATE_HASH_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_HASH_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_RESULT_ACCEPTED_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_ACCEPTED_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_RESULT_STALE_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_STALE_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_RESULT_DURABLE_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_DURABLE_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_RESULT_RUNTIME_READY_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_RUNTIME_READY_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_RESULT_FINALIZATION_ERROR_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_FINALIZATION_ERROR_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_RESULT_EPOCH_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_EPOCH_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_RESULT_HASH_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_HASH_FIELD;
    public static final String FLOW_TRIGGER_UPDATE_RESULT_BINDINGS_FIELD = ReSyncCoreProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_BINDINGS_FIELD;
    public static final byte FLOW_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.FLOW_PACKET_SAVE_ACK;
    public static final byte FLOW_PACKET_DELETE = ReSyncCoreProtocolContract.FLOW_PACKET_DELETE;
    public static final byte FLOW_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.FLOW_PACKET_LIST_REQUEST;
    public static final byte FLOW_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.FLOW_PACKET_LIST_RESPONSE;
    public static final byte FLOW_PACKET_NODE_REGISTRY = ReSyncCoreProtocolContract.FLOW_PACKET_NODE_REGISTRY;
    public static final byte FLOW_PACKET_NODE_REGISTRY_REQUEST = ReSyncCoreProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST;
    public static final byte FLOW_PACKET_NODE_REGISTRY_DELTA = ReSyncCoreProtocolContract.FLOW_PACKET_NODE_REGISTRY_DELTA;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION = ReSyncCoreProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_REQUEST = ReSyncCoreProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED = ReSyncCoreProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED = ReSyncCoreProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_CACHE_REJECTED = ReSyncCoreProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_REJECTED;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_CHUNK = ReSyncCoreProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CHUNK;
    public static final byte FLOW_PACKET_OPTION_CATALOG_REQUEST = ReSyncCoreProtocolContract.FLOW_PACKET_OPTION_CATALOG_REQUEST;
    public static final byte FLOW_PACKET_OPTION_CATALOG = ReSyncCoreProtocolContract.FLOW_PACKET_OPTION_CATALOG;
    public static final byte FLOW_PACKET_PLACEHOLDER_PREVIEW_REQUEST = ReSyncCoreProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW_REQUEST;
    public static final byte FLOW_PACKET_PLACEHOLDER_PREVIEW = ReSyncCoreProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW;
    public static final byte FLOW_PACKET_TRACE_TOGGLE = ReSyncCoreProtocolContract.FLOW_PACKET_TRACE_TOGGLE;
    public static final byte FLOW_PACKET_TRACE_SNAPSHOT = ReSyncCoreProtocolContract.FLOW_PACKET_TRACE_SNAPSHOT;
    public static final byte FLOW_PACKET_TRACE_EVENT = ReSyncCoreProtocolContract.FLOW_PACKET_TRACE_EVENT;
    public static final byte FLOW_PACKET_TRACE_CLEAR = ReSyncCoreProtocolContract.FLOW_PACKET_TRACE_CLEAR;
    public static final byte FLOW_PACKET_JOB = ReSyncCoreProtocolContract.FLOW_PACKET_JOB;
    public static final byte FLOW_PACKET_JOB_SNAPSHOT_REQUEST = ReSyncCoreProtocolContract.FLOW_PACKET_JOB_SNAPSHOT_REQUEST;
    public static final byte FLOW_PACKET_DEBUG_COMMAND = ReSyncCoreProtocolContract.FLOW_PACKET_DEBUG_COMMAND;
    public static final byte FLOW_PACKET_DEBUG_EVENT = ReSyncCoreProtocolContract.FLOW_PACKET_DEBUG_EVENT;
    public static final byte FLOW_PACKET_FUNCTION_TEST_REQUEST = ReSyncCoreProtocolContract.FLOW_PACKET_FUNCTION_TEST_REQUEST;
    public static final byte FLOW_PACKET_FUNCTION_TEST_RESULT = ReSyncCoreProtocolContract.FLOW_PACKET_FUNCTION_TEST_RESULT;
    public static final byte FLOW_PACKET_EDIT_TARGET_STATE = ReSyncCoreProtocolContract.FLOW_PACKET_EDIT_TARGET_STATE;
    public static final byte FLOW_PACKET_PRESENCE_UPDATE = ReSyncCoreProtocolContract.FLOW_PACKET_PRESENCE_UPDATE;
    public static final byte FLOW_PACKET_PRESENCE_SNAPSHOT = ReSyncCoreProtocolContract.FLOW_PACKET_PRESENCE_SNAPSHOT;
    public static final byte FLOW_PACKET_RESOURCE_CHANGED = ReSyncCoreProtocolContract.FLOW_PACKET_RESOURCE_CHANGED;
    public static final byte FLOW_PACKET_RESOURCE_DELETED = ReSyncCoreProtocolContract.FLOW_PACKET_RESOURCE_DELETED;
    public static final byte FLOW_PACKET_WORKSPACE_JOIN = ReSyncCoreProtocolContract.FLOW_PACKET_WORKSPACE_JOIN;
    public static final byte FLOW_PACKET_WORKSPACE_LEAVE = ReSyncCoreProtocolContract.FLOW_PACKET_WORKSPACE_LEAVE;
    public static final byte FLOW_PACKET_WORKSPACE_SNAPSHOT = ReSyncCoreProtocolContract.FLOW_PACKET_WORKSPACE_SNAPSHOT;
    public static final byte FLOW_PACKET_WORKSPACE_OPERATION = ReSyncCoreProtocolContract.FLOW_PACKET_WORKSPACE_OPERATION;
    public static final byte FLOW_PACKET_WORKSPACE_AWARENESS = ReSyncCoreProtocolContract.FLOW_PACKET_WORKSPACE_AWARENESS;
    public static final byte FLOW_PACKET_WORKSPACE_RESYNC = ReSyncCoreProtocolContract.FLOW_PACKET_WORKSPACE_RESYNC;
    public static final byte FLOW_PACKET_COLLABORATION_CHAT = ReSyncCoreProtocolContract.FLOW_PACKET_COLLABORATION_CHAT;
    public static final byte FLOW_PACKET_RESOURCE_ACTIVATION = ReSyncCoreProtocolContract.FLOW_PACKET_RESOURCE_ACTIVATION;
    public static final byte FLOW_PACKET_RESOURCE_ACTIVATION_RESULT = ReSyncCoreProtocolContract.FLOW_PACKET_RESOURCE_ACTIVATION_RESULT;
    public static final byte FLOW_PACKET_QUICK_EDIT_OPEN = ReSyncCoreProtocolContract.FLOW_PACKET_QUICK_EDIT_OPEN;
    public static final byte FLOW_PACKET_QUICK_EDIT_APPLY = ReSyncCoreProtocolContract.FLOW_PACKET_QUICK_EDIT_APPLY;
    public static final byte FLOW_PACKET_QUICK_EDIT_RESULT = ReSyncCoreProtocolContract.FLOW_PACKET_QUICK_EDIT_RESULT;
    public static final byte FLOW_PACKET_OPEN_CUSTOM_CONTENT = ReSyncCoreProtocolContract.FLOW_PACKET_OPEN_CUSTOM_CONTENT;

    public static final byte CUSTOM_CONTENT_PACKET_DATA = ReSyncCoreProtocolContract.CUSTOM_CONTENT_PACKET_DATA;
    public static final byte CUSTOM_CONTENT_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.CUSTOM_CONTENT_PACKET_LIST_RESPONSE;
    public static final byte CUSTOM_CONTENT_PACKET_SAVE = ReSyncCoreProtocolContract.CUSTOM_CONTENT_PACKET_SAVE;
    public static final byte CUSTOM_CONTENT_PACKET_DELETE = ReSyncCoreProtocolContract.CUSTOM_CONTENT_PACKET_DELETE;
    public static final byte CUSTOM_CONTENT_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.CUSTOM_CONTENT_PACKET_SAVE_ACK;
    public static final byte CUSTOM_CONTENT_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.CUSTOM_CONTENT_PACKET_LIST_REQUEST;
    public static final byte PERMISSION_PROFILE_PACKET_REQUEST = ReSyncCoreProtocolContract.PERMISSION_PROFILE_PACKET_REQUEST;
    public static final byte PERMISSION_PROFILE_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.PERMISSION_PROFILE_PACKET_LIST_REQUEST;
    public static final byte PERMISSION_PROFILE_PACKET_DATA = ReSyncCoreProtocolContract.PERMISSION_PROFILE_PACKET_DATA;
    public static final byte PERMISSION_PROFILE_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.PERMISSION_PROFILE_PACKET_LIST_RESPONSE;
    public static final byte PERMISSION_PROFILE_PACKET_SAVE = ReSyncCoreProtocolContract.PERMISSION_PROFILE_PACKET_SAVE;
    public static final byte PERMISSION_PROFILE_PACKET_DELETE = ReSyncCoreProtocolContract.PERMISSION_PROFILE_PACKET_DELETE;
    public static final byte PERMISSION_PROFILE_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.PERMISSION_PROFILE_PACKET_SAVE_ACK;
    public static final byte CHAT_PACKET_REQUEST = ReSyncCoreProtocolContract.CHAT_PACKET_REQUEST;
    public static final byte CHAT_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.CHAT_PACKET_LIST_REQUEST;
    public static final byte CHAT_PACKET_DATA = ReSyncCoreProtocolContract.CHAT_PACKET_DATA;
    public static final byte CHAT_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.CHAT_PACKET_LIST_RESPONSE;
    public static final byte CHAT_PACKET_SAVE = ReSyncCoreProtocolContract.CHAT_PACKET_SAVE;
    public static final byte CHAT_PACKET_DELETE = ReSyncCoreProtocolContract.CHAT_PACKET_DELETE;
    public static final byte CHAT_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.CHAT_PACKET_SAVE_ACK;
    public static final byte MOTD_PROFILE_PACKET_REQUEST = ReSyncCoreProtocolContract.MOTD_PROFILE_PACKET_REQUEST;
    public static final byte MOTD_PROFILE_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.MOTD_PROFILE_PACKET_LIST_REQUEST;
    public static final byte MOTD_PROFILE_PACKET_DATA = ReSyncCoreProtocolContract.MOTD_PROFILE_PACKET_DATA;
    public static final byte MOTD_PROFILE_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.MOTD_PROFILE_PACKET_LIST_RESPONSE;
    public static final byte MOTD_PROFILE_PACKET_SAVE = ReSyncCoreProtocolContract.MOTD_PROFILE_PACKET_SAVE;
    public static final byte MOTD_PROFILE_PACKET_DELETE = ReSyncCoreProtocolContract.MOTD_PROFILE_PACKET_DELETE;
    public static final byte MOTD_PROFILE_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.MOTD_PROFILE_PACKET_SAVE_ACK;
    public static final byte MESSAGE_RULE_PACKET_REQUEST = ReSyncCoreProtocolContract.MESSAGE_RULE_PACKET_REQUEST;
    public static final byte MESSAGE_RULE_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.MESSAGE_RULE_PACKET_LIST_REQUEST;
    public static final byte MESSAGE_RULE_PACKET_DATA = ReSyncCoreProtocolContract.MESSAGE_RULE_PACKET_DATA;
    public static final byte MESSAGE_RULE_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.MESSAGE_RULE_PACKET_LIST_RESPONSE;
    public static final byte MESSAGE_RULE_PACKET_SAVE = ReSyncCoreProtocolContract.MESSAGE_RULE_PACKET_SAVE;
    public static final byte MESSAGE_RULE_PACKET_DELETE = ReSyncCoreProtocolContract.MESSAGE_RULE_PACKET_DELETE;
    public static final byte MESSAGE_RULE_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.MESSAGE_RULE_PACKET_SAVE_ACK;
    public static final byte RECIPE_DEFINITION_PACKET_REQUEST = ReSyncCoreProtocolContract.RECIPE_DEFINITION_PACKET_REQUEST;
    public static final byte RECIPE_DEFINITION_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.RECIPE_DEFINITION_PACKET_LIST_REQUEST;
    public static final byte RECIPE_DEFINITION_PACKET_DATA = ReSyncCoreProtocolContract.RECIPE_DEFINITION_PACKET_DATA;
    public static final byte RECIPE_DEFINITION_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.RECIPE_DEFINITION_PACKET_LIST_RESPONSE;
    public static final byte RECIPE_DEFINITION_PACKET_SAVE = ReSyncCoreProtocolContract.RECIPE_DEFINITION_PACKET_SAVE;
    public static final byte RECIPE_DEFINITION_PACKET_DELETE = ReSyncCoreProtocolContract.RECIPE_DEFINITION_PACKET_DELETE;
    public static final byte RECIPE_DEFINITION_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.RECIPE_DEFINITION_PACKET_SAVE_ACK;
    public static final byte TEXT_TEMPLATE_PACKET_REQUEST = ReSyncCoreProtocolContract.TEXT_TEMPLATE_PACKET_REQUEST;
    public static final byte TEXT_TEMPLATE_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.TEXT_TEMPLATE_PACKET_LIST_REQUEST;
    public static final byte TEXT_TEMPLATE_PACKET_DATA = ReSyncCoreProtocolContract.TEXT_TEMPLATE_PACKET_DATA;
    public static final byte TEXT_TEMPLATE_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.TEXT_TEMPLATE_PACKET_LIST_RESPONSE;
    public static final byte TEXT_TEMPLATE_PACKET_SAVE = ReSyncCoreProtocolContract.TEXT_TEMPLATE_PACKET_SAVE;
    public static final byte TEXT_TEMPLATE_PACKET_DELETE = ReSyncCoreProtocolContract.TEXT_TEMPLATE_PACKET_DELETE;
    public static final byte TEXT_TEMPLATE_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.TEXT_TEMPLATE_PACKET_SAVE_ACK;
    public static final byte ADVANCEMENT_TREE_PACKET_REQUEST = ReSyncCoreProtocolContract.ADVANCEMENT_TREE_PACKET_REQUEST;
    public static final byte ADVANCEMENT_TREE_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.ADVANCEMENT_TREE_PACKET_LIST_REQUEST;
    public static final byte ADVANCEMENT_TREE_PACKET_DATA = ReSyncCoreProtocolContract.ADVANCEMENT_TREE_PACKET_DATA;
    public static final byte ADVANCEMENT_TREE_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.ADVANCEMENT_TREE_PACKET_LIST_RESPONSE;
    public static final byte ADVANCEMENT_TREE_PACKET_SAVE = ReSyncCoreProtocolContract.ADVANCEMENT_TREE_PACKET_SAVE;
    public static final byte ADVANCEMENT_TREE_PACKET_DELETE = ReSyncCoreProtocolContract.ADVANCEMENT_TREE_PACKET_DELETE;
    public static final byte ADVANCEMENT_TREE_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.ADVANCEMENT_TREE_PACKET_SAVE_ACK;
    public static final byte DIALOG_PACKET_REQUEST = ReSyncCoreProtocolContract.DIALOG_PACKET_REQUEST;
    public static final byte DIALOG_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.DIALOG_PACKET_LIST_REQUEST;
    public static final byte DIALOG_PACKET_DATA = ReSyncCoreProtocolContract.DIALOG_PACKET_DATA;
    public static final byte DIALOG_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.DIALOG_PACKET_LIST_RESPONSE;
    public static final byte DIALOG_PACKET_SAVE = ReSyncCoreProtocolContract.DIALOG_PACKET_SAVE;
    public static final byte DIALOG_PACKET_DELETE = ReSyncCoreProtocolContract.DIALOG_PACKET_DELETE;
    public static final byte DIALOG_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.DIALOG_PACKET_SAVE_ACK;
    public static final byte TRADE_PROFILE_PACKET_REQUEST = ReSyncCoreProtocolContract.TRADE_PROFILE_PACKET_REQUEST;
    public static final byte TRADE_PROFILE_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.TRADE_PROFILE_PACKET_LIST_REQUEST;
    public static final byte TRADE_PROFILE_PACKET_DATA = ReSyncCoreProtocolContract.TRADE_PROFILE_PACKET_DATA;
    public static final byte TRADE_PROFILE_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.TRADE_PROFILE_PACKET_LIST_RESPONSE;
    public static final byte TRADE_PROFILE_PACKET_SAVE = ReSyncCoreProtocolContract.TRADE_PROFILE_PACKET_SAVE;
    public static final byte TRADE_PROFILE_PACKET_DELETE = ReSyncCoreProtocolContract.TRADE_PROFILE_PACKET_DELETE;
    public static final byte TRADE_PROFILE_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.TRADE_PROFILE_PACKET_SAVE_ACK;
    public static final byte NPC_DEFINITION_PACKET_REQUEST = ReSyncCoreProtocolContract.NPC_DEFINITION_PACKET_REQUEST;
    public static final byte NPC_DEFINITION_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.NPC_DEFINITION_PACKET_LIST_REQUEST;
    public static final byte NPC_DEFINITION_PACKET_DATA = ReSyncCoreProtocolContract.NPC_DEFINITION_PACKET_DATA;
    public static final byte NPC_DEFINITION_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.NPC_DEFINITION_PACKET_LIST_RESPONSE;
    public static final byte NPC_DEFINITION_PACKET_SAVE = ReSyncCoreProtocolContract.NPC_DEFINITION_PACKET_SAVE;
    public static final byte NPC_DEFINITION_PACKET_DELETE = ReSyncCoreProtocolContract.NPC_DEFINITION_PACKET_DELETE;
    public static final byte NPC_DEFINITION_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.NPC_DEFINITION_PACKET_SAVE_ACK;
    public static final byte LOOT_TABLE_PACKET_REQUEST = ReSyncCoreProtocolContract.LOOT_TABLE_PACKET_REQUEST;
    public static final byte LOOT_TABLE_PACKET_LIST_REQUEST = ReSyncCoreProtocolContract.LOOT_TABLE_PACKET_LIST_REQUEST;
    public static final byte LOOT_TABLE_PACKET_DATA = ReSyncCoreProtocolContract.LOOT_TABLE_PACKET_DATA;
    public static final byte LOOT_TABLE_PACKET_LIST_RESPONSE = ReSyncCoreProtocolContract.LOOT_TABLE_PACKET_LIST_RESPONSE;
    public static final byte LOOT_TABLE_PACKET_SAVE = ReSyncCoreProtocolContract.LOOT_TABLE_PACKET_SAVE;
    public static final byte LOOT_TABLE_PACKET_DELETE = ReSyncCoreProtocolContract.LOOT_TABLE_PACKET_DELETE;
    public static final byte LOOT_TABLE_PACKET_SAVE_ACK = ReSyncCoreProtocolContract.LOOT_TABLE_PACKET_SAVE_ACK;
    public static final byte MESSAGE_LOG_PACKET_REQUEST = ReSyncCoreProtocolContract.MESSAGE_LOG_PACKET_REQUEST;
    public static final byte MESSAGE_LOG_PACKET_RESPONSE = ReSyncCoreProtocolContract.MESSAGE_LOG_PACKET_RESPONSE;
    public static final byte WORLDGEN_PACKET_STATUS = ReSyncCoreProtocolContract.WORLDGEN_PACKET_STATUS;
    public static final byte WORLDGEN_PACKET_JOB = ReSyncCoreProtocolContract.WORLDGEN_PACKET_JOB;
    public static final String JOB_PENDING = ReSyncCoreProtocolContract.JOB_PENDING;
    public static final String JOB_RUNNING = ReSyncCoreProtocolContract.JOB_RUNNING;
    public static final String JOB_SUCCEEDED = ReSyncCoreProtocolContract.JOB_SUCCEEDED;
    public static final String JOB_FAILED = ReSyncCoreProtocolContract.JOB_FAILED;
    public static final String JOB_CANCELLED = ReSyncCoreProtocolContract.JOB_CANCELLED;
    public static final String DTO_JOB_ID = ReSyncCoreProtocolContract.DTO_JOB_ID;
    public static final String DTO_OPERATION_ID = ReSyncCoreProtocolContract.DTO_OPERATION_ID;
    public static final String DTO_ACTION = ReSyncCoreProtocolContract.DTO_ACTION;
    public static final String DTO_STATUS = ReSyncCoreProtocolContract.DTO_STATUS;
    public static final String DTO_MESSAGE = ReSyncCoreProtocolContract.DTO_MESSAGE;
    public static final String DTO_ERROR_TEXT = ReSyncCoreProtocolContract.DTO_ERROR_TEXT;
    public static final String DTO_RESULT = ReSyncCoreProtocolContract.DTO_RESULT;

    public static final ResourceContract[] RESOURCE_CONTRACTS = Arrays.stream(
        ReSyncCoreProtocolContract.RESOURCE_CONTRACTS)
        .map(contract -> new ResourceContract(contract.typeId(), contract.displayName(), contract.defaultFolder(),
            contract.jsonStorageSupported(), contract.flowPackets() == null ? null : new ResourceFlowPackets(
                contract.flowPackets().request(), contract.flowPackets().listRequest(), contract.flowPackets().data(),
                contract.flowPackets().list(), contract.flowPackets().save(), contract.flowPackets().delete(),
                contract.flowPackets().saveAck())))
        .toArray(ResourceContract[]::new);

    public static ResourceContract resource(String typeId) {
        for (ResourceContract resource : RESOURCE_CONTRACTS) {
            if (resource != null && Objects.equals(resource.typeId(), typeId)) {
                return resource;
            }
        }
        return null;
    }

    public record ResourceFlowPackets(byte request, byte listRequest, byte data, byte list, byte save, byte delete,
                                      byte saveAck) {
    }

    public record ResourceContract(String typeId, String displayName, String defaultFolder,
                                   boolean jsonStorageSupported, ResourceFlowPackets flowPackets) {
    }

    public static DialogResource dialogResource(JsonObject json, String fallbackId) {
        return new DialogResource(json, fallbackId);
    }

    public static final class DialogResource {
        private final JsonObject json;
        private final String fallbackId;

        private DialogResource(JsonObject json, String fallbackId) {
            this.json = json != null ? json : new JsonObject();
            this.fallbackId = fallbackId == null || fallbackId.isBlank() ? "dialog" : fallbackId;
        }

        public JsonObject json() {
            return json;
        }

        public void applyDefaults(String defaultFolder) {
            if (!json.has("id") || text("id", "").isBlank()) {
                json.addProperty("id", fallbackId);
            }
            if (!json.has("displayName")) {
                json.addProperty("displayName", text("id", fallbackId));
            }
            if (!json.has("folder")) {
                json.addProperty("folder", defaultFolder == null ? "Content/Dialogs" : defaultFolder);
            }
            if (!json.has("enabled")) {
                json.addProperty("enabled", true);
            }
            if (!json.has("type")) {
                json.addProperty("type", "minecraft:multi_action");
            }
            if (!json.has("title")) {
                json.addProperty("title", displayName());
            }
            ensureArray("body");
            ensureArray("inputs");
            ensureArray("actions");
            if (!json.has("can_close_with_escape")) {
                json.addProperty("can_close_with_escape", true);
            }
            if (!json.has("after_action")) {
                json.addProperty("after_action", "close");
            }
            if (!json.has("columns")) {
                json.addProperty("columns", 1);
            }
        }

        public String displayName() {
            return text("displayName", text("id", fallbackId));
        }

        public String title() {
            return text("title", displayName());
        }

        public String externalTitle() {
            return text("external_title", displayName());
        }

        public String type() {
            return text("type", "minecraft:multi_action");
        }

        public boolean canCloseWithEscape() {
            return bool("can_close_with_escape", true);
        }

        public boolean pause() {
            return bool("pause", true);
        }

        public String afterAction() {
            return text("after_action", "close");
        }

        public int columns() {
            return integer("columns", 1);
        }

        public List<JsonObject> body() {
            return objectArray("body");
        }

        public List<JsonObject> inputs() {
            return objectArray("inputs");
        }

        public List<JsonObject> actions() {
            return objectArray("actions");
        }

        private void ensureArray(String key) {
            if (!json.has(key) || !json.get(key).isJsonArray()) {
                json.add(key, new JsonArray());
            }
        }

        private List<JsonObject> objectArray(String key) {
            List<JsonObject> values = new ArrayList<>();
            JsonArray array = json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
            for (JsonElement element : array) {
                if (element != null && element.isJsonObject()) {
                    values.add(element.getAsJsonObject());
                }
            }
            return values;
        }

        private String text(String key, String fallback) {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
        }

        private boolean bool(String key, boolean fallback) {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
        }

        private int integer(String key, int fallback) {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
        }
    }

    private ReSyncProtocolContract() {
    }
}
