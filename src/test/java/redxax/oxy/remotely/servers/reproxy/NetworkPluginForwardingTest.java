package redxax.oxy.remotely.servers.reproxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redxax.oxy.remotely.servers.ReProxyTarget;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.resource.ResourceType;
import restudio.rebase.ui.screens.resources.ResourceContainerItem;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPluginForwardingTest {
    private TaskScheduler previous;

    @BeforeEach
    void prepareScheduler() {
        previous = TaskSchedulers.current();
        TaskSchedulers.configure(TaskScheduler.direct());
    }

    @AfterEach
    void restoreScheduler() {
        TaskSchedulers.configure(previous);
    }

    @Test
    @Timeout(10)
    void resumesSharedVoiceSetupAfterMemberConnectionsWereSaved() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/assets/remotely/reproxy/integrations.json")) {
            ReProxyIntegrations.load(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
        }
        ReProxyIntegrations.Integration integration = ReProxyIntegrations.catalog().stream().filter(value -> value.id().equals("plasmovoice")).findFirst().orElseThrow();
        String settings = "[host]\nip = \"127.0.0.1\"\nport = 0\n\n[host.public]\nip = \"old.example\"\nport = 0\n";
        String proxySettings = settings + "\n[servers]\nlegacy = \"old.example:24454\"\n";
        String proxyPath = ReProxyIntegrations.paths(integration, true).getFirst();
        String backendPath = ReProxyIntegrations.paths(integration, false).getFirst();
        String proxySecretPath = proxyPath.substring(0, proxyPath.lastIndexOf('/') + 1) + "forwarding-secret";
        String backendSecretPath = backendPath.substring(0, backendPath.lastIndexOf('/') + 1) + "forwarding-secret";
        String proxySecret = "00000000-0000-0000-0000-000000000001\n";
        String backendSecret = "00000000-0000-0000-0000-000000000002\n";
        Memory proxy = new Memory("proxy", 25565, Map.of(proxyPath, proxySettings, proxySecretPath, proxySecret));
        Memory backend = new Memory("backend", 25566, Map.of(backendPath, settings, backendSecretPath, backendSecret));
        PluginForwarding firstProxy = new PluginForwarding(proxy);
        PluginForwarding firstBackend = new PluginForwarding(backend);
        try {
            firstProxy.admitIntegration(integration, true);
            firstBackend.admitIntegration(integration, false);
            firstProxy.connect(integration, true, null, "network:1").join();
            firstBackend.connect(integration, false, null, "network:1").join();
            assertFalse(firstProxy.state(integration).connected());
            assertFalse(firstBackend.state(integration).connected());
        } finally {
            firstProxy.close();
            firstBackend.close();
        }

        PluginForwarding reopenedProxy = new PluginForwarding(proxy);
        PluginForwarding reopenedBackend = new PluginForwarding(backend);
        NetworkPluginForwarding.Network network = new NetworkPluginForwarding.Network("network", 1, "proxy", List.of(
                new NetworkPluginForwarding.Member("proxy", "proxy", "reactor", "proxy.example", 25565, true, reopenedProxy),
                new NetworkPluginForwarding.Member("backend", "lobby", "reactor", "backend.example", 25566, false, reopenedBackend)), true);
        NetworkPluginForwarding forwarding = new NetworkPluginForwarding(reopenedProxy, () -> Async.completed(network), new NetworkPluginForwarding.Coordinator());
        ResourceContainerItem resource = new ResourceContainerItem("plugins/plasmovoice.jar", ResourceType.PLUGIN, "plasmovoice.jar", true);
        try {
            forwarding.admit(List.of(resource));
            assertFalse(forwarding.state(resource).connected());
            forwarding.toggle(resource).join();
            assertTrue(forwarding.state(resource).connected());
            assertEquals(proxySecret, backend.document(backendSecretPath).content());
            assertEquals(proxySecret, proxy.document(proxySecretPath).content());
            assertEquals("\"backend.example:25566\"", ReProxyIntegrations.read(integration, proxy.document(proxyPath).content(), "servers.lobby"));
            assertEquals("\"0.0.0.0\"", ReProxyIntegrations.read(integration, backend.document(backendPath).content(), "host.ip"));

            forwarding.toggle(resource).join();
            assertFalse(forwarding.state(resource).connected());
            assertEquals(proxySettings, proxy.document(proxyPath).content());
            assertEquals(settings, backend.document(backendPath).content());
            assertEquals(proxySecret, proxy.document(proxySecretPath).content());
            assertEquals(backendSecret, backend.document(backendSecretPath).content());
            assertTrue(proxy.ports.isEmpty());
            assertTrue(backend.ports.isEmpty());
        } finally {
            forwarding.close();
            reopenedProxy.close();
            reopenedBackend.close();
        }
    }

    private static final class Memory implements PluginForwarding.Access {
        private final String id;
        private final int gamePort;
        private final Map<String, PluginForwarding.Document> files = new HashMap<>();
        private final Map<String, PluginForwarding.Port> ports = new HashMap<>();
        private long revision;

        private Memory(String id, int gamePort, Map<String, String> originals) {
            this.id = id;
            this.gamePort = gamePort;
            originals.forEach((path, content) -> files.put(path, new PluginForwarding.Document(true, content, Long.toString(++revision))));
        }

        private PluginForwarding.Document document(String path) {
            return files.getOrDefault(path, new PluginForwarding.Document(false, "", ""));
        }

        @Override public String serverKey() { return id; }
        @Override public String name() { return id; }
        @Override public int serverPort() { return gamePort; }
        @Override public boolean local() { return false; }
        @Override public Ports ports() { return Ports.ALLOCATED; }
        @Override public Async<Void> secretFiles() { return Async.completed(null); }
        @Override public Async<Boolean> running() { return Async.completed(false); }
        @Override public Async<PluginForwarding.Document> observe(String path) { return Async.completed(document(path)); }
        @Override public ReProxyTarget localTarget() { return null; }

        @Override
        public Async<PluginForwarding.Document> mutate(String path, PluginForwarding.Document expected, String content, String operationId) {
            if (!document(path).equals(expected)) return Async.failed(new IllegalStateException("File Changed"));
            PluginForwarding.Document changed = new PluginForwarding.Document(content != null, content, Long.toString(++revision));
            files.put(path, changed);
            return Async.completed(changed);
        }

        @Override
        public Async<PluginForwarding.Port> reserve(ReProxyIntegrations.Integration integration, int desiredPort, String operationId) {
            String portId = id + ":" + integration.id();
            PluginForwarding.Port port = ports.computeIfAbsent(portId, key -> new PluginForwarding.Port(id + ".example", desiredPort, key, true, operationId));
            return Async.completed(port);
        }

        @Override
        public Async<Void> release(ReProxyIntegrations.Integration integration, PluginForwarding.Port port) {
            if (!ports.remove(port.id(), port)) return Async.failed(new IllegalStateException("Port Changed"));
            return Async.completed(null);
        }
    }
}
