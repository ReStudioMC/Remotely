package redxax.oxy.remotely.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationCache;
import redxax.oxy.remotely.data.flow.ReSyncDecodedFrame;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncFrameCodec;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncLiveServerSession;
import redxax.oxy.remotely.data.flow.ReSyncProtocolContract;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.worldgen.data.WorldGenGraph;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import redxax.oxy.remotely.worldgen.data.WorldGenSerializer;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.identity.ServerId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldGenReSyncPublicationFixtureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void actualReSyncPublicationProjectsIntoWorldGenSaveAndWireIdentity() throws Exception {
        String sourceDirectory = System.getenv("RESYNC_SOURCE_DIR");
        assumeTrue(sourceDirectory != null && !sourceDirectory.isBlank(),
            "Cross-repo WorldGen acceptance requires an explicit RESYNC_SOURCE_DIR");
        Path sourcePath = Path.of(sourceDirectory).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(sourcePath), "RESYNC_SOURCE_DIR is not a directory: " + sourcePath);
        assertTrue(Files.isRegularFile(sourcePath.resolve("settings.gradle.kts")),
            "RESYNC_SOURCE_DIR is not a ReSync build: " + sourcePath);
        assertTrue(Files.isDirectory(sourcePath.resolve("ReSyncCore")),
            "RESYNC_SOURCE_DIR does not contain ReSyncCore: " + sourcePath);
        Path publicationPath = sourcePath.resolve("build/cross-repo/worldgen/publication.packet");
        assertTrue(Files.isRegularFile(publicationPath), publicationPath.toString());

        byte[] publicationFrame = Files.readAllBytes(publicationPath);
        ReSyncFrameCodec frameCodec = new ReSyncFrameCodec();
        ReSyncDecodedFrame decodedPublication = frameCodec.decode(publicationFrame,
            Set.of(ReSyncProtocolContract.CHANNEL_FLOW_ID));
        assertEquals(ReSyncProtocolContract.MESSAGE_DATA, decodedPublication.messageType());
        assertEquals(ReSyncProtocolContract.CHANNEL_FLOW_ID, decodedPublication.channel());
        ByteBuffer publicationPayload = ByteBuffer.wrap(decodedPublication.payload());
        assertEquals(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION, publicationPayload.get());
        byte[] canonicalPublication = new byte[publicationPayload.remaining()];
        publicationPayload.get(canonicalPublication);
        CatalogCachePublication publication = new CatalogCachePublicationCodec().decodeBytes(canonicalPublication);
        ServerId server = publication.serverId();

        ProductionWireTransport transport = new ProductionWireTransport(publication.key());
        FlowManager flowManager = new FlowManager(null, null,
            ignored -> new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("catalog-cache"))));
        ReSyncFlowClient client = flowManager.activateLiveReSyncSession(
            new ReSyncLiveServerSession(server.canonicalText(), "WorldGen", transport));
        assertNotNull(client);
        WorldGenManager manager = WorldGenManager.getInstance();
        try {
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());
            transport.receive(publicationFrame);
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
            assertTrue(client.acknowledgeCatalogPublicationKey(publication.key()));
            assertEquals(publication, client.catalogPublicationProjection().active().orElseThrow().publication());

            WorldGenCatalogProjection.Snapshot projection = WorldGenCatalogProjection.from(
                WorldGenCatalogProjection.scopedServerId(server.canonicalText()), client);
            var definition = projection.definition("worldgen:simplex").orElseThrow();
            assertEquals(WorldGenCatalogProjection.Authority.TYPED_PUBLICATION, projection.authority());
            assertEquals("worldgen", definition.getOwner());
            assertEquals("simplex", definition.getId());

            FlowGraph graph = new FlowGraph();
            graph.getNodes().put("noise", new FlowNode("worldgen:simplex", 0, 0,
                Map.of("seed", 0, "frequency", 0.01f)));
            graph.getNodes().put("height", new FlowNode("worldgen:output_height", 160, 0, Map.of()));
            graph.getConnections().add(new FlowConnection("noise", "out", "height", "height"));
            WorldGenGraph wire = manager.toWorldGenGraph(projection, graph);
            assertEquals("worldgen:simplex", wire.getNodes().get("noise").getType());
            assertEquals("worldgen:output_height", wire.getNodes().get("height").getType());

            WorldGenProject project = new WorldGenProject();
            project.setId("cross-repo-" + UUID.randomUUID());
            project.setTerrainGraph(wire);
            WorldGenProject restored = WorldGenSerializer.deserializeProject(
                WorldGenSerializer.serializeProject(project));
            assertEquals("worldgen:simplex", restored.getTerrainGraph().getNodes().get("noise").getType());
            manager.saveWorldGen(WorldGenCatalogProjection.scopedServerId(server.canonicalText()), project, false);

            ReSyncDecodedFrame saveFrame = transport.sentFrames().stream()
                .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_WORLDGEN_ID
                    && frame.payload().length > 0 && frame.payload()[0] == 0x30)
                .findFirst()
                .orElseThrow();
            ByteBuffer savePayload = ByteBuffer.wrap(saveFrame.payload());
            assertEquals((byte) 0x30, savePayload.get());
            int requestIdLength = savePayload.getInt();
            assertTrue(requestIdLength > 0 && requestIdLength <= savePayload.remaining());
            byte[] framedRequestIdBytes = new byte[requestIdLength];
            savePayload.get(framedRequestIdBytes);
            byte[] projectBytes = new byte[savePayload.remaining()];
            savePayload.get(projectBytes);
            JsonObject saveEnvelope = JsonParser.parseString(new String(projectBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(1L, saveEnvelope.get("authorityEpoch").getAsLong());
            assertEquals("worldGenProjectSave", saveEnvelope.get("action").getAsString());
            assertEquals(project.getId(), saveEnvelope.get("resourceId").getAsString());
            assertEquals(new String(framedRequestIdBytes, StandardCharsets.UTF_8), saveEnvelope.get("requestId").getAsString());
            WorldGenProject sentProject = WorldGenSerializer.deserializeProject(
                saveEnvelope.getAsJsonObject("data").toString());
            assertEquals(project.getId(), sentProject.getId());
            assertEquals("worldgen:simplex", sentProject.getTerrainGraph().getNodes().get("noise").getType());

            String previewId = "preview-" + UUID.randomUUID();
            client.sendWorldGenPreviewApply(null, project, previewId, "NORMAL", 42L, "");
            ReSyncDecodedFrame previewFrame = transport.sentFrames().stream()
                .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_WORLDGEN_ID
                    && frame.payload().length > 0 && frame.payload()[0] == 0x31)
                .findFirst()
                .orElseThrow();
            ByteBuffer previewPayload = ByteBuffer.wrap(previewFrame.payload());
            assertEquals((byte) 0x31, previewPayload.get());
            int previewRequestIdLength = previewPayload.getInt();
            assertTrue(previewRequestIdLength > 0 && previewRequestIdLength <= previewPayload.remaining());
            previewPayload.position(previewPayload.position() + previewRequestIdLength);
            byte[] previewBytes = new byte[previewPayload.remaining()];
            previewPayload.get(previewBytes);
            JsonObject previewEnvelope = JsonParser.parseString(new String(previewBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(1L, previewEnvelope.get("authorityEpoch").getAsLong());
            assertEquals("worldGenPreviewApply", previewEnvelope.get("action").getAsString());
            JsonObject previewData = previewEnvelope.getAsJsonObject("data");
            assertEquals(previewId, previewData.get("previewId").getAsString());
            WorldGenProject previewProject = WorldGenSerializer.deserializeProject(
                previewData.getAsJsonObject("draftProject").toString());
            assertEquals(project.getId(), previewProject.getId());

            Path outputPath = sourcePath.resolve("build/cross-repo/worldgen/project-request.json");
            writeAtomically(outputPath, WorldGenSerializer.serializeProject(sentProject).getBytes(StandardCharsets.UTF_8));
        } finally {
            manager.clearCache(server.canonicalText());
            flowManager.shutdown();
        }
    }

    private static byte[] handshakeResponse(restudio.resync.flow.cache.CatalogCacheKey key) {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", key.serverId().canonicalText());
        capabilities.addProperty("catalogPublicationKey", key.canonicalText());
        capabilities.addProperty("authorityEpoch", 1L);
        JsonObject protocolEnvelope = new JsonObject();
        protocolEnvelope.addProperty("authorityEpoch", 1L);
        capabilities.add("protocolEnvelope", protocolEnvelope);
        JsonObject contract = new JsonObject();
        contract.addProperty("version", ReSyncProtocolContract.FLOW_CONTRACT.version());
        contract.addProperty("minimumClientVersion", 0);
        JsonArray supported = new JsonArray();
        ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities().forEach(supported::add);
        contract.add("supported", supported);
        JsonArray negotiated = new JsonArray();
        for (String capability : ReSyncProtocolContract.FLOW_CONTRACT.negotiate(
            ReSyncProtocolContract.FLOW_CONTRACT.clientCapabilities())) {
            negotiated.add(capability);
        }
        contract.add("negotiated", negotiated);
        capabilities.add("flowContract", contract);
        byte[] capabilityBytes = capabilities.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 7 + capabilityBytes.length);
        payload.put((byte) 1);
        payload.putInt(0);
        payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(capabilityBytes.length);
        payload.put(capabilityBytes);
        return payload.array();
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class ProductionWireTransport implements ReSyncFrameTransport {
        private final ReSyncFrameCodec codec = new ReSyncFrameCodec();
        private final restudio.resync.flow.cache.CatalogCacheKey publicationKey;
        private final List<ReSyncDecodedFrame> sentFrames = new ArrayList<>();
        private Consumer<byte[]> frameHandler;
        private Runnable closeHandler;
        private boolean handshakeResponded;

        private ProductionWireTransport(restudio.resync.flow.cache.CatalogCacheKey publicationKey) {
            this.publicationKey = publicationKey;
        }

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
            frameHandler = handler;
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public void send(byte[] frame) {
            ReSyncDecodedFrame decoded = codec.decode(frame, null);
            sentFrames.add(decoded);
            if (!handshakeResponded && decoded.messageType() == ReSyncProtocolContract.MESSAGE_HANDSHAKE_REQUEST) {
                handshakeResponded = true;
                frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE,
                    handshakeResponse(publicationKey), (short) 0, 1));
            }
        }

        @Override
        public void close() {
            if (closeHandler != null) {
                closeHandler.run();
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        private void receive(byte[] frame) {
            frameHandler.accept(frame.clone());
        }

        private List<ReSyncDecodedFrame> sentFrames() {
            return List.copyOf(sentFrames);
        }
    }
}
