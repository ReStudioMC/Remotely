package redxax.oxy.remotely.web.platform;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Uint8Array;
import restudio.rebase.backend.TerminalCapability;
import restudio.rebase.backend.TerminalSize;
import restudio.rebase.backend.TerminalTransport;
import restudio.rescreen.platform.Async;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class BrowserSshTerminalTransport implements TerminalTransport {
    private static final int MAX_FRAME_BYTES = 32 * 1024;
    private static final int MAX_PENDING_FRAMES = 16;
    private static final int MAX_PENDING_OUTPUT_CHUNKS = 128;
    private static final int MAX_PENDING_OUTPUT_CHARACTERS = 1024 * 1024;
    private static final Map<Integer, BrowserSshTerminalTransport> TRANSPORTS = new LinkedHashMap<>();
    private static int nextWorkerId = 1;

    private final Supplier<Async<BrowserSshSession>> connector;
    private final Runnable removeAction;
    private final WorkerBridge worker;
    private final Deque<byte[]> pendingInput = new ArrayDeque<>();
    private final Deque<String> pendingOutput = new ArrayDeque<>();
    private TerminalSize size;
    private Consumer<String> outputListener;
    private Consumer<String> disconnectListener;
    private Async<BrowserSshSession> pendingConnection;
    private int workerId;
    private int pendingOutputCharacters;
    private long generation;
    private boolean started;
    private boolean ready;
    private boolean closed;
    private boolean removed;
    private String closeReason = "Disconnected";

    BrowserSshTerminalTransport(Supplier<Async<BrowserSshSession>> connector, TerminalSize size, Runnable removeAction) {
        this(connector, size, removeAction, NativeWorkerBridge.INSTANCE);
    }

    BrowserSshTerminalTransport(Supplier<Async<BrowserSshSession>> connector, TerminalSize size, Runnable removeAction, WorkerBridge worker) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.size = normalized(size);
        this.removeAction = Objects.requireNonNull(removeAction, "removeAction");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        if (buffer == null || offset < 0 || length < 0 || offset + length > buffer.length) throw new IndexOutOfBoundsException();
        return closed ? -1 : 0;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return;
        List<byte[]> frames = frames(bytes);
        synchronized (this) {
            if (closed) throw new IOException("Terminal Is Closed");
            if (!ready) {
                if (pendingInput.size() + frames.size() > MAX_PENDING_FRAMES) throw new IOException("Terminal Input Queue Is Full");
                pendingInput.addAll(frames);
                return;
            }
            for (byte[] frame : frames) {
                if (!worker.input(workerId, frame)) {
                    terminate("Browser SSH Input Failed");
                    throw new IOException("Browser SSH Input Failed");
                }
            }
        }
    }

    @Override
    public synchronized boolean isConnected() {
        return ready && !closed;
    }

    @Override
    public synchronized TerminalCapability capability() {
        return ready && !closed ? TerminalCapability.full() : TerminalCapability.outputOnly();
    }

    @Override
    public boolean pushBased() {
        return true;
    }

    @Override
    public void start(Consumer<String> output, Consumer<String> disconnect) {
        synchronized (this) {
            outputListener = output;
            disconnectListener = disconnect;
            if (started || closed) return;
            started = true;
            generation++;
            workerId = register(this);
        }
        if (!worker.create(workerId)) terminate("Browser SSH Engine Could Not Start");
    }

    @Override
    public void resize(TerminalSize next) {
        int id;
        boolean send;
        TerminalSize resolved = normalized(next);
        synchronized (this) {
            size = resolved;
            id = workerId;
            send = ready && !closed;
        }
        if (send && !worker.resize(id, resolved)) terminate("Browser SSH Resize Failed");
    }

    @Override
    public int waitFor() {
        return 0;
    }

    @Override
    public synchronized boolean ready() {
        return ready && !closed;
    }

    @Override
    public void onOutput(Consumer<String> listener) {
        List<String> output;
        synchronized (this) {
            outputListener = listener;
            if (listener == null || pendingOutput.isEmpty()) return;
            output = new ArrayList<>(pendingOutput);
            pendingOutput.clear();
            pendingOutputCharacters = 0;
        }
        output.forEach(listener);
    }

    @Override
    public void onDisconnect(Consumer<String> listener) {
        String reason = null;
        synchronized (this) {
            disconnectListener = listener;
            if (listener != null && closed) reason = closeReason;
        }
        if (reason != null) listener.accept(reason);
    }

    @Override
    public String name() {
        return "Browser SSH";
    }

    @Override
    public void close() {
        terminate("Closed");
    }

    private void engineReady() {
        long currentGeneration;
        synchronized (this) {
            if (closed || !started || pendingConnection != null) return;
            currentGeneration = generation;
        }
        Async<BrowserSshSession> request;
        try {
            request = connector.get();
        } catch (RuntimeException failure) {
            connectionFailed(currentGeneration, failure);
            return;
        }
        if (request == null) {
            connectionFailed(currentGeneration, new IllegalStateException("Browser SSH Is Unavailable"));
            return;
        }
        synchronized (this) {
            if (closed || currentGeneration != generation) {
                request.cancel();
                return;
            }
            pendingConnection = request;
        }
        request.whenComplete((session, failure) -> {
            synchronized (this) {
                if (pendingConnection == request) pendingConnection = null;
            }
            if (failure != null) connectionFailed(currentGeneration, failure);
            else if (session == null) connectionFailed(currentGeneration, new IllegalStateException("Browser SSH Is Unavailable"));
            else open(currentGeneration, session);
        });
    }

    private void open(long currentGeneration, BrowserSshSession session) {
        TerminalSize currentSize;
        int id;
        synchronized (this) {
            if (closed || currentGeneration != generation) return;
            currentSize = size;
            id = workerId;
        }
        if (!worker.open(id, session, currentSize)) terminate("Browser SSH Could Not Start");
    }

    private void opened() {
        boolean failed = false;
        synchronized (this) {
            if (closed || ready) return;
            ready = true;
            while (!pendingInput.isEmpty()) {
                if (!worker.input(workerId, pendingInput.removeFirst())) {
                    failed = true;
                    break;
                }
            }
            pendingInput.clear();
        }
        if (failed) terminate("Browser SSH Input Failed");
    }

    private void appendOutput(String value) {
        if (value == null || value.isEmpty()) return;
        Consumer<String> listener;
        synchronized (this) {
            if (closed) return;
            listener = outputListener;
            if (listener == null) {
                while (!pendingOutput.isEmpty() && (pendingOutput.size() >= MAX_PENDING_OUTPUT_CHUNKS
                        || pendingOutputCharacters > MAX_PENDING_OUTPUT_CHARACTERS - Math.min(value.length(), MAX_PENDING_OUTPUT_CHARACTERS))) {
                    pendingOutputCharacters -= pendingOutput.removeFirst().length();
                }
                if (value.length() > MAX_PENDING_OUTPUT_CHARACTERS) value = value.substring(value.length() - MAX_PENDING_OUTPUT_CHARACTERS);
                pendingOutput.addLast(value);
                pendingOutputCharacters += value.length();
                return;
            }
        }
        listener.accept(value);
    }

    private void connectionFailed(long currentGeneration, Throwable failure) {
        synchronized (this) {
            if (closed || currentGeneration != generation) return;
        }
        terminate(message(failure));
    }

    private void terminate(String reason) {
        Async<BrowserSshSession> request;
        Consumer<String> listener;
        int id;
        boolean remove;
        synchronized (this) {
            if (closed) return;
            closed = true;
            ready = false;
            generation++;
            closeReason = safe(reason, "Disconnected");
            request = pendingConnection;
            pendingConnection = null;
            pendingInput.clear();
            pendingOutput.clear();
            pendingOutputCharacters = 0;
            listener = disconnectListener;
            id = workerId;
            remove = !removed;
            removed = true;
        }
        if (request != null) request.cancel();
        if (id != 0) {
            unregister(id);
            worker.dispose(id);
        }
        if (remove) removeAction.run();
        if (listener != null) listener.accept(closeReason);
    }

    private void message(String type, String code, String value) {
        switch (safe(type, "")) {
            case "engine-ready" -> engineReady();
            case "ready" -> opened();
            case "output" -> appendOutput(value);
            case "closed", "error" -> terminate(safe(value, "Browser SSH Connection Was Lost"));
            default -> terminate("Browser SSH Protocol Failed");
        }
    }

    private static List<byte[]> frames(byte[] bytes) {
        List<byte[]> result = new ArrayList<>((bytes.length + MAX_FRAME_BYTES - 1) / MAX_FRAME_BYTES);
        for (int offset = 0; offset < bytes.length; offset += MAX_FRAME_BYTES) {
            int length = Math.min(MAX_FRAME_BYTES, bytes.length - offset);
            byte[] frame = new byte[length];
            System.arraycopy(bytes, offset, frame, 0, length);
            result.add(frame);
        }
        return result;
    }

    private static TerminalSize normalized(TerminalSize value) {
        if (value == null) return new TerminalSize(120, 32);
        return new TerminalSize(Math.max(1, Math.min(4096, value.columns())), Math.max(1, Math.min(4096, value.rows())));
    }

    private static String message(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) current = current.getCause();
        return safe(current == null ? null : current.getMessage(), "Browser SSH Could Not Start");
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        StringBuilder result = new StringBuilder(Math.min(240, value.length()));
        for (int index = 0; index < value.length() && result.length() < 240; index++) {
            char character = value.charAt(index);
            if (!Character.isISOControl(character) || character == '\t') result.append(character);
        }
        return result.isEmpty() ? fallback : result.toString();
    }

    private static synchronized int register(BrowserSshTerminalTransport transport) {
        int id = nextWorkerId++;
        if (id <= 0) {
            nextWorkerId = 2;
            id = 1;
        }
        TRANSPORTS.put(id, transport);
        return id;
    }

    private static synchronized void unregister(int id) {
        TRANSPORTS.remove(id);
    }

    public static void workerMessage(int id, String type, String code, String message) {
        BrowserSshTerminalTransport transport;
        synchronized (BrowserSshTerminalTransport.class) {
            transport = TRANSPORTS.get(id);
        }
        if (transport != null) transport.message(type, code, message);
    }

    interface WorkerBridge {
        boolean create(int id);

        boolean open(int id, BrowserSshSession session, TerminalSize size);

        boolean input(int id, byte[] bytes);

        boolean resize(int id, TerminalSize size);

        void dispose(int id);
    }

    private enum NativeWorkerBridge implements WorkerBridge {
        INSTANCE;

        @Override
        public boolean create(int id) {
            return createWorker(id);
        }

        @Override
        public boolean open(int id, BrowserSshSession session, TerminalSize size) {
            return openWorker(id, session.endpoint().toString(), session.grant(), session.username(), session.password(),
                    session.hostKey(), size.columns(), size.rows());
        }

        @Override
        public boolean input(int id, byte[] bytes) {
            Uint8Array values = Uint8Array.create(bytes.length);
            for (int index = 0; index < bytes.length; index++) values.set(index, (short) (bytes[index] & 0xff));
            return inputWorker(id, values);
        }

        @Override
        public boolean resize(int id, TerminalSize size) {
            return resizeWorker(id, size.columns(), size.rows());
        }

        @Override
        public void dispose(int id) {
            disposeWorker(id);
        }
    }

    @JSBody(params = "id", script = """
            try {
                const state = window.__remotelySshWorkers || (window.__remotelySshWorkers = Object.create(null));
                const key = String(id);
                if (state[key]) return false;
                const worker = new Worker(new URL('ssh/restudio-ssh-worker.js', document.baseURI), {name: 'Reactor SSH'});
                worker.onmessage = function(event) {
                    const value = event && event.data && typeof event.data === 'object' ? event.data : {};
                    javaMethods.get('redxax.oxy.remotely.web.platform.BrowserSshTerminalTransport.workerMessage(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V').invoke(id, String(value.type || ''), String(value.code || ''), String(value.message || ''));
                };
                worker.onerror = function(event) {
                    if (event && typeof event.preventDefault === 'function') event.preventDefault();
                    javaMethods.get('redxax.oxy.remotely.web.platform.BrowserSshTerminalTransport.workerMessage(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V').invoke(id, 'error', 'engine_failed', 'Browser SSH Engine Failed');
                };
                state[key] = worker;
                return true;
            } catch (error) {
                return false;
            }
            """)
    private static native boolean createWorker(int id);

    @JSBody(params = {"id", "endpoint", "grant", "username", "password", "hostKey", "columns", "rows"}, script = """
            const state = window.__remotelySshWorkers;
            const worker = state && state[String(id)];
            if (!worker) return false;
            const value = {type: 'open', endpoint: endpoint, grant: grant, username: username, password: password, hostKey: hostKey, columns: columns, rows: rows};
            worker.postMessage(value);
            value.grant = '';
            value.password = '';
            return true;
            """)
    private static native boolean openWorker(int id, String endpoint, String grant, String username, String password,
                                             String hostKey, int columns, int rows);

    @JSBody(params = {"id", "bytes"}, script = """
            const state = window.__remotelySshWorkers;
            const worker = state && state[String(id)];
            if (!worker) return false;
            worker.postMessage({type: 'input', data: bytes}, [bytes.buffer]);
            return true;
            """)
    private static native boolean inputWorker(int id, Uint8Array bytes);

    @JSBody(params = {"id", "columns", "rows"}, script = """
            const state = window.__remotelySshWorkers;
            const worker = state && state[String(id)];
            if (!worker) return false;
            worker.postMessage({type: 'resize', columns: columns, rows: rows});
            return true;
            """)
    private static native boolean resizeWorker(int id, int columns, int rows);

    @JSBody(params = "id", script = """
            const state = window.__remotelySshWorkers;
            const key = String(id);
            const worker = state && state[key];
            if (!worker) return;
            worker.onmessage = null;
            worker.onerror = null;
            try { worker.postMessage({type: 'close'}); } catch (ignored) {}
            window.setTimeout(function() { try { worker.terminate(); } catch (ignored) {} }, 25);
            delete state[key];
            """)
    private static native void disposeWorker(int id);
}

