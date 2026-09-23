package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.host.ApplicationHostRegistry;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.ui.core.Screen;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncWorkspaceFrameDispatchTest {
    private static final ServerId SERVER = ServerId.deterministic("workspace-frame-dispatch");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "0".repeat(64), "1".repeat(64));

    @TempDir
    Path temporaryDirectory;
    private final List<Probe> probes = new ArrayList<>();
    private final ApplicationHost previousHost = ApplicationHostRegistry.current();
    private final RemotelyClient previousClient = RemotelyClient.INSTANCE;

    @AfterEach
    void closeManagers() {
        for (int index = probes.size() - 1; index >= 0; index--) {
            Probe probe = probes.get(index);
            probe.manager.shutdown();
            probe.storageSnapshot.close();
        }
        ApplicationHostRegistry.install(previousHost);
        RemotelyClient.INSTANCE = previousClient;
    }

    @Test
    void negotiatedWorkspaceFramesReachTwoEditorsAndPublishNodeMoves() throws Exception {
        FrameTransport firstTransport = new FrameTransport();
        FrameTransport secondTransport = new FrameTransport();
        try (ReSyncFlowClientTestHarness first = connect("first", firstTransport);
             ReSyncFlowClientTestHarness second = connect("second", secondTransport)) {
            CoreGraphEditorSession firstSession = new CoreGraphEditorSession(graph());
            CoreGraphEditorSession secondSession = new CoreGraphEditorSession(graph());
            CoreGraphLiveWorkspace firstWorkspace = new CoreGraphLiveWorkspace(first.client(), firstSession, ignored -> { });
            CoreGraphLiveWorkspace secondWorkspace = new CoreGraphLiveWorkspace(second.client(), secondSession, ignored -> { });
            try {
                firstWorkspace.join();
                secondWorkspace.join();
                for (FrameTransport transport : List.of(firstTransport, secondTransport)) {
                    JsonObject join = transport.requireFlowPacket(ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_JOIN);
                    assertEquals("flow", join.get("type").getAsString());
                    assertEquals("main", join.get("resourceId").getAsString());
                    assertEquals(1, join.get("documentVersion").getAsInt());
                    assertEquals(1L, join.get("authorityEpoch").getAsLong());
                    assertEquals(publication().key().canonicalText(), join.get("catalogKey").getAsString());
                    transport.receiveFlow(ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_SNAPSHOT, snapshot(), 3);
                }
                first.drain();
                second.drain();
                apply(firstWorkspace, firstSession);
                apply(secondWorkspace, secondSession);

                firstSession.setNodePosition(firstSession.graphDocument().nodes().getFirst().instanceId(), 48, 24);
                apply(firstWorkspace, firstSession);
                JsonObject operation = firstTransport.requireFlowPacket(ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_OPERATION);
                assertEquals(0L, operation.get("baseSequence").getAsLong());
                operation.addProperty("sequence", 1L);
                operation.addProperty("authorSessionId", "first");
                firstTransport.receiveFlow(ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_OPERATION, operation, 4);
                secondTransport.receiveFlow(ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_OPERATION, operation, 4);
                first.drain();
                second.drain();
                apply(firstWorkspace, firstSession);
                apply(secondWorkspace, secondSession);

                assertEquals(48, secondSession.graphDocument().nodes().getFirst().x());
                assertEquals(24, secondSession.graphDocument().nodes().getFirst().y());
                assertEquals(0, secondSession.baselineGraphDocument().nodes().getFirst().x());
                assertTrue(firstSession.isDirty());
                assertTrue(secondSession.isDirty());
                assertEquals(firstSession.graphDocument().canonicalJson(), secondSession.graphDocument().canonicalJson());
            } finally {
                firstWorkspace.close();
                secondWorkspace.close();
            }
        }
    }

    @Test
    void componentBuilderUsesTypedResourceRequestsAfterLegacyPacketRetirement() throws Exception {
        FrameTransport transport = new FrameTransport();
        try (ReSyncFlowClientTestHarness harness = connect("components", transport)) {
            assertFalse(harness.client().legacyReadCompatibilityAllowed());
            harness.client().requestResource(ReSyncResourceType.COMPONENT_BUILDER, "message", false);
            harness.client().requestResourceList(ReSyncResourceType.COMPONENT_BUILDER);
            harness.drain();
            var load = transport.requireRequest(ResourceOperationKind.LOAD, "message");
            assertEquals("component_builder", load.resource().resourceType().value());
            assertTrue(transport.sentFrames().stream()
                .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE)
                .map(frame -> new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json()).decodeBytes(frame.payload()))
                .anyMatch(envelope -> envelope.body() instanceof ProtocolBody.ResourceRequest request
                    && request.operation() instanceof ResourceListRequest list
                    && list.type().id().value().equals("component_builder")));
        }
    }

    private ReSyncFlowClientTestHarness connect(String name, FrameTransport transport) throws Exception {
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(
            DesktopReSyncStorage.fromKey(temporaryDirectory.resolve(name + ".json")));
        Probe owner = new Probe(temporaryDirectory.resolve(name + "-state"));
        probes.add(owner);
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, owner, cache);
        FlowManagerTestConnection.installCurrent(owner.manager, SERVER.canonicalText(), client, transport);
        return ReSyncFlowClientTestHarness.establish(client, transport, publication());
    }

    private void apply(CoreGraphLiveWorkspace workspace, CoreGraphEditorSession session) {
        var request = workspace.capture(CoreGraphUiProjection.EditorSnapshot.capture(session));
        assertNotNull(request, "The negotiated workspace frame must reach the editor");
        var prepared = workspace.prepare(request);
        assertEquals("", prepared.failure());
        if (prepared.edit() != null) {
            assertTrue(session.applyWorkspaceEdit(prepared.edit()));
        }
        workspace.accept(prepared);
    }

    private JsonObject snapshot() {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("type", "flow");
        snapshot.addProperty("resourceId", "main");
        snapshot.addProperty("sequence", 0L);
        snapshot.addProperty("editability", "EDITABLE");
        snapshot.addProperty("authorityEpoch", 1L);
        snapshot.add("document", JsonParser.parseString(graph().canonicalJson()));
        return snapshot;
    }

    private GraphDocument graph() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "main");
        GraphNode node = new GraphNode(NodeInstanceId.deterministic("workspace-frame-node"),
            ContractRef.of(OwnerId.of("fixture"), NodeId.of("node")), 1, Map.of());
        return new GraphDocument(GraphDocument.CURRENT_SCHEMA_VERSION, resource, 1, BINDING, Set.of(), List.of(node),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private static CatalogCachePublication publication() {
        CatalogProjectionVersion version = CatalogProjectionVersion.current();
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0),
            version, List.of(section(CatalogAuthoringPublication.Section.TYPES), section(CatalogAuthoringPublication.Section.EDITORS),
                section(CatalogAuthoringPublication.Section.PREVIEWS), section(CatalogAuthoringPublication.Section.CAPABILITIES)), Set.of());
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            new CatalogCacheKey(SERVER, BINDING, version), BINDING, 1, List.of(), authoring, Map.of());
    }

    private static CatalogAuthoringPublication.SectionProjection section(CatalogAuthoringPublication.Section section) {
        return new CatalogAuthoringPublication.SectionProjection(section, true, true, CatalogCacheState.ACTIVE, List.of());
    }

    private static final class Probe extends RemotelyClient {
        private final FlowManager manager;
        private final ServerSettingsRegistry.StorageSnapshot storageSnapshot;

        private Probe(Path state) {
            this(state, RemotelyComposition.browser(new TestHost()).scheduler(TaskSchedulers.current()).build());
        }

        private Probe(Path state, RemotelyComposition composition) {
            super(composition);
            storageSnapshot = composition.serverSettingsRegistryStorageSnapshot();
            manager = new FlowManager(this, null, null, DesktopReSyncStorage.fromKey(state));
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }
    }

    private static final class TestHost implements ApplicationHost {
        @Override public void setScreen(Screen screen) { }
        @Override public Screen getCurrentScreen() { return null; }
        @Override public void ensureTextRenderer() { }
        @Override public MinecraftGameAssets getGameAssets() { return MinecraftGameAssets.EMPTY; }
        @Override public Object getFontIdentifier(String namespace, String path) { return null; }
        @Override public void openParentScreen(Screen currentScreen, Object parent) { }
        @Override public void setClipboard(String text) { }
        @Override public boolean shouldCloseRootScreen() { return false; }
        @Override public String getGameVersion() { return ""; }
        @Override public String getGameUserName() { return ""; }
        @Override public String getGameUUID() { return ""; }
    }

    private static final class FrameTransport extends ScriptedReSyncTransport {
        private Consumer<byte[]> frameHandler;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
            super.setFrameHandler(handler);
            frameHandler = handler;
        }

        @Override
        void receiveHandshake(String json) {
            JsonObject capabilities = JsonParser.parseString(json).getAsJsonObject();
            JsonObject flow = new JsonObject();
            flow.addProperty("version", 2);
            flow.addProperty("minimumClientVersion", 2);
            JsonArray supported = new JsonArray();
            ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities().forEach(supported::add);
            JsonArray negotiated = new JsonArray();
            ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities().stream()
                .filter(ReSyncProtocolContract.FLOW_CONTRACT.clientCapabilities()::contains).forEach(negotiated::add);
            flow.add("supported", supported);
            flow.add("negotiated", negotiated);
            capabilities.add("flowContract", flow);
            JsonObject protocol = new JsonObject();
            protocol.addProperty("supported", true);
            JsonObject operations = new JsonObject();
            JsonArray read = new JsonArray();
            List.of("load", "list", "query").forEach(read::add);
            operations.add("read", read);
            protocol.add("resourceOperations", operations);
            JsonObject generic = new JsonObject();
            JsonObject version = new JsonObject();
            version.addProperty("generation", 1);
            version.addProperty("minor", 3);
            generic.add("version", version);
            generic.add("capabilities", new JsonArray());
            protocol.add("genericResourceContract", generic);
            capabilities.add("protocolEnvelope", protocol);
            super.receiveHandshake(capabilities.toString());
        }

        void receiveFlow(byte packetId, JsonObject value, int sequence) {
            byte[] json = value.toString().getBytes(StandardCharsets.UTF_8);
            byte[] payload = ByteBuffer.allocate(1 + json.length).put(packetId).put(json).array();
            frameHandler.accept(new ReSyncFrameCodec().encode(ReSyncProtocolContract.MESSAGE_DATA, payload,
                ReSyncProtocolContract.CHANNEL_FLOW_ID, sequence));
        }

        JsonObject requireFlowPacket(byte packetId) {
            return sentFrames().stream().filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID && frame.payload().length > 1
                && frame.payload()[0] == packetId).reduce((first, second) -> second)
                .map(frame -> JsonParser.parseString(new String(frame.payload(), 1, frame.payload().length - 1,
                    StandardCharsets.UTF_8)).getAsJsonObject()).orElseThrow();
        }
    }
}
