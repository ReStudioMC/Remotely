package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.ui.server.ServerScreenHost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserManagerCapabilityTest {
    @Test
    void gatesCreationByActiveTargetAndAuthentication() {
        ServerScreenHost.ActionAvailability signedOut = BrowserServerScreenHost.browserManagerAction(
                ServerScreenHost.Action.CREATE_SERVER, "RESTUDIO_MARKER", false);
        ServerScreenHost.ActionAvailability wrongTarget = BrowserServerScreenHost.browserManagerAction(
                ServerScreenHost.Action.CREATE_SERVER, null, true);
        ServerScreenHost.ActionAvailability available = BrowserServerScreenHost.browserManagerAction(
                ServerScreenHost.Action.CREATE_SERVER, "RESTUDIO_MARKER", true);

        assertFalse(signedOut.available());
        assertEquals("Sign In To Create A Server", signedOut.reason());
        assertFalse(wrongTarget.available());
        assertEquals("Choose ReStudio To Create A Server", wrongTarget.reason());
        assertTrue(available.available());
    }

    @Test
    void keepsUnavailableManagerActionsVisibleWithSpecificReasons() {
        for (ServerScreenHost.Action action : new ServerScreenHost.Action[]{
                ServerScreenHost.Action.NETWORK_IMPORT,
                ServerScreenHost.Action.IMPORT_SERVER,
                ServerScreenHost.Action.REMOTE_HOST}) {
            ServerScreenHost.ActionAvailability availability = BrowserServerScreenHost.browserManagerAction(action, "RESTUDIO_MARKER", true);
            assertFalse(availability.available());
            assertFalse(availability.reason().isBlank());
        }
    }

    @Test
    void enablesHostedNetworksOnlyForAuthenticatedAccounts() {
        assertTrue(BrowserServerScreenHost.browserManagerAction(ServerScreenHost.Action.NETWORK_CREATE, null, true).available());
        assertFalse(BrowserServerScreenHost.browserManagerAction(ServerScreenHost.Action.NETWORK_CREATE, null, false).available());
    }

    @Test
    void enablesCanonicalModpackBrowserForAuthenticatedReStudioTarget() {
        assertTrue(BrowserServerScreenHost.browserManagerAction(
                ServerScreenHost.Action.MODPACK_SERVER, "RESTUDIO_MARKER", true).available());
        assertFalse(BrowserServerScreenHost.browserManagerAction(
                ServerScreenHost.Action.MODPACK_SERVER, null, true).available());
        assertFalse(BrowserServerScreenHost.browserManagerAction(
                ServerScreenHost.Action.MODPACK_SERVER, "RESTUDIO_MARKER", false).available());
    }

    @Test
    void demoKeepsOnlyCanonicalOperationalEntryPoints() {
        assertTrue(BrowserServerScreenHost.demoManagerAction(ServerScreenHost.Action.FILE_EXPLORER).available());
        assertTrue(BrowserServerScreenHost.demoManagerAction(ServerScreenHost.Action.GLOBAL_TERMINAL).available());
        assertTrue(BrowserServerScreenHost.demoManagerAction(ServerScreenHost.Action.SIGN_OUT).available());
        assertTrue(BrowserServerScreenHost.demoManagerAction(ServerScreenHost.Action.SERVER_CONFIGURATION).available());
        for (ServerScreenHost.Action action : new ServerScreenHost.Action[]{
                ServerScreenHost.Action.CREATE_SERVER,
                ServerScreenHost.Action.IMPORT_SERVER,
                ServerScreenHost.Action.RESOURCES,
                ServerScreenHost.Action.WORLD,
                ServerScreenHost.Action.NETWORK_SETTINGS,
                ServerScreenHost.Action.DEVELOPMENT,
                ServerScreenHost.Action.RESYNC_STUDIO,
                ServerScreenHost.Action.REPORTS}) {
            assertFalse(BrowserServerScreenHost.demoManagerAction(action).available());
        }
    }
}
