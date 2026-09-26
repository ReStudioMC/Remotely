package redxax.oxy.remotely.flow.ui.studio;

import restudio.rescreen.platform.Async;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.data.flow.AutomationDefinitionDraft;
import redxax.oxy.remotely.data.flow.CoreGraphDocumentAuthoringAdapter;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.data.flow.ReSyncValueTypeCatalog;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.worldgen.contract.WorldGenGenerationMode;

import java.util.UUID;
import java.util.function.Consumer;

public final class ReSyncResourceCreator {
    private ReSyncResourceCreator() {
    }

    public record Result(String type, String id, Object resource) {
    }

    public record Submission(boolean admitted, String message, Async<Boolean> durable) {
        public Submission {
            message = message == null ? "" : message;
            durable = durable == null ? Async.completed(false) : durable;
        }
    }

    public static void showCreatePopup(Screen screen, String serverId, String type, String folder, String template,
                                       Consumer<Result> onCreated) {
        if (screen == null) {
            traceUiCreation(serverId, type, null, "create_popup_rejected", "screen_missing", 0L);
            return;
        }
        if (AutomationDefinitionDraft.supports(type)) {
            if (screen instanceof StudioScreen studio) {
                studio.openDefinitionCreate(type, folder, onCreated);
                return;
            }
            FlowManager manager = FlowManager.getInstance();
            if (manager != null && serverId != null && !serverId.isBlank()) {
                manager.openStudioDocument(serverId, "definition_create:" + type + ":" + UUID.randomUUID(),
                    studio -> studio.openDefinitionCreate(type, folder, onCreated));
                return;
            }
            traceUiCreation(serverId, type, null, "create_popup_rejected", "studio_unavailable", 0L);
            new Notification("Create", "ReSync Studio Is Unavailable", Notification.Type.ERROR);
            return;
        }
        long startedAt = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        PopupWidget.Builder builder = new PopupWidget.Builder(createPopupTitle(type)).setResizable(false);
        TextInputWidget idInput = new TextInputWidget.Builder()
            .text(ReSyncResourceDragPayload.FOLDER.equals(type) ? "" : suggestedId(serverId, type, folder))
            .placeholder(createIdPlaceholder(type))
            .size(220, 22)
            .build();
        builder.addRow(ReSyncResourceDragPayload.FOLDER.equals(type) ? "Name" : "ID", idInput);
        PopupWidget[] popupRef = new PopupWidget[1];
        Consumer<Result> popupObserver = result -> {
            if (result == null) {
                return;
            }
            if (popupRef[0] != null) {
                popupRef[0].hide();
            }
            if (onCreated != null) {
                onCreated.accept(result);
            }
        };
        Runnable create = () -> {
                String id = idInput.getText() != null ? idInput.getText().trim() : "";
                create(serverId, type, id, folder, template, popupObserver);
            };
        builder.addTitleAction("Create", create, PopupWidget.TitleActionRole.PRIMARY);
        popupRef[0] = builder.build();
        screen.addDrawableChild(popupRef[0]);
        popupRef[0].show();
        traceUiCreation(serverId, type, null, "create_popup_shown", "ready", startedAt);
    }

