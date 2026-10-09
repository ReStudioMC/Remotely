package redxax.oxy.remotely.ui.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.network.NetworkCreationMember;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import restudio.rebase.resource.ResourcePoolClient;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingEntryWidget;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.widgets.DropDownWidget;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkCreationFlowTest {
    @Test
    void reactorSetupNeverFallsBackToLocalServersWhilePoolsLoadOrSourcesChange() {
        ThemeManager.Snapshot theme = ThemeManager.snapshot();
        ThemeManager.initBrowserDefaults();
        Fixture fixture = new Fixture();
        NetworkCreationScreen screen = new NetworkCreationScreen(new Screen(), fixture.host, List.of(),
            NetworkCreationContext.from(fixture.host, "RESTUDIO_MARKER"));
        ScreenManager screens = ScreenManager.getInstance();
        try {
            screen.resize(1000, 400);
            screen.init();
            screens.processTasks();
            assertEquals(List.of("pools:/resource-pools/draft-options", "pools:/resource-pools?page=0&size=50"), fixture.events);
            var network = screen.container();
            textOption(screen, "Network Name").set("Shared Network");

            screen.tabs().setActiveTab(screen.tabs().getTabs().getLast().getContainer());
            chooseSource(screen, "External Backend");
            textOption(screen, "Server Name").set("External Lobby");
            chooseSource(screen, "New Server");
            screens.processTasks();
            assertEquals(List.of("pools:/resource-pools/draft-options", "pools:/resource-pools?page=0&size=50"), fixture.events);

            chooseSource(screen, "Existing Server");
            screens.processTasks();
            assertEquals(List.of("pools:/resource-pools/draft-options", "pools:/resource-pools?page=0&size=50", "reactor-servers"), fixture.events);
            var member = screen.container();
            dropdown(screen, "Server").setSelectedItem("server-10");
            assertEquals("server-10", textOption(screen, "Server").get());
            assertEquals(member, screen.container());
            chooseSource(screen, "External Backend");
            assertEquals("External Lobby", textOption(screen, "Server Name").get());
            chooseSource(screen, "Existing Server");
            screens.processTasks();
            assertEquals("server-10", textOption(screen, "Server").get());
            assertEquals(List.of("pools:/resource-pools/draft-options", "pools:/resource-pools?page=0&size=50", "reactor-servers"), fixture.events);

            for (int i = 0; i < 6; i++) screen.tabs().getPlusButton().action.run();
            screen.tabs().setActiveTab(network);
            assertEquals("Shared Network", textOption(screen, "Network Name").get());
            network.scrollController().snap(40);
            float scroll = network.scrollController().target();
            assertTrue(scroll > 0);
            screen.tabs().setActiveTab(member);
            chooseSource(screen, "New Server");
            screen.tabs().setActiveTab(network);
            assertEquals(scroll, network.scrollController().target());
            assertEquals("Shared Network", textOption(screen, "Network Name").get());
            assertThrows(NoSuchElementException.class, () -> entry(screen, "Resource Pool"));

            ResourcePoolModels.Resources empty = new ResourcePoolModels.Resources("0", "0", "0", "0");
            ResourcePoolModels.Resources available = new ResourcePoolModels.Resources("8192", "800", "81920", "0");
            ResourcePoolModels.Domain domain = new ResourcePoolModels.Domain("Reactor Production", "Shared X86");
            ResourcePoolModels.Pool spent = new ResourcePoolModels.Pool(UUID.randomUUID(), domain,
                new ResourcePoolModels.Balance(available, available, empty, empty));
            ResourcePoolModels.Resources smaller = new ResourcePoolModels.Resources("4096", "400", "40960", "0");
            ResourcePoolModels.Pool small = new ResourcePoolModels.Pool(UUID.randomUUID(), domain,
                new ResourcePoolModels.Balance(smaller, empty, smaller, empty));
            ResourcePoolModels.Pool usable = new ResourcePoolModels.Pool(UUID.randomUUID(), domain,
                new ResourcePoolModels.Balance(available, empty, available, empty));
            Gson gson = new Gson();
            fixture.poolRequests.get("/resource-pools/draft-options").complete("{\"games\":[],\"minimumInstallerRamMiB\":256,\"minimumInstallerCpuPercent\":50}");
            screens.processTasks();
            fixture.poolRequests.get("/resource-pools?page=0&size=50").complete(gson.toJson(new ResourcePoolModels.Page<>(List.of(spent, small), 0, 50, true)));
            screens.processTasks();
            fixture.poolRequests.get("/resource-pools?page=1&size=50").complete(gson.toJson(new ResourcePoolModels.Page<>(List.of(usable), 1, 50, false)));
            screens.processTasks();
            assertTrue(!fixture.poolRequests.containsKey("/resource-pools/" + usable.id()));
            ResourceAllocationBarWidget ram = screen.container().getWidgets().stream().filter(Setting.class::isInstance).map(Setting.class::cast)
                .filter(setting -> "Network Resources".equals(setting.getTitle())).flatMap(setting -> setting.getRows().stream())
                .flatMap(row -> row.getWidgets().stream()).filter(ResourceAllocationBarWidget.class::isInstance)
                .map(ResourceAllocationBarWidget.class::cast).findFirst().orElseThrow();
            assertEquals(8, ram.segments().stream().filter(segment -> segment.key().startsWith("preview:")).count());
            var allocations = ram.segments();
            screen.tabs().setActiveTab(member);
            screen.tabs().setActiveTab(network);
            assertEquals(allocations, ram.segments());
            assertTrue(fixture.events.stream().noneMatch(event -> event.startsWith("local-")));
        } finally {
            screen.removed();
            screens.processTasks();
            ThemeManager.restore(theme);
        }
    }

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

    private static void chooseSource(NetworkCreationScreen screen, String label) {
        choose(entry(screen, "Source").getOption(), label);
    }

    private static SettingEntryWidget entry(NetworkCreationScreen screen, String name) {
        return screen.container().getWidgets().stream()
            .filter(Setting.class::isInstance).map(Setting.class::cast)
            .flatMap(setting -> setting.getRows().stream()).flatMap(row -> row.getWidgets().stream())
            .filter(SettingEntryWidget.class::isInstance).map(SettingEntryWidget.class::cast)
            .filter(value -> name.equals(value.getOption().getName()))
            .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ConfigOption<String> textOption(NetworkCreationScreen screen, String name) {
        return (ConfigOption<String>) entry(screen, name).getOption();
    }

    @SuppressWarnings("unchecked")
    private static DropDownWidget<String> dropdown(NetworkCreationScreen screen, String name) {
        return (DropDownWidget<String>) entry(screen, name).getChildWidgets().stream()
                .filter(DropDownWidget.class::isInstance).findFirst().orElseThrow();
    }

    private static <T> void choose(ConfigOption<T> option, String label) {
        option.set(option.getOptions().stream().filter(value -> label.equals(option.getDisplayFunction().apply(value)))
            .findFirst().orElseThrow());
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final List<NetworkCreationMember> backends = new ArrayList<>();
        private final Map<String, Async<String>> poolRequests = new LinkedHashMap<>();
        private final ResourcePoolClient resourcePools = new ResourcePoolClient((method, path, body) -> {
            events.add("pools:" + path);
            return poolRequests.computeIfAbsent(path, ignored -> Async.pending());
        });
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
            public ResourcePoolClient resourcePools() {
                return resourcePools;
            }

            @Override
            public Async<Void> loadInstanceProperties(Object instance, boolean remote) {
                events.add("local-properties");
                return Async.pending();
            }

            @Override
            public Async<List<ServerModels.ClientServerView>> localServers() {
                events.add("local-servers");
                return Async.completed(List.of());
            }

            @Override
            public Async<List<HostView>> remoteHosts() {
                events.add("hosts");
                return Async.completed(List.of());
            }

            @Override
            public Async<List<ServerModels.ClientServerView>> restudioServers() {
                events.add("reactor-servers");
                List<ServerModels.ClientServerView> servers = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    ServerModels.ClientServerView server = new ServerModels.ClientServerView();
                    server.identifier = "server-" + i;
                    server.name = "Server " + i;
                    server.loader = "PAPER";
                    servers.add(server);
                }
                return Async.completed(servers);
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
