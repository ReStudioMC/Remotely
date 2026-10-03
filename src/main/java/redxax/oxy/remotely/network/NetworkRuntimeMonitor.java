package redxax.oxy.remotely.network;

import redxax.oxy.remotely.util.BrowserSafeState;

import restudio.rebase.backend.ServerBackend;
import restudio.rebase.backend.feature.PortForwardFeature;
import restudio.rebase.backend.impl.LocalBackend;
import restudio.rebase.instance.Instance;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rebase.platform.jvm.JvmClock;
import restudio.rebase.platform.jvm.JvmTaskScheduler;
import restudio.rescreen.platform.websocket.BinaryWebSocket;
import restudio.rescreen.platform.websocket.BinaryWebSocketListener;
import restudio.rescreen.platform.websocket.WebSocketTransport;
import restudio.resync.network.NetworkChannels;
import restudio.resync.network.NetworkEvent;
import restudio.resync.network.NetworkEventCodec;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameCodec;
import restudio.resync.network.NetworkFrameType;
import restudio.resync.network.NetworkNodePresence;
import restudio.resync.network.NetworkNodePresenceCodec;
import restudio.resync.network.NetworkNodeMode;
import restudio.resync.network.NetworkNodeModeCodec;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.NetworkProxyAction;
import restudio.resync.network.NetworkProxyActionCodec;
import restudio.resync.network.NetworkProxyActionType;
import restudio.resync.network.NetworkRequestContext;
import restudio.resync.network.NetworkRoute;
import restudio.resync.network.NetworkRouteSet;
import restudio.resync.network.NetworkRouteSetCodec;
import restudio.resync.network.NetworkRoutingGroup;
import restudio.resync.network.NetworkRoutingStrategy;
import restudio.resync.network.NetworkSnapshotAdminCodec;
import restudio.resync.network.NetworkSnapshotMetadata;
import restudio.resync.network.NetworkSnapshotPin;
import restudio.resync.network.NetworkSnapshotQuery;
import restudio.resync.network.NetworkSnapshotRestore;
import restudio.resync.network.NetworkStateReconciliationCodec;
import restudio.resync.network.NetworkStateReconciliationRequest;
import restudio.resync.network.NetworkTransferCodec;
import restudio.resync.network.PlayerTransfer;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import restudio.rescreen.platform.Async;
import java.util.function.Consumer;
import java.util.function.Function;

public class NetworkRuntimeMonitor implements AutoCloseable {
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAXIMUM_FRAME_BYTES = 1_048_576;
    private static final int MAXIMUM_PAYLOAD_BYTES = 524_288;
    private static final long RETRY_DELAY_MILLIS = 5_000;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 5_000;
    private static final long CONNECT_TIMEOUT_MILLIS = 15_000;
    private final NetworkSecretStore secretStore;
    private final WebSocketTransport webSocketTransport;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final NetworkFrameCodec codec = new NetworkFrameCodec(MAXIMUM_FRAME_BYTES, MAXIMUM_PAYLOAD_BYTES);
    private final TaskScheduler.ScheduledTask tickTask;
    private final Map<String, Target> targets = BrowserSafeState.map();
    private final Map<String, Session> sessions = BrowserSafeState.map();
    private final Map<String, NetworkRuntimeSnapshot> snapshots = BrowserSafeState.map();
    private final Map<String, Long> nextAttempts = BrowserSafeState.map();
    private final List<Consumer<NetworkRuntimeSnapshot>> listeners = BrowserSafeState.list();
    private final List<Consumer<NetworkEvent>> eventListeners = BrowserSafeState.list();
    private final BrowserSafeState.BooleanValue closed = new BrowserSafeState.BooleanValue();

    public NetworkRuntimeMonitor(NetworkSecretStore secretStore, WebSocketTransport webSocketTransport) {
        this(secretStore, webSocketTransport, new JvmTaskScheduler(), new JvmClock());
    }

