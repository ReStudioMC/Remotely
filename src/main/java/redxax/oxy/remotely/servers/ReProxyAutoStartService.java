package redxax.oxy.remotely.servers;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.restudio.AuthStateListener;
import restudio.rebase.restudio.ReStudio;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ReProxyAutoStartService implements AuthStateListener, AutoCloseable {
    private final InstanceManager instances;
    private final Map<Instance, Consumer<InstanceState>> listeners = BrowserSafeState.map();
    private final Set<String> starting = BrowserSafeState.set();
    private final Runnable changed = this::refresh;
    private boolean active;

    public ReProxyAutoStartService(InstanceManager instances) {
        this.instances = instances;
    }

    public synchronized void start() {
        if (active) return;
        active = true;
        ReProxyManager.configure(ReStudio.getInstance().getApi().reProxy(), JvmReProxyConnectorCapability.INSTANCE);
        instances.addChangeListener(changed);
        ReStudio.getInstance().addListener(this);
        refresh();
    }

    private synchronized void refresh() {
        if (!active) return;
        Set<Instance> current = new HashSet<>();
        instances.getAllInstances().stream().filter(ReProxyAutoStartService::isLocalServer).forEach(current::add);
        listeners.entrySet().removeIf(entry -> {
            if (current.contains(entry.getKey())) return false;
            entry.getKey().removeStateListener(entry.getValue());
            starting.remove(entry.getKey().getInstanceId());
            ReProxyManager.stopQuietly(JvmReProxyConnectorCapability.server(entry.getKey()), null);
            return true;
        });
        for (Instance instance : current) {
            if (!listeners.containsKey(instance)) {
                Consumer<InstanceState> listener = state -> stateChanged(instance, state);
                instance.addStateListener(listener);
                listeners.put(instance, listener);
            }
            stateChanged(instance, instance.getState());
        }
    }

    private synchronized void stateChanged(Instance instance, InstanceState state) {
        if (!active) return;
        String id = instance.getInstanceId();
        if (state != InstanceState.RUNNING) {
            starting.remove(id);
            ReProxyManager.stopQuietly(JvmReProxyConnectorCapability.server(instance), null);
            return;
        }
        boolean autoStart = Boolean.parseBoolean(instance.getSettings().getProperty("reproxy.autoStart", "false"));
        if (!isLocalServer(instance) || !autoStart || !ReStudio.getInstance().isAuthenticated() || ReProxyManager.isForwarded(JvmReProxyConnectorCapability.server(instance))) return;
        if (starting.add(id)) ReProxyManager.startQuietly(JvmReProxyConnectorCapability.server(instance), () -> starting.remove(id));
    }

    private static boolean isLocalServer(Instance instance) {
        return instance != null && instance.isServer() && (instance.getBackendConfig() == null || "LOCAL".equalsIgnoreCase(instance.getBackendConfig().type));
    }

    @Override
    public void onLogin(String email) { refresh(); }

    @Override
    public void onLogout() {
        starting.clear();
        ReProxyManager.cancelAll();
    }

    @Override
    public void onSessionExpired() { onLogout(); }

    @Override
    public synchronized void close() {
        if (!active) return;
        active = false;
        instances.removeChangeListener(changed);
        ReStudio.getInstance().removeListener(this);
        listeners.forEach(Instance::removeStateListener);
        listeners.clear();
        starting.clear();
        ReProxyManager.cancelAll();
    }
}
