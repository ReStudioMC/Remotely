package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CreationLifecycleContractTest {
    private static final Path FLOW_MANAGER = Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java");
    private static final Path RESOURCE_CREATOR = Path.of(
        "src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncResourceCreator.java");

    @Test
    void graphExistenceAndCoreCreateAdmissionDoNotHydrateMissingResources() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String existence = methodBody(source, "private boolean creationResourceExists(");
        String admission = methodBody(source, "private CreationAdmission beginCoreGraphCreation(");
        assertFalse(existence.contains("hydrate"));
        assertFalse(existence.contains("isCoreGraphAuthoritative("));
        assertTrue(existence.indexOf("coreGraphUiProjection.authoritative")
            < existence.indexOf("authoritativeTypedMembershipContains"));
        assertTrue(existence.contains("return coreGraphUiProjection.contains(serverId, type, id);"));
        assertTrue(admission.contains("creationResourceExists(serverId, resourceType, id)"));
        assertFalse(admission.contains("isCoreGraphAuthoritative("));
        assertFalse(admission.contains("getAuthoritativeProjectResource("));
    }

    @Test
    void metadataCompletionRequiresAuthoritativeRediscovery() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String finish = methodBody(source, "private void finishCreationMetadata(");
        int localState = finish.indexOf("if (!currentAtFinish)");
        int authority = finish.indexOf("if (!creationMetadataAlreadyAuthoritative(transaction))");
        int completion = finish.indexOf("completeCreation(transaction)");
        assertTrue(localState >= 0 && authority > localState && completion > authority);
        assertFalse(finish.substring(localState, authority).contains("completeCreation(transaction)"));
    }

    @Test
    void creationMetadataDoesNotRefreshBeforeDurableCompletion() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String dispatch = methodBody(source, "private void dispatchCreationMetadata(");
        String finishPayload = methodBody(source, "private void finishCreationPayload(");
        String acceptPayload = methodBody(source, "private void acceptCreationPayload(");
        String completion = methodBody(source, "private void completeCreation(");
        assertTrue(dispatch.contains("transaction.resourceType != null"));
        assertTrue(dispatch.contains("saveProjectMetadata(edit, false, ticket);"));
        assertTrue(finishPayload.contains("acceptCreationPayload(transaction, currentAtFinish);"));
        assertTrue(acceptPayload.contains("CreationPhase.SETTLEMENT"));
        assertFalse(acceptPayload.contains("CreationPhase.METADATA"));
        assertTrue(completion.contains("tickets.forEach(DesignerSaveNotifications::detachResumable);"));
        int removal = completion.indexOf("removed = creationTransactions.remove");
        int refresh = completion.indexOf("refreshStudioWorkspace(transaction.key.serverId(), true);");
        assertTrue(removal >= 0 && refresh > removal);
        assertFalse(completion.contains("List.of(transaction.payloadTicket"));
        assertTrue(completion.contains("discardCoreDraft = transaction.corePayload && !transaction.newerLocalState;"));
        assertTrue(completion.contains("refreshFlowWorkspace(transaction.key.serverId(), transaction.key.id(), true);"));
        assertFalse(completion.contains("notifyObserver = !transaction.newerLocalState"));
        assertTrue(completion.contains("authoritativeCreationResource(transaction)"));
        assertTrue(completion.indexOf("if (observer != null)") > completion.indexOf("if (removed)"));
    }

    @Test
    void coreCreationDoesNotTreatUncommittedLocalGraphAsAuthoritative() throws IOException {
        String manager = Files.readString(FLOW_MANAGER);
        String creator = Files.readString(RESOURCE_CREATOR);
        String owner = methodBody(creator, "private static String graphIdOwner(");
        String exists = methodBody(creator,
            "public static boolean exists(FlowManager manager, String serverId, String type, String id, String folder)");
        assertTrue(owner.contains("manager.hasAuthoritativeResource(serverId, resourceType, id)"));
        assertTrue(exists.contains("manager.hasAuthoritativeResource(serverId, resourceType, id)"));
        assertTrue(manager.contains("transaction.corePayload = true;"));
        assertTrue(manager.contains("admitCreationTransaction(transaction)"));
        assertTrue(manager.contains("enqueueCreationJournal(transaction"));
        assertTrue(manager.contains("rejectCreationAdmission(transaction, \"Creation Journal Unavailable\")"));
        assertFalse(manager.contains("persistCoreTemplateCreation("));
    }

    @Test
    void successfulCreationClosesPopupBeforeExternalCallback() throws IOException {
        String source = Files.readString(RESOURCE_CREATOR);
        String popup = methodBody(source, "public static void showCreatePopup(");
        int hide = popup.indexOf("popupRef[0].hide();");
        int callback = popup.indexOf("onCreated.accept(result);");

        assertTrue(hide >= 0 && callback > hide);
    }

    @Test
    void duplicatePrecheckUsesOnlyAuthoritativeTypedState() throws IOException {
        String source = Files.readString(RESOURCE_CREATOR);
        String exists = methodBody(source,
            "public static boolean exists(FlowManager manager, String serverId, String type, String id, String folder)");

        assertFalse(exists.contains("getProjectResource("));
        assertFalse(exists.contains("getFromDraft("));
        assertFalse(exists.contains("containsKey("));
        assertTrue(exists.contains("manager.hasAuthoritativeResource(serverId, resourceType, id)"));
    }

    @Test
    void reconnectRetirementCannotClearAReplacementProjection() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String cleanup = methodBody(source, "private boolean ownsRetiredProjectionCleanup(");
        String transition = methodBody(source, "private void onCoreGraphResourceTransition(");
        assertTrue(cleanup.contains("retiringServerConnections.contains(serverId)"));
        assertTrue(cleanup.contains("serverConnectionGenerations.getOrDefault(serverId, 0L) != retirementGeneration"));
        assertTrue(cleanup.contains("serverGenerationSources.get(serverId) != retiringSource"));
        assertTrue(cleanup.contains("current == null || current == retiringSource"));
        assertTrue(transition.contains("resumeCreationTransactions(serverId);"));
        assertTrue(transition.contains("if (!hasPendingCreationForServer(serverId))"));
    }

    @Test
    void resumedCreationReplacesLostRuntimeTicketsWithoutChangingTypedIdentity() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String dispatch = methodBody(source, "private void dispatchCreationPayload(");
        String reset = methodBody(source, "private void resetCreationTransactionsForReconnect(");
        String resume = methodBody(source, "private void resumeCreationTransactions(");
        assertTrue(dispatch.contains("!DesignerSaveNotifications.isPending(transaction.payloadTicket)"));
        assertTrue(dispatch.contains("transaction.payloadRequestId"));
        assertTrue(dispatch.contains("transaction.payloadMutationId"));
        assertTrue(reset.contains("transaction.payloadTicket = null;"));
        assertTrue(reset.contains("reset.tickets().forEach(DesignerSaveNotifications::detachResumable);"));
        assertFalse(resume.contains("transaction.attempts = 0;"));
        assertFalse(resume.contains("transaction.journalAttempts = 0;"));
        assertFalse(resume.contains("transaction.metadataHydrationAttempts = 0;"));
    }

    @Test
    void aggregateCreationNeverPublishesAProvisionalAuthoritativeDraft() throws IOException {
        String manager = Files.readString(FLOW_MANAGER);
        String creator = Files.readString(RESOURCE_CREATOR);
        String prepare = methodBody(manager, "private void prepareCreationTransaction(");
        String save = methodBody(manager, "private void saveCreationPayload(");

        assertFalse(manager.contains("publishCreationDraft"));
        assertFalse(manager.contains("restoreCreationDraft"));
        assertFalse(prepare.contains("putInDraft"));
        assertTrue(save.contains("sendPreparedResourceCreate"));
        assertTrue(save.contains("sendCoreGraphCreate"));
        assertTrue(creator.contains("manager.hasAuthoritativeResource(serverId, resourceType, id)"));
    }

    @Test
    void coreCreationJournalRestoresTypedLocatorIdentityAndShutsDownSynchronously() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String restore = methodBody(source, "private CreationTransaction restoreCreationTransaction(");
        String shutdown = methodBody(source, "private RuntimeException suspendCreationTransactions(");

        assertTrue(restore.contains("!locator.equals(locatorOf(resource))"));
        assertFalse(restore.contains("!entry.locator().equals(locatorOf(resource))"));
        assertTrue(shutdown.contains("boolean persisted = persistCreationJournalNow();"));
        assertFalse(shutdown.contains("execute(this::persistCreationJournalNow)"));
    }

    @Test
    void coreTemplateRolloverDefersBeforeTerminalRejection() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String accept = methodBody(source,
            "public CoreGraphDocumentAuthoringAdapter.DraftResult acceptCoreGraphTemplate(");
        String rollover = methodBody(source, "private boolean deferCoreTemplateRollover(");
        String request = methodBody(source,
            "private CoreGraphDocumentAuthoringAdapter.RequestResult requestCoreGraphCreation(");

        int deferred = accept.indexOf("deferCoreTemplateRollover");
        int terminal = accept.indexOf("new Notification(\"Create\", \"Core Template Rejected\"");
        int preparing = request.indexOf("preparingCoreTemplateIntents.putIfAbsent");
        int dispatch = request.indexOf("requestCoreGraphTemplate(resource, publication)");
        assertTrue(accept.contains("coreGraphDocumentAuthoring.pending(requestId)"));
        assertTrue(deferred >= 0 && terminal > deferred);
        assertTrue(accept.contains("matchesCoreTemplateResponse(pendingRequest, response)"));
        assertTrue(rollover.contains("response != null && !responseIdentityValid"));
        assertTrue(rollover.contains("response != null && !isCoreTemplatePublicationTransition"));
        assertTrue(rollover.contains("deferCoreTemplateIntent(intent, true)"));
        assertTrue(rollover.contains("pendingCoreTemplateIntents.remove(requestId, intent)"));
        assertTrue(source.contains("preparingCoreTemplateIntents"));
        assertTrue(preparing >= 0 && dispatch > preparing);
    }

    @Test
    void acceptedCoreTemplateRolloverRequeuesAfterEditorFenceChanges() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String complete = methodBody(source, "private void completeCoreTemplateIntent(");

        int response = complete.indexOf("AuthoringTemplateResponse response = coreTemplateResponses.get(requestId);");
        int session = complete.indexOf("coreGraphDocumentAuthoring.editorSession(requestId)");
        int rollover = complete.indexOf("deferCoreTemplateRollover");
        int settled = complete.lastIndexOf("pendingCoreTemplateIntents.remove(requestId, intent);");
        assertTrue(response >= 0 && session > response && rollover > session && settled > rollover);
        assertTrue(complete.contains("if (session.isEmpty())"));
        assertTrue(complete.contains("AUTHORING_CATALOG_UNAVAILABLE"));
    }

    @Test
    void coreCreationExposesItsSessionOnlyAfterAuthoritativeSettlement() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String begin = methodBody(source, "private CreationAdmission beginCoreGraphCreation(");
        String ensure = methodBody(source, "private CoreGraphEditorSession ensureCoreCreationSession(");
        String complete = methodBody(source, "private void completeCreation(");
        String bind = methodBody(source, "private void bindCompletedCoreCreationSession(");

        assertFalse(begin.contains("bindCoreGraphSession"));
        assertTrue(ensure.contains("registerCoreGraphSession(transaction.locator, session, \"create\")"));
        int removal = complete.indexOf("removed = creationTransactions.remove");
        int exposure = complete.indexOf("bindCompletedCoreCreationSession(transaction)");
        int observer = complete.indexOf("observer.accept(result)");
        assertTrue(removal >= 0 && exposure > removal && observer > exposure);
        assertTrue(bind.contains("coreGraphEditorSession(transaction.key.serverId(), transaction.resourceType"));
        assertTrue(bind.contains("bindCoreGraphSession(transaction.locator, session, transaction.metadata.name())"));
    }

    @Test
    void duplicateCoreGraphsUseServerDuplicateInsteadOfRejectingAuthority() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String duplicate = methodBody(source, "public boolean duplicateResource(");
        String helper = methodBody(source, "private boolean duplicateCoreGraphResource(");
        int authority = duplicate.indexOf("coreGraphUiProjection.authoritative(serverId, graphType, sourceId)");
        int reject = duplicate.indexOf("yield false;", authority);
        int protocol = duplicate.indexOf("duplicateCoreGraphResource(serverId, graphType, sourceId, targetId)");
        assertTrue(authority >= 0 && protocol > authority);
        assertTrue(reject < 0 || protocol < reject);
        assertTrue(helper.contains("sendCoreGraphDuplicate(type, sourceId, targetId)"));
    }

    @Test
    void coreTemplateRolloverRetryIsBoundedAndDeduplicatedByResource() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String defer = methodBody(source, "private boolean deferCoreTemplateIntent(");
        String intent = methodBody(source, "private record CoreTemplateIntent(");
        String requestReason = methodBody(source, "private static boolean isRetryableCoreTemplateRequestReason(");
        String resultReason = methodBody(source, "private static boolean isRetryableCoreTemplateResult(");

        assertTrue(source.contains("MAX_CORE_TEMPLATE_ROLLOVER_RETRIES = 3"));
        assertTrue(defer.contains("deferredCoreTemplateIntents.get(candidate.resource())"));
        assertTrue(defer.contains("existing.rolloverAttempts() < candidate.rolloverAttempts()"));
        assertTrue(intent.contains("if (rolloverAttempts >= MAX_CORE_TEMPLATE_ROLLOVER_RETRIES)"));
        assertTrue(intent.contains("return null;"));
        assertFalse(requestReason.contains("TEMPLATE_TRANSPORT_UNAVAILABLE"));
        assertFalse(resultReason.contains("TEMPLATE_CHECKSUM_MISMATCH"));
        assertTrue(resultReason.contains("response == null"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open, index + 1).replace("\r\n", "\n");
            }
        }
        throw new IllegalStateException(signature);
    }
}
