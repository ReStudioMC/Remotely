package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDetailsTerminalStateTest {
    @Test
    void desktopTerminalTargetRemainsLocalUuid() {
        Object target = NewTerminalTargetProvider.local().newTarget(List.of()).join();

        assertTrue(target instanceof String);
        assertNotNull(UUID.fromString((String) target));
    }

    @Test
    void staleMetricsRequestCannotFinishNewerRequest() {
        ServerDetailsScreen.MetricsRequestGate gate = new ServerDetailsScreen.MetricsRequestGate();
        ServerDetailsScreen.MetricsRequest first = gate.tryStart(1_000);

        assertNotNull(first);
        assertNull(gate.tryStart(2_000));
        ServerDetailsScreen.MetricsRequest second = gate.tryStart(6_001);
        assertNotNull(second);
        assertFalse(gate.current(first));
        assertTrue(gate.current(second));

        gate.finish(first);
        assertTrue(gate.current(second));
        gate.transition(ServerScreenHost.ServerState.RUNNING);
        assertFalse(gate.current(second));
    }

    @Test
    void hostedHealthAllowsOnlyVerifiedOrNotApplicablePrerequisites() {
        ServerScreenHost.ServerHealth hosted = new ServerScreenHost.ServerHealth(
                ServerScreenHost.PrerequisiteState.NOT_APPLICABLE,
                ServerScreenHost.PrerequisiteState.VERIFIED,
                ServerScreenHost.PrerequisiteState.NOT_APPLICABLE,
                true);
        ServerScreenHost.ServerHealth failed = new ServerScreenHost.ServerHealth(
                ServerScreenHost.PrerequisiteState.NOT_APPLICABLE,
                ServerScreenHost.PrerequisiteState.FAILED,
                ServerScreenHost.PrerequisiteState.NOT_APPLICABLE,
                true);
        ServerScreenHost.ServerHealth unknown = new ServerScreenHost.ServerHealth(
                ServerScreenHost.PrerequisiteState.NOT_APPLICABLE,
                ServerScreenHost.PrerequisiteState.UNAVAILABLE,
                ServerScreenHost.PrerequisiteState.NOT_APPLICABLE,
                true);

        assertTrue(hosted.healthy());
        assertFalse(failed.healthy());
        assertFalse(unknown.healthy());
    }

    @Test
    void explicitBrowserCloseClearsOnlyDurableDetailState() {
        assertTrue(ServerDetailsScreen.shouldClearBrowserDetailState(true, true, true));
        assertFalse(ServerDetailsScreen.shouldClearBrowserDetailState(false, true, true));
        assertFalse(ServerDetailsScreen.shouldClearBrowserDetailState(true, false, true));
        assertFalse(ServerDetailsScreen.shouldClearBrowserDetailState(true, true, false));
    }

    @Test
    void localTerminalWaitsForObservedControllerState() {
        assertFalse(DesktopServerTerminalPlatform.canStartTerminalFromInstanceState(true));
        assertTrue(DesktopServerTerminalPlatform.canStartTerminalFromInstanceState(false));
    }
}
