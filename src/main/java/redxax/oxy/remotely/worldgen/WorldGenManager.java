package redxax.oxy.remotely.worldgen;

import restudio.rescreen.platform.TaskScheduler;
import java.time.Duration;
import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncProtocolContract;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.flow.ui.FlowEditorScreen;
import redxax.oxy.remotely.flow.ui.FlowGraphDesignerScreen;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborativeView;
import redxax.oxy.remotely.worldgen.data.WorldGenConnection;
import redxax.oxy.remotely.worldgen.data.WorldGenGraph;
import redxax.oxy.remotely.worldgen.data.WorldGenNode;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import redxax.oxy.remotely.worldgen.data.WorldGenSerializer;
import redxax.oxy.remotely.worldgen.data.WorldGenStage;
import redxax.oxy.remotely.worldgen.registry.WorldGenNodeDefinition;
import redxax.oxy.remotely.worldgen.registry.WorldGenNodeRegistry;
import redxax.oxy.remotely.worldgen.ui.WorldGenEditorScreen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.worldgen.contract.WorldGenGenerationMode;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

public class WorldGenManager {
    private static final long SAVE_TIMEOUT_SECONDS = 30L;
    private static final int PROJECT_PREPARATION_QUEUE_CAPACITY = 8;
    private static final int MAX_PENDING_MUTATIONS = PROJECT_PREPARATION_QUEUE_CAPACITY;
    private static final long MAX_PENDING_MUTATION_BYTES = (long) MAX_PENDING_MUTATIONS
        * ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES;
    private static final int MAX_PENDING_DUPLICATES = PROJECT_PREPARATION_QUEUE_CAPACITY;
    private static final long MAX_PENDING_DUPLICATE_BYTES = (long) MAX_PENDING_DUPLICATES
        * ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES;
    private static final int MAX_PENDING_METADATA_RECONCILIATIONS = 16;
    private static final long METADATA_RECONCILIATION_RETRY_MILLIS = 1000L;
    private static final int MAX_DEFERRED_PROJECT_SAVES = PROJECT_PREPARATION_QUEUE_CAPACITY;
    private static final int MAX_DEFERRED_PROJECT_SAVE_ATTEMPTS = 12;
    private static final long DEFERRED_PROJECT_SAVE_RETRY_MILLIS = 500L;
    public static final String WORLD_GEN_PROJECT_SAVE_ACTION = "worldGenProjectSave";
    public static final String WORLD_GEN_PROJECT_DELETE_ACTION = "worldGenProjectDelete";
    private static final WorldGenManager INSTANCE = new WorldGenManager();
    private static final List<String> PROJECT_CATEGORIES = List.of(WorldGenGenerationMode.VANILLA.displayName(), WorldGenGenerationMode.HYBRID.displayName());
    private static final List<String> VANILLA_TEMPLATES = List.of("Survival", "Amplified", "Large Biomes");
    private static final List<String> HYBRID_TEMPLATES = List.of("Continental", "Alpine", "Islands", "Badlands", "Frozen", "Caves");
    private final WorldGenProjectStore projectStore = new WorldGenProjectStore();
    private final WorldGenPreviewController previewController = new WorldGenPreviewController();
    private final Map<String, Object> capabilities = BrowserSafeState.map();
    private final Map<String, RegistryRevision> registryRevisions = BrowserSafeState.map();
    private final Map<String, Object> registryProjectionLocks = BrowserSafeState.map();
    private final Map<String, RegistryProjectionClaim> registryProjectionClaims = new HashMap<>();
    private final Map<String, Long> registryInvalidations = new HashMap<>();
    private long registryInvalidationSequence;
    private final Map<String, PendingWorldGenSave> pendingSaves = BrowserSafeState.map();
    private final Map<String, PendingWorldGenDelete> pendingDeletes = BrowserSafeState.map();
    private final Map<String, DeferredWorldGenSave> deferredSaves = new HashMap<>();
    private final Map<String, PendingWorldGenDuplicate> pendingDuplicates = BrowserSafeState.map();
    private final Map<String, Map<String, PendingWorldGenDuplicate>> pendingDuplicatesBySource = new HashMap<>();
    private final Map<String, PendingWorldGenMetadataReconciliation> pendingMetadataReconciliations = new HashMap<>();
    private final Map<String, PendingMetadataReconciliationReservation> metadataReconciliationReservations = new HashMap<>();
    private final Map<String, CompletedMetadataReconciliation> completedMetadataReconciliations = new LinkedHashMap<>();
    private int pendingDuplicateCount;
    private long pendingDuplicateBytes;
    private final Set<String> silentDuplicateTargets = BrowserSafeState.set();
    private final Map<String, TaskScheduler.ScheduledTask> silentDuplicateTimeouts = BrowserSafeState.map();
    private final Map<String, SilentDuplicateAdmission> silentDuplicateAdmissions = new HashMap<>();
    private final Map<String, String> silentDuplicateTargetBySource = new HashMap<>();
    private final ThreadLocal<FlowManager.ServerConnectionToken> sourceConnectionToken = new ThreadLocal<>();
    private final BrowserWork.Executor mutationTimeoutScheduler = createMutationTimeoutScheduler();
    private final BrowserWork.Executor projectPreparationExecutor = BrowserWork.executor();

    public static WorldGenManager getInstance() {
        return INSTANCE;
    }

    private static BrowserWork.Executor createMutationTimeoutScheduler() {
        BrowserWork.Executor scheduler = BrowserWork.executor();
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private void traceWorldGenLifecycle(String serverId, String stage, String operation, String projectId,
                                        String requestId, String mutationId, long generation, long authorityEpoch,
                                        long revision, String outcome, String reason, int inputCount, int outputCount,
                                        String uiTarget, long startedAt) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedProjectId = cleanProjectId(projectId);
        String resourceKey = normalizedProjectId.isBlank() ? "worldgen:catalog"
            : "worldgen:project:" + normalizedProjectId;
        ReSyncFlowClient.traceLifecycle(normalizedServerId, stage, "serverId", normalizedServerId, "resourceKey",
            resourceKey, "operation", operation == null ? "unknown" : operation, "requestId",
            requestId == null ? "" : requestId, "correlationId", "", "traceId", "", "mutationId",
            mutationId == null ? "" : mutationId, "generation", generation, "authorityEpoch", authorityEpoch,
            "revision", revision, "outcome", outcome == null ? "unknown" : outcome, "reason",
            reason == null ? "" : reason, "inputCount", inputCount, "outputCount", outputCount, "uiTarget",
            uiTarget == null ? "none" : uiTarget, "elapsedMs", elapsedMillis(startedAt));
    }

    private static long elapsedMillis(long startedAt) {
        return BrowserSafeState.nanosToMillis(Math.max(0L, System.nanoTime() - startedAt));
    }

    private record WorldGenUiToken(FlowManager.ServerConnectionToken connection, String serverId, String projectId,
                                   long authorityEpoch, WorldGenProjectStore.ProjectState projectState) {
    }

