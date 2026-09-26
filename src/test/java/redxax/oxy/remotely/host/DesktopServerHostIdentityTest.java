package redxax.oxy.remotely.host;

import org.junit.jupiter.api.Test;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceOperation;
import restudio.rebase.restudio.api.models.ServerModels;
import redxax.oxy.remotely.ui.server.ServerScreenHost;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopServerHostIdentityTest {
    @Test
    void remoteIdentityRequiresTheOwningHost() {
        Instance instance = new Instance("Shared Name", "1.21.8", "/servers/shared");
        instance.setBackendConfig(new BackendConfig("SSH", new LinkedHashMap<>()));
        instance.getBackendConfig().credentials.put("hostId", "host-a");

        ServerModels.ClientServerView server = new ServerModels.ClientServerView();
        server.identifier = instance.getInstanceId();
        server.environment = new LinkedHashMap<>();
        server.environment.put("remotely.desktopHostId", "host-a");

        assertTrue(DesktopServerHost.matchesDesktopIdentity(server, instance));

        server.environment.put("remotely.desktopHostId", "host-b");

        assertFalse(DesktopServerHost.matchesDesktopIdentity(server, instance));
    }

    @Test
    void terminalInputRoutesPreserveEachBackendTransport() {
        assertEquals(DesktopServerHost.TerminalInputRoute.DIRECT, DesktopServerHost.terminalInputRoute("LOCAL"));
        assertEquals(DesktopServerHost.TerminalInputRoute.DIRECT, DesktopServerHost.terminalInputRoute("RESTUDIO"));
        assertEquals(DesktopServerHost.TerminalInputRoute.DIRECT, DesktopServerHost.terminalInputRoute("SSH"));
        assertEquals(DesktopServerHost.TerminalInputRoute.BACKEND_API, DesktopServerHost.terminalInputRoute("PTERO"));
        assertEquals(DesktopServerHost.TerminalInputRoute.BACKEND_API, DesktopServerHost.terminalInputRoute("CALAGOPUS"));
    }

    @Test
    void unresolvedDesktopInstanceRetainsTheServerViewTransport() {
        ServerModels.ClientServerView server = new ServerModels.ClientServerView();
        server.backendType = "SSH";

        assertEquals("SSH", DesktopServerHost.terminalBackendType(server, null));
        assertEquals(DesktopServerHost.TerminalInputRoute.DIRECT,
                DesktopServerHost.terminalInputRoute(DesktopServerHost.terminalBackendType(server, null)));
    }

    @Test
    void reactorExplorerVisibilityAcceptsIdentifiersAndNames() {
        ServerModels.ClientServerView server = new ServerModels.ClientServerView();
        server.identifier = "reactor-one";
        server.name = "Reactor One";

        assertFalse(DesktopServerHost.isHiddenRestudioServer(List.of(), server));
        assertTrue(DesktopServerHost.isHiddenRestudioServer(List.of("reactor-one"), server));
        assertTrue(DesktopServerHost.isHiddenRestudioServer(List.of("Reactor One"), server));
        assertFalse(DesktopServerHost.isHiddenRestudioServer(List.of("reactor-two"), server));
    }

    @Test
    void reactorExplorerUsesTheLoadedVisibleSnapshotInOrder() {
        ServerModels.ClientServerView first = new ServerModels.ClientServerView();
        first.identifier = "reactor-one";
        first.name = "Reactor One";
        ServerModels.ClientServerView hidden = new ServerModels.ClientServerView();
        hidden.identifier = "reactor-hidden";
        hidden.name = "Hidden Reactor";
        ServerModels.ClientServerView last = new ServerModels.ClientServerView();
        last.identifier = "reactor-three";
        last.name = "Reactor Three";

        List<ServerModels.ClientServerView> visible = DesktopServerHost.visibleRestudioServers(
                List.of(first, hidden, last), List.of("reactor-hidden"));

        assertEquals(List.of(first, last), visible);
    }

    @Test
    void oldControllerResponsesCannotReverseNewPowerRequests() {
        InstanceOperation start = operation(InstanceOperation.Type.START, 2_000);
        InstanceOperation stop = operation(InstanceOperation.Type.STOP, 4_000);
        ServerScreenHost.LocalStatus oldStop = status("STOPPED", "STOPPED", 1_000);
        ServerScreenHost.LocalStatus oldStopping = new ServerScreenHost.LocalStatus(true, true, false, "STOPPING", "STOPPED",
                null, "", 42, 42, 42, List.of(42L), 1_000);
        ServerScreenHost.LocalStatus newCrash = status("CRASHED", "RUNNING", 2_500);
        ServerScreenHost.LocalStatus oldStart = status("STARTING", "RUNNING", 2_500);
        ServerScreenHost.LocalStatus stopping = status("STOPPING", "STOPPED", 2_500);

        assertTrue(DesktopServerHost.isStaleLocalStatus(start, oldStop));
        assertTrue(DesktopServerHost.isStaleLocalStatus(start, oldStopping));
        assertFalse(DesktopServerHost.isStaleLocalStatus(start, newCrash));
        assertTrue(DesktopServerHost.isStaleLocalStatus(stop, oldStart));
        assertFalse(DesktopServerHost.isStaleLocalStatus(stop, stopping));
    }

    private static InstanceOperation operation(InstanceOperation.Type type, long startedAt) {
        return new InstanceOperation(type.name(), type, InstanceOperation.Status.ACTIVE, "Working", "Working", -1,
                startedAt, startedAt, 0, null);
    }

    private static ServerScreenHost.LocalStatus status(String state, String desiredState, long startTimeMs) {
        return new ServerScreenHost.LocalStatus(true, true, false, state, desiredState, null, "", 0, 0, 0,
                List.of(), startTimeMs);
    }
}
