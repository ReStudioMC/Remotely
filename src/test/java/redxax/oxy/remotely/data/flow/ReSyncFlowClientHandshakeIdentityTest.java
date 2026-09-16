package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourcePage;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientHandshakeIdentityTest {
    private static final String SERVER_ID = "abcdefab-cdef-4abc-8def-abcdefabcdef";
    private static final String OTHER_SERVER_ID = "12345678-1234-4234-8234-123456789abc";
    private static final ServerId SERVER = new ServerId(UUID.fromString(SERVER_ID));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");

    @Test
    void commonHandshakeAcceptsOnlyTheExactCanonicalConfiguredIdentity() throws Exception {
        CapturingTransport acceptedTransport = new CapturingTransport();
        ReSyncFlowClient accepted = new ReSyncFlowClient(SERVER_ID, acceptedTransport, null,
            new ReSyncCatalogPublicationCache());
        CountDownLatch connected = new CountDownLatch(1);
        accepted.setConnectionListener(connected::countDown);
        try {
            accepted.connect().join();
            acceptedTransport.receiveHandshake(identityCapabilities(SERVER_ID));
            assertTrue(connected.await(2L, TimeUnit.SECONDS));
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, accepted.connectionState());
        } finally {
            accepted.shutdown();
        }

        assertRejectedHandshake("");
        assertRejectedHandshake("{}");
        assertRejectedHandshake("not-json");
        assertRejectedHandshake(identityCapabilities(SERVER_ID.toUpperCase()));
        assertRejectedHandshake(identityCapabilities(OTHER_SERVER_ID));
    }

    @Test
    void identityMismatchCannotHydrateAuthorityOrPublishStartupRequests() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, null,
            new ReSyncCatalogPublicationCache());
        try {
            client.connect().join();
            assertEquals(1, transport.sentFrames.get());
            JsonObject capabilities = new JsonObject();
            capabilities.addProperty("serverId", OTHER_SERVER_ID);
            capabilities.addProperty("authorityEpoch", 9L);
            transport.receiveHandshake(capabilities.toString());
            awaitDisconnected(client);
            assertEquals(0L, longField(client, "handshakeAuthorityEpoch"));
            assertFalse(booleanField(client, "authorityEpochAdvertised"));
            assertEquals(1, transport.sentFrames.get());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void capabilitylessBridgeHandshakeRequiresExactOuterPeerIdentity() throws Exception {
        CapturingTransport transport = new CapturingTransport(SERVER);
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, null,
            new ReSyncCatalogPublicationCache());
        CountDownLatch connected = new CountDownLatch(1);
        client.setConnectionListener(connected::countDown);
        try {
            client.connect().join();
            transport.receiveLegacyHandshake();
            assertTrue(connected.await(2L, TimeUnit.SECONDS));
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void capabilitylessBridgeHandshakeRejectsMissingOrNoncanonicalOuterIdentity() throws Exception {
        CapturingTransport missingIdentity = new CapturingTransport();
        ReSyncFlowClient missingClient = new ReSyncFlowClient(SERVER_ID, missingIdentity, null,
            new ReSyncCatalogPublicationCache());
        try {
            missingClient.connect().join();
            missingIdentity.receiveLegacyHandshake();
            awaitDisconnected(missingClient);
        } finally {
            missingClient.shutdown();
        }

        CapturingTransport noncanonicalIdentity = new CapturingTransport(SERVER);
        ReSyncFlowClient noncanonicalClient = new ReSyncFlowClient("legacy:bridge:v2", noncanonicalIdentity, null,
            new ReSyncCatalogPublicationCache());
        try {
            noncanonicalClient.connect().join();
            noncanonicalIdentity.receiveLegacyHandshake();
            awaitDisconnected(noncanonicalClient);
        } finally {
            noncanonicalClient.shutdown();
        }
    }

    @Test
    void mismatchedOuterPeerIdentityCannotBeOverriddenByInnerCapabilities() throws Exception {
        CapturingTransport transport = new CapturingTransport(new ServerId(UUID.fromString(OTHER_SERVER_ID)));
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, null,
            new ReSyncCatalogPublicationCache());
        try {
            client.connect().join();
            transport.receiveHandshake(identityCapabilities(SERVER_ID));
            awaitDisconnected(client);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void legacyRegistrySnapshotRequiresTheExactCanonicalConfiguredIdentity() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, new CapturingTransport(), null,
            new ReSyncCatalogPublicationCache());
        Field authenticated = ReSyncFlowClient.class.getDeclaredField("authenticated");
        authenticated.setAccessible(true);
        ((AtomicBoolean) authenticated.get(client)).set(true);
        Method compatible = ReSyncFlowClient.class.getDeclaredMethod("compatibleRegistrySnapshot",
            NodeRegistrySnapshot.class);
        compatible.setAccessible(true);
        try {
            assertTrue((Boolean) compatible.invoke(client, registrySnapshot(SERVER_ID)));
            assertFalse((Boolean) compatible.invoke(client, registrySnapshot("")));
            assertFalse((Boolean) compatible.invoke(client, registrySnapshot(SERVER_ID.toUpperCase())));
            assertFalse((Boolean) compatible.invoke(client, registrySnapshot(OTHER_SERVER_ID)));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void resourcePageResponseRequiresThePagePayloadType() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, new CapturingTransport(), null,
            new ReSyncCatalogPublicationCache());
        Method valid = ReSyncFlowClient.class.getDeclaredMethod("validCoreEnvelope", ProtocolEnvelope.class);
        valid.setAccessible(true);
        try {
            assertTrue((Boolean) valid.invoke(client, pageEnvelope("resource.page")));
            assertFalse((Boolean) valid.invoke(client, pageEnvelope("resource.document")));
        } finally {
            client.shutdown();
        }
    }

    private static void assertRejectedHandshake(String capabilities) throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, null,
            new ReSyncCatalogPublicationCache());
        try {
            client.connect().join();
            transport.receiveHandshake(capabilities);
            awaitDisconnected(client);
            assertEquals(ReSyncFlowClient.ConnectionState.DISCONNECTED, client.connectionState());
            assertEquals(1, transport.sentFrames.get());
        } finally {
            client.shutdown();
        }
    }

    private static void awaitDisconnected(ReSyncFlowClient client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (client.connectionState() != ReSyncFlowClient.ConnectionState.DISCONNECTED
            && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(ReSyncFlowClient.ConnectionState.DISCONNECTED, client.connectionState());
    }

    private static String identityCapabilities(String serverId) {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", serverId);
        return capabilities.toString();
    }

    private static NodeRegistrySnapshot registrySnapshot(String serverIdentity) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setMinimumClientContractVersion(0);
        snapshot.setCapabilities(List.copyOf(ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities()));
        snapshot.setServerIdentity(serverIdentity);
        return snapshot;
    }

    private static ProtocolEnvelope<Map<String, Object>> pageEnvelope(String payloadType) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.RESPONSE,
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), SERVER, null, 0L, null,
            ContractRef.of(OWNER, OperationId.of("resource.list")), Set.of(),
            ContractRef.of(OWNER, ResourceTypeId.of(payloadType)), null, null, false, null, null, null, null, null,
            0L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourcePageResponse(ResourceOperationKind.LIST,
                new ResourcePage<Map<String, Object>>(List.of(), null, true)));
    }

    private static long longField(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(client);
    }

    private static boolean booleanField(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(client);
    }

    private static final class CapturingTransport implements ReSyncFrameTransport {
        private final ReSyncFrameCodec codec = new ReSyncFrameCodec();
        private final AtomicInteger sentFrames = new AtomicInteger();
        private final ServerId peerServerId;
        private Consumer<byte[]> frameHandler;
        private Runnable closeHandler;

        private CapturingTransport() {
            this(null);
        }

        private CapturingTransport(ServerId peerServerId) {
            this.peerServerId = peerServerId;
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
            sentFrames.incrementAndGet();
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

        @Override
        public Optional<ServerId> peerServerId() {
            return Optional.ofNullable(peerServerId);
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

        private void receiveLegacyHandshake() {
            ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 4);
            payload.put((byte) 1);
            payload.putInt(0);
            payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
            payload.putInt(0);
            payload.putInt(0);
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE,
                payload.array(), (short) 0, 1));
        }
    }
}
