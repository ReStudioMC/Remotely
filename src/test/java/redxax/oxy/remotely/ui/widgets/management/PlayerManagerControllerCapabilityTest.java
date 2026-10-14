package redxax.oxy.remotely.ui.widgets.management;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import redxax.oxy.remotely.ui.server.ServerUiCapabilityProvider;
import restudio.rescreen.platform.Async;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.ui.core.ScreenManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerManagerControllerCapabilityTest {
    @Test
    void loadsWhenPlayerCapabilityBecomesReady() {
        ServerModels.ClientServerView server = server("capability-ready");
        MutablePlayerCapabilities capabilities = new MutablePlayerCapabilities();
        PlayerManagerController controller = PlayerManagerController.getOrCreate(server, capabilities);

        controller.setUiBindings(null, null);

        assertEquals(PlayerManagerController.LoadState.LOADING, controller.getLoadState());
        assertEquals(0, capabilities.requests);

        capabilities.supported = true;
        ScreenManager screenManager = ScreenManager.getInstance();
        var runtime = screenManager.runtime();
        screenManager.restoreRuntimeReference(null);
        try {
            capabilities.notifyListeners();
            for (int attempt = 0; attempt < 100 && capabilities.requests == 0; attempt++) screenManager.processTasks();
        } finally {
            screenManager.restoreRuntimeReference(runtime);
        }

        assertEquals(1, capabilities.requests);
        assertEquals(PlayerManagerController.LoadState.LOADED, controller.getLoadState());
    }

    @Test
    void exposesPlayerLoadFailure() {
        ServerModels.ClientServerView server = server("capability-failure");
        MutablePlayerCapabilities capabilities = new MutablePlayerCapabilities();
        capabilities.supported = true;
        capabilities.failure = new IllegalStateException("Player Snapshot Timed Out");
        PlayerManagerController controller = PlayerManagerController.getOrCreate(server, capabilities);

        controller.setUiBindings(null, null);

        assertEquals(PlayerManagerController.LoadState.FAILED, controller.getLoadState());
        assertEquals("Player Snapshot Timed Out", controller.getLoadFailure());
    }

    private static ServerModels.ClientServerView server(String id) {
        ServerModels.ClientServerView server = new ServerModels.ClientServerView();
        server.identifier = id;
        server.name = id;
        return server;
    }

    @Test
    void readsWhitelistMembershipAndRejectsActionsAfterPropertiesSwitchOff() {
        MutablePlayerCapabilities capabilities = new MutablePlayerCapabilities();
        capabilities.supported = true;
        capabilities.properties = "white-list=true\n";
        UnifiedPlayer player = new UnifiedPlayer(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "Player_1");
        capabilities.whitelist = "[{\"uuid\":\"" + player.getUuid() + "\",\"name\":\"Player_1\"}]";
        PlayerManagerController controller = PlayerManagerController.getOrCreate(server("whitelist-gate"), capabilities);
        controller.setUiBindings(null, null);
        assertTrue(controller.isWhitelistEnabled());
        assertTrue(controller.isWhitelisted(player));
        assertTrue(controller.canExecutePlayerAction("unwhitelist"));

        capabilities.properties = "white-list=false\n";
        controller.setWhitelisted(player, false);
        assertFalse(controller.isWhitelistEnabled());
        assertFalse(controller.canExecutePlayerAction("whitelist"));
        assertTrue(capabilities.actions.isEmpty());

        capabilities.properties = "white-list=true\n";
        capabilities.whitelist = "[]";
        controller.refreshWhitelist().join();
        assertFalse(controller.isWhitelisted(player));
        controller.setWhitelisted(List.of(player, new UnifiedPlayer(UUID.randomUUID(), "Player_2")), true);
        assertEquals(List.of("whitelist", "whitelist"), capabilities.actions);

        capabilities.stopped = true;
        capabilities.files.put("ops.json", "[{\"uuid\":\"123e4567-e89b-12d3-a456-426614174001\",\"name\":\"Owner\",\"level\":2,\"custom\":true}]");
        controller.fullRefresh();
        assertTrue(controller.canExecutePlayerAction("op"));
        assertFalse(controller.canExecutePlayerAction("kick"));
        controller.toggleOp(player);
        var operators = JsonParser.parseString(capabilities.files.get("ops.json")).getAsJsonArray();
        assertEquals(2, operators.size());
        assertTrue(operators.get(0).getAsJsonObject().get("custom").getAsBoolean());
        assertEquals(4, operators.get(1).getAsJsonObject().get("level").getAsInt());
        controller.banPlayer(player, "Offline Ban", false).join();
        assertEquals("Offline Ban", JsonParser.parseString(capabilities.files.get("banned-players.json")).getAsJsonArray().get(0).getAsJsonObject().get("reason").getAsString());
        controller.unbanPlayer(player);
        assertEquals("[]", capabilities.files.get("banned-players.json"));
        controller.setWhitelisted(player, false);
        assertFalse(controller.isWhitelisted(player));
        assertEquals(List.of("whitelist", "whitelist"), capabilities.actions);
    }

    private static final class MutablePlayerCapabilities implements ServerUiCapabilityProvider {
        private final List<Runnable> listeners = new ArrayList<>();
        private boolean supported;
        private RuntimeException failure;
        private int requests;
        private String properties = "white-list=false\n";
        private String whitelist = "[]";
        private final List<String> actions = new ArrayList<>();
        private final Map<String, String> files = new LinkedHashMap<>();
        private boolean stopped;

        @Override public boolean serverRunning(Object server) { return !stopped; }
        @Override public boolean serverStopped(Object server) { return stopped; }
        @Override public Availability playerFilesAvailability(Object server) { return Availability.supported(); }

        @Override
        public Async<ServerModels.ServerStatus> status(ServerModels.ClientServerView server) {
            ServerModels.ServerStatus status = new ServerModels.ServerStatus();
            status.currentState = stopped ? "offline" : "running";
            return Async.completed(status);
        }

        @Override
        public Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(ServerModels.ClientServerView server, String directory) {
            List<String> names = new ArrayList<>(files.keySet());
            names.add("whitelist.json");
            return Async.completed(names.stream().map(name -> {
                ServerModels.PteroFileObjectAttributes file = new ServerModels.PteroFileObjectAttributes();
                file.name = name;
                file.isFile = true;
                return file;
            }).toList());
        }

        @Override
        public Async<Void> writeFile(ServerModels.ClientServerView server, String path, String content) {
            if ("whitelist.json".equals(path)) whitelist = content;
            else files.put(path, content);
            return Async.completed(null);
        }

        @Override
        public Async<String> readFile(ServerModels.ClientServerView server, String path) {
            return Async.completed("server.properties".equals(path) ? properties : "whitelist.json".equals(path) ? whitelist : files.get(path));
        }

        @Override
        public Async<Void> playerAction(ServerModels.ClientServerView server, RemotelyServerApi.PlayerAction action) {
            actions.add(action.action());
            if ("whitelist".equals(action.action())) {
                String entry = "{\"uuid\":\"" + action.playerId() + "\",\"name\":\"" + action.playerName() + "\"}";
                whitelist = "[]".equals(whitelist) ? "[" + entry + "]" : whitelist.substring(0, whitelist.length() - 1) + "," + entry + "]";
            }
            return Async.completed(null);
        }

        @Override
        public Availability availability(ServerModels.ClientServerView server, Capability capability) {
            return supported ? Availability.supported() : Availability.missing("Server Capabilities Are Loading");
        }

        @Override
        public Async<List<RemotelyServerApi.Player>> players(ServerModels.ClientServerView server) {
            requests++;
            return failure == null ? Async.completed(List.of()) : Async.failed(failure);
        }

        @Override
        public void addCapabilityListener(Runnable listener) {
            listeners.add(listener);
        }

        @Override
        public void removeCapabilityListener(Runnable listener) {
            listeners.remove(listener);
        }

        private void notifyListeners() {
            List.copyOf(listeners).forEach(Runnable::run);
        }
    }
}