    private static ReSyncValueTypeCatalog.Snapshot currentTypeCatalog(String serverId) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null || serverId.isBlank()) {
            return null;
        }
        return ReSyncValueTypeCatalog.active(manager.existingFlowClient(serverId));
    }

    private static boolean isCurrentTypeCatalog(String serverId, ReSyncValueTypeCatalog.Snapshot catalog) {
        FlowManager manager = FlowManager.getInstance();
        return manager != null && catalog != null
            && ReSyncValueTypeCatalog.current(manager.existingFlowClient(serverId), catalog);
    }

    public static Submission createPreparedAutomation(String serverId, String type, String id, String folder,
                                                       AutomationDefinitionDraft.Prepared prepared,
                                                       ReSyncValueTypeCatalog.Snapshot expectedTypeCatalog,
                                                       Consumer<Result> completion) {
        FlowManager manager = FlowManager.getInstance();
        long startedAt = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        if (manager == null || serverId == null || serverId.isBlank()) {
            traceUiCreation(serverId, type, id, "create_rejected", "manager_missing", startedAt);
            new Notification("Create", "No Server Connection", Notification.Type.ERROR);
            return rejectedSubmission("No Server Connection");
        }
        if (!AutomationDefinitionDraft.supports(type) || id == null || !id.matches("^[a-zA-Z0-9_]+$")
            || prepared == null || !type.equals(prepared.type())) {
            traceUiCreation(serverId, type, id, "create_rejected", "automation_input_invalid", startedAt);
            new Notification("Create", "Invalid Automation Definition", Notification.Type.ERROR);
            return rejectedSubmission("Invalid Automation Definition");
        }
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (resourceType == null || !resourceType.enabled()) {
            traceUiCreation(serverId, type, id, "create_rejected", "resource_type_unavailable", startedAt);
            new Notification("Create", "Resource Type Unavailable", Notification.Type.ERROR);
            return rejectedSubmission("Resource Type Unavailable");
        }
        ReSyncValueTypeCatalog.Snapshot typeCatalog = AutomationDefinitionDraft.VARIABLE.equals(type)
            ? expectedTypeCatalog : null;
        AutomationDefinitionDraft.Prepared exact;
        try {
            if (AutomationDefinitionDraft.VARIABLE.equals(type)
                && (typeCatalog == null || !isCurrentTypeCatalog(serverId, typeCatalog))) {
                throw new IllegalArgumentException("Value Types Changed. Review The Selection And Create Again");
            }
            exact = typeCatalog != null
                ? AutomationDefinitionDraft.prepare(type, id, prepared.document(), typeCatalog.dataTypes())
                : AutomationDefinitionDraft.prepare(type, id, prepared.document());
            if (typeCatalog != null && !isCurrentTypeCatalog(serverId, typeCatalog)) {
                throw new IllegalArgumentException("Value Types Changed. Review The Selection And Create Again");
            }
            if (exact.target() != null && !manager.isAvailableScheduleTarget(serverId,
                exact.target().locator(ServerId.parseCanonicalText(serverId)))) {
                throw new IllegalArgumentException("Selected Target Is No Longer Available");
            }
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Automation Definition Is Invalid" : exception.getMessage();
            traceUiCreation(serverId, type, id, "create_rejected", "automation_template_invalid", startedAt);
            new Notification("Automation", message, Notification.Type.ERROR);
            return rejectedSubmission(message);
        }
        String targetFolder = normalizedFolder(folder);
        if (exists(manager, serverId, type, id, targetFolder)) {
            String message = resourceTypeName(type) + " ID Already Exists";
            traceUiCreation(serverId, type, id, "create_rejected", "resource_exists", startedAt);
            new Notification("Create", message, Notification.Type.ERROR);
            return rejectedSubmission(message);
        }
        FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(serverId);
        FlowManager.ProjectResource existing = metadata.resource(type, id);
        String displayName = AutomationDefinitionDraft.text(type, exact.document(), "name");
        FlowManager.CreationMetadataIntent intent = new FlowManager.CreationMetadataIntent(type, id, displayName,
            FlowManager.canonicalResourcePath(resourceType, id, targetFolder), "",
            existing != null ? existing.sortOrder() : metadata.nextResourceSortOrder(), false);
        FlowManager.CreationAdmission admission = manager.beginResourceCreation(serverId, resourceType, id,
            exact.document().toString(), intent, null, result -> {
                traceCreationProjection(manager, serverId, resourceType, id, result,
                    result != null ? "create_observer_complete" : "create_observer_rejected", startedAt);
                if (result == null || !resourceType.typeId().equals(result.type()) || !id.equals(result.id())) {
                    new Notification("Create", "Creation Result Invalid", Notification.Type.ERROR);
                    return;
                }
                if (completion != null && FlowManager.getInstance() == manager) {
                    completion.accept(new Result(type, id, result.resource()));
                }
            });
        traceUiCreation(serverId, type, id, admission.queued() ? "create_request_admitted"
            : "create_request_rejected", admission.message(), startedAt);
        if (admission.rejected()) {
            new Notification("Create", admission.message(), Notification.Type.ERROR);
        }
        return new Submission(admission.queued(), admission.message(), admission.durable());
    }

    private static Submission rejectedSubmission(String message) {
        return new Submission(false, message, Async.completed(false));
    }

    public static void create(String serverId, String type, String id, String folder, String template,
                              Consumer<Result> completion) {
        create(serverId, type, id, folder, template, null, completion);
    }

    private static void create(String serverId, String type, String id, String folder, String template,
                               ReSyncValueTypeCatalog.Snapshot expectedTypeCatalog, Consumer<Result> completion) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            traceUiCreation(serverId, type, id, "create_rejected", "manager_missing", 0L);
            new Notification("Error", "No server connection", Notification.Type.ERROR);
            return;
        }
        if (serverId == null || serverId.isBlank()) {
            traceUiCreation(serverId, type, id, "create_rejected", "server_identity_missing", 0L);
            new Notification("Error", "No server connection", Notification.Type.ERROR);
            return;
        }
        if (AutomationDefinitionDraft.supports(type)) {
            try {
                ReSyncValueTypeCatalog.Snapshot catalog = AutomationDefinitionDraft.VARIABLE.equals(type)
                    ? expectedTypeCatalog != null ? expectedTypeCatalog : currentTypeCatalog(serverId) : null;
                JsonObject document = JsonParser.parseString(template != null ? template : "").getAsJsonObject();
                AutomationDefinitionDraft.Prepared prepared = catalog != null
                    ? AutomationDefinitionDraft.prepare(type, id, document, catalog.dataTypes())
                    : AutomationDefinitionDraft.prepare(type, id, document);
                createPreparedAutomation(serverId, type, id, folder, prepared, catalog, completion);
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "Automation Definition Is Invalid" : exception.getMessage();
                traceUiCreation(serverId, type, id, "create_rejected", "automation_template_invalid", 0L);
                new Notification("Automation", message, Notification.Type.ERROR);
            }
            return;
        }
        long startedAt = ReSyncFlowClient.TEMP_LIFECYCLE_DEBUG ? System.nanoTime() : 0L;
        ReSyncFlowClient.traceLifecycle(serverId, "create_requested", "serverId", serverId, "resourceKey",
            type + ":" + id, "requestId", "ui", "mutationId", null, "generation", -1L, "authorityEpoch", 0L,
            "revision", 0L);
        if (ReSyncResourceDragPayload.FOLDER.equals(type)) {
            if (id == null || id.isBlank() || id.contains("/") || id.contains("\\")) {
                traceUiCreation(serverId, type, id, "create_rejected", "folder_name_invalid", startedAt);
                new Notification("Explorer", "Invalid Name", Notification.Type.ERROR);
                return;
            }
            String parent = normalizedFolder(folder);
            String path = parent.isBlank() ? id.trim() : parent + "/" + id.trim();
            if (manager.getProjectFolder(serverId, path) != null) {
                traceUiCreation(serverId, type, path, "create_rejected", "folder_exists", startedAt);
                new Notification("Explorer", "Folder Already Exists", Notification.Type.ERROR);
                return;
            }
            FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(serverId);
            FlowManager.CreationMetadataIntent intent = new FlowManager.CreationMetadataIntent(
                ReSyncResourceDragPayload.FOLDER, path, id.trim(), path, parent, metadata.nextFolderSortOrder(), true);
            FlowManager.CreationAdmission admission = manager.beginFolderCreation(serverId, path, intent,
                result -> {
                    traceUiCreation(serverId, type, path, "create_observer_complete", "folder_metadata_settled",
                        startedAt);
                    if (completion != null) {
                        completion.accept(new Result(type, id.trim(), null));
                    }
                });
            traceUiCreation(serverId, type, path, admission.queued() ? "create_request_admitted"
                : "create_request_rejected", admission.message(), startedAt);
            if (admission.rejected()) {
                new Notification("Explorer", admission.message(), Notification.Type.ERROR);
            }
            return;
        }
        if (ReSyncResourceDragPayload.ADVANCEMENT_TREE.equals(type)
            && (id == null || !id.matches("^[a-z0-9._-]+$"))) {
            traceUiCreation(serverId, type, id, "create_rejected", "minecraft_advancement_id_invalid", startedAt);
            new Notification("Advancement ID", "Minecraft requires lowercase advancement IDs. Use a-z, 0-9, dots, dashes, or underscores.", Notification.Type.ERROR);
            return;
        }
        if (id == null || !id.matches(ReSyncResourceDragPayload.ADVANCEMENT_TREE.equals(type)
            ? "^[a-z0-9._-]+$" : "^[a-zA-Z0-9_]+$")) {
            traceUiCreation(serverId, type, id, "create_rejected", "resource_id_invalid", startedAt);
            new Notification("Error", "Invalid ID. Alphanumeric only.", Notification.Type.ERROR);
            return;
        }
        String targetFolder = normalizedFolder(folder);
        String graphOwner = graphIdOwner(manager, serverId, type, id);
        if (graphOwner != null) {
            ReSyncFlowClient.traceLifecycle(serverId, "create_rejected", "serverId", serverId, "resourceKey",
                type + ":" + id, "requestId", "ui", "mutationId", null, "generation", -1L, "authorityEpoch", 0L,
                "revision", 0L, "reason", "graph_id_owned", "elapsedMs", elapsedMillis(startedAt));
            new Notification("Error", "ID Used By " + resourceTypeName(graphOwner), Notification.Type.ERROR);
            return;
        }
        if (exists(manager, serverId, type, id, targetFolder)) {
            ReSyncFlowClient.traceLifecycle(serverId, "create_rejected", "serverId", serverId, "resourceKey",
                type + ":" + id, "requestId", "ui", "mutationId", null, "generation", -1L, "authorityEpoch", 0L,
                "revision", 0L, "reason", "resource_exists", "elapsedMs", elapsedMillis(startedAt));
            new Notification("Error", resourceTypeName(type) + " ID already exists", Notification.Type.ERROR);
            return;
        }
        if (ReSyncResourceDragPayload.WORLDGEN.equals(type)) {
            WorldGenManager worldGen = WorldGenManager.getInstance();
            WorldGenManager.ProjectSaveSubmission submission = worldGen.submitWorldGenTemplateSave(serverId,
                WorldGenGenerationMode.HYBRID.displayName(), "Continental", id, true,
                new WorldGenManager.WorldGenMetadataIntent(id, targetFolder, -1), settlement -> {
                    String payloadReason = worldGenSettlementReason(serverId, id, settlement);
                    ReSyncFlowClient.traceLifecycle(serverId,
                        "settled".equals(payloadReason) ? "create_payload_durable_settled"
                            : "create_payload_rejected",
                        "serverId", serverId, "resourceKey", type + ":" + id, "requestId",
                        settlement != null && settlement.submission() != null
                            ? settlement.submission().operationId() : "worldgen", "mutationId", null,
                        "generation", settlement != null && settlement.connection() != null
                            ? settlement.connection().generation() : -1L,
                        "authorityEpoch", settlement != null ? settlement.authorityEpoch() : 0L, "revision", -1L,
                        "reason", payloadReason, "durableResourceSettled", "settled".equals(payloadReason),
                        "metadataSettled", false, "browserSettled", false, "editorSettled", false, "elapsedMs",
                        elapsedMillis(startedAt));
                    if (!"settled".equals(payloadReason)) {
                        return;
                    }
                    String operationId = settlement.submission().operationId();
                    boolean admitted = worldGen.reconcileWorldGenProjectMetadata(settlement, id, targetFolder, -1,
                        result -> {
                            FlowManager currentManager = FlowManager.getInstance();
                            WorldGenManager currentWorldGen = WorldGenManager.getInstance();
                            String metadataReason = worldGenMetadataSettlementReason(serverId, id, operationId,
                                result, currentManager, currentWorldGen);
                            ReSyncFlowClient.traceLifecycle(serverId,
                                "settled".equals(metadataReason) ? "create_metadata_durable_settled"
                                    : "create_metadata_rejected",
                                "serverId", serverId, "resourceKey", type + ":" + id, "operation", "create",
                                "requestId", operationId, "mutationId", null, "generation",
                                result != null && result.connection() != null ? result.connection().generation() : -1L,
                                "authorityEpoch", result != null ? result.authorityEpoch() : 0L, "revision", -1L,
                                "reason", metadataReason, "durableResourceSettled", true, "metadataSettled",
                                "settled".equals(metadataReason), "browserSettled", false, "editorSettled", false,
                                "elapsedMs", elapsedMillis(startedAt));
                            if (!"settled".equals(metadataReason)) {
                                if (result != null && !result.successful()) {
                                    new Notification("Create", "Metadata Save Failed", Notification.Type.ERROR);
                                }
                                return;
                            }
                            if (completion != null) {
                                completion.accept(new Result(type, id, settlement.project()));
                            }
                            traceUiCreation(serverId, type, id, "create_visibility_callback_dispatched",
                                "worldgen_metadata_settled_browser_unproven", startedAt);
                        });
                    if (!admitted) {
                        traceUiCreation(serverId, type, id, "create_metadata_rejected",
                            "metadata_reconciliation_not_admitted", startedAt);
                        new Notification("Create", "Metadata Save Not Started", Notification.Type.ERROR);
                    }
                });
            ReSyncFlowClient.traceLifecycle(serverId, submission != null ? "create_request_admitted"
                : "create_request_rejected", "serverId", serverId, "resourceKey", type + ":" + id, "requestId",
                submission != null ? submission.operationId() : "worldgen", "mutationId", null, "generation", -1L,
                "authorityEpoch", 0L, "revision", -1L, "elapsedMs", elapsedMillis(startedAt));
            if (submission == null) {
                new Notification("World Generation Failed", "Save Not Submitted", Notification.Type.ERROR);
            }
            return;
        }
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (resourceType == null || !resourceType.enabled()) {
            traceUiCreation(serverId, type, id, "create_rejected",
                resourceType == null ? "resource_type_unknown" : "resource_type_disabled", startedAt);
            new Notification("Create", "Resource Type Unavailable", Notification.Type.ERROR);
            return;
        }
        FlowManager.ProjectMetadataEdit metadata = manager.editProjectMetadata(serverId);
        FlowManager.ProjectResource existing = metadata.resource(type, id);
        FlowManager.CreationMetadataIntent intent = new FlowManager.CreationMetadataIntent(
            type, id, id, FlowManager.canonicalResourcePath(resourceType, id, targetFolder), "",
            existing != null ? existing.sortOrder() : metadata.nextResourceSortOrder(), false);
        if (manager.coreGraphCreationRequired(serverId, resourceType)) {
            CoreGraphDocumentAuthoringAdapter.RequestResult request = manager.requestCoreGraphCreation(
                serverId, resourceType, id, id, null, intent, result -> {
                    traceCreationProjection(manager, serverId, resourceType, id, result,
                        result != null ? "create_observer_complete" : "create_observer_rejected", startedAt);
                    if (result == null || !resourceType.typeId().equals(result.type()) || !id.equals(result.id())) {
                        new Notification("Create", "Creation Result Invalid", Notification.Type.ERROR);
                        return;
                    }
                    if (completion != null && FlowManager.getInstance() == manager) {
                        completion.accept(new Result(type, id, result.resource()));
                    }
                });
            ReSyncFlowClient.traceLifecycle(serverId, request.admitted() ? "create_template_request_admitted"
                : "create_template_request_rejected", "serverId", serverId, "resourceKey", type + ":" + id,
                "requestId", request.requestId(), "mutationId", null, "generation", -1L, "authorityEpoch", 0L,
                "revision", 0L, "reason", request.reason(), "elapsedMs", elapsedMillis(startedAt));
            if (!request.admitted()) {
                new Notification("Create", coreCreationFailure(request.reason()), Notification.Type.ERROR);
            }
            return;
        }
        FlowManager.CreationAdmission admission = manager.beginResourceCreation(serverId, resourceType, id,
            ReSyncResourceDragPayload.FLOW.equals(type) ? template : null, intent,
            ReSyncResourceDragPayload.COMMAND.equals(type) ? id : null,
            result -> {
                traceCreationProjection(manager, serverId, resourceType, id, result,
                    result != null ? "create_observer_complete" : "create_observer_rejected", startedAt);
                if (result == null || !resourceType.typeId().equals(result.type()) || !id.equals(result.id())) {
                    new Notification("Create", "Creation Result Invalid", Notification.Type.ERROR);
                    return;
                }
                if (completion != null && FlowManager.getInstance() == manager) {
                    completion.accept(new Result(type, id, result.resource()));
                }
            });
        ReSyncFlowClient.traceLifecycle(serverId, admission.queued() ? "create_request_admitted"
            : "create_request_rejected", "serverId", serverId, "resourceKey", type + ":" + id, "requestId", "ui",
            "mutationId", null, "generation", -1L, "authorityEpoch", 0L, "revision", 0L, "reason",
            admission.message(), "elapsedMs", elapsedMillis(startedAt));
        if (admission.rejected()) {
            new Notification("Create", admission.message(), Notification.Type.ERROR);
        }
    }

    private static String coreCreationFailure(String reason) {
        return switch (reason == null ? "" : reason) {
            case CoreGraphDocumentAuthoringAdapter.CATALOG_AUTHORITY_UNAVAILABLE,
                 CoreGraphDocumentAuthoringAdapter.AUTHORING_CATALOG_UNAVAILABLE -> "Catalog Authority Unavailable";
            case CoreGraphDocumentAuthoringAdapter.TEMPLATE_TRANSPORT_UNAVAILABLE -> "Template Connection Unavailable";
            case CoreGraphDocumentAuthoringAdapter.TEMPLATE_DISPATCH_FAILED -> "Template Request Failed";
            case CoreGraphDocumentAuthoringAdapter.AUTHORING_TEMPLATE_STALE -> "Template Changed. Try Again";
            case CoreGraphDocumentAuthoringAdapter.AUTHORING_TEMPLATE_CHECKSUM_REQUIRED,
                 CoreGraphDocumentAuthoringAdapter.AUTHORING_TEMPLATE_CHECKSUM_MISMATCH -> "Template Verification Failed";
            case "AUTHORING_TEMPLATE_RESOURCE_INVALID" -> "Invalid Resource";
            default -> "Core Template Unavailable";
        };
    }

    public static boolean exists(FlowManager manager, String serverId, String type, String id) {
        return exists(manager, serverId, type, id, null);
    }

    public static boolean exists(FlowManager manager, String serverId, String type, String id, String folder) {
        if (manager == null || serverId == null || type == null || id == null) {
            return false;
        }
        if (graphIdOwner(manager, serverId, type, id) != null) {
            return true;
        }
        return switch (type) {
            case ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION,
                 ReSyncResourceDragPayload.COMMAND, ReSyncResourceDragPayload.CUSTOM_CONTENT,
                 ReSyncResourceDragPayload.GUI, ReSyncResourceDragPayload.SCOREBOARD,
                 ReSyncResourceDragPayload.TAB, ReSyncResourceDragPayload.CHAT, ReSyncResourceDragPayload.COMPONENT_BUILDER,
                 ReSyncResourceDragPayload.MOTD_PROFILE, ReSyncResourceDragPayload.MESSAGE_RULE,
                 ReSyncResourceDragPayload.RECIPE_DEFINITION, ReSyncResourceDragPayload.TEXT_TEMPLATE,
                 ReSyncResourceDragPayload.ADVANCEMENT_TREE, ReSyncResourceDragPayload.DIALOG,
                 ReSyncResourceDragPayload.TRADE_PROFILE, ReSyncResourceDragPayload.NPC_DEFINITION,
                 ReSyncResourceDragPayload.LOOT_TABLE, ReSyncResourceDragPayload.VARIABLE_DEFINITION,
                 ReSyncResourceDragPayload.TIMER_DEFINITION, ReSyncResourceDragPayload.SCHEDULE_DEFINITION -> {
                ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
                yield resourceType != null && manager.hasAuthoritativeResource(serverId, resourceType, id);
            }
            case ReSyncResourceDragPayload.WORLDGEN -> WorldGenManager.getInstance().getProjectIds(serverId).contains(id);
            case ReSyncResourceDragPayload.WORLD -> WorldResourceCreator.worldExists(manager, serverId, id);
            default -> false;
        };
    }

    private static String graphIdOwner(FlowManager manager, String serverId, String type, String id) {
        if (!isGraphType(type)) {
            return null;
        }
        for (String candidate : new String[]{ReSyncResourceDragPayload.FLOW, ReSyncResourceDragPayload.FUNCTION,
            ReSyncResourceDragPayload.COMMAND}) {
            ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(candidate);
            if (resourceType != null && manager.hasAuthoritativeResource(serverId, resourceType, id)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isGraphType(String type) {
        return ReSyncResourceDragPayload.FLOW.equals(type) || ReSyncResourceDragPayload.FUNCTION.equals(type)
            || ReSyncResourceDragPayload.COMMAND.equals(type);
    }

    public static String createPopupTitle(String type) {
        return "Create " + resourceTypeName(type);
    }

    public static String createIdPlaceholder(String type) {
        return switch (type) {
            case ReSyncResourceDragPayload.FOLDER -> "Folder Name";
            case ReSyncResourceDragPayload.FUNCTION -> "newFunction";
            case ReSyncResourceDragPayload.COMMAND -> "newCommand";
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> "newContent";
            case ReSyncResourceDragPayload.GUI -> "newGui";
            case ReSyncResourceDragPayload.SCOREBOARD -> "newScoreboard";
            case ReSyncResourceDragPayload.TAB -> "newTab";
            case ReSyncResourceDragPayload.CHAT -> "newChat";
            case ReSyncResourceDragPayload.COMPONENT_BUILDER -> "newComponentBuilder";
            case ReSyncResourceDragPayload.MOTD_PROFILE -> "newMotdProfile";
            case ReSyncResourceDragPayload.MESSAGE_RULE -> "newMessageRule";
            case ReSyncResourceDragPayload.RECIPE_DEFINITION -> "newRecipe";
            case ReSyncResourceDragPayload.TEXT_TEMPLATE -> "newTextTemplate";
            case ReSyncResourceDragPayload.ADVANCEMENT_TREE -> "new_advancement_tree";
            case ReSyncResourceDragPayload.DIALOG -> "newDialog";
            case ReSyncResourceDragPayload.TRADE_PROFILE -> "newTradeProfile";
            case ReSyncResourceDragPayload.NPC_DEFINITION -> "newNpc";
            case ReSyncResourceDragPayload.LOOT_TABLE -> "newLootTable";
            case ReSyncResourceDragPayload.WORLDGEN -> "newWorldGen";
            case ReSyncResourceDragPayload.WORLD -> "newWorld";
            case ReSyncResourceDragPayload.VARIABLE_DEFINITION -> "newVariable";
            case ReSyncResourceDragPayload.TIMER_DEFINITION -> "newTimer";
            case ReSyncResourceDragPayload.SCHEDULE_DEFINITION -> "newSchedule";
            default -> "newFlow";
        };
    }

    public static String suggestedId(String serverId, String type, String folder) {
        String base = createIdPlaceholder(type);
        if (ReSyncResourceDragPayload.FOLDER.equals(type)) {
            return "";
        }
        FlowManager manager = FlowManager.getInstance();
        String candidate = base;
        int suffix = 2;
        while (exists(manager, serverId, type, candidate, folder)) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    public static String resourceTypeName(String type) {
        return switch (type) {
            case ReSyncResourceDragPayload.FOLDER -> "Folder";
            case ReSyncResourceDragPayload.FUNCTION -> "Function";
            case ReSyncResourceDragPayload.COMMAND -> "Command";
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> "Content";
            case ReSyncResourceDragPayload.GUI -> "GUI";
            case ReSyncResourceDragPayload.SCOREBOARD -> "Scoreboard";
            case ReSyncResourceDragPayload.TAB -> "Tab";
            case ReSyncResourceDragPayload.CHAT -> "Chat";
            case ReSyncResourceDragPayload.COMPONENT_BUILDER -> "Component Builder";
            case ReSyncResourceDragPayload.MOTD_PROFILE -> "MOTD";
            case ReSyncResourceDragPayload.MESSAGE_RULE -> "Message Rule";
            case ReSyncResourceDragPayload.RECIPE_DEFINITION -> "Recipe";
            case ReSyncResourceDragPayload.TEXT_TEMPLATE -> "Text";
            case ReSyncResourceDragPayload.ADVANCEMENT_TREE -> "Advancement";
            case ReSyncResourceDragPayload.DIALOG -> "Dialog";
            case ReSyncResourceDragPayload.TRADE_PROFILE -> "Trade";
            case ReSyncResourceDragPayload.NPC_DEFINITION -> "NPC";
            case ReSyncResourceDragPayload.LOOT_TABLE -> "Loot Table";
            case ReSyncResourceDragPayload.WORLDGEN -> "WorldGen";
            case ReSyncResourceDragPayload.WORLD -> "World";
            case ReSyncResourceDragPayload.VARIABLE_DEFINITION -> "Variable";
            case ReSyncResourceDragPayload.TIMER_DEFINITION -> "Timer";
            case ReSyncResourceDragPayload.SCHEDULE_DEFINITION -> "Schedule";
            default -> "Flow";
        };
    }

    private static String normalizedFolder(String folder) {
        return ReSyncProjectMetadata.normalizePath(folder == null ? "" : folder);
    }

    private static long elapsedMillis(long startedAt) {
        return startedAt == 0L ? -1L : ((System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static void traceUiCreation(String serverId, String type, String id, String stage, String reason,
                                        long startedAt) {
        String source = serverId == null || serverId.isBlank() ? "unresolved" : serverId;
        ReSyncFlowClient.traceLifecycle(source, stage, "serverId", source, "resourceKey",
            (type == null || type.isBlank() ? "unknown" : type) + ":"
                + (id == null || id.isBlank() ? "unknown" : id),
            "operation", "create", "requestId", "ui", "mutationId", null, "generation", -1L,
            "authorityEpoch", 0L, "revision", 0L, "reason", reason, "elapsedMs", elapsedMillis(startedAt));
    }

    private static void traceCreationProjection(FlowManager manager, String serverId, ReSyncResourceType type,
                                                String id, FlowManager.CreationResult result, String stage,
                                                long startedAt) {
        FlowManager.TypedResourceMembershipSnapshot membership = manager.snapshotTypedResourceMembership(serverId);
        boolean membershipPresent = membership.contains(type.typeId(), id);
        boolean listComplete = membership.completeTypes().contains(type.typeId());
        boolean metadataPresent = manager.getAuthoritativeProjectResource(serverId, type.typeId(), id) != null;
        boolean corePresent = type.isGraph() && manager.isCoreGraphAuthoritative(serverId, type, id);
        boolean browserIncluded = manager.getProjectResource(serverId, type.typeId(), id) != null;
        ReSyncFlowClient.traceLifecycle(serverId, stage, "serverId", serverId, "resourceKey",
            type.typeId() + ":" + id, "operation", "create", "requestId", "observer", "mutationId", null,
            "generation", membership.connectionGeneration(), "authorityEpoch", 0L, "revision", 0L,
            "resultPresent", result != null, "metadataPresent", metadataPresent, "corePresent", corePresent,
            "listComplete", listComplete, "typedMembershipPresent", membershipPresent, "browserIncluded",
            browserIncluded, "browserSettled", false, "editorSettled", false, "elapsedMs",
            elapsedMillis(startedAt));
    }

    private static String worldGenSettlementReason(String serverId, String id,
                                                   WorldGenManager.ProjectSaveSubmissionSettlement settlement) {
        if (settlement == null) {
            return "settlement_missing";
        }
        if (!settlement.committed()) {
            return "resource_not_committed";
        }
        if (settlement.project() == null) {
            return "authoritative_project_missing";
        }
        if (settlement.submission() == null) {
            return "submission_identity_missing";
        }
        if (settlement.connection() == null) {
            return "connection_identity_missing";
        }
        if (settlement.authorityEpoch() < 1L) {
            return "authority_epoch_invalid";
        }
        if (!serverId.equals(settlement.submission().serverId())) {
            return "submission_server_mismatch";
        }
        if (!id.equals(settlement.submission().projectId())) {
            return "submission_resource_mismatch";
        }
        return id.equals(settlement.project().getId()) ? "settled" : "authoritative_project_mismatch";
    }

    private static String worldGenMetadataSettlementReason(String serverId, String id, String operationId,
                                                           WorldGenManager.ProjectMetadataReconciliationResult result,
                                                           FlowManager manager, WorldGenManager worldGen) {
        if (result == null) {
            return "settlement_missing";
        }
        if (!operationId.equals(result.operationId())) {
            return "operation_identity_mismatch";
        }
        if (!serverId.equals(result.serverId())) {
            return "server_identity_mismatch";
        }
        if (!id.equals(result.projectId())) {
            return "resource_identity_mismatch";
        }
        if (!result.add()) {
            return "metadata_operation_mismatch";
        }
        if (!result.successful()) {
            return "metadata_not_committed";
        }
        if (manager == null) {
            return "manager_missing";
        }
        if (worldGen == null) {
            return "worldgen_manager_missing";
        }
        if (result.connection() == null) {
            return "connection_identity_missing";
        }
        if (!manager.isCurrentServerConnection(result.connection())) {
            return "connection_generation_stale";
        }
        return worldGen.authorityEpoch(serverId) == result.authorityEpoch()
            ? "settled" : "authority_epoch_stale";
    }
}
