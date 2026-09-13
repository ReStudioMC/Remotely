package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.network.NetworkCreationMember;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkCreationFlowTest {
    @Test
    void createsEveryDraftBeforeCommittingTheNetwork() {
        Fixture fixture = new Fixture();
        NetworkCreationPlan plan = fixture.plan(false);

        assertEquals("proxy", fixture.host.createNetwork(plan).join());

        assertEquals(List.of("create:proxy", "save:proxy", "create:backend", "save:backend", "network:proxy:backend"), fixture.events);
        assertEquals(25570, fixture.entryPort);
        assertEquals(NetworkMemberRole.LOBBY, fixture.backends.getFirst().role());
    }

    @Test
    void networkFailureRemovesCreatedServersInReverseOrder() {
        Fixture fixture = new Fixture();
        fixture.networkFailure = new IllegalStateException("Network Failed");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> fixture.host.createNetwork(fixture.plan(false)).join());

        assertTrue(failure.getCause().getMessage().contains("Network Failed"));
        assertEquals(List.of("create:proxy", "save:proxy", "create:backend", "save:backend", "network:proxy:backend", "discard:backend", "discard:proxy"), fixture.events);
    }

    @Test
    void saveFailureAlsoRemovesTheServerThatCouldNotBeConfigured() {
        Fixture fixture = new Fixture();
        fixture.failSave = "backend";

        assertThrows(IllegalStateException.class,
            () -> fixture.host.createNetwork(fixture.plan(false)).join());

        assertEquals(List.of("create:proxy", "save:proxy", "create:backend", "save:backend", "discard:backend", "discard:proxy"), fixture.events);
    }

    @Test
    void failedCreationCleansItsPartialServerBeforeEarlierServers() {
        Fixture fixture = new Fixture();
        fixture.failCreate = "backend";

        assertThrows(IllegalStateException.class,
            () -> fixture.host.createNetwork(fixture.plan(false)).join());

        assertEquals(List.of("create:proxy", "save:proxy", "create:backend", "discard:backend", "discard:proxy"), fixture.events);
    }

    @Test
    void rejectsDuplicateRoutesBeforeCreatingAnything() {
        Fixture fixture = new Fixture();
        ServerSettingsDataController settings = ServerSettingsDataController.unavailable();
        NetworkCreationPlan.Server proxy = new NetworkCreationPlan.Server("", "proxy", null, "", settings, true, "proxy", NetworkMemberRole.PROXY, 0, true);
        NetworkCreationPlan.Server first = new NetworkCreationPlan.Server("", "first", null, "", settings, false, "play", NetworkMemberRole.LOBBY, 0, true);
        NetworkCreationPlan.Server second = new NetworkCreationPlan.Server("", "second", null, "", settings, false, "PLAY", NetworkMemberRole.GAMEPLAY, 0, true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> fixture.host.createNetwork(new NetworkCreationPlan("Network", 25570, List.of(proxy, first, second))).join());

        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertTrue(fixture.events.isEmpty());
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final List<NetworkCreationMember> backends = new ArrayList<>();
        private Throwable networkFailure;
        private String failSave = "";
        private String failCreate = "";
        private int entryPort;
        private final ServerScreenHost host = new ServerScreenHost() {
            @Override
            public ApplicationHost application() {
                return (ApplicationHost) Proxy.newProxyInstance(ApplicationHost.class.getClassLoader(), new Class<?>[]{ApplicationHost.class}, (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (!type.isPrimitive()) return null;
                    if (type == boolean.class) return false;
                    if (type == char.class) return '\0';
                    return 0;
                });
            }

            @Override
            public Async<Object> createLocalInstance(Object template, String location) {
                String id = String.valueOf(template);
                events.add("create:" + id);
                return id.equals(failCreate) ? Async.failed(new IllegalStateException("Creation Failed")) : Async.completed(id);
            }

            @Override
            public Async<Void> saveInstanceConfiguration(Object instance, ServerSettingsDataController settings) {
                String id = String.valueOf(instance);
                events.add("save:" + id);
                return id.equals(failSave) ? Async.failed(new IllegalStateException("Save Failed")) : Async.completed(null);
            }

            @Override
            public ServerModels.ClientServerView serverView(Object target) {
                ServerModels.ClientServerView server = new ServerModels.ClientServerView();
                server.identifier = String.valueOf(target);
                return server;
            }

            @Override
            public Async<Void> createNetwork(String name, String proxyId, int port, List<NetworkCreationMember> members, boolean installReSync) {
                entryPort = port;
                backends.clear();
                backends.addAll(members);
                events.add("network:" + proxyId + ":" + members.getFirst().instanceId());
                return networkFailure == null ? Async.completed(null) : Async.failed(networkFailure);
            }

            @Override
            public Async<Void> discardCreatedServer(Object server) {
                events.add("discard:" + server);
                return Async.completed(null);
            }

            @Override
            public Async<Void> discardPartialNetworkServer(NetworkCreationPlan.Server plan) {
                events.add("discard:" + plan.template());
                return Async.completed(null);
            }
        };

        private NetworkCreationPlan plan(boolean existingProxy) {
            ServerSettingsDataController settings = ServerSettingsDataController.unavailable();
            NetworkCreationPlan.Server proxy = new NetworkCreationPlan.Server(existingProxy ? "proxy" : "", existingProxy ? null : "proxy", null, "", settings, true, "proxy", NetworkMemberRole.PROXY, 0, !existingProxy);
            NetworkCreationPlan.Server backend = new NetworkCreationPlan.Server("", "backend", null, "", settings, false, "backend", NetworkMemberRole.LOBBY, 0, true);
            return new NetworkCreationPlan("Network", 25570, List.of(proxy, backend));
        }
    }
}
