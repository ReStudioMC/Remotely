package redxax.oxy.remotely.network;

import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.resync.network.NetworkEditorAssembler;
import restudio.resync.network.NetworkEditorChunk;
import restudio.resync.network.NetworkEditorClose;
import restudio.resync.network.NetworkEditorCodec;
import restudio.resync.network.NetworkFrameType;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

public final class ReSyncNetworkFrameTransport implements ReSyncFrameTransport {
    interface Channel {
        Async<Void> send(NetworkFrameType type, byte[] payload);
        boolean current();
        void remove(UUID tunnelId, ReSyncNetworkFrameTransport transport);
    }

    private static final int MAXIMUM_QUEUE_FRAMES = 16;
    private static final long TIMEOUT_MILLIS = 15_000;
    private final String nodeId;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Consumer<ReSyncNetworkFrameTransport> opener;
    private final Object lock = new Object();
    private final Deque<Outbound> queue = new ArrayDeque<>();
    private NetworkEditorAssembler assembler = new NetworkEditorAssembler();
    private volatile State state = State.NEW;
    private volatile boolean disposed;
    private String closeReason = "";
    private volatile Predicate<byte[]> frameHandler = ignored -> false;
    private volatile Runnable openHandler = () -> {};
    private volatile Runnable closeHandler = () -> {};
    private volatile Runnable sendReadyHandler = () -> {};
    private volatile Consumer<String> reasonHandler = ignored -> {};
    private volatile Consumer<Throwable> errorHandler = ignored -> {};
    private UUID tunnelId;
    private Channel channel;
    private long nextSequence;
    private long queuedBytes;
    private Outbound sending;
    private TaskScheduler.ScheduledTask openTimeout;
    private TaskScheduler.ScheduledTask inboundTimeout;
    private long inboundSequence = -1;

    ReSyncNetworkFrameTransport(String nodeId, TaskScheduler scheduler, Clock clock,
                                Consumer<ReSyncNetworkFrameTransport> opener) {
        this.nodeId = nodeId;
        this.scheduler = scheduler;
        this.clock = clock;
        this.opener = opener;
    }

    String nodeId() { return nodeId; }

    UUID tunnelId() {
        synchronized (lock) { return tunnelId; }
    }

    @Override
    public void setFrameHandler(Consumer<byte[]> handler) {
        frameHandler = handler == null ? ignored -> false : frame -> {
            handler.accept(frame);
            return true;
        };
    }

    @Override
    public void setFrameAdmissionHandler(Predicate<byte[]> handler) { frameHandler = handler == null ? ignored -> false : handler; }

    @Override
    public void setOpenHandler(Runnable handler) { openHandler = handler == null ? () -> {} : handler; }

    @Override
    public void setCloseHandler(Runnable handler) { closeHandler = handler == null ? () -> {} : handler; }

    @Override
    public void setSendReadyHandler(Runnable handler) { sendReadyHandler = handler == null ? () -> {} : handler; }

    @Override
    public void setCloseReasonHandler(Consumer<String> handler) { reasonHandler = handler == null ? ignored -> {} : handler; }

    @Override
    public void setErrorHandler(Consumer<Throwable> handler) { errorHandler = handler == null ? ignored -> {} : handler; }

    @Override
    public void connect() {
        UUID id;
        synchronized (lock) {
            if (disposed || state == State.CONNECTING || state == State.OPEN || state == State.CLOSING) return;
            state = State.CONNECTING;
            closeReason = "";
            tunnelId = UUID.randomUUID();
            id = tunnelId;
            nextSequence = 0;
            assembler = new NetworkEditorAssembler();
        }
        try {
            TaskScheduler.ScheduledTask timeout = scheduler.schedule(() -> {
                terminate(id, "Network Editor Open Timed Out", true, true, () -> state == State.CONNECTING);
            }, Duration.ofMillis(TIMEOUT_MILLIS));
            synchronized (lock) {
                if (matches(id, State.CONNECTING)) openTimeout = timeout;
                else timeout.cancel();
            }
            execute(id, () -> { if (matches(id, State.CONNECTING)) opener.accept(this); });
        } catch (RuntimeException failure) {
            fail(id, message(failure));
        }
    }

