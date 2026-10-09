package redxax.oxy.remotely.network;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;

import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.websocket.BinaryWebSocket;
import restudio.rescreen.platform.websocket.BinaryWebSocketListener;
import restudio.rescreen.platform.websocket.WebSocketTransport;
import restudio.resync.network.NetworkChannels;
import restudio.resync.network.NetworkAuthentication;
import restudio.resync.network.NetworkAuthenticationCodec;
import restudio.resync.network.NetworkEditorChunk;
import restudio.resync.network.NetworkEditorClose;
import restudio.resync.network.NetworkEditorCodec;
import restudio.resync.network.NetworkEditorOpen;
import restudio.resync.network.NetworkCredentials;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
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
    public interface Access {
        default String generateCredential() { return NetworkCredentials.generate(); }
        String credential(String networkId, String nodeId);
        void saveCredential(String networkId, String nodeId, String credential);
        default boolean saveCredential(String networkId, String nodeId, String credential, String expectedStamp) {
            if (!Objects.equals(endpointStamp(networkId), expectedStamp)) return false;
            saveCredential(networkId, nodeId, credential);
            return true;
        }
        String enrollmentToken(String networkId, String nodeId);
        String endpointStamp(String networkId);
        Async<Endpoint> open(String networkId, NetworkRuntimePolicy policy);
        default boolean ready(String networkId, NetworkRuntimePolicy policy) { return policy.transportReady(); }
    }

    public record Endpoint(String url, Runnable closer) implements AutoCloseable {
        public Endpoint {
            Objects.requireNonNull(url, "url");
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                throw new IllegalArgumentException("Network Runtime Endpoint Is Invalid");
            }
            closer = closer == null ? () -> {} : closer;
        }

        @Override
        public void close() { closer.run(); }
    }

    private final Access access;
    private final WebSocketTransport webSocketTransport;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final NetworkFrameCodec codec = new NetworkFrameCodec(MAXIMUM_FRAME_BYTES, MAXIMUM_PAYLOAD_BYTES);
    private final TaskScheduler.ScheduledTask tickTask;
    private volatile Map<String, Target> targets = Map.of();
    private volatile Refresh requested = new Refresh(0, List.of(), Map.of());
    private final Object refreshLock = new Object();
    private final Map<String, Session> sessions = BrowserSafeState.map();
    private final Map<String, NetworkRuntimeSnapshot> snapshots = BrowserSafeState.map();
    private final Map<String, Long> publications = BrowserSafeState.map();
    private final Map<String, SnapshotView> snapshotViews = BrowserSafeState.map();
    private final Map<String, Boolean> retryEnrollment = BrowserSafeState.map();
    private final Map<String, Long> nextAttempts = BrowserSafeState.map();
    private final List<Consumer<NetworkRuntimeSnapshot>> listeners = BrowserSafeState.list();
    private final List<Consumer<NetworkEvent>> eventListeners = BrowserSafeState.list();
    private final BrowserSafeState.BooleanValue closed = new BrowserSafeState.BooleanValue();

    public NetworkRuntimeMonitor(Access access, WebSocketTransport webSocketTransport, TaskScheduler scheduler, Clock clock) {
        this.access = Objects.requireNonNull(access, "access");
        this.webSocketTransport = Objects.requireNonNull(webSocketTransport, "webSocketTransport");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tickTask = scheduler.scheduleAtFixedRate(this::tick, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    public void refresh(Collection<NetworkDefinition> networks) {
        if (closed.get()) return;
        List<NetworkDefinition> snapshot = networks == null ? List.of() : List.copyOf(networks);
        Map<String, Stamp> stamps = new LinkedHashMap<>();
        for (NetworkDefinition network : snapshot) {
            stamps.put(network.networkId(), new Stamp(network.revision(), network.runtime(), access.endpointStamp(network.networkId())));
        }
        Refresh refresh;
        synchronized (refreshLock) {
            if (closed.get()) return;
            refresh = new Refresh(requested.generation() + 1, snapshot, Map.copyOf(stamps));
            requested = refresh;
        }
        scheduler.execute(() -> reconcile(refresh));
    }

    public NetworkRuntimeSnapshot snapshot(String networkId) {
        String normalized = networkId == null ? "" : networkId.trim();
        synchronized (refreshLock) {
            return snapshots.computeIfAbsent(normalized, NetworkRuntimeSnapshot::disabled);
        }
    }

    public NetworkRuntimeSnapshot snapshot(NetworkDefinition network) {
        synchronized (refreshLock) {
            NetworkRuntimeSnapshot current = snapshot(network.networkId());
            Target target = targets.get(network.networkId());
            Session session = sessions.get(network.networkId());
            if (!network.runtime().enabled() || target != null && target.revision() == network.revision() && !current.connected()) return current;
            if (target != null && target.revision() == network.revision() && session != null && session.matches(target) && session.ready()) return current;
            long publication = publications.getOrDefault(network.networkId(), 0L);
            SnapshotView view = snapshotViews.get(network.networkId());
            if (view != null && view.revision() == network.revision() && view.publication() == publication) return view.snapshot();
            NetworkRuntimeSnapshot unavailable = current.connection(NetworkRuntimeConnectionState.UNAVAILABLE, "Hub Connection Is Not Ready For This Network Revision. " + current.message(), true);
            snapshotViews.put(network.networkId(), new SnapshotView(network.revision(), publication, unavailable));
            return unavailable;
        }
    }

    private record SnapshotView(long revision, long publication, NetworkRuntimeSnapshot snapshot) { }

    public ReSyncFrameTransport editorTransport(String networkId, String nodeId) {
        String network = networkId == null ? "" : networkId.trim();
        String node = nodeId == null ? "" : nodeId.trim();
        if (network.isBlank() || node.isBlank() || network.length() > 128 || node.length() > 128) {
            throw new IllegalArgumentException("Network And Server Are Required");
        }
        Session session = sessions.get(network);
        if (closed.get() || session == null || !session.current()) {
            throw new IllegalStateException("Network Editor Hub Is Unavailable");
        }
        return session.editorTransport(node);
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

    private void reconcile(Refresh refresh) {
        if (closed.get() || requested.generation() != refresh.generation()) {
            return;
        }
        List<NetworkDefinition> networks = refresh.networks();
        Map<String, Target> nextTargets = new LinkedHashMap<>();
        for (NetworkDefinition network : networks) {
            NetworkRuntimePolicy runtime = network.runtime();
            if (!runtime.enabled()) {
                publish(NetworkRuntimeSnapshot.disabled(network.networkId()));
                continue;
            }
            if (!access.ready(network.networkId(), runtime)) {
                publish(state(network.networkId(), NetworkRuntimeConnectionState.UNAVAILABLE, "Secure Runtime Transport Pending", false));
                continue;
            }
            NetworkMember proxy = network.proxyMember();
            String endpointStamp = refresh.stamps().get(network.networkId()).endpointStamp();
            Target previous = targets.get(network.networkId());
            if (previous != null && previous.revision() == network.revision() && Objects.equals(previous.endpointStamp(), endpointStamp) && previous.runtime().equals(runtime)) {
                nextTargets.put(network.networkId(), previous);
                continue;
            }
            List<NetworkRoute> routes = network.members().stream().filter(member -> !member.isProxy()).map(member -> new NetworkRoute(member.nodeId(), member.routeName(), member.address(), member.port())).toList();
            List<NetworkRoutingGroup> routingGroups = network.routingGroups().stream().map(group -> new NetworkRoutingGroup(group.id(), group.name(), NetworkRoutingStrategy.valueOf(group.strategy().name()), group.nodeIds(), group.weights(), group.fallbackGroupId(), group.forcedHosts(), group.permission())).toList();
            String maintenanceRoute = NetworkDesiredStatePlanner.fallbackRoutes(network).stream().findFirst().orElse("");
            Target target = new Target(network.networkId(), network.revision(), proxy == null ? "" : proxy.nodeId(), NetworkRuntimeIdentity.operatorNodeId(network.networkId()), runtime, endpointStamp, maintenanceRoute, routes, routingGroups);
            nextTargets.put(network.networkId(), target);
        }
        synchronized (refreshLock) {
            if (closed.get() || requested.generation() != refresh.generation()) return;
            targets = Map.copyOf(nextTargets);
        }
        List.copyOf(sessions.values()).forEach(session -> {
            String networkId = session.target().networkId();
            Target target = targets.get(networkId);
            if (target == null || !session.matches(target)) {
                session.close();
                synchronized (refreshLock) {
                    if (!session.preparing) sessions.remove(networkId, session);
                }
            }
        });
        synchronized (refreshLock) {
            snapshots.keySet().removeIf(networkId -> !requested.stamps().containsKey(networkId));
            publications.keySet().removeIf(networkId -> !requested.stamps().containsKey(networkId));
            snapshotViews.keySet().removeIf(networkId -> !requested.stamps().containsKey(networkId));
        }
        nextAttempts.keySet().removeIf(networkId -> !targets.containsKey(networkId));
        retryEnrollment.keySet().removeIf(networkId -> !targets.containsKey(networkId));
        nextTargets.values().forEach(this::ensureConnected);
    }

    private boolean targetCurrent(Target target) {
        Stamp stamp = requested.stamps().get(target.networkId());
        return !closed.get() && targets.get(target.networkId()) == target && stamp != null
            && stamp.revision() == target.revision() && stamp.runtime().equals(target.runtime())
            && Objects.equals(stamp.endpointStamp(), target.endpointStamp())
            && Objects.equals(access.endpointStamp(target.networkId()), target.endpointStamp());
    }

    private void tick() {
        if (closed.get()) {
            return;
        }
        targets.values().forEach(this::ensureConnected);
        List.copyOf(sessions.values()).forEach(Session::heartbeat);
    }

    private void ensureConnected(Target target) {
        Session retired;
        Session admitted = null;
        boolean timedOut = false;
        synchronized (refreshLock) {
            if (!targetCurrent(target) || clock.millis() < nextAttempts.getOrDefault(target.networkId(), 0L)) return;
            retired = sessions.get(target.networkId());
            if (retired != null) {
                if (retired.preparing && retired.closed()) return;
                if (retired.matches(target) && !retired.closed()) {
                    if (!retired.connectionTimedOut()) return;
                    timedOut = true;
                    nextAttempts.put(target.networkId(), clock.millis() + RETRY_DELAY_MILLIS);
                }
                if (!retired.preparing) sessions.remove(target.networkId(), retired);
            }
            if (!timedOut && (retired == null || !retired.preparing)) {
                admitted = new Session(target);
                sessions.put(target.networkId(), admitted);
            }
        }
        if (retired != null) retired.close();
        if (timedOut) {
            publish(state(target.networkId(), NetworkRuntimeConnectionState.RECONNECTING, "Runtime Connection Timed Out", false));
            return;
        }
        if (admitted == null) return;
        publish(state(target.networkId(), NetworkRuntimeConnectionState.CONNECTING, "Connecting ReSync Runtime", false));
        Session session = admitted;
        try {
            scheduler.execute(session::prepare);
        } catch (RuntimeException failure) {
            disconnected(session, rootMessage(failure));
        }
    }

    private NetworkRuntimeSnapshot state(String networkId, NetworkRuntimeConnectionState state, String message, boolean clearNodes) {
        return snapshots.getOrDefault(networkId, NetworkRuntimeSnapshot.disabled(networkId)).connection(state, message, clearNodes);
    }

    private void publish(NetworkRuntimeSnapshot snapshot) {
        synchronized (refreshLock) {
            snapshots.put(snapshot.networkId(), snapshot);
            publications.merge(snapshot.networkId(), 1L, Long::sum);
            snapshotViews.remove(snapshot.networkId());
        }
        for (Consumer<NetworkRuntimeSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void disconnected(Session session, String reason) {
        boolean current;
        synchronized (refreshLock) {
            if (sessions.get(session.target().networkId()) != session || session.closed() || closed.get()) return;
            current = targetCurrent(session.target());
            if (!session.preparing) sessions.remove(session.target().networkId(), session);
            if (current) {
                if ("Network Credential Rejected".equals(reason)) retryEnrollment.put(session.target().networkId(), true);
                nextAttempts.put(session.target().networkId(), clock.millis() + RETRY_DELAY_MILLIS);
            }
        }
        session.finishDisconnect();
        if (!current) return;
        publish(state(session.target().networkId(), NetworkRuntimeConnectionState.RECONNECTING, reason == null || reason.isBlank() ? "Reconnecting ReSync Runtime" : reason, false));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "Network Connection Failed" : current.getMessage();
    }

    private long deadline(long seconds) {
        return clock.millis() + seconds * 1_000L;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        tickTask.cancel();
        List.copyOf(sessions.values()).forEach(Session::close);
        sessions.clear();
        targets = Map.of();
        synchronized (refreshLock) { requested = new Refresh(requested.generation() + 1, List.of(), Map.of()); }
        nextAttempts.clear();
        retryEnrollment.clear();
    }

    private final class Session implements AutoCloseable {
        private final Target target;
        private volatile Endpoint endpoint;
        private final Object endpointLock = new Object();
        private volatile byte[] authentication;
        private volatile boolean preparing;
        private final Map<UUID, ReSyncNetworkFrameTransport> editors = new LinkedHashMap<>();
        private final Map<String, ReSyncNetworkFrameTransport> nodeEditors = new LinkedHashMap<>();
        private final Object editorLock = new Object();
        private final Deque<byte[]> inbound = new ArrayDeque<>();
        private final Object inboundLock = new Object();
        private boolean inboundRunning;
        private final BrowserSafeState.BooleanValue closed = new BrowserSafeState.BooleanValue();
        private final BrowserSafeState.BooleanValue opened = new BrowserSafeState.BooleanValue();
        private final BrowserSafeState.LongValue requestIds = new BrowserSafeState.LongValue();
        private final Map<String, Async<Void>> pending = BrowserSafeState.map();
        private final Map<String, Async<NetworkFrame>> responses = BrowserSafeState.map();
        private final Set<String> presenceDeltas = BrowserSafeState.set();
        private final long createdAt = clock.millis();
        private volatile BinaryWebSocket client;
        private volatile boolean authorized;
        private volatile boolean routesReady;
        private volatile long lastInbound;
        private volatile long lastHeartbeat;

        private Session(Target target) {
            this.target = target;
        }

        private void prepare() {
            String failure = null;
            try {
                synchronized (refreshLock) {
                    if (!current()) return;
                    preparing = true;
                }
                if (!current()) return;
                if (connectionTimedOut()) throw new IllegalStateException("Runtime Connection Timed Out");
                String credential = access.credential(target.networkId(), target.operatorNodeId());
                if (!current()) return;
                if (connectionTimedOut()) throw new IllegalStateException("Runtime Connection Timed Out");
                boolean enrolling = credential.isBlank() || Boolean.TRUE.equals(retryEnrollment.remove(target.networkId()));
                if (credential.isBlank()) {
                    credential = access.generateCredential();
                    if (!current()) return;
                    if (!access.saveCredential(target.networkId(), target.operatorNodeId(), credential, target.endpointStamp())) {
                        throw new IllegalStateException("Network Runtime Connection Changed");
                    }
                }
                if (!current()) return;
                String token = access.enrollmentToken(target.networkId(), target.operatorNodeId());
                if (!current()) return;
                if (connectionTimedOut()) throw new IllegalStateException("Runtime Connection Timed Out");
                authentication = NetworkAuthenticationCodec.encode(new NetworkAuthentication(target.networkId(),
                    target.operatorNodeId(), enrolling ? "" : credential, token, credential));
            } catch (RuntimeException exception) {
                failure = rootMessage(exception);
            } finally {
                synchronized (refreshLock) {
                    preparing = false;
                    if (!current()) sessions.remove(target.networkId(), this);
                }
            }
            if (failure != null) disconnected(this, failure);
            else if (current()) connect();
        }

        private void dispatch(Runnable action) {
            try { scheduler.execute(action); }
            catch (RuntimeException failure) { disconnected(this, "Runtime Scheduler Unavailable"); }
        }

        private void opened(BinaryWebSocket socket) {
            if (NetworkRuntimeMonitor.this.closed.get() || closed.get()) {
                socket.close(1000, "Runtime Closed");
            } else {
                dispatch(() -> open(socket));
            }
        }

        private void connect() {
            try {
                Async<Endpoint> attempt = access.open(target.networkId(), target.runtime());
                attempt.whenComplete((openedEndpoint, failure) -> {
                    if (failure != null) {
                        dispatch(() -> disconnected(this, rootMessage(failure)));
                    } else if (openedEndpoint == null) {
                        dispatch(() -> disconnected(this, "Network Runtime Endpoint Is Unavailable"));
                    } else {
                        try { scheduler.execute(() -> {
                            synchronized (endpointLock) {
                                if (!current()) {
                                    closeEndpoint(openedEndpoint);
                                    return;
                                }
                                endpoint = openedEndpoint;
                            }
                            connectSocket(openedEndpoint.url());
                        }); } catch (RuntimeException unavailable) {
                            closeEndpoint(openedEndpoint);
                            disconnected(this, "Runtime Scheduler Unavailable");
                        }
                    }
                });
            } catch (RuntimeException failure) {
                disconnected(this, rootMessage(failure));
            }
        }

        private void connectSocket(String url) {
            try {
                webSocketTransport.connectAsync(url, Map.of(), new BinaryWebSocketListener() {
                    @Override
                    public void onOpen(BinaryWebSocket socket) { opened(socket); }

                    @Override
                    public void onText(String message) { dispatch(() -> closeSocket(1003, "Binary Network Frames Required")); }

                    @Override
                    public void onBinary(byte[] message) { enqueue(message); }

                    @Override
                    public void onClose(int code, String reason) {
                        dispatch(() -> {
                            client = null;
                            authorized = false;
                            disconnected(Session.this, reason);
                        });
                    }

                    @Override
                    public void onError(Throwable error) { dispatch(() -> disconnected(Session.this, rootMessage(error))); }
                }).whenComplete((socket, error) -> {
                    if (error != null) dispatch(() -> disconnected(this, rootMessage(error)));
                    else if (socket != null) opened(socket);
                });
            } catch (RuntimeException exception) {
                disconnected(this, rootMessage(exception));
            }
        }

        private void open(BinaryWebSocket socket) {
            if (socket == null) return;
            boolean reject;
            boolean authenticate;
            synchronized (endpointLock) {
                reject = !current() || opened.get() && client != socket;
                authenticate = !reject && opened.compareAndSet(false, true);
                if (authenticate) client = socket;
            }
            if (reject) {
                socket.close(1000, "Connection Closed");
                return;
            }
            if (!authenticate) return;
            authorized = false;
            publish(state(target.networkId(), NetworkRuntimeConnectionState.CONNECTING, "Authenticating ReSync Runtime", false));
            NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(),
                target.operatorNodeId(), "session", deadline(10), Set.of());
            try { send(codec.encode(new NetworkFrame(context, NetworkChannels.CONTROL, NetworkFrameType.ENROLL, authentication))); }
            catch (RuntimeException failure) { disconnected(this, rootMessage(failure)); }
        }

        private void enqueue(byte[] message) {
            if (!current()) return;
            boolean start = false;
            boolean reject;
            synchronized (inboundLock) {
                reject = message == null || message.length > MAXIMUM_FRAME_BYTES || inbound.size() >= 64;
                if (!reject) {
                    inbound.addLast(message.clone());
                    start = !inboundRunning;
                    inboundRunning = true;
                }
            }
            if (reject) dispatch(() -> disconnected(this, "Runtime Frame Limit Exceeded"));
            else if (start) dispatch(this::drainInbound);
        }

        private void drainInbound() {
            for (int count = 0; count < 32; count++) {
                byte[] message;
                synchronized (inboundLock) {
                    if (!current()) inbound.clear();
                    message = inbound.pollFirst();
                    if (message == null) {
                        inboundRunning = false;
                        return;
                    }
                }
                try { if (current()) handle(codec.decode(message)); }
                catch (RuntimeException failure) { disconnected(this, rootMessage(failure)); }
            }
            dispatch(this::drainInbound);
        }

        private ReSyncFrameTransport editorTransport(String node) {
            synchronized (editorLock) {
                if (!current() || target.routes().stream().noneMatch(route -> route.nodeId().equals(node))) {
                    throw new IllegalStateException("Network Editor Server Is Unavailable");
                }
                nodeEditors.values().removeIf(editor -> editor.state() == ReSyncFrameTransport.State.CLOSED
                    || editor.state() == ReSyncFrameTransport.State.FAILED);
                ReSyncNetworkFrameTransport current = nodeEditors.get(node);
                if (current != null) return current;
                if (nodeEditors.size() >= 8) throw new IllegalStateException("Network Editor Session Is Full");
                ReSyncNetworkFrameTransport transport = new ReSyncNetworkFrameTransport(node, scheduler, clock, editor -> {
                    UUID id = editor.tunnelId();
                    Session session = sessions.get(target.networkId());
                    if (NetworkRuntimeMonitor.this.closed.get() || session == null || !session.current() || !session.authorized()) {
                        editor.fail(id, "Network Editor Hub Is Unavailable");
                    } else if (session.target.routes().stream().noneMatch(route -> route.nodeId().equals(node))) {
                        editor.fail(id, "Network Editor Server Is Not Configured");
                    } else {
                        session.openEditor(editor);
                    }
                });
                nodeEditors.put(node, transport);
                return transport;
            }
        }

        private void openEditor(ReSyncNetworkFrameTransport transport) {
            UUID id = transport.tunnelId();
            boolean admitted;
            synchronized (editorLock) {
                ReSyncNetworkFrameTransport resident = nodeEditors.get(transport.nodeId());
                admitted = current() && authorized() && editors.size() < 8 && !editors.containsKey(id)
                    && (resident == null && nodeEditors.size() < 8 || resident == transport)
                    && transport.bind(id, new ReSyncNetworkFrameTransport.Channel() {
                        @Override
                        public Async<Void> send(NetworkFrameType type, byte[] payload) {
                            if (!current()) return Async.failed(new IllegalStateException("Network Editor Session Changed"));
                            NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(),
                                target.operatorNodeId(), id.toString(), deadline(15), Set.of("editors.open"));
                            BinaryWebSocket socket = client;
                            if (socket == null || !socket.isOpen()) return Async.failed(new IllegalStateException("Network Editor Hub Is Unavailable"));
                            return socket.sendBinary(codec.encode(new NetworkFrame(context, NetworkChannels.EDITOR, type, payload)));
                        }

                        @Override
                        public boolean current() { return Session.this.current() && authorized(); }

                        @Override
                        public void remove(UUID tunnelId, ReSyncNetworkFrameTransport editor) {
                            synchronized (editorLock) {
                                editors.remove(tunnelId, editor);
                                nodeEditors.remove(editor.nodeId(), editor);
                            }
                        }
                    });
                if (admitted) {
                    editors.put(id, transport);
                    nodeEditors.put(transport.nodeId(), transport);
                }
            }
            if (!admitted) {
                transport.fail(id, "Network Editor Session Is Unavailable Or Full");
                return;
            }
            try {
                NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(),
                    target.operatorNodeId(), id.toString(), deadline(15), Set.of("editors.open"));
                byte[] payload = NetworkEditorCodec.encodeOpen(new NetworkEditorOpen(id, transport.nodeId()));
                BinaryWebSocket socket = client;
                if (!current() || !authorized() || socket == null) throw new IllegalStateException("Network Editor Hub Is Unavailable");
                socket.sendBinary(codec.encode(new NetworkFrame(context, NetworkChannels.EDITOR, NetworkFrameType.EDITOR_OPEN, payload)))
                    .whenComplete((unused, failure) -> {
                        if (failure != null && current()) transport.fail(id, rootMessage(failure));
                    });
            } catch (RuntimeException failure) {
                transport.fail(id, rootMessage(failure));
            }
        }

        private boolean editorFrame(NetworkFrame frame) {
            if (!frame.channel().equals(NetworkChannels.EDITOR)) return false;
            UUID id;
            NetworkEditorChunk chunk = null;
            NetworkEditorClose close = null;
            if (frame.type() == NetworkFrameType.EDITOR_OPENED) id = NetworkEditorCodec.decodeOpened(frame.payload());
            else if (frame.type() == NetworkFrameType.EDITOR_DATA) {
                chunk = NetworkEditorCodec.decodeChunk(frame.payload());
                id = chunk.tunnelId();
            } else if (frame.type() == NetworkFrameType.EDITOR_CLOSE) {
                close = NetworkEditorCodec.decodeClose(frame.payload());
                id = close.tunnelId();
            } else if (frame.type() == NetworkFrameType.ERROR) {
                try { id = UUID.fromString(frame.context().requestId()); }
                catch (IllegalArgumentException ignored) { return false; }
            } else return false;
            ReSyncNetworkFrameTransport transport;
            synchronized (editorLock) { transport = editors.get(id); }
            if (transport == null || !current() || !authorized()) return true;
            if (chunk != null) transport.receive(chunk);
            else if (close != null) transport.remoteClose(close);
            else if (frame.type() == NetworkFrameType.ERROR) transport.fail(id, new String(frame.payload(), StandardCharsets.UTF_8));
            else transport.opened(id);
            return true;
        }

        private boolean matches(Target candidate) {
            return target.networkId().equals(candidate.networkId()) && target.revision() == candidate.revision() && target.runtime().equals(candidate.runtime()) && Objects.equals(target.endpointStamp(), candidate.endpointStamp());
        }

        private boolean closed() {
            return closed.get();
        }

        private boolean authorized() {
            BinaryWebSocket socket = client;
            return authorized && current() && socket != null && socket.isOpen();
        }

        private boolean current() {
            return !closed.get() && sessions.get(target.networkId()) == this && targetCurrent(target);
        }

        private boolean ready() {
            return current() && authorized() && routesReady && !connectionTimedOut();
        }

        private boolean connectionTimedOut() {
            return !routesReady && clock.millis() - createdAt >= CONNECT_TIMEOUT_MILLIS
                || routesReady && clock.millis() - lastInbound >= CONNECT_TIMEOUT_MILLIS;
        }

        private Target target() {
            return target;
        }

        private void handle(NetworkFrame frame) {
            if (!current()) return;
            if (!frame.context().networkId().equals(target.networkId()) || !frame.context().nodeId().equals(target.hubNodeId())) {
                throw new SecurityException("Network Hub Identity Does Not Match");
            }
            lastInbound = clock.millis();
            if (frame.type() == NetworkFrameType.ENROLL_ACK) {
                String credential = new String(frame.payload(), StandardCharsets.UTF_8).trim();
                if (credential.isBlank() || credential.length() > 128) throw new IllegalArgumentException("Network Credential Is Invalid");
                if (!access.saveCredential(target.networkId(), target.operatorNodeId(), credential, target.endpointStamp())) {
                    throw new IllegalStateException("Network Runtime Connection Changed");
                }
                authorize();
                return;
            }
            if (frame.type() == NetworkFrameType.RESPONSE && frame.context().requestId().equals("session")) {
                authorize();
                return;
            }
            if (editorFrame(frame)) return;
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
                routesReady = true;
                publish(state(target.networkId(), NetworkRuntimeConnectionState.CONNECTED, "Runtime Routes Ready", false));
                return;
            }
            if ((frame.type() == NetworkFrameType.PRESENCE_SNAPSHOT || frame.type() == NetworkFrameType.PRESENCE_DELTA) && frame.channel().equals(NetworkChannels.PRESENCE)) {
                NetworkNodePresence presence = NetworkNodePresenceCodec.decode(target.networkId(), frame.payload());
                NetworkRuntimeNodePresence runtimePresence = new NetworkRuntimeNodePresence(presence.networkId(), presence.nodeId(), NetworkRuntimeNodeStatus.valueOf(presence.status().name()), presence.players(), presence.capacity(), presence.tps(), presence.mspt(), presence.heapUsed(), presence.heapMaximum(), presence.observedAt());
                if (frame.type() == NetworkFrameType.PRESENCE_SNAPSHOT && presenceDeltas.contains(presence.nodeId())) return;
                if (frame.type() == NetworkFrameType.PRESENCE_DELTA) presenceDeltas.add(presence.nodeId());
                NetworkRuntimeSnapshot current = snapshot(target.networkId());
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
                if (frame.context().requestId().equals("routes-" + target.revision()) || frame.context().requestId().equals("session")) {
                    disconnected(this, message);
                    return;
                }
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
            if (!current()) return;
            authorized = true;
            lastHeartbeat = 0;
            routesReady = false;
            presenceDeltas.clear();
            publish(state(target.networkId(), NetworkRuntimeConnectionState.CONNECTING, "Hub Authenticated. Checking Network Routes", true));
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
            if (!current() || !authorized || closed.get() || socket == null || !socket.isOpen() || now - lastHeartbeat < HEARTBEAT_INTERVAL_MILLIS) {
                return;
            }
            lastHeartbeat = now;
            String requestId = target.operatorNodeId() + "-" + requestIds.incrementAndGet();
            NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, target.networkId(), target.operatorNodeId(), requestId, deadline(10), Set.of("node.heartbeat", "presence.read"));
            send(codec.encode(new NetworkFrame(context, NetworkChannels.CONTROL, NetworkFrameType.HEARTBEAT, new byte[0])));
        }

        private void closeEndpoint(Endpoint value) {
            if (value == null) return;
            try { value.close(); }
            catch (RuntimeException ignored) { }
        }

        private void closeEndpoint() {
            Endpoint previous;
            synchronized (endpointLock) {
                previous = endpoint;
                endpoint = null;
            }
            closeEndpoint(previous);
        }

        private void finishDisconnect() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            authorized = false;
            failPending("ReSync Runtime Disconnected");
            closeSocket(1000, "Runtime Disconnected");
            closeEndpoint();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            authorized = false;
            failPending("ReSync Runtime Closed");
            closeSocket(1000, "Runtime Closed");
            closeEndpoint();
        }

        private void failPending(String message) {
            Map<ReSyncNetworkFrameTransport, UUID> retired = new LinkedHashMap<>();
            synchronized (editorLock) {
                for (ReSyncNetworkFrameTransport editor : editors.values()) retired.put(editor, editor.tunnelId());
                for (ReSyncNetworkFrameTransport editor : nodeEditors.values()) retired.put(editor, editor.tunnelId());
                editors.clear();
                nodeEditors.clear();
            }
            retired.forEach((editor, id) -> editor.fail(id, message));
            synchronized (inboundLock) { inbound.clear(); }
            pending.values().forEach(request -> request.completeExceptionally(new IllegalStateException(message)));
            pending.clear();
            responses.values().forEach(request -> request.completeExceptionally(new IllegalStateException(message)));
            responses.clear();
        }

        private void send(byte[] frame) {
            BinaryWebSocket socket = client;
            if (!current() || socket == null || !socket.isOpen()) {
                throw new IllegalStateException("ReSync Runtime WebSocket Is Not Open");
            }
            socket.sendBinary(frame).whenComplete((unused, error) -> {
                if (error != null && !closed.get()) {
                    dispatch(() -> disconnected(this, rootMessage(error)));
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

    private record Stamp(long revision, NetworkRuntimePolicy runtime, String endpointStamp) { }

    private record Refresh(long generation, List<NetworkDefinition> networks, Map<String, Stamp> stamps) { }

    private record Target(String networkId, long revision, String hubNodeId, String operatorNodeId, NetworkRuntimePolicy runtime, String endpointStamp, String maintenanceRoute, List<NetworkRoute> routes, List<NetworkRoutingGroup> routingGroups, byte[] routePayload) {
        private Target(String networkId, long revision, String hubNodeId, String operatorNodeId, NetworkRuntimePolicy runtime, String endpointStamp, String maintenanceRoute, List<NetworkRoute> routes, List<NetworkRoutingGroup> routingGroups) {
            this(networkId, revision, hubNodeId, operatorNodeId, runtime, endpointStamp, maintenanceRoute, routes, routingGroups,
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

}
