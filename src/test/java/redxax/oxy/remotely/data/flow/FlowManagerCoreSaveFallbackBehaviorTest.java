package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowManagerCoreSaveFallbackBehaviorTest {
    @Test
    void missingCoreSessionFailsWithoutSendingALegacyGraphSave() throws Exception {
        String serverId = "missing-core-session";
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, transport, null);
        FlowManager manager = new FlowManager(null, null);
        FlowManagerTestConnection.install(manager, serverId, client, transport);
        try {
            FlowGraph graph = new FlowGraph();
            graph.setId("core-flow");
            graph.setResourceType(ReSyncResourceType.FLOW.typeId());
            DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId,
                ReSyncResourceType.FLOW, graph.getId(), graph.getId());
            AtomicReference<Boolean> result = new AtomicReference<>();
            ticket.whenFinished((saved, ignored) -> result.set(saved));

            manager.saveGraph(serverId, ReSyncResourceType.FLOW, graph, ticket);

            assertFalse(DesignerSaveNotifications.isPending(ticket));
            assertFalse(Boolean.TRUE.equals(result.get()));
            assertTrue(transport.sentFrames().stream().noneMatch(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID && frame.payload().length > 0
                && frame.payload()[0] == ReSyncResourceType.FLOW.saveByte()));
        } finally {
            manager.shutdown();
        }
    }

}
