package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkEditorConnection;
import redxax.oxy.remotely.network.NetworkManager;
import redxax.oxy.remotely.network.NetworkRuntimeMonitor;
import redxax.oxy.remotely.network.NetworkRuntimeNodeStatus;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.websocket.WebSocketTransport;
import restudio.resync.network.NetworkNodeStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class BrowserNetworkManager implements NetworkManager<Object, Object> {
    private final BrowserNetworkRuntimeAccess access = new BrowserNetworkRuntimeAccess();
    private final NetworkRuntimeMonitor monitor;
    private final Map<String, NetworkDefinition> networks = new LinkedHashMap<>();
    private final Runnable authorityListener = this::checkAuthority;
    private String authority = BrowserLaunchSession.authorityKey();
    private boolean authenticated = BrowserLaunchSession.authenticated();
    private boolean closed;

    public BrowserNetworkManager(WebSocketTransport sockets, TaskScheduler scheduler, Clock clock) {
        monitor = new NetworkRuntimeMonitor(access, sockets, scheduler, clock);
        BrowserLaunchSession.addAuthStateListener(authorityListener);
        BrowserLaunchSession.addSessionExpiryListener(authorityListener);
    }

    public void admit(NetworkDefinition network) {
        checkAuthority();
        if (!available() || network == null) return;
        NetworkDefinition current = networks.get(network.networkId());
        if (current != null && current.revision() >= network.revision()) return;
        networks.put(network.networkId(), network);
        refresh();
    }

    public boolean configured(String networkId) {
        checkAuthority();
        return available() && access.configured(networkId);
    }

    @Override
    public boolean available() {
        return !closed && BrowserLaunchSession.authenticated();
    }

    @Override
    public List<NetworkDefinition> getNetworks() {
        checkAuthority();
        return List.copyOf(networks.values());
    }

    @Override
    public Optional<NetworkDefinition> getNetwork(String networkId) {
        checkAuthority();
        return Optional.ofNullable(networks.get(networkId));
    }

    @Override
    public boolean editorAvailable() {
        checkAuthority();
        return available();
    }

    @Override
    public boolean editorConfigurable() {
        return available();
    }

    @Override
    public Async<Void> configureEditor(String networkId, NetworkEditorConnection connection) {
        try {
            checkAuthority();
            if (!available()) throw new IllegalStateException("Sign In To Connect To A Network");
            if (!networks.containsKey(networkId)) throw new IllegalStateException("Reload The Network Before Connecting");
            access.configure(networkId, connection);
            refresh();
            return Async.completed(null);
        } catch (RuntimeException failure) {
            return Async.failed(failure);
        }
    }

    @Override
    public ReSyncFrameTransport editorTransport(String networkId, String nodeId) {
        if (!configured(networkId)) throw new IllegalStateException("Configure The Network Connection First");
        return monitor.editorTransport(networkId, nodeId);
    }

    @Override
    public NetworkRuntimeSnapshot getRuntimeSnapshot(String networkId) {
        checkAuthority();
        NetworkDefinition network = networks.get(networkId);
        return network == null ? NetworkRuntimeSnapshot.disabled(networkId) : monitor.snapshot(network);
    }

    @Override
    public void addRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        monitor.addListener(listener);
    }

    @Override
    public void removeRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        monitor.removeListener(listener);
    }

    @Override
    public Async<Void> setRuntimeNodeMode(String networkId, String nodeId, NetworkRuntimeNodeStatus status) {
        if (!configured(networkId)) return Async.failed(new IllegalStateException("Configure The Network Connection First"));
        return monitor.setNodeMode(networkId, nodeId, NetworkNodeStatus.valueOf(status.name()));
    }

    @Override
    public Async<Void> executeRuntimeProxyCommand(String networkId, String command) {
        if (!configured(networkId)) return Async.failed(new IllegalStateException("Configure The Network Connection First"));
        return monitor.executeProxyCommand(networkId, command);
    }

    @Override
    public Async<Void> broadcastRuntimeMessage(String networkId, String message) {
        if (!configured(networkId)) return Async.failed(new IllegalStateException("Configure The Network Connection First"));
        return monitor.broadcast(networkId, message);
    }

    private void refresh() {
        monitor.refresh(networks.values().stream().filter(network -> access.configured(network.networkId())).toList());
    }

    private void checkAuthority() {
        String current = BrowserLaunchSession.authorityKey();
        boolean currentAuthenticated = BrowserLaunchSession.authenticated();
        if (!closed && (authenticated != currentAuthenticated || !authority.equals(current))) {
            authority = current;
            authenticated = currentAuthenticated;
            access.clear();
            networks.clear();
            monitor.refresh(List.of());
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        BrowserLaunchSession.removeAuthStateListener(authorityListener);
        BrowserLaunchSession.removeSessionExpiryListener(authorityListener);
        access.clear();
        networks.clear();
        monitor.close();
    }
}
