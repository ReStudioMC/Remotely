package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import redxax.oxy.remotely.worldgen.data.WorldGenSerializer;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenProtocolHandlerProjectionTest {
    @Test
    void projectDataUsesValidatedOuterRevisionAndAuthorityEpoch(@TempDir Path temporaryDirectory) throws Exception {
        WorldGenManager manager = WorldGenManager.getInstance();
        try (Fixture fixture = fixture(temporaryDirectory, "data")) {
            String serverId = fixture.serverId();
            WorldGenProject project = new WorldGenProject();
            project.setId("project-data");
            String json = "{\"authorityEpoch\":1,\"revision\":4,\"data\":"
                + WorldGenSerializer.serializeProject(project) + "}";

            fixture.handler().handle(packet(0x35, json));

            assertNotNull(manager.getCachedProject(serverId, project.getId()));
        }
    }

    @Test
    void projectListRequiresPositiveValidatedOuterRevisionAndAuthorityEpoch(@TempDir Path temporaryDirectory) throws Exception {
        WorldGenManager manager = WorldGenManager.getInstance();
        try (Fixture fixture = fixture(temporaryDirectory, "list")) {
            String serverId = fixture.serverId();
            fixture.handler().handle(packet(0x36,
                "{\"authorityEpoch\":1,\"revision\":5,\"data\":[\"project-a\",\"project-b\"]}"));

            assertEquals(List.of("project-a", "project-b"), manager.getProjectIds(serverId));

            fixture.handler().handle(packet(0x36,
                "{\"authorityEpoch\":1,\"revision\":0,\"data\":[\"ignored\"]}"));
            assertTrue(manager.getProjectIds(serverId).containsAll(List.of("project-a", "project-b")));
        }
    }

    @Test
    void projectListObjectRequiresOneExplicitValidListField(@TempDir Path temporaryDirectory) throws Exception {
        WorldGenManager manager = WorldGenManager.getInstance();
        try (Fixture fixture = fixture(temporaryDirectory, "list-object")) {
            String serverId = fixture.serverId();
            fixture.handler().handle(packet(0x36,
                "{\"authorityEpoch\":1,\"revision\":4,\"data\":{\"projectIds\":[\"project-a\"]}}"));
            assertEquals(List.of("project-a"), manager.getProjectIds(serverId));

            fixture.handler().handle(packet(0x36,
                "{\"authorityEpoch\":1,\"revision\":5,\"data\":{}}"));
            assertEquals(List.of("project-a"), manager.getProjectIds(serverId));

            fixture.handler().handle(packet(0x36,
                "{\"authorityEpoch\":1,\"revision\":6,\"data\":{\"ids\":[\"project-b\"],\"projects\":[]}}"));
            assertEquals(List.of("project-a"), manager.getProjectIds(serverId));

            fixture.handler().handle(packet(0x36,
                "{\"authorityEpoch\":1,\"revision\":7,\"data\":{\"ids\":[1]}}"));
            assertEquals(List.of("project-a"), manager.getProjectIds(serverId));

            fixture.handler().handle(packet(0x36,
                "{\"authorityEpoch\":1,\"revision\":8,\"data\":{\"projects\":[]}}"));
            assertTrue(manager.getProjectIds(serverId).isEmpty());
        }
    }

    private static Fixture fixture(Path temporaryDirectory, String name) throws Exception {
        ServerId server = ServerId.random();
        FixtureProbe probe = new FixtureProbe();
        CatalogCacheKey key = new CatalogCacheKey(server, 1L, new ContentHash("a".repeat(64)),
            new ContentHash("b".repeat(64)), CatalogProjectionVersion.current());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            key, 1L, List.of());
        ReSyncFlowClientTestHarness harness = ReSyncFlowClientTestHarness.connect(server, probe, probe.manager,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve(name + ".json"))), publication);
        return new Fixture(server.canonicalText(), probe, harness,
            new WorldGenProtocolHandler(server.canonicalText(), new Gson(), null,
                () -> harness.client().resourceRevisionReconciler().authorityEpoch(server.canonicalText())));
    }

    private static byte[] packet(int packetId, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(payload.length + 1);
        packet.put((byte) packetId).put(payload);
        return packet.array();
    }

    private record Fixture(String serverId, FixtureProbe probe, ReSyncFlowClientTestHarness harness,
                           WorldGenProtocolHandler handler) implements AutoCloseable {
        @Override
        public void close() {
            WorldGenManager.getInstance().clearCache(serverId);
            harness.close();
            probe.close();
        }
    }

    private static final class FixtureProbe extends RemotelyClient {
        private final FlowManager manager;

        private FixtureProbe() {
            super(null);
            manager = new FlowManager(this, null);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
        }
    }
}