    boolean bind(UUID id, Channel candidate) {
        synchronized (lock) {
            if (!matches(id, State.CONNECTING) || channel != null || !candidate.current()) return false;
            channel = candidate;
            return true;
        }
    }

    void opened(UUID id) {
        synchronized (lock) {
            if (!matches(id, State.CONNECTING) || channel == null || !channel.current()) return;
            state = State.OPEN;
            cancel(openTimeout);
            openTimeout = null;
        }
        try { openHandler.run(); }
        catch (RuntimeException failure) { fail(id, message(failure)); }
    }

    void receive(NetworkEditorChunk chunk) {
        try {
            if (chunk.receipt()) {
                Outbound acknowledged;
                synchronized (lock) {
                    if (!matches(chunk.tunnelId(), State.OPEN) || channel == null || !channel.current()) return;
                    acknowledged = sending;
                    if (acknowledged == null || acknowledged.sequence != chunk.sequence() || acknowledged.acknowledged
                        || acknowledged.offeredEnd != acknowledged.bytes.length) {
                        throw new IllegalArgumentException("Network Editor Receipt Does Not Match");
                    }
                    acknowledged.acknowledged = true;
                }
                finishSend(acknowledged);
                return;
            }
            byte[] complete;
            Predicate<byte[]> handler;
            synchronized (lock) {
                if (!matches(chunk.tunnelId(), State.OPEN) || channel == null || !channel.current()) return;
                if (inboundTimeout == null) {
                    UUID id = tunnelId;
                    long sequence = chunk.sequence();
                    inboundSequence = sequence;
                    inboundTimeout = scheduler.schedule(() -> terminate(id, "Network Editor Frame Timed Out", true, true,
                        () -> inboundSequence == sequence), Duration.ofMillis(TIMEOUT_MILLIS));
                }
                complete = assembler.accept(chunk);
                if (complete == null) return;
                cancel(inboundTimeout);
                inboundTimeout = null;
                inboundSequence = -1;
                handler = frameHandler;
            }
            if (!handler.test(complete)) {
                fail(chunk.tunnelId(), "Network Editor Receive Queue Rejected The Frame");
                return;
            }
            Channel destination;
            synchronized (lock) {
                if (!matches(chunk.tunnelId(), State.OPEN) || channel == null || !channel.current()) return;
                destination = channel;
            }
            destination.send(NetworkFrameType.EDITOR_DATA, NetworkEditorCodec.encodeChunk(
                new NetworkEditorChunk(chunk.tunnelId(), chunk.sequence(), 0, 0, new byte[0])))
                .whenComplete((unused, failure) -> {
                    if (failure != null && matches(chunk.tunnelId(), State.OPEN)) fail(chunk.tunnelId(), message(failure));
                });
        } catch (RuntimeException failure) {
            fail(chunk.tunnelId(), message(failure));
        }
    }

    @Override
    public void send(byte[] frame) {
        if (!trySend(frame)) throw new IllegalStateException("Network Editor Send Queue Is Unavailable");
    }

    @Override
    public boolean trySend(byte[] frame) { return trySend(frame, null); }

    @Override
    public boolean trySend(byte[] frame, Consumer<SendResult> resultHandler) {
        return admitSend(frame, resultHandler) == SendAdmission.ACCEPTED;
    }

