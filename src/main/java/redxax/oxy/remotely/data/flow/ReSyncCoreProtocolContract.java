package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.util.Arrays;

final class ReSyncCoreProtocolContract {
    private ReSyncCoreProtocolContract() {
    }

    static final String FLOW_TRIGGER_UPDATE_CAPABILITY = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_CAPABILITY;
    static final int PROTOCOL_VERSION = ReSyncProtocolContract.PROTOCOL_VERSION;
    static final int MAX_ENCODED_FRAME_BYTES = ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES;
    static final int MAX_DECOMPRESSED_PAYLOAD_BYTES = ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES;
    static final CatalogVersion GENERIC_RESOURCE_CONTRACT_VERSION = ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION;
    static final ContractRef<CapabilityId> RESOURCE_ACTIVATION_CAPABILITY = ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY;
    static final ContractRef<CapabilityId> RESOURCE_CREATE_PRESENTATION_CAPABILITY =
        ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY;
    static final ContractRef<CapabilityId> OPTION_QUERIES_CAPABILITY = ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY;
    static final String CHANNEL_FLOW = ReSyncProtocolContract.CHANNEL_FLOW;
    static final String CHANNEL_PLAYER_TRACKING = ReSyncProtocolContract.CHANNEL_PLAYER_TRACKING;
    static final String CHANNEL_WORLD_MANAGEMENT = ReSyncProtocolContract.CHANNEL_WORLD_MANAGEMENT;
    static final String CHANNEL_WORLDGEN = ReSyncProtocolContract.CHANNEL_WORLDGEN;
    static final short CHANNEL_CONTROL_ID = ReSyncProtocolContract.CHANNEL_CONTROL_ID;
    static final short CHANNEL_FLOW_ID = ReSyncProtocolContract.CHANNEL_FLOW_ID;
    static final short CHANNEL_PLAYER_TRACKING_ID = ReSyncProtocolContract.CHANNEL_PLAYER_TRACKING_ID;
    static final short CHANNEL_WORLD_MANAGEMENT_ID = ReSyncProtocolContract.CHANNEL_WORLD_MANAGEMENT_ID;
    static final short CHANNEL_WORLDGEN_ID = ReSyncProtocolContract.CHANNEL_WORLDGEN_ID;
    static final byte MESSAGE_HANDSHAKE_REQUEST = ReSyncProtocolContract.MESSAGE_HANDSHAKE_REQUEST;
    static final byte MESSAGE_HANDSHAKE_RESPONSE = ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE;
    static final byte MESSAGE_SUBSCRIBE = ReSyncProtocolContract.MESSAGE_SUBSCRIBE;
    static final byte MESSAGE_UNSUBSCRIBE = ReSyncProtocolContract.MESSAGE_UNSUBSCRIBE;
    static final byte MESSAGE_DATA = ReSyncProtocolContract.MESSAGE_DATA;
    static final byte MESSAGE_HEARTBEAT = ReSyncProtocolContract.MESSAGE_HEARTBEAT;
    static final byte MESSAGE_ACK = ReSyncProtocolContract.MESSAGE_ACK;
    static final byte MESSAGE_ERROR = ReSyncProtocolContract.MESSAGE_ERROR;
    static final byte MESSAGE_CHANNEL_REGISTRY = ReSyncProtocolContract.MESSAGE_CHANNEL_REGISTRY;
    static final byte MESSAGE_PROTOCOL_ENVELOPE = ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE;
    static final int CHANNEL_REGISTRY_VERSION = ReSyncProtocolContract.CHANNEL_REGISTRY_VERSION;
    static final byte FLOW_PACKET_REQUEST = ReSyncProtocolContract.FLOW_PACKET_REQUEST;
    static final byte FLOW_PACKET_DATA = ReSyncProtocolContract.FLOW_PACKET_DATA;
    static final byte FLOW_PACKET_SAVE = ReSyncProtocolContract.FLOW_PACKET_SAVE;
    static final byte FLOW_PACKET_GUI_STATE = ReSyncProtocolContract.FLOW_PACKET_GUI_STATE;
    static final byte FLOW_PACKET_ERROR = ReSyncProtocolContract.FLOW_PACKET_ERROR;
    static final byte FLOW_PACKET_TRIGGER_UPDATE = ReSyncProtocolContract.FLOW_PACKET_TRIGGER_UPDATE;
    static final int FLOW_TRIGGER_UPDATE_FORMAT_VERSION = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_FORMAT_VERSION;
    static final String FLOW_TRIGGER_UPDATE_FORMAT_VERSION_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_FORMAT_VERSION_FIELD;
    static final String FLOW_TRIGGER_UPDATE_AUTHORITY_EPOCH_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_AUTHORITY_EPOCH_FIELD;
    static final String FLOW_TRIGGER_UPDATE_REQUEST_ID_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_REQUEST_ID_FIELD;
    static final String FLOW_TRIGGER_UPDATE_BINDINGS_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDINGS_FIELD;
    static final String FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_EPOCH_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_EPOCH_FIELD;
    static final String FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_HASH_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_HASH_FIELD;
    static final String FLOW_TRIGGER_UPDATE_BINDING_HASH_DOMAIN = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_HASH_DOMAIN;
    static final String FLOW_TRIGGER_UPDATE_BINDING_STATE_CAPABILITY = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_CAPABILITY;
    static final String FLOW_TRIGGER_UPDATE_BINDING_STATE_EPOCH_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_EPOCH_FIELD;
    static final String FLOW_TRIGGER_UPDATE_BINDING_STATE_HASH_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_HASH_FIELD;
    static final String FLOW_TRIGGER_UPDATE_RESULT_ACCEPTED_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_ACCEPTED_FIELD;
    static final String FLOW_TRIGGER_UPDATE_RESULT_STALE_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_STALE_FIELD;
    static final String FLOW_TRIGGER_UPDATE_RESULT_DURABLE_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_DURABLE_FIELD;
    static final String FLOW_TRIGGER_UPDATE_RESULT_RUNTIME_READY_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_RUNTIME_READY_FIELD;
    static final String FLOW_TRIGGER_UPDATE_RESULT_FINALIZATION_ERROR_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_FINALIZATION_ERROR_FIELD;
    static final String FLOW_TRIGGER_UPDATE_RESULT_EPOCH_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_EPOCH_FIELD;
    static final String FLOW_TRIGGER_UPDATE_RESULT_HASH_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_HASH_FIELD;
    static final String FLOW_TRIGGER_UPDATE_RESULT_BINDINGS_FIELD = ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_BINDINGS_FIELD;
    static final byte FLOW_PACKET_SAVE_ACK = ReSyncProtocolContract.FLOW_PACKET_SAVE_ACK;
    static final byte FLOW_PACKET_DELETE = ReSyncProtocolContract.FLOW_PACKET_DELETE;
    static final byte FLOW_PACKET_LIST_REQUEST = ReSyncProtocolContract.FLOW_PACKET_LIST_REQUEST;
    static final byte FLOW_PACKET_LIST_RESPONSE = ReSyncProtocolContract.FLOW_PACKET_LIST_RESPONSE;
    static final byte FLOW_PACKET_NODE_REGISTRY = ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY;
    static final byte FLOW_PACKET_NODE_REGISTRY_REQUEST = ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST;
    static final byte FLOW_PACKET_NODE_REGISTRY_DELTA = ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_DELTA;
    static final byte FLOW_PACKET_CATALOG_PUBLICATION = ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION;
    static final byte FLOW_PACKET_CATALOG_PUBLICATION_REQUEST = ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST;
    static final byte FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED = ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED;
    static final byte FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED = ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED;
    static final byte FLOW_PACKET_CATALOG_PUBLICATION_CACHE_REJECTED = ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_REJECTED;
    static final byte FLOW_PACKET_CATALOG_PUBLICATION_CHUNK = ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CHUNK;
    static final byte FLOW_PACKET_OPTION_CATALOG_REQUEST = ReSyncProtocolContract.FLOW_PACKET_OPTION_CATALOG_REQUEST;
    static final byte FLOW_PACKET_OPTION_CATALOG = ReSyncProtocolContract.FLOW_PACKET_OPTION_CATALOG;
    static final byte FLOW_PACKET_PLACEHOLDER_PREVIEW_REQUEST = ReSyncProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW_REQUEST;
    static final byte FLOW_PACKET_PLACEHOLDER_PREVIEW = ReSyncProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW;
    static final byte FLOW_PACKET_TRACE_TOGGLE = ReSyncProtocolContract.FLOW_PACKET_TRACE_TOGGLE;
    static final byte FLOW_PACKET_TRACE_SNAPSHOT = ReSyncProtocolContract.FLOW_PACKET_TRACE_SNAPSHOT;
    static final byte FLOW_PACKET_TRACE_EVENT = ReSyncProtocolContract.FLOW_PACKET_TRACE_EVENT;
    static final byte FLOW_PACKET_TRACE_CLEAR = ReSyncProtocolContract.FLOW_PACKET_TRACE_CLEAR;
    static final byte FLOW_PACKET_JOB = ReSyncProtocolContract.FLOW_PACKET_JOB;
    static final byte FLOW_PACKET_JOB_SNAPSHOT_REQUEST = ReSyncProtocolContract.FLOW_PACKET_JOB_SNAPSHOT_REQUEST;
    static final byte FLOW_PACKET_DEBUG_COMMAND = ReSyncProtocolContract.FLOW_PACKET_DEBUG_COMMAND;
    static final byte FLOW_PACKET_DEBUG_EVENT = ReSyncProtocolContract.FLOW_PACKET_DEBUG_EVENT;
    static final byte FLOW_PACKET_FUNCTION_TEST_REQUEST = ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_REQUEST;
    static final byte FLOW_PACKET_FUNCTION_TEST_RESULT = ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_RESULT;
    static final byte FLOW_PACKET_EDIT_TARGET_STATE = ReSyncProtocolContract.FLOW_PACKET_EDIT_TARGET_STATE;
    static final byte FLOW_PACKET_PRESENCE_UPDATE = ReSyncProtocolContract.FLOW_PACKET_PRESENCE_UPDATE;
    static final byte FLOW_PACKET_PRESENCE_SNAPSHOT = ReSyncProtocolContract.FLOW_PACKET_PRESENCE_SNAPSHOT;
    static final byte FLOW_PACKET_RESOURCE_CHANGED = ReSyncProtocolContract.FLOW_PACKET_RESOURCE_CHANGED;
    static final byte FLOW_PACKET_RESOURCE_DELETED = ReSyncProtocolContract.FLOW_PACKET_RESOURCE_DELETED;
    static final byte FLOW_PACKET_WORKSPACE_JOIN = ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_JOIN;
    static final byte FLOW_PACKET_WORKSPACE_LEAVE = ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_LEAVE;
    static final byte FLOW_PACKET_WORKSPACE_SNAPSHOT = ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_SNAPSHOT;
    static final byte FLOW_PACKET_WORKSPACE_OPERATION = ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_OPERATION;
    static final byte FLOW_PACKET_WORKSPACE_AWARENESS = ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_AWARENESS;
    static final byte FLOW_PACKET_WORKSPACE_RESYNC = ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_RESYNC;
    static final byte FLOW_PACKET_COLLABORATION_CHAT = ReSyncProtocolContract.FLOW_PACKET_COLLABORATION_CHAT;
    static final byte FLOW_PACKET_RESOURCE_ACTIVATION = ReSyncProtocolContract.FLOW_PACKET_RESOURCE_ACTIVATION;
    static final byte FLOW_PACKET_RESOURCE_ACTIVATION_RESULT = ReSyncProtocolContract.FLOW_PACKET_RESOURCE_ACTIVATION_RESULT;
    static final byte FLOW_PACKET_QUICK_EDIT_OPEN = ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_OPEN;
    static final byte FLOW_PACKET_QUICK_EDIT_APPLY = ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_APPLY;
    static final byte FLOW_PACKET_QUICK_EDIT_RESULT = ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_RESULT;
    static final byte FLOW_PACKET_OPEN_CUSTOM_CONTENT = ReSyncProtocolContract.FLOW_PACKET_OPEN_CUSTOM_CONTENT;
    static final byte CUSTOM_CONTENT_PACKET_DATA = ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_DATA;
    static final byte CUSTOM_CONTENT_PACKET_LIST_RESPONSE = ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_LIST_RESPONSE;
    static final byte CUSTOM_CONTENT_PACKET_SAVE = ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_SAVE;
    static final byte CUSTOM_CONTENT_PACKET_DELETE = ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_DELETE;
    static final byte CUSTOM_CONTENT_PACKET_SAVE_ACK = ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_SAVE_ACK;
    static final byte CUSTOM_CONTENT_PACKET_LIST_REQUEST = ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_LIST_REQUEST;
    static final byte PERMISSION_PROFILE_PACKET_REQUEST = ReSyncProtocolContract.PERMISSION_PROFILE_PACKET_REQUEST;
    static final byte PERMISSION_PROFILE_PACKET_LIST_REQUEST = ReSyncProtocolContract.PERMISSION_PROFILE_PACKET_LIST_REQUEST;
    static final byte PERMISSION_PROFILE_PACKET_DATA = ReSyncProtocolContract.PERMISSION_PROFILE_PACKET_DATA;
    static final byte PERMISSION_PROFILE_PACKET_LIST_RESPONSE = ReSyncProtocolContract.PERMISSION_PROFILE_PACKET_LIST_RESPONSE;
    static final byte PERMISSION_PROFILE_PACKET_SAVE = ReSyncProtocolContract.PERMISSION_PROFILE_PACKET_SAVE;
    static final byte PERMISSION_PROFILE_PACKET_DELETE = ReSyncProtocolContract.PERMISSION_PROFILE_PACKET_DELETE;
    static final byte PERMISSION_PROFILE_PACKET_SAVE_ACK = ReSyncProtocolContract.PERMISSION_PROFILE_PACKET_SAVE_ACK;
    static final byte CHAT_PACKET_REQUEST = ReSyncProtocolContract.CHAT_PACKET_REQUEST;
    static final byte CHAT_PACKET_LIST_REQUEST = ReSyncProtocolContract.CHAT_PACKET_LIST_REQUEST;
    static final byte CHAT_PACKET_DATA = ReSyncProtocolContract.CHAT_PACKET_DATA;
    static final byte CHAT_PACKET_LIST_RESPONSE = ReSyncProtocolContract.CHAT_PACKET_LIST_RESPONSE;
    static final byte CHAT_PACKET_SAVE = ReSyncProtocolContract.CHAT_PACKET_SAVE;
    static final byte CHAT_PACKET_DELETE = ReSyncProtocolContract.CHAT_PACKET_DELETE;
    static final byte CHAT_PACKET_SAVE_ACK = ReSyncProtocolContract.CHAT_PACKET_SAVE_ACK;
    static final byte MOTD_PROFILE_PACKET_REQUEST = ReSyncProtocolContract.MOTD_PROFILE_PACKET_REQUEST;
    static final byte MOTD_PROFILE_PACKET_LIST_REQUEST = ReSyncProtocolContract.MOTD_PROFILE_PACKET_LIST_REQUEST;
    static final byte MOTD_PROFILE_PACKET_DATA = ReSyncProtocolContract.MOTD_PROFILE_PACKET_DATA;
    static final byte MOTD_PROFILE_PACKET_LIST_RESPONSE = ReSyncProtocolContract.MOTD_PROFILE_PACKET_LIST_RESPONSE;
    static final byte MOTD_PROFILE_PACKET_SAVE = ReSyncProtocolContract.MOTD_PROFILE_PACKET_SAVE;
    static final byte MOTD_PROFILE_PACKET_DELETE = ReSyncProtocolContract.MOTD_PROFILE_PACKET_DELETE;
    static final byte MOTD_PROFILE_PACKET_SAVE_ACK = ReSyncProtocolContract.MOTD_PROFILE_PACKET_SAVE_ACK;
    static final byte MESSAGE_RULE_PACKET_REQUEST = ReSyncProtocolContract.MESSAGE_RULE_PACKET_REQUEST;
    static final byte MESSAGE_RULE_PACKET_LIST_REQUEST = ReSyncProtocolContract.MESSAGE_RULE_PACKET_LIST_REQUEST;
    static final byte MESSAGE_RULE_PACKET_DATA = ReSyncProtocolContract.MESSAGE_RULE_PACKET_DATA;
    static final byte MESSAGE_RULE_PACKET_LIST_RESPONSE = ReSyncProtocolContract.MESSAGE_RULE_PACKET_LIST_RESPONSE;
    static final byte MESSAGE_RULE_PACKET_SAVE = ReSyncProtocolContract.MESSAGE_RULE_PACKET_SAVE;
    static final byte MESSAGE_RULE_PACKET_DELETE = ReSyncProtocolContract.MESSAGE_RULE_PACKET_DELETE;
    static final byte MESSAGE_RULE_PACKET_SAVE_ACK = ReSyncProtocolContract.MESSAGE_RULE_PACKET_SAVE_ACK;
    static final byte RECIPE_DEFINITION_PACKET_REQUEST = ReSyncProtocolContract.RECIPE_DEFINITION_PACKET_REQUEST;
    static final byte RECIPE_DEFINITION_PACKET_LIST_REQUEST = ReSyncProtocolContract.RECIPE_DEFINITION_PACKET_LIST_REQUEST;
    static final byte RECIPE_DEFINITION_PACKET_DATA = ReSyncProtocolContract.RECIPE_DEFINITION_PACKET_DATA;
    static final byte RECIPE_DEFINITION_PACKET_LIST_RESPONSE = ReSyncProtocolContract.RECIPE_DEFINITION_PACKET_LIST_RESPONSE;
    static final byte RECIPE_DEFINITION_PACKET_SAVE = ReSyncProtocolContract.RECIPE_DEFINITION_PACKET_SAVE;
    static final byte RECIPE_DEFINITION_PACKET_DELETE = ReSyncProtocolContract.RECIPE_DEFINITION_PACKET_DELETE;
    static final byte RECIPE_DEFINITION_PACKET_SAVE_ACK = ReSyncProtocolContract.RECIPE_DEFINITION_PACKET_SAVE_ACK;
    static final byte TEXT_TEMPLATE_PACKET_REQUEST = ReSyncProtocolContract.TEXT_TEMPLATE_PACKET_REQUEST;
    static final byte TEXT_TEMPLATE_PACKET_LIST_REQUEST = ReSyncProtocolContract.TEXT_TEMPLATE_PACKET_LIST_REQUEST;
    static final byte TEXT_TEMPLATE_PACKET_DATA = ReSyncProtocolContract.TEXT_TEMPLATE_PACKET_DATA;
    static final byte TEXT_TEMPLATE_PACKET_LIST_RESPONSE = ReSyncProtocolContract.TEXT_TEMPLATE_PACKET_LIST_RESPONSE;
    static final byte TEXT_TEMPLATE_PACKET_SAVE = ReSyncProtocolContract.TEXT_TEMPLATE_PACKET_SAVE;
    static final byte TEXT_TEMPLATE_PACKET_DELETE = ReSyncProtocolContract.TEXT_TEMPLATE_PACKET_DELETE;
    static final byte TEXT_TEMPLATE_PACKET_SAVE_ACK = ReSyncProtocolContract.TEXT_TEMPLATE_PACKET_SAVE_ACK;
    static final byte ADVANCEMENT_TREE_PACKET_REQUEST = ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_REQUEST;
    static final byte ADVANCEMENT_TREE_PACKET_LIST_REQUEST = ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_LIST_REQUEST;
    static final byte ADVANCEMENT_TREE_PACKET_DATA = ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_DATA;
    static final byte ADVANCEMENT_TREE_PACKET_LIST_RESPONSE = ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_LIST_RESPONSE;
    static final byte ADVANCEMENT_TREE_PACKET_SAVE = ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_SAVE;
    static final byte ADVANCEMENT_TREE_PACKET_DELETE = ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_DELETE;
    static final byte ADVANCEMENT_TREE_PACKET_SAVE_ACK = ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_SAVE_ACK;
    static final byte DIALOG_PACKET_REQUEST = ReSyncProtocolContract.DIALOG_PACKET_REQUEST;
    static final byte DIALOG_PACKET_LIST_REQUEST = ReSyncProtocolContract.DIALOG_PACKET_LIST_REQUEST;
    static final byte DIALOG_PACKET_DATA = ReSyncProtocolContract.DIALOG_PACKET_DATA;
    static final byte DIALOG_PACKET_LIST_RESPONSE = ReSyncProtocolContract.DIALOG_PACKET_LIST_RESPONSE;
    static final byte DIALOG_PACKET_SAVE = ReSyncProtocolContract.DIALOG_PACKET_SAVE;
    static final byte DIALOG_PACKET_DELETE = ReSyncProtocolContract.DIALOG_PACKET_DELETE;
    static final byte DIALOG_PACKET_SAVE_ACK = ReSyncProtocolContract.DIALOG_PACKET_SAVE_ACK;
    static final byte TRADE_PROFILE_PACKET_REQUEST = ReSyncProtocolContract.TRADE_PROFILE_PACKET_REQUEST;
    static final byte TRADE_PROFILE_PACKET_LIST_REQUEST = ReSyncProtocolContract.TRADE_PROFILE_PACKET_LIST_REQUEST;
    static final byte TRADE_PROFILE_PACKET_DATA = ReSyncProtocolContract.TRADE_PROFILE_PACKET_DATA;
    static final byte TRADE_PROFILE_PACKET_LIST_RESPONSE = ReSyncProtocolContract.TRADE_PROFILE_PACKET_LIST_RESPONSE;
    static final byte TRADE_PROFILE_PACKET_SAVE = ReSyncProtocolContract.TRADE_PROFILE_PACKET_SAVE;
    static final byte TRADE_PROFILE_PACKET_DELETE = ReSyncProtocolContract.TRADE_PROFILE_PACKET_DELETE;
    static final byte TRADE_PROFILE_PACKET_SAVE_ACK = ReSyncProtocolContract.TRADE_PROFILE_PACKET_SAVE_ACK;
    static final byte NPC_DEFINITION_PACKET_REQUEST = ReSyncProtocolContract.NPC_DEFINITION_PACKET_REQUEST;
    static final byte NPC_DEFINITION_PACKET_LIST_REQUEST = ReSyncProtocolContract.NPC_DEFINITION_PACKET_LIST_REQUEST;
    static final byte NPC_DEFINITION_PACKET_DATA = ReSyncProtocolContract.NPC_DEFINITION_PACKET_DATA;
    static final byte NPC_DEFINITION_PACKET_LIST_RESPONSE = ReSyncProtocolContract.NPC_DEFINITION_PACKET_LIST_RESPONSE;
    static final byte NPC_DEFINITION_PACKET_SAVE = ReSyncProtocolContract.NPC_DEFINITION_PACKET_SAVE;
    static final byte NPC_DEFINITION_PACKET_DELETE = ReSyncProtocolContract.NPC_DEFINITION_PACKET_DELETE;
    static final byte NPC_DEFINITION_PACKET_SAVE_ACK = ReSyncProtocolContract.NPC_DEFINITION_PACKET_SAVE_ACK;
    static final byte LOOT_TABLE_PACKET_REQUEST = ReSyncProtocolContract.LOOT_TABLE_PACKET_REQUEST;
    static final byte LOOT_TABLE_PACKET_LIST_REQUEST = ReSyncProtocolContract.LOOT_TABLE_PACKET_LIST_REQUEST;
    static final byte LOOT_TABLE_PACKET_DATA = ReSyncProtocolContract.LOOT_TABLE_PACKET_DATA;
    static final byte LOOT_TABLE_PACKET_LIST_RESPONSE = ReSyncProtocolContract.LOOT_TABLE_PACKET_LIST_RESPONSE;
    static final byte LOOT_TABLE_PACKET_SAVE = ReSyncProtocolContract.LOOT_TABLE_PACKET_SAVE;
    static final byte LOOT_TABLE_PACKET_DELETE = ReSyncProtocolContract.LOOT_TABLE_PACKET_DELETE;
    static final byte LOOT_TABLE_PACKET_SAVE_ACK = ReSyncProtocolContract.LOOT_TABLE_PACKET_SAVE_ACK;
    static final byte MESSAGE_LOG_PACKET_REQUEST = ReSyncProtocolContract.MESSAGE_LOG_PACKET_REQUEST;
    static final byte MESSAGE_LOG_PACKET_RESPONSE = ReSyncProtocolContract.MESSAGE_LOG_PACKET_RESPONSE;
    static final byte WORLDGEN_PACKET_STATUS = ReSyncProtocolContract.WORLDGEN_PACKET_STATUS;
    static final byte WORLDGEN_PACKET_JOB = ReSyncProtocolContract.WORLDGEN_PACKET_JOB;
    static final String JOB_PENDING = ReSyncProtocolContract.JOB_PENDING;
    static final String JOB_RUNNING = ReSyncProtocolContract.JOB_RUNNING;
    static final String JOB_SUCCEEDED = ReSyncProtocolContract.JOB_SUCCEEDED;
    static final String JOB_FAILED = ReSyncProtocolContract.JOB_FAILED;
    static final String JOB_CANCELLED = ReSyncProtocolContract.JOB_CANCELLED;
    static final String DTO_JOB_ID = ReSyncProtocolContract.DTO_JOB_ID;
    static final String DTO_OPERATION_ID = ReSyncProtocolContract.DTO_OPERATION_ID;
    static final String DTO_ACTION = ReSyncProtocolContract.DTO_ACTION;
    static final String DTO_STATUS = ReSyncProtocolContract.DTO_STATUS;
    static final String DTO_MESSAGE = ReSyncProtocolContract.DTO_MESSAGE;
    static final String DTO_ERROR_TEXT = ReSyncProtocolContract.DTO_ERROR_TEXT;
    static final String DTO_RESULT = ReSyncProtocolContract.DTO_RESULT;

