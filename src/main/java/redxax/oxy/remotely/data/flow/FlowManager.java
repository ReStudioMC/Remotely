package redxax.oxy.remotely.data.flow;

import java.time.Duration;
import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.data.flow.player.PlayerDossier;
import redxax.oxy.remotely.data.flow.player.PlayerTrackingUpdate;
import redxax.oxy.remotely.data.flow.world.WorldChannelMessage;
import redxax.oxy.remotely.data.flow.world.WorldDashboardEntry;
import redxax.oxy.remotely.data.flow.world.WorldInventoryGroup;
import redxax.oxy.remotely.data.flow.world.WorldMapSnapshot;
import redxax.oxy.remotely.data.flow.world.WorldOperationResult;
import redxax.oxy.remotely.data.flow.world.WorldProfileSettings;
import redxax.oxy.remotely.data.flow.world.WorldRegistryEntry;
import redxax.oxy.remotely.data.flow.world.WorldSnapshot;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowJson;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.CustomContentGraphAdapter;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.data.GuiElement;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.data.TabDefinition;
import redxax.oxy.remotely.flow.data.TriggerBinding;
import redxax.oxy.remotely.flow.data.TriggerType;
import redxax.oxy.remotely.flow.data.Visual;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.flow.sync.FlowResourceMetadata;
import redxax.oxy.remotely.flow.ui.AdvancementDesignerScreen;
import redxax.oxy.remotely.flow.ui.DialogDesignerScreen;
import redxax.oxy.remotely.flow.ui.FlowEditorScreen;
import redxax.oxy.remotely.flow.ui.FocusedJsonResourceDesignerScreen;
import redxax.oxy.remotely.flow.ui.GraphEditorScreen;
import redxax.oxy.remotely.flow.ui.GuiEditOverlayState;
import redxax.oxy.remotely.flow.ui.GuiDesignerScreen;
import redxax.oxy.remotely.flow.ui.ResourceDesigners;
import redxax.oxy.remotely.flow.ui.ReSyncProvisioningService;
import redxax.oxy.remotely.flow.ui.ScoreboardDesignerScreen;
import redxax.oxy.remotely.flow.ui.TabDesignerScreen;
import redxax.oxy.remotely.flow.ui.FlowNodeWidget;
import redxax.oxy.remotely.ui.widgets.management.PlayerDataPopup;
import redxax.oxy.remotely.ui.widgets.management.PlayerManagerController;
import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import restudio.rebase.restudio.api.models.MarketplaceModels;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.screens.DesktopWindowsOverlay;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.config.Config;
import restudio.rescreen.util.Notification;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalUuids;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.GraphDraft;
import restudio.resync.flow.cache.GraphResourceCache;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.AuthoringTemplateRequest;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FlowManager {
    public record FunctionReference(String resourceType, String resourceId, String location) {
    }

    private record SaveTicketSettlement(boolean saved, boolean currentAtFinish) {
    }

    public record CreationMetadataIntent(String type, String id, String name, String path, String parentPath,
                                         int sortOrder, boolean folder) {
    }

    public record CreationResult(String type, String id, Object resource) {
    }

    public enum FlowClientActionStatus {
        DELIVERED,
        UNAVAILABLE
    }

    public record FlowClientActionSettlement<T>(FlowClientActionStatus status, T value) {
        public FlowClientActionSettlement {
            status = status == null ? FlowClientActionStatus.UNAVAILABLE : status;
        }

        public boolean delivered() {
            return status == FlowClientActionStatus.DELIVERED;
        }

        public boolean unavailable() {
            return status == FlowClientActionStatus.UNAVAILABLE;
        }
    }

    public enum CreationAdmissionStatus {
        QUEUED,
        REJECTED
    }

    public record CreationAdmission(CreationAdmissionStatus status, String message,
                                    Async<Boolean> durable) {
        public CreationAdmission {
            status = status == null ? CreationAdmissionStatus.REJECTED : status;
            message = message == null ? "" : message;
            durable = durable == null ? Async.completed(false) : durable;
        }

        public boolean queued() {
            return status == CreationAdmissionStatus.QUEUED;
        }

        public boolean rejected() {
            return status == CreationAdmissionStatus.REJECTED;
        }
    }

    public record ResourceDeleteResult(String type, String id, boolean deleted, String message) {
        public ResourceDeleteResult {
            type = type == null ? "" : type;
            id = id == null ? "" : id;
            message = message == null ? "" : message;
        }
    }

    static final class ResourceDeleteSettlement {
        private final String type;
        private final String id;
        private boolean acknowledged;
        private boolean absent;
        private boolean terminal;

        ResourceDeleteSettlement(String type, String id) {
            this.type = type == null ? "" : type;
            this.id = id == null ? "" : id;
        }

        synchronized ResourceDeleteResult acknowledge() {
            if (terminal) {
                return null;
            }
            acknowledged = true;
            return completeIfReady();
        }

        synchronized ResourceDeleteResult observeAbsence() {
            if (terminal) {
                return null;
            }
            absent = true;
            return completeIfReady();
        }

        synchronized ResourceDeleteResult fail(String message) {
            if (terminal) {
                return null;
            }
            terminal = true;
            return new ResourceDeleteResult(type, id, false,
                message == null || message.isBlank() ? "Resource Delete Failed" : message);
        }

        synchronized boolean acknowledged() {
            return acknowledged;
        }

        synchronized boolean absent() {
            return absent;
        }

        private ResourceDeleteResult completeIfReady() {
            if (!acknowledged || !absent) {
                return null;
            }
            terminal = true;
            return new ResourceDeleteResult(type, id, true, "");
        }
    }

    private record ResourceDeleteKey(String serverId, ReSyncResourceType type, String id) {
    }

    private static final class PendingResourceDelete {
        private final ResourceDeleteSettlement settlement;
        private final Async<ResourceDeleteResult> completion = Async.pending();
        private final long debugStartedAtNanos;

        private PendingResourceDelete(ReSyncResourceType type, String id) {
            settlement = new ResourceDeleteSettlement(type != null ? type.typeId() : "", id);
            debugStartedAtNanos = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        }
    }

    private record CreationKey(String serverId, String type, String id) {
    }

    private record CreationReconnectReset(CreationTransaction transaction,
                                          List<DesignerSaveNotifications.SaveTicket> tickets) {
    }

    private enum CreationPhase {
        PREPARING,
        PAYLOAD,
        BINDING,
        SETTLEMENT,
        METADATA,
        PAUSED,
        COMPLETE,
        FAILED
    }

    private enum PayloadSettlement {
        PENDING,
        ACCEPTED,
        REJECTED
    }

    private record CreationJournal(int schemaVersion, long generation, List<CreationJournalEntry> entries) {
        private JsonObject json() {
            JsonObject json = new JsonObject();
            json.addProperty("schemaVersion", schemaVersion);
            json.addProperty("generation", generation);
            JsonArray rows = new JsonArray();
            entries.forEach(entry -> rows.add(entry == null ? JsonNull.INSTANCE : entry.json()));
            json.add("entries", rows);
            return json;
        }

        private static CreationJournal read(String serialized) {
            JsonObject json = FlowJson.parse(serialized).getAsJsonObject();
            List<CreationJournalEntry> entries = null;
            if (json.has("entries") && !json.get("entries").isJsonNull()) {
                entries = new ArrayList<>();
                for (JsonElement entry : json.getAsJsonArray("entries")) {
                    entries.add(entry.isJsonNull() ? null : CreationJournalEntry.read(entry.getAsJsonObject()));
                }
            }
            return new CreationJournal(json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : 0,
                json.has("generation") ? json.get("generation").getAsLong() : 0L, entries);
        }
    }

    private record CreationJournalCandidate(CreationJournal journal) {
    }

    private record CreationJournalEntry(String serverId, String resourceType, String id, String resourceTemplate,
                                        String payloadJson, String locator, String payloadHash, String payloadRequestId,
                                        String payloadMutationId, String metadataRequestId, String metadataMutationId,
                                        String metadataType, String metadataId, String metadataName, String metadataPath,
                                        String metadataParentPath, int metadataSortOrder, boolean folder,
                                        String commandContext, String commandGraphJson, String commandGraphHash,
                                        String commandGraphRequestId, String commandGraphMutationId,
                                        String triggerRequestId, String triggerBindingsJson, String triggerBindingsHash,
                                        long triggerExpectedBindingEpoch, String triggerExpectedBindingHash,
                                        boolean commandGraphCommitted, boolean triggerCommitted,
                                        String phase, String payloadSettlement, boolean payloadCommitted, int attempts,
                                        long sequence, String resumePhase, long commandGraphBaseRevision,
                                        String commandGraphBaseHash, long commandGraphBaseGeneration,
                                        String corePayloadKind) {
        private JsonObject json() {
            JsonObject json = new JsonObject();
            if (serverId != null) json.addProperty("serverId", serverId);
            if (resourceType != null) json.addProperty("resourceType", resourceType);
            if (id != null) json.addProperty("id", id);
            if (resourceTemplate != null) json.addProperty("resourceTemplate", resourceTemplate);
            if (payloadJson != null) json.addProperty("payloadJson", payloadJson);
            if (locator != null) json.addProperty("locator", locator);
            if (payloadHash != null) json.addProperty("payloadHash", payloadHash);
            if (payloadRequestId != null) json.addProperty("payloadRequestId", payloadRequestId);
            if (payloadMutationId != null) json.addProperty("payloadMutationId", payloadMutationId);
            if (metadataRequestId != null) json.addProperty("metadataRequestId", metadataRequestId);
            if (metadataMutationId != null) json.addProperty("metadataMutationId", metadataMutationId);
            if (metadataType != null) json.addProperty("metadataType", metadataType);
            if (metadataId != null) json.addProperty("metadataId", metadataId);
            if (metadataName != null) json.addProperty("metadataName", metadataName);
            if (metadataPath != null) json.addProperty("metadataPath", metadataPath);
            if (metadataParentPath != null) json.addProperty("metadataParentPath", metadataParentPath);
            json.addProperty("metadataSortOrder", metadataSortOrder);
            json.addProperty("folder", folder);
            if (commandContext != null) json.addProperty("commandContext", commandContext);
            if (commandGraphJson != null) json.addProperty("commandGraphJson", commandGraphJson);
            if (commandGraphHash != null) json.addProperty("commandGraphHash", commandGraphHash);
            if (commandGraphRequestId != null) json.addProperty("commandGraphRequestId", commandGraphRequestId);
            if (commandGraphMutationId != null) json.addProperty("commandGraphMutationId", commandGraphMutationId);
            if (triggerRequestId != null) json.addProperty("triggerRequestId", triggerRequestId);
            if (triggerBindingsJson != null) json.addProperty("triggerBindingsJson", triggerBindingsJson);
            if (triggerBindingsHash != null) json.addProperty("triggerBindingsHash", triggerBindingsHash);
            json.addProperty("triggerExpectedBindingEpoch", triggerExpectedBindingEpoch);
            if (triggerExpectedBindingHash != null) json.addProperty("triggerExpectedBindingHash", triggerExpectedBindingHash);
            json.addProperty("commandGraphCommitted", commandGraphCommitted);
            json.addProperty("triggerCommitted", triggerCommitted);
            if (phase != null) json.addProperty("phase", phase);
            if (payloadSettlement != null) json.addProperty("payloadSettlement", payloadSettlement);
            json.addProperty("payloadCommitted", payloadCommitted);
            json.addProperty("attempts", attempts);
            json.addProperty("sequence", sequence);
            if (resumePhase != null) json.addProperty("resumePhase", resumePhase);
            json.addProperty("commandGraphBaseRevision", commandGraphBaseRevision);
            if (commandGraphBaseHash != null) json.addProperty("commandGraphBaseHash", commandGraphBaseHash);
            json.addProperty("commandGraphBaseGeneration", commandGraphBaseGeneration);
            if (corePayloadKind != null) json.addProperty("corePayloadKind", corePayloadKind);
            return json;
        }

        private static CreationJournalEntry read(JsonObject json) {
            return new CreationJournalEntry(
                json.has("serverId") && !json.get("serverId").isJsonNull() ? json.get("serverId").getAsString() : null,
                json.has("resourceType") && !json.get("resourceType").isJsonNull() ? json.get("resourceType").getAsString() : null,
                json.has("id") && !json.get("id").isJsonNull() ? json.get("id").getAsString() : null,
                json.has("resourceTemplate") && !json.get("resourceTemplate").isJsonNull() ? json.get("resourceTemplate").getAsString() : null,
                json.has("payloadJson") && !json.get("payloadJson").isJsonNull() ? json.get("payloadJson").getAsString() : null,
                json.has("locator") && !json.get("locator").isJsonNull() ? json.get("locator").getAsString() : null,
                json.has("payloadHash") && !json.get("payloadHash").isJsonNull() ? json.get("payloadHash").getAsString() : null,
                json.has("payloadRequestId") && !json.get("payloadRequestId").isJsonNull() ? json.get("payloadRequestId").getAsString() : null,
                json.has("payloadMutationId") && !json.get("payloadMutationId").isJsonNull() ? json.get("payloadMutationId").getAsString() : null,
                json.has("metadataRequestId") && !json.get("metadataRequestId").isJsonNull() ? json.get("metadataRequestId").getAsString() : null,
                json.has("metadataMutationId") && !json.get("metadataMutationId").isJsonNull() ? json.get("metadataMutationId").getAsString() : null,
                json.has("metadataType") && !json.get("metadataType").isJsonNull() ? json.get("metadataType").getAsString() : null,
                json.has("metadataId") && !json.get("metadataId").isJsonNull() ? json.get("metadataId").getAsString() : null,
                json.has("metadataName") && !json.get("metadataName").isJsonNull() ? json.get("metadataName").getAsString() : null,
                json.has("metadataPath") && !json.get("metadataPath").isJsonNull() ? json.get("metadataPath").getAsString() : null,
                json.has("metadataParentPath") && !json.get("metadataParentPath").isJsonNull() ? json.get("metadataParentPath").getAsString() : null,
                json.has("metadataSortOrder") && !json.get("metadataSortOrder").isJsonNull() ? json.get("metadataSortOrder").getAsInt() : 0,
                json.has("folder") && !json.get("folder").isJsonNull() ? json.get("folder").getAsBoolean() : false,
                json.has("commandContext") && !json.get("commandContext").isJsonNull() ? json.get("commandContext").getAsString() : null,
                json.has("commandGraphJson") && !json.get("commandGraphJson").isJsonNull() ? json.get("commandGraphJson").getAsString() : null,
                json.has("commandGraphHash") && !json.get("commandGraphHash").isJsonNull() ? json.get("commandGraphHash").getAsString() : null,
                json.has("commandGraphRequestId") && !json.get("commandGraphRequestId").isJsonNull() ? json.get("commandGraphRequestId").getAsString() : null,
                json.has("commandGraphMutationId") && !json.get("commandGraphMutationId").isJsonNull() ? json.get("commandGraphMutationId").getAsString() : null,
                json.has("triggerRequestId") && !json.get("triggerRequestId").isJsonNull() ? json.get("triggerRequestId").getAsString() : null,
                json.has("triggerBindingsJson") && !json.get("triggerBindingsJson").isJsonNull() ? json.get("triggerBindingsJson").getAsString() : null,
                json.has("triggerBindingsHash") && !json.get("triggerBindingsHash").isJsonNull() ? json.get("triggerBindingsHash").getAsString() : null,
                json.has("triggerExpectedBindingEpoch") && !json.get("triggerExpectedBindingEpoch").isJsonNull() ? json.get("triggerExpectedBindingEpoch").getAsLong() : 0L,
                json.has("triggerExpectedBindingHash") && !json.get("triggerExpectedBindingHash").isJsonNull() ? json.get("triggerExpectedBindingHash").getAsString() : null,
                json.has("commandGraphCommitted") && !json.get("commandGraphCommitted").isJsonNull() ? json.get("commandGraphCommitted").getAsBoolean() : false,
                json.has("triggerCommitted") && !json.get("triggerCommitted").isJsonNull() ? json.get("triggerCommitted").getAsBoolean() : false,
                json.has("phase") && !json.get("phase").isJsonNull() ? json.get("phase").getAsString() : null,
                json.has("payloadSettlement") && !json.get("payloadSettlement").isJsonNull() ? json.get("payloadSettlement").getAsString() : null,
                json.has("payloadCommitted") && !json.get("payloadCommitted").isJsonNull() ? json.get("payloadCommitted").getAsBoolean() : false,
                json.has("attempts") && !json.get("attempts").isJsonNull() ? json.get("attempts").getAsInt() : 0,
                json.has("sequence") && !json.get("sequence").isJsonNull() ? json.get("sequence").getAsLong() : 0L,
                json.has("resumePhase") && !json.get("resumePhase").isJsonNull() ? json.get("resumePhase").getAsString() : null,
                json.has("commandGraphBaseRevision") && !json.get("commandGraphBaseRevision").isJsonNull() ? json.get("commandGraphBaseRevision").getAsLong() : 0L,
                json.has("commandGraphBaseHash") && !json.get("commandGraphBaseHash").isJsonNull() ? json.get("commandGraphBaseHash").getAsString() : null,
                json.has("commandGraphBaseGeneration") && !json.get("commandGraphBaseGeneration").isJsonNull() ? json.get("commandGraphBaseGeneration").getAsLong() : 0L,
                json.has("corePayloadKind") && !json.get("corePayloadKind").isJsonNull() ? json.get("corePayloadKind").getAsString() : null);
        }
    }

    private static final class CreationTransaction {
        private final CreationKey key;
        private final ReSyncResourceType resourceType;
        private final Supplier<Object> resourceFactory;
        private final String resourceTemplate;
        private final ServerResourceLocator locator;
        private final UUID payloadRequestId;
        private final UUID payloadMutationId;
        private final CreationMetadataIntent metadata;
        private final String commandContext;
        private final Consumer<CreationResult> observer;
        private final long sequence;
        private final long debugStartedAtNanos;
        private long debugPhaseStartedAtNanos;
        private CreationPhase debugObservedPhase;
        private CoreGraphEditorSession coreSession;
        private String corePayloadKind;
        private boolean corePayload;
        private Object resource;
        private String payloadJson;
        private ContentHash payloadHash;
        private UUID metadataRequestId;
        private UUID metadataMutationId;
        private String commandGraphJson;
        private ContentHash commandGraphHash;
        private long commandGraphBaseRevision = -1L;
        private String commandGraphBaseHash;
        private long commandGraphBaseGeneration = -1L;
        private final UUID commandGraphRequestId;
        private final UUID commandGraphMutationId;
        private UUID triggerRequestId;
        private DesignerSaveNotifications.SaveTicket commandGraphTicket;
        private SyncedResourceCache.SaveLease<?> commandGraphLease;
        private long commandGraphLeaseGeneration = -1L;
        private boolean commandGraphCommitted;
        private boolean triggerCommitted;
        private boolean commandGraphPreparing;
        private boolean creationPreparationQueued;
        private CreationPhase phase;
        private DesignerSaveNotifications.SaveTicket payloadTicket;
        private DesignerSaveNotifications.SaveTicket metadataTicket;
        private int attempts;
        private boolean payloadCommitted;
        private PayloadSettlement payloadSettlement = PayloadSettlement.PENDING;
        private boolean settling;
        private boolean retryScheduled;
        private volatile boolean suspended;
        private boolean reconnectResetPending;
        private boolean journalSettled;
        private boolean journalWriteQueued;
        private long journalRevision;
        private Runnable journalPersisted;
        private Runnable journalFailed;
        private int journalAttempts;
        private boolean terminalRetryScheduled;
        private final long admissionGeneration;
        private boolean newerLocalState;
        private long metadataAuthorityGeneration;
        private boolean metadataAuthorityWaitLogged;
        private int metadataHydrationAttempts;
        private CreationPhase pausedResumePhase;
        private ReSyncFlowClient commandFlowClient;
        private ServerConnectionToken commandConnectionToken;
        private long commandAuthorityEpoch;
        private long commandGraphAttempt;
        private long triggerAttempt;
        private List<TriggerBinding> triggerBaseline = List.of();
        private boolean triggerBaselineCaptured;
        private List<TriggerBinding> triggerPreparedBindings = List.of();
        private boolean triggerPreparationQueued;
        private long triggerPreparationGeneration;
        private String triggerBindingsJson;
        private String triggerBindingsHash;
        private long triggerExpectedBindingEpoch;
        private String triggerExpectedBindingHash;
        private final Async<Boolean> durableAdmission = Async.pending();

        private CreationTransaction(CreationKey key, ReSyncResourceType resourceType, Object resource,
                                    Supplier<Object> resourceFactory,
                                    String resourceTemplate,
                                    ServerResourceLocator locator, ContentHash payloadHash,
                                    UUID payloadRequestId,
                                    UUID payloadMutationId, CreationMetadataIntent metadata, String commandContext,
                                    UUID commandGraphRequestId, UUID commandGraphMutationId, UUID triggerRequestId,
                                    Consumer<CreationResult> observer, long sequence, long admissionGeneration) {
            this.key = key;
            this.resourceType = resourceType;
            this.resource = resource;
            this.resourceFactory = resourceFactory;
            this.resourceTemplate = resourceTemplate;
            this.locator = locator;
            this.payloadHash = payloadHash;
            this.payloadRequestId = payloadRequestId;
            this.payloadMutationId = payloadMutationId;
            this.commandGraphRequestId = commandGraphRequestId;
            this.commandGraphMutationId = commandGraphMutationId;
            this.triggerRequestId = triggerRequestId;
            this.metadata = metadata;
            this.commandContext = commandContext;
            this.observer = observer;
            this.sequence = sequence;
            this.debugStartedAtNanos = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
            this.debugPhaseStartedAtNanos = this.debugStartedAtNanos;
            this.admissionGeneration = admissionGeneration;
            this.payloadJson = null;
            this.corePayload = false;
            this.corePayloadKind = null;
            this.metadataRequestId = UUID.randomUUID();
            this.metadataMutationId = UUID.randomUUID();
            this.phase = resourceType == null ? CreationPhase.METADATA
                : resourceFactory != null ? CreationPhase.PREPARING : CreationPhase.PAYLOAD;
            this.debugObservedPhase = this.phase;
        }

        private boolean isPayloadPhase() {
            return phase == CreationPhase.PAYLOAD;
        }

        private boolean isPreparing() {
            return phase == CreationPhase.PREPARING;
        }

        private boolean isBindingPhase() {
            return phase == CreationPhase.BINDING;
        }

        private boolean isMetadataPhase() {
            return phase == CreationPhase.METADATA;
        }

        private boolean isSettlementPhase() {
            return phase == CreationPhase.SETTLEMENT;
        }

        private boolean isTerminal() {
            return phase == CreationPhase.COMPLETE || phase == CreationPhase.FAILED;
        }
    }

    record ResourceSaveSettlement(boolean accepted, boolean currentAtFinish, long generation) {
        ResourceSaveSettlement(boolean accepted, boolean currentAtFinish) {
            this(accepted, currentAtFinish, -1L);
        }
    }

    private record CommandGraphAdmission(SyncedResourceCache.SaveLease<?> lease, long generation,
                                         boolean preserveNewerDraft, boolean available) {
        private static CommandGraphAdmission unavailable() {
            return new CommandGraphAdmission(null, -1L, false, false);
        }
    }

    public static final class ResourceReadLease {
        private final String serverId;
        private final String type;
        private final String id;
        private final String context;
        private final BooleanSupplier current;
        private final Supplier<String> materializer;
        private final Function<Object, SyncedResourceCache.SaveLease<?>> publisher;

        private ResourceReadLease(String serverId, String type, String id, String context, BooleanSupplier current,
                                  Supplier<String> materializer, Function<Object, SyncedResourceCache.SaveLease<?>> publisher) {
            this.serverId = serverId;
            this.type = type;
            this.id = id;
            this.context = context;
            this.current = current;
            this.materializer = materializer;
            this.publisher = publisher;
        }

        public String type() {
            return type;
        }

        public String id() {
            return id;
        }

        public String context() {
            return context;
        }

        public String materialize() {
            return materializer.get();
        }

        public boolean isCurrent() {
            return current.getAsBoolean();
        }

        private SyncedResourceCache.SaveLease<?> compareAndPublish(Object value) {
            return publisher != null ? publisher.apply(value) : null;
        }
    }

    public record ProjectFolder(String path, String parentPath, String name, int sortOrder, boolean collapsed) {
    }

    public record ProjectResource(String type, String id, String displayName, String path, int sortOrder) {
        public String key() {
            return ReSyncProjectMetadata.resourceKey(type, id);
        }
    }

    public record TypedResourceMembershipSnapshot(String serverId, long connectionGeneration,
                                                   List<ProjectResource> resources, Set<String> completeTypes,
                                                   Set<String> tombstones) {
        public TypedResourceMembershipSnapshot(String serverId, long connectionGeneration,
                                               List<ProjectResource> resources, Set<String> completeTypes) {
            this(serverId, connectionGeneration, resources, completeTypes, Set.of());
        }

        public TypedResourceMembershipSnapshot {
            serverId = serverId == null ? "" : serverId;
            resources = (resources == null ? List.<ProjectResource>of() : resources).stream()
                .filter(resource -> resource != null && resource.type() != null && !resource.type().isBlank()
                    && resource.id() != null && !resource.id().isBlank()).toList();
            completeTypes = (completeTypes == null ? Set.<String>of() : completeTypes).stream()
                .filter(type -> type != null && !type.isBlank()).collect(Collectors.toUnmodifiableSet());
            tombstones = (tombstones == null ? Set.<String>of() : tombstones).stream()
                .filter(key -> key != null && !key.isBlank()).collect(Collectors.toUnmodifiableSet());
        }

        public boolean contains(String type, String id) {
            String key = ReSyncProjectMetadata.resourceKey(type, id);
            return resources.stream().anyMatch(resource -> resource.key().equals(key));
        }
    }

    public record ProjectBundle(String marketplaceSlug, String listingSlug, String title, String versionId, String version,
                                String rootPath, String iconMediaId, boolean enabled, List<String> resourceKeys) {
        public ProjectBundle {
            resourceKeys = List.copyOf(resourceKeys);
        }

        public String key() {
            return ReSyncProjectMetadata.bundleKey(marketplaceSlug, listingSlug);
        }
    }

    public static final class ProjectMetadataEdit {
        private final String serverId;
        private final ProjectMetadataSnapshot.Editor editor;

        private ProjectMetadataEdit(String serverId, ProjectMetadataSnapshot.Editor editor) {
            this.serverId = serverId;
            this.editor = editor;
        }

        public ProjectResource resource(String type, String id) {
            return projectResource(editor.resource(type, id));
        }

        public ProjectFolder folder(String path) {
            return projectFolder(editor.folder(path));
        }

        public List<ProjectResource> resources() {
            return editor.resources().stream().map(FlowManager::projectResource).toList();
        }

        public List<ProjectFolder> folders() {
            return editor.folders().stream().map(FlowManager::projectFolder).toList();
        }

        public void putResource(String type, String id, String displayName, String path, int sortOrder) {
            editor.put(new ProjectMetadataSnapshot.Resource(type, id, displayName, ReSyncProjectMetadata.normalizePath(path), sortOrder));
        }

        public void putFolder(String path, String parentPath, String name, int sortOrder, boolean collapsed) {
            String normalized = ReSyncProjectMetadata.normalizePath(path);
            editor.put(new ProjectMetadataSnapshot.Folder(normalized, ReSyncProjectMetadata.normalizePath(parentPath), name, sortOrder, collapsed));
        }

        public void removeResource(String key) {
            editor.removeResource(key);
        }

        public void renameResource(String oldKey, String type, String id, String displayName, String path, int sortOrder) {
            editor.renameResource(oldKey, new ProjectMetadataSnapshot.Resource(type, id, displayName,
                ReSyncProjectMetadata.normalizePath(path), sortOrder));
        }

        public void removeFolder(String path) {
            editor.removeFolder(path);
        }

        public ProjectBundle bundle(String marketplaceSlug, String listingSlug) {
            ProjectMetadataSnapshot.Bundle bundle = editor.bundle(ReSyncProjectMetadata.bundleKey(marketplaceSlug, listingSlug));
            return bundle != null ? new ProjectBundle(bundle.marketplaceSlug(), bundle.listingSlug(), bundle.title(), bundle.versionId(),
                bundle.version(), bundle.rootPath(), bundle.iconMediaId(), bundle.enabled(), bundle.resourceKeys()) : null;
        }

        public void putBundle(ProjectBundle bundle) {
            editor.put(new ProjectMetadataSnapshot.Bundle(bundle.marketplaceSlug(), bundle.listingSlug(), bundle.title(), bundle.versionId(),
                bundle.version(), bundle.rootPath(), bundle.iconMediaId(), bundle.enabled(), bundle.resourceKeys()));
        }

        public void removeBundle(String key) {
            editor.removeBundle(key);
        }

        public int nextFolderSortOrder() {
            return editor.nextFolderSortOrder();
        }

        public int nextResourceSortOrder() {
            return editor.nextResourceSortOrder();
        }
    }

    private static final BrowserSafeState.ReferenceValue<FlowManager> INSTANCE = new BrowserSafeState.ReferenceValue<>();
    private static final List<String> FLOW_TEMPLATES = List.of("Blank", "Command");
    private static final List<String> CUSTOM_CONTENT_OPTION_CATALOGS = List.of(
        "server:custom_content:recipe_item",
        "server:custom_content:provider",
        "server:custom_content:asset"
    );
    private static final int MAX_TARGETED_FLOW_REFRESH_IDS = 16;
    private static final long CORE_GRAPH_HYDRATION_RETRY_NANOS = ((3L) * 1_000_000_000L);
    private static final long CORE_GRAPH_HYDRATION_TIMEOUT_NANOS = ((15L) * 1_000_000_000L);
    private static final String CORE_GRAPH_PUBLICATION_MISMATCH =
        "The graph catalog binding does not match the active authoring publication.";
    private static final int MAX_CORE_TEMPLATE_ROLLOVER_RETRIES = 3;
    private static final int MAX_PENDING_CREATION_TRANSACTIONS = 32;
    private static final int MAX_CREATION_ATTEMPTS = 4;
    private static final int MAX_CREATION_JOURNAL_LOAD_ATTEMPTS = 4;
    private static final int MAX_CREATION_FIELD_BYTES = ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES;
    private static final int MAX_CREATION_PAYLOAD_BYTES = ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES;
    private static final int MAX_CREATION_COMMAND_GRAPH_BYTES = ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES;
    private static final int MAX_CREATION_TRIGGER_BYTES = ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES;
    private static final int MAX_CREATION_TRIGGER_BINDINGS = 4096;
    private static final int MAX_CREATION_TRIGGER_BINDING_TEXT = 1024;
    private static final long MAX_CREATION_JOURNAL_BYTES = 8L * ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES;
    private static final long CREATION_RETRY_DELAY_SECONDS = 3L;
    private static final long CREATION_JOURNAL_RETRY_DELAY_SECONDS = 5L;
    private static final long MAX_CREATION_JOURNAL_RETRY_DELAY_SECONDS = 60L;
    private static final int MIN_CREATION_JOURNAL_SCHEMA_VERSION = 1;
    private static final int CREATION_JOURNAL_SCHEMA_VERSION = 4;
    private static final OwnerId CREATION_RESOURCE_OWNER = new OwnerId("restudio.resync");
    private final RemotelyClient client;
    private final RemotelyServerApi remotelyApi;
    private final ReSyncConnectionManager connectionManager;
    private final FlowDebugController debugController;
    private final ReSyncWorldService worldService;
    private final ReSyncPlayerService playerService;
    private final TypedGraphCache flowStore = new TypedGraphCache();
    private final CoreGraphUiProjection coreGraphUiProjection = new CoreGraphUiProjection();
    private final CoreGraphDocumentAuthoringAdapter coreGraphDocumentAuthoring =
        new CoreGraphDocumentAuthoringAdapter(this::dispatchCoreGraphTemplate, this::activeAuthoringPublication);
    private volatile CoreGraphDocumentAuthoringAdapter.TemplateTransport coreGraphTemplateTransport;
    private final Map<CoreGraphSessionKey, CoreGraphSessionState> coreGraphEditorSessions = BrowserSafeState.map();
    private final Set<CoreGraphSessionKey> pendingCoreSessionPreparations = BrowserSafeState.set();
    private final CoreGraphHydrationTracker<CoreGraphSessionKey> coreGraphHydrations = new CoreGraphHydrationTracker<>();
    private final Map<UUID, CoreTemplateIntent> pendingCoreTemplateIntents = BrowserSafeState.map();
    private final Map<ServerResourceLocator, CoreTemplateIntent> preparingCoreTemplateIntents = BrowserSafeState.map();
    private final Map<ServerResourceLocator, CoreTemplateIntent> deferredCoreTemplateIntents = BrowserSafeState.map();
    private final Map<UUID, AuthoringTemplateResponse> coreTemplateResponses = BrowserSafeState.map();
    private final Map<String, CoreGraphListenerSubscription> coreGraphListenerSubscriptions = BrowserSafeState.map();
    private final BrowserSafeState.LongValue coreGraphListenerGeneration = new BrowserSafeState.LongValue();
    private final Map<String, List<CoreUiTransition>> pendingCoreUiTransitions = new HashMap<>();
    private final Set<String> scheduledCoreUiTransitions = new HashSet<>();
    private final Map<String, ServerConnectionToken> coreUiTransitionGenerations = new HashMap<>();
    private final Map<String, Integer> activeCoreEffectsByServer = new HashMap<>();
    private final Set<String> closingCoreServers = new HashSet<>();
    private final Object coreUiTransitionLock = new Object();
    private final Object coreLifecycleLock = new Object();
    private final ThreadLocal<CoreGraphHandoff> coreGraphHandoff = new ThreadLocal<>();
    private final ThreadLocal<Integer> coreUiEffectDepth = ThreadLocal.withInitial(() -> 0);
    private volatile boolean closed;
    private boolean shutdownInProgress;
    private boolean shutdownComplete;
    private boolean handoffCleanupPending = true;
    private boolean connectionCleanupPending = true;
    private boolean cacheCleanupPending = true;
    private boolean instanceCleanupPending = true;
    private final Set<String> pendingShutdownServerIds = new TreeSet<>();
    private RuntimeException lastShutdownFailure;
    private int activeCoreUiEffects;
    private final SyncedResourceCache<GuiDefinition> guiStore = new SyncedResourceCache<>(GuiDefinition::getId, g -> g.getTitle() != null ? g.getTitle() : g.getId());
    private final SyncedResourceCache<ScoreboardDefinition> scoreboardStore = new SyncedResourceCache<>(ScoreboardDefinition::getId, s -> s.getTitle() != null ? s.getTitle() : s.getId());
    private final SyncedResourceCache<TabDefinition> tabStore = new SyncedResourceCache<>(TabDefinition::getId, TabDefinition::getId);
    private final SyncedResourceCache<CustomContentDefinition> customContentStore = new SyncedResourceCache<>(CustomContentDefinition::getId,
        c -> c.getDisplayName() != null ? c.getDisplayName() : c.getId(),
        content -> FlowSerializer.deserializeCustomContent(FlowSerializer.serializeCustomContent(content)));
    private final SyncedResourceCache<ProjectMetadataSnapshot> projectMetadataStore = new SyncedResourceCache<>(ProjectMetadataSnapshot::serverId, m -> "Project", Function.identity());
    private final Map<ReSyncResourceType, SyncedResourceCache<JsonObject>> jsonResourceStores = BrowserSafeState.map();
    private final Map<ActivationKey, PendingActivation> pendingActivations = BrowserSafeState.map();
    private final Map<ResourceDeleteKey, PendingResourceDelete> pendingResourceDeletions = BrowserSafeState.map();
    private final Map<CreationKey, CreationTransaction> creationTransactions = BrowserSafeState.map();
    private final Object creationTransactionLock = new Object();
    private final BrowserSafeState.LongValue creationSequence = new BrowserSafeState.LongValue();
    private final ReSyncStorage creationJournalStorage;
    private volatile boolean creationJournalEvidenceRetained;
    private volatile boolean creationJournalLoaded;
    private final BrowserSafeState.IntegerValue creationJournalLoadAttempts = new BrowserSafeState.IntegerValue();
    private final BrowserSafeState.LongValue creationJournalRetryNotBeforeMillis = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue creationJournalGeneration = new BrowserSafeState.LongValue();
    private final BrowserWork.Executor creationJournalScheduler = BrowserWork.executor();
    private final BrowserSafeState.IntegerValue creationJournalLoadQueued = new BrowserSafeState.IntegerValue();
    private final BrowserSafeState.IntegerValue creationJournalRetryScheduled = new BrowserSafeState.IntegerValue();
    private final BrowserSafeState.IntegerValue creationJournalCleanupQueued = new BrowserSafeState.IntegerValue();
    private final BrowserSafeState.IntegerValue creationJournalCleanupRetryScheduled = new BrowserSafeState.IntegerValue();
    private final BrowserSafeState.IntegerValue creationJournalCleanupAttempts = new BrowserSafeState.IntegerValue();
    private final Object creationJournalPersistenceLock = new Object();
    private final Object resourceActivationLock = new Object();
    private final Map<String, JsonObject> serverCapabilities = BrowserSafeState.map();
    private final Map<String, JsonObject> messageLogPages = BrowserSafeState.map();
    private final Object triggerBindingsLock = new Object();
    private final Map<String, List<TriggerBinding>> triggerBindings = new HashMap<>();
    private final Map<String, Integer> projectCatalogRevisions = BrowserSafeState.map();
    private final Map<String, Long> projectMetadataAuthorityGenerations = BrowserSafeState.map();
    private final Map<String, Integer> hydratedProjectCatalogRevisions = BrowserSafeState.map();
    private final Map<String, ProjectMetadataView> projectMetadataViews = BrowserSafeState.map();
    private final Map<String, Integer> scheduledProjectMetadataHydrations = BrowserSafeState.map();
    private final Map<String, ProjectMetadataHydration> pendingProjectMetadataHydrations = BrowserSafeState.map();
    private final BrowserWork.Executor projectMetadataHydrations = BrowserWork.executor();
    private final Map<String, PendingStudioWorkspaceRefresh> pendingStudioWorkspaceRefreshes = BrowserSafeState.map();
    private final Map<String, PendingFlowWorkspaceRefresh> pendingFlowWorkspaceRefreshes = BrowserSafeState.map();
    private final Map<String, Map<String, Consumer<FlowEditorScreen>>> pendingStudioDocumentOpeners = BrowserSafeState.map();
    private final Map<String, String> studioServerTitles = BrowserSafeState.map();
    private final Object studioWorkspaceRefreshLock = new Object();
    private final Object flowWorkspaceRefreshLock = new Object();
    private final Set<String> scheduledStudioWorkspaceRefreshes = new HashSet<>();
    private final Set<String> scheduledFlowWorkspaceRefreshes = new HashSet<>();
    private final Map<String, ServerConnectionToken> studioWorkspaceRefreshGenerations = new HashMap<>();
    private final Map<String, ServerConnectionToken> flowWorkspaceRefreshGenerations = new HashMap<>();
    private final Object serverConnectionGenerationLock = new Object();
    private final Map<String, Long> serverConnectionGenerations = BrowserSafeState.map();
    private final Map<String, ReSyncFlowClient> serverGenerationSources = BrowserSafeState.map();
    private final Set<String> retiringServerConnections = BrowserSafeState.set();
    private final Set<ReSyncFlowClient> settledDisconnectedSources = BrowserSafeState.set();
    private final Set<String> loadedProjectMetadataLists = BrowserSafeState.set();
    private final Set<String> pendingProjectMetadataDocuments = BrowserSafeState.set();
    private final Map<TypedMembershipKey, TypedMembershipState> typedMemberships = BrowserSafeState.map();
    private final Map<String, ServerConnectionToken> worldSnapshotGenerations = BrowserSafeState.map();
    private final Map<String, WorldGenMembershipVersion> worldGenMembershipVersions = BrowserSafeState.map();
    private final Map<String, Long> projectMembershipRevisions = BrowserSafeState.map();
    private final StudioFullEditorSession studioFullEditorSession = new StudioFullEditorSession();
    private volatile boolean guiOverlayEditable;
    private volatile String guiOverlayServerId;
    private volatile String guiOverlayGuiId;
    private volatile String guiOverlayFlowId;
    private volatile boolean editTargetOverlayEditable;
    private volatile String editTargetOverlayServerId;
    private volatile String editTargetOverlayResourceType;
    private volatile String editTargetOverlayResourceId;
    private volatile String editTargetOverlayFlowId;
    private volatile String marketplaceImportServerId;
    private final BrowserSafeState.IntegerValue overlayRevision = new BrowserSafeState.IntegerValue();
    private final BrowserSafeState.IntegerValue projectCatalogRevision = new BrowserSafeState.IntegerValue();
    private final BrowserSafeState.LongValue projectMembershipRevision = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue typedMembershipRevision = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue studioOpenGeneration = new BrowserSafeState.LongValue();
    private final Gson gson = new Gson();

    public record GraphProjectionSnapshot(ReSyncResourceType type, String resourceId, FlowGraph previous,
                                           SyncedResourceCache.EntrySnapshot<FlowGraph> entry) {
        public GraphProjectionSnapshot(ReSyncResourceType type, String resourceId, FlowGraph previous) {
            this(type, resourceId, previous, null);
        }
    }

    private record ProjectMetadataView(int revision, ProjectMetadataSnapshot metadata) {
    }

    private record ProjectMetadataHydration(int revision,
                                            SyncedResourceCache.SnapshotLease<ProjectMetadataSnapshot> snapshot) {
    }

    private record TypedMembershipKey(String serverId, ReSyncResourceType type) {
    }

    private record TypedMembershipState(ServerConnectionToken token, List<String> ids, Set<String> tombstones,
                                         boolean complete, long revision) {
        private TypedMembershipState {
            ids = List.copyOf(ids);
            tombstones = Set.copyOf(tombstones);
        }
    }

    record TypedMembershipMutationResult(List<String> ids, Set<String> tombstones) {
        TypedMembershipMutationResult {
            ids = List.copyOf(ids);
            tombstones = Set.copyOf(tombstones);
        }
    }

    private record TypedMembershipMutation(TypedMembershipKey key, TypedMembershipState before,
                                            TypedMembershipState after) {
    }

    private record WorldGenMembershipVersion(long connectionGeneration, long authorityEpoch, long revision) {
    }

    private record ProjectionKey(String type, String serverId, String resourceId) {
    }

    public static final class ResourceProjectionLease {
        private final Map<ProjectionKey, Long> generations = new HashMap<>();
        private final Map<CoreGraphUiProjection.Key, Long> coreGenerations = new HashMap<>();
        private final Set<CoreGraphUiProjection.Key> coreMutations = new HashSet<>();
        private final Map<CoreGraphSessionKey, CoreGraphEditorSession> editorSessions = new HashMap<>();
        private final Set<CoreGraphSessionKey> editorSessionKeys = new HashSet<>();
        private final Set<ProjectionKey> rejected = new HashSet<>();
        private final Set<CoreGraphUiProjection.Key> rejectedCore = new HashSet<>();
        private ServerConnectionToken connectionToken;
        private TypedMembershipMutation membershipMutation;
        private boolean valid = true;

        public boolean isValid() {
            return valid;
        }

        private void remember(String type, String serverId, String resourceId, long generation) {
            ProjectionKey key = new ProjectionKey(type, serverId, resourceId);
            Long previous = generations.putIfAbsent(key, generation);
            if (previous != null && previous != generation) {
                valid = false;
            }
        }

        private Long expected(String type, String serverId, String resourceId) {
            ProjectionKey key = new ProjectionKey(type, serverId, resourceId);
            return rejected.contains(key) ? null : generations.get(key);
        }

        private boolean advance(String type, String serverId, String resourceId, Long generation) {
            ProjectionKey key = new ProjectionKey(type, serverId, resourceId);
            if (!valid || generation == null) {
                rejected.add(key);
                return false;
            }
            if (!generations.containsKey(key)) {
                valid = false;
                return false;
            }
            generations.put(key, generation);
            return true;
        }

        private void rememberCore(String serverId, ReSyncResourceType type, String resourceId, long generation) {
            CoreGraphUiProjection.Key key = new CoreGraphUiProjection.Key(serverId, type, resourceId);
            Long previous = coreGenerations.putIfAbsent(key, generation);
            if (previous != null && previous != generation) {
                valid = false;
            }
        }

        private Long expectedCore(String serverId, ReSyncResourceType type, String resourceId) {
            CoreGraphUiProjection.Key key = new CoreGraphUiProjection.Key(serverId, type, resourceId);
            return rejectedCore.contains(key) ? null : coreGenerations.get(key);
        }

        private boolean advanceCore(String serverId, ReSyncResourceType type, String resourceId, Long generation) {
            CoreGraphUiProjection.Key key = new CoreGraphUiProjection.Key(serverId, type, resourceId);
            if (!valid || generation == null) {
                rejectedCore.add(key);
                return false;
            }
            Long previous = coreGenerations.get(key);
            if (previous == null) {
                valid = false;
                return false;
            }
            coreGenerations.put(key, generation);
            if (!Objects.equals(previous, generation)) {
                coreMutations.add(key);
            }
            return true;
        }

        private boolean coreMutation(String serverId, ReSyncResourceType type, String resourceId) {
            return coreMutations.contains(new CoreGraphUiProjection.Key(serverId, type, resourceId));
        }

        private void rememberEditorSession(String serverId, ReSyncResourceType type, String resourceId,
                                            CoreGraphSessionState session) {
            CoreGraphSessionKey key = new CoreGraphSessionKey(serverId, type, resourceId);
            editorSessionKeys.add(key);
            editorSessions.put(key, session != null ? session.session() : null);
        }

        private boolean remembersEditorSession(String serverId, ReSyncResourceType type, String resourceId) {
            return editorSessionKeys.contains(new CoreGraphSessionKey(serverId, type, resourceId));
        }

        private CoreGraphEditorSession editorSession(String serverId, ReSyncResourceType type, String resourceId) {
            return editorSessions.get(new CoreGraphSessionKey(serverId, type, resourceId));
        }

        private void rememberConnection(ServerConnectionToken token) {
            if (connectionToken != null && !sameWorkspaceRefreshGeneration(connectionToken, token)) {
                valid = false;
                return;
            }
            connectionToken = token;
        }

        private boolean rememberMembership(TypedMembershipKey key, TypedMembershipState before,
                                           TypedMembershipState after) {
            if (membershipMutation != null) {
                return false;
            }
            membershipMutation = new TypedMembershipMutation(key, before, after);
            return true;
        }

        private void reject() {
            valid = false;
        }
    }

    public record ResourceProjectionSnapshot(ReSyncResourceType type, String serverId, String resourceId,
                                             SyncedResourceCache.EntrySnapshot<?> resource,
                                             List<GraphProjectionSnapshot> graphs,
                                             boolean projectMetadataLoaded,
                                             boolean projectMetadataPending,
                                             CoreGraphUiProjection.StateSnapshot coreGraph,
                                             SyncedResourceCache.EntrySnapshot<ProjectMetadataSnapshot> projectMetadata,
                                             SyncedResourceCache.EntrySnapshot<CustomContentDefinition> derivedContent) {
        public ResourceProjectionSnapshot(ReSyncResourceType type, String serverId, String resourceId,
                                          SyncedResourceCache.EntrySnapshot<?> resource,
                                          List<GraphProjectionSnapshot> graphs,
                                          boolean projectMetadataLoaded, boolean projectMetadataPending) {
            this(type, serverId, resourceId, resource, graphs, projectMetadataLoaded, projectMetadataPending,
                null, null, null);
        }

        public ResourceProjectionSnapshot(ReSyncResourceType type, String serverId, String resourceId,
                                          SyncedResourceCache.EntrySnapshot<?> resource,
                                          List<GraphProjectionSnapshot> graphs,
                                          boolean projectMetadataLoaded, boolean projectMetadataPending,
                                          CoreGraphUiProjection.StateSnapshot coreGraph) {
            this(type, serverId, resourceId, resource, graphs, projectMetadataLoaded, projectMetadataPending,
                coreGraph, null, null);
        }

        public ResourceProjectionSnapshot(ReSyncResourceType type, String serverId, String resourceId,
                                          SyncedResourceCache.EntrySnapshot<?> resource,
                                          List<GraphProjectionSnapshot> graphs,
                                          boolean projectMetadataLoaded, boolean projectMetadataPending,
                                          CoreGraphUiProjection.StateSnapshot coreGraph,
                                          SyncedResourceCache.EntrySnapshot<ProjectMetadataSnapshot> projectMetadata) {
            this(type, serverId, resourceId, resource, graphs, projectMetadataLoaded, projectMetadataPending,
                coreGraph, projectMetadata, null);
        }
    }

    public record ServerConnectionToken(String serverId, ReSyncFlowClient source, long generation) {
    }

    public enum CoreGraphSaveSettlement {
        SETTLED,
        REBASED,
        NO_OPEN_SESSION,
        REHYDRATION_REQUIRED,
        REJECTED;

        public boolean settled() {
            return this == SETTLED || this == REBASED || this == NO_OPEN_SESSION;
        }

        public boolean editorUpdated() {
            return this == SETTLED;
        }

        public boolean retryable() {
            return this == REHYDRATION_REQUIRED;
        }
    }

    public enum CoreGraphSaveRejection {
        MISSING_SAVE_TOKEN,
        MISSING_MUTATION_RESULT,
        MISSING_SUBMITTED_CHECKSUM,
        MISSING_AUTHORITATIVE_PAYLOAD,
        MUTATION_NOT_ACCEPTED,
        UNSUPPORTED_MUTATION_OPERATION,
        ACKNOWLEDGED_RESOURCE_DELETED,
        MISSING_REQUEST_PAYLOAD_HASH,
        MISSING_AUTHORITATIVE_MUTATION_IDENTITY,
        AUTHORITATIVE_MUTATION_IDENTITY_MISMATCH,
        UNSUPPORTED_RESOURCE_TYPE,
        AUTHORITATIVE_PAYLOAD_KIND_MISMATCH,
        MISSING_SERVER_IDENTITY,
        MISSING_RESOURCE_IDENTITY,
        MISSING_REQUEST_IDENTITY,
        MISSING_MUTATION_IDENTITY,
        INVALID_EXPECTED_REVISION,
        INVALID_AUTHORITATIVE_REVISION,
        MISSING_AUTHORITATIVE_PAYLOAD_HASH,
        MISSING_SAVE_SOURCE,
        INVALID_SAVE_GENERATION,
        SAVE_TOKEN_SERVER_MISMATCH,
        MISSING_AUTHORITATIVE_PAYLOAD_RESOURCE,
        AUTHORITATIVE_PAYLOAD_SERVER_MISMATCH,
        AUTHORITATIVE_PAYLOAD_TYPE_MISMATCH,
        AUTHORITATIVE_PAYLOAD_IDENTITY_MISMATCH,
        AUTHORITATIVE_PAYLOAD_REVISION_MISMATCH,
        CURRENT_SOURCE_MISSING,
        SAVE_SOURCE_REPLACED,
        SAVE_GENERATION_RETIRED,
        SAVE_SOURCE_DISCONNECTED,
        MISSING_EDITOR_SESSION,
        EDITOR_SESSION_RESOURCE_MISMATCH,
        ACKNOWLEDGEMENT_OLDER_THAN_EDITOR_BASELINE,
        EDITOR_SESSION_PAYLOAD_KIND_MISMATCH;

        public String diagnosticCode() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public FlowManager(Object client, RemotelyServerApi apiClient, ReSyncFlowClientFactory flowClientFactory,
                       TaskScheduler taskScheduler, Clock flowClock, ReSyncFlowClientConfiguration configuration) {
        this(requireRemotelyClient(client), apiClient, ignored -> ReSyncCatalogPublicationCache.deferred(),
            ReSyncStorage.legacy("remotely.creation-journal"), flowClientFactory, configuration);
    }

    public FlowManager(RemotelyClient client, RemotelyServerApi apiClient) {
        this(client, apiClient, null);
    }

    public FlowManager(RemotelyClient client, RemotelyServerApi apiClient,
                       Function<String, ReSyncCatalogPublicationCache> catalogPublicationCacheFactory) {
        this(client, apiClient, catalogPublicationCacheFactory, ReSyncStorage.legacy("remotely.creation-journal"));
    }

    FlowManager(RemotelyClient client, RemotelyServerApi apiClient,
                Function<String, ReSyncCatalogPublicationCache> catalogPublicationCacheFactory,
                ReSyncStorage creationJournalStorage) {
        this(client, apiClient, catalogPublicationCacheFactory, creationJournalStorage,
            ReSyncFlowClientFactory.unavailable(), null);
    }

    private FlowManager(RemotelyClient client, RemotelyServerApi apiClient,
                        Function<String, ReSyncCatalogPublicationCache> catalogPublicationCacheFactory,
                        ReSyncStorage creationJournalStorage, ReSyncFlowClientFactory flowClientFactory,
                        ReSyncFlowClientConfiguration configuration) {
        this.client = client;
        this.remotelyApi = apiClient;
        this.creationJournalStorage = creationJournalStorage != null
            ? creationJournalStorage : ReSyncStorage.legacy("remotely.creation-journal");
        ReSyncFlowClientContext defaultContext = ReSyncFlowClientContext.defaults();
        ReSyncFlowClientConfiguration defaults = configuration == null
            ? new ReSyncFlowClientConfiguration(flowClientFactory, ReSyncConnectionProfileProvider.unavailable(),
                this::notify, defaultContext.nodeRegistry(), defaultContext)
            : configuration;
        ApplicationHost host = getApplicationHost();
        ReSyncFlowClientConfiguration resolved = host == null ? defaults
            : host.configureFlowClient(client, this, flowClientFactory, defaults);
        if (resolved == null) {
            resolved = defaults;
        }
        this.connectionManager = new ReSyncConnectionManager(client, apiClient, catalogPublicationCacheFactory,
            resolved.factory(), resolved.profileProvider(), resolved.notificationSink(), resolved.nodeRegistry(),
            resolved.context());
        this.debugController = new FlowDebugController(this);
        this.worldService = new ReSyncWorldService();
        this.playerService = new ReSyncPlayerService();
        this.connectionManager.setDisconnectListener(this::handleDisconnectedServerFallback);
        this.connectionManager.setConnectionListener(serverId -> {
            if (!creationJournalLoaded) {
                loadCreationJournal();
            }
            resetCreationTransactionsForReconnect(serverId);
            resumeCreationTransactions(serverId);
            ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
            if (attachCoreGraphListener(flowClient)) {
                rebindRetainedResourceActivations(serverId, flowClient);
            }
            ScreenManager.getInstance().execute(() -> {
                ServerConnectionToken token = captureConnectedServerConnectionToken(serverId, flowClient);
                if (token.generation() <= 0L) {
                    return;
                }
                runIfCurrentServerConnection(token, () -> {
                    FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
                    if (studioScreen != null) {
                        studioScreen.prepareLiveStudioWorkspace();
                    }
                });
            });
        });
        for (ReSyncResourceType type : ReSyncResourceType.values()) {
            if (usesJsonResourceStore(type)) {
                jsonResourceStores.put(type, new SyncedResourceCache<>(this::jsonResourceId, this::jsonResourceName));
            }
        }
        creationJournalScheduler.setRemoveOnCancelPolicy(true);
        creationJournalScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        loadCreationJournal();
        INSTANCE.set(this);
    }

    public static FlowManager getInstance() {
        return INSTANCE.get();
    }

    public RemotelyClient getClient() {
        return client;
    }

    public void notify(String title, String message, ReSyncNotificationLevel level) {
        if (client != null && client.getHost() != null) {
            client.getHost().notify(title, message, level);
        }
    }

    public ApplicationHost getApplicationHost() {
        return client == null ? null : client.getHost();
    }

    public ReSyncFlowClient ensureReSyncFlowClient(String serverId) {
        return connectionManager.getFlowClient(serverId);
    }

    public Async<ReSyncFlowClient.ReadinessState> awaitFlowClientConnected(String serverId, boolean initiate) {
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null && flowClient.isConnectedState()) {
            return Async.completed(ReSyncFlowClient.ReadinessState.READY);
        }
        if (!initiate && flowClient == null) {
            return Async.completed(ReSyncFlowClient.ReadinessState.DISCONNECTED);
        }
        return connectionManager.awaitFlowClientConnected(serverId, false);
    }

    public Async<WorldMapSnapshot> requestWorldMapSnapshotAsync(String serverId, String worldName,
                                                               double x, double z, int zoom) {
        return Async.completed(new WorldMapSnapshot());
    }

    private static RemotelyClient requireRemotelyClient(Object client) {
        if (client instanceof RemotelyClient remotelyClient) {
            return remotelyClient;
        }
        throw new IllegalArgumentException("Flow manager requires a Remotely client");
    }

    private static Object studioApi(RemotelyServerApi apiClient) {
        return apiClient == null ? null : apiClient.studioApi();
    }

    public ReSyncFlowClient existingFlowClient(String serverId) {
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        return attachCoreGraphListener(flowClient) ? flowClient : null;
    }

    public ReSyncConnectionManager.ReSyncConnectionProfile reSyncConnectionProfile(String serverId) {
        return connectionManager.getProfile(serverId);
    }

    public void disconnectServerConnection(String serverId) {
        connectionManager.disconnectServerConnection(serverId);
    }

    public void shutdown() {
        requireExternalShutdown(coreUiEffectDepth.get());
        studioOpenGeneration.incrementAndGet();
        List<Map.Entry<String, CoreGraphListenerSubscription>> subscriptions = List.of();
        boolean interrupted = false;
        RuntimeException concurrentFailure = null;
        boolean performShutdown = true;
        synchronized (coreLifecycleLock) {
            if (shutdownComplete) {
                return;
            }
            boolean waitedForShutdown = false;
            while (shutdownInProgress) {
                waitedForShutdown = true;
                try {
                    coreLifecycleLock.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (shutdownComplete) {
                if (interrupted) {
                    TaskIdentities.access.interrupt();
                }
                return;
            }
            if (waitedForShutdown && lastShutdownFailure != null) {
                concurrentFailure = lastShutdownFailure;
                performShutdown = false;
            }
            if (!performShutdown) {
                if (interrupted) {
                    TaskIdentities.access.interrupt();
                }
                throw concurrentFailure;
            }
            shutdownInProgress = true;
            closed = true;
            while (activeCoreUiEffects > 0) {
                try {
                    coreLifecycleLock.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            pendingActivations.keySet().stream()
                .map(ActivationKey::serverId)
                .filter(serverId -> serverId != null && !serverId.isBlank())
                .forEach(pendingShutdownServerIds::add);
            synchronized (resourceActivationLock) {
                pendingActivations.clear();
            }
            failPendingResourceDeletions("Resource Delete Cancelled");
            clearCoreUiTransitions();
            subscriptions = new ArrayList<>(coreGraphListenerSubscriptions.entrySet());
            subscriptions.sort(Map.Entry.comparingByKey());
        }
        RuntimeException shutdownFailure = suspendCreationTransactions();
        shutdownFailure = attemptShutdownCleanup(shutdownFailure, this::shutdownCreationJournalScheduler);
        for (Map.Entry<String, CoreGraphListenerSubscription> entry : subscriptions) {
            try {
                entry.getValue().close();
                synchronized (coreLifecycleLock) {
                    coreGraphListenerSubscriptions.remove(entry.getKey(), entry.getValue());
                }
            } catch (Throwable error) {
                shutdownFailure = appendShutdownFailure(shutdownFailure, error);
            }
        }
        if (handoffCleanupPending) {
            try {
                coreGraphHandoff.remove();
                coreUiEffectDepth.remove();
                handoffCleanupPending = false;
            } catch (Throwable error) {
                shutdownFailure = appendShutdownFailure(shutdownFailure, error);
            }
        }
        if (connectionCleanupPending) {
            try {
                connectionManager.shutdownAll();
            } catch (Throwable error) {
                shutdownFailure = appendShutdownFailure(shutdownFailure, error);
            } finally {
                connectionCleanupPending = connectionManager.hasFlowClients();
            }
        }
        if (cacheCleanupPending) {
            try {
                RuntimeException cacheFailure = clearShutdownCaches();
                if (cacheFailure == null) {
                    pendingShutdownServerIds.clear();
                    cacheCleanupPending = false;
                } else {
                    shutdownFailure = appendShutdownFailure(shutdownFailure, cacheFailure);
                }
            } catch (Throwable error) {
                shutdownFailure = appendShutdownFailure(shutdownFailure, error);
            }
        }
        if (instanceCleanupPending) {
            try {
                INSTANCE.compareAndSet(this, null);
                instanceCleanupPending = INSTANCE.get() == this;
            } catch (Throwable error) {
                shutdownFailure = appendShutdownFailure(shutdownFailure, error);
            }
        }
        boolean cleanupRemaining;
        synchronized (coreLifecycleLock) {
            cleanupRemaining = !coreGraphListenerSubscriptions.isEmpty()
                || handoffCleanupPending
                || connectionCleanupPending
                || cacheCleanupPending
                || instanceCleanupPending;
            shutdownComplete = !cleanupRemaining;
            shutdownInProgress = false;
            lastShutdownFailure = shutdownFailure;
            coreLifecycleLock.notifyAll();
        }
        if (interrupted) {
            TaskIdentities.access.interrupt();
        }
        if (shutdownFailure != null) {
            throw shutdownFailure;
        }
    }

    private void shutdownCreationJournalScheduler() {
        creationJournalRetryScheduled.set(0);
        creationJournalCleanupRetryScheduled.set(0);
        creationJournalLoadQueued.set(0);
        creationJournalCleanupQueued.set(0);
        creationJournalScheduler.shutdownNow();
        creationJournalScheduler.purge();
        List<CreationTransaction> transactions;
        synchronized (creationTransactionLock) {
            transactions = creationTransactions.values().stream().toList();
        }
        transactions.forEach(transaction -> {
            synchronized (transaction) {
                transaction.terminalRetryScheduled = false;
            }
        });
    }

    private static RuntimeException appendShutdownFailure(RuntimeException aggregate, Throwable error) {
        if (error == null) {
            return aggregate;
        }
        RuntimeException failure = error instanceof RuntimeException runtimeException
            ? runtimeException : new IllegalStateException("FlowManager shutdown cleanup failed.", error);
        if (aggregate == null) {
            aggregate = new IllegalStateException("FlowManager shutdown failed.");
        }
        aggregate.addSuppressed(failure);
        return aggregate;
    }

    private RuntimeException suspendCreationTransactions() {
        List<CreationTransaction> transactions;
        synchronized (creationTransactionLock) {
            transactions = creationTransactions.values().stream()
                .sorted((left, right) -> Long.compare(left.sequence, right.sequence))
                .toList();
        }
        for (CreationTransaction transaction : transactions) {
            DesignerSaveNotifications.SaveTicket payloadTicket;
            DesignerSaveNotifications.SaveTicket metadataTicket;
            DesignerSaveNotifications.SaveTicket commandGraphTicket;
            synchronized (transaction) {
                transaction.suspended = true;
                payloadTicket = transaction.payloadTicket;
                metadataTicket = transaction.metadataTicket;
                commandGraphTicket = transaction.commandGraphTicket;
                transaction.payloadTicket = null;
                transaction.metadataTicket = null;
                transaction.commandGraphTicket = null;
                transaction.settling = false;
                transaction.journalSettled = false;
            }
            transaction.durableAdmission.complete(false);
            DesignerSaveNotifications.detachResumable(payloadTicket);
            DesignerSaveNotifications.detachResumable(metadataTicket);
            DesignerSaveNotifications.detachResumable(commandGraphTicket);
        }
        boolean persisted = persistCreationJournalNow();
        if (persisted) {
            return null;
        }
        return new IllegalStateException("The resource creation journal could not be durably persisted during shutdown.");
    }

    @FunctionalInterface
    private interface ShutdownCleanup {
        void run();
    }

    private static RuntimeException attemptShutdownCleanup(RuntimeException aggregate, ShutdownCleanup cleanup) {
        try {
            cleanup.run();
            return aggregate;
        } catch (Throwable error) {
            return appendShutdownFailure(aggregate, error);
        }
    }

    private RuntimeException clearShutdownCaches() {
        RuntimeException failure = null;
        pendingShutdownServerIds.addAll(shutdownServerIds());
        Set<String> serverIds = new TreeSet<>(pendingShutdownServerIds);
        for (String serverId : serverIds) {
            String currentServerId = serverId;
            failure = attemptShutdownCleanup(failure, () -> flowStore.clearForServer(currentServerId));
            failure = attemptShutdownCleanup(failure, () -> guiStore.clearForServer(currentServerId));
            failure = attemptShutdownCleanup(failure, () -> scoreboardStore.clearForServer(currentServerId));
            failure = attemptShutdownCleanup(failure, () -> tabStore.clearForServer(currentServerId));
            failure = attemptShutdownCleanup(failure, () -> customContentStore.clearForServer(currentServerId));
            failure = attemptShutdownCleanup(failure, () -> projectMetadataStore.clearForServer(currentServerId));
            List<Map.Entry<ReSyncResourceType, SyncedResourceCache<JsonObject>>> jsonStores = jsonResourceStores.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
            for (Map.Entry<ReSyncResourceType, SyncedResourceCache<JsonObject>> entry : jsonStores) {
                failure = attemptShutdownCleanup(failure, () -> entry.getValue().clearForServer(currentServerId));
            }
            failure = attemptShutdownCleanup(failure, () -> studioFullEditorSession.clear(currentServerId));
            failure = attemptShutdownCleanup(failure, () -> playerService.clearCache(currentServerId));
            failure = attemptShutdownCleanup(failure, () -> worldService.clearCache(currentServerId));
            failure = attemptShutdownCleanup(failure, () -> {
                WorldGenManager manager = WorldGenManager.getInstance();
                if (manager != null) {
                    manager.clearCache(currentServerId);
                }
            });
            failure = attemptShutdownCleanup(failure, () -> OptionCatalogCache.getInstance().clearRequestsInFlight(currentServerId));
        }
        failure = attemptShutdownCleanup(failure, flowStore::clearAll);
        failure = attemptShutdownCleanup(failure, guiStore::clearAll);
        failure = attemptShutdownCleanup(failure, scoreboardStore::clearAll);
        failure = attemptShutdownCleanup(failure, tabStore::clearAll);
        failure = attemptShutdownCleanup(failure, customContentStore::clearAll);
        failure = attemptShutdownCleanup(failure, projectMetadataStore::clearAll);
        for (Map.Entry<ReSyncResourceType, SyncedResourceCache<JsonObject>> entry : jsonResourceStores.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .toList()) {
            failure = attemptShutdownCleanup(failure, entry.getValue()::clearAll);
        }
        failure = attemptShutdownCleanup(failure, () -> coreGraphUiProjection.restoreAll(
            new CoreGraphUiProjection.StateSnapshot(Map.of(), Map.of())));
        failure = attemptShutdownCleanup(failure, coreGraphDocumentAuthoring::clear);
        failure = attemptShutdownCleanup(failure, coreGraphEditorSessions::clear);
        failure = attemptShutdownCleanup(failure, pendingCoreSessionPreparations::clear);
        failure = attemptShutdownCleanup(failure, pendingCoreTemplateIntents::clear);
        failure = attemptShutdownCleanup(failure, preparingCoreTemplateIntents::clear);
        failure = attemptShutdownCleanup(failure, deferredCoreTemplateIntents::clear);
        failure = attemptShutdownCleanup(failure, coreTemplateResponses::clear);
        failure = attemptShutdownCleanup(failure, () -> serverCapabilities.clear());
        failure = attemptShutdownCleanup(failure, () -> messageLogPages.clear());
        failure = attemptShutdownCleanup(failure, () -> {
            synchronized (triggerBindingsLock) {
                triggerBindings.clear();
            }
        });
        failure = attemptShutdownCleanup(failure, () -> projectCatalogRevisions.clear());
        failure = attemptShutdownCleanup(failure, () -> projectMetadataAuthorityGenerations.clear());
        failure = attemptShutdownCleanup(failure, () -> hydratedProjectCatalogRevisions.clear());
        failure = attemptShutdownCleanup(failure, () -> projectMetadataViews.clear());
        failure = attemptShutdownCleanup(failure, () -> scheduledProjectMetadataHydrations.clear());
        failure = attemptShutdownCleanup(failure, () -> pendingProjectMetadataHydrations.clear());
        failure = attemptShutdownCleanup(failure, projectMetadataHydrations::shutdownNow);
        failure = attemptShutdownCleanup(failure, () -> pendingStudioWorkspaceRefreshes.clear());
        failure = attemptShutdownCleanup(failure, () -> pendingFlowWorkspaceRefreshes.clear());
        failure = attemptShutdownCleanup(failure, () -> {
            synchronized (studioWorkspaceRefreshLock) {
                scheduledStudioWorkspaceRefreshes.clear();
                studioWorkspaceRefreshGenerations.clear();
            }
        });
        failure = attemptShutdownCleanup(failure, () -> {
            synchronized (flowWorkspaceRefreshLock) {
                scheduledFlowWorkspaceRefreshes.clear();
                flowWorkspaceRefreshGenerations.clear();
            }
        });
        failure = attemptShutdownCleanup(failure, () -> pendingStudioDocumentOpeners.clear());
        failure = attemptShutdownCleanup(failure, () -> studioServerTitles.clear());
        failure = attemptShutdownCleanup(failure, () -> loadedProjectMetadataLists.clear());
        failure = attemptShutdownCleanup(failure, () -> pendingProjectMetadataDocuments.clear());
        failure = attemptShutdownCleanup(failure, () -> typedMemberships.clear());
        failure = attemptShutdownCleanup(failure, () -> worldSnapshotGenerations.clear());
        failure = attemptShutdownCleanup(failure, () -> worldGenMembershipVersions.clear());
        failure = attemptShutdownCleanup(failure, () -> projectMembershipRevisions.clear());
        failure = attemptShutdownCleanup(failure, () -> {
            synchronized (resourceActivationLock) {
                pendingActivations.clear();
            }
        });
        failure = attemptShutdownCleanup(failure, () -> closingCoreServers.clear());
        failure = attemptShutdownCleanup(failure, () -> activeCoreEffectsByServer.clear());
        failure = attemptShutdownCleanup(failure, () -> {
            synchronized (serverConnectionGenerationLock) {
                serverConnectionGenerations.clear();
                serverGenerationSources.clear();
                retiringServerConnections.clear();
                settledDisconnectedSources.clear();
            }
        });
        failure = attemptShutdownCleanup(failure, () -> marketplaceImportServerId = null);
        failure = attemptShutdownCleanup(failure, this::clearOverlayState);
        failure = attemptShutdownCleanup(failure, GuiEditOverlayState::clear);
        return failure;
    }

    private Set<String> shutdownServerIds() {
        Set<String> serverIds = new TreeSet<>();
        serverIds.addAll(flowStore.serverIds());
        serverIds.addAll(guiStore.serverIds());
        serverIds.addAll(scoreboardStore.serverIds());
        serverIds.addAll(tabStore.serverIds());
        serverIds.addAll(customContentStore.serverIds());
        serverIds.addAll(projectMetadataStore.serverIds());
        jsonResourceStores.values().stream()
            .flatMap(store -> store.serverIds().stream())
            .forEach(serverIds::add);
        serverIds.addAll(coreGraphListenerSubscriptions.keySet());
        serverIds.addAll(serverCapabilities.keySet());
        serverIds.addAll(messageLogPages.keySet());
        synchronized (triggerBindingsLock) {
            serverIds.addAll(triggerBindings.keySet());
        }
        serverIds.addAll(projectCatalogRevisions.keySet());
        serverIds.addAll(hydratedProjectCatalogRevisions.keySet());
        serverIds.addAll(pendingStudioWorkspaceRefreshes.keySet());
        serverIds.addAll(pendingFlowWorkspaceRefreshes.keySet());
        serverIds.addAll(pendingStudioDocumentOpeners.keySet());
        serverIds.addAll(studioServerTitles.keySet());
        serverIds.addAll(loadedProjectMetadataLists);
        serverIds.addAll(pendingProjectMetadataDocuments);
        serverIds.addAll(closingCoreServers);
        serverIds.addAll(activeCoreEffectsByServer.keySet());
        if (guiOverlayServerId != null && !guiOverlayServerId.isBlank()) {
            serverIds.add(guiOverlayServerId);
        }
        if (editTargetOverlayServerId != null && !editTargetOverlayServerId.isBlank()) {
            serverIds.add(editTargetOverlayServerId);
        }
        if (marketplaceImportServerId != null && !marketplaceImportServerId.isBlank()) {
            serverIds.add(marketplaceImportServerId);
        }
        return serverIds;
    }

    public void openReSyncStudio(String serverId, ClientServerView server, String loaderHint) {
        openReSyncStudio(serverId, server, loaderHint, server != null ? server.name : "");
    }

    public void openReSyncStudio(String serverId, ClientServerView server, String loaderHint, String serverTitle) {
        openReSyncStudio(serverId, server, loaderHint, serverTitle, () -> true);
    }

    public void openReSyncStudio(String serverId, ClientServerView server, String loaderHint, String serverTitle,
                                 BooleanSupplier admission) {
        ReSyncServerIdentity identity = ReSyncServerIdentity.from(serverId, server);
        String actualServerId = identity.serverId();
        ApplicationHost host = getApplicationHost();
        if (host == null) {
            return;
        }
        long generation = studioOpenGeneration.incrementAndGet();
        Async<ReSyncProvisioningService.StartupProbeResult> preparation;
        try {
            preparation = host.prepareReSyncServerContextAsync(actualServerId, server, loaderHint);
        } catch (Throwable failure) {
            reportReSyncPreparationFailure(host, null, failure);
            return;
        }
        if (preparation == null) {
            reportReSyncPreparationFailure(host, null, null);
            return;
        }
        preparation.whenComplete((result, failure) -> ScreenManager.getInstance().execute(() -> {
            if (closed || preparation.isCancelled() || studioOpenGeneration.get() != generation
                || admission == null || !admission.getAsBoolean()) {
                return;
            }
            if (failure != null || result == null) {
                reportReSyncPreparationFailure(host, result, failure);
                return;
            }
            connectionManager.resolveAndStoreProfile(actualServerId, server).whenComplete((resolution, resolutionFailure) ->
                ScreenManager.getInstance().execute(() -> {
                    if (closed || studioOpenGeneration.get() != generation || admission == null
                        || !admission.getAsBoolean()) {
                        return;
                    }
                    if (resolutionFailure != null) {
                        reportReSyncPreparationFailure(host, result, resolutionFailure);
                        return;
                    }
                    String resolvedServerId = resolution != null && resolution.available()
                        && resolution.serverId() != null && !resolution.serverId().isBlank()
                        ? resolution.serverId() : actualServerId;
                    openResolvedReSyncStudio(resolvedServerId, server, loaderHint, serverTitle);
                }));
        }));
    }

    private void reportReSyncPreparationFailure(ApplicationHost host,
                                                ReSyncProvisioningService.StartupProbeResult result,
                                                Throwable failure) {
        String message = result == null ? "" : result.readinessMessage();
        if ((message == null || message.isBlank()) && failure != null) {
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            message = cause.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = result == null ? "ReSync Unavailable" : switch (result.status()) {
                case SERVER_STOPPED -> "Server Is Offline. Start The Server To Use ReSync";
                case SETUP -> "ReSync Setup Required";
                case NOT_SUPPORTED -> "ReSync Is Not Supported On This Server";
                case SECURE_CONNECTION_REPAIR -> "ReSync Connection Requires Repair";
                case MIGRATION_REQUIRED -> "ReSync Migration Required";
                default -> "ReSync Unavailable";
            };
        }
        host.reportReSyncPreparationFailure(normalizeReSyncNotificationMessage(message));
    }

    private void openResolvedReSyncStudio(String actualServerId, ClientServerView server, String loaderHint,
                                          String serverTitle) {
        marketplaceImportServerId = actualServerId;
        if (actualServerId != null && !actualServerId.isBlank() && serverTitle != null && !serverTitle.isBlank()) {
            studioServerTitles.put(actualServerId, serverTitle);
        }
        FlowEditorScreen existingScreen = FlowEditorScreen.getStudioScreen(actualServerId);
        if (existingScreen != null) {
            activateStudioScreen(existingScreen, false);
            requestInitialFlowData(actualServerId, false);
            return;
        }
        FlowEditorScreen screen = new FlowEditorScreen(new FlowGraph(), actualServerId, ScreenManager.getInstance().getCurrentScreen(), server, loaderHint, serverTitle).enableStudioMode();
        client.getHost().setScreen(screen);
    }

    public void openLiveReSyncStudio(ReSyncLiveServerSession session) {
        if (session == null || session.serverId() == null || session.serverId().isBlank()) {
            new Notification("ReSync", "ReSync Unavailable", Notification.Type.WARN);
            return;
        }
        marketplaceImportServerId = session.serverId();
        activateLiveReSyncSession(session);
        FlowEditorScreen existingScreen = FlowEditorScreen.getStudioScreen(session.serverId());
        if (existingScreen != null) {
            activateStudioScreen(existingScreen, false);
            requestInitialFlowData(session.serverId(), false);
            flushPendingStudioEditTarget(session.serverId());
            return;
        }
        FlowEditorScreen screen = new FlowEditorScreen(new FlowGraph(), session.serverId(), ScreenManager.getInstance().getCurrentScreen(), null, "", session.displayName()).enableStudioMode();
        screen.prepareLiveStudioWorkspace();
        client.getHost().setScreen(screen);
    }

    public void openLiveStudioDesigner(ReSyncLiveServerSession session, String type, String id, boolean fullEditor) {
        openLiveStudioDesigner(session, type, id, fullEditor, null);
    }

    public void openLiveStudioDesigner(ReSyncLiveServerSession session, String type, String id, boolean fullEditor, Object parent) {
        studioFullEditorSession.open(session, new StudioEditTarget(type, id, fullEditor), parent);
    }

    public void createLiveStudioAdvancementTree(ReSyncLiveServerSession session, boolean fullEditor) {
        createLiveStudioAdvancementTree(session, fullEditor, null);
    }

    public void createLiveStudioAdvancementTree(ReSyncLiveServerSession session, boolean fullEditor, Object parent) {
        if (session == null || session.serverId() == null || session.serverId().isBlank()) {
            return;
        }
        marketplaceImportServerId = session.serverId();
        activateLiveReSyncSession(session);
        String id = nextAdvancementTreeId(session.serverId());
        JsonObject tree = createJsonResource(session.serverId(), ReSyncResourceType.ADVANCEMENT_TREE, id, ReSyncResourceType.ADVANCEMENT_TREE.defaultFolder());
        if (tree == null) {
            return;
        }
        saveJsonResource(session.serverId(), ReSyncResourceType.ADVANCEMENT_TREE, tree);
        ProjectMetadataEdit metadata = editProjectMetadata(session.serverId());
        metadata.putResource(ReSyncResourceDragPayload.ADVANCEMENT_TREE, id, id, ReSyncResourceType.ADVANCEMENT_TREE.defaultFolder(),
            metadata.nextResourceSortOrder());
        saveProjectMetadata(metadata, true);
        openLiveStudioDesigner(session, ReSyncResourceDragPayload.ADVANCEMENT_TREE, id, fullEditor, parent);
    }

    public ReSyncFlowClient activateLiveReSyncSession(ReSyncLiveServerSession session) {
        if (closed || session == null || session.serverId() == null || session.serverId().isBlank()) {
            return null;
        }
        marketplaceImportServerId = session.serverId();
        if (session.displayName() != null && !session.displayName().isBlank()) {
            studioServerTitles.put(session.serverId(), session.displayName());
        }
        ReSyncFlowClient flowClient = connectionManager.activateLiveSession(session);
        return attachCoreGraphListener(flowClient) ? flowClient : null;
    }

    public void clearLiveReSyncSession(String serverId) {
        if (serverId == null || !serverId.startsWith("live:")) {
            return;
        }
        if (serverId.equals(guiOverlayServerId) || serverId.equals(editTargetOverlayServerId)) {
            clearOverlayState();
            GuiEditOverlayState.clear();
        }
        closeServerConnection(serverId);
    }

    public void ensureFlowClientForStartup(String serverId, ClientServerView server, boolean showNotifications) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        if (connectionManager.getProfile(serverId) != null) {
            ensureSubscribedFlowClient(serverId, showNotifications);
            return;
        }
        connectionManager.resolveAndStoreProfile(serverId, server).thenAccept(resolution -> {
            if (!resolution.available()) {
                return;
            }
            ScreenManager.getInstance().execute(() -> {
                if (!closed) {
                    ensureSubscribedFlowClient(serverId, showNotifications);
                }
            });
        });
    }

    public boolean isFlowClientConnected(String serverId) {
        return connectionManager.isFlowClientConnected(serverId);
    }

    public ServerConnectionToken captureServerConnectionToken(String serverId) {
        return captureServerConnectionToken(serverId, serverId != null ? connectionManager.getFlowClient(serverId) : null);
    }

    public ServerConnectionToken currentServerConnectionToken(String serverId) {
        ReSyncFlowClient source = serverId != null ? connectionManager.getFlowClient(serverId) : null;
        if (serverId == null || serverId.isBlank() || source == null || closed
            || settledDisconnectedSources.contains(source) || retiringServerConnections.contains(serverId)) {
            return new ServerConnectionToken(serverId, source, 0L);
        }
        long generation = serverConnectionGenerations.getOrDefault(serverId, 0L);
        ReSyncFlowClient generationSource = serverGenerationSources.get(serverId);
        if (generation < 1L && (generationSource == null || generationSource == source)) {
            ReSyncFlowClient published = serverGenerationSources.putIfAbsent(serverId, source);
            if (published == null || published == source) {
                generation = serverConnectionGenerations.computeIfAbsent(serverId, ignored -> 1L);
            }
        }
        if (closed || settledDisconnectedSources.contains(source) || retiringServerConnections.contains(serverId)
            || generation < 1L || serverGenerationSources.get(serverId) != source) {
            return new ServerConnectionToken(serverId, source, 0L);
        }
        return new ServerConnectionToken(serverId, source, generation);
    }

    public ServerConnectionToken captureServerConnectionToken(String serverId, ReSyncFlowClient source) {
        if (serverId == null || serverId.isBlank() || source == null) {
            return new ServerConnectionToken(serverId, source, 0L);
        }
        ServerConnectionToken[] captured = {new ServerConnectionToken(serverId, source, 0L)};
        synchronized (serverConnectionGenerationLock) {
            if (closed || settledDisconnectedSources.contains(source)) {
                return captured[0];
            }
            connectionManager.withCurrentFlowClient(serverId, source, ignored -> {
                if (retiringServerConnections.contains(serverId)) {
                    return;
                }
                long generation = serverConnectionGenerations.getOrDefault(serverId, 0L);
                ReSyncFlowClient previous = serverGenerationSources.get(serverId);
                if (generation <= 0L) {
                    generation = 1L;
                } else if (previous != null && previous != source) {
                    clearPendingWorkspaceRefreshes(serverId);
                    coreGraphDocumentAuthoring.clearServer(serverId);
                    markCoreGraphEditorSessionsStale(serverId);
                    generation = generation == Long.MAX_VALUE ? 1L : generation + 1L;
                }
                serverConnectionGenerations.put(serverId, generation);
                serverGenerationSources.put(serverId, source);
                captured[0] = new ServerConnectionToken(serverId, source, generation);
            });
        }
        return captured[0];
    }

    private ServerConnectionToken captureConnectedServerConnectionToken(String serverId, ReSyncFlowClient source) {
        if (serverId == null || serverId.isBlank() || source == null) {
            return new ServerConnectionToken(serverId, source, 0L);
        }
        ServerConnectionToken[] captured = {new ServerConnectionToken(serverId, source, 0L)};
        synchronized (serverConnectionGenerationLock) {
            if (closed) {
                return captured[0];
            }
            connectionManager.withCurrentFlowClient(serverId, source, ignored -> {
                if (!source.isConnectedState()) {
                    return;
                }
                settledDisconnectedSources.remove(source);
                retiringServerConnections.remove(serverId);
                long generation = serverConnectionGenerations.getOrDefault(serverId, 0L);
                ReSyncFlowClient previous = serverGenerationSources.get(serverId);
                if (generation <= 0L) {
                    generation = 1L;
                } else if (previous != null && previous != source) {
                    clearPendingWorkspaceRefreshes(serverId);
                    coreGraphDocumentAuthoring.clearServer(serverId);
                    markCoreGraphEditorSessionsStale(serverId);
                    generation = generation == Long.MAX_VALUE ? 1L : generation + 1L;
                }
                serverConnectionGenerations.put(serverId, generation);
                serverGenerationSources.put(serverId, source);
                captured[0] = new ServerConnectionToken(serverId, source, generation);
            });
        }
        return captured[0];
    }

    private long invalidateServerConnectionGeneration(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return 0L;
        }
        synchronized (serverConnectionGenerationLock) {
            return invalidateServerConnectionGenerationLocked(serverId);
        }
    }

    private long invalidateServerConnectionGenerationLocked(String serverId) {
        long generation = serverConnectionGenerations.getOrDefault(serverId, 0L);
        long next = generation == Long.MAX_VALUE ? 1L : generation + 1L;
        serverConnectionGenerations.put(serverId, next);
        coreGraphDocumentAuthoring.clearServer(serverId);
        markCoreGraphEditorSessionsStale(serverId);
        synchronized (coreLifecycleLock) {
            clearCoreUiTransitions(serverId, generation);
        }
        return next;
    }

    void handleFlowClientDisconnected(ReSyncFlowClient source) {
        if (source == null || source.getServerId() == null || source.getServerId().isBlank()) {
            return;
        }
        String serverId = source.getServerId();
        if (!settledDisconnectedSources.add(source)) {
            return;
        }
        boolean settled = false;
        boolean[] invalidated = {false};
        synchronized (serverConnectionGenerationLock) {
            settled = connectionManager.withCurrentFlowClient(serverId, source, ignored -> {
                if (source.isConnectedState()) {
                    return;
                }
                clearPendingWorkspaceRefreshes(serverId);
                rollbackResourceActivationsNow(serverId, source);
                playerService.clearCache(serverId);
                invalidateServerConnectionGenerationLocked(serverId);
                invalidated[0] = true;
            });
        }
        if (!settled || !invalidated[0]) {
            settledDisconnectedSources.remove(source);
        }
        if (invalidated[0]) {
            resetCreationTransactionsForReconnect(serverId);
        }
    }

    private void handleDisconnectedServerFallback(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ReSyncFlowClient source = connectionManager.getFlowClient(serverId);
        if (source != null && source.isConnectedState()) {
            return;
        }
        if (source != null && !settledDisconnectedSources.add(source)) {
            return;
        }
        resetCreationTransactionsForReconnect(serverId);
        clearPendingWorkspaceRefreshes(serverId);
        rollbackResourceActivationsNow(serverId, source);
        playerService.clearCache(serverId);
        ScreenManager.getInstance().execute(() -> finishDisconnectedServer(serverId, source));
    }

    private void finishDisconnectedServer(String serverId, ReSyncFlowClient source) {
        boolean settled;
        boolean[] invalidated = {false};
        synchronized (serverConnectionGenerationLock) {
            if (source == null) {
                if (connectionManager.getFlowClient(serverId) == null) {
                    invalidateServerConnectionGenerationLocked(serverId);
                    invalidated[0] = true;
                }
                settled = true;
            } else {
                settled = connectionManager.withCurrentFlowClient(serverId, source,
                    ignored -> {
                        if (!source.isConnectedState()) {
                            invalidateServerConnectionGenerationLocked(serverId);
                            invalidated[0] = true;
                        }
                    });
            }
        }
        if ((!settled || !invalidated[0]) && source != null) {
            settledDisconnectedSources.remove(source);
        }
        if (invalidated[0]) {
            resetCreationTransactionsForReconnect(serverId);
        }
    }

    private void resetCreationTransactionsForReconnect(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        List<CreationReconnectReset> resets = new ArrayList<>();
        creationTransactions.values().stream()
            .filter(transaction -> transaction.key.serverId().equals(serverId))
            .forEach(transaction -> {
                List<DesignerSaveNotifications.SaveTicket> tickets = new ArrayList<>(3);
                synchronized (transaction) {
                    if (transaction.suspended || transaction.isTerminal()) {
                        return;
                    }
                    transaction.reconnectResetPending = true;
                    if (transaction.payloadTicket != null) {
                        tickets.add(transaction.payloadTicket);
                        transaction.payloadTicket = null;
                    }
                    if (transaction.metadataTicket != null) {
                        tickets.add(transaction.metadataTicket);
                        transaction.metadataTicket = null;
                    }
                    if (transaction.commandGraphTicket != null) {
                        tickets.add(transaction.commandGraphTicket);
                        transaction.commandGraphTicket = null;
                    }
                    transaction.settling = false;
                    transaction.commandFlowClient = null;
                    transaction.commandConnectionToken = null;
                    transaction.commandAuthorityEpoch = 0L;
                    transaction.metadataAuthorityGeneration = 0L;
                    transaction.triggerPreparationGeneration++;
                    transaction.triggerPreparationQueued = false;
                }
                resets.add(new CreationReconnectReset(transaction, List.copyOf(tickets)));
            });
        for (CreationReconnectReset reset : resets) {
            reset.tickets().forEach(DesignerSaveNotifications::detachResumable);
            synchronized (reset.transaction()) {
                reset.transaction().reconnectResetPending = false;
            }
        }
    }

    private long beginServerRetirement(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return 0L;
        }
        synchronized (serverConnectionGenerationLock) {
            retiringServerConnections.add(serverId);
            clearPendingWorkspaceRefreshes(serverId);
            long generation = serverConnectionGenerations.getOrDefault(serverId, 0L);
            long next = generation == Long.MAX_VALUE ? 1L : generation + 1L;
            serverConnectionGenerations.put(serverId, next);
            return next;
        }
    }

    private boolean isCurrentServerConnectionGenerationLocked(ServerConnectionToken token) {
        return token != null && token.serverId() != null && !token.serverId().isBlank()
            && token.source() != null && token.generation() > 0L && !closed
            && serverConnectionGenerations.getOrDefault(token.serverId(), 0L) == token.generation()
            && serverGenerationSources.get(token.serverId()) == token.source()
            && !retiringServerConnections.contains(token.serverId());
    }

    private boolean isCurrentServerConnectionLocked(ServerConnectionToken token) {
        return isCurrentServerConnectionGenerationLocked(token)
            && connectionManager.getFlowClient(token.serverId()) == token.source()
            && connectionManager.isFlowClientConnected(token.serverId());
    }

    public boolean isCurrentServerConnection(ServerConnectionToken token) {
        return isCurrentServerConnectionGenerationLocked(token)
            && connectionManager.getFlowClient(token.serverId()) == token.source()
            && token.source().isConnectedState();
    }

    public boolean runIfCurrentServerConnection(ServerConnectionToken token, Runnable action) {
        if (action == null) {
            return false;
        }
        synchronized (serverConnectionGenerationLock) {
            if (!isCurrentServerConnectionGenerationLocked(token)) {
                return false;
            }
            boolean[] ran = {false};
            boolean admitted = connectionManager.withCurrentFlowClient(token.serverId(), token.source(),
                source -> isCurrentServerConnectionGenerationLocked(token) && source.isConnectedState(),
                ignored -> {
                    if (!isCurrentServerConnectionGenerationLocked(token) || !token.source().isConnectedState()) {
                        return;
                    }
                    ran[0] = true;
                    action.run();
                });
            return admitted && ran[0];
        }
    }

    public ReSyncFlowClient.ConnectionState getFlowClientConnectionState(String serverId) {
        return connectionManager.getFlowClientConnectionState(serverId);
    }

    public ReSyncFlowClient ensureFlowClient(String serverId) {
        return ensureSubscribedFlowClient(serverId);
    }

    public Async<ReSyncFlowClient> ensureFlowClientAsync(String serverId) {
        return connectionManager.ensureFlowClientAsync(serverId, true).thenApply(flowClient -> {
            if (attachCoreGraphListener(flowClient)) {
                return flowClient;
            }
            ReSyncFlowClient current = connectionManager.getFlowClient(serverId);
            return attachCoreGraphListener(current) ? current : null;
        });
    }

    public <T> Async<FlowClientActionSettlement<T>> withFlowClient(
        String serverId, Function<ReSyncFlowClient, T> action) {
        Objects.requireNonNull(action, "Flow client action is required");
        Async<FlowClientActionSettlement<T>> settlement = Async.pending();
        if (serverId == null || serverId.isBlank() || closed) {
            settlement.complete(new FlowClientActionSettlement<>(FlowClientActionStatus.UNAVAILABLE, null));
            return settlement;
        }
        admitFlowClientAction(serverId, action, settlement);
        return settlement;
    }

    private <T> void admitFlowClientAction(String serverId, Function<ReSyncFlowClient, T> action,
                                           Async<FlowClientActionSettlement<T>> settlement) {
        if (settlement.isDone()) {
            return;
        }
        ensureFlowClientAsync(serverId).whenComplete((candidate, error) -> {
            if (settlement.isDone()) {
                return;
            }
            if (error != null) {
                settlement.fail(error);
                return;
            }
            if (candidate == null) {
                settlement.complete(new FlowClientActionSettlement<>(FlowClientActionStatus.UNAVAILABLE, null));
                return;
            }
            BrowserSafeState.ReferenceValue<T> value = new BrowserSafeState.ReferenceValue<>();
            try {
                if (withCurrentFlowClientNow(serverId, candidate, current -> value.set(action.apply(current)))) {
                    settlement.complete(new FlowClientActionSettlement<>(FlowClientActionStatus.DELIVERED, value.get()));
                    return;
                }
                retryFlowClientAction(() -> admitFlowClientAction(serverId, action, settlement));
            } catch (Throwable failure) {
                settlement.fail(failure);
            }
        });
    }

    protected void retryFlowClientAction(Runnable action) {
        if (closed) {
            action.run();
            return;
        }
        ScreenManager.getInstance().execute(action);
    }

    public boolean withCurrentFlowClientNow(String serverId, ReSyncFlowClient expected, Consumer<ReSyncFlowClient> action) {
        return connectionManager.withCurrentFlowClientNow(serverId, expected, action);
    }

    private ReSyncFlowClient ensureSubscribedFlowClient(String serverId) {
        return ensureSubscribedFlowClient(serverId, true);
    }

    private ReSyncFlowClient ensureSubscribedFlowClient(String serverId, boolean showNotifications) {
        ReSyncFlowClient flowClient = connectionManager.ensureFlowClient(serverId, showNotifications);
        if (attachCoreGraphListener(flowClient)) {
            return flowClient;
        }
        ReSyncFlowClient current = connectionManager.getFlowClient(serverId);
        return attachCoreGraphListener(current) ? current : null;
    }

    public FlowDebugController getDebugController() {
        return debugController;
    }

    public void closeServerConnection(String serverId) {
        ReSyncFlowClient retiringSource = connectionManager.getFlowClient(serverId);
        long retirementGeneration = beginServerRetirement(serverId);
        connectionManager.closeServerConnectionAtomically(serverId, removed -> retireCoreGraphServer(serverId, removed), () -> {
            clearRetiredServerProjection(serverId, retiringSource, retirementGeneration);
        });
    }

    private boolean ownsRetiredProjectionCleanup(String serverId, ReSyncFlowClient retiringSource,
                                                  long retirementGeneration) {
        if (serverId == null || serverId.isBlank() || retirementGeneration <= 0L) {
            return false;
        }
        synchronized (serverConnectionGenerationLock) {
            if (!retiringServerConnections.contains(serverId)
                || serverConnectionGenerations.getOrDefault(serverId, 0L) != retirementGeneration
                || retiringSource != null && serverGenerationSources.get(serverId) != retiringSource) {
                return false;
            }
            ReSyncFlowClient current = connectionManager.getFlowClient(serverId);
            return current == null || current == retiringSource;
        }
    }

    private void clearRetiredServerProjection(String serverId, ReSyncFlowClient retiringSource,
                                               long retirementGeneration) {
        if (!ownsRetiredProjectionCleanup(serverId, retiringSource, retirementGeneration)) {
            return;
        }
        flowStore.clearForServer(serverId);
        coreGraphDocumentAuthoring.clearServer(serverId);
        clearCoreGraphEditorSessions(serverId);
        guiStore.clearForServer(serverId);
        scoreboardStore.clearForServer(serverId);
        tabStore.clearForServer(serverId);
        customContentStore.clearForServer(serverId);
        projectMetadataStore.clearForServer(serverId);
        jsonResourceStores.values().forEach(store -> store.clearForServer(serverId));
        serverCapabilities.remove(serverId);
        messageLogPages.remove(serverId);
        synchronized (triggerBindingsLock) {
            triggerBindings.remove(serverId);
        }
        projectCatalogRevisions.remove(serverId);
        projectMetadataAuthorityGenerations.remove(serverId);
        hydratedProjectCatalogRevisions.remove(serverId);
        projectMetadataViews.remove(serverId);
        scheduledProjectMetadataHydrations.remove(serverId);
        pendingProjectMetadataHydrations.remove(serverId);
        clearPendingWorkspaceRefreshes(serverId);
        pendingStudioDocumentOpeners.remove(serverId);
        studioServerTitles.remove(serverId);
        loadedProjectMetadataLists.remove(serverId);
        pendingProjectMetadataDocuments.remove(serverId);
        typedMemberships.keySet().removeIf(key -> serverId.equals(key.serverId()));
        worldSnapshotGenerations.remove(serverId);
        worldGenMembershipVersions.remove(serverId);
        advanceProjectMembershipRevision(serverId);
        synchronized (resourceActivationLock) {
            pendingActivations.keySet().removeIf(key -> serverId.equals(key.serverId()));
        }
        playerService.clearCache(serverId);
        worldService.clearCache(serverId);
        WorldGenManager manager = WorldGenManager.getInstance();
        if (manager != null) {
            manager.clearCache(serverId);
        }
        OptionCatalogCache.getInstance().clearRequestsInFlight(serverId);
        synchronized (coreLifecycleLock) {
            closingCoreServers.remove(serverId);
            activeCoreEffectsByServer.remove(serverId);
        }
        if (serverId.equals(marketplaceImportServerId)) {
            marketplaceImportServerId = null;
        }
        if (serverId.equals(guiOverlayServerId) || serverId.equals(editTargetOverlayServerId)) {
            clearOverlayState();
            GuiEditOverlayState.clear();
        }
    }

    private boolean synchronizeAuthorityEpoch(String serverId) {
        return synchronizeAuthorityEpoch(serverId, false);
    }

    private boolean synchronizeLegacyReadAuthorityEpoch(String serverId) {
        return synchronizeAuthorityEpoch(serverId, true);
    }

    private boolean synchronizeAuthorityEpoch(String serverId, boolean legacyRead) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            return false;
        }
        long authorityEpoch = flowClient.resourceRevisionReconciler().authorityEpoch(serverId);
        if (legacyRead && authorityEpoch == 0L && flowClient.legacyReadCompatibilityAllowed()) {
            if (coreGraphUiProjection.authorityEpoch(serverId) > 0L) {
                resetAuthoritativeCaches(serverId);
                coreGraphUiProjection.clearServer(serverId);
            }
            return true;
        }
        CoreGraphUiProjection.EpochDecision decision = coreGraphUiProjection.observeAuthorityEpoch(serverId,
            authorityEpoch);
        if (!decision.accepted()) {
            return false;
        }
        if (decision.advanced()) {
            resetAuthoritativeCaches(serverId);
        }
        return true;
    }

    private void resetAuthoritativeCaches(String serverId) {
        flowStore.clearAuthoritativeForServer(serverId);
        coreGraphDocumentAuthoring.clearServer(serverId);
        markCoreGraphEditorSessionsStale(serverId);
        guiStore.clearAuthoritativeForServer(serverId);
        scoreboardStore.clearAuthoritativeForServer(serverId);
        tabStore.clearAuthoritativeForServer(serverId);
        customContentStore.clearAuthoritativeForServer(serverId);
        projectMetadataStore.clearAuthoritativeForServer(serverId);
        jsonResourceStores.values().forEach(store -> store.clearAuthoritativeForServer(serverId));
        serverCapabilities.remove(serverId);
        messageLogPages.remove(serverId);
        projectCatalogRevisions.put(serverId, projectCatalogRevision.incrementAndGet());
        projectMetadataAuthorityGenerations.remove(serverId);
        hydratedProjectCatalogRevisions.remove(serverId);
        projectMetadataViews.remove(serverId);
        scheduledProjectMetadataHydrations.remove(serverId);
        pendingProjectMetadataHydrations.remove(serverId);
        loadedProjectMetadataLists.remove(serverId);
        typedMemberships.keySet().removeIf(key -> serverId.equals(key.serverId()));
        worldSnapshotGenerations.remove(serverId);
        worldGenMembershipVersions.remove(serverId);
        advanceProjectMembershipRevision(serverId);
        OptionCatalogCache.getInstance().markServerStale(serverId);
    }

    private boolean attachCoreGraphListener(ReSyncFlowClient flowClient) {
        if (flowClient == null || flowClient.getServerId() == null || flowClient.getServerId().isBlank()) {
            return false;
        }
        String serverId = flowClient.getServerId();
        boolean[] attached = {false};
        CoreGraphListenerSubscription[] retired = {null};
        boolean currentOwner;
        synchronized (serverConnectionGenerationLock) {
            currentOwner = connectionManager.withCurrentFlowClient(serverId, flowClient, ignored -> {
                synchronized (coreLifecycleLock) {
                    if (closed) {
                        return;
                    }
                    CoreGraphListenerSubscription current = coreGraphListenerSubscriptions.get(serverId);
                    if (current != null && current.source() == flowClient) {
                        closingCoreServers.remove(serverId);
                        attached[0] = true;
                        return;
                    }
                    ReSyncFlowClient generationSource = serverGenerationSources.get(serverId);
                    if ((current != null && current.source() != flowClient)
                        || (generationSource != null && generationSource != flowClient)) {
                        clearPendingWorkspaceRefreshes(serverId);
                    }
                    closingCoreServers.add(serverId);
                    clearCoreUiTransitions(serverId);
                    boolean interrupted = false;
                    while (activeCoreEffectsByServer.containsKey(serverId)) {
                        try {
                            coreLifecycleLock.wait();
                        } catch (InterruptedException exception) {
                            interrupted = true;
                        }
                    }
                    if (closed) {
                        if (interrupted) {
                            TaskIdentities.access.interrupt();
                        }
                        return;
                    }
                    long generation = coreGraphListenerGeneration.incrementAndGet();
                    ReSyncFlowClient.CoreGraphResourceSubscription resourceSubscription = flowClient.subscribeCoreGraphResource(
                        transition -> onCoreGraphResourceTransition(flowClient, generation, transition));
                    ReSyncFlowClient.CoreGraphListSubscription listSubscription = flowClient.subscribeCoreGraphList(
                        snapshot -> onCoreGraphListComplete(flowClient, generation, snapshot));
                    coreGraphListenerSubscriptions.put(serverId, new CoreGraphListenerSubscription(flowClient, generation,
                        resourceSubscription, listSubscription));
                    closingCoreServers.remove(serverId);
                    retired[0] = current;
                    attached[0] = true;
                    if (interrupted) {
                        TaskIdentities.access.interrupt();
                    }
                }
            });
        }
        if (retired[0] != null) {
            retired[0].close();
        }
        return currentOwner && attached[0];
    }

    private void retireCoreGraphServer(String serverId, ReSyncFlowClient source) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        CoreGraphListenerSubscription subscription;
        boolean interrupted = false;
        synchronized (serverConnectionGenerationLock) {
            synchronized (coreLifecycleLock) {
                closingCoreServers.add(serverId);
                clearCoreUiTransitions(serverId);
                while (activeCoreEffectsByServer.containsKey(serverId)) {
                    try {
                        coreLifecycleLock.wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                CoreGraphListenerSubscription current = coreGraphListenerSubscriptions.get(serverId);
                subscription = current != null && (source == null || current.source() == source)
                    && coreGraphListenerSubscriptions.remove(serverId, current) ? current : null;
            }
        }
        if (subscription != null) {
            subscription.close();
        }
        coreGraphDocumentAuthoring.clearServer(serverId);
        clearCoreGraphEditorSessions(serverId);
        coreGraphUiProjection.clearServer(serverId);
        if (interrupted) {
            TaskIdentities.access.interrupt();
        }
    }

    private void onCoreGraphResourceTransition(ReSyncFlowClient source, long generation,
                                               ReSyncFlowClient.CoreGraphResourceTransition transition) {
        CoreGraphHandoff handoff = CoreGraphHandoff.from(source, generation, transition);
        coreGraphHandoff.set(handoff);
        if (!ownsCoreGraphListener(source, generation, transition)) {
            return;
        }
        enqueueCoreUiTransition(handoff, () -> {
            String serverId = transition.resource().serverId().canonicalText();
            if (!synchronizeAuthorityEpoch(serverId)) {
                return;
            }
            CoreGraphUiProjection.Snapshot authorityBefore = coreGraphUiProjection.snapshot(serverId, transition.type(),
                transition.resource().id());
            long presentationBefore = projectMetadataStamp(serverId);
            coreGraphUiProjection.apply(transition);
            boolean stableAuthorityAndPresentation = authorityBefore.equals(coreGraphUiProjection.snapshot(serverId,
                transition.type(), transition.resource().id())) && presentationBefore == projectMetadataStamp(serverId);
            ReSyncFlowClient.traceLifecycle(serverId, "core_ui_projection_applied", "serverId", serverId,
                "resourceKey", transition.type().typeId() + ":" + transition.resource().id(), "requestId", "event",
                "mutationId", transition.projection().resourceDocument() != null
                    ? transition.projection().resourceDocument().mutationId() : null,
                "generation", generation, "authorityEpoch", coreGraphUiProjection.authorityEpoch(serverId),
                "revision", transition.projection().revision(), "cacheOutcome", transition.status());
            revalidateCoreGraphEditorSession(transition.resource(), source, generation);
            source.coreGraphSessionProjected(transition.resource());
            resumeCreationTransactions(serverId);
            if (transition.tombstoned()) {
                confirmResourceDeletedNow(serverId, transition.type(), transition.resource().id());
                return;
            }
            if (transition.status() == GraphResourceCache.Status.DUPLICATE && stableAuthorityAndPresentation) {
                return;
            }
            invalidateProjectCatalog(serverId);
            if (!hasPendingCreationForServer(serverId)) {
                refreshFlowWorkspaceNow(serverId, new FlowWorkspaceRefreshSnapshot(true, true, Set.of()));
            }
        });
    }

    private void onCoreGraphListComplete(ReSyncFlowClient source, long generation,
                                         ReSyncFlowClient.CoreGraphListSnapshot snapshot) {
        if (source == null || snapshot == null || !snapshot.authoritative() || !snapshot.type().isGraph()
            || !source.getServerId().equals(snapshot.serverId().canonicalText())) {
            return;
        }
        CoreGraphOwnerToken owner = new CoreGraphOwnerToken(source, generation, source.getServerId());
        if (!ownsCoreGraphOwnerToken(owner)) {
            return;
        }
        enqueueCoreUiTransition(owner, () -> {
            ServerConnectionToken token = captureServerConnectionToken(owner.serverId(), owner.source());
            if (!isCurrentServerConnection(token)) {
                return;
            }
            long before = projectMetadataStamp(owner.serverId());
            List<String> liveIds = snapshot.liveResources().stream().map(ServerResourceLocator::id).sorted().toList();
            publishTypedMembership(token, snapshot.type(), liveIds);
            if (projectMetadataStamp(owner.serverId()) == before) {
                return;
            }
            invalidateProjectCatalog(owner.serverId());
            if (!hasPendingCreationForServer(owner.serverId())) {
                refreshFlowWorkspaceNow(owner.serverId(), new FlowWorkspaceRefreshSnapshot(true, true, Set.of()));
            }
        });
    }

    private void enqueueCoreUiTransition(String serverId, Runnable transition) {
        enqueueCoreUiTransition(serverId, transition, false);
    }

    private void enqueueCoreUiTransition(String serverId, Runnable transition, boolean ownerManaged) {
        if (serverId == null || serverId.isBlank() || transition == null) {
            return;
        }
        boolean schedule = false;
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        synchronized (serverConnectionGenerationLock) {
            if (!ownsCoreUiTransitionGenerationLocked(token)) {
                return;
            }
            synchronized (coreLifecycleLock) {
                if (closed || closingCoreServers.contains(serverId)) {
                    return;
                }
                synchronized (coreUiTransitionLock) {
                    ServerConnectionToken queuedGeneration = coreUiTransitionGenerations.get(serverId);
                    if (queuedGeneration != null && !sameCoreUiTransitionGeneration(queuedGeneration, token)) {
                        pendingCoreUiTransitions.remove(serverId);
                        scheduledCoreUiTransitions.remove(serverId);
                        coreUiTransitionGenerations.remove(serverId);
                    }
                    pendingCoreUiTransitions.computeIfAbsent(serverId, ignored -> new ArrayList<>())
                        .add(new CoreUiTransition(transition, ownerManaged, token));
                    schedule = scheduledCoreUiTransitions.add(serverId);
                    if (schedule) {
                        coreUiTransitionGenerations.put(serverId, token);
                    }
                }
            }
        }
        if (schedule) {
            ScreenManager.getInstance().execute(() -> runScheduledCoreUiTransition(serverId, token));
        }
    }

    private void enqueueCoreUiTransition(CoreGraphHandoff handoff, Runnable transition) {
        if (!ownsCoreGraphHandoff(handoff)) {
            return;
        }
        enqueueCoreUiTransition(handoff.serverId(), () -> runCoreGraphOwnerEffect(handoff.ownerToken(), transition), true);
    }

    private void enqueueCoreUiTransition(CoreGraphOwnerToken token, Runnable transition) {
        if (!ownsCoreGraphOwnerToken(token)) {
            return;
        }
        enqueueCoreUiTransition(token.serverId(), () -> runCoreGraphOwnerEffect(token, transition), true);
    }

    public void projectCoreGraphForUi(ReSyncFlowClient source, ReSyncResourceType type,
                                      CoreGraphResourceProjection.Projection projection, Consumer<FlowGraph> consumer) {
        if (source == null || type == null || !type.isGraph() || projection == null || projection.resource() == null || consumer == null
            || !source.getServerId().equals(projection.resource().serverId().canonicalText())) {
            return;
        }
        CoreGraphOwnerToken token = currentCoreGraphOwnerToken(source.getServerId(), source);
        enqueueCoreUiTransition(token, () -> {
            if (synchronizeAuthorityEpoch(source.getServerId())) {
                coreGraphUiProjection.project(type, projection).ifPresent(graph -> {
                    coreGraphEditorSession(source.getServerId(), type, projection.resource().id());
                    consumer.accept(graph);
                });
            }
        });
    }

    private void runScheduledCoreUiTransition(String serverId, ServerConnectionToken token) {
        if (runIfCurrentServerConnection(token, () -> drainCoreUiTransitions(serverId, token))) {
            return;
        }
        rescheduleCoreUiTransitions(serverId, token);
    }

    private void rescheduleCoreUiTransitions(String serverId, ServerConnectionToken staleToken) {
        ServerConnectionToken replacementToken = null;
        boolean schedule = false;
        synchronized (serverConnectionGenerationLock) {
            synchronized (coreUiTransitionLock) {
                ServerConnectionToken queuedGeneration = coreUiTransitionGenerations.get(serverId);
                if (queuedGeneration == null || sameCoreUiTransitionGeneration(queuedGeneration, staleToken)) {
                    pendingCoreUiTransitions.remove(serverId);
                    scheduledCoreUiTransitions.remove(serverId);
                    coreUiTransitionGenerations.remove(serverId);
                    return;
                }
                scheduledCoreUiTransitions.remove(serverId);
                scheduledCoreUiTransitions.add(serverId);
                replacementToken = queuedGeneration;
                schedule = true;
            }
        }
        if (schedule) {
            ServerConnectionToken token = replacementToken;
            ScreenManager.getInstance().execute(() -> runScheduledCoreUiTransition(serverId, token));
        }
    }

    private boolean ownsCoreUiTransitionGenerationLocked(ServerConnectionToken token) {
        if (token == null || token.serverId() == null || token.serverId().isBlank()
            || token.source() == null || token.generation() <= 0L) {
            return false;
        }
        return serverConnectionGenerations.getOrDefault(token.serverId(), 0L) == token.generation()
            && serverGenerationSources.get(token.serverId()) == token.source()
            && !retiringServerConnections.contains(token.serverId());
    }

    private boolean sameCoreUiTransitionGeneration(ServerConnectionToken left, ServerConnectionToken right) {
        return left != null && right != null && left.generation() == right.generation()
            && left.source() == right.source()
            && (left.serverId() == null ? right.serverId() == null : left.serverId().equals(right.serverId()));
    }

    private void drainCoreUiTransitions(String serverId, ServerConnectionToken token) {
        RuntimeException failure = null;
        while (true) {
            List<CoreUiTransition> transitions;
            synchronized (coreUiTransitionLock) {
                if (!sameCoreUiTransitionGeneration(coreUiTransitionGenerations.get(serverId), token)) {
                    return;
                }
                transitions = pendingCoreUiTransitions.remove(serverId);
                if (transitions == null || transitions.isEmpty()) {
                    scheduledCoreUiTransitions.remove(serverId);
                    coreUiTransitionGenerations.remove(serverId);
                    break;
                }
            }
            for (CoreUiTransition transition : transitions) {
                if (!isCurrentServerConnection(transition.token())) {
                    continue;
                }
                if (!beginCoreUiEffect(serverId, !transition.ownerManaged())) {
                    clearCoreUiTransitions(serverId, token);
                    return;
                }
                try {
                    transition.effect().run();
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    }
                } finally {
                    endCoreUiEffect(serverId, !transition.ownerManaged());
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private boolean coreLifecycleClosed() {
        synchronized (coreLifecycleLock) {
            return closed;
        }
    }

    private boolean beginCoreUiEffect(String serverId, boolean serverLease) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        synchronized (coreLifecycleLock) {
            if (closed || closingCoreServers.contains(serverId)) {
                return false;
            }
            activeCoreUiEffects++;
            coreUiEffectDepth.set(coreUiEffectDepth.get() + 1);
            if (serverLease) {
                activeCoreEffectsByServer.merge(serverId, 1, Integer::sum);
            }
            return true;
        }
    }

    private void endCoreUiEffect(String serverId, boolean serverLease) {
        synchronized (coreLifecycleLock) {
            activeCoreUiEffects--;
            int depth = coreUiEffectDepth.get() - 1;
            if (depth == 0) {
                coreUiEffectDepth.remove();
            } else {
                coreUiEffectDepth.set(depth);
            }
            if (serverLease) {
                activeCoreEffectsByServer.computeIfPresent(serverId, (ignored, active) -> active > 1 ? active - 1 : null);
            }
            if (activeCoreUiEffects == 0 || serverLease && !activeCoreEffectsByServer.containsKey(serverId)) {
                coreLifecycleLock.notifyAll();
            }
        }
    }

    static void requireExternalShutdown(int activeEffectDepth) {
        if (activeEffectDepth > 0) {
            throw new IllegalStateException("FlowManager cannot shut down from an active UI transition.");
        }
    }

    private void clearCoreUiTransitions(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        synchronized (coreUiTransitionLock) {
            pendingCoreUiTransitions.remove(serverId);
            scheduledCoreUiTransitions.remove(serverId);
            coreUiTransitionGenerations.remove(serverId);
        }
    }

    private void clearCoreUiTransitions(String serverId, ServerConnectionToken generation) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        synchronized (coreUiTransitionLock) {
            if (!sameCoreUiTransitionGeneration(coreUiTransitionGenerations.get(serverId), generation)) {
                return;
            }
            pendingCoreUiTransitions.remove(serverId);
            scheduledCoreUiTransitions.remove(serverId);
            coreUiTransitionGenerations.remove(serverId);
        }
    }

    private void clearCoreUiTransitions(String serverId, long generation) {
        if (serverId == null || serverId.isBlank() || generation <= 0L) {
            return;
        }
        synchronized (coreUiTransitionLock) {
            ServerConnectionToken queuedGeneration = coreUiTransitionGenerations.get(serverId);
            if (queuedGeneration == null || queuedGeneration.generation() != generation) {
                return;
            }
            pendingCoreUiTransitions.remove(serverId);
            scheduledCoreUiTransitions.remove(serverId);
            coreUiTransitionGenerations.remove(serverId);
        }
    }

    private void clearCoreUiTransitions() {
        synchronized (coreUiTransitionLock) {
            pendingCoreUiTransitions.clear();
            scheduledCoreUiTransitions.clear();
            coreUiTransitionGenerations.clear();
        }
    }

    private void clearPendingWorkspaceRefreshes(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        synchronized (studioWorkspaceRefreshLock) {
            pendingStudioWorkspaceRefreshes.remove(serverId);
            scheduledStudioWorkspaceRefreshes.remove(serverId);
            studioWorkspaceRefreshGenerations.remove(serverId);
        }
        synchronized (flowWorkspaceRefreshLock) {
            pendingFlowWorkspaceRefreshes.remove(serverId);
            scheduledFlowWorkspaceRefreshes.remove(serverId);
            flowWorkspaceRefreshGenerations.remove(serverId);
        }
    }

    private boolean ownsCoreGraphListener(ReSyncFlowClient source, long generation,
                                           ReSyncFlowClient.CoreGraphResourceTransition transition) {
        if (source == null || transition == null || transition.resource() == null) {
            return false;
        }
        String serverId = transition.resource().serverId().canonicalText();
        return serverId.equals(source.getServerId())
            && ownsCoreGraphOwnerToken(new CoreGraphOwnerToken(source, generation, serverId));
    }

    private boolean ownsCoreGraphHandoff(CoreGraphHandoff handoff) {
        return handoff != null && ownsCoreGraphOwnerToken(handoff.ownerToken());
    }

    private CoreGraphOwnerToken currentCoreGraphOwnerToken(String serverId, ReSyncFlowClient source) {
        if (serverId == null || source == null) {
            return null;
        }
        CoreGraphOwnerToken[] token = {null};
        connectionManager.withCurrentFlowClient(serverId, source, ignored -> {
            synchronized (coreLifecycleLock) {
                CoreGraphListenerSubscription owner = coreGraphListenerSubscriptions.get(serverId);
                if (!closed && owner != null && owner.source() == source) {
                    token[0] = new CoreGraphOwnerToken(source, owner.generation(), serverId);
                }
            }
        });
        return token[0];
    }

    private boolean ownsCoreGraphOwnerToken(CoreGraphOwnerToken token) {
        if (token == null) {
            return false;
        }
        boolean[] owned = {false};
        connectionManager.withCurrentFlowClient(token.serverId(), token.source(), ignored -> {
            synchronized (coreLifecycleLock) {
                CoreGraphListenerSubscription owner = coreGraphListenerSubscriptions.get(token.serverId());
                owned[0] = !closed && owner != null && owner.source() == token.source() && owner.generation() == token.generation();
            }
        });
        return owned[0];
    }

    private boolean runCoreGraphOwnerEffect(CoreGraphOwnerToken token, Runnable effect) {
        if (token == null || effect == null) {
            return false;
        }
        boolean[] applied = {false};
        connectionManager.withCurrentFlowClient(token.serverId(), token.source(), ignored -> {
            synchronized (coreLifecycleLock) {
                CoreGraphListenerSubscription owner = coreGraphListenerSubscriptions.get(token.serverId());
                if (closed || closingCoreServers.contains(token.serverId()) || owner == null || owner.source() != token.source()
                    || owner.generation() != token.generation()) {
                    return;
                }
                activeCoreUiEffects++;
                coreUiEffectDepth.set(coreUiEffectDepth.get() + 1);
            }
            try {
                effect.run();
                applied[0] = true;
            } finally {
                endCoreUiEffect(token.serverId(), false);
            }
        });
        return applied[0];
    }

    private boolean runCurrentCoreLifecycleEffect(String serverId, Runnable effect) {
        if (serverId == null || serverId.isBlank() || effect == null) {
            return false;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            ReSyncFlowClient source = connectionManager.getFlowClient(serverId);
            if (source == null) {
                if (beginCoreUiEffect(serverId, true)) {
                    try {
                        if (connectionManager.getFlowClient(serverId) != null) {
                            continue;
                        }
                        effect.run();
                        return true;
                    } finally {
                        endCoreUiEffect(serverId, true);
                    }
                }
                continue;
            }
            boolean[] applied = {false};
            connectionManager.withCurrentFlowClient(serverId, source, ignored -> {
                boolean admitted = false;
                synchronized (coreLifecycleLock) {
                    if (!closed && !closingCoreServers.contains(serverId)) {
                        activeCoreUiEffects++;
                        coreUiEffectDepth.set(coreUiEffectDepth.get() + 1);
                        admitted = true;
                    }
                }
                if (!admitted) {
                    return;
                }
                try {
                    effect.run();
                    applied[0] = true;
                } finally {
                    endCoreUiEffect(serverId, false);
                }
            });
            if (applied[0]) {
                return true;
            }
        }
        return false;
    }

    private void enqueueCoreCompletion(CoreGraphOwnerToken token, Runnable ownedCompletion, Runnable displacedRollback) {
        if (token == null || ownedCompletion == null || displacedRollback == null) {
            return;
        }
        boolean owned = ownsCoreGraphOwnerToken(token);
        if (!owned && coreLifecycleClosed()) {
            return;
        }
        enqueueCoreUiTransition(token.serverId(), () -> {
            if (runCoreGraphOwnerEffect(token, ownedCompletion)) {
                return;
            }
            runCurrentCoreLifecycleEffect(token.serverId(), displacedRollback);
        }, true);
    }

    private CoreGraphHandoff currentCoreGraphHandoff(String serverId, ReSyncResourceType type, String id) {
        CoreGraphHandoff handoff = coreGraphHandoff.get();
        return handoff != null && handoff.matches(serverId, type, id) ? handoff : null;
    }

    public void provisionReSyncForReStudioServer(String serverId, Consumer<Boolean> callback) {
        connectionManager.provisionReSyncForReStudioServer(serverId, callback);
    }

    public void updateReSyncForReStudioServer(String serverId, Consumer<Boolean> callback) {
        connectionManager.updateReSyncForReStudioServer(serverId, callback);
    }

    public Async<String> getReSyncVersionForReStudioServer(String serverId) {
        return connectionManager.getReSyncVersionForReStudioServer(serverId);
    }

    public String getFlowAvailabilityIssue(String serverId, ClientServerView server) {
        return connectionManager.getFlowAvailabilityIssue(serverId, server);
    }

    public Async<String> getFlowAvailabilityIssueAsync(String serverId, ClientServerView server) {
        return connectionManager.getFlowAvailabilityIssueAsync(serverId, server);
    }

    public Object getInstanceByServerId(String serverId) {
        return connectionManager.getInstanceByServerId(serverId);
    }

    public Object findInstanceByServerId(String serverId, ClientServerView server) {
        return connectionManager.findInstanceByServerId(serverId, server);
    }

    public RemotelyServerApi getApiClient() {
        return remotelyApi;
    }

    public boolean isFlowClientReady(String serverId) {
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        return flowClient != null && flowClient.isConnectedState();
    }

    public ReSyncFlowClient.ReadinessState getFlowClientReadiness(String serverId) {
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            return ReSyncFlowClient.ReadinessState.DISCONNECTED;
        }
        return flowClient.readiness();
    }

    public List<String> getFlowTemplates() {
        return FLOW_TEMPLATES;
    }

    public Async<Boolean> isReSyncPluginInstalled(String serverId) {
        if (connectionManager.getApiClient() == null) {
            return Async.completed(false);
        }
        return connectionManager.getReSyncVersionForReStudioServer(serverId)
                .thenApply(version -> version != null && !version.isBlank());
    }

    public String normalizeReSyncNotificationMessage(String message) {
        return connectionManager.normalizeReSyncNotificationMessage(message);
    }

    public void requestInitialFlowData(String serverId) {
        requestInitialFlowData(serverId, false);
    }

    private void requestInitialFlowData(String serverId, boolean refreshCatalogs) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId, true);
        if (flowClient == null) {
            return;
        }
        for (ReSyncResourceType type : List.of(ReSyncResourceType.FLOW, ReSyncResourceType.FUNCTION, ReSyncResourceType.COMMAND)) {
            if (refreshCatalogs || !flowClient.isResourceListAuthoritative(type)) {
                flowClient.requestResourceList(type);
            } else {
                requestMissingResources(flowStore, serverId, type, id -> flowClient.requestResource(type, id, false));
            }
        }
        if (refreshCatalogs || !hasResourceData(guiStore, serverId)) {
            flowClient.requestGuiList();
        } else {
            requestMissingResources(guiStore, serverId, id -> flowClient.requestGui(id, false));
        }
        if (refreshCatalogs || !hasResourceData(scoreboardStore, serverId)) {
            flowClient.requestScoreboardList();
        } else {
            requestMissingResources(scoreboardStore, serverId, id -> flowClient.requestScoreboard(id, false));
        }
        if (refreshCatalogs || !hasResourceData(tabStore, serverId)) {
            flowClient.requestTabList();
        } else {
            requestMissingResources(tabStore, serverId, id -> flowClient.requestTab(id, false));
        }
        if (refreshCatalogs || !hasResourceData(customContentStore, serverId)) {
            flowClient.requestCustomContentList();
        } else {
            requestMissingResources(customContentStore, serverId, id -> flowClient.requestCustomContent(id, false));
        }
        if (refreshCatalogs || !hasProjectMetadataData(serverId)) {
            flowClient.requestProjectMetadataList();
        }
        requestMissingCustomizationResources(flowClient, serverId, refreshCatalogs);
        if (worldService.getWorldSnapshot(serverId) == null) {
            flowClient.requestWorldSnapshot();
            flowClient.requestPlayerTrackingSnapshot();
        }
    }

    private <T> boolean hasResourceData(SyncedResourceCache<T> store, String serverId) {
        return store.hasLoadedServerList(serverId);
    }

    private boolean hasResourceData(TypedGraphCache store, String serverId) {
        return store.hasLoadedServerList(serverId);
    }

    private <T> void requestMissingResources(SyncedResourceCache<T> store, String serverId, Consumer<String> requester) {
        for (String id : store.getResourceIds(serverId)) {
            if (store.get(serverId, id) == null) {
                requester.accept(id);
            }
        }
    }

    private boolean hasLoadedProjectMetadataFromServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        return loadedProjectMetadataLists.contains(serverId);
    }

    private boolean hasCachedProjectMetadata(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        return projectMetadataStore.containsServerId(serverId, serverId);
    }

    private boolean shouldHydrateProjectMetadata(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        if (hasCachedProjectMetadata(serverId)) {
            return true;
        }
        return loadedProjectMetadataLists.contains(serverId) && !pendingProjectMetadataDocuments.contains(serverId);
    }

    private boolean canPersistProjectMetadata(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        if (hasCachedProjectMetadata(serverId)) {
            return true;
        }
        return loadedProjectMetadataLists.contains(serverId) && !pendingProjectMetadataDocuments.contains(serverId);
    }

    private boolean hasProjectMetadataData(String serverId) {
        return hasLoadedProjectMetadataFromServer(serverId);
    }

    private void requestMissingCustomizationResources(ReSyncFlowClient flowClient, String serverId, boolean refreshCatalogs) {
        for (ReSyncResourceType type : ReSyncResourceType.values()) {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            if (store != null && (refreshCatalogs || !flowClient.isResourceListAuthoritative(type))) {
                flowClient.requestResourceList(type);
            } else if (store != null) {
                requestMissingResources(store, serverId, id -> flowClient.requestResource(type, id, false));
            }
        }
    }

    public void openFlowEditor(String serverId, ClientServerView server) {
        String actualServerId = (server != null && server.identifier != null) ? server.identifier : serverId;
        marketplaceImportServerId = actualServerId;
        String flowId = getOrCreateDefaultFlowId(actualServerId);
        openFlowEditor(actualServerId, server, flowId);
    }

    public void openFlowEditor(String serverId, ClientServerView server, String flowId) {
        openFlowEditor(serverId, server, flowId, null);
    }

    public void openGraphEditor(String serverId, ClientServerView server, ReSyncResourceType type, String id) {
        openGraphEditor(serverId, server, type, id, null);
    }

    public void openGraphEditor(String serverId, ClientServerView server, ReSyncResourceType type, String id,
                                String branchPin) {
        if (type == ReSyncResourceType.FLOW) {
            openFlowEditor(serverId, server, id, branchPin);
            return;
        }
        if (type == null || !type.isGraph() || id == null || id.isBlank()) {
            return;
        }
        String actualServerId = server != null && server.identifier != null ? server.identifier : serverId;
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(actualServerId);
        Optional<CoreGraphEditorSession> coreSession = coreGraphEditorSession(actualServerId, type, id);
        if (coreSession.isPresent()) {
            CoreGraphEditorSession session = coreSession.orElseThrow();
            String title = getGraphName(actualServerId, type, id);
            if (openExistingStudioScreen(actualServerId,
                screen -> screen.openWorkspaceCoreEditor(session, title, branchPin))) {
                return;
            }
            openStudioDocument(actualServerId, ReSyncProjectMetadata.resourceKey(type.typeId(), id),
                screen -> screen.openWorkspaceCoreEditor(session, title, branchPin));
            return;
        }
        if (flowClient != null) {
            flowClient.requestResource(type, id, true);
        }
        if (openExistingStudioScreen(actualServerId, screen -> screen.openWorkspaceResource(type.typeId(), id))) {
            return;
        }
        openStudioDocument(actualServerId, ReSyncProjectMetadata.resourceKey(type.typeId(), id),
            screen -> screen.openWorkspaceResource(type.typeId(), id));
    }

    public void openFlowEditor(String serverId, ClientServerView server, String flowId, String branchPin) {
        String actualServerId = (server != null && server.identifier != null) ? server.identifier : serverId;
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(actualServerId);
        Optional<CoreGraphEditorSession> coreSession = coreGraphEditorSession(
            actualServerId, ReSyncResourceType.FLOW, flowId);
        if (coreSession.isPresent()) {
            CoreGraphEditorSession session = coreSession.orElseThrow();
            String title = getFlowName(actualServerId, flowId);
            if (openExistingStudioScreen(actualServerId,
                screen -> screen.openWorkspaceCoreEditor(session, title, branchPin))) {
                return;
            }
            openStudioDocument(actualServerId,
                ReSyncProjectMetadata.resourceKey(ReSyncResourceType.FLOW.typeId(), flowId),
                screen -> screen.openWorkspaceCoreEditor(session, title, branchPin));
            return;
        }
        if (isCoreGraphAuthoritative(actualServerId, ReSyncResourceType.FLOW, flowId)) {
            if (flowClient != null) {
                flowClient.requestResource(ReSyncResourceType.FLOW, flowId, true);
            }
            return;
        }
        if (coreGraphAuthorityEnabled(actualServerId)) {
            if (flowClient != null) {
                ReSyncProjectMetadata.ResourceEntry metadata = getProjectResource(actualServerId, ReSyncResourceType.FLOW.typeId(), flowId);
                if (metadata != null || coreGraphResourceKnown(flowClient, ReSyncResourceType.FLOW, flowId)) {
                    flowClient.requestResource(ReSyncResourceType.FLOW, flowId, true);
                } else {
                    CoreGraphDocumentAuthoringAdapter.RequestResult result = requestCoreGraphCreation(
                        actualServerId, ReSyncResourceType.FLOW, flowId, flowId, branchPin);
                    if (!result.admitted()) {
                        new Notification("Open Flow", "Core Template Unavailable", Notification.Type.WARN);
                    }
                }
            }
            return;
        }
        FlowGraph draft = flowStore.getFromDraft(actualServerId, ReSyncResourceType.FLOW, flowId);
        if (draft != null) {
            if (openExistingStudioScreen(actualServerId, screen -> screen.openWorkspaceFlowEditor(flowId, branchPin))) {
                return;
            }
            openStudioDocument(actualServerId, ReSyncProjectMetadata.resourceKey(draft.getResourceType(), draft.getId()),
                screen -> screen.openWorkspaceFlowEditor(flowId, branchPin));
            return;
        }
        if (flowStore.containsServerId(actualServerId, ReSyncResourceType.FLOW, flowId)) {
            flowClient.requestFlow(flowId, true);
            return;
        }
        FlowGraph newGraph = createDefaultFlow();
        if (flowId != null) {
            newGraph.setId(flowId);
        }
        String actualFlowId = newGraph.getId();
        flowStore.putInDraft(actualServerId, newGraph);
        flowStore.putNameIfAbsent(actualServerId, ReSyncResourceType.FLOW, actualFlowId, actualFlowId);
        if (openExistingStudioScreen(actualServerId, screen -> screen.openWorkspaceFlowEditor(actualFlowId, branchPin))) {
            return;
        }
        openStudioDocument(actualServerId, ReSyncProjectMetadata.resourceKey(newGraph.getResourceType(), actualFlowId),
            screen -> screen.openWorkspaceFlowEditor(actualFlowId, branchPin));
    }

    public void openGuiDesigner(String serverId, ClientServerView server) {
        openGuiDesigner(serverId, server, "main");
    }

    public void openGuiDesigner(String serverId, ClientServerView server, String guiId) {
        openGuiDesigner(serverId, server, guiId, null);
    }

    public void openGuiDesigner(String serverId, ClientServerView server, String guiId, Object parentOverride) {
        openGuiDesigner(serverId, server, guiId, parentOverride, false);
    }

    public void openGuiDesigner(String serverId, ClientServerView server, String guiId, Object parentOverride, boolean fullEditor) {
        String actualServerId = (server != null && server.identifier != null) ? server.identifier : serverId;
        requestDesignerCatalogs(actualServerId);
        GuiDefinition gui = guiStore.get(actualServerId, guiId);
        Object parent = resolveDesignerParent(parentOverride);
        if (gui == null) {
            guiStore.setPendingParent(actualServerId, guiId, designerOpenContext(parent, fullEditor));
            ensureSubscribedFlowClient(actualServerId).requestGui(guiId, false);
            return;
        }
        if (openExistingStudioDesigner(actualServerId, ReSyncResourceDragPayload.GUI, guiId, fullEditor)) {
            return;
        }
        if (!fullEditor) {
            openStudioDocument(actualServerId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.GUI, guiId),
                screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.GUI, guiId, false));
            return;
        }
        client.getHost().setScreen(new GuiDesignerScreen(detachedGui(gui), actualServerId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
    }

    public void openScoreboardDesigner(String serverId, ClientServerView server) {
        openScoreboardDesigner(serverId, server, "main");
    }

    public void openScoreboardDesigner(String serverId, ClientServerView server, String scoreboardId) {
        openScoreboardDesigner(serverId, server, scoreboardId, null);
    }

    public void openScoreboardDesigner(String serverId, ClientServerView server, String scoreboardId, Object parentOverride) {
        openScoreboardDesigner(serverId, server, scoreboardId, parentOverride, false);
    }

    public void openScoreboardDesigner(String serverId, ClientServerView server, String scoreboardId, Object parentOverride, boolean fullEditor) {
        String actualServerId = (server != null && server.identifier != null) ? server.identifier : serverId;
        ScoreboardDefinition scoreboard = scoreboardStore.get(actualServerId, scoreboardId);
        Object parent = resolveDesignerParent(parentOverride);
        if (scoreboard == null) {
            scoreboardStore.setPendingParent(actualServerId, scoreboardId, designerOpenContext(parent, fullEditor));
            ensureSubscribedFlowClient(actualServerId).requestScoreboard(scoreboardId, false);
            return;
        }
        if (openExistingStudioDesigner(actualServerId, ReSyncResourceDragPayload.SCOREBOARD, scoreboardId, fullEditor)) {
            return;
        }
        if (!fullEditor) {
            openStudioDocument(actualServerId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.SCOREBOARD, scoreboardId),
                screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.SCOREBOARD, scoreboardId, false));
            return;
        }
        client.getHost().setScreen(new ScoreboardDesignerScreen(detachedScoreboard(scoreboard), actualServerId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
    }

    public void openTabDesigner(String serverId, ClientServerView server) {
        openTabDesigner(serverId, server, "main");
    }

    public void openTabDesigner(String serverId, ClientServerView server, String tabId) {
        openTabDesigner(serverId, server, tabId, null);
    }

    public void openTabDesigner(String serverId, ClientServerView server, String tabId, Object parentOverride) {
        openTabDesigner(serverId, server, tabId, parentOverride, false);
    }

    public void openTabDesigner(String serverId, ClientServerView server, String tabId, Object parentOverride, boolean fullEditor) {
        String actualServerId = (server != null && server.identifier != null) ? server.identifier : serverId;
        TabDefinition tab = tabStore.get(actualServerId, tabId);
        Object parent = resolveDesignerParent(parentOverride);
        if (tab == null) {
            tabStore.setPendingParent(actualServerId, tabId, designerOpenContext(parent, fullEditor));
            ensureSubscribedFlowClient(actualServerId).requestTab(tabId, false);
            return;
        }
        if (openExistingStudioDesigner(actualServerId, ReSyncResourceDragPayload.TAB, tabId, fullEditor)) {
            return;
        }
        if (!fullEditor) {
            openStudioDocument(actualServerId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.TAB, tabId),
                screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.TAB, tabId, false));
            return;
        }
        client.getHost().setScreen(new TabDesignerScreen(detachedTab(tab), actualServerId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
    }

    public void openAdvancementDesigner(String serverId, String treeId, Object parentOverride) {
        openAdvancementDesigner(serverId, treeId, parentOverride, false);
    }

    public void openAdvancementDesigner(String serverId, String treeId, Object parentOverride, boolean fullEditor) {
        if (serverId == null || serverId.isBlank() || treeId == null || treeId.isBlank()) {
            return;
        }
        requestDesignerCatalogs(serverId);
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(ReSyncResourceType.ADVANCEMENT_TREE);
        if (store == null) {
            return;
        }
        Object parent = resolveDesignerParent(parentOverride);
        JsonObject tree = store.get(serverId, treeId);
        if (tree == null) {
            store.setPendingParent(serverId, treeId, designerOpenContext(parent, fullEditor));
            ensureSubscribedFlowClient(serverId).requestResource(ReSyncResourceType.ADVANCEMENT_TREE, treeId, false);
            return;
        }
        if (fullEditor && openExistingStudioDesigner(serverId, ReSyncResourceDragPayload.ADVANCEMENT_TREE, treeId, true)) {
            return;
        }
        if (!fullEditor) {
            openStudioDocument(serverId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.ADVANCEMENT_TREE, treeId),
                screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.ADVANCEMENT_TREE, treeId, false));
            return;
        }
        client.getHost().setScreen(new AdvancementDesignerScreen(detachedJson(tree), serverId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
    }

    public void openDialogDesigner(String serverId, String dialogId, Object parentOverride) {
        openDialogDesigner(serverId, dialogId, parentOverride, false);
    }

    public void openDialogDesigner(String serverId, String dialogId, Object parentOverride, boolean fullEditor) {
        if (serverId == null || serverId.isBlank() || dialogId == null || dialogId.isBlank()) {
            return;
        }
        requestDesignerCatalogs(serverId);
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(ReSyncResourceType.DIALOG);
        if (store == null) {
            return;
        }
        Object parent = resolveDesignerParent(parentOverride);
        JsonObject dialog = store.get(serverId, dialogId);
        if (dialog == null) {
            store.setPendingParent(serverId, dialogId, designerOpenContext(parent, fullEditor));
            ensureSubscribedFlowClient(serverId).requestResource(ReSyncResourceType.DIALOG, dialogId, false);
            return;
        }
        if (openExistingStudioDesigner(serverId, ReSyncResourceDragPayload.DIALOG, dialogId, fullEditor)) {
            return;
        }
        if (!fullEditor) {
            openStudioDocument(serverId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.DIALOG, dialogId),
                screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.DIALOG, dialogId, false));
            return;
        }
        client.getHost().setScreen(new DialogDesignerScreen(detachedJson(dialog), serverId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
    }

    private boolean openExistingStudioScreen(String serverId, Consumer<FlowEditorScreen> opener) {
        return openExistingStudioScreen(serverId, opener, false);
    }

    private boolean openExistingStudioScreen(String serverId, Consumer<FlowEditorScreen> opener, boolean fullEditor) {
        FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
        if (studioScreen == null) {
            return false;
        }
        opener.accept(studioScreen);
        activateStudioScreen(studioScreen, fullEditor);
        return true;
    }

    private boolean openExistingStudioDesigner(String serverId, String type, String id, boolean fullEditor) {
        return openExistingStudioScreen(serverId, screen -> screen.openWorkspaceDesigner(type, id, fullEditor), fullEditor);
    }

    private void requestDesignerCatalogs(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId, true);
        if (flowClient == null) {
            return;
        }
        for (ReSyncResourceType type : List.of(ReSyncResourceType.FLOW, ReSyncResourceType.FUNCTION, ReSyncResourceType.COMMAND)) {
            if (!flowClient.isResourceListAuthoritative(type)) {
                flowClient.requestResourceList(type);
            }
        }
        if (!hasResourceData(guiStore, serverId)) {
            flowClient.requestGuiList();
        }
        if (!hasProjectMetadataData(serverId)) {
            flowClient.requestProjectMetadataList();
        }
        for (ReSyncResourceType type : ReSyncResourceType.values()) {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            if (store != null && !flowClient.isResourceListAuthoritative(type)) {
                flowClient.requestResourceList(type);
            }
        }
    }

    private void requestDesignerCatalogs(String serverId, StudioEditTarget target) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId, true);
        if (flowClient == null) {
            return;
        }
        for (ReSyncResourceType type : List.of(ReSyncResourceType.FLOW, ReSyncResourceType.FUNCTION, ReSyncResourceType.COMMAND)) {
            if (!flowClient.isResourceListAuthoritative(type)) {
                flowClient.requestResourceList(type);
            }
        }
        if (!hasProjectMetadataData(serverId)) {
            flowClient.requestProjectMetadataList();
        }
        if (target != null && ReSyncResourceDragPayload.GUI.equals(target.type()) && !hasResourceData(guiStore, serverId)) {
            flowClient.requestGuiList();
        }
    }

    private void activateStudioScreen(FlowEditorScreen studioScreen, boolean fullEditor) {
        studioFullEditorSession.activate(studioScreen, fullEditor);
    }

    public boolean activateOpenStudio(String serverId, boolean fullEditor) {
        FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
        if (studioScreen == null) {
            return false;
        }
        activateStudioScreen(studioScreen, fullEditor);
        return true;
    }

    public void openStudioDocument(String serverId, String documentKey, Consumer<FlowEditorScreen> opener) {
        if (serverId == null || serverId.isBlank() || documentKey == null || documentKey.isBlank() || opener == null) {
            return;
        }
        FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
        boolean created = studioScreen == null;
        if (studioScreen == null) {
            String serverTitle = studioServerTitles.getOrDefault(serverId, serverId.startsWith("live:") ? "Live Server" : serverId);
            studioScreen = new FlowEditorScreen(new FlowGraph(), serverId, ScreenManager.getInstance().getCurrentScreen(), null, "", serverTitle).enableStudioMode();
        }
        if (studioScreen.isStudioWorkspaceReady()) {
            opener.accept(studioScreen);
            activateStudioScreen(studioScreen, false);
            ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
            ReSyncFlowClient.traceLifecycle(serverId, "studio_document_open_applied", "serverId", serverId,
                "resourceKey", documentKey, "operation", "open", "requestId", "studio", "mutationId", null,
                "generation", flowClient != null ? flowClient.activeTransportGeneration() : -1,
                "authorityEpoch", flowClient != null ? flowClient.authorityEpoch() : 0L, "revision", 0L,
                "queued", false, "workspaceReady", true);
            return;
        }
        Map<String, Consumer<FlowEditorScreen>> pending = pendingStudioDocumentOpeners.computeIfAbsent(serverId,
            ignored -> BrowserSafeState.map());
        pending.put(documentKey, opener);
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        ReSyncFlowClient.traceLifecycle(serverId, "studio_document_open_queued", "serverId", serverId,
            "resourceKey", documentKey, "operation", "open", "requestId", "studio", "mutationId", null,
            "generation", flowClient != null ? flowClient.activeTransportGeneration() : -1,
            "authorityEpoch", flowClient != null ? flowClient.authorityEpoch() : 0L, "revision", 0L,
            "queued", true, "queueSize", pending.size(), "workspaceReady", false);
        studioScreen.prepareLiveStudioWorkspace();
        if (created) {
            client.getHost().setScreen(studioScreen);
        } else {
            activateStudioScreen(studioScreen, false);
        }
        flushPendingStudioDocumentOpeners(serverId, studioScreen);
    }

    public void onStudioReady(String serverId) {
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        runIfCurrentServerConnection(token, () -> {
            studioFullEditorSession.onReady(serverId);
            FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
            flushPendingStudioDocumentOpeners(serverId, studioScreen);
        });
    }

    private void flushPendingStudioDocumentOpeners(String serverId, FlowEditorScreen studioScreen) {
        if (studioScreen == null || !studioScreen.isStudioWorkspaceReady()) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        runIfCurrentServerConnection(token, () -> {
            Map<String, Consumer<FlowEditorScreen>> openers = pendingStudioDocumentOpeners.remove(serverId);
            if (openers == null) {
                return;
            }
            ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
            ReSyncFlowClient.traceLifecycle(serverId, "studio_document_open_flushed", "serverId", serverId,
                "resourceKey", "studio", "operation", "open", "requestId", "studio", "mutationId", null,
                "generation", flowClient != null ? flowClient.activeTransportGeneration() : -1,
                "authorityEpoch", flowClient != null ? flowClient.authorityEpoch() : 0L, "revision", 0L,
                "queueSize", openers.size(), "workspaceReady", studioScreen.isStudioWorkspaceReady());
            for (Consumer<FlowEditorScreen> opener : openers.values()) {
                opener.accept(studioScreen);
            }
            activateStudioScreen(studioScreen, false);
        });
    }

    public void requestCloseLiveStudioSuperScreen(String serverId) {
        studioFullEditorSession.requestClose(serverId);
    }

    private boolean flushPendingStudioEditTarget(String serverId) {
        return studioFullEditorSession.flush(serverId);
    }

    private boolean flushPendingStudioEditTarget(String serverId, String type, String id) {
        return studioFullEditorSession.flush(serverId, type, id);
    }

    private void requestStudioEditTargetData(String serverId, StudioEditTarget target) {
        if (serverId == null || serverId.isBlank() || target == null) {
            return;
        }
        withFlowClient(serverId, flowClient -> {
            if (ReSyncResourceDragPayload.GUI.equals(target.type())) {
                if (guiStore.get(serverId, target.id()) == null) {
                    flowClient.requestGui(target.id(), false);
                }
                return null;
            }
            if (ReSyncResourceDragPayload.SCOREBOARD.equals(target.type())) {
                if (scoreboardStore.get(serverId, target.id()) == null) {
                    flowClient.requestScoreboard(target.id(), false);
                }
                return null;
            }
            if (ReSyncResourceDragPayload.TAB.equals(target.type())) {
                if (tabStore.get(serverId, target.id()) == null) {
                    flowClient.requestTab(target.id(), false);
                }
                return null;
            }
            ReSyncResourceType jsonType = ReSyncResourceType.byTypeId(target.type());
            SyncedResourceCache<JsonObject> store = jsonType != null ? jsonResourceStores.get(jsonType) : null;
            if (jsonType != null && store != null && store.get(serverId, target.id()) == null) {
                flowClient.requestResource(jsonType, target.id(), false);
            }
            return null;
        });
    }

    private boolean studioEditTargetAvailable(String serverId, StudioEditTarget target) {
        if (ReSyncResourceDragPayload.GUI.equals(target.type())) {
            return guiStore.get(serverId, target.id()) != null;
        }
        if (ReSyncResourceDragPayload.SCOREBOARD.equals(target.type())) {
            return scoreboardStore.get(serverId, target.id()) != null;
        }
        if (ReSyncResourceDragPayload.TAB.equals(target.type())) {
            return tabStore.get(serverId, target.id()) != null;
        }
        ReSyncResourceType jsonType = ReSyncResourceType.byTypeId(target.type());
        SyncedResourceCache<JsonObject> store = jsonType != null ? jsonResourceStores.get(jsonType) : null;
        return store != null && store.get(serverId, target.id()) != null;
    }

    public void createAdvancementTreeFromVanillaScreen(String serverId, Object parentOverride) {
        createAdvancementTreeFromVanillaScreen(serverId, parentOverride, false);
    }

    public void createAdvancementTreeFromVanillaScreen(String serverId, Object parentOverride, boolean fullEditor) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        String id = nextAdvancementTreeId(serverId);
        JsonObject tree = createJsonResource(serverId, ReSyncResourceType.ADVANCEMENT_TREE, id, ReSyncResourceType.ADVANCEMENT_TREE.defaultFolder());
        if (tree == null) {
            return;
        }
        saveJsonResource(serverId, ReSyncResourceType.ADVANCEMENT_TREE, tree);
        ProjectMetadataEdit metadata = editProjectMetadata(serverId);
        metadata.putResource(ReSyncResourceDragPayload.ADVANCEMENT_TREE, id, id, ReSyncResourceType.ADVANCEMENT_TREE.defaultFolder(),
            metadata.nextResourceSortOrder());
        saveProjectMetadata(metadata, true);
        openAdvancementDesigner(serverId, id, parentOverride, fullEditor);
    }

    private String nextAdvancementTreeId(String serverId) {
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(ReSyncResourceType.ADVANCEMENT_TREE);
        String base = "advancement";
        if (store == null || !store.containsKey(serverId, base)) {
            return base;
        }
        int index = 2;
        while (store.containsKey(serverId, base + "_" + index)) {
            index++;
        }
        return base + "_" + index;
    }

    private Object resolveDesignerParent(Object parentOverride) {
        if (parentOverride != null) {
            return parentOverride;
        }
        Screen current = ScreenManager.getInstance().getCurrentScreen();
        if (!Config.desktopMode) {
            return current;
        }
        Screen desktopSuperScreen = ScreenManager.getInstance().getDesktopSuperScreen();
        if (current != null && current.isDesktopWindow() && desktopSuperScreen != null) {
            return desktopSuperScreen;
        }
        return current;
    }

    private Object designerOpenContext(Object parent, boolean fullEditor) {
        return fullEditor ? new DesignerOpenContext(parent, true) : parent;
    }

    private Object designerParent(Object value) {
        return value instanceof DesignerOpenContext context ? context.parent() : value;
    }

    private boolean designerFullEditor(Object value) {
        return value instanceof DesignerOpenContext context && context.fullEditor();
    }

    private class StudioFullEditorSession {
        private final Map<String, StudioEditTarget> pendingTargets = BrowserSafeState.map();
        private final Map<String, ReSyncLiveServerSession> pendingSessions = BrowserSafeState.map();
        private final Map<String, Object> returnParents = BrowserSafeState.map();
        private final Map<String, StudioEditTarget> activeTargets = BrowserSafeState.map();
        private final Set<String> savedTargets = BrowserSafeState.set();
        private final Map<String, UUID> closeRequests = BrowserSafeState.map();

        boolean hasPendingTarget(String serverId) {
            return serverId != null && pendingTargets.containsKey(serverId);
        }

        void open(ReSyncLiveServerSession session, StudioEditTarget target, Object parent) {
            if (session == null || session.serverId() == null || session.serverId().isBlank() || target == null || target.type() == null || target.type().isBlank() || target.id() == null || target.id().isBlank()) {
                new Notification("ReSync", "ReSync Unavailable", Notification.Type.WARN);
                return;
            }
            String serverId = session.serverId();
            marketplaceImportServerId = serverId;
            activateLiveReSyncSession(session);
            closeRequests.remove(serverId);
            pendingTargets.put(serverId, target);
            pendingSessions.put(serverId, session);
            rememberReturnParent(serverId, parent);
            requestDesignerCatalogs(serverId, target);
            requestStudioEditTargetData(serverId, target);
            openPending(serverId, true);
        }

        void rememberReturnParent(String serverId, Object parent) {
            Object actualParent = parent != null ? parent : captureCurrentParent();
            if (actualParent != null) {
                returnParents.put(serverId, actualParent);
            }
        }

        Object captureCurrentParent() {
            ScreenManager screenManager = ScreenManager.getInstance();
            Screen currentScreen = screenManager.getCurrentScreen();
            Screen desktopSuperScreen = screenManager.getDesktopSuperScreen();
            if (currentScreen != null && currentScreen.isDesktopWindow() && desktopSuperScreen != null) {
                return desktopSuperScreen;
            }
            if (currentScreen != null) {
                return currentScreen;
            }
            Screen hostScreen = client.getHost().getCurrentScreen();
            return hostScreen != null ? hostScreen : desktopSuperScreen;
        }

        void activate(FlowEditorScreen studioScreen, boolean fullEditor) {
            if (studioScreen == null) {
                return;
            }
            studioScreen.setLiveStudioFullEditorMode(fullEditor);
            ScreenManager screenManager = ScreenManager.getInstance();
            if (Config.desktopMode && fullEditor) {
                DesktopWindowsOverlay overlay = screenManager.getDesktopWindowsOverlay();
                ScreenWindowWidget window = overlay != null ? overlay.getWindowForScreen(studioScreen) : null;
                if (window != null) {
                    overlay.closeWindow(window);
                }
                client.getHost().setScreen(studioScreen);
                return;
            }
            if (Config.desktopMode) {
                DesktopWindowsOverlay overlay = screenManager.getDesktopWindowsOverlay();
                ScreenWindowWidget window = overlay != null ? overlay.getWindowForScreen(studioScreen) : null;
                if (window != null) {
                    overlay.restoreWindow(window);
                    revealStudioHost(studioScreen);
                    return;
                }
                if (screenManager.getDesktopSuperScreen() == studioScreen) {
                    if (overlay != null) {
                        for (ScreenWindowWidget openWindow : overlay.getWindows()) {
                            overlay.minimizeWindow(openWindow);
                        }
                    }
                    revealStudioHost(studioScreen);
                    return;
                }
            }
            if (screenManager.getCurrentScreen() != studioScreen) {
                client.getHost().setScreen(studioScreen);
                return;
            }
            revealStudioHost(studioScreen);
        }

        void revealStudioHost(FlowEditorScreen studioScreen) {
            if (client.getHost().getCurrentScreen() == null) {
                client.getHost().setScreen(studioScreen);
            }
        }

        void onReady(String serverId) {
            if (serverId == null || serverId.isBlank()) {
                return;
            }
            requestStudioEditTargetData(serverId, pendingTargets.get(serverId));
            openPending(serverId, false);
        }

        boolean flush(String serverId) {
            return openPending(serverId, true);
        }

        boolean flush(String serverId, String type, String id) {
            StudioEditTarget target = pendingTargets.get(serverId);
            if (target == null || !target.type().equals(type) || !target.id().equals(id)) {
                return false;
            }
            return flush(serverId);
        }

        boolean openPending(String serverId, boolean activate) {
            StudioEditTarget target = pendingTargets.get(serverId);
            if (target == null) {
                return false;
            }
            if (!studioEditTargetAvailable(serverId, target)) {
                requestStudioEditTargetData(serverId, target);
                return false;
            }
            FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
            if (studioScreen == null) {
                if (!activate) {
                    return false;
                }
                ReSyncLiveServerSession session = pendingSessions.get(serverId);
                if (session == null) {
                    return false;
                }
                studioScreen = new FlowEditorScreen(new FlowGraph(), serverId, ScreenManager.getInstance().getCurrentScreen(), null, "", session.displayName()).enableStudioMode();
            }
            studioScreen.setLiveStudioFullEditorMode(target.fullEditor());
            if (!studioScreen.isStudioWorkspaceReady()) {
                if (!activate) {
                    return false;
                }
                studioScreen.prepareLiveStudioWorkspace();
                activate(studioScreen, target.fullEditor());
                target = pendingTargets.get(serverId);
                if (target == null) {
                    pendingSessions.remove(serverId);
                    return true;
                }
                studioScreen = FlowEditorScreen.getStudioScreen(serverId);
                if (studioScreen == null || !studioScreen.isStudioWorkspaceReady()) {
                    return false;
                }
                studioScreen.setLiveStudioFullEditorMode(target.fullEditor());
                if (!studioEditTargetAvailable(serverId, target)) {
                    requestStudioEditTargetData(serverId, target);
                    return false;
                }
            }
            pendingTargets.remove(serverId, target);
            pendingSessions.remove(serverId);
            activeTargets.put(serverId, target);
            savedTargets.remove(targetKey(serverId, target));
            studioScreen.openWorkspaceDesigner(target.type(), target.id(), target.fullEditor());
            if (activate) {
                activate(studioScreen, target.fullEditor());
            }
            return true;
        }

        void markSaved(String serverId, String type, String id) {
            StudioEditTarget target = activeTargets.get(serverId);
            if (target != null && target.fullEditor() && target.type().equals(type) && target.id().equals(id)) {
                savedTargets.add(targetKey(serverId, target));
            }
        }

        boolean savedActiveTarget(String serverId) {
            StudioEditTarget target = activeTargets.get(serverId);
            return target != null && savedTargets.contains(targetKey(serverId, target));
        }

        String targetKey(String serverId, StudioEditTarget target) {
            return serverId + ":" + target.type() + ":" + target.id();
        }

        void requestClose(String serverId) {
            if (serverId == null || serverId.isBlank()) {
                return;
            }
            UUID closeRequest = UUID.randomUUID();
            if (closeRequests.putIfAbsent(serverId, closeRequest) != null) {
                return;
            }
            ServerConnectionToken token = captureServerConnectionToken(serverId);
            ScreenManager.getInstance().execute(() -> runIfCurrentServerConnection(token, () -> closeNow(serverId, closeRequest)));
        }

        void closeNow(String serverId, UUID closeRequest) {
            if (serverId == null || serverId.isBlank()) {
                return;
            }
            if (!closeRequest.equals(closeRequests.remove(serverId))) {
                return;
            }
            pendingTargets.remove(serverId);
            pendingSessions.remove(serverId);
            activeTargets.remove(serverId);
            Object returnParent = returnParents.remove(serverId);
            FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
            ScreenManager screenManager = ScreenManager.getInstance();
            Screen currentScreen = screenManager.getCurrentScreen();
            Screen desktopSuperScreen = screenManager.getDesktopSuperScreen();
            Screen hostScreen = client.getHost().getCurrentScreen();
            if (studioScreen == null || (currentScreen != studioScreen && desktopSuperScreen != studioScreen && hostScreen != studioScreen)) {
                return;
            }
            if (studioScreen != null) {
                studioScreen.dismissStudioWorkspace();
                if (desktopSuperScreen == studioScreen) {
                    screenManager.setDesktopSuperScreen(null);
                }
            }
            if (returnParent != null && returnParent != studioScreen) {
                client.getHost().openParentScreen(studioScreen, returnParent);
            } else {
                client.getHost().openParentScreen(studioScreen, null);
            }
        }

        void clear(String serverId) {
            if (serverId == null || serverId.isBlank()) {
                return;
            }
            closeRequests.remove(serverId);
            pendingTargets.remove(serverId);
            pendingSessions.remove(serverId);
            returnParents.remove(serverId);
            activeTargets.remove(serverId);
            savedTargets.removeIf(key -> key.startsWith(serverId + ":"));
        }
    }

    private record DesignerOpenContext(Object parent, boolean fullEditor) {}

    private record StudioEditTarget(String type, String id, boolean fullEditor) {}

    private record StudioWorkspaceRefreshSnapshot(boolean rebuildContentBrowser, boolean invalidateProjectCatalog) {}

    private static final class PendingStudioWorkspaceRefresh {
        private boolean rebuildContentBrowser;
        private boolean invalidateProjectCatalog;

        private void add(boolean rebuildContentBrowser, boolean invalidateProjectCatalog) {
            this.rebuildContentBrowser = this.rebuildContentBrowser || rebuildContentBrowser;
            this.invalidateProjectCatalog = this.invalidateProjectCatalog || invalidateProjectCatalog;
        }

        private StudioWorkspaceRefreshSnapshot snapshot() {
            return new StudioWorkspaceRefreshSnapshot(rebuildContentBrowser, invalidateProjectCatalog);
        }
    }

    private void requestMissingResources(TypedGraphCache store, String serverId, ReSyncResourceType type, Consumer<String> requester) {
        for (String id : store.getResourceIds(serverId, type)) {
            if (store.get(serverId, type, id) == null) {
                requester.accept(id);
            }
        }
    }

    private void loadCreationJournal() {
        if (closed || creationJournalLoaded || !creationJournalLoadQueued.compareAndSet(0, 1)) {
            return;
        }
        long now = System.currentTimeMillis();
        long retryNotBefore = creationJournalRetryNotBeforeMillis.get();
        if (retryNotBefore > now) {
            creationJournalLoadQueued.set(0);
            scheduleCreationJournalLoadRetry(retryNotBefore);
            return;
        }
        try {
            creationJournalScheduler.execute(() -> {
                try {
                    loadCreationJournalFromDisk();
                } finally {
                    creationJournalLoadQueued.set(0);
                }
            });
        } catch (IllegalStateException exception) {
            creationJournalLoadQueued.set(0);
            creationJournalLoaded = false;
            scheduleCreationJournalLoadRetry();
        }
    }

    private void loadCreationJournalFromDisk() {
        String serialized = creationJournalStorage.read("journal");
        boolean canonicalExists = serialized != null && !serialized.isBlank();
        boolean evidenceExists = false;
        CreationJournalCandidate canonical = readCreationJournalCandidate(serialized);
        CreationJournalCandidate evidence = null;
        if (canonical == null && evidence == null && !canonicalExists && !evidenceExists) {
            creationJournalLoaded = true;
            creationJournalLoadAttempts.set(0);
            creationJournalRetryNotBeforeMillis.set(0L);
            ReSyncFlowClient.traceLifecycle(null, "create_journal_loaded", "serverId", "unresolved", "resourceKey",
                "creation-journal", "operation", "journal", "requestId", null, "mutationId", null,
                "generation", creationJournalGeneration.get(), "authorityEpoch", 0L, "revision", 0L,
                "entryCount", 0, "restoredCount", 0, "evidencePresent", false, "outcome", "empty");
            return;
        }
        try {
            CreationJournal journal = selectCreationJournal(canonical, evidence);
            if (journal == null) {
                throw new IllegalStateException("No valid resource creation journal snapshot");
            }
            List<CreationJournalEntry> entries = journal.entries().stream()
                .sorted((left, right) -> Long.compare(left.sequence(), right.sequence()))
                .toList();
            if (entries.size() > MAX_PENDING_CREATION_TRANSACTIONS) {
                throw new IllegalStateException("Resource creation journal capacity exceeded");
            }
            Map<CreationKey, CreationTransaction> restored = new LinkedHashMap<>();
            boolean terminalRows = false;
            for (CreationJournalEntry entry : entries) {
                if (entry == null) {
                    throw new IllegalStateException("Resource creation journal contains an empty entry");
                }
                CreationTransaction transaction = restoreCreationTransaction(entry, journal.schemaVersion());
                if (transaction == null) {
                    ReSyncFlowClient.traceLifecycle(entry.serverId(), "create_journal_entry_rejected", "serverId",
                        entry.serverId(), "resourceKey", Objects.toString(entry.resourceType(), "unknown") + ":"
                            + Objects.toString(entry.id(), "unknown"), "operation", "journal", "requestId",
                        entry.payloadRequestId(), "mutationId", entry.payloadMutationId(), "generation",
                        journal.generation(), "authorityEpoch", 0L, "revision", entry.sequence(), "phase",
                        entry.phase(), "reason", creationJournalRestoreDiagnostic(entry));
                    CreationPhase phase = creationPhase(entry.phase());
                    if (phase == CreationPhase.COMPLETE || phase == CreationPhase.FAILED) {
                        if (phase == CreationPhase.COMPLETE && !isValidCompletedCreationJournalEntry(entry)) {
                            throw new IllegalStateException("Resource creation journal contains an invalid completed entry");
                        }
                        terminalRows = true;
                        continue;
                    }
                    throw new IllegalStateException("Resource creation journal contains an invalid entry");
                }
                if (restored.putIfAbsent(transaction.key, transaction) != null) {
                    throw new IllegalStateException("Resource creation journal contains duplicate identity");
                }
            }
            synchronized (creationTransactionLock) {
                if (!creationTransactions.isEmpty()) {
                    throw new IllegalStateException("Resource creation journal was loaded twice");
                }
                restored.forEach((key, transaction) -> {
                    creationTransactions.put(key, transaction);
                    creationSequence.accumulateAndGet(transaction.sequence, Math::max);
                });
            }
            creationJournalLoaded = true;
            creationJournalGeneration.accumulateAndGet(Math.max(0L, journal.generation()), Math::max);
            creationJournalLoadAttempts.set(0);
            creationJournalRetryNotBeforeMillis.set(0L);
            if (terminalRows) {
                enqueueCreationJournalCleanup();
            }
            if (evidenceExists) {
                enqueueCreationJournalCleanup();
            }
            creationTransactions.keySet().stream().map(CreationKey::serverId).distinct()
                .forEach(this::resumeCreationTransactions);
            ReSyncFlowClient.traceLifecycle(null, "create_journal_loaded", "serverId", "unresolved", "resourceKey",
                "creation-journal", "operation", "journal", "requestId", null, "mutationId", null,
                "generation", creationJournalGeneration.get(), "authorityEpoch", 0L, "revision", 0L,
                "entryCount", entries.size(), "restoredCount", restored.size(), "terminalRows", terminalRows,
                "evidencePresent", evidenceExists, "outcome", "loaded");
        } catch (RuntimeException exception) {
            creationJournalLoaded = false;
            ReSyncFlowClient.traceLifecycle(null, "create_journal_load_failed", "serverId", "unresolved",
                "resourceKey", "creation-journal", "operation", "journal", "requestId", null,
                "mutationId", null, "generation", creationJournalGeneration.get(), "authorityEpoch", 0L,
                "revision", 0L, "reason", TaskIdentities.failureName(exception), "attempt",
                creationJournalLoadAttempts.get());
            scheduleCreationJournalLoadRetry();
        }
    }

    private CreationJournalCandidate readCreationJournalCandidate(String serialized) {
        if (serialized == null || serialized.isBlank() || serialized.length() > MAX_CREATION_JOURNAL_BYTES) {
            return null;
        }
        try {
            CreationJournal journal = CreationJournal.read(serialized);
            if (journal == null || journal.generation() < 0L
                || journal.schemaVersion() < MIN_CREATION_JOURNAL_SCHEMA_VERSION
                || journal.schemaVersion() > CREATION_JOURNAL_SCHEMA_VERSION || journal.entries() == null
                || journal.entries().size() > MAX_PENDING_CREATION_TRANSACTIONS) {
                return null;
            }
            return new CreationJournalCandidate(journal);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private CreationJournal selectCreationJournal(CreationJournalCandidate canonical,
                                                   CreationJournalCandidate evidence) {
        if (canonical == null) {
            return evidence != null ? evidence.journal() : null;
        }
        if (evidence == null) {
            return canonical.journal();
        }
        return evidence.journal().generation() > canonical.journal().generation()
            ? evidence.journal() : canonical.journal();
    }

    private void scheduleCreationJournalLoadRetry() {
        if (closed || creationJournalLoaded) {
            return;
        }
        int attempt = creationJournalLoadAttempts.updateAndGet(value -> {
            if (value <= 0) {
                return 1;
            }
            return Math.min(MAX_CREATION_JOURNAL_LOAD_ATTEMPTS, value + 1);
        });
        long multiplier = 1L << Math.min(30, Math.max(0, attempt - 1));
        long delaySeconds = Math.min(MAX_CREATION_JOURNAL_RETRY_DELAY_SECONDS,
            CREATION_JOURNAL_RETRY_DELAY_SECONDS * multiplier);
        long now = System.currentTimeMillis();
        long delayMillis = ((delaySeconds) * 1000L);
        long retryNotBefore = now + delayMillis;
        creationJournalRetryNotBeforeMillis.accumulateAndGet(retryNotBefore, Math::max);
        scheduleCreationJournalLoadRetry(creationJournalRetryNotBeforeMillis.get());
    }

    private void scheduleCreationJournalLoadRetry(long retryNotBefore) {
        if (closed || creationJournalLoaded) {
            return;
        }
        if (!creationJournalRetryScheduled.compareAndSet(0, 1)) {
            return;
        }
        try {
            long delayMillis = Math.max(0L, retryNotBefore - System.currentTimeMillis());
            creationJournalScheduler.schedule(() -> {
                creationJournalRetryScheduled.set(0);
                if (!closed && !creationJournalLoaded) {
                    loadCreationJournal();
                }
            }, java.time.Duration.ofMillis(delayMillis));
        } catch (RuntimeException exception) {
            creationJournalRetryScheduled.set(0);
        }
    }

    private CreationTransaction restoreCreationTransaction(CreationJournalEntry entry, int journalSchemaVersion) {
        try {
            if (!creationJournalEntryWithinLimits(entry) || entry.serverId() == null || entry.serverId().isBlank()
                || entry.id() == null || entry.id().isBlank()
                || entry.metadataType() == null || entry.metadataType().isBlank() || entry.metadataId() == null
                || entry.metadataId().isBlank() || entry.metadataName() == null || entry.metadataPath() == null
                || entry.metadataParentPath() == null || entry.metadataSortOrder() < 0 || entry.sequence() < 1L) {
                return null;
            }
            ReSyncResourceType resourceType = entry.resourceType() == null || entry.resourceType().isBlank()
                ? null : ReSyncResourceType.byTypeId(entry.resourceType());
            if (resourceType == ReSyncResourceType.PROJECT_METADATA || entry.folder() != (resourceType == null)) {
                return null;
            }
            if (resourceType == null && !"folder".equals(entry.metadataType())) {
                return null;
            }
            if (resourceType != null && (!resourceType.enabled() || !resourceType.typeId().equals(entry.metadataType())
                || !entry.id().equals(entry.metadataId()) || entry.folder())) {
                return null;
            }
            String metadataPath = resourceType == null ? entry.metadataPath()
                : canonicalResourcePath(resourceType, entry.metadataId(), entry.metadataPath());
            CreationMetadataIntent metadata = new CreationMetadataIntent(entry.metadataType(), entry.metadataId(),
                entry.metadataName(), metadataPath, entry.metadataParentPath(), entry.metadataSortOrder(), entry.folder());
            CreationKey key = new CreationKey(entry.serverId(), resourceType != null ? resourceType.typeId() : "folder",
                resourceType != null ? entry.id() : entry.metadataId());
            CreationPhase phase = creationPhase(entry.phase());
            PayloadSettlement settlement = payloadSettlement(entry.payloadSettlement());
            if (phase == null || settlement == null || phase == CreationPhase.COMPLETE || phase == CreationPhase.FAILED) {
                return null;
            }
            CreationPhase resumePhase = phase == CreationPhase.PAUSED ? creationResumePhase(entry.resumePhase()) : phase;
            if (phase == CreationPhase.PAUSED && resumePhase == null) {
                resumePhase = inferCreationResumePhase(entry, resourceType);
            }
            boolean payloadCommitted = entry.payloadCommitted();
            boolean commandGraphCommitted = entry.commandGraphCommitted();
            boolean triggerCommitted = entry.triggerCommitted();
            boolean legacyAggregateReplay = journalSchemaVersion < CREATION_JOURNAL_SCHEMA_VERSION
                && resourceType != null && (payloadCommitted || settlement == PayloadSettlement.ACCEPTED);
            if (legacyAggregateReplay) {
                phase = CreationPhase.PAYLOAD;
                resumePhase = CreationPhase.PAYLOAD;
                settlement = PayloadSettlement.PENDING;
                payloadCommitted = false;
                commandGraphCommitted = false;
                triggerCommitted = false;
            }
            if (resourceType == null && (phase != CreationPhase.METADATA && phase != CreationPhase.PAUSED
                || payloadCommitted || settlement != PayloadSettlement.PENDING)) {
                return null;
            }
            Object resource = null;
            Supplier<Object> resourceFactory = null;
            ServerResourceLocator locator = null;
            ContentHash payloadHash = null;
            UUID payloadRequestId = null;
            UUID payloadMutationId = null;
            if (resourceType != null) {
                if (entry.locator() == null || entry.locator().isBlank() || entry.payloadRequestId() == null
                    || entry.payloadMutationId() == null) {
                    return null;
                }
                locator = creationLocator(entry.serverId(), resourceType, entry.id());
                if (locator == null || !locator.canonicalText().equals(entry.locator())) {
                    return null;
                }
                payloadRequestId = creationUuid(entry.payloadRequestId());
                payloadMutationId = creationUuid(entry.payloadMutationId());
                if (payloadRequestId == null || payloadMutationId == null) {
                    return null;
                }
                boolean corePayload = isCorePayloadKind(entry.corePayloadKind());
                if (corePayload && !resourceType.isGraph()
                    || corePayload && resourceType == ReSyncResourceType.FUNCTION
                    && !"function".equals(entry.corePayloadKind())
                    || corePayload && resourceType != ReSyncResourceType.FUNCTION
                    && !"graph".equals(entry.corePayloadKind())) {
                    return null;
                }
                if (corePayload) {
                    if (resumePhase == CreationPhase.PREPARING || entry.payloadJson() == null
                        || entry.payloadJson().isBlank() || entry.payloadHash() == null
                        || entry.payloadHash().isBlank()) {
                        return null;
                    }
                    resource = decodeCorePayload(entry.corePayloadKind(), entry.payloadJson());
                    if (resource == null || !locator.equals(locatorOf(resource))) {
                        return null;
                    }
                    payloadHash = ContentHash.parseCanonicalText(entry.payloadHash());
                    if (!payloadHash.equals(corePayloadChecksum(entry.corePayloadKind(), entry.payloadJson()))) {
                        return null;
                    }
                } else if (resumePhase == CreationPhase.PREPARING) {
                    if (entry.payloadJson() != null && !entry.payloadJson().isBlank()
                        || entry.payloadHash() != null && !entry.payloadHash().isBlank()) {
                        return null;
                    }
                    resourceFactory = () -> buildResourceForCreation(entry.serverId(), resourceType, entry.id(),
                        entry.metadataPath(), entry.resourceTemplate());
                } else {
                    if (entry.payloadJson() == null || entry.payloadJson().isBlank() || entry.payloadHash() == null
                        || entry.payloadHash().isBlank()) {
                        return null;
                    }
                    resource = resourceType.deserialize(entry.payloadJson());
                    if (resource == null || !entry.id().equals(resourceType.extractId(resource))) {
                        return null;
                    }
                    payloadHash = ContentHash.parseCanonicalText(entry.payloadHash());
                    if (!payloadHash.equals(creationPayloadHash(entry.payloadJson()))) {
                        return null;
                    }
                }
                if ((resumePhase == CreationPhase.PAYLOAD && (payloadCommitted
                    || settlement != PayloadSettlement.PENDING))
                    || (resumePhase != CreationPhase.PAYLOAD && resumePhase != CreationPhase.PREPARING
                    && (!payloadCommitted || settlement != PayloadSettlement.ACCEPTED))) {
                    return null;
                }
            }
            if (resumePhase == CreationPhase.PREPARING && (resourceType == null || payloadCommitted
                || settlement != PayloadSettlement.PENDING)) {
                return null;
            }
            if (resourceType != ReSyncResourceType.COMMAND && entry.commandContext() != null
                && !entry.commandContext().isBlank()) {
                return null;
            }
            UUID metadataRequestId = creationUuid(entry.metadataRequestId());
            UUID metadataMutationId = creationUuid(entry.metadataMutationId());
            if (metadataRequestId == null || metadataMutationId == null) {
                return null;
            }
            UUID commandGraphRequestId = resourceType == ReSyncResourceType.COMMAND
                ? creationUuidOrStable(entry.commandGraphRequestId(), "command-graph-request", entry) : UUID.randomUUID();
            UUID commandGraphMutationId = resourceType == ReSyncResourceType.COMMAND
                ? creationUuidOrStable(entry.commandGraphMutationId(), "command-graph-mutation", entry) : UUID.randomUUID();
            UUID triggerRequestId = resourceType == ReSyncResourceType.COMMAND
                ? creationUuidOrStable(entry.triggerRequestId(), "trigger", entry) : UUID.randomUUID();
            if (resourceType == ReSyncResourceType.COMMAND
                && (commandGraphRequestId == null || commandGraphMutationId == null || triggerRequestId == null)) {
                return null;
            }
            CreationTransaction transaction = new CreationTransaction(key, resourceType, resource, resourceFactory,
                entry.resourceTemplate(), locator, payloadHash, payloadRequestId, payloadMutationId, metadata,
                resourceType == ReSyncResourceType.COMMAND ? entry.commandContext() : null, commandGraphRequestId,
                commandGraphMutationId, triggerRequestId, null, entry.sequence(), 0L);
            transaction.corePayload = isCorePayloadKind(entry.corePayloadKind());
            transaction.corePayloadKind = transaction.corePayload ? entry.corePayloadKind() : null;
            transaction.payloadJson = entry.payloadJson() == null || entry.payloadJson().isBlank()
                ? null : entry.payloadJson();
            transaction.metadataRequestId = metadataRequestId;
            transaction.metadataMutationId = metadataMutationId;
            transaction.commandGraphJson = entry.commandGraphJson() == null || entry.commandGraphJson().isBlank()
                ? null : entry.commandGraphJson();
            transaction.commandGraphHash = entry.commandGraphHash() == null || entry.commandGraphHash().isBlank()
                ? null : ContentHash.parseCanonicalText(entry.commandGraphHash());
            transaction.commandGraphBaseRevision = Math.max(-1L, entry.commandGraphBaseRevision());
            transaction.commandGraphBaseHash = entry.commandGraphBaseHash() == null
                || entry.commandGraphBaseHash().isBlank() ? null : entry.commandGraphBaseHash();
            transaction.commandGraphBaseGeneration = Math.max(-1L, entry.commandGraphBaseGeneration());
            boolean commandGraphJsonPresent = transaction.commandGraphJson != null
                && !transaction.commandGraphJson.isBlank();
            boolean commandGraphHashPresent = transaction.commandGraphHash != null;
            if (commandGraphJsonPresent != commandGraphHashPresent
                || commandGraphJsonPresent && !transaction.commandGraphHash.equals(
                    creationPayloadHash(transaction.commandGraphJson))) {
                return null;
            }
            if (commandGraphCommitted && !commandGraphJsonPresent) {
                return null;
            }
            String triggerBindingsJson = entry.triggerBindingsJson() == null || entry.triggerBindingsJson().isBlank()
                ? null : entry.triggerBindingsJson();
            if (resourceType != ReSyncResourceType.COMMAND
                && (triggerBindingsJson != null || entry.triggerBindingsHash() != null
                && !entry.triggerBindingsHash().isBlank() || entry.triggerExpectedBindingEpoch() != 0L
                || entry.triggerExpectedBindingHash() != null && !entry.triggerExpectedBindingHash().isBlank())) {
                return null;
            }
            if (triggerBindingsJson != null) {
                List<TriggerBinding> parsedTriggerBindings = parseCreationTriggerBindings(triggerBindingsJson);
                if (!triggerBindingsJson.equals(canonicalTriggerBindingsJson(triggerBindingsJson))
                    || entry.triggerBindingsHash() == null || entry.triggerBindingsHash().isBlank()
                    || entry.triggerExpectedBindingEpoch() < 1L || entry.triggerExpectedBindingHash() == null
                    || entry.triggerExpectedBindingHash().isBlank()
                    || parsedTriggerBindings == null
                    || !entry.triggerBindingsHash().equals(triggerBindingsHash(triggerBindingsJson))) {
                    return null;
                }
                transaction.triggerBindingsJson = triggerBindingsJson;
                transaction.triggerBindingsHash = entry.triggerBindingsHash();
                transaction.triggerExpectedBindingEpoch = entry.triggerExpectedBindingEpoch();
                transaction.triggerExpectedBindingHash = entry.triggerExpectedBindingHash();
                transaction.triggerPreparedBindings = parsedTriggerBindings;
            } else if (resourceType == ReSyncResourceType.COMMAND
                && (entry.triggerBindingsHash() != null && !entry.triggerBindingsHash().isBlank()
                || entry.triggerExpectedBindingEpoch() != 0L
                || entry.triggerExpectedBindingHash() != null && !entry.triggerExpectedBindingHash().isBlank())) {
                return null;
            }
            transaction.phase = phase;
            transaction.payloadSettlement = settlement;
            transaction.payloadCommitted = payloadCommitted;
            transaction.commandGraphCommitted = commandGraphCommitted;
            transaction.triggerCommitted = triggerCommitted;
            transaction.attempts = Math.max(0, Math.min(entry.attempts(), MAX_CREATION_ATTEMPTS));
            transaction.pausedResumePhase = phase == CreationPhase.PAUSED
                ? creationResumePhase(entry.resumePhase()) : null;
            boolean normalizedPhase = false;
            if (phase == CreationPhase.PAUSED) {
                if (!isValidCreationResumePhase(transaction, transaction.pausedResumePhase)) {
                    CreationPhase resume = recoverableCreationResumePhase(transaction,
                        transaction.pausedResumePhase);
                    if (resume == null) {
                        return null;
                    }
                    transaction.pausedResumePhase = resume;
                    normalizedPhase = true;
                }
            } else if (!isValidCreationResumePhase(transaction, phase)) {
                CreationPhase resume = recoverableCreationResumePhase(transaction, phase);
                if (resume == null) {
                    return null;
                }
                transaction.phase = CreationPhase.PAUSED;
                transaction.pausedResumePhase = resume;
                normalizedPhase = true;
            }
            boolean bindingResume = transaction.phase == CreationPhase.BINDING
                || transaction.phase == CreationPhase.PAUSED
                && transaction.pausedResumePhase == CreationPhase.BINDING;
            if (resourceType == ReSyncResourceType.COMMAND && transaction.payloadCommitted && bindingResume
                && !transaction.commandGraphCommitted
                && !validCommandGraphBase(transaction.commandGraphBaseRevision, transaction.commandGraphBaseHash,
                    transaction.commandGraphBaseGeneration)) {
                transaction.phase = CreationPhase.PAUSED;
                transaction.pausedResumePhase = CreationPhase.BINDING;
                normalizedPhase = true;
            }
            transaction.journalSettled = !normalizedPhase;
            return transaction;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String creationJournalRestoreDiagnostic(CreationJournalEntry entry) {
        if (entry == null) {
            return "entry=missing";
        }
        try {
            ReSyncResourceType type = entry.resourceType() == null || entry.resourceType().isBlank()
                ? null : ReSyncResourceType.byTypeId(entry.resourceType());
            ServerResourceLocator locator = type != null ? creationLocator(entry.serverId(), type, entry.id()) : null;
            Object corePayload = isCorePayloadKind(entry.corePayloadKind())
                ? decodeCorePayload(entry.corePayloadKind(), entry.payloadJson()) : null;
            ContentHash recordedHash = entry.payloadHash() == null || entry.payloadHash().isBlank()
                ? null : ContentHash.parseCanonicalText(entry.payloadHash());
            ContentHash actualHash = isCorePayloadKind(entry.corePayloadKind())
                ? corePayloadChecksum(entry.corePayloadKind(), entry.payloadJson()) : creationPayloadHash(entry.payloadJson());
            return "server=" + Objects.toString(entry.serverId(), "")
                + ",type=" + Objects.toString(entry.resourceType(), "")
                + ",id=" + Objects.toString(entry.id(), "")
                + ",phase=" + Objects.toString(entry.phase(), "")
                + ",settlement=" + Objects.toString(entry.payloadSettlement(), "")
                + ",limits=" + creationJournalEntryWithinLimits(entry)
                + ",typeEnabled=" + (type != null && type.enabled())
                + ",metadataIdentity=" + (type != null && type.typeId().equals(entry.metadataType())
                    && entry.id().equals(entry.metadataId()))
                + ",locator=" + (locator != null && locator.canonicalText().equals(entry.locator()))
                + ",corePayload=" + (corePayload != null)
                + ",coreLocator=" + (corePayload != null && locator != null && locator.equals(locatorOf(corePayload)))
                + ",payloadHash=" + Objects.equals(recordedHash, actualHash)
                + ",metadataRequest=" + (creationUuid(entry.metadataRequestId()) != null)
                + ",metadataMutation=" + (creationUuid(entry.metadataMutationId()) != null)
                + ",resume=" + Objects.toString(creationResumePhase(entry.resumePhase()), "");
        } catch (RuntimeException exception) {
            return "server=" + Objects.toString(entry.serverId(), "")
                + ",type=" + Objects.toString(entry.resourceType(), "")
                + ",id=" + Objects.toString(entry.id(), "")
                + ",phase=" + Objects.toString(entry.phase(), "")
                + ",diagnostic=" + TaskIdentities.failureName(exception);
        }
    }

    private boolean isValidCreationResumePhase(CreationTransaction transaction, CreationPhase phase) {
        if (transaction == null || phase == null || phase == CreationPhase.PAUSED
            || phase == CreationPhase.COMPLETE || phase == CreationPhase.FAILED) {
            return false;
        }
        if (transaction.resourceType == null) {
            return phase == CreationPhase.METADATA && !transaction.payloadCommitted
                && transaction.payloadSettlement == PayloadSettlement.PENDING
                && !transaction.commandGraphCommitted && !transaction.triggerCommitted;
        }
        if (phase == CreationPhase.PREPARING) {
            return !transaction.payloadCommitted && transaction.payloadJson == null && transaction.payloadHash == null
                && transaction.payloadSettlement == PayloadSettlement.PENDING
                && !transaction.commandGraphCommitted && !transaction.triggerCommitted;
        }
        if (phase == CreationPhase.PAYLOAD) {
            return !transaction.payloadCommitted && transaction.payloadJson != null
                && transaction.payloadHash != null && transaction.payloadSettlement == PayloadSettlement.PENDING
                && !transaction.commandGraphCommitted && !transaction.triggerCommitted;
        }
        if (phase == CreationPhase.BINDING) {
            return transaction.resourceType == ReSyncResourceType.COMMAND && transaction.payloadCommitted
                && transaction.payloadSettlement == PayloadSettlement.ACCEPTED
                && transaction.commandContext != null && !transaction.commandContext.isBlank()
                && !transaction.triggerCommitted && validCommandGraphBase(transaction.commandGraphBaseRevision,
                    transaction.commandGraphBaseHash, transaction.commandGraphBaseGeneration)
                && validCommandGraphPayloadOrEmpty(transaction)
                && (!transaction.commandGraphCommitted || validCommandGraphPayload(transaction));
        }
        return phase == CreationPhase.SETTLEMENT && validCreationSettlementState(transaction);
    }

    private boolean validCreationSettlementState(CreationTransaction transaction) {
        if (transaction == null || transaction.resourceType == null) {
            return false;
        }
        if (!transaction.payloadCommitted || transaction.payloadSettlement != PayloadSettlement.ACCEPTED) {
            return false;
        }
        if (transaction.resourceType != ReSyncResourceType.COMMAND
            || transaction.commandContext == null || transaction.commandContext.isBlank()) {
            return !transaction.commandGraphCommitted && !transaction.triggerCommitted;
        }
        return transaction.commandGraphCommitted && transaction.triggerCommitted
            && validCommandGraphPayload(transaction)
            && validCommandGraphBase(transaction.commandGraphBaseRevision, transaction.commandGraphBaseHash,
                transaction.commandGraphBaseGeneration);
    }

    private boolean validCommandGraphPayloadOrEmpty(CreationTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        boolean jsonPresent = transaction.commandGraphJson != null && !transaction.commandGraphJson.isBlank();
        boolean hashPresent = transaction.commandGraphHash != null;
        return jsonPresent == hashPresent && (!jsonPresent || validCommandGraphPayload(transaction));
    }

    private boolean validCommandGraphPayload(CreationTransaction transaction) {
        if (transaction == null || transaction.commandGraphJson == null
            || transaction.commandGraphJson.isBlank() || transaction.commandGraphHash == null) {
            return false;
        }
        try {
            return transaction.commandGraphHash.equals(creationPayloadHash(transaction.commandGraphJson));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private CreationPhase recoverableCreationResumePhase(CreationTransaction transaction, CreationPhase phase) {
        if (transaction == null || phase == null) {
            return null;
        }
        if (transaction.resourceType == null) {
            return phase == CreationPhase.METADATA && !transaction.payloadCommitted
                && transaction.payloadSettlement == PayloadSettlement.PENDING ? CreationPhase.METADATA : null;
        }
        if (phase == CreationPhase.PREPARING && !transaction.payloadCommitted && transaction.payloadJson == null
            && transaction.payloadHash == null && transaction.payloadSettlement == PayloadSettlement.PENDING
            && transaction.resourceFactory != null) {
            return CreationPhase.PREPARING;
        }
        if (phase == CreationPhase.PAYLOAD && !transaction.payloadCommitted && transaction.payloadJson != null
            && transaction.payloadHash != null && transaction.payloadSettlement == PayloadSettlement.PENDING) {
            return CreationPhase.PAYLOAD;
        }
        if (transaction.resourceType == ReSyncResourceType.COMMAND && transaction.payloadCommitted
            && transaction.payloadSettlement == PayloadSettlement.ACCEPTED && transaction.commandContext != null
            && !transaction.commandContext.isBlank()
            && (phase == CreationPhase.BINDING || phase == CreationPhase.SETTLEMENT)) {
            return CreationPhase.BINDING;
        }
        if (phase == CreationPhase.SETTLEMENT && transaction.payloadCommitted
            && transaction.payloadSettlement == PayloadSettlement.ACCEPTED
            && transaction.resourceType != ReSyncResourceType.COMMAND) {
            return CreationPhase.SETTLEMENT;
        }
        return null;
    }

    private boolean isValidCompletedCreationJournalEntry(CreationJournalEntry entry) {
        if (entry == null || entry.resourceType() == null || entry.resourceType().isBlank()) {
            return "folder".equals(entry != null ? entry.metadataType() : null)
                && entry != null && !entry.payloadCommitted()
                && payloadSettlement(entry.payloadSettlement()) == PayloadSettlement.PENDING;
        }
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(entry.resourceType());
        if (resourceType == null || !resourceType.enabled() || !entry.payloadCommitted()
            || payloadSettlement(entry.payloadSettlement()) != PayloadSettlement.ACCEPTED) {
            return false;
        }
        if (resourceType != ReSyncResourceType.COMMAND || entry.commandContext() == null
            || entry.commandContext().isBlank()) {
            return !entry.commandGraphCommitted() && !entry.triggerCommitted();
        }
        if (!entry.commandGraphCommitted() || !entry.triggerCommitted() || entry.commandGraphJson() == null
            || entry.commandGraphJson().isBlank() || entry.commandGraphHash() == null
            || entry.commandGraphHash().isBlank()
            || !validCommandGraphBase(entry.commandGraphBaseRevision(), entry.commandGraphBaseHash(),
                entry.commandGraphBaseGeneration())) {
            return false;
        }
        try {
            return ContentHash.parseCanonicalText(entry.commandGraphHash())
                .equals(creationPayloadHash(entry.commandGraphJson()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private CreationPhase inferCreationResumePhase(CreationJournalEntry entry, ReSyncResourceType resourceType) {
        if (entry == null) {
            return null;
        }
        if (resourceType == null) {
            return CreationPhase.METADATA;
        }
        if (!entry.payloadCommitted()) {
            return entry.payloadJson() == null || entry.payloadJson().isBlank()
                ? CreationPhase.PREPARING : CreationPhase.PAYLOAD;
        }
        return entry.commandContext() != null && !entry.commandContext().isBlank() && !entry.triggerCommitted()
            ? CreationPhase.BINDING : CreationPhase.SETTLEMENT;
    }

    private CreationPhase inferCreationResumePhase(CreationTransaction transaction) {
        if (transaction == null) {
            return null;
        }
        if (transaction.resourceType == null) {
            return CreationPhase.METADATA;
        }
        if (!transaction.payloadCommitted) {
            return transaction.payloadJson == null && transaction.resourceFactory != null
                ? CreationPhase.PREPARING : CreationPhase.PAYLOAD;
        }
        return transaction.commandContext != null && !transaction.commandContext.isBlank()
            && !transaction.triggerCommitted ? CreationPhase.BINDING : CreationPhase.SETTLEMENT;
    }

    private CommandGraphAdmission prepareCommandGraphAdmission(CreationTransaction transaction) {
        if (transaction == null || transaction.resourceType != ReSyncResourceType.COMMAND) {
            return CommandGraphAdmission.unavailable();
        }
        String commandGraphJson;
        ContentHash commandGraphHash;
        long baseRevision;
        String baseHash;
        long baseGeneration;
        synchronized (transaction) {
            commandGraphJson = transaction.commandGraphJson;
            commandGraphHash = transaction.commandGraphHash;
            baseRevision = transaction.commandGraphBaseRevision;
            baseHash = transaction.commandGraphBaseHash;
            baseGeneration = transaction.commandGraphBaseGeneration;
        }
        if (commandGraphJson == null || commandGraphJson.isBlank() || commandGraphHash == null
            || !validCommandGraphBase(baseRevision, baseHash, baseGeneration)) {
            return CommandGraphAdmission.unavailable();
        }
        synchronized (transaction) {
            if (transaction.commandGraphLease != null && transaction.commandGraphLease.isCurrent()
                && transaction.commandGraphLeaseGeneration >= 0L
                && flowStore.currentGeneration(transaction.key.serverId(), ReSyncResourceType.COMMAND,
                    transaction.key.id()) == transaction.commandGraphLeaseGeneration) {
                return new CommandGraphAdmission(transaction.commandGraphLease,
                    transaction.commandGraphLeaseGeneration, false, true);
            }
        }
        FlowGraph commandGraph;
        try {
            Object decoded = ReSyncResourceType.COMMAND.deserialize(commandGraphJson);
            if (!(decoded instanceof FlowGraph graph) || !transaction.key.id().equals(graph.getId())) {
                return CommandGraphAdmission.unavailable();
            }
            commandGraph = graph;
        } catch (RuntimeException exception) {
            return CommandGraphAdmission.unavailable();
        }
        FlowGraph existingDraft = flowStore.getFromDraft(transaction.key.serverId(), ReSyncResourceType.COMMAND,
            transaction.key.id());
        long currentGeneration = flowStore.currentGeneration(transaction.key.serverId(), ReSyncResourceType.COMMAND,
            transaction.key.id());
        if (existingDraft != null) {
            SyncedResourceCache.SaveLease<FlowGraph> existingLease = flowStore.getDraftLeaseIfGeneration(
                transaction.key.serverId(), ReSyncResourceType.COMMAND, transaction.key.id(), currentGeneration,
                value -> payloadHashMatches(ReSyncResourceType.COMMAND, value, commandGraphHash));
            if (existingLease != null) {
                return new CommandGraphAdmission(existingLease, currentGeneration, false, true);
            }
            return new CommandGraphAdmission(null, currentGeneration, true, true);
        }
        if (!matchesAuthoritativeResourceFence(transaction.key.serverId(), ReSyncResourceType.COMMAND,
            transaction.key.id(), baseRevision, baseHash, baseGeneration)) {
            return CommandGraphAdmission.unavailable();
        }
        long expectedGeneration = baseGeneration;
        SyncedResourceCache.SaveLease<FlowGraph> lease = flowStore
            .putInDraftIfAuthoritativeIfGenerationLease(transaction.key.serverId(), ReSyncResourceType.COMMAND,
                transaction.key.id(), commandGraph, expectedGeneration,
                authoritative -> authoritative != null && authoritative.getResourceRevision() == baseRevision
                    && baseHash.equals(authoritative.getResourceHash()));
        if (lease != null) {
            return new CommandGraphAdmission(lease,
                flowStore.currentGeneration(transaction.key.serverId(), ReSyncResourceType.COMMAND, transaction.key.id()),
                false, true);
        }
        FlowGraph racedDraft = flowStore.getFromDraft(transaction.key.serverId(), ReSyncResourceType.COMMAND,
            transaction.key.id());
        if (racedDraft != null) {
            return new CommandGraphAdmission(null,
                flowStore.currentGeneration(transaction.key.serverId(), ReSyncResourceType.COMMAND,
                    transaction.key.id()), true, true);
        }
        return CommandGraphAdmission.unavailable();
    }

    private boolean validCommandGraphBase(long revision, String hash, long generation) {
        if (revision < 0L || generation <= 0L || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return hash.equals(ContentHash.parseCanonicalText(hash).canonicalText());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean enqueueCreationJournal(CreationTransaction transaction, Runnable persisted, Runnable failed) {
        if (transaction == null || transaction.suspended || closed) {
            return false;
        }
        long revision;
        Runnable rejected;
        boolean coalesced;
        synchronized (transaction) {
            if (transaction.suspended) {
                return false;
            }
            transaction.journalPersisted = persisted;
            transaction.journalFailed = failed;
            rejected = transaction.journalFailed;
            revision = ++transaction.journalRevision;
            coalesced = transaction.journalWriteQueued;
            if (!coalesced) {
                transaction.journalWriteQueued = true;
            }
        }
        if (coalesced) {
            traceCreationLifecycle(transaction, "create_journal_write_coalesced", "newer_revision_recorded");
            return !transaction.suspended;
        }
        traceCreationLifecycle(transaction, "create_journal_write_queued", "phase_snapshot_queued");
        Runnable settle = () -> {
            long startedAt = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
            boolean saved;
            try {
                saved = persistCreationJournalNow();
            } catch (RuntimeException | Error exception) {
                saved = false;
            }
            Runnable nextPersisted = null;
            Runnable nextFailed = null;
            boolean superseded;
            synchronized (transaction) {
                superseded = transaction.journalRevision != revision;
                transaction.journalWriteQueued = false;
                if (superseded) {
                    nextPersisted = transaction.journalPersisted;
                    nextFailed = transaction.journalFailed;
                    transaction.journalSettled = false;
                } else {
                    nextPersisted = transaction.journalPersisted;
                    nextFailed = transaction.journalFailed;
                    transaction.journalPersisted = null;
                    transaction.journalFailed = null;
                    transaction.journalSettled = saved;
                }
            }
            ReSyncFlowClient.traceLifecycle(transaction.key.serverId(), "create_journal_write_settled", "serverId",
                transaction.key.serverId(), "resourceKey", transaction.key.type() + ":" + transaction.key.id(),
                "operation", "journal", "requestId", transaction.payloadRequestId, "mutationId",
                transaction.payloadMutationId, "generation", transaction.admissionGeneration, "authorityEpoch",
                coreGraphUiProjection.authorityEpoch(transaction.key.serverId()), "revision", revision, "phase",
                transaction.phase, "saved", saved, "superseded", superseded, "elapsedMs",
                startedAt == 0L ? -1L : ((System.nanoTime() - startedAt) / 1_000_000L));
            if (superseded) {
                enqueueCreationJournal(transaction, nextPersisted, nextFailed);
                return;
            }
            if (transaction.suspended || creationTransactions.get(transaction.key) != transaction) {
                return;
            }
            if (saved) {
                if (nextPersisted != null) {
                    nextPersisted.run();
                }
            } else if (nextFailed != null) {
                nextFailed.run();
            }
        };
        try {
            creationJournalScheduler.execute(settle);
            return true;
        } catch (IllegalStateException exception) {
            synchronized (transaction) {
                transaction.journalWriteQueued = false;
                transaction.journalSettled = false;
            }
            if (rejected != null) {
                rejected.run();
            }
            traceCreationLifecycle(transaction, "create_journal_write_rejected", "worker_queue_full");
            return false;
        }
    }

    private boolean persistCreationJournalNow() {
        synchronized (creationJournalPersistenceLock) {
            List<CreationJournalEntry> entries;
            List<CreationTransaction> transactionSnapshot;
            synchronized (creationTransactionLock) {
                if (creationTransactions.size() > MAX_PENDING_CREATION_TRANSACTIONS) {
                    return false;
                }
                transactionSnapshot = creationTransactions.values().stream()
                    .sorted((left, right) -> Long.compare(left.sequence, right.sequence)).toList();
            }
            entries = transactionSnapshot.stream().map(this::journalEntry).toList();
            if (!creationJournalEntriesWithinLimits(entries)) {
                return false;
            }
            try {
                long generation = creationJournalGeneration.updateAndGet(value ->
                    value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L);
                byte[] bytes = serializeCreationJournalWithinLimit(
                    new CreationJournal(CREATION_JOURNAL_SCHEMA_VERSION, generation, entries));
                if (bytes == null) {
                    return false;
                }
                creationJournalStorage.write("journal", new String(bytes, StandardCharsets.UTF_8));
                creationJournalEvidenceRetained = false;
                return true;
            } catch (RuntimeException exception) {
                ReSyncFlowClient.traceLifecycle(null, "create_journal_persist_failed", "serverId", "unresolved",
                    "resourceKey", "creation-journal", "operation", "journal", "requestId", null,
                    "mutationId", null, "generation", creationJournalGeneration.get(), "authorityEpoch", 0L,
                    "revision", 0L, "reason", TaskIdentities.failureName(exception));
                return false;
            }
        }
    }

    private byte[] serializeCreationJournalWithinLimit(CreationJournal journal) {
        if (journal == null || journal.entries() == null || journal.entries().size() > MAX_PENDING_CREATION_TRANSACTIONS) {
            return null;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(8_192L,
            Math.max(1L, MAX_CREATION_JOURNAL_BYTES)));
        OutputStream bounded = new OutputStream() {
            private long written;

            @Override
            public void write(int value) throws IOException {
                if (written >= MAX_CREATION_JOURNAL_BYTES) {
                    throw new IOException("Resource creation journal size limit exceeded");
                }
                output.write(value);
                written++;
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length
                    || length > MAX_CREATION_JOURNAL_BYTES - written) {
                    throw new IOException("Resource creation journal size limit exceeded");
                }
                output.write(bytes, offset, length);
                written += length;
            }
        };
        try (Writer writer = new OutputStreamWriter(bounded, StandardCharsets.UTF_8)) {
            gson.toJson(journal.json(), writer);
            writer.flush();
            return output.toByteArray();
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private boolean creationJournalEntriesWithinLimits(List<CreationJournalEntry> entries) {
        if (entries == null || entries.size() > MAX_PENDING_CREATION_TRANSACTIONS) {
            return false;
        }
        long estimate = 256L;
        for (CreationJournalEntry entry : entries) {
            if (!creationJournalEntryWithinLimits(entry)) {
                return false;
            }
            estimate = boundedCreationJournalEstimate(estimate, entry.serverId());
            estimate = boundedCreationJournalEstimate(estimate, entry.resourceType());
            estimate = boundedCreationJournalEstimate(estimate, entry.id());
            estimate = boundedCreationJournalEstimate(estimate, entry.resourceTemplate());
            estimate = boundedCreationJournalEstimate(estimate, entry.payloadJson());
            estimate = boundedCreationJournalEstimate(estimate, entry.locator());
            estimate = boundedCreationJournalEstimate(estimate, entry.payloadHash());
            estimate = boundedCreationJournalEstimate(estimate, entry.payloadRequestId());
            estimate = boundedCreationJournalEstimate(estimate, entry.payloadMutationId());
            estimate = boundedCreationJournalEstimate(estimate, entry.metadataRequestId());
            estimate = boundedCreationJournalEstimate(estimate, entry.metadataMutationId());
            estimate = boundedCreationJournalEstimate(estimate, entry.metadataType());
            estimate = boundedCreationJournalEstimate(estimate, entry.metadataId());
            estimate = boundedCreationJournalEstimate(estimate, entry.metadataName());
            estimate = boundedCreationJournalEstimate(estimate, entry.metadataPath());
            estimate = boundedCreationJournalEstimate(estimate, entry.metadataParentPath());
            estimate = boundedCreationJournalEstimate(estimate, entry.commandContext());
            estimate = boundedCreationJournalEstimate(estimate, entry.commandGraphJson());
            estimate = boundedCreationJournalEstimate(estimate, entry.commandGraphHash());
            estimate = boundedCreationJournalEstimate(estimate, entry.commandGraphRequestId());
            estimate = boundedCreationJournalEstimate(estimate, entry.commandGraphMutationId());
            estimate = boundedCreationJournalEstimate(estimate, entry.triggerRequestId());
            estimate = boundedCreationJournalEstimate(estimate, entry.triggerBindingsJson());
            estimate = boundedCreationJournalEstimate(estimate, entry.triggerBindingsHash());
            estimate = boundedCreationJournalEstimate(estimate, entry.triggerExpectedBindingHash());
            estimate = boundedCreationJournalEstimate(estimate, entry.phase());
            estimate = boundedCreationJournalEstimate(estimate, entry.payloadSettlement());
            estimate = boundedCreationJournalEstimate(estimate, entry.resumePhase());
            estimate = boundedCreationJournalEstimate(estimate, entry.commandGraphBaseHash());
            estimate = boundedCreationJournalEstimate(estimate, entry.corePayloadKind());
            estimate = boundedCreationJournalEstimate(estimate, 2_048L);
            if (estimate > MAX_CREATION_JOURNAL_BYTES) {
                return false;
            }
        }
        return estimate <= MAX_CREATION_JOURNAL_BYTES;
    }

    private boolean creationJournalEntryWithinLimits(CreationJournalEntry entry) {
        if (entry == null || !creationFieldWithinLimit(entry.serverId())
            || !creationFieldWithinLimit(entry.resourceType()) || !creationFieldWithinLimit(entry.id())
            || !creationFieldWithinLimit(entry.resourceTemplate()) || !creationFieldWithinLimit(entry.locator())
            || !creationFieldWithinLimit(entry.payloadHash()) || !creationFieldWithinLimit(entry.payloadRequestId())
            || !creationFieldWithinLimit(entry.payloadMutationId()) || !creationFieldWithinLimit(entry.metadataRequestId())
            || !creationFieldWithinLimit(entry.metadataMutationId()) || !creationFieldWithinLimit(entry.metadataType())
            || !creationFieldWithinLimit(entry.metadataId()) || !creationFieldWithinLimit(entry.metadataName())
            || !creationFieldWithinLimit(entry.metadataPath()) || !creationFieldWithinLimit(entry.metadataParentPath())
            || !creationFieldWithinLimit(entry.commandContext()) || !creationFieldWithinLimit(entry.commandGraphHash())
            || !creationFieldWithinLimit(entry.commandGraphRequestId())
            || !creationFieldWithinLimit(entry.commandGraphMutationId())
            || !creationFieldWithinLimit(entry.triggerRequestId()) || !creationFieldWithinLimit(entry.triggerBindingsHash())
            || !creationFieldWithinLimit(entry.triggerExpectedBindingHash()) || !creationFieldWithinLimit(entry.phase())
            || !creationFieldWithinLimit(entry.payloadSettlement()) || !creationFieldWithinLimit(entry.resumePhase())
            || !creationFieldWithinLimit(entry.commandGraphBaseHash()) || !creationFieldWithinLimit(entry.corePayloadKind())
            || !creationPayloadFieldWithinLimit(entry.payloadJson())
            || !creationCommandGraphFieldWithinLimit(entry.commandGraphJson())
            || !creationTriggerFieldWithinLimit(entry.triggerBindingsJson())) {
            return false;
        }
        if (entry.commandContext() != null && !creationTriggerTextWithinLimit(entry.commandContext())) {
            return false;
        }
        if (entry.triggerBindingsJson() != null && !entry.triggerBindingsJson().isBlank()
            && parseCreationTriggerBindings(entry.triggerBindingsJson()) == null) {
            return false;
        }
        return true;
    }

    private boolean creationJsonFieldWithinLimit(String value) {
        return value == null || creationPayloadFieldWithinLimit(value)
            && creationCommandGraphFieldWithinLimit(value);
    }

    private boolean creationPayloadFieldWithinLimit(String value) {
        return value == null || creationUtf8Bytes(value, MAX_CREATION_PAYLOAD_BYTES) <= MAX_CREATION_PAYLOAD_BYTES;
    }

    private boolean creationCommandGraphFieldWithinLimit(String value) {
        return value == null || creationUtf8Bytes(value, MAX_CREATION_COMMAND_GRAPH_BYTES)
            <= MAX_CREATION_COMMAND_GRAPH_BYTES;
    }

    private boolean creationTriggerFieldWithinLimit(String value) {
        return value == null || creationUtf8Bytes(value, MAX_CREATION_TRIGGER_BYTES) <= MAX_CREATION_TRIGGER_BYTES;
    }

    private static boolean creationFieldWithinLimit(String value) {
        return value == null || creationUtf8Bytes(value, MAX_CREATION_FIELD_BYTES) <= MAX_CREATION_FIELD_BYTES;
    }

    private boolean creationTriggerTextWithinLimit(String value) {
        return value == null || value.length() <= MAX_CREATION_TRIGGER_BINDING_TEXT
            && creationUtf8Bytes(value, MAX_CREATION_TRIGGER_BINDING_TEXT) <= MAX_CREATION_TRIGGER_BINDING_TEXT;
    }

    private boolean creationMetadataWithinLimits(CreationMetadataIntent metadata) {
        return metadata != null && creationFieldWithinLimit(metadata.type())
            && creationFieldWithinLimit(metadata.id()) && creationFieldWithinLimit(metadata.name())
            && creationFieldWithinLimit(metadata.path()) && creationFieldWithinLimit(metadata.parentPath());
    }

    private static long creationUtf8Bytes(String value, long maximumBytes) {
        if (value == null) {
            return 0L;
        }
        if (maximumBytes < 0L || value.length() > maximumBytes) {
            return maximumBytes == Long.MAX_VALUE ? Long.MAX_VALUE : maximumBytes + 1L;
        }
        try {
            return value.getBytes(StandardCharsets.UTF_8).length;
        } catch (RuntimeException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long boundedCreationJournalEstimate(long current, String value) {
        return boundedCreationJournalEstimate(current, value == null ? 0L :
            Math.min(Long.MAX_VALUE - 2L, value.length() * 6L + 2L));
    }

    private long boundedCreationJournalEstimate(long current, long addition) {
        if (current > MAX_CREATION_JOURNAL_BYTES || addition > MAX_CREATION_JOURNAL_BYTES
            || current > MAX_CREATION_JOURNAL_BYTES - addition) {
            return MAX_CREATION_JOURNAL_BYTES + 1L;
        }
        return current + addition;
    }

    private CreationJournalEntry journalEntry(CreationTransaction transaction) {
        synchronized (transaction) {
            return new CreationJournalEntry(transaction.key.serverId(),
                transaction.resourceType != null ? transaction.resourceType.typeId() : "", transaction.key.id(),
                transaction.resourceTemplate,
                transaction.payloadJson, transaction.locator != null ? transaction.locator.canonicalText() : "",
                transaction.payloadHash != null ? transaction.payloadHash.canonicalText() : "",
                transaction.payloadRequestId != null ? transaction.payloadRequestId.toString() : "",
                transaction.payloadMutationId != null ? transaction.payloadMutationId.toString() : "",
                transaction.metadataRequestId != null ? transaction.metadataRequestId.toString() : "",
                transaction.metadataMutationId != null ? transaction.metadataMutationId.toString() : "",
                transaction.metadata.type(), transaction.metadata.id(), transaction.metadata.name(),
                transaction.metadata.path(), transaction.metadata.parentPath(), transaction.metadata.sortOrder(),
                transaction.metadata.folder(), transaction.commandContext, transaction.commandGraphJson,
                transaction.commandGraphHash != null ? transaction.commandGraphHash.canonicalText() : "",
                transaction.commandGraphRequestId != null ? transaction.commandGraphRequestId.toString() : "",
                transaction.commandGraphMutationId != null ? transaction.commandGraphMutationId.toString() : "",
                transaction.triggerRequestId != null ? transaction.triggerRequestId.toString() : "",
                transaction.triggerBindingsJson, transaction.triggerBindingsHash,
                transaction.triggerExpectedBindingEpoch, transaction.triggerExpectedBindingHash,
                transaction.commandGraphCommitted, transaction.triggerCommitted, transaction.phase.name(),
                transaction.payloadSettlement.name(), transaction.payloadCommitted, transaction.attempts,
                transaction.sequence, transaction.pausedResumePhase != null ? transaction.pausedResumePhase.name() : "",
                transaction.commandGraphBaseRevision, transaction.commandGraphBaseHash,
                transaction.commandGraphBaseGeneration, transaction.corePayload ? transaction.corePayloadKind : "");
        }
    }

    private CreationPhase creationPhase(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CreationPhase.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private CreationPhase creationResumePhase(String value) {
        return creationPhase(value);
    }

    private PayloadSettlement payloadSettlement(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PayloadSettlement.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private UUID creationUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equals(value) ? uuid : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private UUID creationUuidOrStable(String value, String purpose, CreationJournalEntry entry) {
        if (value != null && !value.isBlank()) {
            return creationUuid(value);
        }
        if (purpose == null || purpose.isBlank() || entry == null || entry.serverId() == null
            || entry.resourceType() == null || entry.id() == null) {
            return null;
        }
        String identity = purpose + "\u0000" + entry.serverId() + "\u0000" + entry.resourceType()
            + "\u0000" + entry.id() + "\u0000" + entry.sequence();
        return CanonicalUuids.nameUuidFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private ServerResourceLocator creationLocator(String serverId, ReSyncResourceType resourceType, String id) {
        if (serverId == null || serverId.isBlank() || resourceType == null || id == null || id.isBlank()) {
            return null;
        }
        try {
            return new ServerResourceLocator(UUID.fromString(serverId),
                ContractRef.of(CREATION_RESOURCE_OWNER, ResourceTypeId.of(resourceType.typeId())), id);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean queueCreationPreparation(CreationTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        synchronized (transaction) {
            if (transaction.suspended || transaction.isTerminal() || transaction.phase != CreationPhase.PREPARING) {
                traceCreationLifecycle(transaction, "create_preparation_rejected", "transaction_not_preparing");
                return false;
            }
            if (transaction.creationPreparationQueued) {
                traceCreationLifecycle(transaction, "create_preparation_coalesced", "already_queued");
                return true;
            }
            transaction.creationPreparationQueued = true;
        }
        traceCreationLifecycle(transaction, "create_preparation_queued", "worker_admission_pending");
        try {
            projectMetadataHydrations.execute(() -> {
                try {
                    prepareCreationTransaction(transaction);
                } catch (RuntimeException | Error exception) {
                    failCreation(transaction, "Creation Preparation Failed");
                } finally {
                    synchronized (transaction) {
                        transaction.creationPreparationQueued = false;
                    }
                }
            });
            return true;
        } catch (IllegalStateException exception) {
            synchronized (transaction) {
                transaction.creationPreparationQueued = false;
            }
            traceCreationLifecycle(transaction, "create_preparation_rejected", "worker_queue_full");
            return false;
        }
    }

    private void prepareCreationTransaction(CreationTransaction transaction) {
        if (transaction == null || transaction.resourceType == null || transaction.suspended || closed
            || creationTransactions.get(transaction.key) != transaction) {
            traceCreationLifecycle(transaction, "create_preparation_rejected", "transaction_not_current");
            return;
        }
        traceCreationLifecycle(transaction, "create_preparation_started", "resource_factory_started");
        String payloadJson;
        Object resource;
        ContentHash payloadHash;
        try {
            Object source = transaction.resourceFactory != null ? transaction.resourceFactory.get() : transaction.resource;
            payloadJson = source != null ? transaction.resourceType.serialize(source) : null;
            if (!creationPayloadFieldWithinLimit(payloadJson)) {
                throw new IllegalArgumentException("The resource payload exceeds the protocol limit");
            }
            resource = payloadJson != null ? transaction.resourceType.deserialize(payloadJson) : null;
            payloadHash = creationPayloadHash(payloadJson);
        } catch (RuntimeException | Error exception) {
            payloadJson = null;
            resource = null;
            payloadHash = null;
        }
        if (payloadJson == null || resource == null || payloadHash == null
            || !transaction.key.id().equals(transaction.resourceType.extractId(resource))) {
            traceCreationLifecycle(transaction, "create_preparation_rejected", "resource_identity_or_hash_invalid");
            failCreation(transaction, "The resource changed before creation completed.");
            return;
        }
        synchronized (transaction) {
            if (transaction.suspended || transaction.phase != CreationPhase.PREPARING) {
                return;
            }
            transaction.payloadJson = payloadJson;
            transaction.resource = resource;
            transaction.payloadHash = payloadHash;
            transaction.phase = CreationPhase.PAYLOAD;
            transaction.journalSettled = false;
        }
        traceCreationLifecycle(transaction, "create_preparation_complete", "payload_prepared");
        enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
            () -> failCreation(transaction, "Creation Journal Unavailable"));
    }

    private boolean queueCommandBindingPreparation(CreationTransaction transaction) {
        try {
            projectMetadataHydrations.execute(() -> prepareCommandBinding(transaction));
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private void prepareCommandBinding(CreationTransaction transaction) {
        if (transaction == null || transaction.resourceType != ReSyncResourceType.COMMAND
            || transaction.suspended || closed || creationTransactions.get(transaction.key) != transaction) {
            return;
        }
        String payloadJson;
        ContentHash payloadHash;
        FlowGraph graph;
        try {
            Object source = transaction.payloadJson != null ? transaction.resourceType.deserialize(transaction.payloadJson) : null;
            if (!(source instanceof FlowGraph sourceGraph)) {
                finishCommandBindingPreparation(transaction, null, null, null);
                return;
            }
            graph = sourceGraph;
            ensureCommandStartNodeInMemory(graph);
            if (transaction.commandContext == null || transaction.commandContext.isBlank()
                || !applyCommandContext(graph, transaction.commandContext)) {
                finishCommandBindingPreparation(transaction, null, null, null);
                return;
            }
            payloadJson = transaction.resourceType.serialize(graph);
            if (!creationCommandGraphFieldWithinLimit(payloadJson)) {
                finishCommandBindingPreparation(transaction, null, null, null);
                return;
            }
            payloadHash = creationPayloadHash(payloadJson);
        } catch (RuntimeException | Error exception) {
            finishCommandBindingPreparation(transaction, null, null, null);
            return;
        }
        finishCommandBindingPreparation(transaction, graph, payloadJson, payloadHash);
    }

    private void finishCommandBindingPreparation(CreationTransaction transaction, FlowGraph graph,
                                                 String payloadJson, ContentHash payloadHash) {
        if (transaction == null) {
            return;
        }
        boolean accepted = graph != null && payloadJson != null && !payloadJson.isBlank() && payloadHash != null;
        boolean active;
        synchronized (transaction) {
            transaction.commandGraphPreparing = false;
            active = !transaction.suspended && transaction.phase == CreationPhase.BINDING;
            if (active && accepted) {
                transaction.resource = graph;
                transaction.commandGraphJson = payloadJson;
                transaction.commandGraphHash = payloadHash;
                transaction.journalSettled = false;
            }
        }
        if (!active) {
            traceCreationLifecycle(transaction, "create_command_binding_preparation_rejected",
                "transaction_not_binding");
            return;
        }
        if (!accepted) {
            traceCreationLifecycle(transaction, "create_command_binding_preparation_rejected",
                "command_graph_invalid");
            pauseCreationPhase(transaction, "Command Binding Preparation Failed");
            return;
        }
        traceCreationLifecycle(transaction, "create_command_binding_preparation_complete", "command_graph_prepared");
        enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
            () -> handleCreationJournalFailure(transaction));
    }

    public CreationAdmission beginResourceCreation(String serverId, ReSyncResourceType resourceType, String id,
                                                    String resourceTemplate, CreationMetadataIntent metadata,
                                                    String commandContext, Consumer<CreationResult> observer) {
        if (resourceType == null || id == null || id.isBlank()) {
            return rejectedCreationAdmission("Creation input is invalid");
        }
        if (AutomationDefinitionDraft.supports(resourceType)
            && (resourceTemplate == null || resourceTemplate.isBlank())) {
            return rejectedCreationAdmission("Complete The Automation Form Before Creating");
        }
        Supplier<Object> resourceFactory = () -> buildResourceForCreation(serverId, resourceType, id,
            metadata != null ? metadata.path() : null, resourceTemplate);
        return beginResourceCreationInternal(serverId, resourceType, id, resourceFactory, metadata,
            commandContext, observer, resourceTemplate);
    }

    public CreationAdmission beginFolderCreation(String serverId, String path, CreationMetadataIntent metadata,
                                                 Consumer<CreationResult> observer) {
        if (path == null || path.isBlank()) {
            return rejectedCreationAdmission("The folder path is invalid");
        }
        return beginResourceCreationInternal(serverId, null, path, null, metadata, null, observer, null);
    }

    private CreationAdmission beginCoreGraphCreation(CoreTemplateIntent intent, CoreGraphEditorSession session) {
        if (intent == null || intent.resource() == null || session == null || closed) {
            return rejectedCreationAdmission("Core creation input is invalid");
        }
        String serverId = intent.resource().serverId().canonicalText();
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(intent.resource().resourceType().value());
        String id = intent.resource().id();
        CreationMetadataIntent metadata = intent.metadata();
        if (resourceType == null || !resourceType.isGraph() || metadata == null
            || !resourceType.typeId().equals(metadata.type()) || !id.equals(metadata.id())
            || !sessionResourceMatches(session, serverId, resourceType, id)
            || !session.isRevisionZeroBaseline() || !creationMetadataWithinLimits(metadata)
            || !creationFieldWithinLimit(serverId) || !creationFieldWithinLimit(id)) {
            return rejectedCreationAdmission("Core creation metadata is invalid");
        }
        if (!creationJournalLoaded) {
            loadCreationJournal();
            if (!creationJournalLoaded) {
                return rejectedCreationAdmission("Creation journal is still loading");
            }
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null || !flowClient.supportsAggregateResourceCreation(resourceType)) {
            return rejectedCreationAdmission("ReSync Resource Creation Requires Protocol v1.2");
        }
        if (creationResourceExists(serverId, resourceType, id)) {
            return rejectedCreationAdmission("The resource already exists");
        }
        String payloadJson;
        ContentHash payloadHash;
        String payloadKind = corePayloadKind(resourceType, session.payload());
        try {
            payloadJson = session.canonicalPayloadJson();
            payloadHash = session.checksum();
        } catch (RuntimeException exception) {
            return rejectedCreationAdmission("Core creation payload is invalid");
        }
        if (!isCorePayloadKind(payloadKind) || payloadJson == null || payloadJson.isBlank()
            || payloadHash == null || !creationPayloadFieldWithinLimit(payloadJson)
            || !payloadHash.equals(corePayloadChecksum(payloadKind, payloadJson))) {
            return rejectedCreationAdmission("Core creation payload is invalid");
        }
        CreationKey key = new CreationKey(serverId, resourceType.typeId(), id);
        ServerResourceLocator locator = creationLocator(serverId, resourceType, id);
        if (locator == null || !locator.equals(intent.resource())) {
            return rejectedCreationAdmission("The resource locator is invalid");
        }
        CreationTransaction transaction = new CreationTransaction(key, resourceType, session.payload(), null,
            intent.title(), locator, payloadHash, UUID.randomUUID(), UUID.randomUUID(), metadata, null,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), intent.observer(), creationSequence.incrementAndGet(),
            creationResourceGeneration(serverId, resourceType, id));
        transaction.corePayload = true;
        transaction.corePayloadKind = payloadKind;
        transaction.coreSession = session;
        transaction.payloadJson = payloadJson;
        transaction.phase = CreationPhase.PAYLOAD;
        if (!admitCreationTransaction(transaction)) {
            return rejectedCreationAdmission("Creation limit reached or resource already pending");
        }
        boolean queued = enqueueCreationJournal(transaction, () -> {
            transaction.durableAdmission.complete(true);
            ServerConnectionToken token = captureServerConnectionToken(serverId);
            if (!isCurrentServerConnection(token)) {
                failCreation(transaction, "Core Create Not Started");
                return;
            }
            dispatchCreationPhase(transaction);
        }, () -> rejectCreationAdmission(transaction, "Creation Journal Unavailable"));
        if (!queued) {
            rejectCreationAdmission(transaction, "Creation Journal Unavailable");
        }
        return new CreationAdmission(queued ? CreationAdmissionStatus.QUEUED : CreationAdmissionStatus.REJECTED,
            queued ? "Creation queued" : "Creation journal is unavailable", transaction.durableAdmission);
    }

    private CreationAdmission rejectedCreationAdmission(String message) {
        return new CreationAdmission(CreationAdmissionStatus.REJECTED,
            message == null || message.isBlank() ? "Creation Unavailable" : message,
            Async.completed(false));
    }

    private CreationAdmission beginResourceCreationInternal(String serverId, ReSyncResourceType resourceType, String id,
                                                             Supplier<Object> resourceFactory, CreationMetadataIntent metadata,
                                                             String commandContext, Consumer<CreationResult> observer) {
        return beginResourceCreationInternal(serverId, resourceType, id, resourceFactory, metadata, commandContext,
            observer, null);
    }

    private CreationAdmission beginResourceCreationInternal(String serverId, ReSyncResourceType resourceType, String id,
                                                             Supplier<Object> resourceFactory, CreationMetadataIntent metadata,
                                                             String commandContext, Consumer<CreationResult> observer,
                                                             String resourceTemplate) {
        if (closed || serverId == null || serverId.isBlank() || metadata == null
            || metadata.type() == null || metadata.type().isBlank() || metadata.id() == null || metadata.id().isBlank()
            || metadata.path() == null || metadata.sortOrder() < 0 || metadata.name() == null
            || metadata.parentPath() == null || metadata.folder() && metadata.path().isBlank()
            || !creationMetadataWithinLimits(metadata) || !creationFieldWithinLimit(serverId)
            || !creationFieldWithinLimit(id) || !creationFieldWithinLimit(resourceTemplate)
            || !creationTriggerTextWithinLimit(commandContext)) {
            return rejectedCreationAdmission("Creation metadata is invalid");
        }
        if (!creationJournalLoaded) {
            loadCreationJournal();
            if (!creationJournalLoaded) {
                return rejectedCreationAdmission("Creation journal is still loading");
            }
        }
        if (resourceType != null && (!resourceType.enabled() || id == null || id.isBlank()
            || !resourceType.typeId().equals(metadata.type()) || !id.equals(metadata.id())
            || resourceFactory == null)) {
            return rejectedCreationAdmission("Creation input is invalid");
        }
        if (resourceType == null && (!metadata.folder() || !"folder".equals(metadata.type()))) {
            return rejectedCreationAdmission("Folder metadata is invalid");
        }
        if (resourceType != null && resourceType.isGraph()) {
            if (coreGraphUiProjection.authoritative(serverId, resourceType, id) || coreGraphAuthorityEnabled(serverId)) {
                return rejectedCreationAdmission("The resource is already authoritative");
            }
        }
        if (resourceType != ReSyncResourceType.COMMAND && commandContext != null && !commandContext.isBlank()) {
            return rejectedCreationAdmission("Only command resources accept a trigger binding");
        }
        if (resourceType != null) {
            ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
            if (flowClient == null || !flowClient.supportsAggregateResourceCreation(resourceType)) {
                return rejectedCreationAdmission("ReSync Resource Creation Requires Protocol v1.2");
            }
        }
        String resourceKeyType = resourceType != null ? resourceType.typeId() : metadata.type();
        String resourceKeyId = resourceType != null ? id : metadata.id();
        if (resourceType == null && getProjectFolder(serverId, metadata.path()) != null
            || resourceType != null && creationResourceExists(serverId, resourceType, resourceKeyId)) {
            return rejectedCreationAdmission("The resource already exists");
        }
        CreationKey key = new CreationKey(serverId, resourceKeyType, resourceKeyId);
        ServerResourceLocator locator = resourceType == null ? null : creationLocator(serverId, resourceType, id);
        if (resourceType != null && locator == null) {
            return rejectedCreationAdmission("The resource locator is invalid");
        }
        long admissionGeneration = resourceType != null
            ? creationResourceGeneration(serverId, resourceType, resourceKeyId) : projectMetadataStore.currentGeneration(serverId, serverId);
        CreationTransaction transaction = new CreationTransaction(key, resourceType, null, resourceFactory,
            resourceTemplate, locator, null, UUID.randomUUID(), UUID.randomUUID(), metadata,
            resourceType == ReSyncResourceType.COMMAND ? commandContext : null, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), observer, creationSequence.incrementAndGet(), admissionGeneration);
        if (!admitCreationTransaction(transaction)) {
            return rejectedCreationAdmission("Creation limit reached or resource already pending");
        }
        boolean queued = enqueueCreationJournal(transaction, () -> {
            transaction.durableAdmission.complete(true);
            if (resourceType == null) {
                dispatchCreationMetadata(transaction);
            } else if (!transaction.isPreparing()) {
                dispatchCreationPhase(transaction);
            } else if (!queueCreationPreparation(transaction)) {
                failCreation(transaction, "Creation Preparation Unavailable");
            }
        }, () -> rejectCreationAdmission(transaction, "Creation Journal Unavailable"));
        if (!queued) {
            rejectCreationAdmission(transaction, "Creation Journal Unavailable");
        }
        return new CreationAdmission(queued ? CreationAdmissionStatus.QUEUED : CreationAdmissionStatus.REJECTED,
            queued ? "Creation queued" : "Creation journal is unavailable", transaction.durableAdmission);
    }

    private boolean admitCreationTransaction(CreationTransaction transaction) {
        if (transaction == null || transaction.key == null) {
            return false;
        }
        synchronized (creationTransactionLock) {
            if (creationTransactions.size() >= MAX_PENDING_CREATION_TRANSACTIONS
                || creationTransactions.containsKey(transaction.key)) {
                return false;
            }
            List<CreationJournalEntry> entries = new ArrayList<>(creationTransactions.size() + 1);
            creationTransactions.values().stream()
                .sorted((left, right) -> Long.compare(left.sequence, right.sequence))
                .map(this::journalEntry)
                .forEach(entries::add);
            entries.add(journalEntry(transaction));
            if (!creationJournalEntriesWithinLimits(entries)) {
                return false;
            }
            long generation = creationJournalGeneration.get() == Long.MAX_VALUE
                ? Long.MAX_VALUE : creationJournalGeneration.get() + 1L;
            if (serializeCreationJournalWithinLimit(
                new CreationJournal(CREATION_JOURNAL_SCHEMA_VERSION, generation, entries)) == null) {
                return false;
            }
            creationTransactions.put(transaction.key, transaction);
            ReSyncFlowClient.traceLifecycle(transaction.key.serverId(), "create_admitted", "serverId",
                transaction.key.serverId(), "resourceKey", transaction.key.type() + ":" + transaction.key.id(),
                "requestId", transaction.payloadRequestId, "mutationId", transaction.payloadMutationId,
                "generation", transaction.admissionGeneration, "authorityEpoch", coreGraphUiProjection.authorityEpoch(
                    transaction.key.serverId()), "revision", 0L, "phase", transaction.phase);
            return true;
        }
    }

    private void rejectCreationAdmission(CreationTransaction transaction, String message) {
        if (transaction == null) {
            return;
        }
        transaction.durableAdmission.complete(false);
        boolean removed;
        synchronized (transaction) {
            if (transaction.suspended || transaction.isTerminal()) {
                return;
            }
            transaction.phase = CreationPhase.FAILED;
            transaction.journalSettled = false;
            transaction.settling = false;
        }
        synchronized (creationTransactionLock) {
            removed = creationTransactions.remove(transaction.key, transaction);
        }
        if (!removed) {
            return;
        }
        scheduleCreationJournalCleanupRetry();
        new Notification("Create", message == null || message.isBlank() ? "Creation Unavailable" : message,
            Notification.Type.ERROR);
    }

    private void resumeCreationTransactions(String serverId) {
        if (serverId == null || serverId.isBlank() || closed) {
            return;
        }
        creationTransactions.values().stream()
            .filter(transaction -> transaction.key.serverId().equals(serverId) && !transaction.isTerminal())
            .sorted((left, right) -> Long.compare(left.sequence, right.sequence))
            .forEach(transaction -> {
                if (transaction.corePayload && activeAuthoringPublication(transaction.locator).isEmpty()) {
                    return;
                }
                CreationPhase phase;
                boolean invalidState = false;
                synchronized (transaction) {
                    if (transaction.phase != CreationPhase.PAUSED
                        && !isValidCreationResumePhase(transaction, transaction.phase)) {
                        CreationPhase invalidPhase = transaction.phase;
                        transaction.phase = CreationPhase.PAUSED;
                        transaction.pausedResumePhase = recoverableCreationResumePhase(transaction,
                            invalidPhase);
                        transaction.journalSettled = false;
                        invalidState = true;
                    }
                    if (!invalidState && transaction.phase == CreationPhase.PAUSED) {
                        CreationPhase resumePhase = transaction.pausedResumePhase;
                        if (!isValidCreationResumePhase(transaction, resumePhase)) {
                            transaction.pausedResumePhase = recoverableCreationResumePhase(transaction, resumePhase);
                            transaction.journalSettled = false;
                            invalidState = true;
                        } else {
                            transaction.phase = resumePhase;
                            transaction.pausedResumePhase = null;
                            transaction.journalSettled = false;
                        }
                    }
                    if (invalidState) {
                        phase = CreationPhase.PAUSED;
                    } else {
                        phase = transaction.phase;
                    }
                }
                if (invalidState) {
                    pauseCreationPhase(transaction, "Creation Journal Phase Invalid");
                    return;
                }
                if (phase == CreationPhase.PREPARING) {
                    if (!queueCreationPreparation(transaction)) {
                        failCreation(transaction, "Creation Preparation Unavailable");
                    }
                } else if (phase == CreationPhase.PAYLOAD || phase == CreationPhase.BINDING
                    || phase == CreationPhase.SETTLEMENT || phase == CreationPhase.METADATA) {
                    dispatchCreationPhase(transaction);
                }
            });
    }

    private void dispatchCreationPhase(CreationTransaction transaction) {
        if (transaction == null || transaction.isTerminal() || transaction.suspended || closed) {
            return;
        }
        if (transaction.isPreparing() || transaction.phase == CreationPhase.PAUSED) {
            return;
        }
        synchronized (transaction) {
            if (!transaction.journalSettled) {
                if (transaction.journalWriteQueued) {
                    return;
                }
            }
        }
        if (!transaction.journalSettled) {
            enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
                () -> handleCreationJournalFailure(transaction));
            return;
        }
        if (transaction.isPayloadPhase()) {
            dispatchCreationPayload(transaction);
        } else if (transaction.isBindingPhase()) {
            dispatchCreationBinding(transaction);
        } else if (transaction.isSettlementPhase()) {
            completeCreation(transaction);
        } else if (transaction.isMetadataPhase()) {
            dispatchCreationMetadata(transaction);
        }
    }

    private void handleCreationJournalFailure(CreationTransaction transaction) {
        if (transaction == null || transaction.isTerminal() || transaction.suspended || closed) {
            return;
        }
        boolean retry;
        synchronized (transaction) {
            retry = ++transaction.journalAttempts < MAX_CREATION_ATTEMPTS;
        }
        if (transaction.resourceType == null || !transaction.payloadCommitted) {
            failCreation(transaction, "Creation Journal Unavailable");
            return;
        }
        if (retry) {
            enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
                () -> handleCreationJournalFailure(transaction));
            return;
        }
        pauseCreationPhase(transaction, "Creation Journal Unavailable");
    }
    private void dispatchCreationPayload(CreationTransaction transaction) {
        if (transaction == null || transaction.resourceType == null || !transaction.isPayloadPhase()
            || transaction.suspended || closed || !creationTransportReady(transaction.key.serverId())) {
            return;
        }
        if (transaction.corePayload) {
            if (ensureCoreCreationSession(transaction) == null) {
                failCreation(transaction, "Core Creation Could Not Be Restored");
                return;
            }
        }
        synchronized (transaction) {
            if (transaction.settling || transaction.isTerminal()) {
                return;
            }
            transaction.settling = true;
        }
        DesignerSaveNotifications.SaveTicket ticket;
        DesignerSaveNotifications.SaveTicket staleTicket = null;
        synchronized (transaction) {
            if (transaction.payloadTicket != null && !DesignerSaveNotifications.isPending(transaction.payloadTicket)) {
                staleTicket = transaction.payloadTicket;
                transaction.payloadTicket = null;
            }
            if (transaction.payloadTicket == null) {
                transaction.payloadTicket = DesignerSaveNotifications.startSilentResumableExact(
                    transaction.key.serverId(), transaction.resourceType, transaction.key.id(),
                    transaction.resourceType.displayName(), transaction.payloadRequestId,
                    transaction.payloadMutationId);
                ticket = transaction.payloadTicket;
                if (ticket != null) {
                    DesignerSaveNotifications.SaveTicket exactTicket = ticket;
                    ticket.whenFinished((saved, currentAtFinish) -> finishCreationPayload(transaction, exactTicket,
                        saved, currentAtFinish));
                }
            } else {
                ticket = transaction.payloadTicket;
            }
            if (ticket == null) {
                transaction.settling = false;
                failCreation(transaction, "Resource Save Not Started");
                return;
            }
            if (!DesignerSaveNotifications.isPending(ticket)) {
                transaction.payloadTicket = null;
                transaction.settling = false;
                return;
            }
            if (transaction.attempts >= MAX_CREATION_ATTEMPTS) {
                transaction.settling = false;
                failCreation(transaction, "Resource Save Retry Limit Reached");
                return;
            }
            transaction.attempts++;
        }
        if (staleTicket != null) {
            DesignerSaveNotifications.detachResumable(staleTicket);
        }
        try {
            saveCreationPayload(transaction, ticket);
        } catch (RuntimeException | Error exception) {
            deferCreationPayload(transaction, ticket, "Aggregate Create Not Started");
        }
    }

    private void saveCreationPayload(CreationTransaction transaction,
                                      DesignerSaveNotifications.SaveTicket ticket) {
        String serverId = transaction.key.serverId();
        ReSyncResourceType type = transaction.resourceType;
        if (transaction.corePayload) {
            CoreGraphEditorSession session = ensureCoreCreationSession(transaction);
            ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
            if (session == null || flowClient == null) {
                deferCreationPayload(transaction, ticket, "Aggregate Create Connection Unavailable");
                return;
            }
            if (!flowClient.sendCoreGraphCreate(type, session.payload(), transaction.metadata, ticket)
                && DesignerSaveNotifications.isPending(ticket)) {
                deferCreationPayload(transaction, ticket, "Aggregate Create Not Started");
            }
            return;
        }
        String payloadJson;
        ContentHash payloadHash;
        synchronized (transaction) {
            payloadJson = transaction.payloadJson;
            payloadHash = transaction.payloadHash;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null && payloadJson != null && payloadHash != null) {
            flowClient.sendPreparedResourceCreate(type, transaction.key.id(), payloadJson, payloadHash,
                transaction.metadata, ticket);
            return;
        }
        if (flowClient == null) {
            deferCreationPayload(transaction, ticket, "Aggregate Create Connection Unavailable");
        } else if (ticket != null && DesignerSaveNotifications.isPending(ticket)) {
            DesignerSaveNotifications.failExact(ticket, "Aggregate Create Payload Unavailable");
        }
    }

    private void deferCreationPayload(CreationTransaction transaction,
                                      DesignerSaveNotifications.SaveTicket ticket, String message) {
        synchronized (transaction) {
            if (transaction.payloadTicket == ticket) {
                transaction.payloadTicket = null;
            }
            transaction.settling = false;
        }
        DesignerSaveNotifications.detachResumable(ticket);
        pauseCreationPhase(transaction, message);
    }

    private void finishCreationPayload(CreationTransaction transaction,
                                        DesignerSaveNotifications.SaveTicket ticket, boolean saved,
                                        boolean currentAtFinish) {
        if (transaction == null || ticket == null || transaction.suspended || closed) {
            return;
        }
        synchronized (transaction) {
            if (transaction.reconnectResetPending || transaction.payloadTicket != ticket || !transaction.isPayloadPhase()) {
                return;
            }
            transaction.settling = false;
        }
        boolean corePayload;
        synchronized (transaction) {
            corePayload = transaction.corePayload;
        }
        if (!saved || !transactionPayloadIdentityMatches(transaction, ticket)) {
            synchronized (transaction) {
                transaction.payloadSettlement = PayloadSettlement.REJECTED;
            }
            failCreation(transaction, "Resource Create Rejected");
            return;
        }
        if (corePayload && !isCoreGraphAuthoritative(transaction.key.serverId(), transaction.resourceType,
            transaction.key.id())) {
            synchronized (transaction) {
                if (transaction.payloadTicket == ticket) {
                    transaction.payloadTicket = null;
                }
                transaction.settling = false;
            }
            DesignerSaveNotifications.detachResumable(ticket);
            retryCreationPhase(transaction, "Core Create Was Not Confirmed");
            return;
        }
        acceptCreationPayload(transaction, currentAtFinish);
        enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
            () -> handleCreationJournalFailure(transaction));
    }

    boolean settleAggregateCreation(String serverId, ReSyncResourceType type, String id,
                                    UUID requestId, UUID mutationId) {
        if (closed || serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()
            || requestId == null || mutationId == null || !creationResourceExists(serverId, type, id)) {
            ReSyncFlowClient.traceLifecycle(serverId, "create_aggregate_settlement_rejected", "serverId", serverId,
                "resourceKey", (type == null ? "unknown" : type.typeId()) + ":"
                    + (id == null || id.isBlank() ? "unknown" : id), "operation", "create", "requestId",
                requestId, "mutationId", mutationId, "generation", -1L, "authorityEpoch", 0L, "revision", 0L,
                "reason", "aggregate_identity_or_resource_missing");
            return false;
        }
        CreationTransaction transaction = creationTransactions.get(new CreationKey(serverId, type.typeId(), id));
        if (transaction == null) {
            ReSyncFlowClient.traceLifecycle(serverId, "create_aggregate_settlement_recovered", "serverId", serverId,
                "resourceKey", type.typeId() + ":" + id, "operation", "create", "requestId", requestId,
                "mutationId", mutationId, "generation", -1L, "authorityEpoch", 0L, "revision", 0L,
                "reason", "transaction_not_pending");
            return true;
        }
        DesignerSaveNotifications.SaveTicket ticket;
        synchronized (transaction) {
            boolean exactRequest = requestId.equals(transaction.payloadRequestId) || transaction.corePayload;
            if (!exactRequest || !mutationId.equals(transaction.payloadMutationId)
                || transaction.suspended || transaction.isTerminal()) {
                ReSyncFlowClient.traceLifecycle(serverId, "create_aggregate_settlement_rejected", "serverId", serverId,
                    "resourceKey", type.typeId() + ":" + id, "operation", "create", "requestId", requestId,
                    "mutationId", mutationId, "generation", transaction.admissionGeneration, "authorityEpoch",
                    coreGraphUiProjection.authorityEpoch(serverId), "revision", 0L, "phase", transaction.phase,
                    "reason", !exactRequest ? "request_identity_mismatch"
                        : !mutationId.equals(transaction.payloadMutationId) ? "mutation_identity_mismatch"
                        : transaction.suspended ? "transaction_suspended" : "transaction_terminal");
                return false;
            }
            if (transaction.payloadCommitted && transaction.payloadSettlement == PayloadSettlement.ACCEPTED) {
                ReSyncFlowClient.traceLifecycle(serverId, "create_aggregate_settlement_recovered", "serverId", serverId,
                    "resourceKey", type.typeId() + ":" + id, "operation", "create", "requestId", requestId,
                    "mutationId", mutationId, "generation", transaction.admissionGeneration, "authorityEpoch",
                    coreGraphUiProjection.authorityEpoch(serverId), "revision", 0L, "phase", transaction.phase,
                    "payloadCommitted", true, "metadataAuthoritative", creationMetadataAlreadyAuthoritative(transaction),
                    "reason", "payload_already_settled");
                return true;
            }
            if (!transaction.isPayloadPhase() || !creationMetadataAlreadyAuthoritative(transaction)) {
                ReSyncFlowClient.traceLifecycle(serverId, "create_aggregate_settlement_deferred", "serverId", serverId,
                    "resourceKey", type.typeId() + ":" + id, "operation", "create", "requestId", requestId,
                    "mutationId", mutationId, "generation", transaction.admissionGeneration, "authorityEpoch",
                    coreGraphUiProjection.authorityEpoch(serverId), "revision", 0L, "phase", transaction.phase,
                    "payloadPhase", transaction.isPayloadPhase(), "metadataAuthoritative",
                    creationMetadataAlreadyAuthoritative(transaction), "reason", "payload_or_metadata_pending");
                return false;
            }
            ticket = transaction.payloadTicket;
            acceptCreationPayload(transaction, true);
        }
        DesignerSaveNotifications.detachResumable(ticket);
        ReSyncFlowClient.traceLifecycle(serverId, "create_aggregate_journal_settled", "serverId", serverId,
            "resourceKey", type.typeId() + ":" + id, "requestId", requestId, "mutationId", mutationId,
            "generation", transaction.admissionGeneration, "authorityEpoch", coreGraphUiProjection.authorityEpoch(serverId),
            "revision", 0L, "phase", transaction.phase, "payloadCommitted", transaction.payloadCommitted,
            "payloadSettlement", transaction.payloadSettlement, "metadataAuthoritative",
            creationMetadataAlreadyAuthoritative(transaction), "ticketPresent", ticket != null,
            "currentAtFinish", true);
        enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
            () -> handleCreationJournalFailure(transaction));
        return true;
    }

    private void acceptCreationPayload(CreationTransaction transaction, boolean currentAtFinish) {
        synchronized (transaction) {
            transaction.payloadTicket = null;
            transaction.payloadCommitted = true;
            transaction.payloadSettlement = PayloadSettlement.ACCEPTED;
            if (!currentAtFinish) {
                transaction.newerLocalState = true;
            }
            transaction.attempts = 0;
            transaction.commandGraphCommitted = false;
            transaction.triggerCommitted = false;
            transaction.commandGraphJson = null;
            transaction.commandGraphHash = null;
            transaction.commandGraphLease = null;
            transaction.commandGraphLeaseGeneration = -1L;
            boolean commandBinding = transaction.commandContext != null && !transaction.commandContext.isBlank();
            transaction.phase = commandBinding && validCommandGraphBase(transaction.commandGraphBaseRevision,
                transaction.commandGraphBaseHash, transaction.commandGraphBaseGeneration)
                ? CreationPhase.BINDING : CreationPhase.SETTLEMENT;
            if (commandBinding && transaction.phase != CreationPhase.BINDING) {
                transaction.phase = CreationPhase.PAUSED;
                transaction.pausedResumePhase = CreationPhase.BINDING;
            }
            transaction.journalSettled = false;
            transaction.settling = false;
        }
        traceCreationLifecycle(transaction, "create_payload_settled",
            currentAtFinish ? "current_local_state" : "newer_local_state");
    }

    private void dispatchCreationBinding(CreationTransaction transaction) {
        if (transaction == null || !transaction.isBindingPhase() || !transaction.payloadCommitted
            || transaction.suspended || closed || !creationTransportReady(transaction.key.serverId())) {
            return;
        }
        boolean baseUnavailable;
        synchronized (transaction) {
            baseUnavailable = !validCommandGraphBase(transaction.commandGraphBaseRevision,
                transaction.commandGraphBaseHash, transaction.commandGraphBaseGeneration);
        }
        if (baseUnavailable) {
            pauseCreationPhase(transaction, "Command Binding Authoritative Base Unavailable");
            return;
        }
        boolean advanceToSettlement = false;
        synchronized (transaction) {
            if (transaction.commandGraphCommitted && transaction.triggerCommitted) {
                transaction.phase = CreationPhase.SETTLEMENT;
                transaction.journalSettled = false;
                advanceToSettlement = true;
            }
            if (!advanceToSettlement && (transaction.commandGraphPreparing || transaction.settling || transaction.isTerminal())) {
                return;
            }
            if (!advanceToSettlement && !transaction.commandGraphCommitted
                && (transaction.commandGraphJson == null || transaction.commandGraphHash == null)) {
                transaction.commandGraphPreparing = true;
                if (!queueCommandBindingPreparation(transaction)) {
                    transaction.commandGraphPreparing = false;
                    pauseCreationPhase(transaction, "Command Binding Preparation Unavailable");
                }
                return;
            }
            if (!advanceToSettlement && transaction.attempts >= MAX_CREATION_ATTEMPTS) {
                pauseCreationPhase(transaction, "Command Binding Retry Limit Reached");
                return;
            }
            if (!advanceToSettlement) {
                transaction.settling = true;
                transaction.attempts++;
            }
        }
        if (advanceToSettlement) {
            enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
                () -> handleCreationJournalFailure(transaction));
            return;
        }
        if (!transaction.commandGraphCommitted) {
            dispatchCreationCommandGraph(transaction);
            return;
        }
        dispatchCreationTrigger(transaction);
    }

    private void dispatchCreationCommandGraph(CreationTransaction transaction) {
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(transaction.key.serverId());
        ServerConnectionToken connectionToken = captureConnectedServerConnectionToken(transaction.key.serverId(), flowClient);
        long authorityEpoch = flowClient != null
            ? flowClient.resourceRevisionReconciler().authorityEpoch(transaction.key.serverId()) : 0L;
        if (!creationConnectionCurrent(transaction.key.serverId(), flowClient, connectionToken, authorityEpoch)) {
            synchronized (transaction) {
                transaction.settling = false;
            }
            retryCreationPhase(transaction, "Command Binding Connection Unavailable");
            return;
        }
        CommandGraphAdmission admission = prepareCommandGraphAdmission(transaction);
        if (!admission.available()) {
            synchronized (transaction) {
                transaction.settling = false;
            }
            pauseCreationPhase(transaction, "Command Binding Authoritative State Changed");
            return;
        }
        DesignerSaveNotifications.SaveTicket ticket;
        DesignerSaveNotifications.SaveTicket staleTicket = null;
        String payloadJson;
        ContentHash payloadHash;
        SyncedResourceCache.SaveLease<?> draftLease;
        long draftGeneration;
        long baseRevision;
        String baseHash;
        long baseGeneration;
        long attempt;
        synchronized (transaction) {
            if (transaction.commandGraphTicket != null
                && !DesignerSaveNotifications.isPending(transaction.commandGraphTicket)) {
                staleTicket = transaction.commandGraphTicket;
                transaction.commandGraphTicket = null;
            }
            transaction.commandFlowClient = flowClient;
            transaction.commandConnectionToken = connectionToken;
            transaction.commandAuthorityEpoch = authorityEpoch;
            attempt = ++transaction.commandGraphAttempt;
            ticket = transaction.commandGraphTicket;
            if (ticket == null) {
                ticket = DesignerSaveNotifications.startSilentResumableExact(transaction.key.serverId(),
                    ReSyncResourceType.COMMAND, transaction.key.id(), "Command", transaction.commandGraphRequestId,
                    transaction.commandGraphMutationId);
                transaction.commandGraphTicket = ticket;
                if (ticket != null) {
                    DesignerSaveNotifications.SaveTicket exactTicket = ticket;
                    ticket.whenFinished((saved, currentAtFinish) -> finishCreationCommandGraph(transaction,
                        exactTicket, saved, currentAtFinish, flowClient, connectionToken, authorityEpoch, attempt));
                }
            }
            payloadJson = transaction.commandGraphJson;
            payloadHash = transaction.commandGraphHash;
            transaction.commandGraphLease = admission.lease();
            transaction.commandGraphLeaseGeneration = admission.generation();
            transaction.newerLocalState = transaction.newerLocalState || admission.preserveNewerDraft();
            draftLease = transaction.commandGraphLease;
            draftGeneration = admission.generation();
            baseRevision = transaction.commandGraphBaseRevision;
            baseHash = transaction.commandGraphBaseHash;
            baseGeneration = transaction.commandGraphBaseGeneration;
        }
        if (ticket == null || payloadJson == null || payloadHash == null) {
            synchronized (transaction) {
                if (transaction.commandGraphTicket == ticket) {
                    transaction.commandGraphTicket = null;
                }
                transaction.settling = false;
            }
            pauseCreationPhase(transaction, "Command Binding Save Not Started");
            return;
        }
        if (staleTicket != null) {
            DesignerSaveNotifications.detachResumable(staleTicket);
        }
        boolean sent = false;
        try {
            sent = flowClient != null && flowClient.sendPreparedResourceSave(ReSyncResourceType.COMMAND,
                transaction.key.id(), payloadJson, payloadHash, ticket, draftLease, draftGeneration,
                baseRevision, baseHash,
                baseGeneration,
                admission.preserveNewerDraft());
        } catch (RuntimeException | Error exception) {
            sent = false;
        }
        if (!sent && DesignerSaveNotifications.isPending(ticket)) {
            DesignerSaveNotifications.failExact(ticket, "Command Binding Save Failed");
        }
    }

    private void finishCreationCommandGraph(CreationTransaction transaction,
                                            DesignerSaveNotifications.SaveTicket ticket, boolean saved,
                                            boolean currentAtFinish,
                                            ReSyncFlowClient flowClient,
                                            ServerConnectionToken connectionToken, long authorityEpoch, long attempt) {
        if (transaction == null || ticket == null || transaction.suspended || closed) {
            return;
        }
        synchronized (transaction) {
            if (transaction.reconnectResetPending || transaction.commandGraphTicket != ticket || !transaction.isBindingPhase()
                || transaction.commandGraphAttempt != attempt) {
                return;
            }
            transaction.commandGraphTicket = null;
            transaction.settling = false;
        }
        if (!saved || !creationConnectionCurrent(transaction.key.serverId(), flowClient,
            connectionToken, authorityEpoch)) {
            retryCreationPhase(transaction, "Command Binding Save Failed");
            return;
        }
        synchronized (transaction) {
            if (!transaction.isBindingPhase()) {
                return;
            }
            transaction.commandGraphCommitted = true;
            transaction.commandGraphLease = null;
            transaction.commandGraphLeaseGeneration = -1L;
            transaction.newerLocalState = transaction.newerLocalState || !currentAtFinish;
            transaction.attempts = 0;
            transaction.journalSettled = false;
        }
        traceCreationLifecycle(transaction, "create_command_graph_settled",
            currentAtFinish ? "current_local_state" : "newer_local_state");
        enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
            () -> handleCreationJournalFailure(transaction));
    }

    private void dispatchCreationTrigger(CreationTransaction transaction) {
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(transaction.key.serverId());
        ServerConnectionToken connectionToken = captureConnectedServerConnectionToken(transaction.key.serverId(), flowClient);
        int transportGeneration = flowClient != null ? flowClient.activeTransportGeneration() : -1;
        long authorityEpoch = flowClient != null
            ? flowClient.resourceRevisionReconciler().authorityEpoch(transaction.key.serverId()) : 0L;
        if (!creationConnectionCurrent(transaction.key.serverId(), flowClient, connectionToken, transportGeneration,
            authorityEpoch)) {
            synchronized (transaction) {
                transaction.settling = false;
            }
            retryCreationPhase(transaction, "Command Binding Connection Unavailable");
            return;
        }
        List<TriggerBinding> baseline;
        List<TriggerBinding> submittedBindings;
        String submittedJson;
        long expectedBindingEpoch;
        String expectedBindingHash;
        synchronized (transaction) {
            submittedJson = transaction.triggerBindingsJson;
            submittedBindings = transaction.triggerPreparedBindings;
            expectedBindingEpoch = transaction.triggerExpectedBindingEpoch;
            expectedBindingHash = transaction.triggerExpectedBindingHash;
            baseline = transaction.triggerBaselineCaptured ? copyTriggerBindings(transaction.triggerBaseline) : null;
        }
        if (expectedBindingEpoch < 1L || expectedBindingHash == null || expectedBindingHash.isBlank()) {
            expectedBindingEpoch = flowClient.triggerBindingEpoch();
            expectedBindingHash = flowClient.triggerBindingHash();
        }
        if (expectedBindingEpoch < 1L || expectedBindingHash == null || expectedBindingHash.isBlank()) {
            synchronized (transaction) {
                transaction.settling = false;
            }
            retryCreationPhase(transaction, "Command Binding State Unavailable");
            return;
        }
        if (submittedJson == null || submittedBindings == null) {
            baseline = baseline != null ? baseline : snapshotTriggerBindings(transaction.key.serverId());
            if (!queueCreationTriggerPreparation(transaction, baseline, null, null, expectedBindingEpoch,
                expectedBindingHash, true)) {
                synchronized (transaction) {
                    transaction.settling = false;
                }
                pauseCreationPhase(transaction, "Command Binding Preparation Unavailable");
            }
            return;
        }
        if (baseline == null) {
            baseline = snapshotTriggerBindings(transaction.key.serverId());
            synchronized (transaction) {
                transaction.triggerBaseline = copyTriggerBindings(baseline);
                transaction.triggerBaselineCaptured = true;
            }
        }
        long attempt;
        synchronized (transaction) {
            transaction.commandFlowClient = flowClient;
            transaction.commandConnectionToken = connectionToken;
            transaction.commandAuthorityEpoch = authorityEpoch;
            transaction.triggerBaseline = baseline;
            attempt = ++transaction.triggerAttempt;
        }
        Async<ReSyncFlowClient.TriggerUpdateOutcome> settlement;
        try {
            settlement = flowClient.sendTriggerUpdateAwait(submittedJson, transaction.triggerRequestId.toString(),
                expectedBindingEpoch, expectedBindingHash, true);
        } catch (RuntimeException | Error exception) {
            synchronized (transaction) {
                transaction.settling = false;
            }
            retryCreationPhase(transaction, "Command Binding Update Failed");
            return;
        }
        settlement.whenComplete((outcome, error) -> finishCreationTrigger(transaction, flowClient,
            connectionToken, transportGeneration, authorityEpoch, attempt,
            error == null ? outcome : ReSyncFlowClient.TriggerUpdateOutcome.rejected(error.getMessage())));
    }

    private boolean queueCreationTriggerPreparation(CreationTransaction transaction, List<TriggerBinding> baseline,
                                                    List<TriggerBinding> submittedBindings, String submittedJson,
                                                    long expectedBindingEpoch, String expectedBindingHash,
                                                    boolean persist) {
        if (transaction == null || !transaction.isBindingPhase() || transaction.suspended || closed
            || baseline == null || !creationTriggerBindingsWithinLimits(baseline)
            || expectedBindingEpoch < 1L || expectedBindingHash == null || expectedBindingHash.isBlank()) {
            return false;
        }
        long preparationGeneration;
        synchronized (transaction) {
            if (transaction.triggerPreparationQueued) {
                return true;
            }
            transaction.triggerPreparationQueued = true;
            preparationGeneration = transaction.triggerPreparationGeneration;
        }
        try {
            List<TriggerBinding> capturedBaseline = List.copyOf(baseline);
            List<TriggerBinding> capturedSubmitted = submittedBindings == null ? null : List.copyOf(submittedBindings);
            projectMetadataHydrations.execute(() -> {
                List<TriggerBinding> prepared = null;
                String canonical = null;
                String hash = null;
                try {
                    prepared = capturedSubmitted != null ? creationTriggerBindingsWithinLimits(capturedSubmitted)
                        ? capturedSubmitted : null
                        : submittedJson != null ? parseCreationTriggerBindings(submittedJson)
                        : creationTriggerBindings(transaction, capturedBaseline);
                    if (prepared == null) {
                        throw new IllegalArgumentException("Command binding data is invalid");
                    }
                    canonical = canonicalTriggerBindingsJson(prepared);
                    hash = triggerBindingsHash(canonical);
                    if (submittedJson != null && !submittedJson.equals(canonical)) {
                        throw new IllegalArgumentException("Command binding data is not canonical");
                    }
                    synchronized (transaction) {
                        if (transaction.suspended || transaction.phase != CreationPhase.BINDING
                            || transaction.reconnectResetPending
                            || transaction.triggerPreparationGeneration != preparationGeneration) {
                            transaction.triggerPreparationQueued = false;
                            transaction.settling = false;
                            return;
                        }
                        if (submittedJson != null && transaction.triggerBindingsHash != null
                            && !hash.equals(transaction.triggerBindingsHash)) {
                            transaction.triggerPreparationQueued = false;
                            transaction.settling = false;
                            pauseCreationPhase(transaction, "Command Binding State Invalid");
                            return;
                        }
                        transaction.triggerBindingsJson = canonical;
                        transaction.triggerBindingsHash = hash;
                        transaction.triggerExpectedBindingEpoch = expectedBindingEpoch;
                        transaction.triggerExpectedBindingHash = expectedBindingHash;
                        transaction.triggerBaseline = capturedBaseline;
                        transaction.triggerBaselineCaptured = true;
                        transaction.triggerPreparedBindings = List.copyOf(prepared);
                        transaction.triggerPreparationQueued = false;
                        transaction.journalSettled = !persist;
                        transaction.settling = false;
                    }
                    if (persist) {
                        enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
                            () -> handleCreationJournalFailure(transaction));
                    }
                } catch (RuntimeException | Error exception) {
                    synchronized (transaction) {
                        transaction.triggerPreparationQueued = false;
                        transaction.settling = false;
                    }
                    pauseCreationPhase(transaction, "Command Binding Preparation Failed");
                }
            });
            return true;
        } catch (IllegalStateException exception) {
            synchronized (transaction) {
                transaction.triggerPreparationQueued = false;
            }
            return false;
        }
    }

    private List<TriggerBinding> creationTriggerBindings(CreationTransaction transaction,
                                                         List<TriggerBinding> baseline) {
        if (transaction == null || transaction.commandContext == null || transaction.commandContext.isBlank()
            || baseline == null || !creationTriggerBindingsWithinLimits(baseline)
            || !creationTriggerTextWithinLimit(transaction.commandContext)) {
            return null;
        }
        List<TriggerBinding> submitted = new ArrayList<>(baseline);
        submitted.removeIf(binding -> binding != null && transaction.key.id().equals(binding.getFlowId())
            && binding.getType() == TriggerType.COMMAND);
        submitted.add(new TriggerBinding(transaction.key.id() + ":command", transaction.key.id(), TriggerType.COMMAND,
            transaction.commandContext));
        return creationTriggerBindingsWithinLimits(submitted) ? List.copyOf(submitted) : null;
    }

    private boolean creationTriggerBindingsWithinLimits(List<TriggerBinding> bindings) {
        if (bindings == null || bindings.size() > MAX_CREATION_TRIGGER_BINDINGS) {
            return false;
        }
        Set<String> ids = new HashSet<>();
        for (TriggerBinding binding : bindings) {
            if (binding == null || binding.getId() == null || binding.getId().isBlank()
                || binding.getFlowId() == null || binding.getFlowId().isBlank() || binding.getType() == null
                || !creationTriggerTextWithinLimit(binding.getId())
                || !creationTriggerTextWithinLimit(binding.getFlowId())
                || !creationTriggerTextWithinLimit(binding.getContext())
                || !ids.add(binding.getId())) {
                return false;
            }
        }
        return true;
    }

    private List<TriggerBinding> parseCreationTriggerBindings(String value) {
        try {
            if (!creationTriggerFieldWithinLimit(value)) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(canonicalTriggerBindingsJson(value));
            if (!parsed.isJsonArray()) {
                return null;
            }
            if (parsed.getAsJsonArray().size() > MAX_CREATION_TRIGGER_BINDINGS) {
                return null;
            }
            TriggerBinding[] decoded = new TriggerBinding[parsed.getAsJsonArray().size()];
            for (int index = 0; index < decoded.length; index++) {
                JsonElement entry = parsed.getAsJsonArray().get(index);
                decoded[index] = entry.isJsonNull() ? null : FlowJson.trigger(entry.getAsJsonObject());
            }
            if (decoded == null) {
                return null;
            }
            List<TriggerBinding> result = new ArrayList<>(decoded.length);
            for (TriggerBinding binding : decoded) {
                if (binding == null || binding.getId() == null || binding.getId().isBlank()
                    || binding.getFlowId() == null || binding.getFlowId().isBlank() || binding.getType() == null
                    || !creationTriggerTextWithinLimit(binding.getId())
                    || !creationTriggerTextWithinLimit(binding.getFlowId())
                    || !creationTriggerTextWithinLimit(binding.getContext())) {
                    return null;
                }
                result.add(copyTriggerBinding(binding));
            }
            return creationTriggerBindingsWithinLimits(result) ? List.copyOf(result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<TriggerBinding> snapshotTriggerBindings(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return List.of();
        }
        synchronized (triggerBindingsLock) {
            return copyTriggerBindings(triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>()));
        }
    }

    private void finishCreationTrigger(CreationTransaction transaction, ReSyncFlowClient flowClient,
                                       ServerConnectionToken connectionToken, int transportGeneration,
                                       long authorityEpoch, long attempt,
                                       ReSyncFlowClient.TriggerUpdateOutcome outcome) {
        if (transaction == null || transaction.suspended || closed) {
            return;
        }
        synchronized (transaction) {
            if (transaction.reconnectResetPending || !transaction.isBindingPhase() || transaction.triggerAttempt != attempt) {
                return;
            }
            transaction.settling = false;
        }
        if (isTriggerIdentityConflict(outcome)) {
            rotateCreationTriggerRequest(transaction);
            return;
        }
        if (outcome != null && outcome.stale() && outcome.hasAuthoritativeState()
            && creationConnectionCurrent(transaction.key.serverId(), flowClient, connectionToken, transportGeneration,
                authorityEpoch)) {
            rebaseCreationTrigger(transaction, flowClient, outcome, connectionToken, transportGeneration,
                authorityEpoch);
            return;
        }
        if (outcome == null || !outcome.successful()
            || !creationConnectionCurrent(transaction.key.serverId(), flowClient, connectionToken, transportGeneration,
                authorityEpoch)) {
            retryCreationPhase(transaction, "Command Binding Update Failed");
            return;
        }
        if (!applyAuthoritativeTriggerBindings(transaction.key.serverId(), outcome.bindings(), flowClient,
            connectionToken, transportGeneration, authorityEpoch, outcome.bindingEpoch(), outcome.bindingHash())) {
            retryCreationPhase(transaction, "Command Binding State Changed");
            return;
        }
        if (!creationTriggerBindingPresent(transaction, outcome.bindings())) {
            pauseCreationPhase(transaction, "Command Binding Was Not Applied");
            return;
        }
        synchronized (transaction) {
            if (!transaction.isBindingPhase()) {
                return;
            }
            transaction.triggerCommitted = true;
            transaction.attempts = 0;
            transaction.phase = CreationPhase.SETTLEMENT;
            transaction.journalSettled = false;
        }
        traceCreationLifecycle(transaction, "create_trigger_settled", "authoritative_binding_present");
        enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
            () -> handleCreationJournalFailure(transaction));
    }

    private boolean isTriggerIdentityConflict(ReSyncFlowClient.TriggerUpdateOutcome outcome) {
        String diagnostic = outcome == null ? null : outcome.diagnostic();
        return diagnostic != null && diagnostic.startsWith("JOB_IDENTITY_CONFLICT");
    }

    private void rotateCreationTriggerRequest(CreationTransaction transaction) {
        if (transaction == null) {
            return;
        }
        synchronized (transaction) {
            if (!transaction.isBindingPhase() || transaction.suspended || closed) {
                return;
            }
            transaction.triggerRequestId = UUID.randomUUID();
            transaction.triggerCommitted = false;
            transaction.journalSettled = false;
            transaction.settling = false;
        }
        enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
            () -> handleCreationJournalFailure(transaction));
    }

    private void rebaseCreationTrigger(CreationTransaction transaction, ReSyncFlowClient flowClient,
                                       ReSyncFlowClient.TriggerUpdateOutcome outcome,
                                       ServerConnectionToken connectionToken, int transportGeneration,
                                       long authorityEpoch) {
        List<TriggerBinding> authoritative = copyTriggerBindings(outcome.bindings());
        if (!applyAuthoritativeTriggerBindings(transaction.key.serverId(), authoritative, flowClient, connectionToken,
            transportGeneration, authorityEpoch, outcome.bindingEpoch(), outcome.bindingHash())) {
            retryCreationPhase(transaction, "Command Binding State Changed");
            return;
        }
        if (creationTriggerBindingPresent(transaction, authoritative)) {
            synchronized (transaction) {
                if (!transaction.isBindingPhase()) {
                    return;
                }
                transaction.triggerCommitted = true;
                transaction.attempts = 0;
                transaction.phase = CreationPhase.SETTLEMENT;
                transaction.journalSettled = false;
            }
            enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
                () -> handleCreationJournalFailure(transaction));
            return;
        }
        List<TriggerBinding> rebased = creationTriggerBindings(transaction, authoritative);
        if (rebased == null) {
            retryCreationPhase(transaction, "Command Binding State Unavailable");
            return;
        }
        synchronized (transaction) {
            if (!transaction.isBindingPhase()) {
                return;
            }
            transaction.triggerRequestId = UUID.randomUUID();
            transaction.triggerBindingsJson = null;
            transaction.triggerBindingsHash = null;
            transaction.triggerExpectedBindingEpoch = outcome.bindingEpoch();
            transaction.triggerExpectedBindingHash = outcome.bindingHash();
            transaction.triggerBaseline = copyTriggerBindings(authoritative);
            transaction.triggerBaselineCaptured = true;
            transaction.triggerPreparedBindings = List.of();
            transaction.triggerCommitted = false;
            transaction.journalSettled = false;
            transaction.settling = true;
        }
        if (!queueCreationTriggerPreparation(transaction, authoritative, rebased, null, outcome.bindingEpoch(),
            outcome.bindingHash(), true)) {
            synchronized (transaction) {
                transaction.settling = false;
            }
            pauseCreationPhase(transaction, "Command Binding Preparation Unavailable");
        }
    }

    private boolean creationTriggerBindingPresent(CreationTransaction transaction, List<TriggerBinding> bindings) {
        if (transaction == null || bindings == null || transaction.commandContext == null
            || transaction.commandContext.isBlank()) {
            return false;
        }
        return bindings.stream().anyMatch(binding -> binding != null
            && (transaction.key.id() + ":command").equals(binding.getId())
            && transaction.key.id().equals(binding.getFlowId())
            && binding.getType() == TriggerType.COMMAND
            && transaction.commandContext.equals(binding.getContext()));
    }

    private boolean applyCreationTriggerBindingsIfCurrent(String serverId, List<TriggerBinding> baseline,
                                                          List<TriggerBinding> submitted) {
        if (serverId == null || serverId.isBlank() || baseline == null || submitted == null) {
            return false;
        }
        synchronized (triggerBindingsLock) {
            List<TriggerBinding> target = triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>());
            if (!triggerBindingsEqual(target, baseline)) {
                return false;
            }
            target.clear();
            target.addAll(copyTriggerBindings(submitted));
        }
        invalidateProjectCatalog(serverId);
        return true;
    }

    private boolean creationConnectionCurrent(String serverId, ReSyncFlowClient flowClient,
                                               ServerConnectionToken connectionToken, long authorityEpoch) {
        return creationConnectionCurrent(serverId, flowClient, connectionToken, -1, authorityEpoch);
    }

    private boolean creationConnectionCurrent(String serverId, ReSyncFlowClient flowClient,
                                               ServerConnectionToken connectionToken, int transportGeneration,
                                               long authorityEpoch) {
        return serverId != null && !serverId.isBlank() && flowClient != null && connectionToken != null
            && connectionToken.source() == flowClient && connectionToken.generation() > 0L
            && (transportGeneration < 0 || flowClient.activeTransportGeneration() == transportGeneration)
            && authorityEpoch > 0L && connectionManager.getFlowClient(serverId) == flowClient
            && flowClient.isConnectedState() && isCurrentServerConnection(connectionToken)
            && flowClient.resourceRevisionReconciler().authorityEpoch(serverId) == authorityEpoch;
    }

    private boolean triggerBindingsEqual(List<TriggerBinding> left, List<TriggerBinding> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            TriggerBinding first = left.get(index);
            TriggerBinding second = right.get(index);
            if (first == second) {
                continue;
            }
            if (first == null || second == null || !Objects.equals(first.getId(), second.getId())
                || !Objects.equals(first.getFlowId(), second.getFlowId()) || first.getType() != second.getType()
                || !Objects.equals(first.getContext(), second.getContext())) {
                return false;
            }
        }
        return true;
    }

    private void dispatchCreationMetadata(CreationTransaction transaction) {
        if (transaction == null || transaction.resourceType != null || !transaction.isMetadataPhase()
            || transaction.suspended || closed || !creationTransportReady(transaction.key.serverId())
            || !canPersistProjectMetadata(transaction.key.serverId())) {
            return;
        }
        if (!creationMetadataAuthorityReady(transaction)) {
            return;
        }
        if (creationMetadataAlreadyAuthoritative(transaction)) {
            completeCreation(transaction);
            return;
        }
        DesignerSaveNotifications.SaveTicket startedTicket = null;
        DesignerSaveNotifications.SaveTicket staleTicket = null;
        synchronized (transaction) {
            if (transaction.settling || transaction.isTerminal()) {
                return;
            }
            if (transaction.attempts >= MAX_CREATION_ATTEMPTS) {
                pauseCreationPhase(transaction, "Metadata Save Retry Limit Reached");
                return;
            }
            transaction.settling = true;
        }
        try {
            DesignerSaveNotifications.SaveTicket ticket;
            synchronized (transaction) {
                if (transaction.metadataTicket != null && !DesignerSaveNotifications.isPending(transaction.metadataTicket)) {
                    staleTicket = transaction.metadataTicket;
                    transaction.metadataTicket = null;
                }
                if (transaction.metadataTicket == null) {
                    transaction.metadataTicket = DesignerSaveNotifications.startSilentResumableExact(
                        transaction.key.serverId(), ReSyncResourceType.PROJECT_METADATA,
                        transaction.key.serverId(), transaction.metadata.name(), transaction.metadataRequestId,
                        transaction.metadataMutationId);
                    ticket = transaction.metadataTicket;
                    if (ticket != null) {
                        DesignerSaveNotifications.SaveTicket exactTicket = ticket;
                        ticket.whenFinished((saved, currentAtFinish) -> finishCreationMetadata(transaction, exactTicket,
                            saved, currentAtFinish));
                    }
                } else {
                    ticket = transaction.metadataTicket;
                }
                startedTicket = ticket;
                if (ticket == null) {
                    transaction.settling = false;
                    failCreation(transaction, "Metadata Save Not Started");
                    return;
                }
                if (!DesignerSaveNotifications.isPending(ticket)) {
                    transaction.metadataTicket = null;
                    transaction.settling = false;
                    return;
                }
                if (transaction.attempts >= MAX_CREATION_ATTEMPTS) {
                    transaction.settling = false;
                    pauseCreationPhase(transaction, "Metadata Save Retry Limit Reached");
                    return;
                }
                transaction.attempts++;
            }
            if (staleTicket != null) {
                DesignerSaveNotifications.detachResumable(staleTicket);
            }
            ProjectMetadataEdit edit = editProjectMetadata(transaction.key.serverId());
            CreationMetadataIntent metadata = transaction.metadata;
            if (metadata.folder()) {
                ProjectFolder folder = edit.folder(metadata.path());
                if (folder == null || !metadata.name().equals(folder.name())
                    || !metadata.parentPath().equals(folder.parentPath()) || metadata.sortOrder() != folder.sortOrder()) {
                    edit.putFolder(metadata.path(), metadata.parentPath(), metadata.name(), metadata.sortOrder(), false);
                }
            } else {
                edit.putResource(metadata.type(), metadata.id(), metadata.name(), metadata.path(), metadata.sortOrder());
            }
            saveProjectMetadata(edit, false, ticket);
        } catch (RuntimeException | Error exception) {
            if (startedTicket != null && DesignerSaveNotifications.isPending(startedTicket)) {
                DesignerSaveNotifications.failExact(startedTicket, "Metadata Save Failed");
            } else {
                retryCreationPhase(transaction, "Metadata Save Failed");
            }
        }
    }

    private void finishCreationMetadata(CreationTransaction transaction,
                                         DesignerSaveNotifications.SaveTicket ticket, boolean saved,
                                         boolean currentAtFinish) {
        if (transaction == null || ticket == null || transaction.suspended || closed) {
            return;
        }
        synchronized (transaction) {
            if (transaction.reconnectResetPending || transaction.metadataTicket != ticket || !transaction.isMetadataPhase()) {
                return;
            }
            transaction.settling = false;
        }
        if (!saved) {
            synchronized (transaction) {
                if (transaction.metadataTicket == ticket) {
                    transaction.metadataTicket = null;
                }
            }
            if (transaction.payloadCommitted) {
                pauseCreationPhase(transaction, "Metadata Save Rejected");
            } else {
                failCreation(transaction, "Metadata Save Rejected");
            }
            return;
        }
        if (!currentAtFinish) {
            synchronized (transaction) {
                transaction.newerLocalState = true;
            }
        }
        if (!creationMetadataAlreadyAuthoritative(transaction)) {
            synchronized (transaction) {
                if (transaction.metadataTicket == ticket) {
                    transaction.metadataTicket = null;
                }
            }
            retryCreationPhase(transaction, "Metadata Save Was Not Confirmed");
            return;
        }
        traceCreationLifecycle(transaction, "create_metadata_settled",
            currentAtFinish ? "current_local_state" : "newer_local_state");
        completeCreation(transaction);
    }

    private boolean creationMetadataAuthorityReady(CreationTransaction transaction) {
        if (transaction.resourceType == null) {
            return true;
        }
        String serverId = transaction.key.serverId();
        long required;
        synchronized (transaction) {
            required = transaction.metadataAuthorityGeneration;
            if (required == 0L) {
                long current = projectMetadataAuthorityGenerations.getOrDefault(serverId, 0L);
                required = current == Long.MAX_VALUE ? 1L : current + 1L;
                transaction.metadataAuthorityGeneration = required;
            }
        }
        if (projectMetadataAuthorityGenerations.getOrDefault(serverId, 0L) >= required) {
            synchronized (transaction) {
                transaction.metadataAuthorityWaitLogged = false;
                transaction.metadataHydrationAttempts = 0;
                transaction.retryScheduled = false;
            }
            return true;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null || !flowClient.isConnectedState()) {
            return false;
        }
        boolean logWait;
        boolean retryLimit;
        synchronized (transaction) {
            logWait = !transaction.metadataAuthorityWaitLogged;
            transaction.metadataAuthorityWaitLogged = true;
            retryLimit = transaction.metadataHydrationAttempts >= MAX_CREATION_ATTEMPTS;
            if (!retryLimit && !transaction.retryScheduled) {
                transaction.metadataHydrationAttempts++;
                transaction.retryScheduled = true;
            } else if (!retryLimit) {
                return false;
            }
        }
        if (retryLimit) {
            pauseCreationPhase(transaction, "Project Metadata Unavailable");
            return false;
        }
        if (logWait) {
            ReSyncFlowClient.traceLifecycle(serverId, "create_metadata_wait", "serverId", serverId, "resourceKey",
                transaction.metadata.type() + ":" + transaction.metadata.id(), "operation", "create", "requestId",
                transaction.metadataRequestId, "mutationId", transaction.metadataMutationId, "generation",
                projectMetadataAuthorityGenerations.getOrDefault(serverId, 0L), "authorityEpoch",
                flowClient.resourceRevisionReconciler().authorityEpoch(serverId), "revision", 0L,
                "currentGeneration", projectMetadataAuthorityGenerations.getOrDefault(serverId, 0L),
                "requiredGeneration", required, "attempt", transaction.metadataHydrationAttempts,
                "reason", "authoritative_project_metadata_pending");
        }
        flowClient.requestProjectMetadata(serverId);
        boolean scheduled = flowClient.scheduleCreationRetry(() -> {
            synchronized (transaction) {
                transaction.retryScheduled = false;
            }
            dispatchCreationPhase(transaction);
        }, Duration.ofSeconds(CREATION_RETRY_DELAY_SECONDS));
        if (!scheduled) {
            synchronized (transaction) {
                transaction.retryScheduled = false;
            }
            pauseCreationPhase(transaction, "Project Metadata Unavailable");
        }
        return false;
    }

    private void advanceProjectMetadataAuthorityGeneration(String serverId) {
        projectMetadataAuthorityGenerations.compute(serverId, (ignored, current) ->
            current == null || current == Long.MAX_VALUE ? 1L : current + 1L);
    }

    boolean handleResumableResourceSaveFailure(String serverId, ReSyncResourceType type, String id,
                                               String requestId, boolean retryable, String message) {
        if (closed || serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()
            || requestId == null || requestId.isBlank()) {
            return false;
        }
        CreationTransaction transaction = creationTransactions.get(new CreationKey(serverId, type.typeId(), id));
        if (transaction == null) {
            transaction = creationTransactions.values().stream()
                .filter(candidate -> {
                    synchronized (candidate) {
                        return candidate.key.serverId().equals(serverId)
                            && (candidate.metadataTicket != null && requestId.equals(candidate.metadataTicket.requestId())
                            || candidate.commandGraphTicket != null && requestId.equals(candidate.commandGraphTicket.requestId()));
                    }
                })
                .findFirst().orElse(null);
        }
        if (transaction == null) {
            return false;
        }
        boolean payloadRequest;
        boolean metadataRequest;
        boolean commandGraphRequest;
        DesignerSaveNotifications.SaveTicket ticket;
        synchronized (transaction) {
            payloadRequest = transaction.payloadTicket != null && requestId.equals(transaction.payloadTicket.requestId());
            metadataRequest = transaction.metadataTicket != null && requestId.equals(transaction.metadataTicket.requestId());
            commandGraphRequest = transaction.commandGraphTicket != null
                && requestId.equals(transaction.commandGraphTicket.requestId());
            if (!payloadRequest && !metadataRequest && !commandGraphRequest
                || payloadRequest && transaction.payloadCommitted) {
                return false;
            }
            ticket = payloadRequest ? transaction.payloadTicket : metadataRequest
                ? transaction.metadataTicket : transaction.commandGraphTicket;
            transaction.settling = false;
            if (payloadRequest) {
                transaction.payloadTicket = null;
            } else if (metadataRequest) {
                transaction.metadataTicket = null;
            } else {
                transaction.commandGraphTicket = null;
            }
        }
        if (retryable) {
            DesignerSaveNotifications.detachResumable(ticket);
            retryCreationPhase(transaction, message == null || message.isBlank() ? "Resource Save Retryable" : message);
        } else if ((metadataRequest || commandGraphRequest) && transaction.payloadCommitted) {
            DesignerSaveNotifications.failExact(ticket, message);
            pauseCreationPhase(transaction, message == null || message.isBlank() ? "Metadata Save Rejected" : message);
        } else {
            DesignerSaveNotifications.failExact(ticket, message);
            failCreation(transaction, message == null || message.isBlank() ? "Resource Create Rejected" : message);
        }
        return true;
    }

    boolean rebaseCreationMetadataConflict(String serverId, String requestId, String mutationId,
                                           ReSyncProjectMetadata authoritative) {
        if (closed || serverId == null || serverId.isBlank() || requestId == null || requestId.isBlank()
            || mutationId == null || mutationId.isBlank() || authoritative == null) {
            return false;
        }
        CreationTransaction transaction = creationTransactions.values().stream()
            .filter(candidate -> {
                synchronized (candidate) {
                    return candidate.key.serverId().equals(serverId) && candidate.isMetadataPhase()
                        && candidate.metadataTicket != null && requestId.equals(candidate.metadataTicket.requestId())
                        && mutationId.equals(candidate.metadataTicket.mutationId());
                }
            })
            .findFirst().orElse(null);
        if (transaction == null) {
            return false;
        }
        DesignerSaveNotifications.SaveTicket previousTicket;
        synchronized (transaction) {
            if (transaction.suspended || transaction.isTerminal() || !transaction.isMetadataPhase()
                || transaction.metadataTicket == null || !requestId.equals(transaction.metadataTicket.requestId())
                || !mutationId.equals(transaction.metadataTicket.mutationId())) {
                return false;
            }
            previousTicket = transaction.metadataTicket;
            transaction.settling = true;
        }
        boolean terminal;
        boolean retryableState;
        synchronized (transaction) {
            terminal = transaction.isTerminal();
            retryableState = !transaction.suspended && transaction.isMetadataPhase();
            if (terminal || !retryableState) {
                if (terminal) {
                    transaction.metadataTicket = null;
                }
            } else {
                transaction.metadataTicket = null;
                transaction.metadataRequestId = UUID.randomUUID();
                transaction.metadataMutationId = UUID.randomUUID();
                transaction.attempts = 0;
                transaction.journalSettled = false;
                transaction.settling = false;
            }
        }
        if (terminal) {
            DesignerSaveNotifications.detachResumable(previousTicket);
            return true;
        }
        if (!retryableState) {
            return false;
        }
        DesignerSaveNotifications.detachResumable(previousTicket);
        boolean queued = enqueueCreationJournal(transaction, () -> dispatchCreationPhase(transaction),
            () -> handleCreationJournalFailure(transaction));
        if (!queued) {
            pauseCreationPhase(transaction, "Metadata Conflict Could Not Be Journaled");
        }
        return queued;
    }

    private void retryCreationPhase(CreationTransaction transaction, String message) {
        if (transaction == null || transaction.isTerminal() || transaction.suspended || closed) {
            return;
        }
        synchronized (transaction) {
            if (transaction.isTerminal()) {
                return;
            }
            if (transaction.attempts >= MAX_CREATION_ATTEMPTS) {
                if (transaction.payloadCommitted) {
                    pauseCreationPhase(transaction, message);
                } else {
                    failCreation(transaction, message);
                }
                return;
            }
            if (transaction.retryScheduled) {
                return;
            }
            transaction.retryScheduled = true;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(transaction.key.serverId());
        boolean scheduled = flowClient != null && flowClient.scheduleCreationRetry(() -> {
                synchronized (transaction) {
                    transaction.retryScheduled = false;
                }
                dispatchCreationPhase(transaction);
            }, Duration.ofSeconds(CREATION_RETRY_DELAY_SECONDS));
        if (!scheduled) {
            synchronized (transaction) {
                transaction.retryScheduled = false;
            }
            if (transaction.payloadCommitted) {
                pauseCreationPhase(transaction, message);
            } else {
                failCreation(transaction, message);
            }
        }
    }

    private void pauseCreationPhase(CreationTransaction transaction, String message) {
        if (transaction == null || transaction.isTerminal() || transaction.suspended || closed) {
            return;
        }
        boolean notify;
        synchronized (transaction) {
            if (transaction.isTerminal() || transaction.suspended || closed) {
                return;
            }
            notify = transaction.phase != CreationPhase.PAUSED;
            if (transaction.phase != CreationPhase.PAUSED) {
                transaction.pausedResumePhase = transaction.phase;
            }
            transaction.phase = CreationPhase.PAUSED;
            transaction.retryScheduled = false;
            transaction.settling = false;
            transaction.journalSettled = false;
        }
        if (!notify) {
            return;
        }
        traceCreationLifecycle(transaction, "create_paused", message);
        String notificationMessage = message == null || message.isBlank() ? "Creation Pending" : message;
        boolean queued = enqueueCreationJournal(transaction,
            () -> ScreenManager.getInstance().execute(
                () -> new Notification("Create Pending", notificationMessage, Notification.Type.WARN)),
            () -> handleCreationJournalFailure(transaction));
        if (!queued) {
            ScreenManager.getInstance().execute(
                () -> new Notification("Create Pending", notificationMessage, Notification.Type.WARN));
        }
    }

    private void completeCreation(CreationTransaction transaction) {
        Consumer<CreationResult> observer;
        boolean discardCoreDraft;
        List<DesignerSaveNotifications.SaveTicket> tickets;
        synchronized (transaction) {
            if (transaction.isTerminal() || transaction.suspended || closed
                || transaction.resourceType != null && !transaction.payloadCommitted) {
                return;
            }
            transaction.phase = CreationPhase.COMPLETE;
            tickets = new ArrayList<>(3);
            if (transaction.payloadTicket != null) {
                tickets.add(transaction.payloadTicket);
            }
            if (transaction.metadataTicket != null) {
                tickets.add(transaction.metadataTicket);
            }
            if (transaction.commandGraphTicket != null) {
                tickets.add(transaction.commandGraphTicket);
            }
            transaction.payloadTicket = null;
            transaction.metadataTicket = null;
            transaction.commandGraphTicket = null;
            transaction.settling = false;
            observer = transaction.observer;
            discardCoreDraft = transaction.corePayload && !transaction.newerLocalState;
        }
        tickets.forEach(DesignerSaveNotifications::detachResumable);
        if (discardCoreDraft && transaction.locator != null) {
            coreGraphDocumentAuthoring.discard(transaction.locator);
        }
        boolean removed;
        synchronized (creationTransactionLock) {
            removed = creationTransactions.remove(transaction.key, transaction);
        }
        if (removed) {
            enqueueCreationJournalCleanup();
            if (transaction.resourceType != null && transaction.resourceType.isGraph()) {
                recordTypedMembershipPresence(transaction.key.serverId(), transaction.resourceType,
                    transaction.key.id());
            }
            if (transaction.corePayload && transaction.resourceType != null && transaction.resourceType.isGraph()) {
                refreshFlowWorkspace(transaction.key.serverId(), transaction.key.id(), true);
                bindCompletedCoreCreationSession(transaction);
            }
            refreshStudioWorkspace(transaction.key.serverId(), true);
            ScreenManager.getInstance().execute(() -> new Notification(transaction.resourceType != null
                ? transaction.resourceType.displayName() + " Created" : "Folder Created",
                "ID: " + transaction.metadata.id(), Notification.Type.SUCCESS));
            traceCreationVisibility(transaction, "create_projection_observed", "creation_settled");
            ReSyncFlowClient.traceLifecycle(transaction.key.serverId(), "create_settlement_complete", "serverId",
                transaction.key.serverId(), "resourceKey", transaction.key.type() + ":" + transaction.key.id(),
                "requestId", transaction.payloadRequestId, "mutationId", transaction.payloadMutationId, "generation",
                transaction.admissionGeneration, "authorityEpoch", coreGraphUiProjection.authorityEpoch(
                    transaction.key.serverId()), "revision", transaction.commandGraphBaseRevision, "elapsedMs",
                creationDebugElapsed(transaction));
            if (observer != null) {
                CreationResult result = new CreationResult(transaction.metadata.type(), transaction.metadata.id(),
                    authoritativeCreationResource(transaction));
                ScreenManager.getInstance().execute(() -> observer.accept(result));
            }
        }
    }

    private void bindCompletedCoreCreationSession(CreationTransaction transaction) {
        CoreGraphEditorSession session = coreGraphEditorSession(transaction.key.serverId(), transaction.resourceType,
            transaction.key.id()).orElse(null);
        if (session != null && transaction.locator != null) {
            bindCoreGraphSession(transaction.locator, session, transaction.metadata.name());
        }
    }

    private Object authoritativeCreationResource(CreationTransaction transaction) {
        if (transaction == null || transaction.resourceType == null) {
            return null;
        }
        String serverId = transaction.key.serverId();
        String id = transaction.key.id();
        ReSyncResourceType type = transaction.resourceType;
        if (type.isGraph()) {
            Optional<CoreGraphEditorSession> session = coreGraphEditorSession(serverId, type, id);
            if (session.isPresent()) {
                return session.orElseThrow().payload();
            }
            return getGraph(serverId, type, id);
        }
        return switch (type) {
            case GUI -> guiStore.getFromCache(serverId, id);
            case SCOREBOARD -> scoreboardStore.getFromCache(serverId, id);
            case TAB -> tabStore.getFromCache(serverId, id);
            case CUSTOM_CONTENT -> customContentStore.getFromCache(serverId, id);
            case PROJECT_METADATA -> projectMetadataStore.getFromCache(serverId, serverId);
            default -> {
                SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
                yield store != null ? store.getFromCache(serverId, id) : null;
            }
        };
    }

    private void failCreation(CreationTransaction transaction, String message) {
        if (transaction == null) {
            return;
        }
        transaction.durableAdmission.complete(false);
        DesignerSaveNotifications.SaveTicket payloadTicket = null;
        DesignerSaveNotifications.SaveTicket metadataTicket = null;
        DesignerSaveNotifications.SaveTicket commandGraphTicket = null;
        boolean retain = false;
        synchronized (transaction) {
            if (transaction.isTerminal() || transaction.suspended) {
                return;
            }
            if (transaction.payloadCommitted) {
                retain = true;
            } else {
                transaction.payloadSettlement = PayloadSettlement.REJECTED;
                transaction.phase = CreationPhase.FAILED;
                payloadTicket = transaction.payloadTicket;
                metadataTicket = transaction.metadataTicket;
                commandGraphTicket = transaction.commandGraphTicket;
                transaction.payloadTicket = null;
                transaction.metadataTicket = null;
                transaction.commandGraphTicket = null;
                transaction.settling = false;
                transaction.journalSettled = false;
            }
        }
        if (retain) {
            pauseCreationPhase(transaction, message);
            return;
        }
        ReSyncFlowClient.traceLifecycle(transaction.key.serverId(), "create_settlement_failed", "serverId",
            transaction.key.serverId(), "resourceKey", transaction.key.type() + ":" + transaction.key.id(),
            "requestId", transaction.payloadRequestId, "mutationId", transaction.payloadMutationId, "generation",
            transaction.admissionGeneration, "authorityEpoch", coreGraphUiProjection.authorityEpoch(
                transaction.key.serverId()), "revision", transaction.commandGraphBaseRevision, "elapsedMs",
            creationDebugElapsed(transaction), "reason", message);
        if (payloadTicket != null) {
            DesignerSaveNotifications.failExact(payloadTicket, message);
        }
        if (metadataTicket != null) {
            DesignerSaveNotifications.failExact(metadataTicket, message);
        }
        if (commandGraphTicket != null) {
            DesignerSaveNotifications.failExact(commandGraphTicket, message);
        }
        if (transaction.corePayload && transaction.locator != null) {
            coreGraphDocumentAuthoring.discard(transaction.locator);
        }
        String notificationMessage = message == null || message.isBlank() ? "Creation Failed" : message;
        ScreenManager.getInstance().execute(
            () -> new Notification("Create", notificationMessage, Notification.Type.ERROR));
        boolean queued = enqueueCreationJournal(transaction, () -> finalizeCreationFailure(transaction),
            () -> retryTerminalCreationJournal(transaction));
        if (!queued) {
            retryTerminalCreationJournal(transaction);
        }
    }

    private void finalizeCreationFailure(CreationTransaction transaction) {
        if (transaction == null || transaction.suspended || !transaction.isTerminal()) {
            return;
        }
        boolean removed;
        synchronized (creationTransactionLock) {
            removed = creationTransactions.remove(transaction.key, transaction);
        }
        if (removed) {
            enqueueCreationJournalCleanup();
        }
    }

    private long creationDebugElapsed(CreationTransaction transaction) {
        return transaction == null || transaction.debugStartedAtNanos == 0L ? -1L
            : ((System.nanoTime() - transaction.debugStartedAtNanos) / 1_000_000L);
    }

    private void traceCreationLifecycle(CreationTransaction transaction, String stage, String reason) {
        if (transaction == null || transaction.key == null) {
            return;
        }
        long now = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        CreationPhase previousPhase;
        CreationPhase phase;
        long phaseElapsed;
        boolean payloadCommitted;
        boolean commandGraphCommitted;
        boolean triggerCommitted;
        boolean journalSettled;
        int attempts;
        synchronized (transaction) {
            phase = transaction.phase;
            previousPhase = transaction.debugObservedPhase;
            phaseElapsed = now == 0L || transaction.debugPhaseStartedAtNanos == 0L ? -1L
                : ((Math.max(0L, now - transaction.debugPhaseStartedAtNanos)) / 1_000_000L);
            if (previousPhase != phase) {
                transaction.debugObservedPhase = phase;
                transaction.debugPhaseStartedAtNanos = now;
            }
            payloadCommitted = transaction.payloadCommitted;
            commandGraphCommitted = transaction.commandGraphCommitted;
            triggerCommitted = transaction.triggerCommitted;
            journalSettled = transaction.journalSettled;
            attempts = transaction.attempts;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(transaction.key.serverId());
        ReSyncFlowClient.traceLifecycle(transaction.key.serverId(), stage, "serverId", transaction.key.serverId(),
            "resourceKey", transaction.key.type() + ":" + transaction.key.id(), "operation", "create",
            "requestId", transaction.payloadRequestId, "mutationId", transaction.payloadMutationId,
            "metadataRequestId", transaction.metadataRequestId, "metadataMutationId", transaction.metadataMutationId,
            "commandGraphRequestId", transaction.commandGraphRequestId, "commandGraphMutationId",
            transaction.commandGraphMutationId, "triggerRequestId", transaction.triggerRequestId, "generation",
            flowClient != null ? flowClient.activeTransportGeneration() : -1L, "admissionGeneration",
            transaction.admissionGeneration, "authorityEpoch", flowClient != null
                ? flowClient.resourceRevisionReconciler().authorityEpoch(transaction.key.serverId()) : 0L,
            "revision", transaction.commandGraphBaseRevision, "previousPhase", previousPhase, "phase", phase,
            "phaseElapsedMs", phaseElapsed, "elapsedMs", creationDebugElapsed(transaction), "attempt", attempts,
            "journalSettled", journalSettled, "payloadCommitted", payloadCommitted, "commandGraphCommitted",
            commandGraphCommitted, "triggerCommitted", triggerCommitted, "reason", reason);
    }

    private void traceCreationVisibility(CreationTransaction transaction, String stage, String reason) {
        if (transaction == null || transaction.resourceType == null) {
            return;
        }
        String serverId = transaction.key.serverId();
        ReSyncResourceType type = transaction.resourceType;
        String id = transaction.key.id();
        TypedResourceMembershipSnapshot membership = snapshotTypedResourceMembership(serverId);
        boolean membershipPresent = membership.contains(type.typeId(), id);
        boolean listComplete = membership.completeTypes().contains(type.typeId());
        boolean metadataPresent = getAuthoritativeProjectResource(serverId, type.typeId(), id) != null;
        boolean corePresent = type.isGraph() && coreGraphUiProjection.authoritative(serverId, type, id);
        boolean browserIncluded = deriveProjectResources(currentProjectMetadataSnapshot(serverId), membership).stream()
            .anyMatch(resource -> type.typeId().equals(resource.getType()) && id.equals(resource.getId()));
        ReSyncFlowClient.traceLifecycle(serverId, stage, "serverId", serverId, "resourceKey",
            type.typeId() + ":" + id, "operation", "create", "requestId", transaction.payloadRequestId,
            "mutationId", transaction.payloadMutationId, "generation", membership.connectionGeneration(),
            "authorityEpoch", coreGraphUiProjection.authorityEpoch(serverId), "revision",
            transaction.commandGraphBaseRevision, "metadataPresent", metadataPresent, "corePresent", corePresent,
            "listComplete", listComplete, "typedMembershipPresent", membershipPresent, "browserIncluded",
            browserIncluded, "durableResourceSettled", transaction.payloadCommitted, "browserSettled", false,
            "editorSettled", false, "elapsedMs", creationDebugElapsed(transaction), "reason", reason);
    }

    private void retryTerminalCreationJournal(CreationTransaction transaction) {
        if (transaction == null || transaction.suspended || closed
            || creationTransactions.get(transaction.key) != transaction) {
            return;
        }
        synchronized (transaction) {
            if (transaction.terminalRetryScheduled) {
                return;
            }
            transaction.terminalRetryScheduled = true;
        }
        try {
            creationJournalScheduler.schedule(() -> {
                synchronized (transaction) {
                    transaction.terminalRetryScheduled = false;
                }
                if (creationTransactions.get(transaction.key) != transaction) {
                    return;
                }
                enqueueCreationJournal(transaction, () -> finalizeCreationFailure(transaction),
                    () -> retryTerminalCreationJournal(transaction));
            }, Duration.ofSeconds(CREATION_JOURNAL_RETRY_DELAY_SECONDS));
        } catch (RuntimeException ignored) {
            synchronized (transaction) {
                transaction.terminalRetryScheduled = false;
            }
        }
    }

    private void enqueueCreationJournalCleanup() {
        if (!creationJournalCleanupQueued.compareAndSet(0, 1)) {
            return;
        }
        Runnable cleanup = () -> {
            boolean saved;
            try {
                saved = persistCreationJournalNow();
            } catch (RuntimeException | Error exception) {
                saved = false;
            }
            creationJournalCleanupQueued.set(0);
            if (saved) {
                creationJournalCleanupAttempts.set(0);
            } else {
                scheduleCreationJournalCleanupRetry();
            }
        };
        try {
            creationJournalScheduler.execute(cleanup);
        } catch (IllegalStateException exception) {
            creationJournalCleanupQueued.set(0);
            scheduleCreationJournalCleanupRetry();
        }
    }

    private void scheduleCreationJournalCleanupRetry() {
        if (creationJournalCleanupRetryScheduled.compareAndSet(0, 1)) {
            int attempt = creationJournalCleanupAttempts.incrementAndGet();
            if (attempt > MAX_CREATION_JOURNAL_LOAD_ATTEMPTS) {
                creationJournalCleanupAttempts.set(1);
            }
            try {
                creationJournalScheduler.schedule(() -> {
                    creationJournalCleanupRetryScheduled.set(0);
                    enqueueCreationJournalCleanup();
                }, java.time.Duration.ofSeconds(CREATION_JOURNAL_RETRY_DELAY_SECONDS));
            } catch (RuntimeException exception) {
                creationJournalCleanupRetryScheduled.set(0);
            }
        }
    }

    private boolean creationTransportReady(String serverId) {
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        return flowClient != null && flowClient.isConnectedState();
    }

    private boolean transactionPayloadIdentityMatches(CreationTransaction transaction,
                                                      DesignerSaveNotifications.SaveTicket ticket) {
        if (transaction.corePayload) {
            return ticket != null && transaction.resourceType == ticket.type()
                && transaction.key.id().equals(ticket.id());
        }
        return transaction.payloadRequestId.toString().equals(ticket.requestId())
            && transaction.payloadMutationId.toString().equals(ticket.mutationId());
    }

    private String corePayloadKind(ReSyncResourceType type, Object payload) {
        if (type != ReSyncResourceType.FUNCTION && payload instanceof GraphDocument) {
            return "graph";
        }
        return type == ReSyncResourceType.FUNCTION && payload instanceof FunctionSourceDocument ? "function" : null;
    }

    private boolean isCorePayloadKind(String value) {
        return "graph".equals(value) || "function".equals(value);
    }

    private Object decodeCorePayload(String kind, String payloadJson) {
        if (!isCorePayloadKind(kind) || payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        return "function".equals(kind)
            ? FunctionSourceDocumentCodec.INSTANCE.decode(CanonicalCodec.decodePermissive(payloadJson))
            : GraphDocumentCodec.INSTANCE.decode(CanonicalCodec.decodePermissive(payloadJson));
    }

    private ContentHash corePayloadChecksum(String kind, String payloadJson) {
        try {
            Object payload = decodeCorePayload(kind, payloadJson);
            return payload instanceof GraphDocument graph ? graph.checksum()
                : payload instanceof FunctionSourceDocument source ? source.checksum() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private CoreGraphEditorSession coreCreationSession(CreationTransaction transaction) {
        if (transaction == null || !transaction.corePayload || transaction.resourceType == null
            || !transaction.resourceType.isGraph()) {
            return null;
        }
        synchronized (transaction) {
            if (transaction.coreSession != null) {
                return transaction.coreSession;
            }
        }
        Object payload;
        try {
            payload = decodeCorePayload(transaction.corePayloadKind, transaction.payloadJson);
        } catch (RuntimeException exception) {
            return null;
        }
        if (payload == null || transaction.locator == null || !transaction.locator.equals(locatorOf(payload))) {
            return null;
        }
        CatalogAuthoringPublication publication = activeAuthoringPublication(transaction.locator).orElse(null);
        if (publication == null) {
            return null;
        }
        try {
            ContentHash publicationChecksum = CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
            CoreGraphEditorSession session = payload instanceof GraphDocument graph
                ? new CoreGraphEditorSession(graph, graph.catalogBinding(), publicationChecksum,
                    catalogCapabilities(publication), publication.advertisedEditCapabilities())
                : payload instanceof FunctionSourceDocument source
                ? new CoreGraphEditorSession(source, source.graph().catalogBinding(), publicationChecksum,
                    catalogCapabilities(publication), publication.advertisedEditCapabilities()) : null;
            if (session == null || !session.isRevisionZeroBaseline()) {
                return null;
            }
            synchronized (transaction) {
                if (transaction.coreSession == null) {
                    transaction.coreSession = session;
                }
                if (transaction.resource == null) {
                    transaction.resource = session.payload();
                }
                return transaction.coreSession;
            }
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private CoreGraphEditorSession ensureCoreCreationSession(CreationTransaction transaction) {
        CoreGraphEditorSession session = coreCreationSession(transaction);
        if (session == null) {
            return null;
        }
        if (isCurrentCoreGraphEditorSession(transaction.key.serverId(), transaction.resourceType,
            transaction.key.id(), session)) {
            return session;
        }
        if (registerCoreGraphSession(transaction.locator, session, "create") == null) {
            return null;
        }
        return isCurrentCoreGraphEditorSession(transaction.key.serverId(), transaction.resourceType,
            transaction.key.id(), session) ? session : null;
    }

    private ContentHash creationPayloadHash(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank() || !creationJsonFieldWithinLimit(payloadJson)) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(payloadJson);
            if (!element.isJsonObject()) {
                return null;
            }
            Map<String, Object> payload = gson.fromJson(element, Map.class);
            return ResourcePayloadCodecs.json().canonicalize(payload).checksum();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String canonicalTriggerBindingsJson(List<TriggerBinding> bindings) {
        List<TriggerBinding> source = bindings != null ? bindings : List.of();
        if (!creationTriggerBindingsWithinLimits(source)
            || !estimatedCreationTriggerBytesWithinLimit(source)) {
            throw new IllegalArgumentException("Trigger bindings exceed the protocol limit");
        }
        JsonArray encoded = new JsonArray();
        source.forEach(binding -> encoded.add(FlowJson.trigger(binding)));
        String canonical = CanonicalJson.canonicalizeJson(FlowJson.write(encoded).getBytes(StandardCharsets.UTF_8));
        if (!creationTriggerFieldWithinLimit(canonical)) {
            throw new IllegalArgumentException("Trigger bindings exceed the protocol limit");
        }
        return canonical;
    }

    private String canonicalTriggerBindingsJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Trigger bindings are required");
        }
        if (!creationTriggerFieldWithinLimit(value)) {
            throw new IllegalArgumentException("Trigger bindings exceed the protocol limit");
        }
        String canonical = CanonicalJson.canonicalizeJson(value.getBytes(StandardCharsets.UTF_8));
        JsonElement parsed = JsonParser.parseString(canonical);
        if (!parsed.isJsonArray() || parsed.getAsJsonArray().size() > MAX_CREATION_TRIGGER_BINDINGS
            || !canonical.equals(value)) {
            throw new IllegalArgumentException("Trigger bindings must be canonical JSON array data");
        }
        return canonical;
    }

    private boolean estimatedCreationTriggerBytesWithinLimit(List<TriggerBinding> bindings) {
        if (bindings == null || bindings.size() > MAX_CREATION_TRIGGER_BINDINGS) {
            return false;
        }
        long estimate = 128L;
        for (TriggerBinding binding : bindings) {
            estimate = boundedCreationJournalEstimate(estimate, binding.getId());
            estimate = boundedCreationJournalEstimate(estimate, binding.getFlowId());
            estimate = boundedCreationJournalEstimate(estimate, binding.getType().name());
            estimate = boundedCreationJournalEstimate(estimate, binding.getContext());
            if (estimate > MAX_CREATION_TRIGGER_BYTES) {
                return false;
            }
        }
        return estimate <= MAX_CREATION_TRIGGER_BYTES;
    }

    private String triggerBindingsHash(String canonicalBindings) {
        String exact = canonicalTriggerBindingsJson(canonicalBindings);
        JsonArray array = JsonParser.parseString(exact).getAsJsonArray();
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject binding = element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", stringField(binding, "id"));
            value.put("flowId", stringField(binding, "flowId"));
            value.put("type", stringField(binding, "type"));
            value.put("context", stringField(binding, "context"));
            values.add(value);
        }
        values.sort((left, right) -> Objects.toString(left.get("id"), "")
            .compareTo(Objects.toString(right.get("id"), "")));
        return CanonicalJson.sha256(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_HASH_DOMAIN, values);
    }

    private String stringField(JsonObject object, String name) {
        if (object == null || name == null) {
            return null;
        }
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private long creationResourceGeneration(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()) {
            return 0L;
        }
        if (type.isGraph()) {
            return flowStore.currentGeneration(serverId, type, id);
        }
        return switch (type) {
            case GUI -> guiStore.currentGeneration(serverId, id);
            case SCOREBOARD -> scoreboardStore.currentGeneration(serverId, id);
            case TAB -> tabStore.currentGeneration(serverId, id);
            case CUSTOM_CONTENT -> customContentStore.currentGeneration(serverId, id);
            case PROJECT_METADATA -> projectMetadataStore.currentGeneration(serverId, serverId);
            default -> {
                SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
                yield store != null ? store.currentGeneration(serverId, id) : 0L;
            }
        };
    }

    long resourceSaveGeneration(String serverId, ReSyncResourceType type, String id) {
        return creationResourceGeneration(serverId, type, id);
    }

    boolean matchesResourceSaveDraft(String serverId, ReSyncResourceType type, String id,
                                     SyncedResourceCache.SaveLease<?> expectedLease, long expectedGeneration) {
        return matchesResourceSaveDraft(serverId, type, id, expectedLease, expectedGeneration, null);
    }

    boolean matchesResourceSaveDraft(String serverId, ReSyncResourceType type, String id,
                                     SyncedResourceCache.SaveLease<?> expectedLease, long expectedGeneration,
                                     ContentHash expectedPayloadHash) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()
            || expectedGeneration < 0L) {
            return false;
        }
        Predicate<FlowGraph> graphPayload = type.isGraph() && expectedPayloadHash != null
            ? value -> payloadHashMatches(type, value, expectedPayloadHash) : null;
        if (type.isGraph()) {
            return flowStore.matchesDraftFence(serverId, type, id, expectedLease, expectedGeneration, graphPayload);
        }
        Predicate<Object> resourcePayload = expectedPayloadHash == null ? null
            : value -> payloadHashMatches(type, value, expectedPayloadHash);
        return switch (type) {
            case GUI -> guiStore.matchesDraftFence(serverId, id, expectedLease, expectedGeneration, resourcePayload);
            case SCOREBOARD -> scoreboardStore.matchesDraftFence(serverId, id, expectedLease, expectedGeneration,
                resourcePayload);
            case TAB -> tabStore.matchesDraftFence(serverId, id, expectedLease, expectedGeneration, resourcePayload);
            case CUSTOM_CONTENT -> customContentStore.matchesDraftFence(serverId, id, expectedLease,
                expectedGeneration, resourcePayload);
            case PROJECT_METADATA -> projectMetadataStore.matchesDraftFence(serverId, serverId, expectedLease,
                expectedGeneration, resourcePayload);
            default -> {
                SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
                yield store != null && store.matchesDraftFence(serverId, id, expectedLease, expectedGeneration,
                    resourcePayload);
            }
        };
    }

    private boolean creationResourceExists(String serverId, ReSyncResourceType type, String id) {
        if (type == null || id == null || id.isBlank()) {
            return false;
        }
        if (type.isGraph() && coreGraphUiProjection.authoritative(serverId, type, id)) {
            return coreGraphUiProjection.contains(serverId, type, id);
        }
        if (authoritativeTypedMembershipContains(serverId, type, id)) {
            return true;
        }
        if (type.isGraph()) {
            return flowStore.containsServerId(serverId, type, id);
        }
        return switch (type) {
            case GUI -> guiStore.getFromCache(serverId, id) != null;
            case SCOREBOARD -> scoreboardStore.getFromCache(serverId, id) != null;
            case TAB -> tabStore.getFromCache(serverId, id) != null;
            case CUSTOM_CONTENT -> customContentStore.getFromCache(serverId, id) != null;
            case PROJECT_METADATA -> projectMetadataStore.getFromCache(serverId, serverId) != null;
            default -> {
                SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
                yield store != null && store.getFromCache(serverId, id) != null;
            }
        };
    }

    public boolean hasAuthoritativeResource(String serverId, ReSyncResourceType type, String id) {
        return creationResourceExists(serverId, type, id);
    }

    public boolean isAvailableScheduleTarget(String serverId, ServerResourceLocator target) {
        if (!scheduleTargetIdentityMatches(serverId, target)) {
            return false;
        }
        ReSyncResourceType type = ReSyncResourceType.byTypeId(target.resourceType().value());
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        boolean[] available = {false};
        return runIfCurrentServerConnection(token, () -> {
            TypedMembershipState state = typedMemberships.get(new TypedMembershipKey(serverId, type));
            available[0] = state != null && sameWorkspaceRefreshGeneration(state.token(), token)
                && state.complete() && !state.tombstones().contains(target.id())
                && Collections.binarySearch(state.ids(), target.id()) >= 0;
        }) && available[0];
    }

    static boolean scheduleTargetIdentityMatches(String serverId, ServerResourceLocator target) {
        if (serverId == null || target == null || !serverId.equals(target.serverId().canonicalText())
            || !"restudio.resync".equals(target.owner().value())) {
            return false;
        }
        ReSyncResourceType type = ReSyncResourceType.byTypeId(target.resourceType().value());
        return type == ReSyncResourceType.FLOW || type == ReSyncResourceType.FUNCTION
            || type == ReSyncResourceType.COMMAND;
    }

    private boolean creationMetadataAlreadyAuthoritative(CreationTransaction transaction) {
        ProjectMetadataSnapshot snapshot = projectMetadataStore.getFromCache(transaction.key.serverId(), transaction.key.serverId());
        if (snapshot == null) {
            return false;
        }
        CreationMetadataIntent metadata = transaction.metadata;
        if (metadata.folder()) {
            ProjectMetadataSnapshot.Folder folder = snapshot.folder(metadata.path());
            return folder != null && metadata.name().equals(folder.name())
                && metadata.parentPath().equals(folder.parentPath()) && metadata.sortOrder() == folder.sortOrder();
        }
        ProjectMetadataSnapshot.Resource resource = snapshot.resource(metadata.type(), metadata.id());
        return resource != null && metadata.name().equals(resource.displayName())
            && metadata.path().equals(resource.path()) && metadata.sortOrder() == resource.sortOrder();
    }

    private record FlowWorkspaceRefreshSnapshot(boolean rebuildContentBrowser, boolean refreshAllFlowBindings, Set<String> flowIds) {}

    private static final class PendingFlowWorkspaceRefresh {
        private final Set<String> flowIds = new HashSet<>();
        private boolean rebuildContentBrowser;
        private boolean refreshAllFlowBindings;

        private void add(String changedFlowId, boolean rebuildContentBrowser) {
            this.rebuildContentBrowser = this.rebuildContentBrowser || rebuildContentBrowser;
            if (changedFlowId == null || changedFlowId.isBlank()) {
                refreshAllFlowBindings = true;
                flowIds.clear();
                return;
            }
            if (refreshAllFlowBindings) {
                return;
            }
            flowIds.add(changedFlowId);
            if (flowIds.size() > MAX_TARGETED_FLOW_REFRESH_IDS) {
                refreshAllFlowBindings = true;
                flowIds.clear();
            }
        }

        private FlowWorkspaceRefreshSnapshot snapshot() {
            return new FlowWorkspaceRefreshSnapshot(rebuildContentBrowser, refreshAllFlowBindings, Set.copyOf(flowIds));
        }
    }

    public void saveFlow(String serverId, FlowGraph graph) {
        ReSyncResourceType type = graph != null ? ReSyncResourceType.byTypeId(graph.getResourceType()) : null;
        if (type == null || !type.isGraph()) {
            type = graph != null && graph.isFunction() ? ReSyncResourceType.FUNCTION : ReSyncResourceType.FLOW;
        }
        saveGraph(serverId, type, graph);
    }

    public void saveGraph(String serverId, ReSyncResourceType type, FlowGraph graph) {
        saveGraph(serverId, type, graph, null);
    }

    public void saveGraph(String serverId, ReSyncResourceType type, FlowGraph graph,
                          DesignerSaveNotifications.SaveTicket ticket) {
        saveGraph(serverId, type, graph, ticket, true);
    }

    private void saveGraph(String serverId, ReSyncResourceType type, FlowGraph graph,
                           DesignerSaveNotifications.SaveTicket ticket, boolean reconcileFunctionReferences) {
        if (graph == null || type == null || !type.isGraph()) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        hydrateCoreGraphProjection(serverId, type, graph.getId());
        if (coreGraphUiProjection.authoritative(serverId, type, graph.getId())) {
            failSave(serverId, type, graph.getId(), ticket, "Core Editor Session Required");
            return;
        }
        if (coreGraphAuthorityEnabled(serverId)) {
            failSave(serverId, type, graph.getId(), ticket, "The Core graph has not been loaded from ReSync.");
            return;
        }
        FlowGraph draft = detachedGraph(graph);
        if (draft == null || draft.getId() == null) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        FlowGraph stored = flowStore.get(serverId, type, draft.getId());
        if (stored != null) {
            draft.setEnabled(stored.isEnabled());
        }
        draft.setResourceType(type.typeId());
        draft.setFunction(type == ReSyncResourceType.FUNCTION);
        FunctionSignatureTypeResolver.resolve(serverId, draft);
        CustomContentDefinition derivedContent = CustomContentGraphAdapter.toDefinition(draft);
        ReSyncResourceType mutationType = derivedContent != null ? ReSyncResourceType.CUSTOM_CONTENT : type;
        String mutationId = derivedContent != null ? derivedContent.getId() : draft.getId();
        if (!acceptSaveTicket(ticket, serverId, mutationType, mutationId)) return;
        SyncedResourceCache.SaveLease<FlowGraph> graphLease = flowStore.putInDraft(serverId, draft);
        SyncedResourceCache.SaveLease<CustomContentDefinition> derivedContentLease = null;
        if (derivedContent != null) {
            derivedContentLease = customContentStore.putInDraft(serverId, derivedContent);
            customContentStore.putNameIfAbsent(serverId, derivedContent.getId(), derivedContent.getDisplayName());
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            if (derivedContent != null) {
                customContentStore.markSaving(serverId, derivedContent.getId());
                if (ticket != null) {
                    if (!flowClient.sendResourceSave(ReSyncResourceType.CUSTOM_CONTENT, derivedContentLease, ticket)
                        && derivedContentLease.isCurrent()) customContentStore.markFailed(serverId, derivedContent.getId());
                } else if (!flowClient.sendResourceSave(ReSyncResourceType.CUSTOM_CONTENT, derivedContentLease)
                    && derivedContentLease.isCurrent()) customContentStore.markFailed(serverId, derivedContent.getId());
            } else {
                flowStore.markSaving(serverId, type, draft.getId());
                if (ticket != null) {
                    if (!flowClient.sendResourceSave(type, graphLease, ticket) && graphLease.isCurrent())
                        flowStore.markFailed(serverId, type, draft.getId());
                } else if (!flowClient.sendResourceSave(type, graphLease) && graphLease.isCurrent())
                    flowStore.markFailed(serverId, type, draft.getId());
            }
        } else if (derivedContent != null) {
            failSave(serverId, ReSyncResourceType.CUSTOM_CONTENT, derivedContent.getId(), ticket, "ReSync Offline");
        } else if (draft != null) {
            failSave(serverId, type, draft.getId(), ticket, "ReSync Offline");
        }
        if (reconcileFunctionReferences && type == ReSyncResourceType.FUNCTION) {
            reconcileFunctionSignature(serverId, draft);
        }
    }

    public void cacheFlow(String serverId, FlowGraph graph) {
        cacheFlowInternal(serverId, graph, true);
    }

    private void cacheFlowInternal(String serverId, FlowGraph graph, boolean notify) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        ReSyncResourceType graphType = graph != null ? ReSyncResourceType.byTypeId(graph.getResourceType()) : null;
        if (graphType == null || !graphType.isGraph()) {
            graphType = graph != null && graph.isFunction() ? ReSyncResourceType.FUNCTION : ReSyncResourceType.FLOW;
        }
        if (notify) {
            hydrateCoreGraphProjection(serverId, graphType, graph != null ? graph.getId() : null);
        }
        if (graph != null && coreGraphUiProjection.authoritative(serverId, graphType, graph.getId())) {
            return;
        }
        if (coreGraphAuthorityEnabled(serverId)) {
            return;
        }
        FunctionSignatureTypeResolver.resolve(serverId, graph);
        FlowGraph loadedGraph = graph != null && graph.getId() != null ? flowStore.get(serverId, graphType, graph.getId()) : null;
        boolean loadedFlow = loadedGraph != null;
        flowStore.cache(serverId, graph);
        if (graphType == ReSyncResourceType.FUNCTION) {
            reconcileFunctionSignature(serverId, graph);
        }
        if (graph != null && graph.getId() != null) {
            CustomContentDefinition derivedContent = CustomContentGraphAdapter.toDefinition(graph);
            if (derivedContent != null) {
                boolean loadedContent = customContentStore.get(serverId, derivedContent.getId()) != null;
                customContentStore.cache(serverId, derivedContent);
                customContentStore.putNameIfAbsent(serverId, derivedContent.getId(), derivedContent.getDisplayName());
                if (!loadedFlow || !loadedContent) {
                    invalidateProjectCatalog(serverId);
                }
                if (notify) {
                    refreshFlowWorkspace(serverId, graph.getId(), !loadedFlow || !loadedContent);
                }
                return;
            }
            if (!loadedFlow) {
                invalidateProjectCatalog(serverId);
            }
            if (notify) {
                refreshFlowWorkspace(serverId, graph.getId(), !loadedFlow);
            }
        }
    }

    void cacheResourceAuthoritative(String serverId, ReSyncResourceType type, Object item) {
        cacheResourceAuthoritative(serverId, type, item, -1L);
    }

    void cacheResourceAuthoritative(String serverId, ReSyncResourceType type, Object item, long draftVersion) {
        if (serverId == null || serverId.isBlank() || type == null || item == null || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        if (type.isGraph()) {
            cacheFlowInternal(serverId, (FlowGraph) item, false);
        } else if (type == ReSyncResourceType.GUI) {
            guiStore.cache(serverId, (GuiDefinition) item);
        } else if (type == ReSyncResourceType.SCOREBOARD) {
            scoreboardStore.cache(serverId, (ScoreboardDefinition) item);
        } else if (type == ReSyncResourceType.TAB) {
            tabStore.cache(serverId, (TabDefinition) item);
        } else if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            cacheCustomContentInternal(serverId, (CustomContentDefinition) item, false);
        } else if (type == ReSyncResourceType.PROJECT_METADATA) {
            ReSyncProjectMetadata metadata = (ReSyncProjectMetadata) item;
            metadata.setServerId(serverId);
            metadata.ensureDefaultFolders();
            loadedProjectMetadataLists.add(serverId);
            pendingProjectMetadataDocuments.remove(serverId);
            cacheProjectMetadataSnapshot(serverId, ProjectMetadataSnapshot.from(metadata), draftVersion);
        } else if (item instanceof JsonObject json) {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            if (store != null) {
                store.cache(serverId, json);
            }
        }
    }

    boolean cacheResourceAuthoritative(String serverId, ReSyncResourceType type, Object item, long draftVersion,
                                       ResourceProjectionLease lease) {
        if (serverId == null || serverId.isBlank() || type == null || item == null || lease == null || !lease.isValid()
            || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return false;
        }
        boolean cached;
        if (type.isGraph() && item instanceof FlowGraph graph) {
            if (coreGraphUiProjection.authoritative(serverId, type, graph.getId())
                || coreGraphAuthorityEnabled(serverId)) {
                cached = true;
            } else {
                FunctionSignatureTypeResolver.resolve(serverId, graph);
                Long expected = lease.expected(type.typeId(), serverId, graph.getId());
                Long next = expected != null ? flowStore.cacheIfGeneration(serverId, type, graph, expected) : null;
                cached = lease.advance(type.typeId(), serverId, graph.getId(), next);
                if (cached) {
                    CustomContentDefinition derived = CustomContentGraphAdapter.toDefinition(graph);
                    if (derived != null && derived.getId() != null) {
                        expected = lease.expected(ReSyncResourceType.CUSTOM_CONTENT.typeId(), serverId, derived.getId());
                        next = expected != null ? customContentStore.cacheIfGeneration(serverId, derived, expected) : null;
                        cached = lease.advance(ReSyncResourceType.CUSTOM_CONTENT.typeId(), serverId, derived.getId(), next);
                    }
                }
            }
        } else if (type == ReSyncResourceType.GUI && item instanceof GuiDefinition gui) {
            cached = cacheProjection(guiStore, serverId, type, gui.getId(), gui, lease);
        } else if (type == ReSyncResourceType.SCOREBOARD && item instanceof ScoreboardDefinition scoreboard) {
            cached = cacheProjection(scoreboardStore, serverId, type, scoreboard.getId(), scoreboard, lease);
        } else if (type == ReSyncResourceType.TAB && item instanceof TabDefinition tab) {
            cached = cacheProjection(tabStore, serverId, type, tab.getId(), tab, lease);
        } else if (type == ReSyncResourceType.CUSTOM_CONTENT && item instanceof CustomContentDefinition content) {
            cached = cacheProjection(customContentStore, serverId, type, content.getId(), content, lease);
        } else if (type == ReSyncResourceType.PROJECT_METADATA && item instanceof ReSyncProjectMetadata metadata) {
            metadata.setServerId(serverId);
            metadata.ensureDefaultFolders();
            cached = cacheProjectMetadataSnapshot(serverId, ProjectMetadataSnapshot.from(metadata), draftVersion, lease);
            if (cached) {
                loadedProjectMetadataLists.add(serverId);
                pendingProjectMetadataDocuments.remove(serverId);
            }
        } else if (item instanceof JsonObject json) {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            cached = store != null && cacheProjection(store, serverId, type, type.extractId(json), json, lease);
        } else {
            cached = false;
        }
        if (cached && type != ReSyncResourceType.PROJECT_METADATA) {
            cached = mutateTypedMembership(serverId, type, type.extractId(item), true, lease);
        }
        if (cached) {
            invalidateProjectCatalog(serverId);
        }
        return cached;
    }

    private <T> boolean cacheProjection(SyncedResourceCache<T> store, String serverId, ReSyncResourceType type,
                                        String resourceId, T item, ResourceProjectionLease lease) {
        if (store == null || resourceId == null || resourceId.isBlank()) {
            return false;
        }
        Long expected = lease.expected(type.typeId(), serverId, resourceId);
        Long next = expected != null ? store.cacheIfGeneration(serverId, item, expected) : null;
        return lease.advance(type.typeId(), serverId, resourceId, next);
    }

    void notifyResourceDataReceivedAfterCommit(String serverId, ReSyncResourceType type, Object item) {
        if (serverId == null || serverId.isBlank() || type == null || item == null) {
            return;
        }
        boolean deferWorkspaceRefresh = creationRefreshDeferred(serverId, type, item);
        if (type == ReSyncResourceType.GUI && item instanceof GuiDefinition gui) {
            handleGuiDataReceived(serverId, gui);
        } else if (type == ReSyncResourceType.SCOREBOARD && item instanceof ScoreboardDefinition scoreboard) {
            handleScoreboardDataReceived(serverId, scoreboard);
        } else if (type == ReSyncResourceType.TAB && item instanceof TabDefinition tab) {
            handleTabDataReceived(serverId, tab);
        } else if (type == ReSyncResourceType.PROJECT_METADATA) {
            advanceProjectMetadataAuthorityGeneration(serverId);
            resumeCreationTransactions(serverId);
        } else if (type == ReSyncResourceType.ADVANCEMENT_TREE && item instanceof JsonObject tree) {
            handleAdvancementTreeDataReceived(serverId, tree);
        } else if (type == ReSyncResourceType.DIALOG && item instanceof JsonObject dialog) {
            handleDialogDataReceived(serverId, dialog);
        } else if ((type == ReSyncResourceType.TRADE_PROFILE || type == ReSyncResourceType.NPC_DEFINITION
            || type == ReSyncResourceType.LOOT_TABLE) && item instanceof JsonObject resource) {
            handleFocusedJsonResourceDataReceived(serverId, type, resource);
        }
        if (!deferWorkspaceRefresh && type.isGraph() && item instanceof FlowGraph graph) {
            refreshFlowWorkspace(serverId, graph.getId(), false);
        }
        if (!deferWorkspaceRefresh) {
            refreshStudioWorkspace(serverId);
        }
    }

    private boolean creationRefreshDeferred(String serverId, ReSyncResourceType type, Object item) {
        if (serverId == null || serverId.isBlank() || type == null || item == null) {
            return false;
        }
        String id;
        try {
            id = type == ReSyncResourceType.PROJECT_METADATA ? serverId : type.extractId(item);
        } catch (RuntimeException exception) {
            return false;
        }
        if (id == null || id.isBlank()) {
            return false;
        }
        if (type == ReSyncResourceType.PROJECT_METADATA) {
            return hasPendingCreationForServer(serverId);
        }
        CreationTransaction transaction = creationTransactions.get(new CreationKey(serverId, type.typeId(), id));
        return transaction != null && !transaction.isTerminal();
    }

    private boolean hasPendingCreationForServer(String serverId) {
        return serverId != null && !serverId.isBlank() && creationTransactions.values().stream()
            .anyMatch(transaction -> transaction != null && !transaction.isTerminal()
                && serverId.equals(transaction.key.serverId()));
    }

    void markResourceSavedAuthoritative(String serverId, ReSyncResourceType type, String id,
                                        long revision, String hash) {
        markResourceSavedAuthoritative(serverId, type, id, -1L, revision, hash);
    }

    boolean markResourceSavedAuthoritative(String serverId, ReSyncResourceType type, String id,
                                            long draftVersion, long revision, String hash) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()) {
            return false;
        }
        if (type.isGraph()) {
            if (!coreGraphUiProjection.authoritative(serverId, type, id)) {
                flowStore.update(serverId, type, id, graph -> {
                    graph.setResourceRevision(revision);
                    graph.setResourceHash(hash);
                    return graph;
                });
                flowStore.markSaved(serverId, type, id);
            }
            return true;
        } else if (type == ReSyncResourceType.GUI) {
            return markSaved(guiStore, serverId, id, draftVersion);
        } else if (type == ReSyncResourceType.SCOREBOARD) {
            return markSaved(scoreboardStore, serverId, id, draftVersion);
        } else if (type == ReSyncResourceType.TAB) {
            return markSaved(tabStore, serverId, id, draftVersion);
        } else if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            return markSaved(customContentStore, serverId, id, draftVersion);
        } else if (type == ReSyncResourceType.PROJECT_METADATA) {
            return draftVersion >= 0L && projectMetadataStore.compareAndMarkSaved(serverId, serverId, draftVersion);
        } else {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            if (store != null) {
                return markSaved(store, serverId, id, draftVersion);
            }
        }
        return false;
    }

    boolean markResourceSavedAuthoritative(String serverId, ReSyncResourceType type, String id,
                                            long draftVersion, long revision, String hash,
                                            ResourceProjectionLease lease) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()
            || lease == null || !lease.isValid()) {
            return false;
        }
        Long expected = lease.expected(type.typeId(), serverId, id);
        Long next;
        if (type.isGraph()) {
            if (coreGraphUiProjection.authoritative(serverId, type, id)) {
                return true;
            }
            Long updated = expected != null
                ? flowStore.updateIfGeneration(serverId, type, id, expected, graph -> {
                    graph.setResourceRevision(revision);
                    graph.setResourceHash(hash);
                    return graph;
                }) : null;
            if (updated == null || !lease.advance(type.typeId(), serverId, id, updated)) {
                return false;
            }
            next = draftVersion >= 0L
                ? flowStore.compareAndMarkSavedIfGeneration(serverId, type, id, draftVersion, updated)
                : flowStore.markSavedIfGeneration(serverId, type, id, updated);
        } else if (type == ReSyncResourceType.PROJECT_METADATA) {
            next = expected != null
                ? projectMetadataStore.compareAndMarkSavedIfGeneration(serverId, serverId, draftVersion, expected) : null;
        } else {
            SyncedResourceCache<?> store = resourceStore(type);
            next = expected != null ? markSavedIfGeneration(store, serverId, id, draftVersion, expected) : null;
        }
        if (next == null) {
            return false;
        }
        return lease.advance(type.typeId(), serverId, id, next);
    }

    ResourceSaveSettlement markPreparedResourceSavedAuthoritative(
        String serverId, ReSyncResourceType type, String id, long revision, String hash, Object authoritativeItem,
        SyncedResourceCache.SaveLease<?> expectedLease, long expectedGeneration, ContentHash expectedPayloadHash) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()
            || expectedGeneration < 0L || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return new ResourceSaveSettlement(false, false);
        }
        try {
            if (authoritativeItem != null && !id.equals(type.extractId(authoritativeItem))) {
                return new ResourceSaveSettlement(false, false);
            }
            if (type.isGraph()) {
                FlowGraph graph = authoritativeItem instanceof FlowGraph value ? value : null;
                if (graph != null) {
                    graph.setResourceRevision(revision);
                    graph.setResourceHash(hash);
                }
                if (coreGraphUiProjection.authoritative(serverId, type, id)) {
                    return new ResourceSaveSettlement(true,
                        flowStore.matchesDraftFence(serverId, type, id, expectedLease, expectedGeneration,
                            expectedPayloadHash == null ? null
                                : value -> payloadHashMatches(type, value, expectedPayloadHash)),
                        flowStore.currentGeneration(serverId, type, id));
                }
                SyncedResourceCache.SaveSettlement settlement = flowStore.publishAuthoritativeIfCurrent(serverId,
                    type, id, graph, expectedLease, expectedGeneration,
                    expectedPayloadHash == null ? null
                        : value -> payloadHashMatches(type, value, expectedPayloadHash));
                if (!settlement.accepted()) {
                    return new ResourceSaveSettlement(false, settlement.currentAtFinish(), settlement.generation());
                }
                invalidateProjectCatalog(serverId);
                return new ResourceSaveSettlement(true, settlement.currentAtFinish(), settlement.generation());
            }
            SyncedResourceCache<?> store = type == ReSyncResourceType.PROJECT_METADATA
                ? projectMetadataStore : resourceStore(type);
            Object value = authoritativeItem;
            if (type == ReSyncResourceType.PROJECT_METADATA && authoritativeItem instanceof ReSyncProjectMetadata metadata) {
                metadata.setServerId(serverId);
                metadata.ensureDefaultFolders();
                value = ProjectMetadataSnapshot.from(metadata);
            }
            SyncedResourceCache.SaveSettlement settlement = publishAuthoritative(store, serverId,
                type == ReSyncResourceType.PROJECT_METADATA ? serverId : id, value, expectedLease,
                expectedGeneration, expectedPayloadHash == null ? null
                    : candidate -> payloadHashMatches(type, candidate, expectedPayloadHash));
            if (!settlement.accepted()) {
                return new ResourceSaveSettlement(false, settlement.currentAtFinish(), settlement.generation());
            }
            invalidateProjectCatalog(serverId);
            return new ResourceSaveSettlement(true, settlement.currentAtFinish(), settlement.generation());
        } catch (RuntimeException exception) {
            return new ResourceSaveSettlement(false, false);
        }
    }

    void recordCreationPayloadSettlement(String payloadRequestId, long revision, String hash, long generation) {
        if (payloadRequestId == null || payloadRequestId.isBlank() || revision < 1L
            || hash == null || hash.isBlank()) {
            return;
        }
        creationTransactions.values().stream().filter(transaction -> {
            synchronized (transaction) {
                return transaction.payloadTicket != null && payloadRequestId.equals(transaction.payloadTicket.requestId())
                    && transaction.phase == CreationPhase.PAYLOAD && !transaction.payloadCommitted;
            }
        }).findFirst().ifPresent(transaction -> {
            synchronized (transaction) {
                if (transaction.payloadTicket == null || !payloadRequestId.equals(transaction.payloadTicket.requestId())
                    || transaction.phase != CreationPhase.PAYLOAD || transaction.payloadCommitted) {
                    return;
                }
                transaction.commandGraphBaseRevision = revision;
                transaction.commandGraphBaseHash = hash;
                transaction.commandGraphBaseGeneration = generation > 0L
                    ? generation : creationResourceGeneration(transaction.key.serverId(), transaction.resourceType,
                        transaction.key.id());
            }
        });
    }

    boolean matchesAuthoritativeResourceFence(String serverId, ReSyncResourceType type, String id,
                                              long expectedRevision, String expectedHash) {
        return matchesAuthoritativeResourceFence(serverId, type, id, expectedRevision, expectedHash, -1L);
    }

    boolean matchesAuthoritativeResourceFence(String serverId, ReSyncResourceType type, String id,
                                              long expectedRevision, String expectedHash,
                                              long expectedGeneration) {
        if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            CustomContentAuthority authority = customContentAuthority(serverId, id);
            return authority != null && authority.result().revision() == expectedRevision
                && Objects.equals(authority.result().payloadHash(), expectedHash)
                && (expectedGeneration < 0L || authority.generation() == expectedGeneration)
                && isCurrentCustomContentAuthority(authority);
        }
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()
            || expectedRevision < 0L || expectedHash == null || expectedHash.isBlank()
            || expectedGeneration < -1L || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return false;
        }
        hydrateCoreGraphProjection(serverId, type, id);
        if (coreGraphUiProjection.authoritative(serverId, type, id)) {
            boolean matches = coreGraphUiProjection.baseline(serverId, type, id).map(baseline ->
                baseline.revision() == expectedRevision
                    && hashMatches(expectedHash, baseline.graph().getResourceHash(), baseline.assetHash(),
                        baseline.protocolHash())).orElse(false);
            return matches && (expectedGeneration < 0L
                || coreGraphUiProjection.currentGeneration(serverId, type, id) == expectedGeneration);
        }
        SyncedResourceCache.SnapshotLease<FlowGraph> lease = flowStore.snapshotAuthoritativeLease(serverId, type, id);
        FlowGraph graph = lease != null ? lease.materialize(Function.identity()) : null;
        return graph != null && graph.getResourceRevision() == expectedRevision
            && expectedHash.equals(graph.getResourceHash())
            && (expectedGeneration < 0L
            || flowStore.currentGeneration(serverId, type, id) == expectedGeneration);
    }

    private boolean hashMatches(String expected, String graphHash, ContentHash... hashes) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        if (expected.equals(graphHash)) {
            return true;
        }
        for (ContentHash hash : hashes) {
            if (hash != null && expected.equals(hash.canonicalText())) {
                return true;
            }
        }
        return false;
    }

    private boolean payloadHashMatches(ReSyncResourceType type, Object value, ContentHash expectedHash) {
        if (type == null || value == null || expectedHash == null) {
            return false;
        }
        try {
            return expectedHash.equals(creationPayloadHash(type.serialize(value)));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private SyncedResourceCache.SaveSettlement publishAuthoritative(
        SyncedResourceCache<?> store, String serverId, String id, Object value,
        SyncedResourceCache.SaveLease<?> expectedLease, long expectedGeneration,
        Predicate<Object> expectedPayload) {
        if (store == null) {
            return new SyncedResourceCache.SaveSettlement(false, false, 0L);
        }
        return ((SyncedResourceCache<Object>) store).publishAuthoritativeIfCurrent(serverId, id, value,
            expectedLease, expectedGeneration, expectedPayload);
    }

    private SyncedResourceCache<?> resourceStore(ReSyncResourceType type) {
        return switch (type) {
            case GUI -> guiStore;
            case SCOREBOARD -> scoreboardStore;
            case TAB -> tabStore;
            case CUSTOM_CONTENT -> customContentStore;
            default -> jsonResourceStores.get(type);
        };
    }

    private <T> Long markSavedIfGeneration(SyncedResourceCache<T> store, String serverId, String id,
                                           long draftVersion, long expectedGeneration) {
        if (store == null) {
            return null;
        }
        return draftVersion >= 0L
            ? store.compareAndMarkSavedIfGeneration(serverId, id, draftVersion, expectedGeneration)
            : store.markSavedIfGeneration(serverId, id, expectedGeneration);
    }

    private <T> boolean markSaved(SyncedResourceCache<T> store, String serverId, String id, long draftVersion) {
        if (draftVersion >= 0L) {
            return store.compareAndMarkSaved(serverId, id, draftVersion);
        }
        store.markSaved(serverId, id);
        return true;
    }

    private <T> Long updateEnabledIfGeneration(SyncedResourceCache<T> store, String serverId, String id,
                                               boolean enabled, long expectedGeneration) {
        if (store == null) {
            return null;
        }
        return store.updateIfGeneration(serverId, id, expectedGeneration, resource -> {
            setEnabled(resource, enabled);
            return resource;
        });
    }

    private void setEnabled(Object resource, boolean enabled) {
        if (resource instanceof GuiDefinition gui) {
            gui.setEnabled(enabled);
        } else if (resource instanceof ScoreboardDefinition scoreboard) {
            scoreboard.setEnabled(enabled);
        } else if (resource instanceof TabDefinition tab) {
            tab.setEnabled(enabled);
        } else if (resource instanceof CustomContentDefinition content) {
            content.setEnabled(enabled);
            if (content.getGraph() != null) {
                content.getGraph().setEnabled(enabled);
            }
        } else if (resource instanceof JsonObject json) {
            json.addProperty("enabled", enabled);
        }
    }

    void applyResourceActivationStateAuthoritative(String serverId, ReSyncResourceType type, String id, boolean enabled) {
        applyResourceActivationState(serverId, type, id, enabled);
    }

    boolean applyResourceActivationStateAuthoritative(String serverId, ReSyncResourceType type, String id,
                                                      boolean enabled, ResourceProjectionLease lease) {
        return applyResourceActivationStateAuthoritative(serverId, type, id, enabled, lease, null);
    }

    boolean applyResourceActivationStateAuthoritative(String serverId, ReSyncResourceType type, String id,
                                                      boolean enabled, ResourceProjectionLease lease,
                                                      Object projectedItem) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()
            || lease == null || !lease.isValid()) {
            return false;
        }
        if (type.isGraph()) {
            if (coreGraphUiProjection.authoritative(serverId, type, id) || coreGraphAuthorityEnabled(serverId)) {
                return true;
            }
            Long expected = lease.expected(type.typeId(), serverId, id);
            Long next = expected != null
                ? flowStore.updateIfGeneration(serverId, type, id, expected, graph -> {
                    graph.setEnabled(enabled);
                    return graph;
                }) : null;
            if (!lease.advance(type.typeId(), serverId, id, next)) {
                return false;
            }
            if (projectedItem instanceof FlowGraph graph) {
                CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
                if (content != null && content.getId() != null) {
                    expected = lease.expected(ReSyncResourceType.CUSTOM_CONTENT.typeId(), serverId, content.getId());
                    next = expected != null
                        ? updateEnabledIfGeneration(customContentStore, serverId, content.getId(), enabled, expected)
                        : null;
                    if (!lease.advance(ReSyncResourceType.CUSTOM_CONTENT.typeId(), serverId, content.getId(), next)) {
                        return false;
                    }
                }
            }
            return true;
        }
        SyncedResourceCache<?> store = resourceStore(type);
        if (store == null) {
            return false;
        }
        Long expected = lease.expected(type.typeId(), serverId, id);
        Long next = expected != null ? updateEnabledIfGeneration(store, serverId, id, enabled, expected) : null;
        if (!lease.advance(type.typeId(), serverId, id, next)) {
            return false;
        }
        return true;
    }

    void notifyResourceActivationCommitted(String serverId, ReSyncResourceType type, String id, boolean enabled) {
        refreshStudioWorkspace(serverId);
        if (enabled && type != null && id != null) {
            GraphEditorScreen.clearEditorErrorsForServer(serverId, type.typeId(), id);
        }
    }

    boolean applyResourceDeletionAuthoritative(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()) {
            return false;
        }
        CoreGraphSessionKey sessionKey = type.isGraph() ? new CoreGraphSessionKey(serverId, type, id) : null;
        CoreGraphSessionState session = sessionKey != null ? coreGraphEditorSessions.get(sessionKey) : null;
        ProjectMetadataEdit metadata = editProjectMetadata(serverId);
        boolean removedMetadata = metadata.resource(type.typeId(), id) != null;
        if (removedMetadata) metadata.removeResource(ReSyncProjectMetadata.resourceKey(type.typeId(), id));
        if (type.isGraph()) {
            if (!coreGraphUiProjection.authoritative(serverId, type, id)) {
                flowStore.remove(serverId, type, id);
            }
        } else {
            removeResourceFromCache(serverId, type, id);
        }
        if (removedMetadata) saveProjectMetadata(metadata, false);
        if (sessionKey != null && session != null) {
            if (coreGraphEditorSessions.remove(sessionKey, session)) {
                coreGraphHydrations.remove(sessionKey);
            }
        }
        return removedMetadata;
    }

    boolean applyResourceDeletionAuthoritative(String serverId, ReSyncResourceType type, String id,
                                               ResourceProjectionLease lease) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()
            || lease == null || !lease.isValid()) {
            return false;
        }
        ProjectMetadataSnapshot current = projectMetadataStore.get(serverId, serverId);
        if (current == null) {
            ReSyncProjectMetadata initial = new ReSyncProjectMetadata(serverId);
            initial.ensureDefaultFolders();
            current = ProjectMetadataSnapshot.from(initial);
        }
        ProjectMetadataEdit metadata = new ProjectMetadataEdit(serverId, current.edit());
        boolean removedMetadata = metadata.resource(type.typeId(), id) != null;
        if (removedMetadata) {
            metadata.removeResource(ReSyncProjectMetadata.resourceKey(type.typeId(), id));
        }
        if (type.isGraph()) {
            if (!coreGraphUiProjection.authoritative(serverId, type, id)) {
                Long expected = lease.expected(type.typeId(), serverId, id);
                Long next = expected != null ? flowStore.removeIfGeneration(serverId, type, id, expected) : null;
                if (!lease.advance(type.typeId(), serverId, id, next)) {
                    return false;
                }
            }
        } else if (!removeResourceFromCache(serverId, type, id, lease)) {
            return false;
        }
        if (removedMetadata && !persistProjectMetadata(serverId, metadata.editor.freeze(), lease)) {
            return false;
        }
        if (type != ReSyncResourceType.PROJECT_METADATA && !mutateTypedMembership(serverId, type, id, false, lease)) {
            return false;
        }
        invalidateProjectCatalog(serverId);
        return true;
    }

    void finalizeResourceDeletionAuthoritative(String serverId, ReSyncResourceType type, String id,
                                               ResourceProjectionLease lease) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()
            || lease == null || !lease.isValid() || !lease.remembersEditorSession(serverId, type, id)) {
            return;
        }
        CoreGraphSessionKey key = new CoreGraphSessionKey(serverId, type, id);
        CoreGraphEditorSession expected = lease.editorSession(serverId, type, id);
        CoreGraphSessionState current = coreGraphEditorSessions.get(key);
        if (current != null && current.session() == expected && expected != null) {
            if (coreGraphEditorSessions.remove(key, current)) {
                coreGraphHydrations.remove(key);
            }
        }
    }

    void notifyResourceDeletionCommitted(String serverId, ReSyncResourceType type, String id, boolean removedMetadata) {
        observeResourceDeleteAbsence(serverId, type, id);
        invalidateProjectCatalog(serverId);
        refreshStudioWorkspace(serverId);
    }

    void applyCoreGraphProjectionAuthoritative(ReSyncResourceType type,
                                                CoreGraphResourceProjection.Projection projection) {
        if (type == null || projection == null) {
            throw new IllegalStateException("The Core graph projection could not be applied");
        }
        Optional<FlowGraph> projected = coreGraphUiProjection.apply(type, projection);
        if (projected.isEmpty() && !projection.tombstoned()) {
            throw new IllegalStateException("The Core graph projection could not be applied");
        }
    }

    boolean applyCoreGraphProjectionAuthoritative(ReSyncResourceType type,
                                                  CoreGraphResourceProjection.Projection projection,
                                                  ResourceProjectionLease lease) {
        if (type == null || projection == null || projection.resource() == null || lease == null || !lease.isValid()) {
            return false;
        }
        String serverId = projection.resource().serverId().canonicalText();
        String resourceId = projection.resource().id();
        synchronized (coreGraphUiProjection) {
            Long expected = lease.expectedCore(serverId, type, resourceId);
            if (expected == null || coreGraphUiProjection.currentGeneration(serverId, type, resourceId) != expected) {
                return false;
            }
            Optional<FlowGraph> projected = coreGraphUiProjection.apply(type, projection);
            if (projected.isEmpty() && !projection.tombstoned()) {
                return false;
            }
            return lease.advanceCore(serverId, type, resourceId,
                coreGraphUiProjection.currentGeneration(serverId, type, resourceId));
        }
    }

    boolean commitCoreResourceActivation(String serverId, ReSyncResourceType type, String id, String requestId,
                                         boolean enabled, String message, boolean notifyFailure, Runnable commit) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()
            || commit == null) {
            return false;
        }
        PendingActivation pending;
        synchronized (resourceActivationLock) {
            pending = matchingPendingActivation(serverId, type, id, requestId);
            if (pending != null && pending.ownerToken() != null && !ownsCoreGraphOwnerToken(pending.ownerToken())) {
                return false;
            }
            commit.run();
            if (pending == null || !pendingActivations.remove(new ActivationKey(serverId, type, id), pending)) {
                pending = null;
            }
        }
        if (pending != null) {
            try {
                enqueueCommittedResourceActivationNotification(pending, serverId, type, id, enabled, message, notifyFailure);
            } catch (RuntimeException ignored) {
            }
        }
        return true;
    }

    private void enqueueCommittedResourceActivationNotification(PendingActivation pending, String serverId,
                                                                 ReSyncResourceType type, String id, boolean enabled,
                                                                 String message, boolean notifyFailure) {
        Runnable notification = () -> notifyResourceActivationAuthoritativeNow(serverId, type, id, enabled, message, notifyFailure);
        if (pending.ownerToken() != null) {
            enqueueCoreUiTransition(pending.ownerToken(), notification);
            return;
        }
        CoreGraphHandoff handoff = currentCoreGraphHandoff(serverId, type, id);
        if (handoff != null) {
            enqueueCoreUiTransition(handoff, notification);
            return;
        }
        enqueueCoreUiTransition(serverId, notification);
    }

    private void notifyResourceActivationAuthoritativeNow(String serverId, ReSyncResourceType type, String id,
                                                           boolean enabled, String message, boolean notifyFailure) {
        refreshStudioWorkspaceNow(serverId, new StudioWorkspaceRefreshSnapshot(true, true));
        if (!notifyFailure || enabled) {
            GraphEditorScreen.clearEditorErrorsForServer(serverId, type.typeId(), id);
        } else if (message != null && !message.isBlank()) {
            new Notification("Update Failed", message, Notification.Type.ERROR);
        }
    }

    CoreGraphUiProjection.StateSnapshot snapshotCoreGraphProjection(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || type == null || id == null || id.isBlank() || !type.isGraph()) {
            return null;
        }
        return coreGraphUiProjection.snapshotAll();
    }

    public CoreGraphDocumentAuthoringAdapter coreGraphDocumentAuthoring() {
        return coreGraphDocumentAuthoring;
    }

    public void setCoreGraphTemplateTransport(CoreGraphDocumentAuthoringAdapter.TemplateTransport transport) {
        coreGraphTemplateTransport = transport;
    }

    private boolean dispatchCoreGraphTemplate(UUID requestId, AuthoringTemplateRequest request,
                                              Consumer<AuthoringTemplateResponse> ignored) {
        ReSyncFlowClient flowClient = request == null || request.resource() == null ? null
            : connectionManager.getFlowClient(request.resource().serverId().canonicalText());
        if (flowClient != null) {
            try {
                return flowClient.requestAuthoringTemplate(requestId, request,
                    response -> acceptCoreGraphTemplate(requestId, response));
            } catch (RuntimeException exception) {
                return false;
            }
        }
        CoreGraphDocumentAuthoringAdapter.TemplateTransport transport = coreGraphTemplateTransport;
        if (transport == null || requestId == null || request == null) {
            return false;
        }
        try {
            return transport.request(requestId, request,
                response -> acceptCoreGraphTemplate(requestId, response));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public CoreGraphDocumentAuthoringAdapter.RequestResult requestCoreGraphTemplate(
        ServerResourceLocator resource, CatalogAuthoringPublication publication) {
        if (resource != null) {
            ReSyncFlowClient flowClient = connectionManager.getFlowClient(resource.serverId().canonicalText());
            CatalogCacheKey expectedKey = publication == null || publication.binding() == null
                || publication.projectionVersion() == null ? null
                : new CatalogCacheKey(resource.serverId(), publication.binding(), publication.projectionVersion());
            if (flowClient == null || flowClient.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
                || expectedKey == null || !activeCatalogKey(flowClient, expectedKey)) {
                String diagnostic = flowClient == null ? "client=missing,server=" + resource.serverId().canonicalText()
                    : flowClient.catalogAuthorityDebugState() + ",expectedKey="
                        + (expectedKey == null ? "none" : expectedKey.canonicalText())
                        + ",publicationPresent=" + (publication != null);
                ReSyncFlowClient.traceLifecycle(resource.serverId().canonicalText(), "create_template_rejected",
                    "serverId", resource.serverId().canonicalText(), "resourceKey",
                    resource.resourceType().value() + ":" + resource.id(), "operation", "create", "requestId",
                    "template", "mutationId", null, "generation", flowClient != null
                        ? flowClient.activeTransportGeneration() : -1, "authorityEpoch", flowClient != null
                        ? flowClient.authorityEpoch() : 0L, "revision", 0L, "catalogAuthority",
                    flowClient != null ? flowClient.catalogAuthority() : ReSyncFlowClient.CatalogAuthority.UNAVAILABLE,
                    "expectedCatalogKey", expectedKey != null ? expectedKey.canonicalText() : "none",
                    "publicationPresent", publication != null, "reason", diagnostic);
                return new CoreGraphDocumentAuthoringAdapter.RequestResult(
                    CoreGraphDocumentAuthoringAdapter.RequestStatus.UNAVAILABLE, null, null,
                    CoreGraphDocumentAuthoringAdapter.CATALOG_AUTHORITY_UNAVAILABLE);
            }
        }
        return coreGraphDocumentAuthoring.requestTemplate(resource, publication);
    }

    public CoreGraphDocumentAuthoringAdapter.DraftResult acceptCoreGraphTemplate(
        UUID requestId, AuthoringTemplateResponse response) {
        AuthoringTemplateRequest pendingRequest = requestId == null ? null
            : coreGraphDocumentAuthoring.pending(requestId).orElse(null);
        CoreTemplateIntent intent = coreTemplateIntent(requestId, response, pendingRequest);
        if (response != null) {
            ReSyncFlowClient flowClient = response.resource() == null ? null
                : connectionManager.getFlowClient(response.resource().serverId().canonicalText());
            if (flowClient == null || flowClient.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
                || !activeCatalogKey(flowClient, response.publicationKey())) {
                CoreGraphDocumentAuthoringAdapter.DraftResult result = coreGraphDocumentAuthoring.rejectTemplate(requestId,
                    CoreGraphDocumentAuthoringAdapter.CATALOG_AUTHORITY_UNAVAILABLE);
                if (requestId != null) {
                    if (deferCoreTemplateRollover(requestId, intent, pendingRequest, response, result.reason(),
                        matchesCoreTemplateResponse(pendingRequest, response))) {
                        return result;
                    }
                    coreTemplateResponses.remove(requestId);
                    CoreTemplateIntent rejected = pendingCoreTemplateIntents.remove(requestId);
                    if (rejected != null) {
                        ScreenManager.getInstance().execute(() -> new Notification("Create", "Core Template Rejected",
                            Notification.Type.ERROR));
                    }
                }
                return result;
            }
        }
        CoreGraphDocumentAuthoringAdapter.DraftResult result = coreGraphDocumentAuthoring.acceptTemplate(requestId, response);
        if (result.accepted() && response != null && requestId != null) {
            coreTemplateResponses.put(requestId, response);
            completeCoreTemplateIntent(requestId);
        } else if (requestId != null) {
            if (deferCoreTemplateRollover(requestId, intent, pendingRequest, response, result.reason(),
                matchesCoreTemplateResponse(pendingRequest, response))) {
                return result;
            }
            coreTemplateResponses.remove(requestId);
            CoreTemplateIntent rejected = pendingCoreTemplateIntents.remove(requestId);
            if (rejected != null) {
                ScreenManager.getInstance().execute(() -> new Notification("Create", "Core Template Rejected",
                    Notification.Type.ERROR));
            }
        }
        return result;
    }

    public CoreGraphDocumentAuthoringAdapter.RequestResult requestCoreGraphCreation(
        String serverId, ReSyncResourceType type, String id, String title, String branchPin) {
        ProjectMetadataEdit edit = editProjectMetadata(serverId);
        ProjectResource existing = edit.resource(type != null ? type.typeId() : "", id);
        CreationMetadataIntent metadata = type == null ? null : new CreationMetadataIntent(type.typeId(), id,
            title == null || title.isBlank() ? id : title, canonicalResourcePath(type, id, type.defaultFolder()), "",
            existing != null ? existing.sortOrder() : edit.nextResourceSortOrder(), false);
        return requestCoreGraphCreation(serverId, type, id, title, branchPin, metadata, null);
    }

    public CoreGraphDocumentAuthoringAdapter.RequestResult requestCoreGraphCreation(
        String serverId, ReSyncResourceType type, String id, String title, String branchPin,
        CreationMetadataIntent metadata, Consumer<CreationResult> observer) {
        ServerResourceLocator resource = coreGraphResource(serverId, type, id);
        if (resource == null) {
            return new CoreGraphDocumentAuthoringAdapter.RequestResult(
                CoreGraphDocumentAuthoringAdapter.RequestStatus.REJECTED, null, null,
                "AUTHORING_TEMPLATE_RESOURCE_INVALID");
        }
        return requestCoreGraphCreation(resource, title, branchPin, metadata, observer);
    }

    public CoreGraphDocumentAuthoringAdapter.RequestResult requestCoreGraphCreation(
        ServerResourceLocator resource, String title, String branchPin) {
        ReSyncResourceType type = resource != null ? ReSyncResourceType.byTypeId(resource.resourceType().value()) : null;
        ProjectMetadataEdit edit = resource != null ? editProjectMetadata(resource.serverId().canonicalText()) : null;
        ProjectResource existing = edit != null && type != null ? edit.resource(type.typeId(), resource.id()) : null;
        CreationMetadataIntent metadata = resource == null || type == null ? null : new CreationMetadataIntent(
            type.typeId(), resource.id(), title == null || title.isBlank() ? resource.id() : title,
            canonicalResourcePath(type, resource.id(), type.defaultFolder()), "",
            existing != null ? existing.sortOrder() : edit.nextResourceSortOrder(), false);
        return requestCoreGraphCreation(resource, title, branchPin, metadata, null);
    }

    private CoreTemplateIntent coreTemplateIntent(UUID requestId, AuthoringTemplateResponse response,
                                                   AuthoringTemplateRequest pendingRequest) {
        CoreTemplateIntent intent = requestId == null ? null : pendingCoreTemplateIntents.get(requestId);
        if (intent != null) {
            return intent;
        }
        ServerResourceLocator resource = pendingRequest != null ? pendingRequest.resource()
            : response != null ? response.resource() : null;
        return resource == null ? null : preparingCoreTemplateIntents.get(resource);
    }

    private boolean deferCoreTemplateRollover(UUID requestId, CoreTemplateIntent intent,
                                              AuthoringTemplateRequest pendingRequest,
                                              AuthoringTemplateResponse response, String reason,
                                              boolean responseIdentityValid) {
        if (intent == null || !isRetryableCoreTemplateResult(reason, response)
            || response != null && !responseIdentityValid) {
            return false;
        }
        CatalogCacheKey requestKey = pendingRequest != null ? pendingRequest.acknowledgedCatalogKey()
            : response != null ? response.publicationKey() : null;
        if (response != null && !isCoreTemplatePublicationTransition(intent.resource(), requestKey)
            || !deferCoreTemplateIntent(intent, true)) {
            return false;
        }
        pendingCoreTemplateIntents.remove(requestId, intent);
        coreTemplateResponses.remove(requestId);
        coreGraphDocumentAuthoring.discard(intent.resource());
        traceCoreTemplateDeferred(intent, "catalog_publication_rollover");
        return true;
    }

    private boolean deferCoreTemplateIntent(CoreTemplateIntent intent, boolean rollover) {
        if (intent == null || intent.resource() == null) {
            return false;
        }
        CoreTemplateIntent candidate = rollover ? intent.rollover() : intent;
        if (candidate == null) {
            return false;
        }
        synchronized (deferredCoreTemplateIntents) {
            CoreTemplateIntent existing = deferredCoreTemplateIntents.get(candidate.resource());
            if (existing != null) {
                if (existing.rolloverAttempts() < candidate.rolloverAttempts()) {
                    deferredCoreTemplateIntents.put(candidate.resource(), candidate);
                }
                return true;
            }
            if (deferredCoreTemplateIntents.size() >= CoreGraphDocumentAuthoringAdapter.MAX_PENDING_REQUESTS) {
                return false;
            }
            deferredCoreTemplateIntents.put(candidate.resource(), candidate);
            return true;
        }
    }

    private boolean isCoreTemplatePublicationTransition(ServerResourceLocator resource,
                                                         CatalogAuthoringPublication publication,
                                                         ReSyncFlowClient flowClient) {
        if (resource == null) {
            return false;
        }
        ReSyncFlowClient current = connectionManager.getFlowClient(resource.serverId().canonicalText());
        if (current == null) {
            return false;
        }
        flowClient = current;
        if (!flowClient.catalogAuthoringAdvertised()) {
            return false;
        }
        if (flowClient.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION) {
            return true;
        }
        if (flowClient.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
            return false;
        }
        if (publication == null || publication.binding() == null || publication.projectionVersion() == null) {
            return true;
        }
        CatalogCacheKey key = new CatalogCacheKey(resource.serverId(), publication.binding(),
            publication.projectionVersion());
        return !activeCatalogKey(flowClient, key);
    }

    private boolean isCoreTemplatePublicationTransition(ServerResourceLocator resource, CatalogCacheKey requestKey) {
        if (resource == null || requestKey == null) {
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(resource.serverId().canonicalText());
        if (flowClient == null || !flowClient.catalogAuthoringAdvertised()) {
            return false;
        }
        if (flowClient.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION) {
            return true;
        }
        return flowClient.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            && !activeCatalogKey(flowClient, requestKey);
    }

    private static boolean isRetryableCoreTemplateRequestReason(String reason) {
        return CoreGraphDocumentAuthoringAdapter.CATALOG_AUTHORITY_UNAVAILABLE.equals(reason)
            || CoreGraphDocumentAuthoringAdapter.AUTHORING_CATALOG_UNAVAILABLE.equals(reason);
    }

    private static boolean isRetryableCoreTemplateResult(String reason, AuthoringTemplateResponse response) {
        if (response == null) {
            return CoreGraphDocumentAuthoringAdapter.AUTHORING_TEMPLATE_STALE.equals(reason);
        }
        return CoreGraphDocumentAuthoringAdapter.CATALOG_AUTHORITY_UNAVAILABLE.equals(reason)
            || CoreGraphDocumentAuthoringAdapter.AUTHORING_CATALOG_UNAVAILABLE.equals(reason);
    }

    private static boolean matchesCoreTemplateResponse(AuthoringTemplateRequest request,
                                                       AuthoringTemplateResponse response) {
        return response == null || request != null && request.resource() != null
            && request.acknowledgedCatalogKey() != null
            && request.resource().equals(response.resource())
            && request.acknowledgedCatalogKey().equals(response.publicationKey());
    }

    private void traceCoreTemplateDeferred(CoreTemplateIntent intent, String reason) {
        if (intent == null || intent.resource() == null) {
            return;
        }
        CoreTemplateIntent traced = "catalog_publication_rollover".equals(reason) ? intent.rollover() : intent;
        int attempt = traced != null ? traced.rolloverAttempts() : intent.rolloverAttempts();
        String serverId = intent.resource().serverId().canonicalText();
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        ReSyncFlowClient.traceLifecycle(serverId, "create_template_deferred", "serverId", serverId,
            "resourceKey", intent.resource().resourceType().value() + ":" + intent.resource().id(), "requestId",
            "pending", "mutationId", null, "generation", flowClient != null ? flowClient.activeTransportGeneration() : -1L,
            "authorityEpoch", flowClient != null ? flowClient.resourceRevisionReconciler().authorityEpoch(serverId) : 0L,
            "revision", 0L, "attempt", attempt, "reason", reason);
    }

    private CoreGraphDocumentAuthoringAdapter.RequestResult requestCoreGraphCreation(
        ServerResourceLocator resource, String title, String branchPin, CreationMetadataIntent metadata,
        Consumer<CreationResult> observer) {
        CatalogAuthoringPublication publication = activeAuthoringPublication(resource).orElse(null);
        ReSyncFlowClient flowClient = resource != null
            ? connectionManager.getFlowClient(resource.serverId().canonicalText()) : null;
        String documentTitle = title == null || title.isBlank() ? (resource != null ? resource.id() : "") : title;
        CoreTemplateIntent requestedIntent = new CoreTemplateIntent(resource, documentTitle, branchPin, metadata, observer);
        if (isCoreTemplatePublicationTransition(resource, publication, flowClient)) {
            if (deferCoreTemplateIntent(requestedIntent, false)) {
                traceCoreTemplateDeferred(requestedIntent, "catalog_reconciliation_pending");
                return CoreGraphDocumentAuthoringAdapter.RequestResult.pending("CATALOG_RECONCILIATION_PENDING");
            }
            return new CoreGraphDocumentAuthoringAdapter.RequestResult(
                CoreGraphDocumentAuthoringAdapter.RequestStatus.REJECTED, null, null,
                "AUTHORING_TEMPLATE_REQUEST_LIMIT");
        }
        CoreTemplateIntent intent = requestedIntent;
        boolean registered = false;
        if (resource != null) {
            CoreTemplateIntent existing = preparingCoreTemplateIntents.putIfAbsent(resource, requestedIntent);
            if (existing != null) {
                intent = existing;
            } else {
                registered = true;
            }
        }
        CoreGraphDocumentAuthoringAdapter.RequestResult result;
        try {
            result = requestCoreGraphTemplate(resource, publication);
        } finally {
            if (registered) {
                preparingCoreTemplateIntents.remove(resource, intent);
            }
        }
        if (result.status() == CoreGraphDocumentAuthoringAdapter.RequestStatus.REQUESTED
            && result.requestId() != null) {
            if (resource == null || !deferredCoreTemplateIntents.containsKey(resource)) {
                pendingCoreTemplateIntents.putIfAbsent(result.requestId(), intent);
                completeCoreTemplateIntent(result.requestId());
            }
        } else if (!result.admitted() && isRetryableCoreTemplateRequestReason(result.reason())
            && isCoreTemplatePublicationTransition(resource, publication, flowClient)
            && deferCoreTemplateIntent(intent, true)) {
            traceCoreTemplateDeferred(intent, "catalog_publication_rollover");
            result = CoreGraphDocumentAuthoringAdapter.RequestResult.pending("CATALOG_RECONCILIATION_PENDING");
        }
        ReSyncFlowClient.traceLifecycle(resource != null ? resource.serverId().canonicalText() : null,
            result.admitted() ? "create_template_admitted" : "create_template_rejected", "serverId",
            resource != null ? resource.serverId().canonicalText() : "unresolved", "resourceKey",
            resource != null ? resource.resourceType().value() + ":" + resource.id() : "unknown:unknown",
            "requestId", result.requestId(), "mutationId", null, "generation",
            flowClient != null ? flowClient.activeTransportGeneration() : -1L, "authorityEpoch",
            flowClient != null ? flowClient.resourceRevisionReconciler().authorityEpoch(
                resource.serverId().canonicalText()) : 0L,
            "revision", 0L, "reason", result.reason());
        return result;
    }

    public void retryPendingCoreGraphCreations(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null || flowClient.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            || flowClient.activeAuthoringPublication().isEmpty()) {
            return;
        }
        resumeCreationTransactions(serverId);
        List<CoreTemplateIntent> intents;
        synchronized (deferredCoreTemplateIntents) {
            intents = deferredCoreTemplateIntents.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                    && serverId.equals(entry.getKey().serverId().canonicalText()))
                .map(Map.Entry::getValue).filter(Objects::nonNull).toList();
        }
        for (CoreTemplateIntent intent : intents) {
            if (!deferredCoreTemplateIntents.remove(intent.resource(), intent)) {
                continue;
            }
            CoreGraphDocumentAuthoringAdapter.RequestResult result = requestCoreGraphCreation(
                intent.resource(), intent.title(), intent.branchPin(), intent.metadata(), intent.observer());
            if (result.status() == CoreGraphDocumentAuthoringAdapter.RequestStatus.PENDING) {
                deferredCoreTemplateIntents.putIfAbsent(intent.resource(), intent);
            } else if (!result.admitted()) {
                new Notification("Create Unavailable", "Core Template Unavailable", Notification.Type.ERROR);
            }
        }
    }

    private void completeCoreTemplateIntent(UUID requestId) {
        if (requestId == null) {
            return;
        }
        CoreTemplateIntent intent = pendingCoreTemplateIntents.get(requestId);
        if (intent == null) {
            return;
        }
        Optional<CoreGraphDocumentAuthoringAdapter.DraftResult> completed = coreGraphDocumentAuthoring.completed(requestId);
        if (completed.isEmpty()) {
            return;
        }
        CoreGraphDocumentAuthoringAdapter.DraftResult result = completed.orElseThrow();
        if (!result.accepted()) {
            pendingCoreTemplateIntents.remove(requestId, intent);
            coreTemplateResponses.remove(requestId);
            ReSyncFlowClient.traceLifecycle(intent.resource().serverId().canonicalText(), "create_template_rejected",
                "serverId", intent.resource().serverId().canonicalText(), "resourceKey",
                intent.resource().resourceType().value() + ":" + intent.resource().id(), "requestId", requestId,
                "mutationId", null, "generation", -1L, "authorityEpoch", coreGraphUiProjection.authorityEpoch(
                    intent.resource().serverId().canonicalText()), "revision", 0L, "reason", result.reason());
            ScreenManager.getInstance().execute(() -> new Notification("Create", "Core Template Rejected",
                Notification.Type.ERROR));
            return;
        }
        AuthoringTemplateResponse response = coreTemplateResponses.get(requestId);
        Optional<CoreGraphEditorSession> session = coreGraphDocumentAuthoring.editorSession(requestId);
        if (session.isEmpty()) {
            if (deferCoreTemplateRollover(requestId, intent, null, response,
                CoreGraphDocumentAuthoringAdapter.AUTHORING_CATALOG_UNAVAILABLE, true)) {
                return;
            }
            pendingCoreTemplateIntents.remove(requestId, intent);
            coreTemplateResponses.remove(requestId);
            return;
        }
        if (!session.orElseThrow().isRevisionZeroBaseline()) {
            pendingCoreTemplateIntents.remove(requestId, intent);
            coreTemplateResponses.remove(requestId);
            return;
        }
        CoreGraphEditorSession editorSession = session.orElseThrow();
        pendingCoreTemplateIntents.remove(requestId, intent);
        coreTemplateResponses.remove(requestId);
        CreationAdmission admission = beginCoreGraphCreation(intent, editorSession);
        if (admission.rejected()) {
            coreGraphDocumentAuthoring.discard(intent.resource());
            ScreenManager.getInstance().execute(() -> new Notification("Create", admission.message(),
                Notification.Type.ERROR));
        }
    }

    private ServerResourceLocator coreGraphResource(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank() || id.indexOf('/') >= 0) {
            return null;
        }
        try {
            return ServerResourceLocator.parseCanonicalText(
                serverId + "/restudio.resync/" + type.typeId() + "/" + id);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public Optional<CoreGraphEditorSession> coreGraphEditorSession(
        String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank()) {
            return Optional.empty();
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            return Optional.empty();
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        if (!isCurrentServerConnection(token)) {
            return Optional.empty();
        }
        CoreGraphSessionKey key = new CoreGraphSessionKey(serverId, type, id);
        CoreGraphSessionState known;
        synchronized (coreGraphEditorSessions) {
            known = coreGraphEditorSessions.get(key);
        }
        if (known != null && known.stale()) {
            hydrateCoreGraphProjection(serverId, type, id);
        }
        synchronized (serverConnectionGenerationLock) {
            if (!isCurrentServerConnectionLocked(token)) {
                return Optional.empty();
            }
            synchronized (coreGraphEditorSessions) {
                CoreGraphSessionState current = coreGraphEditorSessions.get(key);
                if (current != null) {
                    CoreGraphSessionState reconciled = reconcileCoreGraphSession(key, current, token);
                    if (reconciled != current) {
                        coreGraphEditorSessions.put(key, reconciled);
                    }
                    if (reconciled.stale()) {
                        return Optional.empty();
                    }
                    return Optional.of(reconciled.session());
                }
            }
        }
        hydrateCoreGraphProjection(serverId, type, id);
        CoreGraphUiProjection.Baseline baseline = coreGraphUiProjection.baseline(serverId, type, id).orElse(null);
        if (baseline == null || !baseline.canSave()) {
            return Optional.empty();
        }
        CoreGraphEditorSession session = createCoreGraphSession(baseline, serverId, type, id);
        if (session == null) {
            return Optional.empty();
        }
        synchronized (serverConnectionGenerationLock) {
            if (!isCurrentServerConnectionLocked(token)) {
                return Optional.empty();
            }
            synchronized (coreGraphEditorSessions) {
                CoreGraphSessionState current = coreGraphEditorSessions.get(key);
                if (current != null) {
                    CoreGraphSessionState reconciled = reconcileCoreGraphSession(key, current, token);
                    if (reconciled != current) {
                        coreGraphEditorSessions.put(key, reconciled);
                    }
                    if (reconciled.stale()) {
                        return Optional.empty();
                    }
                    return Optional.of(reconciled.session());
                }
                if (!registerCoreGraphEditorSessionLocked(session, token)) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(session);
    }

    public Optional<CoreGraphEditorSession> activeCoreGraphEditorSession(
        String serverId, ReSyncResourceType type, String id) {
        return coreGraphEditorSession(serverId, type, id);
    }

    public Optional<CoreGraphEditorSession> peekCoreGraphEditorSession(
        String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank()) {
            return Optional.empty();
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        if (!isCurrentServerConnection(token)) {
            return Optional.empty();
        }
        CoreGraphSessionState state = coreGraphEditorSessions.get(new CoreGraphSessionKey(serverId, type, id));
        CoreGraphUiProjection.Baseline baseline = coreGraphUiProjection.baseline(serverId, type, id).orElse(null);
        CatalogAuthoringPublication publication = state != null
            ? activeAuthoringPublication(state.session().resource()).orElse(null) : null;
        boolean current = state != null && !state.stale() && state.token() != null
            && state.token().source() == token.source() && state.token().generation() == token.generation()
            && baseline != null && baseline.canSave() && matchesCurrentCorePublication(state.session(), publication)
            && matchesCoreBaselinePublication(baseline, publication)
            && matchesCoreSessionBaseline(state.session(), baseline, state.baselineProof());
        return current ? Optional.of(state.session()) : Optional.empty();
    }

    public boolean isCurrentCoreGraphEditorSession(String serverId, ReSyncResourceType type, String id,
                                                   CoreGraphEditorSession session) {
        if (session == null || serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank() || !sessionResourceMatches(session, serverId, type, id)) {
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            return false;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        CoreGraphSessionKey key = new CoreGraphSessionKey(serverId, type, id);
        synchronized (serverConnectionGenerationLock) {
            if (!isCurrentServerConnectionLocked(token)) {
                return false;
            }
            synchronized (coreGraphEditorSessions) {
                CoreGraphSessionState state = coreGraphEditorSessions.get(key);
                if (state == null || state.session() != session) {
                    return false;
                }
                CoreGraphSessionState reconciled = reconcileCoreGraphSession(key, state, token);
                if (reconciled != state) {
                    coreGraphEditorSessions.put(key, reconciled);
                }
                return !reconciled.stale() && reconciled.session() == session;
            }
        }
    }

    public boolean ownsCoreGraphEditorSession(String serverId, ReSyncResourceType type, String id,
                                              CoreGraphEditorSession session) {
        if (session == null || serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank() || !sessionResourceMatches(session, serverId, type, id)) {
            return false;
        }
        CoreGraphSessionState state = coreGraphEditorSessions.get(new CoreGraphSessionKey(serverId, type, id));
        return state != null && state.session() == session;
    }

    public boolean isCoreGraphEditorSessionDirty(String serverId, ReSyncResourceType type, String id,
                                                  CoreGraphEditorSession session) {
        if (session == null || serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank() || !sessionResourceMatches(session, serverId, type, id)) {
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
            synchronized (serverConnectionGenerationLock) {
                if (isCurrentServerConnectionLocked(token)) {
                    CoreGraphSessionKey key = new CoreGraphSessionKey(serverId, type, id);
                    synchronized (coreGraphEditorSessions) {
                        CoreGraphSessionState state = coreGraphEditorSessions.get(key);
                        if (state != null && state.session() == session) {
                            CoreGraphSessionState reconciled = reconcileCoreGraphSession(key, state, token);
                            if (reconciled != state) {
                                coreGraphEditorSessions.put(key, reconciled);
                            }
                            return reconciled.stale() || session.isDirty();
                        }
                    }
                }
            }
        }
        CoreGraphSessionState state = coreGraphEditorSessions.get(new CoreGraphSessionKey(serverId, type, id));
        return state != null && state.session() == session && (state.stale() || session.isDirty());
    }

    public boolean isCoreGraphEditorSessionStale(String serverId, ReSyncResourceType type, String id,
                                                 CoreGraphEditorSession session) {
        if (session == null || serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank() || !sessionResourceMatches(session, serverId, type, id)) {
            return false;
        }
        CoreGraphSessionState state = coreGraphEditorSessions.get(new CoreGraphSessionKey(serverId, type, id));
        return state != null && state.session() == session && state.stale();
    }

    public boolean isCoreGraphAuthoritative(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank()) {
            return false;
        }
        hydrateCoreGraphProjection(serverId, type, id);
        return coreGraphUiProjection.authoritative(serverId, type, id);
    }

    public ReSyncFlowClient.CoreGraphActivationOutcome peekCoreGraphActivation(
        String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank()) {
            return new ReSyncFlowClient.CoreGraphActivationOutcome(ReSyncFlowClient.CoreGraphActivationState.PENDING,
                "Core graph activation requires a graph resource identity.", 0L);
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        if (!isCurrentServerConnection(token)) {
            return new ReSyncFlowClient.CoreGraphActivationOutcome(ReSyncFlowClient.CoreGraphActivationState.PENDING,
                "The Core graph connection is unavailable.", 0L);
        }
        return flowClient.peekCoreGraphActivation(type, id);
    }

    public boolean requestCoreGraphHydration(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank()) {
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        if (!isCurrentServerConnection(token)) {
            return false;
        }
        coreGraphHydrations.activate(new CoreGraphSessionKey(serverId, type, id), token.generation(),
            flowClient.resourceRevisionReconciler().authorityEpoch(serverId), System.nanoTime());
        hydrateCoreGraphProjection(serverId, type, id);
        prepareRequestedCoreSession(token, type, id);
        return true;
    }

    private void prepareRequestedCoreSession(ServerConnectionToken token, ReSyncResourceType type, String id) {
        CoreGraphSessionKey key = new CoreGraphSessionKey(token.serverId(), type, id);
        if (coreGraphEditorSessions.containsKey(key) || !pendingCoreSessionPreparations.add(key)) {
            return;
        }
        long authorityEpoch = token.source().resourceRevisionReconciler().authorityEpoch(token.serverId());
        try {
            projectMetadataHydrations.execute(() -> {
                try {
                    if (!isCurrentServerConnection(token) || coreGraphEditorSessions.containsKey(key)) {
                        return;
                    }
                    CoreGraphUiProjection.Baseline baseline = coreGraphUiProjection.baseline(
                        token.serverId(), type, id).orElse(null);
                    CatalogAuthoringPublication publication = baseline != null
                        ? activeAuthoringPublication(locatorOf(baseline.payload())).orElse(null) : null;
                    if (!currentEditableCoreBaseline(baseline, publication, null, false)
                        || coreGraphUiProjection.authorityEpoch(token.serverId()) != authorityEpoch) {
                        return;
                    }
                    CoreGraphEditorSession session = createCoreGraphSession(baseline, token.serverId(), type, id);
                    if (session == null) {
                        return;
                    }
                    synchronized (serverConnectionGenerationLock) {
                        if (!isCurrentServerConnectionLocked(token)
                            || token.source().resourceRevisionReconciler().authorityEpoch(token.serverId()) != authorityEpoch
                            || coreGraphUiProjection.authorityEpoch(token.serverId()) != authorityEpoch
                            || coreGraphUiProjection.baseline(token.serverId(), type, id).orElse(null) != baseline) {
                            return;
                        }
                        synchronized (coreGraphEditorSessions) {
                            if (!coreGraphEditorSessions.containsKey(key)) {
                                registerCoreGraphEditorSessionLocked(session, token);
                            }
                        }
                    }
                } finally {
                    pendingCoreSessionPreparations.remove(key);
                }
            });
        } catch (IllegalStateException exception) {
            pendingCoreSessionPreparations.remove(key);
        }
    }

    public Optional<String> coreGraphOpenIncompatibility(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return Optional.empty();
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        return flowClient == null ? Optional.empty() : flowClient.coreGraphOpenIncompatibility(type, id);
    }

    void notifyCoreGraphOpenIncompatibility(String serverId, ReSyncResourceType type, String id, String reason) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()
            || reason == null || reason.isBlank()) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        ScreenManager.getInstance().execute(() -> {
            if (isCurrentServerConnection(token)
                && coreGraphOpenIncompatibility(serverId, type, id).filter(reason::equals).isPresent()) {
                new Notification("Core Graph Open Failed", reason, Notification.Type.ERROR);
            }
        });
    }

    private CoreGraphEditorSession createCoreGraphSession(CoreGraphUiProjection.Baseline baseline,
                                                          String serverId, ReSyncResourceType type, String id) {
        if (baseline == null || baseline.payload() == null) {
            return null;
        }
        ServerResourceLocator resource = coreGraphResource(serverId, type, id);
        CatalogAuthoringPublication publication = activeAuthoringPublication(resource).orElse(null);
        if (resource == null || publication == null || !resource.equals(locatorOf(baseline.payload()))) {
            return null;
        }
        try {
            ContentHash checksum = CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
            if (baseline.payload() instanceof GraphDocument graph) {
                return new CoreGraphEditorSession(graph, publication.binding(), checksum,
                    catalogCapabilities(publication), publication.advertisedEditCapabilities());
            }
            if (baseline.payload() instanceof FunctionSourceDocument source) {
                return new CoreGraphEditorSession(source, publication.binding(), checksum,
                    catalogCapabilities(publication), publication.advertisedEditCapabilities());
            }
        } catch (RuntimeException exception) {
            return null;
        }
        return null;
    }

    private static Set<ContractRef<CapabilityId>> catalogCapabilities(CatalogAuthoringPublication publication) {
        if (publication == null || publication.capabilities() == null) {
            return Set.of();
        }
        return publication.capabilities().stream()
            .filter(entry -> entry != null && entry.editable())
            .map(CatalogAuthoringPublication.Entry::reference)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static ServerResourceLocator locatorOf(Object payload) {
        if (payload instanceof GraphDocument graph) {
            return graph.resource();
        }
        if (payload instanceof FunctionSourceDocument source) {
            return source.graph().resource();
        }
        return null;
    }

    private CoreGraphSessionState reconcileCoreGraphSession(CoreGraphSessionKey key,
                                                             CoreGraphSessionState current,
                                                             ServerConnectionToken token) {
        CoreGraphEditorSession session = current.session();
        if (session == null || !sessionResourceMatches(session, key.serverId(), key.type(), key.id())
            || token == null) {
            return current.staleState();
        }
        CoreGraphSessionState candidate = current;
        if (current.stale() || current.token() == null || current.token().source() != token.source()
            || current.token().generation() != token.generation()) {
            CoreGraphUiProjection.Baseline baseline = coreGraphUiProjection.baseline(key.serverId(), key.type(), key.id())
                .orElse(null);
            if (baseline == null || !baseline.canSave()) {
                return current.staleState();
            }
            candidate = new CoreGraphSessionState(session, token, false);
        }
        if (candidate.stale()) {
            return candidate;
        }
        CatalogAuthoringPublication publication = activeAuthoringPublication(session.resource()).orElse(null);
        CoreGraphUiProjection.Baseline baseline = coreGraphUiProjection.baseline(key.serverId(), key.type(), key.id())
            .orElse(null);
        if (baseline != null && baseline.canSave() && matchesCoreBaselinePublication(baseline, publication)
            && !matchesCurrentCorePublication(session, publication)) {
            try {
                ContentHash checksum = CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
                if (session.isGraph() && baseline.payload() instanceof GraphDocument graph) {
                    session.rebasePublication(graph, checksum, catalogCapabilities(publication),
                        publication.advertisedEditCapabilities());
                } else if (session.isFunction() && baseline.payload() instanceof FunctionSourceDocument source) {
                    session.rebasePublication(source, checksum, catalogCapabilities(publication),
                        publication.advertisedEditCapabilities());
                } else {
                    return current.staleState();
                }
                candidate = new CoreGraphSessionState(session, token, false);
            } catch (RuntimeException exception) {
                return current.staleState();
            }
        }
        if (!matchesCurrentCorePublication(session, publication)
            || baseline != null && (!baseline.canSave() || !matchesCoreBaselinePublication(baseline, publication))) {
            return current.staleState();
        }
        if (baseline != null) {
            synchronized (session) {
                try {
                    if (!matchesCoreSessionBaseline(session, baseline, candidate.baselineProof())) {
                        if (session.isGraph() && baseline.payload() instanceof GraphDocument graph) {
                            session.rebase(graph);
                        } else if (session.isFunction() && baseline.payload() instanceof FunctionSourceDocument source) {
                            session.rebase(source);
                        } else {
                            return current.staleState();
                        }
                    }
                    candidate = candidate.withBaselineProof(baseline);
                } catch (RuntimeException exception) {
                    return current.staleState();
                }
            }
        }
        return Objects.equals(candidate.token(), token) ? candidate : new CoreGraphSessionState(session, token, false);
    }

    private static boolean matchesCoreBaselinePublication(CoreGraphUiProjection.Baseline baseline,
                                                           CatalogAuthoringPublication publication) {
        return baseline != null && publication != null && publication.binding() != null
            && publication.binding().equals(catalogBindingOf(baseline.payload()));
    }

    private static CatalogBinding catalogBindingOf(Object payload) {
        if (payload instanceof GraphDocument graph) {
            return graph.catalogBinding();
        }
        if (payload instanceof FunctionSourceDocument source) {
            return source.graph().catalogBinding();
        }
        return null;
    }

    private boolean registerCoreGraphEditorSession(CoreGraphEditorSession session, ServerConnectionToken token) {
        synchronized (serverConnectionGenerationLock) {
            return isCurrentServerConnectionLocked(token) && registerCoreGraphEditorSessionLocked(session, token);
        }
    }

    private boolean registerCoreGraphEditorSessionLocked(CoreGraphEditorSession session, ServerConnectionToken token) {
        if (session == null || token == null) {
            return false;
        }
        ServerResourceLocator resource = session.resource();
        ReSyncResourceType type = resource == null ? null : ReSyncResourceType.byTypeId(resource.resourceType().value());
        if (type == null || !type.isGraph() || !sessionResourceMatches(session, token.serverId(), type, resource.id())
            || !matchesActiveCorePublication(session)) {
            ReSyncFlowClient.traceLifecycle(token.serverId(), "core_session_rejected", "serverId", token.serverId(),
                "resourceKey", (type == null ? "unknown" : type.typeId()) + ":"
                    + (resource == null ? "unknown" : resource.id()),
                "requestId", "session", "mutationId", null, "generation", token.generation(), "authorityEpoch",
                coreGraphUiProjection.authorityEpoch(token.serverId()), "revision", session.revision(), "reason",
                "identity_or_publication");
            return false;
        }
        CoreGraphSessionKey key = new CoreGraphSessionKey(token.serverId(), type, resource.id());
        synchronized (coreGraphEditorSessions) {
            CoreGraphSessionState current = coreGraphEditorSessions.get(key);
            if (current != null && current.session() != session && (current.stale() || current.session().isDirty())) {
                ReSyncFlowClient.traceLifecycle(token.serverId(), "core_session_rejected", "serverId", token.serverId(),
                    "resourceKey", type.typeId() + ":" + resource.id(), "requestId", "session", "mutationId", null,
                    "generation", token.generation(), "authorityEpoch", coreGraphUiProjection.authorityEpoch(
                        token.serverId()), "revision", session.revision(), "reason", "existing_session_dirty_or_stale");
                return false;
            }
            coreGraphEditorSessions.put(key, new CoreGraphSessionState(session, token, false));
        }
        ReSyncFlowClient.traceLifecycle(token.serverId(), "core_session_bound", "serverId", token.serverId(),
            "resourceKey", type.typeId() + ":" + resource.id(), "requestId", "session", "mutationId", null,
            "generation", token.generation(), "authorityEpoch", coreGraphUiProjection.authorityEpoch(token.serverId()),
            "revision", session.revision(), "dirty", session.isDirty());
        return true;
    }

    private boolean sessionResourceMatches(CoreGraphEditorSession session, String serverId,
                                           ReSyncResourceType type, String id) {
        ServerResourceLocator resource = session == null ? null : session.resource();
        return resource != null && serverId.equals(resource.serverId().canonicalText())
            && type.typeId().equals(resource.resourceType().value()) && id.equals(resource.id());
    }

    private boolean matchesActiveCorePublication(CoreGraphEditorSession session) {
        if (session == null || session.resource() == null || session.catalogBinding() == null
            || session.activeAuthoringChecksum() == null) {
            return false;
        }
        CatalogAuthoringPublication publication = activeAuthoringPublication(session.resource()).orElse(null);
        return matchesCurrentCorePublication(session, publication);
    }

    private boolean matchesCurrentCorePublication(CoreGraphEditorSession session,
                                                   CatalogAuthoringPublication publication) {
        if (session == null || session.resource() == null || publication == null) {
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(session.resource().serverId().canonicalText());
        if (flowClient == null || flowClient.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            || flowClient.activeAuthoringPublication().orElse(null) != publication) {
            return false;
        }
        ReSyncCatalogAuthoringProjection.Snapshot snapshot = flowClient.catalogAuthoringProjection().active().orElse(null);
        return snapshot != null && snapshot.publication() == publication
            && matchesCorePublication(session, publication, snapshot.checksum())
            && flowClient.catalogAuthoringProjection().active().orElse(null) == snapshot
            && flowClient.activeAuthoringPublication().orElse(null) == publication
            && flowClient.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            && connectionManager.getFlowClient(session.resource().serverId().canonicalText()) == flowClient;
    }

    private static boolean matchesCorePublication(CoreGraphEditorSession session,
                                                  CatalogAuthoringPublication publication) {
        try {
            return publication != null && matchesCorePublication(session, publication,
                CatalogCachePublicationCodec.authoringPublicationChecksum(publication));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean matchesCorePublication(CoreGraphEditorSession session,
                                                  CatalogAuthoringPublication publication, ContentHash checksum) {
        if (session == null || session.resource() == null || session.catalogBinding() == null
            || session.activeAuthoringChecksum() == null || publication == null) {
            return false;
        }
        try {
            return session.matchesPublication(publication, checksum);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean coreGraphMutationAllowed(CoreGraphEditorSession session, ReSyncFlowClient flowClient,
                                             ReSyncResourceType type, ResourceOperationKind operation) {
        return session != null && flowClient != null && type != null && type.isGraph()
            && operation != null && flowClient.catalogAuthorityAllowsDurableSave()
            && matchesActiveCorePublication(session)
            && supportsCoreGraphMutationTransport(flowClient.getServerId(), operation);
    }

    private boolean supportsCoreGraphMutationTransport(String serverId, ResourceOperationKind operation) {
        if (serverId == null || serverId.isBlank() || operation == null) {
            return false;
        }
        JsonObject capabilities = getServerCapabilities(serverId);
        JsonObject protocol = capabilities != null && capabilities.has("protocolEnvelope")
            && capabilities.get("protocolEnvelope").isJsonObject()
            ? capabilities.getAsJsonObject("protocolEnvelope") : null;
        if (protocol == null || protocol.has("supported") && !protocol.get("supported").getAsBoolean()) {
            return false;
        }
        JsonElement version = protocol.has("genericResourceContractVersion")
            ? protocol.get("genericResourceContractVersion") : protocol.get("resourceContractVersion");
        JsonObject contract = protocol.has("genericResourceContract")
            && protocol.get("genericResourceContract").isJsonObject()
            ? protocol.getAsJsonObject("genericResourceContract") : null;
        if (contract != null && contract.has("version")) {
            version = contract.get("version");
        }
        if (!coreResourceContractVersionAtLeast(version)) {
            return false;
        }
        JsonObject authority = protocol.has("mutationAuthority")
            && protocol.get("mutationAuthority").isJsonObject()
            ? protocol.getAsJsonObject("mutationAuthority") : null;
        if (authority == null || !authority.has("supported") || !authority.get("supported").getAsBoolean()
            || !authority.has("durable") || !authority.get("durable").getAsBoolean()) {
            return false;
        }
        JsonObject operations = protocol.has("resourceOperations")
            && protocol.get("resourceOperations").isJsonObject()
            ? protocol.getAsJsonObject("resourceOperations") : null;
        JsonElement advertised = operations == null ? null : operations.get("mutate");
        String operationName = operation.name().toLowerCase(Locale.ROOT);
        return containsCoreResourceOperation(advertised, operationName)
            || containsCoreResourceOperation(advertised, "resource." + operationName);
    }

    private boolean coreResourceContractVersionAtLeast(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return false;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (!object.has("generation") || !object.has("minor")) {
                return false;
            }
            try {
                int generation = object.get("generation").getAsInt();
                int minor = object.get("minor").getAsInt();
                return generation > 1 || generation == 1 && minor >= 0;
            } catch (RuntimeException exception) {
                return false;
            }
        }
        if (!value.isJsonPrimitive()) {
            return false;
        }
        try {
            String text = value.getAsString();
            int separator = text.indexOf('.');
            int generation = separator < 0 ? Integer.parseInt(text) : Integer.parseInt(text.substring(0, separator));
            int minor = separator < 0 ? 0 : Integer.parseInt(text.substring(separator + 1));
            return generation > 1 || generation == 1 && minor >= 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean containsCoreResourceOperation(JsonElement values, String expected) {
        if (values == null || !values.isJsonArray() || expected == null || expected.isBlank()) {
            return false;
        }
        for (JsonElement value : values.getAsJsonArray()) {
            if (value != null && value.isJsonPrimitive() && expected.equals(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCoreSessionBaseline(CoreGraphEditorSession session,
                                                      CoreGraphUiProjection.Baseline baseline) {
        return matchesCoreSessionBaseline(session, baseline, null);
    }

    private static boolean matchesCoreSessionBaseline(CoreGraphEditorSession session,
                                                      CoreGraphUiProjection.Baseline baseline, CoreBaselineProof proof) {
        if (session == null || baseline == null) {
            return false;
        }
        synchronized (session) {
            if (session.resource() == null || !session.resource().equals(locatorOf(baseline.payload()))
                || session.baselineRevision() != baseline.revision()) {
                return false;
            }
            if (proof != null && proof.matches(session, baseline)) {
                return true;
            }
            if (session.isGraph() && baseline.payload() instanceof GraphDocument graph) {
                GraphDocument saved = session.baselineGraphDocument();
                return saved != null && (saved == graph || saved.checksum().equals(graph.checksum()));
            }
            if (session.isFunction() && baseline.payload() instanceof FunctionSourceDocument source) {
                FunctionSourceDocument saved = session.baselineFunctionSourceDocument();
                return saved != null && (saved == source || saved.checksum().equals(source.checksum()));
            }
            return false;
        }
    }

    public boolean openCoreGraphSession(ServerResourceLocator resource, CoreGraphEditorSession session,
                                        String title, String branchPin) {
        ServerConnectionToken token = registerCoreGraphSession(resource, session, "open");
        if (token == null) {
            return false;
        }
        ReSyncResourceType type = ReSyncResourceType.byTypeId(resource.resourceType().value());
        String serverId = resource.serverId().canonicalText();
        String documentTitle = title == null || title.isBlank() ? resource.id() : title;
        String documentKey = ReSyncProjectMetadata.resourceKey(type.typeId(), resource.id());
        if (openExistingStudioScreen(serverId,
            screen -> screen.openWorkspaceCoreEditor(session, documentTitle, branchPin))) {
            ReSyncFlowClient.traceLifecycle(serverId, "core_session_opened", "serverId", serverId, "resourceKey",
                type.typeId() + ":" + resource.id(), "requestId", "open", "mutationId", null, "generation",
                token.generation(), "authorityEpoch", coreGraphUiProjection.authorityEpoch(serverId), "revision",
                session.revision(), "target", "existing_studio");
            return true;
        }
        openStudioDocument(serverId, documentKey,
            screen -> screen.openWorkspaceCoreEditor(session, documentTitle, branchPin));
        ReSyncFlowClient.traceLifecycle(serverId, "core_session_opened", "serverId", serverId, "resourceKey",
            type.typeId() + ":" + resource.id(), "requestId", "open", "mutationId", null, "generation",
            token.generation(), "authorityEpoch", coreGraphUiProjection.authorityEpoch(serverId), "revision",
            session.revision(), "target", "pending_studio");
        return true;
    }

    public boolean bindCoreGraphSession(ServerResourceLocator resource, CoreGraphEditorSession session, String title) {
        ServerConnectionToken token = registerCoreGraphSession(resource, session, "bind");
        if (token == null) {
            return false;
        }
        ReSyncResourceType type = ReSyncResourceType.byTypeId(resource.resourceType().value());
        String serverId = resource.serverId().canonicalText();
        FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
        if (studioScreen != null) {
            ScreenManager.getInstance().execute(() -> {
                boolean current = isCurrentServerConnection(token)
                    && isCurrentCoreGraphEditorSession(serverId, type, resource.id(), session)
                    && FlowEditorScreen.getStudioScreen(serverId) == studioScreen;
                ReSyncFlowClient.traceLifecycle(serverId, current ? "core_session_bind_applied"
                    : "core_session_bind_dropped", "serverId", serverId, "resourceKey",
                    type.typeId() + ":" + resource.id(), "operation", "bind", "requestId", "bind", "mutationId",
                    null, "generation", token.generation(), "authorityEpoch",
                    coreGraphUiProjection.authorityEpoch(serverId), "revision", session.revision(), "queued", true,
                    "reason", current ? "current" : "stale_session_or_studio");
                if (current) {
                    studioScreen.bindWorkspaceCoreEditor(session, title);
                }
            });
        }
        ReSyncFlowClient.traceLifecycle(serverId, "core_session_bound_background", "serverId", serverId,
            "resourceKey", type.typeId() + ":" + resource.id(), "requestId", "bind", "mutationId", null,
            "generation", token.generation(), "authorityEpoch", coreGraphUiProjection.authorityEpoch(serverId),
            "revision", session.revision(), "target", studioScreen != null ? "existing_studio" : "session_only");
        return true;
    }

    private ServerConnectionToken registerCoreGraphSession(ServerResourceLocator resource,
                                                           CoreGraphEditorSession session, String operation) {
        if (resource == null || session == null || !resource.equals(session.resource())) {
            return null;
        }
        ReSyncResourceType type = ReSyncResourceType.byTypeId(resource.resourceType().value());
        if (type == null || !type.isGraph()) {
            return null;
        }
        ServerConnectionToken token = captureServerConnectionToken(resource.serverId().canonicalText());
        if (!registerCoreGraphEditorSession(session, token)) {
            ReSyncFlowClient.traceLifecycle(resource.serverId().canonicalText(), "core_session_" + operation + "_rejected",
                "serverId", resource.serverId().canonicalText(), "resourceKey", type.typeId() + ":" + resource.id(),
                "requestId", operation, "mutationId", null, "generation", token != null ? token.generation() : -1L,
                "authorityEpoch", coreGraphUiProjection.authorityEpoch(resource.serverId().canonicalText()), "revision",
                session.revision());
            return null;
        }
        return token;
    }

    public boolean openCoreGraphSession(CoreGraphEditorSession session, String title, String branchPin) {
        return session != null && openCoreGraphSession(session.resource(), session, title, branchPin);
    }

    public boolean saveGraph(String serverId, ReSyncResourceType type, CoreGraphEditorSession session) {
        return saveCoreGraph(serverId, type, session);
    }

    public boolean saveCoreGraph(String serverId, ReSyncResourceType type, CoreGraphEditorSession session) {
        return saveCoreGraph(serverId, type, session, null);
    }

    public boolean saveCoreGraph(String serverId, ReSyncResourceType type, CoreGraphEditorSession session,
                                 DesignerSaveNotifications.SaveTicket ticket) {
        if (session == null || serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || session.resource() == null || !sessionResourceMatches(session, serverId, type, session.resource().id())) {
            failSaveTicket(ticket, "Core Save Unavailable");
            return false;
        }
        String id = session.resource().id();
        if (!acceptSaveTicket(ticket, serverId, type, id)) return false;
        if (!isCurrentCoreGraphEditorSession(serverId, type, session.resource().id(), session)) {
            ReSyncFlowClient.traceLifecycle(serverId, "save_rejected", "serverId", serverId, "resourceKey",
                type.typeId() + ":" + id, "requestId", ticket != null ? ticket.requestId() : "save", "mutationId",
                ticket != null ? ticket.mutationId() : null, "generation", -1L,
                "authorityEpoch", coreGraphUiProjection.authorityEpoch(serverId), "revision", session.revision(),
                "reason", "core_session_expired");
            failSave(serverId, type, id, ticket, "Core Editor Session Expired");
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null || !flowClient.isConnectedState()) {
            ReSyncFlowClient.traceLifecycle(serverId, "save_rejected", "serverId", serverId, "resourceKey",
                type.typeId() + ":" + id, "requestId", ticket != null ? ticket.requestId() : "save", "mutationId",
                ticket != null ? ticket.mutationId() : null, "generation",
                flowClient != null ? flowClient.activeTransportGeneration() : -1L, "authorityEpoch",
                coreGraphUiProjection.authorityEpoch(serverId), "revision", session.revision(), "reason", "offline");
            failSave(serverId, type, id, ticket, "ReSync Offline");
            return false;
        }
        ResourceOperationKind operation = session.baselineRevision() == 0L
            ? ResourceOperationKind.CREATE : ResourceOperationKind.SAVE;
        if (!coreGraphMutationAllowed(session, flowClient, type, operation)) {
            ReSyncFlowClient.traceLifecycle(serverId, "save_rejected", "serverId", serverId, "resourceKey",
                type.typeId() + ":" + id, "requestId", ticket != null ? ticket.requestId() : "save", "mutationId",
                ticket != null ? ticket.mutationId() : null, "generation", flowClient.activeTransportGeneration(),
                "authorityEpoch", coreGraphUiProjection.authorityEpoch(serverId), "revision", session.revision(),
                "reason", "core_mutation_unavailable");
            failSave(serverId, type, id, ticket,
                matchesActiveCorePublication(session) ? "Core Save Unavailable" : "Core Editor Session Expired");
            return false;
        }
        boolean admitted = flowClient.sendResourceSave(type, session.payload(), ticket);
        ReSyncFlowClient.traceLifecycle(serverId, admitted ? "save_admitted" : "save_rejected", "serverId", serverId,
            "resourceKey", type.typeId() + ":" + id, "operation", operation, "requestId",
            ticket != null ? ticket.requestId() : "save", "mutationId", ticket != null ? ticket.mutationId() : null,
            "generation", flowClient.activeTransportGeneration(), "authorityEpoch",
            flowClient.resourceRevisionReconciler().authorityEpoch(serverId), "revision", session.revision());
        return admitted;
    }

    private CoreGraphSaveSettlement settleCoreGraphEditorSessionSaveAuthoritative(
        ServerConnectionToken saveToken,
        String serverId, ReSyncResourceType type, String id, UUID requestId, UUID mutationId,
        long expectedRevision, Object authoritativePayload, long revision, ContentHash payloadHash,
        ContentHash submittedPayloadHash) {
        if (saveToken == null) {
            return rejectCoreGraphSaveSettlement(null, serverId, type, id, requestId, mutationId, expectedRevision,
                revision, CoreGraphSaveRejection.MISSING_SAVE_TOKEN);
        }
        if (serverId == null || serverId.isBlank()) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.MISSING_SERVER_IDENTITY);
        }
        if (type == null || !type.isGraph()) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.UNSUPPORTED_RESOURCE_TYPE);
        }
        if (id == null || id.isBlank()) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.MISSING_RESOURCE_IDENTITY);
        }
        if (requestId == null) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, null, mutationId, expectedRevision,
                revision, CoreGraphSaveRejection.MISSING_REQUEST_IDENTITY);
        }
        if (mutationId == null) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, null, expectedRevision,
                revision, CoreGraphSaveRejection.MISSING_MUTATION_IDENTITY);
        }
        if (expectedRevision < 0L) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.INVALID_EXPECTED_REVISION);
        }
        if (revision < 1L) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.INVALID_AUTHORITATIVE_REVISION);
        }
        if (authoritativePayload == null) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.MISSING_AUTHORITATIVE_PAYLOAD);
        }
        if (payloadHash == null) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.MISSING_AUTHORITATIVE_PAYLOAD_HASH);
        }
        if (submittedPayloadHash == null) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.MISSING_SUBMITTED_CHECKSUM);
        }
        if (saveToken.source() == null) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.MISSING_SAVE_SOURCE);
        }
        if (saveToken.generation() <= 0L) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.INVALID_SAVE_GENERATION);
        }
        if (!serverId.equals(saveToken.serverId())) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.SAVE_TOKEN_SERVER_MISMATCH);
        }
        ServerResourceLocator payloadResource = locatorOf(authoritativePayload);
        if (!sessionPayloadType(type, authoritativePayload)) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.AUTHORITATIVE_PAYLOAD_KIND_MISMATCH);
        }
        if (payloadResource == null) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.MISSING_AUTHORITATIVE_PAYLOAD_RESOURCE);
        }
        if (!serverId.equals(payloadResource.serverId().canonicalText())) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.AUTHORITATIVE_PAYLOAD_SERVER_MISMATCH);
        }
        if (!type.typeId().equals(payloadResource.resourceType().value())) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.AUTHORITATIVE_PAYLOAD_TYPE_MISMATCH);
        }
        if (!id.equals(payloadResource.id())) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.AUTHORITATIVE_PAYLOAD_IDENTITY_MISMATCH);
        }
        if (payloadRevision(authoritativePayload) != revision) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.AUTHORITATIVE_PAYLOAD_REVISION_MISMATCH);
        }
        CoreGraphSessionKey key = new CoreGraphSessionKey(serverId, type, id);
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.CURRENT_SOURCE_MISSING);
        }
        if (flowClient != saveToken.source()) {
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, CoreGraphSaveRejection.SAVE_SOURCE_REPLACED);
        }
        CoreGraphSaveSettlement settlement;
        CoreGraphEditorSession session;
        synchronized (serverConnectionGenerationLock) {
            if (!isCurrentServerConnectionGenerationLocked(saveToken)) {
                return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                    expectedRevision, revision, CoreGraphSaveRejection.SAVE_GENERATION_RETIRED);
            }
            if (connectionManager.getFlowClient(serverId) != saveToken.source()) {
                return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                    expectedRevision, revision, CoreGraphSaveRejection.SAVE_SOURCE_REPLACED);
            }
            if (!connectionManager.isFlowClientConnected(serverId)) {
                return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                    expectedRevision, revision, CoreGraphSaveRejection.SAVE_SOURCE_DISCONNECTED);
            }
            synchronized (coreGraphEditorSessions) {
                CoreGraphSessionState state = coreGraphEditorSessions.get(key);
                if (state == null) {
                    return CoreGraphSaveSettlement.NO_OPEN_SESSION;
                }
                session = state.session();
                if (session == null) {
                    return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                        expectedRevision, revision, CoreGraphSaveRejection.MISSING_EDITOR_SESSION);
                }
                if (!sessionResourceMatches(session, serverId, type, id)) {
                    return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                        expectedRevision, revision, CoreGraphSaveRejection.EDITOR_SESSION_RESOURCE_MISMATCH);
                }
                if (state.stale() || state.token() == null || state.token().source() != saveToken.source()
                    || state.token().generation() != saveToken.generation() || !matchesActiveCorePublication(session)) {
                    coreGraphEditorSessions.put(key, state.staleState());
                    settlement = CoreGraphSaveSettlement.REHYDRATION_REQUIRED;
                } else {
                    settlement = settleCoreGraphEditorSession(session, authoritativePayload, expectedRevision,
                        revision, submittedPayloadHash);
                    if (settlement.retryable()) {
                        coreGraphEditorSessions.put(key, state.staleState());
                    }
                }
            }
        }
        if (settlement == CoreGraphSaveSettlement.REJECTED) {
            CoreGraphSaveRejection rejection = session.baselineRevision() > revision
                ? CoreGraphSaveRejection.ACKNOWLEDGEMENT_OLDER_THAN_EDITOR_BASELINE
                : CoreGraphSaveRejection.EDITOR_SESSION_PAYLOAD_KIND_MISMATCH;
            return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id, requestId, mutationId,
                expectedRevision, revision, rejection);
        }
        String outcome = settlement == CoreGraphSaveSettlement.REHYDRATION_REQUIRED
            ? "rehydration_required" : settlement == CoreGraphSaveSettlement.REBASED ? "rebased" : "settled";
        traceCoreGraphSessionSettlement(serverId, type, id, requestId, mutationId, session, revision,
            saveToken.generation(), outcome, settlement.editorUpdated());
        if (settlement.retryable()) {
            hydrateCoreGraphProjection(serverId, type, id);
        }
        return settlement;
    }

    private CoreGraphSaveSettlement settleCoreGraphEditorSession(CoreGraphEditorSession session,
                                                                 Object authoritativePayload,
                                                                 long expectedRevision, long revision,
                                                                 ContentHash submittedPayloadHash) {
        synchronized (session) {
            if (session.baselineRevision() == revision && sameCoreSessionBaseline(session, authoritativePayload)) {
                return session.isDirty() ? CoreGraphSaveSettlement.REBASED : CoreGraphSaveSettlement.SETTLED;
            }
            if (session.baselineRevision() > revision) {
                return CoreGraphSaveSettlement.REJECTED;
            }
            if (session.baselineRevision() != expectedRevision) {
                return CoreGraphSaveSettlement.REHYDRATION_REQUIRED;
            }
            try {
                if (!submittedPayloadHash.equals(session.checksum())) {
                    if (!session.isDirty()) {
                        return CoreGraphSaveSettlement.REHYDRATION_REQUIRED;
                    }
                    if (authoritativePayload instanceof GraphDocument graph && session.isGraph()) {
                        session.rebase(graph);
                        return CoreGraphSaveSettlement.REBASED;
                    }
                    if (authoritativePayload instanceof FunctionSourceDocument source && session.isFunction()) {
                        session.rebase(source);
                        return CoreGraphSaveSettlement.REBASED;
                    }
                    return CoreGraphSaveSettlement.REJECTED;
                }
                if (authoritativePayload instanceof GraphDocument graph && session.isGraph()) {
                    session.markSaved(graph);
                    return CoreGraphSaveSettlement.SETTLED;
                }
                if (authoritativePayload instanceof FunctionSourceDocument source && session.isFunction()) {
                    session.markSaved(source);
                    return CoreGraphSaveSettlement.SETTLED;
                }
            } catch (RuntimeException exception) {
                return CoreGraphSaveSettlement.REHYDRATION_REQUIRED;
            }
            return CoreGraphSaveSettlement.REJECTED;
        }
    }

    private void traceCoreGraphSessionSettlement(String serverId, ReSyncResourceType type, String id, UUID requestId,
                                                  UUID mutationId, CoreGraphEditorSession session, long revision,
                                                  long generation, String outcome, boolean sessionMarked) {
        ReSyncFlowClient.traceLifecycle(serverId, "core_session_save_settled", "serverId", serverId, "resourceKey",
            type.typeId() + ":" + id, "requestId", requestId, "mutationId", mutationId, "generation",
            generation, "authorityEpoch", coreGraphUiProjection.authorityEpoch(serverId),
            "revision", revision, "sessionMarked", sessionMarked, "dirty", session.isDirty(), "baselineRevision",
            session.baselineRevision(), "sessionRevision", session.revision(), "outcome", outcome);
    }

    private CoreGraphSaveSettlement rejectCoreGraphSaveSettlement(
        ServerConnectionToken saveToken, String serverId, ReSyncResourceType type, String id, UUID requestId,
        UUID mutationId, long expectedRevision, long revision, CoreGraphSaveRejection rejection) {
        String traceServerId = serverId != null && !serverId.isBlank() ? serverId : "unknown";
        String resourceType = type != null ? type.typeId() : "unknown";
        String resourceId = id != null && !id.isBlank() ? id : "unknown";
        ReSyncFlowClient.traceLifecycle(traceServerId, "core_session_save_rejected", "serverId", traceServerId,
            "resourceKey", resourceType + ":" + resourceId, "requestId", requestId, "mutationId", mutationId,
            "generation", saveToken != null ? saveToken.generation() : -1L, "authorityEpoch",
            serverId != null && !serverId.isBlank() ? coreGraphUiProjection.authorityEpoch(serverId) : -1L,
            "expectedRevision", expectedRevision, "revision", revision, "reason", rejection.diagnosticCode());
        return CoreGraphSaveSettlement.REJECTED;
    }

    public CoreGraphSaveSettlement settleCoreGraphEditorSessionSaveAuthoritative(
        ServerConnectionToken saveToken, ReSyncFlowClient.CoreGraphMutationResult result,
        ContentHash submittedPayloadHash, Object authoritativePayload) {
        if (saveToken == null) {
            return rejectCoreGraphSaveSettlement(null, result, null, CoreGraphSaveRejection.MISSING_SAVE_TOKEN);
        }
        if (result == null) {
            return rejectCoreGraphSaveSettlement(saveToken, null, null,
                CoreGraphSaveRejection.MISSING_MUTATION_RESULT);
        }
        if (submittedPayloadHash == null) {
            return rejectCoreGraphSaveSettlement(saveToken, result, null,
                CoreGraphSaveRejection.MISSING_SUBMITTED_CHECKSUM);
        }
        if (authoritativePayload == null) {
            return rejectCoreGraphSaveSettlement(saveToken, result, null,
                CoreGraphSaveRejection.MISSING_AUTHORITATIVE_PAYLOAD);
        }
        if (!result.accepted()) {
            return rejectCoreGraphSaveSettlement(saveToken, result, null,
                CoreGraphSaveRejection.MUTATION_NOT_ACCEPTED);
        }
        if (result.operation() != ResourceOperationKind.CREATE && result.operation() != ResourceOperationKind.SAVE) {
            return rejectCoreGraphSaveSettlement(saveToken, result, null,
                CoreGraphSaveRejection.UNSUPPORTED_MUTATION_OPERATION);
        }
        if (result.deleted()) {
            return rejectCoreGraphSaveSettlement(saveToken, result, null,
                CoreGraphSaveRejection.ACKNOWLEDGED_RESOURCE_DELETED);
        }
        if (result.requestPayloadHash() == null) {
            return rejectCoreGraphSaveSettlement(saveToken, result, null,
                CoreGraphSaveRejection.MISSING_REQUEST_PAYLOAD_HASH);
        }
        if (result.authoritativeMutationId() == null) {
            return rejectCoreGraphSaveSettlement(saveToken, result, null,
                CoreGraphSaveRejection.MISSING_AUTHORITATIVE_MUTATION_IDENTITY);
        }
        if (!result.mutationId().equals(result.authoritativeMutationId())) {
            return rejectCoreGraphSaveSettlement(saveToken, result, null,
                CoreGraphSaveRejection.AUTHORITATIVE_MUTATION_IDENTITY_MISMATCH);
        }
        ReSyncResourceType type = ReSyncResourceType.byTypeId(result.resource().resourceType().value());
        if (type == null || !type.isGraph()) {
            return rejectCoreGraphSaveSettlement(saveToken, result, type,
                CoreGraphSaveRejection.UNSUPPORTED_RESOURCE_TYPE);
        }
        if (!sessionPayloadType(type, authoritativePayload)) {
            return rejectCoreGraphSaveSettlement(saveToken, result, type,
                CoreGraphSaveRejection.AUTHORITATIVE_PAYLOAD_KIND_MISMATCH);
        }
        return settleCoreGraphEditorSessionSaveAuthoritative(saveToken,
            result.resource().serverId().canonicalText(), type,
            result.resource().id(), result.requestId(), result.mutationId(),
            result.operation() == ResourceOperationKind.CREATE ? 0L : result.expectedRevision(),
            authoritativePayload, result.revision(), result.payloadHash(), submittedPayloadHash);
    }

    private CoreGraphSaveSettlement rejectCoreGraphSaveSettlement(ServerConnectionToken saveToken,
                                                                   ReSyncFlowClient.CoreGraphMutationResult result,
                                                                   ReSyncResourceType type,
                                                                   CoreGraphSaveRejection rejection) {
        ServerResourceLocator resource = result != null ? result.resource() : null;
        String serverId = resource != null ? resource.serverId().canonicalText() : null;
        String id = resource != null ? resource.id() : null;
        return rejectCoreGraphSaveSettlement(saveToken, serverId, type, id,
            result != null ? result.requestId() : null, result != null ? result.mutationId() : null,
            result != null ? result.expectedRevision() : -1L, result != null ? result.revision() : -1L, rejection);
    }

    private boolean sameCoreSessionBaseline(CoreGraphEditorSession session, Object authoritativePayload) {
        if (session == null || authoritativePayload == null) {
            return false;
        }
        if (session.isGraph() && authoritativePayload instanceof GraphDocument graph
            && session.baselineGraphDocument() != null) {
            return session.baselineGraphDocument().checksum().equals(graph.checksum());
        }
        if (session.isFunction() && authoritativePayload instanceof FunctionSourceDocument source
            && session.baselineFunctionSourceDocument() != null) {
            return session.baselineFunctionSourceDocument().checksum().equals(source.checksum());
        }
        return false;
    }

    private boolean sessionPayloadType(ReSyncResourceType type, Object payload) {
        return payload instanceof FunctionSourceDocument && type == ReSyncResourceType.FUNCTION
            || payload instanceof GraphDocument && type != ReSyncResourceType.FUNCTION;
    }

    private boolean sessionResourceMatches(ServerResourceLocator resource, String serverId,
                                            ReSyncResourceType type, String id) {
        return resource != null && serverId.equals(resource.serverId().canonicalText())
            && type.typeId().equals(resource.resourceType().value()) && id.equals(resource.id());
    }

    private long payloadRevision(Object payload) {
        if (payload instanceof GraphDocument graph) {
            return graph.revision();
        }
        if (payload instanceof FunctionSourceDocument source) {
            return source.graph().revision();
        }
        return -1L;
    }

    public boolean discardCoreGraphSession(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank()) {
            return false;
        }
        CoreGraphSessionKey key = new CoreGraphSessionKey(serverId, type, id);
        CoreGraphSessionState state = coreGraphEditorSessions.get(key);
        if (state == null || state.session() == null) {
            return false;
        }
        if (state.session().baselineRevision() == 0L) {
            if (!coreGraphEditorSessions.remove(key, state)) {
                return false;
            }
            coreGraphHydrations.remove(key);
            ServerResourceLocator resource = state.session().resource();
            coreGraphDocumentAuthoring.discard(resource);
            removeCoreTemplateIntent(resource);
            return true;
        }
        try {
            if (state.session().isGraph()) {
                state.session().markSaved(state.session().baselineGraphDocument());
            } else {
                state.session().markSaved(state.session().baselineFunctionSourceDocument());
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void clearCoreGraphEditorSessions(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        coreGraphHydrations.removeIf(key -> serverId.equals(key.serverId()));
        synchronized (coreGraphEditorSessions) {
            coreGraphEditorSessions.keySet().removeIf(key -> serverId.equals(key.serverId()));
        }
        pendingCoreTemplateIntents.entrySet().removeIf(entry -> entry.getValue() != null
            && entry.getValue().resource() != null
            && serverId.equals(entry.getValue().resource().serverId().canonicalText()));
        preparingCoreTemplateIntents.keySet().removeIf(resource -> resource != null
            && serverId.equals(resource.serverId().canonicalText()));
        deferredCoreTemplateIntents.keySet().removeIf(resource -> resource != null
            && serverId.equals(resource.serverId().canonicalText()));
        coreTemplateResponses.entrySet().removeIf(entry -> entry.getValue() != null
            && entry.getValue().resource() != null
            && serverId.equals(entry.getValue().resource().serverId().canonicalText()));
    }

    private void markCoreGraphEditorSessionsStale(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        synchronized (coreGraphEditorSessions) {
            coreGraphEditorSessions.replaceAll((key, state) -> serverId.equals(key.serverId())
                ? state.staleState() : state);
        }
        pendingCoreTemplateIntents.entrySet().removeIf(entry -> entry.getValue() != null
            && entry.getValue().resource() != null
            && serverId.equals(entry.getValue().resource().serverId().canonicalText()));
        preparingCoreTemplateIntents.keySet().removeIf(resource -> resource != null
            && serverId.equals(resource.serverId().canonicalText()));
        deferredCoreTemplateIntents.keySet().removeIf(resource -> resource != null
            && serverId.equals(resource.serverId().canonicalText()));
        coreTemplateResponses.entrySet().removeIf(entry -> entry.getValue() != null
            && entry.getValue().resource() != null
            && serverId.equals(entry.getValue().resource().serverId().canonicalText()));
    }

    private void removeCoreTemplateIntent(ServerResourceLocator resource) {
        if (resource == null) {
            return;
        }
        pendingCoreTemplateIntents.entrySet().removeIf(entry -> entry.getValue() != null
            && resource.equals(entry.getValue().resource()));
        preparingCoreTemplateIntents.remove(resource);
        deferredCoreTemplateIntents.remove(resource);
        coreTemplateResponses.entrySet().removeIf(entry -> entry.getValue() != null
            && resource.equals(entry.getValue().resource()));
    }

    public Optional<GraphDraft> coreGraphDraft(ServerResourceLocator resource) {
        return coreGraphDocumentAuthoring.draft(resource);
    }

    private static boolean activeCatalogKey(ReSyncFlowClient flowClient, CatalogCacheKey expectedKey) {
        return expectedKey != null
            && flowClient.catalogPublicationProjection().active()
                .map(snapshot -> snapshot.publication().key().equals(expectedKey)).orElse(false)
            && flowClient.catalogAuthoringProjection().active()
                .map(snapshot -> snapshot.key().equals(expectedKey)).orElse(false);
    }

    private Optional<CatalogAuthoringPublication> activeAuthoringPublication(ServerResourceLocator resource) {
        if (resource == null) {
            return Optional.empty();
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(resource.serverId().canonicalText());
        if (flowClient == null || flowClient.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
            return Optional.empty();
        }
        return flowClient.activeAuthoringPublication();
    }

    void notifyCoreGraphProjectionCommitted(String serverId) {
        resumeCreationTransactions(serverId);
        if (!hasPendingCreationForServer(serverId)) {
            refreshFlowWorkspace(serverId, null, true);
        }
    }

    private void cacheCustomContentInternal(String serverId, CustomContentDefinition content, boolean notify) {
        boolean loadedContent = content != null && content.getId() != null && customContentStore.get(serverId, content.getId()) != null;
        customContentStore.cache(serverId, content);
        boolean catalogChanged = !loadedContent;
        if (catalogChanged) {
            invalidateProjectCatalog(serverId);
        }
        if (notify) {
            refreshStudioWorkspace(serverId, catalogChanged);
        }
    }

    public void saveGui(String serverId, GuiDefinition gui) {
        saveGui(serverId, gui, null);
    }

    public void saveGui(String serverId, GuiDefinition gui, DesignerSaveNotifications.SaveTicket ticket) {
        if (serverId == null || gui == null || gui.getId() == null) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        if (!acceptSaveTicket(ticket, serverId, ReSyncResourceType.GUI, gui.getId())) return;
        boolean enabled = guiStore.getBoolean(serverId, gui.getId(), gui.isEnabled(), GuiDefinition::isEnabled);
        SyncedResourceCache.SaveLease<GuiDefinition> lease = guiStore.putInDraft(serverId, gui, draft -> draft.setEnabled(enabled));
        guiStore.putNameIfAbsent(serverId, lease.resourceId(), gui.getTitle() != null ? gui.getTitle() : lease.resourceId());
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            guiStore.markSaving(serverId, lease.resourceId());
            studioFullEditorSession.markSaved(serverId, ReSyncResourceDragPayload.GUI, lease.resourceId());
            if (ticket != null) {
                if (!flowClient.sendResourceSave(ReSyncResourceType.GUI, lease, ticket) && lease.isCurrent())
                    guiStore.markFailed(serverId, lease.resourceId());
            } else if (!flowClient.sendResourceSave(ReSyncResourceType.GUI, lease) && lease.isCurrent())
                guiStore.markFailed(serverId, lease.resourceId());
        } else {
            failSave(serverId, ReSyncResourceType.GUI, lease.resourceId(), ticket, "ReSync Offline");
        }
    }

    public void cacheGui(String serverId, GuiDefinition gui) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        guiStore.cache(serverId, gui);
        if (gui != null && gui.getId() != null) {
            refreshStudioWorkspace(serverId);
        }
    }

    public void markFlowSaved(String serverId, ReSyncResourceType type, String flowId) {
        if (!synchronizeAuthorityEpoch(serverId)) {
            return;
        }
        if (type != null && type.isGraph()) {
            hydrateCoreGraphProjection(serverId, type, flowId);
            if (coreGraphUiProjection.authoritative(serverId, type, flowId)) {
                refreshFlowWorkspace(serverId, flowId, false);
                return;
            }
        }
        flowStore.markSaved(serverId, type, flowId);
        refreshFlowWorkspace(serverId, flowId, false);
    }

    public void markFlowSaved(String serverId, ReSyncResourceType type, String flowId, long revision, String hash) {
        if (type != null && type.isGraph()) {
            hydrateCoreGraphProjection(serverId, type, flowId);
            if (coreGraphUiProjection.authoritative(serverId, type, flowId)) {
                refreshFlowWorkspace(serverId, flowId, false);
                return;
            }
        }
        FlowGraph graph = flowStore.getFromDraft(serverId, type, flowId);
        if (graph == null) {
            graph = flowStore.get(serverId, type, flowId);
        }
        if (graph != null) {
            graph.setResourceRevision(revision);
            graph.setResourceHash(hash);
        }
        markFlowSaved(serverId, type, flowId);
    }

    public void markGuiSaved(String serverId, String guiId) {
        if (!synchronizeAuthorityEpoch(serverId)) {
            return;
        }
        guiStore.markSaved(serverId, guiId);
    }

    public void saveScoreboard(String serverId, ScoreboardDefinition scoreboard) {
        saveScoreboard(serverId, scoreboard, null);
    }

    public void saveScoreboard(String serverId, ScoreboardDefinition scoreboard, DesignerSaveNotifications.SaveTicket ticket) {
        if (serverId == null || scoreboard == null || scoreboard.getId() == null) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        if (!acceptSaveTicket(ticket, serverId, ReSyncResourceType.SCOREBOARD, scoreboard.getId())) return;
        boolean enabled = scoreboardStore.getBoolean(serverId, scoreboard.getId(), scoreboard.isEnabled(), ScoreboardDefinition::isEnabled);
        SyncedResourceCache.SaveLease<ScoreboardDefinition> lease = scoreboardStore.putInDraft(serverId, scoreboard, draft -> draft.setEnabled(enabled));
        scoreboardStore.putNameIfAbsent(serverId, lease.resourceId(), scoreboard.getTitle() != null ? scoreboard.getTitle() : lease.resourceId());
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            scoreboardStore.markSaving(serverId, lease.resourceId());
            if (ticket != null) {
                if (!flowClient.sendResourceSave(ReSyncResourceType.SCOREBOARD, lease, ticket) && lease.isCurrent())
                    scoreboardStore.markFailed(serverId, lease.resourceId());
            } else if (!flowClient.sendResourceSave(ReSyncResourceType.SCOREBOARD, lease) && lease.isCurrent())
                scoreboardStore.markFailed(serverId, lease.resourceId());
        } else {
            failSave(serverId, ReSyncResourceType.SCOREBOARD, lease.resourceId(), ticket, "ReSync Offline");
        }
    }

    public void cacheScoreboard(String serverId, ScoreboardDefinition scoreboard) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        scoreboardStore.cache(serverId, scoreboard);
        if (scoreboard != null && scoreboard.getId() != null) {
            refreshStudioWorkspace(serverId);
        }
    }

    public void markScoreboardSaved(String serverId, String scoreboardId) {
        if (!synchronizeAuthorityEpoch(serverId)) {
            return;
        }
        scoreboardStore.markSaved(serverId, scoreboardId);
    }

    public void saveTab(String serverId, TabDefinition tab) {
        saveTab(serverId, tab, null);
    }

    public void saveTab(String serverId, TabDefinition tab, DesignerSaveNotifications.SaveTicket ticket) {
        if (serverId == null || tab == null || tab.getId() == null) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        if (!acceptSaveTicket(ticket, serverId, ReSyncResourceType.TAB, tab.getId())) return;
        boolean enabled = tabStore.getBoolean(serverId, tab.getId(), tab.isEnabled(), TabDefinition::isEnabled);
        SyncedResourceCache.SaveLease<TabDefinition> lease = tabStore.putInDraft(serverId, tab, draft -> draft.setEnabled(enabled));
        tabStore.putNameIfAbsent(serverId, lease.resourceId(), lease.resourceId());
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            tabStore.markSaving(serverId, lease.resourceId());
            if (ticket != null) {
                if (!flowClient.sendResourceSave(ReSyncResourceType.TAB, lease, ticket) && lease.isCurrent())
                    tabStore.markFailed(serverId, lease.resourceId());
            } else if (!flowClient.sendResourceSave(ReSyncResourceType.TAB, lease) && lease.isCurrent())
                tabStore.markFailed(serverId, lease.resourceId());
        } else {
            failSave(serverId, ReSyncResourceType.TAB, lease.resourceId(), ticket, "ReSync Offline");
        }
    }

    public void cacheTab(String serverId, TabDefinition tab) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        tabStore.cache(serverId, tab);
        if (tab != null && tab.getId() != null) {
            refreshStudioWorkspace(serverId);
        }
    }

    public void markTabSaved(String serverId, String tabId) {
        if (!synchronizeAuthorityEpoch(serverId)) {
            return;
        }
        tabStore.markSaved(serverId, tabId);
    }

    public void saveCustomContent(String serverId, CustomContentDefinition content) {
        saveCustomContent(serverId, content, null);
    }

    public void saveCustomContent(String serverId, CustomContentDefinition content, DesignerSaveNotifications.SaveTicket ticket) {
        if (serverId == null || content == null || content.getId() == null) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        if (!acceptSaveTicket(ticket, serverId, ReSyncResourceType.CUSTOM_CONTENT, content.getId())) return;
        boolean enabled = customContentStore.getBoolean(serverId, content.getId(), content.isEnabled(), CustomContentDefinition::isEnabled);
        SyncedResourceCache.SaveLease<CustomContentDefinition> lease = customContentStore.putInDraft(serverId, content, draft -> {
            draft.setEnabled(enabled);
            if (draft.getGraph() != null) {
                draft.getGraph().setEnabled(enabled);
            }
        });
        customContentStore.putNameIfAbsent(serverId, lease.resourceId(), content.getDisplayName() != null ? content.getDisplayName() : lease.resourceId());
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            customContentStore.markSaving(serverId, lease.resourceId());
            if (ticket != null) {
                if (!flowClient.sendResourceSave(ReSyncResourceType.CUSTOM_CONTENT, lease, ticket) && lease.isCurrent())
                    customContentStore.markFailed(serverId, lease.resourceId());
            } else if (!flowClient.sendResourceSave(ReSyncResourceType.CUSTOM_CONTENT, lease) && lease.isCurrent())
                customContentStore.markFailed(serverId, lease.resourceId());
        } else {
            failSave(serverId, ReSyncResourceType.CUSTOM_CONTENT, lease.resourceId(), ticket, "ReSync Offline");
        }
        invalidateCustomContentOptionCatalogs(serverId);
    }

    public boolean saveCustomContent(String serverId, CustomContentDefinition content,
                                     DesignerSaveNotifications.SaveTicket ticket, CustomContentAuthority authority) {
        if (content == null || authority == null || ticket == null
            || !Objects.equals(serverId, authority.result().serverId())
            || !Objects.equals(content.getId(), authority.result().resourceId())
            || !isCurrentCustomContentAuthority(authority)
            || !acceptSaveTicket(ticket, serverId, ReSyncResourceType.CUSTOM_CONTENT, content.getId())) {
            failSaveTicket(ticket, "Content Changed Before Save");
            return false;
        }
        SyncedResourceCache.SaveLease<CustomContentDefinition> lease = customContentStore.putInDraftIfGenerationLease(
            serverId, content, authority.generation());
        if (lease == null) {
            failSaveTicket(ticket, "Content Changed Before Save");
            return false;
        }
        JsonObject preservedPayload = authority.result().payload().deepCopy();
        JsonObject updatedPayload = JsonParser.parseString(lease.serialize(FlowSerializer::serializeCustomContent)).getAsJsonObject();
        updatedPayload.entrySet().forEach(entry -> preservedPayload.add(entry.getKey(), entry.getValue()));
        String payload = preservedPayload.toString();
        ContentHash hash = creationPayloadHash(payload);
        if (hash == null) {
            failSave(serverId, ReSyncResourceType.CUSTOM_CONTENT, content.getId(), ticket, "Save Snapshot Rejected");
            return false;
        }
        customContentStore.markSaving(serverId, content.getId());
        boolean accepted = authority.source().sendPreparedResourceSave(ReSyncResourceType.CUSTOM_CONTENT, content.getId(),
            payload, hash, ticket, lease, customContentStore.currentGeneration(serverId, content.getId()),
            authority.result().revision(), authority.result().payloadHash(), authority.generation(), false);
        if (!accepted && lease.isCurrent()) {
            customContentStore.markFailed(serverId, content.getId());
        }
        invalidateCustomContentOptionCatalogs(serverId);
        return accepted;
    }

    public void applyQuickEdit(String serverId, String sessionId, CustomContentDefinition content) {
        if (serverId == null || sessionId == null || sessionId.isBlank() || content == null) {
            return;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            flowClient.sendQuickEditApply(sessionId, content);
        }
    }

    public void cacheCustomContent(String serverId, CustomContentDefinition content) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        cacheCustomContentInternal(serverId, content, true);
    }

    private Async<Boolean> importMarketplaceContent(MarketplaceModels.Listing listing, MarketplaceModels.Version version, String payloadJson) {
        Async<Boolean> result = Async.pending();
        try {
            projectMetadataHydrations.execute(() -> {
            String serverId = marketplaceImportServerId;
            if (serverId == null || serverId.isBlank() || listing == null || payloadJson == null || payloadJson.isBlank()) {
                result.complete(false);
                return;
            }
            try {
                if (installMarketplaceBundle(serverId, listing, version, payloadJson)) {
                    result.complete(true);
                    return;
                }
                Async<Boolean> save = switch (listing.type) {
                    case "FLOW" -> saveMarketplaceFlow(serverId, FlowJson.graph(FlowJson.parse(payloadJson).getAsJsonObject()));
                    case "UI" -> saveMarketplaceGui(serverId, (GuiDefinition) ReSyncResourceType.GUI.deserialize(payloadJson));
                    case "TAB_LIST" -> saveMarketplaceTab(serverId, (TabDefinition) ReSyncResourceType.TAB.deserialize(payloadJson));
                    case "SCOREBOARD" -> saveMarketplaceScoreboard(serverId, (ScoreboardDefinition) ReSyncResourceType.SCOREBOARD.deserialize(payloadJson));
                    case "CUSTOM_CONTENT", "RESYNC_CONTENT" -> saveMarketplaceFlow(serverId, FlowJson.graph(FlowJson.parse(payloadJson).getAsJsonObject()));
                    default -> Async.completed(false);
                };
                save.whenComplete((saved, failure) -> result.complete(failure == null && Boolean.TRUE.equals(saved)));
            } catch (Exception e) {
                result.complete(false);
            }
            });
        } catch (IllegalStateException exception) {
            result.complete(false);
        }
        return result;
    }

    private Async<Boolean> saveMarketplaceFlow(String serverId, FlowGraph graph) {
        if (graph == null || graph.getId() == null || graph.getId().isBlank()) {
            return Async.completed(false);
        }
        CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
        ReSyncResourceType graphType = ReSyncResourceType.byTypeId(graph.getResourceType());
        ReSyncResourceType type = content != null ? ReSyncResourceType.CUSTOM_CONTENT : graphType != null && graphType.isGraph() ? graphType : ReSyncResourceType.FLOW;
        String id = content != null ? content.getId() : graph.getId();
        String name = content != null ? content.getDisplayName() : graph.getId();
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, type, id, name);
        Async<Boolean> completion = saveTicketCompletion(ticket);
        ReSyncResourceType saveType = graphType != null && graphType.isGraph() ? graphType
            : graph.isFunction() ? ReSyncResourceType.FUNCTION : ReSyncResourceType.FLOW;
        saveGraph(serverId, saveType, graph, ticket);
        return completion;
    }

    private Async<Boolean> saveMarketplaceGui(String serverId, GuiDefinition gui) {
        if (gui == null || gui.getId() == null || gui.getId().isBlank()) {
            return Async.completed(false);
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.GUI, gui.getId(), gui.getTitle());
        Async<Boolean> completion = saveTicketCompletion(ticket);
        saveGui(serverId, gui, ticket);
        return completion;
    }

    private Async<Boolean> saveMarketplaceTab(String serverId, TabDefinition tab) {
        if (tab == null || tab.getId() == null || tab.getId().isBlank()) {
            return Async.completed(false);
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.TAB, tab.getId(), tab.getId());
        Async<Boolean> completion = saveTicketCompletion(ticket);
        saveTab(serverId, tab, ticket);
        return completion;
    }

    private Async<Boolean> saveMarketplaceScoreboard(String serverId, ScoreboardDefinition scoreboard) {
        if (scoreboard == null || scoreboard.getId() == null || scoreboard.getId().isBlank()) {
            return Async.completed(false);
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.SCOREBOARD,
            scoreboard.getId(), scoreboard.getTitle());
        Async<Boolean> completion = saveTicketCompletion(ticket);
        saveScoreboard(serverId, scoreboard, ticket);
        return completion;
    }

    public boolean installMarketplaceBundle(String serverId, MarketplaceModels.Listing listing, MarketplaceModels.Version version, String payloadJson) {
        JsonElement parsed = JsonParser.parseString(payloadJson);
        if (!parsed.isJsonObject()) {
            return false;
        }
        JsonObject bundle = parsed.getAsJsonObject();
        if (!bundle.has("assets") || !bundle.get("assets").isJsonArray()) {
            return false;
        }
        String folderName = text(bundle, "folderName");
        if (folderName.isBlank()) {
            folderName = listing != null && listing.title != null && !listing.title.isBlank() ? listing.title : "Marketplace Bundle";
        }
        String rootFolder = "Marketplace/" + safeFolderName(folderName);
        ProjectMetadataEdit metadata = editProjectMetadata(serverId);
        ensureMarketplaceFolder(metadata, "Marketplace");
        ensureMarketplaceFolder(metadata, rootFolder);
        ProjectBundle installed = metadata.bundle(listing.marketplaceSlug, listing.slug);
        Set<String> previousKeys = installed == null ? new HashSet<>() : new HashSet<>(installed.resourceKeys());
        JsonArray assets = bundle.getAsJsonArray("assets");
        List<String> resourceKeys = new ArrayList<>();
        List<Async<Boolean>> assetImports = new ArrayList<>();
        for (JsonElement element : assets) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            String type = text(asset, "type");
            String id = text(asset, "id");
            String displayName = text(asset, "displayName");
            if (type.isBlank() || id.isBlank() || !asset.has("payload")) {
                continue;
            }
            assetImports.add(importMarketplaceBundleAsset(serverId, type, id, displayName, asset.get("payload")));
            String folder = rootFolder + "/" + marketplaceBundleFolder(type);
            ensureMarketplaceFolder(metadata, folder);
            metadata.putResource(type, id, displayName.isBlank() ? id : displayName, folder, metadata.nextResourceSortOrder());
            resourceKeys.add(ReSyncProjectMetadata.resourceKey(type, id));
        }
        if (!assetImports.isEmpty()) {
            Async.allOf(assetImports.toArray(Async[]::new)).join();
        }
        boolean assetsSaved = !assetImports.isEmpty() && assetImports.stream().map(Async::join).allMatch(Boolean::booleanValue);
        if (!assetsSaved) {
            for (String key : resourceKeys) {
                ProjectResource resource = metadata.editor.resourceByKey(key) != null ? projectResource(metadata.editor.resourceByKey(key)) : null;
                if (resource != null) {
                    deleteMarketplaceBundleResource(serverId, resource);
                }
            }
            resourceKeys.forEach(metadata::removeResource);
            metadata.folders().stream().filter(folder -> folder.path().equals(rootFolder) || folder.path().startsWith(rootFolder + "/"))
                .map(ProjectFolder::path).forEach(metadata::removeFolder);
            throw new IllegalStateException("Marketplace bundle resources failed to save");
        }
        previousKeys.removeAll(resourceKeys);
        for (String key : previousKeys) {
            ProjectResource resource = metadata.editor.resourceByKey(key) != null ? projectResource(metadata.editor.resourceByKey(key)) : null;
            if (resource != null) {
                deleteMarketplaceBundleResource(serverId, resource);
            }
        }
        previousKeys.forEach(metadata::removeResource);
        metadata.putBundle(new ProjectBundle(listing.marketplaceSlug, listing.slug,
            listing.title != null && !listing.title.isBlank() ? listing.title : folderName,
            version != null ? version.id : "", version != null ? version.version : "", rootFolder,
            listing.iconMediaId, true, resourceKeys));
        saveProjectMetadata(metadata, true);
        return true;
    }

    public void deleteMarketplaceBundle(String serverId, ReSyncProjectMetadata.InstalledBundleEntry bundle) {
        if (bundle == null) {
            return;
        }
        ProjectMetadataEdit metadata = editProjectMetadata(serverId);
        Set<String> ownedKeys = new HashSet<>(bundle.getResourceKeys());
        if (ownedKeys.isEmpty() && !bundle.getRootPath().isBlank()) {
            for (ProjectResource resource : metadata.resources()) {
                if (resource.path().equals(bundle.getRootPath()) || resource.path().startsWith(bundle.getRootPath() + "/")) {
                    ownedKeys.add(resource.key());
                }
            }
        }
        for (String key : ownedKeys) {
            ProjectResource resource = metadata.editor.resourceByKey(key) != null ? projectResource(metadata.editor.resourceByKey(key)) : null;
            if (resource != null) {
                deleteMarketplaceBundleResource(serverId, resource);
            }
        }
        ownedKeys.forEach(metadata::removeResource);
        String rootPath = bundle.getRootPath();
        if (!rootPath.isBlank()) {
            metadata.folders().stream().filter(folder -> folder.path().equals(rootPath) || folder.path().startsWith(rootPath + "/"))
                .map(ProjectFolder::path).forEach(metadata::removeFolder);
        }
        metadata.removeBundle(bundle.key());
        saveProjectMetadata(metadata, true);
    }

    public void setMarketplaceBundleEnabled(String serverId, ReSyncProjectMetadata.InstalledBundleEntry bundle, boolean enabled) {
        if (bundle == null || bundle.isEnabled() == enabled) {
            return;
        }
        ProjectMetadataEdit metadata = editProjectMetadata(serverId);
        ProjectBundle stored = metadata.bundle(bundle.getMarketplaceSlug(), bundle.getListingSlug());
        if (stored == null) {
            return;
        }
        metadata.putBundle(new ProjectBundle(stored.marketplaceSlug(), stored.listingSlug(), stored.title(), stored.versionId(), stored.version(),
            stored.rootPath(), stored.iconMediaId(), enabled, stored.resourceKeys()));
        for (String key : stored.resourceKeys()) {
            ProjectResource resource = metadata.editor.resourceByKey(key) != null ? projectResource(metadata.editor.resourceByKey(key)) : null;
            if (resource != null && ReSyncResourceDragPayload.COMMAND.equals(resource.type())) {
                if (enabled) {
                    setCommandBinding(serverId, resource.id(), resource.displayName().isBlank() ? resource.id() : resource.displayName());
                } else {
                    clearCommandBinding(serverId, resource.id());
                }
            }
        }
        saveProjectMetadata(metadata, true);
    }

    private void deleteMarketplaceBundleResource(String serverId, ReSyncProjectMetadata.ResourceEntry resource) {
        deleteMarketplaceBundleResource(serverId, resource.getType(), resource.getId());
    }

    private void deleteMarketplaceBundleResource(String serverId, ProjectResource resource) {
        deleteMarketplaceBundleResource(serverId, resource.type(), resource.id());
    }

    private void deleteMarketplaceBundleResource(String serverId, String type, String id) {
        switch (type) {
            case ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION -> deleteFlow(serverId, id);
            case ReSyncResourceDragPayload.COMMAND -> deleteGraph(serverId, ReSyncResourceType.COMMAND, id);
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> deleteCustomContent(serverId, id);
            case ReSyncResourceDragPayload.GUI -> deleteGui(serverId, id);
            case ReSyncResourceDragPayload.SCOREBOARD -> deleteScoreboard(serverId, id);
            case ReSyncResourceDragPayload.TAB -> deleteTab(serverId, id);
            case ReSyncResourceDragPayload.CHAT, ReSyncResourceDragPayload.COMPONENT_BUILDER, ReSyncResourceDragPayload.MOTD_PROFILE, ReSyncResourceDragPayload.MESSAGE_RULE,
                 ReSyncResourceDragPayload.RECIPE_DEFINITION, ReSyncResourceDragPayload.TEXT_TEMPLATE, ReSyncResourceDragPayload.ADVANCEMENT_TREE,
                 ReSyncResourceDragPayload.DIALOG, ReSyncResourceDragPayload.TRADE_PROFILE, ReSyncResourceDragPayload.NPC_DEFINITION,
                 ReSyncResourceDragPayload.LOOT_TABLE -> {
                ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
                if (resourceType != null) {
                    deleteJsonResource(serverId, resourceType, id);
                }
            }
            default -> {
            }
        }
    }

    private Async<Boolean> importMarketplaceBundleAsset(String serverId, String type, String id, String displayName, JsonElement payload) {
        return switch (type) {
            case ReSyncResourceDragPayload.COMMAND -> {
                FlowGraph graph = FlowJson.graph(payload.getAsJsonObject());
                if (graph != null) {
                    graph.setId(id);
                    yield saveMarketplaceFlow(serverId, graph).thenApply(saved -> {
                        if (saved) {
                            setCommandBinding(serverId, id, displayName.isBlank() ? id : displayName);
                        }
                        return saved;
                    });
                }
                yield Async.completed(false);
            }
            case ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION -> {
                FlowGraph graph = FlowJson.graph(payload.getAsJsonObject());
                if (graph != null) {
                    graph.setId(id);
                    yield saveMarketplaceFlow(serverId, graph);
                }
                yield Async.completed(false);
            }
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> {
                CustomContentDefinition content = (CustomContentDefinition) ReSyncResourceType.CUSTOM_CONTENT.deserialize(FlowJson.write(payload));
                if (content != null) {
                    content.setId(id);
                    yield saveMarketplaceCustomContent(serverId, content);
                }
                yield Async.completed(false);
            }
            case ReSyncResourceDragPayload.GUI -> {
                GuiDefinition gui = (GuiDefinition) ReSyncResourceType.GUI.deserialize(FlowJson.write(payload));
                if (gui != null) {
                    gui.setId(id);
                    yield saveMarketplaceGui(serverId, gui);
                }
                yield Async.completed(false);
            }
            case ReSyncResourceDragPayload.SCOREBOARD -> {
                ScoreboardDefinition scoreboard = (ScoreboardDefinition) ReSyncResourceType.SCOREBOARD.deserialize(FlowJson.write(payload));
                if (scoreboard != null) {
                    scoreboard.setId(id);
                    yield saveMarketplaceScoreboard(serverId, scoreboard);
                }
                yield Async.completed(false);
            }
            case ReSyncResourceDragPayload.TAB -> {
                TabDefinition tab = (TabDefinition) ReSyncResourceType.TAB.deserialize(FlowJson.write(payload));
                if (tab != null) {
                    tab.setId(id);
                    yield saveMarketplaceTab(serverId, tab);
                }
                yield Async.completed(false);
            }
            case ReSyncResourceDragPayload.CHAT, ReSyncResourceDragPayload.COMPONENT_BUILDER, ReSyncResourceDragPayload.MOTD_PROFILE, ReSyncResourceDragPayload.MESSAGE_RULE,
                 ReSyncResourceDragPayload.RECIPE_DEFINITION, ReSyncResourceDragPayload.TEXT_TEMPLATE, ReSyncResourceDragPayload.ADVANCEMENT_TREE,
                 ReSyncResourceDragPayload.DIALOG, ReSyncResourceDragPayload.TRADE_PROFILE, ReSyncResourceDragPayload.NPC_DEFINITION,
                 ReSyncResourceDragPayload.LOOT_TABLE -> {
                ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
                JsonObject resource = payload != null && payload.isJsonObject() ? payload.getAsJsonObject() : null;
                if (resourceType != null && resource != null) {
                    resource.addProperty("id", id);
                    yield saveMarketplaceJsonResource(serverId, resourceType, resource, displayName);
                }
                yield Async.completed(false);
            }
            default -> Async.completed(false);
        };
    }

    private Async<Boolean> saveMarketplaceCustomContent(String serverId, CustomContentDefinition content) {
        if (content == null || content.getId() == null || content.getId().isBlank()) {
            return Async.completed(false);
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.CUSTOM_CONTENT,
            content.getId(), content.getDisplayName());
        Async<Boolean> completion = saveTicketCompletion(ticket);
        saveCustomContent(serverId, content, ticket);
        return completion;
    }

    private Async<Boolean> saveMarketplaceJsonResource(String serverId, ReSyncResourceType type, JsonObject resource, String displayName) {
        String id = type.extractId(resource);
        if (id == null || id.isBlank()) {
            return Async.completed(false);
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, type, id, displayName.isBlank() ? id : displayName);
        Async<Boolean> completion = saveTicketCompletion(ticket);
        saveJsonResource(serverId, type, resource, ticket);
        return completion;
    }

    private Async<Boolean> saveTicketCompletion(DesignerSaveNotifications.SaveTicket ticket) {
        if (ticket == null) return Async.completed(false);
        Async<Boolean> completion = Async.pending();
        ticket.whenFinished((saved, ignored) -> completion.complete(saved));
        return completion;
    }

    private Async<SaveTicketSettlement> saveTicketSettlement(DesignerSaveNotifications.SaveTicket ticket) {
        if (ticket == null) return Async.completed(new SaveTicketSettlement(false, false));
        Async<SaveTicketSettlement> completion = Async.pending();
        ticket.whenFinished((saved, currentAtFinish) -> completion.complete(new SaveTicketSettlement(saved, currentAtFinish)));
        return completion;
    }

    private void ensureMarketplaceFolder(ReSyncProjectMetadata metadata, String path) {
        String normalized = ReSyncProjectMetadata.normalizePath(path);
        int split = normalized.lastIndexOf('/');
        String parent = split > 0 ? normalized.substring(0, split) : "";
        metadata.ensureFolder(normalized, parent, metadata.getFolders().size());
    }

    private void ensureMarketplaceFolder(ProjectMetadataEdit metadata, String path) {
        String normalized = ReSyncProjectMetadata.normalizePath(path);
        if (metadata.folder(normalized) != null) return;
        int split = normalized.lastIndexOf('/');
        String parent = split > 0 ? normalized.substring(0, split) : "";
        String name = split >= 0 ? normalized.substring(split + 1) : normalized;
        metadata.putFolder(normalized, parent, name, metadata.nextFolderSortOrder(), false);
    }

    private String marketplaceBundleFolder(String type) {
        return switch (type) {
            case ReSyncResourceDragPayload.FUNCTION -> "Functions";
            case ReSyncResourceDragPayload.COMMAND -> "Commands";
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> "Content";
            case ReSyncResourceDragPayload.GUI -> "GUIs";
            case ReSyncResourceDragPayload.SCOREBOARD -> "Scoreboards";
            case ReSyncResourceDragPayload.TAB -> "Tabs";
            case ReSyncResourceDragPayload.CHAT -> "Chat";
            case ReSyncResourceDragPayload.COMPONENT_BUILDER -> "Component Builders";
            case ReSyncResourceDragPayload.DIALOG -> "Dialogs";
            case ReSyncResourceDragPayload.TRADE_PROFILE -> "Trades";
            case ReSyncResourceDragPayload.NPC_DEFINITION -> "NPCs";
            case ReSyncResourceDragPayload.LOOT_TABLE -> "Loot Tables";
            default -> "Flows";
        };
    }

    private String safeFolderName(String value) {
        String name = value == null ? "" : value.trim().replace('\\', '/').replaceAll("[/:*?\"<>|]+", "-");
        name = name.replaceAll("\\s+", " ").trim();
        return name.isBlank() ? "Bundle" : name;
    }

    private String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean usesJsonResourceStore(ReSyncResourceType type) {
        return type == ReSyncResourceType.CHAT
            || type == ReSyncResourceType.COMPONENT_BUILDER
            || type == ReSyncResourceType.MOTD_PROFILE
            || type == ReSyncResourceType.MESSAGE_RULE
            || type == ReSyncResourceType.RECIPE_DEFINITION
            || type == ReSyncResourceType.TEXT_TEMPLATE
            || type == ReSyncResourceType.ADVANCEMENT_TREE
            || type == ReSyncResourceType.DIALOG
            || type == ReSyncResourceType.TRADE_PROFILE
            || type == ReSyncResourceType.NPC_DEFINITION
            || type == ReSyncResourceType.LOOT_TABLE
            || type == ReSyncResourceType.VARIABLE_DEFINITION
            || type == ReSyncResourceType.TIMER_DEFINITION
            || type == ReSyncResourceType.SCHEDULE_DEFINITION;
    }

    private String jsonResourceId(JsonObject resource) {
        return text(resource, "id");
    }

    private String jsonResourceName(JsonObject resource) {
        String displayName = text(resource, "displayName");
        if (!displayName.isBlank()) {
            return displayName;
        }
        String name = text(resource, "name");
        return name.isBlank() ? text(resource, "id") : name;
    }

    private JsonObject defaultJsonResource(ReSyncResourceType type, String id, String folder) {
        JsonObject resource = new JsonObject();
        resource.addProperty("id", id);
        resource.addProperty("displayName", id);
        resource.addProperty("folder", folder == null || folder.isBlank() ? type.defaultFolder() : folder);
        resource.addProperty("enabled", true);
        switch (type) {
            case COMPONENT_BUILDER -> {
                JsonObject scope = new JsonObject();
                scope.addProperty("kind", "dynamic");
                scope.addProperty("value", "");
                resource.add("scope", scope);
                resource.add("components", new JsonObject());
            }
            case CHAT -> {
                JsonObject channel = new JsonObject();
                channel.addProperty("priority", 0);
                channel.addProperty("defaultChannel", true);
                channel.addProperty("autojoin", true);
                channel.addProperty("prefix", "<gray>[Chat]</gray> ");
                channel.addProperty("format", "");
                channel.addProperty("range", -1);
                channel.addProperty("allowMiniMessage", false);
                resource.add("channel", channel);
                JsonObject format = new JsonObject();
                format.addProperty("template", "{prefix}{sender}: {message}");
                resource.add("format", format);
                JsonObject rule = new JsonObject();
                rule.addProperty("contains", "");
                rule.addProperty("action", "replace");
                rule.addProperty("replacement", "{message}");
                resource.add("rule", rule);
                JsonObject privateMessages = new JsonObject();
                privateMessages.addProperty("sender", "<gray>To <white>{receiver}</white>: <message>");
                privateMessages.addProperty("receiver", "<gray>From <white>{sender}</white>: <message>");
                privateMessages.addProperty("spy", "<gray>Spy <white>{sender}</white> -> <white>{receiver}</white>: <message>");
                resource.add("privateMessages", privateMessages);
                JsonObject mention = new JsonObject();
                mention.addProperty("template", "<yellow>@{player}</yellow>");
                resource.add("mention", mention);
                JsonObject ignore = new JsonObject();
                ignore.add("players", new JsonArray());
                resource.add("ignore", ignore);
            }
            case MOTD_PROFILE -> {
                resource.addProperty("priority", 0);
                resource.addProperty("line1", "<green>ReSync Server");
                resource.addProperty("line2", "<gray>Powered By ReStudio");
                resource.addProperty("playerCountMode", "real");
            }
            case MESSAGE_RULE -> {
                resource.addProperty("source", "join");
                resource.addProperty("priority", 0);
                resource.addProperty("action", "replace_section");
                resource.addProperty("contains", "");
                resource.addProperty("replacement", "{message}");
            }
            case RECIPE_DEFINITION -> {
                resource.addProperty("type", "shaped");
                JsonObject output = new JsonObject();
                output.addProperty("material", "STONE");
                output.addProperty("amount", 1);
                resource.add("output", output);
                JsonArray shape = new JsonArray();
                shape.add("A");
                resource.add("shape", shape);
                JsonObject keys = new JsonObject();
                keys.addProperty("A", "STONE");
                resource.add("keys", keys);
                resource.addProperty("experience", 0);
                resource.addProperty("cookingTime", 200);
                resource.add("conditions", new JsonObject());
            }
            case TEXT_TEMPLATE -> {
                resource.addProperty("kind", "animation");
                resource.addProperty("mode", "frames");
                resource.addProperty("text", id);
                JsonArray frames = new JsonArray();
                frames.add(id);
                resource.add("frames", frames);
            }
            case ADVANCEMENT_TREE -> {
                JsonObject nodes = new JsonObject();
                JsonObject root = new JsonObject();
                root.addProperty("enabled", true);
                root.addProperty("parent", "");
                JsonObject position = new JsonObject();
                position.addProperty("x", 0);
                position.addProperty("y", 0);
                root.add("position", position);
                JsonObject display = new JsonObject();
                display.addProperty("title", id);
                display.addProperty("description", "Server Progress");
                display.addProperty("icon", "minecraft:nether_star");
                display.addProperty("frame", "task");
                display.addProperty("background", "minecraft:gui/advancements/backgrounds/adventure");
                display.addProperty("showToast", false);
                display.addProperty("announceToChat", false);
                display.addProperty("hidden", false);
                root.add("display", display);
                root.add("criteria", new JsonObject());
                root.add("requirements", new JsonArray());
                JsonObject rewards = new JsonObject();
                rewards.addProperty("experience", 0);
                rewards.add("loot", new JsonArray());
                rewards.add("recipes", new JsonArray());
                root.add("rewards", rewards);
                JsonObject onComplete = new JsonObject();
                onComplete.add("commands", new JsonArray());
                onComplete.addProperty("flowId", "");
                root.add("onComplete", onComplete);
                nodes.add("root", root);
                resource.add("nodes", nodes);
            }
            case DIALOG -> {
                resource.addProperty("type", "minecraft:multi_action");
                resource.addProperty("title", id);
                resource.add("body", new JsonArray());
                resource.add("inputs", new JsonArray());
                resource.addProperty("can_close_with_escape", true);
                resource.addProperty("after_action", "close");
                resource.addProperty("columns", 1);
                JsonArray actions = new JsonArray();
                JsonObject button = new JsonObject();
                button.addProperty("label", "Button");
                button.addProperty("width", 150);
                JsonObject resync = new JsonObject();
                resync.addProperty("actionMode", "None");
                resync.addProperty("predicateMode", "None");
                button.add("resync", resync);
                actions.add(button);
                resource.add("actions", actions);
            }
            case TRADE_PROFILE -> {
                resource.addProperty("profession", "librarian");
                resource.addProperty("villagerType", "plains");
                resource.addProperty("level", 1);
                resource.addProperty("restockTicks", 24000);
                resource.addProperty("maxUses", 12);
                resource.addProperty("lootTable", "");
                JsonArray offers = new JsonArray();
                JsonObject offer = new JsonObject();
                offer.addProperty("result", "minecraft:book");
                offer.addProperty("resultAmount", 1);
                offer.addProperty("cost", "minecraft:emerald");
                offer.addProperty("costAmount", 1);
                offer.addProperty("weight", 1);
                offers.add(offer);
                resource.add("offers", offers);
                resource.add("hooks", new JsonObject());
            }
            case NPC_DEFINITION -> {
                resource.addProperty("entityType", "villager");
                resource.addProperty("displayName", id);
                resource.addProperty("invulnerable", true);
                resource.addProperty("gravity", true);
                resource.addProperty("ai", false);
                resource.addProperty("followPlayer", false);
                resource.addProperty("followRange", 12);
                resource.addProperty("tradeProfile", "");
                resource.addProperty("dialog", "");
                resource.addProperty("lootTable", "");
                JsonObject skin = new JsonObject();
                skin.addProperty("username", "");
                resource.add("skin", skin);
                JsonObject equipment = new JsonObject();
                equipment.addProperty("mainHand", "");
                equipment.addProperty("offHand", "");
                equipment.addProperty("helmet", "");
                equipment.addProperty("chestplate", "");
                equipment.addProperty("leggings", "");
                equipment.addProperty("boots", "");
                resource.add("equipment", equipment);
                resource.add("hooks", new JsonObject());
            }
            case LOOT_TABLE -> {
                resource.addProperty("displayName", id);
                resource.addProperty("enabled", true);
                JsonObject trigger = new JsonObject();
                trigger.addProperty("event", "none");
                trigger.addProperty("target", "");
                trigger.addProperty("entity", "");
                trigger.addProperty("tool", "");
                trigger.addProperty("overrideDrops", true);
                resource.add("trigger", trigger);
                JsonArray pools = new JsonArray();
                JsonObject pool = new JsonObject();
                pool.addProperty("rolls", 1);
                JsonArray entries = new JsonArray();
                JsonObject entry = new JsonObject();
                entry.addProperty("item", "minecraft:stone");
                entry.addProperty("minAmount", 1);
                entry.addProperty("maxAmount", 1);
                entry.addProperty("weight", 1);
                entry.addProperty("chance", 100);
                entries.add(entry);
                pool.add("entries", entries);
                pools.add(pool);
                resource.add("pools", pools);
                JsonObject hooks = new JsonObject();
                hooks.addProperty("beforeRollFlow", "");
                hooks.addProperty("afterRollFlow", "");
                hooks.addProperty("deniedRollFlow", "");
                resource.add("hooks", hooks);
            }
            case VARIABLE_DEFINITION -> {
                resource.addProperty("description", "");
                resource.addProperty("valueType", "boolean");
                resource.addProperty("scope", "flow");
                resource.addProperty("persistent", false);
                resource.addProperty("defaultValue", false);
            }
            case TIMER_DEFINITION -> {
                resource.addProperty("description", "");
                resource.addProperty("scope", "server");
                resource.addProperty("persistent", false);
                resource.addProperty("defaultDuration", 60);
                resource.addProperty("defaultUnit", "seconds");
                resource.addProperty("tickInterval", 0);
            }
            case SCHEDULE_DEFINITION -> {
                resource.addProperty("description", "");
                resource.addProperty("targetType", "function");
                resource.addProperty("targetId", "");
                resource.addProperty("timingMode", "after_delay");
                resource.addProperty("duration", 60);
                resource.addProperty("unit", "seconds");
                resource.addProperty("initialDelay", 0);
                resource.addProperty("dateTime", "");
                resource.addProperty("timeZone", "UTC");
                resource.addProperty("cron", "0 12 * * *");
                resource.addProperty("scope", "server");
                resource.addProperty("persistent", false);
                resource.addProperty("overlapPolicy", "skip");
                resource.addProperty("existingTaskPolicy", "replace");
                resource.addProperty("failurePolicy", "continue");
                resource.addProperty("offlinePolicy", "wait");
                resource.addProperty("missedRunPolicy", "run_once");
            }
            default -> {
            }
        }
        return resource;
    }

    public void markCustomContentSaved(String serverId, String contentId) {
        if (!synchronizeAuthorityEpoch(serverId)) {
            return;
        }
        customContentStore.markSaved(serverId, contentId);
        CustomContentDefinition content = customContentStore.get(serverId, contentId);
        refreshFlowWorkspace(serverId, content != null ? content.getFlowId() : null, false);
    }

    public void markResourceSaveFailed(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || type == null || id == null || id.isBlank() || !synchronizeAuthorityEpoch(serverId)) {
            return;
        }
        if (type.isGraph()) {
            if (hasCoreGraphEditorSession(serverId, type, id)) {
                notifyCoreGraphSaveFailed(serverId, type, id);
                refreshFlowWorkspace(serverId, id, false);
                return;
            }
            hydrateCoreGraphProjection(serverId, type, id);
            if (coreGraphUiProjection.authoritative(serverId, type, id)) {
                refreshFlowWorkspace(serverId, id, false);
                return;
            }
            flowStore.markFailed(serverId, type, id);
            refreshFlowWorkspace(serverId, id, false);
        } else if (type == ReSyncResourceType.GUI) {
            guiStore.markFailed(serverId, id);
        } else if (type == ReSyncResourceType.SCOREBOARD) {
            scoreboardStore.markFailed(serverId, id);
        } else if (type == ReSyncResourceType.TAB) {
            tabStore.markFailed(serverId, id);
        } else if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            customContentStore.markFailed(serverId, id);
            CustomContentDefinition content = customContentStore.get(serverId, id);
            refreshFlowWorkspace(serverId, content != null ? content.getFlowId() : null, false);
        } else if (type == ReSyncResourceType.PROJECT_METADATA) {
            projectMetadataStore.markFailed(serverId, serverId);
        } else {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            if (store != null) {
                store.markFailed(serverId, id);
            }
        }
    }

    public void saveProjectMetadata(String serverId, ReSyncProjectMetadata metadata) {
        saveProjectMetadata(serverId, metadata, true);
    }

    public void saveProjectMetadata(String serverId, ReSyncProjectMetadata metadata, boolean refreshWorkspace) {
        if (serverId == null || metadata == null) {
            return;
        }
        persistProjectMetadata(serverId, metadata);
        refreshStudioWorkspace(serverId, refreshWorkspace);
    }

    public void saveProjectMetadata(String serverId, ReSyncProjectMetadata metadata, boolean refreshWorkspace,
                                    DesignerSaveNotifications.SaveTicket ticket) {
        if (serverId == null || metadata == null) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        persistProjectMetadata(serverId, metadata, ticket);
        refreshStudioWorkspace(serverId, refreshWorkspace);
    }

    public void persistOpenProjectDocument(String serverId, String type, String id, String title) {
        persistOpenProjectDocument(serverId, type, id, title, true);
    }

    public void persistOpenProjectDocument(String serverId, String type, String id, String title, boolean activate) {
        ProjectMetadataSnapshot.Editor edit = currentProjectMetadataSnapshot(serverId).edit();
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        boolean changed = false;
        if (activate) {
            String previousKey = edit.selectedResourceKey();
            if (!previousKey.isBlank() && !previousKey.equals(key)) {
                ProjectMetadataSnapshot.Document previous = edit.document(previousKey);
                if (previous != null && previous.active()) {
                    edit.put(new ProjectMetadataSnapshot.Document(previous.type(), previous.id(), previous.displayName(), false));
                    changed = true;
                }
            }
        }
        ProjectMetadataSnapshot.Document existing = edit.document(key);
        String displayName = title != null && !title.isBlank() ? title : existing != null ? existing.displayName() : id;
        boolean active = activate || existing != null && existing.active();
        if (existing == null || existing.active() != active || !existing.displayName().equals(displayName)) {
            edit.put(new ProjectMetadataSnapshot.Document(type, id, displayName, active));
            changed = true;
        }
        if (activate && !key.equals(edit.selectedResourceKey())) {
            edit.selectedResourceKey(key);
            changed = true;
        }
        if (!changed) {
            return;
        }
        persistProjectMetadata(serverId, edit.freeze());
        refreshStudioWorkspace(serverId, false);
    }

    public void removeOpenProjectDocument(String serverId, String type, String id) {
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        ProjectMetadataSnapshot.Editor edit = currentProjectMetadataSnapshot(serverId).edit();
        if (edit.document(key) == null) return;
        edit.removeDocument(key);
        if (key.equals(edit.selectedResourceKey())) edit.selectedResourceKey("");
        persistProjectMetadata(serverId, edit.freeze());
        refreshStudioWorkspace(serverId, false);
    }

    public void renameOpenProjectDocument(String serverId, String type, String oldId, String newId) {
        String oldKey = ReSyncProjectMetadata.resourceKey(type, oldId);
        String newKey = ReSyncProjectMetadata.resourceKey(type, newId);
        ProjectMetadataSnapshot.Editor edit = currentProjectMetadataSnapshot(serverId).edit();
        ProjectMetadataSnapshot.Document renamed = edit.document(oldKey);
        boolean selectionRenamed = edit.selectedResourceKey().equals(oldKey);
        if (renamed == null && !selectionRenamed) return;
        if (renamed != null) {
            edit.renameDocument(oldKey, new ProjectMetadataSnapshot.Document(type, newId, newId, renamed.active()));
        }
        if (selectionRenamed) edit.selectedResourceKey(newKey);
        persistProjectMetadata(serverId, edit.freeze());
        refreshStudioWorkspace(serverId, false);
    }

    private ProjectMetadataSnapshot currentProjectMetadataSnapshot(String serverId) {
        String actualServerId = serverId != null ? serverId : "";
        ProjectMetadataView view = projectMetadataViews.get(actualServerId);
        int revision = projectCatalogRevisions.getOrDefault(actualServerId, 0);
        if (view != null && view.revision() == revision) {
            return view.metadata();
        }
        SyncedResourceCache.SnapshotLease<ProjectMetadataSnapshot> lease = projectMetadataStore.snapshotLease(actualServerId,
            actualServerId);
        if (lease != null) {
            if (shouldHydrateProjectMetadata(actualServerId)
                && hydratedProjectCatalogRevisions.getOrDefault(actualServerId, -1) != revision) {
                scheduleProjectMetadataHydration(actualServerId, revision, lease);
            }
            return lease.materialize(Function.identity());
        }
        ReSyncProjectMetadata metadata = new ReSyncProjectMetadata(actualServerId);
        metadata.ensureDefaultFolders();
        ProjectMetadataSnapshot initial = ProjectMetadataSnapshot.from(metadata);
        projectMetadataStore.putInDraft(actualServerId, initial);
        return initial;
    }

    private void persistProjectMetadata(String serverId, ReSyncProjectMetadata metadata) {
        metadata.setServerId(serverId);
        persistProjectMetadata(serverId, ProjectMetadataSnapshot.from(metadata));
    }

    private void persistProjectMetadata(String serverId, ReSyncProjectMetadata metadata,
                                        DesignerSaveNotifications.SaveTicket ticket) {
        if (metadata == null) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        metadata.setServerId(serverId);
        persistProjectMetadata(serverId, ProjectMetadataSnapshot.from(metadata), ticket);
    }

    private void persistProjectMetadata(String serverId, ProjectMetadataSnapshot metadata) {
        persistProjectMetadata(serverId, metadata, (DesignerSaveNotifications.SaveTicket) null);
    }

    private void persistProjectMetadata(String serverId, ProjectMetadataSnapshot metadata,
                                        DesignerSaveNotifications.SaveTicket ticket) {
        SyncedResourceCache.SaveLease<ProjectMetadataSnapshot> lease = projectMetadataStore.putInDraft(serverId, metadata);
        invalidateProjectCatalog(serverId);
        if (canPersistProjectMetadata(serverId)) {
            ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
            if (flowClient != null) {
                projectMetadataStore.markSaving(serverId, lease.resourceId());
                boolean sent = ticket != null
                    ? flowClient.sendResourceSave(ReSyncResourceType.PROJECT_METADATA, lease, ticket)
                    : flowClient.sendResourceSave(ReSyncResourceType.PROJECT_METADATA, lease);
                if (!sent && lease.isCurrent())
                    projectMetadataStore.markFailed(serverId, lease.resourceId());
            } else {
                failSave(serverId, ReSyncResourceType.PROJECT_METADATA, serverId, ticket, "ReSync Offline");
            }
        } else if (ticket != null) {
            failSave(serverId, ReSyncResourceType.PROJECT_METADATA, serverId, ticket, "Project Metadata Unavailable");
        }
    }

    private boolean persistProjectMetadata(String serverId, ProjectMetadataSnapshot metadata,
                                           ResourceProjectionLease projectionLease) {
        if (serverId == null || serverId.isBlank() || metadata == null || projectionLease == null
            || !projectionLease.isValid()) {
            return false;
        }
        String resourceId = serverId;
        String typeId = ReSyncResourceType.PROJECT_METADATA.typeId();
        Long expected = projectionLease.expected(typeId, serverId, resourceId);
        SyncedResourceCache.SaveLease<ProjectMetadataSnapshot> lease = expected != null
            ? projectMetadataStore.putInDraftIfGenerationLease(serverId, metadata, expected) : null;
        if (lease == null || !projectionLease.advance(typeId, serverId, resourceId,
            projectMetadataStore.currentGeneration(serverId, resourceId))) {
            return false;
        }
        invalidateProjectCatalog(serverId);
        if (!canPersistProjectMetadata(serverId)) {
            return true;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            return true;
        }
        expected = projectionLease.expected(typeId, serverId, resourceId);
        Long saving = expected != null
            ? projectMetadataStore.markSavingIfGeneration(serverId, resourceId, expected) : null;
        if (saving == null) {
            return false;
        }
        if (!projectionLease.advance(typeId, serverId, resourceId, saving)) {
            return false;
        }
        boolean sent = flowClient.sendResourceSave(ReSyncResourceType.PROJECT_METADATA, lease);
        if (sent) {
            return true;
        }
        expected = projectionLease.expected(typeId, serverId, resourceId);
        Long failed = expected != null
            ? projectMetadataStore.markFailedIfGeneration(serverId, resourceId, expected) : null;
        if (failed != null) {
            projectionLease.advance(typeId, serverId, resourceId, failed);
        }
        return false;
    }

    private boolean hasCoreGraphEditorSession(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return false;
        }
        CoreGraphSessionState state = coreGraphEditorSessions.get(new CoreGraphSessionKey(serverId, type, id));
        return state != null && state.session() != null;
    }

    private void notifyCoreGraphSaveFailed(String serverId, ReSyncResourceType type, String id) {
        ScreenManager.getInstance().execute(() -> {
            GraphEditorScreen studioScreen = GraphEditorScreen.getStudioScreen(serverId);
            if (studioScreen != null) {
                studioScreen.markStudioDocumentSaveFailed(type.typeId(), id);
            }
        });
    }

    public void cacheProjectMetadata(String serverId, ReSyncProjectMetadata metadata) {
        if (serverId == null || metadata == null || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        boolean deferWorkspaceRefresh = hasPendingCreationForServer(serverId);
        metadata.setServerId(serverId);
        metadata.ensureDefaultFolders();
        loadedProjectMetadataLists.add(serverId);
        pendingProjectMetadataDocuments.remove(serverId);
        cacheProjectMetadataSnapshot(serverId, ProjectMetadataSnapshot.from(metadata), -1L);
        advanceProjectMetadataAuthorityGeneration(serverId);
        ReSyncFlowClient.traceLifecycle(serverId, "project_metadata_applied", "serverId", serverId, "resourceKey",
            ReSyncResourceType.PROJECT_METADATA.typeId() + ":" + serverId, "operation", "refresh", "requestId",
            "list", "mutationId", null, "generation", projectMetadataAuthorityGenerations.getOrDefault(serverId,
                0L), "authorityEpoch", 0L, "revision", 0L, "folderCount", metadata.getFolders().size(),
            "resourceCount", metadata.getResources().size(), "deferWorkspaceRefresh", deferWorkspaceRefresh,
            "outcome", "applied");
        resumeCreationTransactions(serverId);
        if (!deferWorkspaceRefresh) {
            refreshStudioWorkspace(serverId);
        }
    }

    private void cacheProjectMetadataSnapshot(String serverId, ProjectMetadataSnapshot authoritative, long draftVersion) {
        projectMetadataStore.cacheAndRebase(serverId, authoritative, draftVersion,
            (previous, local) -> ProjectMetadataSnapshot.rebase(previous, local, authoritative));
        invalidateProjectCatalog(serverId);
    }

    private boolean cacheProjectMetadataSnapshot(String serverId, ProjectMetadataSnapshot authoritative, long draftVersion,
                                                 ResourceProjectionLease lease) {
        Long expected = lease.expected(ReSyncResourceType.PROJECT_METADATA.typeId(), serverId, serverId);
        Long next = expected != null
            ? projectMetadataStore.cacheAndRebaseIfGeneration(serverId, authoritative, draftVersion,
                (previous, local) -> ProjectMetadataSnapshot.rebase(previous, local, authoritative), expected)
            : null;
        boolean cached = lease.advance(ReSyncResourceType.PROJECT_METADATA.typeId(), serverId, serverId, next);
        if (cached) {
            invalidateProjectCatalog(serverId);
        }
        return cached;
    }

    public ResourceProjectionSnapshot snapshotResourceProjection(String serverId, ReSyncResourceType type,
                                                                 String resourceId, Object incoming) {
        if (serverId == null || serverId.isBlank() || type == null || resourceId == null || resourceId.isBlank()) {
            return new ResourceProjectionSnapshot(type, serverId, resourceId, null, List.of(), false, false, null, null);
        }
        String storeId = type == ReSyncResourceType.PROJECT_METADATA ? serverId : resourceId;
        SyncedResourceCache.EntrySnapshot<?> resource = switch (type) {
            case GUI -> guiStore.snapshot(serverId, storeId);
            case SCOREBOARD -> scoreboardStore.snapshot(serverId, storeId);
            case TAB -> tabStore.snapshot(serverId, storeId);
            case CUSTOM_CONTENT -> customContentStore.snapshot(serverId, storeId);
            case PROJECT_METADATA -> projectMetadataStore.snapshot(serverId, storeId);
            default -> {
                SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
                yield store != null ? store.snapshot(serverId, storeId) : null;
            }
        };
        List<GraphProjectionSnapshot> graphs = type.isGraph()
            ? snapshotGraph(serverId, type, storeId) : List.of();
        CoreGraphUiProjection.StateSnapshot coreGraph = type.isGraph()
            ? snapshotCoreGraphProjection(serverId, type, storeId) : null;
        SyncedResourceCache.EntrySnapshot<ProjectMetadataSnapshot> projectMetadata = projectMetadataStore.snapshot(serverId, serverId);
        SyncedResourceCache.EntrySnapshot<CustomContentDefinition> derivedContent = null;
        if (type.isGraph() && incoming instanceof FlowGraph graph) {
            CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
            if (content != null && content.getId() != null) {
                derivedContent = customContentStore.snapshot(serverId, content.getId());
            }
        }
        return new ResourceProjectionSnapshot(type, serverId, resourceId, resource, graphs,
            loadedProjectMetadataLists.contains(serverId), pendingProjectMetadataDocuments.contains(serverId), coreGraph,
            projectMetadata, derivedContent);
    }

    public ResourceProjectionLease beginResourceProjection(ResourceProjectionSnapshot snapshot) {
        ResourceProjectionLease lease = new ResourceProjectionLease();
        if (snapshot == null || snapshot.type() == null || snapshot.serverId() == null || snapshot.serverId().isBlank()) {
            lease.reject();
            return lease;
        }
        ServerConnectionToken token = captureServerConnectionToken(snapshot.serverId());
        if (!isCurrentServerConnection(token)) {
            lease.reject();
            return lease;
        }
        lease.rememberConnection(token);
        rememberProjectionEntry(lease, snapshot.type(), snapshot.serverId(), snapshot.resourceId(), snapshot.resource());
        rememberProjectionEntry(lease, ReSyncResourceType.PROJECT_METADATA, snapshot.serverId(), snapshot.serverId(),
            snapshot.projectMetadata());
        rememberProjectionEntry(lease, ReSyncResourceType.CUSTOM_CONTENT, snapshot.serverId(),
            snapshot.derivedContent() != null ? snapshot.derivedContent().resourceId() : null, snapshot.derivedContent());
        if (snapshot.type().isGraph()) {
            lease.rememberCore(snapshot.serverId(), snapshot.type(), snapshot.resourceId(),
                coreGraphUiProjection.currentGeneration(snapshot.serverId(), snapshot.type(), snapshot.resourceId()));
            lease.rememberEditorSession(snapshot.serverId(), snapshot.type(), snapshot.resourceId(),
                coreGraphEditorSessions.get(new CoreGraphSessionKey(snapshot.serverId(), snapshot.type(), snapshot.resourceId())));
        }
        for (GraphProjectionSnapshot graph : snapshot.graphs()) {
            rememberProjectionEntry(lease, graph.type(), snapshot.serverId(), graph.resourceId(), graph.entry());
        }
        return lease;
    }

    private void rememberProjectionEntry(ResourceProjectionLease lease, ReSyncResourceType type, String serverId,
                                         String resourceId, SyncedResourceCache.EntrySnapshot<?> entry) {
        if (lease == null || type == null || resourceId == null || resourceId.isBlank() || entry == null) {
            return;
        }
        lease.remember(type.typeId(), serverId, resourceId, entry.generation());
    }

    public void restoreResourceProjection(ResourceProjectionSnapshot snapshot) {
        restoreResourceProjection(snapshot, null);
    }

    public void restoreResourceProjection(ResourceProjectionSnapshot snapshot, ResourceProjectionLease lease) {
        if (snapshot == null || snapshot.type() == null || snapshot.serverId() == null || snapshot.serverId().isBlank()) {
            return;
        }
        if (lease == null || !lease.isValid()) {
            return;
        }
        String serverId = snapshot.serverId();
        String resourceId = snapshot.type() == ReSyncResourceType.PROJECT_METADATA ? serverId : snapshot.resourceId();
        if (snapshot.coreGraph() != null && lease.coreMutation(serverId, snapshot.type(), resourceId)) {
            Long expectedCore = lease.expectedCore(serverId, snapshot.type(), resourceId);
            if (expectedCore != null) {
                coreGraphUiProjection.restoreIfGeneration(snapshot.coreGraph(), serverId, snapshot.type(), resourceId,
                    expectedCore);
            }
        }
        boolean projectMetadataRestored = snapshot.projectMetadata() != null
            && restoreEntryIfCurrent(projectMetadataStore, snapshot.projectMetadata(), lease,
                ReSyncResourceType.PROJECT_METADATA, serverId);
        boolean resourceRestored = restoreResourceEntryIfCurrent(snapshot.type(), serverId, resourceId, snapshot.resource(), lease);
        boolean derivedContentRestored = snapshot.derivedContent() == null
            || restoreEntryIfCurrent(customContentStore, snapshot.derivedContent(), lease,
                ReSyncResourceType.CUSTOM_CONTENT, snapshot.derivedContent().resourceId());
        if (projectMetadataRestored || snapshot.projectMetadata() == null && resourceRestored) {
            if (snapshot.projectMetadataLoaded()) {
                loadedProjectMetadataLists.add(serverId);
            } else {
                loadedProjectMetadataLists.remove(serverId);
            }
            if (snapshot.projectMetadataPending()) {
                pendingProjectMetadataDocuments.add(serverId);
            } else {
                pendingProjectMetadataDocuments.remove(serverId);
            }
        }
        for (GraphProjectionSnapshot graph : snapshot.graphs()) {
            restoreGraphProjectionIfCurrent(serverId, graph, lease);
        }
        restoreTypedMembershipIfCurrent(lease);
    }

    private void restoreTypedMembershipIfCurrent(ResourceProjectionLease lease) {
        TypedMembershipMutation mutation = lease.membershipMutation;
        ServerConnectionToken token = lease.connectionToken;
        if (mutation == null || token == null) {
            return;
        }
        runIfCurrentServerConnection(token, () -> {
            TypedMembershipState current = typedMemberships.get(mutation.key());
            if (current == null || !sameWorkspaceRefreshGeneration(current.token(), token)) {
                return;
            }
            boolean restored;
            if (current.revision() == mutation.after().revision()) {
                if (mutation.before() == null) {
                    restored = typedMemberships.remove(mutation.key(), current);
                } else {
                    restored = typedMemberships.replace(mutation.key(), current, mutation.before());
                }
            } else {
                restored = restoreTypedMembershipMutation(mutation, current, token);
            }
            if (restored) {
                advanceProjectMembershipRevision(token.serverId());
            }
        });
    }

    private boolean restoreTypedMembershipMutation(TypedMembershipMutation mutation, TypedMembershipState current,
                                                   ServerConnectionToken token) {
        TypedMembershipState before = mutation.before();
        TypedMembershipState after = mutation.after();
        Set<String> changed = new HashSet<>();
        changed.addAll(before != null ? before.ids() : List.of());
        changed.addAll(before != null ? before.tombstones() : Set.of());
        changed.addAll(after.ids());
        changed.addAll(after.tombstones());
        changed.removeIf(id -> (before != null && before.ids().contains(id)) == after.ids().contains(id)
            && (before != null && before.tombstones().contains(id)) == after.tombstones().contains(id));
        if (changed.size() != 1) {
            return false;
        }
        String id = changed.iterator().next();
        boolean afterPresent = after.ids().contains(id);
        boolean afterTombstone = after.tombstones().contains(id);
        if (current.ids().contains(id) != afterPresent || current.tombstones().contains(id) != afterTombstone) {
            return false;
        }
        TreeSet<String> ids = new TreeSet<>(current.ids());
        TreeSet<String> tombstones = new TreeSet<>(current.tombstones());
        if (before != null && before.ids().contains(id)) {
            ids.add(id);
        } else {
            ids.remove(id);
        }
        if (before != null && before.tombstones().contains(id)) {
            tombstones.add(id);
        } else {
            tombstones.remove(id);
        }
        TypedMembershipState restored = new TypedMembershipState(token, List.copyOf(ids), Set.copyOf(tombstones),
            current.complete(), typedMembershipRevision.incrementAndGet());
        return typedMemberships.replace(mutation.key(), current, restored);
    }

    private boolean restoreResourceEntryIfCurrent(ReSyncResourceType type, String serverId, String resourceId,
                                                  SyncedResourceCache.EntrySnapshot<?> resource,
                                                  ResourceProjectionLease lease) {
        if (resource == null) {
            return false;
        }
        switch (type) {
            case GUI -> {
                return restoreEntryIfCurrent(guiStore, resource, lease, type, resourceId);
            }
            case SCOREBOARD -> {
                return restoreEntryIfCurrent(scoreboardStore, resource, lease, type, resourceId);
            }
            case TAB -> {
                return restoreEntryIfCurrent(tabStore, resource, lease, type, resourceId);
            }
            case CUSTOM_CONTENT -> {
                return restoreEntryIfCurrent(customContentStore, resource, lease, type, resourceId);
            }
            case PROJECT_METADATA -> {
                return restoreEntryIfCurrent(projectMetadataStore, resource, lease, type, resourceId);
            }
            default -> {
                SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
                return store != null && restoreEntryIfCurrent(store, resource, lease, type, resourceId);
            }
        }
    }

    private <T> boolean restoreEntryIfCurrent(SyncedResourceCache<T> store, SyncedResourceCache.EntrySnapshot<?> resource,
                                              ResourceProjectionLease lease, ReSyncResourceType type, String resourceId) {
        @SuppressWarnings("unchecked")
        SyncedResourceCache.EntrySnapshot<T> typed = (SyncedResourceCache.EntrySnapshot<T>) resource;
        Long expected = lease.expected(type.typeId(), typed.serverId(), resourceId);
        return expected != null && store.restoreIfGeneration(typed, expected);
    }

    private void restoreGraphProjectionIfCurrent(String serverId, GraphProjectionSnapshot graph,
                                                 ResourceProjectionLease lease) {
        if (graph == null || graph.entry() == null) {
            return;
        }
        Long expected = lease.expected(graph.type().typeId(), serverId, graph.resourceId());
        if (expected != null) {
            flowStore.restoreIfGeneration(serverId, graph.type(), graph.resourceId(), graph.entry(), expected);
        }
    }

    private List<GraphProjectionSnapshot> snapshotGraph(String serverId, ReSyncResourceType type, String id) {
        FlowGraph previous = flowStore.get(serverId, type, id);
        return List.of(new GraphProjectionSnapshot(type, id, detachedGraph(previous), flowStore.snapshot(serverId, type, id)));
    }

    public void markProjectMetadataSaved(String serverId) {
        synchronizeAuthorityEpoch(serverId);
    }

    public void cacheJsonResource(String serverId, ReSyncResourceType type, JsonObject resource) {
        if (serverId == null || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        if (store != null) {
            store.cache(serverId, resource);
            invalidateResourceOptionCatalogs(serverId, type);
            refreshStudioWorkspace(serverId);
        }
    }

    public void cacheServerCapabilities(String serverId, JsonObject capabilities) {
        if (serverId != null && capabilities != null && synchronizeLegacyReadAuthorityEpoch(serverId)) {
            serverCapabilities.put(serverId, capabilities);
            refreshStudioWorkspace(serverId, false);
        }
    }

    public JsonObject getServerCapabilities(String serverId) {
        return serverCapabilities.get(serverId);
    }

    public void requestMessageLog(String serverId, int page, int pageSize, String query, String source) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (flowClient != null) {
            flowClient.requestMessageLog(page, pageSize, query, source);
        }
    }

    public void cacheMessageLogPage(String serverId, JsonObject page) {
        if (serverId == null || page == null || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        if (!runIfCurrentServerConnection(token, () -> messageLogPages.put(serverId, page))) {
            return;
        }
        ScreenManager.getInstance().execute(() -> runIfCurrentServerConnection(token, () -> {
                FocusedJsonResourceDesignerScreen.refreshMessageLogForServer(serverId);
        }));
    }

    public JsonObject getMessageLogPage(String serverId) {
        return messageLogPages.get(serverId);
    }

    public void markJsonResourceSaved(String serverId, ReSyncResourceType type, String id) {
        if (!synchronizeAuthorityEpoch(serverId)) {
            return;
        }
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        if (store != null) {
            store.markSaved(serverId, id);
            invalidateResourceOptionCatalogs(serverId, type);
        }
    }

    public JsonObject createJsonResource(String serverId, ReSyncResourceType type, String id, String folder) {
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        if (store == null || id == null || id.isBlank()) {
            return null;
        }
        JsonObject resource = defaultJsonResource(type, id, folder);
        store.putInDraft(serverId, resource);
        store.putNameIfAbsent(serverId, id, type.extractName(resource));
        return resource;
    }

    public void saveJsonResource(String serverId, ReSyncResourceType type, JsonObject resource) {
        saveJsonResource(serverId, type, resource, null);
    }

    public boolean saveManagedJsonResource(String serverId, String typeId, String id, JsonObject resource,
                                           DesignerSaveNotifications.SaveTicket ticket) {
        ManagedResourceCatalog.Descriptor descriptor = managedResourceDescriptor(serverId, typeId);
        ReSyncResourceType type = ReSyncResourceType.byTypeId(typeId);
        SyncedResourceCache<JsonObject> store = type != null ? jsonResourceStores.get(type) : null;
        if (!descriptor.available() || descriptor.opaque()
            || (!descriptor.supports(ManagedResourceCatalog.Operation.SAVE)
            && !descriptor.supports(ManagedResourceCatalog.Operation.UPDATE))
            || type == null || store == null || id == null || id.isBlank() || resource == null
            || !managedJsonIdentityMatches(resource, id) || !acceptSaveTicket(ticket, serverId, type, id)) {
            failSaveTicket(ticket, descriptor.available()
                ? "Managed Resource Save Is Unavailable" : descriptor.unavailableReason());
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            failSave(serverId, type, id, ticket, "ReSync Offline");
            return false;
        }
        SyncedResourceCache.SaveLease<JsonObject> lease = store.putInDraft(serverId, resource.deepCopy());
        store.putName(serverId, id, type.extractName(resource));
        store.markSaving(serverId, id);
        boolean admitted = flowClient.sendResourceSave(type, lease, ticket);
        if (!admitted && lease.isCurrent()) {
            store.markFailed(serverId, id);
        }
        refreshStudioWorkspaceState(serverId);
        return admitted;
    }

    private boolean managedJsonIdentityMatches(JsonObject resource, String id) {
        if (resource == null || id == null || !resource.has("id") || resource.get("id").isJsonNull()) {
            return false;
        }
        try {
            return id.equals(resource.get("id").getAsString());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public void saveJsonResource(String serverId, ReSyncResourceType type, JsonObject resource,
                                 DesignerSaveNotifications.SaveTicket ticket) {
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        if (store == null || resource == null) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        String id = type.extractId(resource);
        if (id == null || id.isBlank()) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        if (!acceptSaveTicket(ticket, serverId, type, id)) return;
        boolean cached = store.hasCached(serverId, id);
        boolean enabled = cached && isResourceEnabled(serverId, type.typeId(), id);
        SyncedResourceCache.SaveLease<JsonObject> lease = store.putInDraft(serverId, resource, draft -> {
            if (cached) {
                draft.addProperty("enabled", enabled);
            }
        });
        store.putName(serverId, id, type.extractName(resource));
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            store.markSaving(serverId, id);
            if (ticket != null) {
                if (!flowClient.sendResourceSave(type, lease, ticket) && lease.isCurrent()) store.markFailed(serverId, id);
            } else if (!flowClient.sendResourceSave(type, lease) && lease.isCurrent()) store.markFailed(serverId, id);
        } else {
            failSave(serverId, type, id, ticket, "ReSync Offline");
        }
        refreshStudioWorkspaceState(serverId);
    }

    public boolean saveStudioPanelResource(String serverId, ReSyncResourceType type, ResourceReadLease expected,
                                           Object resource, DesignerSaveNotifications.SaveTicket ticket) {
        if (serverId == null || type == null || expected == null || resource == null
            || !Objects.equals(expected.serverId, serverId) || !Objects.equals(expected.type, type.typeId())) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return false;
        }
        boolean supported = switch (type) {
            case GUI -> resource instanceof GuiDefinition;
            case SCOREBOARD -> resource instanceof ScoreboardDefinition;
            case TAB -> resource instanceof TabDefinition;
            default -> resource instanceof JsonObject && jsonResourceStores.containsKey(type);
        };
        String id = supported ? type.extractId(resource) : null;
        if (!supported || id == null || id.isBlank() || !Objects.equals(expected.id, id)
            || !acceptSaveTicket(ticket, serverId, type, id)) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            failSave(serverId, type, id, ticket, "ReSync Offline");
            return false;
        }
        SyncedResourceCache.SaveLease<?> lease = expected.compareAndPublish(resource);
        if (lease == null) return false;
        switch (type) {
            case GUI -> {
                GuiDefinition gui = (GuiDefinition) resource;
                lease.updateName(gui.getTitle() != null ? gui.getTitle() : id, false);
            }
            case SCOREBOARD -> {
                ScoreboardDefinition scoreboard = (ScoreboardDefinition) resource;
                lease.updateName(scoreboard.getTitle() != null ? scoreboard.getTitle() : id, false);
            }
            case TAB -> lease.updateName(id, false);
            default -> lease.updateName(type.extractName(resource), true);
        }
        lease.markSaving();
        boolean admitted = flowClient.sendResourceSave(type, lease, ticket);
        if (!admitted) lease.markFailed();
        refreshStudioWorkspaceState(serverId);
        return admitted;
    }

    private boolean acceptSaveTicket(DesignerSaveNotifications.SaveTicket ticket, String serverId,
                                     ReSyncResourceType type, String id) {
        if (ticket == null) return true;
        if (Objects.equals(ticket.serverId(), serverId) && ticket.type() == type && Objects.equals(ticket.id(), id)
            && DesignerSaveNotifications.isPending(ticket)) return true;
        DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
        return false;
    }

    private void failSave(String serverId, ReSyncResourceType type, String id,
                          DesignerSaveNotifications.SaveTicket ticket, String message) {
        if (ticket != null) DesignerSaveNotifications.failExact(ticket, message);
        else DesignerSaveNotifications.failResource(serverId, type, id, message);
    }

    private void failSaveTicket(DesignerSaveNotifications.SaveTicket ticket, String message) {
        if (ticket != null) DesignerSaveNotifications.failExact(ticket, message);
    }

    public void deleteJsonResource(String serverId, ReSyncResourceType type, String id) {
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        if (store == null || id == null || id.isBlank()) {
            return;
        }
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (flowClient != null) {
            store.markSaving(serverId, id);
            flowClient.sendResourceDelete(type, id);
        }
    }

    public void applyServerJsonResourceList(String serverId, ReSyncResourceType type, List<String> ids) {
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        if (store == null || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        if (!isCurrentServerConnection(token)) {
            return;
        }
        store.applyServerList(serverId, ids);
        publishTypedMembership(token, type, ids);
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (ids != null) {
            for (String id : ids) {
                if (!hasHydratedResource(serverId, type, id)) {
                    flowClient.requestResource(type, id, false);
                }
            }
        }
        refreshStudioWorkspace(serverId);
    }

    public boolean supportsResourceActivation(String type) {
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        return resourceType != null && (resourceType.isGraph() || resourceType == ReSyncResourceType.GUI
            || resourceType == ReSyncResourceType.SCOREBOARD || resourceType == ReSyncResourceType.TAB
            || resourceType == ReSyncResourceType.CUSTOM_CONTENT || jsonResourceStores.containsKey(resourceType));
    }

    public Map<String, Boolean> cachedResourceActivationSnapshot(String serverId,
                                                                 List<ReSyncProjectMetadata.ResourceEntry> resources) {
        if (resources == null || resources.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> activations = new LinkedHashMap<>();
        for (ReSyncProjectMetadata.ResourceEntry resource : resources) {
            if (resource == null) {
                continue;
            }
            ReSyncResourceType type = ReSyncResourceType.byTypeId(resource.getType());
            boolean enabled = type == null || !supportsResourceActivation(resource.getType())
                || cachedResourceEnabled(serverId, type, resource.getId());
            activations.put(resource.key(), enabled);
        }
        return Map.copyOf(activations);
    }

    public boolean isCachedResourceEnabled(String serverId, String type, String id) {
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (resourceType == null || !supportsResourceActivation(type)) {
            return true;
        }
        return cachedResourceEnabled(serverId, resourceType, id);
    }

    private boolean cachedResourceEnabled(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()) {
            return true;
        }
        PendingActivation pending;
        synchronized (resourceActivationLock) {
            pending = pendingActivations.get(new ActivationKey(serverId, type, id));
        }
        if (pending != null) {
            return pending.enabled();
        }
        if (type.isGraph()) {
            CoreGraphUiProjection.Baseline baseline = coreGraphUiProjection.baseline(serverId, type, id).orElse(null);
            if (baseline != null) {
                return baseline.activationState() == null || baseline.activationState() == ResourceActivationState.ACTIVE;
            }
            FlowGraph graph = flowStore.get(serverId, type, id);
            return graph == null || graph.isEnabled();
        }
        if (type == ReSyncResourceType.GUI) {
            return guiStore.getBoolean(serverId, id, true, GuiDefinition::isEnabled);
        }
        if (type == ReSyncResourceType.SCOREBOARD) {
            return scoreboardStore.getBoolean(serverId, id, true, ScoreboardDefinition::isEnabled);
        }
        if (type == ReSyncResourceType.TAB) {
            return tabStore.getBoolean(serverId, id, true, TabDefinition::isEnabled);
        }
        if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            return customContentStore.getBoolean(serverId, id, true, CustomContentDefinition::isEnabled);
        }
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        return store == null || store.getBoolean(serverId, id, true, resource -> !resource.has("enabled")
            || !resource.get("enabled").isJsonPrimitive() || resource.get("enabled").getAsBoolean());
    }

    public boolean isResourceEnabled(String serverId, String type, String id) {
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (resourceType != null && resourceType.isGraph()) {
            synchronized (resourceActivationLock) {
                PendingActivation pending = pendingActivations.get(new ActivationKey(serverId, resourceType, id));
                if (pending != null) {
                    return pending.enabled();
                }
            }
            FlowGraph graph = getGraph(serverId, resourceType, id);
            return graph == null || graph.isEnabled();
        }
        if (resourceType == ReSyncResourceType.GUI) {
            GuiDefinition resource = guiStore.get(serverId, id);
            return resource == null || resource.isEnabled();
        }
        if (resourceType == ReSyncResourceType.SCOREBOARD) {
            ScoreboardDefinition resource = scoreboardStore.get(serverId, id);
            return resource == null || resource.isEnabled();
        }
        if (resourceType == ReSyncResourceType.TAB) {
            TabDefinition resource = tabStore.get(serverId, id);
            return resource == null || resource.isEnabled();
        }
        if (resourceType == ReSyncResourceType.CUSTOM_CONTENT) {
            CustomContentDefinition resource = customContentStore.get(serverId, id);
            return resource == null || resource.isEnabled();
        }
        SyncedResourceCache<JsonObject> store = resourceType != null ? jsonResourceStores.get(resourceType) : null;
        JsonObject resource = store != null ? store.get(serverId, id) : null;
        return resource == null || !resource.has("enabled") || !resource.get("enabled").isJsonPrimitive() || resource.get("enabled").getAsBoolean();
    }

    public boolean setResourceEnabled(String serverId, String type, String id, boolean enabled) {
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (resourceType == null || resourceType == ReSyncResourceType.PROJECT_METADATA || flowClient == null || !flowClient.isConnectedState()) {
            return false;
        }
        if (resourceType.isGraph()) {
            hydrateCoreGraphProjection(serverId, resourceType, id);
            if (coreGraphUiProjection.baseline(serverId, resourceType, id).map(CoreGraphUiProjection.Baseline::readOnly).orElse(false)
                || coreGraphUiProjection.tombstoned(serverId, resourceType, id)) {
                return false;
            }
        }
        boolean currentEnabled = isResourceEnabled(serverId, type, id);
        if (currentEnabled == enabled || !hasResource(serverId, resourceType, id)) {
            return false;
        }
        String requestId = "activation:" + UUID.randomUUID();
        boolean coreAuthority = resourceType.isGraph() && coreGraphAuthorityEnabled(serverId);
        CoreGraphOwnerToken ownerToken = coreAuthority ? currentCoreGraphOwnerToken(serverId, flowClient) : null;
        if (coreAuthority && ownerToken == null) {
            return false;
        }
        boolean[] begun = {false};
        synchronized (serverConnectionGenerationLock) {
            if (closed || retiringServerConnections.contains(serverId) || settledDisconnectedSources.contains(flowClient)) {
                return false;
            }
            if (!connectionManager.withCurrentFlowClient(serverId, flowClient, ignored -> {
                if (!flowClient.isConnectedState()
                    || closed || retiringServerConnections.contains(serverId) || settledDisconnectedSources.contains(flowClient)) {
                    return;
                }
                if (beginResourceActivation(serverId, resourceType, id, currentEnabled, enabled, requestId, ownerToken)) {
                    applyResourceActivationState(serverId, resourceType, id, enabled);
                    begun[0] = true;
                }
            }) || !begun[0]) {
                return false;
            }
        }
        if (!begun[0]) {
            return false;
        }
        refreshStudioWorkspace(serverId);
        flowClient.sendResourceActivation(resourceType, id, enabled, requestId);
        return true;
    }

    private boolean hasResource(String serverId, ReSyncResourceType type, String id) {
        if (type.isGraph()) return getGraph(serverId, type, id) != null;
        if (type == ReSyncResourceType.GUI) return guiStore.get(serverId, id) != null;
        if (type == ReSyncResourceType.SCOREBOARD) return scoreboardStore.get(serverId, id) != null;
        if (type == ReSyncResourceType.TAB) return tabStore.get(serverId, id) != null;
        if (type == ReSyncResourceType.CUSTOM_CONTENT) return customContentStore.get(serverId, id) != null;
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        return store != null && store.get(serverId, id) != null;
    }

    private boolean beginResourceActivation(String serverId, ReSyncResourceType type, String id, boolean previousEnabled, boolean enabled, String requestId,
                                            CoreGraphOwnerToken ownerToken) {
        synchronized (resourceActivationLock) {
            return pendingActivations.putIfAbsent(new ActivationKey(serverId, type, id),
                new PendingActivation(previousEnabled, enabled, requestId, ownerToken)) == null;
        }
    }

    void completeResourceActivation(String serverId, ReSyncResourceType type, String id, boolean enabled, String requestId, boolean success, String message,
                                    boolean notifyFailure) {
        PendingActivation pending = matchingPendingActivation(serverId, type, id, requestId);
        if (pending != null && pending.ownerToken() != null) {
            enqueueCoreCompletion(pending.ownerToken(),
                () -> completeResourceActivationNow(serverId, type, id, enabled, requestId, success, message, notifyFailure),
                () -> completeResourceActivationNow(serverId, type, id, pending.enabled(), requestId, false,
                    "The ReSync connection changed before activation completed.", true));
            return;
        }
        CoreGraphHandoff handoff = type != null && type.isGraph() ? currentCoreGraphHandoff(serverId, type, id) : null;
        if (handoff != null) {
            enqueueCoreUiTransition(handoff,
                () -> completeResourceActivationNow(serverId, type, id, enabled, requestId, success, message, notifyFailure));
            return;
        }
        if (type != null && type.isGraph() && coreGraphAuthorityEnabled(serverId)) {
            return;
        }
        enqueueCoreUiTransition(serverId, () -> completeResourceActivationNow(serverId, type, id, enabled, requestId, success, message, notifyFailure));
    }

    private PendingActivation matchingPendingActivation(String serverId, ReSyncResourceType type, String id, String requestId) {
        PendingActivation pending = pendingActivations.get(new ActivationKey(serverId, type, id));
        return pending != null && pending.requestId().equals(requestId) ? pending : null;
    }

    private void completeResourceActivationNow(String serverId, ReSyncResourceType type, String id, boolean enabled, String requestId, boolean success,
                                               String message, boolean notifyFailure) {
        ActivationKey key = new ActivationKey(serverId, type, id);
        PendingActivation pending;
        synchronized (resourceActivationLock) {
            pending = pendingActivations.get(key);
            if (pending == null || !pending.requestId().equals(requestId) || pending.enabled() != enabled
                || !pendingActivations.remove(key, pending)) {
                return;
            }
            applyResourceActivationState(serverId, type, id, success ? enabled : pending.previousEnabled());
        }
        refreshStudioWorkspaceNow(serverId, new StudioWorkspaceRefreshSnapshot(true, true));
        if (success) {
            GraphEditorScreen.clearEditorErrorsForServer(serverId, type.typeId(), id);
        } else if (notifyFailure) {
            String detail = message != null && !message.isBlank() ? message : "The resource could not be updated.";
            new Notification("Update Failed", detail, Notification.Type.ERROR);
        }
    }

    private void rollbackResourceActivations(String serverId) {
        if (!rollbackResourceActivationsNow(serverId)) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        runIfCurrentServerConnection(token,
            () -> refreshStudioWorkspaceNow(serverId, new StudioWorkspaceRefreshSnapshot(true, true)));
    }

    private boolean rollbackResourceActivationsNow(String serverId) {
        return rollbackResourceActivationsNow(serverId, null);
    }

    private boolean rollbackResourceActivationsNow(String serverId, ReSyncFlowClient retainedOwner) {
        boolean changed = false;
        synchronized (resourceActivationLock) {
            for (Map.Entry<ActivationKey, PendingActivation> entry : pendingActivations.entrySet()) {
                ActivationKey key = entry.getKey();
                PendingActivation pending = entry.getValue();
                if (serverId.equals(key.serverId())
                    && (retainedOwner == null || !retainedOwner.retainsResourceActivation(key.type(), key.id(), pending.requestId()))
                    && pendingActivations.remove(key, pending)) {
                    applyResourceActivationState(serverId, key.type(), key.id(), pending.previousEnabled());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void rebindRetainedResourceActivations(String serverId, ReSyncFlowClient source) {
        rebindRetainedResourceActivations(serverId, source, currentCoreGraphOwnerToken(serverId, source));
    }

    private void rebindRetainedResourceActivations(String serverId, ReSyncFlowClient source, CoreGraphOwnerToken ownerToken) {
        if (serverId == null || serverId.isBlank() || source == null) {
            return;
        }
        synchronized (resourceActivationLock) {
            for (Map.Entry<ActivationKey, PendingActivation> entry : pendingActivations.entrySet()) {
                ActivationKey key = entry.getKey();
                PendingActivation pending = entry.getValue();
                if (!serverId.equals(key.serverId())
                    || !source.retainsResourceActivation(key.type(), key.id(), pending.requestId())
                    || key.type().isGraph() && ownerToken == null) {
                    continue;
                }
                PendingActivation rebound = new PendingActivation(pending.previousEnabled(), pending.enabled(), pending.requestId(),
                    key.type().isGraph() ? ownerToken : null);
                pendingActivations.replace(key, pending, rebound);
            }
        }
    }

    private boolean applyResourceActivationState(String serverId, ReSyncResourceType type, String id, boolean enabled) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()) {
            return false;
        }
        if (type.isGraph()) {
            hydrateCoreGraphProjection(serverId, type, id);
            if (coreGraphUiProjection.authoritative(serverId, type, id) || coreGraphAuthorityEnabled(serverId)) {
                return true;
            }
            return flowStore.update(serverId, type, id, graph -> {
                graph.setEnabled(enabled);
                return graph;
            });
        }
        SyncedResourceCache<?> store = resourceStore(type);
        return store != null && store.update(serverId, id, resource -> {
            setEnabled(resource, enabled);
            return resource;
        });
    }

    private record ActivationKey(String serverId, ReSyncResourceType type, String id) {
    }

    private record PendingActivation(boolean previousEnabled, boolean enabled, String requestId, CoreGraphOwnerToken ownerToken) {
    }

    private record CoreGraphOwnerToken(ReSyncFlowClient source, long generation, String serverId) {
    }

    private record CoreUiTransition(Runnable effect, boolean ownerManaged, ServerConnectionToken token) {
    }

    private record CoreGraphListenerSubscription(ReSyncFlowClient source, long generation,
                                                  ReSyncFlowClient.CoreGraphResourceSubscription resourceSubscription,
                                                  ReSyncFlowClient.CoreGraphListSubscription listSubscription) {
        private void close() {
            try {
                resourceSubscription.close();
            } finally {
                listSubscription.close();
            }
        }
    }

    private record CoreGraphHandoff(ReSyncFlowClient source, long generation, String serverId,
                                    ReSyncResourceType type, String id, boolean deleted) {
        private static CoreGraphHandoff from(ReSyncFlowClient source, long generation,
                                             ReSyncFlowClient.CoreGraphResourceTransition transition) {
            return new CoreGraphHandoff(source, generation, transition.resource().serverId().canonicalText(),
                transition.type(), transition.resource().id(), transition.projection().tombstoned());
        }

        private boolean matches(String serverId, ReSyncResourceType type, String id) {
            return this.serverId.equals(serverId) && this.type == type && this.id.equals(id);
        }

        private CoreGraphOwnerToken ownerToken() {
            return new CoreGraphOwnerToken(source, generation, serverId);
        }
    }

    private record CoreGraphSessionKey(String serverId, ReSyncResourceType type, String id) {
        private CoreGraphSessionKey {
            if (serverId == null || serverId.isBlank()) {
                throw new IllegalArgumentException("Core editor server ID is required");
            }
            if (type == null || !type.isGraph()) {
                throw new IllegalArgumentException("Core editor resource type is required");
            }
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Core editor resource ID is required");
            }
        }
    }

    record CoreGraphHydrationLifecycle(long transportGeneration, long authorityEpoch, long firstAttemptNanos,
                                       long nextAttemptNanos, long deadlineNanos, int attempts,
                                       String terminalReason) {
        CoreGraphHydrationLifecycle {
            terminalReason = terminalReason == null ? "" : terminalReason;
        }

        private static CoreGraphHydrationLifecycle start(long transportGeneration, long authorityEpoch, long now) {
            return new CoreGraphHydrationLifecycle(transportGeneration, authorityEpoch, now, now,
                now + CORE_GRAPH_HYDRATION_TIMEOUT_NANOS, 0, "");
        }

        boolean terminal() {
            return !terminalReason.isBlank();
        }

        private boolean sameEpoch(long transportGeneration, long authorityEpoch) {
            return this.transportGeneration == transportGeneration && this.authorityEpoch == authorityEpoch;
        }

        private boolean olderThan(long transportGeneration, long authorityEpoch) {
            return this.transportGeneration < transportGeneration
                || this.transportGeneration == transportGeneration && this.authorityEpoch < authorityEpoch;
        }

        private CoreGraphHydrationLifecycle attempted(long now) {
            return new CoreGraphHydrationLifecycle(transportGeneration, authorityEpoch, firstAttemptNanos,
                now + CORE_GRAPH_HYDRATION_RETRY_NANOS, deadlineNanos, attempts + 1, terminalReason);
        }

        private CoreGraphHydrationLifecycle completed(String reason) {
            return terminal() ? this : new CoreGraphHydrationLifecycle(transportGeneration, authorityEpoch,
                firstAttemptNanos, nextAttemptNanos, deadlineNanos, attempts, reason);
        }
    }

    record CoreGraphHydrationDecision(boolean dispatch, boolean becameTerminal, String reason) {
        private static CoreGraphHydrationDecision dispatchNow() {
            return new CoreGraphHydrationDecision(true, false, "");
        }

        private static CoreGraphHydrationDecision waitForCadence() {
            return new CoreGraphHydrationDecision(false, false, "cadence");
        }

        private static CoreGraphHydrationDecision terminal(boolean becameTerminal, String reason) {
            return new CoreGraphHydrationDecision(false, becameTerminal, reason);
        }
    }

    static final class CoreGraphHydrationTracker<K> {
        private final Map<K, CoreGraphHydrationLifecycle> lifecycles = new HashMap<>();

        synchronized void activate(K key, long transportGeneration, long authorityEpoch, long now) {
            CoreGraphHydrationLifecycle lifecycle = lifecycles.get(key);
            if (lifecycle == null || lifecycle.olderThan(transportGeneration, authorityEpoch)
                || (lifecycle.terminal() && lifecycle.sameEpoch(transportGeneration, authorityEpoch))) {
                lifecycles.put(key, CoreGraphHydrationLifecycle.start(transportGeneration, authorityEpoch, now));
            }
        }

        synchronized CoreGraphHydrationDecision begin(K key, long transportGeneration, long authorityEpoch,
                                                      long now) {
            CoreGraphHydrationLifecycle lifecycle = lifecycles.get(key);
            if (lifecycle == null || lifecycle.olderThan(transportGeneration, authorityEpoch)) {
                lifecycle = CoreGraphHydrationLifecycle.start(transportGeneration, authorityEpoch, now);
                lifecycles.put(key, lifecycle);
            } else if (!lifecycle.sameEpoch(transportGeneration, authorityEpoch)) {
                return CoreGraphHydrationDecision.terminal(false, "stale_epoch");
            }
            if (lifecycle.terminal()) {
                return CoreGraphHydrationDecision.terminal(false, lifecycle.terminalReason());
            }
            if (now - lifecycle.deadlineNanos() >= 0L) {
                CoreGraphHydrationLifecycle terminal = lifecycle.completed("deadline_exceeded");
                lifecycles.put(key, terminal);
                return CoreGraphHydrationDecision.terminal(true, terminal.terminalReason());
            }
            if (now - lifecycle.nextAttemptNanos() < 0L) {
                return CoreGraphHydrationDecision.waitForCadence();
            }
            lifecycles.put(key, lifecycle.attempted(now));
            return CoreGraphHydrationDecision.dispatchNow();
        }

        synchronized void complete(K key, long transportGeneration, long authorityEpoch, boolean reconciled) {
            if (!reconciled) {
                return;
            }
            CoreGraphHydrationLifecycle lifecycle = lifecycles.get(key);
            if (lifecycle != null && lifecycle.sameEpoch(transportGeneration, authorityEpoch)) {
                lifecycles.put(key, lifecycle.completed("complete"));
            }
        }

        synchronized CoreGraphHydrationLifecycle lifecycle(K key) {
            return lifecycles.get(key);
        }

        synchronized int size() {
            return lifecycles.size();
        }

        synchronized void remove(K key) {
            lifecycles.remove(key);
        }

        synchronized void removeIf(Predicate<K> predicate) {
            lifecycles.keySet().removeIf(predicate);
        }
    }

    private record CoreBaselineProof(CoreGraphUiProjection.Baseline authority, GraphDocument graph,
                                     FunctionSourceDocument source) {
        private boolean matches(CoreGraphEditorSession session, CoreGraphUiProjection.Baseline baseline) {
            return authority == baseline && (graph != null ? graph == session.baselineGraphDocument()
                : source != null && source == session.baselineFunctionSourceDocument());
        }
    }

    private record CoreGraphSessionState(CoreGraphEditorSession session, ServerConnectionToken token, boolean stale,
                                         CoreBaselineProof baselineProof) {
        private CoreGraphSessionState(CoreGraphEditorSession session, ServerConnectionToken token, boolean stale) {
            this(session, token, stale, null);
        }

        private CoreGraphSessionState(CoreGraphEditorSession session, ServerConnectionToken token) {
            this(session, token, false);
        }

        private CoreGraphSessionState withBaselineProof(CoreGraphUiProjection.Baseline baseline) {
            if (baselineProof != null && baselineProof.matches(session, baseline)) {
                return this;
            }
            return new CoreGraphSessionState(session, token, stale, new CoreBaselineProof(baseline,
                session.baselineGraphDocument(), session.baselineFunctionSourceDocument()));
        }

        private CoreGraphSessionState staleState() {
            return stale ? this : new CoreGraphSessionState(session, token, true);
        }
    }

    private record CoreTemplateIntent(ServerResourceLocator resource, String title, String branchPin,
                                      CreationMetadataIntent metadata, Consumer<CreationResult> observer,
                                      int rolloverAttempts) {
        private CoreTemplateIntent(ServerResourceLocator resource, String title, String branchPin,
                                   CreationMetadataIntent metadata, Consumer<CreationResult> observer) {
            this(resource, title, branchPin, metadata, observer, 0);
        }

        private CoreTemplateIntent {
            if (rolloverAttempts < 0 || rolloverAttempts > MAX_CORE_TEMPLATE_ROLLOVER_RETRIES) {
                throw new IllegalArgumentException("Core template rollover attempts are out of bounds");
            }
        }

        private CoreTemplateIntent rollover() {
            if (rolloverAttempts >= MAX_CORE_TEMPLATE_ROLLOVER_RETRIES) {
                return null;
            }
            return new CoreTemplateIntent(resource, title, branchPin, metadata, observer, rolloverAttempts + 1);
        }
    }

    public Map<String, JsonObject> getJsonResourcesForServer(String serverId, ReSyncResourceType type) {
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        return store != null ? store.getForServer(serverId) : Map.of();
    }

    public JsonObject getJsonResource(String serverId, ReSyncResourceType type, String id) {
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        return store != null ? store.getOwned(serverId, id) : null;
    }

    private void hydrateCoreGraphProjections(String serverId, ReSyncResourceType type) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()) {
            return;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            return;
        }
        if (!attachCoreGraphListener(flowClient)) {
            return;
        }
        flowClient.coreGraphResourceCache().states().values().stream()
            .filter(state -> matchesCoreResource(state.resource(), serverId, type, state.resource().id()))
            .forEach(state -> applyHydratedCoreGraphState(flowClient, type, state));
        flowClient.coreGraphResourceCache().readOnlyProjections().values().stream()
            .filter(projection -> matchesCoreResource(projection.resource(), serverId, type, projection.resource().id()))
            .forEach(projection -> applyHydratedCoreGraphProjection(flowClient, type, projection));
    }

    private void hydrateCoreGraphProjection(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return;
        }
        CoreGraphSessionKey key = new CoreGraphSessionKey(serverId, type, id);
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        if (!isCurrentServerConnection(token)) {
            return;
        }
        long authorityEpoch = flowClient.resourceRevisionReconciler().authorityEpoch(serverId);
        if (currentEditableCoreBaseline(key)) {
            coreGraphHydrations.complete(key, token.generation(), authorityEpoch, true);
            return;
        }
        if (!attachCoreGraphListener(flowClient)) {
            return;
        }
        flowClient.coreGraphResourceCache().states().values().stream()
            .filter(candidate -> matchesCoreResource(candidate.resource(), serverId, type, id))
            .findFirst()
            .ifPresent(candidate -> applyHydratedCoreGraphState(flowClient, type, candidate));
        if (currentEditableCoreBaseline(key)) {
            coreGraphHydrations.complete(key, token.generation(), authorityEpoch, true);
            return;
        }
        flowClient.coreGraphResourceCache().readOnlyProjections().values().stream()
            .filter(projection -> matchesCoreResource(projection.resource(), serverId, type, id))
            .findFirst()
            .ifPresent(projection -> applyHydratedCoreGraphProjection(flowClient, type, projection));
        boolean publicationRefreshRequired = coreGraphPublicationRefreshRequired(flowClient);
        if (!currentEditableCoreBaseline(key) && beginCoreGraphHydration(key, token, authorityEpoch)) {
            CoreGraphHydrationLifecycle hydration = coreGraphHydrations.lifecycle(key);
            if (publicationRefreshRequired && hydration != null && hydration.attempts() == 1) {
                flowClient.ensureCatalogPublication(true);
            }
            flowClient.requestResource(type, id, false);
        }
    }

    private boolean coreGraphPublicationRefreshRequired(ReSyncFlowClient flowClient) {
        return flowClient.activeAuthoringPublication().isEmpty();
    }

    private boolean currentEditableCoreBaseline(CoreGraphSessionKey key) {
        CoreGraphUiProjection.Baseline baseline = coreGraphUiProjection.baseline(
            key.serverId(), key.type(), key.id()).orElse(null);
        CoreGraphSessionState state = coreGraphEditorSessions.get(key);
        CatalogAuthoringPublication publication = baseline != null
            ? activeAuthoringPublication(locatorOf(baseline.payload())).orElse(null) : null;
        return currentEditableCoreBaseline(baseline, publication, state != null ? state.session() : null,
            state != null && state.stale());
    }

    static boolean currentEditableCoreBaseline(CoreGraphUiProjection.Baseline baseline,
                                               CatalogAuthoringPublication publication,
                                               CoreGraphEditorSession retainedSession,
                                               boolean retainedSessionStale) {
        return baseline != null && baseline.canSave() && matchesCoreBaselinePublication(baseline, publication)
            && (retainedSession == null || !retainedSessionStale
                && matchesCorePublication(retainedSession, publication)
                && matchesCoreSessionBaseline(retainedSession, baseline));
    }

    private boolean beginCoreGraphHydration(CoreGraphSessionKey key, ServerConnectionToken token,
                                            long authorityEpoch) {
        long now = System.nanoTime();
        CoreGraphHydrationDecision decision = coreGraphHydrations.begin(key, token.generation(), authorityEpoch,
            now);
        if (decision.becameTerminal()) {
            CoreGraphHydrationLifecycle lifecycle = coreGraphHydrations.lifecycle(key);
            long elapsedMillis = lifecycle != null
                ? BrowserSafeState.nanosToMillis(Math.max(0L, now - lifecycle.firstAttemptNanos())) : 0L;
            ReSyncFlowClient.traceLifecycle(key.serverId(), "core_graph_hydration_terminal", "serverId",
                key.serverId(), "resourceKey", key.type().typeId() + ":" + key.id(), "requestId", null,
                "mutationId", null, "generation", token.generation(), "authorityEpoch", authorityEpoch,
                "revision", 0L, "reason", decision.reason(), "attempts",
                lifecycle != null ? lifecycle.attempts() : 0, "elapsedMs", elapsedMillis);
        }
        return decision.dispatch();
    }

    private void applyHydratedCoreGraphState(ReSyncFlowClient source, ReSyncResourceType type, GraphResourceState state) {
        CoreGraphOwnerToken token = currentCoreGraphOwnerToken(source.getServerId(), source);
        runCoreGraphOwnerEffect(token, () -> {
            if (synchronizeLegacyReadAuthorityEpoch(source.getServerId())) {
                coreGraphUiProjection.apply(type, state, false,
                    source.resourceRevisionReconciler().authorityEpoch(source.getServerId()));
                revalidateCoreGraphEditorSession(state.resource(), source, token.generation());
                completeCoreGraphHydration(source, type, state.resource().id(), token.generation());
            }
        });
    }

    private void applyHydratedCoreGraphProjection(ReSyncFlowClient source, ReSyncResourceType type,
                                                  CoreGraphResourceProjection.Projection projection) {
        CoreGraphOwnerToken token = currentCoreGraphOwnerToken(source.getServerId(), source);
        runCoreGraphOwnerEffect(token, () -> {
            if (synchronizeLegacyReadAuthorityEpoch(source.getServerId())) {
                coreGraphUiProjection.apply(type, projection);
                revalidateCoreGraphEditorSession(projection.resource(), source, token.generation());
                completeCoreGraphHydration(source, type, projection.resource().id(), token.generation());
            }
        });
    }

    private void completeCoreGraphHydration(ReSyncFlowClient source, ReSyncResourceType type, String id,
                                            long generation) {
        CoreGraphSessionKey key = new CoreGraphSessionKey(source.getServerId(), type, id);
        boolean reconciled = currentEditableCoreBaseline(key);
        coreGraphHydrations.complete(key, generation,
            source.resourceRevisionReconciler().authorityEpoch(source.getServerId()), reconciled);
    }

    private void revalidateCoreGraphEditorSession(ServerResourceLocator resource, ReSyncFlowClient source,
                                                  long generation) {
        if (resource == null || source == null || generation <= 0L) {
            return;
        }
        ReSyncResourceType type = ReSyncResourceType.byTypeId(resource.resourceType().value());
        if (type == null || !type.isGraph()) {
            return;
        }
        String serverId = resource.serverId().canonicalText();
        CoreGraphSessionKey key = new CoreGraphSessionKey(serverId, type, resource.id());
        synchronized (serverConnectionGenerationLock) {
            long connectionGeneration = serverConnectionGenerations.getOrDefault(serverId, 0L);
            ServerConnectionToken token = new ServerConnectionToken(serverId, source, connectionGeneration);
            if (!isCurrentServerConnectionLocked(token)) {
                return;
            }
            synchronized (coreGraphEditorSessions) {
                CoreGraphSessionState state = coreGraphEditorSessions.get(key);
                if (state == null || state.session() == null) {
                    return;
                }
                CoreGraphUiProjection.Baseline baseline = coreGraphUiProjection.baseline(token.serverId(), type, resource.id())
                    .orElse(null);
                if (baseline == null || !baseline.canSave()) {
                    return;
                }
                CoreGraphSessionState candidate = new CoreGraphSessionState(state.session(), token, false);
                CoreGraphSessionState reconciled = reconcileCoreGraphSession(key, candidate, token);
                coreGraphEditorSessions.put(key, reconciled);
            }
        }
    }

    private static boolean matchesCoreResource(ServerResourceLocator resource,
                                               String serverId, ReSyncResourceType type, String id) {
        return resource != null && serverId != null && serverId.equals(resource.serverId().canonicalText())
            && type != null && type.typeId().equals(resource.resourceType().value())
            && id != null && id.equals(resource.id());
    }

    private boolean coreGraphAuthorityEnabled(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            return false;
        }
        if (flowClient.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY) {
            return true;
        }
        return flowClient.coreGraphResourceCache().states().keySet().stream()
            .anyMatch(resource -> serverId.equals(resource.serverId().canonicalText()))
            || flowClient.coreGraphResourceCache().readOnlyProjections().values().stream()
            .anyMatch(projection -> serverId.equals(projection.resource().serverId().canonicalText()));
    }

    public boolean coreGraphCreationRequired(String serverId, ReSyncResourceType type) {
        return type != null && type.isGraph() && coreGraphAuthorityEnabled(serverId);
    }

    public boolean isCoreGraphAuthorityEnabled(String serverId, ReSyncResourceType type) {
        return type != null && type.isGraph() && coreGraphAuthorityEnabled(serverId);
    }

    private boolean coreGraphResourceKnown(ReSyncFlowClient flowClient, ReSyncResourceType type, String id) {
        if (flowClient == null || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return false;
        }
        return flowClient.coreGraphResourceCache().states().values().stream()
            .anyMatch(state -> matchesCoreResource(state.resource(), flowClient.getServerId(), type, id))
            || flowClient.coreGraphResourceCache().readOnlyProjections().values().stream()
            .anyMatch(projection -> matchesCoreResource(projection.resource(), flowClient.getServerId(), type, id));
    }

    public Map<String, FlowGraph> getGraphsForServer(String serverId, ReSyncResourceType type) {
        if (type == null || !type.isGraph()) {
            return Map.of();
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null && !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return Map.of();
        }
        hydrateCoreGraphProjections(serverId, type);
        Map<String, FlowGraph> result = new LinkedHashMap<>();
        for (Map.Entry<CoreGraphUiProjection.Key, CoreGraphUiProjection.Baseline> entry : coreGraphUiProjection.baselines().entrySet()) {
            if (entry.getKey().serverId().equals(serverId) && entry.getKey().type() == type) {
                FlowGraph graph = detachedGraph(entry.getValue().graph());
                result.put(entry.getKey().id(), applyPendingCoreActivation(serverId, type, entry.getKey().id(), graph));
            }
        }
        if (!coreGraphAuthorityEnabled(serverId)) {
            flowStore.getForServer(serverId, type).forEach((id, graph) -> {
                if (!coreGraphUiProjection.authoritative(serverId, type, id)) {
                    result.putIfAbsent(id, detachedGraph(graph));
                }
            });
        }
        return result;
    }

    public List<String> getCachedGraphIdsForServer(String serverId, ReSyncResourceType type) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()) {
            return List.of();
        }
        Set<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        TypedMembershipState membership = typedMemberships.get(new TypedMembershipKey(serverId, type));
        if (membership != null) {
            ids.addAll(membership.ids());
            ids.removeAll(membership.tombstones());
        }
        coreGraphUiProjection.baselines().keySet().stream()
            .filter(key -> serverId.equals(key.serverId()) && type == key.type())
            .map(CoreGraphUiProjection.Key::id)
            .forEach(ids::add);
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            flowClient.coreGraphResourceCache().states().keySet().stream()
                .filter(resource -> resource != null && matchesCoreResource(resource, serverId, type, resource.id()))
                .map(ServerResourceLocator::id)
                .forEach(ids::add);
            flowClient.coreGraphResourceCache().readOnlyProjections().values().stream()
                .map(CoreGraphResourceProjection.Projection::resource)
                .filter(resource -> resource != null && matchesCoreResource(resource, serverId, type, resource.id()))
                .map(ServerResourceLocator::id)
                .forEach(ids::add);
        }
        if (!coreGraphAuthorityEnabled(serverId)) {
            ids.addAll(flowStore.getForServer(serverId, type).keySet());
        }
        return List.copyOf(ids);
    }

    public FlowGraph getGraph(String serverId, ReSyncResourceType type, String id) {
        if (type == null || !type.isGraph()) {
            return null;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null && !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return null;
        }
        hydrateCoreGraphProjection(serverId, type, id);
        if (coreGraphUiProjection.authoritative(serverId, type, id)) {
            return coreGraphUiProjection.graph(serverId, type, id).map(this::detachedGraph).orElse(null);
        }
        if (coreGraphAuthorityEnabled(serverId)) {
            return null;
        }
        return flowStore.getOwned(serverId, type, id);
    }

    long authoritativeResourceRevision(String serverId, ReSyncResourceType type, String id) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return 0L;
        }
        hydrateCoreGraphProjection(serverId, type, id);
        Optional<CoreGraphUiProjection.Baseline> baseline = coreGraphUiProjection.baseline(serverId, type, id);
        if (baseline.isPresent()) {
            return Math.max(0L, baseline.orElseThrow().revision());
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            for (GraphResourceState state : flowClient.coreGraphResourceCache().states().values()) {
                if (matchesCoreResource(state.resource(), serverId, type, id)) {
                    return Math.max(0L, state.revision());
                }
            }
        }
        if (coreGraphAuthorityEnabled(serverId)) {
            return 0L;
        }
        FlowGraph graph = getGraph(serverId, type, id);
        return graph == null ? 0L : Math.max(0L, graph.getResourceRevision());
    }

    public void discardGraphDraft(String serverId, ReSyncResourceType type, String id) {
        discardResourceDraft(serverId, type, id);
    }

    public void discardResourceDraft(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()) {
            return;
        }
        if (type.isGraph()) {
            hydrateCoreGraphProjection(serverId, type, id);
            if (coreGraphUiProjection.authoritative(serverId, type, id)) {
                return;
            }
            if (coreGraphAuthorityEnabled(serverId)) {
                return;
            }
            FlowGraph draft = flowStore.getFromDraft(serverId, type, id);
            flowStore.discardDraft(serverId, type, id);
            CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(draft);
            if (content != null && content.getId() != null) {
                customContentStore.discardDraft(serverId, content.getId());
            }
        } else if (type == ReSyncResourceType.GUI) {
            guiStore.discardDraft(serverId, id);
        } else if (type == ReSyncResourceType.SCOREBOARD) {
            scoreboardStore.discardDraft(serverId, id);
        } else if (type == ReSyncResourceType.TAB) {
            tabStore.discardDraft(serverId, id);
        } else if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            customContentStore.discardDraft(serverId, id);
        } else if (type == ReSyncResourceType.PROJECT_METADATA) {
            projectMetadataStore.discardDraft(serverId, serverId);
        } else {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            if (store != null) {
                store.discardDraft(serverId, id);
            }
        }
    }

    public boolean hasServerGraph(String serverId, ReSyncResourceType type, String id) {
        return getGraph(serverId, type, id) != null;
    }

    public boolean hasLoadedGraphList(String serverId, ReSyncResourceType type) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph()) {
            return false;
        }
        hydrateCoreGraphProjections(serverId, type);
        return coreGraphUiProjection.baselines().keySet().stream()
            .anyMatch(key -> key.serverId().equals(serverId) && key.type() == type)
            || flowStore.hasLoadedServerList(serverId, type);
    }

    private boolean missingFromAuthoritativeGraphList(String serverId, ReSyncResourceType type, String id) {
        if (coreGraphUiProjection.tombstoned(serverId, type, id)) {
            return true;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null || !flowClient.isResourceListAuthoritative(type)) {
            return false;
        }
        return !flowStore.containsServerId(serverId, type, id)
            && !coreGraphResourceKnown(flowClient, type, id);
    }

    public ReSyncResourceType getGraphType(String serverId, String id) {
        List<ReSyncResourceType> types = graphTypes(serverId, id);
        return types.size() == 1 ? types.getFirst() : null;
    }

    private List<ReSyncResourceType> graphTypes(String serverId, String id) {
        List<ReSyncResourceType> types = new ArrayList<>();
        for (ReSyncResourceType type : List.of(ReSyncResourceType.FLOW, ReSyncResourceType.FUNCTION, ReSyncResourceType.COMMAND)) {
            if (getGraph(serverId, type, id) != null) {
                types.add(type);
            }
        }
        return types;
    }

    public boolean hasGraphForServer(String serverId, String type, String id) {
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (resourceType == null || !resourceType.isGraph()) {
            return false;
        }
        return getGraph(serverId, resourceType, id) != null;
    }

    public Map<String, GuiDefinition> getGuisForServer(String serverId) {
        return guiStore.getForServer(serverId);
    }

    public GuiDefinition getGui(String serverId, String guiId) {
        return guiStore.getOwned(serverId, guiId);
    }

    public Map<String, ScoreboardDefinition> getScoreboardsForServer(String serverId) {
        return scoreboardStore.getForServer(serverId);
    }

    public ScoreboardDefinition getScoreboard(String serverId, String scoreboardId) {
        return scoreboardStore.getOwned(serverId, scoreboardId);
    }

    public String resolveScoreboardId(String serverId, String objectiveId) {
        if (serverId == null || serverId.isBlank() || objectiveId == null || objectiveId.isBlank()) {
            return objectiveId;
        }
        for (ScoreboardDefinition scoreboard : scoreboardStore.getForServer(serverId).values()) {
            if (scoreboard == null || scoreboard.getId() == null) {
                continue;
            }
            String expectedObjectiveId = scoreboard.getObjectiveId() != null && !scoreboard.getObjectiveId().isBlank() ? scoreboard.getObjectiveId() : scoreboard.getId();
            if (objectiveId.equals(expectedObjectiveId)) {
                return scoreboard.getId();
            }
        }
        return objectiveId;
    }

    public Map<String, TabDefinition> getTabsForServer(String serverId) {
        return tabStore.getForServer(serverId);
    }

    public TabDefinition getTab(String serverId, String tabId) {
        return tabStore.getOwned(serverId, tabId);
    }

    public Map<String, CustomContentDefinition> getCustomContentForServer(String serverId) {
        return customContentStore.getForServer(serverId);
    }

    public CustomContentDefinition getCustomContent(String serverId, String contentId) {
        return customContentStore.getOwned(serverId, contentId);
    }

    public long getCustomContentRevision(String serverId, String contentId) {
        return customContentStore.currentGeneration(serverId, contentId);
    }

    public CustomContentAuthority customContentAuthority(String serverId, String contentId) {
        ReSyncFlowClient source = connectionManager.getFlowClient(serverId);
        if (source == null) {
            return null;
        }
        long generation = customContentStore.currentGeneration(serverId, contentId);
        ReSyncResourceRevisionReconciler.ResourceResult result = source.resourceRevisionReconciler()
            .get(serverId, ReSyncResourceType.CUSTOM_CONTENT.typeId(), contentId);
        if (result == null || result.deleted() || result.payload() == null || result.revision() < 1L
            || generation != customContentStore.currentGeneration(serverId, contentId)
            || source != connectionManager.getFlowClient(serverId)) {
            return null;
        }
        return new CustomContentAuthority(source, generation, result);
    }

    public ReSyncResourceRevisionReconciler.Stamp customContentStamp(String serverId, String contentId) {
        ReSyncFlowClient source = connectionManager.getFlowClient(serverId);
        return source != null ? source.resourceRevisionReconciler().stamp(serverId, ReSyncResourceType.CUSTOM_CONTENT.typeId(), contentId) : null;
    }

    public boolean isCurrentCustomContentAuthority(CustomContentAuthority authority) {
        if (authority == null) {
            return false;
        }
        ReSyncResourceRevisionReconciler.ResourceResult result = authority.result();
        return authority.source() == connectionManager.getFlowClient(result.serverId())
            && authority.generation() == customContentStore.currentGeneration(result.serverId(), result.resourceId())
            && Objects.equals(ReSyncResourceRevisionReconciler.Stamp.from(result), customContentStamp(result.serverId(), result.resourceId()))
            && result.authorityEpoch() == authority.source().resourceRevisionReconciler().authorityEpoch(result.serverId());
    }

    public record CustomContentAuthority(ReSyncFlowClient source, long generation,
                                         ReSyncResourceRevisionReconciler.ResourceResult result) {
        public CustomContentDefinition materialize() {
            return FlowSerializer.deserializeCustomContent(result.payload().toString());
        }
    }

    public String getCustomContentType(String serverId, String contentId) {
        String contentType = customContentStore.getString(serverId, contentId, CustomContentDefinition::getType);
        return contentType != null ? contentType : "";
    }

    public ReSyncProjectMetadata getProjectMetadata(String serverId) {
        String actualServerId = serverId != null ? serverId : "";
        int revision = projectCatalogRevisions.getOrDefault(actualServerId, 0);
        ProjectMetadataView view = projectMetadataViews.get(actualServerId);
        if (view != null && view.revision() == revision) {
            return view.metadata().materialize();
        }
        ProjectMetadataSnapshot metadata = projectMetadataStore.getFromDraft(actualServerId, actualServerId);
        if (metadata == null) {
            metadata = projectMetadataStore.getFromCache(actualServerId, actualServerId);
        }
        if (metadata == null) {
            ReSyncProjectMetadata initial = new ReSyncProjectMetadata(actualServerId);
            initial.ensureDefaultFolders();
            projectMetadataStore.putInDraft(actualServerId, ProjectMetadataSnapshot.from(initial));
            metadata = projectMetadataStore.getFromDraft(actualServerId, actualServerId);
        }
        if (shouldHydrateProjectMetadata(actualServerId)) {
            if (hydratedProjectCatalogRevisions.getOrDefault(actualServerId, -1) != revision) {
                scheduleProjectMetadataHydration(actualServerId, revision,
                    projectMetadataStore.snapshotLease(actualServerId, actualServerId));
            }
        }
        return metadata.materialize();
    }

    public ProjectMetadataEdit editProjectMetadata(String serverId) {
        return new ProjectMetadataEdit(serverId, currentProjectMetadataSnapshot(serverId).edit());
    }

    public List<ReSyncProjectMetadata.ResourceEntry> getProjectResources(String serverId) {
        return deriveProjectResources(currentProjectMetadataSnapshot(serverId), snapshotTypedResourceMembership(serverId));
    }

    public TypedResourceMembershipSnapshot snapshotTypedResourceMembership(String serverId) {
        String actualServerId = serverId != null ? serverId : "";
        ServerConnectionToken token = captureServerConnectionToken(actualServerId);
        if (!isCurrentServerConnection(token)) {
            return new TypedResourceMembershipSnapshot(actualServerId, 0L, List.of(), Set.of());
        }
        Map<String, ProjectResource> resources = new LinkedHashMap<>();
        Set<String> completeTypes = new HashSet<>();
        Set<String> tombstones = new HashSet<>();
        if (!runIfCurrentServerConnection(token, () -> {
            typedMemberships.entrySet().stream()
                .filter(entry -> actualServerId.equals(entry.getKey().serverId())
                    && sameWorkspaceRefreshGeneration(entry.getValue().token(), token))
                .sorted((left, right) -> left.getKey().type().typeId().compareTo(right.getKey().type().typeId()))
                .forEach(entry -> {
                    ReSyncResourceType type = entry.getKey().type();
                    if (entry.getValue().complete()) {
                        completeTypes.add(type.typeId());
                    }
                    for (String id : entry.getValue().ids()) {
                        addTypedMembershipResource(resources, actualServerId, type, id);
                    }
                    for (String id : entry.getValue().tombstones()) {
                        tombstones.add(ReSyncProjectMetadata.resourceKey(type.typeId(), id));
                    }
                });
            ReSyncFlowClient flowClient = token.source();
            flowClient.coreGraphResourceCache().states().values().stream()
                .filter(state -> state != null && state.resource() != null
                    && actualServerId.equals(state.resource().serverId().canonicalText()))
                .sorted((left, right) -> left.resource().canonicalText().compareTo(right.resource().canonicalText()))
                .forEach(state -> addCoreMembershipResource(resources, completeTypes, state.resource()));
            flowClient.coreGraphResourceCache().readOnlyProjections().values().stream()
                .filter(projection -> projection != null && projection.resource() != null
                    && actualServerId.equals(projection.resource().serverId().canonicalText()))
                .sorted((left, right) -> left.resource().canonicalText().compareTo(right.resource().canonicalText()))
                .forEach(projection -> addCoreMembershipResource(resources, completeTypes, projection.resource()));
            WorldGenManager worldGen = WorldGenManager.getInstance();
            WorldGenManager.ProjectListReadSnapshot worldGenSnapshot = worldGen != null
                ? worldGen.snapshotProjectList(actualServerId) : null;
            if (worldGenSnapshot != null && worldGen.isCurrentProjectListSnapshot(worldGenSnapshot)) {
                observeWorldGenMembership(token, worldGenSnapshot);
                completeTypes.add(ReSyncResourceDragPayload.WORLDGEN);
                for (String id : worldGenSnapshot.projectIds().stream().sorted().toList()) {
                    addPresentationMembershipResource(resources, ReSyncResourceDragPayload.WORLDGEN, id, id,
                        ReSyncResourceType.defaultFolderFor(ReSyncResourceDragPayload.WORLDGEN));
                }
            }
            ServerConnectionToken worldToken = worldSnapshotGenerations.get(actualServerId);
            if (sameWorkspaceRefreshGeneration(worldToken, token)) {
                completeTypes.add(ReSyncResourceDragPayload.WORLD);
                getWorldsForServer(actualServerId).keySet().stream().sorted().forEach(id ->
                    addPresentationMembershipResource(resources, ReSyncResourceDragPayload.WORLD, id, id,
                        ReSyncResourceType.defaultFolderFor(ReSyncResourceDragPayload.WORLD)));
            }
        })) {
            return new TypedResourceMembershipSnapshot(actualServerId, 0L, List.of(), Set.of());
        }
        return new TypedResourceMembershipSnapshot(actualServerId, token.generation(), List.copyOf(resources.values()),
            completeTypes, tombstones);
    }

    public boolean isCurrentTypedResourceMembership(TypedResourceMembershipSnapshot snapshot) {
        if (snapshot == null || snapshot.serverId().isBlank() || snapshot.connectionGeneration() <= 0L) {
            return false;
        }
        ServerConnectionToken token = captureServerConnectionToken(snapshot.serverId());
        return token.generation() == snapshot.connectionGeneration() && isCurrentServerConnection(token);
    }

    static List<ReSyncProjectMetadata.ResourceEntry> deriveProjectResources(ProjectMetadataSnapshot metadata,
                                                                             TypedResourceMembershipSnapshot membership) {
        ReSyncProjectMetadata presentation = metadata != null ? metadata.materialize()
            : new ReSyncProjectMetadata(membership != null ? membership.serverId() : "");
        applyTypedMembershipProjection(presentation, membership);
        return presentation.getResources().stream().map(FlowManager::copyProjectResource).toList();
    }

    private static void applyTypedMembershipProjection(ReSyncProjectMetadata metadata,
                                                       TypedResourceMembershipSnapshot membership) {
        if (metadata == null || membership == null) {
            return;
        }
        Set<String> authoritativeKeys = membership.resources().stream().map(ProjectResource::key).collect(Collectors.toSet());
        metadata.getResources().removeIf(resource -> resource != null && (membership.tombstones().contains(resource.key())
            || membership.completeTypes().contains(resource.getType()) && !authoritativeKeys.contains(resource.key())));
        metadata.deduplicateResources();
        Map<String, ReSyncProjectMetadata.ResourceEntry> resourceIndex = new LinkedHashMap<>();
        for (ReSyncProjectMetadata.ResourceEntry resource : metadata.getResources()) {
            if (resource != null) {
                resourceIndex.put(resource.key(), resource);
            }
        }
        for (ProjectResource resource : membership.resources()) {
            ensureProjectResource(metadata, resourceIndex, resource.type(), resource.id(), resource.displayName(), resource.path());
        }
    }

    private void addTypedMembershipResource(Map<String, ProjectResource> resources, String serverId,
                                            ReSyncResourceType type, String id) {
        if (type == null || type == ReSyncResourceType.PROJECT_METADATA || id == null || id.isBlank()) {
            return;
        }
        addPresentationMembershipResource(resources, type.typeId(), id,
            authoritativeMembershipDisplayName(serverId, type, id), authoritativeMembershipDefaultPath(serverId, type, id));
    }

    private void addCoreMembershipResource(Map<String, ProjectResource> resources, Set<String> completeTypes,
                                           ServerResourceLocator resource) {
        ReSyncResourceType type = resource != null ? ReSyncResourceType.byTypeId(resource.resourceType().value()) : null;
        String key = type != null ? ReSyncProjectMetadata.resourceKey(type.typeId(), resource.id()) : "";
        if (type != null && type.isGraph() && (!completeTypes.contains(type.typeId()) || resources.containsKey(key))) {
            addPresentationMembershipResource(resources, type.typeId(), resource.id(), resource.id(),
                canonicalResourcePath(type, resource.id(), type.defaultFolder()));
        }
    }

    private static void addPresentationMembershipResource(Map<String, ProjectResource> resources, String type,
                                                          String id, String displayName, String path) {
        if (resources == null || type == null || type.isBlank() || id == null || id.isBlank()) {
            return;
        }
        String name = displayName == null || displayName.isBlank() ? id : displayName;
        ProjectResource resource = new ProjectResource(type, id, name, ReSyncProjectMetadata.normalizePath(path), -1);
        resources.putIfAbsent(resource.key(), resource);
    }

    private String authoritativeMembershipDisplayName(String serverId, ReSyncResourceType type, String id) {
        Object resource = switch (type) {
            case GUI -> guiStore.getFromCache(serverId, id);
            case SCOREBOARD -> scoreboardStore.getFromCache(serverId, id);
            case TAB -> tabStore.getFromCache(serverId, id);
            case CUSTOM_CONTENT -> customContentStore.getFromCache(serverId, id);
            default -> {
                SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
                yield store != null ? store.getFromCache(serverId, id) : null;
            }
        };
        if (resource == null) {
            return id;
        }
        try {
            String name = type.extractName(resource);
            return name == null || name.isBlank() ? id : name;
        } catch (RuntimeException exception) {
            return id;
        }
    }

    private String authoritativeMembershipDefaultPath(String serverId, ReSyncResourceType type, String id) {
        String folder = type.defaultFolder();
        if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            CustomContentDefinition content = customContentStore.getFromCache(serverId, id);
            String contentType = content != null && content.getType() != null
                ? content.getType().toLowerCase(Locale.ROOT) : "";
            folder = switch (contentType) {
                case "armor" -> "Content/Armor";
                case "block" -> "Content/Blocks";
                case "projectile" -> "Content/Projectiles";
                default -> type.defaultFolder();
            };
        }
        return canonicalResourcePath(type, id, folder);
    }

    private boolean authoritativeTypedMembershipContains(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()) {
            return false;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        TypedMembershipState state = typedMemberships.get(new TypedMembershipKey(serverId, type));
        boolean[] contains = {false};
        return state != null && sameWorkspaceRefreshGeneration(state.token(), token)
            && runIfCurrentServerConnection(token, () -> contains[0] = state.ids().contains(id)) && contains[0];
    }

    static TypedMembershipMutationResult mutateTypedMembershipState(List<String> ids, Set<String> tombstones,
                                                                     String id, boolean present) {
        TreeSet<String> nextIds = new TreeSet<>(ids == null ? List.of() : ids);
        TreeSet<String> nextTombstones = new TreeSet<>(tombstones == null ? Set.of() : tombstones);
        if (id != null && !id.isBlank()) {
            if (present) {
                nextIds.add(id);
                nextTombstones.remove(id);
            } else {
                nextIds.remove(id);
                nextTombstones.add(id);
            }
        }
        return new TypedMembershipMutationResult(List.copyOf(nextIds), Set.copyOf(nextTombstones));
    }

    private boolean mutateTypedMembership(String serverId, ReSyncResourceType type, String id, boolean present,
                                          ResourceProjectionLease lease) {
        if (serverId == null || serverId.isBlank() || type == null || type == ReSyncResourceType.PROJECT_METADATA
            || id == null || id.isBlank() || lease == null || !lease.isValid()) {
            return false;
        }
        ServerConnectionToken token = lease.connectionToken;
        if (token == null || !serverId.equals(token.serverId())) {
            return false;
        }
        boolean[] applied = {false};
        if (!runIfCurrentServerConnection(token, () -> {
            TypedMembershipKey key = new TypedMembershipKey(serverId, type);
            TypedMembershipState stored = typedMemberships.get(key);
            TypedMembershipState before = stored != null && sameWorkspaceRefreshGeneration(stored.token(), token)
                ? stored : null;
            TypedMembershipMutationResult mutation = mutateTypedMembershipState(before != null ? before.ids() : List.of(),
                before != null ? before.tombstones() : Set.of(), id, present);
            if (before != null && before.ids().equals(mutation.ids())
                && before.tombstones().equals(mutation.tombstones())) {
                applied[0] = true;
                return;
            }
            TypedMembershipState after = new TypedMembershipState(token, mutation.ids(), mutation.tombstones(),
                before != null && before.complete(), typedMembershipRevision.incrementAndGet());
            if (!lease.rememberMembership(key, before, after)) {
                return;
            }
            typedMemberships.put(key, after);
            advanceProjectMembershipRevision(serverId);
            applied[0] = true;
        })) {
            return false;
        }
        return applied[0];
    }

    private void publishTypedMembership(ServerConnectionToken token, ReSyncResourceType type, List<String> ids) {
        if (token == null || type == null || type == ReSyncResourceType.PROJECT_METADATA) {
            return;
        }
        List<String> normalized = (ids == null ? List.<String>of() : ids).stream()
            .filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
        runIfCurrentServerConnection(token, () -> {
            TypedMembershipKey key = new TypedMembershipKey(token.serverId(), type);
            TypedMembershipState next = new TypedMembershipState(token, normalized, Set.of(), true,
                typedMembershipRevision.incrementAndGet());
            TypedMembershipState previous = typedMemberships.put(key, next);
            ReSyncFlowClient.traceLifecycle(token.serverId(), "authoritative_membership_applied", "serverId",
                token.serverId(), "resourceKey", type.typeId() + ":catalog", "requestId", "list", "mutationId",
                null, "generation", token.generation(), "authorityEpoch", coreGraphUiProjection.authorityEpoch(
                    token.serverId()), "revision", next.revision(), "memberCount", normalized.size());
            if (previous == null || !sameWorkspaceRefreshGeneration(previous.token(), token)
                || !previous.complete() || !previous.ids().equals(normalized) || !previous.tombstones().isEmpty()) {
                advanceProjectMembershipRevision(token.serverId());
            }
            observeResourceDeleteAbsence(token.serverId(), type, normalized);
        });
    }

    private boolean recordTypedMembershipTombstone(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return false;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        boolean[] changed = {false};
        if (!runIfCurrentServerConnection(token, () -> {
            TypedMembershipKey key = new TypedMembershipKey(serverId, type);
            TypedMembershipState stored = typedMemberships.get(key);
            TypedMembershipState before = stored != null && sameWorkspaceRefreshGeneration(stored.token(), token)
                ? stored : null;
            TypedMembershipMutationResult mutation = mutateTypedMembershipState(before != null ? before.ids() : List.of(),
                before != null ? before.tombstones() : Set.of(), id, false);
            if (before != null && before.ids().equals(mutation.ids())
                && before.tombstones().equals(mutation.tombstones())) {
                return;
            }
            TypedMembershipState after = new TypedMembershipState(token, mutation.ids(), mutation.tombstones(),
                before != null && before.complete(), typedMembershipRevision.incrementAndGet());
            typedMemberships.put(key, after);
            advanceProjectMembershipRevision(serverId);
            changed[0] = true;
        })) {
            return false;
        }
        return changed[0];
    }

    private boolean recordTypedMembershipPresence(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return false;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        boolean[] changed = {false};
        if (!runIfCurrentServerConnection(token, () -> {
            TypedMembershipKey key = new TypedMembershipKey(serverId, type);
            TypedMembershipState stored = typedMemberships.get(key);
            TypedMembershipState before = stored != null && sameWorkspaceRefreshGeneration(stored.token(), token)
                ? stored : null;
            TypedMembershipMutationResult mutation = mutateTypedMembershipState(before != null ? before.ids() : List.of(),
                before != null ? before.tombstones() : Set.of(), id, true);
            if (before != null && before.ids().equals(mutation.ids())
                && before.tombstones().equals(mutation.tombstones())) {
                return;
            }
            TypedMembershipState after = new TypedMembershipState(token, mutation.ids(), mutation.tombstones(),
                before != null && before.complete(), typedMembershipRevision.incrementAndGet());
            typedMemberships.put(key, after);
            advanceProjectMembershipRevision(serverId);
            changed[0] = true;
        })) {
            return false;
        }
        return changed[0];
    }

    public static String canonicalResourcePath(ReSyncResourceType type, String id, String path) {
        String normalizedId = id == null ? "" : id.trim();
        String normalizedPath = ReSyncProjectMetadata.normalizePath(path);
        if (normalizedId.isBlank()) {
            return normalizedPath;
        }
        String folder = normalizedPath;
        if (folder.toLowerCase(Locale.ROOT).endsWith(".json")) {
            int separator = folder.lastIndexOf('/');
            folder = separator < 0 ? "" : folder.substring(0, separator);
        }
        if (type != null) {
            String root = ReSyncProjectMetadata.normalizePath(type.defaultFolder());
            if (folder.isBlank() || !folder.equals(root) && !folder.startsWith(root + "/")) {
                folder = root;
            }
        }
        return ReSyncProjectMetadata.normalizePath(folder.isBlank()
            ? normalizedId + ".json" : folder + "/" + normalizedId + ".json");
    }

    private void observeWorldGenMembership(ServerConnectionToken token,
                                           WorldGenManager.ProjectListReadSnapshot snapshot) {
        WorldGenMembershipVersion next = new WorldGenMembershipVersion(token.generation(), snapshot.authorityEpoch(),
            snapshot.revision());
        WorldGenMembershipVersion previous = worldGenMembershipVersions.put(token.serverId(), next);
        if (!next.equals(previous)) {
            advanceProjectMembershipRevision(token.serverId());
        }
    }

    private void advanceProjectMembershipRevision(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        projectMembershipRevisions.put(serverId, projectMembershipRevision.incrementAndGet());
    }

    public List<ReSyncProjectMetadata.FolderEntry> getProjectFolders(String serverId) {
        return currentProjectMetadataSnapshot(serverId).folders().stream().map(FlowManager::copyProjectFolder).toList();
    }

    public ReSyncProjectMetadata.FolderEntry getProjectFolder(String serverId, String path) {
        return copyProjectFolder(currentProjectMetadataSnapshot(serverId).folder(path));
    }

    public List<ReSyncProjectMetadata.InstalledBundleEntry> getProjectBundles(String serverId) {
        return currentProjectMetadataSnapshot(serverId).bundles().stream().map(FlowManager::copyProjectBundle).toList();
    }

    public long projectMetadataStamp(String serverId) {
        String actualServerId = serverId != null ? serverId : "";
        int revision = projectCatalogRevisions.getOrDefault(actualServerId, 0);
        ProjectMetadataView view = projectMetadataViews.get(actualServerId);
        int identity = view != null && view.revision() == revision ? System.identityHashCode(view.metadata())
            : projectMetadataStore.currentIdentity(actualServerId, actualServerId);
        if (identity == 0) identity = System.identityHashCode(currentProjectMetadataSnapshot(actualServerId));
        long membershipRevision = projectMembershipRevisions.getOrDefault(actualServerId, 0L);
        return Long.rotateLeft((Integer.toUnsignedLong(revision) << 32) | Integer.toUnsignedLong(identity), 1)
            ^ membershipRevision;
    }

    public long projectBrowserStamp(String serverId) {
        String actualServerId = serverId != null ? serverId : "";
        long presentation = currentProjectMetadataSnapshot(actualServerId).browserPresentationStamp();
        long membershipRevision = projectMembershipRevisions.getOrDefault(actualServerId, 0L);
        return Long.rotateLeft(presentation, 1) ^ membershipRevision;
    }

    public void saveProjectMetadata(ProjectMetadataEdit edit, boolean refreshWorkspace) {
        if (edit == null || edit.serverId == null || edit.serverId.isBlank()) return;
        persistProjectMetadata(edit.serverId, edit.editor.freeze());
        refreshStudioWorkspace(edit.serverId, refreshWorkspace);
    }

    public void saveProjectMetadata(ProjectMetadataEdit edit, boolean refreshWorkspace,
                                    DesignerSaveNotifications.SaveTicket ticket) {
        if (edit == null || edit.serverId == null || edit.serverId.isBlank()) {
            failSaveTicket(ticket, "Save Snapshot Rejected");
            return;
        }
        persistProjectMetadata(edit.serverId, edit.editor.freeze(), ticket);
        refreshStudioWorkspace(edit.serverId, refreshWorkspace);
    }

    public Async<Boolean> saveProjectMetadataSettled(ProjectMetadataEdit edit, boolean refreshWorkspace) {
        if (edit == null || edit.serverId == null || edit.serverId.isBlank()) {
            return Async.completed(false);
        }
        String serverId = edit.serverId;
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId,
            ReSyncResourceType.PROJECT_METADATA, serverId, "Project Metadata");
        Async<Boolean> completion = saveTicketCompletion(ticket);
        saveProjectMetadata(edit, refreshWorkspace, ticket);
        return completion.thenApply(saved -> {
            if (!saved) {
                discardResourceDraft(serverId, ReSyncResourceType.PROJECT_METADATA, serverId);
                ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId, true);
                if (flowClient != null) {
                    flowClient.requestProjectMetadataList();
                }
                refreshStudioWorkspace(serverId, true);
            }
            return saved;
        });
    }

    public ResourceReadLease snapshotProjectMetadata(String serverId) {
        String actualServerId = serverId != null ? serverId : "";
        SyncedResourceCache.SnapshotLease<ProjectMetadataSnapshot> lease = projectMetadataStore.snapshotLease(actualServerId, actualServerId);
        return lease != null ? new ResourceReadLease(actualServerId, ReSyncResourceType.PROJECT_METADATA.typeId(), actualServerId,
            "", lease::isCurrent, () -> lease.serialize(ReSyncResourceType.PROJECT_METADATA::serialize), null) : null;
    }

    public ReSyncProjectMetadata.ResourceEntry getProjectResource(String serverId, String type, String id) {
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        return getProjectResources(serverId).stream().filter(resource -> resource.key().equals(key)).findFirst().orElse(null);
    }

    public ManagedResourceCatalog.Descriptor managedResourceDescriptor(String serverId, String typeId) {
        if (typeId == null || typeId.isBlank()) {
            throw new IllegalArgumentException("Managed resource type is required");
        }
        ReSyncFlowClient source = existingFlowClient(serverId);
        if (source != null && source.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY) {
            return ManagedResourceCatalog.fromCapabilities(typeId, getServerCapabilities(serverId));
        }
        NodeRegistry registry = NodeRegistry.getInstance();
        FlowResourceMetadata metadata = registry != null ? registry.getResourceMetadata(serverId, typeId) : null;
        return ManagedResourceCatalog.fromMetadata(typeId, metadata);
    }

    public ReSyncProjectMetadata.ResourceEntry getAuthoritativeProjectResource(String serverId, String type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || type.isBlank() || id == null || id.isBlank()) {
            return null;
        }
        ProjectMetadataSnapshot metadata = projectMetadataStore.getFromCache(serverId, serverId);
        return metadata == null ? null : copyProjectResource(metadata.resource(type, id));
    }

    public long projectMetadataAuthorityGeneration(String serverId) {
        return serverId == null || serverId.isBlank() ? 0L
            : projectMetadataAuthorityGenerations.getOrDefault(serverId, 0L);
    }

    public ReSyncProjectMetadata.ResourceEntry getProjectResource(String serverId, String key) {
        return getProjectResources(serverId).stream().filter(resource -> resource.key().equals(key)).findFirst().orElse(null);
    }

    public ResourceReadLease snapshotResource(String serverId, String typeId, String id) {
        if (ReSyncResourceDragPayload.WORLDGEN.equals(typeId)) {
            WorldGenManager.ProjectReadSnapshot snapshot = WorldGenManager.getInstance().snapshotProject(serverId, id);
            return snapshot != null ? new ResourceReadLease(snapshot.serverId(), typeId, snapshot.projectId(), "",
                () -> WorldGenManager.getInstance().isCurrentProjectSnapshot(snapshot), snapshot::serializedContent, null) : null;
        }
        ReSyncResourceType type = ReSyncResourceType.byTypeId(typeId);
        if (type == null || id == null || id.isBlank()) {
            return null;
        }
        if (type.isGraph()) {
            SyncedResourceCache.SnapshotLease<FlowGraph> lease = flowStore.snapshotLease(serverId, type, id);
            TriggerBinding binding = type == ReSyncResourceType.COMMAND ? getCommandBinding(serverId, id) : null;
            String context = binding == null || binding.getContext() == null ? "" : binding.getContext();
            return lease != null ? new ResourceReadLease(serverId, typeId, id, context, lease::isCurrent,
                () -> lease.serialize(graph -> FlowSerializer.toSnapshotJsonObject(graph).toString()), null) : null;
        }
        return switch (type) {
            case GUI -> resourceReadLease(serverId, typeId, id, guiStore.snapshotLease(serverId, id), FlowSerializer::serializeGui,
                GuiDefinition.class);
            case SCOREBOARD -> resourceReadLease(serverId, typeId, id, scoreboardStore.snapshotLease(serverId, id),
                FlowSerializer::serializeScoreboard, ScoreboardDefinition.class);
            case TAB -> resourceReadLease(serverId, typeId, id, tabStore.snapshotLease(serverId, id), FlowSerializer::serializeTab,
                TabDefinition.class);
            case CUSTOM_CONTENT -> resourceReadLease(typeId, id, customContentStore.snapshotLease(serverId, id), FlowSerializer::serializeCustomContent);
            case PROJECT_METADATA -> snapshotProjectMetadata(serverId);
            default -> {
                SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
                yield store != null ? resourceReadLease(serverId, typeId, id, store.snapshotLease(serverId, id),
                    JsonObject::toString, JsonObject.class) : null;
            }
        };
    }

    public ResourceReadLease snapshotAuthoritativeResource(String serverId, String typeId, String id) {
        ReSyncResourceType type = ReSyncResourceType.byTypeId(typeId);
        if (type == null || !type.isGraph() || id == null || id.isBlank()) {
            return null;
        }
        SyncedResourceCache.SnapshotLease<FlowGraph> lease = flowStore.snapshotAuthoritativeLease(serverId, type, id);
        return lease != null ? new ResourceReadLease(serverId, typeId, id, "", lease::isCurrent,
            () -> lease.serialize(graph -> FlowSerializer.toSnapshotJsonObject(graph).toString()), null) : null;
    }

    private <T> ResourceReadLease resourceReadLease(String type, String id, SyncedResourceCache.SnapshotLease<T> lease,
                                                    Function<? super T, String> serializer) {
        return lease != null ? new ResourceReadLease("", type, id, "", lease::isCurrent, () -> lease.serialize(serializer), null) : null;
    }

    private <T> ResourceReadLease resourceReadLease(String serverId, String type, String id,
                                                    SyncedResourceCache.SnapshotLease<T> lease,
                                                    Function<? super T, String> serializer, Class<T> valueType) {
        return lease != null ? new ResourceReadLease(serverId, type, id, "", lease::isCurrent,
            () -> lease.serialize(serializer), value -> {
                @SuppressWarnings("unchecked")
                T typed = (T) value;
                return lease.compareAndPutInDraft(typed);
            }) : null;
    }

    private void scheduleProjectMetadataHydration(String serverId, int revision,
                                                  SyncedResourceCache.SnapshotLease<ProjectMetadataSnapshot> snapshot) {
        if (serverId == null || serverId.isBlank() || snapshot == null) {
            return;
        }
        pendingProjectMetadataHydrations.compute(serverId, (ignored, current) -> current == null
            || revision >= current.revision() ? new ProjectMetadataHydration(revision, snapshot) : current);
        if (scheduledProjectMetadataHydrations.putIfAbsent(serverId, revision) != null) {
            return;
        }
        try {
            projectMetadataHydrations.execute(() -> drainProjectMetadataHydrations(serverId));
        } catch (IllegalStateException exception) {
            scheduledProjectMetadataHydrations.remove(serverId);
        }
    }

    private void drainProjectMetadataHydrations(String serverId) {
        try {
            ProjectMetadataHydration hydration;
            while (!closed && (hydration = pendingProjectMetadataHydrations.remove(serverId)) != null) {
                hydrateProjectMetadata(serverId, hydration.revision(), hydration.snapshot());
            }
        } finally {
            scheduledProjectMetadataHydrations.remove(serverId);
            ProjectMetadataHydration pending = pendingProjectMetadataHydrations.get(serverId);
            if (!closed && pending != null) {
                scheduleProjectMetadataHydration(serverId, pending.revision(), pending.snapshot());
            }
        }
    }

    private void hydrateProjectMetadata(String serverId, int revision,
                                        SyncedResourceCache.SnapshotLease<ProjectMetadataSnapshot> snapshot) {
        if (!snapshot.isCurrent()) {
            return;
        }
        ReSyncProjectMetadata metadata = snapshot.materialize(ProjectMetadataSnapshot::materialize);
        if (metadata == null) {
            return;
        }
        hydrateProjectMetadata(serverId, metadata);
        if (closed || !snapshot.isCurrent() || projectCatalogRevisions.getOrDefault(serverId, 0) != revision) {
            return;
        }
        projectMetadataViews.put(serverId, new ProjectMetadataView(revision, ProjectMetadataSnapshot.from(metadata)));
        hydratedProjectCatalogRevisions.put(serverId, revision);
        ScreenManager.getInstance().execute(() -> {
            if (!closed && !hasPendingCreationForServer(serverId)
                && projectCatalogRevisions.getOrDefault(serverId, 0) == revision) {
                scheduleStudioWorkspaceRefresh(serverId, true, false);
            }
        });
    }

    private void invalidateProjectCatalog(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        projectCatalogRevisions.put(serverId, projectCatalogRevision.incrementAndGet());
        hydratedProjectCatalogRevisions.remove(serverId);
        projectMetadataViews.remove(serverId);
    }

    private void invalidateCustomContentOptionCatalogs(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        if (!runIfCurrentServerConnection(token,
            () -> OptionCatalogCache.getInstance().markAllStale(serverId, CUSTOM_CONTENT_OPTION_CATALOGS))) {
            return;
        }
        ScreenManager.getInstance().execute(() -> runIfCurrentServerConnection(token, () -> {
            FlowEditorScreen.refreshCatalogForServer(serverId);
            FocusedJsonResourceDesignerScreen.refreshCatalogForServer(serverId);
            AdvancementDesignerScreen.refreshCatalogForServer(serverId);
            DialogDesignerScreen.refreshCatalogForServer(serverId);
            GuiDesignerScreen.refreshCatalogForServer(serverId);
        }));
    }

    private void invalidateResourceOptionCatalogs(String serverId, ReSyncResourceType type) {
        if (serverId == null || serverId.isBlank() || type == null) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        String source = "server:resync:" + type.typeId();
        if (!runIfCurrentServerConnection(token, () -> OptionCatalogCache.getInstance().markStale(serverId, source))) {
            return;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            runIfCurrentServerConnection(token, () -> flowClient.requestOptionCatalog(source, Map.of(), true));
        }
        ScreenManager.getInstance().execute(() -> runIfCurrentServerConnection(token, () -> {
            FlowEditorScreen.refreshCatalogForServer(serverId);
            FocusedJsonResourceDesignerScreen.refreshCatalogForServer(serverId);
            AdvancementDesignerScreen.refreshCatalogForServer(serverId);
            DialogDesignerScreen.refreshCatalogForServer(serverId);
            GuiDesignerScreen.refreshCatalogForServer(serverId);
            refreshStudioWorkspace(serverId, true);
        }));
    }

    public void moveProjectResource(String serverId, ReSyncResourceDragPayload payload, String folderPath) {
        if (payload == null || payload.id() == null || payload.id().isBlank()) {
            return;
        }
        ProjectMetadataEdit metadata = editProjectMetadata(serverId);
        ProjectResource resource = metadata.resource(payload.type(), payload.id());
        if (resource == null) return;
        metadata.putResource(resource.type(), resource.id(), resource.displayName(), folderPath, resource.sortOrder());
        saveProjectMetadata(metadata, true);
    }

    public void createProjectFolder(String serverId, String parentPath, String folderName) {
        String name = folderName == null ? "" : folderName.trim();
        if (name.isBlank()) {
            return;
        }
        ProjectMetadataEdit metadata = editProjectMetadata(serverId);
        String parent = ReSyncProjectMetadata.normalizePath(parentPath);
        String path = parent.isBlank() ? name : parent + "/" + name;
        if (metadata.folder(path) == null) metadata.putFolder(path, parent, name, metadata.nextFolderSortOrder(), false);
        saveProjectMetadata(metadata, true);
    }

    public void setProjectFolderCollapsed(String serverId, String folderPath, boolean collapsed) {
        ProjectMetadataEdit metadata = editProjectMetadata(serverId);
        ProjectFolder folder = metadata.folder(folderPath);
        if (folder == null || folder.collapsed() == collapsed) return;
        metadata.putFolder(folder.path(), folder.parentPath(), folder.name(), folder.sortOrder(), collapsed);
        saveProjectMetadata(metadata, true);
    }

    public SyncedResourceState getFlowState(String serverId, String flowId) {
        hydrateCoreGraphProjection(serverId, ReSyncResourceType.FLOW, flowId);
        if (coreGraphUiProjection.authoritative(serverId, ReSyncResourceType.FLOW, flowId)) {
            return SyncedResourceState.CLEAN;
        }
        if (coreGraphAuthorityEnabled(serverId)) {
            return SyncedResourceState.CLEAN;
        }
        return flowStore.getState(serverId, ReSyncResourceType.FLOW, flowId);
    }

    public SyncedResourceState getCustomContentState(String serverId, String contentId) {
        return customContentStore.getState(serverId, contentId);
    }

    public Map<String, FlowGraph> getContentGraphsForServer(String serverId, String type) {
        String normalizedType = type != null ? type.toLowerCase(Locale.ROOT) : "";
        Map<String, FlowGraph> result = new HashMap<>();
        for (Map.Entry<String, FlowGraph> entry : flowStore.getForServer(serverId, ReSyncResourceType.FLOW).entrySet()) {
            FlowGraph graph = entry.getValue();
            String contentType = CustomContentGraphAdapter.contentType(graph);
            if (contentType != null && (normalizedType.isBlank() || normalizedType.equals(contentType))) {
                result.put(entry.getKey(), graph);
            }
        }
        return result;
    }

    public String getFlowName(String serverId, String flowId) {
        List<ReSyncResourceType> types = graphTypes(serverId, flowId);
        if (types.size() != 1) {
            return types.isEmpty() ? flowId : "";
        }
        return getGraphName(serverId, types.getFirst(), flowId);
    }
    public void setFlowName(String serverId, String flowId, String name) {
        ReSyncResourceType type = getGraphType(serverId, flowId);
        if (type != null) {
            hydrateCoreGraphProjection(serverId, type, flowId);
            if (coreGraphUiProjection.authoritative(serverId, type, flowId)) {
                ProjectMetadataEdit metadata = editProjectMetadata(serverId);
                ProjectResource resource = metadata.resource(type.typeId(), flowId);
                metadata.putResource(type.typeId(), flowId, name,
                    resource != null ? resource.path() : ReSyncResourceType.defaultFolderFor(type.typeId()),
                    resource != null ? resource.sortOrder() : metadata.nextResourceSortOrder());
                saveProjectMetadata(metadata, true);
            } else {
                flowStore.putName(serverId, type, flowId, name);
            }
        }
    }

    private String getGraphName(String serverId, ReSyncResourceType type, String id) {
        hydrateCoreGraphProjection(serverId, type, id);
        if (!coreGraphUiProjection.authoritative(serverId, type, id)) {
            if (coreGraphAuthorityEnabled(serverId)) {
                return id;
            }
            return flowStore.getName(serverId, type, id);
        }
        ProjectMetadataSnapshot metadata = projectMetadataStore.getFromDraft(serverId, serverId);
        if (metadata == null) {
            metadata = projectMetadataStore.getFromCache(serverId, serverId);
        }
        ProjectMetadataSnapshot.Resource entry = metadata != null ? metadata.resource(type.typeId(), id) : null;
        return entry != null && entry.displayName() != null && !entry.displayName().isBlank()
            ? entry.displayName() : id;
    }
    public String getGuiName(String serverId, String guiId) { return guiStore.getName(serverId, guiId); }
    public void setGuiName(String serverId, String guiId, String name) { guiStore.putName(serverId, guiId, name); }
    public String getScoreboardName(String serverId, String scoreboardId) { return scoreboardStore.getName(serverId, scoreboardId); }
    public void setScoreboardName(String serverId, String scoreboardId, String name) { scoreboardStore.putName(serverId, scoreboardId, name); }
    public String getTabName(String serverId, String tabId) { return tabStore.getName(serverId, tabId); }
    public void setTabName(String serverId, String tabId, String name) { tabStore.putName(serverId, tabId, name); }
    public String getCustomContentName(String serverId, String contentId) { return customContentStore.getName(serverId, contentId); }
    public void setCustomContentName(String serverId, String contentId, String name) { customContentStore.putName(serverId, contentId, name); }

    public FlowGraph createFlow(String serverId) {
        return createFlow(serverId, null);
    }

    public FlowGraph createFlow(String serverId, String flowId) {
        return createFlow(serverId, flowId, false);
    }

    public FlowGraph createFlow(String serverId, String flowId, boolean function) {
        return createFlow(serverId, flowId, function, FLOW_TEMPLATES.getFirst());
    }

    public FlowGraph createFlow(String serverId, String flowId, boolean function, String templateName) {
        ReSyncResourceType type = function ? ReSyncResourceType.FUNCTION : ReSyncResourceType.FLOW;
        if (flowId != null) {
            hydrateCoreGraphProjection(serverId, type, flowId);
            if (coreGraphUiProjection.authoritative(serverId, type, flowId)) {
                return null;
            }
        }
        if (coreGraphAuthorityEnabled(serverId)) {
            String requestedId = flowId == null || flowId.isBlank() ? UUID.randomUUID().toString() : flowId;
            CoreGraphDocumentAuthoringAdapter.RequestResult result = requestCoreGraphCreation(
                serverId, type, requestedId, requestedId, null);
            if (!result.admitted()) {
                new Notification("Create Unavailable", "Core Template Unavailable", Notification.Type.WARN);
            }
            return null;
        }
        FlowGraph graph = createDefaultFlow(serverId, function, templateName);
        if (graph == null) {
            return null;
        }
        if (flowId != null) {
            graph.setId(flowId);
        }
        graph.setResourceType(function ? ReSyncResourceDragPayload.FUNCTION : ReSyncResourceDragPayload.FLOW);
        if (function) {
            graph.setFunctionOwner("server");
            graph.setFunctionNamespace(serverId != null && !serverId.isBlank() ? serverId : "local");
            graph.setFunctionVersion(1);
            graph.setFunctionDescription("Run " + graph.getId() + ".");
        }
        flowStore.putInDraft(serverId, graph);
        flowStore.putNameIfAbsent(serverId, function ? ReSyncResourceType.FUNCTION : ReSyncResourceType.FLOW, graph.getId(), graph.getId());
        invalidateProjectCatalog(serverId);
        return graph;
    }

    public FlowGraph createCommand(String serverId, String commandId) {
        ReSyncResourceType type = ReSyncResourceType.COMMAND;
        if (commandId != null) {
            hydrateCoreGraphProjection(serverId, type, commandId);
            if (coreGraphUiProjection.authoritative(serverId, type, commandId)) {
                return null;
            }
        }
        if (coreGraphAuthorityEnabled(serverId)) {
            String requestedId = commandId == null || commandId.isBlank() ? UUID.randomUUID().toString() : commandId;
            CoreGraphDocumentAuthoringAdapter.RequestResult result = requestCoreGraphCreation(
                serverId, type, requestedId, requestedId, null);
            if (!result.admitted()) {
                new Notification("Create Unavailable", "Core Template Unavailable", Notification.Type.WARN);
            }
            return null;
        }
        FlowGraph graph = createDefaultFlow(serverId, false, "Command");
        if (graph == null) {
            return null;
        }
        if (commandId != null) {
            graph.setId(commandId);
        }
        graph.setResourceType(type.typeId());
        graph.setFunction(false);
        flowStore.putInDraft(serverId, graph);
        flowStore.putNameIfAbsent(serverId, type, graph.getId(), graph.getId());
        invalidateProjectCatalog(serverId);
        return graph;
    }

    public FlowGraph createContentFlow(String serverId, String flowId, String type, String displayName) {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph(flowId, type, displayName);
        flowStore.putInDraft(serverId, graph);
        flowStore.putNameIfAbsent(serverId, ReSyncResourceType.FLOW, graph.getId(), CustomContentGraphAdapter.displayName(graph));
        CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(graph);
        if (definition != null) {
            customContentStore.putInDraft(serverId, definition);
            customContentStore.putNameIfAbsent(serverId, definition.getId(), definition.getDisplayName());
        }
        invalidateProjectCatalog(serverId);
        invalidateCustomContentOptionCatalogs(serverId);
        return graph;
    }

    public GuiDefinition createGui(String serverId, String id) {
        GuiDefinition gui = createDefaultGui(id);
        guiStore.putInDraft(serverId, gui);
        guiStore.putNameIfAbsent(serverId, id, gui.getTitle());
        invalidateProjectCatalog(serverId);
        return gui;
    }

    public ScoreboardDefinition createScoreboard(String serverId, String id) {
        ScoreboardDefinition scoreboard = createDefaultScoreboard(id);
        scoreboardStore.putInDraft(serverId, scoreboard);
        scoreboardStore.putNameIfAbsent(serverId, id, scoreboard.getTitle());
        invalidateProjectCatalog(serverId);
        return scoreboard;
    }

    public TabDefinition createTab(String serverId, String id) {
        TabDefinition tab = createDefaultTab(id);
        tabStore.putInDraft(serverId, tab);
        tabStore.putNameIfAbsent(serverId, id, tab.getId());
        invalidateProjectCatalog(serverId);
        return tab;
    }

    private Object buildResourceForCreation(String serverId, ReSyncResourceType type, String id, String folder,
                                            String templateName) {
        if (type == null || !type.enabled() || id == null || id.isBlank()) {
            return null;
        }
        return switch (type) {
            case FLOW, FUNCTION -> {
                FlowGraph graph = createDefaultFlow(serverId, type == ReSyncResourceType.FUNCTION,
                    templateName != null && !templateName.isBlank() ? templateName : FLOW_TEMPLATES.getFirst());
                if (graph == null) {
                    yield null;
                }
                graph.setId(id);
                graph.setResourceType(type.typeId());
                graph.setFunction(type == ReSyncResourceType.FUNCTION);
                if (type == ReSyncResourceType.FUNCTION) {
                    graph.setFunctionOwner("server");
                    graph.setFunctionNamespace(serverId != null && !serverId.isBlank() ? serverId : "local");
                    graph.setFunctionVersion(1);
                    graph.setFunctionDescription("Run " + id + ".");
                }
                yield graph;
            }
            case COMMAND -> {
                FlowGraph graph = createDefaultFlow(serverId, false, "Command");
                if (graph == null) {
                    yield null;
                }
                graph.setId(id);
                graph.setResourceType(type.typeId());
                graph.setFunction(false);
                yield graph;
            }
            case GUI -> createDefaultGui(id);
            case SCOREBOARD -> createDefaultScoreboard(id);
            case TAB -> createDefaultTab(id);
            case CUSTOM_CONTENT -> buildCustomContentForCreation(id, templateName);
            case VARIABLE_DEFINITION, TIMER_DEFINITION, SCHEDULE_DEFINITION, COMPONENT_BUILDER ->
                buildAutomationResourceForCreation(type, id, templateName);
            default -> defaultJsonResource(type, id, folder);
        };
    }

    static JsonObject buildAutomationResourceForCreation(ReSyncResourceType type, String id, String templateJson) {
        if (!AutomationDefinitionDraft.supports(type) || templateJson == null || templateJson.isBlank()
            || !creationFieldWithinLimit(templateJson)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(templateJson);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject document = parsed.getAsJsonObject();
            if (type == ReSyncResourceType.SCHEDULE_DEFINITION
                && (!document.has("target") || !document.get("target").isJsonObject()
                || !document.getAsJsonObject("target").has("type")
                || !document.getAsJsonObject("target").get("type").isJsonObject())) {
                return null;
            }
            if (type == ReSyncResourceType.SCHEDULE_DEFINITION) {
                JsonObject targetType = document.getAsJsonObject("target").getAsJsonObject("type");
                for (String field : List.of("ownerId", "localId")) {
                    JsonElement value = targetType.get(field);
                    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || value.getAsString().isBlank()) {
                        return null;
                    }
                }
            }
            return AutomationDefinitionDraft.prepare(type, id, document).document();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private CustomContentDefinition buildCustomContentForCreation(String id, String templateJson) {
        JsonObject template;
        try {
            template = templateJson == null || templateJson.isBlank() ? new JsonObject()
                : gson.fromJson(templateJson, JsonObject.class);
        } catch (RuntimeException exception) {
            return null;
        }
        if (template == null) {
            return null;
        }
        String requestedType = template.has("type") ? template.get("type").getAsString().toLowerCase(Locale.ROOT) : "item";
        String type = switch (requestedType) {
            case "block", "armor", "projectile" -> requestedType;
            default -> "item";
        };
        String name = template.has("name") && !template.get("name").getAsString().isBlank()
            ? template.get("name").getAsString() : id;
        String provider = template.has("provider") && !template.get("provider").getAsString().isBlank()
            ? template.get("provider").getAsString() : "vanilla";
        String asset = template.has("asset") ? template.get("asset").getAsString() : "";
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph(id, type, name);
        CustomContentGraphAdapter.setContentProperty(graph, "content_id", id);
        CustomContentGraphAdapter.setContentProperty(graph, "name", name);
        CustomContentGraphAdapter.setContentProperty(graph, "provider", provider);
        if ("vanilla".equalsIgnoreCase(provider)) {
            if (!asset.isBlank()) {
                CustomContentGraphAdapter.setContentProperty(graph, "material", asset.toUpperCase(Locale.ROOT));
            }
            CustomContentGraphAdapter.setContentProperty(graph, "external_id", "");
        } else {
            CustomContentGraphAdapter.setContentProperty(graph, "external_id", asset);
        }
        return CustomContentGraphAdapter.toDefinition(graph);
    }

    public Async<ResourceDeleteResult> deleteResourceSettled(String serverId, ReSyncResourceType type,
                                                                         String id) {
        String actualServerId = serverId == null ? "" : serverId.trim();
        String actualId = id == null ? "" : id.trim();
        ReSyncFlowClient currentClient = actualServerId.isBlank() ? null : connectionManager.getFlowClient(actualServerId);
        String invalidReason = actualServerId.isBlank() ? "invalid_server"
            : type == null ? "invalid_type"
            : type == ReSyncResourceType.PROJECT_METADATA ? "protected_type"
            : actualId.isBlank() ? "invalid_id"
            : !hasAuthoritativeResource(actualServerId, type, actualId) ? "membership_authority_missing"
            : null;
        if (invalidReason != null) {
            traceResourceDeletePreflight(currentClient, actualServerId, type, actualId, "rejected", invalidReason, 0L);
            return Async.completed(new ResourceDeleteResult(type != null ? type.typeId() : "",
                actualId, false, "Resource Delete Unavailable"));
        }
        if (type == ReSyncResourceType.FUNCTION) {
            List<FunctionReference> callers = analyzeFunctionReferences(actualServerId, actualId).stream()
                .filter(reference -> !actualId.equals(reference.resourceId()))
                .toList();
            if (!callers.isEmpty()) {
                FunctionReference first = callers.getFirst();
                traceResourceDeletePreflight(currentClient, actualServerId, type, actualId, "rejected",
                    "function_reference_authority", 0L);
                return Async.completed(new ResourceDeleteResult(type.typeId(), actualId, false,
                    "Function In Use: " + callers.size() + " References · " + first.resourceId() + " · " + first.location()));
            }
        }
        ResourceDeleteKey key = new ResourceDeleteKey(actualServerId, type, actualId);
        PendingResourceDelete pending = new PendingResourceDelete(type, actualId);
        PendingResourceDelete current = pendingResourceDeletions.putIfAbsent(key, pending);
        if (current != null) {
            traceResourceDeletePreflight(currentClient, actualServerId, type, actualId, "coalesced",
                "pending_delete_exists", 0L);
            return current.completion;
        }
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(actualServerId);
        if (flowClient == null) {
            traceResourceDeletePreflight(null, actualServerId, type, actualId, "rejected", "active_client_missing", 0L);
            settleResourceDelete(key, pending, pending.settlement.fail("ReSync Offline"));
            return pending.completion;
        }
        markResourceDeleteSaving(actualServerId, type, actualId);
        if (!flowClient.sendSettledResourceDelete(type, actualId)) {
            traceResourceDeletePreflight(flowClient, actualServerId, type, actualId, "rejected",
                "client_dispatch_rejected", 0L);
            settleResourceDelete(key, pending, pending.settlement.fail("Resource Delete Rejected"));
        } else {
            traceResourceDeletePreflight(flowClient, actualServerId, type, actualId, "admitted",
                "client_dispatch_accepted", 0L);
        }
        return pending.completion;
    }

    private void traceResourceDeletePreflight(ReSyncFlowClient flowClient, String serverId, ReSyncResourceType type,
                                              String id, String outcome, String reason, long revision) {
        ReSyncFlowClient.traceLifecycle(serverId, "delete_preflight", "serverId", serverId, "resourceKey",
            (type == null ? "unknown" : type.typeId()) + ":" + (id == null || id.isBlank() ? "unknown" : id),
            "operation", ResourceOperationKind.DELETE, "outcome", outcome, "reason", reason, "generation",
            flowClient != null ? flowClient.activeTransportGeneration() : -1, "authorityEpoch",
            flowClient != null ? flowClient.authorityEpoch() : 0L, "revision", revision, "connectionState",
            flowClient != null ? flowClient.connectionState() : ReSyncFlowClient.ConnectionState.DISCONNECTED,
            "catalogAuthority", flowClient != null ? flowClient.catalogAuthority()
                : ReSyncFlowClient.CatalogAuthority.UNAVAILABLE, "listAuthority",
            flowClient != null && type != null && flowClient.isResourceListAuthoritative(type));
    }

    void acknowledgeResourceDelete(String serverId, ReSyncResourceType type, String id) {
        ResourceDeleteKey key = resourceDeleteKey(serverId, type, id);
        PendingResourceDelete pending = key != null ? pendingResourceDeletions.get(key) : null;
        if (pending != null) {
            settleResourceDelete(key, pending, pending.settlement.acknowledge());
        }
    }

    void observeResourceDeleteTombstone(String serverId, ReSyncResourceType type, String id) {
        observeResourceDeleteAbsence(serverId, type, id);
    }

    private void observeResourceDeleteAbsence(String serverId, ReSyncResourceType type, String id) {
        ResourceDeleteKey key = resourceDeleteKey(serverId, type, id);
        PendingResourceDelete pending = key != null ? pendingResourceDeletions.get(key) : null;
        if (pending != null) {
            settleResourceDelete(key, pending, pending.settlement.observeAbsence());
        }
    }

    private void observeResourceDeleteAbsence(String serverId, ReSyncResourceType type, List<String> liveIds) {
        Set<String> live = Set.copyOf(liveIds == null ? List.of() : liveIds);
        for (Map.Entry<ResourceDeleteKey, PendingResourceDelete> entry : List.copyOf(pendingResourceDeletions.entrySet())) {
            ResourceDeleteKey key = entry.getKey();
            if (key.serverId().equals(serverId) && key.type() == type && !live.contains(key.id())) {
                PendingResourceDelete pending = entry.getValue();
                settleResourceDelete(key, pending, pending.settlement.observeAbsence());
            }
        }
    }

    private ResourceDeleteKey resourceDeleteKey(String serverId, ReSyncResourceType type, String id) {
        String actualServerId = serverId == null ? "" : serverId.trim();
        String actualId = id == null ? "" : id.trim();
        if (actualServerId.isBlank() || type == null || actualId.isBlank()) {
            return null;
        }
        return new ResourceDeleteKey(actualServerId, type, actualId);
    }

    private void settleResourceDelete(ResourceDeleteKey key, PendingResourceDelete pending,
                                      ResourceDeleteResult result) {
        if (key == null || pending == null || result == null
            || !pendingResourceDeletions.remove(key, pending)) {
            return;
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(key.serverId());
        ReSyncFlowClient.traceLifecycle(key.serverId(), result.deleted() ? "delete_settlement_complete"
            : "delete_settlement_failed", "serverId", key.serverId(), "resourceKey",
            key.type().typeId() + ":" + key.id(), "operation", ResourceOperationKind.DELETE, "requestId", null,
            "mutationId", null, "generation", flowClient != null ? flowClient.activeTransportGeneration() : -1,
            "authorityEpoch", flowClient != null ? flowClient.authorityEpoch() : 0L, "revision", 0L,
            "acknowledged", pending.settlement.acknowledged(), "absent", pending.settlement.absent(), "outcome",
            result.deleted() ? "settled" : "failed", "reason", result.message());
        pending.completion.complete(result);
    }

    private void failPendingResourceDeletions(String message) {
        for (Map.Entry<ResourceDeleteKey, PendingResourceDelete> entry : List.copyOf(pendingResourceDeletions.entrySet())) {
            PendingResourceDelete pending = entry.getValue();
            settleResourceDelete(entry.getKey(), pending, pending.settlement.fail(message));
        }
    }

    private void markResourceDeleteSaving(String serverId, ReSyncResourceType type, String id) {
        if (type.isGraph()) {
            flowStore.markSaving(serverId, type, id);
        } else if (type == ReSyncResourceType.GUI) {
            guiStore.markSaving(serverId, id);
        } else if (type == ReSyncResourceType.SCOREBOARD) {
            scoreboardStore.markSaving(serverId, id);
        } else if (type == ReSyncResourceType.TAB) {
            tabStore.markSaving(serverId, id);
        } else if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            customContentStore.markSaving(serverId, id);
        } else {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            if (store != null) {
                store.markSaving(serverId, id);
            }
        }
    }

    public boolean deleteFlow(String serverId, String flowId) {
        ReSyncResourceType type = getGraphType(serverId, flowId);
        if (type == null) {
            return false;
        }
        return deleteGraph(serverId, type, flowId);
    }

    public boolean deleteGraph(String serverId, ReSyncResourceType type, String flowId) {
        hydrateCoreGraphProjection(serverId, type, flowId);
        if (coreGraphUiProjection.authoritative(serverId, type, flowId)) {
            CoreGraphUiProjection.Baseline baseline = coreGraphUiProjection.baseline(serverId, type, flowId).orElse(null);
            if (baseline == null) {
                return false;
            }
            ReSyncFlowClient coreFlowClient = ensureSubscribedFlowClient(serverId);
            if (coreFlowClient == null) {
                return false;
            }
            return coreFlowClient.sendCoreResourceDelete(type, flowId);
        }
        if (type == ReSyncResourceType.FUNCTION) {
            List<FunctionReference> callers = analyzeFunctionReferences(serverId, flowId).stream()
                .filter(reference -> !flowId.equals(reference.resourceId()))
                .toList();
            if (!callers.isEmpty()) {
                FunctionReference first = callers.getFirst();
                new Notification("Function In Use", callers.size() + " References · " + first.resourceId() + " · " + first.location(), Notification.Type.WARN);
                return false;
            }
        }
        if (!hasGraphForServer(serverId, type.typeId(), flowId)) {
            return false;
        }
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (flowClient == null) {
            return false;
        }
        flowStore.markSaving(serverId, type, flowId);
        flowClient.sendGraphDelete(type, flowId);
        return true;
    }

    public void confirmResourceDeleted(String serverId, ReSyncResourceType type, String id) {
        confirmResourceDeleted(serverId, type, id, false);
    }

    void confirmResourceDeletedFromProjection(String serverId, ReSyncResourceType type, String id) {
        confirmResourceDeleted(serverId, type, id, true);
    }

    private void confirmResourceDeleted(String serverId, ReSyncResourceType type, String id, boolean legacyRead) {
        if (type == null || id == null || id.isBlank()
            || !(legacyRead ? synchronizeLegacyReadAuthorityEpoch(serverId) : synchronizeAuthorityEpoch(serverId))) {
            return;
        }
        CoreGraphHandoff handoff = type.isGraph() ? currentCoreGraphHandoff(serverId, type, id) : null;
        if (handoff != null) {
            if (!handoff.deleted()) {
                return;
            }
            enqueueCoreUiTransition(handoff, () -> confirmResourceDeletedNow(serverId, type, id));
            return;
        }
        if (type.isGraph() && coreGraphAuthorityEnabled(serverId)) {
            return;
        }
        enqueueCoreUiTransition(serverId, () -> confirmResourceDeletedNow(serverId, type, id));
    }

    private void confirmResourceDeletedNow(String serverId, ReSyncResourceType type, String id) {
        CoreGraphSessionKey sessionKey = type.isGraph() ? new CoreGraphSessionKey(serverId, type, id) : null;
        CoreGraphSessionState session = sessionKey != null ? coreGraphEditorSessions.get(sessionKey) : null;
        ProjectMetadataEdit metadata = editProjectMetadata(serverId);
        String resourceKey = ReSyncProjectMetadata.resourceKey(type.typeId(), id);
        boolean removedMetadata = metadata.resource(type.typeId(), id) != null;
        if (removedMetadata) metadata.removeResource(resourceKey);
        boolean membershipChanged = type.isGraph() && recordTypedMembershipTombstone(serverId, type, id);
        if (type.isGraph()) {
            flowStore.remove(serverId, type, id);
        } else {
            removeResourceFromCache(serverId, type, id);
        }
        if (removedMetadata && canPersistProjectMetadata(serverId)) {
            saveProjectMetadata(metadata, false);
        }
        boolean removedSession = sessionKey != null && session != null && coreGraphEditorSessions.remove(sessionKey, session);
        if (removedSession) {
            coreGraphHydrations.remove(sessionKey);
        }
        observeResourceDeleteAbsence(serverId, type, id);
        if (!removedMetadata && !membershipChanged && !removedSession) {
            ReSyncFlowClient.traceLifecycle(serverId, "delete_projection_noop", "serverId", serverId, "resourceKey",
                type.typeId() + ":" + id, "operation", ResourceOperationKind.DELETE, "requestId", null,
                "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision", 0L,
                "metadataRemoved", false, "membershipRemoved", false, "sessionRemoved", false,
                "reason", "projection_already_absent");
            return;
        }
        invalidateProjectCatalog(serverId);
        refreshStudioWorkspaceNow(serverId, new StudioWorkspaceRefreshSnapshot(true, false));
        ReSyncFlowClient.traceLifecycle(serverId, "delete_projection_applied", "serverId", serverId, "resourceKey",
            type.typeId() + ":" + id, "operation", ResourceOperationKind.DELETE, "requestId", null,
            "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision", 0L,
            "metadataRemoved", removedMetadata, "membershipRemoved", membershipChanged, "sessionRemoved",
            removedSession, "browserIncluded", getProjectResource(serverId, type.typeId(), id) != null,
            "reason", "tombstone_projection_applied");
    }

    public void failResourceDelete(String serverId, ReSyncResourceType type, String id, String message) {
        ResourceDeleteKey key = resourceDeleteKey(serverId, type, id);
        PendingResourceDelete pending = key != null ? pendingResourceDeletions.get(key) : null;
        if (pending != null) {
            settleResourceDelete(key, pending, pending.settlement.fail(message));
        }
        markResourceSaveFailed(serverId, type, id);
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            flowClient.requestResource(type, id, false);
        }
        refreshStudioWorkspace(serverId, true);
    }

    private void removeResourceFromCache(String serverId, ReSyncResourceType type, String id) {
        if (type == ReSyncResourceType.GUI) {
            guiStore.remove(serverId, id);
        } else if (type == ReSyncResourceType.SCOREBOARD) {
            scoreboardStore.remove(serverId, id);
        } else if (type == ReSyncResourceType.TAB) {
            tabStore.remove(serverId, id);
        } else if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            customContentStore.remove(serverId, id);
        } else if (type == ReSyncResourceType.PROJECT_METADATA) {
            projectMetadataStore.remove(serverId, id);
        } else {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            if (store != null) {
                store.remove(serverId, id);
            }
        }
        invalidateProjectCatalog(serverId);
        invalidateResourceOptionCatalogs(serverId, type);
    }

    private boolean removeResourceFromCache(String serverId, ReSyncResourceType type, String id,
                                            ResourceProjectionLease lease) {
        SyncedResourceCache<?> store = resourceStore(type);
        if (store == null) {
            return false;
        }
        Long expected = lease.expected(type.typeId(), serverId, id);
        Long next = expected != null ? store.removeIfGeneration(serverId, id, expected) : null;
        boolean removed = lease.advance(type.typeId(), serverId, id, next);
        if (removed) {
            invalidateProjectCatalog(serverId);
            invalidateResourceOptionCatalogs(serverId, type);
        }
        return removed;
    }

    public void deleteGui(String serverId, String guiId) {
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (flowClient != null) {
            guiStore.markSaving(serverId, guiId);
            flowClient.sendGuiDelete(guiId);
        }
    }

    public void deleteScoreboard(String serverId, String scoreboardId) {
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (flowClient != null) {
            scoreboardStore.markSaving(serverId, scoreboardId);
            flowClient.sendScoreboardDelete(scoreboardId);
        }
    }

    public void deleteTab(String serverId, String tabId) {
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (flowClient != null) {
            tabStore.markSaving(serverId, tabId);
            flowClient.sendTabDelete(tabId);
        }
    }

    public void deleteCustomContent(String serverId, String contentId) {
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (flowClient != null) {
            customContentStore.markSaving(serverId, contentId);
            flowClient.sendResourceDelete(ReSyncResourceType.CUSTOM_CONTENT, contentId);
        }
    }

    public CustomContentDefinition createCustomContent(String serverId, String type) {
        String normalizedType = type != null ? type.toLowerCase(Locale.ROOT) : "item";
        String id = normalizedType + "_" + UUID.randomUUID().toString().substring(0, 8);
        FlowGraph graph = createContentFlow(serverId, id, normalizedType, null);
        CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
        if (content != null) {
            saveFlow(serverId, graph);
        }
        return content;
    }

    public boolean renameFlow(String serverId, String flowId, String newFlowId) {
        ReSyncResourceType type = getGraphType(serverId, flowId);
        if (type != null) {
            hydrateCoreGraphProjection(serverId, type, flowId);
            if (coreGraphUiProjection.authoritative(serverId, type, flowId)) {
                return false;
            }
        }
        if (type == ReSyncResourceType.FUNCTION) {
            return renameFunction(serverId, flowId, newFlowId);
        }
        if (type == ReSyncResourceType.COMMAND) {
            FlowGraph graph = flowStore.get(serverId, type, flowId);
            FlowNode start = commandStartNode(graph);
            Object previousCommand = start != null && start.getInputValues() != null ? start.getInputValues().get("command") : null;
            if (start != null && start.getInputValues() != null && normalizedCommandLabel(String.valueOf(previousCommand)).equals(normalizedCommandLabel(flowId))) {
                start.getInputValues().put("command", normalizedCommandLabel(newFlowId));
            }
            boolean renamed = renameResource(flowStore, serverId, flowId, newFlowId, type, () -> {
                if (start == null || start.getInputValues() == null) {
                    return;
                }
                if (previousCommand != null) {
                    start.getInputValues().put("command", previousCommand);
                } else {
                    start.getInputValues().remove("command");
                }
            });
            if (!renamed && start != null && start.getInputValues() != null) {
                if (previousCommand != null) {
                    start.getInputValues().put("command", previousCommand);
                } else {
                    start.getInputValues().remove("command");
                }
            }
            return renamed;
        }
        return type != null ? renameResource(flowStore, serverId, flowId, newFlowId, type) : false;
    }

    public List<FunctionReference> analyzeFunctionReferences(String serverId, String functionId) {
        if (serverId == null || functionId == null || functionId.isBlank()) {
            return List.of();
        }
        List<FunctionReference> references = new ArrayList<>();
        for (FlowGraph graph : flowStore.valuesForServer(serverId)) {
            if (graph == null || graph.getId() == null) {
                continue;
            }
            for (String location : FunctionReferenceAnalyzer.findGraphReferences(graph, functionId)) {
                String type = graph.getResourceType();
                references.add(new FunctionReference(type != null && !type.isBlank() ? type : ReSyncResourceType.FLOW.typeId(), graph.getId(), location));
            }
        }
        collectSerializedFunctionReferences(references, ReSyncResourceType.GUI, guiStore.getForServer(serverId), functionId);
        collectSerializedFunctionReferences(references, ReSyncResourceType.SCOREBOARD, scoreboardStore.getForServer(serverId), functionId);
        collectSerializedFunctionReferences(references, ReSyncResourceType.TAB, tabStore.getForServer(serverId), functionId);
        collectSerializedFunctionReferences(references, ReSyncResourceType.CUSTOM_CONTENT, customContentStore.getForServer(serverId), functionId);
        for (Map.Entry<ReSyncResourceType, SyncedResourceCache<JsonObject>> storeEntry : jsonResourceStores.entrySet()) {
            for (Map.Entry<String, JsonObject> resourceEntry : storeEntry.getValue().getForServer(serverId).entrySet()) {
                for (String location : FunctionReferenceAnalyzer.findJsonReferences(resourceEntry.getValue(), functionId)) {
                    references.add(new FunctionReference(storeEntry.getKey().typeId(), resourceEntry.getKey(), location));
                }
            }
        }
        references.sort((left, right) -> {
            int typeOrder = left.resourceType().compareTo(right.resourceType());
            if (typeOrder != 0) {
                return typeOrder;
            }
            int idOrder = left.resourceId().compareTo(right.resourceId());
            return idOrder != 0 ? idOrder : left.location().compareTo(right.location());
        });
        return List.copyOf(references);
    }

    public boolean renameGui(String serverId, String guiId, String newGuiId) {
        return renameResource(guiStore, serverId, guiId, newGuiId, ReSyncResourceType.GUI);
    }

    public boolean renameScoreboard(String serverId, String scoreboardId, String newScoreboardId) {
        return renameResource(scoreboardStore, serverId, scoreboardId, newScoreboardId, ReSyncResourceType.SCOREBOARD);
    }

    public boolean renameTab(String serverId, String tabId, String newTabId) {
        return renameResource(tabStore, serverId, tabId, newTabId, ReSyncResourceType.TAB);
    }

    public boolean renameCustomContent(String serverId, String contentId, String newContentId) {
        if (newContentId == null || newContentId.isBlank()) {
            return false;
        }
        CustomContentDefinition content = customContentStore.get(serverId, contentId);
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (content == null || flowClient == null) return false;
        String oldFlowId = content.getFlowId();
        if (!customContentStore.rename(serverId, contentId, newContentId, this::applyCustomContentIdentity)) return false;
        CustomContentDefinition renamed = customContentStore.get(serverId, newContentId);
        if (renamed == null) {
            customContentStore.restoreRename(serverId, newContentId, contentId, this::applyCustomContentIdentity);
            return false;
        }
        String newFlowId = renamed.getFlowId();
        if (oldFlowId != null && newFlowId != null && !oldFlowId.equals(newFlowId)) {
            flowStore.rename(serverId, ReSyncResourceType.FLOW, oldFlowId, newFlowId, FlowGraph::setId);
        }
        customContentStore.resolveDisplayName(serverId, contentId, newContentId);
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.CUSTOM_CONTENT,
            newContentId, renamed.getDisplayName());
        Async<SaveTicketSettlement> completion = saveTicketSettlement(ticket);
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        saveCustomContent(serverId, renamed, ticket);
        SyncedResourceCache.SaveLease<CustomContentDefinition> lease = customContentStore.getDraftLease(serverId, newContentId);
        completion.thenAccept(settlement -> ScreenManager.getInstance().execute(() -> runIfCurrentServerConnection(token, () -> {
            if (settlement.saved()) {
                flowClient.sendResourceDelete(ReSyncResourceType.CUSTOM_CONTENT, contentId);
            } else if (settlement.currentAtFinish() && lease != null && lease.isCurrent()) {
                customContentStore.restoreRename(serverId, newContentId, contentId, this::applyCustomContentIdentity);
                if (oldFlowId != null && newFlowId != null && !oldFlowId.equals(newFlowId)) {
                    restoreGraphRename(flowStore, serverId, ReSyncResourceType.FLOW, newFlowId, oldFlowId);
                }
            }
            invalidateCustomContentOptionCatalogs(serverId);
            refreshStudioWorkspace(serverId);
        })));
        invalidateCustomContentOptionCatalogs(serverId);
        refreshStudioWorkspace(serverId);
        return true;
    }

    public boolean renameJsonResource(String serverId, ReSyncResourceType type, String oldId, String newId) {
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        return store != null && renameResource(store, serverId, oldId, newId, type);
    }

    public boolean duplicateResource(String serverId, String type, String sourceId, String targetId) {
        if (serverId == null || type == null || sourceId == null || targetId == null || targetId.isBlank()) {
            return false;
        }
        return switch (type) {
            case ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION, ReSyncResourceDragPayload.COMMAND -> {
                ReSyncResourceType graphType = ReSyncResourceType.byTypeId(type);
                hydrateCoreGraphProjection(serverId, graphType, sourceId);
                if (coreGraphUiProjection.authoritative(serverId, graphType, sourceId) || coreGraphAuthorityEnabled(serverId)) {
                    yield duplicateCoreGraphResource(serverId, graphType, sourceId, targetId);
                }
                FlowGraph source = flowStore.get(serverId, graphType, sourceId);
                if (source == null) {
                    yield false;
                }
                FlowGraph copy = FlowSerializer.deserialize(FlowSerializer.serialize(source));
                copy.setId(targetId);
                copy.setResourceType(graphType.typeId());
                copy.setFunction(graphType == ReSyncResourceType.FUNCTION);
                flowStore.putInDraft(serverId, copy);
                flowStore.putName(serverId, graphType, targetId, targetId);
                saveGraph(serverId, graphType, copy);
                yield true;
            }
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> {
                CustomContentDefinition source = customContentStore.get(serverId, sourceId);
                if (source == null) {
                    yield false;
                }
                CustomContentDefinition copy = (CustomContentDefinition) ReSyncResourceType.CUSTOM_CONTENT.deserialize(ReSyncResourceType.CUSTOM_CONTENT.serialize(source));
                applyCustomContentIdentity(copy, targetId);
                saveCustomContent(serverId, copy);
                yield true;
            }
            case ReSyncResourceDragPayload.GUI -> {
                GuiDefinition source = guiStore.get(serverId, sourceId);
                if (source == null) {
                    yield false;
                }
                GuiDefinition copy = (GuiDefinition) ReSyncResourceType.GUI.deserialize(ReSyncResourceType.GUI.serialize(source));
                ReSyncResourceType.GUI.applyRename(copy, targetId);
                saveGui(serverId, copy);
                yield true;
            }
            case ReSyncResourceDragPayload.SCOREBOARD -> {
                ScoreboardDefinition source = scoreboardStore.get(serverId, sourceId);
                if (source == null) {
                    yield false;
                }
                ScoreboardDefinition copy = (ScoreboardDefinition) ReSyncResourceType.SCOREBOARD.deserialize(ReSyncResourceType.SCOREBOARD.serialize(source));
                ReSyncResourceType.SCOREBOARD.applyRename(copy, targetId);
                saveScoreboard(serverId, copy);
                yield true;
            }
            case ReSyncResourceDragPayload.TAB -> {
                TabDefinition source = tabStore.get(serverId, sourceId);
                if (source == null) {
                    yield false;
                }
                TabDefinition copy = source.copy();
                ReSyncResourceType.TAB.applyRename(copy, targetId);
                saveTab(serverId, copy);
                yield true;
            }
            default -> {
                ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
                JsonObject source = resourceType != null ? getJsonResource(serverId, resourceType, sourceId) : null;
                if (resourceType == null || source == null) {
                    yield false;
                }
                JsonObject copy = source.deepCopy();
                resourceType.applyRename(copy, targetId);
                saveJsonResource(serverId, resourceType, copy);
                yield true;
            }
        };
    }

    private boolean duplicateCoreGraphResource(String serverId, ReSyncResourceType type, String sourceId, String targetId) {
        if (type == null || !type.isGraph() || sourceId == null || sourceId.isBlank() || targetId == null
            || targetId.isBlank() || sourceId.equals(targetId)) {
            return false;
        }
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        return flowClient != null && flowClient.sendCoreGraphDuplicate(type, sourceId, targetId);
    }

    private void applyCustomContentIdentity(CustomContentDefinition content, String targetId) {
        String flowId = CustomContentGraphAdapter.contentFlowId(content.getType(), targetId);
        content.setId(targetId);
        content.setFlowId(flowId);
        if (content.getGraph() != null) {
            content.getGraph().setId(flowId);
            FlowNode startNode = CustomContentGraphAdapter.findStartNode(content.getGraph());
            if (startNode != null && startNode.getInputValues() != null) startNode.getInputValues().put("content_id", targetId);
        }
        if (content.getAbilities() != null) {
            content.getAbilities().forEach(ability -> {
                String id = ability.getId();
                ability.setId(id != null && id.contains(".") ? targetId + id.substring(id.indexOf('.')) : targetId);
                ability.setFlowId(flowId);
            });
        }
    }

    private <T> boolean renameResource(SyncedResourceCache<T> store, String serverId, String oldId, String newId, ReSyncResourceType type) {
        if (newId == null || newId.isBlank()) {
            return false;
        }
        String trimmedId = newId.trim();
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (flowClient == null) {
            return false;
        }
        if (!store.rename(serverId, oldId, trimmedId, type::applyRename)) {
            return false;
        }
        store.resolveDisplayName(serverId, oldId, trimmedId);
        SyncedResourceCache.SaveLease<T> lease = store.getDraftLease(serverId, trimmedId);
        if (lease == null) {
            store.restoreRename(serverId, trimmedId, oldId, type::applyRename);
            return false;
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, type, trimmedId, store.getName(serverId, trimmedId));
        Async<SaveTicketSettlement> completion = saveTicketSettlement(ticket);
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        store.markSaving(serverId, trimmedId);
        if (!flowClient.sendResourceSave(type, lease, ticket)) {
            if (lease.isCurrent()) {
                store.restoreRename(serverId, trimmedId, oldId, type::applyRename);
                store.resolveDisplayName(serverId, trimmedId, oldId);
            }
            refreshStudioWorkspace(serverId);
            return false;
        }
        completion.thenAccept(settlement -> ScreenManager.getInstance().execute(() -> runIfCurrentServerConnection(token, () -> {
            if (settlement.saved()) {
                flowClient.sendResourceDelete(type, oldId);
            } else if (settlement.currentAtFinish() && lease.isCurrent()) {
                store.restoreRename(serverId, trimmedId, oldId, type::applyRename);
                store.resolveDisplayName(serverId, trimmedId, oldId);
            }
            refreshStudioWorkspace(serverId);
        })));
        refreshStudioWorkspace(serverId);
        return true;
    }

    private boolean renameResource(TypedGraphCache store, String serverId, String oldId, String newId, ReSyncResourceType type) {
        return renameResource(store, serverId, oldId, newId, type, () -> {
        });
    }

    private boolean renameResource(TypedGraphCache store, String serverId, String oldId, String newId, ReSyncResourceType type, Runnable rollback) {
        if (newId == null || newId.isBlank()) {
            return false;
        }
        String trimmedId = newId.trim();
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (flowClient == null) {
            return false;
        }
        if (!store.rename(serverId, type, oldId, trimmedId, type::applyRename)) {
            return false;
        }
        store.resolveDisplayName(serverId, type, oldId, trimmedId);
        FlowGraph graph = store.get(serverId, type, trimmedId);
        SyncedResourceCache.SaveLease<FlowGraph> lease = store.getDraftLease(serverId, type, trimmedId);
        if (graph == null || lease == null) {
            restoreGraphRename(store, serverId, type, trimmedId, oldId);
            return false;
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, type, trimmedId,
            store.getName(serverId, type, trimmedId));
        Async<SaveTicketSettlement> completion = saveTicketSettlement(ticket);
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        store.markSaving(serverId, type, trimmedId);
        if (!flowClient.sendResourceSave(type, graph, ticket)) {
            if (lease.isCurrent()) {
                restoreGraphRename(store, serverId, type, trimmedId, oldId);
                store.resolveDisplayName(serverId, type, trimmedId, oldId);
                rollback.run();
            }
            refreshStudioWorkspace(serverId);
            return false;
        }
        completion.thenAccept(settlement -> ScreenManager.getInstance().execute(() -> runIfCurrentServerConnection(token, () -> {
            if (settlement.saved()) {
                flowClient.sendResourceDelete(type, oldId);
            } else if (settlement.currentAtFinish() && lease.isCurrent()) {
                restoreGraphRename(store, serverId, type, trimmedId, oldId);
                store.resolveDisplayName(serverId, type, trimmedId, oldId);
                rollback.run();
            }
            refreshStudioWorkspace(serverId);
        })));
        refreshStudioWorkspace(serverId);
        return true;
    }

    private boolean renameFunction(String serverId, String oldId, String newId) {
        String trimmedId = newId != null ? newId.trim() : "";
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (trimmedId.isBlank() || flowClient == null || !flowStore.rename(serverId, ReSyncResourceType.FUNCTION, oldId, trimmedId, ReSyncResourceType.FUNCTION::applyRename)) {
            return false;
        }
        flowStore.resolveDisplayName(serverId, ReSyncResourceType.FUNCTION, oldId, trimmedId);
        FlowGraph renamed = flowStore.get(serverId, ReSyncResourceType.FUNCTION, trimmedId);
        SyncedResourceCache.SaveLease<FlowGraph> lease = flowStore.getDraftLease(serverId, ReSyncResourceType.FUNCTION, trimmedId);
        if (renamed == null || lease == null) {
            restoreGraphRename(flowStore, serverId, ReSyncResourceType.FUNCTION, trimmedId, oldId);
            return false;
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.FUNCTION, trimmedId,
            flowStore.getName(serverId, ReSyncResourceType.FUNCTION, trimmedId));
        Async<SaveTicketSettlement> completion = saveTicketSettlement(ticket);
        ServerConnectionToken token = captureServerConnectionToken(serverId, flowClient);
        flowStore.markSaving(serverId, ReSyncResourceType.FUNCTION, trimmedId);
        if (!flowClient.sendResourceSave(ReSyncResourceType.FUNCTION, renamed, ticket)) {
            if (lease.isCurrent()) {
                restoreGraphRename(flowStore, serverId, ReSyncResourceType.FUNCTION, trimmedId, oldId);
                flowStore.resolveDisplayName(serverId, ReSyncResourceType.FUNCTION, trimmedId, oldId);
            }
            refreshStudioWorkspace(serverId);
            return false;
        }
        completion.thenAccept(settlement -> ScreenManager.getInstance().execute(() -> runIfCurrentServerConnection(token, () -> {
            if (!settlement.saved()) {
                if (settlement.currentAtFinish() && lease.isCurrent()) {
                    restoreGraphRename(flowStore, serverId, ReSyncResourceType.FUNCTION, trimmedId, oldId);
                    flowStore.resolveDisplayName(serverId, ReSyncResourceType.FUNCTION, trimmedId, oldId);
                }
                refreshStudioWorkspace(serverId);
                return;
            }
            int refactored = refactorFunctionReferences(serverId, oldId, trimmedId);
            flowClient.sendResourceDelete(ReSyncResourceType.FUNCTION, oldId);
            invalidateProjectCatalog(serverId);
            refreshStudioWorkspace(serverId);
            if (refactored > 0) {
                new Notification("Function Renamed", refactored + " References Updated", Notification.Type.SUCCESS);
            }
        })));
        invalidateProjectCatalog(serverId);
        refreshStudioWorkspace(serverId);
        return true;
    }

    private boolean restoreGraphRename(TypedGraphCache store, String serverId, ReSyncResourceType type,
                                       String renamedId, String originalId) {
        FlowGraph draft = store.getFromDraft(serverId, type, renamedId);
        if (draft == null) {
            return false;
        }
        FlowGraph restored = detachedGraph(draft);
        if (restored == null) {
            return false;
        }
        type.applyRename(restored, originalId);
        store.remove(serverId, type, renamedId);
        store.putInDraft(serverId, restored);
        return true;
    }

    private int refactorFunctionReferences(String serverId, String oldFunctionId, String newFunctionId) {
        int replacements = 0;
        for (FlowGraph source : flowStore.valuesForServer(serverId)) {
            FlowGraph graph = detachedGraph(source);
            if (graph == null) {
                continue;
            }
            int changed = FunctionReferenceAnalyzer.replaceGraphReferences(graph, oldFunctionId, newFunctionId);
            if (changed > 0) {
                saveFlow(serverId, graph);
                replacements += changed;
            }
        }
        replacements += refactorSerializedFunctionReferences(serverId, ReSyncResourceType.GUI, guiStore.getForServer(serverId), oldFunctionId, newFunctionId);
        replacements += refactorSerializedFunctionReferences(serverId, ReSyncResourceType.SCOREBOARD, scoreboardStore.getForServer(serverId), oldFunctionId, newFunctionId);
        replacements += refactorSerializedFunctionReferences(serverId, ReSyncResourceType.TAB, tabStore.getForServer(serverId), oldFunctionId, newFunctionId);
        replacements += refactorSerializedFunctionReferences(serverId, ReSyncResourceType.CUSTOM_CONTENT, customContentStore.getForServer(serverId), oldFunctionId, newFunctionId);
        for (Map.Entry<ReSyncResourceType, SyncedResourceCache<JsonObject>> storeEntry : jsonResourceStores.entrySet()) {
            for (JsonObject source : storeEntry.getValue().getForServer(serverId).values()) {
                JsonObject resource = source != null ? source.deepCopy() : null;
                if (resource == null) {
                    continue;
                }
                int changed = FunctionReferenceAnalyzer.replaceJsonReferences(resource, oldFunctionId, newFunctionId);
                if (changed > 0) {
                    saveJsonResource(serverId, storeEntry.getKey(), resource);
                    replacements += changed;
                }
            }
        }
        return replacements;
    }

    private void reconcileFunctionSignature(String serverId, FlowGraph function) {
        if (function == null || function.getId() == null || function.getId().isBlank()) {
            return;
        }
        boolean legacy = usesLegacyFunctionIdentity(function.getFunctionInputs()) || usesLegacyFunctionIdentity(function.getFunctionOutputs());
        Set<String> inputPins;
        Set<String> outputPins;
        if (legacy) {
            Optional<Set<String>> inputIdentities = functionParameterIdentities(function.getFunctionInputs());
            Optional<Set<String>> outputIdentities = functionParameterIdentities(function.getFunctionOutputs());
            if (inputIdentities.isEmpty() || outputIdentities.isEmpty()) {
                return;
            }
            inputPins = inputIdentities.orElseThrow();
            outputPins = outputIdentities.orElseThrow();
        } else {
            inputPins = functionParameterPins(function.getFunctionInputs(), FunctionReferenceAnalyzer.FUNCTION_INPUT_PIN_PREFIX);
            outputPins = functionParameterPins(function.getFunctionOutputs(), FunctionReferenceAnalyzer.FUNCTION_OUTPUT_PIN_PREFIX);
        }
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        for (FlowGraph source : flowStore.valuesForServer(serverId)) {
            FlowGraph caller = detachedGraph(source);
            if (caller == null || hasUnidentifiedFunctionCallerPin(caller, function.getId(), legacy)) {
                continue;
            }
            int changed = legacy
                ? FunctionReferenceAnalyzer.reconcileLegacyGraphCallers(caller, function.getId(), inputPins, outputPins)
                : FunctionReferenceAnalyzer.reconcileGraphCallers(caller, function.getId(), inputPins, outputPins);
            if (changed == 0) {
                continue;
            }
            ReSyncResourceType callerType = ReSyncResourceType.byTypeId(caller.getResourceType());
            if (callerType == null || !callerType.isGraph()) {
                callerType = ReSyncResourceType.FLOW;
            }
            flowStore.putInDraft(serverId, caller);
            FlowGraph pending = flowStore.getFromDraft(serverId, callerType, caller.getId());
            if (flowClient != null) {
                saveGraph(serverId, callerType, pending, null, false);
            }
        }
    }

    private static boolean hasUnidentifiedFunctionCallerPin(FlowGraph graph, String functionId, boolean legacy) {
        if (graph == null || graph.getNodes() == null || graph.getConnections() == null || functionId == null || functionId.isBlank()) {
            return false;
        }
        Set<String> callerNodeIds = graph.getNodes().entrySet().stream()
            .filter(entry -> entry.getValue() != null && (FunctionReferenceAnalyzer.NODE_PREFIX + functionId).equals(entry.getValue().getType()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        if (callerNodeIds.isEmpty()) {
            return false;
        }
        for (FlowConnection connection : graph.getConnections()) {
            if (connection == null) {
                continue;
            }
            if (callerNodeIds.contains(connection.getTargetNodeId())) {
                String pin = legacy ? connection.getTargetPin() : connection.getTargetPinId();
                if (pin == null || pin.isBlank()) {
                    return true;
                }
            }
            if (callerNodeIds.contains(connection.getSourceNodeId())) {
                String pin = legacy ? connection.getSourcePin() : connection.getSourcePinId();
                if (pin == null || pin.isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    void completeResourceActivationAuthoritative(String serverId, ReSyncResourceType type, String id, boolean enabled,
                                                  String requestId, String message, boolean notifyFailure) {
        PendingActivation pending = matchingPendingActivation(serverId, type, id, requestId);
        if (pending != null && pending.ownerToken() != null) {
            enqueueCoreCompletion(pending.ownerToken(),
                () -> completeResourceActivationAuthoritativeNow(serverId, type, id, enabled, requestId, message, notifyFailure),
                () -> completeResourceActivationNow(serverId, type, id, pending.enabled(), requestId, false,
                    "The ReSync connection changed before activation completed.", true));
            return;
        }
        if (type != null && type.isGraph()) {
            CoreGraphHandoff handoff = currentCoreGraphHandoff(serverId, type, id);
            enqueueCoreUiTransition(handoff,
                () -> completeResourceActivationAuthoritativeNow(serverId, type, id, enabled, requestId, message, notifyFailure));
            return;
        }
        enqueueCoreUiTransition(serverId,
            () -> completeResourceActivationAuthoritativeNow(serverId, type, id, enabled, requestId, message, notifyFailure));
    }

    private void completeResourceActivationAuthoritativeNow(String serverId, ReSyncResourceType type, String id, boolean enabled,
                                                             String requestId, String message, boolean notifyFailure) {
        ActivationKey key = new ActivationKey(serverId, type, id);
        PendingActivation pending;
        synchronized (resourceActivationLock) {
            pending = pendingActivations.get(key);
            if (pending == null || !pending.requestId().equals(requestId) || !pendingActivations.remove(key, pending)) {
                return;
            }
            applyResourceActivationState(serverId, type, id, enabled);
        }
        notifyResourceActivationAuthoritativeNow(serverId, type, id, enabled, message, notifyFailure);
    }

    void applyAuthoritativeResourceActivationState(String serverId, ReSyncResourceType type, String id, boolean enabled) {
        if (type != null && type.isGraph()) {
            CoreGraphHandoff handoff = currentCoreGraphHandoff(serverId, type, id);
            if (handoff == null || handoff.deleted()) {
                return;
            }
            enqueueCoreUiTransition(handoff, () -> applyResourceActivationState(serverId, type, id, enabled));
            return;
        }
        enqueueCoreUiTransition(serverId, () -> applyResourceActivationState(serverId, type, id, enabled));
    }

    private static Set<String> functionParameterPins(List<FlowGraph.FunctionParameter> parameters, String prefix) {
        Set<String> pins = new HashSet<>();
        if (parameters == null) {
            return pins;
        }
        for (FlowGraph.FunctionParameter parameter : parameters) {
            String identity = parameter != null ? parameter.getParameterId() : null;
            if (identity != null && !identity.isBlank()) {
                String pin = FunctionReferenceAnalyzer.FUNCTION_INPUT_PIN_PREFIX.equals(prefix)
                    ? FunctionReferenceAnalyzer.canonicalInputPin(identity)
                    : FunctionReferenceAnalyzer.canonicalOutputPin(identity);
                if (pin.isBlank()) {
                    continue;
                }
                pins.add(pin);
            }
        }
        return pins;
    }

    private static Optional<Set<String>> functionParameterIdentities(List<FlowGraph.FunctionParameter> parameters) {
        Set<String> identities = new HashSet<>();
        if (parameters == null) {
            return Optional.of(Set.of());
        }
        for (FlowGraph.FunctionParameter parameter : parameters) {
            String identity = FunctionSignatureTypeResolver.parameterIdentity(parameter);
            if (identity == null || identity.isBlank()) {
                return Optional.empty();
            }
            identities.add(identity);
        }
        return Optional.of(Set.copyOf(identities));
    }

    private static boolean usesLegacyFunctionIdentity(List<FlowGraph.FunctionParameter> parameters) {
        if (parameters == null) {
            return false;
        }
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter == null) {
                return true;
            }
            String parameterId = parameter.getParameterId();
            if (parameterId == null || parameterId.isBlank()) {
                return true;
            }
            String identity = FunctionSignatureTypeResolver.parameterIdentity(parameter);
            if (identity == null || identity.isBlank() || !identity.equals(parameterId)) {
                return true;
            }
        }
        return false;
    }

    private <T> void collectSerializedFunctionReferences(List<FunctionReference> references, ReSyncResourceType type, Map<String, T> resources, String functionId) {
        for (Map.Entry<String, T> entry : resources.entrySet()) {
            JsonElement serialized = FlowJson.parse(type.serialize(entry.getValue()));
            for (String location : FunctionReferenceAnalyzer.findJsonReferences(serialized, functionId)) {
                references.add(new FunctionReference(type.typeId(), entry.getKey(), location));
            }
        }
    }

    private <T> int refactorSerializedFunctionReferences(String serverId, ReSyncResourceType type, Map<String, T> resources, String oldFunctionId, String newFunctionId) {
        int replacements = 0;
        for (T resource : resources.values()) {
            JsonElement serialized = FlowJson.parse(type.serialize(resource));
            int changed = FunctionReferenceAnalyzer.replaceJsonReferences(serialized, oldFunctionId, newFunctionId);
            if (changed == 0) {
                continue;
            }
            Object refactored = type.deserialize(gson.toJson(serialized));
            saveRefactoredResource(serverId, type, refactored);
            replacements += changed;
        }
        return replacements;
    }

    private void saveRefactoredResource(String serverId, ReSyncResourceType type, Object resource) {
        switch (type) {
            case GUI -> saveGui(serverId, (GuiDefinition) resource);
            case SCOREBOARD -> saveScoreboard(serverId, (ScoreboardDefinition) resource);
            case TAB -> saveTab(serverId, (TabDefinition) resource);
            case CUSTOM_CONTENT -> saveCustomContent(serverId, (CustomContentDefinition) resource);
            default -> {
            }
        }
    }

    public void refreshFlowsFromServer(String serverId) {
        flowStore.clearForServer(serverId);
        withFlowClient(serverId, flowClient -> {
            for (ReSyncResourceType type : List.of(ReSyncResourceType.FLOW, ReSyncResourceType.FUNCTION, ReSyncResourceType.COMMAND)) {
                flowClient.requestResourceList(type);
            }
            return null;
        });
        refreshStudioWorkspace(serverId);
    }

    public void refreshGuisFromServer(String serverId) {
        guiStore.clearForServer(serverId);
        withFlowClient(serverId, flowClient -> {
            flowClient.requestGuiList();
            return null;
        });
        refreshStudioWorkspace(serverId);
    }

    public void refreshScoreboardsFromServer(String serverId) {
        scoreboardStore.clearForServer(serverId);
        withFlowClient(serverId, flowClient -> {
            flowClient.requestScoreboardList();
            return null;
        });
        refreshStudioWorkspace(serverId);
    }

    public void refreshTabsFromServer(String serverId) {
        tabStore.clearForServer(serverId);
        withFlowClient(serverId, flowClient -> {
            flowClient.requestTabList();
            return null;
        });
        refreshStudioWorkspace(serverId);
    }

    public void refreshCustomContentFromServer(String serverId) {
        customContentStore.clearForServer(serverId);
        withFlowClient(serverId, flowClient -> {
            flowClient.requestCustomContentList();
            return null;
        });
        refreshStudioWorkspace(serverId);
    }

    public void refreshProjectMetadataFromServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        refreshCustomizationResourcesFromServer(serverId);
        withFlowClient(serverId, flowClient -> {
            flowClient.requestProjectMetadataList();
            return null;
        });
        refreshStudioWorkspace(serverId);
    }

    public void refreshCustomizationResourcesFromServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        for (ReSyncResourceType type : ReSyncResourceType.values()) {
            SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
            if (store != null) {
                store.clearForServer(serverId);
            }
        }
        withFlowClient(serverId, flowClient -> {
            for (ReSyncResourceType type : ReSyncResourceType.values()) {
                if (jsonResourceStores.get(type) != null) {
                    flowClient.requestResourceList(type);
                }
            }
            return null;
        });
    }

    public void refreshWorldsFromServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        withFlowClient(serverId, flowClient -> {
            flowClient.requestWorldSnapshot();
            flowClient.requestPlayerTrackingSnapshot();
            return null;
        });
        refreshStudioWorkspace(serverId);
    }

    public void applyServerFlowList(String serverId, List<String> flowIds) {
        applyServerGraphList(serverId, ReSyncResourceType.FLOW, flowIds);
    }

    public void applyServerGraphList(String serverId, ReSyncResourceType type, List<String> graphIds) {
        if (type == null || !type.isGraph() || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        if (!isCurrentServerConnection(token)) {
            return;
        }
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (!coreGraphAuthorityEnabled(serverId)) {
            flowStore.applyServerList(serverId, type, graphIds != null ? graphIds : List.of());
        }
        publishTypedMembership(token, type, graphIds);
        for (String graphId : new HashSet<>(graphIds != null ? graphIds : List.of())) {
            if (!hasHydratedResource(serverId, type, graphId)) {
                flowClient.requestResource(type, graphId, false);
            }
        }
        refreshStudioWorkspace(serverId);
    }

    public void applyServerGuiList(String serverId, List<String> guiIds) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        if (!isCurrentServerConnection(token)) {
            return;
        }
        guiStore.applyServerList(serverId, guiIds);
        publishTypedMembership(token, ReSyncResourceType.GUI, guiIds);
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (guiIds != null) {
            for (String guiId : guiIds) {
                if (!hasHydratedResource(serverId, ReSyncResourceType.GUI, guiId)) {
                    flowClient.requestGui(guiId, false);
                }
            }
        }
        refreshStudioWorkspace(serverId);
    }

    public void applyServerScoreboardList(String serverId, List<String> scoreboardIds) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        if (!isCurrentServerConnection(token)) {
            return;
        }
        scoreboardStore.applyServerList(serverId, scoreboardIds);
        publishTypedMembership(token, ReSyncResourceType.SCOREBOARD, scoreboardIds);
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (scoreboardIds != null) {
            for (String scoreboardId : scoreboardIds) {
                if (!hasHydratedResource(serverId, ReSyncResourceType.SCOREBOARD, scoreboardId)) {
                    flowClient.requestScoreboard(scoreboardId, false);
                }
            }
        }
        refreshStudioWorkspace(serverId);
    }

    public void applyServerTabList(String serverId, List<String> tabIds) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        if (!isCurrentServerConnection(token)) {
            return;
        }
        tabStore.applyServerList(serverId, tabIds);
        publishTypedMembership(token, ReSyncResourceType.TAB, tabIds);
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (tabIds != null) {
            for (String tabId : tabIds) {
                if (!hasHydratedResource(serverId, ReSyncResourceType.TAB, tabId)) {
                    flowClient.requestTab(tabId, false);
                }
            }
        }
        refreshStudioWorkspace(serverId);
    }

    public void applyServerCustomContentList(String serverId, List<String> contentIds) {
        if (!synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        if (!isCurrentServerConnection(token)) {
            return;
        }
        customContentStore.applyServerList(serverId, contentIds);
        publishTypedMembership(token, ReSyncResourceType.CUSTOM_CONTENT, contentIds);
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (contentIds != null) {
            for (String contentId : contentIds) {
                if (!hasHydratedResource(serverId, ReSyncResourceType.CUSTOM_CONTENT, contentId)) {
                    flowClient.requestCustomContent(contentId, false);
                }
            }
        }
        invalidateCustomContentOptionCatalogs(serverId);
        refreshStudioWorkspace(serverId);
    }

    public void applyServerProjectMetadataList(String serverId, List<String> metadataIds) {
        if (serverId == null || serverId.isBlank() || !synchronizeLegacyReadAuthorityEpoch(serverId)) {
            return;
        }
        loadedProjectMetadataLists.add(serverId);
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        if (metadataIds != null && !metadataIds.isEmpty()
            && !hasHydratedResource(serverId, ReSyncResourceType.PROJECT_METADATA, metadataIds.getFirst())) {
            pendingProjectMetadataDocuments.add(serverId);
            flowClient.requestProjectMetadata(metadataIds.getFirst());
        } else {
            refreshFlowWorkspace(serverId, false);
        }
    }

    private boolean hasHydratedResource(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || id == null || id.isBlank()) {
            return false;
        }
        if (type.isGraph()) {
            return getGraph(serverId, type, id) != null;
        }
        if (type == ReSyncResourceType.GUI) {
            return guiStore.getOwned(serverId, id) != null;
        }
        if (type == ReSyncResourceType.SCOREBOARD) {
            return scoreboardStore.getOwned(serverId, id) != null;
        }
        if (type == ReSyncResourceType.TAB) {
            return tabStore.getOwned(serverId, id) != null;
        }
        if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            return customContentStore.getOwned(serverId, id) != null;
        }
        if (type == ReSyncResourceType.PROJECT_METADATA) {
            return projectMetadataStore.getFromDraft(serverId, serverId) != null
                || projectMetadataStore.getFromCache(serverId, serverId) != null;
        }
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        return store != null && store.get(serverId, id) != null;
    }

    private void hydrateProjectMetadata(String serverId, ReSyncProjectMetadata metadata) {
        metadata.setServerId(serverId);
        metadata.ensureDefaultFolders();
        boolean deduplicatedResources = metadata.deduplicateResources();
        boolean removedMissingGraphs = metadata.getResources().removeIf(resource -> {
            ReSyncResourceType type = resource != null ? ReSyncResourceType.byTypeId(resource.getType()) : null;
            return type != null && type.isGraph() && resource.getId() != null
                && missingFromAuthoritativeGraphList(serverId, type, resource.getId());
        });
        boolean removedCorruptCommands = metadata.getResources().removeIf(resource -> resource != null
            && ReSyncResourceDragPayload.COMMAND.equals(resource.getType())
            && isCommandFlowIdentityBlocked(serverId, resource.getId()));
        if (removedCorruptCommands) {
            synchronized (triggerBindingsLock) {
                List<TriggerBinding> bindings = triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>());
                bindings.removeIf(binding -> binding != null
                    && binding.getType() == TriggerType.COMMAND
                    && isCommandFlowIdentityBlocked(serverId, binding.getFlowId()));
            }
        }
        if ((removedCorruptCommands || deduplicatedResources || removedMissingGraphs) && canPersistProjectMetadata(serverId)) {
            persistProjectMetadata(serverId, metadata);
            if (removedCorruptCommands) {
                sendTriggerUpdate(serverId);
            }
        }
    }

    public void applyPlayerTrackingUpdate(String serverId, PlayerTrackingUpdate update) {
        playerService.applyPlayerTrackingUpdate(serverId, update);
    }

    public PlayerDossier getPlayerDossier(String serverId, UUID playerId) {
        return playerService.getPlayerDossier(serverId, playerId);
    }

    public List<String> getOnlinePlayerNamesForServer(String serverId) {
        return playerService.getOnlinePlayerNamesForServer(serverId);
    }

    public List<PlayerDossier> getOnlinePlayersForServer(String serverId) {
        return playerService.getOnlinePlayersForServer(serverId);
    }

    public Async<List<PlayerDossier>> requestOnlinePlayers(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return Async.failed(new IllegalArgumentException("Server Identifier Is Required"));
        }
        requestPlayerTrackingSnapshot(serverId);
        return Async.completed(getOnlinePlayersForServer(serverId));
    }

    public long getPlayerTrackingSnapshotRevision(String serverId) {
        return playerService.snapshotRevision(serverId);
    }

    public void requestPlayerTrackingSnapshot(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        withFlowClient(serverId, flowClient -> {
            flowClient.requestPlayerTrackingSnapshot();
            return null;
        });
    }

    public void requestPlayerDossier(String serverId, UUID playerId) {
        if (serverId == null || serverId.isBlank() || playerId == null) {
            return;
        }
        ReSyncFlowClient client = connectionManager.getFlowClient(serverId);
        if (client != null && client.isConnectedState()) client.requestPlayerDossier(playerId);
    }

    public AutoCloseable watchPlayer(String serverId, UUID playerId) {
        ReSyncFlowClient client = connectionManager.getFlowClient(serverId);
        if (client == null || !client.isConnectedState() || playerId == null) return () -> {};
        client.watchPlayer(playerId);
        client.requestPlayerControlCapabilities();
        return () -> client.unwatchPlayer(playerId);
    }

    public Async<JsonObject> requestPlayerControl(String serverId, String action, UUID playerId, Map<String, Object> payload) {
        ReSyncFlowClient client = connectionManager.getFlowClient(serverId);
        if (client == null || !client.isConnectedState()) {
            return Async.failed(new IllegalStateException("ReSync Unavailable"));
        }
        return client.requestPlayerControl(action, playerId, payload);
    }

    public JsonObject getPlayerControlCapabilities(String serverId) {
        ReSyncFlowClient client = connectionManager.getFlowClient(serverId);
        return client != null ? client.getPlayerControlCapabilities() : null;
    }

    public Map<String, WorldRegistryEntry> getWorldsForServer(String serverId) {
        return worldService.getWorldsForServer(serverId);
    }

    public void applyCollaborativeWorld(String serverId, WorldRegistryEntry world) {
        worldService.applyCollaborativeWorld(serverId, world);
    }

    public List<WorldDashboardEntry> getWorldDashboardForServer(String serverId) {
        return worldService.getWorldDashboardForServer(serverId);
    }

    public List<WorldInventoryGroup> getWorldInventoryGroupsForServer(String serverId) {
        return worldService.getWorldInventoryGroupsForServer(serverId);
    }

    public WorldInventoryGroup getWorldInventoryGroup(String serverId, String groupId) {
        return worldService.getWorldInventoryGroup(serverId, groupId);
    }

    public WorldRegistryEntry getWorld(String serverId, String worldName) {
        return worldService.getWorld(serverId, worldName);
    }

    public WorldSnapshot getWorldSnapshot(String serverId) {
        return worldService.getWorldSnapshot(serverId);
    }

    public WorldOperationResult getLastWorldOperationResult(String serverId) {
        return worldService.getLastWorldOperationResult(serverId);
    }

    public WorldMapSnapshot getWorldMapSnapshot(String serverId, String worldName) {
        return worldService.getWorldMapSnapshot(serverId, worldName);
    }

    public void applyWorldManagementMessage(String serverId, WorldChannelMessage message) {
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        worldService.applyWorldManagementMessage(serverId, message, this);
        if (message != null && message.getData() != null && !message.getData().isJsonNull()
            && "snapshot".equalsIgnoreCase(message.getAction()) && worldService.getWorldSnapshot(serverId) != null
            && isCurrentServerConnection(token)) {
            worldSnapshotGenerations.put(serverId, token);
            advanceProjectMembershipRevision(serverId);
        }
    }

    public void requestWorldMapSnapshot(String serverId, String worldName, double centerX, double centerZ, int zoom) {
        worldService.requestWorldMapSnapshot(serverId, worldName, centerX, centerZ, zoom, this);
    }

    public void requestWorldAuditSnapshot(String serverId) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("auditSnapshot", "limit", 25));
    }

    public void suppressNextWorldSuccessNotification(String serverId, String action) {
        worldService.suppressNextWorldSuccessNotification(serverId, action);
    }

    public long beginWorldSaveNotification(String serverId, String worldName, int operationCount) {
        return worldService.beginWorldSaveNotification(serverId, worldName, operationCount);
    }

    public void beginWorldOperationNotification(String serverId, String targetName, int operationCount, String savingTitle, String successTitle, String failureTitle) {
        worldService.beginWorldOperationNotification(serverId, targetName, operationCount, savingTitle, successTitle, failureTitle);
    }

    public void createWorld(String serverId, String worldName, String seed, String environment, String generator, String generatorConfig) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("createWorld", "worldName", worldName, "seed", seed, "environment", environment, "generator", generator, "generatorConfig", generatorConfig));
    }

    public void importWorlds(String serverId) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("importUnregisteredWorlds"));
    }

    public void scanWorlds(String serverId) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("scanUnregisteredWorlds"));
    }

    public void cloneWorld(String serverId, String sourceWorld, String targetWorld, boolean loadAfterClone) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("cloneWorld", "sourceWorld", sourceWorld, "targetWorld", targetWorld, "loadAfterClone", loadAfterClone));
    }

    public void loadWorld(String serverId, String worldName) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("loadWorld", "worldName", worldName));
    }

    public void unloadWorld(String serverId, String worldName, String fallbackWorld) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("unloadWorld", "worldName", worldName, "fallbackWorld", fallbackWorld));
    }

    public void deleteWorld(String serverId, String worldName, boolean deleteFiles, String fallbackWorld) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("deleteWorld", "worldName", worldName, "deleteFiles", deleteFiles, "fallbackWorld", fallbackWorld));
    }

    public void setWorldGameRule(String serverId, String worldName, String ruleName, String value) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("setGameRule", "worldName", worldName, "ruleName", ruleName, "ruleValue", value));
    }

    public void setWorldGameRules(String serverId, String worldName, Map<String, String> rules) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("setGameRules", "worldName", worldName, "gameRules", rules));
    }

    public void setWorldDifficulty(String serverId, String worldName, String difficulty) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("setDifficulty", "worldName", worldName, "difficulty", difficulty));
    }

    public void setWorldTimeLock(String serverId, String worldName, boolean enabled, long lockedTime) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("setTimeLock", "worldName", worldName, "enabled", enabled, "lockedTime", lockedTime));
    }

    public void setWorldWeatherLock(String serverId, String worldName, boolean enabled, boolean storm, boolean thundering) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("setWeatherLock", "worldName", worldName, "enabled", enabled, "storm", storm, "thundering", thundering));
    }

    public void setWorldIsolatedState(String serverId, String worldName, boolean enabled) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("setIsolatedPlayerState", "worldName", worldName, "enabled", enabled));
    }

    public void setWorldProfile(String serverId, String worldName, WorldProfileSettings profileSettings) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("setWorldProfile", "worldName", worldName, "profileSettings", profileSettings));
    }

    public void teleportPlayerToWorld(String serverId, String playerName, String worldName, Double x, Double y, Double z, Float yaw, Float pitch) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction(
            "teleportPlayerToWorld",
            "playerName", playerName,
            "worldName", worldName,
            "destinationX", x,
            "destinationY", y,
            "destinationZ", z,
            "destinationYaw", yaw,
            "destinationPitch", pitch,
            "hasPosition", x != null && y != null && z != null,
            "hasRotation", yaw != null && pitch != null
        ));
    }

    public void teleportPlayerToWorldSpawn(String serverId, String playerName, String worldName) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("teleportPlayerToWorldSpawn", "playerName", playerName, "worldName", worldName));
    }

    public void createInventoryGroup(String serverId, WorldInventoryGroup group) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("createInventoryGroup", "inventoryGroup", group));
    }

    public void updateInventoryGroup(String serverId, WorldInventoryGroup group) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("updateInventoryGroup", "inventoryGroup", group));
    }

    public void deleteInventoryGroup(String serverId, String groupId) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("deleteInventoryGroup", "groupId", groupId));
    }

    public void whoWorld(String serverId, String worldName) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction("whoWorld", "worldName", worldName));
    }

    public void purgeWorld(String serverId, String worldName, boolean monsters, boolean animals, boolean ambient, boolean misc, boolean vehicles, boolean items) {
        sendWorldAction(serverId, ReSyncWorldService.worldAction(
            "purgeWorld",
            "worldName", worldName,
            "purgeMonsters", monsters,
            "purgeAnimals", animals,
            "purgeAmbient", ambient,
            "purgeMisc", misc,
            "purgeVehicles", vehicles,
            "purgeItems", items
        ));
    }

    public void openWorldMap(String serverId, ClientServerView server, String worldName) {
        ApplicationHost host = getApplicationHost();
        Object parent = resolveDesignerParent(null);
        if (!FlowManagerUiAdapter.forHost(host).openWorldMap(this, host, serverId, server, worldName, parent)) {
            new Notification("WorldMap", "InstanceUnavailable", Notification.Type.ERROR);
        }
    }

    public List<TriggerBinding> getBindings(String serverId) {
        return snapshotTriggerBindings(serverId);
    }

    boolean applyAuthoritativeTriggerBindings(String serverId, List<TriggerBinding> authoritative,
                                              ReSyncFlowClient source, ServerConnectionToken connectionToken,
                                              int transportGeneration, long authorityEpoch,
                                              long bindingEpoch, String bindingHash) {
        if (serverId == null || serverId.isBlank() || authoritative == null || source == null
            || connectionToken == null || transportGeneration < 0 || authorityEpoch < 1L || bindingEpoch < 1L
            || bindingHash == null || bindingHash.isBlank()) {
            return false;
        }
        boolean[] applied = {false};
        synchronized (serverConnectionGenerationLock) {
            if (!creationConnectionCurrent(serverId, source, connectionToken, transportGeneration, authorityEpoch)) {
                return false;
            }
            boolean fenced = source.withCurrentTransportGeneration(transportGeneration, () -> {
                if (!creationConnectionCurrent(serverId, source, connectionToken, transportGeneration, authorityEpoch)) {
                    return false;
                }
                if (!source.matchesTriggerBindingState(bindingEpoch, bindingHash)) {
                    return false;
                }
                synchronized (triggerBindingsLock) {
                    List<TriggerBinding> target = triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>());
                    target.clear();
                    target.addAll(copyTriggerBindings(authoritative));
                    applied[0] = true;
                }
                return true;
            });
            if (!fenced || !applied[0]) {
                return false;
            }
        }
        invalidateProjectCatalog(serverId);
        return true;
    }

    public FlowGraph resolveCommandFlowGraph(String serverId, String commandResourceId) {
        if (serverId == null || commandResourceId == null || commandResourceId.isBlank()) {
            return null;
        }
        FlowGraph graph = getGraph(serverId, ReSyncResourceType.COMMAND, commandResourceId);
        if (graph != null && CustomContentGraphAdapter.isContentGraph(graph)) {
            return null;
        }
        return graph;
    }

    public boolean isCommandFlowIdentityBlocked(String serverId, String flowId) {
        if (serverId == null || flowId == null || flowId.isBlank()) {
            return false;
        }
        FlowGraph graph = getGraph(serverId, ReSyncResourceType.COMMAND, flowId);
        return graph != null && CustomContentGraphAdapter.isContentGraph(graph);
    }

    public TriggerBinding getCommandBinding(String serverId, String flowId) {
        if (serverId == null || flowId == null) {
            return null;
        }
        for (TriggerBinding binding : getBindings(serverId)) {
            if (flowId.equals(binding.getFlowId()) && binding.getType() == TriggerType.COMMAND) {
                return binding;
            }
        }
        return null;
    }

    public void setCommandBinding(String serverId, String flowId, String context) {
        setCommandBindingAwait(serverId, flowId, context);
    }

    public Async<Boolean> setCommandBindingAwait(String serverId, String flowId, String context) {
        if (serverId == null || serverId.isBlank() || flowId == null || flowId.isBlank()) {
            return Async.completed(false);
        }
        hydrateCoreGraphProjection(serverId, ReSyncResourceType.COMMAND, flowId);
        if (coreGraphAuthorityEnabled(serverId)
            || coreGraphUiProjection.authoritative(serverId, ReSyncResourceType.COMMAND, flowId)) {
            return Async.completed(false);
        }
        String desiredContext = context != null && !context.isBlank() ? context : null;
        TriggerBinding previous = copyTriggerBinding(getCommandBinding(serverId, flowId));
        if (desiredContext != null && isCommandFlowIdentityBlocked(serverId, flowId)) {
            return Async.completed(false);
        }
        List<TriggerBinding> submittedBindings;
        synchronized (triggerBindingsLock) {
            List<TriggerBinding> bindings = triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>());
            bindings.removeIf(binding -> flowId.equals(binding.getFlowId()) && binding.getType() == TriggerType.COMMAND);
            if (desiredContext != null) {
                bindings.add(new TriggerBinding(flowId + ":command", flowId, TriggerType.COMMAND, desiredContext));
            }
            submittedBindings = bindings;
        }
        invalidateProjectCatalog(serverId);
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient == null) {
            restoreCommandBinding(serverId, flowId, desiredContext, previous);
            return Async.completed(false);
        }
        FlowGraph graph = desiredContext != null ? getGraph(serverId, ReSyncResourceType.COMMAND, flowId) : null;
        if (desiredContext != null && (graph == null || CustomContentGraphAdapter.isContentGraph(graph))) {
            if (connectionManager.getFlowClient(serverId) == flowClient) {
                restoreCommandBinding(serverId, flowId, desiredContext, previous);
            }
            return Async.completed(false);
        }
        boolean graphChanged = false;
        if (graph != null) {
            graphChanged |= ensureCommandStartNodeInMemory(graph);
            graphChanged |= applyCommandContext(graph, desiredContext);
        }
        Async<Boolean> graphSettlement = Async.completed(true);
        if (graphChanged) {
            DesignerSaveNotifications.SaveTicket graphTicket = DesignerSaveNotifications.startExact(serverId,
                ReSyncResourceType.COMMAND, flowId, "Command");
            if (graphTicket == null) {
                if (connectionManager.getFlowClient(serverId) == flowClient) {
                    restoreCommandBinding(serverId, flowId, desiredContext, previous);
                }
                return Async.completed(false);
            }
            Async<Boolean> graphResult = Async.pending();
            graphTicket.whenFinished((saved, ignoredCurrentAtFinish) -> graphResult.complete(saved));
            graphSettlement = graphResult;
            saveGraph(serverId, ReSyncResourceType.COMMAND, graph, graphTicket);
        }
        Async<Boolean> result = Async.pending();
        Async<Boolean> acceptedGraph = graphSettlement;
        acceptedGraph.whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                if (connectionManager.getFlowClient(serverId) == flowClient) {
                    restoreCommandBinding(serverId, flowId, desiredContext, previous);
                }
                result.complete(false);
                return;
            }
            ServerConnectionToken triggerConnectionToken = captureConnectedServerConnectionToken(serverId, flowClient);
            int triggerTransportGeneration = flowClient.activeTransportGeneration();
            long triggerAuthorityEpoch = flowClient.resourceRevisionReconciler().authorityEpoch(serverId);
            if (!creationConnectionCurrent(serverId, flowClient, triggerConnectionToken, triggerTransportGeneration,
                triggerAuthorityEpoch)) {
                result.complete(false);
                return;
            }
            Async<ReSyncFlowClient.TriggerUpdateOutcome> triggerSettlement;
            try {
                long expectedBindingEpoch = flowClient.triggerBindingEpoch();
                String expectedBindingHash = flowClient.triggerBindingHash();
                if (expectedBindingEpoch < 1L || expectedBindingHash == null || expectedBindingHash.isBlank()) {
                    if (creationConnectionCurrent(serverId, flowClient, triggerConnectionToken,
                        triggerTransportGeneration, triggerAuthorityEpoch)) {
                        restoreCommandBinding(serverId, flowId, desiredContext, previous);
                    }
                    result.complete(false);
                    return;
                }
                triggerSettlement = flowClient.sendTriggerUpdateAwait(submittedBindings,
                    UUID.randomUUID().toString(), expectedBindingEpoch, expectedBindingHash, false);
            } catch (RuntimeException exception) {
                triggerSettlement = Async.completed(
                    ReSyncFlowClient.TriggerUpdateOutcome.rejected(exception.getMessage()));
            }
            triggerSettlement.whenComplete((outcome, triggerError) -> ScreenManager.getInstance().execute(() -> {
                boolean hasAuthoritativeState = triggerError == null && outcome != null
                    && outcome.hasAuthoritativeState();
                boolean currentSource = creationConnectionCurrent(serverId, flowClient, triggerConnectionToken,
                    triggerTransportGeneration, triggerAuthorityEpoch);
                boolean authoritative = hasAuthoritativeState && currentSource;
                if (authoritative) {
                    authoritative = applyAuthoritativeTriggerBindings(serverId, outcome.bindings(), flowClient,
                        triggerConnectionToken, triggerTransportGeneration, triggerAuthorityEpoch,
                        outcome.bindingEpoch(), outcome.bindingHash());
                }
                boolean converged = authoritative && commandBindingMatches(serverId, flowId, desiredContext);
                boolean success = converged || authoritative && outcome.successful()
                    && commandBindingMatches(serverId, flowId, desiredContext);
                if (!success) {
                    if (!hasAuthoritativeState && currentSource) {
                        restoreCommandBinding(serverId, flowId, desiredContext, previous);
                    }
                    new Notification("Command", "Binding Save Failed", Notification.Type.ERROR);
                }
                result.complete(success);
            }));
        });
        return result;
    }

    private List<TriggerBinding> copyTriggerBindings(List<TriggerBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        return bindings.stream().map(this::copyTriggerBinding).filter(Objects::nonNull).toList();
    }

    private TriggerBinding copyTriggerBinding(TriggerBinding binding) {
        return binding == null ? null : new TriggerBinding(binding.getId(), binding.getFlowId(), binding.getType(), binding.getContext());
    }

    private boolean commandBindingMatches(String serverId, String flowId, String context) {
        TriggerBinding binding = getCommandBinding(serverId, flowId);
        return context == null ? binding == null : binding != null && context.equals(binding.getContext());
    }

    private boolean commandBindingMatchesInList(List<TriggerBinding> bindings, String flowId, String context) {
        if (bindings == null || flowId == null) {
            return false;
        }
        for (TriggerBinding binding : bindings) {
            if (binding != null && flowId.equals(binding.getFlowId()) && binding.getType() == TriggerType.COMMAND) {
                return context == null ? false : context.equals(binding.getContext());
            }
        }
        return context == null;
    }

    private boolean validTriggerBinding(TriggerBinding binding) {
        return binding != null && binding.getId() != null && !binding.getId().isBlank()
            && binding.getFlowId() != null && !binding.getFlowId().isBlank() && binding.getType() != null
            && binding.getId().length() <= 1024 && binding.getFlowId().length() <= 1024
            && (binding.getContext() == null || binding.getContext().length() <= 1024);
    }

    private void restoreCommandBinding(String serverId, String flowId, String expectedContext, TriggerBinding previous) {
        synchronized (triggerBindingsLock) {
            List<TriggerBinding> bindings = triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>());
            if (!commandBindingMatchesInList(bindings, flowId, expectedContext)) {
                return;
            }
            bindings.removeIf(binding -> flowId.equals(binding.getFlowId()) && binding.getType() == TriggerType.COMMAND);
            if (previous != null) {
                bindings.add(copyTriggerBinding(previous));
            }
        }
        invalidateProjectCatalog(serverId);
    }

    public void clearCommandBinding(String serverId, String flowId) {
        setCommandBinding(serverId, flowId, null);
    }

    public void addBinding(String serverId, TriggerBinding binding) {
        if (serverId == null || serverId.isBlank() || !validTriggerBinding(binding)) {
            return;
        }
        List<TriggerBinding> previous;
        List<TriggerBinding> submitted;
        synchronized (triggerBindingsLock) {
            List<TriggerBinding> target = triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>());
            if (target.stream().anyMatch(existing -> existing != null && binding.getId().equals(existing.getId()))) {
                return;
            }
            previous = copyTriggerBindings(target);
            target.add(copyTriggerBinding(binding));
            submitted = target;
        }
        sendTriggerUpdate(serverId, submitted, previous);
    }

    public void removeBinding(String serverId, String bindingId) {
        if (serverId == null || serverId.isBlank() || bindingId == null || bindingId.isBlank()) {
            return;
        }
        List<TriggerBinding> previous;
        List<TriggerBinding> submitted;
        synchronized (triggerBindingsLock) {
            List<TriggerBinding> target = triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>());
            previous = copyTriggerBindings(target);
            target.removeIf(binding -> bindingId.equals(binding.getId()));
            submitted = target;
        }
        sendTriggerUpdate(serverId, submitted, previous);
    }

    public void resolvePlaceholderPreview(String serverId, String text, Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        String value = text != null ? text : "";
        if (serverId == null || serverId.isBlank()) {
            callback.accept(value);
            return;
        }
        withFlowClient(serverId, flowClient -> {
            flowClient.requestPlaceholderPreview(value, true, callback);
            return null;
        }).whenComplete((settlement, error) -> {
            if (error != null || settlement == null || settlement.unavailable()) {
                callback.accept(value);
            }
        });
    }

    public void handleGuiStatePacket(String serverId, boolean editable, String guiId, String flowId) {
        if (!editable) {
            boolean sameServer = serverId == null || serverId.isBlank() || serverId.equals(guiOverlayServerId);
            boolean sameGui = guiId != null && !guiId.isBlank() && guiId.equals(guiOverlayGuiId);
            if (sameServer && sameGui) {
                clearGuiOverlayState();
                GuiEditOverlayState.clear();
            }
            return;
        }
        guiOverlayEditable = true;
        guiOverlayServerId = serverId;
        guiOverlayGuiId = guiId;
        guiOverlayFlowId = flowId;
        overlayRevision.incrementAndGet();
        GuiEditOverlayState.update(serverId, guiId, flowId, true);
        if (guiId != null && !guiId.isBlank()) {
            ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
            if (flowClient != null) {
                flowClient.requestGui(guiId, false);
            }
        }
    }

    public void handleEditTargetStatePacket(String serverId, boolean editable, String resourceType, String resourceId, String flowId) {
        if (!editable) {
            if (resourceType == null || resourceType.isBlank() || resourceType.equals(editTargetOverlayResourceType)) {
                clearEditTargetOverlayState();
            }
            return;
        }
        if (resourceType == null || resourceType.isBlank() || resourceId == null || resourceId.isBlank()) {
            return;
        }
        editTargetOverlayEditable = true;
        editTargetOverlayServerId = serverId;
        editTargetOverlayResourceType = resourceType;
        editTargetOverlayResourceId = resourceId;
        editTargetOverlayFlowId = flowId;
        overlayRevision.incrementAndGet();
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        if (flowClient != null) {
            if ("gui".equals(resourceType)) {
                flowClient.requestGui(resourceId, false);
            } else if ("scoreboard".equals(resourceType)) {
                flowClient.requestScoreboard(resourceId, false);
            } else {
                ReSyncResourceType jsonType = ReSyncResourceType.byTypeId(resourceType);
                if (jsonType != null) {
                    flowClient.requestResource(jsonType, resourceId, false);
                }
            }
        }
    }

    public boolean isGuiOverlayEditable() { return guiOverlayEditable; }
    public String getGuiOverlayServerId() { return guiOverlayServerId; }
    public String getGuiOverlayGuiId() { return guiOverlayGuiId; }
    public String getGuiOverlayFlowId() { return guiOverlayFlowId; }
    public boolean isEditTargetOverlayEditable() { return editTargetOverlayEditable; }
    public String getEditTargetOverlayServerId() { return editTargetOverlayServerId; }
    public String getEditTargetOverlayResourceType() { return editTargetOverlayResourceType; }
    public String getEditTargetOverlayResourceId() { return editTargetOverlayResourceId; }
    public String getEditTargetOverlayFlowId() { return editTargetOverlayFlowId; }
    public int getOverlayRevision() { return overlayRevision.get(); }

    public void clearOverlayState() {
        clearGuiOverlayState();
        clearEditTargetOverlayState();
    }

    private void clearGuiOverlayState() {
        guiOverlayEditable = false;
        guiOverlayServerId = null;
        guiOverlayGuiId = null;
        guiOverlayFlowId = null;
        overlayRevision.incrementAndGet();
    }

    private void clearEditTargetOverlayState() {
        editTargetOverlayEditable = false;
        editTargetOverlayServerId = null;
        editTargetOverlayResourceType = null;
        editTargetOverlayResourceId = null;
        editTargetOverlayFlowId = null;
        overlayRevision.incrementAndGet();
    }

    public void handleGuiDataReceived(String serverId, GuiDefinition gui) {
        if (gui == null || gui.getId() == null || gui.getId().isBlank()) {
            return;
        }
        if (flushPendingStudioEditTarget(serverId, ReSyncResourceDragPayload.GUI, gui.getId())) {
            return;
        }
        Object parent = guiStore.removePendingParent(serverId, gui.getId());
        if (parent != null) {
            boolean fullEditor = designerFullEditor(parent);
            parent = designerParent(parent);
            if (openExistingStudioDesigner(serverId, ReSyncResourceDragPayload.GUI, gui.getId(), fullEditor)) {
                return;
            }
            if (!fullEditor) {
                openStudioDocument(serverId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.GUI, gui.getId()),
                    screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.GUI, gui.getId(), false));
                return;
            }
            client.getHost().setScreen(new GuiDesignerScreen(detachedGui(gui), serverId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
        }
    }

    public void handleScoreboardDataReceived(String serverId, ScoreboardDefinition scoreboard) {
        if (scoreboard == null || scoreboard.getId() == null || scoreboard.getId().isBlank()) {
            return;
        }
        if (flushPendingStudioEditTarget(serverId, ReSyncResourceDragPayload.SCOREBOARD, scoreboard.getId())) {
            return;
        }
        Object parent = scoreboardStore.removePendingParent(serverId, scoreboard.getId());
        if (parent != null) {
            boolean fullEditor = designerFullEditor(parent);
            parent = designerParent(parent);
            if (openExistingStudioDesigner(serverId, ReSyncResourceDragPayload.SCOREBOARD, scoreboard.getId(), fullEditor)) {
                return;
            }
            if (!fullEditor) {
                openStudioDocument(serverId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.SCOREBOARD, scoreboard.getId()),
                    screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.SCOREBOARD, scoreboard.getId(), false));
                return;
            }
            client.getHost().setScreen(new ScoreboardDesignerScreen(detachedScoreboard(scoreboard), serverId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
        }
    }

    public void handleTabDataReceived(String serverId, TabDefinition tab) {
        if (tab == null || tab.getId() == null || tab.getId().isBlank()) {
            return;
        }
        if (flushPendingStudioEditTarget(serverId, ReSyncResourceDragPayload.TAB, tab.getId())) {
            return;
        }
        Object parent = tabStore.removePendingParent(serverId, tab.getId());
        if (parent != null) {
            boolean fullEditor = designerFullEditor(parent);
            parent = designerParent(parent);
            if (openExistingStudioDesigner(serverId, ReSyncResourceDragPayload.TAB, tab.getId(), fullEditor)) {
                return;
            }
            if (!fullEditor) {
                openStudioDocument(serverId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.TAB, tab.getId()),
                    screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.TAB, tab.getId(), false));
                return;
            }
            client.getHost().setScreen(new TabDesignerScreen(detachedTab(tab), serverId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
        }
    }

    public void handleAdvancementTreeDataReceived(String serverId, JsonObject tree) {
        String treeId = ReSyncResourceType.ADVANCEMENT_TREE.extractId(tree);
        if (treeId == null || treeId.isBlank()) {
            return;
        }
        if (flushPendingStudioEditTarget(serverId, ReSyncResourceDragPayload.ADVANCEMENT_TREE, treeId)) {
            return;
        }
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(ReSyncResourceType.ADVANCEMENT_TREE);
        Object parent = store != null ? store.removePendingParent(serverId, treeId) : null;
        if (parent != null) {
            boolean fullEditor = designerFullEditor(parent);
            parent = designerParent(parent);
            if (fullEditor && openExistingStudioDesigner(serverId, ReSyncResourceDragPayload.ADVANCEMENT_TREE, treeId, true)) {
                return;
            }
            if (!fullEditor) {
                openStudioDocument(serverId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.ADVANCEMENT_TREE, treeId),
                    screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.ADVANCEMENT_TREE, treeId, false));
                return;
            }
            client.getHost().setScreen(new AdvancementDesignerScreen(detachedJson(tree), serverId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
        }
    }

    public void handleDialogDataReceived(String serverId, JsonObject dialog) {
        String dialogId = ReSyncResourceType.DIALOG.extractId(dialog);
        if (dialogId == null || dialogId.isBlank()) {
            return;
        }
        if (flushPendingStudioEditTarget(serverId, ReSyncResourceDragPayload.DIALOG, dialogId)) {
            return;
        }
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(ReSyncResourceType.DIALOG);
        Object parent = store != null ? store.removePendingParent(serverId, dialogId) : null;
        if (parent != null) {
            boolean fullEditor = designerFullEditor(parent);
            parent = designerParent(parent);
            if (openExistingStudioDesigner(serverId, ReSyncResourceDragPayload.DIALOG, dialogId, fullEditor)) {
                return;
            }
            if (!fullEditor) {
                openStudioDocument(serverId, ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.DIALOG, dialogId),
                    screen -> screen.openWorkspaceDesigner(ReSyncResourceDragPayload.DIALOG, dialogId, false));
                return;
            }
            client.getHost().setScreen(new DialogDesignerScreen(detachedJson(dialog), serverId, parent, fullEditor || !(parent instanceof Screen), fullEditor));
        }
    }

    public void handleFocusedJsonResourceDataReceived(String serverId, ReSyncResourceType type, JsonObject resource) {
        String resourceId = type.extractId(resource);
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        if (flushPendingStudioEditTarget(serverId, type.typeId(), resourceId)) {
            return;
        }
        SyncedResourceCache<JsonObject> store = jsonResourceStores.get(type);
        Object parent = store != null ? store.removePendingParent(serverId, resourceId) : null;
        if (parent == null) {
            return;
        }
        boolean fullEditor = designerFullEditor(parent);
        parent = designerParent(parent);
        if (openExistingStudioDesigner(serverId, type.typeId(), resourceId, fullEditor)) {
            return;
        }
        if (!fullEditor) {
            openStudioDocument(serverId, ReSyncProjectMetadata.resourceKey(type.typeId(), resourceId),
                screen -> screen.openWorkspaceDesigner(type.typeId(), resourceId, false));
            return;
        }
        if (AutomationDefinitionDraft.supports(type)) {
            openStudioDocument(serverId, ReSyncProjectMetadata.resourceKey(type.typeId(), resourceId),
                screen -> screen.openDefinition(type.typeId(), resourceId));
            return;
        }
        JsonObject editorResource = detachedJson(resource);
        client.getHost().setScreen(ResourceDesigners.create(null, type.typeId(), resourceId, editorResource, serverId, parent));
    }

    void refreshStudioWorkspace(String serverId) {
        refreshStudioWorkspace(serverId, true);
    }

    void refreshStudioWorkspace(String serverId, boolean rebuildContentBrowser) {
        if (rebuildContentBrowser) {
            rehydrateRetainedCoreGraphSessions(serverId);
        }
        scheduleStudioWorkspaceRefresh(serverId, rebuildContentBrowser, true);
    }

    private void rehydrateRetainedCoreGraphSessions(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ReSyncFlowClient source = connectionManager.getFlowClient(serverId);
        if (source == null || source.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            || source.activeAuthoringPublication().isEmpty()) {
            return;
        }
        ServerConnectionToken connectionToken = captureServerConnectionToken(serverId, source);
        CoreGraphOwnerToken ownerToken = currentCoreGraphOwnerToken(serverId, source);
        if (!isCurrentServerConnection(connectionToken) || !ownsCoreGraphOwnerToken(ownerToken)) {
            return;
        }
        List<CoreGraphSessionKey> retained;
        synchronized (coreGraphEditorSessions) {
            retained = coreGraphEditorSessions.entrySet().stream()
                .filter(entry -> serverId.equals(entry.getKey().serverId()) && entry.getValue() != null
                    && entry.getValue().session() != null && entry.getValue().stale())
                .map(Map.Entry::getKey)
                .sorted((left, right) -> {
                    int type = left.type().typeId().compareTo(right.type().typeId());
                    return type != 0 ? type : left.id().compareTo(right.id());
                }).toList();
        }
        for (CoreGraphSessionKey key : retained) {
            if (!isCurrentServerConnection(connectionToken) || !ownsCoreGraphOwnerToken(ownerToken)) {
                return;
            }
            hydrateCoreGraphProjection(serverId, key.type(), key.id());
            synchronized (serverConnectionGenerationLock) {
                if (!isCurrentServerConnectionLocked(connectionToken)) {
                    return;
                }
                synchronized (coreGraphEditorSessions) {
                    CoreGraphSessionState state = coreGraphEditorSessions.get(key);
                    if (state != null && state.session() != null && state.stale()) {
                        coreGraphEditorSessions.put(key, reconcileCoreGraphSession(key, state, connectionToken));
                    }
                }
            }
        }
    }

    private void refreshStudioWorkspaceState(String serverId) {
        scheduleStudioWorkspaceRefresh(serverId, false, false);
    }

    private void scheduleStudioWorkspaceRefresh(String serverId, boolean rebuildContentBrowser, boolean invalidateCatalog) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        boolean[] schedule = {false};
        if (!runIfCurrentServerConnection(token, () -> {
            synchronized (studioWorkspaceRefreshLock) {
                ServerConnectionToken queuedGeneration = studioWorkspaceRefreshGenerations.get(serverId);
                if (queuedGeneration != null && !sameWorkspaceRefreshGeneration(queuedGeneration, token)) {
                    pendingStudioWorkspaceRefreshes.remove(serverId);
                    scheduledStudioWorkspaceRefreshes.remove(serverId);
                    studioWorkspaceRefreshGenerations.remove(serverId);
                }
                PendingStudioWorkspaceRefresh pending = pendingStudioWorkspaceRefreshes.computeIfAbsent(serverId,
                    ignored -> new PendingStudioWorkspaceRefresh());
                pending.add(rebuildContentBrowser, invalidateCatalog);
                schedule[0] = scheduledStudioWorkspaceRefreshes.add(serverId);
                if (schedule[0]) {
                    studioWorkspaceRefreshGenerations.put(serverId, token);
                }
            }
            if (schedule[0]) {
                ScreenManager.getInstance().execute(() -> runScheduledStudioWorkspaceRefresh(serverId, token));
            }
        })) {
            return;
        }
    }

    private static ReSyncProjectMetadata.ResourceEntry ensureProjectResource(ReSyncProjectMetadata metadata,
                                                                              Map<String, ReSyncProjectMetadata.ResourceEntry> resourceIndex,
                                                                              String type, String id, String displayName,
                                                                              String defaultFolder) {
        String key = ReSyncProjectMetadata.resourceKey(type, id);
        ReSyncProjectMetadata.ResourceEntry resource = resourceIndex.get(key);
        if (resource == null) {
            resource = new ReSyncProjectMetadata.ResourceEntry();
            resource.setType(type);
            resource.setId(id);
            resource.setDisplayName(displayName == null || displayName.isBlank() ? id : displayName);
            resource.setPath(ReSyncProjectMetadata.normalizePath(defaultFolder));
            resource.setSortOrder(metadata.getResources().size());
            metadata.getResources().add(resource);
            resourceIndex.put(key, resource);
            return resource;
        }
        if ((resource.getDisplayName() == null || resource.getDisplayName().isBlank())
            && displayName != null && !displayName.isBlank()) {
            resource.setDisplayName(displayName);
        }
        if (resource.getPath() == null || resource.getPath().isBlank()) {
            resource.setPath(ReSyncProjectMetadata.normalizePath(defaultFolder));
        }
        return resource;
    }

    private void runScheduledStudioWorkspaceRefresh(String serverId, ServerConnectionToken token) {
        if (runIfCurrentServerConnection(token, () -> drainStudioWorkspaceRefresh(serverId, token))) {
            return;
        }
        rescheduleStudioWorkspaceRefresh(serverId, token);
    }

    private void rescheduleStudioWorkspaceRefresh(String serverId, ServerConnectionToken staleToken) {
        ServerConnectionToken replacementToken = null;
        boolean schedule = false;
        synchronized (serverConnectionGenerationLock) {
            synchronized (studioWorkspaceRefreshLock) {
                ServerConnectionToken queuedGeneration = studioWorkspaceRefreshGenerations.get(serverId);
                if (queuedGeneration == null || sameWorkspaceRefreshGeneration(queuedGeneration, staleToken)) {
                    pendingStudioWorkspaceRefreshes.remove(serverId);
                    scheduledStudioWorkspaceRefreshes.remove(serverId);
                    studioWorkspaceRefreshGenerations.remove(serverId);
                    return;
                }
                scheduledStudioWorkspaceRefreshes.remove(serverId);
                scheduledStudioWorkspaceRefreshes.add(serverId);
                replacementToken = queuedGeneration;
                schedule = true;
            }
        }
        if (schedule) {
            ServerConnectionToken token = replacementToken;
            ScreenManager.getInstance().execute(() -> runScheduledStudioWorkspaceRefresh(serverId, token));
        }
    }

    private void drainStudioWorkspaceRefresh(String serverId, ServerConnectionToken token) {
        StudioWorkspaceRefreshSnapshot refresh;
        synchronized (studioWorkspaceRefreshLock) {
            if (!sameWorkspaceRefreshGeneration(studioWorkspaceRefreshGenerations.get(serverId), token)) {
                return;
            }
            PendingStudioWorkspaceRefresh pending = pendingStudioWorkspaceRefreshes.remove(serverId);
            scheduledStudioWorkspaceRefreshes.remove(serverId);
            studioWorkspaceRefreshGenerations.remove(serverId);
            if (pending == null) {
                return;
            }
            refresh = pending.snapshot();
        }
        refreshStudioWorkspaceNow(serverId, refresh);
    }

    private void refreshStudioWorkspaceNow(String serverId, StudioWorkspaceRefreshSnapshot refresh) {
        if (refresh.invalidateProjectCatalog()) {
            invalidateProjectCatalog(serverId);
        }
        refreshOpenStudioWorkspace(serverId, refresh.rebuildContentBrowser());
        flushPendingStudioEditTarget(serverId);
        AdvancementDesignerScreen.refreshCatalogForServer(serverId);
        DialogDesignerScreen.refreshCatalogForServer(serverId);
        FocusedJsonResourceDesignerScreen.refreshCatalogForServer(serverId);
        GuiDesignerScreen.refreshCatalogForServer(serverId);
        FlowEditorScreen.refreshWorldsForServer(serverId);
        traceWorkspaceRefresh(serverId, "studio", "applied",
            refresh.invalidateProjectCatalog() ? "catalog_invalidated" : "catalog_current",
            refresh.rebuildContentBrowser(), refresh.invalidateProjectCatalog(), false, 0);
    }

    void refreshFlowWorkspace(String serverId, boolean rebuildContentBrowser) {
        refreshFlowWorkspace(serverId, null, rebuildContentBrowser);
    }

    void refreshFlowWorkspace(String serverId, String changedFlowId, boolean rebuildContentBrowser) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        if (!hasFlowWorkspaceRefreshTargets(serverId, changedFlowId, rebuildContentBrowser)) {
            return;
        }
        boolean[] schedule = {false};
        if (!runIfCurrentServerConnection(token, () -> {
            synchronized (flowWorkspaceRefreshLock) {
                ServerConnectionToken queuedGeneration = flowWorkspaceRefreshGenerations.get(serverId);
                if (queuedGeneration != null && !sameWorkspaceRefreshGeneration(queuedGeneration, token)) {
                    pendingFlowWorkspaceRefreshes.remove(serverId);
                    scheduledFlowWorkspaceRefreshes.remove(serverId);
                    flowWorkspaceRefreshGenerations.remove(serverId);
                }
                PendingFlowWorkspaceRefresh pending = pendingFlowWorkspaceRefreshes.computeIfAbsent(serverId,
                    ignored -> new PendingFlowWorkspaceRefresh());
                pending.add(changedFlowId, rebuildContentBrowser);
                schedule[0] = scheduledFlowWorkspaceRefreshes.add(serverId);
                if (schedule[0]) {
                    flowWorkspaceRefreshGenerations.put(serverId, token);
                }
            }
            if (schedule[0]) {
                ScreenManager.getInstance().execute(() -> runScheduledFlowWorkspaceRefresh(serverId, token));
            }
        })) {
            return;
        }
    }

    private void runScheduledFlowWorkspaceRefresh(String serverId, ServerConnectionToken token) {
        if (runIfCurrentServerConnection(token, () -> drainFlowWorkspaceRefresh(serverId, token))) {
            return;
        }
        rescheduleFlowWorkspaceRefresh(serverId, token);
    }

    private void rescheduleFlowWorkspaceRefresh(String serverId, ServerConnectionToken staleToken) {
        ServerConnectionToken replacementToken = null;
        boolean schedule = false;
        synchronized (serverConnectionGenerationLock) {
            synchronized (flowWorkspaceRefreshLock) {
                ServerConnectionToken queuedGeneration = flowWorkspaceRefreshGenerations.get(serverId);
                if (queuedGeneration == null || sameWorkspaceRefreshGeneration(queuedGeneration, staleToken)) {
                    pendingFlowWorkspaceRefreshes.remove(serverId);
                    scheduledFlowWorkspaceRefreshes.remove(serverId);
                    flowWorkspaceRefreshGenerations.remove(serverId);
                    return;
                }
                scheduledFlowWorkspaceRefreshes.remove(serverId);
                scheduledFlowWorkspaceRefreshes.add(serverId);
                replacementToken = queuedGeneration;
                schedule = true;
            }
        }
        if (schedule) {
            ServerConnectionToken token = replacementToken;
            ScreenManager.getInstance().execute(() -> runScheduledFlowWorkspaceRefresh(serverId, token));
        }
    }

    private void drainFlowWorkspaceRefresh(String serverId, ServerConnectionToken token) {
        FlowWorkspaceRefreshSnapshot refresh;
        synchronized (flowWorkspaceRefreshLock) {
            if (!sameWorkspaceRefreshGeneration(flowWorkspaceRefreshGenerations.get(serverId), token)) {
                return;
            }
            PendingFlowWorkspaceRefresh pending = pendingFlowWorkspaceRefreshes.remove(serverId);
            scheduledFlowWorkspaceRefreshes.remove(serverId);
            flowWorkspaceRefreshGenerations.remove(serverId);
            if (pending == null) {
                return;
            }
            refresh = pending.snapshot();
        }
        refreshFlowWorkspaceNow(serverId, refresh);
    }

    private static boolean sameWorkspaceRefreshGeneration(ServerConnectionToken left, ServerConnectionToken right) {
        return left != null && right != null && left.generation() == right.generation()
            && left.source() == right.source()
            && (left.serverId() == null ? right.serverId() == null : left.serverId().equals(right.serverId()));
    }

    private void refreshFlowWorkspaceNow(String serverId, FlowWorkspaceRefreshSnapshot refresh) {
        if (refresh.rebuildContentBrowser()) {
            refreshOpenStudioContentBrowser(serverId);
        }
        flushPendingStudioEditTarget(serverId);
        if (refresh.refreshAllFlowBindings() || refresh.flowIds().isEmpty()) {
            refreshFlowBindingsForServer(serverId, null);
            traceWorkspaceRefresh(serverId, "flow", "applied", "all_bindings", refresh.rebuildContentBrowser(),
                false, true, refresh.flowIds().size());
            return;
        }
        for (String flowId : refresh.flowIds()) {
            refreshFlowBindingsForServer(serverId, flowId);
        }
        traceWorkspaceRefresh(serverId, "flow", "applied", "targeted_bindings", refresh.rebuildContentBrowser(),
            false, false, refresh.flowIds().size());
    }

    private void traceWorkspaceRefresh(String serverId, String target, String outcome, String reason,
                                       boolean rebuildContentBrowser, boolean invalidateProjectCatalog,
                                       boolean refreshAllBindings, int targetedBindingCount) {
        ProjectMetadataSnapshot metadata = currentProjectMetadataSnapshot(serverId);
        TypedResourceMembershipSnapshot membership = snapshotTypedResourceMembership(serverId);
        Set<String> metadataKeys = metadata.resources().stream().map(ProjectMetadataSnapshot.Resource::key)
            .collect(Collectors.toSet());
        List<ReSyncProjectMetadata.ResourceEntry> projected = deriveProjectResources(metadata, membership);
        Set<String> projectedKeys = projected.stream().map(resource -> resource.key()).collect(Collectors.toSet());
        int excludedCount = (int) metadataKeys.stream().filter(key -> !projectedKeys.contains(key)).count();
        ReSyncFlowClient flowClient = connectionManager.getFlowClient(serverId);
        FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
        ReSyncFlowClient.traceLifecycle(serverId, "workspace_refresh_" + outcome, "serverId", serverId,
            "resourceKey", target, "operation", "refresh", "requestId", "workspace", "mutationId", null,
            "generation", membership.connectionGeneration(), "authorityEpoch", flowClient != null
                ? flowClient.authorityEpoch() : 0L, "revision", projectMembershipRevisions.getOrDefault(serverId, 0L),
            "rebuildContentBrowser", rebuildContentBrowser, "invalidateProjectCatalog", invalidateProjectCatalog,
            "refreshAllBindings", refreshAllBindings, "targetedBindingCount", targetedBindingCount,
            "includedResourceCount", projected.size(), "excludedResourceCount", excludedCount,
            "typedMemberCount", membership.resources().size(), "completeTypeCount", membership.completeTypes().size(),
            "tombstoneCount", membership.tombstones().size(), "workspaceReady", studioScreen != null
                && studioScreen.isStudioWorkspaceReady(), "reason", reason);
    }

    private void refreshFlowBindingsForServer(String serverId, String flowId) {
        AdvancementDesignerScreen.refreshFlowBindingsForServer(serverId, flowId);
        DialogDesignerScreen.refreshFlowBindingsForServer(serverId, flowId);
        FocusedJsonResourceDesignerScreen.refreshFlowBindingsForServer(serverId, flowId);
        GuiDesignerScreen.refreshFlowBindingsForServer(serverId, flowId);
    }

    private boolean hasFlowWorkspaceRefreshTargets(String serverId, String changedFlowId, boolean rebuildContentBrowser) {
        if (rebuildContentBrowser || FlowEditorScreen.hasOpenStudioScreenForServer(serverId) || studioFullEditorSession.hasPendingTarget(serverId)) {
            return true;
        }
        if (changedFlowId == null || changedFlowId.isBlank()) {
            return AdvancementDesignerScreen.hasOpenScreenForServer(serverId)
                || DialogDesignerScreen.hasOpenScreenForServer(serverId)
                || FocusedJsonResourceDesignerScreen.hasOpenScreenForServer(serverId)
                || GuiDesignerScreen.hasOpenScreenForServer(serverId);
        }
        return AdvancementDesignerScreen.hasFlowBindingForServer(serverId, changedFlowId)
            || DialogDesignerScreen.hasFlowBindingForServer(serverId, changedFlowId)
            || FocusedJsonResourceDesignerScreen.hasFlowBindingForServer(serverId, changedFlowId)
            || GuiDesignerScreen.hasFlowBindingForServer(serverId, changedFlowId);
    }

    private GuiDefinition detachedGui(GuiDefinition gui) {
        return gui != null ? FlowSerializer.deserializeGui(FlowSerializer.serializeGui(gui)) : null;
    }

    private ScoreboardDefinition detachedScoreboard(ScoreboardDefinition scoreboard) {
        return scoreboard != null ? FlowSerializer.deserializeScoreboard(FlowSerializer.serializeScoreboard(scoreboard)) : null;
    }

    private TabDefinition detachedTab(TabDefinition tab) {
        return tab != null ? FlowSerializer.deserializeTab(FlowSerializer.serializeTab(tab)) : null;
    }

    private JsonObject detachedJson(JsonObject json) {
        return json != null ? json.deepCopy() : new JsonObject();
    }

    private FlowGraph detachedGraph(FlowGraph graph) {
        return graph != null ? FlowSerializer.deserialize(FlowSerializer.serialize(graph)) : null;
    }

    private FlowGraph applyPendingCoreActivation(String serverId, ReSyncResourceType type, String id, FlowGraph graph) {
        if (graph == null || serverId == null || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return graph;
        }
        synchronized (resourceActivationLock) {
            PendingActivation pending = pendingActivations.get(new ActivationKey(serverId, type, id));
            if (pending != null) {
                graph.setEnabled(pending.enabled());
            }
        }
        return graph;
    }

    private CustomContentDefinition detachedCustomContent(CustomContentDefinition content) {
        return content != null ? (CustomContentDefinition) ReSyncResourceType.CUSTOM_CONTENT.deserialize(ReSyncResourceType.CUSTOM_CONTENT.serialize(content)) : null;
    }

    private ReSyncProjectMetadata detachedProjectMetadata(ReSyncProjectMetadata metadata) {
        return copyProjectMetadata(metadata);
    }

    private static ReSyncProjectMetadata copyProjectMetadata(ReSyncProjectMetadata source) {
        if (source == null) {
            return null;
        }
        ReSyncProjectMetadata copy = new ReSyncProjectMetadata(source.getServerId());
        copy.setSelectedResourceKey(source.getSelectedResourceKey());
        List<ReSyncProjectMetadata.FolderEntry> folders = new ArrayList<>(source.getFolders().size());
        for (ReSyncProjectMetadata.FolderEntry sourceFolder : source.getFolders()) {
            ReSyncProjectMetadata.FolderEntry folder = new ReSyncProjectMetadata.FolderEntry();
            folder.setPath(sourceFolder.getPath());
            folder.setParentPath(sourceFolder.getParentPath());
            folder.setName(sourceFolder.getName());
            folder.setSortOrder(sourceFolder.getSortOrder());
            folder.setCollapsed(sourceFolder.isCollapsed());
            folders.add(folder);
        }
        copy.setFolders(folders);
        List<ReSyncProjectMetadata.ResourceEntry> resources = new ArrayList<>(source.getResources().size());
        for (ReSyncProjectMetadata.ResourceEntry sourceResource : source.getResources()) {
            ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
            resource.setType(sourceResource.getType());
            resource.setId(sourceResource.getId());
            resource.setDisplayName(sourceResource.getDisplayName());
            resource.setPath(sourceResource.getPath());
            resource.setSortOrder(sourceResource.getSortOrder());
            resources.add(resource);
        }
        copy.setResources(resources);
        List<ReSyncProjectMetadata.InstalledBundleEntry> bundles = new ArrayList<>(source.getInstalledBundles().size());
        for (ReSyncProjectMetadata.InstalledBundleEntry sourceBundle : source.getInstalledBundles()) {
            ReSyncProjectMetadata.InstalledBundleEntry bundle = new ReSyncProjectMetadata.InstalledBundleEntry();
            bundle.setMarketplaceSlug(sourceBundle.getMarketplaceSlug());
            bundle.setListingSlug(sourceBundle.getListingSlug());
            bundle.setTitle(sourceBundle.getTitle());
            bundle.setVersionId(sourceBundle.getVersionId());
            bundle.setVersion(sourceBundle.getVersion());
            bundle.setRootPath(sourceBundle.getRootPath());
            bundle.setIconMediaId(sourceBundle.getIconMediaId());
            bundle.setEnabled(sourceBundle.isEnabled());
            bundle.setResourceKeys(new ArrayList<>(sourceBundle.getResourceKeys()));
            bundles.add(bundle);
        }
        copy.setInstalledBundles(bundles);
        List<ReSyncProjectMetadata.OpenDocumentEntry> documents = new ArrayList<>(source.getOpenDocuments().size());
        for (ReSyncProjectMetadata.OpenDocumentEntry sourceDocument : source.getOpenDocuments()) {
            ReSyncProjectMetadata.OpenDocumentEntry document = new ReSyncProjectMetadata.OpenDocumentEntry();
            document.setType(sourceDocument.getType());
            document.setId(sourceDocument.getId());
            document.setDisplayName(sourceDocument.getDisplayName());
            document.setActive(sourceDocument.isActive());
            documents.add(document);
        }
        copy.setOpenDocuments(documents);
        return copy;
    }

    private static ReSyncProjectMetadata.ResourceEntry copyProjectResource(ReSyncProjectMetadata.ResourceEntry source) {
        if (source == null) {
            return null;
        }
        ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
        resource.setType(source.getType());
        resource.setId(source.getId());
        resource.setDisplayName(source.getDisplayName());
        resource.setPath(source.getPath());
        resource.setSortOrder(source.getSortOrder());
        return resource;
    }

    private static ReSyncProjectMetadata.ResourceEntry copyProjectResource(ProjectMetadataSnapshot.Resource source) {
        if (source == null) {
            return null;
        }
        ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
        resource.setType(source.type());
        resource.setId(source.id());
        resource.setDisplayName(source.displayName());
        resource.setPath(source.path());
        resource.setSortOrder(source.sortOrder());
        return resource;
    }

    private static ProjectResource projectResource(ProjectMetadataSnapshot.Resource source) {
        return source != null ? new ProjectResource(source.type(), source.id(), source.displayName(), source.path(), source.sortOrder()) : null;
    }

    private static ProjectFolder projectFolder(ProjectMetadataSnapshot.Folder source) {
        return source != null ? new ProjectFolder(source.path(), source.parentPath(), source.name(), source.sortOrder(), source.collapsed()) : null;
    }

    private static ReSyncProjectMetadata.FolderEntry copyProjectFolder(ProjectMetadataSnapshot.Folder source) {
        if (source == null) return null;
        ReSyncProjectMetadata.FolderEntry folder = new ReSyncProjectMetadata.FolderEntry();
        folder.setPath(source.path());
        folder.setParentPath(source.parentPath());
        folder.setName(source.name());
        folder.setSortOrder(source.sortOrder());
        folder.setCollapsed(source.collapsed());
        return folder;
    }

    private static ReSyncProjectMetadata.InstalledBundleEntry copyProjectBundle(ProjectMetadataSnapshot.Bundle source) {
        if (source == null) return null;
        ReSyncProjectMetadata.InstalledBundleEntry bundle = new ReSyncProjectMetadata.InstalledBundleEntry();
        bundle.setMarketplaceSlug(source.marketplaceSlug());
        bundle.setListingSlug(source.listingSlug());
        bundle.setTitle(source.title());
        bundle.setVersionId(source.versionId());
        bundle.setVersion(source.version());
        bundle.setRootPath(source.rootPath());
        bundle.setIconMediaId(source.iconMediaId());
        bundle.setEnabled(source.enabled());
        bundle.setResourceKeys(source.resourceKeys());
        return bundle;
    }

    private void refreshOpenStudioWorkspace(String serverId, boolean rebuildContentBrowser) {
        FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
        if (studioScreen != null) {
            studioScreen.refreshStudioWorkspace(rebuildContentBrowser);
        }
    }

    private void refreshOpenStudioContentBrowser(String serverId) {
        FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
        if (studioScreen != null) {
            studioScreen.refreshStudioContentBrowserOnly();
        }
    }

    void refreshStudioWorlds(String serverId) {
        ServerConnectionToken token = captureServerConnectionToken(serverId);
        runIfCurrentServerConnection(token, () -> ScreenManager.getInstance().execute(
            () -> runIfCurrentServerConnection(token, () -> {
                FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
                if (studioScreen != null) {
                    studioScreen.refreshStudioWorkspace();
                }
                FlowEditorScreen.refreshWorldsForServer(serverId);
            })));
    }

    private FlowGraph createDefaultFlow() {
        return createDefaultFlow((ReSyncFlowClient) null, false, FLOW_TEMPLATES.getFirst());
    }

    private FlowGraph createDefaultFlow(String serverId, boolean function, String templateName) {
        return createDefaultFlow(function && serverId != null ? existingFlowClient(serverId) : null, function, templateName);
    }

    static FlowGraph createDefaultFlow(ReSyncFlowClient flowClient, boolean function, String templateName) {
        FlowGraph graph = new FlowGraph();
        graph.setFunction(function);
        if (function) {
            if (flowClient == null) {
                return null;
            }
            if (flowClient.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
                FlowNodeWidget.FunctionBoundaryCatalog catalog = ReSyncTypedInteractionProjection.from(flowClient)
                    .map(FlowNodeWidget::fromTypedProjection)
                    .orElseGet(FlowNodeWidget.FunctionBoundaryCatalog::unavailable);
                FlowNodeWidget.FunctionBoundaryIntent inputBoundary = catalog.intent(FlowNodeWidget.FunctionBoundaryRole.INPUTS);
                FlowNodeWidget.FunctionBoundaryIntent outputBoundary = catalog.intent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS);
                if (!catalog.isTypedProjectionAvailable() || inputBoundary == null || outputBoundary == null) {
                    return null;
                }
                String startId = UUID.randomUUID().toString();
                String endId = UUID.randomUUID().toString();
                graph.getNodes().put(startId, new FlowNode(inputBoundary.nodeReference(), 120, 120, new HashMap<>()));
                graph.getNodes().put(endId, new FlowNode(outputBoundary.nodeReference(), 380, 120, new HashMap<>()));
                graph.getConnections().add(new FlowConnection(startId, inputBoundary.flowPin(), endId, outputBoundary.flowPin()));
                addFunctionParameters(graph.getFunctionInputs(), inputBoundary);
                addFunctionParameters(graph.getFunctionOutputs(), outputBoundary);
                return graph;
            }
            if (flowClient.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY) {
                return null;
            }
            String startId = UUID.randomUUID().toString();
            String endId = UUID.randomUUID().toString();
            graph.getNodes().put(startId, new FlowNode("function_start", 120, 120, new HashMap<>()));
            graph.getNodes().put(endId, new FlowNode("function_end", 380, 120, new HashMap<>()));
            graph.getConnections().add(new FlowConnection(startId, "flow", endId, "flow"));
            return graph;
        }
        if (!function && "command".equalsIgnoreCase(templateName)) {
            graph.getNodes().put(UUID.randomUUID().toString(), new FlowNode("event.command", 120, 120, new HashMap<>()));
        }
        return graph;
    }

    private static void addFunctionParameters(List<FlowGraph.FunctionParameter> parameters,
                                              FlowNodeWidget.FunctionBoundaryIntent boundary) {
        if (parameters == null || boundary == null) {
            return;
        }
        for (FlowNodeWidget.FunctionParameterPin pin : boundary.parameterPins()) {
            if (pin == null || pin.id() == null || pin.id().isBlank() || pin.typeRef() == null) {
                continue;
            }
            String name = pin.name() != null && !pin.name().isBlank() ? pin.name() : pin.id();
            if (name == null || name.isBlank() || parameters.stream().anyMatch(parameter -> parameter != null && pin.id().equals(parameter.getParameterId()))) {
                continue;
            }
            FlowGraph.FunctionParameter parameter = FlowGraph.FunctionParameter.stable(pin.id(), name,
                FlowDataType.fromString(pin.typeRef().getTypeId()));
            parameter.setTypeRef(pin.typeRef());
            parameters.add(parameter);
        }
    }

    private GuiDefinition createDefaultGui(String id) {
        GuiDefinition gui = new GuiDefinition();
        gui.setId(id);
        gui.setTitle("Main Menu");
        gui.setRows(3);
        Visual visual = new Visual("DIAMOND", "<yellow>Main Button</yellow>");
        GuiElement btn = new GuiElement();
        btn.getSlots().add(13);
        btn.setVisual(visual);
        btn.setFlowId("main_flow");
        gui.getElements().add(btn);
        return gui;
    }

    private ScoreboardDefinition createDefaultScoreboard(String id) {
        ScoreboardDefinition scoreboard = new ScoreboardDefinition();
        scoreboard.setId(id);
        scoreboard.setObjectiveId(id);
        scoreboard.setTitle("Server");
        scoreboard.setDisplaySlot("sidebar");
        scoreboard.getLines().add("<gray>Online: <white>%server_online%</white>");
        scoreboard.getLines().add("<gray>Ping: <green>%player_ping%</green>");
        scoreboard.getLines().add("<gray>Rank: <gold>%vault_rank%</gold>");
        return scoreboard;
    }

    private TabDefinition createDefaultTab(String id) {
        TabDefinition tab = new TabDefinition();
        tab.setId(id);
        tab.setHeader("<gold>Server Network");
        tab.setEntryFormat("<gray>•</gray> <white>%player%</white>");
        tab.setFooter("<gray>Online: <green>%server_online%</green>");
        return tab;
    }

    private void ensureCommandStartNode(String serverId, String flowId) {
        FlowGraph graph = getGraph(serverId, ReSyncResourceType.COMMAND, flowId);
        if (graph == null || graph.getNodes() == null || CustomContentGraphAdapter.isContentGraph(graph)) {
            return;
        }
        if (coreGraphUiProjection.authoritative(serverId, ReSyncResourceType.COMMAND, flowId)) {
            return;
        }
        if (ensureCommandStartNodeInMemory(graph)) {
            saveFlow(serverId, graph);
        }
    }

    private boolean ensureCommandStartNodeInMemory(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null || CustomContentGraphAdapter.isContentGraph(graph)) {
            return false;
        }
        boolean changed = false;
        boolean hasCommandNode = false;
        for (FlowNode node : graph.getNodes().values()) {
            if (node == null) {
                continue;
            }
            if (!CommandGraphContract.isAnyStart(node.getType())) {
                continue;
            }
            hasCommandNode = true;
            if (CommandGraphContract.isLegacyStart(node.getType())) {
                node.setType("event.command");
                changed = true;
            }
        }
        if (hasCommandNode) {
            return changed;
        }
        graph.getNodes().put(UUID.randomUUID().toString(), new FlowNode("event.command", 120, 120, new HashMap<>()));
        return true;
    }

    private FlowNode commandStartNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return null;
        }
        return graph.getNodes().values().stream()
            .filter(node -> node != null && CommandGraphContract.isAnyStart(node.getType()))
            .findFirst()
            .orElse(null);
    }

    private String normalizedCommandLabel(String label) {
        String normalized = label != null ? label.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int namespace = normalized.indexOf(':');
        return namespace >= 0 && namespace < normalized.length() - 1 ? normalized.substring(namespace + 1) : normalized;
    }

    private boolean applyCommandContext(FlowGraph graph, String context) {
        if (graph == null || graph.getNodes() == null || context == null || context.isBlank()) {
            return false;
        }
        String command = context.trim();
        List<String> subcommands = new ArrayList<>();
        boolean structured = false;
        if (command.startsWith("{")) {
            try {
                JsonObject value = gson.fromJson(command, JsonObject.class);
                command = value != null && value.has("command") && !value.get("command").isJsonNull() ? value.get("command").getAsString() : "";
                if (value != null && value.has("subcommands") && value.get("subcommands").isJsonArray()) {
                    value.getAsJsonArray("subcommands").forEach(path -> {
                        if (path != null && !path.isJsonNull() && !path.getAsString().isBlank()) {
                            subcommands.add(path.getAsString());
                        }
                    });
                }
                structured = value != null && value.has("structured") && value.get("structured").getAsBoolean();
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if (command.isBlank()) {
            return false;
        }
        for (FlowNode node : graph.getNodes().values()) {
            if (node == null || !CommandGraphContract.isAnyStart(node.getType())) {
                continue;
            }
            if (node.getInputValues() == null) {
                node.setInputValues(new HashMap<>());
            }
            node.getInputValues().put("command", command);
            node.getInputValues().put("subcommands", subcommands);
            node.getInputValues().put("structured", structured);
            graph.setResourceType(ReSyncResourceDragPayload.COMMAND);
            return true;
        }
        return false;
    }

    private void sendTriggerUpdate(String serverId) {
        List<TriggerBinding> bindings;
        synchronized (triggerBindingsLock) {
            bindings = triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>());
        }
        sendTriggerUpdate(serverId, bindings, null);
    }

    private void sendTriggerUpdate(String serverId, List<TriggerBinding> bindings) {
        sendTriggerUpdate(serverId, bindings, null);
    }

    private void sendTriggerUpdate(String serverId, List<TriggerBinding> bindings, List<TriggerBinding> previous) {
        ReSyncFlowClient flowClient = ensureSubscribedFlowClient(serverId);
        List<TriggerBinding> submitted = bindings;
        if (flowClient == null) {
            restoreTriggerBindingsIfCurrent(serverId, submitted, previous, null, -1, 0L);
            return;
        }
        ServerConnectionToken connectionToken = captureConnectedServerConnectionToken(serverId, flowClient);
        int transportGeneration = flowClient.activeTransportGeneration();
        long authorityEpoch = flowClient.resourceRevisionReconciler().authorityEpoch(serverId);
        try {
            flowClient.sendTriggerUpdateAwait(submitted).whenComplete((outcome, error) -> {
                if (outcome != null && outcome.hasAuthoritativeState()) {
                    applyAuthoritativeTriggerBindings(serverId, outcome.bindings(), flowClient, connectionToken,
                        transportGeneration, authorityEpoch, outcome.bindingEpoch(), outcome.bindingHash());
                    return;
                }
                if (previous == null || error == null && outcome != null && outcome.successful()) {
                    return;
                }
                restoreTriggerBindingsIfCurrent(serverId, submitted, previous, connectionToken, transportGeneration,
                    authorityEpoch);
            });
        } catch (RuntimeException exception) {
            restoreTriggerBindingsIfCurrent(serverId, submitted, previous, connectionToken, transportGeneration,
                authorityEpoch);
        }
    }

    private void restoreTriggerBindingsIfCurrent(String serverId, List<TriggerBinding> submitted,
                                                 List<TriggerBinding> previous, ServerConnectionToken connectionToken,
                                                 int transportGeneration, long authorityEpoch) {
        if (serverId == null || submitted == null || previous == null || connectionToken == null
            || transportGeneration < 0 || authorityEpoch < 1L
            || !creationConnectionCurrent(serverId, connectionToken.source(), connectionToken, transportGeneration,
                authorityEpoch)) {
            return;
        }
        synchronized (triggerBindingsLock) {
            List<TriggerBinding> target = triggerBindings.computeIfAbsent(serverId, id -> new ArrayList<>());
            if (!triggerBindingsEqual(target, submitted)) {
                return;
            }
            target.clear();
            target.addAll(copyTriggerBindings(previous));
        }
        invalidateProjectCatalog(serverId);
    }

    private void sendWorldAction(String serverId, Map<String, Object> request) {
        if (serverId == null || serverId.isBlank() || request == null || request.isEmpty()) {
            return;
        }
        withFlowClient(serverId, flowClient -> {
            flowClient.sendWorldAction(request);
            return null;
        });
    }

    private String getOrCreateDefaultFlowId(String serverId) {
        Map<String, FlowGraph> flows = getGraphsForServer(serverId, ReSyncResourceType.FLOW);
        if (!flows.isEmpty()) {
            return flows.keySet().iterator().next();
        }
        if (coreGraphAuthorityEnabled(serverId)) {
            return "main_flow";
        }
        FlowGraph graph = createFlow(serverId);
        return graph != null ? graph.getId() : "main_flow";
    }
}