record BrowserSshSession(int version, URI endpoint, String grant, String username, String password, String hostKey,
                         long expiresAt, String scope) {
    private static final Pattern HOST_KEY = Pattern.compile("^SHA256:[A-Za-z0-9+/]{43}=?$");

    BrowserSshSession {
        if (version != 1 || !validEndpoint(endpoint) || !bounded(grant, 8 * 1024) || !bounded(username, 512)
                || !bounded(password, 4096) || hostKey == null || !HOST_KEY.matcher(hostKey).matches()
                || expiresAt <= 0 || !"remotely.terminal.ssh".equals(scope)) {
            throw new IllegalArgumentException("Browser SSH Configuration Is Invalid");
        }
    }

    BrowserSshSession requireUsable(long now) {
        if (expiresAt <= now + 5_000L) throw new IllegalStateException("Browser SSH Grant Expired");
        return this;
    }

    @Override
    public String toString() {
        return "BrowserSshSession[version=" + version + ", endpoint=" + endpoint + ", grant=REDACTED, username=" + username
                + ", password=REDACTED, hostKey=" + hostKey + ", expiresAt=" + expiresAt + ", scope=" + scope + "]";
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validEndpoint(URI endpoint) {
        if (endpoint == null || !"wss".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getHost().isBlank()
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) return false;
        String path = endpoint.getPath();
        return path != null && path.equals(endpoint.getRawPath()) && path.endsWith("/api/browser-ssh")
                && !path.contains("//") && !path.contains("/../") && !path.contains("/./");
    }
}
