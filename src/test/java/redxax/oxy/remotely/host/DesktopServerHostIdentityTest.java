package redxax.oxy.remotely.host;

import org.junit.jupiter.api.Test;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.instance.Instance;
import restudio.rebase.restudio.api.models.ServerModels;

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
}