    static final ReSyncProtocolContract.FlowContract FLOW_CONTRACT = ReSyncProtocolContract.FLOW_CONTRACT;

    static final ResourceContractData[] RESOURCE_CONTRACTS = Arrays.stream(
        ReSyncProtocolContract.RESOURCE_CONTRACTS).map(ReSyncCoreProtocolContract::resourceContract)
        .toArray(ResourceContractData[]::new);

    private static ResourceContractData resourceContract(ReSyncProtocolContract.ResourceContract contract) {
        if (contract == null) {
            return null;
        }
        ReSyncProtocolContract.ResourceFlowPackets packets = contract.flowPackets();
        ResourceFlowPacketsData flowPackets = packets == null ? null : new ResourceFlowPacketsData(
            packets.request(), packets.listRequest(), packets.data(), packets.list(), packets.save(),
            packets.delete(), packets.saveAck());
        return new ResourceContractData(contract.typeId(), contract.displayName(), contract.defaultFolder(),
            contract.jsonStorageSupported(), flowPackets);
    }

    record ResourceFlowPacketsData(byte request, byte listRequest, byte data, byte list, byte save,
                                    byte delete, byte saveAck) {
    }

    record ResourceContractData(String typeId, String displayName, String defaultFolder,
                                boolean jsonStorageSupported, ResourceFlowPacketsData flowPackets) {
    }
}
