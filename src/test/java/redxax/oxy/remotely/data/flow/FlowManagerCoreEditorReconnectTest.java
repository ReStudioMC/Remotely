package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerCoreEditorReconnectTest {
    private static final Path FLOW_MANAGER = Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java");
    private static final Path STUDIO_SCREEN = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java");

    @Test
    void coreProjectionRevalidationUsesTransportGenerationAfterReconnect() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String method = methodBody(source, "private void revalidateCoreGraphEditorSession(");

        assertTrue(method.contains("long connectionGeneration = serverConnectionGenerations.getOrDefault(serverId, 0L);"));
        assertTrue(method.contains("new ServerConnectionToken(serverId, source, connectionGeneration)"));
        assertFalse(method.contains("new ServerConnectionToken(resource.serverId().canonicalText(), source, generation)"));
    }

    @Test
    void staleCoreSessionsRequireCurrentBaselineBeforeLookupReturns() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String reconcile = methodBody(source, "private CoreGraphSessionState reconcileCoreGraphSession(");
        String lookup = methodBody(source, "public Optional<CoreGraphEditorSession> coreGraphEditorSession(");

        assertTrue(reconcile.contains("current.stale() || current.token() == null"));
        assertTrue(reconcile.contains("baseline == null || !baseline.canSave()"));
        assertTrue(reconcile.contains("candidate = new CoreGraphSessionState(session, token, false)"));
        assertTrue(lookup.contains("if (reconciled.stale())"));
        assertTrue(lookup.contains("return Optional.empty()"));
    }

    @Test
    void retainedStudioDocumentsRebindBeforeSelection() throws IOException {
        String source = Files.readString(STUDIO_SCREEN).replace("\r\n", "\n");
        String rebind = methodBody(source, "protected StudioDocument rebindCoreStudioDocument(");
        String select = methodBody(source, "protected void selectStudioDocument(");
        String openCore = methodBody(source, "protected void openStudioCoreDocument(");
        String backgroundOpen = methodBody(source, "public void bindWorkspaceCoreEditor(");

        assertTrue(rebind.contains("coreGraphActivation(manager, type, document.id())"));
        assertTrue(rebind.contains("manager.peekCoreGraphEditorSession(studioServerId(), type, document.id())"));
        assertTrue(rebind.contains("new StudioDocument(document.type(), document.id(), document.title(), null, session"));
        assertTrue(select.contains("StudioDocument selected = rebindCoreStudioDocument(document)"));
        assertTrue(rebind.contains("studioDocuments.set(index, rebound)"));
        assertTrue(rebind.contains("activeStudioDocument = rebound"));
        assertTrue(rebind.indexOf("studioDocuments.set(index, rebound)")
            < rebind.indexOf("activeStudioDocument = rebound"));
        assertTrue(select.contains("activateStudioDocument(selected)"));
        assertTrue(openCore.contains("bindStudioCoreDocument(type, id, title, session, true)"));
        assertTrue(backgroundOpen.contains("bindStudioCoreDocument(type, id, title, session, false)"));
        assertFalse(backgroundOpen.contains("selectStudioDocument("));
    }

    @Test
    void missingCoreProjectionLoadsAndRetriesRetainedSelection() throws IOException {
        String managerSource = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String hydrate = methodBody(managerSource, "private void hydrateCoreGraphProjection(");
        assertTrue(hydrate.contains("beginCoreGraphHydration(key, token, authorityEpoch)"));
        assertTrue(hydrate.contains("flowClient.requestResource(type, id, false)"));

        String screenSource = Files.readString(STUDIO_SCREEN).replace("\r\n", "\n");
        String update = methodBody(screenSource, "protected void updateStudioTabStates(");
        String retry = methodBody(screenSource, "private void retryPendingCoreStudioDocument(");
        String select = methodBody(screenSource, "protected void selectStudioDocument(");
        assertTrue(update.contains("retryPendingCoreStudioDocument()"));
        assertTrue(retry.contains("selectStudioDocument(key)"));
        assertTrue(select.contains("pendingCoreStudioDocumentKey = key"));
    }

    @Test
    void sessionlessCoreRebindUsesTheOwnedRequestCadenceAndFailsClosedAtTheDeadline() throws IOException {
        String source = Files.readString(STUDIO_SCREEN).replace("\r\n", "\n");
        String retry = methodBody(source, "private void retryPendingCoreStudioDocument(");
        String drain = methodBody(source, "void drainCoreOpenIntents(");
        String fail = methodBody(source, "private boolean failCoreOpenIntent(");
        String backgroundOpen = methodBody(source, "public void bindWorkspaceCoreEditor(");
        String request = methodBody(source, "private void requestStudioResource(");
        String select = methodBody(source, "protected void selectStudioDocument(");
        int timeoutStart = retry.indexOf("now - pendingCoreStudioDocumentAt >= CORE_STUDIO_REBIND_TIMEOUT_MILLIS");
        int sessionlessStart = retry.indexOf("if (document.coreSession() == null)");
        int sessionlessEnd = retry.indexOf("if (rebindCoreStudioDocument(document) != null)");
        String sessionless = retry.substring(sessionlessStart, sessionlessEnd);

        assertTrue(source.contains("CORE_STUDIO_REBIND_TIMEOUT_MILLIS = 15_000L"));
        assertTrue(source.contains("CORE_OPEN_RETRY_MILLIS = 1_000L"));
        assertTrue(timeoutStart >= 0 && timeoutStart < sessionlessStart);
        assertTrue(retry.contains("terminalCoreStudioRebinds.put(key, \"resource_unavailable\")"));
        assertTrue(retry.contains("failCoreOpenIntent(intent, \"resource_unavailable\")"));
        assertTrue(fail.contains("pendingCoreOpenIntents.remove(intent.key(), intent)"));
        assertTrue(retry.contains("restorePendingCoreStudioSelection(previousKey)"));
        assertTrue(sessionless.contains("acceptCoreOpenIntent(type, document.id(), document.title())"));
        assertFalse(sessionless.contains("requestStudioResource(manager, type, document.id())"));
        assertTrue(drain.contains("now - intent.lastRequestAt() < CORE_OPEN_RETRY_MILLIS"));
        assertTrue(drain.indexOf("pendingCoreOpenIntents.put(intent.key(), intent.requested(now))")
            < drain.indexOf("requestCoreOpenResource(manager, intent.type(), intent.id())"));
        assertTrue(request.contains("type.isGraph() && (pendingCoreOpenIntents.containsKey(coreKey)"));
        assertTrue(request.contains("|| terminalCoreStudioRebinds.containsKey(coreKey)"));
        assertTrue(request.indexOf("terminalCoreStudioRebinds.containsKey(coreKey)")
            < request.lastIndexOf("requestStudioResource(manager, type, id)"));
        assertTrue(backgroundOpen.contains("bindStudioCoreDocument(type, id, title, session, false)"));
        assertFalse(backgroundOpen.contains("selectStudioDocument("));
        assertTrue(select.contains("terminalCoreStudioRebinds.remove(key)"));
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
                return source.substring(open, index + 1);
            }
        }
        throw new IllegalStateException(signature);
    }
}
