package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.RemotelyServerApi.CapabilityAvailability;
import redxax.oxy.remotely.RemotelyServerApi.ServerCapabilities;
import redxax.oxy.remotely.ui.server.ServerUiCapabilityProvider;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rescreen.platform.Async;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BrowserPlayerCapabilityTest {
    @Test
    void unavailablePlayerActionsDoNotReadProtectedFiles() {
        ClientServerView server = server();
        Async<ServerCapabilities> inventory = Async.pending();
        RemotelyServerApi api = api(inventory);
        Async<String> result = ServerUiCapabilityProvider.api(api).readPlayerActions(server);
        assertFalse(result.isDone());
        inventory.complete(capabilities(false));
        assertEquals("", result.value());
    }

    @Test
    void normalPlayerActionsReadTheirFileAndFollowCapabilityRevocation() {
        ClientServerView server = server();
        AtomicReference<Async<ServerCapabilities>> inventory = new AtomicReference<>(Async.completed(capabilities(true)));
        RemotelyServerApi api = (RemotelyServerApi) Proxy.newProxyInstance(RemotelyServerApi.class.getClassLoader(),
                new Class<?>[]{RemotelyServerApi.class}, (object, method, arguments) -> {
                    if (method.getName().equals("getServerCapabilities")) return inventory.get();
                    if (method.getName().equals("getFileContent")) {
                        assertEquals(server.identifier, arguments[0]);
                        assertEquals("Remotely/player-actions.json", arguments[1]);
                        if (!inventory.get().value().action("players.actions").supported()) throw new AssertionError("Protected File Requested");
                        return Async.completed("{\"actions\":[]}");
                    }
                    throw new AssertionError("Unexpected API Request: " + method.getName());
                });
        ServerUiCapabilityProvider provider = ServerUiCapabilityProvider.api(api);
        assertEquals("{\"actions\":[]}", provider.readPlayerActions(server).value());
        inventory.set(Async.completed(capabilities(false)));
        assertEquals("", provider.readPlayerActions(server).value());
    }

    private RemotelyServerApi api(Async<ServerCapabilities> inventory) {
        return (RemotelyServerApi) Proxy.newProxyInstance(RemotelyServerApi.class.getClassLoader(),
                new Class<?>[]{RemotelyServerApi.class}, (object, method, arguments) -> {
                    if (method.getName().equals("getServerCapabilities")) return inventory;
                    throw new AssertionError("Protected API Requested: " + method.getName());
                });
    }

    private ClientServerView server() {
        ClientServerView server = new ClientServerView();
        server.identifier = "demo-server";
        return server;
    }

    private ServerCapabilities capabilities(boolean supported) {
        return new ServerCapabilities("demo-server", Map.of("players.list", new CapabilityAvailability(true, "", ""),
                "players.actions", new CapabilityAvailability(supported, "", "")), null);
    }
}
