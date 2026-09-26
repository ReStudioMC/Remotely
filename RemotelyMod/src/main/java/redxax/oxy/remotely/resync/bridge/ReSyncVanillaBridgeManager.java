package redxax.oxy.remotely.resync.bridge;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import redxax.oxy.remotely.Constants;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncLiveServerSession;
import redxax.oxy.remotely.util.InitializationManager;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.identity.ServerId;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ReSyncVanillaBridgeManager {
    private static final ReSyncVanillaBridgeManager INSTANCE = new ReSyncVanillaBridgeManager();
    private static final Logger LOGGER = LogManager.getLogger("Remotely");
    private static final long HELLO_RETRY_NANOS = 1_000_000_000L;
    private static final long REJECTED_HELLO_RETRY_NANOS = 10_000_000_000L;
    private static final long LIVE_SESSION_AUDIT_NANOS = 1_000_000_000L;
    private static final int BRIDGE_PROTOCOL_VERSION = 2;
    private static final int MAX_INBOUND_TASKS = 2_048;
    private static final long MAX_INBOUND_BYTES = 48L * 1_024L * 1_024L;
    private static final int MAX_LIFECYCLE_TASKS = 64;
    private static final int MAX_OUTBOUND_PACKETS = 2_048;
    private static final long MAX_OUTBOUND_BYTES = 48L * 1_024L * 1_024L;
    private static final int MAX_OUTBOUND_PACKETS_PER_TICK = 2;
    private static final int MAX_OUTBOUND_BYTES_PER_TICK = 768 * 1_024;
    private static final long MAX_OUTBOUND_NANOS_PER_TICK = 2_000_000L;
    private static final long MAX_OUTBOUND_DELIVERY_NANOS = TimeUnit.SECONDS.toNanos(75L);
    private static final int MAX_ACTIVATION_FRAMES = 2048;
    private static final int MAX_ACTIVATION_BYTES = 32 * 1_024 * 1_024;
    private static final int MAX_ACTIVATION_CALLBACKS = 64;
    private final ReSyncVanillaPacketAdapter adapter = new ReSyncVanillaPacketAdapter();
    private final ReSyncBridgeChunker chunker = new ReSyncBridgeChunker();
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final AtomicLong bridgeGeneration = new AtomicLong(1L);
    private final AtomicLong activationGeneration = new AtomicLong(-1L);
    private final AtomicLong inboundBytes = new AtomicLong();
    private final AtomicBoolean restartScheduled = new AtomicBoolean();
    private final AtomicLong restartRequiredGeneration = new AtomicLong(-1L);
    private final AtomicBoolean resetScheduled = new AtomicBoolean();
    private final AtomicReference<ResetRequest> pendingReset = new AtomicReference<>();
    private final Object chunkerLock = new Object();
    private final Object outboundLock = new Object();
    private final ArrayDeque<OutboundPacket> outboundPackets = new ArrayDeque<>();
    private final Set<OutboundSend> pendingOutboundSends = new HashSet<>();
    private final ThreadPoolExecutor bridgeExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(MAX_INBOUND_TASKS), runnable -> {
            Thread thread = new Thread(runnable, "remotely-resync-bridge");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor lifecycleExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(MAX_LIFECYCLE_TASKS), runnable -> {
            Thread thread = new Thread(runnable, "remotely-resync-lifecycle");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private volatile UUID sessionId = UUID.randomUUID();
    private volatile boolean helloSent;
    private volatile boolean authenticated;
    private volatile String liveServerId;
    private volatile String displayName = "Live Server";
    private volatile Set<String> supportedChannels = Set.of();
    private volatile BridgeTransport transport;
    private volatile Object lastConnection;
    private volatile long nextHelloNanos;
    private volatile long nextLiveSessionAuditNanos;
    private volatile boolean channelRegistered;
    private volatile boolean liveSessionActivated;
    private volatile String rejectedReason;
    private volatile String terminalMismatchReason;
    private final AtomicInteger consecutiveConnectionFailures = new AtomicInteger();
    private long outboundBytes;
    private long reservedOutboundBytes;
    private int reservedOutboundPackets;

    private record ResetRequest(long generation, UUID sessionId, String serverId, BridgeTransport transport,
                                Object connection) {
    }

    public static ReSyncVanillaBridgeManager getInstance() {
        return INSTANCE;
    }

    public void tick() {
        long now = System.nanoTime();
        expireOutboundSends(now);
        Minecraft client = Minecraft.getInstance();
        Object connection = client.getConnection();
        if (connection == null || client.player == null) {
            scheduleReset();
            return;
        }
        if (pendingReset.get() != null) {
            scheduleReset();
            return;
        }
        if (connection != lastConnection) {
            boolean resetRequired;
            synchronized (sequence) {
                resetRequired = lastConnection != null || helloSent || authenticated || transport != null;
                if (!resetRequired) {
                    lastConnection = connection;
                }
            }
            if (resetRequired) {
                scheduleReset();
                return;
            }
        }
        long restartGeneration = restartRequiredGeneration.get();
        if (restartGeneration == bridgeGeneration.get()) {
            scheduleRestart(restartGeneration);
            return;
        }
        drainOutbound();
        if (!authenticated && terminalMismatchReason == null && now >= nextHelloNanos) {
            sendHello();
        }
        if (authenticated && now >= nextLiveSessionAuditNanos) {
            nextLiveSessionAuditNanos = now + LIVE_SESSION_AUDIT_NANOS;
            ensureLiveSessionActive();
        }
    }

    public void handlePayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        long generation = bridgeGeneration.get();
        byte[] retained = payload.clone();
        long queued = inboundBytes.addAndGet(retained.length);
        if (queued > MAX_INBOUND_BYTES) {
            inboundBytes.addAndGet(-retained.length);
            requestRestart(generation, "inbound_byte_limit");
            return;
        }
        try {
            bridgeExecutor.execute(() -> {
                try {
                    processPayload(generation, retained);
                } finally {
                    inboundBytes.addAndGet(-retained.length);
                }
            });
        } catch (RejectedExecutionException exception) {
            inboundBytes.addAndGet(-retained.length);
            requestRestart(generation, "inbound_executor_rejected");
        }
    }

    private void processPayload(long generation, byte[] payload) {
        if (generation != bridgeGeneration.get()) {
            return;
        }
        try {
            ReSyncBridgeEnvelope envelope = ReSyncBridgeEnvelope.decode(payload);
            if (generation != bridgeGeneration.get() || !sessionId.equals(envelope.sessionId())) {
                return;
            }
            byte[] complete;
            synchronized (chunkerLock) {
                complete = chunker.accept(envelope);
            }
            if (complete == null || generation != bridgeGeneration.get()) {
                return;
            }
            switch (envelope.type()) {
                case ReSyncBridgeEnvelope.AUTH_RESULT -> handleAuthResult(generation, envelope.sessionId(), complete);
                case ReSyncBridgeEnvelope.DATA -> {
                    BridgeTransport active = transport;
                    if (active != null && active.matches(generation, envelope.sessionId())) {
                        active.receive(complete);
                    }
                }
                case ReSyncBridgeEnvelope.CLOSE, ReSyncBridgeEnvelope.ERROR -> {
                    BridgeTransport active = transport;
                    if (active != null && active.matches(generation, envelope.sessionId())) {
                        active.remoteClose();
                    }
                    requestRestart(generation, envelope.type() == ReSyncBridgeEnvelope.CLOSE
                        ? "peer_closed" : "peer_error");
                }
                default -> {
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("ReSync bridge payload rejected: {}", exception.getMessage());
            requestRestart(generation, "payload_rejected");
        }
    }

    public void openStudioFromKey() {
        InitializationManager.ensureInitialized();
        if (RemotelyClient.INSTANCE == null) {
            new Notification("ReSync", "Remotely Loading", Notification.Type.WARN);
            return;
        }
        if (!authenticated) {
            if (rejectedReason != null) {
                return;
            }
            new Notification("ReSync", helloSent ? "Bridge Waiting" : "Bridge Not Ready", Notification.Type.WARN);
            return;
        }
        if (transport == null || !isCanonicalServerId(liveServerId)) {
            new Notification("ReSync", "Bridge Transport Missing", Notification.Type.WARN);
            return;
        }
        ensureLiveSessionActive();
        RemotelyClient.INSTANCE.openLiveReSyncStudio(new ReSyncLiveServerSession(liveServerId, displayName, transport));
    }

    public void openStudioEditTarget(String type, String id, boolean fullEditor) {
        openStudioEditTarget(liveServerId, type, id, fullEditor, currentMinecraftScreen());
    }

    public void openStudioEditTarget(String serverId, String type, String id, boolean fullEditor) {
        openStudioEditTarget(serverId, type, id, fullEditor, currentMinecraftScreen());
    }

    public void openStudioEditTarget(String serverId, String type, String id, boolean fullEditor, Object parent) {
        String actualServerId = serverId != null && !serverId.isBlank() ? serverId : liveServerId;
        if (!isLiveStudioAvailable(actualServerId)) {
            return;
        }
        synchronized (sequence) {
            if (liveServerId == null || liveServerId.isBlank()) {
                liveServerId = actualServerId;
            }
        }
        ensureLiveSessionActive();
        RemotelyClient.INSTANCE.getFlowManager().openLiveStudioDesigner(new ReSyncLiveServerSession(actualServerId, displayName, transport), type, id, fullEditor, parent);
    }

    public void createStudioAdvancementTree(boolean fullEditor) {
        createStudioAdvancementTree(liveServerId, fullEditor, currentMinecraftScreen());
    }

    public void createStudioAdvancementTree(String serverId, boolean fullEditor) {
        createStudioAdvancementTree(serverId, fullEditor, currentMinecraftScreen());
    }

    public void createStudioAdvancementTree(String serverId, boolean fullEditor, Object parent) {
        String actualServerId = serverId != null && !serverId.isBlank() ? serverId : liveServerId;
        if (!isLiveStudioAvailable(actualServerId)) {
            return;
        }
        synchronized (sequence) {
            if (liveServerId == null || liveServerId.isBlank()) {
                liveServerId = actualServerId;
            }
        }
        ensureLiveSessionActive();
        RemotelyClient.INSTANCE.getFlowManager().createLiveStudioAdvancementTree(new ReSyncLiveServerSession(actualServerId, displayName, transport), fullEditor, parent);
    }

    private Object currentMinecraftScreen() {
        //#if MC >= 26.2
        //$$ return Minecraft.getInstance().gui.screen();
        //#else
        return Minecraft.getInstance().screen;
        //#endif
    }

    private boolean isLiveStudioAvailable(String serverId) {
        InitializationManager.ensureInitialized();
        if (RemotelyClient.INSTANCE == null || RemotelyClient.INSTANCE.getFlowManager() == null) {
            new Notification("ReSync", "Remotely Loading", Notification.Type.WARN);
            return false;
        }
        if (!authenticated) {
            if (rejectedReason != null) {
                return false;
            }
            new Notification("ReSync", helloSent ? "Bridge Waiting" : "Bridge Not Ready", Notification.Type.WARN);
            return false;
        }
        if (transport == null) {
            new Notification("ReSync", "Bridge Transport Missing", Notification.Type.WARN);
            return false;
        }
        return isCanonicalServerId(serverId) && serverId.equals(liveServerId);
    }

    public boolean ensureLiveSessionActive() {
        long generation = bridgeGeneration.get();
        BridgeTransport activeTransport = transport;
        String serverId = liveServerId;
        if (!authenticated || activeTransport == null || !activeTransport.matches(generation, sessionId)
            || !isCanonicalServerId(serverId)) {
            return false;
        }
        if (!activeTransport.isOpen()) {
            requestRestart(generation, "transport_closed");
            return false;
        }
        if (liveSessionActivated) {
            RemotelyClient remotely = RemotelyClient.INSTANCE;
            FlowManager manager = remotely != null ? remotely.getFlowManager() : null;
            if (manager == null) {
                return false;
            }
            ReSyncFlowClient.ConnectionState state = manager.getFlowClientConnectionState(liveServerId);
            if (state == ReSyncFlowClient.ConnectionState.CONNECTED) {
                consecutiveConnectionFailures.set(0);
                return true;
            }
            if (state == ReSyncFlowClient.ConnectionState.CONNECTING) {
                return true;
            }
            liveSessionActivated = false;
            scheduleActivation(generation, activeTransport, serverId, displayName);
            return true;
        }
        scheduleActivation(generation, activeTransport, serverId, displayName);
        return true;
    }

    public String getLiveServerId() {
        return liveServerId;
    }

    private void sendHello() {
        if (terminalMismatchReason != null) {
            return;
        }
        nextHelloNanos = System.nanoTime() + (rejectedReason != null ? REJECTED_HELLO_RETRY_NANOS : HELLO_RETRY_NANOS);
        if (!channelRegistered) {
            channelRegistered = adapter.registerBridgeChannel();
        }
        Minecraft client = Minecraft.getInstance();
        String address = client.getCurrentServer() != null ? client.getCurrentServer().ip : "local";
        byte[] versionBytes = Constants.VERSION.getBytes(StandardCharsets.UTF_8);
        byte[] addressBytes = address.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + versionBytes.length + 4 + addressBytes.length);
        buffer.putInt(BRIDGE_PROTOCOL_VERSION);
        buffer.putInt(versionBytes.length);
        buffer.put(versionBytes);
        buffer.putInt(addressBytes.length);
        buffer.put(addressBytes);
        synchronized (sequence) {
            helloSent = enqueueFrame(bridgeGeneration.get(), sessionId, ReSyncBridgeEnvelope.HELLO, buffer.array());
        }
    }

    private void handleAuthResult(long generation, UUID serverSessionId, byte[] payload) {
        if (generation != bridgeGeneration.get() || !sessionId.equals(serverSessionId)
            || resetPendingFor(generation) || terminalMismatchReason != null) {
            return;
        }
        BridgeTransport existingTransport = transport;
        if (authenticated && existingTransport != null && existingTransport.matches(generation, serverSessionId)) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (buffer.remaining() < 1) {
            rejectTerminalMismatch(generation, serverSessionId, "Bridge Protocol Mismatch. Update ReSync And Remotely");
            return;
        }
        boolean success = buffer.get() == 1;
        if (!success) {
            int responseProtocol = buffer.remaining() >= 4 ? buffer.getInt() : -1;
            String reason = readString(buffer, "ReSync Unavailable");
            if (responseProtocol != BRIDGE_PROTOCOL_VERSION || reason.toLowerCase(Locale.ROOT).contains("unsupported")) {
                rejectTerminalMismatch(generation, serverSessionId,
                    "Bridge Protocol Mismatch. Update ReSync And Remotely");
                return;
            }
            synchronized (sequence) {
                if (generation != bridgeGeneration.get() || !sessionId.equals(serverSessionId)
                    || resetPendingFor(generation)) {
                    return;
                }
                rejectedReason = "No Permission".equals(reason) ? "No Permission" : "ReSync Unavailable";
                nextHelloNanos = System.nanoTime() + REJECTED_HELLO_RETRY_NANOS;
            }
            return;
        }
        if (buffer.remaining() < 4 || buffer.getInt() != BRIDGE_PROTOCOL_VERSION) {
            rejectTerminalMismatch(generation, serverSessionId,
                "Bridge Protocol Mismatch. Update ReSync And Remotely");
            return;
        }
        String nextDisplayName = readString(buffer, "Live Server");
        Set<String> nextSupportedChannels = readChannels(buffer);
        String canonicalServerId = readCanonicalServerId(buffer);
        if (!isCanonicalServerId(canonicalServerId)) {
            rejectTerminalMismatch(generation, serverSessionId,
                "Bridge Server Identity Missing. Update ReSync");
            return;
        }
        BridgeTransport nextTransport = new BridgeTransport(generation, serverSessionId,
            ServerId.parseCanonicalText(canonicalServerId));
        synchronized (sequence) {
            if (generation != bridgeGeneration.get() || !sessionId.equals(serverSessionId)
                || resetPendingFor(generation)) {
                return;
            }
            BridgeTransport currentTransport = transport;
            if (authenticated && currentTransport != null && currentTransport.matches(generation, serverSessionId)) {
                return;
            }
            sessionId = serverSessionId;
            rejectedReason = null;
            terminalMismatchReason = null;
            displayName = nextDisplayName;
            supportedChannels = nextSupportedChannels;
            liveServerId = canonicalServerId;
            transport = nextTransport;
            authenticated = true;
        }
        ensureLiveSessionActive();
        nextLiveSessionAuditNanos = System.nanoTime() + LIVE_SESSION_AUDIT_NANOS;
    }

    private void rejectTerminalMismatch(long generation, UUID expectedSession, String reason) {
        BridgeTransport previousTransport;
        synchronized (sequence) {
            if (generation != bridgeGeneration.get() || !sessionId.equals(expectedSession)
                || resetPendingFor(generation)) {
                return;
            }
            previousTransport = transport;
            terminalMismatchReason = reason;
            rejectedReason = reason;
            authenticated = false;
            liveSessionActivated = false;
            nextHelloNanos = Long.MAX_VALUE;
        }
        if (previousTransport != null) {
            previousTransport.remoteClose();
        }
        Minecraft.getInstance().execute(() -> {
            if (generation == bridgeGeneration.get() && sessionId.equals(expectedSession)
                && reason.equals(terminalMismatchReason)) {
                new Notification("ReSync", reason, Notification.Type.ERROR);
            }
        });
    }

    private Set<String> readChannels(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return Set.of();
        }
        int count = buffer.getInt();
        if (count <= 0 || count > 256) {
            return Set.of();
        }
        Set<String> channels = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String channel = readString(buffer, "");
            if (!channel.isBlank()) {
                channels.add(channel);
            }
        }
        return channels;
    }

    private String readCanonicalServerId(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return "";
        }
        String value = readString(buffer, "");
        if (value.isBlank()) {
            return "";
        }
        try {
            ServerId serverId = ServerId.parseCanonicalText(value);
            return serverId.canonicalText().equals(value) ? value : "";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private boolean isCanonicalServerId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            ServerId serverId = ServerId.parseCanonicalText(value);
            return serverId.canonicalText().equals(value);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String readString(ByteBuffer buffer, String fallback) {
        if (buffer.remaining() < 4) {
            return fallback;
        }
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            return fallback;
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        String value = new String(bytes, StandardCharsets.UTF_8);
        return value.isBlank() ? fallback : value;
    }

    private void scheduleActivation(long generation, BridgeTransport expectedTransport, String serverId, String title) {
        if (!isCanonicalServerId(serverId) || !serverId.equals(liveServerId)) {
            return;
        }
        if (!activationGeneration.compareAndSet(-1L, generation)) {
            return;
        }
        boolean admitted = executeLifecycleTask(() -> {
            try {
                if (generation != bridgeGeneration.get()) {
                    return;
                }
                RemotelyClient remotely = RemotelyClient.INSTANCE;
                if (generation != bridgeGeneration.get() || expectedTransport != transport || !serverId.equals(liveServerId)
                    || remotely == null || remotely.getFlowManager() == null || !expectedTransport.isOpen()) {
                    return;
                }
                if (!expectedTransport.beginActivation()) {
                    return;
                }
                try {
                    boolean activated = remotely.getFlowManager().activateLiveReSyncSession(
                        new ReSyncLiveServerSession(serverId, title, expectedTransport)) != null;
                    if (activated && generation == bridgeGeneration.get() && expectedTransport == transport
                        && expectedTransport.commitActivation()) {
                        liveSessionActivated = true;
                    } else if (activated && generation == bridgeGeneration.get() && expectedTransport == transport) {
                        requestRestart(generation, "activation_commit_rejected");
                    }
                } finally {
                    expectedTransport.finishActivation();
                }
            } finally {
                activationGeneration.compareAndSet(generation, -1L);
            }
        });
        if (!admitted) {
            activationGeneration.compareAndSet(generation, -1L);
        }
    }

    private boolean executeLifecycleTask(Runnable action) {
        try {
            lifecycleExecutor.execute(action);
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }

    private void requestRestart(long generation, String reason) {
        if (generation != bridgeGeneration.get() || terminalMismatchReason != null || pendingReset.get() != null) {
            return;
        }
        if (restartRequiredGeneration.compareAndSet(-1L, generation)) {
            LOGGER.warn("ReSync bridge restart requested: {}", reason);
        }
        scheduleRestart(generation);
    }

    private void scheduleRestart(long generation) {
        if (restartRequiredGeneration.get() != generation || generation != bridgeGeneration.get()
            || !restartScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean admitted = executeLifecycleTask(() -> {
            boolean complete = false;
            try {
                complete = restartBridge(generation);
            } finally {
                if (complete) {
                    restartRequiredGeneration.compareAndSet(generation, -1L);
                }
                restartScheduled.set(false);
            }
        });
        if (!admitted) {
            restartScheduled.set(false);
        }
    }

    private boolean restartBridge(long generation) {
        if (generation != bridgeGeneration.get()) {
            return true;
        }
        BridgeTransport previousTransport = transport;
        UUID previousSession = sessionId;
        if (previousTransport != null && !previousTransport.remoteClose()) {
            return false;
        }
        sendCloseAsync(previousSession, lastConnection);
        int failures = consecutiveConnectionFailures.updateAndGet(current -> Math.min(current + 1, 5));
        long retryDelay = HELLO_RETRY_NANOS << Math.max(0, failures - 1);
        beginBridgeAttempt(System.nanoTime() + Math.min(retryDelay, 30_000_000_000L));
        return true;
    }

    private void beginBridgeAttempt(long nextHello) {
        List<SendPublication> failures;
        synchronized (sequence) {
            synchronized (outboundLock) {
                failures = failAllOutboundSendsLocked("bridge_restarted");
                bridgeGeneration.incrementAndGet();
                restartRequiredGeneration.set(-1L);
                activationGeneration.set(-1L);
                sessionId = UUID.randomUUID();
                sequence.set(1);
                authenticated = false;
                helloSent = false;
                liveSessionActivated = false;
                transport = null;
                supportedChannels = Set.of();
                rejectedReason = null;
                terminalMismatchReason = null;
                liveServerId = null;
                nextLiveSessionAuditNanos = 0L;
                nextHelloNanos = nextHello;
                outboundPackets.clear();
                outboundBytes = 0L;
                reservedOutboundPackets = 0;
                reservedOutboundBytes = 0L;
            }
        }
        synchronized (chunkerLock) {
            chunker.clear();
        }
        publishSendResults(failures);
    }

    private void sendCloseAsync(UUID expectedSession, Object expectedConnection) {
        if (expectedSession == null || expectedConnection == null) {
            return;
        }
        byte[] packet;
        synchronized (sequence) {
            int nextSequence = sequence.get();
            packet = new ReSyncBridgeEnvelope(ReSyncBridgeEnvelope.PROTOCOL, ReSyncBridgeEnvelope.CLOSE,
                expectedSession, nextSequence, 0, 1, new byte[0]).encode();
            sequence.incrementAndGet();
        }
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().getConnection() == expectedConnection) {
                adapter.send(packet);
            }
        });
    }

    private List<byte[]> encodePackets(UUID expectedSession, int packetSequence, byte type, byte[] payload) {
        List<byte[]> packets = new ArrayList<>();
        chunker.send(expectedSession, packetSequence, type, payload, envelope -> packets.add(envelope.encode()));
        return List.copyOf(packets);
    }

    private boolean enqueueFrame(long generation, UUID expectedSession, byte type, byte[] payload) {
        return enqueueFrame(generation, expectedSession, type, payload, null);
    }

    private boolean enqueueFrame(long generation, UUID expectedSession, byte type, byte[] payload,
                                 BridgeTransport owner) {
        return enqueueFrame(generation, expectedSession, type, payload, owner, null);
    }

    private boolean enqueueFrame(long generation, UUID expectedSession, byte type, byte[] payload,
                                 BridgeTransport owner, Consumer<ReSyncFrameTransport.SendResult> resultHandler) {
        byte[] retained = payload == null ? new byte[0] : payload.clone();
        long enqueuedAtNanos = System.nanoTime();
        int packetCount = chunker.chunkCount(retained.length);
        long reservedBytes = retained.length + packetCount * 34L;
        OutboundSend send;
        synchronized (sequence) {
            int packetSequence = sequence.get();
            synchronized (outboundLock) {
                if (generation != bridgeGeneration.get() || !expectedSession.equals(sessionId)
                    || outboundPackets.size() + reservedOutboundPackets + packetCount > MAX_OUTBOUND_PACKETS
                    || outboundBytes + reservedOutboundBytes + reservedBytes > MAX_OUTBOUND_BYTES) {
                    return false;
                }
                send = new OutboundSend(generation, owner, packetCount, reservedBytes,
                    new ReSyncFrameTransport.SendTracker(enqueuedAtNanos, packetCount, reservedBytes,
                        outboundPackets.size() + reservedOutboundPackets, outboundBytes + reservedOutboundBytes,
                        resultHandler));
                reservedOutboundPackets += packetCount;
                reservedOutboundBytes += reservedBytes;
                pendingOutboundSends.add(send);
            }
            try {
                bridgeExecutor.execute(() -> publishEncodedFrame(generation, expectedSession, packetSequence, type,
                    retained, owner, send));
            } catch (RejectedExecutionException exception) {
                cancelOutboundAdmission(send);
                return false;
            }
            sequence.incrementAndGet();
            return true;
        }
    }

    private void publishEncodedFrame(long generation, UUID expectedSession, int packetSequence, byte type,
                                     byte[] payload, BridgeTransport owner, OutboundSend send) {
        List<byte[]> packets;
        try {
            packets = encodePackets(expectedSession, packetSequence, type, payload);
        } catch (RuntimeException exception) {
            failOutboundSend(send, "encoding_failed");
            requestRestart(generation, "encoding_failed");
            return;
        }
        boolean published;
        if (owner != null) {
            synchronized (owner.activationFence) {
                published = publishEncodedPackets(generation, expectedSession, packets, owner, send);
            }
        } else {
            published = publishEncodedPackets(generation, expectedSession, packets, null, send);
        }
        if (!published) {
            failOutboundSend(send, "stale_owner");
        }
    }

    private boolean publishEncodedPackets(long generation, UUID expectedSession, List<byte[]> packets,
                                          BridgeTransport owner, OutboundSend send) {
        synchronized (outboundLock) {
            releaseOutboundReservation(send);
            if (generation != bridgeGeneration.get() || !expectedSession.equals(sessionId)
                || owner != null && !owner.outboundCurrent() || !pendingOutboundSends.contains(send)
                || send.tracker.completed()) {
                return false;
            }
            send.tracker.encoded(packets.size(), packets.stream().mapToLong(packet -> packet.length).sum());
            for (byte[] packet : packets) {
                outboundPackets.addLast(new OutboundPacket(generation, expectedSession, packet, owner, send));
                outboundBytes += packet.length;
            }
            return true;
        }
    }

    private void cancelOutboundAdmission(OutboundSend send) {
        synchronized (outboundLock) {
            pendingOutboundSends.remove(send);
            releaseOutboundReservation(send);
            send.tracker.cancel();
        }
    }

    private void releaseOutboundReservation(OutboundSend send) {
        if (send == null || send.reservationReleased) {
            return;
        }
        send.reservationReleased = true;
        reservedOutboundPackets -= send.reservedPackets;
        reservedOutboundBytes -= send.reservedBytes;
    }

    private void expireOutboundSends(long nowNanos) {
        List<OutboundSend> expired;
        synchronized (outboundLock) {
            expired = pendingOutboundSends.stream()
                .filter(send -> send.tracker.expired(nowNanos, MAX_OUTBOUND_DELIVERY_NANOS)).toList();
        }
        for (OutboundSend send : expired) {
            if (failOutboundSend(send, "delivery_timeout")) {
                LOGGER.warn("ReSync bridge send expired: generation={}, packets={}, bytes={}, queuedPackets={}, queuedBytes={}",
                    send.generation, send.reservedPackets, send.reservedBytes, outboundPacketCount(), outboundByteCount());
            }
        }
    }

    private int outboundPacketCount() {
        synchronized (outboundLock) {
            return outboundPackets.size() + reservedOutboundPackets;
        }
    }

    private long outboundByteCount() {
        synchronized (outboundLock) {
            return outboundBytes + reservedOutboundBytes;
        }
    }

    private boolean failOutboundSend(OutboundSend send, String reason) {
        if (send == null) {
            return false;
        }
        SendPublication publication;
        synchronized (outboundLock) {
            publication = failOutboundSendLocked(send, reason);
        }
        publishSendResult(publication);
        return publication != null;
    }

    private List<SendPublication> failAllOutboundSendsLocked(String reason) {
        List<SendPublication> publications = new ArrayList<>();
        for (OutboundSend send : List.copyOf(pendingOutboundSends)) {
            SendPublication publication = failOutboundSendLocked(send, reason);
            if (publication != null) {
                publications.add(publication);
            }
        }
        return publications;
    }

    private List<SendPublication> failOutboundSendsLocked(BridgeTransport owner, String reason) {
        List<SendPublication> publications = new ArrayList<>();
        List<OutboundSend> pending = pendingOutboundSends.stream().filter(send -> send.owner == owner).toList();
        for (OutboundSend send : pending) {
            SendPublication publication = failOutboundSendLocked(send, reason);
            if (publication != null) {
                publications.add(publication);
            }
        }
        return publications;
    }

    private SendPublication failOutboundSendLocked(OutboundSend send, String reason) {
        if (send == null || !pendingOutboundSends.remove(send)) {
            return null;
        }
        releaseOutboundReservation(send);
        outboundPackets.removeIf(packet -> {
            if (packet.send() != send) {
                return false;
            }
            outboundBytes -= packet.payload().length;
            return true;
        });
        ReSyncFrameTransport.SendResult result = send.tracker.claimFailure(reason);
        return result == null ? null : new SendPublication(send.tracker, result);
    }

    private void publishSendResults(List<SendPublication> publications) {
        publications.forEach(this::publishSendResult);
    }

    private void publishSendResult(SendPublication publication) {
        if (publication == null) {
            return;
        }
        if (!publication.publish()) {
            LOGGER.warn("ReSync transport-delivery callback failed");
        }
    }

    private OutboundDelivery deliverOutboundPacketLocked(OutboundPacket packet, boolean current) {
        if (!pendingOutboundSends.contains(packet.send()) || packet.generation() != bridgeGeneration.get()
            || !packet.sessionId().equals(sessionId) || !current) {
            return new OutboundDelivery(false, false, failOutboundSendLocked(packet.send(), "stale_owner"), null);
        }
        boolean sent;
        RuntimeException failure = null;
        try {
            sent = adapter.send(packet.payload());
        } catch (RuntimeException exception) {
            sent = false;
            failure = exception;
        }
        if (!sent) {
            return new OutboundDelivery(false, true,
                failOutboundSendLocked(packet.send(), "adapter_send_failed"), failure);
        }
        ReSyncFrameTransport.SendResult result = packet.claimPhysicalSend();
        SendPublication publication = null;
        if (result != null) {
            pendingOutboundSends.remove(packet.send());
            publication = new SendPublication(packet.send().tracker, result);
        }
        return new OutboundDelivery(true, true, publication, null);
    }

    private void drainOutbound() {
        long deadline = System.nanoTime() + MAX_OUTBOUND_NANOS_PER_TICK;
        int packetsSent = 0;
        int bytesSent = 0;
        while (packetsSent < MAX_OUTBOUND_PACKETS_PER_TICK && System.nanoTime() < deadline) {
            OutboundPacket packet;
            boolean stale;
            synchronized (outboundLock) {
                packet = outboundPackets.peekFirst();
                if (packet == null) {
                    return;
                }
                stale = packet.generation() != bridgeGeneration.get() || !packet.sessionId().equals(sessionId);
                if (stale) {
                    outboundPackets.removeFirst();
                    outboundBytes -= packet.payload().length;
                } else if (packetsSent > 0 && bytesSent + packet.payload().length > MAX_OUTBOUND_BYTES_PER_TICK) {
                    return;
                } else {
                    outboundPackets.removeFirst();
                    outboundBytes -= packet.payload().length;
                }
            }
            if (stale) {
                failOutboundSend(packet.send(), "stale_generation");
                continue;
            }
            OutboundDelivery delivery;
            if (packet.owner() != null) {
                delivery = packet.owner().sendOutboundPacket(packet);
            } else {
                synchronized (outboundLock) {
                    delivery = deliverOutboundPacketLocked(packet, true);
                }
            }
            publishSendResult(delivery.publication());
            if (!delivery.sent()) {
                if (delivery.failure() != null) {
                    LOGGER.warn("ReSync bridge adapter send failed", delivery.failure());
                }
                if (delivery.current()) {
                    requestRestart(packet.generation(), "adapter_send_failed");
                    return;
                }
                continue;
            }
            packetsSent++;
            bytesSent += packet.payload().length;
        }
    }

    private void scheduleReset() {
        ResetRequest retained = pendingReset.get();
        if (retained == null && lastConnection == null && !helloSent && !authenticated && transport == null) {
            return;
        }
        ResetRequest request = captureResetRequest();
        if (!resetScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean admitted = executeLifecycleTask(() -> {
            boolean complete = false;
            try {
                complete = resetBridge(request);
            } finally {
                if (complete) {
                    pendingReset.compareAndSet(request, null);
                }
                resetScheduled.set(false);
            }
        });
        if (!admitted) {
            resetScheduled.set(false);
        }
    }

    private ResetRequest captureResetRequest() {
        synchronized (sequence) {
            ResetRequest existing = pendingReset.get();
            if (existing != null) {
                return existing;
            }
            ResetRequest captured = new ResetRequest(bridgeGeneration.get(), sessionId, liveServerId, transport,
                lastConnection);
            pendingReset.set(captured);
            return captured;
        }
    }

    private boolean resetPendingFor(long generation) {
        ResetRequest request = pendingReset.get();
        return request != null && request.generation() == generation;
    }

    private boolean resetPendingFor(long generation, BridgeTransport expectedTransport) {
        ResetRequest request = pendingReset.get();
        return request != null && request.generation() == generation && request.transport() == expectedTransport;
    }

    private boolean resetBridge(ResetRequest request) {
        String previousServerId = request.serverId();
        BridgeTransport previousTransport = request.transport();
        if (previousTransport != null && !previousTransport.remoteClose()) {
            return false;
        }
        if (previousServerId != null && !previousServerId.isBlank()) {
            FlowManager manager = FlowManager.getInstance();
            if (manager != null) {
                manager.closeServerConnection(previousServerId);
            }
        }
        synchronized (sequence) {
            if (request.generation() == bridgeGeneration.get() && request.sessionId().equals(sessionId)
                && request.transport() == transport && request.connection() == lastConnection) {
                sendCloseAsync(request.sessionId(), request.connection());
                beginBridgeAttempt(0L);
            }
            consecutiveConnectionFailures.set(0);
            if (lastConnection == request.connection()) {
                lastConnection = null;
                channelRegistered = false;
            }
        }
        return true;
    }

    private class BridgeTransport implements ReSyncFrameTransport {
        private final long generation;
        private final UUID transportSessionId;
        private final ServerId peerServerId;
        private final Object activationFence = new Object();
        private final ArrayDeque<byte[]> pendingActivationFrames = new ArrayDeque<>();
        private final ArrayDeque<Runnable> pendingActivationCallbacks = new ArrayDeque<>();
        private volatile Consumer<byte[]> frameHandler;
        private volatile Runnable closeHandler;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean closeDelivered = new AtomicBoolean();
        private final AtomicBoolean closeScheduling = new AtomicBoolean();
        private boolean activationStarted;
        private boolean activationFinished;
        private boolean activationDrainScheduled;
        private boolean activationCommitted;
        private int pendingActivationBytes;

        private BridgeTransport(long generation, UUID transportSessionId, ServerId peerServerId) {
            this.generation = generation;
            this.transportSessionId = transportSessionId;
            this.peerServerId = peerServerId;
        }

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
            this.frameHandler = handler;
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            this.closeHandler = handler;
            if (!open.get()) {
                dispatchCloseHandler();
            }
        }

        @Override
        public void send(byte[] frame) {
            trySend(frame);
        }

        @Override
        public boolean trySend(byte[] frame) {
            return trySend(frame, null);
        }

        @Override
        public boolean trySend(byte[] frame, Consumer<ReSyncFrameTransport.SendResult> resultHandler) {
            synchronized (activationFence) {
                if (!outboundCurrent()) {
                    return false;
                }
                return enqueueFrame(generation, transportSessionId, ReSyncBridgeEnvelope.DATA, frame, this,
                    resultHandler);
            }
        }

        @Override
        public void close() {
            List<SendPublication> failures;
            synchronized (activationFence) {
                if (!open.compareAndSet(true, false)) {
                    dispatchCloseHandler();
                    return;
                }
                clearPendingActivationFrames();
                clearPendingActivationCallbacks();
                synchronized (outboundLock) {
                    failures = failOutboundSendsLocked(this, "transport_closed");
                }
            }
            publishSendResults(failures);
            enqueueFrame(generation, transportSessionId, ReSyncBridgeEnvelope.CLOSE, new byte[0]);
            dispatchCloseHandler();
        }

        @Override
        public boolean isOpen() {
            return open.get() && authenticated && transport == this && matches(generation, transportSessionId)
                && !resetPendingFor(generation, this);
        }

        @Override
        public Optional<ServerId> peerServerId() {
            return Optional.of(peerServerId);
        }

        @Override
        public boolean reusableAfterDisconnect() {
            return true;
        }

        @Override
        public ReSyncFrameTransport.CallbackPublication publishCallback(Runnable callback) {
            if (callback == null) {
                return ReSyncFrameTransport.CallbackPublication.STALE;
            }
            synchronized (activationFence) {
                if (!open.get() || transport != this || resetPendingFor(generation, this)
                    || !matches(generation, transportSessionId)) {
                    return ReSyncFrameTransport.CallbackPublication.STALE;
                }
                if (!activationCommitted) {
                    if (pendingActivationCallbacks.size() >= MAX_ACTIVATION_CALLBACKS) {
                        return ReSyncFrameTransport.CallbackPublication.STALE;
                    }
                    pendingActivationCallbacks.addLast(callback);
                    return ReSyncFrameTransport.CallbackPublication.DEFERRED;
                }
            }
            callback.run();
            return ReSyncFrameTransport.CallbackPublication.EXECUTED;
        }

        private void receive(byte[] frame) {
            Consumer<byte[]> handler;
            synchronized (activationFence) {
                if (!open.get() || transport != this || resetPendingFor(generation, this)
                    || !matches(generation, transportSessionId)) {
                    return;
                }
                if (!activationCommitted) {
                    byte[] retained = frame == null ? new byte[0] : frame.clone();
                    if (pendingActivationFrames.size() >= MAX_ACTIVATION_FRAMES
                        || pendingActivationBytes + retained.length > MAX_ACTIVATION_BYTES) {
                        requestRestart(generation, "activation_frame_limit");
                        return;
                    }
                    pendingActivationFrames.addLast(retained);
                    pendingActivationBytes += retained.length;
                    return;
                }
                handler = frameHandler;
            }
            if (handler != null) {
                handler.accept(frame);
            }
        }

        private boolean commitActivation() {
            synchronized (activationFence) {
                if (!open.get() || transport != this || resetPendingFor(generation, this)
                    || !matches(generation, transportSessionId)) {
                    return false;
                }
                if (activationCommitted || activationDrainScheduled) {
                    return true;
                }
                activationDrainScheduled = true;
            }
            try {
                bridgeExecutor.execute(this::publishPendingActivationFrames);
                return true;
            } catch (RejectedExecutionException exception) {
                synchronized (activationFence) {
                    activationDrainScheduled = false;
                }
                return false;
            }
        }

        private boolean beginActivation() {
            synchronized (activationFence) {
                if (!open.get() || transport != this || resetPendingFor(generation, this)
                    || !matches(generation, transportSessionId)) {
                    return false;
                }
                activationStarted = true;
                activationFinished = false;
                return true;
            }
        }

        private void finishActivation() {
            synchronized (activationFence) {
                activationFinished = true;
            }
        }

        private void publishPendingActivationFrames() {
            List<byte[]> frames;
            List<Runnable> callbacks;
            Consumer<byte[]> handler;
            synchronized (activationFence) {
                activationDrainScheduled = false;
                if (!open.get() || transport != this || resetPendingFor(generation, this)
                    || !matches(generation, transportSessionId)) {
                    clearPendingActivationFrames();
                    clearPendingActivationCallbacks();
                    return;
                }
                activationCommitted = true;
                frames = List.copyOf(pendingActivationFrames);
                clearPendingActivationFrames();
                callbacks = List.copyOf(pendingActivationCallbacks);
                clearPendingActivationCallbacks();
                handler = frameHandler;
            }
            if (handler != null) {
                try {
                    frames.forEach(handler::accept);
                } catch (RuntimeException exception) {
                    requestRestart(generation, "activation_frame_delivery_failed");
                    return;
                }
            }
            publishPendingActivationCallbacks(callbacks);
        }

        private void publishPendingActivationCallbacks(List<Runnable> callbacks) {
            for (Runnable callback : callbacks) {
                synchronized (activationFence) {
                    if (!open.get() || transport != this || resetPendingFor(generation, this)
                        || !matches(generation, transportSessionId)) {
                        return;
                    }
                }
                try {
                    callback.run();
                } catch (RuntimeException exception) {
                    requestRestart(generation, "activation_callback_failed");
                    return;
                }
            }
        }

        private boolean remoteClose() {
            List<SendPublication> failures;
            synchronized (activationFence) {
                open.set(false);
                activationCommitted = false;
                clearPendingActivationFrames();
                clearPendingActivationCallbacks();
                synchronized (outboundLock) {
                    failures = failOutboundSendsLocked(this, "transport_closed");
                }
            }
            publishSendResults(failures);
            return dispatchCloseHandler();
        }

        private boolean matches(long expectedGeneration, UUID expectedSession) {
            return generation == expectedGeneration && transportSessionId.equals(expectedSession);
        }

        private boolean outboundCurrent() {
            return open.get() && transport == this && matches(generation, transportSessionId)
                && !resetPendingFor(generation, this);
        }

        private OutboundDelivery sendOutboundPacket(OutboundPacket packet) {
            synchronized (activationFence) {
                synchronized (outboundLock) {
                    return deliverOutboundPacketLocked(packet, outboundCurrent());
                }
            }
        }

        private boolean dispatchCloseHandler() {
            Runnable handler = closeHandler;
            if (closeDelivered.get()) {
                return true;
            }
            if (handler == null) {
                synchronized (activationFence) {
                    if (activationStarted && !activationFinished) {
                        return false;
                    }
                    closeDelivered.set(true);
                    return true;
                }
            }
            if (!closeScheduling.compareAndSet(false, true)) {
                return false;
            }
            try {
                if (closeDelivered.get()) {
                    return true;
                }
                boolean admitted = executeLifecycleTask(handler);
                if (admitted) {
                    closeDelivered.set(true);
                }
                return admitted;
            } finally {
                closeScheduling.set(false);
            }
        }

        private void clearPendingActivationFrames() {
            pendingActivationFrames.clear();
            pendingActivationBytes = 0;
        }

        private void clearPendingActivationCallbacks() {
            pendingActivationCallbacks.clear();
        }
    }

    private record OutboundPacket(long generation, UUID sessionId, byte[] payload, BridgeTransport owner,
                                  OutboundSend send) {
        private ReSyncFrameTransport.SendResult claimPhysicalSend() {
            return send == null ? null : send.tracker.claimSentFrame();
        }
    }

    private record SendPublication(ReSyncFrameTransport.SendTracker tracker,
                                   ReSyncFrameTransport.SendResult result) {
        private boolean publish() {
            return tracker.publish(result);
        }
    }

    private record OutboundDelivery(boolean sent, boolean current, SendPublication publication,
                                    RuntimeException failure) {
    }

    private static final class OutboundSend {
        private final long generation;
        private final BridgeTransport owner;
        private final int reservedPackets;
        private final long reservedBytes;
        private final ReSyncFrameTransport.SendTracker tracker;
        private boolean reservationReleased;

        private OutboundSend(long generation, BridgeTransport owner, int reservedPackets, long reservedBytes,
                             ReSyncFrameTransport.SendTracker tracker) {
            this.generation = generation;
            this.owner = owner;
            this.reservedPackets = reservedPackets;
            this.reservedBytes = reservedBytes;
            this.tracker = tracker;
        }
    }
}