    @Override
    public SendAdmission admitSend(byte[] frame, Consumer<SendResult> resultHandler) {
        Outbound admitted;
        synchronized (lock) {
            if (state == State.FAILED || nextSequence == Long.MAX_VALUE) return SendAdmission.FAILED;
            if (state != State.OPEN || channel == null || !channel.current()) return SendAdmission.CLOSED;
            if (frame == null || frame.length == 0 || frame.length > NetworkEditorChunk.MAXIMUM_FRAME_BYTES) return SendAdmission.INVALID;
            if (queue.size() + (sending == null ? 0 : 1) >= MAXIMUM_QUEUE_FRAMES
                || queuedBytes + frame.length > NetworkEditorChunk.MAXIMUM_FRAME_BYTES) return SendAdmission.QUEUE_FULL;
            admitted = new Outbound(tunnelId, nextSequence++, frame.clone(),
                new SendTracker(System.nanoTime(), 1, frame.length, queue.size(), queuedBytes, resultHandler), clock.millis());
            queue.addLast(admitted);
            queuedBytes += frame.length;
        }
        try {
            TaskScheduler.ScheduledTask timeout = scheduler.schedule(() -> {
                terminate(admitted.tunnelId, "Network Editor Send Timed Out", true, true,
                    () -> sending == admitted || queue.contains(admitted));
            }, Duration.ofMillis(TIMEOUT_MILLIS));
            synchronized (lock) {
                if (admitted.tracker.completed()) timeout.cancel();
                else admitted.timeout = timeout;
            }
            execute(admitted.tunnelId, () -> drain(admitted.tunnelId));
        } catch (RuntimeException failure) {
            fail(admitted.tunnelId, message(failure));
        }
        return SendAdmission.ACCEPTED;
    }

    private void drain(UUID id) {
        Outbound current;
        synchronized (lock) {
            if (!matches(id, State.OPEN) || channel == null || !channel.current() || sending != null || queue.isEmpty()) return;
            sending = queue.removeFirst();
            current = sending;
        }
        sendChunk(current);
    }

