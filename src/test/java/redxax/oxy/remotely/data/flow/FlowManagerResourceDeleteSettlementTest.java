package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowManagerResourceDeleteSettlementTest {
    @Test
    void exactAckWaitsForAuthoritativeAbsence() {
        FlowManager.ResourceDeleteSettlement settlement = new FlowManager.ResourceDeleteSettlement("flow", "alpha");

        assertNull(settlement.acknowledge());
        assertTrue(settlement.acknowledged());
        assertFalse(settlement.absent());

        FlowManager.ResourceDeleteResult result = settlement.observeAbsence();
        assertTrue(result.deleted());
        assertTrue(result.message().isEmpty());
    }

    @Test
    void authoritativeAbsenceWaitsForExactAck() {
        FlowManager.ResourceDeleteSettlement settlement = new FlowManager.ResourceDeleteSettlement("gui", "menu");

        assertNull(settlement.observeAbsence());
        assertFalse(settlement.acknowledged());
        assertTrue(settlement.absent());

        assertTrue(settlement.acknowledge().deleted());
    }

    @Test
    void offlineFailurePreservesADeleteFailureAgainstLateSignals() {
        FlowManager.ResourceDeleteSettlement settlement = new FlowManager.ResourceDeleteSettlement("tab", "players");

        FlowManager.ResourceDeleteResult result = settlement.fail("ReSync Offline");

        assertFalse(result.deleted());
        assertTrue(result.message().contains("Offline"));
        assertNull(settlement.acknowledge());
        assertNull(settlement.observeAbsence());
    }

    @Test
    void rejectionAfterAckCannotBecomeSuccessOnLateAbsence() {
        FlowManager.ResourceDeleteSettlement settlement = new FlowManager.ResourceDeleteSettlement("function", "shared");

        assertNull(settlement.acknowledge());
        FlowManager.ResourceDeleteResult result = settlement.fail("Resource Delete Rejected");

        assertFalse(result.deleted());
        assertNull(settlement.observeAbsence());
    }

    @Test
    void timeoutAfterAbsenceCannotBecomeSuccessOnLateAck() {
        FlowManager.ResourceDeleteSettlement settlement = new FlowManager.ResourceDeleteSettlement("chat", "global");

        assertNull(settlement.observeAbsence());
        FlowManager.ResourceDeleteResult result = settlement.fail("Resource Delete Timed Out");

        assertFalse(result.deleted());
        assertTrue(result.message().contains("Timed Out"));
        assertNull(settlement.acknowledge());
    }

    @Test
    void reconnectFailurePreservesTheResourceAgainstRediscoveredAbsenceAndAck() {
        FlowManager.ResourceDeleteSettlement settlement = new FlowManager.ResourceDeleteSettlement("scoreboard", "sidebar");

        FlowManager.ResourceDeleteResult result = settlement.fail("Connection Changed");

        assertFalse(result.deleted());
        assertTrue(result.message().contains("Connection Changed"));
        assertNull(settlement.observeAbsence());
        assertNull(settlement.acknowledge());
    }
}
