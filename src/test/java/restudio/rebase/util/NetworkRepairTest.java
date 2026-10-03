package restudio.rebase.util;

import com.google.gson.Gson;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.network.ConfigurationFormat;
import redxax.oxy.remotely.network.DesktopNetworkManager;
import redxax.oxy.remotely.network.ForwardingMode;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkEntryPoint;
import redxax.oxy.remotely.network.NetworkForwardingPolicy;
import redxax.oxy.remotely.network.NetworkJob;
import redxax.oxy.remotely.network.NetworkJobStatus;
import redxax.oxy.remotely.network.NetworkJobType;
import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.network.NetworkMember;
import redxax.oxy.remotely.network.NetworkMemberRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rescreen.platform.TaskScheduler;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.network.config.NetworkConfigurationAdapters;
import redxax.oxy.remotely.network.config.DesktopStructuredDocumentParser;
import restudio.rebase.backend.BackendFactory;
import restudio.rebase.backend.impl.LocalBackend;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.instance.loaders.ModLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRepairTest {
    @TempDir
    Path directory;

    private TaskScheduler previous;

    @BeforeEach
    void configureScheduler() {
        previous = TaskSchedulers.current();
        TaskSchedulers.configure(TaskScheduler.direct());
    }

    @AfterEach
    void restoreScheduler() {
        TaskSchedulers.configure(previous);
    }

    @ParameterizedTest
    @EnumSource(value = ForwardingMode.class, names = {"NONE", "MODERN"})
    void reapplyCommitsModernForwardingAndKeepsTheKeyAcrossRepeatedRepairs(ForwardingMode mode) throws Exception {
        BackendFactory.register("LOCAL", LocalBackend::new);
        Instance proxy = instance("proxy", ModLoader.VELOCITY, 40000);
        Instance lobby = instance("lobby", ModLoader.PAPER, 40001);
        Files.writeString(Path.of(proxy.getPath()).resolve("velocity.toml"), "config-version = \"2.9\"\nforced-hosts = {}\n[servers]\ntry = []\n[forced-hosts]\n");
        DesktopNetworkManager manager = new DesktopNetworkManager(directory.resolve("app"));
        try (AutoCloseable credentials = CredentialsManager.useStoresForTests(null, new FallbackCredentialStore(directory.resolve("credentials.store"), directory.resolve("credential.key")), false)) {
            NetworkDefinition base = definition(proxy, lobby);
            manager.save(base);
            if (mode == ForwardingMode.MODERN) {
                base = base.withForwarding(new NetworkForwardingPolicy(mode, true, "", false));
                Files.writeString(DesktopRemotelyPaths.dataDir(directory.resolve("app")).resolve("networks").resolve(base.networkId() + ".json"), new Gson().toJson(base));
                manager.reload();
                assertEquals(base, manager.getNetwork(base.networkId()).orElseThrow());
            }
            NetworkJob first = manager.runJob(base, List.of(proxy, lobby), List.of(), NetworkJobType.RECONCILE, "Test").join();
            assertEquals(NetworkJobStatus.SUCCEEDED, first.status(), first.message());
            NetworkDefinition repaired = manager.getNetwork(base.networkId()).orElseThrow();
            assertEquals(ForwardingMode.MODERN, repaired.forwarding().mode());
            assertEquals(base.revision() + 1, repaired.revision());
            String key = Files.readString(Path.of(proxy.getPath()).resolve("forwarding.secret")).trim();
            assertFalse(key.isBlank());
            String toml = Files.readString(Path.of(proxy.getPath()).resolve("velocity.toml"));
            assertFalse(toml.contains("forced-hosts = {}"));
            assertTrue(toml.contains("try = [\"lobby\"]"));
            String paper = Files.readString(Path.of(lobby.getPath()).resolve("config/paper-global.yml"));
            assertEquals(key, new NetworkConfigurationAdapters(new DesktopStructuredDocumentParser()).get(ConfigurationFormat.YAML).read(paper, "proxies.velocity.secret"));
            NetworkJob second = manager.runJob(repaired, List.of(proxy, lobby), List.of(), NetworkJobType.RECONCILE, "Test").join();
            assertEquals(NetworkJobStatus.SUCCEEDED, second.status(), second.message());
            assertEquals(repaired, manager.getNetwork(base.networkId()).orElseThrow());
            assertEquals(key, Files.readString(Path.of(proxy.getPath()).resolve("forwarding.secret")).trim());
            try (AutoCloseable restartedCredentials = CredentialsManager.useStoresForTests(null, new FallbackCredentialStore(directory.resolve("credentials.store"), directory.resolve("credential.key")), false)) {
                manager.reload();
                assertEquals(repaired, manager.getNetwork(base.networkId()).orElseThrow());
                assertEquals(key, manager.getSecretStore().resolveForwardingSecret(repaired.forwarding().secretReference()));
            }
            manager.getSecretStore().deleteForwardingSecret(repaired.forwarding().secretReference());
        } finally {
            manager.close();
            proxy.closeBackend();
            lobby.closeBackend();
        }
    }

    @Test
    void startCannotChangeKeysWhileAnyMemberIsRunning() throws Exception {
        BackendFactory.register("LOCAL", LocalBackend::new);
        Instance proxy = instance("proxy", ModLoader.VELOCITY, 40000);
        Instance lobby = instance("lobby", ModLoader.PAPER, 40001);
        lobby.setState(InstanceState.RUNNING);
        DesktopNetworkManager manager = new DesktopNetworkManager(directory.resolve("app"));
        try {
            NetworkDefinition base = definition(proxy, lobby);
            manager.save(base);
            assertThrows(RuntimeException.class, () -> manager.runLifecycle(base, List.of(proxy, lobby), NetworkLifecycleOperation.START, "Test").join());
            assertThrows(RuntimeException.class, () -> manager.runMemberLifecycle(base, base.proxyMember(), List.of(proxy, lobby), NetworkLifecycleOperation.START, "Test").join());
            assertEquals(base, manager.getNetwork(base.networkId()).orElseThrow());
            assertFalse(Files.exists(Path.of(proxy.getPath()).resolve("forwarding.secret")));
        } finally {
            manager.close();
            proxy.closeBackend();
            lobby.closeBackend();
        }
    }

    private Instance instance(String name, ModLoader loader, int port) throws Exception {
        Path path = Files.createDirectories(directory.resolve(name));
        Instance instance = new Instance(name, "1.21.10", path.toString());
        instance.setServer(true);
        instance.setModLoader(loader);
        instance.setState(InstanceState.STOPPED);
        instance.getServerProperties().setProperty("server-port", String.valueOf(port));
        return instance;
    }

    private NetworkDefinition definition(Instance proxy, Instance lobby) {
        NetworkMember backend = new NetworkMember(lobby.getInstanceId(), UUID.randomUUID().toString(), "lobby", NetworkMemberRole.LOBBY, "local", "127.0.0.1", 40001, 0, false);
        return NetworkDefinition.create("Repair", proxy.getInstanceId(), new NetworkForwardingPolicy(ForwardingMode.NONE, true, "", false),
                List.of(NetworkEntryPoint.primary(40000)), List.of(NetworkMember.proxy(proxy.getInstanceId(), 40000), backend));
    }
}
