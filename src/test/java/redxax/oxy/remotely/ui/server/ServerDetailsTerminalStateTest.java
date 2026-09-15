package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;
import restudio.rebase.health.ServerHealth;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void idleGenericHealthCheckIsNotRepairing() {
        assertFalse(ServerDetailsScreen.healthRepairing(ServerDetailsScreen.ServerHealthRepair.NONE,
                ServerDetailsScreen.ServerHealthRepair.NONE));
        assertTrue(ServerDetailsScreen.healthRepairing(ServerDetailsScreen.ServerHealthRepair.EULA,
                ServerDetailsScreen.ServerHealthRepair.EULA));
        assertFalse(ServerDetailsScreen.healthRepairing(ServerDetailsScreen.ServerHealthRepair.EULA,
                ServerDetailsScreen.ServerHealthRepair.SERVER_JAR));
    }

    @Test
    void staleRepairCompletionCannotFinishNewerSameServerRepair() {
        ServerDetailsScreen.HealthRepairRequestGate gate = new ServerDetailsScreen.HealthRepairRequestGate();
        Object firstPopup = new Object();
        Object secondPopup = new Object();
        Object context = new Object();
        ServerDetailsScreen.HealthRepairRequest first = gate.tryStart("server", "subject:account", 4, context, firstPopup);

        assertNotNull(first);
        assertNull(gate.tryStart("server", "subject:account", 4, context, secondPopup));
        gate.invalidatePopup(firstPopup);
        ServerDetailsScreen.HealthRepairRequest second = gate.tryStart("server", "subject:account", 4, context, secondPopup);

        assertNotNull(second);
        assertTrue(second.requestGeneration() > first.requestGeneration());
        assertFalse(gate.current(second, "subject:account", 5, context, secondPopup));
        assertFalse(gate.current(second, "subject:account", 4, context, firstPopup));
        assertTrue(gate.current(second, "subject:account", 4, context, secondPopup));

        gate.finish(first);
        assertTrue(gate.current(second, "subject:account", 4, context, secondPopup));
    }

    @Test
    void hostedHealthAllowsOnlyAvailableVerifiedOrNotApplicableChecks() {
        ServerHealth hosted = health(ServerHealth.LaunchAvailability.AVAILABLE, ServerHealth.CheckState.NOT_APPLICABLE,
                ServerHealth.CheckState.VERIFIED, true);
        ServerHealth failed = health(ServerHealth.LaunchAvailability.AVAILABLE, ServerHealth.CheckState.NOT_APPLICABLE,
                ServerHealth.CheckState.FAILED, true);
        ServerHealth unknown = health(ServerHealth.LaunchAvailability.AVAILABLE, ServerHealth.CheckState.NOT_APPLICABLE,
                ServerHealth.CheckState.UNAVAILABLE, true);
        ServerHealth unsupported = health(ServerHealth.LaunchAvailability.UNSUPPORTED, ServerHealth.CheckState.VERIFIED,
                ServerHealth.CheckState.VERIFIED, true);

        assertTrue(hosted.healthy());
        assertFalse(failed.healthy());
        assertFalse(unknown.healthy());
        assertFalse(unsupported.healthy());
    }

    private static ServerHealth health(ServerHealth.LaunchAvailability availability, ServerHealth.CheckState first,
                                       ServerHealth.CheckState second, boolean healthy) {
        return new ServerHealth("server", "game:test", availability, List.of(
                new ServerHealth.LaunchCheck("first", "First", first),
                new ServerHealth.LaunchCheck("second", "Second", second)), healthy);
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

    @Test
    void healthRepairsAreOnlyMappedForKnownMinecraftChecks() {
        assertEquals("eula", ServerDetailsScreen.healthRepairAction(ServerHealth.MINECRAFT_JAVA, "minecraft:eula"));
        assertEquals("server-jar", ServerDetailsScreen.healthRepairAction(ServerHealth.MINECRAFT_JAVA, "minecraft:server-jar"));
        assertEquals("start-script", ServerDetailsScreen.healthRepairAction(ServerHealth.MINECRAFT_JAVA, "startup"));
        assertEquals("", ServerDetailsScreen.healthRepairAction("process:generic", "startup"));
        assertEquals("", ServerDetailsScreen.healthRepairAction(ServerHealth.MINECRAFT_JAVA, "other"));
    }
}
