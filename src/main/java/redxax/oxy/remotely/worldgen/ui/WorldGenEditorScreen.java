package redxax.oxy.remotely.worldgen.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.FlowDebugController;
import redxax.oxy.remotely.data.flow.ReSyncProtocolContract;
import redxax.oxy.remotely.data.flow.player.PlayerDossier;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.ui.FlowGraphDesignerScreen;
import redxax.oxy.remotely.flow.ui.FlowNodeWidget;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborativeView;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import redxax.oxy.remotely.worldgen.data.WorldGenProjectSettings;
import redxax.oxy.remotely.worldgen.data.WorldGenStage;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.worldgen.contract.WorldGenGenerationMode;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class WorldGenEditorScreen extends FlowGraphDesignerScreen implements ReSyncCollaborativeView {
    private static final int MAX_DEFERRED_INTERACTIONS = 512;
    private static final int MAX_DEFERRED_USER_INTERACTIONS = 480;
    private static final int MAX_DEFERRED_COLLABORATIONS = 8;
    private static final long DEFERRED_COLLABORATION_RESERVATION_BYTES =
        ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES;
    private final String actualServerId;
    private final Screen parentScreen;
    private final String boundProjectId;
    private final WorldGenManager manager = WorldGenManager.getInstance();
    private WorldGenProject project;
    private String pendingProjectRequest;
    private final String previewId;
    private WorldGenStage activeStage = WorldGenStage.TERRAIN;
    private String previewEnvironment = "NORMAL";
    private long previewSeed;
    private String previewPlayerUuid = "";
    private String previewPlayerName = "";
    private ItemSelectorWidget activePlayerSelector;
    private WorldGenNavigationPanel navigationPanel;
    private SettingsWidgets settingsWidgets;
    private final Deque<DeferredInteraction> deferredInteractions = new ArrayDeque<>();
    private final List<DeferredCollaboration> deferredCollaborations = new ArrayList<>();
    private long deferredCollaborationBytes;
    private volatile long editorGeneration = 1L;
    private long viewPreparationSequence;
    private long activeViewPreparation;
    private long preparationSequence;
    private long activePreparation;
    private long baselineMutationVersion;
    private long submittedMutationVersion = -1L;
    private boolean replayingInteractions;
    private long replayMutationVersion = -1L;
    private boolean saveAfterPreparation;
    private boolean previewAfterPreparation;
    private String duplicateAfterPreparation = "";
    private boolean preparationBusyNotified;
    private WorldGenManager.PreparedEditorProjectSave preparedProject;
    private WorldGenManager.PreparedEditorProjectSave pendingSaveProject;
    private boolean initialProjectPending;
    private WorldGenProject pendingInitialProject;
    private WorldGenProject pendingNewProject;
    private long viewPreparationRetryAt;
    private long projectRequestSequence;
    private long pendingProjectRequestSequence;

    @Override
    public JsonObject collaborationDocument() {
        StableWorkspaceDocument stable = stableWorkspaceDocument();
        if (stable != null && stable.generation() == workspaceSnapshotGeneration()
            && stable.mutationVersion() == workspaceMutationVersion()) {
            return stable.document();
        }
        return null;
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<ReSyncCollaborativeView.CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle();
        long editVersion = collaborationEditVersion();
        StableWorkspaceDocument stable = stableWorkspaceDocument();
        if (preparationPending() || stable == null || stable.generation() != workspaceSnapshotGeneration()
            || stable.mutationVersion() != editVersion) {
            try {
                completion.accept(new ReSyncCollaborativeView.CollaborationDocumentSnapshot(this, lifecycle,
                    editVersion, null, new IllegalStateException("World Generation collaboration snapshot is pending")));
            } catch (RuntimeException | Error ignored) {
            }
            return false;
        }
        String projectId = safeProjectId(project);
        boolean admitted = manager.prepareCollaborationDocumentSnapshot(actualServerId, this, stable.document(),
            projectId, lifecycle, editVersion, stable.generation(), stable.mutationVersion(), snapshot -> {
                boolean current = snapshot != null && snapshot.successful() && snapshot.owner() == this
                    && lifecycle == collaborationLifecycle() && editVersion == collaborationEditVersion()
                    && stable == stableWorkspaceDocument()
                    && stable.generation() == workspaceSnapshotGeneration()
                    && stable.mutationVersion() == workspaceMutationVersion();
                ReSyncCollaborativeView.CollaborationDocumentSnapshot delivered = current ? snapshot
                    : new ReSyncCollaborativeView.CollaborationDocumentSnapshot(this, lifecycle, editVersion, null,
                        snapshot != null && snapshot.failure() != null ? snapshot.failure()
                            : new IllegalStateException("World Generation collaboration snapshot expired"));
                try {
                    completion.accept(delivered);
                } catch (RuntimeException | Error ignored) {
                }
            });
        if (!admitted) {
            try {
                completion.accept(new ReSyncCollaborativeView.CollaborationDocumentSnapshot(this, lifecycle,
                    editVersion, null, new IllegalStateException("World Generation collaboration snapshot is busy")));
            } catch (RuntimeException | Error ignored) {
            }
            return false;
        }
        return true;
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        applyCollaborationDocument(document, patches, result -> {
        });
    }

    @Override
    public boolean applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches,
                                              Consumer<ReSyncCollaborativeView.CollaborationDocumentApplyResult> completion) {
        if (completion == null) {
            return false;
        }
        String admissionProjectId = safeProjectId(project);
        long admissionLifecycle = collaborationLifecycle();
        long admissionWorkspaceGeneration = workspaceSnapshotGeneration();
        long admissionMutationVersion = workspaceMutationVersion();
        CollaborationApplySettlement settlement = new CollaborationApplySettlement(completion, admissionProjectId,
            admissionLifecycle, admissionWorkspaceGeneration, admissionMutationVersion);
        DeferredCollaboration deferred = new DeferredCollaboration(document, patches == null ? List.of() : patches,
            settlement);
        boolean reserved = false;
        if (preparationPending()) {
            try {
                reserved = reserveDeferredCollaboration(deferred);
            } catch (RuntimeException | Error exception) {
                settlement.fail(asRuntimeException("World Generation collaboration request was rejected", exception));
                return false;
            }
            if (!reserved) {
                settlement.fail(new IllegalStateException("World Generation collaboration is busy"));
                return false;
            }
        }
        InteractionAdmission admission;
        try {
            admission = deferInteraction("collaboration", deferred, MAX_DEFERRED_INTERACTIONS);
        } catch (RuntimeException | Error exception) {
            if (reserved) {
                deferred.releaseReservation();
            }
            settlement.fail(asRuntimeException("World Generation collaboration request was rejected", exception));
            return false;
        }
        if (admission == InteractionAdmission.EXECUTE && reserved) {
            deferred.releaseReservation();
        }
        if (admission != InteractionAdmission.EXECUTE) {
            if (admission == InteractionAdmission.REJECTED) {
                if (reserved) {
                    deferred.releaseReservation();
                }
                settlement.fail(new IllegalStateException("World Generation collaboration is busy"));
            } else {
                try {
                    deferredCollaborations.add(deferred);
                } catch (RuntimeException | Error exception) {
                    deferredInteractions.removeIf(item -> item.action() == deferred);
                    if (reserved) {
                        deferred.releaseReservation();
                    }
                    settlement.fail(asRuntimeException("World Generation collaboration deferral failed", exception));
                    return false;
                }
            }
            return admission == InteractionAdmission.DEFERRED;
        }
        return applyCollaborationDocumentNow(document, patches == null ? List.of() : patches, settlement);
    }

    private boolean applyCollaborationDocumentNow(JsonObject document, List<WorkspacePatch<JsonElement>> patches,
                                                  CollaborationApplySettlement settlement) {
        String expectedProjectId = settlement.projectId();
        if (document == null || expectedProjectId.isBlank() || !acceptsProjectId(expectedProjectId)
            || !settlement.admissionCurrent()) {
            settlement.fail(new IllegalArgumentException("World Generation workspace project identity is invalid"));
            return false;
        }
        long mutationVersion = workspaceMutationVersion();
        boolean admitted = beginEditorDocumentPreparation(document, patches, expectedProjectId, activeStage, mutationVersion,
            prepared -> {
                boolean applied = false;
                RuntimeException failure = null;
                long beforeEditVersion;
                if (!settlement.admissionCurrent()) {
                    beforeEditVersion = collaborationEditVersion();
                    failure = new IllegalStateException("World Generation collaboration admission expired");
                } else {
                    beforeEditVersion = collaborationEditVersion();
                    try {
                        applied = applyCollaborativeProject(prepared.prepared(), prepared.patches(), expectedProjectId);
                    } catch (RuntimeException exception) {
                        failure = exception;
                    } catch (Error error) {
                        failure = new IllegalStateException("World Generation collaboration apply failed", error);
                    }
                }
                long afterEditVersion = collaborationEditVersion();
                settlement.complete(collaborationLifecycle(), beforeEditVersion, afterEditVersion, applied,
                    applied ? null : failure != null ? failure
                        : new IllegalStateException("World Generation workspace application expired"));
            }, failure -> {
                settlement.fail(failure == null ? new IllegalStateException("World Generation collaboration preparation failed") : failure);
            });
        if (!admitted) {
            preparationUnavailable();
            settlement.fail(new IllegalStateException("World Generation collaboration preparation was rejected"));
        }
        return admitted;
    }

    @Override
    public long collaborationLifecycle() {
        return editorGeneration;
    }

    @Override
    public long collaborationEditVersion() {
        return workspaceMutationVersion();
    }

    @Override
    protected void captureStableWorkspaceDocument(long mutationVersion) {
        if (replayingInteractions) {
            replayMutationVersion = Math.max(replayMutationVersion, mutationVersion);
            return;
        }
        if (preparationPending()) {
            return;
        }
        long preparation = ++preparationSequence;
        long generation = editorGeneration;
        long workspaceGeneration = workspaceSnapshotGeneration();
        String catalogFence = workspaceCatalogFence();
        activePreparation = preparation;
        boolean admitted = manager.prepareFrozenEditorProject(actualServerId, project, graph, activeStage, generation,
            mutationVersion, workspaceGeneration,
            result -> completeProjectPreparation(preparation, generation, mutationVersion, workspaceGeneration,
                catalogFence, result));
        if (!admitted) {
            activePreparation = 0L;
            replayDeferredInteractions();
            preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
        }
    }

    @Override
    protected boolean workspaceSnapshotPending() {
        return preparationPending();
    }

    private boolean preparationPending() {
        return activePreparation != 0L || activeViewPreparation != 0L || initialProjectPending || pendingNewProject != null;
    }

    private boolean beginEditorProjectPreparation(WorldGenProject source, WorldGenStage stage, long mutationVersion,
                                                  Consumer<WorldGenManager.PreparedEditorProjectSave> apply) {
        if (activePreparation != 0L || activeViewPreparation != 0L || stage == null || apply == null) {
            return false;
        }
        long preparation = ++viewPreparationSequence;
        long generation = editorGeneration;
        long workspaceGeneration = workspaceSnapshotGeneration();
        String catalogFence = workspaceCatalogFence();
        activeViewPreparation = preparation;
        boolean admitted = manager.prepareEditorProjectView(actualServerId, source, stage, generation, mutationVersion,
            workspaceGeneration, result -> completeEditorViewPreparation(preparation, generation, mutationVersion,
                workspaceGeneration, catalogFence, safeProjectId(source), apply, null, result));
        if (!admitted) {
            activeViewPreparation = 0L;
            preparationUnavailable();
            replayDeferredInteractions();
            scheduleViewPreparationRetry();
        }
        return admitted;
    }

    private boolean beginEditorDocumentPreparation(JsonObject document, List<WorkspacePatch<JsonElement>> patches,
                                                   String expectedProjectId, WorldGenStage stage, long mutationVersion,
                                                   Consumer<WorldGenManager.EditorCollaborationPreparation> apply,
                                                   Consumer<RuntimeException> failure) {
        if (preparationPending() || document == null || expectedProjectId == null || expectedProjectId.isBlank()
            || stage == null || apply == null) {
            return false;
        }
        long preparation = ++viewPreparationSequence;
        long generation = editorGeneration;
        long workspaceGeneration = workspaceSnapshotGeneration();
        String catalogFence = workspaceCatalogFence();
        activeViewPreparation = preparation;
        boolean admitted = manager.prepareEditorCollaborationDocument(actualServerId, document, patches,
            expectedProjectId, stage, generation, mutationVersion, workspaceGeneration,
            result -> completeEditorCollaborationPreparation(preparation, generation, mutationVersion,
                workspaceGeneration, catalogFence, expectedProjectId, apply, failure, result));
        if (!admitted) {
            activeViewPreparation = 0L;
            preparationUnavailable();
            replayDeferredInteractions();
        }
        return admitted;
    }

    private void completeEditorCollaborationPreparation(long preparation, long generation, long mutationVersion,
                                                        long workspaceGeneration, String catalogFence,
                                                        String expectedProjectId,
                                                        Consumer<WorldGenManager.EditorCollaborationPreparation> apply,
                                                        Consumer<RuntimeException> failure,
                                                        WorldGenManager.EditorCollaborationPreparation result) {
        if (preparation != activeViewPreparation || generation != editorGeneration) {
            if (failure != null) {
                try {
                    failure.accept(new IllegalStateException("World Generation editor preparation was superseded"));
                } catch (RuntimeException | Error ignored) {
                }
            }
            return;
        }
        activeViewPreparation = 0L;
        WorldGenManager.PreparedEditorProjectSave prepared = result == null ? null : result.prepared();
        boolean completed = result != null && result.ready() && prepared != null
            && prepared.editorGeneration() == generation && prepared.mutationVersion() == mutationVersion
            && prepared.workspaceGeneration() == workspaceGeneration && prepared.activeGraph() != null
            && manager.isCurrentPreparedEditorProject(prepared)
            && workspaceSnapshotGeneration() == workspaceGeneration
            && workspaceMutationVersion() == mutationVersion
            && catalogFence.equals(workspaceCatalogFence())
            && (expectedProjectId == null || expectedProjectId.isBlank()
                || expectedProjectId.equals(safeProjectId(prepared.project())));
        try {
            if (completed) {
                try {
                    apply.accept(result);
                } catch (RuntimeException | Error exception) {
                    if (failure != null) {
                        failure.accept(exception instanceof RuntimeException runtime
                            ? runtime : new IllegalStateException("World Generation collaboration apply failed", exception));
                    } else {
                        preparationUnavailable();
                        scheduleViewPreparationRetry();
                    }
                }
            } else {
                preparationUnavailable();
                scheduleViewPreparationRetry();
                if (failure != null) {
                    failure.accept(new IllegalStateException("World Generation collaboration preparation failed"));
                }
            }
        } finally {
            try {
                replayDeferredInteractions();
            } finally {
                if (!preparationPending() && saveAfterPreparation) {
                    saveAfterPreparation = false;
                    try {
                        saveGraph();
                    } catch (RuntimeException | Error exception) {
                        saveAfterPreparation = true;
                        preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
                    }
                }
            }
        }
    }

    private boolean beginEditorStagePreparation(WorldGenStage stage, long mutationVersion,
                                                Consumer<WorldGenManager.PreparedEditorProjectSave> apply) {
        if (preparationPending() || stage == null || project == null || graph == null || apply == null) {
            return false;
        }
        long preparation = ++viewPreparationSequence;
        long generation = editorGeneration;
        long workspaceGeneration = workspaceSnapshotGeneration();
        String catalogFence = workspaceCatalogFence();
        WorldGenStage currentStage = activeStage;
        WorldGenProject source = project;
        FlowGraph currentGraph = graph;
        activeViewPreparation = preparation;
        boolean admitted = manager.prepareEditorStageSwitch(actualServerId, source, currentGraph, currentStage, stage,
            generation, mutationVersion, workspaceGeneration,
            result -> completeEditorViewPreparation(preparation, generation, mutationVersion, workspaceGeneration,
                catalogFence, safeProjectId(source), apply, null, result));
        if (!admitted) {
            activeViewPreparation = 0L;
            preparationUnavailable();
            replayDeferredInteractions();
        }
        return admitted;
    }

    private void completeEditorViewPreparation(long preparation, long generation, long mutationVersion,
                                               long workspaceGeneration, String catalogFence, String expectedProjectId,
                                               Consumer<WorldGenManager.PreparedEditorProjectSave> apply,
                                               Consumer<RuntimeException> failure,
                                               WorldGenManager.EditorProjectPreparation result) {
        if (preparation != activeViewPreparation || generation != editorGeneration) {
            if (failure != null) {
                failure.accept(new IllegalStateException("World Generation editor preparation was superseded"));
            }
            return;
        }
        activeViewPreparation = 0L;
        WorldGenManager.PreparedEditorProjectSave prepared = result == null ? null : result.prepared();
        boolean completed = result != null && result.ready() && prepared != null
            && prepared.editorGeneration() == generation && prepared.mutationVersion() == mutationVersion
            && prepared.workspaceGeneration() == workspaceGeneration && prepared.activeGraph() != null
            && manager.isCurrentPreparedEditorProject(prepared)
            && catalogFence.equals(workspaceCatalogFence())
            && (expectedProjectId == null || expectedProjectId.isBlank()
                || expectedProjectId.equals(safeProjectId(prepared.project())));
        try {
            if (completed) {
                try {
                    apply.accept(prepared);
                } catch (RuntimeException | Error exception) {
                    if (failure != null) {
                        RuntimeException wrapped = exception instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException("World Generation editor preparation apply failed", exception);
                        failure.accept(wrapped);
                    } else {
                        preparationUnavailable();
                        scheduleViewPreparationRetry();
                    }
                }
            } else {
                preparationUnavailable();
                scheduleViewPreparationRetry();
                if (failure != null) {
                    failure.accept(new IllegalStateException("World Generation collaboration preparation failed"));
                }
            }
        } finally {
            try {
                replayDeferredInteractions();
            } finally {
                if (!preparationPending() && saveAfterPreparation) {
                    saveAfterPreparation = false;
                    try {
                        saveGraph();
                    } catch (RuntimeException | Error exception) {
                        saveAfterPreparation = true;
                        preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
                    }
                }
            }
        }
    }

    private void scheduleViewPreparationRetry() {
        viewPreparationRetryAt = System.currentTimeMillis() + 100L;
    }

    private void retryPendingEditorProjectPreparation() {
        if ((!initialProjectPending && pendingNewProject == null) || activePreparation != 0L
            || activeViewPreparation != 0L || System.currentTimeMillis() < viewPreparationRetryAt) {
            return;
        }
        viewPreparationRetryAt = 0L;
        long mutationVersion = workspaceMutationVersion();
        if (initialProjectPending) {
            WorldGenProject source = pendingInitialProject;
            if (!beginEditorProjectPreparation(source, WorldGenStage.TERRAIN, mutationVersion,
                this::applyInitialProject)) {
                scheduleViewPreparationRetry();
            }
            return;
        }
        WorldGenProject candidate = pendingNewProject;
        if (!beginEditorProjectPreparation(candidate, WorldGenStage.TERRAIN, mutationVersion,
            prepared -> applyNewProject(candidate, prepared))) {
            if (pendingNewProject == candidate) {
                scheduleViewPreparationRetry();
            }
        }
    }

    private void applyInitialProject(WorldGenManager.PreparedEditorProjectSave prepared) {
        if (prepared == null || !initialProjectPending) {
            return;
        }
        if (!publishStableWorkspaceDocument(prepared.workspaceGeneration(), prepared.mutationVersion(),
            prepared.workspaceDocument())) {
            preparationUnavailable();
            scheduleViewPreparationRetry();
            return;
        }
        initialProjectPending = false;
        pendingInitialProject = null;
        project = prepared.project();
        activeStage = WorldGenStage.TERRAIN;
        baselineMutationVersion = workspaceMutationVersion();
        submittedMutationVersion = -1L;
        preparedProject = prepared;
        adoptPreparedGraph(prepared.activeGraph(), true);
        syncSettingsWidgets(project.getSettings());
        refreshStatus();
    }

    private void applyNewProject(WorldGenProject candidate, WorldGenManager.PreparedEditorProjectSave prepared) {
        if (candidate == null || pendingNewProject != candidate || prepared == null
            || !safeProjectId(candidate).equals(safeProjectId(prepared.project()))) {
            return;
        }
        if (!publishStableWorkspaceDocument(prepared.workspaceGeneration(), prepared.mutationVersion(),
            prepared.workspaceDocument())) {
            preparationUnavailable();
            scheduleViewPreparationRetry();
            return;
        }
        advanceEditorGeneration();
        pendingNewProject = null;
        project = prepared.project();
        activeStage = WorldGenStage.TERRAIN;
        adoptPreparedGraph(prepared.activeGraph(), true);
        baselineMutationVersion = workspaceMutationVersion();
        submittedMutationVersion = -1L;
        preparedProject = null;
        markWorkspaceMutation();
        refreshStatus();
    }

    private boolean applyLoadedProject(WorldGenManager.PreparedEditorProjectSave prepared, String expectedProjectId) {
        if (prepared == null || !acceptsProjectId(expectedProjectId)
            || !expectedProjectId.equals(safeProjectId(prepared.project()))) {
            return false;
        }
        if (!publishStableWorkspaceDocument(prepared.workspaceGeneration(), prepared.mutationVersion(),
            prepared.workspaceDocument())) {
            preparationUnavailable();
            scheduleViewPreparationRetry();
            return false;
        }
        advanceEditorGeneration();
        project = prepared.project();
        activeStage = WorldGenStage.TERRAIN;
        baselineMutationVersion = workspaceMutationVersion();
        submittedMutationVersion = -1L;
        preparedProject = null;
        adoptPreparedGraph(prepared.activeGraph(), true);
        syncSettingsWidgets(project.getSettings());
        refreshStatus();
        return true;
    }

    private boolean applyCollaborativeProject(WorldGenManager.PreparedEditorProjectSave prepared,
                                              List<WorkspacePatch<JsonElement>> patches, String expectedProjectId) {
        if (prepared == null || !expectedProjectId.equals(safeProjectId(project))
            || !expectedProjectId.equals(safeProjectId(prepared.project()))) {
            return false;
        }
        if (!publishStableWorkspaceDocument(prepared.workspaceGeneration(), prepared.mutationVersion(),
            prepared.workspaceDocument())) {
            preparationUnavailable();
            scheduleViewPreparationRetry();
            return false;
        }
        advanceEditorGeneration();
        project = prepared.project();
        baselineMutationVersion = workspaceMutationVersion();
        submittedMutationVersion = -1L;
        preparedProject = null;
        adoptPreparedGraph(prepared.activeGraph(), true);
        onWorkspaceGraphApplied(prepared.workspaceDocument(), patches == null ? List.of() : patches);
        syncSettingsWidgets(project.getSettings());
        refreshStatus();
        return true;
    }

    public WorldGenEditorScreen(String serverId, ClientServerView server, Screen parent) {
        this(serverId, server, parent, null, "");
    }

    public WorldGenEditorScreen(String serverId, ClientServerView server, Screen parent, WorldGenProject project) {
        this(serverId, server, parent, project, "");
    }

    public WorldGenEditorScreen(String serverId, ClientServerView server, Screen parent, WorldGenProject project,
                                String boundProjectId) {
        super(new FlowGraph(), serverId, parent);
        this.actualServerId = serverId;
        this.parentScreen = parent;
        this.boundProjectId = boundProjectId == null ? "" : boundProjectId.strip();
        if (!this.boundProjectId.isBlank() && (project == null || !this.boundProjectId.equals(safeProjectId(project)))) {
            throw new IllegalArgumentException("Embedded World Generation project identity does not match the Studio document");
        }
        WorldGenProject initialProject = project != null ? project : new WorldGenProject();
        if (project == null && !this.boundProjectId.isBlank()) {
            initialProject.setId(this.boundProjectId);
        }
        this.project = initialProject;
        this.initialProjectPending = project != null || this.boundProjectId.isBlank();
        this.pendingInitialProject = project;
        this.previewId = "worldgen_" + sanitizePreviewId(serverId);
    }

    public String getActualServerId() {
        return actualServerId;
    }

    @Override
    public void init() {
        super.init();
        navigationPanel = new WorldGenNavigationPanel(this);
        manager.ensureLocalDefinitions(actualServerId);
        manager.requestRegistry(actualServerId);
        manager.requestProjectList(actualServerId);
        if (initialProjectPending && activeViewPreparation == 0L) {
            long mutationVersion = workspaceMutationVersion();
            if (!beginEditorProjectPreparation(pendingInitialProject, WorldGenStage.TERRAIN,
                mutationVersion, this::applyInitialProject)) {
                preparationUnavailable();
                scheduleViewPreparationRetry();
            }
        }
        if (stableWorkspaceDocument() == null && !preparationPending()) {
            captureStableWorkspaceDocument(workspaceMutationVersion());
        }
    }

    @Override
    public void tick() {
        super.tick();
        retryPendingEditorProjectPreparation();
    }

    @Override
    public String getDesktopAppId() {
        return "worldgen-editor";
    }

    @Override
    public String getDesktopAppTitle() {
        return "World Generation";
    }

    @Override
    public String getDesktopAppIconPath() {
        return "node.png";
    }

    @Override
    public String collaborationScope() {
        return "worldgen-stage:" + activeStage.name().toLowerCase(Locale.ROOT);
    }

    @Override
    protected boolean showExtractButton() {
        return false;
    }

    @Override
    protected boolean showDebugControls() {
        return false;
    }

    @Override
    protected FlowDebugController debugController() {
        return null;
    }

    @Override
    protected boolean useStrictTypeCompatibility() {
        return true;
    }

    @Override
    protected boolean allowFlowPins() {
        return false;
    }

    @Override
    protected boolean canConnect(FlowNodeWidget sourceWidget, String sourcePin, FlowNodeWidget targetWidget, String targetPin) {
        return super.canConnect(sourceWidget, sourcePin, targetWidget, targetPin);
    }

    @Override
    protected Map<String, Object> optionCatalogContext() {
        return Map.of(WorldGenTargetVersion.OPTION_CONTEXT_KEY, manager.targetVersion(actualServerId, project.getSettings().getTargetVersion()));
    }

    void switchStage(WorldGenStage stage) {
        if (deferInteraction("stage", () -> switchStage(stage)) != InteractionAdmission.EXECUTE) {
            return;
        }
        if (stage == null || stage == activeStage) {
            return;
        }
        long mutationVersion = workspaceMutationVersion();
        if (!beginEditorStagePreparation(stage, mutationVersion, prepared -> {
            if (!publishStableWorkspaceDocument(prepared.workspaceGeneration(), prepared.mutationVersion(),
                prepared.workspaceDocument())) {
                preparationUnavailable();
                scheduleViewPreparationRetry();
                return;
            }
            advanceEditorGeneration();
            project = prepared.project();
            activeStage = stage;
            adoptPreparedGraph(prepared.activeGraph(), true);
            baselineMutationVersion = workspaceMutationVersion();
            submittedMutationVersion = -1L;
            preparedProject = null;
            markWorkspaceMutation();
            refreshStatus();
        })) {
            preparationUnavailable();
        }
    }

    int stageNodeCount(WorldGenStage stage) {
        return stage == activeStage ? graph.getNodes().size() : project.graph(stage).getNodes().size();
    }

    WorldGenStage activeStage() {
        return activeStage;
    }

    String projectId() {
        return project.getId();
    }

    String generationMode() {
        return WorldGenGenerationMode.resolve(project.getSettings().getGenerationMode()).displayName();
    }

    @Override
    protected void onSave() {
        if (!canMutateWorldGen()) {
            return;
        }
        super.onSave();
    }

    @Override
    protected void saveGraph() {
        if (!canMutateWorldGen()) {
            return;
        }
        if (preparationPending()) {
            saveAfterPreparation = true;
            return;
        }
        StableWorkspaceDocument stable = stableWorkspaceDocument();
        if (preparedProject == null || stable == null
            || preparedProject.editorGeneration() != editorGeneration
            || preparedProject.workspaceGeneration() != stable.generation()
            || preparedProject.mutationVersion() != stable.mutationVersion()) {
            saveAfterPreparation = true;
            captureStableWorkspaceDocument(workspaceMutationVersion());
            return;
        }
        admitPreparedProject(preparedProject);
    }

    @Override
    protected boolean saveCollaborativeWorkspace() {
        if (!canMutateWorldGen()) {
            return false;
        }
        saveGraph();
        return true;
    }

    public boolean requestProjectSave(String projectId) {
        return acceptsProjectId(projectId) && safeProjectId(project).equals(projectId.strip())
            && saveCollaborativeWorkspace();
    }

    private void completeProjectPreparation(long preparation, long generation, long mutationVersion,
                                            long workspaceGeneration, String catalogFence,
                                            WorldGenManager.EditorProjectPreparation result) {
        if (preparation != activePreparation || generation != editorGeneration) {
            return;
        }
        activePreparation = 0L;
        boolean completed = result != null && result.ready() && result.prepared().editorGeneration() == generation
            && result.prepared().mutationVersion() == mutationVersion
            && result.prepared().workspaceGeneration() == workspaceGeneration
            && catalogFence.equals(workspaceCatalogFence())
            && publishStableWorkspaceDocument(workspaceGeneration, mutationVersion, result.prepared().workspaceDocument());
        try {
            if (!completed) {
                notifyPreparationDelay();
            } else {
                preparedProject = result.prepared();
                preparationBusyNotified = false;
                if (saveAfterPreparation) {
                    saveAfterPreparation = false;
                    try {
                        admitPreparedProject(preparedProject);
                    } catch (RuntimeException | Error exception) {
                        if (pendingSaveProject == preparedProject) {
                            settlePreparedProjectSave(preparedProject,
                                new WorldGenManager.EditorProjectSaveSettlement(preparedProject, false,
                                    "WORLDGEN_PROJECT_SAVE_FAILED"));
                        } else {
                            saveAfterPreparation = true;
                            preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
                        }
                    }
                }
                if (previewAfterPreparation) {
                    previewAfterPreparation = false;
                    try {
                        requestPreparedPreview(preparedProject);
                    } catch (RuntimeException | Error exception) {
                        previewAfterPreparation = true;
                        preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
                    }
                }
                if (!duplicateAfterPreparation.isBlank()) {
                    String duplicateId = duplicateAfterPreparation;
                    duplicateAfterPreparation = "";
                    try {
                        prepareProjectCopy(preparedProject, duplicateId);
                    } catch (RuntimeException | Error exception) {
                        duplicateAfterPreparation = duplicateId;
                        preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
                    }
                }
            }
        } finally {
            try {
                replayDeferredInteractions();
            } finally {
                if (!completed && activePreparation == 0L) {
                    preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
                }
            }
        }
    }

    private void admitPreparedProject(WorldGenManager.PreparedEditorProjectSave prepared) {
        if (prepared == null || prepared.editorGeneration() != editorGeneration
            || !acceptsProjectId(prepared.project().getId())) {
            saveAfterPreparation = true;
            preparedProject = null;
            preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
            return;
        }
        if (pendingSaveProject == prepared) {
            return;
        }
        if (pendingSaveProject != null) {
            saveAfterPreparation = true;
            return;
        }
        pendingSaveProject = prepared;
        if (!manager.admitPreparedEditorProjectSave(actualServerId, prepared, true,
            generation -> generation == editorGeneration
                && prepared.workspaceGeneration() == workspaceSnapshotGeneration(),
            settlement -> settlePreparedProjectSave(prepared, settlement))) {
            pendingSaveProject = null;
            saveAfterPreparation = true;
            preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
            return;
        }
    }

    private void settlePreparedProjectSave(WorldGenManager.PreparedEditorProjectSave prepared,
                                           WorldGenManager.EditorProjectSaveSettlement settlement) {
        if (prepared == null || settlement == null || settlement.prepared() != prepared
            || pendingSaveProject != prepared
            || prepared.editorGeneration() != editorGeneration
            || prepared.workspaceGeneration() != workspaceSnapshotGeneration()) {
            return;
        }
        pendingSaveProject = null;
        if (settlement.submitted()) {
            submittedMutationVersion = prepared.mutationVersion();
            preparationBusyNotified = false;
            if (saveAfterPreparation) {
                saveAfterPreparation = false;
                StableWorkspaceDocument stable = stableWorkspaceDocument();
                if (preparedProject != null && stable != null
                    && preparedProject.editorGeneration() == editorGeneration
                    && preparedProject.workspaceGeneration() == stable.generation()
                    && preparedProject.mutationVersion() == stable.mutationVersion()) {
                    admitPreparedProject(preparedProject);
                } else if (activePreparation == 0L) {
                    captureStableWorkspaceDocument(workspaceMutationVersion());
                }
            }
            return;
        }
        if (preparedProject == prepared) {
            preparedProject = null;
        }
        saveAfterPreparation = true;
        if (activePreparation == 0L) {
            preparationRetrying(workspaceSnapshotGeneration(), workspaceMutationVersion());
        }
    }

    private InteractionAdmission deferInteraction(Runnable interaction) {
        return deferInteraction(null, interaction, MAX_DEFERRED_INTERACTIONS);
    }

    private InteractionAdmission deferInteraction(String key, Runnable interaction) {
        return deferInteraction(key, interaction, MAX_DEFERRED_INTERACTIONS);
    }

    private InteractionAdmission deferUserInteraction(String key, Runnable interaction) {
        return deferInteraction(key, interaction, MAX_DEFERRED_USER_INTERACTIONS);
    }

    private boolean reserveDeferredCollaboration(DeferredCollaboration collaboration) {
        if (collaboration == null) {
            return false;
        }
        DeferredInteraction tail = deferredInteractions.peekLast();
        boolean replacing = tail != null && "collaboration".equals(tail.key())
            && tail.action() instanceof DeferredCollaboration;
        long retained = deferredCollaborationBytes
            - (replacing ? DEFERRED_COLLABORATION_RESERVATION_BYTES : 0L);
        if (deferredCollaborations.size() >= MAX_DEFERRED_COLLABORATIONS && !replacing
            || retained < 0L || retained > Long.MAX_VALUE - DEFERRED_COLLABORATION_RESERVATION_BYTES
            || retained + DEFERRED_COLLABORATION_RESERVATION_BYTES
                > MAX_DEFERRED_COLLABORATIONS * DEFERRED_COLLABORATION_RESERVATION_BYTES) {
            return false;
        }
        deferredCollaborationBytes += DEFERRED_COLLABORATION_RESERVATION_BYTES;
        collaboration.markReserved();
        return true;
    }

    private void releaseDeferredCollaboration() {
        deferredCollaborationBytes = Math.max(0L,
            deferredCollaborationBytes - DEFERRED_COLLABORATION_RESERVATION_BYTES);
    }

    private InteractionAdmission deferInteraction(String key, Runnable interaction, int limit) {
        if (!preparationPending() || replayingInteractions || interaction == null) {
            return InteractionAdmission.EXECUTE;
        }
        key = key == null ? "" : key;
        DeferredInteraction deferred = new DeferredInteraction(key, interaction);
        DeferredInteraction tail = deferredInteractions.peekLast();
        if (tail != null && !key.isBlank() && key.equals(tail.key())) {
            deferredInteractions.removeLast();
            cancelDeferredInteraction(tail);
            deferredInteractions.addLast(deferred);
            return InteractionAdmission.DEFERRED;
        }
        if (deferredInteractions.size() >= limit) {
            interactionUnavailable();
            return InteractionAdmission.REJECTED;
        }
        deferredInteractions.addLast(deferred);
        return InteractionAdmission.DEFERRED;
    }

    private void cancelDeferredInteraction(DeferredInteraction deferred) {
        if (deferred != null && deferred.action() instanceof DeferredCollaboration collaboration) {
            collaboration.cancel();
        }
    }

    private InteractionAdmission deferDragInteraction(ReMouseEvent event) {
        if (!preparationPending() || replayingInteractions || event == null) {
            return InteractionAdmission.EXECUTE;
        }
        DeferredInteraction tail = deferredInteractions.peekLast();
        if (tail != null && tail.action() instanceof DeferredDrag drag) {
            drag.merge(event);
            return InteractionAdmission.DEFERRED;
        }
        return deferUserInteraction("drag", new DeferredDrag(event));
    }

    private void replayDeferredInteractions() {
        if (preparationPending() || replayingInteractions || deferredInteractions.isEmpty()) {
            return;
        }
        replayingInteractions = true;
        replayMutationVersion = -1L;
        try {
            while (!deferredInteractions.isEmpty() && !preparationPending()) {
                deferredInteractions.removeFirst().action().run();
            }
        } finally {
            replayingInteractions = false;
        }
        long mutationVersion = replayMutationVersion;
        replayMutationVersion = -1L;
        if (mutationVersion >= 0L && !preparationPending()) {
            captureStableWorkspaceDocument(mutationVersion);
        }
    }

    private void interactionUnavailable() {
        if (!preparationBusyNotified) {
            preparationBusyNotified = true;
            new Notification("World Generation", "Editor Busy", Notification.Type.WARN);
        }
    }

    private void preparationRetrying(long workspaceGeneration, long mutationVersion) {
        scheduleWorkspaceSnapshotRetry(workspaceGeneration, mutationVersion);
        notifyPreparationDelay();
    }

    private void notifyPreparationDelay() {
        if (!preparationBusyNotified) {
            preparationBusyNotified = true;
            new Notification("World Generation", "Save Delayed", Notification.Type.WARN);
        }
    }

    private void preparationUnavailable() {
        saveAfterPreparation = false;
        previewAfterPreparation = false;
        duplicateAfterPreparation = "";
        if (!preparationBusyNotified) {
            preparationBusyNotified = true;
            new Notification("World Generation", "Save Busy", Notification.Type.WARN);
        }
    }

    private RuntimeException asRuntimeException(String message, Throwable exception) {
        return exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(message, exception);
    }

    private final class CollaborationApplySettlement {
        private final Consumer<ReSyncCollaborativeView.CollaborationDocumentApplyResult> completion;
        private final String projectId;
        private final long admissionLifecycle;
        private final long admissionWorkspaceGeneration;
        private final long admissionMutationVersion;
        private final BrowserSafeState.BooleanValue settled = new BrowserSafeState.BooleanValue();

        private CollaborationApplySettlement(Consumer<ReSyncCollaborativeView.CollaborationDocumentApplyResult> completion,
                                              String projectId, long admissionLifecycle, long admissionWorkspaceGeneration,
                                              long admissionMutationVersion) {
            this.completion = completion;
            this.projectId = projectId;
            this.admissionLifecycle = admissionLifecycle;
            this.admissionWorkspaceGeneration = admissionWorkspaceGeneration;
            this.admissionMutationVersion = admissionMutationVersion;
        }

        private boolean admissionCurrent() {
            return admissionLifecycle == collaborationLifecycle()
                && admissionWorkspaceGeneration == workspaceSnapshotGeneration()
                && admissionMutationVersion == workspaceMutationVersion()
                && projectId.equals(safeProjectId(project));
        }

        private String projectId() {
            return projectId;
        }

        private void complete(long lifecycle, long beforeEditVersion, long afterEditVersion, boolean applied,
                              RuntimeException failure) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            boolean successful = applied && failure == null;
            RuntimeException terminalFailure = successful ? null
                : failure == null ? new IllegalStateException("World Generation collaboration apply failed") : failure;
            ReSyncCollaborativeView.completeApply(completion,
                new ReSyncCollaborativeView.CollaborationDocumentApplyResult(WorldGenEditorScreen.this, lifecycle,
                    beforeEditVersion, afterEditVersion, successful, terminalFailure));
        }

        private void fail(RuntimeException failure) {
            long editVersion = collaborationEditVersion();
            complete(collaborationLifecycle(), editVersion, editVersion, false, failure);
        }
    }

    private final class DeferredCollaboration implements Runnable {
        private final JsonObject document;
        private final List<WorkspacePatch<JsonElement>> patches;
        private final CollaborationApplySettlement settlement;
        private final BrowserSafeState.BooleanValue reserved = new BrowserSafeState.BooleanValue();

        private DeferredCollaboration(JsonObject document, List<WorkspacePatch<JsonElement>> patches,
                                      CollaborationApplySettlement settlement) {
            this.document = document;
            this.patches = patches == null ? List.of() : patches;
            this.settlement = settlement;
        }

        private void markReserved() {
            reserved.set(true);
        }

        private void releaseReservation() {
            if (reserved.compareAndSet(true, false)) {
                releaseDeferredCollaboration();
            }
        }

        @Override
        public void run() {
            deferredCollaborations.remove(this);
            releaseReservation();
            try {
                applyCollaborationDocumentNow(document, patches, settlement);
            } catch (RuntimeException | Error exception) {
                settlement.fail(asRuntimeException("World Generation collaboration replay failed", exception));
            }
        }

        private void cancel() {
            deferredCollaborations.remove(this);
            releaseReservation();
            settlement.fail(new IllegalStateException("World Generation collaboration was closed"));
        }
    }

    private enum InteractionAdmission {
        EXECUTE,
        DEFERRED,
        REJECTED
    }

    private record DeferredInteraction(String key, Runnable action) {
        private DeferredInteraction {
            key = key == null ? "" : key;
        }
    }

    private final class DeferredDrag implements Runnable {
        private ReMouseEvent event;

        private DeferredDrag(ReMouseEvent event) {
            this.event = event;
        }

        private void merge(ReMouseEvent next) {
            event = new ReMouseEvent(next.source(), next.target(), next.timestampNanos(), next.modifiers(), next.action(), next.x(), next.y(),
                event.deltaX() + next.deltaX(), event.deltaY() + next.deltaY(), next.button(), next.nativeButton(), next.clickCount());
        }

        @Override
        public void run() {
            mouseDragged(event);
        }
    }

    public void refreshStatus() {
    }

    void showSettingsPopup() {
        WorldGenProjectSettings settings = project.getSettings();
        PopupWidget.Builder builder = new PopupWidget.Builder("World Settings").setResizable(false).onClose(() -> settingsWidgets = null);
        String automaticVersion = "Automatic · " + manager.targetVersion(actualServerId, WorldGenTargetVersion.AUTOMATIC);
        List<String> targetVersions = new ArrayList<>();
        targetVersions.add(automaticVersion);
        targetVersions.addAll(WorldGenTargetVersion.supportedIds());
        DropDownWidget<String> targetVersion = new DropDownWidget.Builder<>(targetVersions)
            .size(220, 18)
            .selectedItem(WorldGenTargetVersion.AUTOMATIC.equals(settings.getTargetVersion()) ? automaticVersion : settings.getTargetVersion())
            .maxVisibleItems(10)
            .onSelectionChanged(value -> updateSettings(() -> settings.setTargetVersion(value.startsWith("Automatic") ? WorldGenTargetVersion.AUTOMATIC : value)))
            .build();
        WorldGenGenerationMode generationMode = WorldGenGenerationMode.resolve(settings.getGenerationMode());
        AnimatedButton generation = new AnimatedButton.Builder().label(generationMode.displayName()).size(220, 18).active(false).build();
        DropDownWidget<String> terrainPreset = new DropDownWidget.Builder<>(manager.getProjectTemplates(WorldGenGenerationMode.VANILLA.displayName()))
            .size(220, 18)
            .selectedItem(switch (safeText(settings.getTerrainTemplate())) {
                case "amplified" -> "Amplified";
                case "large_biomes" -> "Large Biomes";
                default -> "Survival";
            })
            .onSelectionChanged(value -> updateSettings(() -> settings.setTerrainTemplate(switch (safeText(value)) {
                case "Amplified" -> "amplified";
                case "Large Biomes" -> "large_biomes";
                default -> "overworld";
            })))
            .build();
        TextInputWidget minimumY = settingsInput(settings.getMinY(), value -> settings.setMinY((int) parseLong(value, settings.getMinY())));
        TextInputWidget maximumY = settingsInput(settings.getMaxY(), value -> settings.setMaxY((int) parseLong(value, settings.getMaxY())));
        TextInputWidget seaLevel = settingsInput(settings.getSeaLevel(), value -> settings.setSeaLevel((int) parseLong(value, settings.getSeaLevel())));
        DropDownWidget<String> structureSafety = new DropDownWidget.Builder<>(List.of("Enabled", "Disabled"))
            .size(220, 18)
            .selectedItem(settings.isVanillaStructureTerrainSafety() ? "Enabled" : "Disabled")
            .onSelectionChanged(value -> updateSettings(() -> settings.setVanillaStructureTerrainSafety("Enabled".equals(value))))
            .build();
        TextInputWidget structureRadius = settingsInput(settings.getVanillaStructureSampleRadius(), value -> settings.setVanillaStructureSampleRadius((int) parseLong(value, settings.getVanillaStructureSampleRadius())));
        TextInputWidget structureHeightDelta = settingsInput(settings.getVanillaStructureMaxHeightDelta(), value -> settings.setVanillaStructureMaxHeightDelta((int) parseLong(value, settings.getVanillaStructureMaxHeightDelta())));
        List<String> vanillaPolicies = List.of("Keep Vanilla", "Replace Vanilla");
        DropDownWidget<String> vanillaFeatures = new DropDownWidget.Builder<>(vanillaPolicies).size(220, 18)
            .selectedItem(settings.isVanillaFeaturesEnabled() ? "Keep Vanilla" : "Replace Vanilla")
            .onSelectionChanged(value -> updateSettings(() -> settings.setVanillaFeaturesEnabled("Keep Vanilla".equals(value)))).build();
        DropDownWidget<String> vanillaStructures = new DropDownWidget.Builder<>(vanillaPolicies).size(220, 18)
            .selectedItem(settings.isVanillaStructuresEnabled() ? "Keep Vanilla" : "Replace Vanilla")
            .onSelectionChanged(value -> updateSettings(() -> settings.setVanillaStructuresEnabled("Keep Vanilla".equals(value)))).build();
        DropDownWidget<String> vanillaSpawns = new DropDownWidget.Builder<>(vanillaPolicies).size(220, 18)
            .selectedItem(settings.isVanillaSpawnsEnabled() ? "Keep Vanilla" : "Replace Vanilla")
            .onSelectionChanged(value -> updateSettings(() -> settings.setVanillaSpawnsEnabled("Keep Vanilla".equals(value)))).build();
        ReSyncStudioPanelState.identify(targetVersion, "worldgen-setting:target-version");
        ReSyncStudioPanelState.identify(terrainPreset, "worldgen-setting:terrain-preset");
        ReSyncStudioPanelState.identify(minimumY, "worldgen-setting:minimum-y");
        ReSyncStudioPanelState.identify(maximumY, "worldgen-setting:maximum-y");
        ReSyncStudioPanelState.identify(seaLevel, "worldgen-setting:sea-level");
        ReSyncStudioPanelState.identify(vanillaFeatures, "worldgen-setting:vanilla-features");
        ReSyncStudioPanelState.identify(vanillaStructures, "worldgen-setting:vanilla-structures");
        ReSyncStudioPanelState.identify(vanillaSpawns, "worldgen-setting:vanilla-spawns");
        ReSyncStudioPanelState.identify(structureSafety, "worldgen-setting:structure-safety");
        ReSyncStudioPanelState.identify(structureRadius, "worldgen-setting:structure-radius");
        ReSyncStudioPanelState.identify(structureHeightDelta, "worldgen-setting:structure-height-delta");
        settingsWidgets = new SettingsWidgets(targetVersion, terrainPreset, minimumY, maximumY, seaLevel, vanillaFeatures, vanillaStructures, vanillaSpawns,
            structureSafety, structureRadius, structureHeightDelta, automaticVersion);
        builder.addRow("Generation", generation);
        builder.addRow("Minecraft", targetVersion);
        if (generationMode == WorldGenGenerationMode.VANILLA) {
            builder.addRow("Terrain", terrainPreset);
        }
        builder.addRow("Minimum Y", minimumY);
        builder.addRow("Maximum Y", maximumY);
        builder.addRow("Sea Level", seaLevel);
        builder.addRow("Vanilla Features", vanillaFeatures);
        builder.addRow("Vanilla Structures", vanillaStructures);
        builder.addRow("Vanilla Spawns", vanillaSpawns);
        builder.addRow("Structure Safety", structureSafety);
        builder.addRow("Safety Radius", structureRadius);
        builder.addRow("Maximum Height Difference", structureHeightDelta);
        PopupWidget[] popupRef = new PopupWidget[1];
        builder.addTitleAction("Save", () -> {
            if (!canMutateWorldGen()) {
                return;
            }
            String selectedVersion = safeText(targetVersion.getSelectedItem());
            settings.setTargetVersion(selectedVersion.startsWith("Automatic") ? WorldGenTargetVersion.AUTOMATIC : selectedVersion);
            if (generationMode == WorldGenGenerationMode.VANILLA) {
                settings.setTerrainTemplate(switch (safeText(terrainPreset.getSelectedItem())) {
                    case "Amplified" -> "amplified";
                    case "Large Biomes" -> "large_biomes";
                    default -> "overworld";
                });
            }
            settings.setMinY((int) parseLong(minimumY.getText(), settings.getMinY()));
            settings.setMaxY((int) parseLong(maximumY.getText(), settings.getMaxY()));
            settings.setSeaLevel((int) parseLong(seaLevel.getText(), settings.getSeaLevel()));
            settings.setVanillaFeaturesEnabled("Keep Vanilla".equals(vanillaFeatures.getSelectedItem()));
            settings.setVanillaStructuresEnabled("Keep Vanilla".equals(vanillaStructures.getSelectedItem()));
            settings.setVanillaSpawnsEnabled("Keep Vanilla".equals(vanillaSpawns.getSelectedItem()));
            settings.setVanillaStructureTerrainSafety("Enabled".equals(structureSafety.getSelectedItem()));
            settings.setVanillaStructureSampleRadius((int) parseLong(structureRadius.getText(), settings.getVanillaStructureSampleRadius()));
            settings.setVanillaStructureMaxHeightDelta((int) parseLong(structureHeightDelta.getText(), settings.getVanillaStructureMaxHeightDelta()));
            manager.invalidateProjectContent(project);
            queueWorkspaceMutation();
            saveAfterPreparation = true;
            refreshNodeRegistry();
            if (popupRef[0] != null) {
                popupRef[0].hide();
            }
        }, PopupWidget.TitleActionRole.PRIMARY);
        popupRef[0] = builder.build();
        addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    private TextInputWidget settingsInput(int value, Consumer<String> onChange) {
        TextInputWidget input = new TextInputWidget.Builder()
            .placeholder("Value")
            .size(220, 18)
            .onChange(next -> updateSettings(() -> onChange.accept(next)))
            .build();
        input.setText(String.valueOf(value));
        return input;
    }

    private void updateSettings(Runnable mutation) {
        if (deferInteraction(() -> updateSettings(mutation)) != InteractionAdmission.EXECUTE) {
            return;
        }
        if (mutation == null || !canMutateWorldGen()) {
            return;
        }
        mutation.run();
        manager.invalidateProjectContent(project);
        queueWorkspaceMutation();
        refreshStatus();
    }

    private void syncSettingsWidgets(WorldGenProjectSettings settings) {
        SettingsWidgets widgets = settingsWidgets;
        if (widgets == null) {
            return;
        }
        widgets.targetVersion().setSelectedItem(WorldGenTargetVersion.AUTOMATIC.equals(settings.getTargetVersion()) ? widgets.automaticVersion() : settings.getTargetVersion());
        widgets.terrainPreset().setSelectedItem(switch (safeText(settings.getTerrainTemplate())) {
            case "amplified" -> "Amplified";
            case "large_biomes" -> "Large Biomes";
            default -> "Survival";
        });
        widgets.minimumY().setText(String.valueOf(settings.getMinY()));
        widgets.maximumY().setText(String.valueOf(settings.getMaxY()));
        widgets.seaLevel().setText(String.valueOf(settings.getSeaLevel()));
        widgets.vanillaFeatures().setSelectedItem(settings.isVanillaFeaturesEnabled() ? "Keep Vanilla" : "Replace Vanilla");
        widgets.vanillaStructures().setSelectedItem(settings.isVanillaStructuresEnabled() ? "Keep Vanilla" : "Replace Vanilla");
        widgets.vanillaSpawns().setSelectedItem(settings.isVanillaSpawnsEnabled() ? "Keep Vanilla" : "Replace Vanilla");
        widgets.structureSafety().setSelectedItem(settings.isVanillaStructureTerrainSafety() ? "Enabled" : "Disabled");
        widgets.structureRadius().setText(String.valueOf(settings.getVanillaStructureSampleRadius()));
        widgets.structureHeightDelta().setText(String.valueOf(settings.getVanillaStructureMaxHeightDelta()));
    }

    private record SettingsWidgets(DropDownWidget<String> targetVersion, DropDownWidget<String> terrainPreset, TextInputWidget minimumY,
                                   TextInputWidget maximumY, TextInputWidget seaLevel, DropDownWidget<String> vanillaFeatures,
                                   DropDownWidget<String> vanillaStructures, DropDownWidget<String> vanillaSpawns,
                                   DropDownWidget<String> structureSafety, TextInputWidget structureRadius, TextInputWidget structureHeightDelta,
                                   String automaticVersion) {
    }

    void showPreviewPopup() {
        PopupWidget.Builder builder = new PopupWidget.Builder("Preview")
            .onClose(this::closePlayerSelector)
            .setResizable(false);
        TextInputWidget seedInput = new TextInputWidget.Builder()
            .placeholder("Seed")
            .size(220, 18)
            .build();
        seedInput.setText(String.valueOf(previewSeed));

        DropDownWidget<String> environmentSelect = new DropDownWidget.Builder<>(List.of("NORMAL", "NETHER", "THE_END", "CUSTOM"))
            .size(220, 18)
            .selectedItem(previewEnvironment)
            .build();

        AnimatedButton playerButton = new AnimatedButton.Builder()
            .label(previewPlayerName.isBlank() ? "No Player" : previewPlayerName)
            .size(220, 18)
            .entranceAnimation(false)
            .build();
        ReSyncStudioPanelState.identify(seedInput, "worldgen-preview:seed");
        ReSyncStudioPanelState.identify(environmentSelect, "worldgen-preview:environment");
        ReSyncStudioPanelState.identify(playerButton, "worldgen-preview:player");
        playerButton.setAction(() -> showPlayerSelector(playerButton, player -> {
            previewPlayerName = player.name();
            previewPlayerUuid = player.uuid();
            playerButton.setMessage(previewPlayerName.isBlank() ? "No Player" : previewPlayerName);
        }));

        builder.addRow("Seed", seedInput);
        builder.addRow("Environment", environmentSelect);
        builder.addRow("Player", playerButton);

        PopupWidget[] popupRef = new PopupWidget[1];
        AnimatedButton startButton = new AnimatedButton.Builder()
            .label("Preview")
            .onClick(() -> {
                previewEnvironment = safeText(environmentSelect.getSelectedItem()).toUpperCase(Locale.ROOT);
                previewSeed = parseLong(seedInput.getText(), 0);
                previewCurrentGraph();
                closePlayerSelector();
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            })
            .build();
        builder.addTitleAction("Preview", () -> startButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.PRIMARY);
        popupRef[0] = builder.build();
        addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    private void showPlayerSelector(AnimatedButton anchor, Consumer<PlayerOption> onSelected) {
        if (anchor == null || onSelected == null) {
            return;
        }
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager == null) {
            return;
        }
        closePlayerSelector();
        long snapshotRevision = flowManager.getPlayerTrackingSnapshotRevision(actualServerId);
        var overlay = ScreenManager.getInstance().getPopupOverlay();
        ItemSelectorWidget[] selectorRef = new ItemSelectorWidget[1];
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(overlay)
            .size(220, 240)
            .dismissOnSelect(true)
            .emptyMessage("No Players")
            .asyncItems(() -> flowManager.requestPlayerTrackingSnapshot(actualServerId),
                () -> previewPlayerSnapshot(flowManager, onSelected, snapshotRevision))
            .onClose(() -> closePlayerSelector(selectorRef[0]))
            .build();
        selector.setLayer(900);
        selector.setPriority(30);
        selectorRef[0] = selector;
        activePlayerSelector = selector;
        selector.setSelectedItem(previewPlayerName.isBlank() ? "No Player" : previewPlayerName);
        overlay.addDrawableChild(selector);
        selector.show(anchor.getX(), anchor.getY() + anchor.getHeight());
    }

    private void closePlayerSelector() {
        closePlayerSelector(activePlayerSelector);
    }

    private void closePlayerSelector(ItemSelectorWidget selector) {
        if (selector == null) {
            return;
        }
        selector.onClose = null;
        selector.hide();
        ScreenManager.getInstance().getPopupOverlay().remove(selector);
        if (selector == activePlayerSelector) {
            activePlayerSelector = null;
        }
    }

    private List<PlayerOption> previewPlayerOptions(FlowManager flowManager) {
        List<PlayerOption> players = new ArrayList<>();
        players.add(new PlayerOption("No Player", "", ""));
        for (PlayerDossier dossier : flowManager.getOnlinePlayersForServer(actualServerId)) {
            if (dossier == null || safeText(dossier.getPlayerName()).isBlank() || safeText(dossier.getPlayerId()).isBlank()) {
                continue;
            }
            players.add(new PlayerOption(dossier.getPlayerName(), dossier.getPlayerId(), dossier.getPlayerName() + " " + dossier.getPlayerId()));
        }
        return players;
    }

    private ItemSelectorWidget.AsyncItemSnapshot previewPlayerSnapshot(FlowManager flowManager, Consumer<PlayerOption> onSelected, long snapshotRevision) {
        List<PlayerOption> players = previewPlayerOptions(flowManager);
        List<ItemSelectorWidget.AsyncItem> items = players.stream()
            .map(player -> new ItemSelectorWidget.AsyncItem(player.label(), player.uuid(), player.searchTerms(), () -> onSelected.accept(player)))
            .toList();
        boolean loading = players.size() == 1 && flowManager.getPlayerTrackingSnapshotRevision(actualServerId) == snapshotRevision;
        return new ItemSelectorWidget.AsyncItemSnapshot(items, loading, "No Players");
    }

    private record PlayerOption(String name, String uuid, String searchTerms) {
        private String label() {
            return name;
        }
    }

    void showProjectPopup() {
        manager.requestProjectList(actualServerId);
        PopupWidget.Builder builder = new PopupWidget.Builder("WorldGen Projects").setResizable(false);
        List<String> ids = manager.getProjectIds(actualServerId);
        DropDownWidget<String> projectSelect = new DropDownWidget.Builder<>(ids.isEmpty() ? List.of(project.getId()) : ids)
            .size(240, 18)
            .selectedItem(project.getId())
            .build();
        TextInputWidget projectIdInput = new TextInputWidget.Builder()
            .placeholder("Project ID")
            .size(240, 18)
            .build();
        projectIdInput.setText(project.getId());
        String projectCategory = generationMode();
        DropDownWidget<String> templateSelect = new DropDownWidget.Builder<>(manager.getProjectTemplates(projectCategory))
            .size(240, 18)
            .selectedItem(manager.getProjectTemplates(projectCategory).getFirst())
            .build();
        DropDownWidget<String> categorySelect = new DropDownWidget.Builder<>(manager.getProjectCategories())
            .size(240, 18)
            .selectedItem(projectCategory)
            .onSelectionChanged(category -> {
                List<String> templates = manager.getProjectTemplates(category);
                templateSelect.setItems(templates, templates.getFirst());
            })
            .build();
        ReSyncStudioPanelState.identify(projectSelect, "worldgen-project:project");
        ReSyncStudioPanelState.identify(projectIdInput, "worldgen-project:id");
        ReSyncStudioPanelState.identify(categorySelect, "worldgen-project:category");
        ReSyncStudioPanelState.identify(templateSelect, "worldgen-project:template");
        builder.addRow("Project", projectSelect);
        builder.addRow("Project ID", projectIdInput);
        builder.addRow("Category", categorySelect);
        builder.addRow("Template", templateSelect);
        PopupWidget[] popupRef = new PopupWidget[1];
        AnimatedButton newButton = new AnimatedButton.Builder()
            .label("New")
            .onClick(() -> {
                if (projectIdentityBound()) {
                    projectSwitchUnavailable();
                    return;
                }
                if (!canMutateWorldGen()) {
                    return;
                }
                if (preparationPending()) {
                    interactionUnavailable();
                    return;
                }
                WorldGenProject candidate = manager.createProjectTemplate(safeText(categorySelect.getSelectedItem()),
                    safeText(templateSelect.getSelectedItem()), null);
                pendingNewProject = candidate;
                projectIdInput.setText(candidate.getId());
                long mutationVersion = workspaceMutationVersion();
                if (!beginEditorProjectPreparation(candidate, WorldGenStage.TERRAIN, mutationVersion,
                    prepared -> applyNewProject(candidate, prepared))) {
                    preparationUnavailable();
                    scheduleViewPreparationRetry();
                }
            })
            .build();
        AnimatedButton openButton = new AnimatedButton.Builder()
            .label("Open")
            .onClick(() -> {
                if (projectIdentityBound()) {
                    projectSwitchUnavailable();
                    return;
                }
                String selected = safeText(projectSelect.getSelectedItem()).trim();
                if (!selected.isBlank()) {
                    requestProject(selected);
                }
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            })
            .build();
        AnimatedButton duplicateButton = new AnimatedButton.Builder()
            .label("Duplicate")
            .onClick(() -> {
                if (projectIdentityBound()) {
                    projectSwitchUnavailable();
                    return;
                }
                if (!canMutateWorldGen()) {
                    return;
                }
                duplicateProject(projectIdInput.getText().isBlank() ? UUID.randomUUID().toString() : projectIdInput.getText().trim());
            })
            .build();
        AnimatedButton deleteButton = new AnimatedButton.Builder()
            .label("Delete")
            .onClick(() -> {
                if (projectIdentityBound()) {
                    projectSwitchUnavailable();
                    return;
                }
                if (!canMutateWorldGen()) {
                    return;
                }
                String selected = safeText(projectSelect.getSelectedItem()).trim();
                if (!selected.isBlank()) {
                    manager.deleteProject(actualServerId, selected);
                }
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            })
            .build();
        builder.addTitleAction("New", () -> newButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.SECONDARY);
        builder.addTitleAction("Open", () -> openButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.PRIMARY);
        builder.addTitleAction("Duplicate", () -> duplicateButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.SECONDARY);
        builder.addTitleAction("Delete", () -> deleteButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.DESTRUCTIVE);
        popupRef[0] = builder.build();
        addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    public void loadProject(WorldGenProject project) {
        if (deferInteraction("project", () -> loadProject(project)) != InteractionAdmission.EXECUTE) {
            return;
        }
        if (project == null) {
            return;
        }
        String incomingProjectId = safeText(project.getId()).strip();
        if (incomingProjectId.isBlank() || !acceptsProjectId(incomingProjectId)) {
            return;
        }
        boolean explicitlyRequested = incomingProjectId.equals(safeText(pendingProjectRequest));
        long requestedSequence = explicitlyRequested ? pendingProjectRequestSequence : 0L;
        if (this.project != null && !sameProjectId(this.project, project) && !explicitlyRequested) {
            return;
        }
        long currentMutationVersion = workspaceMutationVersion();
        long protectedMutationVersion = Math.max(baselineMutationVersion, submittedMutationVersion);
        if (this.project != null && sameProjectId(this.project, project)
            && currentMutationVersion > protectedMutationVersion) {
            refreshStatus();
            return;
        }
        if (this.project != null && sameProjectId(this.project, project)
            && currentMutationVersion <= baselineMutationVersion && manager.knownSameProjectContent(this.project, project)) {
            if (explicitlyRequested) {
                pendingProjectRequest = null;
                pendingProjectRequestSequence = 0L;
            }
            refreshStatus();
            return;
        }
        if (this.project != null && sameProjectId(this.project, project)
            && submittedMutationVersion >= 0L && currentMutationVersion == submittedMutationVersion
            && preparedProject != null && manager.knownSameProjectContent(project, preparedProject.project())) {
            advanceEditorGeneration();
            this.project = project;
            if (explicitlyRequested) {
                pendingProjectRequest = null;
                pendingProjectRequestSequence = 0L;
            }
            baselineMutationVersion = currentMutationVersion;
            submittedMutationVersion = -1L;
            refreshStatus();
            return;
        }
        long expectedMutationVersion = currentMutationVersion;
        if (!beginEditorProjectPreparation(project, WorldGenStage.TERRAIN, expectedMutationVersion, prepared -> {
            if (requestedSequence != 0L && requestedSequence != pendingProjectRequestSequence) {
                return;
            }
            if (applyLoadedProject(prepared, incomingProjectId)) {
                pendingProjectRequest = null;
                pendingProjectRequestSequence = 0L;
            }
        })) {
            preparationUnavailable();
        }
    }

    private void previewCurrentGraph() {
        if (preparationPending()) {
            previewAfterPreparation = true;
            return;
        }
        StableWorkspaceDocument stable = stableWorkspaceDocument();
        if (preparedProject == null || stable == null || preparedProject.editorGeneration() != editorGeneration
            || preparedProject.workspaceGeneration() != stable.generation()
            || preparedProject.mutationVersion() != stable.mutationVersion()) {
            previewAfterPreparation = true;
            captureStableWorkspaceDocument(workspaceMutationVersion());
            return;
        }
        requestPreparedPreview(preparedProject);
    }

    private void requestPreparedPreview(WorldGenManager.PreparedEditorProjectSave prepared) {
        if (prepared != null && prepared.editorGeneration() == editorGeneration) {
            manager.requestPreview(actualServerId, previewId, prepared, previewEnvironment, previewSeed,
                previewPlayerUuid);
        }
    }

    private void duplicateProject(String projectId) {
        if (deferInteraction("duplicate", () -> duplicateProject(projectId)) != InteractionAdmission.EXECUTE) {
            return;
        }
        if (projectIdentityBound()) {
            projectSwitchUnavailable();
            return;
        }
        StableWorkspaceDocument stable = stableWorkspaceDocument();
        if (preparationPending() || preparedProject == null || stable == null
            || preparedProject.editorGeneration() != editorGeneration
            || preparedProject.workspaceGeneration() != stable.generation()
            || preparedProject.mutationVersion() != stable.mutationVersion()) {
            duplicateAfterPreparation = safeText(projectId).trim();
            if (!preparationPending()) {
                captureStableWorkspaceDocument(workspaceMutationVersion());
            }
            return;
        }
        prepareProjectCopy(preparedProject, projectId);
    }

    private void prepareProjectCopy(WorldGenManager.PreparedEditorProjectSave source, String projectId) {
        long sourceGeneration = editorGeneration;
        long sourceMutationVersion = source.mutationVersion();
        long targetGeneration = sourceGeneration + 1L;
        long workspaceGeneration = workspaceSnapshotGeneration();
        String catalogFence = workspaceCatalogFence();
        if (!manager.prepareEditorProjectCopy(source, projectId, WorldGenStage.TERRAIN, targetGeneration, workspaceGeneration, result -> {
            if (sourceGeneration != editorGeneration || workspaceGeneration != workspaceSnapshotGeneration()
                || !catalogFence.equals(workspaceCatalogFence())) {
                return;
            }
            if (result == null || !result.ready() || result.prepared().activeGraph() == null
                || result.prepared().editorGeneration() != targetGeneration
                || !manager.isCurrentPreparedEditorProject(result.prepared())) {
                preparationUnavailable();
                return;
            }
            if (workspaceMutationVersion() != sourceMutationVersion || preparationPending()) {
                if (!manager.admitPreparedEditorProjectSave(actualServerId, result.prepared(), true,
                    ignored -> sourceGeneration == editorGeneration
                        && result.prepared().workspaceGeneration() == workspaceSnapshotGeneration(),
                    settlement -> {
                        if (!settlement.submitted() && sourceGeneration == editorGeneration) {
                            preparationUnavailable();
                        }
                    })) {
                    preparationUnavailable();
                }
                return;
            }
            if (!publishStableWorkspaceDocument(result.prepared().workspaceGeneration(), result.prepared().mutationVersion(),
                result.prepared().workspaceDocument())) {
                preparationUnavailable();
                return;
            }
            advanceEditorGeneration();
            project = result.prepared().project();
            preparedProject = result.prepared();
            activeStage = WorldGenStage.TERRAIN;
            adoptPreparedGraph(result.prepared().activeGraph(), true);
            admitPreparedProject(preparedProject);
        })) {
            preparationUnavailable();
        }
    }

    private boolean canMutateWorldGen() {
        return manager.canMutateWorldGen(actualServerId);
    }

    private void advanceEditorGeneration() {
        editorGeneration++;
        baselineMutationVersion = workspaceMutationVersion();
        submittedMutationVersion = -1L;
        preparedProject = null;
        pendingSaveProject = null;
        saveAfterPreparation = false;
        previewAfterPreparation = false;
        duplicateAfterPreparation = "";
        preparationBusyNotified = false;
    }

    private void requestProject(String projectId) {
        pendingProjectRequest = safeText(projectId).trim();
        if (!acceptsProjectId(pendingProjectRequest)) {
            pendingProjectRequest = null;
            pendingProjectRequestSequence = 0L;
            projectSwitchUnavailable();
            return;
        }
        pendingProjectRequestSequence = ++projectRequestSequence;
        manager.requestProject(actualServerId, pendingProjectRequest);
    }

    private boolean projectIdentityBound() {
        return !boundProjectId.isBlank();
    }

    private boolean acceptsProjectId(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        return !normalized.isBlank() && (!projectIdentityBound() || boundProjectId.equals(normalized));
    }

    private void projectSwitchUnavailable() {
        new Notification("World Generation", "Open From Resources", Notification.Type.WARN);
    }

    private static String safeProjectId(WorldGenProject project) {
        return project == null || project.getId() == null ? "" : project.getId().strip();
    }

    private boolean sameProjectId(WorldGenProject left, WorldGenProject right) {
        return left != null && right != null && safeText(left.getId()).equals(safeText(right.getId()));
    }

    @Override
    protected int viewportFitLeft() {
        return super.viewportFitLeft();
    }

    @Override
    protected int viewportFitWidth() {
        return Math.max(1, super.viewportFitWidth() - (navigationPanel != null ? navigationPanel.layoutWidth() : 0));
    }

    @Override
    protected void renderAdditionalStudioPanels(IDrawContext context, int mouseX, int mouseY, float delta) {
        super.renderAdditionalStudioPanels(context, mouseX, mouseY, delta);
        if (navigationPanel != null) {
            navigationPanel.render(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public void updatePositions() {
        super.updatePositions();
        if (navigationPanel != null) {
            navigationPanel.layout();
        }
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        InteractionAdmission admission = deferUserInteraction("", () -> mouseClicked(event));
        if (admission != InteractionAdmission.EXECUTE) {
            return admission == InteractionAdmission.DEFERRED;
        }
        try {
            if (navigationPanel != null && navigationPanel.mouseClicked(event)) {
                return true;
            }
            return super.mouseClicked(event);
        } finally {
            completeQueuedWorkspaceMutation();
        }
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        InteractionAdmission admission = deferUserInteraction("", () -> mouseReleased(event));
        if (admission != InteractionAdmission.EXECUTE) {
            return admission == InteractionAdmission.DEFERRED;
        }
        try {
            if (navigationPanel != null && navigationPanel.mouseReleased(event)) {
                return true;
            }
            return super.mouseReleased(event);
        } finally {
            completeQueuedWorkspaceMutation();
        }
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        InteractionAdmission admission = deferDragInteraction(event);
        if (admission != InteractionAdmission.EXECUTE) {
            return admission == InteractionAdmission.DEFERRED;
        }
        try {
            if (navigationPanel != null && navigationPanel.mouseDragged(event)) {
                return true;
            }
            return super.mouseDragged(event);
        } finally {
            completeQueuedWorkspaceMutation();
        }
    }

    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        InteractionAdmission admission = deferUserInteraction("", () -> mouseScrolled(event));
        if (admission != InteractionAdmission.EXECUTE) {
            return admission == InteractionAdmission.DEFERRED;
        }
        try {
            if (navigationPanel != null && navigationPanel.mouseScrolled(event)) {
                return true;
            }
            return super.mouseScrolled(event);
        } finally {
            completeQueuedWorkspaceMutation();
        }
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        InteractionAdmission admission = deferUserInteraction("", () -> keyPressed(event));
        if (admission != InteractionAdmission.EXECUTE) {
            return admission == InteractionAdmission.DEFERRED;
        }
        try {
            if (navigationPanel != null && navigationPanel.keyPressed(event)) {
                return true;
            }
            return super.keyPressed(event);
        } finally {
            completeQueuedWorkspaceMutation();
        }
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        InteractionAdmission admission = deferUserInteraction("", () -> textInput(event));
        if (admission != InteractionAdmission.EXECUTE) {
            return admission == InteractionAdmission.DEFERRED;
        }
        try {
            return super.textInput(event);
        } finally {
            completeQueuedWorkspaceMutation();
        }
    }

    String stageDescription(WorldGenStage stage) {
        if (WorldGenGenerationMode.resolve(project.getSettings().getGenerationMode()) == WorldGenGenerationMode.VANILLA) {
            return switch (stage) {
                case TERRAIN -> "Minecraft Shapes Terrain From The World Settings Preset";
                case BIOME -> "Minecraft Routes Its Native Biomes And Climate";
                case SURFACE -> "Minecraft Paints Native Surface Rules";
                case CAVE -> "Minecraft Carves Native Caves And Ravines";
                case FEATURE -> "Add Datapack Ores, Vegetation, Lakes, And Catalog Features";
                case STRUCTURE -> "Add Datapack Structures With Game-Owned Placement";
                case SPAWN -> "Edit Native Biome Spawn Tables And Group Sizes";
            };
        }
        return switch (stage) {
            case TERRAIN -> "Shape Land, Oceans, Height, And Density";
            case BIOME -> "Route Climate Into Biome Behavior";
            case SURFACE -> "Paint Top, Filler, And Material Layers";
            case CAVE -> "Carve Underground Spaces And Ravines";
            case FEATURE -> "Place Ores, Vegetation, Trees, And Lakes";
            case STRUCTURE -> "Control Structures And Placement Safety";
            case SPAWN -> "Define Biome Spawn Tables And Group Sizes";
        };
    }

    void stopPreview() {
        manager.stopPreview(actualServerId, previewId);
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String sanitizePreviewId(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return "local";
        }
        return serverId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    @Override
    public void close() {
        editorGeneration++;
        initialProjectPending = false;
        pendingInitialProject = null;
        pendingNewProject = null;
        viewPreparationRetryAt = 0L;
        activeViewPreparation = 0L;
        activePreparation = 0L;
        pendingSaveProject = null;
        pendingProjectRequest = null;
        pendingProjectRequestSequence = 0L;
        for (DeferredCollaboration deferred : new ArrayList<>(deferredCollaborations)) {
            deferred.cancel();
        }
        deferredCollaborations.clear();
        deferredInteractions.clear();
        deferredCollaborationBytes = 0L;
        ScreenManager.getInstance().setScreen(parentScreen);
    }
}
