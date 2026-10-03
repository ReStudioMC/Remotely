package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.rebase.backend.TerminalCapability;
import restudio.rebase.backend.TerminalSize;
import restudio.rebase.backend.TerminalTransport;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.websocket.BinaryWebSocket;
import restudio.rescreen.platform.websocket.BinaryWebSocketListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

final class BrowserConsoleTransport implements TerminalTransport, BinaryWebSocketListener {
    private final Function<BinaryWebSocketListener, Async<BinaryWebSocket>> connector;
    private final Runnable removeAction;
    private final StringBuilder input = new StringBuilder();
    private Consumer<String> output;
    private Consumer<String> disconnect;
    private Async<BinaryWebSocket> pending;
    private BinaryWebSocket socket;
    private boolean started;
    private boolean ready;
    private boolean closed;

    BrowserConsoleTransport(Function<BinaryWebSocketListener, Async<BinaryWebSocket>> connector, Runnable removeAction) {
        this.connector = Objects.requireNonNull(connector);
        this.removeAction = Objects.requireNonNull(removeAction);
    }

    @Override
    public synchronized int read(char[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        return closed ? -1 : 0;
    }

    @Override
    public synchronized void write(byte[] bytes) throws IOException {
        if (!isConnected()) throw new IOException("Server Console Is Disconnected");
        if (bytes == null || bytes.length == 0) return;
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.length() > 65_536 || value.chars().anyMatch(character -> character == 0x7f
                || character < 0x20 && character != '\r' && character != '\n')) throw new IOException("Server Command Is Invalid");
        int length = input.length();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            length = character == '\r' || character == '\n' ? 0 : length + 1;
            if (length > 4096) throw new IOException("Server Command Is Invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\r' && character != '\n') {
                input.append(character);
                continue;
            }
            String command = input.toString();
            input.setLength(0);
            if (command.isBlank()) continue;
            JsonObject message = new JsonObject();
            message.addProperty("event", "send command");
            JsonArray args = new JsonArray();
            args.add(command);
            message.add("args", args);
            socket.sendText(message.toString()).whenComplete((ignored, failure) -> {
                if (failure != null) terminate("Server Command Failed");
            });
            if (closed) return;
        }
    }

    @Override
    public synchronized boolean isConnected() {
        return ready && !closed && socket != null && socket.isOpen();
    }

    @Override
    public synchronized TerminalCapability capability() {
        return isConnected() ? TerminalCapability.lineCommand() : TerminalCapability.outputOnly();
    }

    @Override
    public boolean pushBased() {
        return true;
    }

    @Override
    public synchronized void start(Consumer<String> output, Consumer<String> disconnect) {
        this.output = Objects.requireNonNull(output);
        this.disconnect = Objects.requireNonNull(disconnect);
        if (closed || started) return;
        started = true;
        try {
            pending = Objects.requireNonNull(connector.apply(this));
            pending.whenComplete((connected, failure) -> {
                synchronized (this) {
                    pending = null;
                    if (closed) {
                        if (connected != null) connected.close(1000, "Console Closed");
                        return;
                    }
                    if (failure != null || connected == null) terminate("Server Console Connection Failed");
                    else socket = connected;
                }
            });
        } catch (RuntimeException failure) {
            terminate("Server Console Connection Failed");
        }
    }

    @Override
    public synchronized void onOpen(BinaryWebSocket connected) {
        if (closed) connected.close(1000, "Console Closed");
        else socket = connected;
    }

    @Override
    public synchronized void onText(String text) {
        if (closed || !started) return;
        try {
            if (text == null || text.length() > 65_536) throw new IllegalArgumentException("Console Frame Is Invalid");
            JsonObject message = BrowserJson.object(text);
            String event = BrowserJson.string(message, "event");
            if ("auth success".equals(event)) ready = true;
            else if ("console output".equals(event)) {
                JsonElement args = message.get("args");
                if (args == null || !args.isJsonArray()) throw new IllegalArgumentException("Console Output Is Invalid");
                for (JsonElement line : args.getAsJsonArray()) {
                    if (!line.isJsonPrimitive()) throw new IllegalArgumentException("Console Output Is Invalid");
                    if (output != null) output.accept(line.getAsString() + "\r\n");
                }
            }
        } catch (RuntimeException failure) {
            terminate("Server Console Frame Is Invalid");
        }
    }

    @Override
    public void onClose(int statusCode, String reason) {
        terminate("Server Console Disconnected");
    }

    @Override
    public void onError(Throwable error) {
        terminate("Server Console Connection Failed");
    }

    @Override
    public synchronized void onOutput(Consumer<String> listener) {
        output = listener;
    }

    @Override
    public synchronized void onDisconnect(Consumer<String> listener) {
        disconnect = listener;
    }

    @Override
    public void resize(TerminalSize size) {
    }

    @Override
    public int waitFor() {
        return 0;
    }

    @Override
    public synchronized boolean ready() {
        return isConnected();
    }

    @Override
    public String name() {
        return "Server Console";
    }

    @Override
    public void close() {
        terminate("Server Console Closed");
    }

    private synchronized void terminate(String reason) {
        if (closed) return;
        closed = true;
        ready = false;
        input.setLength(0);
        if (pending != null) pending.cancel();
        if (socket != null) socket.close(1000, "Console Closed");
        removeAction.run();
        if (disconnect != null) disconnect.accept(reason);
    }
}
