package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import restudio.rebase.backend.TerminalSize;
import restudio.rescreen.platform.Async;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserSshTerminalTransportTest {
    private static final BrowserSshSession SESSION = new BrowserSshSession(1,
            URI.create("wss://dev.restudiomc.net/wings-proxy/8c470a20-d92d-426c-93aa-b7ceaddabff1/api/browser-ssh"),
            "opaque-grant", "provider-user", "opaque-password", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            4_102_444_800_000L, "remotely.terminal.ssh");

    @Test
    void workerWaitsForEngineBeforeIssuingCapabilityAndPublishesFullPtyOnlyAfterReady() throws IOException {
        AtomicInteger capabilities = new AtomicInteger();
        AtomicInteger removals = new AtomicInteger();
        AtomicReference<String> output = new AtomicReference<>();
        AtomicReference<String> disconnect = new AtomicReference<>();
        TestWorker worker = new TestWorker();
        BrowserSshTerminalTransport transport = new BrowserSshTerminalTransport(() -> {
            capabilities.incrementAndGet();
            return Async.completed(SESSION);
        }, new TerminalSize(120, 32), removals::incrementAndGet, worker);
        transport.onOutput(output::set);
        transport.onDisconnect(disconnect::set);

        transport.start(output::set, disconnect::set);
        assertEquals(0, capabilities.get());
        assertFalse(transport.capability().supportsLineInput());
        BrowserSshTerminalTransport.workerMessage(worker.id, "engine-ready", "", "");
        assertEquals(1, capabilities.get());
        assertEquals(SESSION, worker.session);
        assertFalse(transport.isConnected());

        transport.write("version\r".getBytes(StandardCharsets.UTF_8));
        assertTrue(worker.input.isEmpty());
        BrowserSshTerminalTransport.workerMessage(worker.id, "ready", "", "");
        assertTrue(transport.isConnected());
        assertTrue(transport.capability().supportsLineInput());
        assertTrue(transport.capability().supportsControlInput());
        assertTrue(transport.capability().supportsResize());
        assertArrayEquals("version\r".getBytes(StandardCharsets.UTF_8), worker.input.getFirst());

        BrowserSshTerminalTransport.workerMessage(worker.id, "output", "", "Done\r\n");
        assertEquals("Done\r\n", output.get());
        transport.resize(new TerminalSize(151, 41));
        assertEquals(new TerminalSize(151, 41), worker.size);
        BrowserSshTerminalTransport.workerMessage(worker.id, "error", "connection_lost", "Browser SSH Connection Was Lost");
        assertEquals("Browser SSH Connection Was Lost", disconnect.get());
        assertEquals(1, removals.get());
        assertEquals(1, worker.disposals);
    }

    @Test
    void closeCancelsPendingCapabilityAndRejectsLateCompletion() {
        Async<BrowserSshSession> pending = Async.pending();
        AtomicInteger removals = new AtomicInteger();
        TestWorker worker = new TestWorker();
        BrowserSshTerminalTransport transport = new BrowserSshTerminalTransport(() -> pending,
                new TerminalSize(120, 32), removals::incrementAndGet, worker);

        transport.start(ignored -> {}, ignored -> {});
        BrowserSshTerminalTransport.workerMessage(worker.id, "engine-ready", "", "");
        transport.close();
        pending.complete(SESSION);

        assertTrue(pending.isCancelled());
        assertEquals(0, worker.opens);
        assertEquals(1, worker.disposals);
        assertEquals(1, removals.get());
    }

    @Test
    void invalidOrExpiringCapabilityIsRejectedAndSecretsAreRedacted() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserSshSession(1,
                URI.create("ws://example.com/api/browser-ssh"), "grant", "user", "password",
                "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 100_000, "remotely.terminal.ssh"));
        assertThrows(IllegalStateException.class, () -> SESSION.requireUsable(SESSION.expiresAt() - 4_999));
        assertFalse(SESSION.toString().contains("opaque-grant"));
        assertFalse(SESSION.toString().contains("opaque-password"));
    }

    @Test
    void pendingInputIsBounded() {
        TestWorker worker = new TestWorker();
        BrowserSshTerminalTransport transport = new BrowserSshTerminalTransport(() -> Async.completed(SESSION),
                new TerminalSize(120, 32), () -> {}, worker);
        transport.start(ignored -> {}, ignored -> {});
        byte[] frame = new byte[32 * 1024];

        for (int index = 0; index < 16; index++) {
            try {
                transport.write(frame);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }
        assertThrows(IOException.class, () -> transport.write(new byte[]{1}));
        transport.close();
    }

    private static final class TestWorker implements BrowserSshTerminalTransport.WorkerBridge {
        private final List<byte[]> input = new ArrayList<>();
        private int id;
        private int opens;
        private int disposals;
        private BrowserSshSession session;
        private TerminalSize size;

        @Override
        public boolean create(int id) {
            this.id = id;
            return true;
        }

        @Override
        public boolean open(int id, BrowserSshSession session, TerminalSize size) {
            opens++;
            this.session = session;
            this.size = size;
            return true;
        }

        @Override
        public boolean input(int id, byte[] bytes) {
            input.add(bytes.clone());
            return true;
        }

        @Override
        public boolean resize(int id, TerminalSize size) {
            this.size = size;
            return true;
        }

        @Override
        public void dispose(int id) {
            disposals++;
        }
    }
}
