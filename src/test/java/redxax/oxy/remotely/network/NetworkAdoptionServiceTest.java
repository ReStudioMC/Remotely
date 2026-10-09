package redxax.oxy.remotely.network;

import com.google.gson.Gson;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.BackendFactory;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.impl.LocalBackend;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rescreen.platform.Async;

import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAdoptionServiceTest {
    @TempDir
    Path temporaryDirectory;
    private final List<Instance> instances = new ArrayList<>();

    @AfterEach
    void closeBackends() {
        instances.forEach(Instance::closeBackend);
    }

    @Test
    void parsesAndMatchesVelocityRoutesWithoutReadingSecretContents() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        Instance lobby = instance("Lobby", ModLoader.PAPER, 25566);
        Instance survival = instance("Survival", ModLoader.PAPER, 25567);
        String velocity = """
                bind = "0.0.0.0:25565"
                online-mode = true
                player-info-forwarding-mode = "modern"
                forwarding-secret-file = "forwarding.secret"

                [servers]
                lobby = "127.0.0.1:25566"
                survival = "localhost:25567"
                try = ["lobby", "survival"]

                [forced-hosts]
                "play.example.com" = ["lobby", "survival"]
                "survival.example.com" = ["survival"]
                """;

        NetworkAdoptionReport report = new NetworkAdoptionService().parse(proxy, velocity, List.of(proxy, lobby, survival), List.of());

        assertTrue(report.canAdopt());
        assertEquals(ForwardingMode.MODERN, report.forwardingMode());
        assertEquals("forwarding.secret", report.secretFile());
        assertEquals(List.of("lobby", "survival"), report.fallbackRoutes());
        assertEquals(2, report.routes().stream().filter(NetworkAdoptionRoute::matched).count());
        assertEquals(List.of("survival"), report.forcedHosts().get("survival.example.com"));
    }

    @Test
    void blocksUnknownBackendsAndBrokenFallbacks() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        String velocity = """
                bind = "0.0.0.0:25565"
                player-info-forwarding-mode = "none"

                [servers]
                missing = "127.0.0.1:25570"
                try = ["missing", "unknown"]
                """;

        NetworkAdoptionReport report = new NetworkAdoptionService().parse(proxy, velocity, List.of(proxy), List.of());

        assertFalse(report.canAdopt());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("adoption.route.unknown")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("adoption.fallback.unknown")));
    }

    @Test
    void recognizesTheMultilineVelocityExampleAsAnEmptyFreshProxy() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        String velocity = """
                bind = "0.0.0.0:25565"
                player-info-forwarding-mode = "none"

                [servers]
                lobby = "127.0.0.1:30066"
                factions = "127.0.0.1:30067"
                minigames = "127.0.0.1:30068"
                try = [
                    "lobby"
                ]

                [forced-hosts]
                "lobby.example.com" = [
                    "lobby"
                ]
                """;

        NetworkAdoptionReport report = new NetworkAdoptionService().parse(proxy, velocity, List.of(proxy), List.of());

        assertTrue(report.routes().isEmpty());
        assertTrue(report.fallbackRoutes().isEmpty());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("adoption.stock-config")));
        assertFalse(report.issues().stream().anyMatch(issue -> issue.code().equals("adoption.route.unknown")));
    }

    @Test
    void scansADissolvedProxyAtItsRemoteRoot() {
        String velocity = "bind = \"0.0.0.0:25072\"\nplayer-info-forwarding-mode = \"none\"\n[servers]\ntry = []\n";
        BackendFactory.register("ADOPTION_VIRTUAL_ROOT", (config, owner) -> new LocalBackend(config, owner) {
            private final FileSystemProvider delegate = super.getFileSystem();
            private final FileSystemProvider fileSystem = (FileSystemProvider) Proxy.newProxyInstance(FileSystemProvider.class.getClassLoader(),
                new Class<?>[]{FileSystemProvider.class}, (provider, method, arguments) -> {
                    if (method.getName().equals("read")) {
                        Path path = (Path) arguments[0];
                        return path.equals(Path.of("velocity.toml")) ? CompletableFuture.completedFuture(velocity)
                            : CompletableFuture.failedFuture(new NoSuchFileException(path.toString()));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });

            @Override
            public FileSystemProvider getFileSystem() {
                return fileSystem;
            }
        });
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25072);
        proxy.setPath("");
        proxy.setBackendConfig(new BackendConfig("ADOPTION_VIRTUAL_ROOT", Map.of()));

        NetworkAdoptionReport report = JvmAsyncBridge.toFuture(new NetworkAdoptionService().scan(proxy, List.of(proxy), List.of())).join();

        assertEquals(25072, report.entryPort());
        assertTrue(report.routes().isEmpty());
        assertTrue(report.canAdopt());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("adoption.routes.empty")));
    }

    @Test
    void manuallyResolvesAnUnknownRouteWithoutChangingVelocityAddress() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        Instance backend = instance("Imported Survival", ModLoader.PAPER, 25590);
        String velocity = """
                bind = "0.0.0.0:25565"
                player-info-forwarding-mode = "none"

                [servers]
                survival = "10.0.0.20:25570"
                try = ["survival"]
                """;
        NetworkAdoptionService service = new NetworkAdoptionService();
        NetworkAdoptionReport scanned = service.parse(proxy, velocity, List.of(proxy), List.of());

        NetworkAdoptionReport resolved = service.resolveRoute(scanned, "survival", backend, List.of(proxy, backend), List.of());

        assertTrue(resolved.canAdopt());
        assertEquals(backend.getInstanceId(), resolved.routes().getFirst().instanceId());
        assertEquals("10.0.0.20", resolved.routes().getFirst().address());
        assertEquals(25570, resolved.routes().getFirst().port());
        assertFalse(resolved.issues().stream().anyMatch(issue -> issue.code().startsWith("adoption.route.")));
    }

    @Test
    void registersAnUnknownRouteAsExternallyManaged() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        String velocity = """
                bind = "0.0.0.0:25565"
                player-info-forwarding-mode = "none"

                [servers]
                minigames = "10.0.0.40:25580"
                try = ["minigames"]
                """;
        NetworkAdoptionService service = new NetworkAdoptionService();
        NetworkAdoptionReport scanned = service.parse(proxy, velocity, List.of(proxy), List.of());

        NetworkAdoptionReport resolved = service.resolveExternalRoute(scanned, "minigames");

        assertTrue(resolved.canAdopt());
        assertTrue(resolved.routes().getFirst().matched());
        assertEquals(NetworkMemberManagement.EXTERNAL, resolved.routes().getFirst().management());
        assertTrue(resolved.routes().getFirst().instanceId().startsWith("external:"));
        assertTrue(resolved.issues().stream().anyMatch(issue -> issue.code().equals("adoption.route.external")));
        assertFalse(resolved.issues().stream().anyMatch(NetworkValidationIssue::blocksPersistence));
    }

    @Test
    void requiresExternalMappingWhenBackendCannotWriteConfigurationAtomically() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        Instance backend = instance("Panel Backend", ModLoader.PAPER, 25566);
        BackendFactory.register("ADOPTION_NON_ATOMIC", (config, owner) -> new LocalBackend(config, owner) {
            private final FileSystemProvider delegate = super.getFileSystem();
            private final FileSystemProvider fileSystem = (FileSystemProvider) Proxy.newProxyInstance(FileSystemProvider.class.getClassLoader(),
                new Class<?>[]{FileSystemProvider.class}, (provider, method, arguments) -> {
                    if (method.getName().equals("supportsAtomicWrites")) return false;
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });

            @Override
            public FileSystemProvider getFileSystem() {
                return fileSystem;
            }
        });
        backend.setBackendConfig(new BackendConfig("ADOPTION_NON_ATOMIC", Map.of("host", "10.0.0.20")));
        String velocity = """
                bind = "0.0.0.0:25565"
                player-info-forwarding-mode = "none"

                [servers]
                minigames = "10.0.0.20:25566"
                try = ["minigames"]
                """;
        NetworkAdoptionService service = new NetworkAdoptionService();
        NetworkAdoptionReport scanned = service.parse(proxy, velocity, List.of(proxy, backend), List.of());

        assertFalse(scanned.canAdopt());
        assertFalse(scanned.routes().getFirst().matched());
        assertThrows(IllegalArgumentException.class, () -> service.resolveRoute(scanned, "minigames", backend, List.of(proxy, backend), List.of()));

        NetworkAdoptionReport external = service.resolveExternalRoute(scanned, "minigames");

        assertTrue(external.canAdopt());
        assertEquals(NetworkMemberManagement.EXTERNAL, external.routes().getFirst().management());
        assertEquals("10.0.0.20", external.routes().getFirst().address());
        assertEquals(25566, external.routes().getFirst().port());
    }

    @Test
    void parsesLegacyPrioritiesAndForcedHostsForVelocityMigration() {
        Instance proxy = instance("Waterfall", ModLoader.WATERFALL, 25565);
        Instance lobby = instance("Lobby", ModLoader.PAPER, 25566);
        Instance survival = instance("Survival", ModLoader.PAPER, 25567);
        String legacy = """
                online_mode: true
                ip_forward: true
                listeners:
                  - host: 0.0.0.0:25565
                    priorities:
                      - lobby
                      - survival
                    forced_hosts:
                      survival.example.com: survival
                servers:
                  lobby:
                    address: 127.0.0.1:25566
                    restricted: false
                  survival:
                    address: localhost:25567
                    restricted: false
                """;

        NetworkAdoptionReport report = new NetworkAdoptionService().parseLegacy(proxy, legacy, List.of(proxy, lobby, survival), List.of());

        assertTrue(report.canAdopt());
        assertEquals(ForwardingMode.LEGACY, report.forwardingMode());
        assertEquals(List.of("lobby", "survival"), report.fallbackRoutes());
        assertEquals(List.of("survival"), report.forcedHosts().get("survival.example.com"));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("migration.plugins.review")));
    }

    @Test
    void importedNetworkCanEnableReSyncSafely() throws Exception {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        Instance lobby = instance("Lobby", ModLoader.PAPER, 25566);
        NetworkAdoptionReport report = new NetworkAdoptionReport(proxy.getInstanceId(), "0.0.0.0", 25565, true, ForwardingMode.MODERN, "forwarding.secret",
            List.of(new NetworkAdoptionRoute("lobby", "127.0.0.1", 25566, lobby.getInstanceId(), "Matched Lobby")), List.of("lobby"), Map.of(), List.of());
        DesktopNetworkManager manager = new DesktopNetworkManager(temporaryDirectory);
        try {
            Method build = DesktopNetworkManager.class.getDeclaredMethod("buildAdoptedNetwork", String.class, NetworkAdoptionReport.class, Map.class, String.class, Map.class);
            build.setAccessible(true);
            NetworkDefinition network = (NetworkDefinition) build.invoke(manager, "Imported", report,
                Map.of(proxy.getInstanceId(), proxy, lobby.getInstanceId(), lobby), "", Map.of());

            assertFalse(network.runtime().enabled());
            assertFalse(network.featureEnabled(NetworkDefinition.FEATURE_RUNTIME));
            assertFalse(network.featureEnabled(NetworkDefinition.FEATURE_SHARED_CHAT));
            assertTrue(network.members().stream().filter(NetworkMember::isManaged).noneMatch(NetworkMember::resyncEnabled));
            NetworkDiscoveryResult discovery = new NetworkDiscoveryService(new NetworkPortAllocator()).discover(network, List.of(proxy, lobby), List.of(network), List.of());
            NetworkReconciliationPlan reconciliation = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery), secrets());
            assertTrue(reconciliation.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId())
                && mutation.key().equals("network.enabled") && mutation.desiredValue().equals("false")));
            assertTrue(reconciliation.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(lobby.getInstanceId())
                && mutation.key().equals("network.enabled") && mutation.desiredValue().equals("false")));
            NetworkDefinition enabled = manager.buildReSyncCandidate(network, List.of(lobby.getInstanceId()), proxy, List.of());
            assertTrue(enabled.runtime().enabled());
            assertTrue(enabled.featureEnabled(NetworkDefinition.FEATURE_RUNTIME));
            assertTrue(enabled.featureEnabled(NetworkDefinition.FEATURE_SHARED_CHAT));
            assertTrue(enabled.members().stream().filter(NetworkMember::isManaged).allMatch(NetworkMember::resyncEnabled));
            NetworkDiscoveryResult enabledDiscovery = new NetworkDiscoveryService(new NetworkPortAllocator()).discover(enabled, List.of(proxy, lobby), List.of(enabled), List.of());
            NetworkReconciliationPlan enabledReconciliation = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(enabledDiscovery), secrets());
            assertTrue(enabledReconciliation.mutations().stream().filter(mutation -> mutation.key().equals("network.enabled")).allMatch(mutation -> mutation.desiredValue().equals("true")));
        } finally {
            manager.close();
        }
    }

    @Test
    void networkCreationMatchesReSyncSelection() throws Exception {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        Instance lobby = instance("Lobby", ModLoader.PAPER, 25566);
        DesktopNetworkManager manager = new DesktopNetworkManager(temporaryDirectory);
        try {
            Method build = DesktopNetworkManager.class.getDeclaredMethod("buildCreationCandidate", NetworkCreationRequest.class, Collection.class, Collection.class, String.class, Map.class);
            build.setAccessible(true);
            NetworkCreationRequest disabledRequest = new NetworkCreationRequest("Without ReSync", proxy.getInstanceId(), 25565,
                List.of(new NetworkCreationMember(lobby.getInstanceId(), "lobby", NetworkMemberRole.LOBBY, "", 25566, 0, false)), false);
            NetworkDefinition disabled = (NetworkDefinition) build.invoke(manager, disabledRequest, List.of(proxy, lobby), List.of(), "secret", Map.of());
            assertFalse(disabled.runtime().enabled());
            assertFalse(disabled.featureEnabled(NetworkDefinition.FEATURE_RUNTIME));
            assertTrue(disabled.members().stream().filter(NetworkMember::isManaged).noneMatch(NetworkMember::resyncEnabled));

            NetworkCreationRequest enabledRequest = new NetworkCreationRequest("With ReSync", proxy.getInstanceId(), 25565,
                List.of(new NetworkCreationMember(lobby.getInstanceId(), "lobby", NetworkMemberRole.LOBBY, "", 25566, 0, true)), false);
            NetworkDefinition enabled = (NetworkDefinition) build.invoke(manager, enabledRequest, List.of(proxy, lobby), List.of(), "secret", Map.of());
            assertTrue(enabled.runtime().enabled());
            assertTrue(enabled.featureEnabled(NetworkDefinition.FEATURE_RUNTIME));
            assertTrue(enabled.members().stream().filter(NetworkMember::isManaged).allMatch(NetworkMember::resyncEnabled));
        } finally {
            manager.close();
        }
    }

    @Test
    void adoptionRollbackWaitsForBindingPersistence() throws Exception {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        Instance lobby = instance("Lobby", ModLoader.PAPER, 25566);
        NetworkAdoptionReport report = new NetworkAdoptionReport(proxy.getInstanceId(), "0.0.0.0", 25565, true, ForwardingMode.MODERN, "forwarding.secret",
                List.of(new NetworkAdoptionRoute("lobby", "127.0.0.1", 25566, lobby.getInstanceId(), "Matched Lobby")), List.of("lobby"), Map.of(), List.of());
        DesktopNetworkManager manager = new DesktopNetworkManager(temporaryDirectory);
        try {
            Method build = DesktopNetworkManager.class.getDeclaredMethod("buildAdoptedNetwork", String.class, NetworkAdoptionReport.class, Map.class, String.class, Map.class);
            build.setAccessible(true);
            NetworkDefinition network = (NetworkDefinition) build.invoke(manager, "Imported", report,
                    Map.of(proxy.getInstanceId(), proxy, lobby.getInstanceId(), lobby), "secret-reference", Map.of());
            manager.save(network);
            DelayedSaveInstance instance = new DelayedSaveInstance();
            instance.bindNetwork(network.networkId(), "lobby", network.revision());

            CompletableFuture<NetworkDefinition> rollback = JvmAsyncBridge.toFuture(manager.rollbackAdoption(network, List.of(new DesktopNetworkManager.InstanceBinding(instance, "", "", 0)), null,
                    new IllegalStateException("Binding Save Failed")));

            assertFalse(rollback.isDone());
            instance.saved.complete(null);
            assertThrows(CompletionException.class, rollback::join);
            assertFalse(instance.isNetworkMember());
            assertTrue(manager.getNetwork(network.networkId()).isEmpty());
        } finally {
            manager.close();
        }
    }

    @Test
    void creationFailureRestoresAndPersistsExistingExternalBinding() throws Exception {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY, 25565);
        proxy.setPath(Files.createDirectories(temporaryDirectory.resolve("proxy")).toString());
        FailedBindingInstance panel = configure(new FailedBindingInstance(Files.createDirectories(temporaryDirectory.resolve("panel"))), ModLoader.PAPER, 25566);
        panel.bindNetwork("previous-network", "previous-node", 7);
        panel.save().join();
        NetworkCreationRequest request = new NetworkCreationRequest("Imported Panel", proxy.getInstanceId(), 25565, List.of(
            new NetworkCreationMember(panel.getInstanceId(), "panel", NetworkMemberRole.LOBBY, "10.0.0.20", 25566, 0, false, NetworkMemberManagement.EXTERNAL),
            new NetworkCreationMember("external:address-only", "external", NetworkMemberRole.GAMEPLAY, "10.0.0.30", 25567, 0, false, NetworkMemberManagement.EXTERNAL)), true);
        DesktopNetworkManager manager = new DesktopNetworkManager(temporaryDirectory);
        try {
            List<Instance> participants = List.of(proxy, panel);
            Method build = DesktopNetworkManager.class.getDeclaredMethod("buildCreationCandidate", NetworkCreationRequest.class, Collection.class, Collection.class, String.class, Map.class);
            build.setAccessible(true);
            NetworkDefinition candidate = (NetworkDefinition) build.invoke(manager, request, participants, List.of(), "secret", Map.of());
            Method capture = DesktopNetworkManager.class.getDeclaredMethod("captureCreationBindings", NetworkDefinition.class, Collection.class);
            capture.setAccessible(true);
            Gson gson = new Gson();
            NetworkJob job = new NetworkJob(NetworkJob.CURRENT_SCHEMA_VERSION, UUID.randomUUID().toString(), candidate.networkId(), candidate.revision(),
                NetworkJobType.QUICK_CREATE, NetworkJobStatus.SUCCEEDED, "Test", 0, 0, 1, "Configuration Applied",
                Map.of("network", gson.toJson(candidate), "bindings", gson.toJson(capture.invoke(manager, candidate, participants)), "creationMetadataPending", "true"),
                List.of(new NetworkJobDocument(new NetworkConfigDocumentKey(proxy.getInstanceId(), "velocity.toml"), 0, false, "", "", NetworkJobDocumentState.UNCHANGED)), List.of());
            new NetworkJobRepository(DesktopRemotelyPaths.dataDir(temporaryDirectory)).save(job);
            manager.getJobManager().reload();
            panel.failNextSave = true;
            Method finalize = DesktopNetworkManager.class.getDeclaredMethod("finalizeCreation", NetworkJob.class, Collection.class);
            finalize.setAccessible(true);
            CompletableFuture<?> completion = JvmAsyncBridge.toFuture((Async<?>) finalize.invoke(manager, job, participants));

            panel.rollbackStarted.get(5, TimeUnit.SECONDS);
            assertFalse(completion.isDone());
            panel.rollbackReady.complete(null);
            CompletionException failure = assertThrows(CompletionException.class, completion::join);

            assertEquals("Binding Save Failed", failure.getCause().getMessage());
            assertEquals("previous-network", panel.getNetworkId());
            assertEquals("previous-node", panel.getNetworkNodeId());
            assertEquals(7, panel.getNetworkRevision());
            Properties persisted = new Properties();
            try (Reader reader = Files.newBufferedReader(Path.of(panel.getPath(), "instance.properties"))) {
                persisted.load(reader);
            }
            assertEquals("previous-network", persisted.getProperty("network.id"));
            assertEquals("previous-node", persisted.getProperty("network.nodeId"));
            assertEquals("7", persisted.getProperty("network.revision"));
            assertFalse(proxy.isNetworkMember());
            assertTrue(manager.getNetwork(candidate.networkId()).isEmpty());
            assertEquals(NetworkJobStatus.ROLLED_BACK, manager.getJobManager().getJob(job.jobId()).orElseThrow().status());
        } finally {
            panel.rollbackReady.complete(null);
            manager.close();
        }
    }

    private Instance instance(String name, ModLoader loader, int port) {
        return configure(new Instance(name, "1.21.4", name.toLowerCase()), loader, port);
    }

    private <T extends Instance> T configure(T instance, ModLoader loader, int port) {
        BackendFactory.register("LOCAL", LocalBackend::new);
        instance.setBackendConfig(new BackendConfig("LOCAL", Map.of()));
        instance.setServer(true);
        instance.setModLoader(loader);
        instance.getServerProperties().setProperty("server-port", String.valueOf(port));
        instances.add(instance);
        return instance;
    }

    private static final class FailedBindingInstance extends Instance {
        private final CompletableFuture<Void> rollbackStarted = new CompletableFuture<>();
        private final CompletableFuture<Void> rollbackReady = new CompletableFuture<>();
        private boolean failNextSave;
        private boolean failed;

        private FailedBindingInstance(Path path) {
            super("Panel Backend", "1.21.4", path.toString());
        }

        @Override
        public CompletableFuture<Void> save() {
            if (failNextSave) {
                failNextSave = false;
                failed = true;
                return CompletableFuture.failedFuture(new IllegalStateException("Binding Save Failed"));
            }
            if (!failed) return super.save();
            rollbackStarted.complete(null);
            return rollbackReady.thenCompose(ignored -> super.save());
        }
    }

    private static final class DelayedSaveInstance extends Instance {
        private final CompletableFuture<Void> saved = new CompletableFuture<>();

        private DelayedSaveInstance() {
            super("Lobby", "1.21.4", "lobby");
        }

        @Override
        public CompletableFuture<Void> save() {
            return saved;
        }
    }

    private NetworkSecretStore secrets() {
        return new NetworkSecretStore() {
            @Override
            public String resolveForwardingSecret(String reference) {
                return "forwarding-secret";
            }

            @Override
            public String getOrCreateEnrollmentToken(String networkId, String nodeId) {
                return "enrollment-token";
            }
        };
    }
}
