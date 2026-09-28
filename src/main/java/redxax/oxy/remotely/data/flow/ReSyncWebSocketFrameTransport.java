package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;

import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.websocket.BinaryWebSocket;
import restudio.rescreen.platform.websocket.BinaryWebSocketListener;
import restudio.rescreen.platform.websocket.WebSocketOptions;
import restudio.rescreen.platform.websocket.WebSocketTransport;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ReSyncWebSocketFrameTransport implements ReSyncFrameTransport {
    private final String endpoint;
    private final WebSocketTransport transport;
    private final Supplier<WebSocketOptions> optionsProvider;
    private final boolean reconnectable;
    private final BrowserSafeState.ReferenceValue<State> state = new BrowserSafeState.ReferenceValue<>(State.NEW);
    private final BrowserSafeState.IntegerValue connectionGeneration = new BrowserSafeState.IntegerValue();
    private final Object lifecycleLock = new Object();
    private volatile Consumer<byte[]> frameHandler = ignored -> {};
    private volatile Runnable openHandler = () -> {};
    private volatile Runnable closeHandler = () -> {};
    private volatile Consumer<String> closeReasonHandler = ignored -> {};
    private volatile Consumer<Throwable> errorHandler = ignored -> {};
    private volatile BinaryWebSocket socket;
    private volatile ConnectionAttempt pendingAttempt;

    public ReSyncWebSocketFrameTransport(String endpoint, WebSocketTransport transport) {
        this(endpoint, transport, WebSocketOptions.empty());
    }

    public ReSyncWebSocketFrameTransport(String endpoint, WebSocketTransport transport, WebSocketOptions options) {
        this(endpoint, transport, () -> options == null ? WebSocketOptions.empty() : options);
    }

    public ReSyncWebSocketFrameTransport(String endpoint, WebSocketTransport transport, Supplier<WebSocketOptions> optionsProvider) {
        this(endpoint, transport, optionsProvider, true);
    }

    public ReSyncWebSocketFrameTransport(String endpoint, WebSocketTransport transport,
                                         Supplier<WebSocketOptions> optionsProvider, boolean reconnectable) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.optionsProvider = Objects.requireNonNull(optionsProvider, "optionsProvider");
        this.reconnectable = reconnectable;
    }

    @Override
    public void setFrameHandler(Consumer<byte[]> handler) {
        frameHandler = handler != null ? handler : ignored -> {};
    }

    @Override
    public void setOpenHandler(Runnable handler) {
        openHandler = handler != null ? handler : () -> {};
    }

    @Override
    public void setCloseHandler(Runnable handler) {
        closeHandler = handler != null ? handler : () -> {};
    }

    @Override
    public void setCloseReasonHandler(Consumer<String> handler) {
        closeReasonHandler = handler != null ? handler : ignored -> {};
    }

    @Override
    public void setErrorHandler(Consumer<Throwable> handler) {
        errorHandler = handler != null ? handler : ignored -> {};
    }

    @Override
    public void renewSession(String ticket) {
        BinaryWebSocket current = socket;
        if (current == null || !current.isOpen() || ticket == null || ticket.isBlank()) return;
        current.sendText("resync-ticket." + ticket).whenComplete((ignored, failure) -> {
            if (failure != null && socket == current && current.isOpen()) errorHandler.accept(failure);
        });
    }

    @Override
    public void connect() {
        int generation;
        ConnectionAttempt attempt;
        synchronized (lifecycleLock) {
            if (!state.compareAndSet(State.NEW, State.CONNECTING) && !state.compareAndSet(State.CLOSED, State.CONNECTING)
                && !state.compareAndSet(State.FAILED, State.CONNECTING)) {
                return;
            }
            generation = connectionGeneration.incrementAndGet();
            attempt = new ConnectionAttempt();
            pendingAttempt = attempt;
        }
        ConnectionAttempt connectionAttempt = attempt;
        BinaryWebSocketListener listener = new BinaryWebSocketListener() {
            @Override
            public void onOpen(BinaryWebSocket next) {
                boolean opened = false;
                boolean reject = false;
                synchronized (lifecycleLock) {
                    BinaryWebSocket accepted = connectionAttempt.acceptedSocket.get();
                    if (connectionGeneration.get() != generation || state.get() != State.CONNECTING) {
                        reject = accepted != next && socket != next;
                    } else if (accepted == next || socket == next) {
                        return;
                    } else if (accepted != null || socket != null) {
                        reject = true;
                    } else {
                        connectionAttempt.acceptedSocket.set(next);
                        socket = next;
                        state.set(State.OPEN);
                        opened = true;
                    }
                }
                if (reject) {
                    connectionAttempt.reject(next, "Connection Superseded");
                    return;
                }
                if (opened) {
                    openHandler.run();
                }
            }

            @Override
            public void onText(String text) {
                if (connectionGeneration.get() != generation) {
                    return;
                }
                errorHandler.accept(new IllegalStateException("ReSync WebSocket received text data"));
            }

            @Override
            public void onBinary(byte[] bytes) {
                Consumer<byte[]> handler = frameHandler;
                if (connectionGeneration.get() == generation && state.get() == State.OPEN) {
                    handler.accept(bytes);
                }
            }

            @Override
            public void onClose(int statusCode, String reason) {
                Consumer<String> reasonHandler;
                Runnable handler;
                synchronized (lifecycleLock) {
                    if (connectionGeneration.get() != generation || state.get() == State.CLOSED) {
                        return;
                    }
                    socket = null;
                    state.set(State.CLOSED);
                    reasonHandler = closeReasonHandler;
                    handler = closeHandler;
                }
                reasonHandler.accept(reason);
                handler.run();
            }

            @Override
            public void onError(Throwable error) {
                Consumer<Throwable> handler = errorHandler;
                synchronized (lifecycleLock) {
                    if (connectionGeneration.get() != generation || state.get() == State.CLOSING || state.get() == State.CLOSED) {
                        return;
                    }
                    state.set(State.FAILED);
                }
                handler.accept(error);
            }
        };
        Async<BinaryWebSocket> connection;
        try {
            synchronized (lifecycleLock) {
                if (connectionGeneration.get() != generation || pendingAttempt != connectionAttempt
                    || state.get() != State.CONNECTING) {
                    return;
                }
            }
            WebSocketOptions options = optionsProvider.get();
            connection = transport.connectAsync(endpoint, options == null ? WebSocketOptions.empty() : options, listener);
            Objects.requireNonNull(connection, "WebSocket transport returned no connection");
        } catch (RuntimeException exception) {
            boolean notify;
            synchronized (lifecycleLock) {
                notify = connectionGeneration.get() == generation && state.get() != State.CLOSING && state.get() != State.CLOSED;
                if (notify) {
                    state.set(State.FAILED);
                }
                if (pendingAttempt == connectionAttempt) {
                    pendingAttempt = null;
                }
            }
            if (notify) {
                errorHandler.accept(exception);
            }
            return;
        }
        connectionAttempt.setConnection(connection);
        connection.whenComplete((next, error) -> {
            boolean opened = false;
            boolean reject = false;
            boolean notifyError = false;
            synchronized (lifecycleLock) {
                if (pendingAttempt == connectionAttempt) {
                    pendingAttempt = null;
                }
                BinaryWebSocket accepted = connectionAttempt.acceptedSocket.get();
                if (connectionGeneration.get() != generation || state.get() == State.CLOSING || state.get() == State.CLOSED) {
                    reject = next != null && accepted != next && socket != next;
                } else if (error != null) {
                    state.set(State.FAILED);
                    notifyError = true;
                } else if (next != null) {
                    if (accepted == next || socket == next) {
                        return;
                    }
                    if (accepted != null || socket != null || state.get() != State.CONNECTING) {
                        reject = true;
                    } else {
                        connectionAttempt.acceptedSocket.set(next);
                        socket = next;
                        state.set(State.OPEN);
                        opened = true;
                    }
                }
            }
            if (reject) {
                connectionAttempt.reject(next, "Connection Superseded");
            }
            if (notifyError) {
                errorHandler.accept(error);
            } else if (opened) {
                openHandler.run();
            }
        });
        boolean cancel;
        synchronized (lifecycleLock) {
            cancel = connectionGeneration.get() != generation || state.get() == State.CLOSING
                || state.get() == State.CLOSED || state.get() == State.FAILED;
        }
        if (cancel) {
            connectionAttempt.cancel();
        }
    }

    @Override
    public void send(byte[] frame) {
        Consumer<Throwable> handler = errorHandler;
        BinaryWebSocket current = socket;
        int generation = connectionGeneration.get();
        if (state.get() != State.OPEN || current == null || !current.isOpen()) {
            throw new IllegalStateException("ReSync WebSocket is not open");
        }
        current.sendBinary(frame).exceptionally(error -> {
            if (connectionGeneration.get() == generation && socket == current && state.get() == State.OPEN) {
                handler.accept(error);
            }
            return null;
        });
    }

    @Override
    public void close() {
        Consumer<Throwable> handler = errorHandler;
        BinaryWebSocket current;
        ConnectionAttempt attempt;
        synchronized (lifecycleLock) {
            State previous = state.getAndSet(State.CLOSING);
            if (previous == State.CLOSED || previous == State.NEW) {
                state.set(State.CLOSED);
                return;
            }
            current = socket;
            attempt = pendingAttempt;
            if (current == null) {
                connectionGeneration.incrementAndGet();
                state.set(State.CLOSED);
            }
        }
        if (attempt != null) {
            attempt.cancel();
        }
        if (current == null) {
            return;
        }
        current.close(1000, "Client Closed").exceptionally(error -> {
            if (socket == current && state.get() == State.CLOSING) {
                handler.accept(error);
            }
            return null;
        });
    }

    @Override
    public boolean isOpen() {
        BinaryWebSocket current = socket;
        return state.get() == State.OPEN && current != null && current.isOpen();
    }

    @Override
    public State state() {
        return state.get();
    }

    @Override
    public boolean reconnectable() {
        return reconnectable;
    }

    private static final class ConnectionAttempt {
        private final BrowserSafeState.ReferenceValue<Async<BinaryWebSocket>> connection = new BrowserSafeState.ReferenceValue<>();
        private final BrowserSafeState.BooleanValue cancelled = new BrowserSafeState.BooleanValue();
        private final BrowserSafeState.ReferenceValue<BinaryWebSocket> acceptedSocket = new BrowserSafeState.ReferenceValue<>();
        private final BrowserSafeState.ReferenceValue<BinaryWebSocket> rejectedSocket = new BrowserSafeState.ReferenceValue<>();

        private void setConnection(Async<BinaryWebSocket> value) {
            connection.set(value);
            if (cancelled.get()) {
                value.cancel();
            }
        }

        private void cancel() {
            cancelled.set(true);
            Async<BinaryWebSocket> value = connection.get();
            if (value != null) {
                value.cancel();
            }
        }

        private void reject(BinaryWebSocket value, String reason) {
            if (value == null || !rejectedSocket.compareAndSet(null, value)) {
                return;
            }
            try {
                value.close(1000, reason);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
