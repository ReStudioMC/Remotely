package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NetworkValidatorTest {
    @Test
    void providerContainersCannotShareLoopbackEvenOnTheSameHost() {
        for (String scope : List.of("ptero:host", "calagopus:host", "restudio:host")) {
            NetworkMember proxy = new NetworkMember("proxy", "proxy", "proxy", NetworkMemberRole.PROXY, scope, "127.0.0.1", 25565, 0, true);
            NetworkMember backend = new NetworkMember("backend", "backend", "lobby", NetworkMemberRole.LOBBY, scope, "127.0.0.1", 25566, 0, true);
            assertFalse(NetworkRuntimePolicy.sharesLoopback(proxy, backend));
        }
        assertTrue(NetworkRuntimePolicy.sharesLoopback(NetworkMember.proxy("proxy", 25565), NetworkMember.backend("backend", "lobby", NetworkMemberRole.LOBBY, 25566)));
    }

    @Test
    void acceptsValidVelocityNetwork() {
        NetworkDefinition network = validNetwork();

        assertDoesNotThrow(() -> NetworkValidator.requireValid(network));
        assertTrue(NetworkValidator.validate(network).stream().noneMatch(NetworkValidationIssue::blocksPersistence));
    }

    @Test
    void rejectsDuplicateRoutesAndUnknownRealmNodes() {
        NetworkDefinition valid = validNetwork();
        NetworkMember backend = valid.members().get(1);
        NetworkMember duplicate = new NetworkMember(UUID.randomUUID().toString(), UUID.randomUUID().toString(), backend.routeName(), NetworkMemberRole.GAMEPLAY, "local", "127.0.0.1", 25567, 0, true);
        SyncRealm realm = SyncRealm.presence("presence", "Presence", Set.of(UUID.randomUUID().toString()));
        NetworkDefinition invalid = new NetworkDefinition(valid.schemaVersion(), valid.networkId(), valid.name(), valid.revision(), valid.proxyInstanceId(), valid.desiredState(), valid.forwarding(), valid.entryPoints(), List.of(valid.members().getFirst(), backend, duplicate), valid.routingGroups(), List.of(realm), valid.runtime(), valid.features(), valid.createdAt(), valid.updatedAt());

        Set<String> codes = NetworkValidator.validate(invalid).stream().map(NetworkValidationIssue::code).collect(Collectors.toSet());

        assertTrue(codes.contains("member.route.duplicate"));
        assertTrue(codes.contains("realm.node.unknown"));
    }

    @Test
    void rejectsVelocityFallbackRouteAsBackendName() {
        NetworkDefinition valid = validNetwork();
        NetworkMember backend = valid.members().get(1);
        NetworkMember reserved = new NetworkMember(backend.instanceId(), backend.nodeId(), "try", backend.role(), backend.hostScope(), backend.address(), backend.port(), backend.capacity(), backend.resyncEnabled());
        NetworkDefinition invalid = valid.nextRevision(List.of(valid.members().getFirst(), reserved), valid.routingGroups(), valid.syncRealms(), valid.desiredState());

        assertTrue(NetworkValidator.validate(invalid).stream().anyMatch(issue -> issue.code().equals("member.route.reserved")));
    }

    @Test
    void requiresPersistentDataAllowlist() {
        NetworkDefinition valid = validNetwork();
        SyncRealm realm = new SyncRealm("survival", "Survival", Set.of(valid.members().get(1).nodeId()), Set.of(SyncDataFamily.PERSISTENT_DATA), SyncLocationPolicy.NEVER, Set.of(), 10, 30);
        NetworkDefinition invalid = new NetworkDefinition(valid.schemaVersion(), valid.networkId(), valid.name(), valid.revision(), valid.proxyInstanceId(), valid.desiredState(), valid.forwarding(), valid.entryPoints(), valid.members(), valid.routingGroups(), List.of(realm), valid.runtime(), valid.features(), valid.createdAt(), valid.updatedAt());

        assertTrue(NetworkValidator.validate(invalid).stream().anyMatch(issue -> issue.code().equals("realm.pdc.allowlist.empty")));
    }

    @Test
    void rejectsDuplicateForcedHostsAndFallbackCycles() {
        NetworkDefinition valid = validNetwork();
        String nodeId = valid.members().get(1).nodeId();
        RoutingGroup first = new RoutingGroup("first", "First", RoutingStrategy.ORDERED, List.of(nodeId), Map.of(), "second", Set.of("play.example.com"), "");
        RoutingGroup second = new RoutingGroup("second", "Second", RoutingStrategy.ORDERED, List.of(nodeId), Map.of(), "first", Set.of("play.example.com"), "");
        NetworkDefinition invalid = valid.nextRevision(valid.members(), List.of(first, second), valid.syncRealms(), valid.desiredState());

        Set<String> codes = NetworkValidator.validate(invalid).stream().map(NetworkValidationIssue::code).collect(Collectors.toSet());

        assertTrue(codes.contains("routing.host.duplicate"));
        assertTrue(codes.contains("routing.fallback.cycle"));
    }

    @Test
    void keepsExternalMembersOutOfReSyncRealms() {
        NetworkDefinition valid = validNetwork();
        NetworkMember managed = valid.members().get(1);
        NetworkMember external = new NetworkMember("external:" + UUID.randomUUID(), UUID.randomUUID().toString(), "minigames", NetworkMemberRole.GAMEPLAY, "external:10.0.0.40", "10.0.0.40", 25580, 100, true, NetworkMemberManagement.EXTERNAL);
        RoutingGroup group = new RoutingGroup("fallback", "Fallback", RoutingStrategy.ORDERED, List.of(managed.nodeId(), external.nodeId()), Map.of(), "", Set.of(), "");
        SyncRealm realm = SyncRealm.presence("presence", "Presence", Set.of(managed.nodeId(), external.nodeId()));
        NetworkDefinition invalid = new NetworkDefinition(valid.schemaVersion(), valid.networkId(), valid.name(), valid.revision(), valid.proxyInstanceId(), valid.desiredState(), valid.forwarding(), valid.entryPoints(), List.of(valid.members().getFirst(), managed, external), List.of(group), List.of(realm), valid.runtime(), valid.features(), valid.createdAt(), valid.updatedAt());

        Set<String> codes = NetworkValidator.validate(invalid).stream().map(NetworkValidationIssue::code).collect(Collectors.toSet());

        assertTrue(codes.contains("member.external.resync"));
        assertTrue(codes.contains("realm.node.external"));
    }

    @Test
    void defaultsExistingMemberDocumentsToManagedOwnership() {
        NetworkMember member = new NetworkMember(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "survival", NetworkMemberRole.GAMEPLAY, "local", "127.0.0.1", 25566, 0, false);

        assertTrue(member.isManaged());
    }

    @Test
    void requiresRuntimeForReSyncMembers() {
        NetworkDefinition valid = validNetwork();
        NetworkDefinition invalid = new NetworkDefinition(valid.schemaVersion(), valid.networkId(), valid.name(), valid.revision(), valid.proxyInstanceId(), valid.desiredState(), valid.forwarding(), valid.entryPoints(), valid.members(), valid.routingGroups(), valid.syncRealms(), NetworkRuntimePolicy.disabled(), valid.features(), valid.createdAt(), valid.updatedAt());

        assertTrue(NetworkValidator.validate(invalid).stream().anyMatch(issue -> issue.code().equals("runtime.required")));
    }

    @Test
    void rejectsLoopbackRuntimeAcrossHostsAndPortCollisions() {
        NetworkDefinition valid = validNetwork();
        NetworkMember backend = valid.members().get(1);
        NetworkMember remote = new NetworkMember(backend.instanceId(), backend.nodeId(), backend.routeName(), backend.role(), "ssh:remote", "10.0.0.20", backend.port(), backend.capacity(), true);
        NetworkRuntimePolicy runtime = new NetworkRuntimePolicy(true, "127.0.0.1", valid.entryPoints().getFirst().port(), NetworkTransportSecurity.LOOPBACK, true);
        NetworkDefinition invalid = new NetworkDefinition(valid.schemaVersion(), valid.networkId(), valid.name(), valid.revision(), valid.proxyInstanceId(), valid.desiredState(), valid.forwarding(), valid.entryPoints(), List.of(valid.members().getFirst(), remote), valid.routingGroups(), valid.syncRealms(), runtime, valid.features(), valid.createdAt(), valid.updatedAt());
        Set<String> codes = NetworkValidator.validate(invalid).stream().map(NetworkValidationIssue::code).collect(Collectors.toSet());

        assertTrue(codes.contains("runtime.loopback.cross-host"));
        assertTrue(codes.contains("runtime.port.entry-conflict"));
    }

    static NetworkDefinition validNetwork() {
        String proxyInstanceId = UUID.randomUUID().toString();
        NetworkMember proxy = NetworkMember.proxy(proxyInstanceId, 25565);
        NetworkMember lobby = NetworkMember.backend(UUID.randomUUID().toString(), "lobby", NetworkMemberRole.LOBBY, 25566);
        NetworkDefinition base = NetworkDefinition.create("Network", proxyInstanceId, NetworkForwardingPolicy.secureDefault("secret/network"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxy, lobby));
        RoutingGroup group = new RoutingGroup("fallback", "Fallback", RoutingStrategy.ORDERED, List.of(lobby.nodeId()), Map.of(), "", Set.of(), "");
        return new NetworkDefinition(base.schemaVersion(), base.networkId(), base.name(), base.revision(), base.proxyInstanceId(), base.desiredState(), base.forwarding(), base.entryPoints(), base.members(), List.of(group), List.of(), base.runtime(), base.features(), base.createdAt(), base.updatedAt());
    }
}