    private void sendChunk(Outbound current) {
        Channel destination;
        byte[] payload;
        int end;
        synchronized (lock) {
            if (sending != current || !matches(current.tunnelId, State.OPEN) || channel == null || !channel.current()) return;
            if (clock.millis() - current.admittedAt >= TIMEOUT_MILLIS) {
                execute(current.tunnelId, () -> fail(current.tunnelId, "Network Editor Send Timed Out"));
                return;
            }
            end = Math.min(current.bytes.length, current.offset + NetworkEditorChunk.MAXIMUM_CHUNK_BYTES);
            payload = NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(current.tunnelId, current.sequence, current.offset,
                current.bytes.length, Arrays.copyOfRange(current.bytes, current.offset, end)));
            destination = channel;
            current.offeredEnd = end;
        }
        try {
            Async<Void> attempt = destination.send(NetworkFrameType.EDITOR_DATA, payload);
            synchronized (lock) {
                if (sending != current || !matches(current.tunnelId, State.OPEN)) {
                    attempt.cancel(false);
                    return;
                }
                current.attempt = attempt;
            }
            int next = end;
            attempt.whenComplete((unused, failure) -> execute(current.tunnelId, () -> {
                synchronized (lock) {
                    if (sending != current || !matches(current.tunnelId, State.OPEN)) return;
                    current.attempt = null;
                    if (failure == null) current.offset = next;
                }
                if (failure != null) fail(current.tunnelId, message(failure));
                else if (next < current.bytes.length) sendChunk(current);
                else finishSend(current);
            }));
        } catch (RuntimeException failure) {
            fail(current.tunnelId, message(failure));
        }
    }

    private void finishSend(Outbound current) {
        Runnable ready;
        synchronized (lock) {
            if (sending != current || !matches(current.tunnelId, State.OPEN) || !current.acknowledged || current.offset != current.bytes.length) return;
            sending = null;
            queuedBytes -= current.bytes.length;
            cancel(current.timeout);
            ready = sendReadyHandler;
        }
        current.tracker.sentFrame();
        drain(current.tunnelId);
        execute(current.tunnelId, () -> {
            if (!matches(current.tunnelId, State.OPEN)) return;
            try { ready.run(); }
            catch (RuntimeException failure) { fail(current.tunnelId, message(failure)); }
        });
    }

    void remoteClose(NetworkEditorClose close) {
        if (matches(close.tunnelId(), State.CONNECTING) || matches(close.tunnelId(), State.OPEN)) {
            terminate(close.tunnelId(), close.reason().isBlank() ? "Network Editor Closed" : close.reason(), false, false);
        }
    }

    void fail(String reason) { terminate(null, reason, true, true); }

    void fail(UUID id, String reason) { terminate(id, reason, true, true); }

    @Override
    public void retireSession() {
        terminate(null, "Network Editor Disconnected", false, true);
    }

    @Override
    public void close() {
        synchronized (lock) { disposed = true; }
        terminate(null, "Network Editor Closed", false, true);
    }

    @Override
    public boolean reusableAfterDisconnect() { return !disposed; }

    private void terminate(UUID expectedId, String reason, boolean failed, boolean notifyPeer) {
        terminate(expectedId, reason, failed, notifyPeer, () -> true);
    }

    private void terminate(UUID expectedId, String reason, boolean failed, boolean notifyPeer, BooleanSupplier applicable) {
        Channel previous;
        UUID id;
        List<Outbound> retired;
        Consumer<String> closingReason;
        Consumer<Throwable> closingError;
        Runnable closing;
        synchronized (lock) {
            if (!applicable.getAsBoolean() || expectedId != null && !expectedId.equals(tunnelId)
                || state == State.CLOSED || state == State.FAILED || state == State.CLOSING) return;
            closeReason = reason;
            state = failed ? State.FAILED : State.CLOSED;
            previous = channel;
            channel = null;
            id = tunnelId;
            closingReason = reasonHandler;
            closingError = errorHandler;
            closing = closeHandler;
            cancel(openTimeout);
            cancel(inboundTimeout);
            openTimeout = null;
            inboundTimeout = null;
            inboundSequence = -1;
            assembler.clear();
            retired = new ArrayList<>(queue);
            queue.clear();
            if (sending != null) retired.add(sending);
            sending = null;
            queuedBytes = 0;
            for (Outbound outbound : retired) cancel(outbound.timeout);
        }
        if (previous != null) {
            previous.remove(id, this);
            if (notifyPeer && previous.current()) {
                try { previous.send(NetworkFrameType.EDITOR_CLOSE, NetworkEditorCodec.encodeClose(
                    new NetworkEditorClose(id, failed ? 1011 : 1000, reason.substring(0, Math.min(256, reason.length()))))); }
                catch (RuntimeException ignored) { }
            }
        }
        try { closingReason.accept(reason); }
        catch (RuntimeException ignored) { }
        for (Outbound outbound : retired) {
            outbound.tracker.fail(reason);
            if (outbound.attempt != null) outbound.attempt.cancel(false);
        }
        if (failed) {
            try { closingError.accept(new IllegalStateException(reason)); }
            catch (RuntimeException ignored) { }
        }
        try { closing.run(); }
        catch (RuntimeException ignored) { }
    }

    private boolean matches(UUID id, State expected) {
        synchronized (lock) { return state == expected && id != null && id.equals(tunnelId); }
    }

    @Override
    public String closeReason() {
        synchronized (lock) { return closeReason; }
    }

    private void execute(UUID id, Runnable action) {
        try { scheduler.execute(action); }
        catch (RuntimeException failure) { terminate(id, "Network Editor Scheduler Unavailable", true, true); }
    }

    private static void cancel(TaskScheduler.ScheduledTask task) { if (task != null) task.cancel(); }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "Network Editor Unavailable" : error.getMessage();
    }

    @Override
    public boolean isOpen() {
        synchronized (lock) { return state == State.OPEN && channel != null && channel.current(); }
    }

    @Override
    public State state() { return state; }

    private static final class Outbound {
        private final UUID tunnelId;
        private final long sequence;
        private final byte[] bytes;
        private final SendTracker tracker;
        private final long admittedAt;
        private int offset;
        private int offeredEnd;
        private boolean acknowledged;
        private Async<Void> attempt;
        private TaskScheduler.ScheduledTask timeout;

        private Outbound(UUID tunnelId, long sequence, byte[] bytes, SendTracker tracker, long admittedAt) {
            this.tunnelId = tunnelId;
            this.sequence = sequence;
            this.bytes = bytes;
            this.tracker = tracker;
            this.admittedAt = admittedAt;
        }
    }
}
