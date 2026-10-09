package redxax.oxy.remotely.servers.reproxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import redxax.oxy.remotely.RemotelyCapabilityException;
import redxax.oxy.remotely.servers.ReProxyTarget;
import restudio.rebase.restudio.api.ReStudioApiException;
import redxax.oxy.remotely.servers.reproxy.PluginForwarding.Document;
import redxax.oxy.remotely.servers.reproxy.PluginForwarding.Port;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.util.JsonTreeParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class HostedPluginAccess implements PluginForwarding.Access {
    @FunctionalInterface
    public interface Transport {
        Async<String> request(String method, String path, String body);
    }

    private final String serverId;
    private final String name;
    private volatile int port;
    private final String root;
    private final Transport transport;
    private final BooleanSupplier current;
    private volatile String serverStamp = "";
    private volatile NetworkStamp network;
    private NetworkStamp pinnedNetwork;
    private int networkChanges;
    private volatile boolean secretAuthority;
    private record NetworkStamp(String id, long revision) { }

    public HostedPluginAccess(String serverId, String name, int port, Transport transport, BooleanSupplier current) {
        this.serverId = serverId;
        this.name = name;
        this.port = port;
        this.transport = transport;
        this.current = current;
        root = "/servers/" + encode(serverId) + "/plugin-forwarding";
    }

    @Override
    public String serverKey() { return "reactor:" + serverId; }

    @Override
    public String name() { return name; }

    @Override
    public int serverPort() { return port; }

    @Override
    public boolean local() { return false; }

    @Override
    public String authorityStamp() { return serverStamp; }

    @Override
    public synchronized void admitNetwork(String id, long revision) {
        network = id == null ? null : new NetworkStamp(id, revision);
    }

    @Override
    public synchronized AutoCloseable networkChange(String id, long revision) {
        NetworkStamp expected = id == null ? null : new NetworkStamp(id, revision);
        if (!Objects.equals(expected, network) || networkChanges > 0 && !Objects.equals(expected, pinnedNetwork)) {
            throw new IllegalStateException("Network Membership Changed. Retry The Plugin Setup");
        }
        pinnedNetwork = expected;
        networkChanges++;
        return new AutoCloseable() {
            private boolean closed;
            @Override
            public void close() {
                synchronized (HostedPluginAccess.this) {
                    if (closed) return;
                    closed = true;
                    networkChanges--;
                    if (networkChanges == 0) pinnedNetwork = null;
                }
            }
        };
    }

    @Override
    public synchronized AutoCloseable change() {
        NetworkStamp stamp = network;
        return networkChange(stamp == null ? null : stamp.id(), stamp == null ? 0 : stamp.revision());
    }

    @Override
    public Async<Void> secretFiles() {
        return running().thenAccept(ignored -> {
            if (!secretAuthority) throw new IllegalStateException("Plasmo Voice Uses An Environment Secret Override. Configure Its Shared Secret Through Server Startup Settings");
        });
    }

    @Override
    public Async<Boolean> running() {
        return request("GET", "/target", null).thenApply(value -> {
            String stamp = text(value, "serverStamp");
            if (stamp.isBlank()) throw new IllegalStateException("Server Identity Is Unavailable");
            if (!serverStamp.isBlank() && !serverStamp.equals(stamp)) throw new IllegalStateException("Server Identity Changed; Reopen Server Details");
            serverStamp = stamp;
            int observedPort = value.get("serverPort").getAsInt();
            if (observedPort < 1 || observedPort > 65535) throw new IllegalStateException("Server Port Is Unavailable");
            port = observedPort;
            secretAuthority = value.has("forwardingSecretOverride") && !value.get("forwardingSecretOverride").getAsBoolean();
            return value.get("running").getAsBoolean();
        });
    }

    @Override
    public Async<Document> observe(String path) {
        return observe(integration(path), path);
    }

    @Override
    public Async<Document> observe(ReProxyIntegrations.Integration integration, String path) {
        return request("GET", "/documents?integration=" + encode(owner(integration, path)) + "&path=" + encode(path), null)
                .thenApply(HostedPluginAccess::document);
    }

    @Override
    public AutoCloseable watch(String path, Runnable changed) {
        return watch(integration(path), path, changed);
    }

    @Override
    public AutoCloseable watch(ReProxyIntegrations.Integration integration, String path, Runnable changed) {
        owner(integration, path);
        String relative = ReProxyIntegrations.relativePath(path);
        if (!integration.paths().contains(relative)) throw new IllegalArgumentException("Plugin File Is Unavailable");
        return new FileWatch(integration, relative, Objects.requireNonNull(changed, "changed"));
    }

    @Override
    public Async<Document> mutate(String path, Document expected, String content, String operationId) {
        return mutate(integration(path), path, expected, content, operationId);
    }

    @Override
    public Async<Document> mutate(ReProxyIntegrations.Integration integration, String path, Document expected, String content, String operationId) {
        JsonObject body = new JsonObject();
        body.addProperty("integration", owner(integration, path));
        body.addProperty("path", path);
        body.addProperty("expectedExists", expected.exists());
        if (expected.exists()) body.addProperty("expectedSha256", expected.stamp()); else body.add("expectedSha256", JsonNull.INSTANCE);
        if (content == null) body.add("content", JsonNull.INSTANCE); else body.addProperty("content", content);
        body.addProperty("operationId", operationId);
        body.addProperty("expectedServerStamp", requiredStamp());
        network(body);
        return request("POST", "/documents", body.toString()).thenApply(HostedPluginAccess::document);
    }

    @Override
    public Async<Void> reload(ReProxyIntegrations.Integration integration, String path, Document expected) {
        JsonObject body = new JsonObject();
        body.addProperty("integration", integration.id());
        body.addProperty("path", path);
        body.addProperty("expectedSha256", expected.stamp());
        body.addProperty("expectedServerStamp", requiredStamp());
        network(body);
        body.add("commands", ReProxyIntegrations.definition(integration).getAsJsonArray("reloadCommands"));
        return request("POST", "/reload", body.toString()).thenApply(ignored -> null);
    }

    @Override
    public Async<Port> reserve(ReProxyIntegrations.Integration integration, int desiredPort, String operationId) {
        return reserve(integration, desiredPort, operationId, false);
    }

    @Override
    public Async<Port> reserveExact(ReProxyIntegrations.Integration integration, int desiredPort, String operationId) {
        return reserve(integration, desiredPort, operationId, true);
    }

    private Async<Port> reserve(ReProxyIntegrations.Integration integration, int desiredPort, String operationId, boolean exactPort) {
        JsonObject body = new JsonObject();
        body.addProperty("integration", integration.id());
        body.addProperty("desiredPort", desiredPort);
        body.addProperty("exactPort", exactPort);
        body.addProperty("operationId", operationId);
        body.addProperty("expectedServerStamp", requiredStamp());
        network(body);
        return request("POST", "/ports", body.toString()).thenApply(HostedPluginAccess::port).exceptionallyCompose(failure -> {
            if (rejected(failure)) return Async.failed(new PluginForwarding.ReservationRejected(failure.getMessage()));
            return Async.failed(failure);
        });
    }

    private static boolean rejected(Throwable failure) {
        return failure instanceof ReStudioApiException nativeFailure && nativeFailure.getStatus() == 409 && "hosting_precondition".equals(nativeFailure.getCode())
                || failure instanceof RemotelyCapabilityException browserFailure && browserFailure.status() == 409 && "hosting_precondition".equals(browserFailure.code());
    }

    @Override
    public Async<Void> release(ReProxyIntegrations.Integration integration, Port port) {
        JsonObject body = new JsonObject();
        body.addProperty("integration", integration.id());
        body.addProperty("allocationId", port.id());
        body.addProperty("operationId", port.operationId());
        body.addProperty("expectedServerStamp", requiredStamp());
        network(body);
        return request("POST", "/ports/release", body.toString()).thenApply(ignored -> null);
    }

    @Override
    public ReProxyTarget localTarget() { return null; }

    private String requiredStamp() {
        if (serverStamp.isBlank()) throw new IllegalStateException("Check The Server Before Changing Plugin Connections");
        return serverStamp;
    }

    private synchronized void network(JsonObject body) {
        if (networkChanges > 0 && !Objects.equals(pinnedNetwork, network)) throw new IllegalStateException("Network Membership Changed. Retry The Plugin Setup");
        NetworkStamp stamp = networkChanges > 0 ? pinnedNetwork : network;
        if (stamp != null) {
            body.addProperty("expectedNetworkId", stamp.id());
            body.addProperty("expectedNetworkRevision", Long.toString(stamp.revision()));
        }
    }

    private Async<JsonObject> request(String method, String path, String body) {
        if (!current.getAsBoolean()) return Async.failed(new IllegalStateException("ReStudio Account Changed"));
        return transport.request(method, root + path, body).thenApply(value -> {
            if (!current.getAsBoolean()) throw new IllegalStateException("ReStudio Account Changed");
            return JsonTreeParser.parse(value).getAsJsonObject();
        });
    }

    private static Document document(JsonObject value) {
        boolean exists = value.get("exists").getAsBoolean();
        return new Document(exists, text(value, "content"), text(value, "sha256"));
    }

    private static Port port(JsonObject value) {
        return new Port(text(value, "host"), value.get("port").getAsInt(), text(value, "allocationId"),
                value.get("created").getAsBoolean(), text(value, "operationId"));
    }

    private static ReProxyIntegrations.Integration integration(String path) {
        String normalized = ReProxyIntegrations.relativePath(path);
        for (ReProxyIntegrations.Integration value : ReProxyIntegrations.catalog()) {
            if (value.paths().contains(normalized) || normalized.equals(".remotely/plugin-forwarding-" + value.id() + ".json")) return value;
        }
        throw new IllegalArgumentException("Plugin File Is Unavailable");
    }

    private static String owner(ReProxyIntegrations.Integration integration, String path) {
        String normalized = ReProxyIntegrations.relativePath(path);
        boolean secret = "plasmovoice".equals(integration.id()) && integration.paths().stream().map(value ->
                value.substring(0, value.lastIndexOf('/') + 1) + "forwarding-secret").anyMatch(normalized::equals);
        if (!integration.paths().contains(normalized) && !secret && !normalized.equals(".remotely/plugin-forwarding-" + integration.id() + ".json")) {
            throw new IllegalArgumentException("Plugin File Is Unavailable");
        }
        return integration.id();
    }

    private static String text(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private final class FileWatch implements AutoCloseable {
        private final ReProxyIntegrations.Integration integration;
        private final String path;
        private final Runnable changed;
        private TaskScheduler.ScheduledTask task;
        private String admittedStamp;
        private boolean admittedExists;
        private boolean observing;
        private boolean closed;

        private FileWatch(ReProxyIntegrations.Integration integration, String path, Runnable changed) {
            this.integration = integration;
            this.path = path;
            this.changed = changed;
            task = TaskSchedulers.current().scheduleAtFixedRate(this::poll, Duration.ZERO, Duration.ofSeconds(4));
            synchronized (this) {
                if (closed) task.cancel();
            }
        }

        private void poll() {
            synchronized (this) {
                if (closed || observing) return;
                if (!current.getAsBoolean()) {
                    close();
                    return;
                }
                observing = true;
            }
            try {
                observe(integration, path).whenComplete(this::observed);
            } catch (RuntimeException failure) {
                observed(null, failure);
            }
        }

        private void observed(Document document, Throwable failure) {
            boolean notify;
            synchronized (this) {
                observing = false;
                if (closed) return;
                if (!current.getAsBoolean()) {
                    close();
                    return;
                }
                if (failure != null) return;
                notify = admittedStamp == null || admittedExists != document.exists() || !admittedStamp.equals(document.stamp());
                admittedStamp = document.stamp();
                admittedExists = document.exists();
            }
            if (notify) {
                try {
                    changed.run();
                } catch (RuntimeException ignored) {
                }
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            admittedStamp = null;
            if (task != null) task.cancel();
        }
    }
}
