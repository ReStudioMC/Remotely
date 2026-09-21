package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.identity.ServerId;

import java.util.Optional;
import java.util.function.Consumer;

public interface ReSyncFrameTransport {
    enum CallbackPublication {
        EXECUTED,
        DEFERRED,
        STALE
    }

    interface SendResult {
        long completedAtNanos();

        long enqueueToCompletionNanos();

        int frameCount();

        long byteCount();

        int queuedFrameCount();

        long queuedByteCount();

        boolean delivered();

        String reason();
    }

    record SendReceipt(long completedAtNanos, long enqueueToCompletionNanos, int frameCount, long byteCount,
                       int queuedFrameCount, long queuedByteCount) implements SendResult {
        public SendReceipt {
            if (enqueueToCompletionNanos < 0L || frameCount < 1 || byteCount < 0L || queuedFrameCount < 0
                || queuedByteCount < 0L) {
                throw new IllegalArgumentException("Invalid transport send receipt");
            }
        }

        public static SendReceipt immediate(long enqueuedAtNanos, int byteCount) {
            long sentAtNanos = System.nanoTime();
            return new SendReceipt(sentAtNanos, Math.max(0L, sentAtNanos - enqueuedAtNanos), 1,
                Math.max(0, byteCount), 0, 0L);
        }

        @Override
        public boolean delivered() {
            return true;
        }

        @Override
        public String reason() {
            return "";
        }
    }

    record SendFailure(long completedAtNanos, long enqueueToCompletionNanos, int frameCount, long byteCount,
                       int queuedFrameCount, long queuedByteCount, String reason) implements SendResult {
        public SendFailure {
            if (enqueueToCompletionNanos < 0L || frameCount < 1 || byteCount < 0L || queuedFrameCount < 0
                || queuedByteCount < 0L || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Invalid transport send failure");
            }
        }

        @Override
        public boolean delivered() {
            return false;
        }
    }

    final class SendTracker {
        private final long enqueuedAtNanos;
        private final int queuedFrameCount;
        private final long queuedByteCount;
        private final Consumer<SendResult> resultHandler;
        private int frameCount;
        private long byteCount;
        private int remainingFrames;
        private boolean completed;
        private SendResult terminalResult;
        private boolean published;

        public SendTracker(long enqueuedAtNanos, int frameCount, long byteCount, int queuedFrameCount,
                           long queuedByteCount, Consumer<SendResult> resultHandler) {
            if (frameCount < 1 || byteCount < 0L || queuedFrameCount < 0 || queuedByteCount < 0L) {
                throw new IllegalArgumentException("Invalid transport send tracker");
            }
            this.enqueuedAtNanos = enqueuedAtNanos;
            this.frameCount = frameCount;
            this.byteCount = byteCount;
            this.remainingFrames = frameCount;
            this.queuedFrameCount = queuedFrameCount;
            this.queuedByteCount = queuedByteCount;
            this.resultHandler = resultHandler;
        }

        public void encoded(int encodedFrameCount, long encodedByteCount) {
            synchronized (this) {
                if (completed) {
                    return;
                }
                if (encodedFrameCount < 1 || encodedByteCount < 0L) {
                    throw new IllegalArgumentException("Invalid encoded transport send");
                }
                frameCount = encodedFrameCount;
                byteCount = encodedByteCount;
                remainingFrames = encodedFrameCount;
            }
        }

        public boolean sentFrame() {
            SendResult result = claimSentFrame();
            publish(result);
            return result != null;
        }

        public synchronized SendResult claimSentFrame() {
            if (completed || remainingFrames < 1 || --remainingFrames != 0) {
                return null;
            }
            completed = true;
            long completedAtNanos = System.nanoTime();
            terminalResult = new SendReceipt(completedAtNanos,
                Math.max(0L, completedAtNanos - enqueuedAtNanos), frameCount, byteCount, queuedFrameCount,
                queuedByteCount);
            return terminalResult;
        }

        public boolean fail(String reason) {
            SendResult result = claimFailure(reason);
            publish(result);
            return result != null;
        }

        public synchronized SendResult claimFailure(String reason) {
            if (completed) {
                return null;
            }
            completed = true;
            long completedAtNanos = System.nanoTime();
            terminalResult = new SendFailure(completedAtNanos,
                Math.max(0L, completedAtNanos - enqueuedAtNanos), frameCount, byteCount, queuedFrameCount,
                queuedByteCount, reason);
            return terminalResult;
        }

        public boolean publish(SendResult result) {
            Consumer<SendResult> handler;
            synchronized (this) {
                if (result == null || terminalResult != result || published) {
                    return true;
                }
                published = true;
                handler = resultHandler;
            }
            if (handler == null) {
                return true;
            }
            try {
                handler.accept(result);
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }

        public synchronized boolean expired(long nowNanos, long timeoutNanos) {
            return !completed && timeoutNanos >= 0L && nowNanos - enqueuedAtNanos >= timeoutNanos;
        }

        public synchronized boolean cancel() {
            if (completed) {
                return false;
            }
            completed = true;
            return true;
        }

        public synchronized boolean completed() {
            return completed;
        }
    }

    void setFrameHandler(Consumer<byte[]> handler);

    void setCloseHandler(Runnable handler);

    default void setOpenHandler(Runnable handler) {
    }

    default void setCloseReasonHandler(Consumer<String> handler) {
    }

    default void setErrorHandler(Consumer<Throwable> handler) {
    }

    default void connect() {
    }

    void send(byte[] frame);

    default boolean trySend(byte[] frame) {
        send(frame);
        return true;
    }

    default boolean trySend(byte[] frame, Consumer<SendResult> resultHandler) {
        long enqueuedAtNanos = System.nanoTime();
        if (!trySend(frame)) {
            return false;
        }
        if (resultHandler != null) {
            try {
                resultHandler.accept(SendReceipt.immediate(enqueuedAtNanos, frame == null ? 0 : frame.length));
            } catch (RuntimeException ignored) {
            }
        }
        return true;
    }

    void close();

    boolean isOpen();

    enum State {
        NEW,
        CONNECTING,
        OPEN,
        CLOSING,
        CLOSED,
        FAILED
    }

    default State state() {
        return isOpen() ? State.OPEN : State.CLOSED;
    }

    default Optional<ServerId> peerServerId() {
        return Optional.empty();
    }

    default boolean reusableAfterDisconnect() {
        return true;
    }

    default boolean reconnectable() {
        return reusableAfterDisconnect();
    }

    default CallbackPublication publishCallback(Runnable callback) {
        if (callback == null) {
            return CallbackPublication.STALE;
        }
        callback.run();
        return CallbackPublication.EXECUTED;
    }
}
