package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkDetachPlannerTest {
    @Test
    void refusesToDetachTheLastBackend() {
        Instance proxy = new Instance("Proxy", "1.21.4", "proxy");
        proxy.setServer(true);
        proxy.setModLoader(ModLoader.VELOCITY);
        Instance lobby = new Instance("Lobby", "1.21.4", "lobby");
        lobby.setServer(true);
        lobby.setModLoader(ModLoader.PAPER);
        NetworkMember proxyMember = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkMember lobbyMember = NetworkMember.backend(lobby.getInstanceId(), "lobby", NetworkMemberRole.LOBBY, 25566);
        NetworkDefinition network = NetworkDefinition.create("Network", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxyMember, lobbyMember));
        NetworkDiscoveryResult discovery = new NetworkDiscoveryResult(network, Map.of(proxy.getInstanceId(), proxy, lobby.getInstanceId(), lobby), List.of(), List.of(), List.of());

        NetworkReconciliationPlan plan = new NetworkDetachPlanner().plan(DesktopNetworkPlanInput.from(discovery), lobby.getInstanceId());

        assertFalse(plan.canApply());
        assertTrue(plan.mutations().isEmpty());
        assertTrue(plan.issues().stream().anyMatch(issue -> issue.code().equals("detach.last-backend")));
    }

    @Test
    void removesRoutesAndRestoresExactPreAttachBackendSettings() {
        Instance proxy = new Instance("Proxy", "1.21.4", "proxy");
        proxy.setServer(true);
        proxy.setModLoader(ModLoader.VELOCITY);
        Instance lobby = new Instance("Lobby", "1.21.4", "lobby");
        lobby.setServer(true);
        lobby.setModLoader(ModLoader.PAPER);
        Instance survival = new Instance("Survival", "1.21.4", "survival");
        survival.setServer(true);
        survival.setModLoader(ModLoader.PAPER);
        NetworkMember proxyMember = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkMember lobbyMember = NetworkMember.backend(lobby.getInstanceId(), "lobby", NetworkMemberRole.LOBBY, 25566);
        NetworkMember survivalMember = NetworkMember.backend(survival.getInstanceId(), "survival", NetworkMemberRole.GAMEPLAY, 25567);
        NetworkDefinition base = NetworkDefinition.create("Network", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxyMember, lobbyMember, survivalMember));
        RoutingGroup fallback = new RoutingGroup("fallback", "Fallback", RoutingStrategy.ORDERED, List.of(lobbyMember.nodeId(), survivalMember.nodeId()), Map.of(), "", Set.of("play.example.com"), "");
        NetworkDefinition network = new NetworkDefinition(base.schemaVersion(), base.networkId(), base.name(), base.revision(), base.proxyInstanceId(), base.desiredState(), base.forwarding(), base.entryPoints(), base.members(), List.of(fallback), List.of(), base.runtime(), base.features(), base.createdAt(), base.updatedAt());
        NetworkDiscoveryResult discovery = new NetworkDiscoveryResult(network, Map.of(proxy.getInstanceId(), proxy, lobby.getInstanceId(), lobby, survival.getInstanceId(), survival), List.of(), List.of(), List.of());

        NetworkMemberRestorePoint restorePoint = new NetworkMemberRestorePoint(1, survival.getInstanceId(), survivalMember.nodeId(), 1, List.of(
            new NetworkRestoreEntry("server.properties", ConfigurationFormat.PROPERTIES, "server-port", true, "25565", false),
            new NetworkRestoreEntry("server.properties", ConfigurationFormat.PROPERTIES, "online-mode", true, "false", false),
            new NetworkRestoreEntry("server.properties", ConfigurationFormat.PROPERTIES, "server-ip", true, "192.168.1.20", false),
            new NetworkRestoreEntry("spigot.yml", ConfigurationFormat.YAML, "settings.bungeecord", true, "true", false),
            new NetworkRestoreEntry("config/paper-global.yml", ConfigurationFormat.YAML, "proxies.velocity.enabled", true, "false", false),
            new NetworkRestoreEntry("plugins/ReSync/resync.properties", ConfigurationFormat.PROPERTIES, "network.enabled", true, "true", false),
            new NetworkRestoreEntry("plugins/ReSync/resync.properties", ConfigurationFormat.PROPERTIES, "network.id", false, "", false),
            new NetworkRestoreEntry("plugins/ReSync/network/node.credential", ConfigurationFormat.SECRET, "content", true, "credential-reference", true)
        ));
        NetworkSecretStore secrets = new NetworkSecretStore() {
            @Override
            public String resolveRestoreValue(String reference) {
                return reference.equals("credential-reference") ? "original-credential" : "";
            }
        };

        NetworkReconciliationPlan plan = new NetworkDetachPlanner().plan(DesktopNetworkPlanInput.from(discovery), survival.getInstanceId(), restorePoint,
                entry -> entry.sensitive() ? secrets.resolveRestoreValue(entry.value()) : entry.value());

        assertEquals(NetworkPlanStrategy.DETACH, plan.strategy());
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().equals("servers.survival") && mutation.action() == NetworkMutationAction.REMOVE));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(survival.getInstanceId()) && mutation.key().equals("server-port") && mutation.desiredValue().equals("25565")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(survival.getInstanceId()) && mutation.key().equals("online-mode") && mutation.desiredValue().equals("false")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(survival.getInstanceId()) && mutation.key().equals("server-ip") && mutation.desiredValue().equals("192.168.1.20")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(survival.getInstanceId()) && mutation.key().equals("settings.bungeecord") && mutation.desiredValue().equals("true")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(survival.getInstanceId()) && mutation.key().equals("proxies.velocity.enabled") && mutation.desiredValue().equals("false")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(survival.getInstanceId()) && mutation.key().equals("network.enabled") && mutation.desiredValue().equals("true")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.id") && mutation.action() == NetworkMutationAction.REMOVE));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().equals("nodes") && !mutation.desiredValue().contains(survivalMember.nodeId())));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().equals("node." + survivalMember.nodeId() + ".enrollment-token-hash") && mutation.action() == NetworkMutationAction.REMOVE));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().equals("routes") && mutation.desiredValue().equals("lobby")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().equals("route.survival.port") && mutation.action() == NetworkMutationAction.REMOVE));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(survival.getInstanceId()) && mutation.path().equals("plugins/ReSync/network/node.credential") && mutation.sensitive() && mutation.desiredValue().equals("original-credential")));
    }

    @Test
    void warnsAndDetachesManagedServerWithoutAnExactRestorePoint() {
        Instance proxy = new Instance("Proxy", "1.21.4", "proxy");
        proxy.setServer(true);
        proxy.setModLoader(ModLoader.VELOCITY);
        Instance lobby = new Instance("Lobby", "1.21.4", "lobby");
        lobby.setServer(true);
        lobby.setModLoader(ModLoader.PAPER);
        Instance survival = new Instance("Survival", "1.21.4", "survival");
        survival.setServer(true);
        survival.setModLoader(ModLoader.PAPER);
        NetworkMember proxyMember = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkMember lobbyMember = NetworkMember.backend(lobby.getInstanceId(), "lobby", NetworkMemberRole.LOBBY, 25566);
        NetworkMember survivalMember = NetworkMember.backend(survival.getInstanceId(), "survival", NetworkMemberRole.GAMEPLAY, 25567);
        NetworkDefinition network = NetworkDefinition.create("Network", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxyMember, lobbyMember, survivalMember));
        NetworkDiscoveryResult discovery = new NetworkDiscoveryResult(network, Map.of(proxy.getInstanceId(), proxy, lobby.getInstanceId(), lobby, survival.getInstanceId(), survival), List.of(), List.of(), List.of());

        NetworkReconciliationPlan plan = new NetworkDetachPlanner().plan(DesktopNetworkPlanInput.from(discovery), survival.getInstanceId());

        assertTrue(plan.canApply());
        assertTrue(plan.issues().stream().anyMatch(issue -> issue.code().equals("detach.restore-point.missing") && issue.severity() == NetworkValidationIssue.Severity.WARNING));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().equals("servers.survival") && mutation.action() == NetworkMutationAction.REMOVE));
        assertTrue(plan.mutations().stream().noneMatch(mutation -> mutation.instanceId().equals(survival.getInstanceId())));
    }

    @Test
    void detachesExternalRoutesWithoutWritingBackendFiles() {
        Instance proxy = new Instance("Proxy", "1.21.4", "proxy");
        proxy.setServer(true);
        proxy.setModLoader(ModLoader.VELOCITY);
        Instance lobby = new Instance("Lobby", "1.21.4", "lobby");
        lobby.setServer(true);
        lobby.setModLoader(ModLoader.PAPER);
        NetworkMember proxyMember = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkMember lobbyMember = NetworkMember.backend(lobby.getInstanceId(), "lobby", NetworkMemberRole.LOBBY, 25566);
        NetworkMember external = new NetworkMember("external:" + UUID.randomUUID(), UUID.randomUUID().toString(), "minigames", NetworkMemberRole.GAMEPLAY, "external:10.0.0.40", "10.0.0.40", 25580, 100, false, NetworkMemberManagement.EXTERNAL);
        NetworkDefinition base = NetworkDefinition.create("Network", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxyMember, lobbyMember, external));
        RoutingGroup fallback = new RoutingGroup("fallback", "Fallback", RoutingStrategy.ORDERED, List.of(lobbyMember.nodeId(), external.nodeId()), Map.of(), "", Set.of(), "");
        NetworkDefinition network = new NetworkDefinition(base.schemaVersion(), base.networkId(), base.name(), base.revision(), base.proxyInstanceId(), base.desiredState(), base.forwarding(), base.entryPoints(), base.members(), List.of(fallback), List.of(), base.runtime(), base.features(), base.createdAt(), base.updatedAt());
        NetworkDiscoveryResult discovery = new NetworkDiscoveryResult(network, Map.of(proxy.getInstanceId(), proxy, lobby.getInstanceId(), lobby), List.of(), List.of(), List.of());

        NetworkReconciliationPlan plan = new NetworkDetachPlanner().plan(DesktopNetworkPlanInput.from(discovery), external.instanceId());

        assertTrue(plan.canApply());
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().equals("servers.minigames") && mutation.action() == NetworkMutationAction.REMOVE));
        assertTrue(plan.mutations().stream().noneMatch(mutation -> mutation.instanceId().equals(external.instanceId())));
    }
}
