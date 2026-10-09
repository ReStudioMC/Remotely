package redxax.oxy.remotely.servers;

import restudio.rebase.instance.Instance;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rebase.reproxy.ReProxyModels.Binding;
import restudio.rebase.reproxy.ReProxyModels.Endpoint;
import restudio.rebase.reproxy.ReProxyModels.Node;
import restudio.rebase.reproxy.ReProxyModels.RuntimeLimits;
import restudio.rebase.reproxy.ReProxyModels.Ticket;
import restudio.reproxy.connector.ReProxyConnector;
import restudio.rescreen.platform.Async;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToIntFunction;

public final class JvmReProxyConnectorCapability implements ReProxyConnectorCapability {
    public static final JvmReProxyConnectorCapability INSTANCE = new JvmReProxyConnectorCapability();
    private static volatile ToIntFunction<Instance> gamePort = instance -> {
        if (instance.getNetworkId() != null && !instance.getNetworkId().isBlank()) {
            throw new IllegalStateException("Network Port Access Is Unavailable");
        }
        return instance.getPort();
    };

    public static void configureGamePort(ToIntFunction<Instance> resolver) {
        gamePort = Objects.requireNonNull(resolver, "Network Port Access Is Required");
    }
    @Override
    public Availability availability(Binding binding) {
        return binding != null && "LOCAL".equals(binding.kind()) && binding.instanceId() != null && !binding.instanceId().isBlank()
                ? new Availability(true, "") : new Availability(false, "This Server Requires Its Hosted Or Agent Connector");
    }

    public static Server server(Instance instance) {
        return instance == null ? null : new NativeServer(instance, instance.getInstanceId());
    }

    @Override
    public Server server(Object target) {
        if (!(target instanceof Instance instance)) throw new UnsupportedOperationException("Choose A Local Server");
        return server(instance);
    }

    private record NativeServer(Instance instance, String id) implements Server {
        @Override
        public String name() { return instance.getName(); }
        @Override
        public int port() { return gamePort.applyAsInt(instance); }
        @Override
        public boolean local() { return instance.isServer() && (instance.getBackendConfig() == null || "LOCAL".equalsIgnoreCase(instance.getBackendConfig().type)); }
        @Override
        public String setting(String key, String fallback) { return instance.getSettings().getProperty(key, fallback); }
        @Override
        public void putSetting(String key, String value) { instance.getSettings().setProperty(key, value); }
        @Override
        public Async<Void> save() { return JvmAsyncBridge.fromFuture(instance.save()); }
    }

    @Override
    public Handle open(Ticket ticket, Binding binding, List<Endpoint> approved, Listener listener) {
        Availability availability = availability(binding);
        if (!availability.available()) throw new UnsupportedOperationException(availability.reason());
        NativeHandle handle = new NativeHandle(binding, approved);
        List<ReProxyConnector.Target> targets = ticket.endpoints().stream().filter(endpoint -> endpoint.enabled()).map(endpoint ->
                new ReProxyConnector.Target(UUID.fromString(endpoint.id()), ReProxyConnector.Protocol.valueOf(endpoint.protocol()), endpoint.targetHost(), endpoint.targetPort())).toList();
        ReProxyConnector.Session session = new ReProxyConnector.Session(UUID.fromString(ticket.connectionId()), UUID.fromString(ticket.sessionId()),
                Long.parseUnsignedLong(ticket.generation()), ticket.token(), uri(ticket.node()), targets, limits(ticket.limits()));
        handle.connector = new ReProxyConnector(session, handle::allows, new ReProxyConnector.Listener() {
            @Override
            public void onAuthenticated() { listener.authenticated(); }
            @Override
            public void onReady(long revision, List<ReProxyConnector.Target> current) { listener.activated(Long.toUnsignedString(revision)); }
            @Override
            public void onConfiguration(long revision, List<ReProxyConnector.Target> current) { listener.changed(Long.toUnsignedString(revision)); }
            @Override
            public void onError(Throwable error) { listener.failed(error); }
            @Override
            public void onClosed() { listener.closed(); }
        });
        handle.ready = JvmAsyncBridge.fromFuture(handle.connector.connect());
        return handle;
    }

    private static URI uri(Node node) {
        if (node == null || !"wss".equalsIgnoreCase(node.tunnelScheme())) throw new IllegalArgumentException("A Secure Relay Address Is Required");
        try {
            return new URI("wss", null, node.tunnelHost(), node.tunnelPort(), "/reproxy/v2/tunnel", null, null);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Relay Address Is Invalid", error);
        }
    }

    private static ReProxyConnector.Limits limits(RuntimeLimits runtime) {
        if (runtime == null) throw new IllegalArgumentException("Connector Limits Are Missing");
        ReProxyConnector.Limits defaults = ReProxyConnector.Limits.defaults();
        int queue = runtime.queuedBytes();
        return new ReProxyConnector.Limits(runtime.maxStreams(), runtime.maxUdpFlows(), runtime.maxEndpoints(), defaults.maxFrameBytes(), defaults.maxDatagramBytes(),
                Math.min(defaults.maxFlowQueueBytes(), queue), defaults.maxFlowQueuePackets(), Math.min(defaults.maxSendQueueBytes(), queue), defaults.maxSendQueueFrames(),
                defaults.connectTimeout(), defaults.authenticationTimeout(), defaults.writeTimeout(), Duration.ofMillis(runtime.datagramAgeMillis()),
                runtime.admissionsPerSecond(), runtime.packetsPerSecond(), Long.parseLong(runtime.bytesPerSecond()), runtime.pendingHandshakes());
    }

    private static final class NativeHandle implements Handle {
        private final Binding binding;
        private ReProxyConnector connector;
        private Async<Void> ready;

        NativeHandle(Binding binding, List<Endpoint> approved) {
            this.binding = binding;
            updateGrants(approved);
        }

        private boolean allows(ReProxyConnector.Target target) {
            return "127.0.0.1".equals(target.host()) && target.port() > 0 && target.port() <= 65535;
        }

        @Override
        public Async<Void> ready() { return ready; }

        @Override
        public void updateGrants(List<Endpoint> approved) {
            for (Endpoint endpoint : approved) {
                if (endpoint.target() == null || !binding.id().equals(endpoint.target().bindingId()) || endpoint.target().port() < 1 || endpoint.target().port() > 65535) {
                    throw new IllegalArgumentException("Endpoint Target Is Outside This Server Binding");
                }
            }
        }

        @Override
        public void close() {
            if (connector != null) connector.close();
        }
    }
}
