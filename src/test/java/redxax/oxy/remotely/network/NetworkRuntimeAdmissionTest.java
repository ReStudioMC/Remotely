package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import restudio.rebase.backend.BackendFactory;
import restudio.rebase.backend.impl.LocalBackend;
import restudio.rebase.instance.Instance;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.websocket.BinaryWebSocket;
import restudio.rescreen.platform.websocket.BinaryWebSocketListener;
import restudio.resync.network.NetworkChannels;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameCodec;
import restudio.resync.network.NetworkFrameType;
import restudio.resync.network.NetworkRequestContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRuntimeAdmissionTest {
    @Test
    void admissionRequiresRouteAcknowledgementAndRejectsExpiredOrReplacedSessions() {
        BackendFactory.register("LOCAL", LocalBackend::new);
        Instance proxy = new Instance("Proxy", "", ".");
        NetworkMember member = NetworkMember.proxy(proxy.getInstanceId(), 25565);
        NetworkDefinition network = NetworkDefinition.create("Test", proxy.getInstanceId(), NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(member, NetworkMember.backend("backend", "lobby", NetworkMemberRole.LOBBY, 25566)));
        AtomicLong time = new AtomicLong(1000);
        Scheduler scheduler = new Scheduler();
        Secrets secrets = new Secrets();
        List<Socket> sockets = new ArrayList<>();
        NetworkRuntimeMonitor monitor = new NetworkRuntimeMonitor(secrets, (uri, headers, listener) -> {
            assertEquals(secrets.credential, headers.get("X-ReSync-Enrollment-Credential"));
            Socket socket = new Socket(listener, headers);
            sockets.add(socket);
            listener.onOpen(socket);
            return Async.completed(socket);
        }, scheduler, time::get);
        try {
            monitor.refresh(List.of(network), List.of(proxy));
            Socket first = sockets.getFirst();
            first.frame(network, NetworkFrameType.RESPONSE, "session");
            assertFalse(monitor.snapshot(network).connected());
            first.frame(network, NetworkFrameType.RESPONSE, "routes-" + network.revision());
            assertTrue(monitor.snapshot(network).connected());
            NetworkDefinition changed = network.nextRevision(network.members(), network.routingGroups(), network.syncRealms(), NetworkDesiredState.RUNNING);
            assertFalse(monitor.snapshot(changed).connected());
            monitor.refresh(List.of(changed), List.of(proxy));
            assertFalse(first.open);
            first.frame(network, NetworkFrameType.RESPONSE, "routes-" + network.revision());
            assertFalse(monitor.snapshot(changed).connected());
            Socket second = sockets.getLast();
            second.frame(changed, NetworkFrameType.RESPONSE, "session");
            second.frame(changed, NetworkFrameType.RESPONSE, "routes-" + changed.revision());
            assertTrue(monitor.snapshot(changed).connected());
            time.addAndGet(30_000);
            assertFalse(monitor.snapshot(changed).connected());
            scheduler.tick.run();
            assertFalse(second.open);
            assertFalse(monitor.snapshot(changed).connected());
            time.addAndGet(10_000);
            scheduler.tick.run();
            Socket rejected = sockets.getLast();
            assertTrue(rejected.headers.containsKey("X-ReSync-Credential"));
            rejected.close(1008, "Network Credential Rejected");
            time.addAndGet(10_000);
            scheduler.tick.run();
            assertFalse(sockets.getLast().headers.containsKey("X-ReSync-Credential"));
            assertEquals(secrets.credential, sockets.getLast().headers.get("X-ReSync-Enrollment-Credential"));
        } finally { monitor.close(); }
    }

    private static final class Secrets extends NetworkSecretStore {
        private String credential = "";
        @Override
        public String resolveRuntimeCredential(String networkId, String nodeId) { return credential; }
        @Override
        public void saveRuntimeCredential(String networkId, String nodeId, String value) { credential = value; }
        @Override
        public String getOrCreateEnrollmentToken(String networkId, String nodeId) { return "token"; }
    }

    private static final class Scheduler implements TaskScheduler {
        private Runnable tick;
        @Override
        public void execute(Runnable task) { task.run(); }
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
        private final Map<String, String> headers;
        private Socket(BinaryWebSocketListener listener, Map<String, String> headers) { this.listener = listener; this.headers = headers; }
        private void frame(NetworkDefinition network, NetworkFrameType type, String requestId) {
            NetworkRequestContext context = new NetworkRequestContext(1, network.networkId(), network.proxyMember().nodeId(), requestId, Long.MAX_VALUE, Set.of("node.heartbeat", "routes.write"));
            listener.onBinary(new NetworkFrameCodec(1_048_576, 500_000).encode(new NetworkFrame(context, NetworkChannels.CONTROL, type, new byte[0])));
        }
        @Override
        public Async<Void> sendText(String value) { return Async.completed(null); }
        @Override
        public Async<Void> sendBinary(byte[] value) { return Async.completed(null); }
        @Override
        public Async<Void> close(int code, String reason) { open = false; listener.onClose(code, reason); return Async.completed(null); }
        @Override
        public boolean isOpen() { return open; }
    }
}
