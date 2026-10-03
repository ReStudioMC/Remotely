package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.websocket.BinaryWebSocket;
import restudio.rescreen.platform.websocket.BinaryWebSocketListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserConsoleTransportTest {
    @Test
    void authenticatesBeforeLineInputAndUsesTheConsoleProtocol() throws IOException {
        Socket socket = new Socket();
        List<String> output = new ArrayList<>();
        BrowserConsoleTransport transport = new BrowserConsoleTransport(listener -> {
            listener.onOpen(socket);
            return Async.completed(socket);
        }, () -> {});
        transport.start(output::add, ignored -> {});

        assertFalse(transport.capability().supportsLineInput());
        assertThrows(IOException.class, () -> transport.write("list\r"));
        transport.onText("{\"event\":\"auth success\",\"args\":[]}");
        assertTrue(transport.isConnected());
        assertTrue(transport.capability().supportsLineInput());
        assertFalse(transport.capability().supportsControlInput());
        assertFalse(transport.capability().supportsResize());
        transport.write("li");
        transport.write("st\r\nversion\n");
        assertEquals(List.of("list", "version"), socket.messages.stream()
                .map(BrowserJson::object).map(value -> value.getAsJsonArray("args").get(0).getAsString()).toList());
        assertTrue(socket.messages.stream().allMatch(value -> "send command".equals(BrowserJson.string(BrowserJson.object(value), "event"))));
        transport.onText("{\"event\":\"console output\",\"args\":[\"Paper Ready\"]}");
        assertEquals(List.of("Paper Ready\r\n"), output);
    }

    @Test
    void rejectsControlInputAndBoundsUnterminatedCommandsWithoutDispatch() throws IOException {
        Socket socket = new Socket();
        BrowserConsoleTransport transport = connected(socket);

        assertThrows(IOException.class, () -> transport.write("list\n\u0003"));
        assertTrue(socket.messages.isEmpty());
        transport.write("x".repeat(4096));
        assertThrows(IOException.class, () -> transport.write("y"));
        assertTrue(socket.messages.isEmpty());
        transport.write("\n");
        assertEquals(4096, BrowserJson.object(socket.messages.getFirst()).getAsJsonArray("args").get(0).getAsString().length());
        transport.close();
        assertThrows(IOException.class, () -> transport.write("list\n"));
    }

    @Test
    void closeCancelsAdmissionAndFencesLateSocketAndOutput() {
        Async<BinaryWebSocket> pending = Async.pending();
        AtomicReference<BinaryWebSocketListener> listener = new AtomicReference<>();
        List<String> output = new ArrayList<>();
        BrowserConsoleTransport transport = new BrowserConsoleTransport(value -> {
            listener.set(value);
            return pending;
        }, () -> {});
        transport.start(output::add, ignored -> {});
        transport.close();
        Socket late = new Socket();
        listener.get().onOpen(late);
        listener.get().onText("{\"event\":\"console output\",\"args\":[\"Old Lease Output\"]}");

        assertTrue(pending.isCancelled());
        assertFalse(late.isOpen());
        assertFalse(transport.isConnected());
        assertTrue(output.isEmpty());
    }

    @Test
    void failedSendAndMalformedOutputCloseTheConsole() throws IOException {
        Socket socket = new Socket();
        BrowserConsoleTransport transport = connected(socket);
        socket.failSend = true;
        transport.write("list\n");
        assertFalse(transport.isConnected());
        assertFalse(socket.isOpen());

        Socket other = new Socket();
        BrowserConsoleTransport malformed = connected(other);
        malformed.onText("{\"event\":\"console output\",\"args\":{}}");
        assertFalse(malformed.isConnected());
        assertFalse(other.isOpen());
    }

    private BrowserConsoleTransport connected(Socket socket) {
        BrowserConsoleTransport transport = new BrowserConsoleTransport(listener -> {
            listener.onOpen(socket);
            return Async.completed(socket);
        }, () -> {});
        transport.start(ignored -> {}, ignored -> {});
        transport.onText("{\"event\":\"auth success\",\"args\":[]}");
        return transport;
    }

    private static final class Socket implements BinaryWebSocket {
        private final List<String> messages = new ArrayList<>();
        private boolean open = true;
        private boolean failSend;

        @Override
        public Async<Void> sendText(String text) {
            if (failSend) return Async.failed(new IOException("Disconnected"));
            messages.add(text);
            return Async.completed(null);
        }

        @Override
        public Async<Void> sendBinary(byte[] bytes) {
            return Async.failed(new IOException("Text Protocol Required"));
        }

        @Override
        public Async<Void> close(int statusCode, String reason) {
            open = false;
            return Async.completed(null);
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
