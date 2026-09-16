package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import redxax.oxy.remotely.RemotelyClient;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.AuthoringTemplateRequest;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class ReSyncLiveCrudAcceptanceMain {
    static final String MUTATION_CONFIRMATION = "MUTATE_RESYNC_ACCEPTANCE_RESOURCES";
    static final int REPORT_SCHEMA_VERSION = 1;
    private static final int MAX_REPORT_BYTES = 65_536;
    private static final int MAX_IDENTIFIER_LENGTH = 96;
    private static final OwnerId PROTOCOL_OWNER = new OwnerId("restudio.resync");
    private static final Gson GSON = new Gson();
    private static final TypeToken<Map<String, Object>> JSON_MAP = new TypeToken<>() {
    };

    private ReSyncLiveCrudAcceptanceMain() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.from(System.getenv());
        AcceptanceReport report = new AcceptanceReport(config);
        HeadlessProbe probe = null;
        ReSyncFlowClient client = null;
        try {
            config.validate();
            probe = new HeadlessProbe();
            FlowManager manager = probe.getFlowManager();
            client = new ReSyncFlowClient(config.serverId(), null, config.wsUrl(), config.apiKey(), probe);
            FlowManagerTestConnection.installDirectCurrent(manager, config.serverId(), client,
                config.wsUrl(), config.apiKey());
            if (client == null) {
                throw new AcceptanceFailure("flow_client_admission_failed");
            }
            ReSyncFlowClient activeClient = client;
            AtomicInteger protocolErrors = new AtomicInteger();
            FlowManagerTestConnection.observeCurrent(manager, client, null, null,
                (ignored, message) -> protocolErrors.incrementAndGet());
            if (FlowManagerTestConnection.connectCurrent(manager, config.serverId()) != client) {
                throw new AcceptanceFailure("flow_client_ownership_lost");
            }
            await("connection_ready", config.timeoutMillis(), () -> activeClient.isConnectedState()
                && activeClient.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
                && activeClient.resourceRevisionReconciler().authorityEpoch(config.serverId()) > 0L);
            report.observe("connection", "ready");
            report.observe("authorityEpoch", client.resourceRevisionReconciler().authorityEpoch(config.serverId()));
            report.observe("catalogAuthority", client.catalogAuthority().name());

            if (config.existingGeneric() != null) {
                runExistingGeneric(config, report, client, manager);
            }
            if (config.existingCore() != null) {
                runExistingCore(config, report, client);
            }
            if (config.mutating() && config.genericType() != null) {
                runGenericCycle(config, report, client, probe.getFlowManager());
            }
            if (config.mutating() && config.coreCreateType() != null) {
                runCoreCreateCycle(config, report, client);
            }
            if (config.mutating() && config.coreRollover()) {
                runCoreRolloverCycle(config, report, client, manager);
            }
            report.observe("protocolErrors", protocolErrors.get());
            if (protocolErrors.get() > 0) {
                throw new AcceptanceFailure("protocol_errors");
            }
            report.pass();
        } catch (Throwable failure) {
            report.fail(safeFailure(failure));
        } finally {
            if (probe != null) {
                probe.shutdownProbe();
            } else if (client != null) {
                client.shutdown();
            }
        }
        Path reportPath = writeReport(report);
        System.out.println("ReSync live CRUD acceptance " + report.status() + "; report=" + reportPath);
        if (!report.passed()) {
            throw new IllegalStateException("ReSync live CRUD acceptance failed; report=" + reportPath);
        }
    }

    private static void runGenericCycle(Config config, AcceptanceReport report, ReSyncFlowClient client,
                                        FlowManager manager) throws Exception {
        ReSyncResourceType type = config.genericType();
        String id = uniqueId(config.idPrefix(), "json");
        client.requestResourceList(type);
        await("generic_list_authority", config.timeoutMillis(), () -> client.isResourceListAuthoritative(type));
        if (manager.getJsonResourcesForServer(config.serverId(), type).containsKey(id)
            || live(client.resourceRevisionReconciler().get(config.serverId(), type.typeId(), id))) {
            throw new AcceptanceFailure("generic_id_collision");
        }
        JsonObject created = manager.createJsonResource(config.serverId(), type, id, type.defaultFolder());
        if (created == null) {
            throw new AcceptanceFailure("generic_type_not_json_backed");
        }
        created.addProperty("displayName", "Acceptance " + id);
        PreparedJson createPayload = prepareJson(type, created);
        FlowManager.CreationMetadataIntent metadata = new FlowManager.CreationMetadataIntent(type.typeId(), id,
            "Acceptance " + id, canonicalPath(type, id), "", 0, false);
        DesignerSaveNotifications.SaveTicket createTicket = ticket(config.serverId(), type, id);
        CompletableFuture<Boolean> createSettlement = settlement(createTicket);
        if (!client.sendPreparedResourceCreate(type, id, createPayload.json(), createPayload.hash(), metadata,
            createTicket)) {
            throw new AcceptanceFailure("generic_create_not_admitted");
        }
        if (!awaitSettlement("generic_create", createSettlement, config.timeoutMillis())) {
            throw new AcceptanceFailure("generic_create_rejected");
        }
        ReSyncResourceRevisionReconciler.ResourceResult createdState = awaitGenericState(client, config, type, id,
            result -> live(result) && result.payloadHash().equals(createPayload.hash().canonicalText()));
        report.phase("genericCreate", type, id, createdState.revision(), createdState.payloadHash(), "passed");

        client.requestResource(type, id, false);
        ReSyncResourceRevisionReconciler.ResourceResult loadedState = awaitGenericState(client, config, type, id,
            result -> live(result) && result.revision() == createdState.revision()
                && result.payloadHash().equals(createdState.payloadHash()));
        JsonObject loaded = manager.getJsonResource(config.serverId(), type, id);
        if (loaded == null || !id.equals(type.extractId(loaded))) {
            throw new AcceptanceFailure("generic_load_projection_missing");
        }
        report.phase("genericLoad", type, id, loadedState.revision(), loadedState.payloadHash(), "passed");

        JsonObject updated = loaded.deepCopy();
        updated.addProperty("displayName", "Acceptance Updated " + id);
        PreparedJson savePayload = prepareJson(type, updated);
        DesignerSaveNotifications.SaveTicket saveTicket = ticket(config.serverId(), type, id);
        CompletableFuture<Boolean> saveSettlement = settlement(saveTicket);
        manager.saveJsonResource(config.serverId(), type, updated, saveTicket);
        if (!awaitSettlement("generic_save", saveSettlement, config.timeoutMillis())) {
            throw new AcceptanceFailure("generic_save_rejected");
        }
        ReSyncResourceRevisionReconciler.ResourceResult savedState = awaitGenericState(client, config, type, id,
            result -> live(result) && result.revision() > createdState.revision()
                && result.payloadHash().equals(savePayload.hash().canonicalText()));
        report.phase("genericSave", type, id, savedState.revision(), savedState.payloadHash(), "passed");

        if (!client.sendSettledResourceDelete(type, id)) {
            throw new AcceptanceFailure("generic_delete_not_admitted");
        }
        ReSyncResourceRevisionReconciler.ResourceResult tombstone = awaitGenericState(client, config, type, id,
            result -> result != null && result.deleted() && result.revision() > savedState.revision());
        client.requestResourceList(type);
        await("generic_delete_list", config.timeoutMillis(), () -> client.isResourceListAuthoritative(type)
            && !manager.getJsonResourcesForServer(config.serverId(), type).containsKey(id));
        report.phase("genericDelete", type, id, tombstone.revision(), tombstone.payloadHash(), "passed");
    }

    private static void runExistingGeneric(Config config, AcceptanceReport report, ReSyncFlowClient client,
                                           FlowManager manager) throws Exception {
        ExistingGeneric existing = config.existingGeneric();
        client.requestResourceList(existing.type());
        await("existing_generic_list_authority", config.timeoutMillis(),
            () -> client.isResourceListAuthoritative(existing.type()));
        client.requestResource(existing.type(), existing.id(), false);
        ReSyncResourceRevisionReconciler.ResourceResult state = awaitGenericState(client, config, existing.type(),
            existing.id(), ReSyncLiveCrudAcceptanceMain::live);
        if (state.revision() != existing.expectedRevision()
            || !state.payloadHash().equals(existing.expectedPayloadHash())) {
            throw new AcceptanceFailure("existing_generic_identity_mismatch");
        }
        JsonObject resource = manager.getJsonResource(config.serverId(), existing.type(), existing.id());
        if (resource == null || !existing.id().equals(existing.type().extractId(resource))) {
            throw new AcceptanceFailure("existing_generic_projection_missing");
        }
        report.phase("existingGenericLoad", existing.type(), existing.id(), state.revision(), state.payloadHash(),
            "passed");
        if (!existing.delete()) {
            return;
        }
        if (!client.sendSettledResourceDelete(existing.type(), existing.id())) {
            throw new AcceptanceFailure("existing_generic_delete_not_admitted");
        }
        ReSyncResourceRevisionReconciler.ResourceResult tombstone = awaitGenericState(client, config,
            existing.type(), existing.id(), result -> result != null && result.deleted()
                && result.revision() > existing.expectedRevision());
        client.requestResourceList(existing.type());
        await("existing_generic_delete_list", config.timeoutMillis(), () ->
            client.isResourceListAuthoritative(existing.type())
                && !manager.getJsonResourcesForServer(config.serverId(), existing.type()).containsKey(existing.id()));
        report.phase("existingGenericDelete", existing.type(), existing.id(), tombstone.revision(),
            tombstone.payloadHash(), "passed");
    }

    private static void runCoreCreateCycle(Config config, AcceptanceReport report, ReSyncFlowClient client)
        throws Exception {
        ReSyncResourceType type = config.coreCreateType();
        String id = uniqueId(config.idPrefix(), type.typeId());
        ServerResourceLocator locator = locator(config.serverId(), type, id);
        client.requestResourceList(type);
        await("core_create_list_authority", config.timeoutMillis(), () -> client.isResourceListAuthoritative(type));
        if (client.resourceRevisionReconciler().graphState(locator).filter(state -> !state.deleted()).isPresent()) {
            throw new AcceptanceFailure("core_id_collision");
        }
        ReSyncCatalogPublicationProjection.Snapshot publication = client.catalogPublicationProjection().active()
            .orElseThrow(() -> new AcceptanceFailure("core_publication_missing"));
        ContentHash authoringChecksum = client.activeCatalogAuthoringChecksum()
            .orElseThrow(() -> new AcceptanceFailure("core_authoring_missing"));
        AuthoringTemplateRequest request = new AuthoringTemplateRequest(locator, publication.publication().key(),
            authoringChecksum);
        CompletableFuture<AuthoringTemplateResponse> response = new CompletableFuture<>();
        UUID templateRequestId = UUID.randomUUID();
        if (!client.requestAuthoringTemplate(templateRequestId, request, response::complete)) {
            throw new AcceptanceFailure("core_template_not_admitted");
        }
        AuthoringTemplateResponse template = response.get(config.timeoutMillis(), TimeUnit.MILLISECONDS);
        if (template == null || !locator.equals(template.resource())) {
            throw new AcceptanceFailure("core_template_rejected");
        }
        CoreGraphEditorSession session = CoreGraphEditorSession.fromResponse(template);
        FlowManager.CreationMetadataIntent metadata = new FlowManager.CreationMetadataIntent(type.typeId(), id,
            "Acceptance " + id, canonicalPath(type, id), "", 0, false);
        DesignerSaveNotifications.SaveTicket createTicket = ticket(config.serverId(), type, id);
        CompletableFuture<Boolean> createSettlement = settlement(createTicket);
        if (!client.sendCoreGraphCreate(type, session.payload(), metadata, createTicket)) {
            throw new AcceptanceFailure("core_create_not_admitted");
        }
        if (!awaitSettlement("core_create", createSettlement, config.timeoutMillis())) {
            throw new AcceptanceFailure("core_create_rejected");
        }
        GraphResourceState created = awaitGraphState(client, config, locator,
            state -> state != null && !state.deleted() && state.revision() > 0L && state.assetHash() != null);
        report.phase("coreCreate", type, id, created.revision(), created.assetHash().canonicalText(), "passed");

        client.requestResource(type, id, false);
        GraphResourceState loaded = awaitGraphState(client, config, locator,
            state -> state != null && !state.deleted() && state.revision() == created.revision()
                && state.assetHash().equals(created.assetHash()));
        report.phase("coreLoad", type, id, loaded.revision(), loaded.assetHash().canonicalText(), "passed");

        if (!client.sendCoreResourceDelete(type, id)) {
            throw new AcceptanceFailure("core_delete_not_admitted");
        }
        GraphResourceState tombstone = awaitGraphState(client, config, locator,
            state -> state != null && state.deleted() && state.revision() > loaded.revision());
        report.phase("coreDelete", type, id, tombstone.revision(), tombstone.protocolHash().canonicalText(), "passed");
    }

    private static void runCoreRolloverCycle(Config config, AcceptanceReport report, ReSyncFlowClient client,
                                             FlowManager manager) throws Exception {
        String flowId = uniqueId(config.idPrefix(), "rollover_command");
        String functionId = uniqueId(config.idPrefix(), "rollover_function");
        ServerResourceLocator flowLocator = locator(config.serverId(), ReSyncResourceType.COMMAND, flowId);
        ServerResourceLocator functionLocator = locator(config.serverId(), ReSyncResourceType.FUNCTION, functionId);
        boolean flowCreated = false;
        boolean functionCreated = false;
        try {
            GraphResourceState createdFlow = createCoreResource(config, client, ReSyncResourceType.COMMAND, flowId);
            flowCreated = true;
            client.requestResource(ReSyncResourceType.COMMAND, flowId, false);
            GraphResourceState loadedFlow = awaitGraphState(client, config, flowLocator,
                state -> state != null && !state.deleted() && state.revision() == createdFlow.revision());
            if (!manager.requestCoreGraphHydration(config.serverId(), ReSyncResourceType.COMMAND, flowId)) {
                throw new AcceptanceFailure("rollover_flow_hydration_not_admitted");
            }
            AtomicReference<CoreGraphEditorSession> opened = new AtomicReference<>();
            await("rollover_flow_session", config.timeoutMillis(), () -> {
                CoreGraphEditorSession candidate = manager.coreGraphEditorSession(config.serverId(),
                    ReSyncResourceType.COMMAND, flowId).orElse(null);
                opened.set(candidate);
                return candidate != null && manager.isCurrentCoreGraphEditorSession(config.serverId(),
                    ReSyncResourceType.COMMAND, flowId, candidate);
            });
            CoreGraphEditorSession session = opened.get();
            if (session.graphDocument().nodes().isEmpty()) {
                throw new AcceptanceFailure("rollover_flow_template_empty");
            }
            var node = session.graphDocument().nodes().getFirst();
            double firstX = node.x() + 37.0D;
            double firstY = node.y() + 19.0D;
            session.setNodePosition(node.instanceId(), firstX, firstY);
            if (!session.isDirty()) {
                throw new AcceptanceFailure("rollover_flow_edit_not_dirty");
            }
            var previousPublication = client.catalogPublicationProjection().active()
                .orElseThrow(() -> new AcceptanceFailure("rollover_publication_missing"))
                .publication().key();

            createCoreResource(config, client, ReSyncResourceType.FUNCTION, functionId);
            functionCreated = true;
            await("rollover_catalog_transition", config.timeoutMillis(), () -> client.catalogPublicationProjection()
                .active().map(snapshot -> !snapshot.publication().key().equals(previousPublication)).orElse(false));
            var nextPublication = client.catalogPublicationProjection().active().orElseThrow().publication();
            client.requestResource(ReSyncResourceType.COMMAND, flowId, false);
            GraphResourceState reboundFlow = awaitGraphState(client, config, flowLocator,
                state -> state != null && !state.deleted() && state.revision() > loadedFlow.revision()
                    && state.catalogBinding().equals(nextPublication.binding()));
            await("rollover_session_rebase", config.timeoutMillis(), () -> {
                CoreGraphEditorSession current = manager.coreGraphEditorSession(config.serverId(),
                    ReSyncResourceType.COMMAND, flowId).orElse(null);
                if (current != session || !manager.isCurrentCoreGraphEditorSession(config.serverId(),
                    ReSyncResourceType.COMMAND, flowId, session) || !session.isDirty()
                    || !session.catalogBinding().equals(nextPublication.binding())) {
                    return false;
                }
                return session.graphDocument().nodes().stream().anyMatch(candidate ->
                    candidate.instanceId().equals(node.instanceId()) && candidate.x() == firstX && candidate.y() == firstY);
            });
            report.phase("coreRolloverRebase", ReSyncResourceType.COMMAND, flowId, reboundFlow.revision(),
                reboundFlow.assetHash().canonicalText(), "passed");

            if (!manager.discardCoreGraphSession(config.serverId(), ReSyncResourceType.COMMAND, flowId)
                || session.isDirty()) {
                throw new AcceptanceFailure("rollover_discard_failed");
            }
            report.phase("coreRolloverDiscard", ReSyncResourceType.COMMAND, flowId, session.baselineRevision(),
                session.baselineGraphDocument().checksum().canonicalText(), "passed");

            var savedNode = session.graphDocument().nodes().getFirst();
            session.setNodePosition(savedNode.instanceId(), savedNode.x() + 11.0D, savedNode.y() + 7.0D);
            DesignerSaveNotifications.SaveTicket saveTicket = ticket(config.serverId(), ReSyncResourceType.COMMAND, flowId);
            CompletableFuture<Boolean> saveSettlement = settlement(saveTicket);
            if (!manager.saveCoreGraph(config.serverId(), ReSyncResourceType.COMMAND, session, saveTicket)) {
                throw new AcceptanceFailure("rollover_save_not_admitted");
            }
            if (!awaitSettlement("rollover_save", saveSettlement, config.timeoutMillis())) {
                throw new AcceptanceFailure("rollover_save_rejected");
            }
            GraphResourceState savedFlow = awaitGraphState(client, config, flowLocator,
                state -> state != null && !state.deleted() && state.revision() > reboundFlow.revision());
            await("rollover_save_session", config.timeoutMillis(), () -> !session.isDirty()
                && session.baselineRevision() == savedFlow.revision());
            report.phase("coreRolloverSave", ReSyncResourceType.COMMAND, flowId, savedFlow.revision(),
                savedFlow.assetHash().canonicalText(), "passed");
        } finally {
            if (functionCreated) {
                deleteCoreResource(config, client, ReSyncResourceType.FUNCTION, functionId, functionLocator);
            }
            if (flowCreated) {
                deleteCoreResource(config, client, ReSyncResourceType.COMMAND, flowId, flowLocator);
            }
        }
    }

    private static GraphResourceState createCoreResource(Config config, ReSyncFlowClient client,
                                                         ReSyncResourceType type, String id) throws Exception {
        ServerResourceLocator resource = locator(config.serverId(), type, id);
        client.requestResourceList(type);
        await("rollover_create_list", config.timeoutMillis(), () -> client.isResourceListAuthoritative(type));
        if (client.resourceRevisionReconciler().graphState(resource).filter(state -> !state.deleted()).isPresent()) {
            throw new AcceptanceFailure("rollover_id_collision");
        }
        ReSyncCatalogPublicationProjection.Snapshot publication = client.catalogPublicationProjection().active()
            .orElseThrow(() -> new AcceptanceFailure("rollover_publication_missing"));
        ContentHash authoringChecksum = client.activeCatalogAuthoringChecksum()
            .orElseThrow(() -> new AcceptanceFailure("rollover_authoring_missing"));
        CompletableFuture<AuthoringTemplateResponse> response = new CompletableFuture<>();
        if (!client.requestAuthoringTemplate(UUID.randomUUID(),
            new AuthoringTemplateRequest(resource, publication.publication().key(), authoringChecksum),
            response::complete)) {
            throw new AcceptanceFailure("rollover_template_not_admitted");
        }
        AuthoringTemplateResponse template = response.get(config.timeoutMillis(), TimeUnit.MILLISECONDS);
        if (template == null || !resource.equals(template.resource())) {
            throw new AcceptanceFailure("rollover_template_rejected");
        }
        CoreGraphEditorSession session = CoreGraphEditorSession.fromResponse(template);
        FlowManager.CreationMetadataIntent metadata = new FlowManager.CreationMetadataIntent(type.typeId(), id,
            "Acceptance " + id, canonicalPath(type, id), "", 0, false);
        DesignerSaveNotifications.SaveTicket createTicket = ticket(config.serverId(), type, id);
        CompletableFuture<Boolean> settlement = settlement(createTicket);
        if (!client.sendCoreGraphCreate(type, session.payload(), metadata, createTicket)) {
            throw new AcceptanceFailure("rollover_create_not_admitted");
        }
        if (!awaitSettlement("rollover_create", settlement, config.timeoutMillis())) {
            throw new AcceptanceFailure("rollover_create_rejected");
        }
        return awaitGraphState(client, config, resource,
            state -> state != null && !state.deleted() && state.revision() > 0L && state.assetHash() != null);
    }

    private static void deleteCoreResource(Config config, ReSyncFlowClient client, ReSyncResourceType type,
                                           String id, ServerResourceLocator resource) throws Exception {
        GraphResourceState before = client.resourceRevisionReconciler().graphState(resource).orElse(null);
        if (before == null || before.deleted()) {
            return;
        }
        if (!client.sendCoreResourceDelete(type, id)) {
            throw new AcceptanceFailure("rollover_cleanup_not_admitted");
        }
        awaitGraphState(client, config, resource,
            state -> state != null && state.deleted() && state.revision() > before.revision());
    }

    private static void runExistingCore(Config config, AcceptanceReport report, ReSyncFlowClient client)
        throws Exception {
        ExistingCore expected = config.existingCore();
        ServerResourceLocator locator = locator(config.serverId(), expected.type(), expected.id());
        client.requestResource(expected.type(), expected.id(), false);
        GraphResourceState loaded = awaitGraphState(client, config, locator,
            state -> state != null && !state.deleted() && state.assetHash() != null);
        if (expected.expectedRevision() > 0L && loaded.revision() != expected.expectedRevision()) {
            throw new AcceptanceFailure("existing_core_revision_mismatch");
        }
        if (!expected.expectedAssetHash().isBlank()
            && !expected.expectedAssetHash().equals(loaded.assetHash().canonicalText())) {
            throw new AcceptanceFailure("existing_core_hash_mismatch");
        }
        report.phase("existingCoreLoad", expected.type(), expected.id(), loaded.revision(),
            loaded.assetHash().canonicalText(), "passed");
        if (!expected.delete()) {
            return;
        }
        String exactConfirmation = deleteConfirmation(expected.type(), expected.id(), loaded.revision(),
            loaded.assetHash().canonicalText());
        if (!exactConfirmation.equals(expected.deleteConfirmation())) {
            throw new AcceptanceFailure("existing_core_delete_confirmation_mismatch");
        }
        if (!client.sendCoreResourceDelete(expected.type(), expected.id())) {
            throw new AcceptanceFailure("existing_core_delete_not_admitted");
        }
        GraphResourceState tombstone = awaitGraphState(client, config, locator,
            state -> state != null && state.deleted() && state.revision() > loaded.revision());
        report.phase("existingCoreDelete", expected.type(), expected.id(), tombstone.revision(),
            tombstone.protocolHash().canonicalText(), "passed");
    }

    private static ReSyncResourceRevisionReconciler.ResourceResult awaitGenericState(ReSyncFlowClient client,
                                                                                     Config config,
                                                                                     ReSyncResourceType type,
                                                                                     String id,
                                                                                     Predicate<ReSyncResourceRevisionReconciler.ResourceResult> predicate)
        throws Exception {
        AtomicReference<ReSyncResourceRevisionReconciler.ResourceResult> result = new AtomicReference<>();
        await("generic_state", config.timeoutMillis(), () -> {
            ReSyncResourceRevisionReconciler.ResourceResult current = client.resourceRevisionReconciler()
                .get(config.serverId(), type.typeId(), id);
            result.set(current);
            return predicate.test(current);
        });
        return result.get();
    }

    private static GraphResourceState awaitGraphState(ReSyncFlowClient client, Config config,
                                                      ServerResourceLocator locator,
                                                      Predicate<GraphResourceState> predicate)
        throws Exception {
        AtomicReference<GraphResourceState> result = new AtomicReference<>();
        await("core_state", config.timeoutMillis(), () -> {
            GraphResourceState current = client.resourceRevisionReconciler().graphState(locator).orElse(null);
            result.set(current);
            return predicate.test(current);
        });
        return result.get();
    }

    private static PreparedJson prepareJson(ReSyncResourceType type, JsonObject resource) {
        String json = type.serialize(resource);
        Map<String, Object> payload = GSON.fromJson(json, JSON_MAP.getType());
        ContentHash hash = ResourcePayloadCodecs.json().canonicalize(payload).checksum();
        return new PreparedJson(json, hash);
    }

    private static DesignerSaveNotifications.SaveTicket ticket(String serverId, ReSyncResourceType type, String id) {
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startSilentResumableExact(serverId,
            type, id, "Acceptance " + id, UUID.randomUUID(), UUID.randomUUID());
        if (ticket == null) {
            throw new AcceptanceFailure("save_ticket_unavailable");
        }
        return ticket;
    }

    private static CompletableFuture<Boolean> settlement(DesignerSaveNotifications.SaveTicket ticket) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        ticket.whenFinished((saved, currentAtFinish) -> result.complete(saved));
        return result;
    }

    private static boolean awaitSettlement(String phase, CompletableFuture<Boolean> settlement, long timeoutMillis)
        throws Exception {
        try {
            return settlement.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new AcceptanceFailure(phase + "_timeout");
        }
    }

    private static void await(String phase, long timeoutMillis, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AcceptanceFailure(phase + "_timeout");
    }

    static String uniqueId(String prefix, String kind) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return normalizeId(prefix) + "_" + normalizeId(kind) + "_" + suffix;
    }

    static String deleteConfirmation(ReSyncResourceType type, String id, long revision, String hash) {
        return "DELETE:" + type.typeId() + ":" + id + ":" + revision + ":" + hash;
    }

    private static String normalizeId(String value) {
        String normalized = value == null ? "acceptance" : value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_-]", "_").replaceAll("_+", "_");
        if (normalized.isBlank()) {
            normalized = "acceptance";
        }
        return normalized.substring(0, Math.min(32, normalized.length()));
    }

    private static String canonicalPath(ReSyncResourceType type, String id) {
        String folder = type.defaultFolder().replace('\\', '/').replaceAll("/+$", "");
        return folder + "/" + id + ".json";
    }

    private static ServerResourceLocator locator(String serverId, ReSyncResourceType type, String id) {
        return new ServerResourceLocator(new ServerId(UUID.fromString(serverId)),
            ContractRef.of(PROTOCOL_OWNER, ResourceTypeId.of(type.typeId())), id);
    }

    private static boolean live(ReSyncResourceRevisionReconciler.ResourceResult result) {
        return result != null && !result.deleted() && result.revision() > 0L && !result.payloadHash().isBlank();
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static Path writeReport(AcceptanceReport report) throws IOException {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Files.createDirectories(home);
        Path target = home.resolve("resync-live-crud-acceptance.json");
        byte[] bytes = CanonicalJson.canonicalize(report.toMap()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REPORT_BYTES) {
            throw new IOException("Acceptance report exceeded its size limit");
        }
        Path staged = home.resolve("resync-live-crud-acceptance.json.stage");
        Files.write(staged, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static String safeFailure(Throwable failure) {
        if (failure instanceof AcceptanceFailure acceptance) {
            return acceptance.code();
        }
        return failure == null ? "unknown_failure" : failure.getClass().getSimpleName();
    }

    record PreparedJson(String json, ContentHash hash) {
    }

    record ExistingCore(ReSyncResourceType type, String id, long expectedRevision, String expectedAssetHash,
                        boolean delete, String deleteConfirmation) {
    }

    record ExistingGeneric(ReSyncResourceType type, String id, long expectedRevision, String expectedPayloadHash,
                           boolean delete, String deleteConfirmation) {
    }

    record Config(boolean enabled, boolean mutating, String serverId, String wsUrl, String apiKey,
                   long timeoutMillis, String idPrefix, ReSyncResourceType genericType,
                   ReSyncResourceType coreCreateType, boolean coreRollover,
                   ExistingGeneric existingGeneric, ExistingCore existingCore) {
        static Config from(Map<String, String> environment) {
            Map<String, String> values = environment == null ? Map.of() : environment;
            boolean enabled = truth(values, "RESYNC_CRUD_ACCEPTANCE_ENABLED");
            String mode = value(values, "RESYNC_CRUD_ACCEPTANCE_MODE", "read-only");
            if (!"read-only".equalsIgnoreCase(mode) && !"mutating".equalsIgnoreCase(mode)) {
                throw new AcceptanceFailure("crud_mode_invalid");
            }
            boolean mutating = "mutating".equalsIgnoreCase(mode);
            String serverId = value(values, "RESYNC_SERVER_ID", "");
            String wsUrl = value(values, "RESYNC_WS_URL", "");
            String apiKey = value(values, "RESYNC_API_KEY", "");
            long timeoutMillis = boundedLong(values, "RESYNC_CRUD_TIMEOUT_SECONDS", 45L, 5L, 120L) * 1000L;
            String idPrefix = value(values, "RESYNC_CRUD_ID_PREFIX", "codex_acceptance");
            ReSyncResourceType generic = resourceType(values, "RESYNC_CRUD_GENERIC_TYPE");
            ReSyncResourceType coreCreate = resourceType(values, "RESYNC_CRUD_CORE_CREATE_TYPE");
            boolean coreRollover = truth(values, "RESYNC_CRUD_CORE_ROLLOVER");
            String existingGenericTypeValue = value(values, "RESYNC_CRUD_EXISTING_GENERIC_TYPE", "");
            String existingGenericId = value(values, "RESYNC_CRUD_EXISTING_GENERIC_ID", "");
            ExistingGeneric existingGeneric = null;
            if (!existingGenericTypeValue.isBlank() || !existingGenericId.isBlank()) {
                ReSyncResourceType type = ReSyncResourceType.byTypeId(existingGenericTypeValue);
                long revision = boundedLong(values, "RESYNC_CRUD_EXISTING_GENERIC_EXPECT_REVISION", 0L, 0L,
                    Long.MAX_VALUE);
                String hash = value(values, "RESYNC_CRUD_EXISTING_GENERIC_EXPECT_HASH", "");
                boolean delete = truth(values, "RESYNC_CRUD_EXISTING_GENERIC_DELETE");
                String confirmation = value(values, "RESYNC_CRUD_EXISTING_GENERIC_DELETE_CONFIRM", "");
                existingGeneric = new ExistingGeneric(type, existingGenericId, revision, hash, delete, confirmation);
            }
            String oldTypeValue = value(values, "RESYNC_CRUD_EXISTING_CORE_TYPE", "");
            String oldId = value(values, "RESYNC_CRUD_EXISTING_CORE_ID", "");
            ExistingCore old = null;
            if (!oldTypeValue.isBlank() || !oldId.isBlank()) {
                ReSyncResourceType oldType = ReSyncResourceType.byTypeId(oldTypeValue);
                long revision = boundedLong(values, "RESYNC_CRUD_EXISTING_CORE_EXPECT_REVISION", 0L, 0L,
                    Long.MAX_VALUE);
                String hash = value(values, "RESYNC_CRUD_EXISTING_CORE_EXPECT_HASH", "");
                boolean delete = truth(values, "RESYNC_CRUD_EXISTING_CORE_DELETE");
                String confirmation = value(values, "RESYNC_CRUD_EXISTING_CORE_DELETE_CONFIRM", "");
                old = new ExistingCore(oldType, oldId, revision, hash, delete, confirmation);
            }
            Config config = new Config(enabled, mutating, serverId, wsUrl, apiKey, timeoutMillis, idPrefix, generic,
                coreCreate, coreRollover, existingGeneric, old);
            config.validateMutationConfirmation(value(values, "RESYNC_CRUD_MUTATION_CONFIRM", ""));
            return config;
        }

        void validate() {
            if (!enabled) {
                throw new AcceptanceFailure("acceptance_not_enabled");
            }
            if (serverId.isBlank() || wsUrl.isBlank() || apiKey.isBlank()) {
                throw new AcceptanceFailure("connection_configuration_missing");
            }
            try {
                UUID.fromString(serverId);
            } catch (IllegalArgumentException exception) {
                throw new AcceptanceFailure("server_id_not_uuid");
            }
            if (idPrefix.isBlank() || idPrefix.length() > MAX_IDENTIFIER_LENGTH) {
                throw new AcceptanceFailure("id_prefix_invalid");
            }
            if (!mutating && (genericType != null || coreCreateType != null || coreRollover
                || existingGeneric != null && existingGeneric.delete()
                || existingCore != null && existingCore.delete())) {
                throw new AcceptanceFailure("mutation_requested_in_read_only_mode");
            }
            if (genericType != null && (!genericType.enabled() || genericType.isGraph()
                || genericType == ReSyncResourceType.PROJECT_METADATA
                || !genericJsonType(genericType))) {
                throw new AcceptanceFailure("generic_type_not_supported");
            }
            if (coreCreateType != null && (!coreCreateType.enabled() || !coreCreateType.isGraph())) {
                throw new AcceptanceFailure("core_create_type_not_supported");
            }
            if (existingGeneric != null) {
                if (existingGeneric.type() == null || existingGeneric.type().isGraph()
                    || existingGeneric.type() == ReSyncResourceType.PROJECT_METADATA
                    || !genericJsonType(existingGeneric.type()) || existingGeneric.id().isBlank()
                    || existingGeneric.id().length() > MAX_IDENTIFIER_LENGTH
                    || existingGeneric.expectedRevision() <= 0L
                    || !validHash(existingGeneric.expectedPayloadHash())) {
                    throw new AcceptanceFailure("existing_generic_requires_exact_identity");
                }
                if (existingGeneric.delete() && !deleteConfirmation(existingGeneric.type(), existingGeneric.id(),
                    existingGeneric.expectedRevision(), existingGeneric.expectedPayloadHash())
                    .equals(existingGeneric.deleteConfirmation())) {
                    throw new AcceptanceFailure("existing_generic_delete_confirmation_mismatch");
                }
            }
            if (existingCore != null) {
                if (existingCore.type() == null || !existingCore.type().isGraph()
                    || existingCore.id().isBlank() || existingCore.id().length() > MAX_IDENTIFIER_LENGTH) {
                    throw new AcceptanceFailure("existing_core_configuration_invalid");
                }
                if (existingCore.delete() && (existingCore.expectedRevision() <= 0L
                    || !validHash(existingCore.expectedAssetHash())
                    || existingCore.deleteConfirmation().isBlank())) {
                    throw new AcceptanceFailure("existing_core_delete_requires_exact_identity");
                }
            }
        }

        private void validateMutationConfirmation(String confirmation) {
            if (mutating && !MUTATION_CONFIRMATION.equals(confirmation)) {
                throw new AcceptanceFailure("mutation_confirmation_missing");
            }
        }

        private static boolean genericJsonType(ReSyncResourceType type) {
            return switch (type) {
                case CHAT, MOTD_PROFILE, MESSAGE_RULE, RECIPE_DEFINITION, TEXT_TEMPLATE, ADVANCEMENT_TREE, DIALOG,
                     TRADE_PROFILE, NPC_DEFINITION, LOOT_TABLE, VARIABLE_DEFINITION, TIMER_DEFINITION,
                     SCHEDULE_DEFINITION -> true;
                default -> false;
            };
        }

        private static ReSyncResourceType resourceType(Map<String, String> values, String name) {
            String type = value(values, name, "");
            if (type.isBlank()) {
                return null;
            }
            ReSyncResourceType result = ReSyncResourceType.byTypeId(type);
            if (result == null) {
                throw new AcceptanceFailure(name.toLowerCase(Locale.ROOT) + "_invalid");
            }
            return result;
        }

        private static boolean truth(Map<String, String> values, String name) {
            return "true".equalsIgnoreCase(value(values, name, "false"));
        }

        private static long boundedLong(Map<String, String> values, String name, long fallback, long minimum,
                                        long maximum) {
            String raw = value(values, name, Long.toString(fallback));
            try {
                long parsed = Long.parseLong(raw);
                if (parsed < minimum || parsed > maximum) {
                    throw new AcceptanceFailure(name.toLowerCase(Locale.ROOT) + "_out_of_range");
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new AcceptanceFailure(name.toLowerCase(Locale.ROOT) + "_invalid");
            }
        }

        private static String value(Map<String, String> values, String name, String fallback) {
            String value = values.get(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    static final class AcceptanceReport {
        private final Map<String, Object> observed = new LinkedHashMap<>();
        private final Map<String, Object> phases = new LinkedHashMap<>();
        private final String mode;
        private final String serverId;
        private String status = "failed";
        private String failure = "not_started";

        AcceptanceReport(Config config) {
            mode = config.mutating() ? "mutating" : "read-only";
            serverId = config.serverId();
        }

        void observe(String name, Object value) {
            observed.put(name, value);
        }

        void phase(String name, ReSyncResourceType type, String id, long revision, String hash, String outcome) {
            Map<String, Object> phase = new LinkedHashMap<>();
            phase.put("type", type.typeId());
            phase.put("id", id);
            phase.put("revision", revision);
            phase.put("hash", hash == null ? "" : hash);
            phase.put("outcome", outcome);
            phases.put(name, phase);
        }

        void pass() {
            status = "passed";
            failure = "";
        }

        void fail(String code) {
            status = "failed";
            failure = code == null || code.isBlank() ? "unknown_failure" : code;
        }

        boolean passed() {
            return "passed".equals(status);
        }

        String status() {
            return status;
        }

        Map<String, Object> toMap() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", REPORT_SCHEMA_VERSION);
            report.put("createdAt", Instant.now().toString());
            report.put("status", status);
            report.put("mode", mode);
            report.put("serverId", serverId);
            report.put("failure", failure);
            report.put("observed", new LinkedHashMap<>(observed));
            report.put("phases", new LinkedHashMap<>(phases));
            return report;
        }
    }

    static final class AcceptanceFailure extends RuntimeException {
        private final String code;

        AcceptanceFailure(String code) {
            super(code);
            this.code = code == null || code.isBlank() ? "acceptance_failure" : code;
        }

        String code() {
            return code;
        }
    }

    private static final class HeadlessProbe extends RemotelyClient {
        private FlowManager manager;

        private HeadlessProbe() {
            super(null);
            manager = new FlowManager(this, null);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void shutdownProbe() {
            if (manager != null) {
                manager.shutdown();
                manager = null;
            }
        }
    }
}