    private FlowManager.ServerConnectionToken captureConnectionToken(String serverId) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return null;
        }
        String normalizedServerId = connectionServerId(serverId);
        FlowManager.ServerConnectionToken sourceToken = sourceConnectionToken.get();
        if (sourceToken != null && sameServer(normalizedServerId, sourceToken.serverId())) {
            return sourceToken;
        }
        return manager.currentServerConnectionToken(normalizedServerId);
    }

    public void withSourceConnectionToken(FlowManager.ServerConnectionToken token, Runnable action) {
        if (action == null) {
            return;
        }
        FlowManager.ServerConnectionToken previous = sourceConnectionToken.get();
        if (token == null) {
            sourceConnectionToken.remove();
        } else {
            sourceConnectionToken.set(token);
        }
        try {
            action.run();
        } finally {
            if (previous == null) {
                sourceConnectionToken.remove();
            } else {
                sourceConnectionToken.set(previous);
            }
        }
    }

    private WorldGenUiToken captureWorldGenUiToken(String serverId, String projectId, long expectedEpoch) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedProjectId = cleanProjectId(projectId);
        return new WorldGenUiToken(captureConnectionToken(normalizedServerId), normalizedServerId,
            normalizedProjectId, expectedEpoch, projectStore.projectState(normalizedServerId, normalizedProjectId));
    }

    private boolean isCurrentWorldGenConnection(FlowManager.ServerConnectionToken token, long expectedEpoch) {
        if (token == null || expectedEpoch < 1L) {
            return false;
        }
        FlowManager manager = FlowManager.getInstance();
        return manager != null && manager.isCurrentServerConnection(token)
            && authorityEpoch(token.serverId()) == expectedEpoch;
    }

    private boolean isCurrentWorldGenUi(WorldGenUiToken token) {
        if (token == null || !isCurrentWorldGenConnection(token.connection(), token.authorityEpoch())) {
            return false;
        }
        if (token.projectId() == null || token.projectId().isBlank()) {
            return true;
        }
        WorldGenProjectStore.ProjectState current = projectStore.projectState(token.serverId(), token.projectId());
        WorldGenProjectStore.ProjectState expected = token.projectState();
        return expected != null && current.stateRevision() == expected.stateRevision()
            && current.authorityRevision() == expected.authorityRevision() && current.present() == expected.present()
            && current.canonicalHash().equals(expected.canonicalHash());
    }

    private void executeWorldGenUi(WorldGenUiToken token, Runnable action) {
        if (token == null || action == null) {
            return;
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return;
        }
        executeAfterManagerRelease(() -> ScreenManager.getInstance().execute(() ->
            manager.runIfCurrentServerConnection(token.connection(), () -> {
                if (isCurrentWorldGenUi(token)) {
                    action.run();
                }
            })));
    }

    private void executeWorldGenMutationUi(Runnable action) {
        if (action != null) {
            executeAfterManagerRelease(() -> ScreenManager.getInstance().execute(action));
        }
    }

    private void executeAfterManagerRelease(Runnable action) {
        if (action == null) {
            return;
        }
        mutationTimeoutScheduler.execute(() -> {
            awaitManagerRelease();
            action.run();
        });
    }

    private synchronized void awaitManagerRelease() {
    }

    private boolean runWorldGenConnectionNow(WorldGenUiToken token, Runnable action) {
        if (token == null || action == null) {
            return false;
        }
        FlowManager manager = FlowManager.getInstance();
        return manager != null && manager.runIfCurrentServerConnection(token.connection(), action);
    }

    private boolean runWorldGenNow(WorldGenUiToken token, Runnable action) {
        if (token == null || action == null) {
            return false;
        }
        FlowManager manager = FlowManager.getInstance();
        return manager != null && manager.runIfCurrentServerConnection(token.connection(), () -> {
            if (isCurrentWorldGenUi(token)) {
                action.run();
            }
        });
    }

    private boolean refreshProjectAfterMutation(String serverId, String projectId, long expectedEpoch) {
        WorldGenUiToken token = captureWorldGenUiToken(serverId, projectId, expectedEpoch);
        return runWorldGenNow(token, () -> {
            if (projectId != null && !projectId.isBlank()) {
                requestProject(serverId, projectId);
            }
            requestProjectList(serverId);
        });
    }

    private boolean refreshProjectListAfterMutation(String serverId, String projectId, long expectedEpoch) {
        WorldGenUiToken token = captureWorldGenUiToken(serverId, projectId, expectedEpoch);
        return runWorldGenNow(token, () -> requestProjectList(serverId));
    }

    public static String registryServerId(String serverId) {
        return WorldGenCatalogProjection.scopedServerId(serverId);
    }

    public static String connectionServerId(String serverId) {
        return WorldGenCatalogProjection.normalizeBaseServerId(serverId);
    }

    public static boolean isWorldGenDefinition(NodeDefinition definition) {
        return WorldGenCatalogProjection.isWorldGenDefinition(definition);
    }

    public boolean canMutateWorldGen(String serverId) {
        ReSyncFlowClient client = serverId == null || serverId.isBlank() ? null : flowClient(serverId);
        return client != null && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            && liveCatalog(serverId).authority() == WorldGenCatalogProjection.Authority.TYPED_PUBLICATION;
    }

    public static boolean isWorldGenServerId(String serverId) {
        return serverId != null && serverId.strip().endsWith(":worldgen");
    }

    public void clearCache(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        String normalizedServerId = WorldGenCatalogProjection.normalizeBaseServerId(serverId);
        boolean reconnectCandidate = hasWorldGenReconnectCandidate(normalizedServerId);
        Object projectionLock = registryProjectionLocks.computeIfAbsent(normalizedServerId, ignored -> new Object());
        RegistryInvalidation invalidation;
        boolean cleared;
        synchronized (projectionLock) {
            synchronized (this) {
                previewController.clearServer(normalizedServerId);
                cancelPendingSaves(normalizedServerId, "World Generation Cache Cleared; Refresh Required");
                cancelPendingDeletes(normalizedServerId, "World Generation Cache Cleared; Refresh Required");
                cancelPendingDuplicates(normalizedServerId, "World Generation Cache Cleared; Refresh Required");
                cancelSilentDuplicateTimeouts(normalizedServerId);
                if (!reconnectCandidate) {
                    finishDeferredWorldGenSaves(normalizedServerId, "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED");
                }
                projectStore.clearServer(normalizedServerId, reconnectCandidate);
                invalidation = stageRegistryInvalidationLocked(normalizedServerId);
            }
            cleared = clearRegistryInvalidationState(invalidation);
        }
        if (cleared) {
            publishRegistryInvalidation(normalizedServerId);
        }
    }

    public synchronized void shutdown(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        String normalizedServerId = WorldGenCatalogProjection.normalizeBaseServerId(serverId);
        drainPendingMutations(normalizedServerId, "ReSync Disconnected");
        finishDeferredWorldGenSaves(normalizedServerId, "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED");
        cancelPendingDuplicates(normalizedServerId, "ReSync Disconnected");
        previewController.clearServer(normalizedServerId);
        cancelSilentDuplicateTimeouts(normalizedServerId);
    }

    public synchronized void shutdown() {
        Set<String> servers = new HashSet<>();
        pendingSaves.values().forEach(save -> {
            if (save != null) {
                servers.add(save.uiToken.serverId());
            }
        });
        pendingDeletes.values().forEach(deletion -> {
            if (deletion != null) {
                servers.add(deletion.uiToken.serverId());
            }
        });
        pendingDuplicates.values().forEach(duplicate -> {
            if (duplicate != null) {
                servers.add(duplicate.serverId);
            }
        });
        deferredSaves.values().forEach(deferred -> {
            if (deferred != null) {
                servers.add(deferred.submission.serverId());
            }
        });
        silentDuplicateAdmissions.values().forEach(admission -> {
            if (admission != null) {
                servers.add(admission.serverId);
            }
        });
        for (String server : servers) {
            drainPendingMutations(server, "ReSync Disconnected");
            finishDeferredWorldGenSaves(server, "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED");
        }
        previewController.clearAll();
        cancelSilentDuplicateTimeouts(null);
        cancelPendingDuplicates(null, "ReSync Disconnected");
    }

    public synchronized EpochDecision acceptAuthorityEpoch(String serverId, long authorityEpoch) {
        String normalizedServerId = connectionServerId(serverId);
        long currentEpoch = projectStore.authorityEpoch(normalizedServerId);
        if (authorityEpoch < 1L) {
            return new EpochDecision(EpochOutcome.INVALID, currentEpoch, authorityEpoch);
        }
        if (authorityEpoch < currentEpoch) {
            return new EpochDecision(EpochOutcome.STALE, currentEpoch, authorityEpoch);
        }
        if (authorityEpoch == currentEpoch) {
            return new EpochDecision(EpochOutcome.CURRENT, currentEpoch, authorityEpoch);
        }
        return adoptAuthorityEpochFromCurrentConnection(normalizedServerId, authorityEpoch, currentEpoch);
    }

    private EpochDecision adoptAuthorityEpochFromCurrentConnection(String serverId, long authorityEpoch,
                                                                   long currentEpoch) {
        FlowManager manager = FlowManager.getInstance();
        FlowManager.ServerConnectionToken connection = captureConnectionToken(serverId);
        ReSyncFlowClient client = connection == null ? null : connection.source();
        if (manager == null || connection == null || client == null || !manager.isCurrentServerConnection(connection)
            || client.resourceRevisionReconciler().authorityEpoch(serverId) != authorityEpoch) {
            return new EpochDecision(EpochOutcome.STALE, currentEpoch, authorityEpoch);
        }
        return acceptAuthorityEpoch(serverId, authorityEpoch, new AuthorityEpochTransition() {
            @Override
            public boolean prepare(long nextEpoch) {
                return isCurrentAuthorityConnection(serverId, connection, nextEpoch);
            }

            @Override
            public boolean commit(long nextEpoch) {
                return isCurrentAuthorityConnection(serverId, connection, nextEpoch);
            }

            @Override
            public void rollback(long previousEpoch, long nextEpoch) {
            }
        });
    }

    private boolean isCurrentAuthorityConnection(String serverId, FlowManager.ServerConnectionToken connection,
                                                  long authorityEpoch) {
        FlowManager manager = FlowManager.getInstance();
        return manager != null && connection != null && connection.source() != null
            && manager.isCurrentServerConnection(connection)
            && connection.source().resourceRevisionReconciler().authorityEpoch(serverId) == authorityEpoch;
    }

    public synchronized EpochDecision acceptAuthorityEpoch(String serverId, long authorityEpoch,
                                                            AuthorityEpochTransition authorityTransition) {
        String normalizedServerId = connectionServerId(serverId);
        long currentEpoch = projectStore.authorityEpoch(normalizedServerId);
        if (authorityEpoch < 1L) {
            return new EpochDecision(EpochOutcome.INVALID, currentEpoch, authorityEpoch);
        }
        if (authorityEpoch < currentEpoch) {
            return new EpochDecision(EpochOutcome.STALE, currentEpoch, authorityEpoch);
        }
        if (authorityEpoch == currentEpoch) {
            return new EpochDecision(EpochOutcome.CURRENT, currentEpoch, authorityEpoch);
        }
        if (authorityTransition != null) {
            try {
                if (!authorityTransition.prepare(authorityEpoch)) {
                    return new EpochDecision(EpochOutcome.STALE, currentEpoch, authorityEpoch);
                }
            } catch (RuntimeException | Error exception) {
                return new EpochDecision(EpochOutcome.STALE, currentEpoch, authorityEpoch);
            }
        }
        WorldGenProjectStore.EpochTransition storeTransition = projectStore.prepareAuthorityEpoch(normalizedServerId, authorityEpoch);
        if (storeTransition == null) {
            return new EpochDecision(EpochOutcome.STALE, currentEpoch, authorityEpoch);
        }
        Map<PendingWorldGenMutation, PendingAuthorityState> pendingStates = capturePendingAuthorityStates(normalizedServerId);
        try {
            if (!projectStore.commitAuthorityEpoch(storeTransition)
                || authorityTransition != null && !authorityTransition.commit(authorityEpoch)) {
                throw new IllegalStateException("World Generation Authority Epoch Was Not Committed");
            }
            if (currentEpoch == 0L) {
                establishPendingAuthorityEpoch(normalizedServerId, authorityEpoch);
            } else {
                markPendingAuthorityTransition(normalizedServerId, authorityEpoch);
            }
            invalidateAfterAuthorityTransition(normalizedServerId);
            return new EpochDecision(EpochOutcome.ADVANCED, currentEpoch, authorityEpoch);
        } catch (RuntimeException | Error exception) {
            try {
                projectStore.rollbackAuthorityEpoch(storeTransition);
            } catch (RuntimeException | Error ignored) {
            }
            try {
                restorePendingAuthorityStates(pendingStates);
            } catch (RuntimeException | Error ignored) {
            }
            if (authorityTransition != null) {
                try {
                    authorityTransition.rollback(storeTransition.previousEpoch(), authorityEpoch);
                } catch (RuntimeException | Error ignored) {
                }
            }
            return new EpochDecision(EpochOutcome.STALE, currentEpoch, authorityEpoch);
        }
    }

    private void invalidateAfterAuthorityTransition(String serverId) {
        previewController.clearServer(serverId);
        RegistryInvalidation invalidation = stageRegistryInvalidationLocked(serverId);
        scheduleRegistryInvalidation(invalidation, () -> requestProjectList(serverId));
    }

    public synchronized long authorityEpoch(String serverId) {
        return projectStore.authorityEpoch(serverId);
    }

    public synchronized boolean acceptsWorldGenMutation(String serverId, WorldGenMutationIdentity identity) {
        return pendingWorldGenMutation(serverId, identity) != null;
    }

    public synchronized boolean acceptsWorldGenMutation(String serverId, String action, String projectId,
                                                        String resourceId, String requestId, String mutationId,
                                                        String operationId) {
        return acceptsWorldGenMutation(serverId, new WorldGenMutationIdentity(action, cleanProjectId(projectId),
            cleanProjectId(resourceId), requestId, mutationId, operationId));
    }

    public synchronized List<WorldGenMutationIdentity> pendingWorldGenMutations(String serverId) {
        String prefix = connectionServerId(serverId) + ":";
        List<WorldGenMutationIdentity> identities = new ArrayList<>();
        pendingSaves.forEach((key, save) -> {
            if (key.startsWith(prefix) && !save.finished) {
                identities.add(save.identity());
            }
        });
        pendingDeletes.forEach((key, deletion) -> {
            if (key.startsWith(prefix) && !deletion.finished) {
                identities.add(deletion.identity());
            }
        });
        return List.copyOf(identities);
    }

    public synchronized int drainPendingMutations(String serverId, String message) {
        if (serverId == null || serverId.isBlank()) {
            return 0;
        }
        String normalizedServerId = connectionServerId(serverId);
        String terminalMessage = message == null || message.isBlank() ? "ReSync Disconnected" : message;
        cancelSilentDuplicateTimeouts(normalizedServerId);
        int drained = cancelPendingDuplicates(normalizedServerId, "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED");
        for (WorldGenMutationIdentity identity : pendingWorldGenMutations(normalizedServerId)) {
            if (!validMutationIdentity(identity)) {
                continue;
            }
            String key = saveKey(normalizedServerId, identity.projectId());
            if (WORLD_GEN_PROJECT_SAVE_ACTION.equals(identity.action())) {
                PendingWorldGenSave save = pendingSaves.get(key);
                if (save == null || !save.matches(identity) || !pendingSaves.remove(key, save)) {
                    continue;
                }
                save.finished = true;
                save.cancelTimeout();
                try {
                    projectStore.setDraftProject(normalizedServerId, save.draftTransfer);
                } catch (RuntimeException | Error ignored) {
                }
                try {
                    save.settleSubmission(false, "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED");
                } catch (RuntimeException | Error ignored) {
                }
                try {
                    finishSaveFailure(save, terminalMessage);
                } catch (RuntimeException | Error ignored) {
                }
                drained++;
                continue;
            }
            PendingWorldGenDelete deletion = pendingDeletes.get(key);
            if (deletion == null || !deletion.matches(identity) || !pendingDeletes.remove(key, deletion)) {
                continue;
            }
            deletion.finished = true;
            deletion.cancelTimeout();
            try {
                deletion.settleSubmission(ProjectSubmissionState.NOT_SUBMITTED, 0L,
                    "WORLDGEN_PROJECT_DELETE_CONNECTION_CHANGED");
            } catch (RuntimeException | Error ignored) {
            }
            try {
                finishDeleteFailure(deletion, terminalMessage);
            } catch (RuntimeException | Error ignored) {
            }
            drained++;
        }
        return drained;
    }

    private synchronized void cancelSilentDuplicateTimeouts(String serverId) {
        String normalizedServerId = serverId == null || serverId.isBlank()
            ? "" : connectionServerId(serverId);
        for (Map.Entry<String, SilentDuplicateAdmission> entry : new ArrayList<>(silentDuplicateAdmissions.entrySet())) {
            SilentDuplicateAdmission admission = entry.getValue();
            if (admission != null && (normalizedServerId.isBlank() || normalizedServerId.equals(admission.serverId))) {
                removeSilentDuplicate(entry.getKey(), true);
            }
        }
    }

    private synchronized SilentDuplicateAdmission consumeSilentDuplicateTarget(String key) {
        SilentDuplicateAdmission admission = silentDuplicateAdmissions.get(key);
        return admission != null && removeSilentDuplicate(key, true, false) ? admission : null;
    }

    private synchronized void timeoutSilentDuplicate(SilentDuplicateAdmission admission) {
        if (admission == null) {
            return;
        }
        String targetKey = saveKey(admission.serverId, admission.targetProjectId);
        if (silentDuplicateAdmissions.get(targetKey) != admission
            || !removeSilentDuplicate(targetKey, true)) {
            return;
        }
        if (admission.notifyOnSave) {
            WorldGenUiToken token = captureWorldGenUiToken(admission.serverId, admission.targetProjectId,
                authorityEpoch(admission.serverId));
            executeWorldGenUi(token, () -> new Notification("World Generation Failed", "Project Copy Timed Out",
                Notification.Type.ERROR));
        }
    }

    private boolean removeSilentDuplicate(String key, boolean clearStore) {
        return removeSilentDuplicate(key, clearStore, true);
    }

    private boolean removeSilentDuplicate(String key, boolean clearStore, boolean releaseMetadata) {
        SilentDuplicateAdmission admission = silentDuplicateAdmissions.remove(key);
        if (admission == null) {
            return false;
        }
        silentDuplicateTargets.remove(key);
        silentDuplicateTargetBySource.remove(admission.sourceKey(), key);
        TaskScheduler.ScheduledTask timeout = silentDuplicateTimeouts.remove(key);
        if (timeout != null) {
            timeout.cancel();
        }
        if (clearStore) {
            try {
                projectStore.removePendingDuplicateId(admission.serverId, admission.sourceProjectId);
            } catch (RuntimeException | Error ignored) {
            }
        }
        if (releaseMetadata) {
            try {
                releaseMetadataReconciliation(admission.submission.operationId());
            } catch (RuntimeException | Error ignored) {
            }
        }
        return true;
    }

    public synchronized boolean failWorldGenMutation(String serverId, WorldGenMutationIdentity identity, String message) {
        if (!validMutationIdentity(identity)) {
            return false;
        }
        return failProjectMutationFromIdentity(serverId, identity.action(), identity.projectId(), identity.requestId(),
            identity.mutationId(), identity.operationId(), message);
    }

    public synchronized boolean hasPendingWorldGenMutation(String serverId, WorldGenMutationIdentity identity) {
        if (!validMutationIdentity(identity)) {
            return false;
        }
        return pendingWorldGenMutation(serverId, identity) != null;
    }

    public synchronized boolean acceptWorldGenAuthorityTransition(String serverId, long nextEpoch,
                                                                   WorldGenMutationIdentity identity, String message,
                                                                   LongPredicate authorityTransition) {
        return acceptWorldGenAuthorityTransition(serverId, nextEpoch, identity, message,
            new AuthorityEpochTransition() {
                @Override
                public boolean prepare(long epoch) {
                    return authorityTransition != null;
                }

                @Override
                public boolean commit(long epoch) {
                    return authorityTransition.test(epoch);
                }

                @Override
                public void rollback(long previousEpoch, long epoch) {
                }
            });
    }

    public synchronized boolean acceptWorldGenAuthorityTransition(String serverId, long nextEpoch,
                                                                   WorldGenMutationIdentity identity, String message,
                                                                   AuthorityEpochTransition authorityTransition) {
        if (!validMutationIdentity(identity) || nextEpoch <= authorityEpoch(serverId)) {
            return false;
        }
        PendingWorldGenMutation pending = pendingWorldGenMutation(serverId, identity);
        if (pending == null) {
            return false;
        }
        boolean prepared;
        try {
            prepared = authorityTransition != null && authorityTransition.prepare(nextEpoch);
        } catch (RuntimeException | Error exception) {
            prepared = false;
        }
        if (!prepared) {
            return false;
        }
        WorldGenProjectStore.EpochTransition storeTransition = projectStore.prepareAuthorityEpoch(serverId, nextEpoch);
        if (storeTransition == null) {
            return false;
        }
        Map<PendingWorldGenMutation, PendingAuthorityState> pendingStates = capturePendingAuthorityStates(serverId);
        PendingAuthorityState pendingState = pending.authorityState();
        if (!pending.markAuthorityTransition(nextEpoch)) {
            return false;
        }
        boolean settled = false;
        try {
            if (!projectStore.commitAuthorityEpoch(storeTransition)
                || !authorityTransition.commit(nextEpoch)
                || !settleAuthorityTransition(serverId, identity, message, false)) {
                throw new IllegalStateException("World Generation Authority Transition Was Not Committed");
            }
            markPendingAuthorityTransition(serverId, nextEpoch);
            settled = true;
        } catch (RuntimeException | Error exception) {
            try {
                projectStore.rollbackAuthorityEpoch(storeTransition);
            } catch (RuntimeException | Error ignored) {
            }
            try {
                restorePendingAuthorityStates(pendingStates);
            } catch (RuntimeException | Error ignored) {
            }
            try {
                restorePendingAuthorityTransition(serverId, identity, pending, pendingState);
            } catch (RuntimeException | Error ignored) {
            }
            try {
                authorityTransition.rollback(storeTransition.previousEpoch(), nextEpoch);
            } catch (RuntimeException | Error ignored) {
            }
            return false;
        }
        if (!settled) {
            return false;
        }
        finishAuthorityTransitionNotification(pending, identity, message);
        refreshAfterAuthorityTransition(serverId, identity);
        return true;
    }

    public interface AuthorityEpochTransition {
        boolean prepare(long nextEpoch);

        boolean commit(long nextEpoch);

        void rollback(long previousEpoch, long nextEpoch);
    }

    private Map<PendingWorldGenMutation, PendingAuthorityState> capturePendingAuthorityStates(String serverId) {
        String prefix = connectionServerId(serverId) + ":";
        Map<PendingWorldGenMutation, PendingAuthorityState> states = new IdentityHashMap<>();
        pendingSaves.forEach((key, save) -> {
            if (key.startsWith(prefix)) {
                states.put(save, save.authorityState());
            }
        });
        pendingDeletes.forEach((key, deletion) -> {
            if (key.startsWith(prefix)) {
                states.put(deletion, deletion.authorityState());
            }
        });
        return states;
    }

    private void restorePendingAuthorityStates(Map<PendingWorldGenMutation, PendingAuthorityState> states) {
        if (states == null) {
            return;
        }
        states.forEach((pending, state) -> pending.restoreAuthorityState(state));
    }

    private void restorePendingAuthorityTransition(String serverId, WorldGenMutationIdentity identity,
                                                   PendingWorldGenMutation pending, PendingAuthorityState pendingState) {
        if (pending == null || identity == null) {
            return;
        }
        pending.restoreAuthorityState(pendingState);
        if (WORLD_GEN_PROJECT_DELETE_ACTION.equals(identity.action())) {
            if (pending instanceof PendingWorldGenDelete deletion) {
                pendingDeletes.put(saveKey(serverId, identity.projectId()), deletion);
            }
        } else {
            if (pending instanceof PendingWorldGenSave save) {
                pendingSaves.put(saveKey(serverId, identity.projectId()), save);
            }
        }
    }

    private void finishAuthorityTransitionNotification(PendingWorldGenMutation pending,
                                                        WorldGenMutationIdentity identity, String message) {
        String text = message == null || message.isBlank() ? "Committed On An Older Authority; Refresh Required" : message;
        try {
            if (pending instanceof PendingWorldGenSave save) {
                save.settleSubmission(false, "WORLDGEN_PROJECT_SAVE_AUTHORITY_CHANGED");
                executeWorldGenMutationUi(() -> save.finish("World Generation Requires Refresh", text, Notification.Type.INFO));
            } else if (pending instanceof PendingWorldGenDelete deletion) {
                executeWorldGenMutationUi(() -> {
                    try {
                        deletion.finish("World Generation Requires Refresh", text, Notification.Type.INFO);
                    } catch (RuntimeException notificationException) {
                    }
                });
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void refreshAfterAuthorityTransition(String serverId, WorldGenMutationIdentity identity) {
        String normalizedServerId = connectionServerId(serverId);
        RegistryInvalidation invalidation = stageRegistryInvalidationLocked(normalizedServerId);
        scheduleRegistryInvalidation(invalidation, () -> {
            requestProject(serverId, identity.projectId());
            requestProjectList(serverId);
        });
    }

    private RegistryInvalidation stageRegistryInvalidationLocked(String serverId) {
        String normalizedServerId = connectionServerId(serverId);
        capabilities.remove(normalizedServerId);
        registryRevisions.remove(normalizedServerId);
        registryProjectionClaims.remove(normalizedServerId);
        long generation = registryInvalidationSequence == Long.MAX_VALUE ? 1L : registryInvalidationSequence + 1L;
        registryInvalidationSequence = generation;
        registryInvalidations.put(normalizedServerId, generation);
        return new RegistryInvalidation(normalizedServerId, generation);
    }

    private void scheduleRegistryInvalidation(RegistryInvalidation invalidation, Runnable completion) {
        mutationTimeoutScheduler.execute(() -> {
            awaitManagerRelease();
            Object projectionLock = registryProjectionLocks.computeIfAbsent(invalidation.serverId(), ignored -> new Object());
            boolean cleared;
            synchronized (projectionLock) {
                cleared = clearRegistryInvalidationState(invalidation);
            }
            if (cleared) {
                publishRegistryInvalidation(invalidation.serverId());
            }
            if (completion != null) {
                try {
                    completion.run();
                } catch (RuntimeException | Error ignored) {
                }
            }
        });
    }

    private boolean clearRegistryInvalidationState(RegistryInvalidation invalidation) {
        synchronized (this) {
            if (invalidation == null
                || registryInvalidations.getOrDefault(invalidation.serverId(), 0L) != invalidation.generation()
                || registryRevisions.containsKey(invalidation.serverId())
                || registryProjectionClaims.containsKey(invalidation.serverId())) {
                return false;
            }
        }
        WorldGenNodeRegistry.getInstance().replaceDefinitions(invalidation.serverId(), List.of(), false);
        NodeRegistry registry = NodeRegistry.getInstance();
        if (registry != null) {
            registry.clearServer(registryServerId(invalidation.serverId()), false);
        }
        return true;
    }

    private void publishRegistryInvalidation(String serverId) {
        NodeRegistry registry = NodeRegistry.getInstance();
        if (registry != null) {
            try {
                registry.publish(registryServerId(serverId));
            } catch (RuntimeException | Error ignored) {
            }
        }
        try {
            WorldGenNodeRegistry.getInstance().publish(serverId);
        } catch (RuntimeException | Error ignored) {
        }
    }

    private PendingWorldGenMutation pendingWorldGenMutation(String serverId, WorldGenMutationIdentity identity) {
        if (!validMutationIdentity(identity)) {
            return null;
        }
        String key = saveKey(serverId, identity.projectId());
        if (WORLD_GEN_PROJECT_SAVE_ACTION.equals(identity.action())) {
            PendingWorldGenSave save = pendingSaves.get(key);
            return save != null && !save.finished && save.matches(identity) ? save : null;
        }
        PendingWorldGenDelete deletion = pendingDeletes.get(key);
        return deletion != null && !deletion.finished && deletion.matches(identity) ? deletion : null;
    }

    private boolean validMutationIdentity(WorldGenMutationIdentity identity) {
        return identity != null
            && (WORLD_GEN_PROJECT_SAVE_ACTION.equals(identity.action()) || WORLD_GEN_PROJECT_DELETE_ACTION.equals(identity.action()))
            && !identity.projectId().isBlank()
            && identity.projectId().equals(identity.resourceId())
            && hasIdentity(identity.projectId(), identity.requestId(), identity.mutationId(), identity.operationId());
    }

    public record WorldGenMutationIdentity(String action, String projectId, String resourceId, String requestId,
                                           String mutationId, String operationId) {
        public WorldGenMutationIdentity {
            action = canonicalWorldGenAction(action);
            projectId = projectId == null ? "" : projectId;
            resourceId = resourceId == null ? "" : resourceId;
            requestId = requestId == null ? "" : requestId;
            mutationId = mutationId == null ? "" : mutationId;
            operationId = operationId == null ? "" : operationId;
        }
    }

    public static final class PreparedEditorProjectSave {
        private final WorldGenProject project;
        private final FlowGraph activeGraph;
        private final String serializedContent;
        private final JsonObject workspaceDocument;
        private final long editorGeneration;
        private final long mutationVersion;
        private final long workspaceGeneration;
        private final WorldGenUiToken sourceToken;
        private final String canonicalHash;

        private PreparedEditorProjectSave(WorldGenProject project, FlowGraph activeGraph, String serializedContent,
                                          JsonObject workspaceDocument, long editorGeneration,
                                          long mutationVersion, long workspaceGeneration,
                                          WorldGenUiToken sourceToken, String canonicalHash) {
            this.project = Objects.requireNonNull(project, "World Generation project is required");
            this.activeGraph = Objects.requireNonNull(activeGraph, "World Generation active graph is required");
            this.serializedContent = Objects.requireNonNullElse(serializedContent, "");
            this.workspaceDocument = Objects.requireNonNull(workspaceDocument, "World Generation workspace document is required");
            this.sourceToken = Objects.requireNonNull(sourceToken, "World Generation source token is required");
            this.canonicalHash = Objects.requireNonNullElse(canonicalHash, "");
            this.editorGeneration = editorGeneration;
            this.mutationVersion = mutationVersion;
            this.workspaceGeneration = workspaceGeneration;
            if (this.serializedContent.isBlank() || this.canonicalHash.isBlank() || editorGeneration < 1L
                || mutationVersion < 0L || workspaceGeneration < 0L
                || !sourceToken.projectId().equals(project.getId())) {
                throw new IllegalArgumentException("Prepared World Generation save is incomplete");
            }
        }

        public WorldGenProject project() {
            return project;
        }

        public FlowGraph activeGraph() {
            return activeGraph;
        }

        public String serializedContent() {
            return serializedContent;
        }

        public JsonObject workspaceDocument() {
            return workspaceDocument;
        }

        public long editorGeneration() {
            return editorGeneration;
        }

        public long mutationVersion() {
            return mutationVersion;
        }

        public long workspaceGeneration() {
            return workspaceGeneration;
        }
    }

    public static final class ProjectSaveAdmission {
        private final WorldGenUiToken sourceToken;

        private ProjectSaveAdmission(WorldGenUiToken sourceToken) {
            this.sourceToken = Objects.requireNonNull(sourceToken, "World Generation source token is required");
        }

        public String serverId() {
            return sourceToken.serverId();
        }

        public String projectId() {
            return sourceToken.projectId();
        }
    }

    public record EditorProjectPreparation(PreparedEditorProjectSave prepared, WorldGenProject editableProject,
                                           String code) {
        public EditorProjectPreparation {
            code = Objects.requireNonNullElse(code, "");
        }

        public boolean ready() {
            return prepared != null && code.isBlank();
        }
    }

    public record EditorCollaborationPreparation(PreparedEditorProjectSave prepared,
                                                 List<WorkspacePatch<JsonElement>> patches, String code) {
        public EditorCollaborationPreparation {
            patches = List.copyOf(patches == null ? List.of() : patches);
            code = Objects.requireNonNullElse(code, "");
        }

        public boolean ready() {
            return prepared != null && code.isBlank();
        }
    }

    public record EditorProjectSaveSettlement(PreparedEditorProjectSave prepared, boolean submitted, String code) {
        public EditorProjectSaveSettlement {
            code = Objects.requireNonNullElse(code, "");
        }
    }

    public record ProjectSaveSubmission(String operationId, String serverId, String projectId) {
        public ProjectSaveSubmission {
            operationId = Objects.requireNonNullElse(operationId, "");
            serverId = Objects.requireNonNullElse(serverId, "");
            projectId = Objects.requireNonNullElse(projectId, "");
            if (operationId.isBlank() || serverId.isBlank() || projectId.isBlank()) {
                throw new IllegalArgumentException("World Generation save submission identity is incomplete");
            }
        }
    }

    public enum ProjectSubmissionState {
        NOT_SUBMITTED,
        SUBMITTED,
        COMMITTED
    }

    public record ProjectSaveSubmissionSettlement(ProjectSaveSubmission submission, WorldGenProject project,
                                                  ProjectSubmissionState state, String code,
                                                  FlowManager.ServerConnectionToken connection, long authorityEpoch) {
        public ProjectSaveSubmissionSettlement(ProjectSaveSubmission submission, WorldGenProject project,
                                               ProjectSubmissionState state, String code) {
            this(submission, project, state, code, null, 0L);
        }

        public ProjectSaveSubmissionSettlement(ProjectSaveSubmission submission, WorldGenProject project,
                                               boolean submitted, String code) {
            this(submission, project, submitted ? ProjectSubmissionState.SUBMITTED : ProjectSubmissionState.NOT_SUBMITTED,
                code);
        }

        public ProjectSaveSubmissionSettlement {
            submission = Objects.requireNonNull(submission, "World Generation save submission is required");
            state = Objects.requireNonNullElse(state, ProjectSubmissionState.NOT_SUBMITTED);
            code = Objects.requireNonNullElse(code, "");
            if (submitted() && project == null) {
                throw new IllegalArgumentException("Submitted World Generation project is required");
            }
        }

        public boolean submitted() {
            return state != ProjectSubmissionState.NOT_SUBMITTED;
        }

        public boolean committed() {
            return state == ProjectSubmissionState.COMMITTED;
        }
    }

    public record ProjectDeleteSubmission(String operationId, String serverId, String projectId) {
        public ProjectDeleteSubmission {
            operationId = Objects.requireNonNullElse(operationId, "");
            serverId = Objects.requireNonNullElse(serverId, "");
            projectId = Objects.requireNonNullElse(projectId, "");
            if (operationId.isBlank() || serverId.isBlank() || projectId.isBlank()) {
                throw new IllegalArgumentException("World Generation delete submission identity is incomplete");
            }
        }
    }

    public record ProjectDeleteSubmissionSettlement(ProjectDeleteSubmission submission, ProjectSubmissionState state,
                                                    long authoritativeRevision, String code,
                                                    FlowManager.ServerConnectionToken connection, long authorityEpoch) {
        public ProjectDeleteSubmissionSettlement(ProjectDeleteSubmission submission, ProjectSubmissionState state,
                                                 long authoritativeRevision, String code) {
            this(submission, state, authoritativeRevision, code, null, 0L);
        }

        public ProjectDeleteSubmissionSettlement {
            submission = Objects.requireNonNull(submission, "World Generation delete submission is required");
            state = Objects.requireNonNullElse(state, ProjectSubmissionState.NOT_SUBMITTED);
            code = Objects.requireNonNullElse(code, "");
        }

        public boolean submitted() {
            return state != ProjectSubmissionState.NOT_SUBMITTED;
        }

        public boolean committed() {
            return state == ProjectSubmissionState.COMMITTED;
        }
    }

    public record ProjectReadSnapshot(String serverId, String projectId, long revision, long authorityEpoch,
                                      String canonicalHash, String serializedContent,
                                      FlowManager.ServerConnectionToken connection) {
        public ProjectReadSnapshot(String serverId, String projectId, long revision, long authorityEpoch,
                                   String canonicalHash, String serializedContent) {
            this(serverId, projectId, revision, authorityEpoch, canonicalHash, serializedContent, null);
        }

        public ProjectReadSnapshot {
            serverId = Objects.requireNonNullElse(serverId, "");
            projectId = Objects.requireNonNullElse(projectId, "");
            canonicalHash = Objects.requireNonNullElse(canonicalHash, "");
            serializedContent = Objects.requireNonNullElse(serializedContent, "");
        }
    }

    public record ProjectListReadSnapshot(String serverId, long revision, long authorityEpoch,
                                          List<String> projectIds, FlowManager.ServerConnectionToken connection) {
        public ProjectListReadSnapshot {
            serverId = Objects.requireNonNullElse(serverId, "");
            projectIds = List.copyOf(projectIds == null ? List.of() : projectIds);
        }

        public boolean contains(String projectId) {
            return projectId != null && projectIds.contains(projectId);
        }
    }

    public record ProjectMetadataReconciliationResult(String operationId, String serverId, String projectId,
                                                      boolean add, boolean committed, boolean currentAtFinish,
                                                      String code, FlowManager.ServerConnectionToken connection,
                                                      long authorityEpoch) {
        public ProjectMetadataReconciliationResult {
            operationId = Objects.requireNonNullElse(operationId, "");
            serverId = Objects.requireNonNullElse(serverId, "");
            projectId = Objects.requireNonNullElse(projectId, "");
            code = Objects.requireNonNullElse(code, "");
        }

        public boolean successful() {
            return committed && currentAtFinish && code.isBlank();
        }
    }

    public record WorldGenMetadataIntent(String displayName, String path, int sortOrder) {
        public WorldGenMetadataIntent {
            displayName = Objects.requireNonNullElse(displayName, "");
            path = ReSyncProjectMetadata.normalizePath(path);
        }
    }

    public WorldGenGraph getOrCreateGraph(String serverId) {
        return getOrCreateProject(serverId).getTerrainGraph();
    }

    public WorldGenProject getOrCreateProject(String serverId) {
        return projectStore.getOrCreateProject(serverId, this::createDefaultProject);
    }

    public List<String> getProjectTemplates() {
        return HYBRID_TEMPLATES;
    }

    public List<String> getProjectCategories() {
        return PROJECT_CATEGORIES;
    }

    public List<String> getProjectTemplates(String category) {
        return WorldGenGenerationMode.resolve(category) == WorldGenGenerationMode.VANILLA ? VANILLA_TEMPLATES : HYBRID_TEMPLATES;
    }

    public WorldGenProject createProjectTemplate(String templateName, String projectId) {
        return createProjectTemplate(WorldGenGenerationMode.HYBRID.displayName(), templateName, projectId);
    }

    public WorldGenProject createProjectTemplate(String category, String templateName, String projectId) {
        WorldGenGenerationMode mode = WorldGenGenerationMode.resolve(category);
        List<String> templates = getProjectTemplates(category);
        String safeTemplateName = templateName == null || templateName.isBlank() ? templates.getFirst() : templateName;
        WorldGenProject project = mode == WorldGenGenerationMode.VANILLA ? createVanillaProject() : createHybridProject();
        applyProjectTemplate(project, safeTemplateName);
        if (projectId != null && !projectId.isBlank()) {
            project.setId(projectId.trim());
        }
        return project;
    }

    public WorldGenProject copyProject(WorldGenProject project) {
        if (project == null) {
            return createProjectTemplate(HYBRID_TEMPLATES.getFirst(), null);
        }
        return projectStore.copyProject(project, () -> createProjectTemplate(HYBRID_TEMPLATES.getFirst(), null));
    }

    public WorldGenProject getCachedProject(String serverId, String projectId) {
        long startedAt = System.nanoTime();
        WorldGenProject project = projectStore.cachedProject(serverId, projectId);
        traceWorldGenLifecycle(serverId, "worldgen_cache_lookup", "read_project", projectId, "", "", -1L,
            authorityEpoch(serverId), project == null ? -1L : projectStore.projectRevision(serverId, projectId),
            project == null ? "miss" : "hit", project == null ? "project_cache_miss" : "project_cache_hit", 0,
            project == null ? 0 : 1, "none", startedAt);
        return project;
    }

    public ProjectReadSnapshot snapshotProject(String serverId, String projectId) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedProjectId = cleanProjectId(projectId);
        if (normalizedServerId.isBlank() || normalizedProjectId.isBlank()) {
            return null;
        }
        WorldGenProjectStore.ProjectState state = projectStore.projectState(normalizedServerId, normalizedProjectId);
        long revision = projectStore.projectRevision(normalizedServerId, normalizedProjectId);
        long epoch = authorityEpoch(normalizedServerId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(normalizedServerId);
        if (!state.present() || revision < 1L || epoch < 1L || state.canonicalContent().isBlank()
            || !isCurrentWorldGenConnection(connection, epoch)) {
            return null;
        }
        return new ProjectReadSnapshot(normalizedServerId, normalizedProjectId, revision, epoch,
            state.canonicalHash(), state.canonicalContent(), connection);
    }

    public boolean isCurrentProjectSnapshot(ProjectReadSnapshot snapshot) {
        if (snapshot == null || snapshot.serverId().isBlank() || snapshot.projectId().isBlank()
            || snapshot.revision() < 1L || snapshot.authorityEpoch() < 1L || snapshot.canonicalHash().isBlank()
            || snapshot.serializedContent().isBlank()) {
            return false;
        }
        FlowManager manager = FlowManager.getInstance();
        WorldGenProjectStore.ProjectState state = projectStore.projectState(snapshot.serverId(), snapshot.projectId());
        return manager != null && manager.isCurrentServerConnection(snapshot.connection())
            && authorityEpoch(snapshot.serverId()) == snapshot.authorityEpoch()
            && projectStore.projectRevision(snapshot.serverId(), snapshot.projectId()) == snapshot.revision()
            && state.present() && state.canonicalHash().equals(snapshot.canonicalHash())
            && state.canonicalContent().equals(snapshot.serializedContent());
    }

    public ProjectListReadSnapshot snapshotProjectList(String serverId) {
        String normalizedServerId = connectionServerId(serverId);
        long revision = projectStore.projectListRevision(normalizedServerId);
        long epoch = authorityEpoch(normalizedServerId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(normalizedServerId);
        if (normalizedServerId.isBlank() || revision < 1L || epoch < 1L || !projectStore.hasProjectList(normalizedServerId)
            || !isCurrentWorldGenConnection(connection, epoch)) {
            return null;
        }
        return new ProjectListReadSnapshot(normalizedServerId, revision, epoch,
            projectStore.getProjectIds(normalizedServerId), connection);
    }

    public boolean isCurrentProjectListSnapshot(ProjectListReadSnapshot snapshot) {
        if (snapshot == null || snapshot.serverId().isBlank() || snapshot.revision() < 1L
            || snapshot.authorityEpoch() < 1L || snapshot.connection() == null) {
            return false;
        }
        FlowManager manager = FlowManager.getInstance();
        return manager != null && manager.isCurrentServerConnection(snapshot.connection())
            && authorityEpoch(snapshot.serverId()) == snapshot.authorityEpoch()
            && projectStore.projectListRevision(snapshot.serverId()) == snapshot.revision()
            && projectStore.hasProjectList(snapshot.serverId())
            && projectStore.getProjectIds(snapshot.serverId()).equals(snapshot.projectIds());
    }

    public boolean sameProjectContent(WorldGenProject left, WorldGenProject right) {
        return projectStore.sameProjectContent(left, right);
    }

    public boolean knownSameProjectContent(WorldGenProject left, WorldGenProject right) {
        return projectStore.knownSameProjectContent(left, right);
    }

    public ProjectSaveAdmission captureProjectSaveAdmission(String serverId, String projectId) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedProjectId = cleanProjectId(projectId);
        long epoch = authorityEpoch(normalizedServerId);
        WorldGenUiToken token = captureWorldGenUiToken(normalizedServerId, normalizedProjectId, epoch);
        return normalizedProjectId.isBlank() || !isCurrentWorldGenUi(token) ? null : new ProjectSaveAdmission(token);
    }

    private boolean isCurrentProjectSaveAdmission(ProjectSaveAdmission admission) {
        return admission != null && isCurrentWorldGenUi(admission.sourceToken);
    }

    public boolean isCurrentPreparedEditorProject(PreparedEditorProjectSave prepared) {
        return prepared != null && isCurrentWorldGenUi(prepared.sourceToken)
            && prepared.sourceToken.projectId().equals(cleanProjectId(prepared.project().getId()));
    }

    public void invalidateProjectContent(WorldGenProject project) {
        projectStore.invalidateProjectContent(project);
    }

    public void retainDraftProject(String serverId, WorldGenProject project) {
        if (serverId == null || serverId.isBlank() || project == null || project.getId() == null || project.getId().isBlank()) {
            return;
        }
        if (!canMutateWorldGen(serverId)) {
            return;
        }
        long currentEpoch = authorityEpoch(serverId);
        WorldGenUiToken token = captureWorldGenUiToken(serverId, project.getId(), currentEpoch);
        if (!token.projectState().present()) {
            return;
        }
        runWorldGenNow(token, () -> projectStore.setDraftProject(token.serverId(), project));
    }

    public FlowGraph getOrCreateEditorGraph(String serverId, WorldGenStage stage) {
        ensureLocalDefinitions(serverId);
        return toFlowGraph(serverId, getOrCreateProject(serverId).graph(stage));
    }

    public FlowGraph getOrCreateEditorGraph(String serverId) {
        ensureLocalDefinitions(serverId);
        return toFlowGraph(serverId, getOrCreateGraph(serverId));
    }

    public boolean prepareFrozenEditorProject(String serverId, WorldGenProject project, FlowGraph graph,
                                              WorldGenStage stage, long editorGeneration, long mutationVersion,
                                              long workspaceGeneration,
                                              Consumer<EditorProjectPreparation> completion) {
        if (serverId == null || serverId.isBlank() || project == null || graph == null || stage == null
            || editorGeneration < 1L || mutationVersion < 0L || workspaceGeneration < 0L || completion == null) {
            return false;
        }
        ProjectSaveAdmission admission = captureProjectSaveAdmission(serverId, project.getId());
        if (admission == null) {
            return false;
        }
        try {
            projectPreparationExecutor.execute(() -> {
                EditorProjectPreparation result;
                try {
                    if (!isCurrentProjectSaveAdmission(admission)) {
                        throw new IllegalStateException("World Generation source expired");
                    }
                    PreparedEditorProjectSave prepared = materializeFrozenEditorProject(admission.sourceToken, project,
                        graph, stage, editorGeneration, mutationVersion, workspaceGeneration);
                    if (!isCurrentPreparedEditorProject(prepared)) {
                        throw new IllegalStateException("World Generation source expired");
                    }
                    result = new EditorProjectPreparation(prepared, null, "");
                } catch (RuntimeException | Error exception) {
                    result = new EditorProjectPreparation(null, null, "WORLDGEN_PROJECT_PREPARATION_FAILED");
                }
                EditorProjectPreparation completed = result;
                ScreenManager.getInstance().execute(() -> completion.accept(completed));
            });
            return true;
        } catch (RuntimeException | Error exception) {
            return false;
        }
    }

    public boolean admitPreparedEditorProjectSave(String serverId, PreparedEditorProjectSave prepared,
                                                  boolean notify, LongPredicate generationCurrent,
                                                  Consumer<EditorProjectSaveSettlement> completion) {
        if (serverId == null || serverId.isBlank() || prepared == null || generationCurrent == null
            || completion == null
            || !connectionServerId(serverId).equals(prepared.sourceToken.serverId())
            || !isCurrentPreparedEditorProject(prepared)) {
            return false;
        }
        try {
            projectPreparationExecutor.execute(() -> {
                boolean submitted = false;
                String code = "WORLDGEN_PROJECT_SAVE_EXPIRED";
                ProjectSubmissionCompletion metadataCompletion = null;
                String metadataOperationId = "";
                boolean metadataReserved = false;
                try {
                    if (generationCurrent.test(prepared.editorGeneration()) && isCurrentPreparedEditorProject(prepared)) {
                        WorldGenProjectStore.ProjectTransfer transfer = projectStore.transferPreparedProject(
                            prepared.project(), prepared.serializedContent(), prepared.workspaceDocument(), prepared.canonicalHash);
                        if (transfer == null) {
                            code = "WORLDGEN_PROJECT_SAVE_PAYLOAD_INVALID";
                        } else if (generationCurrent.test(prepared.editorGeneration()) && isCurrentPreparedEditorProject(prepared)) {
                            WorldGenProjectStore.ProjectState targetState = prepared.sourceToken.projectState();
                            if (targetState != null && !targetState.present()) {
                                ProjectSaveSubmission metadataSubmission = new ProjectSaveSubmission(
                                    UUID.randomUUID().toString(), connectionServerId(serverId),
                                    cleanProjectId(prepared.project().getId()));
                                metadataOperationId = metadataSubmission.operationId();
                                WorldGenMetadataIntent metadataIntent = new WorldGenMetadataIntent(
                                    metadataSubmission.projectId(), "", -1);
                                if (!reserveMetadataReconciliation(metadataSubmission.operationId(),
                                    metadataSubmission.serverId(), metadataSubmission.projectId(), true, metadataIntent)) {
                                    code = "WORLDGEN_PROJECT_SAVE_BUSY";
                                } else {
                                    metadataReserved = true;
                                    metadataCompletion = new ProjectSubmissionCompletion(metadataSubmission,
                                        transfer.project(), ignored -> {
                                        }, notify, prepared.sourceToken);
                                    submitted = saveWorldGen(serverId, transfer, notify, prepared.sourceToken,
                                        metadataCompletion);
                                    code = submitted ? "" : "WORLDGEN_PROJECT_SAVE_NOT_SUBMITTED";
                                }
                            } else {
                                submitted = saveWorldGen(serverId, transfer, notify, prepared.sourceToken);
                                code = submitted ? "" : "WORLDGEN_PROJECT_SAVE_NOT_SUBMITTED";
                            }
                        }
                    }
                } catch (RuntimeException | Error exception) {
                    code = "WORLDGEN_PROJECT_SAVE_FAILED";
                }
                if (!submitted && metadataCompletion != null) {
                    try {
                        metadataCompletion.settle(false, code);
                    } catch (RuntimeException | Error exception) {
                        releaseMetadataReconciliation(metadataOperationId);
                    }
                } else if (!submitted && metadataReserved) {
                    releaseMetadataReconciliation(metadataOperationId);
                }
                EditorProjectSaveSettlement settlement = new EditorProjectSaveSettlement(prepared, submitted, code);
                ScreenManager.getInstance().execute(() -> {
                    boolean currentConnection = isCurrentWorldGenConnection(prepared.sourceToken.connection(),
                        prepared.sourceToken.authorityEpoch());
                    completion.accept(settlement.submitted() && !currentConnection
                        ? new EditorProjectSaveSettlement(prepared, false, "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED")
                        : settlement);
                });
            });
            return true;
        } catch (IllegalStateException exception) {
            EditorProjectSaveSettlement settlement = new EditorProjectSaveSettlement(prepared, false,
                "WORLDGEN_PROJECT_SAVE_BUSY");
            ScreenManager.getInstance().execute(() -> completion.accept(settlement));
            return true;
        }
    }

    public boolean prepareEditorProjectCopy(PreparedEditorProjectSave prepared, String projectId,
                                            long editorGeneration, long workspaceGeneration,
                                            Consumer<EditorProjectPreparation> completion) {
        return prepareEditorProjectCopy(prepared, projectId, WorldGenStage.TERRAIN, editorGeneration,
            workspaceGeneration, completion);
    }

    public boolean prepareEditorProjectCopy(PreparedEditorProjectSave prepared, String projectId,
                                            WorldGenStage activeStage, long editorGeneration, long workspaceGeneration,
                                            Consumer<EditorProjectPreparation> completion) {
        String normalizedProjectId = cleanProjectId(projectId);
        if (prepared == null || normalizedProjectId.isBlank() || activeStage == null || editorGeneration < 1L || workspaceGeneration < 0L
            || completion == null || !isCurrentPreparedEditorProject(prepared)) {
            return false;
        }
        ProjectSaveAdmission targetAdmission = captureProjectSaveAdmission(prepared.sourceToken.serverId(), normalizedProjectId);
        if (targetAdmission == null) {
            return false;
        }
        try {
            projectPreparationExecutor.execute(() -> {
                EditorProjectPreparation result;
                try {
                    if (!isCurrentPreparedEditorProject(prepared) || !isCurrentProjectSaveAdmission(targetAdmission)) {
                        throw new IllegalStateException("World Generation project copy expired");
                    }
                    WorldGenProject project = WorldGenSerializer.deserializeProject(prepared.serializedContent());
                    if (project == null) {
                        throw new IllegalArgumentException("World Generation project copy is invalid");
                    }
                    project.setId(normalizedProjectId);
                    String serialized = WorldGenSerializer.serializeProject(project);
                    JsonElement document = JsonParser.parseString(serialized);
                    if (!document.isJsonObject()) {
                        throw new IllegalArgumentException("World Generation project copy is invalid");
                    }
                    WorldGenProject editableProject = WorldGenSerializer.deserializeProject(serialized);
                    if (editableProject == null) {
                        throw new IllegalArgumentException("World Generation project copy is invalid");
                    }
                    String canonicalHash = projectStore.preparedProjectHash(project, serialized, document.getAsJsonObject());
                    if (canonicalHash.isBlank() || !isCurrentProjectSaveAdmission(targetAdmission)) {
                        throw new IllegalArgumentException("World Generation project copy is invalid");
                    }
                    ensureLocalDefinitions(prepared.sourceToken.serverId());
                    result = new EditorProjectPreparation(new PreparedEditorProjectSave(project,
                        toFlowGraph(prepared.sourceToken.serverId(), project.graph(activeStage)), serialized,
                        document.getAsJsonObject(), editorGeneration, prepared.mutationVersion(), workspaceGeneration,
                        targetAdmission.sourceToken, canonicalHash), editableProject, "");
                } catch (RuntimeException | Error exception) {
                    result = new EditorProjectPreparation(null, null, "WORLDGEN_PROJECT_COPY_FAILED");
                }
                EditorProjectPreparation completed = result;
                ScreenManager.getInstance().execute(() -> completion.accept(completed));
            });
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    public boolean prepareEditorProjectView(String serverId, WorldGenProject source, WorldGenStage activeStage,
                                            long editorGeneration, long mutationVersion, long workspaceGeneration,
                                            Consumer<EditorProjectPreparation> completion) {
        String normalizedServerId = connectionServerId(serverId);
        String sourceProjectId = cleanProjectId(source == null ? "" : source.getId());
        if (normalizedServerId.isBlank() || activeStage == null || editorGeneration < 1L || mutationVersion < 0L
            || workspaceGeneration < 0L || completion == null || source != null && sourceProjectId.isBlank()) {
            return false;
        }
        long admittedEpoch = authorityEpoch(normalizedServerId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(normalizedServerId);
        if (!isCurrentWorldGenConnection(connection, admittedEpoch)) {
            return false;
        }
        WorldGenUiToken admission = sourceProjectId.isBlank()
            ? null : captureWorldGenUiToken(normalizedServerId, sourceProjectId, admittedEpoch);
        if (admission != null && !isCurrentWorldGenUi(admission)) {
            return false;
        }
        return enqueueEditorPreparation(() -> {
            if (!isCurrentWorldGenConnection(connection, admittedEpoch)) {
                throw new IllegalStateException("World Generation editor source expired");
            }
            WorldGenProject sourceProject = source != null ? source : getOrCreateProject(normalizedServerId);
            String projectId = cleanProjectId(sourceProject == null ? "" : sourceProject.getId());
            if (projectId.isBlank() || !sourceProjectId.isBlank() && !sourceProjectId.equals(projectId)) {
                throw new IllegalStateException("World Generation editor project identity changed");
            }
            WorldGenUiToken token = admission != null ? admission
                : captureWorldGenUiToken(normalizedServerId, projectId, admittedEpoch);
            if (!isCurrentWorldGenUi(token)) {
                throw new IllegalStateException("World Generation editor source expired");
            }
            PreparedEditorProjectSave prepared = materializeEditorProjectView(token, sourceProject, null, null,
                activeStage, editorGeneration, mutationVersion, workspaceGeneration);
            if (!isCurrentPreparedEditorProject(prepared)) {
                throw new IllegalStateException("World Generation editor source expired");
            }
            return new EditorProjectPreparation(prepared, null, "");
        }, completion);
    }

    public boolean prepareEditorDocument(String serverId, JsonObject document, String expectedProjectId,
                                         WorldGenStage activeStage, long editorGeneration, long mutationVersion,
                                         long workspaceGeneration, Consumer<EditorProjectPreparation> completion) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedProjectId = cleanProjectId(expectedProjectId);
        if (normalizedServerId.isBlank() || document == null || normalizedProjectId.isBlank() || activeStage == null
            || editorGeneration < 1L || mutationVersion < 0L || workspaceGeneration < 0L || completion == null) {
            return false;
        }
        long admittedEpoch = authorityEpoch(normalizedServerId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(normalizedServerId);
        WorldGenUiToken admission = captureWorldGenUiToken(normalizedServerId, normalizedProjectId, admittedEpoch);
        if (!isCurrentWorldGenConnection(connection, admittedEpoch) || !isCurrentWorldGenUi(admission)) {
            return false;
        }
        return enqueueEditorPreparation(() -> {
            if (!isCurrentWorldGenConnection(connection, admittedEpoch) || !isCurrentWorldGenUi(admission)) {
                throw new IllegalStateException("World Generation collaboration source expired");
            }
            WorldGenProject source = WorldGenSerializer.deserializeProject(document.toString());
            if (source == null || !normalizedProjectId.equals(cleanProjectId(source.getId()))) {
                throw new IllegalArgumentException("World Generation collaboration project identity is invalid");
            }
            PreparedEditorProjectSave prepared = materializeEditorProjectView(admission, source, null, null,
                activeStage, editorGeneration, mutationVersion, workspaceGeneration);
            if (!isCurrentPreparedEditorProject(prepared)) {
                throw new IllegalStateException("World Generation collaboration source expired");
            }
            return new EditorProjectPreparation(prepared, null, "");
        }, completion);
    }

    public boolean prepareEditorCollaborationDocument(String serverId, JsonObject document,
                                                      List<WorkspacePatch<JsonElement>> patches,
                                                      String expectedProjectId, WorldGenStage activeStage,
                                                      long editorGeneration, long mutationVersion,
                                                      long workspaceGeneration,
                                                      Consumer<EditorCollaborationPreparation> completion) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedProjectId = cleanProjectId(expectedProjectId);
        if (normalizedServerId.isBlank() || document == null || normalizedProjectId.isBlank() || activeStage == null
            || editorGeneration < 1L || mutationVersion < 0L || workspaceGeneration < 0L || completion == null) {
            return false;
        }
        long admittedEpoch = authorityEpoch(normalizedServerId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(normalizedServerId);
        WorldGenUiToken admission = captureWorldGenUiToken(normalizedServerId, normalizedProjectId, admittedEpoch);
        if (!isCurrentWorldGenConnection(connection, admittedEpoch) || !isCurrentWorldGenUi(admission)) {
            return false;
        }
        List<WorkspacePatch<JsonElement>> inboundPatches = patches == null ? List.of() : patches;
        return enqueueEditorCollaborationPreparation(() -> {
            if (!isCurrentWorldGenConnection(connection, admittedEpoch) || !isCurrentWorldGenUi(admission)) {
                throw new IllegalStateException("World Generation collaboration source expired");
            }
            JsonObject ownedDocument = document.deepCopy();
            List<WorkspacePatch<JsonElement>> ownedPatches = copyWorkspacePatches(inboundPatches);
            WorldGenProject source = WorldGenSerializer.deserializeProject(ownedDocument.toString());
            if (source == null || !normalizedProjectId.equals(cleanProjectId(source.getId()))) {
                throw new IllegalArgumentException("World Generation collaboration project identity is invalid");
            }
            PreparedEditorProjectSave prepared = materializeEditorProjectView(admission, source, null, null,
                activeStage, editorGeneration, mutationVersion, workspaceGeneration);
            if (!isCurrentPreparedEditorProject(prepared)) {
                throw new IllegalStateException("World Generation collaboration source expired");
            }
            return new EditorCollaborationPreparation(prepared, ownedPatches, "");
        }, completion);
    }

    public boolean prepareCollaborationDocumentSnapshot(String serverId, ReSyncCollaborativeView owner,
                                                        JsonObject document, String projectId, long lifecycle,
                                                        long editVersion, long workspaceGeneration,
                                                        long mutationVersion,
                                                        Consumer<ReSyncCollaborativeView.CollaborationDocumentSnapshot> completion) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedProjectId = cleanProjectId(projectId);
        if (normalizedServerId.isBlank() || owner == null || document == null || normalizedProjectId.isBlank()
            || lifecycle < 1L || editVersion < 0L || workspaceGeneration < 0L || mutationVersion < 0L
            || completion == null) {
            return false;
        }
        long admittedEpoch = authorityEpoch(normalizedServerId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(normalizedServerId);
        WorldGenUiToken admission = captureWorldGenUiToken(normalizedServerId, normalizedProjectId, admittedEpoch);
        if (!isCurrentWorldGenConnection(connection, admittedEpoch) || !isCurrentWorldGenUi(admission)) {
            return false;
        }
        try {
            projectPreparationExecutor.execute(() -> {
                ReSyncCollaborativeView.CollaborationDocumentSnapshot result;
                try {
                    if (!isCurrentWorldGenConnection(connection, admittedEpoch) || !isCurrentWorldGenUi(admission)) {
                        throw new IllegalStateException("World Generation collaboration snapshot expired");
                    }
                    result = new ReSyncCollaborativeView.CollaborationDocumentSnapshot(owner, lifecycle, editVersion,
                        document, null);
                } catch (RuntimeException | Error exception) {
                    RuntimeException failure = exception instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException("World Generation collaboration snapshot failed", exception);
                    result = new ReSyncCollaborativeView.CollaborationDocumentSnapshot(owner, lifecycle, editVersion,
                        null, failure);
                }
                ReSyncCollaborativeView.CollaborationDocumentSnapshot completed = result;
                try {
                    ScreenManager.getInstance().execute(() -> completion.accept(completed));
                } catch (RuntimeException | Error ignored) {
                }
            });
            return true;
        } catch (RuntimeException | Error exception) {
            return false;
        }
    }

    private boolean enqueueEditorCollaborationPreparation(Supplier<EditorCollaborationPreparation> preparation,
                                                           Consumer<EditorCollaborationPreparation> completion) {
        try {
            projectPreparationExecutor.execute(() -> {
                EditorCollaborationPreparation result;
                try {
                    result = preparation.get();
                } catch (RuntimeException | Error exception) {
                    result = new EditorCollaborationPreparation(null, List.of(),
                        "WORLDGEN_PROJECT_PREPARATION_FAILED");
                }
                EditorCollaborationPreparation completed = result;
                try {
                    ScreenManager.getInstance().execute(() -> completion.accept(completed));
                } catch (RuntimeException | Error ignored) {
                }
            });
            return true;
        } catch (RuntimeException | Error exception) {
            return false;
        }
    }

    private static List<WorkspacePatch<JsonElement>> copyWorkspacePatches(List<WorkspacePatch<JsonElement>> patches) {
        if (patches == null || patches.isEmpty()) {
            return List.of();
        }
        ArrayList<WorkspacePatch<JsonElement>> copy = new ArrayList<>(patches.size());
        for (WorkspacePatch<JsonElement> patch : patches) {
            if (patch == null) {
                throw new IllegalArgumentException("Workspace patch is required");
            }
            JsonElement value = patch.value();
            copy.add(new WorkspacePatch<>(patch.op(), patch.path(), value == null ? null : value.deepCopy()));
        }
        return List.copyOf(copy);
    }

    public boolean prepareEditorStageSwitch(String serverId, WorldGenProject source, FlowGraph currentGraph,
                                             WorldGenStage currentStage, WorldGenStage activeStage,
                                             long editorGeneration, long mutationVersion, long workspaceGeneration,
                                             Consumer<EditorProjectPreparation> completion) {
        String normalizedServerId = connectionServerId(serverId);
        String sourceProjectId = cleanProjectId(source == null ? "" : source.getId());
        if (normalizedServerId.isBlank() || source == null || sourceProjectId.isBlank() || currentGraph == null
            || currentStage == null || activeStage == null || editorGeneration < 1L || mutationVersion < 0L
            || workspaceGeneration < 0L || completion == null) {
            return false;
        }
        long admittedEpoch = authorityEpoch(normalizedServerId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(normalizedServerId);
        WorldGenUiToken admission = captureWorldGenUiToken(normalizedServerId, sourceProjectId, admittedEpoch);
        if (!isCurrentWorldGenConnection(connection, admittedEpoch) || !isCurrentWorldGenUi(admission)) {
            return false;
        }
        return enqueueEditorPreparation(() -> {
            if (!isCurrentWorldGenConnection(connection, admittedEpoch) || !isCurrentWorldGenUi(admission)) {
                throw new IllegalStateException("World Generation stage source expired");
            }
            PreparedEditorProjectSave prepared = materializeEditorProjectView(admission, source, currentGraph,
                currentStage, activeStage, editorGeneration, mutationVersion, workspaceGeneration);
            if (!isCurrentPreparedEditorProject(prepared)) {
                throw new IllegalStateException("World Generation stage source expired");
            }
            return new EditorProjectPreparation(prepared, null, "");
        }, completion);
    }

    private boolean enqueueEditorPreparation(Supplier<EditorProjectPreparation> preparation,
                                              Consumer<EditorProjectPreparation> completion) {
        try {
            projectPreparationExecutor.execute(() -> {
                EditorProjectPreparation result;
                try {
                    result = preparation.get();
                } catch (RuntimeException | Error exception) {
                    result = new EditorProjectPreparation(null, null, "WORLDGEN_PROJECT_PREPARATION_FAILED");
                }
                EditorProjectPreparation completed = result;
                ScreenManager.getInstance().execute(() -> completion.accept(completed));
            });
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private PreparedEditorProjectSave materializeEditorProjectView(WorldGenUiToken sourceToken, WorldGenProject source,
                                                                    FlowGraph syncGraph, WorldGenStage syncStage,
                                                                    WorldGenStage activeStage, long editorGeneration,
                                                                    long mutationVersion, long workspaceGeneration) {
        if (sourceToken == null || source == null || activeStage == null || !isCurrentWorldGenUi(sourceToken)
            || !sourceToken.projectId().equals(cleanProjectId(source.getId()))) {
            throw new IllegalStateException("World Generation editor source expired");
        }
        String baseline = WorldGenSerializer.serializeProject(source);
        WorldGenProject project = WorldGenSerializer.deserializeProject(baseline);
        if (project == null) {
            throw new IllegalArgumentException("World Generation editor project is invalid");
        }
        ensureLocalDefinitions(sourceToken.serverId());
        if (syncGraph != null) {
            if (syncStage == null) {
                throw new IllegalArgumentException("World Generation synchronization stage is required");
            }
            project.setGraph(syncStage, toWorldGenGraph(sourceToken.serverId(), syncGraph));
        }
        FlowGraph activeGraph = toFlowGraph(sourceToken.serverId(), project.graph(activeStage));
        String serialized = WorldGenSerializer.serializeProject(project);
        JsonElement document = JsonParser.parseString(serialized);
        if (!document.isJsonObject()) {
            throw new IllegalArgumentException("World Generation editor document is invalid");
        }
        String canonicalHash = projectStore.preparedProjectHash(project, serialized, document.getAsJsonObject());
        if (canonicalHash.isBlank() || !isCurrentWorldGenUi(sourceToken)) {
            throw new IllegalStateException("World Generation editor source expired");
        }
        return new PreparedEditorProjectSave(project, activeGraph, serialized, document.getAsJsonObject(),
            editorGeneration, mutationVersion, workspaceGeneration, sourceToken, canonicalHash);
    }

    private PreparedEditorProjectSave materializeFrozenEditorProject(WorldGenUiToken sourceToken, WorldGenProject source,
                                                                     FlowGraph graph, WorldGenStage stage,
                                                                     long editorGeneration, long mutationVersion,
                                                                     long workspaceGeneration) {
        if (!isCurrentWorldGenUi(sourceToken) || !sourceToken.projectId().equals(cleanProjectId(source.getId()))) {
            throw new IllegalStateException("World Generation source expired");
        }
        String baseline = WorldGenSerializer.serializeProject(source);
        WorldGenProject project = WorldGenSerializer.deserializeProject(baseline);
        if (project == null) {
            throw new IllegalArgumentException("World Generation project baseline is invalid");
        }
        ensureLocalDefinitions(sourceToken.serverId());
        project.setGraph(stage, toWorldGenGraph(sourceToken.serverId(), graph));
        String serialized = WorldGenSerializer.serializeProject(project);
        JsonElement document = JsonParser.parseString(serialized);
        if (!document.isJsonObject()) {
            throw new IllegalArgumentException("World Generation workspace document is invalid");
        }
        String canonicalHash = projectStore.preparedProjectHash(project, serialized, document.getAsJsonObject());
        if (canonicalHash.isBlank() || !isCurrentWorldGenUi(sourceToken)) {
            throw new IllegalStateException("World Generation source expired");
        }
        return new PreparedEditorProjectSave(project, toFlowGraph(sourceToken.serverId(), project.graph(stage)), serialized,
            document.getAsJsonObject(), editorGeneration, mutationVersion, workspaceGeneration, sourceToken, canonicalHash);
    }

    public boolean saveFrozenWorkspaceProject(ProjectSaveAdmission admission, FlowGraph graph, WorldGenStage stage,
                                              boolean notify) {
        if (!isCurrentProjectSaveAdmission(admission) || graph == null || stage == null) {
            return false;
        }
        WorldGenProject project = projectStore.cachedProject(admission.serverId(), admission.projectId());
        if (project == null) {
            project = createProjectTemplate("Continental", admission.projectId());
        }
        try {
            PreparedEditorProjectSave prepared = materializeFrozenEditorProject(admission.sourceToken, project, graph,
                stage, 1L, 0L, 0L);
            WorldGenProjectStore.ProjectTransfer transfer = projectStore.transferPreparedProject(prepared.project(),
                prepared.serializedContent(), prepared.workspaceDocument(), prepared.canonicalHash);
            return transfer != null && isCurrentPreparedEditorProject(prepared)
                && saveWorldGen(admission.serverId(), transfer, notify, admission.sourceToken);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public ProjectSaveSubmission submitWorldGenTemplateSave(String serverId, String category, String template,
                                                            String projectId, boolean notify,
                                                            Consumer<ProjectSaveSubmissionSettlement> completion) {
        return submitWorldGenTemplateSave(serverId, category, template, projectId, notify,
            new WorldGenMetadataIntent(projectId, "", -1), completion);
    }

    public ProjectSaveSubmission submitWorldGenTemplateSave(String serverId, String category, String template,
                                                            String projectId, boolean notify,
                                                            WorldGenMetadataIntent metadataIntent,
                                                            Consumer<ProjectSaveSubmissionSettlement> completion) {
        String normalizedCategory = Objects.requireNonNullElse(category, "");
        String normalizedTemplate = Objects.requireNonNullElse(template, "");
        String normalizedProjectId = cleanProjectId(projectId);
        return submitWorldGenProjectSave(serverId, normalizedProjectId, notify,
            () -> createProjectTemplate(normalizedCategory, normalizedTemplate, normalizedProjectId), metadataIntent,
            completion);
    }

    public ProjectSaveSubmission submitWorldGenSerializedSave(String serverId, String projectId, String serialized,
                                                              boolean notify,
                                                              Consumer<ProjectSaveSubmissionSettlement> completion) {
        String normalizedProjectId = cleanProjectId(projectId);
        String immutableSerialized = Objects.requireNonNullElse(serialized, "");
        if (immutableSerialized.isBlank()) {
            return null;
        }
        return submitWorldGenProjectSave(serverId, normalizedProjectId, notify,
            () -> WorldGenSerializer.deserializeProject(immutableSerialized),
            new WorldGenMetadataIntent(normalizedProjectId, "", -1), completion);
    }

    private ProjectSaveSubmission submitWorldGenProjectSave(String serverId, String projectId, boolean notify,
                                                            Supplier<WorldGenProject> materializer,
                                                            WorldGenMetadataIntent metadataIntent,
                                                            Consumer<ProjectSaveSubmissionSettlement> completion) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedProjectId = cleanProjectId(projectId);
        if (normalizedServerId.isBlank() || normalizedProjectId.isBlank() || materializer == null || completion == null) {
            return null;
        }
        WorldGenMetadataIntent normalizedMetadataIntent = metadataIntent == null
            ? new WorldGenMetadataIntent(normalizedProjectId, "", -1) : metadataIntent;
        ProjectSaveAdmission admission = captureProjectSaveAdmission(normalizedServerId, normalizedProjectId);
        ProjectSaveSubmission submission = new ProjectSaveSubmission(UUID.randomUUID().toString(), normalizedServerId,
            normalizedProjectId);
        if (admission == null || !canMutateWorldGen(normalizedServerId)) {
            if (!canDeferWorldGenProjectSave(normalizedServerId)) {
                return null;
            }
            if (!enqueueDeferredWorldGenSave(submission, notify, materializer, normalizedMetadataIntent, completion)) {
                settleProjectSubmission(completion, new ProjectSaveSubmissionSettlement(submission, null, false,
                    "WORLDGEN_PROJECT_SAVE_BUSY"), notify, admission == null ? null : admission.sourceToken);
            }
            return submission;
        }
        startWorldGenProjectSave(submission, notify, materializer, normalizedMetadataIntent, completion, admission);
        return submission;
    }

    private boolean startWorldGenProjectSave(ProjectSaveSubmission submission, boolean notify,
                                              Supplier<WorldGenProject> materializer,
                                              WorldGenMetadataIntent metadataIntent,
                                              Consumer<ProjectSaveSubmissionSettlement> completion,
                                              ProjectSaveAdmission admission) {
        String normalizedServerId = connectionServerId(submission == null ? "" : submission.serverId());
        String normalizedProjectId = cleanProjectId(submission == null ? "" : submission.projectId());
        if (submission == null || materializer == null || metadataIntent == null || completion == null
            || admission == null) {
            return false;
        }
        if (!isCurrentProjectSaveAdmission(admission)) {
            settleProjectSubmission(completion, new ProjectSaveSubmissionSettlement(submission, null, false,
                "WORLDGEN_PROJECT_SAVE_EXPIRED"), notify, admission.sourceToken);
            return false;
        }
        if (!reserveMetadataReconciliation(submission.operationId(), normalizedServerId, normalizedProjectId, true,
            metadataIntent)) {
            settleProjectSubmission(completion, new ProjectSaveSubmissionSettlement(submission, null, false,
                "WORLDGEN_PROJECT_SAVE_BUSY"), notify, admission.sourceToken);
            return false;
        }
        try {
            projectPreparationExecutor.execute(() -> {
                WorldGenProject editableProject = null;
                boolean submitted = false;
                ProjectSubmissionCompletion pendingCompletion = null;
                String code = "WORLDGEN_PROJECT_SAVE_EXPIRED";
                try {
                    if (isCurrentProjectSaveAdmission(admission)) {
                        WorldGenProject project = materializer.get();
                        if (project == null || !normalizedProjectId.equals(cleanProjectId(project.getId()))) {
                            code = "WORLDGEN_PROJECT_SAVE_PAYLOAD_INVALID";
                        } else {
                            WorldGenProjectStore.ProjectTransfer transfer = projectStore.transferProject(project, () -> project);
                            if (transfer == null) {
                                code = "WORLDGEN_PROJECT_SAVE_PAYLOAD_INVALID";
                            } else if (isCurrentProjectSaveAdmission(admission)) {
                                editableProject = transfer.project();
                                WorldGenProjectStore.ProjectTransfer saveTransfer = new WorldGenProjectStore.ProjectTransfer(
                                    editableProject, transfer.source(), transfer.identity());
                                pendingCompletion = new ProjectSubmissionCompletion(submission, editableProject, completion,
                                    notify, admission.sourceToken);
                                submitted = saveWorldGen(normalizedServerId, saveTransfer, notify, admission.sourceToken,
                                    pendingCompletion);
                                code = submitted ? "" : "WORLDGEN_PROJECT_SAVE_NOT_SUBMITTED";
                            }
                        }
                    }
                } catch (RuntimeException | Error exception) {
                    code = "WORLDGEN_PROJECT_SAVE_FAILED";
                }
                if (!submitted) {
                    if (pendingCompletion != null) {
                        pendingCompletion.settle(false, code);
                    } else {
                        releaseMetadataReconciliation(submission.operationId());
                        settleProjectSubmission(completion, new ProjectSaveSubmissionSettlement(submission, null, false,
                            code), notify, admission.sourceToken);
                    }
                }
            });
        } catch (RuntimeException | Error exception) {
            releaseMetadataReconciliation(submission.operationId());
            settleProjectSubmission(completion, new ProjectSaveSubmissionSettlement(submission, null, false,
                "WORLDGEN_PROJECT_SAVE_BUSY"), notify, admission.sourceToken);
            return false;
        }
        return true;
    }

    private boolean canDeferWorldGenProjectSave(String serverId) {
        FlowManager manager = FlowManager.getInstance();
        FlowManager.ServerConnectionToken connection = captureConnectionToken(serverId);
        ReSyncFlowClient client = connection == null ? null : connection.source();
        if (manager == null || manager.getFlowClientConnectionState(serverId)
            == ReSyncFlowClient.ConnectionState.DISCONNECTED) {
            return false;
        }
        return client == null || client.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY;
    }

    private boolean hasWorldGenReconnectCandidate(String serverId) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return false;
        }
        FlowManager.ServerConnectionToken connection = captureConnectionToken(serverId);
        return connection != null && connection.source() != null
            || manager.getFlowClientConnectionState(serverId) != ReSyncFlowClient.ConnectionState.DISCONNECTED;
    }

    private void synchronizeWorldGenAuthorityEpoch(String serverId) {
        String normalizedServerId = connectionServerId(serverId);
        long currentEpoch = authorityEpoch(normalizedServerId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(normalizedServerId);
        ReSyncFlowClient client = connection == null ? null : connection.source();
        long observedEpoch = client == null ? 0L
            : client.resourceRevisionReconciler().authorityEpoch(normalizedServerId);
        if (observedEpoch > currentEpoch) {
            acceptAuthorityEpoch(normalizedServerId, observedEpoch);
        }
    }

    private synchronized boolean enqueueDeferredWorldGenSave(ProjectSaveSubmission submission, boolean notify,
                                                              Supplier<WorldGenProject> materializer,
                                                              WorldGenMetadataIntent metadataIntent,
                                                              Consumer<ProjectSaveSubmissionSettlement> completion) {
        if (submission == null || materializer == null || metadataIntent == null || completion == null
            || deferredSaves.size() >= MAX_DEFERRED_PROJECT_SAVES) {
            return false;
        }
        String key = saveKey(submission.serverId(), submission.projectId());
        if (deferredSaves.containsKey(key) || pendingSaves.containsKey(key) || pendingDeletes.containsKey(key)) {
            return false;
        }
        DeferredWorldGenSave deferred = new DeferredWorldGenSave(submission, notify, materializer, metadataIntent,
            completion);
        deferredSaves.put(key, deferred);
        try {
            scheduleDeferredWorldGenSave(deferred, 0L);
            return true;
        } catch (RuntimeException | Error exception) {
            deferredSaves.remove(key, deferred);
            return false;
        }
    }

    private synchronized void scheduleDeferredWorldGenSave(DeferredWorldGenSave deferred, long delayMillis) {
        if (deferred == null || deferred.finished
            || deferredSaves.get(deferred.key()) != deferred) {
            return;
        }
        TaskScheduler.ScheduledTask previous = deferred.retryTask;
        if (previous != null) {
            previous.cancel();
        }
        deferred.retryTask = mutationTimeoutScheduler.schedule(
            () -> attemptDeferredWorldGenSave(deferred), Duration.ofMillis(Math.max(0L, delayMillis)));
    }

    private void attemptDeferredWorldGenSave(DeferredWorldGenSave deferred) {
        synchronized (this) {
            if (deferred == null || deferred.finished || deferredSaves.get(deferred.key()) != deferred) {
                return;
            }
            deferred.retryTask = null;
            if (++deferred.attempts > MAX_DEFERRED_PROJECT_SAVE_ATTEMPTS) {
                finishDeferredWorldGenSave(deferred, "WORLDGEN_PROJECT_SAVE_AUTHORITY_UNAVAILABLE");
                return;
            }
        }
        synchronizeWorldGenAuthorityEpoch(deferred.submission.serverId());
        ProjectSaveAdmission admission = captureProjectSaveAdmission(deferred.submission.serverId(),
            deferred.submission.projectId());
        if (admission == null || !canMutateWorldGen(deferred.submission.serverId())) {
            synchronized (this) {
                if (deferred.finished || deferredSaves.get(deferred.key()) != deferred) {
                    return;
                }
                if (hasWorldGenReconnectCandidate(deferred.submission.serverId())) {
                    scheduleDeferredWorldGenSave(deferred, DEFERRED_PROJECT_SAVE_RETRY_MILLIS);
                } else {
                    finishDeferredWorldGenSave(deferred, "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED");
                }
            }
            return;
        }
        synchronized (this) {
            if (deferred.finished || !deferredSaves.remove(deferred.key(), deferred)) {
                return;
            }
            deferred.finished = true;
            deferred.cancelRetry();
        }
        startWorldGenProjectSave(deferred.submission, deferred.notify, deferred.materializer,
            deferred.metadataIntent, deferred.completion, admission);
    }

    private synchronized void finishDeferredWorldGenSave(DeferredWorldGenSave deferred, String code) {
        if (deferred == null || deferred.finished || !deferredSaves.remove(deferred.key(), deferred)) {
            return;
        }
        deferred.finished = true;
        deferred.cancelRetry();
        settleProjectSubmission(deferred.completion, new ProjectSaveSubmissionSettlement(deferred.submission, null,
            false, code), deferred.notify, null);
    }

    private synchronized void finishDeferredWorldGenSaves(String serverId, String code) {
        String normalizedServerId = connectionServerId(serverId);
        for (DeferredWorldGenSave deferred : new ArrayList<>(deferredSaves.values())) {
            if (deferred != null && normalizedServerId.equals(connectionServerId(deferred.submission.serverId()))) {
                finishDeferredWorldGenSave(deferred, code);
            }
        }
    }

    void saveWorldGen(String serverId, WorldGenProject project, boolean notify) {
        if (serverId == null || serverId.isBlank() || project == null || !canMutateWorldGen(serverId)) {
            return;
        }
        ProjectSaveAdmission admission = captureProjectSaveAdmission(serverId, project.getId());
        if (admission == null) {
            return;
        }
        WorldGenProjectStore.ProjectTransfer transfer = projectStore.transferProject(project, () -> project);
        if (transfer != null && isCurrentProjectSaveAdmission(admission)) {
            saveWorldGen(serverId, transfer, notify, admission.sourceToken);
        }
    }

    private void settleProjectSubmission(Consumer<ProjectSaveSubmissionSettlement> completion,
                                         ProjectSaveSubmissionSettlement settlement, boolean notify,
                                         WorldGenUiToken sourceToken) {
        if (completion == null) {
            return;
        }
        try {
            executeAfterManagerRelease(() -> ScreenManager.getInstance().execute(() -> {
                ProjectSaveSubmissionSettlement admittedSettlement = settlement.connection() == null && sourceToken != null
                    ? new ProjectSaveSubmissionSettlement(settlement.submission(), settlement.project(), settlement.state(),
                        settlement.code(), sourceToken.connection(), sourceToken.authorityEpoch()) : settlement;
                boolean currentConnection = sourceToken != null && isCurrentWorldGenConnection(sourceToken.connection(),
                    sourceToken.authorityEpoch());
                ProjectSaveSubmissionSettlement delivered = admittedSettlement.state() == ProjectSubmissionState.SUBMITTED
                    && !currentConnection
                    ? new ProjectSaveSubmissionSettlement(admittedSettlement.submission(), null,
                        ProjectSubmissionState.NOT_SUBMITTED,
                        "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED", admittedSettlement.connection(),
                        admittedSettlement.authorityEpoch()) : admittedSettlement;
                if (notify && !delivered.submitted()) {
                    new Notification("World Generation Failed", projectSubmissionDescription(delivered.code()),
                        Notification.Type.ERROR);
                }
                completion.accept(delivered);
            }));
        } catch (RuntimeException | Error ignored) {
        }
    }

    private void settleProjectDeletion(Consumer<ProjectDeleteSubmissionSettlement> completion,
                                       ProjectDeleteSubmissionSettlement settlement, WorldGenUiToken sourceToken) {
        if (completion == null) {
            return;
        }
        try {
            executeAfterManagerRelease(() -> ScreenManager.getInstance().execute(() -> {
                ProjectDeleteSubmissionSettlement admittedSettlement = settlement.connection() == null && sourceToken != null
                    ? new ProjectDeleteSubmissionSettlement(settlement.submission(), settlement.state(),
                        settlement.authoritativeRevision(), settlement.code(), sourceToken.connection(),
                        sourceToken.authorityEpoch()) : settlement;
                boolean currentConnection = sourceToken != null && isCurrentWorldGenConnection(sourceToken.connection(),
                    sourceToken.authorityEpoch());
                ProjectDeleteSubmissionSettlement delivered = admittedSettlement.state() == ProjectSubmissionState.SUBMITTED
                    && !currentConnection
                    ? new ProjectDeleteSubmissionSettlement(admittedSettlement.submission(), ProjectSubmissionState.NOT_SUBMITTED,
                        0L, "WORLDGEN_PROJECT_DELETE_CONNECTION_CHANGED", admittedSettlement.connection(),
                        admittedSettlement.authorityEpoch()) : admittedSettlement;
                completion.accept(delivered);
            }));
        } catch (RuntimeException | Error ignored) {
        }
    }

    private String projectSubmissionDescription(String code) {
        return switch (Objects.requireNonNullElse(code, "")) {
            case "WORLDGEN_PROJECT_SAVE_BUSY" -> "Save Busy";
            case "WORLDGEN_PROJECT_SAVE_EXPIRED" -> "Connection Changed";
            case "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED" -> "Connection Changed";
            case "WORLDGEN_PROJECT_SAVE_PAYLOAD_INVALID" -> "Project Invalid";
            case "WORLDGEN_PROJECT_SAVE_SUPERSEDED" -> "Save Superseded";
            case "WORLDGEN_PROJECT_SAVE_TIMEOUT" -> "Save Timed Out";
            case "WORLDGEN_PROJECT_SAVE_AUTHORITY_CHANGED" -> "Requires Refresh";
            case "WORLDGEN_PROJECT_SAVE_AUTHORITY_UNAVAILABLE" -> "ReSync Not Ready; Retry";
            case "WORLDGEN_PROJECT_SAVE_FAILED" -> "Save Failed";
            default -> "Save Not Submitted";
        };
    }

    private final class ProjectSubmissionCompletion {
        private final ProjectSaveSubmission submission;
        private final WorldGenProject project;
        private final Consumer<ProjectSaveSubmissionSettlement> completion;
        private final boolean notify;
        private final WorldGenUiToken sourceToken;
        private final BrowserSafeState.BooleanValue settled = new BrowserSafeState.BooleanValue();

        private ProjectSubmissionCompletion(ProjectSaveSubmission submission, WorldGenProject project,
                                            Consumer<ProjectSaveSubmissionSettlement> completion, boolean notify,
                                            WorldGenUiToken sourceToken) {
            this.submission = Objects.requireNonNull(submission, "World Generation save submission is required");
            this.project = Objects.requireNonNull(project, "World Generation submitted project is required");
            this.completion = Objects.requireNonNull(completion, "World Generation save completion is required");
            this.notify = notify;
            this.sourceToken = Objects.requireNonNull(sourceToken, "World Generation source token is required");
        }

        private void settle(boolean submitted, String code) {
            settle(submitted ? ProjectSubmissionState.SUBMITTED : ProjectSubmissionState.NOT_SUBMITTED, code);
        }

        private void settle(ProjectSubmissionState state, String code) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            ProjectSaveSubmissionSettlement settlement = new ProjectSaveSubmissionSettlement(submission,
                state == ProjectSubmissionState.NOT_SUBMITTED ? null : project, state, code,
                sourceToken.connection(), sourceToken.authorityEpoch());
            if (state == ProjectSubmissionState.COMMITTED) {
                try {
                    promoteMetadataReconciliation(settlement);
                } catch (RuntimeException | Error exception) {
                    try {
                        releaseMetadataReconciliation(submission.operationId());
                    } catch (RuntimeException | Error ignored) {
                    }
                }
            } else {
                try {
                    releaseMetadataReconciliation(submission.operationId());
                } catch (RuntimeException | Error ignored) {
                }
            }
            try {
                settleProjectSubmission(completion, settlement, notify, sourceToken);
            } catch (RuntimeException | Error ignored) {
            }
        }

        private void settleCommitted() {
            settle(ProjectSubmissionState.COMMITTED, "");
        }
    }

    private boolean saveWorldGen(String serverId, WorldGenProjectStore.ProjectTransfer draftTransfer,
                                 boolean notify, WorldGenUiToken sourceToken) {
        return saveWorldGen(serverId, draftTransfer, notify, sourceToken, null);
    }

    private boolean saveWorldGen(String serverId, WorldGenProjectStore.ProjectTransfer draftTransfer,
                                 boolean notify, WorldGenUiToken sourceToken,
                                 ProjectSubmissionCompletion submissionCompletion) {
        String normalizedServerId = connectionServerId(serverId);
        if (normalizedServerId.isBlank() || !canMutateWorldGen(normalizedServerId)) {
            return false;
        }
        synchronized (this) {
            return saveWorldGenLocked(serverId, draftTransfer, notify, sourceToken, submissionCompletion);
        }
    }

    private boolean saveWorldGenLocked(String serverId, WorldGenProjectStore.ProjectTransfer draftTransfer,
                                       boolean notify, WorldGenUiToken sourceToken,
                                       ProjectSubmissionCompletion submissionCompletion) {
        WorldGenProject project = draftTransfer == null ? null : draftTransfer.project();
        String normalizedServerId = connectionServerId(serverId);
        if (normalizedServerId.isBlank() || project == null || sourceToken == null
            || !normalizedServerId.equals(sourceToken.serverId())) {
            return false;
        }
        String projectId = cleanProjectId(project.getId());
        if (projectId.isBlank() || !projectId.equals(sourceToken.projectId()) || !isCurrentWorldGenUi(sourceToken)) {
            return false;
        }
        ReSyncFlowClient client = sourceToken.connection().source();
        long admittedEpoch = sourceToken.authorityEpoch();
        WorldGenUiToken uiToken = sourceToken;
        if (pendingDeletes.containsKey(saveKey(normalizedServerId, projectId))) {
            executeWorldGenUi(uiToken, () -> new Notification("World Generation Failed",
                "Delete Already In Progress", Notification.Type.ERROR));
            return false;
        }
        project.setId(projectId);
        if (draftTransfer == null || draftTransfer.identity() == null) {
            return false;
        }
        long retainedBytes = retainedMutationBytes(draftTransfer.identity().canonicalContent());
        if (!canAdmitPendingMutation(retainedBytes)) {
            executeWorldGenUi(uiToken, () -> new Notification("World Generation Failed",
                "Save Queue Busy", Notification.Type.ERROR));
            return false;
        }
        if (!isCurrentWorldGenUi(uiToken)) {
            return false;
        }
        projectStore.setDraftProject(uiToken.serverId(), draftTransfer);
        if (!isCurrentWorldGenUi(uiToken)) {
            return false;
        }
        long expectedRevision = projectStore.projectRevision(normalizedServerId, projectId);
        String mutationId = UUID.randomUUID().toString();
        String requestId = "worldGenProjectSave:" + projectId + ":" + mutationId;
        String operationId = requestId;
        PendingWorldGenSave pendingSave = new PendingWorldGenSave(projectId, requestId, mutationId, operationId,
            expectedRevision, admittedEpoch, draftTransfer, retainedBytes, notify, uiToken, submissionCompletion);
        PendingWorldGenSave previous = pendingSaves.put(saveKey(normalizedServerId, projectId), pendingSave);
        if (previous != null) {
            previous.finished = true;
            previous.cancelTimeout();
            try {
                previous.settleSubmission(false, "WORLDGEN_PROJECT_SAVE_SUPERSEDED");
            } catch (RuntimeException | Error ignored) {
            }
            try {
                executeWorldGenMutationUi(() -> previous.finish("World Generation Failed", "Save Superseded", Notification.Type.ERROR));
            } catch (RuntimeException | Error ignored) {
            }
        }
        try {
            long timeoutToken = pendingSave.nextTimeoutToken();
            pendingSave.setTimeoutTask(mutationTimeoutScheduler.schedule(
                () -> timeoutProjectSave(normalizedServerId, projectId, pendingSave, timeoutToken),
                Duration.ofSeconds(SAVE_TIMEOUT_SECONDS)));
            if (notify) {
                executeWorldGenUi(uiToken, pendingSave::showSaving);
            }
            if (client != null) {
                executeAfterManagerRelease(() -> {
                    try {
                        boolean sent = runWorldGenConnectionNow(uiToken, () -> client.sendWorldGenSave(draftTransfer.project(),
                            pendingSave.requestId, pendingSave.mutationId, pendingSave.operationId, pendingSave.expectedRevision));
                        if (!sent) {
                            failProjectSave(normalizedServerId, projectId, requestId, mutationId, operationId, "ReSync Offline");
                        }
                    } catch (RuntimeException | Error exception) {
                        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                            ? "Save Failed" : exception.getMessage();
                        failProjectSave(normalizedServerId, projectId, requestId, mutationId, operationId, message);
                    }
                });
            } else {
                if (!failProjectSave(normalizedServerId, projectId, requestId, mutationId, operationId, "ReSync Offline")) {
                    executeWorldGenUi(uiToken, () -> new Notification("World Generation Failed", "ReSync Offline", Notification.Type.ERROR));
                }
                return false;
            }
        } catch (RuntimeException | Error exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Save Failed" : exception.getMessage();
            if (!failProjectSave(normalizedServerId, projectId, requestId, mutationId, operationId, message)) {
                executeWorldGenUi(uiToken, () -> new Notification("World Generation Failed", message, Notification.Type.ERROR));
            }
            return false;
        }
        return true;
    }

    public void requestProject(String serverId, String projectId) {
        long startedAt = System.nanoTime();
        ReSyncFlowClient client = flowClient(serverId);
        if (client == null) {
            traceWorldGenLifecycle(serverId, "worldgen_request", "project", projectId, "", "", -1L,
                authorityEpoch(serverId), -1L, "rejected", "client_missing", 0, 0, "none", startedAt);
            return;
        }
        client.requestWorldGenProject(projectId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(serverId);
        traceWorldGenLifecycle(serverId, "worldgen_request", "project", projectId, "", "",
            connection == null ? -1L : connection.generation(), authorityEpoch(serverId), -1L,
            "dispatched", "request_sent", 0, 0, "none", startedAt);
    }

    public void requestProjectList(String serverId) {
        long startedAt = System.nanoTime();
        ReSyncFlowClient client = flowClient(serverId);
        if (client == null) {
            traceWorldGenLifecycle(serverId, "worldgen_request", "project_list", null, "", "", -1L,
                authorityEpoch(serverId), -1L, "rejected", "client_missing", 0, 0, "none", startedAt);
            return;
        }
        client.requestWorldGenProjectList();
        FlowManager.ServerConnectionToken connection = captureConnectionToken(serverId);
        traceWorldGenLifecycle(serverId, "worldgen_request", "project_list", null, "", "",
            connection == null ? -1L : connection.generation(), authorityEpoch(serverId), -1L,
            "dispatched", "request_sent", 0, 0, "none", startedAt);
    }

    public void requestProjectListIfMissing(String serverId) {
        if (!projectStore.hasProjectList(serverId)) {
            requestProjectList(serverId);
        }
    }

    public boolean reconcileWorldGenProjectMetadata(ProjectSaveSubmissionSettlement settlement,
                                                    String displayName, String path, int sortOrder,
                                                    Consumer<ProjectMetadataReconciliationResult> completion) {
        if (settlement == null || !settlement.committed() || settlement.project() == null
            || settlement.connection() == null || settlement.authorityEpoch() < 1L) {
            if (settlement != null) {
                releaseMetadataReconciliation(settlement.submission().operationId());
            }
            return false;
        }
        String serverId = connectionServerId(settlement.submission().serverId());
        String projectId = cleanProjectId(settlement.submission().projectId());
        if (serverId.isBlank() || projectId.isBlank() || !serverId.equals(connectionServerId(settlement.connection().serverId()))
            || !projectId.equals(cleanProjectId(settlement.project().getId()))) {
            releaseMetadataReconciliation(settlement.submission().operationId());
            return false;
        }
        String canonicalHash = projectStore.knownCanonicalHash(settlement.project());
        if (canonicalHash.isBlank()) {
            releaseMetadataReconciliation(settlement.submission().operationId());
            return false;
        }
        return enqueueMetadataReconciliation(new PendingWorldGenMetadataReconciliation(
            settlement.submission().operationId(), serverId, projectId, true,
            Objects.requireNonNullElse(displayName, projectId), path, sortOrder,
            projectStore.projectRevision(serverId, projectId), canonicalHash,
            settlement.connection(), settlement.authorityEpoch(), completion));
    }

    public boolean reconcileWorldGenProjectMetadata(ProjectDeleteSubmissionSettlement settlement,
                                                    String displayName, String path, int sortOrder,
                                                    Consumer<ProjectMetadataReconciliationResult> completion) {
        if (settlement == null || !settlement.committed() || settlement.connection() == null
            || settlement.authorityEpoch() < 1L || settlement.authoritativeRevision() < 1L) {
            if (settlement != null) {
                releaseMetadataReconciliation(settlement.submission().operationId());
            }
            return false;
        }
        String serverId = connectionServerId(settlement.submission().serverId());
        String projectId = cleanProjectId(settlement.submission().projectId());
        if (serverId.isBlank() || projectId.isBlank()
            || !serverId.equals(connectionServerId(settlement.connection().serverId()))) {
            releaseMetadataReconciliation(settlement.submission().operationId());
            return false;
        }
        return enqueueMetadataReconciliation(new PendingWorldGenMetadataReconciliation(
            settlement.submission().operationId(), serverId, projectId, false,
            Objects.requireNonNullElse(displayName, projectId), path, sortOrder,
            settlement.authoritativeRevision(), "", settlement.connection(), settlement.authorityEpoch(), completion));
    }

    private synchronized boolean reserveMetadataReconciliation(String operationId, String serverId, String projectId,
                                                               boolean add, WorldGenMetadataIntent metadataIntent) {
        if (operationId == null || operationId.isBlank() || serverId == null || serverId.isBlank()
            || projectId == null || projectId.isBlank() || pendingMetadataReconciliations.size()
                + metadataReconciliationReservations.size() >= MAX_PENDING_METADATA_RECONCILIATIONS
            || metadataIntent == null || metadataReconciliationReservations.containsKey(operationId)
            || pendingMetadataReconciliations.containsKey(operationId)) {
            return false;
        }
        WorldGenMetadataIntent normalizedIntent = metadataIntent.displayName().isBlank()
            ? new WorldGenMetadataIntent(projectId, metadataIntent.path(), metadataIntent.sortOrder()) : metadataIntent;
        if (add) {
            normalizedIntent = new WorldGenMetadataIntent(normalizedIntent.displayName(),
                canonicalWorldGenResourcePath(projectId, normalizedIntent.path()), normalizedIntent.sortOrder());
        }
        metadataReconciliationReservations.put(operationId,
            new PendingMetadataReconciliationReservation(operationId, serverId, projectId, add, normalizedIntent));
        return true;
    }

    private synchronized void releaseMetadataReconciliation(String operationId) {
        if (operationId != null && !operationId.isBlank()) {
            metadataReconciliationReservations.remove(operationId);
        }
    }

    private synchronized void promoteMetadataReconciliation(ProjectSaveSubmissionSettlement settlement) {
        if (settlement == null || !settlement.committed() || settlement.connection() == null
            || settlement.authorityEpoch() < 1L || settlement.project() == null) {
            return;
        }
        String operationId = settlement.submission().operationId();
        PendingMetadataReconciliationReservation reservation = metadataReconciliationReservations.remove(operationId);
        if (reservation == null || !reservation.add) {
            return;
        }
        String serverId = connectionServerId(settlement.submission().serverId());
        String projectId = cleanProjectId(settlement.submission().projectId());
        String canonicalHash = projectStore.knownCanonicalHash(settlement.project());
        if (!reservation.matches(serverId, projectId, true)
            || !projectId.equals(cleanProjectId(settlement.project().getId())) || canonicalHash.isBlank()) {
            return;
        }
        PendingWorldGenMetadataReconciliation reconciliation = new PendingWorldGenMetadataReconciliation(operationId,
            serverId, projectId, true, reservation.intent, projectStore.projectRevision(serverId, projectId), canonicalHash,
            settlement.connection(), settlement.authorityEpoch(), null);
        pendingMetadataReconciliations.put(operationId, reconciliation);
        try {
            scheduleMetadataReconciliation(reconciliation, 0L);
        } catch (RuntimeException | Error exception) {
            pendingMetadataReconciliations.remove(operationId, reconciliation);
            completeMetadataReconciliation(reconciliation, false, false, "WORLDGEN_METADATA_RECONCILIATION_FAILED");
        }
    }

    private synchronized void promoteMetadataReconciliation(ProjectDeleteSubmissionSettlement settlement) {
        if (settlement == null || !settlement.committed() || settlement.connection() == null
            || settlement.authorityEpoch() < 1L || settlement.authoritativeRevision() < 1L) {
            return;
        }
        String operationId = settlement.submission().operationId();
        PendingMetadataReconciliationReservation reservation = metadataReconciliationReservations.remove(operationId);
        if (reservation == null || reservation.add) {
            return;
        }
        String serverId = connectionServerId(settlement.submission().serverId());
        String projectId = cleanProjectId(settlement.submission().projectId());
        if (!reservation.matches(serverId, projectId, false)) {
            return;
        }
        PendingWorldGenMetadataReconciliation reconciliation = new PendingWorldGenMetadataReconciliation(operationId,
            serverId, projectId, false, reservation.intent, settlement.authoritativeRevision(), "",
            settlement.connection(), settlement.authorityEpoch(), null);
        pendingMetadataReconciliations.put(operationId, reconciliation);
        try {
            scheduleMetadataReconciliation(reconciliation, 0L);
        } catch (RuntimeException | Error exception) {
            pendingMetadataReconciliations.remove(operationId, reconciliation);
            completeMetadataReconciliation(reconciliation, false, false, "WORLDGEN_METADATA_RECONCILIATION_FAILED");
        }
    }

    private synchronized boolean enqueueMetadataReconciliation(PendingWorldGenMetadataReconciliation reconciliation) {
        if (reconciliation == null || reconciliation.operationId.isBlank() || reconciliation.serverId.isBlank()
            || reconciliation.projectId.isBlank()) {
            return false;
        }
        CompletedMetadataReconciliation completed = completedMetadataReconciliations.get(reconciliation.operationId);
        if (completed != null) {
            ProjectMetadataReconciliationResult result = completed.result();
            if (!reconciliation.operationId.equals(result.operationId())
                || !reconciliation.serverId.equals(result.serverId())
                || !reconciliation.projectId.equals(result.projectId())
                || reconciliation.add != result.add()
                || !reconciliation.sameIntent(completed.intent())
                || !reconciliation.canonicalHash.equals(completed.canonicalHash())) {
                return false;
            }
            if (reconciliation.completion != null) {
                Consumer<ProjectMetadataReconciliationResult> observer = reconciliation.completion;
                executeAfterManagerRelease(() -> ScreenManager.getInstance().execute(() -> {
                    try {
                        observer.accept(result);
                    } catch (RuntimeException | Error ignored) {
                    }
                }));
            }
            return true;
        }
        PendingWorldGenMetadataReconciliation existing = pendingMetadataReconciliations.get(reconciliation.operationId);
        if (existing != null) {
            if (!existing.sameIntent(reconciliation)) {
                return false;
            }
            if (existing.completion == null) {
                existing.completion = reconciliation.completion;
            }
            return true;
        }
        PendingMetadataReconciliationReservation reservation = metadataReconciliationReservations.get(reconciliation.operationId);
        if (reservation != null) {
            if (!reservation.serverId.equals(reconciliation.serverId) || !reservation.projectId.equals(reconciliation.projectId)
                || reservation.add != reconciliation.add || !reservation.matches(reconciliation)) {
                metadataReconciliationReservations.remove(reconciliation.operationId, reservation);
                completeMetadataReconciliation(reconciliation, false, false, "WORLDGEN_METADATA_RECONCILIATION_IDENTITY_MISMATCH");
                return false;
            }
            metadataReconciliationReservations.remove(reconciliation.operationId, reservation);
        } else if (pendingMetadataReconciliations.size() + metadataReconciliationReservations.size()
            >= MAX_PENDING_METADATA_RECONCILIATIONS) {
            completeMetadataReconciliation(reconciliation, false, false, "WORLDGEN_METADATA_RECONCILIATION_BUSY");
            return false;
        }
        pendingMetadataReconciliations.put(reconciliation.operationId, reconciliation);
        try {
            scheduleMetadataReconciliation(reconciliation, 0L);
            return true;
        } catch (RuntimeException | Error exception) {
            pendingMetadataReconciliations.remove(reconciliation.operationId, reconciliation);
            completeMetadataReconciliation(reconciliation, false, false, "WORLDGEN_METADATA_RECONCILIATION_FAILED");
            return false;
        }
    }

    private synchronized void scheduleMetadataReconciliation(PendingWorldGenMetadataReconciliation reconciliation,
                                                              long delayMillis) {
        if (reconciliation == null || reconciliation.finished
            || pendingMetadataReconciliations.get(reconciliation.operationId) != reconciliation) {
            return;
        }
        TaskScheduler.ScheduledTask previous = reconciliation.retryTask;
        if (previous != null) {
            previous.cancel();
        }
        reconciliation.retryTask = mutationTimeoutScheduler.schedule(
            () -> runMetadataReconciliation(reconciliation), Duration.ofMillis(Math.max(0L, delayMillis)));
    }

    private void runMetadataReconciliation(PendingWorldGenMetadataReconciliation reconciliation) {
        synchronized (this) {
            if (reconciliation == null || reconciliation.finished
                || pendingMetadataReconciliations.get(reconciliation.operationId) != reconciliation
                || reconciliation.inFlight) {
                return;
            }
            reconciliation.inFlight = true;
            reconciliation.retryTask = null;
        }
        try {
            submitMetadataReconciliation(reconciliation);
        } catch (RuntimeException | Error exception) {
            deferMetadataReconciliation(reconciliation, "World Generation Metadata Sync Failed", true);
        }
    }

    private void submitMetadataReconciliation(PendingWorldGenMetadataReconciliation reconciliation) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || !rebindMetadataAuthority(reconciliation, manager)) {
            deferMetadataReconciliation(reconciliation, "World Generation Metadata Sync Pending", false);
            return;
        }
        if (!hasCurrentProjectMetadataAuthority(reconciliation, manager)) {
            requestProjectMetadataAuthority(reconciliation);
            awaitMetadataReconciliation(reconciliation);
            return;
        }
        if (!hasCurrentMetadataLease(reconciliation)) {
            requestMetadataLease(reconciliation);
            awaitMetadataReconciliation(reconciliation);
            return;
        }
        if (metadataStateMatches(manager, reconciliation)) {
            completeMetadataReconciliation(reconciliation, true, true);
            return;
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(reconciliation.serverId,
            ReSyncResourceType.PROJECT_METADATA, reconciliation.serverId, "World Generation");
        if (ticket == null || !DesignerSaveNotifications.attachMutationId(ticket, reconciliation.operationId)) {
            if (ticket != null) {
                DesignerSaveNotifications.failExact(ticket, "World Generation Metadata Sync Unavailable");
            }
            deferMetadataReconciliation(reconciliation, "World Generation Metadata Sync Pending", false);
            return;
        }
        synchronized (this) {
            if (pendingMetadataReconciliations.get(reconciliation.operationId) != reconciliation || reconciliation.finished) {
                DesignerSaveNotifications.failExact(ticket, "World Generation Metadata Sync Superseded");
                return;
            }
            reconciliation.ticket = ticket;
            reconciliation.requiredMetadataAuthorityGeneration = nextProjectMetadataAuthorityGeneration(manager,
                reconciliation.serverId);
        }
        ticket.whenFinished((saved, currentAtFinish) -> ScreenManager.getInstance().execute(() ->
            finishMetadataReconciliation(reconciliation, ticket, saved, currentAtFinish)));
        boolean[] sent = {false};
        try {
            boolean admitted = manager.runIfCurrentServerConnection(reconciliation.connection, () -> {
                if (!hasCurrentMetadataLease(reconciliation)) {
                    return;
                }
                FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(reconciliation.serverId);
                FlowManager.ProjectResource existing = metadata.resource(ReSyncResourceDragPayload.WORLDGEN,
                    reconciliation.projectId);
                if (reconciliation.add) {
                    int sortOrder = reconciliation.sortOrder >= 0 ? reconciliation.sortOrder
                        : existing != null ? existing.sortOrder() : metadata.nextResourceSortOrder();
                    metadata.putResource(ReSyncResourceDragPayload.WORLDGEN, reconciliation.projectId,
                        reconciliation.displayName, reconciliation.path, sortOrder);
                } else {
                    metadata.removeResource(ReSyncProjectMetadata.resourceKey(ReSyncResourceDragPayload.WORLDGEN,
                        reconciliation.projectId));
                }
                manager.saveProjectMetadata(metadata, true, ticket);
                sent[0] = true;
            });
            if (!admitted || !sent[0]) {
                DesignerSaveNotifications.failExact(ticket, "World Generation Authority Changed");
            }
        } catch (RuntimeException | Error exception) {
            DesignerSaveNotifications.failExact(ticket, "World Generation Metadata Sync Failed");
        }
    }

    private boolean rebindMetadataAuthority(PendingWorldGenMetadataReconciliation reconciliation, FlowManager manager) {
        if (reconciliation == null || manager == null) {
            return false;
        }
        if (reconciliation.connection != null && manager.isCurrentServerConnection(reconciliation.connection)
            && authorityEpoch(reconciliation.serverId) == reconciliation.authorityEpoch) {
            return true;
        }
        FlowManager.ServerConnectionToken connection = manager.captureServerConnectionToken(reconciliation.serverId);
        long epoch = authorityEpoch(reconciliation.serverId);
        if (connection == null || epoch < 1L || !manager.isCurrentServerConnection(connection)) {
            return false;
        }
        synchronized (this) {
            boolean connectionChanged = !Objects.equals(reconciliation.connection, connection);
            if (reconciliation.authorityEpoch != epoch || connectionChanged) {
                reconciliation.requiredRevision = 0L;
                reconciliation.requiredMetadataAuthorityGeneration = nextProjectMetadataAuthorityGeneration(manager,
                    reconciliation.serverId);
                if (connectionChanged) {
                    projectStore.clearAuthoritative(reconciliation.serverId);
                }
            }
            reconciliation.connection = connection;
            reconciliation.authorityEpoch = epoch;
        }
        return true;
    }

    private boolean hasCurrentProjectMetadataAuthority(PendingWorldGenMetadataReconciliation reconciliation,
                                                       FlowManager manager) {
        return reconciliation != null && manager != null
            && manager.projectMetadataAuthorityGeneration(reconciliation.serverId)
                >= reconciliation.requiredMetadataAuthorityGeneration;
    }

    private static long nextProjectMetadataAuthorityGeneration(FlowManager manager, String serverId) {
        long current = manager == null ? 0L : manager.projectMetadataAuthorityGeneration(serverId);
        return current == Long.MAX_VALUE ? 1L : current + 1L;
    }

    private void requestProjectMetadataAuthority(PendingWorldGenMetadataReconciliation reconciliation) {
        ReSyncFlowClient client = reconciliation == null ? null : flowClient(reconciliation.serverId);
        if (client != null) {
            client.requestProjectMetadata(reconciliation.serverId);
        }
    }

    private boolean hasCurrentMetadataLease(PendingWorldGenMetadataReconciliation reconciliation) {
        if (reconciliation == null || reconciliation.connection == null) {
            return false;
        }
        if (reconciliation.add) {
            ProjectReadSnapshot snapshot = snapshotProject(reconciliation.serverId, reconciliation.projectId);
            if (snapshot == null || !isCurrentProjectSnapshot(snapshot)
                || snapshot.authorityEpoch() != reconciliation.authorityEpoch
                || !Objects.equals(snapshot.connection(), reconciliation.connection)
                || snapshot.revision() < reconciliation.requiredRevision
                || !reconciliation.canonicalHash.equals(snapshot.canonicalHash())) {
                return false;
            }
            return true;
        }
        ProjectListReadSnapshot snapshot = snapshotProjectList(reconciliation.serverId);
        return snapshot != null && isCurrentProjectListSnapshot(snapshot)
            && snapshot.authorityEpoch() == reconciliation.authorityEpoch
            && Objects.equals(snapshot.connection(), reconciliation.connection)
            && snapshot.revision() >= reconciliation.requiredRevision
            && !snapshot.contains(reconciliation.projectId);
    }

    private void requestMetadataLease(PendingWorldGenMetadataReconciliation reconciliation) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || reconciliation == null || reconciliation.connection == null) {
            return;
        }
        manager.runIfCurrentServerConnection(reconciliation.connection, () -> {
            if (reconciliation.add) {
                requestProject(reconciliation.serverId, reconciliation.projectId);
            } else {
                requestProjectList(reconciliation.serverId);
            }
        });
    }

    private boolean metadataMatches(FlowManager.ProjectResource entry,
                                    PendingWorldGenMetadataReconciliation reconciliation) {
        return entry != null && ReSyncResourceDragPayload.WORLDGEN.equals(entry.type())
            && reconciliation.projectId.equals(entry.id())
            && reconciliation.displayName.equals(entry.displayName())
            && reconciliation.path.equals(ReSyncProjectMetadata.normalizePath(entry.path()))
            && (reconciliation.sortOrder < 0 || reconciliation.sortOrder == entry.sortOrder());
    }

    private boolean metadataMatches(ReSyncProjectMetadata.ResourceEntry entry,
                                    PendingWorldGenMetadataReconciliation reconciliation) {
        return entry != null && ReSyncResourceDragPayload.WORLDGEN.equals(entry.getType())
            && reconciliation.projectId.equals(entry.getId())
            && reconciliation.displayName.equals(entry.getDisplayName())
            && reconciliation.path.equals(ReSyncProjectMetadata.normalizePath(entry.getPath()))
            && (reconciliation.sortOrder < 0 || reconciliation.sortOrder == entry.getSortOrder());
    }

    private void finishMetadataReconciliation(PendingWorldGenMetadataReconciliation reconciliation,
                                              DesignerSaveNotifications.SaveTicket ticket, boolean saved,
                                              boolean currentAtFinish) {
        synchronized (this) {
            if (reconciliation == null || pendingMetadataReconciliations.get(reconciliation.operationId) != reconciliation
                || reconciliation.ticket != ticket || reconciliation.finished) {
                return;
            }
            reconciliation.ticket = null;
            reconciliation.inFlight = false;
        }
        FlowManager manager = FlowManager.getInstance();
        boolean currentAuthority = manager != null && manager.isCurrentServerConnection(reconciliation.connection)
            && authorityEpoch(reconciliation.serverId) == reconciliation.authorityEpoch;
        if (!saved || !currentAtFinish || !currentAuthority || !hasCurrentMetadataLease(reconciliation)
            || manager == null || !metadataStateMatches(manager, reconciliation)) {
            deferMetadataReconciliation(reconciliation, "World Generation Metadata Sync Pending", true);
            return;
        }
        synchronized (this) {
            if (!pendingMetadataReconciliations.remove(reconciliation.operationId, reconciliation)) {
                return;
            }
            reconciliation.finished = true;
            if (reconciliation.retryTask != null) {
                reconciliation.retryTask.cancel();
                reconciliation.retryTask = null;
            }
            completeMetadataReconciliation(reconciliation, true, true, "");
        }
    }

    private boolean metadataStateMatches(FlowManager manager,
                                         PendingWorldGenMetadataReconciliation reconciliation) {
        ReSyncProjectMetadata.ResourceEntry resource = manager.getAuthoritativeProjectResource(reconciliation.serverId,
            ReSyncResourceDragPayload.WORLDGEN, reconciliation.projectId);
        return reconciliation.add ? metadataMatches(resource, reconciliation) : resource == null;
    }

    private synchronized void awaitMetadataReconciliation(PendingWorldGenMetadataReconciliation reconciliation) {
        if (reconciliation == null || reconciliation.finished
            || pendingMetadataReconciliations.get(reconciliation.operationId) != reconciliation) {
            return;
        }
        reconciliation.inFlight = false;
        reconciliation.ticket = null;
        scheduleMetadataReconciliation(reconciliation, METADATA_RECONCILIATION_RETRY_MILLIS);
    }

    private void completeMetadataReconciliation(PendingWorldGenMetadataReconciliation reconciliation,
                                                boolean committed, boolean currentAtFinish) {
        synchronized (this) {
            if (reconciliation == null || reconciliation.finished
                || !pendingMetadataReconciliations.remove(reconciliation.operationId, reconciliation)) {
                return;
            }
            reconciliation.finished = true;
            reconciliation.inFlight = false;
            reconciliation.ticket = null;
            if (reconciliation.retryTask != null) {
                reconciliation.retryTask.cancel();
                reconciliation.retryTask = null;
            }
        }
        completeMetadataReconciliation(reconciliation, committed, currentAtFinish, "");
    }

    private synchronized void deferMetadataReconciliation(PendingWorldGenMetadataReconciliation reconciliation,
                                                           String message, boolean rebind) {
        if (reconciliation == null || reconciliation.finished
            || pendingMetadataReconciliations.get(reconciliation.operationId) != reconciliation) {
            return;
        }
        reconciliation.inFlight = false;
        reconciliation.ticket = null;
        if (rebind) {
            reconciliation.requiredRevision = 0L;
            reconciliation.requiredMetadataAuthorityGeneration = nextProjectMetadataAuthorityGeneration(
                FlowManager.getInstance(), reconciliation.serverId);
        }
        boolean notify = !reconciliation.issueNotified;
        reconciliation.issueNotified = true;
        scheduleMetadataReconciliation(reconciliation, METADATA_RECONCILIATION_RETRY_MILLIS);
        if (notify) {
            executeAfterManagerRelease(() -> ScreenManager.getInstance().execute(() ->
                new Notification("World Generation", message, Notification.Type.WARN)));
        }
    }

    private void completeMetadataReconciliation(PendingWorldGenMetadataReconciliation reconciliation,
                                                boolean committed, boolean currentAtFinish, String code) {
        if (reconciliation == null) {
            return;
        }
        ProjectMetadataReconciliationResult result = new ProjectMetadataReconciliationResult(
            reconciliation.operationId, reconciliation.serverId, reconciliation.projectId,
            reconciliation.add, committed, currentAtFinish, code, reconciliation.connection,
            reconciliation.authorityEpoch);
        synchronized (this) {
            completedMetadataReconciliations.put(reconciliation.operationId,
                new CompletedMetadataReconciliation(result,
                    new WorldGenMetadataIntent(reconciliation.displayName, reconciliation.path, reconciliation.sortOrder),
                    reconciliation.canonicalHash));
            while (completedMetadataReconciliations.size() > MAX_PENDING_METADATA_RECONCILIATIONS) {
                completedMetadataReconciliations.remove(completedMetadataReconciliations.keySet().iterator().next());
            }
        }
        if (reconciliation.completion != null) {
            executeAfterManagerRelease(() -> ScreenManager.getInstance().execute(() -> {
                try {
                    reconciliation.completion.accept(result);
                } catch (RuntimeException | Error ignored) {
                }
            }));
        } else if (!committed) {
            executeAfterManagerRelease(() -> ScreenManager.getInstance().execute(() ->
                new Notification("World Generation", "Metadata Sync Required", Notification.Type.ERROR)));
        }
    }

    public ProjectDeleteSubmission deleteProject(String serverId, String projectId) {
        return deleteProject(serverId, projectId, null);
    }

    public ProjectDeleteSubmission deleteProject(String serverId, String projectId,
                                                  Consumer<ProjectDeleteSubmissionSettlement> completion) {
        return deleteProject(serverId, projectId, new WorldGenMetadataIntent(projectId, "", -1), completion);
    }

    public ProjectDeleteSubmission deleteProject(String serverId, String projectId,
                                                  WorldGenMetadataIntent metadataIntent,
                                                  Consumer<ProjectDeleteSubmissionSettlement> completion) {
        if (serverId == null || serverId.isBlank() || projectId == null || projectId.isBlank()) {
            return null;
        }
        if (!canMutateWorldGen(serverId)) {
            return null;
        }
        String normalizedServerId = connectionServerId(serverId);
        String normalizedProjectId = cleanProjectId(projectId);
        ReSyncFlowClient client = flowClient(normalizedServerId);
        long admittedEpoch = authorityEpoch(normalizedServerId);
        WorldGenUiToken uiToken = captureWorldGenUiToken(normalizedServerId, normalizedProjectId, admittedEpoch);
        synchronized (this) {
            return deleteProjectLocked(normalizedServerId, normalizedProjectId, metadataIntent, completion, client,
                admittedEpoch, uiToken);
        }
    }

    private ProjectDeleteSubmission deleteProjectLocked(String normalizedServerId, String normalizedProjectId,
                                                         WorldGenMetadataIntent metadataIntent,
                                                         Consumer<ProjectDeleteSubmissionSettlement> completion,
                                                         ReSyncFlowClient client, long admittedEpoch,
                                                         WorldGenUiToken uiToken) {
        String pendingKey = saveKey(normalizedServerId, normalizedProjectId);
        if (admittedEpoch < 1L || !isCurrentWorldGenUi(uiToken)) {
            return null;
        }
        if (pendingSaves.containsKey(pendingKey)) {
            executeWorldGenUi(uiToken, () -> new Notification("World Generation Failed", "Save Already In Progress", Notification.Type.ERROR));
            return null;
        }
        if (pendingDeletes.containsKey(pendingKey)) {
            executeWorldGenUi(uiToken, () -> new Notification("World Generation Failed", "Delete Already In Progress", Notification.Type.ERROR));
            return null;
        }
        long expectedRevision = projectStore.projectRevision(normalizedServerId, normalizedProjectId);
        String requestId = WORLD_GEN_PROJECT_DELETE_ACTION + ":" + normalizedProjectId + ":" + UUID.randomUUID();
        String mutationId = UUID.randomUUID().toString();
        ProjectDeleteSubmission submission = new ProjectDeleteSubmission(requestId, normalizedServerId, normalizedProjectId);
        if (!canAdmitPendingMutation(0L)) {
            settleProjectDeletion(completion, new ProjectDeleteSubmissionSettlement(submission,
                ProjectSubmissionState.NOT_SUBMITTED, 0L, "WORLDGEN_PROJECT_DELETE_BUSY"), uiToken);
            return null;
        }
        if (!reserveMetadataReconciliation(requestId, normalizedServerId, normalizedProjectId, false, metadataIntent)) {
            settleProjectDeletion(completion, new ProjectDeleteSubmissionSettlement(submission,
                ProjectSubmissionState.NOT_SUBMITTED, 0L, "WORLDGEN_PROJECT_DELETE_BUSY"), uiToken);
            return null;
        }
        PendingWorldGenDelete deletion = new PendingWorldGenDelete(normalizedProjectId, requestId, mutationId, requestId,
            expectedRevision, admittedEpoch, uiToken, submission, completion);
        PendingWorldGenDelete previous = pendingDeletes.putIfAbsent(pendingKey, deletion);
        if (previous != null) {
            releaseMetadataReconciliation(requestId);
            settleProjectDeletion(completion, new ProjectDeleteSubmissionSettlement(submission,
                ProjectSubmissionState.NOT_SUBMITTED, 0L, "WORLDGEN_PROJECT_DELETE_BUSY"), uiToken);
            executeWorldGenUi(uiToken, () -> new Notification("World Generation Failed", "Delete Already In Progress", Notification.Type.ERROR));
            return null;
        }
        try {
            long timeoutToken = deletion.nextTimeoutToken();
            deletion.setTimeoutTask(mutationTimeoutScheduler.schedule(
                () -> timeoutProjectDelete(normalizedServerId, normalizedProjectId, deletion, timeoutToken),
                Duration.ofSeconds(SAVE_TIMEOUT_SECONDS)));
            executeWorldGenUi(uiToken, deletion::showDeleting);
            if (client != null) {
                executeAfterManagerRelease(() -> {
                    try {
                        client.sendWorldGenProjectDelete(normalizedProjectId, requestId, mutationId, requestId,
                            expectedRevision);
                    } catch (RuntimeException | Error exception) {
                        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                            ? "Delete Failed" : exception.getMessage();
                        failProjectDelete(normalizedServerId, normalizedProjectId, requestId, mutationId, requestId,
                            message);
                    }
                });
            } else {
                if (failProjectDelete(normalizedServerId, normalizedProjectId, requestId, mutationId, requestId, "ReSync Offline")) {
                    executeAfterManagerRelease(() -> {
                        requestProject(normalizedServerId, normalizedProjectId);
                        requestProjectList(normalizedServerId);
                    });
                }
            }
        } catch (RuntimeException | Error exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Delete Failed" : exception.getMessage();
            failProjectDelete(normalizedServerId, normalizedProjectId, requestId, mutationId, requestId, message);
        }
        return submission;
    }

    public void duplicateProject(String serverId, String sourceProjectId, String targetProjectId) {
        duplicateProject(serverId, sourceProjectId, targetProjectId, true, null);
    }

    public void duplicateProject(String serverId, String sourceProjectId, String targetProjectId, boolean notify) {
        duplicateProject(serverId, sourceProjectId, targetProjectId, notify, null);
    }

    public ProjectSaveSubmission duplicateProject(String serverId, String sourceProjectId, String targetProjectId,
                                                   boolean notify, Consumer<ProjectSaveSubmissionSettlement> completion) {
        return duplicateProject(serverId, sourceProjectId, targetProjectId, notify,
            new WorldGenMetadataIntent(targetProjectId, "", -1), completion);
    }

    public ProjectSaveSubmission duplicateProject(String serverId, String sourceProjectId, String targetProjectId,
                                                   boolean notify, WorldGenMetadataIntent metadataIntent,
                                                   Consumer<ProjectSaveSubmissionSettlement> completion) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedSourceId = cleanProjectId(sourceProjectId);
        String normalizedTargetId = cleanProjectId(targetProjectId);
        if (normalizedServerId.isBlank() || normalizedSourceId.isBlank() || normalizedTargetId.isBlank()
            || normalizedSourceId.equals(normalizedTargetId) || !canMutateWorldGen(normalizedServerId)) {
            return null;
        }
        String targetKey = saveKey(normalizedServerId, normalizedTargetId);
        if (completion == null) {
            synchronized (this) {
                String sourceKey = saveKey(normalizedServerId, normalizedSourceId);
                long admittedEpoch = authorityEpoch(normalizedServerId);
                WorldGenUiToken sourceToken = captureWorldGenUiToken(normalizedServerId, normalizedSourceId, admittedEpoch);
                WorldGenUiToken targetToken = captureWorldGenUiToken(normalizedServerId, normalizedTargetId, admittedEpoch);
                String operationId = "worldGenProjectDuplicate:" + normalizedTargetId + ":" + UUID.randomUUID();
                ProjectSaveSubmission submission = new ProjectSaveSubmission(operationId, normalizedServerId,
                    normalizedTargetId);
                WorldGenMetadataIntent normalizedIntent = metadataIntent == null
                    ? new WorldGenMetadataIntent(normalizedTargetId, "", -1) : metadataIntent;
                if (projectStore.projectState(normalizedServerId, normalizedTargetId).present()
                    || pendingSaves.containsKey(targetKey) || pendingDeletes.containsKey(targetKey)
                    || pendingDuplicates.containsKey(targetKey) || silentDuplicateAdmissions.containsKey(targetKey)
                    || silentDuplicateTargetBySource.containsKey(sourceKey)
                    || admittedEpoch < 1L || !isCurrentWorldGenUi(sourceToken) || !isCurrentWorldGenUi(targetToken)
                    || !reserveMetadataReconciliation(operationId, normalizedServerId, normalizedTargetId, true,
                        normalizedIntent)
                    || !canAdmitPendingMutation(ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES)) {
                    releaseMetadataReconciliation(operationId);
                    if (notify) {
                        executeWorldGenUi(captureWorldGenUiToken(normalizedServerId, normalizedTargetId,
                            authorityEpoch(normalizedServerId)), () -> new Notification("World Generation Failed",
                            "Project Copy Busy", Notification.Type.ERROR));
                    }
                    return null;
                }
                SilentDuplicateAdmission admission = new SilentDuplicateAdmission(normalizedServerId,
                    normalizedSourceId, normalizedTargetId, ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES,
                    notify, submission, normalizedIntent, sourceToken, targetToken);
                try {
                    silentDuplicateAdmissions.put(targetKey, admission);
                    silentDuplicateTargetBySource.put(sourceKey, targetKey);
                    silentDuplicateTargets.add(targetKey);
                    projectStore.setPendingDuplicateId(normalizedServerId, normalizedSourceId, normalizedTargetId);
                    TaskScheduler.ScheduledTask timeout = mutationTimeoutScheduler.schedule(
                        () -> timeoutSilentDuplicate(admission), java.time.Duration.ofSeconds(SAVE_TIMEOUT_SECONDS));
                    TaskScheduler.ScheduledTask previousTimeout = silentDuplicateTimeouts.put(targetKey, timeout);
                    if (previousTimeout != null) {
                        previousTimeout.cancel();
                    }
                } catch (RuntimeException | Error exception) {
                    removeSilentDuplicate(targetKey, true);
                    if (notify) {
                        executeWorldGenUi(captureWorldGenUiToken(normalizedServerId, normalizedTargetId,
                            authorityEpoch(normalizedServerId)), () -> new Notification("World Generation Failed",
                            "Project Copy Failed", Notification.Type.ERROR));
                    }
                    return null;
                }
            }
            try {
                requestProject(normalizedServerId, normalizedSourceId);
            } catch (RuntimeException | Error exception) {
                synchronized (this) {
                    removeSilentDuplicate(targetKey, true);
                }
                if (notify) {
                    executeWorldGenUi(captureWorldGenUiToken(normalizedServerId, normalizedTargetId,
                        authorityEpoch(normalizedServerId)), () -> new Notification("World Generation Failed",
                        "Project Copy Failed", Notification.Type.ERROR));
                }
            }
            return null;
        }
        ProjectSaveSubmission submission = new ProjectSaveSubmission(
            "worldGenProjectDuplicate:" + normalizedTargetId + ":" + UUID.randomUUID(), normalizedServerId, normalizedTargetId);
        WorldGenUiToken targetToken;
        PendingWorldGenDuplicate duplicate = null;
        String rejectionCode = null;
        synchronized (this) {
            long admittedEpoch = authorityEpoch(normalizedServerId);
            WorldGenUiToken sourceToken = captureWorldGenUiToken(normalizedServerId, normalizedSourceId, admittedEpoch);
            targetToken = captureWorldGenUiToken(normalizedServerId, normalizedTargetId, admittedEpoch);
            if (admittedEpoch < 1L || !isCurrentWorldGenUi(sourceToken) || !isCurrentWorldGenUi(targetToken)) {
                rejectionCode = "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED";
            }
            WorldGenProjectStore.ProjectState sourceState = projectStore.projectState(normalizedServerId, normalizedSourceId);
            long retainedBytes = retainedDuplicateBytes(sourceState.canonicalContent());
            if (rejectionCode == null
                && (!sourceState.present() || retainedBytes < 1L || retainedBytes > ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES)) {
                rejectionCode = "WORLDGEN_PROJECT_SAVE_PAYLOAD_INVALID";
            }
            if (rejectionCode == null
                && (projectStore.projectState(normalizedServerId, normalizedTargetId).present()
                || pendingSaves.containsKey(targetKey) || pendingDeletes.containsKey(targetKey)
                || pendingDuplicates.containsKey(targetKey) || !canAdmitPendingDuplicate(retainedBytes)
                || !canAdmitPendingMutation(retainedBytes))) {
                rejectionCode = "WORLDGEN_PROJECT_SAVE_BUSY";
            }
            if (rejectionCode == null) {
                long sourceRevision = projectStore.projectRevision(normalizedServerId, normalizedSourceId);
                if (sourceRevision < 1L) {
                    rejectionCode = "WORLDGEN_PROJECT_SAVE_PAYLOAD_INVALID";
                }
            }
            if (rejectionCode == null) {
                long sourceRevision = projectStore.projectRevision(normalizedServerId, normalizedSourceId);
                if (!reserveMetadataReconciliation(submission.operationId(), normalizedServerId,
                    normalizedTargetId, true, metadataIntent)) {
                    rejectionCode = "WORLDGEN_PROJECT_SAVE_BUSY";
                } else {
                    duplicate = new PendingWorldGenDuplicate(normalizedServerId, normalizedSourceId,
                        normalizedTargetId, sourceRevision, sourceState.canonicalHash(), admittedEpoch, retainedBytes,
                        notify, sourceToken, targetToken, submission, completion);
                    try {
                        PendingWorldGenDuplicate previous = pendingDuplicates.putIfAbsent(targetKey, duplicate);
                        if (previous != null) {
                            duplicate = null;
                            rejectionCode = "WORLDGEN_PROJECT_SAVE_BUSY";
                            releaseMetadataReconciliation(submission.operationId());
                        } else {
                            pendingDuplicateCount++;
                            pendingDuplicateBytes += retainedBytes;
                            try {
                                pendingDuplicatesBySource.computeIfAbsent(saveKey(normalizedServerId, normalizedSourceId),
                                    ignored -> new HashMap<>()).put(targetKey, duplicate);
                            } catch (RuntimeException | Error exception) {
                                removePendingDuplicate(duplicate);
                                duplicate = null;
                                rejectionCode = "WORLDGEN_PROJECT_SAVE_BUSY";
                                releaseMetadataReconciliation(submission.operationId());
                            }
                        }
                    } catch (RuntimeException | Error exception) {
                        if (duplicate != null) {
                            pendingDuplicates.remove(targetKey, duplicate);
                            duplicate = null;
                        }
                        rejectionCode = "WORLDGEN_PROJECT_SAVE_BUSY";
                        releaseMetadataReconciliation(submission.operationId());
                    }
                }
            }
        }
        if (rejectionCode != null) {
            settleProjectSubmission(completion, new ProjectSaveSubmissionSettlement(submission, null, false,
                rejectionCode), notify, targetToken);
            return submission;
        }
        PendingWorldGenDuplicate admittedDuplicate = duplicate;
        long timeoutToken = admittedDuplicate.nextTimeoutToken();
        try {
            admittedDuplicate.setTimeoutTask(mutationTimeoutScheduler.schedule(
                () -> timeoutProjectDuplicate(normalizedServerId, normalizedTargetId, admittedDuplicate, timeoutToken),
                Duration.ofSeconds(SAVE_TIMEOUT_SECONDS)));
            requestProject(normalizedServerId, normalizedSourceId);
        } catch (RuntimeException | Error exception) {
            cancelPendingDuplicate(admittedDuplicate, "WORLDGEN_PROJECT_SAVE_FAILED");
        }
        return submission;
    }

    private long retainedDuplicateBytes(String content) {
        return content == null || content.isBlank() ? 0L : content.getBytes(StandardCharsets.UTF_8).length;
    }

    private long retainedMutationBytes(String content) {
        return retainedDuplicateBytes(content);
    }

    private boolean canAdmitPendingMutation(long retainedBytes) {
        if (retainedBytes < 0L || retainedBytes > MAX_PENDING_MUTATION_BYTES) {
            return false;
        }
        int count = 0;
        long bytes = retainedBytes;
        for (PendingWorldGenSave save : pendingSaves.values()) {
            if (save != null && !save.finished) {
                count++;
                bytes = boundedMutationBytes(bytes, save.retainedBytes);
            }
        }
        for (PendingWorldGenDelete deletion : pendingDeletes.values()) {
            if (deletion != null && !deletion.finished) {
                count++;
            }
        }
        for (PendingWorldGenDuplicate duplicate : pendingDuplicates.values()) {
            if (duplicate != null && !duplicate.finished) {
                count++;
                bytes = boundedMutationBytes(bytes, duplicate.retainedBytes);
            }
        }
        count += silentDuplicateAdmissions.size();
        for (SilentDuplicateAdmission admission : silentDuplicateAdmissions.values()) {
            if (admission != null) {
                bytes = boundedMutationBytes(bytes, admission.retainedBytes);
            }
        }
        return count < MAX_PENDING_MUTATIONS && bytes <= MAX_PENDING_MUTATION_BYTES;
    }

    private long boundedMutationBytes(long current, long additional) {
        if (current > MAX_PENDING_MUTATION_BYTES - Math.max(0L, additional)) {
            return MAX_PENDING_MUTATION_BYTES + 1L;
        }
        return current + Math.max(0L, additional);
    }

    private boolean canAdmitPendingDuplicate(long retainedBytes) {
        return pendingDuplicateCount < MAX_PENDING_DUPLICATES && retainedBytes > 0L
            && retainedBytes <= MAX_PENDING_DUPLICATE_BYTES
            && pendingDuplicateBytes <= MAX_PENDING_DUPLICATE_BYTES - retainedBytes;
    }

    private synchronized List<PendingWorldGenDuplicate> pendingDuplicatesFor(String serverId, String sourceProjectId) {
        String normalizedServerId = connectionServerId(serverId);
        String normalizedSourceId = cleanProjectId(sourceProjectId);
        Map<String, PendingWorldGenDuplicate> indexed = pendingDuplicatesBySource.get(saveKey(normalizedServerId, normalizedSourceId));
        return indexed == null || indexed.isEmpty() ? List.of() : List.copyOf(indexed.values());
    }

    private void settlePendingDuplicate(PendingWorldGenDuplicate duplicate, String code) {
        if (duplicate == null || !duplicate.settled.compareAndSet(false, true)) {
            return;
        }
        try {
            releaseMetadataReconciliation(duplicate.submission.operationId());
        } catch (RuntimeException | Error ignored) {
        }
        try {
            settleProjectSubmission(duplicate.completion, new ProjectSaveSubmissionSettlement(duplicate.submission,
                null, false, code), duplicate.notify,
                duplicate.targetToken);
        } catch (RuntimeException | Error ignored) {
        }
    }

    private synchronized boolean removePendingDuplicate(PendingWorldGenDuplicate duplicate) {
        if (duplicate == null || !pendingDuplicates.remove(saveKey(duplicate.serverId, duplicate.targetProjectId), duplicate)) {
            return false;
        }
        String sourceKey = saveKey(duplicate.serverId, duplicate.sourceProjectId);
        Map<String, PendingWorldGenDuplicate> indexed = pendingDuplicatesBySource.get(sourceKey);
        if (indexed != null) {
            indexed.remove(saveKey(duplicate.serverId, duplicate.targetProjectId), duplicate);
            if (indexed.isEmpty()) {
                pendingDuplicatesBySource.remove(sourceKey, indexed);
            }
        }
        pendingDuplicateCount = Math.max(0, pendingDuplicateCount - 1);
        pendingDuplicateBytes = Math.max(0L, pendingDuplicateBytes - duplicate.retainedBytes);
        return true;
    }

    private synchronized boolean cancelPendingDuplicate(PendingWorldGenDuplicate duplicate, String code) {
        if (!removePendingDuplicate(duplicate)) {
            return false;
        }
        duplicate.finished = true;
        duplicate.cancelTimeout();
        settlePendingDuplicate(duplicate, code);
        return true;
    }

    private synchronized int cancelPendingDuplicates(String serverId, String code) {
        String normalizedServerId = serverId == null || serverId.isBlank() ? ""
            : connectionServerId(serverId);
        List<PendingWorldGenDuplicate> duplicates = pendingDuplicates.values().stream()
            .filter(duplicate -> normalizedServerId.isBlank() || duplicate.serverId.equals(normalizedServerId))
            .toList();
        int canceled = 0;
        for (PendingWorldGenDuplicate duplicate : duplicates) {
            if (cancelPendingDuplicate(duplicate, code)) {
                canceled++;
            }
        }
        if (normalizedServerId.isBlank()) {
            pendingDuplicatesBySource.clear();
            pendingDuplicateCount = 0;
            pendingDuplicateBytes = 0L;
        }
        return canceled;
    }

    public synchronized void handleProjectData(String serverId, WorldGenProject project) {
        handleProjectData(serverId, project, 1L, 1L);
    }

    public synchronized void handleProjectData(String serverId, WorldGenProject project, long revision,
                                               long authorityEpoch) {
        long startedAt = System.nanoTime();
        FlowManager.ServerConnectionToken connectionToken = captureConnectionToken(serverId);
        String projectId = project == null ? "" : project.getId();
        boolean currentConnection = project != null && isCurrentWorldGenConnection(connectionToken, authorityEpoch);
        boolean acceptedProjection = currentConnection
            && acceptAuthoritativeProjection(serverId, projectId, revision, authorityEpoch);
        if (project == null || !currentConnection || !acceptedProjection) {
            traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "project_data", projectId, "", "",
                connectionToken == null ? -1L : connectionToken.generation(), authorityEpoch, revision, "rejected",
                project == null ? "project_missing" : !currentConnection ? "connection_or_epoch_stale"
                    : "revision_or_projection_rejected", 1, 0, "none", startedAt);
            return;
        }
        WorldGenProject activeProject = projectStore.acceptAndSetActiveProject(serverId, project, revision);
        if (activeProject == null) {
            traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "project_data", projectId, "", "",
                connectionToken == null ? -1L : connectionToken.generation(), authorityEpoch, revision, "rejected",
                "project_store_rejected", 1, 0, "none", startedAt);
            return;
        }
        traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "project_data", projectId, "", "",
            connectionToken == null ? -1L : connectionToken.generation(), authorityEpoch, revision, "applied",
            "authoritative_project_cached", 1, 1, "pending", startedAt);
        settlePendingAfterRefresh(serverId, project.getId());
        List<PendingWorldGenDuplicate> pendingDuplicateList = pendingDuplicatesFor(serverId, project.getId());
        if (!pendingDuplicateList.isEmpty()) {
            for (PendingWorldGenDuplicate pendingDuplicate : pendingDuplicateList) {
                if (!isCurrentWorldGenConnection(pendingDuplicate.sourceToken.connection(), authorityEpoch)) {
                    cancelPendingDuplicate(pendingDuplicate,
                        authorityEpoch != pendingDuplicate.admittedEpoch
                            ? "WORLDGEN_PROJECT_SAVE_AUTHORITY_CHANGED"
                            : "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED");
                    continue;
                }
                if (!pendingDuplicate.accepts(revision, authorityEpoch,
                    projectStore.projectState(pendingDuplicate.serverId, pendingDuplicate.sourceProjectId))
                    || !isCurrentWorldGenUi(pendingDuplicate.sourceToken)) {
                    cancelPendingDuplicate(pendingDuplicate, "WORLDGEN_PROJECT_SAVE_SUPERSEDED");
                    continue;
                }
                if (!removePendingDuplicate(pendingDuplicate)) {
                    continue;
                }
                pendingDuplicate.finished = true;
                pendingDuplicate.cancelTimeout();
                try {
                    WorldGenProject copy = copyProject(project);
                    if (copy == null) {
                        settlePendingDuplicate(pendingDuplicate, "WORLDGEN_PROJECT_SAVE_PAYLOAD_INVALID");
                        continue;
                    }
                    copy.setId(pendingDuplicate.targetProjectId);
                    WorldGenProjectStore.ProjectTransfer transfer = projectStore.transferProject(copy, () -> copy);
                    if (transfer == null) {
                        settlePendingDuplicate(pendingDuplicate, "WORLDGEN_PROJECT_SAVE_PAYLOAD_INVALID");
                        continue;
                    }
                    ProjectSubmissionCompletion completion = new ProjectSubmissionCompletion(pendingDuplicate.submission,
                        transfer.project(), pendingDuplicate.completion, pendingDuplicate.notify, pendingDuplicate.targetToken);
                    boolean submitted = saveWorldGen(serverId, transfer, pendingDuplicate.notify,
                        pendingDuplicate.targetToken, completion);
                    if (!submitted) {
                        try {
                            completion.settle(false, "WORLDGEN_PROJECT_SAVE_NOT_SUBMITTED");
                        } catch (RuntimeException | Error ignored) {
                            releaseMetadataReconciliation(pendingDuplicate.submission.operationId());
                        }
                    }
                } catch (RuntimeException | Error exception) {
                    settlePendingDuplicate(pendingDuplicate, "WORLDGEN_PROJECT_SAVE_FAILED");
                }
            }
            return;
        }
        String duplicateId = projectStore.removePendingDuplicateId(serverId, project.getId());
        if (duplicateId != null && !duplicateId.isBlank()) {
            String duplicateKey = saveKey(serverId, duplicateId);
            SilentDuplicateAdmission admission = consumeSilentDuplicateTarget(duplicateKey);
            boolean notify = admission != null && admission.notifyOnSave;
            ProjectSubmissionCompletion submissionCompletion = null;
            try {
                if (admission == null || !admission.targetProjectId.equals(cleanProjectId(duplicateId))
                    || !isCurrentWorldGenUi(admission.sourceToken) || !isCurrentWorldGenUi(admission.targetToken)) {
                    throw new IllegalStateException("World Generation project copy admission expired");
                }
                WorldGenProject copy = copyProject(project);
                if (copy == null) {
                    throw new IllegalArgumentException("World Generation project copy is invalid");
                }
                copy.setId(admission.targetProjectId);
                submissionCompletion = new ProjectSubmissionCompletion(admission.submission, copy, ignored -> {
                }, notify, admission.targetToken);
                WorldGenProjectStore.ProjectTransfer transfer = projectStore.transferProject(copy, () -> copy);
                if (transfer == null || !saveWorldGen(serverId, transfer, notify, admission.targetToken,
                    submissionCompletion)) {
                    submissionCompletion.settle(false, "WORLDGEN_PROJECT_SAVE_NOT_SUBMITTED");
                }
            } catch (RuntimeException | Error exception) {
                if (submissionCompletion != null) {
                    submissionCompletion.settle(false, "WORLDGEN_PROJECT_SAVE_FAILED");
                } else if (admission != null) {
                    releaseMetadataReconciliation(admission.submission.operationId());
                }
                WorldGenUiToken token = captureWorldGenUiToken(serverId, duplicateId, authorityEpoch(serverId));
                executeWorldGenUi(token, () -> new Notification("World Generation Failed", "Project Copy Failed",
                    Notification.Type.ERROR));
            }
            return;
        }
        WorldGenUiToken uiToken = captureWorldGenUiToken(serverId, project.getId(), authorityEpoch);
        executeWorldGenUi(uiToken, () -> {
            String uiTarget = "none";
            if (ScreenManager.getInstance().getCurrentScreen() instanceof WorldGenEditorScreen screen
                && sameServer(serverId, screen.getActualServerId())) {
                screen.loadProject(activeProject != null ? activeProject : project);
                uiTarget = "WorldGenEditorScreen";
            } else if (ScreenManager.getInstance().getCurrentScreen() instanceof FlowGraphDesignerScreen screen
                && sameServer(serverId, screen.getServerId())) {
                screen.loadStudioWorldGenProject(activeProject != null ? activeProject : project);
                uiTarget = "FlowGraphDesignerScreen";
            }
            traceWorldGenLifecycle(serverId, "worldgen_ui_delivery", "project_data", project.getId(), "", "",
                uiToken.connection() == null ? -1L : uiToken.connection().generation(), uiToken.authorityEpoch(),
                revision, uiTarget.equals("none") ? "dropped" : "delivered",
                uiTarget.equals("none") ? "no_matching_screen" : "project_loaded", 1, 1, uiTarget, startedAt);
        });
    }

    public synchronized void handleProjectList(String serverId, List<String> ids) {
        handleProjectList(serverId, ids, 1L, 1L);
    }

    public synchronized void handleProjectSaved(String serverId, String projectId) {
        String normalizedProjectId = cleanProjectId(projectId);
        PendingWorldGenSave save = pendingSaves.get(saveKey(serverId, normalizedProjectId));
        if (save != null) {
            completeProjectSave(serverId, normalizedProjectId, save, Math.max(1L, save.expectedRevision));
        }
        requestProjectList(serverId);
    }

    public synchronized void handleProjectList(String serverId, List<String> ids, long revision,
                                               long authorityEpoch) {
        long startedAt = System.nanoTime();
        FlowManager.ServerConnectionToken connectionToken = captureConnectionToken(serverId);
        boolean currentConnection = isCurrentWorldGenConnection(connectionToken, authorityEpoch);
        boolean acceptedProjection = currentConnection
            && acceptAuthoritativeProjection(serverId, ids, revision, authorityEpoch);
        if (!currentConnection || !acceptedProjection) {
            traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "project_list", null, "", "",
                connectionToken == null ? -1L : connectionToken.generation(), authorityEpoch, revision, "rejected",
                !currentConnection ? "connection_or_epoch_stale" : "revision_or_projection_rejected",
                ids == null ? 0 : ids.size(), 0, "none", startedAt);
            return;
        }
        if (!projectStore.acceptProjectList(serverId, ids, revision)) {
            traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "project_list", null, "", "",
                connectionToken == null ? -1L : connectionToken.generation(), authorityEpoch, revision, "rejected",
                "project_list_store_rejected", ids == null ? 0 : ids.size(), 0, "none", startedAt);
            return;
        }
        traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "project_list", null, "", "",
            connectionToken == null ? -1L : connectionToken.generation(), authorityEpoch, revision, "applied",
            "authoritative_project_list_cached", ids == null ? 0 : ids.size(), ids == null ? 0 : ids.size(),
            "pending", startedAt);
        settlePendingAfterRefresh(serverId, null);
        WorldGenUiToken uiToken = captureWorldGenUiToken(serverId, null, authorityEpoch);
        executeWorldGenUi(uiToken, () -> {
            FlowEditorScreen studioScreen = FlowEditorScreen.getStudioScreen(serverId);
            if (studioScreen != null) {
                studioScreen.refreshStudioWorkspace();
            }
            String uiTarget = studioScreen == null ? "none" : "FlowEditorScreen";
            traceWorldGenLifecycle(serverId, "worldgen_ui_delivery", "project_list", null, "", "",
                uiToken.connection() == null ? -1L : uiToken.connection().generation(), uiToken.authorityEpoch(),
                revision, studioScreen == null ? "dropped" : "delivered",
                studioScreen == null ? "no_studio_screen" : "workspace_refreshed", ids == null ? 0 : ids.size(),
                ids == null ? 0 : ids.size(), uiTarget, startedAt);
        });
    }

    public synchronized void handleProjectMutationAck(String serverId, String projectId, long revision, String requestId,
                                         String mutationId, String operationId, boolean deleted, long authorityEpoch) {
        long startedAt = System.nanoTime();
        String normalizedProjectId = cleanProjectId(projectId);
        String action = deleted ? WORLD_GEN_PROJECT_DELETE_ACTION : WORLD_GEN_PROJECT_SAVE_ACTION;
        FlowManager.ServerConnectionToken connectionToken = captureConnectionToken(serverId);
        WorldGenUiToken authorityToken = captureWorldGenUiToken(serverId, normalizedProjectId, authorityEpoch);
        boolean validRevision = revision >= 1L;
        boolean validIdentity = validRevision && hasCanonicalIdentity(action, normalizedProjectId, requestId, mutationId, operationId);
        boolean currentConnection = validIdentity && isCurrentWorldGenConnection(connectionToken, authorityEpoch);
        boolean currentUi = currentConnection && isCurrentWorldGenUi(authorityToken);
        if (!validRevision || !validIdentity || !currentConnection || !currentUi) {
            traceWorldGenLifecycle(serverId, "worldgen_cache_apply", deleted ? "delete_ack" : "save_ack",
                normalizedProjectId, requestId, mutationId, connectionToken == null ? -1L : connectionToken.generation(),
                authorityEpoch, revision, "rejected", !validRevision ? "revision_invalid" : !validIdentity
                    ? "mutation_identity_invalid" : !currentConnection ? "connection_or_epoch_stale" : "ui_fence_stale",
                1, 0, "none", startedAt);
            return;
        }
        if (deleted) {
            PendingWorldGenDelete deletion = pendingDeletes.get(saveKey(serverId, normalizedProjectId));
            boolean pendingMatch = deletion != null && deletion.matches(normalizedProjectId, requestId, mutationId, operationId);
            boolean currentRevision = pendingMatch && revision >= projectStore.projectRevision(serverId, normalizedProjectId);
            if (!pendingMatch || !currentRevision) {
                traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "delete_ack", normalizedProjectId, requestId,
                    mutationId, connectionToken.generation(), authorityEpoch, revision, "rejected",
                    deletion == null ? "pending_delete_missing" : !pendingMatch ? "mutation_identity_mismatch"
                        : "stale_revision", 1, 0, "none", startedAt);
                return;
            }
            if (deletion.awaitingAuthorityTransition) {
                traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "delete_ack", normalizedProjectId, requestId,
                    mutationId, connectionToken.generation(), authorityEpoch, revision, "deferred",
                    "authority_transition_refresh_required", 1, 0, "none", startedAt);
                settlePendingDeleteAfterTransition(serverId, normalizedProjectId, deletion,
                    "World Generation Delete Committed On An Older Authority; Refresh Required");
                refreshProjectAfterMutation(serverId, normalizedProjectId, authorityEpoch(serverId));
                return;
            }
            if (pendingDeletes.remove(saveKey(serverId, normalizedProjectId), deletion)) {
                deletion.finished = true;
                deletion.cancelTimeout();
                try {
                    projectStore.removeProject(serverId, normalizedProjectId, revision);
                } catch (RuntimeException | Error ignored) {
                }
                try {
                    deletion.settleSubmission(ProjectSubmissionState.COMMITTED, revision, "");
                } catch (RuntimeException | Error ignored) {
                }
                WorldGenUiToken currentToken = captureWorldGenUiToken(serverId, normalizedProjectId, authorityEpoch(serverId));
                try {
                    executeWorldGenUi(currentToken, () -> deletion.finish("World Generation Deleted", normalizedProjectId,
                        Notification.Type.SUCCESS));
                } catch (RuntimeException | Error ignored) {
                }
                try {
                    refreshProjectListAfterMutation(serverId, normalizedProjectId, authorityEpoch(serverId));
                } catch (RuntimeException | Error ignored) {
                }
                traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "delete_ack", normalizedProjectId, requestId,
                    mutationId, connectionToken.generation(), authorityEpoch, revision, "applied",
                    "authoritative_delete_cached", 1, 1, "WorldGenNotification", startedAt);
            }
            return;
        }
        PendingWorldGenSave save = pendingSaves.get(saveKey(serverId, normalizedProjectId));
        boolean pendingMatch = save != null && save.matches(normalizedProjectId, requestId, mutationId, operationId);
        if (!pendingMatch) {
            traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "save_ack", normalizedProjectId, requestId,
                mutationId, connectionToken.generation(), authorityEpoch, revision, "rejected",
                save == null ? "pending_save_missing" : "mutation_identity_mismatch", 1, 0, "none", startedAt);
            return;
        }
        if (revision < projectStore.projectRevision(serverId, normalizedProjectId)) {
            traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "save_ack", normalizedProjectId, requestId,
                mutationId, connectionToken.generation(), authorityEpoch, revision, "rejected", "stale_revision", 1,
                0, "none", startedAt);
            return;
        }
        if (save.awaitingAuthorityTransition) {
            traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "save_ack", normalizedProjectId, requestId,
                mutationId, connectionToken.generation(), authorityEpoch, revision, "deferred",
                "authority_transition_refresh_required", 1, 0, "none", startedAt);
            settlePendingSaveAfterTransition(serverId, normalizedProjectId, save,
                "World Generation Save Committed On An Older Authority; Refresh Required");
            refreshProjectAfterMutation(serverId, normalizedProjectId, authorityEpoch(serverId));
            return;
        }
        completeProjectSave(serverId, normalizedProjectId, save, revision);
        traceWorldGenLifecycle(serverId, "worldgen_cache_apply", "save_ack", normalizedProjectId, requestId, mutationId,
            connectionToken.generation(), authorityEpoch, revision, "applied", "authoritative_save_cached", 1, 1,
            "WorldGenNotification", startedAt);
        refreshProjectListAfterMutation(serverId, normalizedProjectId, authorityEpoch(serverId));
    }

    public synchronized void handleWorldGenMutationStatus(String serverId, String action, String projectId, String requestId,
                                               String mutationId, String operationId, String status, String message,
                                               long responseRevision, long responseEpoch) {
        String canonicalAction = canonicalWorldGenAction(action);
        FlowManager.ServerConnectionToken connectionToken = captureConnectionToken(serverId);
        String normalizedProjectId = cleanProjectId(projectId);
        WorldGenUiToken authorityToken = captureWorldGenUiToken(serverId, normalizedProjectId, responseEpoch);
        if (!hasCanonicalIdentity(canonicalAction, projectId, requestId, mutationId, operationId)
            || responseRevision < 1L || !isCurrentWorldGenConnection(connectionToken, responseEpoch)
            || !isCurrentWorldGenUi(authorityToken)) {
            return;
        }
        if (status == null || status.isBlank() || "in_progress".equalsIgnoreCase(status)) {
            return;
        }
        if ("authority_transition".equalsIgnoreCase(status)
            || "committed_old_authority".equalsIgnoreCase(status)) {
            settleAuthorityTransition(serverId, canonicalAction, normalizedProjectId, requestId, mutationId, operationId,
                message);
            refreshProjectAfterMutation(serverId, normalizedProjectId, authorityEpoch(serverId));
            return;
        }
        if (WORLD_GEN_PROJECT_DELETE_ACTION.equals(canonicalAction)) {
            if (failProjectDelete(serverId, normalizedProjectId, requestId, mutationId, operationId, message)) {
                refreshProjectAfterMutation(serverId, normalizedProjectId, authorityEpoch(serverId));
            }
            return;
        }
        if (WORLD_GEN_PROJECT_SAVE_ACTION.equals(canonicalAction)) {
            if (failProjectSave(serverId, normalizedProjectId, requestId, mutationId, operationId, message)) {
                refreshProjectAfterMutation(serverId, normalizedProjectId, authorityEpoch(serverId));
            }
        }
    }

    public enum EpochOutcome {
        CURRENT,
        ADVANCED,
        STALE,
        INVALID
    }

    public record EpochDecision(EpochOutcome outcome, long previousEpoch, long authorityEpoch) {
        public boolean accepted() {
            return outcome == EpochOutcome.CURRENT || outcome == EpochOutcome.ADVANCED;
        }

        public boolean advanced() {
            return outcome == EpochOutcome.ADVANCED;
        }

        public boolean stale() {
            return outcome == EpochOutcome.STALE;
        }
    }

    public record RegistryRevision(long authorityEpoch, long revision) {
        public RegistryRevision {
            if (authorityEpoch < 1L || revision < 1L) {
                throw new IllegalArgumentException("World Generation registry revision must be positive");
            }
        }
    }

    public void handleCompileDiagnostics(String serverId, String json) {
        handleCompileDiagnostics(serverId, json, "", "", "", "", 1L, 1L);
    }

    public void handleCompileDiagnostics(String serverId, String json, String projectId, String requestId,
                                         String mutationId, String operationId, long responseRevision,
                                         long responseEpoch) {
        FlowManager.ServerConnectionToken connectionToken = captureConnectionToken(serverId);
        if (json == null || json.isBlank() || responseRevision < 1L
            || !isCurrentWorldGenConnection(connectionToken, responseEpoch)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean success = root.has("success") && root.get("success").getAsBoolean();
            JsonArray diagnostics = root.has("diagnostics") && root.get("diagnostics").isJsonArray() ? root.getAsJsonArray("diagnostics") : new JsonArray();
            if (!success) {
                String action = root.has("action") ? root.get("action").getAsString() : "";
                String normalizedProjectId = cleanProjectId(projectId);
                if (!hasCanonicalIdentity(canonicalWorldGenAction(action), normalizedProjectId, requestId, mutationId, operationId)) {
                    return;
                }
                WorldGenUiToken authorityToken = captureWorldGenUiToken(serverId, normalizedProjectId, responseEpoch);
                if (!isCurrentWorldGenUi(authorityToken)) {
                    return;
                }
                String message = firstDiagnostic(diagnostics, "Compile Failed");
                runWorldGenNow(authorityToken, () -> failProjectMutationFromIdentity(serverId, action, normalizedProjectId,
                    requestId, mutationId, operationId, message));
                return;
            }
        } catch (Exception ignored) {
            return;
        }
    }

    public boolean failProjectSaveFromRequestId(String serverId, String requestId, String message) {
        String deleteProjectId = projectIdFromRequestId(requestId, WORLD_GEN_PROJECT_DELETE_ACTION);
        String deleteMutationId = mutationIdFromRequestId(requestId, WORLD_GEN_PROJECT_DELETE_ACTION);
        if (!deleteProjectId.isBlank() && !deleteMutationId.isBlank()
            && failProjectMutationFromIdentity(serverId, WORLD_GEN_PROJECT_DELETE_ACTION, deleteProjectId, requestId,
                deleteMutationId, requestId, message)) {
            return true;
        }
        String saveProjectId = projectIdFromRequestId(requestId, WORLD_GEN_PROJECT_SAVE_ACTION);
        String saveMutationId = mutationIdFromRequestId(requestId, WORLD_GEN_PROJECT_SAVE_ACTION);
        return !saveProjectId.isBlank() && !saveMutationId.isBlank()
            && failProjectMutationFromIdentity(serverId, WORLD_GEN_PROJECT_SAVE_ACTION, saveProjectId, requestId,
                saveMutationId, requestId, message);
    }

    public synchronized boolean failProjectMutationFromIdentity(String serverId, String action, String projectId, String requestId,
                                                   String mutationId, String operationId, String message) {
        String canonicalAction = canonicalWorldGenAction(action);
        String normalizedProjectId = cleanProjectId(projectId);
        WorldGenUiToken authorityToken = captureWorldGenUiToken(serverId, normalizedProjectId, authorityEpoch(serverId));
        if (!hasCanonicalIdentity(canonicalAction, normalizedProjectId, requestId, mutationId, operationId)
            || !isCurrentWorldGenUi(authorityToken)) {
            return false;
        }
        boolean failed = WORLD_GEN_PROJECT_DELETE_ACTION.equals(canonicalAction)
            ? failProjectDelete(serverId, normalizedProjectId, requestId, mutationId, operationId, message)
            : WORLD_GEN_PROJECT_SAVE_ACTION.equals(canonicalAction)
                && failProjectSave(serverId, normalizedProjectId, requestId, mutationId, operationId, message);
        if (failed) {
            refreshProjectAfterMutation(serverId, normalizedProjectId, authorityEpoch(serverId));
        }
        return failed;
    }

    public boolean failProjectSaveFromIdentity(String serverId, String projectId, String requestId, String mutationId,
                                               String operationId, String message) {
        if (projectId == null || projectId.isBlank() || requestId == null || requestId.isBlank()
            || mutationId == null || mutationId.isBlank() || operationId == null || operationId.isBlank()) {
            return false;
        }
        return failProjectMutationFromIdentity(serverId, WORLD_GEN_PROJECT_SAVE_ACTION, cleanProjectId(projectId), requestId,
            mutationId, operationId, message);
    }

    private synchronized void completeProjectSave(String serverId, String projectId, PendingWorldGenSave save,
                                                  long authoritativeRevision) {
        if (save == null || !pendingSaves.remove(saveKey(serverId, projectId), save)) {
            return;
        }
        save.finished = true;
        save.cancelTimeout();
        try {
            projectStore.promoteDraftProject(serverId, save.draftTransfer, authoritativeRevision);
            save.settleCommitted();
            executeWorldGenMutationUi(() -> save.finish("World Generation Saved", projectId, Notification.Type.SUCCESS));
        } catch (RuntimeException | Error exception) {
            try {
                projectStore.setDraftProject(serverId, save.draftTransfer);
            } catch (RuntimeException | Error ignored) {
            }
            try {
                save.settleSubmission(false, "WORLDGEN_PROJECT_SAVE_FAILED");
            } catch (RuntimeException | Error ignored) {
            }
            try {
                finishSaveFailure(save, "Save Failed");
            } catch (RuntimeException | Error ignored) {
            }
        }
    }

    private void markPendingAuthorityTransition(String serverId, long currentEpoch) {
        if (currentEpoch < 1L) {
            return;
        }
        String prefix = WorldGenCatalogProjection.normalizeBaseServerId(serverId) + ":";
        pendingSaves.forEach((key, save) -> {
            if (key.startsWith(prefix)) {
                save.markAuthorityTransition(currentEpoch);
            }
        });
        pendingDeletes.forEach((key, deletion) -> {
            if (key.startsWith(prefix)) {
                deletion.markAuthorityTransition(currentEpoch);
            }
        });
        cancelSilentDuplicateTimeouts(serverId);
        cancelPendingDuplicates(serverId, "WORLDGEN_PROJECT_SAVE_AUTHORITY_CHANGED");
    }

    private boolean acceptAuthoritativeProjection(String serverId, String projectId, long revision,
                                                  long authorityEpoch) {
        return serverId != null && !serverId.isBlank() && projectId != null && !projectId.isBlank()
            && revision > 0L && authorityEpoch > 0L && authorityEpoch == authorityEpoch(serverId);
    }

    private boolean acceptAuthoritativeProjection(String serverId, List<String> ids, long revision,
                                                  long authorityEpoch) {
        return serverId != null && !serverId.isBlank() && ids != null && ids.stream().allMatch(id -> id != null && !id.isBlank())
            && revision > 0L && authorityEpoch > 0L && authorityEpoch == authorityEpoch(serverId);
    }

    private void establishPendingAuthorityEpoch(String serverId, long currentEpoch) {
        if (currentEpoch < 1L) {
            return;
        }
        String prefix = WorldGenCatalogProjection.normalizeBaseServerId(serverId) + ":";
        pendingSaves.forEach((key, save) -> {
            if (key.startsWith(prefix)) {
                save.establishAuthorityEpoch(currentEpoch);
            }
        });
        pendingDeletes.forEach((key, deletion) -> {
            if (key.startsWith(prefix)) {
                deletion.establishAuthorityEpoch(currentEpoch);
            }
        });
    }

    private synchronized void settlePendingAfterRefresh(String serverId, String projectId) {
        long currentEpoch = authorityEpoch(serverId);
        if (currentEpoch < 1L) {
            return;
        }
        String normalizedProjectId = projectId == null ? "" : cleanProjectId(projectId);
        String prefix = WorldGenCatalogProjection.normalizeBaseServerId(serverId) + ":";
        pendingSaves.forEach((key, save) -> {
            if (key.startsWith(prefix) && (normalizedProjectId.isBlank() || save.projectId.equals(normalizedProjectId))
                && save.awaitingAuthorityTransition && save.admittedEpoch < currentEpoch) {
                if (settlePendingSaveAfterTransition(serverId, save.projectId, save,
                    "World Generation Save Result Requires Refresh")) {
                    return;
                }
            }
        });
        pendingDeletes.forEach((key, deletion) -> {
            if (key.startsWith(prefix) && (normalizedProjectId.isBlank() || deletion.projectId.equals(normalizedProjectId))
                && deletion.awaitingAuthorityTransition && deletion.admittedEpoch < currentEpoch) {
                settlePendingDeleteAfterTransition(serverId, deletion.projectId, deletion,
                    "World Generation Delete Result Requires Refresh");
            }
        });
    }

    private synchronized boolean settleAuthorityTransition(String serverId, String action, String projectId,
                                                             String requestId, String mutationId, String operationId,
                                                             String message) {
        return settleAuthorityTransition(serverId, new WorldGenMutationIdentity(action, cleanProjectId(projectId),
            cleanProjectId(projectId), requestId, mutationId, operationId), message);
    }

    private synchronized boolean settleAuthorityTransition(String serverId, WorldGenMutationIdentity identity,
                                                             String message) {
        return settleAuthorityTransition(serverId, identity, message, true);
    }

    private synchronized boolean settleAuthorityTransition(String serverId, WorldGenMutationIdentity identity,
                                                             String message, boolean notify) {
        String text = message == null || message.isBlank() ? "Committed On An Older Authority; Refresh Required" : message;
        if (identity == null || !validMutationIdentity(identity)) {
            return false;
        }
        if (WORLD_GEN_PROJECT_DELETE_ACTION.equals(identity.action())) {
            PendingWorldGenDelete deletion = pendingDeletes.get(saveKey(serverId, identity.projectId()));
            return deletion != null && deletion.matches(identity)
                && settlePendingDeleteAfterTransition(serverId, identity.projectId(), deletion, text, notify);
        }
        PendingWorldGenSave save = pendingSaves.get(saveKey(serverId, identity.projectId()));
        return save != null && save.matches(identity)
            && settlePendingSaveAfterTransition(serverId, identity.projectId(), save, text, notify);
    }

    private synchronized boolean settlePendingSaveAfterTransition(String serverId, String projectId, PendingWorldGenSave save,
                                                      String message) {
        return settlePendingSaveAfterTransition(serverId, projectId, save, message, true);
    }

    private synchronized boolean settlePendingSaveAfterTransition(String serverId, String projectId, PendingWorldGenSave save,
                                                      String message, boolean notify) {
        if (save == null || !pendingSaves.remove(saveKey(serverId, projectId), save)) {
            return false;
        }
        save.finished = true;
        save.cancelTimeout();
        try {
            projectStore.setDraftProject(serverId, save.draftTransfer);
        } catch (RuntimeException | Error ignored) {
        }
        String text = message == null || message.isBlank() ? "Committed On An Older Authority; Refresh Required" : message;
        try {
            save.settleSubmission(false, "WORLDGEN_PROJECT_SAVE_AUTHORITY_CHANGED");
        } catch (RuntimeException | Error ignored) {
        }
        if (notify) {
            try {
                executeWorldGenMutationUi(() -> save.finish("World Generation Requires Refresh", text, Notification.Type.INFO));
            } catch (RuntimeException | Error ignored) {
            }
        }
        return true;
    }

    private synchronized boolean settlePendingDeleteAfterTransition(String serverId, String projectId, PendingWorldGenDelete deletion,
                                                         String message) {
        return settlePendingDeleteAfterTransition(serverId, projectId, deletion, message, true);
    }

    private synchronized boolean settlePendingDeleteAfterTransition(String serverId, String projectId, PendingWorldGenDelete deletion,
                                                         String message, boolean notify) {
        if (deletion == null || !pendingDeletes.remove(saveKey(serverId, projectId), deletion)) {
            return false;
        }
        deletion.finished = true;
        deletion.cancelTimeout();
        String text = message == null || message.isBlank() ? "Committed On An Older Authority; Refresh Required" : message;
        try {
            deletion.settleSubmission(ProjectSubmissionState.NOT_SUBMITTED, 0L,
                "WORLDGEN_PROJECT_DELETE_AUTHORITY_CHANGED");
        } catch (RuntimeException | Error ignored) {
        }
        if (notify) {
            try {
                executeWorldGenMutationUi(() -> deletion.finish("World Generation Requires Refresh", text, Notification.Type.INFO));
            } catch (RuntimeException | Error ignored) {
            }
        }
        return true;
    }

    private synchronized boolean failProjectSave(String serverId, String projectId, String message) {
        PendingWorldGenSave save = pendingSaves.remove(saveKey(serverId, projectId));
        if (save == null) {
            return false;
        }
        save.finished = true;
        save.cancelTimeout();
        try {
            projectStore.setDraftProject(serverId, save.draftTransfer);
        } catch (RuntimeException | Error ignored) {
        }
        String text = message == null || message.isBlank() ? "Save Failed" : message;
        try {
            save.settleSubmission(false, "WORLDGEN_PROJECT_SAVE_FAILED");
        } catch (RuntimeException | Error ignored) {
        }
        try {
            finishSaveFailure(save, text);
        } catch (RuntimeException | Error ignored) {
        }
        try {
            executeAfterManagerRelease(() -> runWorldGenConnectionNow(save.uiToken, () -> {
                requestProject(serverId, projectId);
                requestProjectList(serverId);
            }));
        } catch (RuntimeException | Error ignored) {
        }
        return true;
    }

    private synchronized boolean failProjectSave(String serverId, String projectId, String requestId, String mutationId,
                                    String operationId, String message) {
        PendingWorldGenSave save = pendingSaves.get(saveKey(serverId, projectId));
        if (save == null || !save.matches(projectId, requestId, mutationId, operationId)) {
            return false;
        }
        return failProjectSave(serverId, projectId, message);
    }

    private synchronized boolean failProjectDelete(String serverId, String projectId, String requestId, String mutationId,
                                      String operationId, String message) {
        String normalizedProjectId = cleanProjectId(projectId);
        PendingWorldGenDelete deletion = pendingDeletes.get(saveKey(serverId, normalizedProjectId));
        if (deletion == null || !deletion.matches(normalizedProjectId, requestId, mutationId, operationId)
            || !pendingDeletes.remove(saveKey(serverId, normalizedProjectId), deletion)) {
            return false;
        }
        deletion.finished = true;
        deletion.cancelTimeout();
        String text = message == null || message.isBlank() ? "Delete Failed" : message;
        try {
            deletion.settleSubmission(ProjectSubmissionState.NOT_SUBMITTED, 0L, "WORLDGEN_PROJECT_DELETE_FAILED");
        } catch (RuntimeException | Error ignored) {
        }
        try {
            finishDeleteFailure(deletion, text);
        } catch (RuntimeException | Error ignored) {
        }
        return true;
    }

    private synchronized void cancelPendingSaves(String serverId, String message) {
        String prefix = WorldGenCatalogProjection.normalizeBaseServerId(serverId) + ":";
        for (String key : new ArrayList<>(pendingSaves.keySet())) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            PendingWorldGenSave save = pendingSaves.remove(key);
            if (save != null) {
                save.finished = true;
                save.cancelTimeout();
                try {
                    projectStore.setDraftProject(serverId, save.draftTransfer);
                } catch (RuntimeException | Error ignored) {
                }
                try {
                    save.settleSubmission(false, "WORLDGEN_PROJECT_SAVE_CONNECTION_CHANGED");
                } catch (RuntimeException | Error ignored) {
                }
                try {
                    finishSaveFailure(save, message);
                } catch (RuntimeException | Error ignored) {
                }
            }
        }
    }

    private synchronized void cancelPendingDeletes(String serverId, String message) {
        String prefix = WorldGenCatalogProjection.normalizeBaseServerId(serverId) + ":";
        for (String key : new ArrayList<>(pendingDeletes.keySet())) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            PendingWorldGenDelete deletion = pendingDeletes.remove(key);
            if (deletion != null) {
                deletion.finished = true;
                deletion.cancelTimeout();
                try {
                    deletion.settleSubmission(ProjectSubmissionState.NOT_SUBMITTED, 0L,
                        "WORLDGEN_PROJECT_DELETE_CONNECTION_CHANGED");
                } catch (RuntimeException | Error ignored) {
                }
                try {
                    finishDeleteFailure(deletion, message);
                } catch (RuntimeException | Error ignored) {
                }
            }
        }
    }

    private void finishSaveFailure(PendingWorldGenSave save, String message) {
        Notification.Type type = save.awaitingAuthorityTransition ? Notification.Type.INFO : Notification.Type.ERROR;
        String title = save.awaitingAuthorityTransition ? "World Generation Requires Refresh" : "World Generation Failed";
        executeWorldGenMutationUi(() -> save.finish(title, message, type));
    }

    private void finishDeleteFailure(PendingWorldGenDelete deletion, String message) {
        Notification.Type type = deletion.awaitingAuthorityTransition ? Notification.Type.INFO : Notification.Type.ERROR;
        String title = deletion.awaitingAuthorityTransition ? "World Generation Requires Refresh" : "World Generation Delete Failed";
        executeWorldGenMutationUi(() -> deletion.finish(title, message, type));
    }

    private String projectIdFromRequestId(String requestId) {
        return projectIdFromRequestId(requestId, WORLD_GEN_PROJECT_SAVE_ACTION);
    }

    private String projectIdFromRequestId(String requestId, String action) {
        if (requestId == null || requestId.isBlank()) {
            return "";
        }
        String[] parts = requestId.split(":", 4);
        if (parts.length == 3 && action.equals(parts[0])) {
            return parts[1];
        }
        if (parts.length >= 3 && action.equals(parts[1])) {
            return parts[2];
        }
        return "";
    }

    private String mutationIdFromRequestId(String requestId) {
        return mutationIdFromRequestId(requestId, WORLD_GEN_PROJECT_SAVE_ACTION);
    }

    private String mutationIdFromRequestId(String requestId, String action) {
        if (requestId == null || requestId.isBlank()) {
            return "";
        }
        String[] parts = requestId.split(":", 4);
        if (parts.length == 3 && action.equals(parts[0])) {
            try {
                UUID mutationId = UUID.fromString(parts[2]);
                return mutationId.toString().equals(parts[2]) ? parts[2] : "";
            } catch (IllegalArgumentException ignored) {
                return "";
            }
        }
        if (parts.length >= 4 && action.equals(parts[1])) {
            try {
                UUID mutationId = UUID.fromString(parts[3]);
                return mutationId.toString().equals(parts[3]) ? parts[3] : "";
            } catch (IllegalArgumentException ignored) {
                return "";
            }
        }
        return "";
    }

    private String cleanProjectId(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            JsonElement element = JsonParser.parseString(value);
            if (element != null && element.isJsonPrimitive()) {
                return element.getAsString();
            }
        } catch (Exception ignored) {
        }
        return value.replace("\"", "").trim();
    }

    private static String canonicalWorldGenResourcePath(String projectId, String path) {
        String normalizedProjectId = projectId == null ? "" : projectId.trim();
        String normalizedPath = ReSyncProjectMetadata.normalizePath(path);
        if (normalizedProjectId.isBlank()) {
            return normalizedPath;
        }
        String folder = normalizedPath;
        if (folder.toLowerCase(Locale.ROOT).endsWith(".json")) {
            int separator = folder.lastIndexOf('/');
            folder = separator < 0 ? "" : folder.substring(0, separator);
        }
        if (folder.isBlank()) {
            folder = ReSyncResourceType.defaultFolderFor(ReSyncResourceDragPayload.WORLDGEN);
        }
        return ReSyncProjectMetadata.normalizePath(folder + "/" + normalizedProjectId + ".json");
    }

    private static String canonicalWorldGenAction(String action) {
        return switch (action == null ? "" : action) {
            case "saveWorldGenProject", WORLD_GEN_PROJECT_SAVE_ACTION -> WORLD_GEN_PROJECT_SAVE_ACTION;
            case "deleteWorldGenProject", WORLD_GEN_PROJECT_DELETE_ACTION -> WORLD_GEN_PROJECT_DELETE_ACTION;
            default -> action == null ? "" : action;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean identityMatches(String expected, String actual) {
        return expected != null && !expected.isBlank() && actual != null && !actual.isBlank()
            && Objects.equals(expected, actual);
    }

    private boolean hasIdentity(String projectId, String requestId, String mutationId, String operationId) {
        return projectId != null && !projectId.isBlank() && requestId != null && !requestId.isBlank()
            && mutationId != null && !mutationId.isBlank() && operationId != null && !operationId.isBlank();
    }

    private boolean hasCanonicalIdentity(String action, String projectId, String requestId, String mutationId,
                                         String operationId) {
        return (WORLD_GEN_PROJECT_SAVE_ACTION.equals(action) || WORLD_GEN_PROJECT_DELETE_ACTION.equals(action))
            && hasIdentity(projectId, requestId, mutationId, operationId);
    }

    private String saveKey(String serverId, String projectId) {
        return WorldGenCatalogProjection.normalizeBaseServerId(serverId) + ":" + (projectId == null ? "" : projectId);
    }

    private synchronized void timeoutProjectSave(String serverId, String projectId, PendingWorldGenSave save, long timeoutToken) {
        if (save != null && !save.finished && save.timeoutToken == timeoutToken && pendingSaves.remove(saveKey(serverId, projectId), save)) {
            save.finished = true;
            save.cancelTimeout();
            try {
                projectStore.setDraftProject(serverId, save.draftTransfer);
            } catch (RuntimeException | Error ignored) {
            }
            Notification.Type type = save.awaitingAuthorityTransition ? Notification.Type.INFO : Notification.Type.ERROR;
            String title = save.awaitingAuthorityTransition ? "World Generation Requires Refresh" : "World Generation Failed";
            String message = save.awaitingAuthorityTransition ? "Save Result Requires Refresh" : "Save Timed Out";
            try {
                save.settleSubmission(false, save.awaitingAuthorityTransition
                    ? "WORLDGEN_PROJECT_SAVE_AUTHORITY_CHANGED" : "WORLDGEN_PROJECT_SAVE_TIMEOUT");
            } catch (RuntimeException | Error ignored) {
            }
            try {
                executeWorldGenMutationUi(() -> save.finish(title, message, type));
            } catch (RuntimeException | Error ignored) {
            }
            try {
                executeAfterManagerRelease(() -> runWorldGenConnectionNow(save.uiToken, () -> {
                    requestProject(serverId, projectId);
                    requestProjectList(serverId);
                }));
            } catch (RuntimeException | Error ignored) {
            }
        }
    }

    private synchronized void timeoutProjectDelete(String serverId, String projectId, PendingWorldGenDelete deletion, long timeoutToken) {
        if (deletion != null && !deletion.finished && deletion.timeoutToken == timeoutToken
            && pendingDeletes.remove(saveKey(serverId, projectId), deletion)) {
            deletion.finished = true;
            deletion.cancelTimeout();
            Notification.Type type = deletion.awaitingAuthorityTransition ? Notification.Type.INFO : Notification.Type.ERROR;
            String title = deletion.awaitingAuthorityTransition ? "World Generation Requires Refresh" : "World Generation Delete Failed";
            String message = deletion.awaitingAuthorityTransition ? "Delete Result Requires Refresh" : "Delete Timed Out";
            try {
                deletion.settleSubmission(ProjectSubmissionState.NOT_SUBMITTED, 0L, deletion.awaitingAuthorityTransition
                    ? "WORLDGEN_PROJECT_DELETE_AUTHORITY_CHANGED" : "WORLDGEN_PROJECT_DELETE_TIMEOUT");
            } catch (RuntimeException | Error ignored) {
            }
            try {
                executeWorldGenMutationUi(() -> deletion.finish(title, message, type));
            } catch (RuntimeException | Error ignored) {
            }
            try {
                executeAfterManagerRelease(() -> runWorldGenConnectionNow(deletion.uiToken, () -> {
                    requestProject(serverId, projectId);
                    requestProjectList(serverId);
                }));
            } catch (RuntimeException | Error ignored) {
            }
        }
    }

    private synchronized void timeoutProjectDuplicate(String serverId, String projectId,
                                                       PendingWorldGenDuplicate duplicate, long timeoutToken) {
        if (duplicate != null && !duplicate.finished && duplicate.timeoutToken == timeoutToken
            && removePendingDuplicate(duplicate)) {
            duplicate.finished = true;
            duplicate.cancelTimeout();
            settlePendingDuplicate(duplicate, "WORLDGEN_PROJECT_SAVE_TIMEOUT");
        }
    }

    private interface PendingWorldGenMutation {
        boolean awaitingAuthorityTransition();

        boolean markAuthorityTransition(long currentEpoch);

        PendingAuthorityState authorityState();

        void restoreAuthorityState(PendingAuthorityState state);
    }

    private record SilentDuplicateAdmission(String serverId, String sourceProjectId, String targetProjectId,
                                             long retainedBytes, boolean notifyOnSave, ProjectSaveSubmission submission,
                                             WorldGenMetadataIntent metadataIntent, WorldGenUiToken sourceToken,
                                             WorldGenUiToken targetToken) {
        private String sourceKey() {
            return WorldGenCatalogProjection.normalizeBaseServerId(serverId) + ":" + sourceProjectId;
        }
    }

    private record PendingAuthorityState(boolean awaitingAuthorityTransition, long admittedEpoch, boolean finished) {
    }

    private static final class PendingWorldGenSave implements PendingWorldGenMutation {
        private final String projectId;
        private final String requestId;
        private final String mutationId;
        private final String operationId;
        private final long expectedRevision;
        private final WorldGenProjectStore.ProjectTransfer draftTransfer;
        private final long retainedBytes;
        private final boolean notify;
        private final WorldGenUiToken uiToken;
        private final ProjectSubmissionCompletion submissionCompletion;
        private volatile long admittedEpoch;
        private Notification notification;
        private volatile boolean finished;
        private volatile boolean awaitingAuthorityTransition;
        private long timeoutToken;
        private volatile TaskScheduler.ScheduledTask timeoutTask;

        private PendingWorldGenSave(String projectId, String requestId, String mutationId, String operationId, long expectedRevision,
                                    long admittedEpoch, WorldGenProjectStore.ProjectTransfer draftTransfer, long retainedBytes,
                                    boolean notify,
                                    WorldGenUiToken uiToken, ProjectSubmissionCompletion submissionCompletion) {
            this.projectId = projectId;
            this.requestId = requestId;
            this.mutationId = mutationId;
            this.operationId = operationId;
            this.expectedRevision = expectedRevision;
            this.draftTransfer = Objects.requireNonNull(draftTransfer, "World Generation draft is required");
            this.retainedBytes = retainedBytes;
            this.notify = notify;
            this.uiToken = Objects.requireNonNull(uiToken, "World Generation UI token is required");
            this.submissionCompletion = submissionCompletion;
            this.admittedEpoch = admittedEpoch;
        }

        private void settleSubmission(boolean submitted, String code) {
            if (submissionCompletion != null) {
                submissionCompletion.settle(submitted, code);
            }
        }

        private void settleCommitted() {
            if (submissionCompletion != null) {
                submissionCompletion.settleCommitted();
            }
        }

        private boolean matches(String projectId, String requestId, String mutationId, String operationId) {
            return Objects.equals(this.projectId, projectId) && identityMatches(this.requestId, requestId)
                && identityMatches(this.mutationId, mutationId) && identityMatches(this.operationId, operationId);
        }

        private boolean matches(WorldGenMutationIdentity identity) {
            return identity != null && WORLD_GEN_PROJECT_SAVE_ACTION.equals(identity.action())
                && Objects.equals(this.projectId, identity.projectId()) && Objects.equals(this.projectId, identity.resourceId())
                && matches(identity.projectId(), identity.requestId(), identity.mutationId(), identity.operationId());
        }

        private WorldGenMutationIdentity identity() {
            return new WorldGenMutationIdentity(WORLD_GEN_PROJECT_SAVE_ACTION, projectId, projectId, requestId,
                mutationId, operationId);
        }

        public boolean markAuthorityTransition(long currentEpoch) {
            if (currentEpoch > admittedEpoch) {
                awaitingAuthorityTransition = true;
                return true;
            }
            return false;
        }

        @Override
        public boolean awaitingAuthorityTransition() {
            return awaitingAuthorityTransition;
        }

        @Override
        public PendingAuthorityState authorityState() {
            return new PendingAuthorityState(awaitingAuthorityTransition, admittedEpoch, finished);
        }

        @Override
        public void restoreAuthorityState(PendingAuthorityState state) {
            if (state == null) {
                return;
            }
            awaitingAuthorityTransition = state.awaitingAuthorityTransition();
            admittedEpoch = state.admittedEpoch();
            finished = state.finished();
        }

        private void establishAuthorityEpoch(long currentEpoch) {
            if (admittedEpoch < 1L) {
                admittedEpoch = currentEpoch;
            }
        }

        private long nextTimeoutToken() {
            return ++timeoutToken;
        }

        private void setTimeoutTask(TaskScheduler.ScheduledTask task) {
            TaskScheduler.ScheduledTask previous = timeoutTask;
            timeoutTask = task;
            if (previous != null) {
                previous.cancel();
            }
        }

        private void cancelTimeout() {
            TaskScheduler.ScheduledTask task = timeoutTask;
            timeoutTask = null;
            if (task != null) {
                task.cancel();
            }
        }

        private void showSaving() {
            if (notification == null) {
                notification = new Notification.Builder()
                    .message("Saving World Generation")
                    .description(projectId)
                    .type(Notification.Type.INFO)
                    .loading(true)
                    .autoSlideOut(false)
                    .build();
                return;
            }
            notification.update()
                .message("Saving World Generation")
                .description(projectId)
                .type(Notification.Type.INFO)
                .loading(true)
                .autoSlideOut(false);
        }

        private void finish(String title, String description, Notification.Type type) {
            if (!notify) {
                return;
            }
            if (notification == null) {
                notification = new Notification.Builder()
                    .message(title)
                    .description(description)
                    .type(type)
                    .build();
                return;
            }
            notification.change(title, description, type, null);
        }
    }

    private final class DeferredWorldGenSave {
        private final ProjectSaveSubmission submission;
        private final boolean notify;
        private final Supplier<WorldGenProject> materializer;
        private final WorldGenMetadataIntent metadataIntent;
        private final Consumer<ProjectSaveSubmissionSettlement> completion;
        private int attempts;
        private boolean finished;
        private TaskScheduler.ScheduledTask retryTask;

        private DeferredWorldGenSave(ProjectSaveSubmission submission, boolean notify,
                                     Supplier<WorldGenProject> materializer, WorldGenMetadataIntent metadataIntent,
                                     Consumer<ProjectSaveSubmissionSettlement> completion) {
            this.submission = Objects.requireNonNull(submission, "World Generation deferred save submission is required");
            this.notify = notify;
            this.materializer = Objects.requireNonNull(materializer, "World Generation deferred save materializer is required");
            this.metadataIntent = Objects.requireNonNull(metadataIntent, "World Generation deferred metadata is required");
            this.completion = Objects.requireNonNull(completion, "World Generation deferred save completion is required");
        }

        private String key() {
            return saveKey(submission.serverId(), submission.projectId());
        }

        private void cancelRetry() {
            TaskScheduler.ScheduledTask task = retryTask;
            retryTask = null;
            if (task != null) {
                task.cancel();
            }
        }
    }

    private String firstDiagnostic(JsonArray diagnostics, String fallback) {
        for (JsonElement element : diagnostics) {
            if (element != null && element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("message")) {
                    String message = object.get("message").getAsString();
                    if (message != null && !message.isBlank()) {
                        return message;
                    }
                }
            }
        }
        return fallback;
    }

    private String firstWarning(JsonArray diagnostics) {
        for (JsonElement element : diagnostics) {
            if (element != null && element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                String severity = object.has("severity") ? object.get("severity").getAsString() : "";
                if ("warning".equalsIgnoreCase(severity) && object.has("message")) {
                    return object.get("message").getAsString();
                }
            }
        }
        return "";
    }

    public List<String> getProjectIds(String serverId) {
        return projectStore.getProjectIds(serverId);
    }

    public void requestRegistry(String serverId) {
        String normalizedServerId = connectionServerId(serverId);
        ReSyncFlowClient client = flowClient(serverId);
        if (client == null) {
            handleRegistryUpdated(normalizedServerId);
            return;
        }
        switch (client.catalogAuthority()) {
            case LEGACY_COMPATIBILITY -> client.requestWorldGenRegistry();
            case TYPED_PUBLICATION -> handleRegistryUpdated(normalizedServerId);
            case TYPED_RECONCILIATION, UNAVAILABLE -> {
                client.requestCatalogPublication(true);
                handleRegistryUpdated(normalizedServerId);
            }
        }
    }

    private static final class PendingWorldGenDuplicate {
        private final String serverId;
        private final String sourceProjectId;
        private final String targetProjectId;
        private final long sourceRevision;
        private final String sourceHash;
        private final long retainedBytes;
        private final long admittedEpoch;
        private final boolean notify;
        private final WorldGenUiToken sourceToken;
        private final WorldGenUiToken targetToken;
        private final ProjectSaveSubmission submission;
        private final Consumer<ProjectSaveSubmissionSettlement> completion;
        private final BrowserSafeState.BooleanValue settled = new BrowserSafeState.BooleanValue();
        private volatile boolean finished;
        private long timeoutToken;
        private volatile TaskScheduler.ScheduledTask timeoutTask;

        private PendingWorldGenDuplicate(String serverId, String sourceProjectId, String targetProjectId,
                                         long sourceRevision, String sourceHash, long admittedEpoch, long retainedBytes, boolean notify,
                                         WorldGenUiToken sourceToken,
                                         WorldGenUiToken targetToken, ProjectSaveSubmission submission,
                                         Consumer<ProjectSaveSubmissionSettlement> completion) {
            this.serverId = Objects.requireNonNull(serverId, "World Generation duplicate server is required");
            this.sourceProjectId = sourceProjectId;
            this.targetProjectId = targetProjectId;
            this.sourceRevision = sourceRevision;
            this.sourceHash = Objects.requireNonNullElse(sourceHash, "");
            this.admittedEpoch = admittedEpoch;
            this.retainedBytes = retainedBytes;
            this.notify = notify;
            this.sourceToken = Objects.requireNonNull(sourceToken, "World Generation source token is required");
            this.targetToken = Objects.requireNonNull(targetToken, "World Generation target token is required");
            this.submission = Objects.requireNonNull(submission, "World Generation duplicate submission is required");
            this.completion = Objects.requireNonNull(completion, "World Generation duplicate completion is required");
        }

        private boolean accepts(long revision, long authorityEpoch, WorldGenProjectStore.ProjectState state) {
            return authorityEpoch == admittedEpoch && revision == sourceRevision && state != null && state.present()
                && sourceRevision >= 1L && sourceHash.equals(state.canonicalHash());
        }

        private long nextTimeoutToken() {
            return ++timeoutToken;
        }

        private void setTimeoutTask(TaskScheduler.ScheduledTask task) {
            TaskScheduler.ScheduledTask previous = timeoutTask;
            timeoutTask = task;
            if (previous != null) {
                previous.cancel();
            }
        }

        private void cancelTimeout() {
            TaskScheduler.ScheduledTask task = timeoutTask;
            timeoutTask = null;
            if (task != null) {
                task.cancel();
            }
        }
    }

    private static final class PendingWorldGenMetadataReconciliation {
        private final String operationId;
        private final String serverId;
        private final String projectId;
        private final boolean add;
        private final WorldGenMetadataIntent intent;
        private final String displayName;
        private final String path;
        private final int sortOrder;
        private long requiredRevision;
        private long requiredMetadataAuthorityGeneration;
        private final String canonicalHash;
        private FlowManager.ServerConnectionToken connection;
        private long authorityEpoch;
        private volatile Consumer<ProjectMetadataReconciliationResult> completion;
        private DesignerSaveNotifications.SaveTicket ticket;
        private TaskScheduler.ScheduledTask retryTask;
        private boolean inFlight;
        private boolean finished;
        private boolean issueNotified;

        private PendingWorldGenMetadataReconciliation(String operationId, String serverId, String projectId,
                                                       boolean add, String displayName, String path, int sortOrder,
                                                       long requiredRevision, String canonicalHash,
                                                       FlowManager.ServerConnectionToken connection, long authorityEpoch,
                                                       Consumer<ProjectMetadataReconciliationResult> completion) {
            this(operationId, serverId, projectId, add, new WorldGenMetadataIntent(displayName, path, sortOrder),
                requiredRevision, canonicalHash, connection, authorityEpoch, completion);
        }

        private PendingWorldGenMetadataReconciliation(String operationId, String serverId, String projectId,
                                                       boolean add, WorldGenMetadataIntent intent, long requiredRevision,
                                                       String canonicalHash, FlowManager.ServerConnectionToken connection,
                                                       long authorityEpoch,
                                                       Consumer<ProjectMetadataReconciliationResult> completion) {
            this.operationId = Objects.requireNonNullElse(operationId, "");
            this.serverId = Objects.requireNonNullElse(serverId, "");
            this.projectId = Objects.requireNonNullElse(projectId, "");
            this.add = add;
            WorldGenMetadataIntent requestedIntent = Objects.requireNonNullElseGet(intent,
                () -> new WorldGenMetadataIntent(this.projectId, "", -1));
            this.intent = add
                ? new WorldGenMetadataIntent(requestedIntent.displayName(),
                    canonicalWorldGenResourcePath(this.projectId, requestedIntent.path()), requestedIntent.sortOrder())
                : requestedIntent;
            this.displayName = this.intent.displayName().isBlank() ? this.projectId : this.intent.displayName();
            this.path = this.intent.path();
            this.sortOrder = this.intent.sortOrder();
            this.requiredRevision = Math.max(0L, requiredRevision);
            this.requiredMetadataAuthorityGeneration = nextProjectMetadataAuthorityGeneration(FlowManager.getInstance(),
                this.serverId);
            this.canonicalHash = Objects.requireNonNullElse(canonicalHash, "");
            this.connection = connection;
            this.authorityEpoch = authorityEpoch;
            this.completion = completion;
        }

        private boolean sameIntent(PendingWorldGenMetadataReconciliation other) {
            return other != null && add == other.add && Objects.equals(displayName, other.displayName)
                && Objects.equals(path, other.path) && sortOrder == other.sortOrder
                && Objects.equals(canonicalHash, other.canonicalHash);
        }

        private boolean sameIntent(WorldGenMetadataIntent other) {
            return other != null && Objects.equals(displayName, other.displayName().isBlank() ? projectId : other.displayName())
                && Objects.equals(path, other.path()) && sortOrder == other.sortOrder();
        }
    }

    private record CompletedMetadataReconciliation(ProjectMetadataReconciliationResult result,
                                                   WorldGenMetadataIntent intent, String canonicalHash) {
    }

    private static final class PendingMetadataReconciliationReservation {
        private final String operationId;
        private final String serverId;
        private final String projectId;
        private final boolean add;
        private final WorldGenMetadataIntent intent;

        private PendingMetadataReconciliationReservation(String operationId, String serverId, String projectId,
                                                         boolean add, WorldGenMetadataIntent intent) {
            this.operationId = operationId;
            this.serverId = serverId;
            this.projectId = projectId;
            this.add = add;
            this.intent = intent;
        }

        private boolean matches(String serverId, String projectId, boolean add) {
            return this.add == add && this.serverId.equals(serverId) && this.projectId.equals(projectId);
        }

        private boolean matches(PendingWorldGenMetadataReconciliation reconciliation) {
            return reconciliation != null && matches(reconciliation.serverId, reconciliation.projectId, reconciliation.add)
                && intent.displayName().equals(reconciliation.displayName)
                && intent.path().equals(reconciliation.path) && intent.sortOrder() == reconciliation.sortOrder;
        }
    }

    private final class PendingWorldGenDelete implements PendingWorldGenMutation {
        private final String projectId;
        private final String requestId;
        private final String mutationId;
        private final String operationId;
        private final long expectedRevision;
        private final WorldGenUiToken uiToken;
        private final ProjectDeleteSubmission submission;
        private final Consumer<ProjectDeleteSubmissionSettlement> completion;
        private final BrowserSafeState.BooleanValue completionSettled = new BrowserSafeState.BooleanValue();
        private volatile long admittedEpoch;
        private Notification notification;
        private volatile boolean finished;
        private volatile boolean awaitingAuthorityTransition;
        private long timeoutToken;
        private volatile TaskScheduler.ScheduledTask timeoutTask;

        private PendingWorldGenDelete(String projectId, String requestId, String mutationId, String operationId,
                                      long expectedRevision, long admittedEpoch, WorldGenUiToken uiToken,
                                      ProjectDeleteSubmission submission,
                                      Consumer<ProjectDeleteSubmissionSettlement> completion) {
            this.projectId = projectId;
            this.requestId = requestId;
            this.mutationId = mutationId;
            this.operationId = operationId;
            this.expectedRevision = expectedRevision;
            this.uiToken = Objects.requireNonNull(uiToken, "World Generation UI token is required");
            this.submission = Objects.requireNonNull(submission, "World Generation delete submission is required");
            this.completion = completion;
            this.admittedEpoch = admittedEpoch;
        }

        private void settleSubmission(ProjectSubmissionState state, long revision, String code) {
            if (!completionSettled.compareAndSet(false, true)) {
                return;
            }
            ProjectDeleteSubmissionSettlement settlement = new ProjectDeleteSubmissionSettlement(submission, state,
                revision, code, uiToken.connection(), uiToken.authorityEpoch());
            if (state == ProjectSubmissionState.COMMITTED) {
                try {
                    promoteMetadataReconciliation(settlement);
                } catch (RuntimeException | Error exception) {
                    try {
                        releaseMetadataReconciliation(operationId);
                    } catch (RuntimeException | Error ignored) {
                    }
                }
            } else {
                try {
                    releaseMetadataReconciliation(operationId);
                } catch (RuntimeException | Error ignored) {
                }
            }
            if (completion != null) {
                settleProjectDeletion(completion, settlement, uiToken);
            }
        }

        private boolean matches(String projectId, String requestId, String mutationId, String operationId) {
            return Objects.equals(this.projectId, projectId) && identityMatches(this.requestId, requestId)
                && identityMatches(this.mutationId, mutationId) && identityMatches(this.operationId, operationId);
        }

        private boolean matches(WorldGenMutationIdentity identity) {
            return identity != null && WORLD_GEN_PROJECT_DELETE_ACTION.equals(identity.action())
                && Objects.equals(this.projectId, identity.projectId()) && Objects.equals(this.projectId, identity.resourceId())
                && matches(identity.projectId(), identity.requestId(), identity.mutationId(), identity.operationId());
        }

        private WorldGenMutationIdentity identity() {
            return new WorldGenMutationIdentity(WORLD_GEN_PROJECT_DELETE_ACTION, projectId, projectId, requestId,
                mutationId, operationId);
        }

        public boolean markAuthorityTransition(long currentEpoch) {
            if (currentEpoch > admittedEpoch) {
                awaitingAuthorityTransition = true;
                return true;
            }
            return false;
        }

        @Override
        public boolean awaitingAuthorityTransition() {
            return awaitingAuthorityTransition;
        }

        @Override
        public PendingAuthorityState authorityState() {
            return new PendingAuthorityState(awaitingAuthorityTransition, admittedEpoch, finished);
        }

        @Override
        public void restoreAuthorityState(PendingAuthorityState state) {
            if (state == null) {
                return;
            }
            awaitingAuthorityTransition = state.awaitingAuthorityTransition();
            admittedEpoch = state.admittedEpoch();
            finished = state.finished();
        }

        private void establishAuthorityEpoch(long currentEpoch) {
            if (admittedEpoch < 1L) {
                admittedEpoch = currentEpoch;
            }
        }

        private long nextTimeoutToken() {
            return ++timeoutToken;
        }

        private void setTimeoutTask(TaskScheduler.ScheduledTask task) {
            TaskScheduler.ScheduledTask previous = timeoutTask;
            timeoutTask = task;
            if (previous != null) {
                previous.cancel();
            }
        }

        private void cancelTimeout() {
            TaskScheduler.ScheduledTask task = timeoutTask;
            timeoutTask = null;
            if (task != null) {
                task.cancel();
            }
        }

        private void showDeleting() {
            if (notification == null) {
                notification = new Notification.Builder()
                    .message("Deleting World Generation")
                    .description(projectId)
                    .type(Notification.Type.INFO)
                    .loading(true)
                    .autoSlideOut(false)
                    .build();
                return;
            }
            notification.update()
                .message("Deleting World Generation")
                .description(projectId)
                .type(Notification.Type.INFO)
                .loading(true)
                .autoSlideOut(false);
        }

        private void finish(String title, String description, Notification.Type type) {
            if (notification == null) {
                notification = new Notification.Builder()
                    .message(title)
                    .description(description)
                    .type(type)
                    .build();
                return;
            }
            notification.change(title, description, type, null);
        }
    }

    public void applyRegistrySnapshot(String serverId, Collection<WorldGenNodeDefinition> definitions,
                                      Object capabilitySnapshot) {
        applyRegistrySnapshot(serverId, definitions, capabilitySnapshot, 1L, 1L);
    }

    public void applyRegistrySnapshot(String serverId, Collection<WorldGenNodeDefinition> definitions,
                                      Object capabilitySnapshot, long revision, long authorityEpoch) {
        applyRegistrySnapshot(serverId, definitions, capabilitySnapshot, capabilitySnapshot != null, revision,
            authorityEpoch);
    }

    public void applyRegistrySnapshot(String serverId, Collection<WorldGenNodeDefinition> definitions,
                                      Object capabilitySnapshot, boolean capabilitiesPresent,
                                      long revision, long authorityEpoch) {
        long startedAt = System.nanoTime();
        String normalizedServerId = connectionServerId(serverId);
        FlowManager.ServerConnectionToken connection = captureConnectionToken(normalizedServerId);
        WorldGenNodeRegistry worldGenRegistry = WorldGenNodeRegistry.getInstance();
        NodeRegistry flowRegistry = NodeRegistry.getInstance();
        int inputCount = definitions == null ? 0 : definitions.size();
        boolean committed = false;
        RegistryProjectionCandidate candidate = null;
        Object projectionLock = registryProjectionLocks.computeIfAbsent(normalizedServerId, ignored -> new Object());
        synchronized (projectionLock) {
            RegistryProjectionFence fence;
            synchronized (this) {
                if (!canAdmitRegistrySnapshot(normalizedServerId, revision, authorityEpoch, connection)) {
                    traceWorldGenLifecycle(normalizedServerId, "worldgen_registry_projection", "apply_registry_snapshot",
                        null, "", "", connection == null ? -1L : connection.generation(), authorityEpoch, revision,
                        "rejected", "registry_admission_rejected", inputCount, 0, "none", startedAt);
                    return;
                }
                fence = new RegistryProjectionFence(registryRevisions.get(normalizedServerId),
                    capabilities.containsKey(normalizedServerId), capabilities.get(normalizedServerId));
            }
            try {
                candidate = stageRegistryProjection(normalizedServerId, definitions, capabilitySnapshot,
                    capabilitiesPresent, fence);
            } catch (RuntimeException exception) {
                traceWorldGenLifecycle(normalizedServerId, "worldgen_registry_projection", "apply_registry_snapshot",
                    null, "", "", connection == null ? -1L : connection.generation(), authorityEpoch, revision,
                    "rejected", "registry_stage_rejected:" + TaskIdentities.failureName(exception), inputCount, 0,
                    "none", startedAt);
                return;
            }
            RegistryProjectionClaim claim = new RegistryProjectionClaim(connection, authorityEpoch, revision, fence);
            synchronized (this) {
                if (!Objects.equals(fence.revision(), registryRevisions.get(normalizedServerId))
                    || !canAdmitRegistrySnapshot(normalizedServerId, revision, authorityEpoch, connection)
                    || registryProjectionClaims.putIfAbsent(normalizedServerId, claim) != null) {
                    traceWorldGenLifecycle(normalizedServerId, "worldgen_registry_projection", "apply_registry_snapshot",
                        null, "", "", connection == null ? -1L : connection.generation(), authorityEpoch, revision,
                        "rejected", "registry_publication_fence_rejected", inputCount, 0, "none", startedAt);
                    return;
                }
            }
            boolean worldGenApplied = false;
            boolean flowApplied = false;
            boolean restorePrevious = false;
            try {
                worldGenRegistry.restoreSnapshot(candidate.worldGenState(), false);
                worldGenApplied = true;
                if (flowRegistry != null && candidate.flowState() != null) {
                    flowApplied = true;
                    if (!flowRegistry.swapServerState(candidate.flowState(), false)) {
                        throw new IllegalStateException("World Generation Flow Registry Projection Was Not Committed");
                    }
                }
                synchronized (this) {
                    boolean sourceCurrent = registryProjectionClaims.get(normalizedServerId) == claim
                        && Objects.equals(fence.revision(), registryRevisions.get(normalizedServerId))
                        && canAdmitRegistrySnapshot(normalizedServerId, revision, authorityEpoch, connection);
                    if (sourceCurrent) {
                        committed = commitRegistryProjectionState(normalizedServerId, candidate, authorityEpoch, revision);
                        restorePrevious = !committed;
                    }
                    registryProjectionClaims.remove(normalizedServerId, claim);
                }
            } catch (RuntimeException | Error exception) {
                synchronized (this) {
                    restorePrevious = registryProjectionClaims.get(normalizedServerId) == claim
                        && Objects.equals(fence.revision(), registryRevisions.get(normalizedServerId))
                        && canAdmitRegistrySnapshot(normalizedServerId, revision, authorityEpoch, connection);
                    registryProjectionClaims.remove(normalizedServerId, claim);
                }
            }
            if (!committed) {
                traceWorldGenLifecycle(normalizedServerId, "worldgen_registry_projection", "apply_registry_snapshot",
                    null, "", "", connection == null ? -1L : connection.generation(), authorityEpoch, revision,
                    "rejected", "registry_commit_rejected", inputCount,
                    candidate == null || candidate.worldGenState() == null ? 0 : candidate.worldGenState().definitions().size(),
                    "none", startedAt);
                restoreRejectedRegistryProjection(normalizedServerId, candidate, worldGenRegistry, flowRegistry,
                    worldGenApplied, flowApplied, restorePrevious);
            }
        }
        if (!committed) {
            return;
        }
        traceWorldGenLifecycle(normalizedServerId, "worldgen_registry_projection", "apply_registry_snapshot", null, "", "",
            connection == null ? -1L : connection.generation(), authorityEpoch, revision, "applied",
            "registry_projection_committed", inputCount,
            candidate == null || candidate.worldGenState() == null ? 0 : candidate.worldGenState().definitions().size(),
            "pending", startedAt);
        publishCommittedRegistryObservers(normalizedServerId, flowRegistry, worldGenRegistry);
        try {
            handleRegistryUpdated(normalizedServerId);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean commitRegistryProjectionState(String serverId, RegistryProjectionCandidate candidate,
                                                  long authorityEpoch, long revision) {
        boolean capabilitiesApplied = false;
        try {
            if (candidate.updateCapabilities()) {
                capabilities.put(serverId, candidate.capabilities());
                capabilitiesApplied = true;
            }
            registryRevisions.put(serverId, new RegistryRevision(authorityEpoch, revision));
            return true;
        } catch (RuntimeException | Error exception) {
            if (candidate.fence().revision() != null) {
                registryRevisions.put(serverId, candidate.fence().revision());
            } else {
                registryRevisions.remove(serverId);
            }
            if (capabilitiesApplied) {
                if (candidate.fence().capabilitiesPresent()) {
                    capabilities.put(serverId, candidate.fence().capabilities());
                } else {
                    capabilities.remove(serverId);
                }
            }
            return false;
        }
    }

    private void restoreRejectedRegistryProjection(String serverId, RegistryProjectionCandidate candidate,
                                                   WorldGenNodeRegistry worldGenRegistry, NodeRegistry flowRegistry,
                                                   boolean worldGenApplied, boolean flowApplied, boolean restorePrevious) {
        if (worldGenApplied) {
            try {
                WorldGenNodeRegistry.Snapshot replacement = restorePrevious ? candidate.previousWorldGenState()
                    : new WorldGenNodeRegistry.Snapshot(serverId, false, Map.of());
                worldGenRegistry.restoreSnapshotIfCurrent(candidate.worldGenState(), replacement, false);
            } catch (RuntimeException | Error ignored) {
            }
        }
        if (flowApplied && flowRegistry != null) {
            try {
                synchronized (flowRegistry) {
                    NodeRegistry.ServerState current = flowRegistry.captureServerState(registryServerId(serverId));
                    if (current.equals(candidate.flowState())) {
                        if (restorePrevious && candidate.previousFlowState() != null) {
                            flowRegistry.restoreServerState(candidate.previousFlowState(), false);
                            flowRegistry.restoreInvalidationReason(registryServerId(serverId),
                                candidate.previousFlowInvalidationReason());
                        } else {
                            flowRegistry.clearServer(registryServerId(serverId), false);
                        }
                    }
                }
            } catch (RuntimeException | Error ignored) {
            }
        }
    }

    private void publishCommittedRegistryObservers(String serverId, NodeRegistry flowRegistry,
                                                   WorldGenNodeRegistry worldGenRegistry) {
        if (flowRegistry != null) {
            try {
                flowRegistry.publish(registryServerId(serverId));
            } catch (RuntimeException | Error ignored) {
            }
        }
        try {
            worldGenRegistry.publish(serverId);
        } catch (RuntimeException | Error ignored) {
        }
    }

    private RegistryProjectionCandidate stageRegistryProjection(String serverId,
                                                                Collection<WorldGenNodeDefinition> definitions,
                                                                Object capabilitySnapshot,
                                                                boolean capabilitiesPresent,
                                                                RegistryProjectionFence fence) {
        if (!capabilitiesPresent || definitions == null) {
            throw new IllegalArgumentException("World Generation registry snapshot is incomplete");
        }
        Collection<WorldGenNodeDefinition> source = definitions;
        Map<String, WorldGenNodeDefinition> stagedWorldGen = new LinkedHashMap<>();
        Map<String, NodeDefinition> stagedFlow = new LinkedHashMap<>();
        for (WorldGenNodeDefinition definition : source) {
            validateWorldGenDefinition(definition);
            if (stagedWorldGen.put(definition.getId(), definition) != null) {
                throw new IllegalArgumentException("Duplicate World Generation node definition: " + definition.getId());
            }
        }
        Object stagedCapabilities = capabilitySnapshot != null ? copyCapabilityValue(capabilitySnapshot,
            new IdentityHashMap<>()) : Map.of();
        List<WorldGenNodeDefinition> stagedDefinitions = List.copyOf(stagedWorldGen.values());
        stagedFlow.putAll(convertFlowDefinitions(stagedDefinitions));
        WorldGenNodeRegistry worldGenRegistry = WorldGenNodeRegistry.getInstance();
        WorldGenNodeRegistry.Snapshot previousWorldGenState = worldGenRegistry.captureSnapshot(serverId);
        WorldGenNodeRegistry.Snapshot worldGenState = worldGenRegistry.prepareSnapshot(serverId, stagedDefinitions);
        NodeRegistry flowRegistry = NodeRegistry.getInstance();
        NodeRegistry.ServerState previousFlowState = flowRegistry != null
            ? flowRegistry.captureServerState(registryServerId(serverId)) : null;
        String previousFlowInvalidationReason = flowRegistry != null
            ? flowRegistry.getInvalidationReason(registryServerId(serverId)) : "";
        NodeRegistry.ServerState flowState = previousFlowState != null
            ? stageFlowRegistryState(previousFlowState, stagedFlow) : null;
        return new RegistryProjectionCandidate(worldGenState, flowState, stagedCapabilities, true, fence,
            previousWorldGenState, previousFlowState, previousFlowInvalidationReason);
    }

    private NodeRegistry.ServerState stageFlowRegistryState(NodeRegistry.ServerState current,
                                                            Map<String, NodeDefinition> definitions) {
        return new NodeRegistry.ServerState(current.serverId(), definitions, current.plugins(), current.unresolvedPlugins(),
            current.nodeIds(), current.propertyActions(), current.propertyOutputTypes(), current.propertyMetadata(),
            current.resourceMetadata(), current.typeMetadata(), current.dataTypes(), current.categoryMetadata(),
            current.optionSourceMetadata(), current.conversionRules(), current.session(), current.authority(),
            current.projectionIdentity(), current.snapshotData(), current.catalogAuthority());
    }

    private Map<String, NodeDefinition> convertFlowDefinitions(Collection<WorldGenNodeDefinition> definitions) {
        Map<String, NodeDefinition> converted = new LinkedHashMap<>();
        for (WorldGenNodeDefinition definition : definitions) {
            NodeDefinition flowDefinition = toFlowDefinition(definition);
            NodeRegistry.NodeReference reference = new NodeRegistry.NodeReference(flowDefinition.getOwner(), flowDefinition.getId());
            if (converted.put(reference.canonical(), flowDefinition) != null) {
                throw new IllegalArgumentException("Duplicate World Generation Flow node definition: " + reference.canonical());
            }
        }
        return converted;
    }

    private void validateWorldGenDefinition(WorldGenNodeDefinition definition) {
        if (definition == null || definition.getId() == null || definition.getId().isBlank()
            || !definition.getId().equals(definition.getId().trim()) || definition.getId().chars().anyMatch(Character::isISOControl)
            || definition.getDisplayName() == null || definition.getDisplayName().isBlank()
            || definition.getInputs() == null || definition.getOutputs() == null) {
            throw new IllegalArgumentException("World Generation node definition is invalid");
        }
        validatePins(definition.getInputs());
        validatePins(definition.getOutputs());
    }

    private void validatePins(Collection<WorldGenNodeDefinition.PinDefinition> pins) {
        Set<String> names = new HashSet<>();
        for (WorldGenNodeDefinition.PinDefinition pin : pins) {
            if (pin == null || pin.name() == null || pin.name().isBlank() || !names.add(pin.name())
                || pin.dataType() == null || pin.direction() == null || pin.options() == null
                || pin.constraints() == null) {
                throw new IllegalArgumentException("World Generation node pin is invalid");
            }
            if (pin.options().stream().anyMatch(value -> value == null || value.isBlank())
                || pin.constraints().entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
                throw new IllegalArgumentException("World Generation node pin metadata is invalid");
            }
        }
    }

    private Object copyCapabilityValue(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || number instanceof Float floatValue && !Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("World Generation capability contains a non-finite number");
            }
            return number;
        }
        if (value instanceof JsonElement element) {
            if (element.isJsonNull()) {
                return null;
            }
            if (element.isJsonPrimitive()) {
                if (element.getAsJsonPrimitive().isBoolean()) {
                    return element.getAsBoolean();
                }
                if (element.getAsJsonPrimitive().isNumber()) {
                    return copyCapabilityValue(element.getAsNumber(), visiting);
                }
                if (element.getAsJsonPrimitive().isString()) {
                    return element.getAsString();
                }
                throw new IllegalArgumentException("World Generation capability primitive is invalid");
            }
            if (visiting.put(element, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("World Generation capability contains a cycle");
            }
            try {
                if (element.isJsonArray()) {
                    List<Object> values = new ArrayList<>();
                    for (JsonElement child : element.getAsJsonArray()) {
                        values.add(copyCapabilityValue(child, visiting));
                    }
                    return Collections.unmodifiableList(values);
                }
                if (element.isJsonObject()) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                        if (entry.getKey() == null || entry.getKey().isBlank()) {
                            throw new IllegalArgumentException("World Generation capability key is invalid");
                        }
                        values.put(entry.getKey(), copyCapabilityValue(entry.getValue(), visiting));
                    }
                    return Collections.unmodifiableMap(values);
                }
            } finally {
                visiting.remove(element);
            }
        }
        if (value instanceof Map<?, ?> map) {
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("World Generation capability contains a cycle");
            }
            try {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                        throw new IllegalArgumentException("World Generation capability key is invalid");
                    }
                    values.put(key, copyCapabilityValue(entry.getValue(), visiting));
                }
                return Collections.unmodifiableMap(values);
            } finally {
                visiting.remove(value);
            }
        }
        if (value instanceof Iterable<?> iterable) {
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("World Generation capability contains a cycle");
            }
            try {
                List<Object> values = new ArrayList<>();
                for (Object child : iterable) {
                    values.add(copyCapabilityValue(child, visiting));
                }
                return Collections.unmodifiableList(values);
            } finally {
                visiting.remove(value);
            }
        }
        throw new IllegalArgumentException("World Generation capability value is unsupported");
    }

    private boolean canAdmitRegistrySnapshot(String serverId, long revision, long authorityEpoch,
                                             FlowManager.ServerConnectionToken connection) {
        if (serverId == null || serverId.isBlank() || revision < 1L || authorityEpoch < 1L
            || connection == null || connection.source() == null || connection.generation() < 1L
            || !sameServer(serverId, connection.serverId())
            || connection.source().catalogAuthority() != ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY
            || !isCurrentWorldGenConnection(connection, authorityEpoch)) {
            return false;
        }
        RegistryRevision current = registryRevisions.get(serverId);
        if (current != null && (authorityEpoch < current.authorityEpoch()
            || authorityEpoch == current.authorityEpoch() && revision <= current.revision())) {
            return false;
        }
        return true;
    }

    private record RegistryProjectionFence(RegistryRevision revision, boolean capabilitiesPresent,
                                           Object capabilities) {
    }

    private record RegistryProjectionClaim(FlowManager.ServerConnectionToken connection, long authorityEpoch,
                                           long revision, RegistryProjectionFence fence) {
    }

    private record RegistryInvalidation(String serverId, long generation) {
    }

    private record RegistryProjectionCandidate(WorldGenNodeRegistry.Snapshot worldGenState,
                                               NodeRegistry.ServerState flowState, Object capabilities,
                                               boolean updateCapabilities, RegistryProjectionFence fence,
                                               WorldGenNodeRegistry.Snapshot previousWorldGenState,
                                               NodeRegistry.ServerState previousFlowState,
                                               String previousFlowInvalidationReason) {
    }

    public synchronized Object getCapabilities(String serverId) {
        return capabilities.get(WorldGenCatalogProjection.normalizeBaseServerId(serverId));
    }

    public synchronized RegistryRevision registryRevision(String serverId) {
        return registryRevisions.get(WorldGenCatalogProjection.normalizeBaseServerId(serverId));
    }

    public synchronized String targetVersion(String serverId, String configuredVersion) {
        if (configuredVersion != null && !configuredVersion.isBlank() && !WorldGenTargetVersion.AUTOMATIC.equalsIgnoreCase(configuredVersion)) {
            return WorldGenTargetVersion.require(configuredVersion).id();
        }
        Object snapshot = capabilities.get(WorldGenCatalogProjection.normalizeBaseServerId(serverId));
        if (snapshot instanceof Map<?, ?> values) {
            Object minecraftVersion = values.get("minecraftVersion");
            if (minecraftVersion != null && !String.valueOf(minecraftVersion).isBlank()) {
                return WorldGenTargetVersion.require(String.valueOf(minecraftVersion)).id();
            }
        }
        return WorldGenTargetVersion.DEFAULT.id();
    }

    public void requestPreview(String serverId, String previewId, FlowGraph graph, String environment, long seed, String playerUuid) {
        if (serverId == null || serverId.isBlank() || graph == null || !canMutateWorldGen(serverId)) {
            return;
        }
        requestPreview(serverId, previewId, toWorldGenGraph(serverId, graph), environment, seed, playerUuid);
    }

    public void requestPreview(String serverId, String previewId, WorldGenGraph graph, String environment, long seed, String playerUuid) {
        if (serverId == null || serverId.isBlank() || graph == null || !canMutateWorldGen(serverId)) {
            return;
        }
        ReSyncFlowClient client = flowClient(serverId);
        if (client == null) {
            new Notification("World Generation", "ReSync Offline", Notification.Type.ERROR);
            return;
        }
        WorldGenProject project = getOrCreateProject(serverId);
        project.setTerrainGraph(graph);
        requestPreview(serverId, previewId, project, environment, seed, playerUuid);
    }

    public void requestPreview(String serverId, String previewId, WorldGenProject project, String environment, long seed, String playerUuid) {
        ReSyncFlowClient client = flowClient(serverId);
        if (client == null) {
            new Notification("World Generation", "ReSync Offline", Notification.Type.ERROR);
            return;
        }
        if (!canMutateWorldGen(serverId)) {
            return;
        }
        previewController.markCreating(serverId, previewId);
        try {
            client.sendWorldGenPreviewApply(null, project, previewId, environment, seed, isUuid(playerUuid) ? playerUuid : "");
            new Notification("World Generation", "Creating Preview", Notification.Type.INFO);
        } catch (RuntimeException exception) {
            previewController.fail(serverId, previewId);
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Preview Failed" : exception.getMessage();
            new Notification("World Generation", message, Notification.Type.ERROR);
        }
    }

    public void requestPreview(String serverId, String previewId, PreparedEditorProjectSave prepared,
                               String environment, long seed, String playerUuid) {
        String normalizedServerId = connectionServerId(serverId);
        FlowManager.ServerConnectionToken connection = prepared == null ? null : prepared.sourceToken.connection();
        if (normalizedServerId.isBlank() || prepared == null || connection == null
            || !normalizedServerId.equals(prepared.sourceToken.serverId())
            || previewId == null || previewId.isBlank()
            || !isCurrentPreparedEditorProject(prepared)
            || !isCurrentWorldGenConnection(connection, prepared.sourceToken.authorityEpoch())
            || !canMutateWorldGen(normalizedServerId)) {
            return;
        }
        ReSyncFlowClient client = flowClient(normalizedServerId);
        if (client == null || client != connection.source()) {
            return;
        }
        previewController.markCreating(normalizedServerId, previewId);
        try {
            projectPreparationExecutor.execute(() -> {
                boolean sent = false;
                String failure = "Preview Failed";
                try {
                    boolean[] admitted = {false};
                    if (runWorldGenConnectionNow(prepared.sourceToken, () -> {
                        if (isCurrentPreparedEditorProject(prepared)
                            && isCurrentWorldGenConnection(connection, prepared.sourceToken.authorityEpoch())
                            && canMutateWorldGen(normalizedServerId)) {
                            client.sendWorldGenPreviewApply(null, prepared.serializedContent(), previewId, environment, seed,
                                isUuid(playerUuid) ? playerUuid : "");
                            admitted[0] = true;
                        }
                    }) && admitted[0]) {
                        executeWorldGenUi(prepared.sourceToken,
                            () -> new Notification("World Generation", "Creating Preview", Notification.Type.INFO));
                        sent = true;
                    } else {
                        failure = "Preview Request Expired";
                    }
                } catch (RuntimeException | Error exception) {
                    if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
                        failure = exception.getMessage();
                    }
                }
                if (!sent) {
                    previewController.fail(normalizedServerId, previewId);
                    String message = failure;
                    executeWorldGenUi(prepared.sourceToken,
                        () -> new Notification("World Generation", message, Notification.Type.ERROR));
                }
            });
        } catch (IllegalStateException exception) {
            previewController.fail(normalizedServerId, previewId);
            executeWorldGenUi(prepared.sourceToken,
                () -> new Notification("World Generation", "Preview Busy", Notification.Type.WARN));
        }
    }

    public void requestSavedProjectPreview(String serverId, String projectId, String previewId, String environment, long seed, String playerUuid) {
        long startedAt = System.nanoTime();
        ReSyncFlowClient client = flowClient(serverId);
        if (client == null) {
            traceWorldGenLifecycle(serverId, "worldgen_request", "preview_saved_project", projectId, "", "", -1L,
                authorityEpoch(serverId), -1L, "rejected", "client_missing", 0, 0, "none", startedAt);
            new Notification("World Generation", "ReSync Offline", Notification.Type.ERROR);
            return;
        }
        if (!canMutateWorldGen(serverId)) {
            traceWorldGenLifecycle(serverId, "worldgen_request", "preview_saved_project", projectId, "", "", -1L,
                authorityEpoch(serverId), -1L, "rejected", "typed_authority_unavailable", 0, 0, "none", startedAt);
            return;
        }
        previewController.markCreating(serverId, previewId);
        WorldGenProject cachedProject = projectStore.cachedProject(serverId, projectId);
        traceWorldGenLifecycle(serverId, "worldgen_cache_lookup", "preview_saved_project", projectId, "", "",
            -1L, authorityEpoch(serverId), cachedProject == null ? -1L : projectStore.projectRevision(serverId, projectId),
            cachedProject == null ? "miss" : "hit", cachedProject == null ? "cache_miss_ui_dead_end" : "project_cache_hit",
            0, cachedProject == null ? 0 : 1, "none", startedAt);
        try {
            if (cachedProject != null) {
                client.sendWorldGenPreviewApply(null, cachedProject, previewId, environment, seed, isUuid(playerUuid) ? playerUuid : "");
            } else {
                client.sendWorldGenPreviewApply(projectId, (WorldGenProject) null, previewId, environment, seed,
                    isUuid(playerUuid) ? playerUuid : "");
            }
            new Notification("World Generation", "Creating Preview", Notification.Type.INFO);
            FlowManager.ServerConnectionToken connection = captureConnectionToken(serverId);
            traceWorldGenLifecycle(serverId, "worldgen_request", "preview_saved_project", projectId, "", "",
                connection == null ? -1L : connection.generation(), authorityEpoch(serverId), -1L, "dispatched",
                cachedProject == null ? "cache_miss_request_fallback" : "cached_project_request", 1,
                cachedProject == null ? 0 : 1, "WorldGenNotification", startedAt);
        } catch (RuntimeException exception) {
            previewController.fail(serverId, previewId);
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Preview Failed" : exception.getMessage();
            traceWorldGenLifecycle(serverId, "worldgen_request", "preview_saved_project", projectId, "", "", -1L,
                authorityEpoch(serverId), -1L, "failed", "preview_request_exception", 1, 0, "WorldGenNotification",
                startedAt);
            new Notification("World Generation", message, Notification.Type.ERROR);
        }
    }

    public void stopPreview(String serverId, String previewId) {
        ReSyncFlowClient client = flowClient(serverId);
        if (client != null && canMutateWorldGen(serverId)) {
            try {
                client.sendWorldGenPreviewStop(previewId);
                previewController.stop(serverId, previewId);
                new Notification("World Generation", "Preview Stopped", Notification.Type.SUCCESS);
            } catch (RuntimeException exception) {
                previewController.stop(serverId, previewId);
                String message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "Preview Stop Failed" : exception.getMessage();
                new Notification("World Generation", message, Notification.Type.ERROR);
            }
        }
    }

    public void handlePreviewStatus(String serverId, String previewId, String status, String message) {
        long startedAt = System.nanoTime();
        WorldGenUiToken uiToken = captureWorldGenUiToken(serverId, null, authorityEpoch(serverId));
        if (!isCurrentWorldGenUi(uiToken)) {
            traceWorldGenLifecycle(serverId, "worldgen_ui_delivery", "preview_status", null, "", "",
                uiToken.connection() == null ? -1L : uiToken.connection().generation(), uiToken.authorityEpoch(), -1L,
                "dropped", "ui_fence_stale", 1, 0, "none", startedAt);
            return;
        }
        if (!runWorldGenNow(uiToken, () -> previewController.update(serverId, previewId, status))) {
            traceWorldGenLifecycle(serverId, "worldgen_ui_delivery", "preview_status", null, "", "",
                uiToken.connection() == null ? -1L : uiToken.connection().generation(), uiToken.authorityEpoch(), -1L,
                "dropped", "preview_state_update_rejected", 1, 0, "none", startedAt);
            return;
        }
        executeWorldGenUi(uiToken, () -> {
            new Notification("World Generation", message == null || message.isBlank() ? status : message, "ready".equalsIgnoreCase(status) ? Notification.Type.SUCCESS : Notification.Type.ERROR);
            String uiTarget = "WorldGenNotification";
            if (ScreenManager.getInstance().getCurrentScreen() instanceof WorldGenEditorScreen screen
                && sameServer(serverId, screen.getActualServerId())) {
                screen.refreshStatus();
                uiTarget += "+WorldGenEditorScreen";
            }
            traceWorldGenLifecycle(serverId, "worldgen_ui_delivery", "preview_status", null, "", "",
                uiToken.connection() == null ? -1L : uiToken.connection().generation(), uiToken.authorityEpoch(), -1L,
                "delivered", "preview_status_applied", 1, 1, uiTarget, startedAt);
        });
    }

    public void handleRegistryUpdated(String serverId) {
        long startedAt = System.nanoTime();
        WorldGenUiToken uiToken = captureWorldGenUiToken(serverId, null, authorityEpoch(serverId));
        executeWorldGenUi(uiToken, () -> {
            String uiTarget = "none";
            if (ScreenManager.getInstance().getCurrentScreen() instanceof WorldGenEditorScreen screen
                && sameServer(serverId, screen.getActualServerId())) {
                screen.refreshNodeRegistry();
                uiTarget = "WorldGenEditorScreen";
            }
            traceWorldGenLifecycle(serverId, "worldgen_ui_delivery", "registry_update", null, "", "",
                uiToken.connection() == null ? -1L : uiToken.connection().generation(), uiToken.authorityEpoch(), -1L,
                uiTarget.equals("none") ? "dropped" : "delivered",
                uiTarget.equals("none") ? "no_matching_screen" : "registry_refreshed", 1, 1, uiTarget, startedAt);
        });
    }

    public String getPreviewState(String serverId, String previewId) {
        return previewController.state(serverId, previewId);
    }

    public void ensureLocalDefinitions(String serverId) {
        String normalizedServerId = connectionServerId(serverId);
        ReSyncFlowClient client = flowClient(serverId);
        if (client == null || client.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY) {
            return;
        }
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        if (!registry.hasDefinitions(normalizedServerId)) {
            registerFallbackDefinitions(normalizedServerId);
            if (client != null && client.isConnectedState()) {
                client.requestWorldGenRegistry();
            }
        }
        registerFlowDefinitions(normalizedServerId, registry.getAllDefinitions(normalizedServerId));
    }

    public WorldGenCatalogProjection.Snapshot liveCatalog(String serverId) {
        String normalizedServerId = WorldGenCatalogProjection.normalizeBaseServerId(serverId);
        ReSyncFlowClient client = flowClient(serverId);
        WorldGenCatalogProjection.Snapshot projection = WorldGenCatalogProjection.from(normalizedServerId, client);
        if (projection.authority() != WorldGenCatalogProjection.Authority.LEGACY_COMPATIBILITY) {
            return projection;
        }
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        Map<String, NodeDefinition> definitions = new LinkedHashMap<>();
        for (WorldGenNodeDefinition definition : registry.getAllDefinitions(normalizedServerId)) {
            NodeDefinition flowDefinition = toFlowDefinition(definition);
            String identity = WorldGenCatalogProjection.canonicalReference("worldgen", definition.getId());
            if (!identity.isBlank()) {
                definitions.put(identity, flowDefinition);
            }
        }
        return WorldGenCatalogProjection.legacy(normalizedServerId, definitions, projection.diagnostic());
    }

    public List<NodeDefinition> liveFlowDefinitions(String serverId) {
        return List.copyOf(liveCatalog(serverId).definitions().values());
    }

    public String liveFlowNodeType(String serverId, String nodeType) {
        return liveFlowNodeType(liveCatalog(serverId), nodeType);
    }

    String liveFlowNodeType(WorldGenCatalogProjection.Snapshot projection, String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return "";
        }
        if (projection == null) {
            return nodeType;
        }
        if (projection.authority() != WorldGenCatalogProjection.Authority.TYPED_PUBLICATION) {
            return nodeType;
        }
        String canonical = WorldGenCatalogProjection.canonicalReference(nodeType);
        if (canonical.isBlank()) {
            throw new IllegalArgumentException("WorldGen node identity must be owner-qualified in the acknowledged catalog: " + nodeType);
        }
        if (!projection.definitions().containsKey(canonical)) {
            throw new IllegalArgumentException("WorldGen node identity is not in the acknowledged catalog: " + canonical);
        }
        return canonical;
    }

    private void registerFallbackDefinitions(String serverId) {
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        for (WorldGenNodeDefinition definition : defaultDefinitions()) {
            registry.register(serverId, definition);
        }
    }

    public WorldGenGraph toWorldGenGraph(FlowGraph flowGraph) {
        return toWorldGenGraph((WorldGenCatalogProjection.Snapshot) null, flowGraph);
    }

    public WorldGenGraph toWorldGenGraph(String serverId, FlowGraph flowGraph) {
        return toWorldGenGraph(serverId == null ? null : liveCatalog(serverId), flowGraph);
    }

    WorldGenGraph toWorldGenGraph(WorldGenCatalogProjection.Snapshot projection, FlowGraph flowGraph) {
        WorldGenGraph graph = new WorldGenGraph();
        if (flowGraph == null) {
            return graph;
        }
        graph.setId(flowGraph.getId());
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, FlowNode> entry : flowGraph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            if (node == null) {
                throw new IllegalArgumentException("WorldGen node is missing: " + entry.getKey());
            }
            String type = projection == null ? wireNodeType(node.getType()) : wireNodeType(projection, node.getType());
            if (type.isBlank()) {
                throw new IllegalArgumentException("WorldGen node type is missing: " + entry.getKey());
            }
            nodes.put(entry.getKey(), new WorldGenNode(type, node.getX(), node.getY(), node.getInputValues() != null ? new HashMap<>(node.getInputValues()) : new HashMap<>()));
        }
        List<WorldGenConnection> connections = new ArrayList<>();
        for (FlowConnection connection : flowGraph.getConnections()) {
            connections.add(new WorldGenConnection(connection.getSourceNodeId(), connection.getSourcePin(), connection.getTargetNodeId(), connection.getTargetPin()));
        }
        graph.setNodes(nodes);
        graph.setConnections(connections);
        return graph;
    }

    private String wireNodeType(WorldGenCatalogProjection.Snapshot projection, String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return "";
        }
        String canonical = WorldGenCatalogProjection.canonicalReference(nodeType);
        if (projection.authority() == WorldGenCatalogProjection.Authority.TYPED_PUBLICATION) {
            if (!canonical.isBlank()) {
                if (!projection.definitions().containsKey(canonical)) {
                    throw new IllegalArgumentException("WorldGen node identity is not in the acknowledged catalog: " + canonical);
                }
                return canonical;
            }
            return liveFlowNodeType(projection, nodeType);
        }
        if (projection.authority() == WorldGenCatalogProjection.Authority.LEGACY_COMPATIBILITY) {
            return wireLegacyNodeType(nodeType);
        }
        throw new IllegalStateException("WorldGen catalog is unavailable for graph persistence: " + projection.diagnostic());
    }

    private String wireNodeType(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return "";
        }
        String canonical = WorldGenCatalogProjection.canonicalReference(nodeType);
        if (canonical.isBlank()) {
            throw new IllegalArgumentException("WorldGen node identity must be owner-qualified: " + nodeType);
        }
        if (!WorldGenCatalogProjection.isWorldGenReference(canonical)) {
            throw new IllegalArgumentException("WorldGen node owner is not supported: " + canonical);
        }
        return canonical;
    }

    private String wireLegacyNodeType(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return "";
        }
        String canonical = WorldGenCatalogProjection.canonicalReference(nodeType);
        if (canonical.isBlank()) {
            return nodeType;
        }
        if (!WorldGenCatalogProjection.isWorldGenReference(canonical)) {
            throw new IllegalArgumentException("WorldGen node owner is not supported: " + canonical);
        }
        return canonical;
    }

    public FlowGraph toFlowGraph(WorldGenGraph worldGenGraph) {
        return toFlowGraph(null, worldGenGraph);
    }

    public FlowGraph toFlowGraph(String serverId, WorldGenGraph worldGenGraph) {
        FlowGraph graph = new FlowGraph();
        if (worldGenGraph == null) {
            return graph;
        }
        WorldGenCatalogProjection.Snapshot projection = serverId == null ? null : liveCatalog(serverId);
        graph.setId(worldGenGraph.getId());
        for (Map.Entry<String, WorldGenNode> entry : worldGenGraph.getNodes().entrySet()) {
            WorldGenNode node = entry.getValue();
            if (node == null) {
                throw new IllegalArgumentException("WorldGen node is missing: " + entry.getKey());
            }
            String type = projection == null ? wireNodeType(node.getType()) : liveFlowNodeType(projection, node.getType());
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("WorldGen node type is missing: " + entry.getKey());
            }
            graph.getNodes().put(entry.getKey(), new FlowNode(type, node.getX(), node.getY(), node.getInputValues() != null ? new HashMap<>(node.getInputValues()) : new HashMap<>()));
        }
        for (WorldGenConnection connection : worldGenGraph.getConnections()) {
            graph.getConnections().add(new FlowConnection(connection.getSourceNodeId(), connection.getSourcePin(), connection.getTargetNodeId(), connection.getTargetPin()));
        }
        graph.setFunction(false);
        graph.setResourceType(ReSyncResourceDragPayload.WORLDGEN);
        graph.getLocalVariables().clear();
        graph.getFunctionInputs().clear();
        graph.getFunctionOutputs().clear();
        return graph;
    }

    private void registerFlowDefinitions(String serverId, Collection<WorldGenNodeDefinition> definitions) {
        NodeRegistry registry = NodeRegistry.getInstance();
        if (registry == null) {
            return;
        }
        String key = registryServerId(serverId);
        try {
            for (WorldGenNodeDefinition definition : definitions) {
                validateWorldGenDefinition(definition);
            }
            NodeRegistry.ServerState current = registry.captureServerState(key);
            NodeRegistry.ServerState candidate = stageFlowRegistryState(current, convertFlowDefinitions(definitions));
            if (!registry.swapServerState(candidate)) {
                return;
            }
        } catch (RuntimeException ignored) {
        }
    }

    private NodeDefinition toFlowDefinition(WorldGenNodeDefinition definition) {
        NodeDefinition.Builder builder = new NodeDefinition.Builder(definition.getId(), definition.getDisplayName(), categoryFor(definition))
            .owner("worldgen")
            .color(definition.getColor())
            .priority(definition.getPriority())
            .description(definition.getDescription())
            .hidden(definition.isHidden());
        for (WorldGenNodeDefinition.PinDefinition pin : definition.getInputs()) {
            builder.input(toFlowPin(pin, NodeDefinition.PinDirection.INPUT));
        }
        for (WorldGenNodeDefinition.PinDefinition pin : definition.getOutputs()) {
            builder.output(toFlowPin(pin, NodeDefinition.PinDirection.OUTPUT));
        }
        return builder.build();
    }

    private NodeDefinition.NodeCategory categoryFor(WorldGenNodeDefinition definition) {
        String category = definition.getCategory();
        if (category == null || category.isBlank() || "World Gen".equals(category)) {
            return NodeDefinition.NodeCategory.WORLD_GEN;
        }
        String id = category.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
        return NodeDefinition.NodeCategory.registerServerCategory(id, category, definition.getColor(), definition.getPriority());
    }

    private NodeDefinition.PinDefinition toFlowPin(WorldGenNodeDefinition.PinDefinition pin, NodeDefinition.PinDirection direction) {
        NodeDefinition.PinBuilder builder = new NodeDefinition.PinBuilder(pin.name(), NodeDefinition.PinType.DATA, direction, pin.dataType());
        NodeDefinition.WidgetType widgetType = widgetType(pin);
        if (widgetType != null) {
            builder.widget(widgetType);
        }
        if (pin.defaultValue() != null) {
            builder.defaultValue(String.valueOf(pin.defaultValue()));
        }
        if (pin.options() != null && !pin.options().isEmpty()) {
            builder.options(pin.options());
        }
        if ("distance_func".equals(pin.name())) {
            builder.options(List.of("euclidean", "euclidean_sq", "manhattan", "hybrid"));
        }
        if ("material".equalsIgnoreCase(pin.widgetType())) {
            builder.optionsSource("worldgen:blocks");
        }
        if (FlowDataType.BIOME.equals(pin.dataType())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST);
            builder.optionsSource("worldgen:biomes");
        }
        if (FlowDataType.ENTITY_TYPE.equals(pin.dataType())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST);
            builder.optionsSource("worldgen:entity_types");
        }
        if ("structure_id".equals(pin.name())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST);
            builder.optionsSource("worldgen:structures");
        }
        if ("tree".equals(pin.name())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST);
            builder.optionsSource("worldgen:tree_features");
        }
        if ("feature".equals(pin.name())) {
            builder.widget(NodeDefinition.WidgetType.SEARCHABLE_LIST);
            builder.optionsSource("worldgen:features");
        }
        if (pin.constraints() != null && !pin.constraints().isEmpty()) {
            builder.constraints(number(pin.constraints().get("min")), number(pin.constraints().get("max")), number(pin.constraints().get("step")));
        }
        if (pin.description() != null && !pin.description().isBlank()) {
            builder.description(pin.description());
        }
        return builder.build();
    }

    private NodeDefinition.WidgetType widgetType(WorldGenNodeDefinition.PinDefinition pin) {
        String widgetType = pin.widgetType();
        if (widgetType == null || widgetType.isBlank()) {
            return null;
        }
        return switch (widgetType.toLowerCase()) {
            case "number" -> NodeDefinition.WidgetType.NUMBER;
            case "dropdown" -> NodeDefinition.WidgetType.DROPDOWN;
            case "slider" -> NodeDefinition.WidgetType.SLIDER;
            case "toggle" -> NodeDefinition.WidgetType.TOGGLE;
            case "material" -> NodeDefinition.WidgetType.SEARCHABLE_LIST;
            case "searchable", "searchable_list" -> NodeDefinition.WidgetType.SEARCHABLE_LIST;
            default -> NodeDefinition.WidgetType.TEXT;
        };
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<WorldGenNodeDefinition> defaultDefinitions() {
        return List.of(
            terrain(WorldGenNodeDefinition.builder("simplex", "Simplex").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("perlin", "Perlin").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("value", "Value").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("cellular", "Cellular").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").input("distance_func", FlowDataType.STRING, "euclidean", "dropdown").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("white", "White").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("fbm", "Fractal FBm").input("source", FlowDataType.FLOAT, 0f, "number").input("octaves", FlowDataType.FLOAT, 4f, "number").input("lacunarity", FlowDataType.FLOAT, 2f, "number").input("gain", FlowDataType.FLOAT, 0.5f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("ridged", "Fractal Ridged").input("source", FlowDataType.FLOAT, 0f, "number").input("octaves", FlowDataType.FLOAT, 4f, "number").input("lacunarity", FlowDataType.FLOAT, 2f, "number").input("gain", FlowDataType.FLOAT, 0.5f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("ping_pong", "Ping Pong").input("source", FlowDataType.FLOAT, 0f, "number").input("octaves", FlowDataType.FLOAT, 4f, "number").input("lacunarity", FlowDataType.FLOAT, 2f, "number").input("gain", FlowDataType.FLOAT, 0.5f, "number").input("ping_pong_strength", FlowDataType.FLOAT, 2f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("add", "Add").input("a", FlowDataType.FLOAT, 0f, "number").input("b", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("multiply", "Multiply").input("a", FlowDataType.FLOAT, 1f, "number").input("b", FlowDataType.FLOAT, 1f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("remap", "Remap").input("in", FlowDataType.FLOAT, 0f, "number").input("from_min", FlowDataType.FLOAT, -1f, "number").input("from_max", FlowDataType.FLOAT, 1f, "number").input("to_min", FlowDataType.FLOAT, 0f, "number").input("to_max", FlowDataType.FLOAT, 128f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("clamp", "Clamp").input("in", FlowDataType.FLOAT, 0f, "number").input("min", FlowDataType.FLOAT, 0f, "number").input("max", FlowDataType.FLOAT, 1f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("abs", "Abs").input("in", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("min", "Min").input("a", FlowDataType.FLOAT, 0f, "number").input("b", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("max", "Max").input("a", FlowDataType.FLOAT, 0f, "number").input("b", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("domain_warp_gradient", "Domain Warp Gradient").input("source", FlowDataType.FLOAT, 0f, "number").input("amplitude", FlowDataType.FLOAT, 1f, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("domain_warp_simplex", "Domain Warp Simplex").input("source", FlowDataType.FLOAT, 0f, "number").input("amplitude", FlowDataType.FLOAT, 1f, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("terrace", "Terrace").input("in", FlowDataType.FLOAT, 0f, "number").input("step_count", FlowDataType.FLOAT, 8f, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("seed_offset", "Seed Offset").input("in", FlowDataType.FLOAT, 0f, "number").input("offset", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("continental_shelf", "Continental Shelf").input("scale", FlowDataType.FLOAT, 1f, "number").input("ocean", FlowDataType.FLOAT, 0.42f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("mountain_range", "Mountain Range").input("amount", FlowDataType.FLOAT, 1f, "number").input("scale", FlowDataType.FLOAT, 1f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("river_network", "River Network").input("density", FlowDataType.FLOAT, 1f, "number").input("depth", FlowDataType.FLOAT, 24f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("eroded_peaks", "Eroded Peaks").input("amount", FlowDataType.FLOAT, 1f, "number").input("terraces", FlowDataType.FLOAT, 12f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("badlands_plateau", "Badlands Plateau").input("height", FlowDataType.FLOAT, 86f, "number").input("erosion", FlowDataType.FLOAT, 0.55f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("volcanic_field", "Volcanic Field").input("height", FlowDataType.FLOAT, 72f, "number").input("roughness", FlowDataType.FLOAT, 0.7f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("density_from_height", "Density From Height").input("height", FlowDataType.FLOAT, 64f, "number").input("falloff", FlowDataType.FLOAT, 12f, "number").output("density", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("terrain_density", "Terrain Density").input("continentalness", FlowDataType.FLOAT, 0f, "number").input("erosion", FlowDataType.FLOAT, 0f, "number").input("weirdness", FlowDataType.FLOAT, 0f, "number").input("depth", FlowDataType.FLOAT, 0f, "number").input("base", FlowDataType.FLOAT, 64f, "number").input("seed", FlowDataType.SEED, 0, "number").output("density", FlowDataType.FLOAT)),
            terrain(WorldGenNodeDefinition.builder("output_height", "Output Height").input("height", FlowDataType.FLOAT, 64f, "number")),
            terrain(WorldGenNodeDefinition.builder("output_density", "Output Density").input("density", FlowDataType.FLOAT, 0f, "number")),
            terrain(WorldGenNodeDefinition.builder("output_continentalness", "Output Continentalness").input("continentalness", FlowDataType.FLOAT, 0f, "number")),
            terrain(WorldGenNodeDefinition.builder("output_erosion", "Output Erosion").input("erosion", FlowDataType.FLOAT, 0f, "number")),
            terrain(WorldGenNodeDefinition.builder("output_weirdness", "Output Weirdness").input("weirdness", FlowDataType.FLOAT, 0f, "number")),
            terrain(WorldGenNodeDefinition.builder("output_depth", "Output Depth").input("depth", FlowDataType.FLOAT, 0f, "number")),
            terrain(WorldGenNodeDefinition.builder("output_temperature", "Output Temperature").input("temperature", FlowDataType.FLOAT, 0.5f, "number")),
            terrain(WorldGenNodeDefinition.builder("output_humidity", "Output Humidity").input("humidity", FlowDataType.FLOAT, 0.5f, "number")),
            biome(WorldGenNodeDefinition.builder("output_biome", "Output Biome").input("biome", FlowDataType.BIOME, "minecraft:plains", "searchable").input("temperature", FlowDataType.FLOAT, 0.5f, "number").input("humidity", FlowDataType.FLOAT, 0.5f, "number").input("keep_vanilla_features", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_structures", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_spawns", FlowDataType.BOOLEAN, false, "toggle")),
            surface(WorldGenNodeDefinition.builder("output_block", "Output Block").input("block", FlowDataType.BLOCK, null, "material").input("y", FlowDataType.FLOAT, 0f, "number").input("replace", FlowDataType.FLOAT, 1f, "number")),
            biome(WorldGenNodeDefinition.builder("biome_constant", "Biome Constant").input("biome", FlowDataType.BIOME, "minecraft:plains", "searchable").input("keep_vanilla_features", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_structures", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_spawns", FlowDataType.BOOLEAN, false, "toggle").output("biome", FlowDataType.BIOME)),
            biome(WorldGenNodeDefinition.builder("biome_profile", "Biome Profile").input("profile", FlowDataType.BIOME, "minecraft:plains", "searchable").input("keep_vanilla_features", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_structures", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_spawns", FlowDataType.BOOLEAN, false, "toggle").output("biome", FlowDataType.BIOME)),
            biome(WorldGenNodeDefinition.builder("biome_select", "Biome Select").input("mask", FlowDataType.BOOLEAN, false, "toggle").input("true_biome", FlowDataType.BIOME, "minecraft:forest", "searchable").input("false_biome", FlowDataType.BIOME, "minecraft:plains", "searchable").input("keep_vanilla_features", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_structures", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_spawns", FlowDataType.BOOLEAN, false, "toggle").output("biome", FlowDataType.BIOME)),
            biome(WorldGenNodeDefinition.builder("biome_blend", "Biome Blend").input("a", FlowDataType.BIOME, "minecraft:plains", "dropdown").input("b", FlowDataType.BIOME, "minecraft:forest", "dropdown").input("weight", FlowDataType.FLOAT, 0.5f, "number").output("biome", FlowDataType.BIOME)),
            biome(WorldGenNodeDefinition.builder("climate_map", "Climate Map").input("temperature", FlowDataType.FLOAT, 0.5f, "number").input("humidity", FlowDataType.FLOAT, 0.5f, "number").input("keep_vanilla_features", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_structures", FlowDataType.BOOLEAN, false, "toggle").input("keep_vanilla_spawns", FlowDataType.BOOLEAN, false, "toggle").output("biome", FlowDataType.BIOME)),
            biome(WorldGenNodeDefinition.builder("biome_climate_router", "Biome Climate Router").input("temperature", FlowDataType.FLOAT, 0.5f, "number").input("humidity", FlowDataType.FLOAT, 0.5f, "number").input("continentalness", FlowDataType.FLOAT, 0f, "number").input("erosion", FlowDataType.FLOAT, 0f, "number").input("weirdness", FlowDataType.FLOAT, 0f, "number").input("temperature_scale", FlowDataType.FLOAT, 1f, "number").input("humidity_scale", FlowDataType.FLOAT, 1f, "number").input("keep_vanilla_features", FlowDataType.BOOLEAN, true, "toggle").input("keep_vanilla_structures", FlowDataType.BOOLEAN, true, "toggle").input("keep_vanilla_spawns", FlowDataType.BOOLEAN, false, "toggle").input("seed", FlowDataType.SEED, 0, "number").output("biome", FlowDataType.BIOME)),
            biome(WorldGenNodeDefinition.builder("temperature", "Temperature").input("value", FlowDataType.FLOAT, 0.5f, "number").output("out", FlowDataType.FLOAT)),
            biome(WorldGenNodeDefinition.builder("humidity", "Humidity").input("value", FlowDataType.FLOAT, 0.5f, "number").output("out", FlowDataType.FLOAT)),
            biome(WorldGenNodeDefinition.builder("continentalness", "Continentalness").input("value", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT)),
            biome(WorldGenNodeDefinition.builder("erosion", "Erosion").input("value", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT)),
            biome(WorldGenNodeDefinition.builder("weirdness", "Weirdness").input("value", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT)),
            surface(WorldGenNodeDefinition.builder("surface_rule", "Surface Rule").input("top", FlowDataType.BLOCK, "minecraft:grass_block", "material").input("filler", FlowDataType.BLOCK, "minecraft:dirt", "material").output("surface", FlowDataType.BLOCK)),
            surface(WorldGenNodeDefinition.builder("material_layer", "Material Layer").input("block", FlowDataType.BLOCK, "minecraft:stone", "material").input("depth", FlowDataType.FLOAT, 3f, "number").output("surface", FlowDataType.BLOCK)),
            surface(WorldGenNodeDefinition.builder("height_band", "Height Band").input("min", FlowDataType.FLOAT, 0f, "number").input("max", FlowDataType.FLOAT, 320f, "number").output("mask", FlowDataType.BOOLEAN)),
            surface(WorldGenNodeDefinition.builder("slope_mask", "Slope Mask").input("min", FlowDataType.FLOAT, 0f, "number").input("max", FlowDataType.FLOAT, 1f, "number").output("mask", FlowDataType.BOOLEAN)),
            surface(WorldGenNodeDefinition.builder("beach_rule", "Beach Rule").input("sand", FlowDataType.BLOCK, "minecraft:sand", "material").output("surface", FlowDataType.BLOCK)),
            surface(WorldGenNodeDefinition.builder("underwater_rule", "Underwater Rule").input("block", FlowDataType.BLOCK, "minecraft:gravel", "material").output("surface", FlowDataType.BLOCK)),
            surface(WorldGenNodeDefinition.builder("snow_rule", "Snow Rule").input("block", FlowDataType.BLOCK, "minecraft:snow_block", "material").output("surface", FlowDataType.BLOCK)),
            cave(WorldGenNodeDefinition.builder("cave_noise", "Cave Noise").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.02f, "number").output("density", FlowDataType.FLOAT)),
            cave(WorldGenNodeDefinition.builder("worm_cave", "Worm Cave").input("radius", FlowDataType.FLOAT, 3f, "number").output("density", FlowDataType.FLOAT)),
            cave(WorldGenNodeDefinition.builder("cheese_cave", "Cheese Cave").input("threshold", FlowDataType.FLOAT, 0.6f, "number").output("density", FlowDataType.FLOAT)),
            cave(WorldGenNodeDefinition.builder("ravine", "Ravine").input("width", FlowDataType.FLOAT, 6f, "number").output("density", FlowDataType.FLOAT)),
            cave(WorldGenNodeDefinition.builder("carve_if", "Carve If").input("mask", FlowDataType.BOOLEAN, false, "toggle").input("density", FlowDataType.FLOAT, 0f, "number").output("density", FlowDataType.FLOAT)),
            cave(WorldGenNodeDefinition.builder("density_combine", "Density Combine").input("a", FlowDataType.FLOAT, 0f, "number").input("b", FlowDataType.FLOAT, 0f, "number").output("density", FlowDataType.FLOAT)),
            cave(WorldGenNodeDefinition.builder("cave_system", "Cave System").input("amount", FlowDataType.FLOAT, 1f, "number").input("scale", FlowDataType.FLOAT, 1f, "number").input("seed", FlowDataType.SEED, 0, "number").output("density", FlowDataType.FLOAT)),
            feature(WorldGenNodeDefinition.builder("ore_vein", "Ore Vein").input("block", FlowDataType.BLOCK, "minecraft:coal_ore", "material", List.of(), "Block Placed In The Vein").input("size", FlowDataType.FLOAT, 8f, "number", List.of(), "Average Blocks Per Vein").output("feature", FlowDataType.WORLDGEN_FEATURE)),
            feature(WorldGenNodeDefinition.builder("tree_feature", "Tree Feature").input("tree", FlowDataType.STRING, "TREE", "searchable", List.of(), "Vanilla Tree Configuration").output("feature", FlowDataType.WORLDGEN_FEATURE)),
            feature(WorldGenNodeDefinition.builder("vegetation_patch", "Vegetation Patch").input("block", FlowDataType.BLOCK, "minecraft:short_grass", "material", List.of(), "Plant Or Ground Block Used By The Patch").output("feature", FlowDataType.WORLDGEN_FEATURE)),
            feature(WorldGenNodeDefinition.builder("liquid_lake", "Liquid Lake").input("fluid", FlowDataType.BLOCK, "minecraft:water", "material", List.of(), "Fluid Used To Fill The Lake").output("feature", FlowDataType.WORLDGEN_FEATURE)),
            feature(WorldGenNodeDefinition.builder("disk", "Disk").input("block", FlowDataType.BLOCK, "minecraft:clay", "material", List.of(), "Block Used By The Disk").input("radius", FlowDataType.FLOAT, 4f, "number", List.of(), "Maximum Horizontal Radius").output("feature", FlowDataType.WORLDGEN_FEATURE)),
            feature(WorldGenNodeDefinition.builder("boulder", "Boulder").input("block", FlowDataType.BLOCK, "minecraft:mossy_cobblestone", "material", List.of(), "Block Used By The Boulder").output("feature", FlowDataType.WORLDGEN_FEATURE)),
            feature(WorldGenNodeDefinition.builder("placed_feature", "Catalog Feature").input("feature", FlowDataType.STRING, "minecraft:patch_grass", "searchable", List.of(), "Existing Placed Feature From The Selected Minecraft Version").output("feature", FlowDataType.WORLDGEN_FEATURE)),
            feature(WorldGenNodeDefinition.builder("scatter", "Scatter").input("previous", FlowDataType.WORLDGEN_FEATURES, "", null, List.of(), "Earlier Placements In This Chain").input("feature", FlowDataType.WORLDGEN_FEATURE, "", null, List.of(), "Feature Added By This Placement").input("count", FlowDataType.FLOAT, 8f, "number", List.of(), "Placement Attempts Per Chunk").input("chance", FlowDataType.FLOAT, 1f, "number", List.of(), "Chance For Each Chunk From 0 To 1").input("min_y", FlowDataType.FLOAT, -64f, "number", List.of(), "Lowest Allowed Block Height").input("max_y", FlowDataType.FLOAT, 319f, "number", List.of(), "Highest Allowed Block Height").input("biome", FlowDataType.BIOME, "", "searchable", List.of(), "Optional Biome Restriction").input("generation_step", FlowDataType.STRING, "vegetal_decoration", "dropdown", List.of("raw_generation", "lakes", "local_modifications", "underground_structures", "surface_structures", "strongholds", "underground_ores", "underground_decoration", "fluid_springs", "vegetal_decoration", "top_layer_modification"), "Vanilla Generation Step Used By The Feature").input("override_vanilla", FlowDataType.BOOLEAN, false, "toggle", List.of(), "Replace Vanilla Features In Matching Biomes").output("placement", FlowDataType.WORLDGEN_FEATURES)),
            feature(WorldGenNodeDefinition.builder("poisson_scatter", "Poisson Scatter").input("previous", FlowDataType.WORLDGEN_FEATURES, "", null, List.of(), "Earlier Placements In This Chain").input("feature", FlowDataType.WORLDGEN_FEATURE, "", null, List.of(), "Feature Added By This Placement").input("spacing", FlowDataType.FLOAT, 12f, "number", List.of(), "Minimum Distance Between Placements").input("min_y", FlowDataType.FLOAT, -64f, "number", List.of(), "Lowest Allowed Block Height").input("max_y", FlowDataType.FLOAT, 319f, "number", List.of(), "Highest Allowed Block Height").input("biome", FlowDataType.BIOME, "", "searchable", List.of(), "Optional Biome Restriction").input("generation_step", FlowDataType.STRING, "vegetal_decoration", "dropdown", List.of("raw_generation", "lakes", "local_modifications", "underground_structures", "surface_structures", "strongholds", "underground_ores", "underground_decoration", "fluid_springs", "vegetal_decoration", "top_layer_modification"), "Vanilla Generation Step Used By The Feature").input("override_vanilla", FlowDataType.BOOLEAN, false, "toggle", List.of(), "Replace Vanilla Features In Matching Biomes").output("placement", FlowDataType.WORLDGEN_FEATURES)),
            feature(WorldGenNodeDefinition.builder("biome_filter", "Biome Filter").input("biome", FlowDataType.BIOME, "minecraft:plains", "dropdown").output("mask", FlowDataType.BOOLEAN)),
            feature(WorldGenNodeDefinition.builder("height_filter", "Height Filter").input("min", FlowDataType.FLOAT, 0f, "number").input("max", FlowDataType.FLOAT, 320f, "number").output("mask", FlowDataType.BOOLEAN)),
            feature(WorldGenNodeDefinition.builder("chance_filter", "Chance Filter").input("chance", FlowDataType.FLOAT, 0.5f, "number").input("salt", FlowDataType.SEED, 0, "number").output("mask", FlowDataType.BOOLEAN)),
            structure(WorldGenNodeDefinition.builder("structure_placement", "Structure Placement").input("previous", FlowDataType.WORLDGEN_STRUCTURES, "", null, List.of(), "Earlier Structures In This Chain").input("structure_id", FlowDataType.STRING, "", "searchable", List.of(), "Vanilla Or ReSync Structure To Place").input("spacing", FlowDataType.FLOAT, 32f, "number", List.of(), "Average Distance Between Candidate Regions In Chunks").input("separation", FlowDataType.FLOAT, 8f, "number", List.of(), "Minimum Chunk Gap Inside A Candidate Region").input("salt", FlowDataType.SEED, 0, "number", List.of(), "Stable Randomization Salt").input("anchor", FlowDataType.STRING, "surface", "dropdown", List.of("surface", "origin", "buried"), "How The Structure's Vertical Origin Meets The Terrain").input("y_offset", FlowDataType.FLOAT, 0f, "number", List.of(), "Final Vertical Adjustment In Blocks").input("biome", FlowDataType.BIOME, "", "searchable", List.of(), "Optional Biome Restriction").input("terrain_match", FlowDataType.BOOLEAN, true, "toggle", List.of(), "Reject Placements Across Unsafe Height Changes").input("override_vanilla", FlowDataType.BOOLEAN, false, "toggle", List.of(), "Replace Vanilla Structures In Matching Biomes").output("structure", FlowDataType.WORLDGEN_STRUCTURES)),
            spawn(WorldGenNodeDefinition.builder("spawn_rule", "Spawn Rule").input("previous", FlowDataType.WORLDGEN_SPAWNS, "", null, List.of(), "Earlier Spawn Rules In This Chain").input("entity", FlowDataType.ENTITY_TYPE, "minecraft:zombie", "searchable", List.of(), "Entity Added By This Rule").input("category", FlowDataType.STRING, "monster", "dropdown", List.of("monster", "creature", "ambient", "axolotls", "underground_water_creature", "water_creature", "water_ambient", "misc"), "Minecraft Spawn Category").input("weight", FlowDataType.FLOAT, 10f, "number", List.of(), "Relative Selection Weight").input("min_group", FlowDataType.FLOAT, 1f, "number", List.of(), "Smallest Spawn Group").input("max_group", FlowDataType.FLOAT, 4f, "number", List.of(), "Largest Spawn Group").input("biome", FlowDataType.BIOME, "", "searchable", List.of(), "Optional Biome Restriction").input("min_y", FlowDataType.FLOAT, -64f, "number", List.of(), "Lowest Allowed Block Height").input("max_y", FlowDataType.FLOAT, 319f, "number", List.of(), "Highest Allowed Block Height").input("block_below", FlowDataType.BLOCK, "", "material", List.of(), "Optional Required Block Below The Spawn").input("min_light", FlowDataType.FLOAT, 0f, "number", List.of(), "Lowest Allowed Light Level").input("max_light", FlowDataType.FLOAT, 15f, "number", List.of(), "Highest Allowed Light Level").input("time", FlowDataType.STRING, "any", "dropdown", List.of("any", "day", "night"), "Allowed Time Of Day").input("weather", FlowDataType.STRING, "any", "dropdown", List.of("any", "clear", "rain", "thunder"), "Allowed Weather").input("override_vanilla", FlowDataType.BOOLEAN, false, "toggle", List.of(), "Replace Vanilla Spawns In Matching Biomes").output("spawn", FlowDataType.WORLDGEN_SPAWNS)),
            feature(WorldGenNodeDefinition.builder("output_features", "Output Features").input("placements", FlowDataType.WORLDGEN_FEATURES, "", null)),
            structure(WorldGenNodeDefinition.builder("output_structures", "Output Structures").input("placements", FlowDataType.WORLDGEN_STRUCTURES, "", null)),
            spawn(WorldGenNodeDefinition.builder("output_spawns", "Output Spawns").input("table", FlowDataType.WORLDGEN_SPAWNS, "", null))
        );
    }

    private WorldGenNodeDefinition terrain(WorldGenNodeDefinition.Builder builder) {
        return builder.category("Terrain").build();
    }

    private WorldGenNodeDefinition biome(WorldGenNodeDefinition.Builder builder) {
        return builder.category("Biomes").build();
    }

    private WorldGenNodeDefinition surface(WorldGenNodeDefinition.Builder builder) {
        return builder.category("Surface").build();
    }

    private WorldGenNodeDefinition cave(WorldGenNodeDefinition.Builder builder) {
        return builder.category("Caves").build();
    }

    private WorldGenNodeDefinition feature(WorldGenNodeDefinition.Builder builder) {
        return builder.category("Features").build();
    }

    private WorldGenNodeDefinition structure(WorldGenNodeDefinition.Builder builder) {
        return builder.category("Structures").build();
    }

    private WorldGenNodeDefinition spawn(WorldGenNodeDefinition.Builder builder) {
        return builder.category("Spawns").build();
    }

    private WorldGenGraph createDefaultGraph() {
        WorldGenGraph graph = new WorldGenGraph();
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        Map<String, Object> noiseValues = new HashMap<>();
        noiseValues.put("seed", 12001);
        noiseValues.put("frequency", 0.006f);
        nodes.put("terrain_noise_1", new WorldGenNode(qualifiedNodeType("simplex"), 80, 120, noiseValues));
        Map<String, Object> remapValues = new HashMap<>();
        remapValues.put("from_min", -1f);
        remapValues.put("from_max", 1f);
        remapValues.put("to_min", 58f);
        remapValues.put("to_max", 92f);
        nodes.put("height_remap_1", new WorldGenNode(qualifiedNodeType("remap"), 360, 120, remapValues));
        Map<String, Object> clampValues = new HashMap<>();
        clampValues.put("min", 48f);
        clampValues.put("max", 128f);
        nodes.put("height_clamp_1", new WorldGenNode(qualifiedNodeType("clamp"), 640, 120, clampValues));
        nodes.put("output_height_1", new WorldGenNode(qualifiedNodeType("output_height"), 920, 120, new HashMap<>()));
        graph.setNodes(nodes);
        graph.setConnections(new ArrayList<>(List.of(
            new WorldGenConnection("terrain_noise_1", "out", "height_remap_1", "in"),
            new WorldGenConnection("height_remap_1", "out", "height_clamp_1", "in"),
            new WorldGenConnection("height_clamp_1", "out", "output_height_1", "height")
        )));
        return graph;
    }

    private WorldGenProject createDefaultProject() {
        return createHybridProject();
    }

    private WorldGenProject createHybridProject() {
        WorldGenProject project = new WorldGenProject();
        project.getSettings().setGenerationMode(WorldGenGenerationMode.HYBRID.id());
        project.setTerrainGraph(createDefaultGraph());
        project.setBiomeGraph(createDefaultBiomeGraph());
        project.setSurfaceGraph(createDefaultSurfaceGraph());
        project.setCaveGraph(createDefaultCaveGraph());
        project.setFeatureGraph(createDefaultFeatureGraph());
        project.setStructureGraph(createDefaultStructureGraph());
        project.setSpawnGraph(createDefaultSpawnGraph());
        return project;
    }

    private WorldGenProject createVanillaProject() {
        WorldGenProject project = new WorldGenProject();
        project.getSettings().setGenerationMode(WorldGenGenerationMode.VANILLA.id());
        project.getSettings().setVanillaBiomesEnabled(true);
        project.getSettings().setVanillaFeaturesEnabled(true);
        project.getSettings().setVanillaStructuresEnabled(true);
        project.getSettings().setVanillaSpawnsEnabled(true);
        project.setTerrainGraph(emptyGraph("terrain"));
        project.setBiomeGraph(emptyGraph("biome"));
        project.setSurfaceGraph(emptyGraph("surface"));
        project.setCaveGraph(emptyGraph("cave"));
        project.setFeatureGraph(createDefaultFeatureGraph());
        project.setStructureGraph(createDefaultStructureGraph());
        project.setSpawnGraph(createDefaultSpawnGraph());
        return project;
    }

    private void applyProjectTemplate(WorldGenProject project, String templateName) {
        String normalized = templateName == null ? "" : templateName.toLowerCase(Locale.ROOT);
        if (WorldGenGenerationMode.resolve(project.getSettings().getGenerationMode()) == WorldGenGenerationMode.VANILLA) {
            project.getSettings().setTerrainTemplate(switch (normalized) {
                case "amplified" -> "amplified";
                case "large biomes" -> "large_biomes";
                default -> "overworld";
            });
            return;
        }
        switch (normalized) {
            case "alpine" -> {
                project.getSettings().setTerrainTemplate("alpine");
                setNodeInput(project.getTerrainGraph(), "terrain_noise_1", "frequency", 0.009f);
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_min", 72f);
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_max", 156f);
                setNodeInput(project.getTerrainGraph(), "height_clamp_1", "max", 224f);
                setNodeInput(project.getBiomeGraph(), "biome_climate_router_1", "temperature_scale", 0.75f);
                setNodeInput(project.getBiomeGraph(), "biome_climate_router_1", "humidity_scale", 1.15f);
            }
            case "islands" -> {
                project.getSettings().setTerrainTemplate("islands");
                project.getSettings().setSeaLevel(68);
                setNodeInput(project.getTerrainGraph(), "terrain_noise_1", "frequency", 0.012f);
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_min", 42f);
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_max", 86f);
                setNodeInput(project.getTerrainGraph(), "height_clamp_1", "min", 28f);
            }
            case "badlands" -> {
                project.getSettings().setTerrainTemplate("badlands");
                project.getSettings().setDefaultBlock("minecraft:terracotta");
                setNodeInput(project.getTerrainGraph(), "terrain_noise_1", "frequency", 0.0075f);
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_min", 64f);
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_max", 118f);
                setNodeInput(project.getBiomeGraph(), "output_biome_1", "biome", "minecraft:badlands");
                setNodeInput(project.getBiomeGraph(), "biome_climate_router_1", "temperature_scale", 1.45f);
                setNodeInput(project.getBiomeGraph(), "biome_climate_router_1", "humidity_scale", 0.55f);
            }
            case "frozen" -> {
                project.getSettings().setTerrainTemplate("frozen");
                project.getSettings().setDefaultFluid("minecraft:water");
                setNodeInput(project.getTerrainGraph(), "terrain_noise_1", "frequency", 0.005f);
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_min", 60f);
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_max", 96f);
                setNodeInput(project.getBiomeGraph(), "output_biome_1", "biome", "minecraft:snowy_plains");
                setNodeInput(project.getBiomeGraph(), "biome_climate_router_1", "temperature_scale", 0.45f);
                setNodeInput(project.getBiomeGraph(), "biome_climate_router_1", "humidity_scale", 1.25f);
            }
            case "caves" -> {
                project.getSettings().setTerrainTemplate("caves");
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_min", 48f);
                setNodeInput(project.getTerrainGraph(), "height_remap_1", "to_max", 80f);
                setNodeInput(project.getCaveGraph(), "cave_system_1", "amount", 1.6f);
                setNodeInput(project.getCaveGraph(), "cave_system_1", "scale", 1.35f);
                setNodeInput(project.getBiomeGraph(), "output_biome_1", "biome", "minecraft:dripstone_caves");
            }
            default -> project.getSettings().setTerrainTemplate("continental");
        }
    }

    private void setNodeInput(WorldGenGraph graph, String nodeId, String input, Object value) {
        if (graph == null || graph.getNodes() == null) {
            return;
        }
        WorldGenNode node = graph.getNodes().get(nodeId);
        if (node != null) {
            node.getInputValues().put(input, value);
        }
    }

    private WorldGenGraph createDefaultBiomeGraph() {
        WorldGenGraph graph = emptyGraph("biome");
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        Map<String, Object> routerValues = new HashMap<>();
        routerValues.put("temperature_scale", 1f);
        routerValues.put("humidity_scale", 1f);
        routerValues.put("keep_vanilla_features", true);
        routerValues.put("keep_vanilla_structures", true);
        routerValues.put("keep_vanilla_spawns", true);
        routerValues.put("seed", 23001);
        nodes.put("biome_climate_router_1", new WorldGenNode(qualifiedNodeType("biome_climate_router"), 80, 120, routerValues));
        Map<String, Object> biomeValues = new HashMap<>();
        biomeValues.put("biome", "minecraft:plains");
        biomeValues.put("temperature", 0.5f);
        biomeValues.put("humidity", 0.5f);
        biomeValues.put("keep_vanilla_features", true);
        biomeValues.put("keep_vanilla_structures", true);
        biomeValues.put("keep_vanilla_spawns", true);
        nodes.put("output_biome_1", new WorldGenNode(qualifiedNodeType("output_biome"), 360, 120, biomeValues));
        graph.setNodes(nodes);
        graph.setConnections(new ArrayList<>(List.of(new WorldGenConnection("biome_climate_router_1", "biome", "output_biome_1", "biome"))));
        return graph;
    }

    private WorldGenGraph createDefaultSurfaceGraph() {
        WorldGenGraph graph = emptyGraph("surface");
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        Map<String, Object> blockValues = new HashMap<>();
        blockValues.put("block", "minecraft:grass_block");
        blockValues.put("y", 0f);
        blockValues.put("replace", 1f);
        nodes.put("output_block_1", new WorldGenNode(qualifiedNodeType("output_block"), 220, 120, blockValues));
        graph.setNodes(nodes);
        return graph;
    }

    private WorldGenGraph createDefaultCaveGraph() {
        WorldGenGraph graph = emptyGraph("cave");
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        Map<String, Object> noiseValues = new HashMap<>();
        noiseValues.put("seed", 31001);
        noiseValues.put("amount", 0.8f);
        noiseValues.put("scale", 1f);
        nodes.put("cave_system_1", new WorldGenNode(qualifiedNodeType("cave_system"), 80, 120, noiseValues));
        Map<String, Object> carveValues = new HashMap<>();
        carveValues.put("mask", true);
        nodes.put("carve_if_1", new WorldGenNode(qualifiedNodeType("carve_if"), 330, 120, carveValues));
        graph.setNodes(nodes);
        graph.setConnections(new ArrayList<>(List.of(new WorldGenConnection("cave_system_1", "density", "carve_if_1", "density"))));
        return graph;
    }

    private WorldGenGraph createDefaultFeatureGraph() {
        WorldGenGraph graph = emptyGraph("feature");
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        nodes.put("output_features_1", new WorldGenNode(qualifiedNodeType("output_features"), 360, 120, new HashMap<>()));
        graph.setNodes(nodes);
        return graph;
    }

    private WorldGenGraph createDefaultStructureGraph() {
        WorldGenGraph graph = emptyGraph("structure");
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        nodes.put("output_structures_1", new WorldGenNode(qualifiedNodeType("output_structures"), 360, 120, new HashMap<>()));
        graph.setNodes(nodes);
        return graph;
    }

    private WorldGenGraph createDefaultSpawnGraph() {
        WorldGenGraph graph = emptyGraph("spawn");
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        nodes.put("output_spawns_1", new WorldGenNode(qualifiedNodeType("output_spawns"), 80, 120, new HashMap<>()));
        graph.setNodes(nodes);
        return graph;
    }

    private WorldGenGraph emptyGraph(String id) {
        WorldGenGraph graph = new WorldGenGraph();
        graph.setId(id);
        return graph;
    }

    private String qualifiedNodeType(String nodeId) {
        return WorldGenCatalogProjection.canonicalReference("worldgen", nodeId);
    }

    private boolean sameServer(String left, String right) {
        return WorldGenCatalogProjection.normalizeBaseServerId(left)
            .equals(WorldGenCatalogProjection.normalizeBaseServerId(right));
    }

    private ReSyncFlowClient flowClient(String serverId) {
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager == null) {
            return null;
        }
        FlowManager.ServerConnectionToken sourceToken = sourceConnectionToken.get();
        if (sourceToken != null && sameServer(connectionServerId(serverId), sourceToken.serverId())) {
            return flowManager.isCurrentServerConnection(sourceToken) ? sourceToken.source() : null;
        }
        return flowManager.ensureFlowClient(connectionServerId(serverId));
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

}