    public NetworkRuntimeMonitor(NetworkSecretStore secretStore, WebSocketTransport webSocketTransport,
                                 TaskScheduler scheduler, Clock clock) {
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        this.webSocketTransport = Objects.requireNonNull(webSocketTransport, "webSocketTransport");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tickTask = scheduler.scheduleAtFixedRate(this::tick, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    public void refresh(Collection<NetworkDefinition> networks, Collection<Instance> instances) {
        if (closed.get()) {
            return;
        }
        ListSnapshot snapshot = new ListSnapshot(networks, instances);
        scheduler.execute(() -> reconcile(snapshot));
    }

    public NetworkRuntimeSnapshot snapshot(String networkId) {
        String normalized = networkId == null ? "" : networkId.trim();
        return normalized.isBlank() ? NetworkRuntimeSnapshot.disabled("") : snapshots.getOrDefault(normalized, NetworkRuntimeSnapshot.disabled(normalized));
    }

    public void addListener(Consumer<NetworkRuntimeSnapshot> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        listeners.remove(listener);
    }

    public void addEventListener(Consumer<NetworkEvent> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    public void removeEventListener(Consumer<NetworkEvent> listener) {
        eventListeners.remove(listener);
    }

    public Async<Void> setNodeMode(String networkId, String nodeId, NetworkNodeStatus status) {
        String normalizedNetworkId = networkId == null ? "" : networkId.trim();
        if (closed.get() || normalizedNetworkId.isBlank()) {
            return Async.failed(new IllegalStateException("ReSync Runtime Is Not Available"));
        }
        NetworkNodeMode mode;
        try {
            mode = new NetworkNodeMode(nodeId, status);
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
        Async<Void> result = Async.pending();
        try {
            scheduler.execute(() -> {
                Session session = sessions.get(normalizedNetworkId);
                if (session == null || !session.authorized()) {
                    result.completeExceptionally(new IllegalStateException("ReSync Runtime Is Not Connected"));
                    return;
                }
                session.setNodeMode(mode, result);
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    public Async<Void> executeProxyCommand(String networkId, String command) {
        try {
            return proxyAction(networkId, new NetworkProxyAction(NetworkProxyActionType.COMMAND, command));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    public Async<Void> broadcast(String networkId, String message) {
        try {
            return proxyAction(networkId, new NetworkProxyAction(NetworkProxyActionType.BROADCAST, message));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    public Async<List<NetworkSnapshotMetadata>> listSnapshots(String networkId, UUID playerId, int limit) {
        return listSnapshots(networkId, playerId, 0, limit);
    }

    public Async<List<NetworkSnapshotMetadata>> listSnapshots(String networkId, UUID playerId, int offset, int limit) {
        try {
            NetworkSnapshotQuery query = new NetworkSnapshotQuery(playerId, offset, limit);
            return runtimeRequest(networkId, session -> session.request(NetworkFrameType.SNAPSHOT_LIST, NetworkChannels.STATE, NetworkSnapshotAdminCodec.encodeQuery(query), Set.of("state.inspect"), 10).thenApply(frame -> NetworkSnapshotAdminCodec.decodeList(frame.payload())));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    public Async<NetworkSnapshotMetadata> readSnapshot(String networkId, String snapshotId) {
        try {
            return runtimeRequest(networkId, session -> session.request(NetworkFrameType.SNAPSHOT_READ, NetworkChannels.STATE, NetworkSnapshotAdminCodec.encodeReference(snapshotId), Set.of("state.inspect"), 10).thenApply(frame -> NetworkSnapshotAdminCodec.decodeMetadata(frame.payload())));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    public Async<NetworkSnapshotMetadata> pinSnapshot(String networkId, String snapshotId, boolean pinned) {
        try {
            NetworkSnapshotPin pin = new NetworkSnapshotPin(snapshotId, pinned);
            return runtimeRequest(networkId, session -> session.request(NetworkFrameType.SNAPSHOT_PIN, NetworkChannels.STATE, NetworkSnapshotAdminCodec.encodePin(pin), Set.of("state.restore"), 10).thenApply(frame -> NetworkSnapshotAdminCodec.decodeMetadata(frame.payload())));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    public Async<PlayerTransfer> restoreSnapshot(String networkId, String snapshotId, String targetNodeId) {
        try {
            NetworkSnapshotRestore restore = new NetworkSnapshotRestore(snapshotId, targetNodeId, deadline(600));
            return runtimeRequest(networkId, session -> session.request(NetworkFrameType.SNAPSHOT_RESTORE, NetworkChannels.STATE, NetworkSnapshotAdminCodec.encodeRestore(restore), Set.of("state.restore"), 30).thenApply(frame -> NetworkTransferCodec.decodeTransfer(frame.payload())));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    public Async<Void> reconcilePlayerState(String networkId, NetworkStateReconciliationRequest request) {
        try {
            return runtimeRequest(networkId, session -> session.request(NetworkFrameType.STATE_RECONCILE, NetworkChannels.STATE, NetworkStateReconciliationCodec.encodeRequest(request), Set.of("state.restore"), 610).thenApply(frame -> null));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    private <T> Async<T> runtimeRequest(String networkId, Function<Session, Async<T>> operation) {
        String normalizedNetworkId = networkId == null ? "" : networkId.trim();
        if (closed.get() || normalizedNetworkId.isBlank()) {
            return Async.failed(new IllegalStateException("ReSync Runtime Is Not Available"));
        }
        Async<T> result = Async.pending();
        try {
            scheduler.execute(() -> {
                Session session = sessions.get(normalizedNetworkId);
                if (session == null || !session.authorized()) {
                    result.completeExceptionally(new IllegalStateException("ReSync Runtime Is Not Connected"));
                    return;
                }
                try {
                    operation.apply(session).whenComplete((value, throwable) -> {
                        if (throwable != null) {
                            result.completeExceptionally(throwable);
                        } else {
                            result.complete(value);
                        }
                    });
                } catch (RuntimeException exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private Async<Void> proxyAction(String networkId, NetworkProxyAction action) {
        String normalizedNetworkId = networkId == null ? "" : networkId.trim();
        if (closed.get() || normalizedNetworkId.isBlank()) {
            return Async.failed(new IllegalStateException("ReSync Runtime Is Not Available"));
        }
        Async<Void> result = Async.pending();
        try {
            scheduler.execute(() -> {
                Session session = sessions.get(normalizedNetworkId);
                if (session == null || !session.authorized()) {
                    result.completeExceptionally(new IllegalStateException("ReSync Runtime Is Not Connected"));
                    return;
                }
                session.proxyAction(action, result);
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private void reconcile(ListSnapshot source) {
        if (closed.get()) {
            return;
        }
        Map<String, Instance> instancesById = new LinkedHashMap<>();
        source.instances().forEach(instance -> instancesById.put(instance.getInstanceId(), instance));
        Map<String, Target> nextTargets = new LinkedHashMap<>();
        for (NetworkDefinition network : source.networks()) {
            NetworkRuntimePolicy runtime = network.runtime();
            if (!runtime.enabled()) {
                publish(NetworkRuntimeSnapshot.disabled(network.networkId()));
                continue;
            }
            if (!runtime.transportReady()) {
                publish(state(network.networkId(), NetworkRuntimeConnectionState.UNAVAILABLE, "Secure Runtime Transport Pending", false));
                continue;
            }
            NetworkMember proxy = network.proxyMember();
            Instance proxyInstance = proxy == null ? null : instancesById.get(proxy.instanceId());
            Target previous = targets.get(network.networkId());
            if (previous != null && previous.revision() == network.revision() && previous.proxyInstance() == proxyInstance && previous.runtime().equals(runtime)) {
                nextTargets.put(network.networkId(), previous);
                continue;
            }
            List<NetworkRoute> routes = network.members().stream().filter(member -> !member.isProxy()).map(member -> new NetworkRoute(member.nodeId(), member.routeName(), member.address(), member.port())).toList();
            List<NetworkRoutingGroup> routingGroups = network.routingGroups().stream().map(group -> new NetworkRoutingGroup(group.id(), group.name(), NetworkRoutingStrategy.valueOf(group.strategy().name()), group.nodeIds(), group.weights(), group.fallbackGroupId(), group.forcedHosts(), group.permission())).toList();
            String maintenanceRoute = NetworkDesiredStatePlanner.fallbackRoutes(network).stream().findFirst().orElse("");
            Target target = new Target(network.networkId(), network.revision(), proxy == null ? "" : proxy.nodeId(), NetworkRuntimeIdentity.operatorNodeId(network.networkId()), runtime, proxyInstance, maintenanceRoute, routes, routingGroups);
            nextTargets.put(network.networkId(), target);
        }
        targets.clear();
        targets.putAll(nextTargets);
        sessions.forEach((networkId, session) -> {
            Target target = nextTargets.get(networkId);
            if (target == null || !session.matches(target)) {
                if (sessions.remove(networkId, session)) {
                    session.close();
                }
            }
        });
        snapshots.keySet().removeIf(networkId -> source.networks().stream().noneMatch(network -> network.networkId().equals(networkId)));
        nextAttempts.keySet().removeIf(networkId -> !nextTargets.containsKey(networkId));
        nextTargets.values().forEach(this::ensureConnected);
    }

    private void tick() {
        if (closed.get()) {
            return;
        }
        targets.values().forEach(this::ensureConnected);
        sessions.values().forEach(Session::heartbeat);
    }

    private void ensureConnected(Target target) {
        if (closed.get() || clock.millis() < nextAttempts.getOrDefault(target.networkId(), 0L)) {
            return;
        }
        Session current = sessions.get(target.networkId());
        if (current != null && current.matches(target) && !current.closed()) {
            if (!current.connectionTimedOut()) {
                return;
            }
            if (sessions.remove(target.networkId(), current)) {
                current.close();
            }
            nextAttempts.put(target.networkId(), clock.millis() + RETRY_DELAY_MILLIS);
            publish(state(target.networkId(), NetworkRuntimeConnectionState.RECONNECTING, "Runtime Connection Timed Out", false));
            return;
        }
        if (current != null && sessions.remove(target.networkId(), current)) {
            current.close();
        }
        publish(state(target.networkId(), NetworkRuntimeConnectionState.CONNECTING, "Connecting ReSync Runtime", false));
        Session session = null;
        try {
            session = open(target);
            sessions.put(target.networkId(), session);
            session.connect();
        } catch (Exception exception) {
            if (session != null && sessions.remove(target.networkId(), session)) {
                session.close();
            }
            nextAttempts.put(target.networkId(), clock.millis() + RETRY_DELAY_MILLIS);
            publish(state(target.networkId(), NetworkRuntimeConnectionState.UNAVAILABLE, rootMessage(exception), false));
        }
    }

    private Session open(Target target) throws IOException {
        if (target.proxyInstance() == null) {
            throw new IllegalStateException("Proxy Instance Unavailable");
        }
        PortForwardFeature.Forward forward = null;
        URI endpoint;
        if (target.runtime().security() == NetworkTransportSecurity.LOOPBACK) {
            ServerBackend backend = target.proxyInstance().getBackend();
            PortForwardFeature portForward = backend.getFeature(PortForwardFeature.class).orElse(null);
            if (portForward != null) {
                forward = portForward.open(target.runtime().hubAddress(), target.runtime().hubPort());
                endpoint = URI.create("ws://127.0.0.1:" + forward.localPort());
            } else if (backend instanceof LocalBackend) {
                endpoint = URI.create(target.runtime().hubUrl());
            } else {
                throw new IllegalStateException("Proxy Runtime Tunnel Is Unavailable");
            }
        } else {
            endpoint = URI.create(target.runtime().hubUrl());
        }
        try {
            return new Session(target, endpoint, forward);
        } catch (RuntimeException exception) {
            closeForward(forward);
            throw exception;
        }
    }

    private NetworkRuntimeSnapshot state(String networkId, NetworkRuntimeConnectionState state, String message, boolean clearNodes) {
        return snapshots.getOrDefault(networkId, NetworkRuntimeSnapshot.disabled(networkId)).connection(state, message, clearNodes);
    }

    private void publish(NetworkRuntimeSnapshot snapshot) {
        snapshots.put(snapshot.networkId(), snapshot);
        for (Consumer<NetworkRuntimeSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void disconnected(Session session, String reason) {
        if (!sessions.remove(session.target().networkId(), session) || session.closed() || closed.get()) {
            return;
        }
        session.finishDisconnect();
        if ("Network Credential Rejected".equals(reason)) {
            secretStore.deleteRuntimeCredential(session.target().networkId(), session.target().operatorNodeId());
        }
        nextAttempts.put(session.target().networkId(), clock.millis() + RETRY_DELAY_MILLIS);
        publish(state(session.target().networkId(), NetworkRuntimeConnectionState.RECONNECTING, reason == null || reason.isBlank() ? "Reconnecting ReSync Runtime" : reason, false));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private long deadline(long seconds) {
        return clock.millis() + seconds * 1_000L;
    }

    private void closeForward(PortForwardFeature.Forward forward) {
        if (forward == null) {
            return;
        }
        try {
            forward.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        tickTask.cancel();
        sessions.values().forEach(Session::close);
        sessions.clear();
        targets.clear();
        nextAttempts.clear();
    }

    private final class Session implements AutoCloseable {
        private final Target target;
        private final PortForwardFeature.Forward forward;
        private final String endpoint;
        private final Map<String, String> headers;
        private final BrowserSafeState.BooleanValue closed = new BrowserSafeState.BooleanValue();
        private final BrowserSafeState.BooleanValue opened = new BrowserSafeState.BooleanValue();
        private final BrowserSafeState.LongValue requestIds = new BrowserSafeState.LongValue();
        private final Map<String, Async<Void>> pending = BrowserSafeState.map();
        private final Map<String, Async<NetworkFrame>> responses = BrowserSafeState.map();
        private final long createdAt = clock.millis();
        private volatile BinaryWebSocket client;
        private volatile boolean authorized;
        private volatile long lastHeartbeat;

        private Session(Target target, URI endpoint, PortForwardFeature.Forward forward) {
            this.target = target;
            this.forward = forward;
            String credential = secretStore.resolveRuntimeCredential(target.networkId(), target.operatorNodeId());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-ReSync-Network", target.networkId());
            headers.put("X-ReSync-Node", target.operatorNodeId());
            if (credential.isBlank()) {
                headers.put("X-ReSync-Enrollment", secretStore.getOrCreateEnrollmentToken(target.networkId(), target.operatorNodeId()));
            } else {
                headers.put("X-ReSync-Credential", credential);
            }
            this.endpoint = endpoint.toString();
            this.headers = Map.copyOf(headers);
        }

        private void connect() {
            try {
                webSocketTransport.connectAsync(endpoint, headers, new BinaryWebSocketListener() {
                    @Override
                    public void onOpen(BinaryWebSocket socket) {
                        open(socket);
                    }

                    @Override
                    public void onText(String message) {
                        BinaryWebSocket socket = client;
                        if (socket != null) {
                            socket.close(1003, "Binary Network Frames Required");
                        }
                    }

                    @Override
                    public void onBinary(byte[] message) {
                        try {
                            handle(codec.decode(message));
                        } catch (RuntimeException exception) {
                            BinaryWebSocket socket = client;
                            if (socket != null) {
                                socket.close(1008, rootMessage(exception));
                            }
                        }
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        client = null;
                        authorized = false;
                        disconnected(Session.this, reason);
                    }

                    @Override
                    public void onError(Throwable error) {
                        BinaryWebSocket socket = client;
                        if (!Session.this.closed.get() && (socket == null || !socket.isOpen())) {
                            disconnected(Session.this, rootMessage(error));
                        }
                    }
                }).whenComplete((socket, error) -> {
                    if (error != null) {
                        disconnected(Session.this, rootMessage(error));
                    } else if (socket != null) {
                        open(socket);
                    }
                });
            } catch (RuntimeException exception) {
                disconnected(this, rootMessage(exception));
            }
        }

        private void open(BinaryWebSocket socket) {
            if (socket == null || closed.get()) {
                if (socket != null) {
                    socket.close(1000, "Connection Closed");
                }
                return;
            }
            client = socket;
            if (opened.compareAndSet(false, true)) {
                authorized = false;
                publish(state(target.networkId(), NetworkRuntimeConnectionState.CONNECTING, "Authenticating ReSync Runtime", false));
            }
        }

        private boolean matches(Target candidate) {
            return target.networkId().equals(candidate.networkId()) && target.revision() == candidate.revision() && target.runtime().equals(candidate.runtime()) && target.proxyInstance() == candidate.proxyInstance();
        }

        private boolean closed() {
            return closed.get();
        }

        private boolean authorized() {
            BinaryWebSocket socket = client;
            return authorized && !closed.get() && socket != null && socket.isOpen();
        }

        private boolean connectionTimedOut() {
            return !authorized && clock.millis() - createdAt >= CONNECT_TIMEOUT_MILLIS;
        }

        private Target target() {
            return target;
        }

        private void handle(NetworkFrame frame) {
            if (!frame.context().networkId().equals(target.networkId()) || !frame.context().nodeId().equals(target.hubNodeId())) {
                throw new SecurityException("Network Hub Identity Does Not Match");
            }
            if (frame.type() == NetworkFrameType.ENROLL_ACK) {
                String credential = new String(frame.payload(), StandardCharsets.UTF_8).trim();
                secretStore.saveRuntimeCredential(target.networkId(), target.operatorNodeId(), credential);
                authorize();
                return;
            }
            if (frame.type() == NetworkFrameType.RESPONSE && frame.context().requestId().equals("session")) {
                authorize();
                return;
            }
            Async<NetworkFrame> response = frame.type() == NetworkFrameType.ERROR ? null : responses.remove(frame.context().requestId());
            if (response != null) {
                response.complete(frame);
                return;
            }
            if (frame.type() == NetworkFrameType.RESPONSE) {
                Async<Void> request = pending.remove(frame.context().requestId());
                if (request != null) {
                    request.complete(null);
                    return;
                }
            }
            if (frame.type() == NetworkFrameType.RESPONSE && frame.context().requestId().equals("routes-" + target.revision())) {
                publish(state(target.networkId(), NetworkRuntimeConnectionState.CONNECTED, "Runtime Routes Ready", false));
                return;
            }
            if ((frame.type() == NetworkFrameType.PRESENCE_SNAPSHOT || frame.type() == NetworkFrameType.PRESENCE_DELTA) && frame.channel().equals(NetworkChannels.PRESENCE)) {
                NetworkNodePresence presence = NetworkNodePresenceCodec.decode(target.networkId(), frame.payload());
                NetworkRuntimeNodePresence runtimePresence = new NetworkRuntimeNodePresence(presence.networkId(), presence.nodeId(), NetworkRuntimeNodeStatus.valueOf(presence.status().name()), presence.players(), presence.capacity(), presence.tps(), presence.mspt(), presence.heapUsed(), presence.heapMaximum(), presence.observedAt());
                NetworkRuntimeSnapshot current = snapshots.getOrDefault(target.networkId(), NetworkRuntimeSnapshot.disabled(target.networkId()));
                publish(current.presence(runtimePresence));
                return;
            }
            if (frame.type() == NetworkFrameType.EVENT_DELIVERY && frame.channel().equals(NetworkChannels.EVENTS)) {
                NetworkEvent event = NetworkEventCodec.decodeEvent(frame.payload());
                if (!event.networkId().equals(target.networkId())) {
                    throw new SecurityException("Network Event Identity Does Not Match");
                }
                for (Consumer<NetworkEvent> listener : eventListeners) {
                    try {
                        listener.accept(event);
                    } catch (RuntimeException ignored) {
                    }
                }
                acknowledgeEvent(event.eventId());
                return;
            }
            if (frame.type() == NetworkFrameType.ERROR) {
                String message = new String(frame.payload(), StandardCharsets.UTF_8);
                Async<Void> request = pending.remove(frame.context().requestId());
                if (request != null) {
                    request.completeExceptionally(new IllegalStateException(message));
                }
                Async<NetworkFrame> typed = responses.remove(frame.context().requestId());
                if (typed != null) {
                    typed.completeExceptionally(new IllegalStateException(message));
                }
                publish(state(target.networkId(), NetworkRuntimeConnectionState.CONNECTED, "Runtime Operation Failed • " + message, false));
            }
        }

        private void authorize() {
            authorized = true;
            lastHeartbeat = 0;
            publish(state(target.networkId(), NetworkRuntimeConnectionState.CONNECTED, "ReSync Runtime Connected", true));
            reconcileRoutes();
            heartbeat();
        }

        private void reconcileRoutes() {
            String requestId = "routes-" + target.revision();
            NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(), target.operatorNodeId(), requestId, deadline(10), Set.of("routes.write"));
            byte[] payload = target.routePayload();
            send(codec.encode(new NetworkFrame(context, NetworkChannels.ROUTING, NetworkFrameType.ROUTE_RECONCILE, payload)));
        }

        private void setNodeMode(NetworkNodeMode mode, Async<Void> result) {
            String requestId = "mode-" + requestIds.incrementAndGet();
            NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(), target.operatorNodeId(), requestId, deadline(10), Set.of("nodes.manage"));
            pending.put(requestId, result);
            try {
                send(codec.encode(new NetworkFrame(context, NetworkChannels.CONTROL, NetworkFrameType.NODE_MODE_SET, NetworkNodeModeCodec.encode(mode))));
                scheduler.schedule(() -> timeout(requestId), Duration.ofSeconds(10));
            } catch (RuntimeException exception) {
                pending.remove(requestId, result);
                result.completeExceptionally(exception);
            }
        }

        private void proxyAction(NetworkProxyAction action, Async<Void> result) {
            String requestId = "action-" + requestIds.incrementAndGet();
            String scope = action.type() == NetworkProxyActionType.COMMAND ? "proxy.command" : "proxy.broadcast";
            NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(), target.operatorNodeId(), requestId, deadline(10), Set.of(scope));
            pending.put(requestId, result);
            try {
                send(codec.encode(new NetworkFrame(context, NetworkChannels.CONTROL, NetworkFrameType.PROXY_ACTION, NetworkProxyActionCodec.encode(action))));
                scheduler.schedule(() -> timeout(requestId), Duration.ofSeconds(10));
            } catch (RuntimeException exception) {
                pending.remove(requestId, result);
                result.completeExceptionally(exception);
            }
        }

        private Async<NetworkFrame> request(NetworkFrameType type, String channel, byte[] payload, Set<String> scopes, int timeoutSeconds) {
            String requestId = "request-" + requestIds.incrementAndGet();
            NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(), target.operatorNodeId(), requestId, deadline(timeoutSeconds), scopes);
            Async<NetworkFrame> result = Async.pending();
            responses.put(requestId, result);
            try {
                send(codec.encode(new NetworkFrame(context, channel, type, payload)));
                scheduler.schedule(() -> timeout(requestId), Duration.ofSeconds(timeoutSeconds));
            } catch (RuntimeException exception) {
                responses.remove(requestId, result);
                result.completeExceptionally(exception);
            }
            return result;
        }

        private void acknowledgeEvent(String eventId) {
            String requestId = "event-ack-" + eventId;
            NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(), target.operatorNodeId(), requestId, deadline(10), Set.of("events.consume"));
            send(codec.encode(new NetworkFrame(context, NetworkChannels.EVENTS, NetworkFrameType.EVENT_ACK, NetworkEventCodec.encodeAcknowledgement(eventId))));
        }

        private void timeout(String requestId) {
            Async<Void> request = pending.remove(requestId);
            if (request != null) {
                request.completeExceptionally(new IllegalStateException("Runtime Operation Timed Out"));
            }
            Async<NetworkFrame> response = responses.remove(requestId);
            if (response != null) {
                response.completeExceptionally(new IllegalStateException("Runtime Operation Timed Out"));
            }
        }

        private void heartbeat() {
            long now = clock.millis();
            BinaryWebSocket socket = client;
            if (!authorized || closed.get() || socket == null || !socket.isOpen() || now - lastHeartbeat < HEARTBEAT_INTERVAL_MILLIS) {
                return;
            }
            lastHeartbeat = now;
            String requestId = target.operatorNodeId() + "-" + requestIds.incrementAndGet();
            NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(), target.operatorNodeId(), requestId, deadline(10), Set.of("node.heartbeat", "presence.read"));
            send(codec.encode(new NetworkFrame(context, NetworkChannels.CONTROL, NetworkFrameType.HEARTBEAT, new byte[0])));
        }

        private void closeForward() {
            NetworkRuntimeMonitor.this.closeForward(forward);
        }

        private void finishDisconnect() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            authorized = false;
            failPending("ReSync Runtime Disconnected");
            closeSocket(1000, "Runtime Disconnected");
            closeForward();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            authorized = false;
            failPending("ReSync Runtime Closed");
            closeSocket(1000, "Runtime Closed");
            closeForward();
        }

        private void failPending(String message) {
            pending.values().forEach(request -> request.completeExceptionally(new IllegalStateException(message)));
            pending.clear();
            responses.values().forEach(request -> request.completeExceptionally(new IllegalStateException(message)));
            responses.clear();
        }

        private void send(byte[] frame) {
            BinaryWebSocket socket = client;
            if (socket == null || !socket.isOpen()) {
                throw new IllegalStateException("ReSync Runtime WebSocket Is Not Open");
            }
            socket.sendBinary(frame).whenComplete((unused, error) -> {
                if (error != null && !closed.get()) {
                    disconnected(this, rootMessage(error));
                }
            });
        }

        private void closeSocket(int statusCode, String reason) {
            BinaryWebSocket socket = client;
            if (socket != null && socket.isOpen()) {
                socket.close(statusCode, reason);
            }
        }
    }

    private record Target(String networkId, long revision, String hubNodeId, String operatorNodeId, NetworkRuntimePolicy runtime, Instance proxyInstance, String maintenanceRoute, List<NetworkRoute> routes, List<NetworkRoutingGroup> routingGroups, byte[] routePayload) {
        private Target(String networkId, long revision, String hubNodeId, String operatorNodeId, NetworkRuntimePolicy runtime, Instance proxyInstance, String maintenanceRoute, List<NetworkRoute> routes, List<NetworkRoutingGroup> routingGroups) {
            this(networkId, revision, hubNodeId, operatorNodeId, runtime, proxyInstance, maintenanceRoute, routes, routingGroups,
                    NetworkRouteSetCodec.encode(new NetworkRouteSet(revision, maintenanceRoute, routes, routingGroups)));
        }

        private Target {
            maintenanceRoute = maintenanceRoute == null ? "" : maintenanceRoute;
            routes = List.copyOf(routes);
            routingGroups = List.copyOf(routingGroups);
            routePayload = routePayload.clone();
        }

        @Override
        public byte[] routePayload() {
            return routePayload.clone();
        }
    }

    private record ListSnapshot(List<NetworkDefinition> networks, List<Instance> instances) {
        private ListSnapshot(Collection<NetworkDefinition> networks, Collection<Instance> instances) {
            this(networks == null ? List.of() : networks.stream().toList(), instances == null ? List.of() : instances.stream().toList());
        }
    }
}
