package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowManagerCommandGraphContractTest {
    private static final Path FLOW_MANAGER = Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java");

    @Test
    void commandStartSerializationUsesTheSharedContract() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String ensureStart = section(source, "private boolean ensureCommandStartNodeInMemory", "private FlowNode commandStartNode");
        String findStart = section(source, "private FlowNode commandStartNode", "private String normalizedCommandLabel");
        String applyContext = section(source, "private boolean applyCommandContext", "private void sendTriggerUpdate");

        assertTrue(source.contains("import restudio.resync.flow.command.CommandGraphContract;"));
        assertTrue(ensureStart.contains("CommandGraphContract.isAnyStart(node.getType())"));
        assertTrue(ensureStart.contains("CommandGraphContract.isLegacyStart(node.getType())"));
        assertTrue(ensureStart.contains("node.setType(\"event.command\")"));
        assertTrue(ensureStart.contains("new FlowNode(\"event.command\""));
        assertTrue(findStart.contains("CommandGraphContract.isAnyStart(node.getType())"));
        assertTrue(applyContext.contains("CommandGraphContract.isAnyStart(node.getType())"));
        assertFalse(ensureStart.contains("new FlowNode(\"event.resync.command\""));
    }

    @Test
    void coreCommandAuthorityIsFencedBeforeLegacyBindingMutation() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String binding = section(source, "public CompletableFuture<Boolean> setCommandBindingAwait", "private List<TriggerBinding> copyTriggerBindings");

        int hydration = binding.indexOf("hydrateCoreGraphProjection(serverId, ReSyncResourceType.COMMAND, flowId)");
        int coreAuthority = binding.indexOf("coreGraphAuthorityEnabled(serverId)");
        int authority = binding.indexOf("coreGraphUiProjection.authoritative(serverId, ReSyncResourceType.COMMAND, flowId)");
        int triggerMutation = binding.indexOf("synchronized (triggerBindingsLock)");
        int legacySave = binding.indexOf("saveGraph(serverId, ReSyncResourceType.COMMAND, graph, graphTicket)");

        assertTrue(hydration >= 0);
        assertTrue(coreAuthority > hydration);
        assertTrue(authority > coreAuthority);
        assertTrue(triggerMutation > authority);
        assertTrue(legacySave > triggerMutation);
    }

    private static String section(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0);
        assertTrue(endIndex > startIndex);
        return source.substring(startIndex, endIndex);
    }
}
