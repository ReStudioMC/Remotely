package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.websocket.BinaryWebSocket;
import restudio.rescreen.platform.websocket.BinaryWebSocketListener;
import restudio.rescreen.platform.websocket.WebSocketTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncWebSocketFrameTransportTest {
    @Test
    void openingBeforeTheConnectFutureCompletesDoesNotCancelTheConnection() {
        Transport sockets = new Transport();
        ReSyncWebSocketFrameTransport transport = new ReSyncWebSocketFrameTransport("ws://localhost", sockets);
        AtomicInteger errors = new AtomicInteger();
        transport.setErrorHandler(ignored -> errors.incrementAndGet());
        transport.connect();

        assertTrue(transport.isOpen());
        assertFalse(sockets.connecting.isCancelled());
        sockets.connecting.complete(sockets.latest());
        assertTrue(transport.isOpen());
        assertEquals(0, errors.get());
    }

    @Test
    void failedOldSendsAndDuplicateCloseEventsCannotRetireTheReplacement() {
        Transport sockets = new Transport();
        ReSyncWebSocketFrameTransport transport = new ReSyncWebSocketFrameTransport("ws://localhost", sockets);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        transport.setErrorHandler(ignored -> errors.incrementAndGet());
        transport.setCloseHandler(closes::incrementAndGet);
        transport.connect();
        Socket old = sockets.latest();
        sockets.connecting.complete(old);
        transport.send(new byte[] {1});
        old.listener.onClose(1006, "Lost");
        old.listener.onClose(1006, "Lost");
        assertEquals(1, closes.get());
        transport.connect();
        sockets.connecting.complete(sockets.latest());
        old.send.fail(new IllegalStateException("Late Send Failure"));
        old.listener.onClose(1006, "Late Close");
        old.listener.onError(new IllegalStateException("Late Error"));

        assertTrue(transport.isOpen());
        assertEquals(0, errors.get());
        assertEquals(1, closes.get());
    }

    private static final class Transport implements WebSocketTransport {
        private final List<Socket> sockets = new ArrayList<>();
        private Async<BinaryWebSocket> connecting;

        @Override
        public Async<BinaryWebSocket> connectAsync(String uri, Map<String, String> headers, BinaryWebSocketListener listener) {
            connecting = Async.pending();
            Socket socket = new Socket(listener);
            sockets.add(socket);
            listener.onOpen(socket);
            return connecting;
        }

        private Socket latest() {
            return sockets.getLast();
        }
    }

    private static final class Socket implements BinaryWebSocket {
        private final BinaryWebSocketListener listener;
        private final Async<Void> send = Async.pending();

        private Socket(BinaryWebSocketListener listener) {
            this.listener = listener;
        }

        @Override public Async<Void> sendText(String text) { return send; }
        @Override public Async<Void> sendBinary(byte[] bytes) { return send; }
        @Override public Async<Void> close(int code, String reason) { return Async.completed(null); }
        @Override public boolean isOpen() { return true; }
    }
}
