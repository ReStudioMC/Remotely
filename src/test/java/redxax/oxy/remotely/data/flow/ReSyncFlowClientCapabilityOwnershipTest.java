package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import restudio.rebase.platform.jvm.JvmTaskScheduler;
import restudio.rescreen.platform.TaskScheduler;
import redxax.oxy.remotely.util.TaskSchedulers;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.host.ApplicationHost;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.ui.core.Screen;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientCapabilityOwnershipTest {
    private static final String SERVER_ID = "abcdefab-cdef-4abc-8def-abcdefabcdef";

    @TempDir
    Path stateRoot;
    private TaskScheduler previousScheduler;
    private JvmTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        previousScheduler = TaskSchedulers.current();
        scheduler = new JvmTaskScheduler();
        TaskSchedulers.configure(scheduler);
    }

    @AfterEach
    void tearDown() {
        TaskSchedulers.configure(previousScheduler);
        scheduler.close();
    }

    @Test
    void handshakeReconnectAndCapabilityReadsStayWithTheClientOwner() throws Exception {
        OwnedClient owner = new OwnedClient();
        FlowManager ownerManager = manager(owner, "owner");
        owner.bind(ownerManager);
        OwnedClient firstDecoy = new OwnedClient();
        FlowManager firstDecoyManager = manager(firstDecoy, "first-decoy");
        firstDecoy.bind(firstDecoyManager);
        TestTransport transport = new TestTransport();
        ReSyncFlowClient flowClient = new ReSyncFlowClient(SERVER_ID, transport, owner,
            new ReSyncCatalogPublicationCache());
        FlowManager secondDecoyManager = null;
        FlowManager finalDecoyManager = null;
        CountDownLatch firstConnected = new CountDownLatch(1);
        CountDownLatch secondConnected = new CountDownLatch(1);
        AtomicInteger connections = new AtomicInteger();

        try {
            FlowManagerTestConnection.installCurrent(ownerManager, SERVER_ID, flowClient, transport);
            flowClient.setConnectionListener(() -> {
                if (connections.incrementAndGet() == 1) {
                    firstConnected.countDown();
                } else {
                    secondConnected.countDown();
                }
            });

            flowClient.connect().join();
            transport.receiveHandshake(capabilities("first"));
            assertTrue(firstConnected.await(2L, TimeUnit.SECONDS));
            assertEquals("first", ownerManager.getServerCapabilities(SERVER_ID).get("ownerMarker").getAsString());
            assertNull(firstDecoyManager.getServerCapabilities(SERVER_ID));
            ownerManager.getServerCapabilities(SERVER_ID).remove("protocolEnvelope");
            assertTrue(flowClient.supportsGenericResourceActivation(ReSyncResourceType.GUI));
            ownerManager.cacheServerCapabilities(SERVER_ID, new JsonObject());
            assertTrue(flowClient.supportsFlowCapability("resource_revisions"));
            assertTrue(flowClient.supportsGenericResourceActivation(ReSyncResourceType.GUI));

            OwnedClient secondDecoy = new OwnedClient();
            secondDecoyManager = manager(secondDecoy, "second-decoy");
            secondDecoy.bind(secondDecoyManager);
            transport.disconnect();
            ReSyncFlowClientTestHarness.drain(flowClient);
            flowClient.connect().join();
            transport.receiveHandshake(capabilities("reconnected"));
            assertTrue(secondConnected.await(2L, TimeUnit.SECONDS));
            assertEquals("reconnected",
                ownerManager.getServerCapabilities(SERVER_ID).get("ownerMarker").getAsString());
            assertNull(secondDecoyManager.getServerCapabilities(SERVER_ID));
            assertFalse(flowClient.supportsGenericResourceActivation(ReSyncResourceType.GUI));

            OwnedClient finalDecoy = new OwnedClient();
            finalDecoyManager = manager(finalDecoy, "final-decoy");
            finalDecoy.bind(finalDecoyManager);
            assertTrue(flowClient.supportsFlowCapability("resource_revisions"));
            assertNull(finalDecoyManager.getServerCapabilities(SERVER_ID));
        } finally {
            flowClient.shutdown();
            ownerManager.shutdown();
            firstDecoyManager.shutdown();
            if (secondDecoyManager != null) {
                secondDecoyManager.shutdown();
            }
            if (finalDecoyManager != null) {
                finalDecoyManager.shutdown();
            }
        }
    }

    private FlowManager manager(OwnedClient owner, String directory) {
        return new FlowManager(owner, null, null, DesktopReSyncStorage.fromKey(stateRoot.resolve(directory)));
    }

    private static String capabilities(String marker) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", SERVER_ID);
        root.addProperty("authorityEpoch", 1L);
        root.addProperty("ownerMarker", marker);
        JsonObject protocol = new JsonObject();
        protocol.addProperty("supported", true);
        protocol.addProperty("resourceContractVersion", "1.3");
        JsonObject authority = new JsonObject();
        authority.addProperty("supported", true);
        authority.addProperty("durable", true);
        protocol.add("mutationAuthority", authority);
        JsonObject operations = new JsonObject();
        JsonArray mutations = new JsonArray();
        mutations.add("activate");
        operations.add("mutate", mutations);
        protocol.add("resourceOperations", operations);
        JsonArray resourceCapabilities = new JsonArray();
        if ("first".equals(marker)) {
            resourceCapabilities.add("resource_activation");
        }
        protocol.add("resourceCapabilities", resourceCapabilities);
        root.add("protocolEnvelope", protocol);
        JsonObject contract = new JsonObject();
        contract.addProperty("version", ReSyncProtocolContract.FLOW_CONTRACT.version());
        contract.addProperty("minimumClientVersion", 0);
        JsonArray supported = new JsonArray();
        ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities().forEach(supported::add);
        contract.add("supported", supported);
        JsonArray negotiated = new JsonArray();
        ReSyncProtocolContract.FLOW_CONTRACT.negotiate(ReSyncProtocolContract.FLOW_CONTRACT.clientCapabilities())
            .forEach(negotiated::add);
        contract.add("negotiated", negotiated);
        root.add("flowContract", contract);
        return root.toString();
    }

    private static final class OwnedClient extends RemotelyClient {
        private FlowManager manager;

        private OwnedClient() {
            super(RemotelyComposition.browser(new TestHost()).scheduler(TaskSchedulers.current()).build());
        }

        private void bind(FlowManager manager) {
            this.manager = manager;
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
        @Override public MinecraftGameAssets getGameAssets() { return null; }
        @Override public Object getFontIdentifier(String namespace, String path) { return null; }
        @Override public void openParentScreen(Screen currentScreen, Object parent) { }
        @Override public void setClipboard(String text) { }
        @Override public boolean shouldCloseRootScreen() { return false; }
        @Override public String getGameVersion() { return ""; }
        @Override public String getGameUserName() { return ""; }
        @Override public String getGameUUID() { return ""; }
    }

    private static final class TestTransport implements ReSyncFrameTransport {
        private final ReSyncFrameCodec codec = new ReSyncFrameCodec();
        private Consumer<byte[]> frameHandler;
        private Runnable closeHandler;

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
        }

        @Override
        public void close() {
            disconnect();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        private void receiveHandshake(String capabilities) {
            byte[] bytes = capabilities.getBytes(StandardCharsets.UTF_8);
            ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 7 + bytes.length);
            payload.put((byte) 1);
            payload.putInt(0);
            payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
            payload.putInt(0);
            payload.putInt(0);
            payload.putInt(0);
            payload.putInt(0);
            payload.putInt(bytes.length);
            payload.put(bytes);
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE,
                payload.array(), (short) 0, 1));
        }

        private void disconnect() {
            if (closeHandler != null) {
                closeHandler.run();
            }
        }
    }
}
