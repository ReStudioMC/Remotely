package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncLiveCrudAcceptanceMainTest {
    @Test
    void liveHarnessConnectsTheInstalledOwnerThroughTheManagerSeam() throws Exception {
        String source = Files.readString(Path.of("src/test/java/redxax/oxy/remotely/data/flow/"
            + "ReSyncLiveCrudAcceptanceMain.java"));

        assertTrue(source.contains("FlowManagerTestConnection.installDirectCurrent(manager, config.serverId(), client,"));
        assertTrue(source.contains("FlowManagerTestConnection.connectCurrent(manager, config.serverId()) != client"));
        assertFalse(source.contains("manager.ensureFlowClient(config.serverId()) != client"));
        assertFalse(source.contains("client.connect()"));
    }

    @Test
    void genericSaveUsesTheProductionDraftLeaseOwner() throws Exception {
        String source = Files.readString(Path.of("src/test/java/redxax/oxy/remotely/data/flow/"
            + "ReSyncLiveCrudAcceptanceMain.java"));

        assertTrue(source.contains("manager.saveJsonResource(config.serverId(), type, updated, saveTicket)"));
        assertFalse(source.contains("client.sendPreparedResourceSave(type, id, savePayload.json()"));
        assertFalse(source.contains("generic_save_not_admitted"));
    }

    @Test
    void defaultsToReadOnlyAndDoesNotEnableAnyMutation() {
        ReSyncLiveCrudAcceptanceMain.Config config = ReSyncLiveCrudAcceptanceMain.Config.from(Map.of());

        assertFalse(config.enabled());
        assertFalse(config.mutating());
        assertEquals(null, config.genericType());
        assertEquals(null, config.coreCreateType());
        assertFalse(config.coreRollover());
        assertEquals(null, config.existingGeneric());
        assertEquals(null, config.existingCore());
    }

    @Test
    void coreRolloverExerciseRequiresMutatingModeAndConfirmation() {
        Map<String, String> environment = baseEnvironment();
        environment.put("RESYNC_CRUD_CORE_ROLLOVER", "true");
        ReSyncLiveCrudAcceptanceMain.Config readOnly = ReSyncLiveCrudAcceptanceMain.Config.from(environment);

        ReSyncLiveCrudAcceptanceMain.AcceptanceFailure modeFailure = assertThrows(
            ReSyncLiveCrudAcceptanceMain.AcceptanceFailure.class, readOnly::validate);
        assertEquals("mutation_requested_in_read_only_mode", modeFailure.code());

        environment.put("RESYNC_CRUD_ACCEPTANCE_MODE", "mutating");
        ReSyncLiveCrudAcceptanceMain.AcceptanceFailure confirmationFailure = assertThrows(
            ReSyncLiveCrudAcceptanceMain.AcceptanceFailure.class,
            () -> ReSyncLiveCrudAcceptanceMain.Config.from(environment));
        assertEquals("mutation_confirmation_missing", confirmationFailure.code());

        environment.put("RESYNC_CRUD_MUTATION_CONFIRM", ReSyncLiveCrudAcceptanceMain.MUTATION_CONFIRMATION);
        ReSyncLiveCrudAcceptanceMain.Config enabled = ReSyncLiveCrudAcceptanceMain.Config.from(environment);
        enabled.validate();
        assertTrue(enabled.coreRollover());
    }

    @Test
    void mutatingModeRequiresTheExactGlobalConfirmation() {
        Map<String, String> environment = baseEnvironment();
        environment.put("RESYNC_CRUD_ACCEPTANCE_MODE", "mutating");
        environment.put("RESYNC_CRUD_GENERIC_TYPE", "text_template");

        ReSyncLiveCrudAcceptanceMain.AcceptanceFailure failure = assertThrows(
            ReSyncLiveCrudAcceptanceMain.AcceptanceFailure.class,
            () -> ReSyncLiveCrudAcceptanceMain.Config.from(environment));

        assertEquals("mutation_confirmation_missing", failure.code());
        environment.put("RESYNC_CRUD_MUTATION_CONFIRM", ReSyncLiveCrudAcceptanceMain.MUTATION_CONFIRMATION);
        ReSyncLiveCrudAcceptanceMain.Config config = ReSyncLiveCrudAcceptanceMain.Config.from(environment);
        config.validate();
        assertTrue(config.mutating());
        assertEquals(ReSyncResourceType.TEXT_TEMPLATE, config.genericType());
    }

    @Test
    void unknownModeIsRejectedInsteadOfFallingBackToReadOnly() {
        Map<String, String> environment = baseEnvironment();
        environment.put("RESYNC_CRUD_ACCEPTANCE_MODE", "dry-run");

        ReSyncLiveCrudAcceptanceMain.AcceptanceFailure failure = assertThrows(
            ReSyncLiveCrudAcceptanceMain.AcceptanceFailure.class,
            () -> ReSyncLiveCrudAcceptanceMain.Config.from(environment));

        assertEquals("crud_mode_invalid", failure.code());
    }

    @Test
    void existingCoreDeleteRequiresExactRevisionHashAndConfirmation() {
        Map<String, String> environment = baseEnvironment();
        environment.put("RESYNC_CRUD_ACCEPTANCE_MODE", "mutating");
        environment.put("RESYNC_CRUD_MUTATION_CONFIRM", ReSyncLiveCrudAcceptanceMain.MUTATION_CONFIRMATION);
        environment.put("RESYNC_CRUD_EXISTING_CORE_TYPE", "command");
        environment.put("RESYNC_CRUD_EXISTING_CORE_ID", "old-command");
        environment.put("RESYNC_CRUD_EXISTING_CORE_DELETE", "true");

        ReSyncLiveCrudAcceptanceMain.Config incomplete = ReSyncLiveCrudAcceptanceMain.Config.from(environment);
        ReSyncLiveCrudAcceptanceMain.AcceptanceFailure failure = assertThrows(
            ReSyncLiveCrudAcceptanceMain.AcceptanceFailure.class, incomplete::validate);
        assertEquals("existing_core_delete_requires_exact_identity", failure.code());

        String hash = "a".repeat(64);
        environment.put("RESYNC_CRUD_EXISTING_CORE_EXPECT_REVISION", "17");
        environment.put("RESYNC_CRUD_EXISTING_CORE_EXPECT_HASH", hash);
        environment.put("RESYNC_CRUD_EXISTING_CORE_DELETE_CONFIRM",
            ReSyncLiveCrudAcceptanceMain.deleteConfirmation(ReSyncResourceType.COMMAND, "old-command", 17, hash));
        ReSyncLiveCrudAcceptanceMain.Config complete = ReSyncLiveCrudAcceptanceMain.Config.from(environment);
        complete.validate();
        assertTrue(complete.existingCore().delete());
        assertEquals(17, complete.existingCore().expectedRevision());
    }

    @Test
    void existingGenericDeleteRequiresExactIdentityAndConfirmation() {
        Map<String, String> environment = baseEnvironment();
        environment.put("RESYNC_CRUD_ACCEPTANCE_MODE", "mutating");
        environment.put("RESYNC_CRUD_MUTATION_CONFIRM", ReSyncLiveCrudAcceptanceMain.MUTATION_CONFIRMATION);
        environment.put("RESYNC_CRUD_EXISTING_GENERIC_TYPE", "text_template");
        environment.put("RESYNC_CRUD_EXISTING_GENERIC_ID", "disposable-template");
        environment.put("RESYNC_CRUD_EXISTING_GENERIC_DELETE", "true");

        ReSyncLiveCrudAcceptanceMain.Config missingIdentity = ReSyncLiveCrudAcceptanceMain.Config.from(environment);
        ReSyncLiveCrudAcceptanceMain.AcceptanceFailure identityFailure = assertThrows(
            ReSyncLiveCrudAcceptanceMain.AcceptanceFailure.class, missingIdentity::validate);
        assertEquals("existing_generic_requires_exact_identity", identityFailure.code());

        String hash = "b".repeat(64);
        environment.put("RESYNC_CRUD_EXISTING_GENERIC_EXPECT_REVISION", "1");
        environment.put("RESYNC_CRUD_EXISTING_GENERIC_EXPECT_HASH", hash);
        environment.put("RESYNC_CRUD_EXISTING_GENERIC_DELETE_CONFIRM", "DELETE:text_template:wrong:1:" + hash);
        ReSyncLiveCrudAcceptanceMain.Config wrongConfirmation =
            ReSyncLiveCrudAcceptanceMain.Config.from(environment);
        ReSyncLiveCrudAcceptanceMain.AcceptanceFailure confirmationFailure = assertThrows(
            ReSyncLiveCrudAcceptanceMain.AcceptanceFailure.class, wrongConfirmation::validate);
        assertEquals("existing_generic_delete_confirmation_mismatch", confirmationFailure.code());

        environment.put("RESYNC_CRUD_EXISTING_GENERIC_DELETE_CONFIRM",
            ReSyncLiveCrudAcceptanceMain.deleteConfirmation(ReSyncResourceType.TEXT_TEMPLATE,
                "disposable-template", 1, hash));
        ReSyncLiveCrudAcceptanceMain.Config complete = ReSyncLiveCrudAcceptanceMain.Config.from(environment);
        complete.validate();
        assertTrue(complete.existingGeneric().delete());
        assertEquals(1, complete.existingGeneric().expectedRevision());
    }

    @Test
    void existingGenericCleanupProvesIdentityBeforeDeleteAndListAbsence() throws Exception {
        String source = Files.readString(Path.of("src/test/java/redxax/oxy/remotely/data/flow/"
            + "ReSyncLiveCrudAcceptanceMain.java"));
        int identity = source.indexOf("state.revision() != existing.expectedRevision()");
        int deletion = source.indexOf("client.sendSettledResourceDelete(existing.type(), existing.id())");

        assertTrue(identity >= 0);
        assertTrue(deletion > identity);
        assertTrue(source.contains("existing_generic_identity_mismatch"));
        assertTrue(source.contains("existing_generic_delete_list"));
        assertTrue(source.contains("!manager.getJsonResourcesForServer(config.serverId(), existing.type())"
            + ".containsKey(existing.id())"));
    }

    @Test
    void collisionFreeIdsAreBoundedSafeAndUnique() {
        String first = ReSyncLiveCrudAcceptanceMain.uniqueId(" Codex Live! ", "Flow");
        String second = ReSyncLiveCrudAcceptanceMain.uniqueId(" Codex Live! ", "Flow");

        assertTrue(first.matches("[a-z0-9_-]+"));
        assertTrue(first.length() <= 96);
        assertNotEquals(first, second);
    }

    @Test
    void reportNeverIncludesConnectionOrPayloadConfiguration() {
        Map<String, String> environment = baseEnvironment();
        ReSyncLiveCrudAcceptanceMain.Config config = ReSyncLiveCrudAcceptanceMain.Config.from(environment);
        ReSyncLiveCrudAcceptanceMain.AcceptanceReport report =
            new ReSyncLiveCrudAcceptanceMain.AcceptanceReport(config);
        report.pass();
        String serialized = report.toMap().toString();

        assertFalse(serialized.contains(environment.get("RESYNC_API_KEY")));
        assertFalse(serialized.contains(environment.get("RESYNC_WS_URL")));
        assertFalse(serialized.toLowerCase().contains("payload"));
    }

    private static Map<String, String> baseEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("RESYNC_CRUD_ACCEPTANCE_ENABLED", "true");
        environment.put("RESYNC_SERVER_ID", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        environment.put("RESYNC_WS_URL", "ws://127.0.0.1:12441");
        environment.put("RESYNC_API_KEY", "test-secret");
        return environment;
    }
}
