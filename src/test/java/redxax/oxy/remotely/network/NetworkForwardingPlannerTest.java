package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkForwardingPlannerTest {
    @Test
    void configuresFabricProxyLiteWithoutWritingPaperFiles() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Fabric", ModLoader.FABRIC);
        backend.getSettings().setProperty("network.forwarding.bridge", "fabricproxy-lite");
        NetworkDefinition network = network(proxy, backend);
        NetworkDiscoveryResult discovery = discovery(network, proxy, backend);

        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery), secrets());

        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.path().equals("config/FabricProxy-Lite.toml") && mutation.key().equals("secret")));
        assertFalse(plan.mutations().stream().anyMatch(mutation -> mutation.path().contains("paper")));
    }

    @Test
    void configuresProxyCompatibleForgeWithoutWritingPaperFiles() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Forge", ModLoader.NEOFORGE);
        backend.getSettings().setProperty("network.forwarding.bridge", "proxy-compatible-forge");
        NetworkDefinition network = network(proxy, backend);
        NetworkDiscoveryResult discovery = discovery(network, proxy, backend);

        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery), secrets());

        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.path().equals("config/proxy-compatible-forge.toml") && mutation.key().equals("forwarding.enabled") && mutation.desiredValue().equals("true")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.path().equals("config/proxy-compatible-forge.toml") && mutation.key().equals("forwarding.secret") && mutation.sensitive()));
        assertFalse(plan.mutations().stream().anyMatch(mutation -> mutation.path().contains("paper")));
    }

    @Test
    void blocksUnknownBridgeInsteadOfWritingPaperFiles() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Vanilla", ModLoader.VANILLA);
        backend.getSettings().setProperty("network.forwarding.bridge", "velocity-modern");
        NetworkDefinition network = network(proxy, backend);
        NetworkDiscoveryResult discovery = discovery(network, proxy, backend);

        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery), secrets());

        assertTrue(plan.issues().stream().anyMatch(issue -> issue.code().equals("backend.forwarding.adapter.unavailable")));
        assertFalse(plan.mutations().stream().anyMatch(mutation -> mutation.path().contains("paper")));
    }

    @Test
    void dissolvingForgeNetworkDisablesItsBridge() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Forge", ModLoader.FORGE);
        backend.getSettings().setProperty("network.forwarding.bridge", "velocity-modern");
        NetworkDefinition network = network(proxy, backend);
        NetworkDiscoveryResult discovery = discovery(network, proxy, backend);

        NetworkReconciliationPlan plan = new NetworkDetachPlanner().planDissolve(DesktopNetworkPlanInput.from(discovery));

        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.path().equals("config/proxy-compatible-forge.toml") && mutation.key().equals("forwarding.enabled") && mutation.desiredValue().equals("false")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(backend.getInstanceId()) && mutation.key().equals("online-mode") && mutation.desiredValue().equals("true")));
    }

    @Test
    void writesFallbackOrderAndForcedHostsFromRoutingGroups() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Lobby", ModLoader.PAPER);
        NetworkDefinition base = network(proxy, backend);
        NetworkMember backendMember = base.members().stream().filter(member -> !member.isProxy()).findFirst().orElseThrow();
        RoutingGroup fallback = new RoutingGroup("fallback", "Fallback", RoutingStrategy.ORDERED, List.of(backendMember.nodeId()), Map.of(), "", Set.of("play.example.com"), "");
        NetworkDefinition routed = base.nextRevision(base.members(), List.of(fallback), base.syncRealms(), base.desiredState());

        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery(routed, proxy, backend)), secrets());

        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("servers.try") && mutation.desiredValue().contains("backend")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("forced-hosts.play.example.com") && mutation.desiredValue().contains("backend")));
    }

    @Test
    void configuresEmbeddedHubAndOneTimeBackendEnrollment() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Lobby", ModLoader.PAPER);
        NetworkDefinition network = network(proxy, backend);

        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery(network, proxy, backend)), secrets());

        assertTrue(network.runtime().enabled());
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.path().equals("plugins/resyncvelocity/network.properties") && mutation.key().equals("network.enabled") && mutation.desiredValue().equals("true")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().endsWith("enrollment-token-hash") && mutation.sensitive() && !mutation.desiredValue().equals("enrollment-token")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().equals("routes") && mutation.desiredValue().equals("backend")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().equals("route.backend.port") && mutation.desiredValue().equals("25566")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().endsWith(".capabilities") && mutation.desiredValue().equals("observe,routing,operate,command,broadcast,state-admin,events")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(proxy.getInstanceId()) && mutation.key().endsWith(".capabilities") && mutation.desiredValue().equals("presence,observe,variables,events,transfer,operate,command,broadcast,resources")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(backend.getInstanceId()) && mutation.key().equals("network.hub-url") && mutation.desiredValue().equals(network.runtime().hubUrl())));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(backend.getInstanceId()) && mutation.key().equals("network.enrollment-token") && mutation.sensitive() && mutation.desiredValue().equals("enrollment-token")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(backend.getInstanceId()) && mutation.key().equals("network.chat.enabled") && mutation.desiredValue().equals("true")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.instanceId().equals(backend.getInstanceId()) && mutation.key().equals("network.resources.enabled") && mutation.desiredValue().equals("true")));
    }

    @Test
    void writesSharedChatAndResourcePolicy() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Lobby", ModLoader.PAPER);
        Instance survival = instance("Survival", ModLoader.PAPER);
        NetworkDefinition initial = network(proxy, backend);
        NetworkMember survivalMember = NetworkMember.backend(survival.getInstanceId(), "survival", NetworkMemberRole.GAMEPLAY, 25567);
        NetworkDefinition base = initial.nextRevision(List.of(initial.members().getFirst(), initial.members().get(1), survivalMember), initial.routingGroups(), initial.syncRealms(), initial.desiredState());
        NetworkPathSync sync = new NetworkPathSync("luckperms", "LuckPerms", true, Set.of(base.members().get(1).nodeId(), survivalMember.nodeId()), Set.of("server.properties", "plugins/LuckPerms"), NetworkSharedDataPolicy.ConflictPolicy.LOCAL_WINS, List.of("lp reload"));
        NetworkSharedDataPolicy policy = new NetworkSharedDataPolicy(NetworkSharedDataPolicy.SelectionMode.ALLOW_LIST, Set.of("global", "staff"), 900_000, NetworkSharedDataPolicy.SelectionMode.DENY_LIST, Set.of("world", "secret"), List.of(sync), NetworkSharedDataPolicy.ConflictPolicy.LOCAL_WINS, 262_144);
        Map<String, Boolean> features = new LinkedHashMap<>(base.features());
        features.put(NetworkDefinition.FEATURE_PATH_SYNC, true);
        NetworkDefinition network = base.withSharedData(base.syncRealms(), features, policy);

        NetworkDiscoveryResult discovery = new NetworkDiscoveryResult(network, Map.of(proxy.getInstanceId(), proxy, backend.getInstanceId(), backend, survival.getInstanceId(), survival), List.of(), List.of(), List.of());
        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery), secrets());

        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.chat.channel-mode") && mutation.desiredValue().equals("ALLOW_LIST")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.chat.channels") && Set.of(mutation.desiredValue().split(",")).equals(Set.of("global", "staff"))));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.chat.retention-millis") && mutation.desiredValue().equals("900000")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.resources.type-mode") && mutation.desiredValue().equals("DENY_LIST")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.resources.types") && Set.of(mutation.desiredValue().split(",")).equals(Set.of("world", "secret"))));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.resources.conflict-policy") && mutation.desiredValue().equals("LOCAL_WINS")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.path-sync.ids") && mutation.desiredValue().equals("luckperms")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.path-sync.luckperms.enabled") && mutation.desiredValue().equals("true")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.path-sync.luckperms.paths") && Set.of(mutation.desiredValue().split(",")).equals(Set.of("server.properties", "plugins/LuckPerms"))));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.path-sync.luckperms.command.0") && mutation.desiredValue().equals("lp reload")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("network.maximum-payload-bytes") && mutation.desiredValue().equals("262144")));
    }

    @Test
    void blocksCrossHostRuntimeUntilWssTransportIsProvisioned() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Lobby", ModLoader.PAPER);
        NetworkDefinition local = network(proxy, backend);
        NetworkMember currentBackend = local.members().get(1);
        NetworkMember remoteBackend = new NetworkMember(currentBackend.instanceId(), currentBackend.nodeId(), currentBackend.routeName(), currentBackend.role(), "ssh:remote", "10.0.0.20", currentBackend.port(), currentBackend.capacity(), true);
        NetworkRuntimePolicy pending = new NetworkRuntimePolicy(true, "10.0.0.10", local.runtime().hubPort(), NetworkTransportSecurity.WSS, false);
        NetworkDefinition network = new NetworkDefinition(local.schemaVersion(), local.networkId(), local.name(), local.revision(), local.proxyInstanceId(), local.desiredState(), local.forwarding(), local.entryPoints(), List.of(local.members().getFirst(), remoteBackend), local.routingGroups(), local.syncRealms(), pending, local.features(), local.createdAt(), local.updatedAt());

        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery(network, proxy, backend)), secrets());

        assertTrue(plan.issues().stream().anyMatch(issue -> issue.code().equals("resync.transport.unavailable") && issue.blocksPersistence()));
    }

    @Test
    void configuresStateAuthorityOnlyForMembersOfTheSameRealm() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance first = instance("Survival One", ModLoader.PAPER);
        Instance second = instance("Survival Two", ModLoader.PAPER);
        NetworkMember proxyMember = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkMember firstMember = NetworkMember.backend(first.getInstanceId(), "survival-one", NetworkMemberRole.GAMEPLAY, 25566);
        NetworkMember secondMember = NetworkMember.backend(second.getInstanceId(), "survival-two", NetworkMemberRole.GAMEPLAY, 25567);
        NetworkDefinition base = NetworkDefinition.create("Network", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxyMember, firstMember, secondMember));
        SyncRealm realm = new SyncRealm("survival", "Survival", Set.of(firstMember.nodeId(), secondMember.nodeId()), Set.of(SyncDataFamily.INVENTORY, SyncDataFamily.ENDER_CHEST, SyncDataFamily.EXPERIENCE, SyncDataFamily.VITALS, SyncDataFamily.EFFECTS, SyncDataFamily.PLAYER_STATE), SyncLocationPolicy.NEVER, Set.of(), 20, 30);
        NetworkDefinition network = base.nextRevision(base.members(), base.routingGroups(), List.of(realm), base.desiredState());
        NetworkDiscoveryResult discovery = new NetworkDiscoveryResult(network, Map.of(proxy.getInstanceId(), proxy, first.getInstanceId(), first, second.getInstanceId(), second), List.of(), List.of(), List.of());

        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery), secrets());

        assertTrue(plan.mutations().stream().filter(mutation -> mutation.key().endsWith(".capabilities")).filter(mutation -> mutation.desiredValue().contains("state:survival")).count() == 2);
        assertTrue(plan.mutations().stream().filter(mutation -> mutation.key().equals("network.transfer.profile") && mutation.desiredValue().equals("CUSTOM")).count() == 2);
        assertTrue(plan.mutations().stream().filter(mutation -> mutation.key().equals("network.transfer.family.inventory") && mutation.desiredValue().equals("true")).count() == 2);
        assertTrue(plan.issues().stream().noneMatch(issue -> issue.code().equals("realm.family.runtime.unsupported")));
    }

    @Test
    void propagatesRotatedSecretThroughProxyAndBackendDocuments() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Lobby", ModLoader.PAPER);
        NetworkDefinition base = network(proxy, backend);
        NetworkForwardingPolicy forwarding = new NetworkForwardingPolicy(ForwardingMode.MODERN, true, "new-secret", true);
        NetworkDefinition rotated = base.withForwarding(forwarding);
        NetworkSecretStore secrets = new NetworkSecretStore() {
            @Override
            public String resolveForwardingSecret(String reference) {
                return reference + "-value";
            }

            @Override
            public String getOrCreateEnrollmentToken(String networkId, String nodeId) {
                return "enrollment-token";
            }
        };

        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery(rotated, proxy, backend)), secrets);

        assertTrue(rotated.revision() == base.revision() + 1);
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.path().equals("forwarding.secret") && mutation.sensitive() && mutation.desiredValue().equals("new-secret-value")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.path().equals("config/paper-global.yml") && mutation.key().equals("proxies.velocity.secret") && mutation.sensitive() && mutation.desiredValue().equals("new-secret-value")));
    }

    @Test
    void removesUnusedVelocityExampleRoutesAndHosts() {
        Instance proxy = instance("Proxy", ModLoader.VELOCITY);
        Instance backend = instance("Survival", ModLoader.PAPER);

        NetworkReconciliationPlan plan = new NetworkDesiredStatePlanner().plan(DesktopNetworkPlanInput.from(discovery(network(proxy, backend), proxy, backend)), secrets());

        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.key().equals("config-version") && mutation.desiredValue().equals("\"2.8\"")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.action() == NetworkMutationAction.REMOVE && mutation.key().equals("forwarding-secret")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.action() == NetworkMutationAction.REMOVE && mutation.key().equals("servers.*")));
        assertTrue(plan.mutations().stream().anyMatch(mutation -> mutation.action() == NetworkMutationAction.REMOVE && mutation.key().equals("forced-hosts.*")));
    }

    private NetworkDefinition network(Instance proxy, Instance backend) {
        NetworkMember proxyMember = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkMember backendMember = NetworkMember.backend(backend.getInstanceId(), "backend", NetworkMemberRole.GAMEPLAY, 25566);
        return NetworkDefinition.create("Network", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxyMember, backendMember));
    }

    private NetworkDiscoveryResult discovery(NetworkDefinition network, Instance proxy, Instance backend) {
        return new NetworkDiscoveryResult(network, Map.of(proxy.getInstanceId(), proxy, backend.getInstanceId(), backend), List.of(), List.of(), List.of());
    }

    private Instance instance(String name, ModLoader loader) {
        Instance instance = new Instance(name, "1.21.4", name.toLowerCase());
        instance.setServer(true);
        instance.setModLoader(loader);
        return instance;
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
