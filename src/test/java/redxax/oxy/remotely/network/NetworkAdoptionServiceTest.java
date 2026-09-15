package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAdoptionServiceTest {
    @TempDir
    Path temporaryDirectory;

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

    private Instance instance(String name, ModLoader loader, int port) {
        Instance instance = new Instance(name, "1.21.4", name.toLowerCase());
        instance.setServer(true);
        instance.setModLoader(loader);
        instance.getServerProperties().setProperty("server-port", String.valueOf(port));
        return instance;
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
