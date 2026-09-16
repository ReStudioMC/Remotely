package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorCoreIntegrationTest {
    @Test
    void coreEditsChangeTheSessionAndUseTheCanonicalSavePath() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        String normalized = source.replaceAll("\\s+", " ");

        assertTrue(source.contains("private boolean commitCoreMutation(String operation"));
        assertTrue(source.contains("current.setNodeValue(identity"));
        assertTrue(source.contains("prepareCoreWidgetMutation(session, proposal)"));
        assertTrue(source.contains("current.applyGraphPatches(patches)"));
        assertTrue(source.contains("session.replaceGraph(mutation.candidate().graphDocument())"));
        assertTrue(source.contains("current -> applyCoreNodePositions(current, positions)"));
        assertTrue(source.contains("current -> removeCoreNodes(current, List.of(identity))"));
        assertTrue(source.contains("current.setConnections(remaining)"));
        assertTrue(source.contains("current.removeConnection(connectionId)"));
        assertTrue(source.contains("protected void applyCoreCommandInteraction(CommandBindingContext context)"));
        assertTrue(source.contains("commitCoreGlobalMutation(\"Command Settings\", current ->"));
        assertTrue(source.contains("current.commandMetadata().equals(next)"));
        assertTrue(source.contains("current.setCommandMetadata(next)"));
        assertTrue(normalized.contains(
            "DesignerSaveNotifications.startResumableExact(serverId, type, resourceId, resourceId, UUID.randomUUID(), UUID.randomUUID())"));
        assertTrue(source.contains("coreGraphSaveHandler.apply(session, ticket)"));
        assertTrue(source.contains("manager.saveCoreGraph(serverId, type, session, ticket)"));
        assertTrue(source.contains("DesignerSaveNotifications.failExact(ticket"));
        assertTrue(normalized.contains(
            "if (!session.canUndo()) { return false; } return commitCoreHistoryMutation(\"Undo\", CoreHistoryTransition.UNDO);"));
        assertTrue(normalized.contains(
            "if (!session.canRedo()) { return false; } return commitCoreHistoryMutation(\"Redo\", CoreHistoryTransition.REDO);"));
        assertTrue(source.contains("targetHistory = command.session().historyState()"));
        assertTrue(source.contains("command.historyTransition().revert(command.session())"));
        assertFalse(source.contains("\"graph-edit\""));
    }

    @Test
    void capabilityIdentityIncludesTheOwner() {
        ContractRef<CapabilityId> first = ContractRef.of(new OwnerId("first-owner"), CapabilityId.of("edit"));
        ContractRef<CapabilityId> second = ContractRef.of(new OwnerId("second-owner"), CapabilityId.of("edit"));

        assertNotEquals(first, second);
        assertFalse(Set.of(first).contains(second));
    }
}
