package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientTestHarness;
import redxax.oxy.remotely.data.flow.ReSyncFrameCodec;
import redxax.oxy.remotely.data.flow.ReSyncProtocolContract;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.DesktopTaskIdentities;
import redxax.oxy.remotely.util.TaskIdentities;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.websocket.BinaryWebSocket;
import restudio.rescreen.platform.websocket.BinaryWebSocketListener;
import restudio.resync.network.NetworkChannels;
import restudio.resync.network.NetworkAuthentication;
import restudio.resync.network.NetworkAuthenticationCodec;
import restudio.resync.network.NetworkEditorChunk;
import restudio.resync.network.NetworkEditorCodec;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameCodec;
import restudio.resync.network.NetworkFrameType;
import restudio.resync.network.NetworkNodePresence;
import restudio.resync.network.NetworkNodePresenceCodec;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.NetworkRequestContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class NetworkRuntimeAdmissionTest {
    @Test
    void admissionRequiresRouteAcknowledgementAndRejectsExpiredOrReplacedSessions() {
        NetworkMember member = NetworkMember.proxy("proxy", 25565);
        NetworkDefinition network = NetworkDefinition.create("Test", "proxy", NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(member, NetworkMember.backend("backend", "lobby", NetworkMemberRole.LOBBY, 25566)));
        AtomicLong time = new AtomicLong(1000);
        Scheduler scheduler = new Scheduler();
        Secrets secrets = new Secrets();
        List<Socket> sockets = new ArrayList<>();
        NetworkRuntimeMonitor monitor = new NetworkRuntimeMonitor(secrets, (uri, headers, listener) -> {
            assertTrue(headers.isEmpty());
            Socket socket = new Socket(listener);
            sockets.add(socket);
            listener.onOpen(socket);
            return Async.completed(socket);
        }, scheduler, time::get);
        try {
            monitor.refresh(List.of(network));
            Socket first = sockets.getFirst();
            assertEquals(secrets.credential, first.authentication().offeredCredential());
            assertTrue(first.authentication().credential().isBlank());
            first.frame(network, NetworkFrameType.RESPONSE, "session");
            assertFalse(monitor.snapshot(network).connected());
            first.frame(network, NetworkFrameType.RESPONSE, "routes-" + network.revision());
            assertTrue(monitor.snapshot(network).connected());
            String nodeId = network.members().get(1).nodeId();
            first.presence(network, NetworkFrameType.PRESENCE_DELTA, nodeId, NetworkNodeStatus.ONLINE, time.get());
            first.presence(network, NetworkFrameType.PRESENCE_SNAPSHOT, nodeId, NetworkNodeStatus.OFFLINE, time.get());
            assertEquals(NetworkRuntimeNodeStatus.ONLINE, monitor.snapshot(network).node(nodeId).orElseThrow().status());
            first.presence(network, NetworkFrameType.PRESENCE_DELTA, nodeId, NetworkNodeStatus.OFFLINE, time.get());
            assertEquals(NetworkRuntimeNodeStatus.OFFLINE, monitor.snapshot(network).node(nodeId).orElseThrow().status());
            ReSyncFrameTransport editor = monitor.editorTransport(network.networkId(), network.members().get(1).nodeId());
            List<byte[]> received = new ArrayList<>();
            List<ReSyncFrameTransport.SendResult> receipts = new ArrayList<>();
            editor.setFrameHandler(received::add);
            assertSame(editor, monitor.editorTransport(network.networkId(), network.members().get(1).nodeId()));
            editor.connect();
            UUID tunnel = NetworkEditorCodec.decodeOpen(first.sent.getLast().payload()).tunnelId();
            first.editorFrame(network, NetworkFrameType.EDITOR_OPENED, NetworkEditorCodec.encodeOpened(tunnel));
            assertTrue(editor.isOpen());
            byte[] nativeFrame = new byte[NetworkEditorChunk.MAXIMUM_CHUNK_BYTES + 1];
            assertTrue(editor.trySend(nativeFrame, receipts::add));
            assertTrue(receipts.isEmpty());
            NetworkEditorChunk last = NetworkEditorCodec.decodeChunk(first.sent.getLast().payload());
            assertEquals(NetworkEditorChunk.MAXIMUM_CHUNK_BYTES, last.offset());
            first.editorFrame(network, NetworkFrameType.EDITOR_DATA,
                NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(tunnel, 0, 0, 0, new byte[0])));
            assertEquals(1, receipts.size());
            assertTrue(receipts.getFirst().delivered());
            first.editorFrame(network, NetworkFrameType.EDITOR_DATA,
                NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(tunnel, 0, 0, 1, new byte[]{42})));
            assertEquals(1, received.size());
            assertEquals(42, received.getFirst()[0]);
            assertTrue(editor.trySend(new byte[]{3}, receipts::add));
            NetworkDefinition changed = network.nextRevision(network.members(), network.routingGroups(), network.syncRealms(), NetworkDesiredState.RUNNING);
            assertFalse(monitor.snapshot(changed).connected());
            monitor.refresh(List.of(changed));
            assertFalse(first.open);
            assertFalse(editor.isOpen());
            assertEquals(2, receipts.size());
            assertFalse(receipts.getLast().delivered());
            first.editorFrame(network, NetworkFrameType.EDITOR_DATA,
                NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(tunnel, 1, 0, 1, new byte[]{43})));
            assertEquals(1, received.size());
            first.frame(network, NetworkFrameType.RESPONSE, "routes-" + network.revision());
            assertFalse(monitor.snapshot(changed).connected());
            Socket second = sockets.getLast();
            second.frame(changed, NetworkFrameType.RESPONSE, "session");
            second.frame(changed, NetworkFrameType.RESPONSE, "routes-" + changed.revision());
            assertTrue(monitor.snapshot(changed).connected());
            second.presence(changed, NetworkFrameType.PRESENCE_SNAPSHOT, nodeId, NetworkNodeStatus.ONLINE, time.get());
            assertEquals(NetworkRuntimeNodeStatus.ONLINE, monitor.snapshot(changed).node(nodeId).orElseThrow().status());
            ReSyncFrameTransport refused = monitor.editorTransport(changed.networkId(), changed.members().get(1).nodeId());
            refused.setFrameAdmissionHandler(frame -> false);
            refused.connect();
            UUID refusedId = NetworkEditorCodec.decodeOpen(second.sent.getLast().payload()).tunnelId();
            second.editorFrame(changed, NetworkFrameType.EDITOR_OPENED, NetworkEditorCodec.encodeOpened(refusedId));
            second.editorFrame(changed, NetworkFrameType.EDITOR_DATA,
                NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(refusedId, 0, 0, 1, new byte[]{1})));
            assertFalse(refused.isOpen());
            assertEquals(NetworkFrameType.EDITOR_CLOSE, second.sent.getLast().type());
            time.addAndGet(30_000);
            assertFalse(monitor.snapshot(changed).connected());
            scheduler.tick.run();
            assertFalse(second.open);
            assertFalse(monitor.snapshot(changed).connected());
            time.addAndGet(10_000);
            scheduler.tick.run();
            Socket rejected = sockets.getLast();
            assertEquals(secrets.credential, rejected.authentication().credential());
            rejected.close(1008, "Network Credential Rejected");
            time.addAndGet(10_000);
            scheduler.tick.run();
            assertTrue(sockets.getLast().authentication().credential().isBlank());
            assertEquals(secrets.credential, sockets.getLast().authentication().offeredCredential());
        } finally { monitor.close(); }
    }

    @Test
    void overlappingColdConnectionsReuseTheOfferedCredentialAfterLostAcknowledgement() {
        NetworkDefinition network = NetworkDefinition.create("Test", "proxy", NetworkForwardingPolicy.secureDefault("secret"),
            List.of(NetworkEntryPoint.primary(25565)), List.of(NetworkMember.proxy("proxy", 25565),
                NetworkMember.backend("backend", "lobby", NetworkMemberRole.LOBBY, 25566)));
        AtomicLong time = new AtomicLong(1000);
        Scheduler scheduler = new Scheduler();
        Secrets secrets = new Secrets();
        List<Socket> sockets = new ArrayList<>();
        NetworkRuntimeMonitor monitor = new NetworkRuntimeMonitor(secrets, (uri, headers, listener) -> {
            Socket socket = new Socket(listener);
            sockets.add(socket);
            listener.onOpen(socket);
            return Async.completed(socket);
        }, scheduler, time::get);
        try {
            secrets.readAction = scheduler.tick;
            monitor.refresh(List.of(network));
            assertEquals(1, sockets.size());
            assertEquals(1, secrets.reads);
            assertEquals(1, secrets.saves);
            assertTrue(sockets.getFirst().authentication().credential().isBlank());
            String offered = sockets.getFirst().authentication().offeredCredential();
            assertEquals(offered, secrets.credential);
            sockets.getFirst().close(1006, "Enrollment Acknowledgement Lost");
            time.addAndGet(10_000);
            scheduler.tick.run();
            assertEquals(2, sockets.size());
            assertEquals(offered, sockets.getLast().authentication().credential());
            assertEquals(offered, secrets.credential);
            assertEquals(1, secrets.saves);
        } finally { monitor.close(); }
    }

    @Test
    void rejectedRetiredSendCancellationLeavesReconnectedTunnelOpen() {
        Scheduler scheduler = new Scheduler();
        Async<Void> oldSend = Async.pending();
        ReSyncNetworkFrameTransport editor = new ReSyncNetworkFrameTransport("backend", scheduler, () -> 1000, transport -> {
            UUID id = transport.tunnelId();
            transport.bind(id, new ReSyncNetworkFrameTransport.Channel() {
                @Override
                public Async<Void> send(NetworkFrameType type, byte[] payload) {
                    return type == NetworkFrameType.EDITOR_DATA ? oldSend : Async.completed(null);
                }
                @Override
                public boolean current() { return true; }
                @Override
                public void remove(UUID tunnelId, ReSyncNetworkFrameTransport retired) { }
            });
            transport.opened(id);
        });
        try {
            editor.connect();
            UUID retired = editor.tunnelId();
            assertTrue(editor.trySend(new byte[]{1}, result -> {
                editor.connect();
                scheduler.reject = true;
            }));
            editor.fail(retired, "Disconnected");
            assertTrue(oldSend.isCancelled());
            assertTrue(editor.isOpen());
        } finally { editor.close(); }
    }

    @Test
    void nativeBurstWaitsForPeerReceiptsWithoutRetiringTheClient() throws Exception {
        Scheduler scheduler = new Scheduler();
        List<NetworkEditorChunk> sent = new CopyOnWriteArrayList<>();
        List<ReSyncFrameTransport.SendResult> receipts = new CopyOnWriteArrayList<>();
        ReSyncNetworkFrameTransport editor = new ReSyncNetworkFrameTransport("backend", scheduler, () -> 1000, transport -> {
            UUID id = transport.tunnelId();
            transport.bind(id, new ReSyncNetworkFrameTransport.Channel() {
                @Override
                public Async<Void> send(NetworkFrameType type, byte[] payload) {
                    if (type == NetworkFrameType.EDITOR_DATA) sent.add(NetworkEditorCodec.decodeChunk(payload));
                    return Async.completed(null);
                }
                @Override
                public boolean current() { return true; }
                @Override
                public void remove(UUID tunnelId, ReSyncNetworkFrameTransport retired) { }
            });
            transport.opened(id);
        });
        TaskIdentities.Access previousIdentity = TaskIdentities.access;
        DesktopTaskIdentities.install();
        ReSyncFlowClient client = new ReSyncFlowClient(UUID.randomUUID().toString(), editor, "bridge", null,
            scheduler, () -> 1000, null, null);
        try {
            editor.connect();
            client.connect().join();
            UUID tunnel = editor.tunnelId();
            editor.receive(new NetworkEditorChunk(tunnel, 0, 0, 0, new byte[0]));
            Field authenticated = ReSyncFlowClient.class.getDeclaredField("authenticated");
            authenticated.setAccessible(true);
            ((BrowserSafeState.BooleanValue) authenticated.get(client)).set(true);
            Method send = ReSyncFlowClient.class.getDeclaredMethod("sendFrame", int.class, byte[].class, short.class,
                boolean.class, int.class, boolean.class, Consumer.class);
            send.setAccessible(true);
            for (int index = 1; index <= 22; index++) {
                assertTrue((boolean) send.invoke(client, 4, new byte[]{(byte) index}, (short) 1, true, -1, false,
                    (Consumer<ReSyncFrameTransport.SendResult>) receipts::add));
            }
            assertEquals(ReSyncFrameTransport.SendAdmission.QUEUE_FULL, editor.admitSend(new byte[]{1}, receipts::add));
            assertFalse(editor.trySend(new byte[]{1}));
            assertEquals(ReSyncFrameTransport.SendAdmission.INVALID, editor.admitSend(new byte[0], receipts::add));
            assertTrue(receipts.isEmpty());
            ReSyncFrameCodec codec = new ReSyncFrameCodec();
            for (int index = 1; index <= 22; index++) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (sent.size() <= index && System.nanoTime() < deadline) Thread.sleep(1);
                assertTrue(sent.size() > index, "The pending burst did not resume after peer receipt");
                NetworkEditorChunk chunk = sent.get(index);
                assertEquals(index, chunk.sequence());
                var frame = codec.decode(chunk.bytes(), Set.of((short) 1));
                assertEquals(index, frame.sequence());
                assertEquals(index, frame.payload()[0]);
                editor.receive(new NetworkEditorChunk(tunnel, index, 0, 0, new byte[0]));
            }
            assertEquals(22, receipts.size());
            assertTrue(receipts.stream().allMatch(ReSyncFrameTransport.SendResult::delivered));
            assertTrue(editor.isOpen());
            assertEquals(1, client.handshakeObservation().activeGeneration());
            int beforeReconnect = sent.size();
            editor.fail(tunnel, "ReSync Runtime Disconnected");
            ReSyncFlowClientTestHarness.drain(client);
            client.connect().join();
            ReSyncFlowClientTestHarness.drain(client);
            assertTrue(editor.isOpen(), () -> client.handshakeObservation() + " " + client.connectionFailure()
                + " Reusable " + editor.reusableAfterDisconnect());
            assertFalse(tunnel.equals(editor.tunnelId()));
            assertTrue(sent.size() > beforeReconnect);
            assertEquals(ReSyncProtocolContract.MESSAGE_HANDSHAKE_REQUEST,
                codec.decode(sent.get(beforeReconnect).bytes(), null).messageType());
            int beforeDenied = sent.size();
            editor.fail(editor.tunnelId(), "Access Denied");
            ReSyncFlowClientTestHarness.drain(client);
            client.connect().join();
            assertFalse(editor.isOpen());
            assertEquals(beforeDenied, sent.size());
        } finally {
            client.shutdown();
            TaskIdentities.install(previousIdentity);
        }
    }

    private static final class Secrets implements NetworkRuntimeMonitor.Access {
        private String credential = "";
        private Runnable readAction;
        private int reads;
        private int saves;
        @Override
        public String credential(String networkId, String nodeId) {
            reads++;
            String value = credential;
            Runnable action = readAction;
            readAction = null;
            if (action != null) action.run();
            return value;
        }
        @Override
        public void saveCredential(String networkId, String nodeId, String value) { saves++; credential = value; }
        @Override
        public String enrollmentToken(String networkId, String nodeId) { return "token"; }
        @Override
        public String endpointStamp(String networkId) { return "endpoint-1"; }
        @Override
        public Async<NetworkRuntimeMonitor.Endpoint> open(String networkId, NetworkRuntimePolicy policy) {
            return Async.completed(new NetworkRuntimeMonitor.Endpoint("ws://127.0.0.1:25566", () -> {}));
        }
    }

    private static final class Scheduler implements TaskScheduler {
        private Runnable tick;
        private boolean reject;
        @Override
        public void execute(Runnable task) {
            if (reject) throw new IllegalStateException("Scheduler Unavailable");
            task.run();
        }
        @Override
        public ScheduledTask schedule(Runnable task, Duration delay) { return scheduled(); }
        @Override
        public ScheduledTask scheduleAtFixedRate(Runnable task, Duration delay, Duration period) { tick = task; return scheduled(); }
        private ScheduledTask scheduled() {
            return new ScheduledTask() {
                @Override
                public boolean cancel() { return true; }
                @Override
                public boolean isCancelled() { return false; }
            };
        }
    }

    private static final class Socket implements BinaryWebSocket {
        private final BinaryWebSocketListener listener;
        private boolean open = true;
        private final List<NetworkFrame> sent = new ArrayList<>();
        private Socket(BinaryWebSocketListener listener) { this.listener = listener; }
        private NetworkAuthentication authentication() { return NetworkAuthenticationCodec.decode(sent.getFirst().payload()); }
        private void editorFrame(NetworkDefinition network, NetworkFrameType type, byte[] payload) {
            NetworkRequestContext context = new NetworkRequestContext(1, network.networkId(), network.proxyMember().nodeId(), "editor", Long.MAX_VALUE, Set.of());
            listener.onBinary(new NetworkFrameCodec(1_048_576, 500_000).encode(new NetworkFrame(context, NetworkChannels.EDITOR, type, payload)));
        }
        private void presence(NetworkDefinition network, NetworkFrameType type, String nodeId, NetworkNodeStatus status, long observedAt) {
            NetworkRequestContext context = new NetworkRequestContext(1, network.networkId(), network.proxyMember().nodeId(), "presence", Long.MAX_VALUE, Set.of("presence.read"));
            NetworkNodePresence presence = new NetworkNodePresence(network.networkId(), nodeId, status, 0, 100, 20, 1, 1, 2, observedAt);
            listener.onBinary(new NetworkFrameCodec(1_048_576, 500_000).encode(new NetworkFrame(context, NetworkChannels.PRESENCE, type, NetworkNodePresenceCodec.encode(presence))));
        }
        private void frame(NetworkDefinition network, NetworkFrameType type, String requestId) {
            NetworkRequestContext context = new NetworkRequestContext(1, network.networkId(), network.proxyMember().nodeId(), requestId, Long.MAX_VALUE, Set.of("node.heartbeat", "routes.write"));
            listener.onBinary(new NetworkFrameCodec(1_048_576, 500_000).encode(new NetworkFrame(context, NetworkChannels.CONTROL, type, new byte[0])));
        }
        @Override
        public Async<Void> sendText(String value) { return Async.completed(null); }
        @Override
        public Async<Void> sendBinary(byte[] value) { sent.add(new NetworkFrameCodec(1_048_576, 500_000).decode(value)); return Async.completed(null); }
        @Override
        public Async<Void> close(int code, String reason) { open = false; listener.onClose(code, reason); return Async.completed(null); }
        @Override
        public boolean isOpen() { return open; }
    }
}
