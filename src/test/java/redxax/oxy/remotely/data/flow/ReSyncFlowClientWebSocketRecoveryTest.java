package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.ArrayList;
import java.util.UUID;
import restudio.rescreen.platform.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientWebSocketRecoveryTest {
    private static final ServerId TEST_SERVER = new ServerId(UUID.fromString("123e4567-e89b-42d3-a456-426614174000"));

    private Async.Snapshot asyncSnapshot;
    private ExecutorService asyncPool;
    private TaskScheduler previousScheduler;
    private ManualScheduler scheduler;

    @BeforeEach
    void setUp() {
        asyncSnapshot = Async.snapshot();
        asyncPool = Executors.newCachedThreadPool();
        Async.installExecutor(asyncPool::execute, ignored -> Thread.currentThread().interrupt());
        previousScheduler = TaskSchedulers.current();
        scheduler = new ManualScheduler();
        TaskSchedulers.configure(scheduler);
    }

    @AfterEach
    void tearDown() {
        TaskSchedulers.configure(previousScheduler);
        Async.restore(asyncSnapshot);
        asyncPool.shutdownNow();
    }

    @Test
    void suppliedSchedulerKeepsHeartbeatsAliveAfterGlobalSchedulerChanges() throws Exception {
        RecoveringTransport transport = new RecoveringTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, "bridge",
            ReSyncFlowClientContext.defaults(), scheduler, Clock.system(), null, ReSyncCredentialProvider.apiKey());
        TaskSchedulers.configure(TaskScheduler.unavailable());
        AtomicInteger connections = new AtomicInteger();
        client.setConnectionListener(connections::incrementAndGet);
        try {
            client.connect().join();
            transport.receive(handshakeFrame());
            await(() -> connections.get() == 1);
            for (int tick = 1; tick <= 4; tick++) {
                scheduler.tick();
                int expected = tick;
                await(() -> transport.heartbeats.get() == expected);
            }
            client.shutdown();
            scheduler.tick();
            assertEquals(4, transport.heartbeats.get());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void transientWebSocketLossRecoversWithoutPublishingDisconnectOrError() throws Exception {
        RecoveringTransport transport = new RecoveringTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger disconnects = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        client.setConnectionListener(connections::incrementAndGet);
        client.setDisconnectListener(disconnects::incrementAndGet);
        client.setErrorListener((nodeId, message) -> errors.incrementAndGet());

        try {
            client.connect().join();
            transport.receive(handshakeFrame());
            await(() -> connections.get() == 1);

            transport.disconnect();
            await(() -> scheduler.has(Duration.ofMillis(250L)));

            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTING, client.connectionState());
            assertEquals(0, disconnects.get());
            assertEquals(0, errors.get());

            scheduler.run(Duration.ofMillis(250L));
            transport.open();
            await(() -> transport.handshakeRequests() == 2);
            transport.receive(handshakeFrame());
            await(() -> connections.get() == 2);

            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());
            assertEquals(0, disconnects.get());
            assertEquals(0, errors.get());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void prolongedWebSocketOutageRecoversAfterBackendStartup() throws Exception {
        RecoveringTransport transport = new RecoveringTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        AtomicInteger disconnects = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger connections = new AtomicInteger();
        client.setConnectionListener(connections::incrementAndGet);
        client.setDisconnectListener(disconnects::incrementAndGet);
        client.setErrorListener((nodeId, message) -> errors.incrementAndGet());

        try {
            client.connect().join();
            transport.receive(handshakeFrame());
            await(() -> connections.get() == 1);

            transport.disconnect();
            for (Duration delay : List.of(Duration.ofMillis(250L), Duration.ofSeconds(1L),
                Duration.ofSeconds(3L), Duration.ofSeconds(5L))) {
                await(() -> scheduler.has(delay));
                scheduler.run(delay);
                transport.failOpening();
            }
            await(() -> scheduler.has(Duration.ofSeconds(5L)));
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTING, client.connectionState());
            assertEquals(0, disconnects.get());
            assertEquals(0, errors.get());

            scheduler.run(Duration.ofSeconds(5L));
            transport.open();
            await(() -> transport.handshakeRequests() == 2);
            transport.receive(handshakeFrame());
            await(() -> connections.get() == 2);

            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());
            assertEquals(0, disconnects.get());
            assertEquals(0, errors.get());
        } finally {
            client.shutdown();
        }
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!check.satisfied() && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue(check.satisfied());
    }

    private static byte[] handshakeFrame() {
        List<String> channels = List.of("flow", "world_management", "worldgen", "player_tracking");
        int payloadLength = 1 + Integer.BYTES * 6;
        for (String channel : channels) {
            payloadLength += Integer.BYTES + channel.getBytes(StandardCharsets.UTF_8).length + Integer.BYTES;
        }
        ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        payload.put((byte) 1);
        payload.putInt(0);
        payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(channels.size());
        short[] ids = {
            ReSyncProtocolContract.CHANNEL_FLOW_ID,
            ReSyncProtocolContract.CHANNEL_WORLD_MANAGEMENT_ID,
            ReSyncProtocolContract.CHANNEL_WORLDGEN_ID,
            ReSyncProtocolContract.CHANNEL_PLAYER_TRACKING_ID
        };
        for (int index = 0; index < channels.size(); index++) {
            byte[] channel = channels.get(index).getBytes(StandardCharsets.UTF_8);
            payload.putInt(channel.length);
            payload.put(channel);
            payload.putInt(ids[index]);
        }
        return new ReSyncFrameCodec().encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE,
            payload.array(), (short) 0, 1);
    }

    private interface Check {
        boolean satisfied();
    }

    private static final class ManualScheduler implements TaskScheduler {
        private final Queue<ManualTask> tasks = new ArrayDeque<>();
        private final List<ManualTask> periodic = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public synchronized ScheduledTask schedule(Runnable task, Duration delay) {
            ManualTask scheduled = new ManualTask(task, delay);
            tasks.add(scheduled);
            return scheduled;
        }

        @Override
        public synchronized ScheduledTask scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
            ManualTask scheduled = new ManualTask(task, initialDelay);
            periodic.add(scheduled);
            return scheduled;
        }

        private void tick() {
            List<ManualTask> scheduled;
            synchronized (this) {
                scheduled = List.copyOf(periodic);
            }
            scheduled.forEach(ManualTask::run);
        }

        private synchronized boolean has(Duration delay) {
            removeCancelled();
            return tasks.stream().anyMatch(task -> task.delay.equals(delay));
        }

        private void run(Duration delay) {
            ManualTask task;
            synchronized (this) {
                removeCancelled();
                task = tasks.stream().filter(candidate -> candidate.delay.equals(delay)).findFirst().orElseThrow();
                tasks.remove(task);
            }
            task.run();
        }

        private void removeCancelled() {
            tasks.removeIf(ManualTask::isCancelled);
        }
    }

    private static final class ManualTask implements TaskScheduler.ScheduledTask {
        private final Runnable action;
        private final Duration delay;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private ManualTask(Runnable action, Duration delay) {
            this.action = action;
            this.delay = delay;
        }

        private void run() {
            if (!cancelled.get()) {
                action.run();
            }
        }

        @Override
        public boolean cancel() {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static final class RecoveringTransport implements ReSyncFrameTransport {
        private final ReSyncFrameCodec codec = new ReSyncFrameCodec();
        private final AtomicInteger handshakeRequests = new AtomicInteger();
        private final AtomicInteger heartbeats = new AtomicInteger();
        private volatile Consumer<byte[]> frameHandler = ignored -> {};
        private volatile Runnable openHandler = () -> {};
        private volatile Runnable closeHandler = () -> {};
        private volatile Consumer<Throwable> errorHandler = ignored -> {};
        private volatile State state = State.OPEN;

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
        public void setErrorHandler(Consumer<Throwable> handler) {
            errorHandler = handler;
        }

        @Override
        public void connect() {
            state = State.CONNECTING;
        }

        @Override
        public void send(byte[] frame) {
            if (codec.decode(frame, null).messageType() == ReSyncProtocolContract.MESSAGE_HEARTBEAT) {
                heartbeats.incrementAndGet();
            }
            if (codec.decode(frame, null).messageType() == ReSyncProtocolContract.MESSAGE_HANDSHAKE_REQUEST) {
                handshakeRequests.incrementAndGet();
            }
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

        @Override
        public Optional<ServerId> peerServerId() {
            return Optional.of(TEST_SERVER);
        }

        @Override
        public boolean reconnectable() {
            return true;
        }

        private void open() {
            state = State.OPEN;
            openHandler.run();
        }

        private void disconnect() {
            state = State.CLOSED;
            closeHandler.run();
        }

        private void failOpening() {
            state = State.FAILED;
            errorHandler.accept(new IllegalStateException("unreachable"));
            state = State.CLOSED;
            closeHandler.run();
        }

        private void receive(byte[] frame) {
            frameHandler.accept(frame);
        }

        private int handshakeRequests() {
            return handshakeRequests.get();
        }
    }
}
