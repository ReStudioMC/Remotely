package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.rebase.backend.BackendFactory;
import restudio.rebase.backend.impl.LocalBackend;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkConfigurationTransactionTest {
    @Test
    void freshApplyDoesNotCreateAnAbsentDocumentForAnUnchangedMutation(@TempDir Path directory) {
        BackendFactory.register("LOCAL", LocalBackend::new);
        Path proxyDirectory = directory.resolve("proxy");
        Path backendDirectory = directory.resolve("backend");
        Instance proxy = instance("Proxy", proxyDirectory, ModLoader.VELOCITY);
        Instance backend = instance("Lobby", backendDirectory, ModLoader.PAPER);
        NetworkMember proxyMember = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkMember backendMember = NetworkMember.backend(backend.getInstanceId(), "lobby", NetworkMemberRole.LOBBY, 25566);
        NetworkDefinition network = NetworkDefinition.create("Network", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxyMember, backendMember));
        NetworkConfigDocumentKey key = new NetworkConfigDocumentKey(backend.getInstanceId(), "plugins/ReSync/resync.properties");
        NetworkConfigMutation unchanged = new NetworkConfigMutation(backend.getInstanceId(), key.path(), ConfigurationFormat.PROPERTIES,
                "network.transfer.realm", "", "", false, true, "Clear Player State Realm", NetworkMutationAction.SET, false);
        NetworkReconciliationPlan plan = new NetworkReconciliationPlan("", network.networkId(), network.revision(), 0, List.of(unchanged), List.of());
        NetworkPreparedPlan prepared = new NetworkPreparedPlan(plan, Map.of(key, new NetworkDocumentSnapshot(key, "", false)));
        NetworkConfigurationTransaction transaction = new NetworkConfigurationTransaction();

        List<NetworkJobDocument> documents = transaction.describe(prepared, network, List.of(proxy, backend));
        NetworkApplyResult result = transaction.apply(prepared, network, List.of(proxy, backend)).join();

        assertEquals(NetworkJobDocumentState.UNCHANGED, documents.getFirst().state());
        assertTrue(result.applied());
        assertTrue(result.changedDocuments().isEmpty());
        assertFalse(Files.exists(backendDirectory.resolve(key.path())));
        assertFalse(backend.getServerProperties().containsKey("network.transfer.realm"));
    }

    @Test
    void recoveryDistinguishesAbsentDocumentsFromEmptyFiles() {
        NetworkConfigDocumentKey key = new NetworkConfigDocumentKey("server", "empty.properties");
        String empty = NetworkExecutionPlan.fingerprint("");
        NetworkJobDocument absent = new NetworkJobDocument(key, 0, false, empty, empty, NetworkJobDocumentState.UNCHANGED);
        NetworkJobDocument emptyFile = new NetworkJobDocument(key, 0, true, empty, empty, NetworkJobDocumentState.UNCHANGED);
        NetworkJobDocument changedToEmpty = new NetworkJobDocument(key, 0, true, NetworkExecutionPlan.fingerprint("value=true\n"), empty,
                NetworkJobDocumentState.PENDING);

        assertThrows(IllegalStateException.class, () -> NetworkExecutionPlan.recover(List.of(absent), List.of(emptyFile)));
        assertThrows(IllegalStateException.class, () -> NetworkExecutionPlan.recover(List.of(changedToEmpty), List.of(absent)));
    }

    @Test
    void preparedFingerprintMatchesOnlyAppliedChangesForNewPropertiesFile(@TempDir Path directory) throws Exception {
        BackendFactory.register("LOCAL", LocalBackend::new);
        Path proxyDirectory = Files.createDirectories(directory.resolve("proxy"));
        Path backendDirectory = Files.createDirectories(directory.resolve("backend"));
        Instance proxy = instance("Proxy", proxyDirectory, ModLoader.VELOCITY);
        Instance backend = instance("Lobby", backendDirectory, ModLoader.PAPER);
        NetworkMember proxyMember = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkMember backendMember = NetworkMember.backend(backend.getInstanceId(), "lobby", NetworkMemberRole.LOBBY, 25566);
        NetworkDefinition network = NetworkDefinition.create("Network", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxyMember, backendMember));
        NetworkConfigDocumentKey key = new NetworkConfigDocumentKey(backend.getInstanceId(), "plugins/ReSync/resync.properties");
        NetworkConfigMutation networkId = new NetworkConfigMutation(backend.getInstanceId(), key.path(), ConfigurationFormat.PROPERTIES, "network.id", "", network.networkId(), false, true, "Set ReSync Network", NetworkMutationAction.SET, false);
        NetworkConfigMutation emptyRealm = new NetworkConfigMutation(backend.getInstanceId(), key.path(), ConfigurationFormat.PROPERTIES, "network.transfer.realm", "", "", false, true, "Clear Player State Realm", NetworkMutationAction.SET, false);
        NetworkReconciliationPlan plan = new NetworkReconciliationPlan("", network.networkId(), network.revision(), 0, List.of(networkId, emptyRealm), List.of());
        NetworkPreparedPlan prepared = new NetworkPreparedPlan(plan, Map.of(key, new NetworkDocumentSnapshot(key, "", false)));
        NetworkConfigurationTransaction transaction = new NetworkConfigurationTransaction();

        List<NetworkJobDocument> documents = transaction.describe(prepared, network, List.of(proxy, backend));
        NetworkApplyResult result = transaction.apply(prepared, network, List.of(proxy, backend), NetworkTransactionListener.NONE, documents).join();
        String written = Files.readString(backendDirectory.resolve(key.path()));

        assertTrue(result.applied());
        assertTrue(written.contains("network.id=" + network.networkId()));
        assertFalse(written.contains("network.transfer.realm"));
    }

    @Test
    void retainsReadableOriginalDocumentForLaterMemberDetach(@TempDir Path directory) throws Exception {
        BackendFactory.register("LOCAL", LocalBackend::new);
        Path proxyDirectory = Files.createDirectories(directory.resolve("proxy"));
        Path backendDirectory = Files.createDirectories(directory.resolve("backend"));
        String original = "server-port=25565\nonline-mode=false\nserver-ip=192.168.1.20\nmotd=Keep Me\n";
        Files.writeString(backendDirectory.resolve("server.properties"), original);
        Instance proxy = instance("Proxy", proxyDirectory, ModLoader.VELOCITY);
        Instance backend = instance("Backend", backendDirectory, ModLoader.PAPER);
        NetworkMember proxyMember = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkMember backendMember = NetworkMember.backend(backend.getInstanceId(), "backend", NetworkMemberRole.GAMEPLAY, 25566);
        NetworkDefinition network = NetworkDefinition.create("Network", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxyMember, backendMember));
        NetworkConfigMutation port = new NetworkConfigMutation(backend.getInstanceId(), "server.properties", ConfigurationFormat.PROPERTIES, "server-port", "", "25566", false, true, "Set Backend Port");
        NetworkConfigMutation onlineMode = new NetworkConfigMutation(backend.getInstanceId(), "server.properties", ConfigurationFormat.PROPERTIES, "online-mode", "", "false", false, true, "Delegate Authentication");
        NetworkReconciliationPlan plan = new NetworkReconciliationPlan("", network.networkId(), network.revision(), 0, List.of(port, onlineMode), List.of());
        NetworkConfigurationTransaction transaction = new NetworkConfigurationTransaction();

        NetworkPreparedPlan prepared = transaction.prepare(plan, List.of(proxy, backend)).join();
        List<NetworkJobDocument> documents = transaction.describe(prepared, network, List.of(proxy, backend));
        transaction.apply(prepared, network, List.of(proxy, backend), NetworkTransactionListener.NONE, documents).join();
        Map<NetworkConfigDocumentKey, NetworkDocumentSnapshot> originals = transaction.readOriginalDocuments(plan.planId(), documents, List.of(proxy, backend)).join();

        assertEquals(original, originals.get(new NetworkConfigDocumentKey(backend.getInstanceId(), "server.properties")).content());
    }

    private Instance instance(String name, Path directory, ModLoader loader) {
        Instance instance = new Instance(name, "1.21.10", directory.toString());
        instance.setServer(true);
        instance.setModLoader(loader);
        return instance;
    }
}
