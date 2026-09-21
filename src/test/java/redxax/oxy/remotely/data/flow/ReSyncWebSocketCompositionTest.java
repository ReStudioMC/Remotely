package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ReSyncWebSocketCompositionTest {

    @Test
    void providerProfileCreatesTheConfiguredTransportAndTicketRotationReplacesIt() throws Exception {
        String serverId = "123e4567-e89b-42d3-a456-426614174000";
        String endpoint = "wss://example.test/ws/remotely-web/resync/" + serverId;
        AtomicReference<String> ticket = new AtomicReference<>("browser-ticket");
        AtomicReference<OpeningTransport> latestTransport = new AtomicReference<>();
        AtomicReference<String> createdEndpoint = new AtomicReference<>();
        ReSyncFrameTransportFactory transports = value -> {
            createdEndpoint.set(value);
            OpeningTransport transport = new OpeningTransport();
            latestTransport.set(transport);
            return transport;
        };
        ReSyncFlowClientFactory clients = (id, api, url, credential, supplied, state) ->
            supplied != null ? new ReSyncFlowClient(id, supplied, credential, ReSyncFlowClientContext.defaults(),
                TaskScheduler.unavailable(), Clock.system(), null, ReSyncCredentialProvider.apiKey())
                : new ReSyncFlowClient(id, api, url, credential, ReSyncFlowClientContext.defaults(),
                    TaskScheduler.unavailable(), Clock.system(), transports, null, ReSyncCredentialProvider.apiKey());
        ReSyncConnectionProfileProvider profiles = identity ->
            new ReSyncConnectionManager.ReSyncConnectionProfile(endpoint, ticket.get());
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null,
            ignored -> ReSyncCatalogPublicationCache.deferred(), clients, profiles,
            ReSyncConnectionNotificationSink.noop(), null, ReSyncFlowClientContext.defaults());
        try {
            ReSyncFlowClient client = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> manager.ensureFlowClientAsync(serverId, false).join());
            OpeningTransport transport = latestTransport.get();

            assertNotNull(client);
            assertSame(client, manager.getFlowClient(serverId));
            assertEquals(endpoint, createdEndpoint.get());
            assertEquals(1, transport.connectCalls.get());
            assertEquals(ticket.get(), transport.handshakeCredential());
            assertEquals(ReSyncFlowClient.ReadinessState.CONNECTING, client.readiness());

            manager.disconnectServerConnection(serverId);
            ticket.set("rotated-ticket");
            ReSyncFlowClient rotated = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> manager.ensureFlowClientAsync(serverId, false).join());
            OpeningTransport rotatedTransport = latestTransport.get();

            assertNotSame(client, rotated);
            assertNotSame(transport, rotatedTransport);
            assertEquals(1, rotatedTransport.connectCalls.get());
            assertEquals("rotated-ticket", rotatedTransport.handshakeCredential());
        } finally {
            manager.shutdownAll();
        }
    }

    private static final class OpeningTransport implements ReSyncFrameTransport {
        private final AtomicInteger connectCalls = new AtomicInteger();
        private volatile Consumer<byte[]> frameHandler = ignored -> {};
        private volatile Runnable openHandler = () -> {};
        private volatile Runnable closeHandler = () -> {};
        private volatile State state = State.NEW;
        private volatile byte[] handshake;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
            frameHandler = handler;
        }

        @Override
        public void setOpenHandler(Runnable handler) {
            openHandler = handler;
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public void connect() {
            connectCalls.incrementAndGet();
            state = State.OPEN;
            openHandler.run();
        }

        @Override
        public void send(byte[] frame) {
            handshake = frame.clone();
        }

        @Override
        public void close() {
            state = State.CLOSED;
            closeHandler.run();
        }

        @Override
        public boolean isOpen() {
            return state == State.OPEN;
        }

        @Override
        public State state() {
            return state;
        }

        private String handshakeCredential() {
            ReSyncDecodedFrame frame = new ReSyncFrameCodec().decode(handshake, null);
            ByteBuffer payload = ByteBuffer.wrap(frame.payload());
            byte[] credential = new byte[payload.getInt()];
            payload.get(credential);
            return new String(credential, StandardCharsets.UTF_8);
        }
    }
}
