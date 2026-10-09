package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonObject;
import org.teavm.jso.JSBody;
import redxax.oxy.remotely.servers.ReProxyConnectorCapability;
import redxax.oxy.remotely.servers.ReProxyManager;
import redxax.oxy.remotely.ui.settings.controllers.ReProxySettingsCapability;
import restudio.rebase.reproxy.ReProxyClient;
import restudio.rebase.reproxy.ReProxyModels.Binding;
import restudio.rebase.reproxy.ReProxyModels.Connection;
import restudio.rebase.reproxy.ReProxyModels.Endpoint;
import restudio.rebase.reproxy.ReProxyModels.Ticket;
import restudio.rebase.reproxy.ReProxyWireCodec;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.http.HttpRequest;
import restudio.rescreen.platform.http.HttpResponse;
import restudio.rescreen.platform.http.HttpTransport;
import restudio.rescreen.ui.core.ScreenManager;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BrowserReProxyAccess implements AutoCloseable {
    private final HttpTransport transport;
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final ReProxyClient client;
    private final Set<Async<?>> pending = new HashSet<>();
    private final Set<Handle> handles = new HashSet<>();
    private final ReProxyConnectorCapability connector = new Connector();
    private final ReProxySettingsCapability settings = new Settings();
    private final Runnable authorityListener = this::authorityChanged;
    private String authority;
    private boolean closed;

    public BrowserReProxyAccess(HttpTransport transport, Clock clock, TaskScheduler scheduler) {
        this.transport = transport;
        this.clock = clock;
        this.scheduler = scheduler;
        authority = BrowserLaunchSession.authorityKey();
        client = new ReProxyClient(this::request, "/remotely-web/reproxy/v2", clock, scheduler);
        BrowserLaunchSession.addAuthStateListener(authorityListener);
        BrowserLaunchSession.addSessionExpiryListener(authorityListener);
    }

    public ReProxyClient client() { return client; }
    public ReProxyConnectorCapability connector() { return connector; }
    public ReProxySettingsCapability settings() { return settings; }

    private Async<ReProxyClient.Response> request(ReProxyClient.Request request) {
        return request(request, true, BrowserLaunchSession.authorityKey());
    }

    private Async<ReProxyClient.Response> request(ReProxyClient.Request request, boolean renew, String scope) {
        if (closed || !BrowserLaunchSession.authenticated()) return Async.failed(new IllegalStateException("Sign In To ReStudio"));
        if (!scope.equals(BrowserLaunchSession.authorityKey())) return Async.failed(new IllegalStateException("ReStudio Account Changed"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BrowserLaunchSession.apiBaseUrl() + request.path()))
                .header("Accept", "application/json").header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket()).timeout(Duration.ofSeconds(30));
        request.headers().forEach(builder::header);
        if (request.body() != null) builder.header("Content-Type", "application/json");
        builder.method(request.method(), request.body() == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(request.body()));
        Async<HttpResponse<String>> sent = transport.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (!request.path().endsWith("/connector/stop")) {
            pending.add(sent);
            sent.whenComplete((value, failure) -> pending.remove(sent));
        }
        return sent.thenCompose(response -> {
            if (closed || !scope.equals(BrowserLaunchSession.authorityKey())) return Async.failed(new IllegalStateException("ReStudio Account Changed"));
            if (response.statusCode() == 401 && renew) {
                return BrowserLaunchSession.renewAsync().thenCompose(ignored -> request(request, false, scope));
            }
            Map<String, String> headers = new HashMap<>();
            response.headers().map().forEach((name, values) -> { if (!values.isEmpty()) headers.put(name, values.getFirst()); });
            return Async.completed(new ReProxyClient.Response(response.statusCode(), response.body(), headers));
        });
    }

    private Async<JsonObject> command(Ticket ticket, Binding binding, String action, String operation) {
        return command(ticket, binding, action, operation, ticket.routeRevision());
    }

    private Async<JsonObject> command(Ticket ticket, Binding binding, String action, String operation, String routeRevision) {
        JsonObject body = new JsonObject();
        body.addProperty("operationId", operation);
        body.addProperty("bindingId", binding.id());
        body.addProperty("sessionId", ticket.sessionId());
        body.addProperty("generation", ticket.generation());
        body.addProperty("routeRevision", routeRevision);
        String path = "/remotely-web/reproxy/v2/connections/" + encode(ticket.connectionId()) + "/connector/" + action;
        if ("status".equals(action)) path += "?sessionId=" + encode(ticket.sessionId()) + "&generation=" + encode(ticket.generation());
        return request(new ReProxyClient.Request("status".equals(action) ? "GET" : "POST", path,
                "status".equals(action) ? null : body.toString(), Map.of())).thenApply(response -> {
            if (response.status() < 200 || response.status() >= 300) {
                String message = "ReProxy Connector Is Unavailable";
                try {
                    JsonObject error = BrowserJson.object(response.body());
                    String detail = BrowserJson.string(error, "message");
                    if (!detail.isBlank()) message = detail;
                } catch (RuntimeException ignored) { }
                throw new IllegalStateException(message);
            }
            return BrowserJson.object(response.body());
        });
    }

    private void authorityChanged() {
        String current = BrowserLaunchSession.authorityKey();
        if (BrowserLaunchSession.authenticated() && current.equals(authority)) return;
        new ArrayList<>(handles).forEach(Handle::close);
        authority = current;
        new ArrayList<>(pending).forEach(Async::cancel);
        pending.clear();
        ReProxyManager.cancelAll();
    }

    @Override
    public void close() {
        if (closed) return;
        new ArrayList<>(handles).forEach(Handle::close);
        closed = true;
        BrowserLaunchSession.removeAuthStateListener(authorityListener);
        BrowserLaunchSession.removeSessionExpiryListener(authorityListener);
        new ArrayList<>(pending).forEach(Async::cancel);
        pending.clear();
        ReProxyManager.cancelAll();
    }

    private final class Connector implements ReProxyConnectorCapability {
        @Override
        public Availability availability(Binding binding) {
            if (closed || !BrowserLaunchSession.authenticated()) return new Availability(false, "Sign In To ReStudio");
            if (binding == null) return new Availability(false, "Choose A Server Binding");
            if ("HOSTED".equals(binding.kind())) return new Availability(true, "");
            if ("AGENT".equals(binding.kind()) && !binding.agentId().isBlank() && !binding.serverId().isBlank()
                    && ("READY".equals(binding.readiness()) || "AVAILABLE".equals(binding.readiness()))) return new Availability(true, "");
            return new Availability(false, "Connect A Local Agent");
        }

        @Override
        public ReProxyConnectorCapability.Handle open(Ticket ticket, Binding binding, List<Endpoint> approved, Listener listener) {
            Availability availability = availability(binding);
            if (!availability.available()) throw new UnsupportedOperationException(availability.reason());
            BrowserReProxyAccess.Handle handle = new BrowserReProxyAccess.Handle(ticket, binding, listener);
            handles.add(handle);
            handle.begin();
            return handle;
        }
    }

    private final class Handle implements ReProxyConnectorCapability.Handle {
        private final Ticket ticket;
        private final Binding binding;
        private final ReProxyConnectorCapability.Listener listener;
        private final Async<Void> ready = Async.pending();
        private final String operation = operationKey();
        private final long deadline = clock.millis() + 90_000;
        private TaskScheduler.ScheduledTask poll;
        private Async<JsonObject> active;
        private boolean disposed;
        private boolean authenticated;
        private String revision = "";

        private Handle(Ticket ticket, Binding binding, ReProxyConnectorCapability.Listener listener) {
            this.ticket = ticket;
            this.binding = binding;
            this.listener = listener;
        }

        private void begin() {
            active = command(ticket, binding, "start", operation);
            active.whenComplete((status, failure) -> {
                if (disposed) return;
                if (failure != null) failed(failure); else observed(status);
            });
        }

        private void observed(JsonObject status) {
            if (disposed) return;
            if (!ticket.connectionId().equals(BrowserJson.string(status, "connectionId"))
                    || !ticket.sessionId().equals(BrowserJson.string(status, "sessionId"))
                    || !ticket.generation().equals(BrowserJson.string(status, "generation"))) {
                failed(new IllegalStateException("ReProxy Connector Identity Changed"));
                return;
            }
            String state = BrowserJson.string(status, "state");
            if ("FAILED".equals(state) || "STOPPED".equals(state)) {
                failed(new IllegalStateException(BrowserJson.string(status, "lastError")));
                return;
            }
            if (BrowserJson.bool(status, "authenticated", false) && !authenticated) {
                authenticated = true;
                listener.authenticated();
            }
            String route = BrowserJson.string(status, "routeRevision");
            if (authenticated && BrowserJson.bool(status, "ready", false) && !route.isBlank()) {
                if (revision.isBlank()) {
                    revision = route;
                    listener.activated(route);
                    ready.complete(null);
                } else if (!route.equals(revision)) {
                    revision = route;
                    listener.changed(route);
                }
            }
            if (!ready.isDone() && clock.millis() >= deadline) {
                failed(new IllegalStateException("ReProxy Connector Timed Out"));
                return;
            }
            poll = scheduler.schedule(this::observe, Duration.ofSeconds(1));
        }

        private void observe() {
            if (disposed) return;
            active = command(ticket, binding, "status", operation);
            active.whenComplete((status, failure) -> { if (!disposed) { if (failure == null) observed(status); else failed(failure); } });
        }

        private void failed(Throwable failure) {
            if (disposed) return;
            ready.completeExceptionally(failure);
            listener.failed(failure);
            close();
        }

        @Override
        public Async<Void> ready() { return ready; }

        @Override
        public void updateGrants(List<Endpoint> approved) {
            if (disposed) return;
            command(ticket, binding, "update", operationKey(), revision.isBlank() ? ticket.routeRevision() : revision).whenComplete((ignored, failure) -> { if (!disposed && failure != null) failed(failure); });
        }

        @Override
        public void close() {
            if (disposed) return;
            disposed = true;
            handles.remove(this);
            if (poll != null) poll.cancel();
            if (active != null && !active.isDone()) active.cancel();
            ready.completeExceptionally(new IllegalStateException("ReProxy Connector Closed"));
            if (!closed && BrowserLaunchSession.authenticated() && authority.equals(BrowserLaunchSession.authorityKey())) {
                command(ticket, binding, "stop", operationKey(), revision.isBlank() ? ticket.routeRevision() : revision).exceptionally(ignored -> null);
            }
            listener.closed();
        }
    }

    private final class Settings implements ReProxySettingsCapability {
        public boolean authenticated() { return BrowserLaunchSession.authenticated(); }
        public Availability availability() { return authenticated() ? Availability.supported() : Availability.unavailable("Sign In To ReStudio"); }
        public ReProxyClient client() { return client; }
        public String accountId() { return BrowserLaunchSession.metadata().subjectId(); }
        public String operationKey() { return BrowserReProxyAccess.operationKey(); }
        public Async<List<ServerTarget>> serverTargets() {
            return request(new ReProxyClient.Request("GET", "/remotely-web/reproxy/v2/targets", null, Map.of())).thenApply(response -> {
                if (response.status() != 200) throw new IllegalStateException("Server Targets Are Unavailable");
                List<ServerTarget> targets = new ArrayList<>();
                BrowserJson.parse(response.body()).getAsJsonArray().forEach(element -> {
                    JsonObject value = element.getAsJsonObject();
                    Binding binding = ReProxyWireCodec.binding(value.getAsJsonObject("binding"));
                    targets.add(new ServerTarget(BrowserJson.string(value, "id"), BrowserJson.string(value, "name"), binding));
                });
                return List.copyOf(targets);
            });
        }
        public Async<Void> selectConnection(Connection connection) {
            if (connection == null || connection.binding() == null || connection.binding().serverId().isBlank()) {
                return Async.failed(new IllegalArgumentException("Choose A Server Connection"));
            }
            ReProxyManager.selectServerConnection(connection.binding().serverId(), connection.id());
            return Async.completed(null);
        }

        public Availability connectionAvailability(Connection connection) {
            ReProxyConnectorCapability.Availability value = ReProxyManager.availability(connection);
            return new Availability(value.available(), value.reason());
        }
        public Async<Void> attachConnection(Connection connection) { return ReProxyManager.attachConnection(connection); }
        public void connectionChanged(Connection connection) { ReProxyManager.connectionChanged(connection); }
        public void connectionDeleted(String id) { ReProxyManager.connectionDeleted(id); }
        public void copyAddress(String address) { ScreenManager.getInstance().clipboardHandler().setClipboard(address); }
        public Async<ServerModels.ReProxySummary> summary() { return legacy(); }
        public Async<ServerModels.ReProxyDomain> createDomain(String label) { return legacy(); }
        public Async<Void> deleteDomain(String id) { return legacy(); }
        public Async<Void> stopTunnel(String id) { return legacy(); }
        private <T> Async<T> legacy() { return Async.failed(new UnsupportedOperationException("Use Saved ReProxy Connections")); }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    @JSBody(script = "if (!window.crypto || !window.crypto.getRandomValues) throw new Error('Secure Browser Entropy Is Unavailable'); var b = new Uint8Array(16); window.crypto.getRandomValues(b); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128; var s = Array.from(b, function(v) { return v.toString(16).padStart(2, '0'); }).join(''); return s.slice(0,8)+'-'+s.slice(8,12)+'-'+s.slice(12,16)+'-'+s.slice(16,20)+'-'+s.slice(20);")
    private static native String operationKey();
}
