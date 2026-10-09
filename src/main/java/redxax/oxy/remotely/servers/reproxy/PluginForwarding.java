package redxax.oxy.remotely.servers.reproxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.servers.ReProxyManager;
import redxax.oxy.remotely.servers.ReProxyTarget;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.reproxy.ReProxyClient;
import restudio.rebase.reproxy.ReProxyModels.Connection;
import restudio.rebase.reproxy.ReProxyModels.Endpoint;
import restudio.rebase.reproxy.ReProxyModels.EndpointSpec;
import restudio.rebase.reproxy.ReProxyModels.Operation;
import restudio.rebase.reproxy.ReProxyModels.PublicPort;
import restudio.rebase.reproxy.ReProxyModels.Target;
import restudio.rebase.ui.screens.resources.ResourceContainerItem;
import restudio.rebase.ui.screens.resources.ResourceForwarding;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.util.JsonTreeParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PluginForwarding implements ResourceForwarding, AutoCloseable {
    public record Document(boolean exists, String content, String stamp) {
        public Document {
            content = Objects.requireNonNullElse(content, "");
            stamp = Objects.requireNonNullElse(stamp, "");
        }
    }

    public record Port(String host, int port, String id, boolean created, String operationId) {
        public Port {
            host = Objects.requireNonNullElse(host, "");
            id = Objects.requireNonNullElse(id, "");
            operationId = Objects.requireNonNullElse(operationId, "");
            if (host.isBlank() || port < 1 || port > 65535 || id.isBlank() || operationId.isBlank()) {
                throw new IllegalArgumentException("The Allocated Port Is Invalid");
            }
        }
    }

    public static final class ReservationRejected extends IllegalStateException {
        public ReservationRejected(String message) {
            super(message);
        }
    }

    private static final class MoveRolledBack extends IllegalStateException {
        private MoveRolledBack(String message) {
            super(message);
        }
    }

    public interface Access {
        enum Ports { RELAY, ALLOCATED, PRIVATE }
        String serverKey();
        default String authorityStamp() { return serverKey(); }
        String name();
        int serverPort();
        boolean local();
        default Ports ports() { return local() ? Ports.RELAY : Ports.ALLOCATED; }
        default boolean proxy() { return false; }
        default String hostScope() { return serverKey(); }
        default void admitNetwork(String id, long revision) { }
        default AutoCloseable networkChange(String id, long revision) { return () -> { }; }
        default AutoCloseable change() { return () -> { }; }
        default Async<Void> secretFiles() {
            return Async.failed(new IllegalStateException("The Host Must Confirm Plasmo Voice Uses Its Settings Folder For The Shared Secret"));
        }
        Async<Boolean> running();
        Async<Document> observe(String path);
        default Async<Document> observe(ReProxyIntegrations.Integration integration, String path) { return observe(path); }
        Async<Document> mutate(String path, Document expected, String content, String operationId);
        default Async<Document> mutate(ReProxyIntegrations.Integration integration, String path, Document expected, String content, String operationId) { return mutate(path, expected, content, operationId); }
        Async<Port> reserve(ReProxyIntegrations.Integration integration, int desiredPort, String operationId);
        default Async<Port> reserveExact(ReProxyIntegrations.Integration integration, int desiredPort, String operationId) {
            return Async.failed(new ReservationRejected("Configure A Matching Port In Server Ports Before Updating This Plugin"));
        }
        default AutoCloseable watch(String path, Runnable changed) { return () -> { }; }
        default AutoCloseable watch(ReProxyIntegrations.Integration integration, String path, Runnable changed) { return watch(path, changed); }
        Async<Void> release(ReProxyIntegrations.Integration integration, Port port);
        default Async<Void> reload(ReProxyIntegrations.Integration integration, String path, Document expected) {
            return Async.failed(new UnsupportedOperationException("Plugin Reload Is Unavailable"));
        }
        ReProxyTarget localTarget();
    }

    public record NetworkRoute(String host, int port) {
        public NetworkRoute {
            host = Objects.requireNonNullElse(host, "").strip();
            if (host.isBlank() || host.length() > 253 || host.chars().anyMatch(Character::isWhitespace) || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Network Voice Address Is Unavailable");
            }
        }
    }

    public record NetworkEdit(String path, Document expected, String content) {
        public NetworkEdit {
            path = ReProxyIntegrations.relativePath(path);
            Objects.requireNonNull(expected, "Expected File Is Required");
            if (path.startsWith(".remotely/")) throw new IllegalArgumentException("Plugin File Is Unavailable");
        }
    }

    private record Admission(String stamp, String identity, Entry entry, boolean enabled) {
    }

    private enum Goal { CONNECT, UNLINK, REPAIR }

    private static final class Waiting extends IllegalStateException {
        private Waiting(String hint) {
            super(hint);
        }
    }

    private static final class Desired {
        private final Async<Void> result = Async.pending();
        private String authority;
        private final String resourceKey;
        private final String resourceStamp;
        private Goal goal;
        private AutoCloseable lease;
        private int pipelines;
        private boolean settled;
        private boolean read;
        private String hint = "Waiting For The Current Connection Change To Finish";

        private Desired(String authority, String resourceKey, String resourceStamp, boolean unlink) {
            this.authority = authority;
            this.resourceKey = resourceKey;
            this.resourceStamp = resourceStamp;
            goal = unlink ? Goal.UNLINK : null;
        }
    }

    private final Access access;
    private final Map<String, Entry> integrations = new HashMap<>();
    private Map<String, Admission> resources = Map.of();
    private Connection canonical;
    private AutoCloseable watch;
    private TaskScheduler.ScheduledTask stopWatch;
    private long stopGeneration;
    private boolean checkingStop;
    private boolean closed;
    private static final State HIDDEN = new State(false, false, false, "", "");
    private static final Set<String> PHASES = Set.of("OFF", "ABANDONED", "PREPARED", "ALLOCATED", "ENDPOINT_REQUESTED", "ENDPOINT_PENDING",
            "CONFIG_READY", "ACTIVE", "SYNC_PREPARED", "SYNC_REQUESTED", "SYNC_PENDING", "MOVE_REQUESTED", "MOVE_ALLOCATED", "MOVE_CONFIG_READY", "MOVE_APPLIED", "MOVE_ROLLBACK", "RESTORE_REQUESTED", "RESTORED", "REMOVE_REQUESTED", "REMOVE_PENDING", "REMOVED");

    public PluginForwarding(Access access) {
        this.access = Objects.requireNonNull(access, "access");
        if (access.serverKey() == null || access.serverKey().isBlank()) throw new IllegalArgumentException("Server Is Required");
    }

    public String authorityStamp() {
        return access.authorityStamp();
    }

    public Access access() {
        return access;
    }

    private static boolean networkFile(ReProxyIntegrations.Integration integration, String path) {
        return integration.paths().contains(path) || "plasmovoice".equals(integration.id()) && integration.paths().stream().map(value ->
                value.substring(0, value.lastIndexOf('/') + 1) + "forwarding-secret").anyMatch(path::equals);
    }

    public synchronized void admitIntegration(ReProxyIntegrations.Integration integration, boolean proxy) {
        Entry entry = entry(integration);
        entry.proxy = proxy;
    }

    public AutoCloseable watchIntegration(ReProxyIntegrations.Integration integration, Runnable changed) {
        Objects.requireNonNull(changed, "Changed Handler Is Required");
        Entry entry;
        synchronized (this) {
            entry = entry(integration);
            entry.listeners.add(changed);
        }
        entry.loaded.whenComplete((ignored, failure) -> announce(entry));
        return () -> {
            synchronized (this) { entry.listeners.remove(changed); }
        };
    }

    public synchronized State state(ReProxyIntegrations.Integration integration) {
        Entry entry = integrations.get(integration.id());
        return entry == null ? HIDDEN : state(entry, true);
    }

    public synchronized NetworkRoute route(ReProxyIntegrations.Integration integration) {
        Entry entry = integrations.get(integration.id());
        Journal journal = entry == null ? null : entry.journal;
        return journal == null || !"ACTIVE".equals(journal.phase) || !linked(entry) ? null : new NetworkRoute(journal.publicHost, journal.publicPort);
    }

    public synchronized NetworkRoute networkRoute(ReProxyIntegrations.Integration integration) {
        Entry entry = integrations.get(integration.id());
        return entry == null || entry.journal == null ? null : entry.journal.networkRoute;
    }

    public synchronized int targetPort(ReProxyIntegrations.Integration integration) {
        Entry entry = integrations.get(integration.id());
        return entry == null || entry.journal == null ? 0 : entry.journal.targetPort;
    }

    public synchronized boolean ownsEndpoint(ReProxyIntegrations.Integration integration, String endpoint) {
        Entry entry = integrations.get(integration.id());
        return entry != null && entry.journal != null && !inactive(entry.journal.phase) && endpoint.equals(entry.journal.endpoint);
    }

    public synchronized boolean networkGroup(ReProxyIntegrations.Integration integration, String group) {
        Entry entry = integrations.get(integration.id());
        return entry != null && entry.journal != null && group.equals(entry.journal.networkGroup);
    }

    public synchronized boolean hasConnection(ReProxyIntegrations.Integration integration) {
        Entry entry = integrations.get(integration.id());
        return entry != null && entry.journal != null && "ACTIVE".equals(entry.journal.phase) && linked(entry);
    }

    public Async<Void> validateNetwork(ReProxyIntegrations.Integration integration, boolean proxy, NetworkRoute route) {
        Entry entry;
        synchronized (this) { entry = entry(integration); }
        return entry.loaded.thenRun(() -> {
            synchronized (this) {
                if (closed || entry.busy || entry.desired != null) throw new IllegalStateException("Wait For The Current Plugin Change To Finish");
                if (entry.journal != null && !inactive(entry.journal.phase) && (!Objects.equals(route, entry.journal.networkRoute)
                        || !ReProxyIntegrations.paths(integration(entry), proxy).contains(entry.journal.path))) {
                    throw new IllegalStateException("Disconnect The Previous Plugin Connection Before Changing Its Network Route");
                }
            }
        });
    }

    public Async<Void> connect(ReProxyIntegrations.Integration integration, boolean proxy, NetworkRoute route) {
        return connect(integration, proxy, route, "");
    }

    public Async<Void> connect(ReProxyIntegrations.Integration integration, boolean proxy, NetworkRoute route, String group) {
        Entry entry;
        synchronized (this) { entry = entry(integration); }
        return validateNetwork(integration, proxy, route).thenCompose(ignored -> {
            synchronized (this) {
                entry.proxy = proxy;
                entry.networkRoute = route;
                entry.networkGroup = group;
            }
            return pendingNetwork(entry, group).thenCompose(done -> action(entry, false, "", "", Goal.CONNECT));
        });
    }

    private Async<Void> pendingNetwork(Entry entry, String group) {
        if (group.isBlank()) return Async.completed(null);
        synchronized (this) {
            if (entry.journal == null || inactive(entry.journal.phase)) return Async.completed(null);
            if (closed || entry.busy || entry.desired != null) return Async.failed(new IllegalStateException("Wait For The Current Plugin Change To Finish"));
            entry.busy = true;
            entry.journal.networkGroup = group;
            entry.journal.networkPending = true;
            entry.journal.networkReady = false;
            entry.journal.sequence++;
        }
        return save(entry).whenComplete((ignored, failure) -> {
            synchronized (this) { entry.busy = false; }
            announce(entry);
            drain(entry);
        });
    }

    public Async<Void> finishNetwork(ReProxyIntegrations.Integration integration, String group) {
        Entry entry;
        synchronized (this) { entry = entry(integration); }
        return entry.loaded.thenCompose(ignored -> {
            synchronized (this) {
                if (closed || entry.busy || entry.desired != null) return Async.failed(new IllegalStateException("Wait For The Current Plugin Change To Finish"));
                if (entry.journal == null || !"ACTIVE".equals(entry.journal.phase) || !linked(entry)
                        || !group.equals(entry.journal.networkGroup) || entry.journal.auxiliary.values().stream().anyMatch(edit -> !edit.applied || !edit.verified)) {
                    return Async.failed(new IllegalStateException("Network Plugin Setup Is Incomplete. Retry Its Setup"));
                }
                if (!entry.journal.networkPending) return Async.completed(null);
                entry.busy = true;
                entry.journal.networkPending = false;
                entry.journal.networkReady = true;
                if (entry.error.equals("Network Plugin Settings Were Edited. Their Manual Values Were Preserved")) entry.error = "";
                entry.journal.sequence++;
            }
            return save(entry).whenComplete((done, failure) -> {
                synchronized (this) { entry.busy = false; }
                announce(entry);
                drain(entry);
            });
        });
    }

    public Async<Void> disconnect(ReProxyIntegrations.Integration integration) {
        Entry entry;
        synchronized (this) { entry = entry(integration); }
        return action(entry, true, "", "", Goal.UNLINK);
    }

    public Async<Void> configureNetwork(ReProxyIntegrations.Integration integration, List<NetworkEdit> edits) {
        return configureNetwork(integration, edits, UUID.randomUUID().toString());
    }

    public Async<Void> configureNetwork(ReProxyIntegrations.Integration integration, List<NetworkEdit> edits, String changeId) {
        UUID.fromString(changeId);
        Entry entry;
        synchronized (this) { entry = entry(integration); }
        return entry.loaded.thenCompose(ignored -> {
            AutoCloseable lease;
            synchronized (this) {
                if (closed || entry.busy || entry.desired != null) return Async.failed(new IllegalStateException("Wait For The Current Plugin Change To Finish"));
                if (entry.journal == null || !"ACTIVE".equals(entry.journal.phase) || !linked(entry)) return Async.failed(new IllegalStateException("Connect The Plugin Before Configuring Its Network"));
                try { lease = access.change(); } catch (RuntimeException failure) { return Async.failed(failure); }
                entry.busy = true;
            }
            return running().thenCompose(active -> {
                if (Boolean.TRUE.equals(active)) throw new IllegalStateException("Stop The Network Before Configuring Its Plugins");
                requireAuthority(entry);
                for (NetworkEdit edit : edits) {
                    if (!networkFile(integration(entry), edit.path())) throw new IllegalArgumentException("Network Plugin File Is Unavailable");
                    Auxiliary previous = entry.journal.auxiliary.get(edit.path());
                    if (previous != null && !Objects.equals(edit.expected().content(), previous.content) && !edit.expected().equals(previous.original)) {
                        throw new IllegalStateException("Network Plugin Settings Were Edited. Their Manual Values Were Preserved");
                    }
                    if (!edit.expected().exists() || !Objects.equals(edit.expected().content(), edit.content())) {
                        entry.journal.auxiliary.put(edit.path(), new Auxiliary(previous == null ? edit.expected() : previous.original, edit.content(), false, changeId, edit.expected()));
                    }
                }
                if (entry.journal.auxiliary.size() > 64) throw new IllegalArgumentException("Too Many Network Plugin Files");
                entry.journal.networkReady = !entry.journal.networkPending && entry.journal.auxiliary.values().stream().allMatch(edit -> edit.applied && edit.verified);
                entry.journal.sequence++;
                return save(entry).thenCompose(done -> auxiliary(entry, false));
            }).thenCompose(done -> refresh(entry)).whenComplete((done, failure) -> {
                close(lease);
                synchronized (this) { entry.busy = false; }
                announce(entry);
                drain(entry);
            });
        });
    }

    public Async<Void> rollbackNetwork(ReProxyIntegrations.Integration integration, String changeId) {
        Entry entry;
        synchronized (this) { entry = entry(integration); }
        return entry.loaded.thenCompose(ignored -> {
            AutoCloseable lease;
            synchronized (this) {
                if (closed || entry.busy || entry.desired != null) return Async.failed(new IllegalStateException("Wait For The Current Plugin Change To Finish"));
                if (entry.journal == null || inactive(entry.journal.phase)) return Async.completed(null);
                try { lease = access.change(); } catch (RuntimeException failure) { return Async.failed(failure); }
                entry.busy = true;
            }
            Async<Void> result = running().thenAccept(active -> {
                if (Boolean.TRUE.equals(active)) throw new IllegalStateException("Stop The Network Before Restoring Its Plugin Settings");
            });
            for (Map.Entry<String, Auxiliary> item : List.copyOf(entry.journal.auxiliary.entrySet())) {
                Auxiliary edit = item.getValue();
                if (!changeId.equals(edit.changeId)) continue;
                result = result.thenCompose(done -> {
                    requireAuthority(entry);
                    return access.observe(integration(entry), item.getKey()).thenCompose(current -> {
                        if (current.equals(edit.previous)) return Async.completed(current);
                        if (!current.exists() || !current.content().equals(edit.content)) throw new IllegalStateException("Network Plugin Settings Were Edited. Their Manual Values Were Preserved");
                        return access.mutate(integration(entry), item.getKey(), current, edit.previous.exists() ? edit.previous.content() : null,
                                operation(entry.journal, (12L << 32) ^ entry.journal.sequence));
                    }).thenCompose(current -> {
                        if (current.exists() != edit.previous.exists() || !current.content().equals(edit.previous.content())) {
                            throw new IllegalStateException("Network Plugin Settings Were Edited. Their Manual Values Were Preserved");
                        }
                        synchronized (PluginForwarding.this) {
                        if (item.getKey().equals(entry.journal.path)) document(entry, current);
                        if (edit.previous.equals(edit.original)) entry.journal.auxiliary.remove(item.getKey());
                        else {
                            edit.content = edit.previous.content();
                            edit.applied = true;
                            edit.verified = true;
                            edit.observation++;
                            edit.changeId = "";
                            edit.previous = null;
                        }
                        entry.journal.sequence++;
                        entry.journal.networkReady = !entry.journal.networkPending && entry.journal.auxiliary.values().stream().allMatch(value -> value.applied && value.verified);
                        }
                        return save(entry);
                    });
                });
            }
            return result.thenCompose(done -> refresh(entry)).whenComplete((done, failure) -> {
                close(lease);
                synchronized (this) { entry.busy = false; }
                announce(entry);
                drain(entry);
            });
        });
    }

    private Async<Void> auxiliary(Entry entry, boolean restore) {
        Async<Void> result = running().thenAccept(active -> {
            if (Boolean.TRUE.equals(active)) throw new IllegalStateException("Stop The Network Before Changing Its Plugin Settings");
        });
        for (Map.Entry<String, Auxiliary> item : List.copyOf(entry.journal.auxiliary.entrySet())) {
            String path = item.getKey();
            Auxiliary edit = item.getValue();
            result = result.thenCompose(ignored -> {
                requireAuthority(entry);
                return access.observe(integration(entry), path).thenCompose(current -> {
                    String content = restore ? edit.original.exists() ? edit.original.content() : null : edit.content;
                    if (current.exists() == (content != null) && Objects.equals(current.content(), Objects.requireNonNullElse(content, ""))) return Async.completed(current);
                    boolean matches = restore ? current.exists() && Objects.equals(current.content(), edit.content) : current.equals(edit.original);
                    if (!matches) throw new IllegalStateException("Network Plugin Settings Were Edited. Their Manual Values Were Preserved");
                    return access.mutate(integration(entry), path, current, content, operation(entry.journal, (11L << 32) ^ entry.journal.sequence));
                }).thenCompose(current -> {
                    String expected = restore ? edit.original.exists() ? edit.original.content() : null : edit.content;
                    if (current.exists() != (expected != null) || !current.content().equals(Objects.requireNonNullElse(expected, ""))) {
                        throw new IllegalStateException("Network Plugin Settings Were Edited. Their Manual Values Were Preserved");
                    }
                    synchronized (PluginForwarding.this) {
                    if (path.equals(entry.journal.path)) document(entry, current);
                    if (restore) entry.journal.auxiliary.remove(path);
                    else {
                        edit.applied = true;
                        edit.verified = true;
                        edit.observation++;
                    }
                    entry.journal.sequence++;
                    entry.journal.networkReady = !entry.journal.networkPending && entry.journal.auxiliary.values().stream().allMatch(value -> value.applied && value.verified);
                    }
                    return save(entry);
                });
            });
        }
        return result;
    }

    private boolean local(Entry entry) {
        NetworkRoute route = entry.journal == null ? entry.networkRoute : entry.journal.networkRoute;
        return access.local() && route == null;
    }

    @Override
    public synchronized void admit(List<ResourceContainerItem> inventory) {
        Map<String, Admission> next = new HashMap<>();
        admit(inventory, true, next, new HashSet<>());
        resources = Map.copyOf(next);
    }

    private void admit(List<ResourceContainerItem> inventory, boolean parentEnabled, Map<String, Admission> next, Set<String> parents) {
        for (ResourceContainerItem resource : inventory == null ? List.<ResourceContainerItem>of() : inventory) {
            if (resource == null) continue;
            String key = ResourceContainerItem.key(resource);
            if (!parents.add(key)) continue;
            boolean enabled = parentEnabled && resource.isEnabled();
            String identity = identity(resource);
            String stamp = stamp(resource) + "\u0000" + enabled;
            Admission previous = resources.get(key);
            if (previous != null && previous.stamp().equals(stamp)) next.put(key, previous);
            else {
                ReProxyIntegrations.Integration integration = ReProxyIntegrations.find(resource);
                Entry entry = integration == null ? null : entry(integration);
                next.put(key, new Admission(stamp, identity, entry, enabled));
            }
            admit(resource.getChildren(), enabled, next, parents);
            parents.remove(key);
        }
    }

    private synchronized Entry entry(ReProxyIntegrations.Integration integration) {
        Entry entry = integrations.get(integration.id());
        if (entry != null) return entry;
        entry = new Entry(integration);
        entry.proxy = access.proxy();
        integrations.put(integration.id(), entry);
        Entry admitted = entry;
        load(admitted).whenComplete((ignored, failure) -> {
            synchronized (PluginForwarding.this) {
                admitted.loading = false;
                if (failure != null) admitted.error = message(failure);
            }
            admitted.loaded.complete(null);
            drain(admitted);
        });
        return entry;
    }

    @Override
    public synchronized void admitConfig(String relativePath) {
        String path = ReProxyIntegrations.relativePath(relativePath);
        ReProxyIntegrations.catalog().stream().filter(integration -> integration.paths().contains(path)).findFirst().ifPresent(integration -> {
            Entry entry = entry(integration);
            if (entry.configPath.isBlank()) entry.configPath = path;
        });
    }

    private synchronized Entry config(String path) {
        return integrations.values().stream().filter(entry -> entry.journal != null && entry.journal.path.equals(path)
                || entry.journal == null && integration(entry).paths().contains(path)).findFirst().orElse(null);
    }

    @Override
    public synchronized State configState(String relativePath) {
        Entry entry = config(relativePath);
        if (entry == null || entry.desired == null && (!entry.loading && entry.journal == null || entry.journal != null && inactive(entry.journal.phase))) return HIDDEN;
        return state(entry, true);
    }

    @Override
    public synchronized boolean ownsPort(String endpointId) {
        if (endpointId == null || endpointId.isBlank()) return false;
        return integrations.values().stream().anyMatch(entry -> entry.journal != null && !inactive(entry.journal.phase) && endpointId.equals(entry.journal.endpoint));
    }

    @Override
    public Async<Void> unlinkConfig(String relativePath) {
        admitConfig(relativePath);
        Entry entry = config(relativePath);
        if (entry == null) return Async.failed(new IllegalStateException("This File Has No Linked Port"));
        return action(entry, true, "", "");
    }

    @Override
    public Async<Void> changed(String relativePath) {
        admitConfig(relativePath);
        Entry entry = config(relativePath);
        if (entry == null) return Async.completed(null);
        return entry.loaded.thenCompose(ignored -> queued(entry, false));
    }

    @Override
    public Async<Void> reconcile() {
        return running().thenCompose(active -> {
            List<Entry> entries = ReProxyIntegrations.catalog().stream().map(this::entry).toList();
            Async<?>[] requests = new Async<?>[entries.size()];
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                requests[index] = entry.loaded.thenCompose(ignored -> entry.document == null ? load(entry) : Async.completed(null)).thenCompose(ignored -> queued(entry, true));
            }
            return Async.allOf(requests);
        });
    }

    private Async<Void> queued(Entry entry, boolean force) {
        Async<Void> result;
        synchronized (this) {
            if (closed || entry.journal == null || inactive(entry.journal.phase)) return Async.completed(null);
            if (entry.change == null) {
                entry.change = Async.pending();
                entry.restores = 0;
                entry.endpointRetries = 0;
                entry.removeRetries = 0;
            }
            else entry.dirty = true;
            entry.force |= force;
            result = entry.change;
        }
        drain(entry);
        return result;
    }

    private void drain(Entry entry) {
        boolean force;
        synchronized (this) {
            if (closed) return;
            if (entry.desired != null) {
                resume(entry, null);
                return;
            }
            if (entry.loading || entry.busy || entry.change == null || entry.changing) return;
            entry.busy = true;
            entry.changing = true;
            entry.dirty = false;
            force = entry.force;
            entry.force = false;
        }
        AutoCloseable[] lease = new AutoCloseable[1];
        Async.supply(() -> {
            lease[0] = access.change();
            return entry.recover ? running().thenCompose(ignored -> read(entry)).thenRun(() -> entry.recover = false) : Async.<Void>completed(null);
        })
                .thenCompose(value -> value).thenCompose(ignored -> "plasmovoice".equals(integration(entry).id())
                        ? running().thenCompose(active -> Boolean.TRUE.equals(active) ? Async.completed(null) : access.secretFiles()) : Async.<Void>completed(null))
                .thenCompose(ignored -> reconcile(entry, force)).thenCompose(ignored -> refresh(entry)).whenComplete((ignored, failure) -> {
            close(lease[0]);
            Async<Void> result;
            boolean again;
            synchronized (PluginForwarding.this) {
                entry.busy = false;
                entry.changing = false;
                entry.error = failure == null ? "" : message(failure);
                entry.recover = failure != null;
                again = entry.dirty;
                result = entry.change;
                if (!again) entry.change = null;
            }
            if (result == null) return;
            if (again) drain(entry);
            else {
                if (failure == null) result.complete(null); else result.fail(failure);
                resume(entry, null);
            }
        });
    }

    @Override
    public synchronized State state(ResourceContainerItem resource) {
        Admission admission = resources.get(ResourceContainerItem.key(resource));
        if (admission == null || admission.entry() == null) return HIDDEN;
        return state(admission.entry(), admission.enabled());
    }

    private State state(Entry entry, boolean enabled) {
        Journal journal = entry.journal;
        boolean connected = journal != null && "ACTIVE".equals(journal.phase) && linked(entry) && journal.networkReady;
        String address = journal == null ? "" : journal.address;
        String hint;
        if (entry.desired != null) hint = entry.desired.hint;
        else if (!entry.error.isBlank()) hint = entry.error;
        else if (entry.loading) hint = "Loading Connection";
        else if (entry.busy) hint = "Updating Connection";
        else if (!entry.notice.isBlank()) hint = entry.notice;
        else if (connected) hint = "Settings Are Saved. Restart The Server To Apply Them";
        else if (journal != null && "ACTIVE".equals(journal.phase) && local(entry)) hint = "The Server Connection Changed. Stop The Server And Reconnect";
        else if (journal != null && !inactive(journal.phase)) hint = "Finish The Previous Connection Change. Stop The Server First";
        else if (!enabled) hint = "Enable " + integration(entry).name() + " Before Connecting";
        else hint = "Connect " + integration(entry).name() + (integration(entry).requiresStopped() ? ". Stop The Server First" : ". Settings Are Saved Before Reloading The Plugin");
        return new State(true, connected, entry.loading || entry.busy || entry.desired != null, hint, address);
    }

    @Override
    public Async<Void> toggle(ResourceContainerItem resource) {
        Admission admission;
        synchronized (this) { admission = resources.get(ResourceContainerItem.key(resource)); }
        if (admission == null || admission.entry() == null) return Async.failed(new IllegalStateException("Plugin Connection Is Unavailable"));
        return action(admission.entry(), false, ResourceContainerItem.key(resource), admission.identity());
    }

    private Async<Void> action(Entry entry, boolean unlink, String resourceKey, String resourceStamp) {
        return action(entry, unlink, resourceKey, resourceStamp, null);
    }

    private Async<Void> action(Entry entry, boolean unlink, String resourceKey, String resourceStamp, Goal goal) {
        Desired desired;
        synchronized (this) {
            if (closed) return Async.failed(new IllegalStateException("This Server Connection Was Closed"));
            if (entry.desired != null) {
                Desired previous = entry.desired;
                return unlink && previous.goal != Goal.UNLINK ? previous.result.thenCompose(ignored -> action(entry, true, resourceKey, resourceStamp)) : previous.result;
            }
            String authority = access.authorityStamp();
            AutoCloseable lease;
            try { lease = access.change(); } catch (RuntimeException failure) { return Async.failed(failure); }
            desired = new Desired(entry.loading && !local(entry) && authority.isBlank() ? null : authority, resourceKey, resourceStamp, unlink);
            desired.lease = lease;
            desired.result.whenComplete((ignored, failure) -> settle(desired, false));
            if (goal != null) desired.goal = goal;
            else if (!entry.loading && !entry.busy && entry.journal != null && "ACTIVE".equals(entry.journal.phase) && linked(entry)) desired.goal = Goal.UNLINK;
            entry.desired = desired;
            entry.error = "";
            entry.restores = 0;
            entry.endpointRetries = 0;
            entry.removeRetries = 0;
            ensureStopWatch();
        }
        desired.result.onCancel(() -> failDesired(entry, new Async.Cancellation()));
        announce(entry);
        resume(entry, null);
        return desired.result;
    }

    private void resume(Entry entry, Boolean active) {
        Desired desired;
        synchronized (this) {
            desired = entry.desired;
            if (closed || desired == null || entry.loading || entry.busy) return;
            entry.busy = true;
            entry.resourceKey = desired.resourceKey;
            desired.pipelines++;
            entry.resourceStamp = desired.resourceStamp;
            desired.hint = "Updating " + integration(entry).name() + " Connection";
        }
        Async.supply(() -> {
            requireDesired(entry, desired);
            return active == null ? running() : Async.completed(active);
        }).thenCompose(value -> value).thenCompose(running -> {
            if (Boolean.TRUE.equals(running) && integration(entry).requiresStopped()) throw stop(entry, desired);
            Async<Void> check = "plasmovoice".equals(integration(entry).id()) ? access.secretFiles() : Async.completed(null);
            return check.thenCompose(checked -> desired.read && !entry.recover ? Async.<Void>completed(null) : read(entry).thenRun(() -> {
                desired.read = true;
                entry.recover = false;
            }));
        }).thenCompose(ignored -> connectionReady(entry, desired)).thenCompose(ignored -> {
            requireDesired(entry, desired);
            if (local(entry) && entry.client == null) entry.client = ReProxyManager.serverClient();
            if (entry.journal != null) requireAuthority(entry);
            Journal journal = entry.journal;
            Async<Void> selection;
            if (desired.goal != null) selection = Async.completed(null);
            else if (journal == null || inactive(journal.phase)) {
                desired.goal = Goal.CONNECT;
                selection = Async.completed(null);
            } else if ("ACTIVE".equals(journal.phase) && local(entry)) {
                desired.goal = linked(entry) ? Goal.UNLINK : Goal.REPAIR;
                selection = Async.completed(null);
            } else {
                desired.goal = "ACTIVE".equals(journal.phase) && linked(entry) || disconnecting(journal.phase) ? Goal.UNLINK : Goal.CONNECT;
                selection = Async.completed(null);
            }
            return selection.thenCompose(done -> perform(entry, desired)).thenCompose(done -> refresh(entry));
        }).whenComplete((ignored, failure) -> {
            Waiting waiting = waiting(failure);
            settle(desired, true);
            Async<Void> failedChange = null;
            synchronized (PluginForwarding.this) {
                entry.busy = false;
                if (closed || entry.desired != desired) return;
                entry.recover = failure != null && waiting == null;
                if (waiting != null) {
                    desired.hint = waiting.getMessage();
                    entry.error = "";
                    ensureStopWatch();
                } else {
                    entry.desired = null;
                    entry.resourceKey = "";
                    entry.resourceStamp = "";
                    entry.error = failure == null ? "" : message(failure);
                    if (failure != null) {
                        failedChange = entry.change;
                        entry.change = null;
                        entry.dirty = false;
                        entry.force = false;
                        entry.waitingStop = false;
                    }
                    if (integrations.values().stream().noneMatch(value -> value.waitingStop || value.desired != null)) cancelStop();
                }
            }
            announce(entry);
            if (waiting != null) return;
            if (failure == null) desired.result.complete(null); else desired.result.fail(failure);
            if (failedChange != null) failedChange.fail(failure);
            if (failure == null) drain(entry);
        });
    }

    private Async<Void> connectionReady(Entry entry, Desired desired) {
        requireDesired(entry, desired);
        if (!local(entry) || desired.goal == Goal.UNLINK && (entry.journal == null || inactive(entry.journal.phase))) return Async.completed(null);
        if (entry.client == null) entry.client = ReProxyManager.serverClient();
        Connection connection = assigned();
        if (connection.pendingOperationId() == null || connection.pendingOperationId().isBlank()) {
            admitted(connection);
            return Async.completed(null);
        }
        synchronized (this) { desired.hint = "Waiting For The Server Connection To Finish Updating"; }
        announce(entry);
        return ReProxyManager.serverReady(access.localTarget(), connection.id()).thenAccept(current -> {
            requireDesired(entry, desired);
            requireConnection(current, connection.id());
            admitted(current);
        });
    }

    private Async<Void> perform(Entry entry, Desired desired) {
        synchronized (this) {
            requireDesired(entry, desired);
            if (desired.goal != Goal.UNLINK && !desired.resourceKey.isBlank() && !resources.get(desired.resourceKey).enabled()) {
                throw new Waiting("Enable " + integration(entry).name() + " And We Will Continue");
            }
        }
        Journal journal = entry.journal;
        if (journal == null || inactive(journal.phase)) {
            if (desired.goal == Goal.UNLINK) return Async.completed(null);
            return prepare(entry);
        }
        if (desired.goal == Goal.UNLINK && !"ACTIVE".equals(journal.phase) && !disconnecting(journal.phase)) return connect(entry)
                .exceptionallyCompose(failure -> (failure instanceof ReservationRejected || failure instanceof MoveRolledBack) && "ACTIVE".equals(journal.phase) ? Async.completed(null) : Async.failed(failure))
                .thenCompose(done -> disconnect(entry));
        if (desired.goal == Goal.UNLINK) return disconnect(entry);
        if (disconnecting(journal.phase)) return disconnect(entry).thenCompose(ignored -> prepare(entry));
        if ("ACTIVE".equals(journal.phase)) return (local(entry) ? settled(entry).thenCompose(connection -> repair(entry, connection)) : reconcile(entry, true))
                .thenCompose(ignored -> !journal.networkReady ? auxiliary(entry, false) : Async.completed(null));
        return connect(entry);
    }

    private Waiting stop(Entry entry, Desired desired) {
        String action = desired.goal == null ? "Updating " : desired.goal == Goal.UNLINK ? "Disconnecting " : "Connecting ";
        return new Waiting("Stop The Server And We Will Finish " + action + integration(entry).name());
    }

    private static Waiting waiting(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof Waiting waiting) return waiting;
        return null;
    }

    private void requireDesired(Entry entry, Desired desired) {
        synchronized (this) {
            if (closed || entry.desired != desired || desired.result.isCancelled()) throw new Async.Cancellation();
            String authority = access.authorityStamp();
            if (desired.authority == null) {
                if (entry.loading || authority.isBlank()) throw new IllegalStateException("Server Identity Is Unavailable");
                desired.authority = authority;
            } else if (!desired.authority.equals(authority)) throw new IllegalStateException("The Server Changed. Choose Its Plugin Connection Again");
            if (!desired.resourceKey.isBlank()) {
                Admission current = resources.get(desired.resourceKey);
                if (current == null || current.entry() != entry || !desired.resourceStamp.equals(current.identity())) {
                    throw new IllegalStateException("The Plugin Changed. Choose Its Connection Again");
                }
            }
        }
    }

    private void announce(Entry entry) {
        synchronized (this) {
            if (closed || entry.notification != null) return;
            long generation = ++entry.noticeGeneration;
            entry.notification = TaskSchedulers.current().schedule(() -> publish(entry, generation), Duration.ZERO);
        }
    }

    private void publish(Entry entry, long generation) {
        List<Runnable> listeners;
        synchronized (this) {
            if (closed || entry.noticeGeneration != generation) return;
            entry.notification = null;
            listeners = List.copyOf(entry.listeners);
        }
        for (Runnable listener : listeners) {
            synchronized (this) {
                if (closed || entry.noticeGeneration != generation) return;
                if (!entry.listeners.contains(listener)) continue;
            }
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }

    private Async<Void> load(Entry entry) {
        return running().thenCompose(ignored -> read(entry)).thenCompose(ignored -> {
            synchronized (this) {
                if (closed) throw new Async.Cancellation();
                if (local(entry)) {
                    if (entry.client == null) entry.client = ReProxyManager.serverClient();
                    if (watch == null) watch = ReProxyManager.watchServer(access.localTarget(), this::admitted);
                }
            }
            if (entry.journal == null || inactive(entry.journal.phase) || !"ACTIVE".equals(entry.journal.phase) && !syncing(entry.journal.phase) && !moving(entry.journal.phase)) return Async.completed(null);
            watch(entry);
            Async<Void> ready = local(entry) ? settled(entry).thenAccept(connection -> { }) : Async.completed(null);
            return ready.thenCompose(done -> {
                watch(entry);
                return observe(entry);
            }).handle((document, failure) -> { if (failure != null) entry.error = message(failure); return null; });
        });
    }

    @Override
    public AutoCloseable watchConfig(String relativePath, Runnable changed) {
        Objects.requireNonNull(changed, "changed");
        admitConfig(relativePath);
        Entry entry = config(relativePath);
        if (entry == null) return () -> { };
        synchronized (this) { entry.listeners.add(changed); }
        entry.loaded.whenComplete((ignored, failure) -> { if (failure == null) watch(entry); });
        return () -> {
            synchronized (PluginForwarding.this) {
                entry.listeners.remove(changed);
                if (entry.listeners.isEmpty() && (entry.journal == null || inactive(entry.journal.phase))) unwatch(entry);
            }
        };
    }

    private void document(Entry entry, Document document) {
        document(entry, document, -1);
    }

    private void document(Entry entry, Document document, long generation) {
        synchronized (this) {
            if (closed || generation >= 0 && generation != entry.watchGeneration) return;
            if (document.equals(entry.observed)) return;
            entry.observed = document;
            entry.prepared = null;
            entry.parseFailure = null;
            entry.actualValues = Map.of();
        }
        announce(entry);
    }

    private Async<Document> observe(Entry entry) {
        requireAuthority(entry);
        return access.observe(integration(entry), entry.journal.path).thenApply(document -> {
            document(entry, document);
            synchronized (this) {
                if (closed) throw new Async.Cancellation();
                int serverPort = access.serverPort();
                if (serverPort != entry.preparedServerPort) {
                    entry.prepared = null;
                    entry.parseFailure = null;
                    entry.actualValues = Map.of();
                    entry.preparedServerPort = serverPort;
                }
                if (entry.prepared == null && entry.parseFailure == null) {
                    try {
                        if (!document.exists()) throw new IllegalStateException("The Linked Plugin Configuration Was Removed");
                        entry.prepared = ReProxyIntegrations.prepare(integration(entry), entry.journal.path, document.content(), serverPort);
                        entry.actualValues = entry.prepared.connect(entry.journal.publicHost, entry.journal.publicPort, entry.prepared.targetPort()).original();
                    } catch (Throwable failure) { entry.parseFailure = failure; }
                }
                if (entry.parseFailure != null) throw new IllegalStateException(message(entry.parseFailure), entry.parseFailure);
            }
            return document;
        });
    }

    private void watch(Entry entry) {
        synchronized (this) {
            if (closed) return;
            Journal journal = entry.journal;
            if ((journal == null || inactive(journal.phase)) && entry.listeners.isEmpty()) {
                unwatch(entry);
                return;
            }
            String path = journal == null ? entry.configPath : journal.path;
            if (path.isBlank()) return;
            if (entry.fileWatch != null && !path.equals(entry.watchedPath)) unwatch(entry);
            watchNetworkFiles(entry);
            if (entry.fileWatch != null || entry.listeners.isEmpty() && !"ACTIVE".equals(journal.phase) && !syncing(journal.phase) && !moving(journal.phase)) return;
            long generation = ++entry.watchGeneration;
            entry.watchedPath = path;
            entry.fileWatch = access.watch(integration(entry), path, () -> {
                synchronized (PluginForwarding.this) {
                    if (entry.watchGeneration != generation) return;
                }
                if (entry.journal == null || inactive(entry.journal.phase)) access.observe(integration(entry), path).thenAccept(value -> document(entry, value, generation));
                else changed(path);
            });
            if (journal != null && !inactive(journal.phase)) queued(entry, false);
            else access.observe(integration(entry), path).thenAccept(value -> document(entry, value, generation));
        }
    }

    private void unwatch(Entry entry) {
        AutoCloseable current = entry.fileWatch;
        entry.fileWatch = null;
        entry.watchedPath = "";
        entry.watchGeneration++;
        entry.networkWatches.values().forEach(PluginForwarding::close);
        entry.networkWatches.clear();
        entry.networkObserved.clear();
        if (current == null) return;
        try { current.close(); }
        catch (Exception failure) { entry.error = "Could Not Stop Watching This Plugin Configuration"; }
    }

    private void watchNetworkFiles(Entry entry) {
        Journal journal = entry.journal;
        Set<String> paths = journal == null || inactive(journal.phase) ? Set.of() : journal.auxiliary.keySet();
        for (String path : List.copyOf(entry.networkWatches.keySet())) if (!paths.contains(path)) {
            close(entry.networkWatches.remove(path));
            entry.networkObserved.remove(path);
        }
        for (String path : paths) {
            entry.networkWatches.computeIfAbsent(path, value -> access.watch(integration(entry), value, () -> networkDocument(entry, value)));
            Auxiliary edit = journal.auxiliary.get(path);
            if (entry.networkObserved.put(path, edit) != edit) networkDocument(entry, path);
        }
    }

    private void networkDocument(Entry entry, String path) {
        Journal journal;
        Auxiliary edit;
        long observation;
        synchronized (this) {
            journal = entry.journal;
            edit = journal == null ? null : journal.auxiliary.get(path);
            if (closed || edit == null || inactive(journal.phase)) return;
            observation = ++edit.observation;
            edit.verified = false;
            journal.networkReady = false;
        }
        announce(entry);
        access.observe(integration(entry), path).whenComplete((document, failure) -> {
            synchronized (PluginForwarding.this) {
                if (closed || entry.journal != journal || journal.auxiliary.get(path) != edit || observation != edit.observation) return;
                edit.verified = failure == null && document.exists() && document.content().equals(edit.content);
                journal.networkReady = !journal.networkPending && journal.auxiliary.values().stream().allMatch(value -> value.applied && value.verified);
                if (edit.applied && !edit.verified) entry.error = "Network Plugin Settings Were Edited. Their Manual Values Were Preserved";
                else if (journal.networkReady && entry.error.equals("Network Plugin Settings Were Edited. Their Manual Values Were Preserved")) entry.error = "";
            }
            announce(entry);
        });
    }

    private static void close(AutoCloseable value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) { }
    }

    private Async<Void> reconcile(Entry entry, boolean force) {
        clearStop(entry);
        Journal journal = entry.journal;
        if (journal == null || inactive(journal.phase)) return Async.completed(null);
        if (syncing(journal.phase)) return sync(entry).thenCompose(ignored -> reconcile(entry, force));
        if (moving(journal.phase)) return move(entry).thenCompose(ignored -> reconcile(entry, force));
        if (!"ACTIVE".equals(journal.phase)) return Async.failed(new IllegalStateException("Finish The Previous Plugin Connection Change First"));
        watch(entry);
        return observe(entry).thenCompose(document -> {
            if (!local(entry)) {
                if (entry.prepared.targetPort() == journal.targetPort) return Async.completed(null);
                return running().thenCompose(active -> {
                    if (active && integration(entry).requiresStopped()) {
                        awaitStop(entry);
                        throw entry.desired == null ? new IllegalStateException("Stop The Server Before Moving This Plugin To Its New Port. The Port Updates Automatically After It Stops") : stop(entry, entry.desired);
                    }
                    ReProxyIntegrations.Change values = entry.prepared.connect(journal.publicHost, journal.publicPort, entry.prepared.targetPort());
                    requireLayout(journal, values);
                    journal.movePort = entry.prepared.targetPort();
                    journal.source = document;
                    journal.config = document.content();
                    journal.currentValues = values.original();
                    journal.publicValues = values.desired();
                    journal.moveOwned = journal.desiredValues;
                    journal.previousPort = journal.port;
                    journal.nextPort = null;
                    journal.moveAttempt++;
                    journal.sequence++;
                    journal.phase = "MOVE_REQUESTED";
                    return save(entry).thenCompose(ignored -> move(entry));
                });
            }
            return settled(entry).thenCompose(connection -> {
                Endpoint endpoint = connection.endpoints().stream().filter(value -> journal.endpoint.equals(value.id())).findFirst().orElse(null);
                if (endpoint == null) {
                    if (force) return running().thenCompose(active -> {
                        if (active && integration(entry).requiresStopped()) throw entry.desired == null ? new IllegalStateException("Stop The Server And Reconnect This Plugin. Its Linked Port Was Removed") : stop(entry, entry.desired);
                        return repair(entry, connection);
                    });
                    throw new IllegalStateException("The Linked Plugin Port Was Removed. Stop The Server And Reconnect");
                }
                requireOwned(entry, connection, endpoint);
                if (entry.prepared.targetPort() != endpoint.target().port()) return beginSync(entry, connection, endpoint, document).thenCompose(ignored -> reconcile(entry, force));
                if (linked(entry)) return Async.completed(null);
                return repairAddress(entry, connection, endpoint, document);
            });
        });
    }

    private Async<Void> repair(Entry entry, Connection connection) {
        Endpoint endpoint = connection.endpoints().stream().filter(value -> entry.journal.endpoint.equals(value.id())).findFirst().orElse(null);
        if (endpoint == null) return disconnect(entry).thenCompose(ignored -> prepare(entry));
        requireOwned(entry, connection, endpoint);
        return observe(entry).thenCompose(document -> entry.prepared.targetPort() != endpoint.target().port()
                ? beginSync(entry, connection, endpoint, document).thenCompose(ignored -> reconcile(entry, true))
                : repairAddress(entry, connection, endpoint, document));
    }

    private record Patch(String content, Map<String, ReProxyIntegrations.Value> owned, Map<String, ReProxyIntegrations.Value> current,
                         Map<String, ReProxyIntegrations.Value> expected) {
    }

    private Patch patch(Entry entry, Document document, String host, int port, int targetPort) {
        Journal journal = entry.journal;
        ReProxyIntegrations.Prepared prepared = ReProxyIntegrations.prepare(integration(entry), journal.path, document.content(), access.serverPort());
        ReProxyIntegrations.Change goal = prepared.connect(host, port, targetPort);
        requireLayout(journal, goal);
        String content = ReProxyIntegrations.restore(integration(entry), document.content(), goal.desired(), journal.desiredValues);
        Map<String, ReProxyIntegrations.Value> current = ReProxyIntegrations.prepare(integration(entry), journal.path, content, access.serverPort())
                .connect(host, port, targetPort).original();
        if (!current.keySet().equals(journal.desiredValues.keySet())) throw layoutChanged();
        Map<String, ReProxyIntegrations.Value> owned = new LinkedHashMap<>(journal.desiredValues);
        for (String key : owned.keySet()) if (goal.original().get(key).equals(journal.desiredValues.get(key))) owned.put(key, current.get(key));
        return new Patch(content, Map.copyOf(owned), current, goal.desired());
    }

    private static void requireLayout(Journal journal, ReProxyIntegrations.Change change) {
        if (!change.original().keySet().equals(journal.desiredValues.keySet()) || !change.desired().keySet().equals(journal.desiredValues.keySet())) throw layoutChanged();
    }

    private static IllegalStateException layoutChanged() {
        return new IllegalStateException("The Plugin Configuration Layout Changed. Disconnect And Reconnect To Use Its New Settings");
    }

    private Async<Void> repairAddress(Entry entry, Connection connection, Endpoint endpoint, Document document) {
        Journal journal = entry.journal;
        Patch patch = patch(entry, document, endpoint.publicHost(), endpoint.publicPort(), endpoint.target().port());
        boolean write = !patch.content().equals(document.content());
        return running().thenCompose(active -> {
            if (active && write && integration(entry).requiresStopped()) throw entry.desired == null ? new IllegalStateException("Stop The Server To Update This Plugin's Public Address") : stop(entry, entry.desired);
            journal.binding = connection.binding().id();
            journal.targetPort = endpoint.target().port();
            journal.publicHost = endpoint.publicHost();
            journal.publicPort = endpoint.publicPort();
            journal.address = address(entry, journal.publicHost, journal.publicPort);
            journal.source = document;
            journal.config = patch.content();
            journal.desiredValues = patch.owned();
            journal.currentValues = patch.current();
            journal.publicValues = patch.expected();
            journal.applyAttempt++;
            journal.sequence++;
            journal.phase = write ? "CONFIG_READY" : "ACTIVE";
            return save(entry).thenCompose(ignored -> write ? apply(entry) : Async.completed(null)).thenCompose(ignored ->
                    patch.current().equals(patch.expected()) ? Async.completed(null)
                            : Async.failed(new IllegalStateException("The Plugin's Public Settings Were Edited. Its Manual Values Were Preserved")));
        });
    }

    private String address(Entry entry, String host, int port) {
        return ReProxyIntegrations.publicAddress(integration(entry), host, port, entry.journal.targetPort);
    }

    private void requireOwned(Entry entry, Connection connection, Endpoint endpoint) {
        if (!integration(entry).name().equals(endpoint.name()) || !integration(entry).protocol().equals(endpoint.protocol()) || !"DEDICATED".equals(endpoint.routingMode())
                || endpoint.target() == null || !connection.binding().id().equals(endpoint.target().bindingId()) || !endpoint.enabled()
                || endpoint.publicHost() == null || endpoint.publicHost().isBlank() || endpoint.publicPort() < 1) {
            throw new IllegalStateException("The Linked Plugin Port Was Edited. Its Manual Settings Were Preserved");
        }
    }

    private Async<Void> beginSync(Entry entry, Connection connection, Endpoint endpoint, Document source) {
        return running().thenCompose(active -> {
            if (active && integration(entry).requiresStopped()) {
                awaitStop(entry);
                throw entry.desired == null ? new IllegalStateException("Stop The Server To Update This Plugin Port. It Resumes Automatically After The Server Stops") : stop(entry, entry.desired);
            }
            return beginSyncStopped(entry, connection, endpoint, source);
        });
    }

    private Async<Void> beginSyncStopped(Entry entry, Connection connection, Endpoint endpoint, Document source) {
        requireAuthority(entry);
        Journal journal = entry.journal;
        ReProxyIntegrations.Change values = entry.prepared.connect(endpoint.publicHost(), endpoint.publicPort(), entry.prepared.targetPort());
        requireLayout(journal, values);
        journal.syncPort = endpoint.target().port();
        journal.targetPort = entry.prepared.targetPort();
        journal.binding = connection.binding().id();
        journal.publicHost = endpoint.publicHost();
        journal.publicPort = endpoint.publicPort();
        journal.address = address(entry, journal.publicHost, journal.publicPort);
        journal.syncPolicy = Objects.requireNonNull(endpoint.publicPortPolicy(), "The Linked Port Settings Are Unavailable");
        journal.source = source;
        journal.config = source.content();
        journal.currentValues = values.original();
        journal.publicValues = values.desired();
        journal.syncAttempt++;
        journal.sequence++;
        journal.phase = "SYNC_PREPARED";
        return save(entry).thenCompose(ignored -> sync(entry));
    }

    private Async<Void> sync(Entry entry) {
        Journal journal = entry.journal;
        return ReProxyManager.serverChange(access.localTarget(), journal.connection, (api, connection) -> {
            requireClient(entry, api);
            requireConnection(connection, journal.connection);
            Endpoint endpoint = connection.endpoints().stream().filter(value -> journal.endpoint.equals(value.id())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("The Linked Plugin Port Was Removed. Reconnect This Plugin"));
            requireOwned(entry, connection, endpoint);
            if (!journal.binding.equals(connection.binding().id()) || !journal.publicHost.equals(endpoint.publicHost()) || journal.publicPort != endpoint.publicPort()
                    || !journal.syncPolicy.equals(endpoint.publicPortPolicy())) throw new IllegalStateException("The Linked Public Port Changed. Reconnect This Plugin");
            if ("SYNC_PREPARED".equals(journal.phase)) {
                journal.syncRevision = connection.revision();
                journal.phase = "SYNC_REQUESTED";
                return save(entry).thenCompose(ignored -> requestSync(entry, api));
            }
            return requestSync(entry, api);
        }).thenCompose(operation -> settled(entry)).thenCompose(connection -> finishSync(entry, connection)).exceptionallyCompose(failure -> {
            if ("SYNC_REQUESTED".equals(journal.phase) && revisionFailure(failure)) return reconcileSync(entry, failure);
            if (!"SYNC_PENDING".equals(journal.phase)) return Async.failed(failure);
            return terminal(entry).thenCompose(terminal -> terminal ? reconcileSync(entry, failure) : Async.failed(failure));
        });
    }

    private Async<Operation> requestSync(Entry entry, ReProxyClient api) {
        requireAuthority(entry);
        Journal journal = entry.journal;
        EndpointSpec spec = new EndpointSpec(journal.endpoint, integration(entry).name(), integration(entry).protocol(), "DEDICATED",
                new Target(journal.binding, journal.targetPort), journal.syncPolicy, true);
        Async<Operation> request = "SYNC_PENDING".equals(journal.phase) ? api.operation(journal.operation)
                : api.updateEndpoint(journal.connection, journal.endpoint, spec, journal.syncRevision, operation(journal, (6L << 32) ^ journal.syncAttempt));
        return request.thenCompose(receipt -> {
            journal.operation = receipt.id();
            journal.phase = "SYNC_PENDING";
            return save(entry).thenApply(ignored -> receipt);
        });
    }

    private Async<Void> finishSync(Entry entry, Connection connection) {
        Journal journal = entry.journal;
        Endpoint endpoint = connection.endpoints().stream().filter(value -> journal.endpoint.equals(value.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The Linked Plugin Port Was Removed. Reconnect This Plugin"));
        requireEndpoint(entry, connection, endpoint);
        if (!journal.publicHost.equals(endpoint.publicHost()) || journal.publicPort != endpoint.publicPort()) throw new IllegalStateException("The Public Port Changed While Updating This Plugin");
        journal.phase = "ACTIVE";
        return save(entry);
    }

    private Async<Void> reconcileSync(Entry entry, Throwable failure) {
        Journal journal = entry.journal;
        return settled(entry).thenCompose(connection -> {
            Endpoint endpoint = connection.endpoints().stream().filter(value -> journal.endpoint.equals(value.id())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("The Linked Plugin Port Was Removed. Reconnect This Plugin"));
            requireOwned(entry, connection, endpoint);
            if (endpoint.target().port() == journal.targetPort) return finishSync(entry, connection);
            if (!journal.binding.equals(connection.binding().id()) || endpoint.target().port() != journal.syncPort
                    || !journal.syncPolicy.equals(endpoint.publicPortPolicy()) || journal.publicPort != endpoint.publicPort() || !journal.publicHost.equals(endpoint.publicHost())) {
                throw new IllegalStateException("The Linked Plugin Port Was Edited. Its Manual Settings Were Preserved");
            }
            if (entry.endpointRetries++ >= 2) return Async.failed(failure);
            journal.syncRevision = connection.revision();
            journal.syncAttempt++;
            journal.sequence++;
            journal.phase = "SYNC_REQUESTED";
            return save(entry).thenCompose(ignored -> sync(entry));
        });
    }

    private Async<Void> move(Entry entry) {
        requireAuthority(entry);
        return running().thenCompose(active -> {
            if (active && integration(entry).requiresStopped()) {
                awaitStop(entry);
                throw entry.desired == null ? new IllegalStateException("Stop The Server To Finish This Plugin Port Move. It Resumes Automatically After The Server Stops") : stop(entry, entry.desired);
            }
            clearStop(entry);
            return moveStopped(entry);
        });
    }

    private Async<Void> moveStopped(Entry entry) {
        Journal journal = entry.journal;
        if ("MOVE_REQUESTED".equals(journal.phase)) return access.reserveExact(integration(entry), journal.movePort, operation(journal, (7L << 32) ^ journal.moveAttempt))
                .exceptionallyCompose(failure -> failure instanceof ReservationRejected ? cancelMove(entry, failure).thenCompose(ignored -> Async.failed(failure)) : Async.failed(failure))
                .thenCompose(port -> {
            if (!port.operationId().equals(operation(journal, (7L << 32) ^ journal.moveAttempt))) throw new IllegalStateException("The New Port Does Not Belong To This Plugin Change");
            journal.nextPort = port;
            journal.phase = "MOVE_ALLOCATED";
            return save(entry).thenCompose(ignored -> move(entry));
        });
        if ("MOVE_ALLOCATED".equals(journal.phase)) {
            if (journal.nextPort.port() != journal.movePort) return releaseMove(entry, "The Provider Could Not Match The Plugin's Edited Port. Configure It In Server Ports");
            return observe(entry).thenCompose(document -> {
                if (!document.equals(journal.source)) return releaseMove(entry, "The Plugin Configuration Changed While Preparing Its New Port. Retry With Its Current Settings");
                Patch patch = patch(entry, document, journal.nextPort.host(), journal.nextPort.port(), journal.movePort);
                if (!patch.current().equals(patch.expected())) return releaseMove(entry, "The Plugin's Public Settings Were Edited. Its Manual Values Were Preserved");
                journal.config = patch.content();
                journal.desiredValues = patch.owned();
                journal.currentValues = patch.current();
                journal.publicValues = patch.expected();
                journal.phase = "MOVE_CONFIG_READY";
                journal.applyAttempt++;
                journal.sequence++;
                return save(entry).thenCompose(ignored -> move(entry));
            });
        }
        if ("MOVE_CONFIG_READY".equals(journal.phase)) return running().thenCompose(active -> {
            if (active && integration(entry).requiresStopped()) {
                awaitStop(entry);
                throw entry.desired == null ? new IllegalStateException("Stop The Server To Update This Plugin's Public Address. It Resumes Automatically After The Server Stops") : stop(entry, entry.desired);
            }
            return access.observe(integration(entry), journal.path);
        }).thenCompose(document -> {
            if (document.content().equals(journal.config)) return Async.completed(document);
            if (!document.equals(journal.source)) return releaseMove(entry, "The Plugin Configuration Changed Before Its New Public Address Was Saved").thenApply(ignored -> (Document) null);
            return mutate(entry, journal.path, journal.source, journal.config, applyOperation(journal));
        }).thenCompose(document -> {
            if (document == null) return Async.completed(null);
            document(entry, document);
            if (!document.exists() || !document.content().equals(journal.config)) return releaseMove(entry, "The Plugin Configuration Changed While Saving Its New Public Address");
            journal.port = journal.nextPort;
            journal.targetPort = journal.movePort;
            journal.publicHost = journal.port.host();
            journal.publicPort = journal.port.port();
            journal.address = address(entry, journal.publicHost, journal.publicPort);
            journal.phase = "MOVE_APPLIED";
            return save(entry).thenCompose(ignored -> move(entry));
        });
        if ("MOVE_APPLIED".equals(journal.phase)) return access.release(integration(entry), journal.previousPort).thenCompose(ignored -> {
            journal.previousPort = null;
            journal.nextPort = null;
            journal.phase = "ACTIVE";
            journal.sequence++;
            return save(entry);
        });
        if ("MOVE_ROLLBACK".equals(journal.phase)) return rollbackMove(entry);
        return Async.failed(new IllegalStateException("The Plugin Port Move Must Be Finished First"));
    }

    private Async<Void> releaseMove(Entry entry, String hint) {
        Journal journal = entry.journal;
        requireAuthority(entry);
        return access.observe(integration(entry), journal.path).thenCompose(document -> {
            if (!document.exists()) throw new IllegalStateException("Restore The Plugin Configuration Before Releasing Its New Port");
            journal.restoreExpected = document;
            journal.restoreContent = ReProxyIntegrations.restore(integration(entry), document.content(), journal.moveOwned, journal.desiredValues);
            journal.moveHint = hint;
            journal.restoreAttempt++;
            journal.sequence++;
            journal.phase = "MOVE_ROLLBACK";
            return save(entry).thenCompose(ignored -> rollbackMove(entry));
        });
    }

    private Async<Void> cancelMove(Entry entry, Throwable failure) {
        Journal journal = entry.journal;
        requireAuthority(entry);
        return access.observe(integration(entry), journal.path).thenCompose(document -> {
            if (!document.exists()) throw new IllegalStateException("Restore The Plugin Configuration Before Finishing This Port Change", failure);
            journal.source = document;
            journal.config = document.content();
            ReProxyIntegrations.Change values = ReProxyIntegrations.prepare(integration(entry), journal.path, document.content(), access.serverPort())
                    .connect(journal.publicHost, journal.publicPort, journal.targetPort);
            journal.currentValues = values.original();
            journal.publicValues = values.desired();
            journal.previousPort = null;
            journal.nextPort = null;
            journal.phase = "ACTIVE";
            journal.sequence++;
            document(entry, document);
            return save(entry);
        });
    }

    private Async<Void> rollbackMove(Entry entry) {
        Journal journal = entry.journal;
        requireAuthority(entry);
        return access.observe(integration(entry), journal.path).thenCompose(document -> {
            if (!document.exists()) throw new IllegalStateException("Restore The Plugin Configuration Before Releasing Its New Port");
            if (document.content().equals(journal.restoreContent)) return Async.completed(document);
            if (!document.equals(journal.restoreExpected)) {
                if (++entry.restores > 2) throw new IllegalStateException("The Plugin Configuration Keeps Changing. Retry To Finish Releasing Its New Port");
                return releaseMove(entry, journal.moveHint).thenApply(ignored -> (Document) null);
            }
            return running().thenCompose(active -> {
                if (active && integration(entry).requiresStopped()) {
                    awaitStop(entry);
                    throw entry.desired == null ? new IllegalStateException("Stop The Server To Restore This Plugin's Previous Public Address. It Resumes Automatically After The Server Stops") : stop(entry, entry.desired);
                }
                requireAuthority(entry);
                return mutate(entry, journal.path, journal.restoreExpected, journal.restoreContent, restoreOperation(journal));
            });
        }).thenCompose(document -> {
            if (document == null) return Async.completed(null);
            document(entry, document);
            if (!document.content().equals(journal.restoreContent)) throw new IllegalStateException("The Plugin Configuration Changed While Restoring Its Previous Public Address. Retry To Finish Safely");
            requireAuthority(entry);
            return access.release(integration(entry), journal.nextPort).thenCompose(ignored -> {
                journal.port = journal.previousPort;
                journal.publicHost = journal.port.host();
                journal.publicPort = journal.port.port();
                journal.targetPort = journal.port.port();
                journal.address = address(entry, journal.publicHost, journal.publicPort);
                journal.desiredValues = journal.moveOwned;
                journal.source = document;
                journal.config = document.content();
                ReProxyIntegrations.Change values = ReProxyIntegrations.prepare(integration(entry), journal.path, document.content(), access.serverPort())
                        .connect(journal.publicHost, journal.publicPort, journal.targetPort);
                journal.currentValues = values.original();
                journal.publicValues = values.desired();
                journal.previousPort = null;
                journal.nextPort = null;
                journal.phase = "ACTIVE";
                journal.sequence++;
                String hint = journal.moveHint;
                journal.moveHint = "";
                return save(entry).thenCompose(saved -> Async.failed(new MoveRolledBack(hint)));
            });
        });
    }

    private synchronized void admitted(Connection connection) {
        if (closed) return;
        canonical = connection;
        if (connection == null) integrations.values().forEach(this::unwatch);
    }

    private synchronized void awaitStop(Entry entry) {
        if (closed) return;
        requireAuthority(entry);
        entry.waitingStop = true;
        ensureStopWatch();
    }

    private synchronized void ensureStopWatch() {
        if (closed) return;
        if (stopWatch != null) return;
        long generation = ++stopGeneration;
        TaskScheduler.ScheduledTask task = TaskSchedulers.current().scheduleAtFixedRate(() -> pollStop(generation), Duration.ofSeconds(4), Duration.ofSeconds(4));
        if (closed || generation != stopGeneration || integrations.values().stream().noneMatch(value -> value.waitingStop || value.desired != null)) task.cancel();
        else stopWatch = task;
    }

    private synchronized void clearStop(Entry entry) {
        entry.waitingStop = false;
        if (integrations.values().stream().noneMatch(value -> value.waitingStop || value.desired != null)) cancelStop();
    }

    private void pollStop(long generation) {
        List<Entry> waiting;
        synchronized (this) {
            if (closed || generation != stopGeneration || checkingStop) return;
            waiting = integrations.values().stream().filter(entry -> entry.waitingStop || entry.desired != null && !entry.loading && !entry.busy).toList();
            if (waiting.isEmpty()) {
                if (integrations.values().stream().noneMatch(entry -> entry.waitingStop || entry.desired != null)) cancelStop();
                return;
            }
            checkingStop = true;
        }
        Async.supply(() -> {
            return running();
        }).thenCompose(value -> value).whenComplete((active, failure) -> {
            List<Entry> resumed = new ArrayList<>();
            synchronized (PluginForwarding.this) {
                checkingStop = false;
                if (closed || generation != stopGeneration) return;
                for (Entry entry : waiting) {
                    if (entry.waitingStop && (failure != null || !Boolean.TRUE.equals(active))) {
                        entry.waitingStop = false;
                        if (failure != null) entry.error = message(failure);
                        else resumed.add(entry);
                    }
                }
                if (integrations.values().stream().noneMatch(entry -> entry.waitingStop || entry.desired != null)) cancelStop();
            }
            for (Entry entry : waiting) {
                if (entry.desired == null) continue;
                if (failure == null) resume(entry, active);
                else failDesired(entry, failure);
            }
            resumed.forEach(entry -> queued(entry, false));
        });
    }

    private void failDesired(Entry entry, Throwable failure) {
        Desired desired;
        synchronized (this) {
            desired = entry.desired;
            if (desired == null || entry.busy) return;
            entry.desired = null;
            entry.resourceKey = "";
            entry.resourceStamp = "";
            entry.error = message(failure);
            if (integrations.values().stream().noneMatch(value -> value.waitingStop || value.desired != null)) cancelStop();
        }
        desired.result.fail(failure);
        announce(entry);
        drain(entry);
    }

    private synchronized void cancelStop() {
        stopGeneration++;
        if (stopWatch != null) stopWatch.cancel();
        stopWatch = null;
    }

    private void settle(Desired desired, boolean pipeline) {
        AutoCloseable lease = null;
        synchronized (this) {
            if (pipeline) desired.pipelines--;
            else desired.settled = true;
            if (desired.settled && desired.pipelines == 0) {
                lease = desired.lease;
                desired.lease = null;
            }
        }
        close(lease);
    }

    private synchronized boolean linked(Entry entry) {
        Journal journal = entry.journal;
        if (entry.parseFailure != null || entry.prepared != null && entry.prepared.targetPort() != journal.targetPort
                || !entry.actualValues.isEmpty() && !entry.actualValues.equals(journal.publicValues)) return false;
        if (!local(entry)) return journal.port != null && journal.targetPort == journal.port.port() && journal.publicHost.equals(journal.port.host()) && journal.publicPort == journal.port.port();
        Connection connection = canonical;
        if (connection == null || !journal.connection.equals(connection.id()) || connection.binding() == null
                || !journal.binding.equals(connection.binding().id()) || connection.pendingOperationId() != null && !connection.pendingOperationId().isBlank()) return false;
        return connection.endpoints().stream().anyMatch(endpoint -> journal.endpoint.equals(endpoint.id()) && integration(entry).protocol().equals(endpoint.protocol())
                && "DEDICATED".equals(endpoint.routingMode()) && endpoint.enabled() && endpoint.target() != null
                && journal.binding.equals(endpoint.target().bindingId()) && journal.targetPort == endpoint.target().port()
                && journal.publicHost.equals(endpoint.publicHost()) && journal.publicPort == endpoint.publicPort());
    }

    private Async<Connection> settled(Entry entry) {
        requireAuthority(entry);
        return ReProxyManager.serverReady(access.localTarget(), entry.journal.connection).thenApply(connection -> {
            requireAuthority(entry);
            requireConnection(connection, entry.journal.connection);
            admitted(connection);
            return connection;
        });
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancelStop();
        integrations.values().forEach(entry -> {
            entry.noticeGeneration++;
            if (entry.notification != null) entry.notification.cancel();
            entry.notification = null;
            entry.waitingStop = false;
            if (entry.desired != null) entry.desired.result.fail(new Async.Cancellation());
            entry.desired = null;
            unwatch(entry);
            if (entry.change != null) entry.change.fail(new Async.Cancellation());
            entry.change = null;
        });
        if (watch != null) {
            try { watch.close(); }
            catch (Exception failure) { throw new IllegalStateException("Could Not Stop Watching The Server Connection", failure); }
            finally { watch = null; }
        }
        canonical = null;
    }

    private Async<Document> mutate(Entry entry, String path, Document expected, String content, String operation) {
        return access.mutate(integration(entry), path, expected, content, operation).thenApply(document -> {
            if (!Objects.equals(expected.content(), content)) {
                entry.refreshNeeded = true;
                entry.notice = "Settings Are Saved. Restart The Server To Apply Them";
            }
            return document;
        });
    }

    private Async<Void> refresh(Entry entry) {
        if (!entry.refreshNeeded) return Async.completed(null);
        entry.refreshNeeded = false;
        ReProxyIntegrations.Integration integration = integration(entry);
        entry.notice = "Settings Are Saved. Restart The Server To Apply Them";
        String authority = access.authorityStamp();
        return running().thenCompose(active -> {
            if (!active) {
                entry.notice = "Settings Are Saved. Start The Server When You Are Ready";
                return Async.completed(null);
            }
            if (integration.reloadCommands().isEmpty()) return Async.completed(null);
            if (closed || !authority.equals(access.authorityStamp())) throw new Async.Cancellation();
            if (entry.desired != null) requireDesired(entry, entry.desired);
            Journal journal = entry.journal;
            boolean newPort = !local(entry) && "ACTIVE".equals(journal.phase) && journal.port != null && journal.port.created()
                    && ReProxyIntegrations.prepare(integration, journal.path, journal.source.content(), access.serverPort()).targetPort() != journal.targetPort;
            return access.observe(integration, journal.path).thenCompose(document -> {
                if (closed || !authority.equals(access.authorityStamp())) throw new Async.Cancellation();
                if (entry.desired != null) requireDesired(entry, entry.desired);
                if (!document.equals(entry.observed)) throw new IllegalStateException("Plugin Settings Changed Before Reload");
                return access.reload(integration, journal.path, document);
            }).thenRun(() -> entry.notice = newPort
                    ? "Settings Are Saved. Plugin Reload Commands Were Sent. Restart The Server To Open Its New Port"
                    : "Settings Are Saved. Plugin Reload Commands Were Sent; Check The Console For The Result");
        }).exceptionally(failure -> {
            entry.notice = "Settings Are Saved. Restart The Server Or Reload The Plugin Manually. " + message(failure);
            return null;
        });
    }

    private Async<Boolean> running() {
        return Async.supply(access::running).thenCompose(value -> value);
    }

    private Async<Void> read(Entry entry) {
        return access.observe(entry.integration, entry.sidecar).thenAccept(document -> {
            Journal journal = document.exists() ? decode(entry, document.content()) : null;
            synchronized (this) {
                if (closed) throw new Async.Cancellation();
                entry.document = document;
                entry.journal = journal;
                if ((journal == null || inactive(journal.phase)) && entry.listeners.isEmpty()) unwatch(entry);
            }
        });
    }

    private Async<Void> prepare(Entry entry) {
        Async<Void> check = "plasmovoice".equals(integration(entry).id()) ? access.secretFiles() : Async.completed(null);
        return check.thenCompose(ignored -> configuration(entry, 0)).thenCompose(found -> {
            Journal journal = new Journal();
            journal.definition = integration(entry);
            journal.phase = "PREPARED";
            journal.authority = access.authorityStamp();
            journal.key = UUID.randomUUID().toString();
            journal.path = found.path();
            journal.original = found.document();
            journal.source = found.document();
            journal.targetPort = found.prepared().targetPort();
            journal.networkRoute = entry.networkRoute;
            journal.networkGroup = entry.networkGroup;
            journal.networkPending = !journal.networkGroup.isBlank();
            journal.networkReady = !journal.networkPending;
            if (journal.networkRoute != null) journal.targetPort = journal.networkRoute.port();
            if (local(entry)) {
                Connection connection = assigned();
                journal.connection = connection.id();
                journal.revision = connection.revision();
                journal.binding = connection.binding().id();
                journal.endpoint = UUID.randomUUID().toString();
            }
            entry.journal = journal;
            return save(entry).thenCompose(ignored -> connect(entry));
        });
    }

    private record Found(String path, Document document, ReProxyIntegrations.Prepared prepared) {
    }

    private Async<Found> configuration(Entry entry, int index) {
        ReProxyIntegrations.Integration integration = integration(entry);
        if (entry.pathPolicy != integration || entry.pathProxy != entry.proxy) {
            entry.paths = ReProxyIntegrations.paths(integration, entry.proxy);
            entry.pathPolicy = integration;
            entry.pathProxy = entry.proxy;
        }
        List<String> paths = entry.paths;
        if (index >= paths.size()) return Async.failed(new Waiting("Start The Server Once To Create " + integration.name() + " Settings, Then Stop It. We Will Continue Automatically"));
        String path = paths.get(index);
        return access.observe(integration, path).thenCompose(document -> document.exists() && !document.content().isBlank()
                ? Async.completed(new Found(path, document, ReProxyIntegrations.prepare(integration, path, document.content(), access.serverPort())))
                : configuration(entry, index + 1));
    }

    private Connection assigned() {
        ReProxyTarget target = access.localTarget();
        if (target == null || !"LOCAL".equals(target.binding().kind())) throw new IllegalStateException("Choose A Private Server");
        Connection connection = ReProxyManager.serverConnection(target);
        if (connection == null || !target.matches(connection.binding()) || connection.address() == null || connection.address().publicHost() == null
                || connection.address().publicHost().isBlank() || connection.endpoints().stream().noneMatch(endpoint -> "JAVA_HOSTNAME".equals(endpoint.routingMode()))) {
            throw new Waiting("Choose A Minecraft Address In Server Settings And We Will Continue");
        }
        return connection;
    }

    private Async<Void> connect(Entry entry) {
        Journal journal = entry.journal;
        return switch (journal.phase) {
            case "PREPARED" -> local(entry) ? addEndpoint(entry) : (journal.networkRoute == null
                    ? access.reserve(integration(entry), journal.targetPort, operation(journal, 1))
                    : Async.completed(new Port(journal.networkRoute.host(), journal.networkRoute.port(), "network:" + access.serverKey(), false, operation(journal, 1))))
                    .thenCompose(port -> {
                        if (!port.operationId().equals(operation(journal, 1))) throw new IllegalStateException("The Allocated Port Does Not Belong To This Connection");
                        journal.port = port;
                        journal.phase = "ALLOCATED";
                        return save(entry).thenCompose(ignored -> connect(entry));
                    }).exceptionallyCompose(failure -> failure instanceof ReservationRejected && "PREPARED".equals(journal.phase)
                            ? abandon(entry, message(failure)) : Async.failed(failure));
            case "ALLOCATED" -> ready(entry, journal.port.host(), journal.port.port(), journal.port.port());
            case "ENDPOINT_REQUESTED", "ENDPOINT_PENDING" -> addEndpoint(entry);
            case "CONFIG_READY" -> apply(entry);
            case "ACTIVE" -> Async.completed(null);
            case "SYNC_PREPARED", "SYNC_REQUESTED", "SYNC_PENDING" -> sync(entry);
            case "MOVE_REQUESTED", "MOVE_ALLOCATED", "MOVE_CONFIG_READY", "MOVE_APPLIED", "MOVE_ROLLBACK" -> move(entry);
            default -> Async.failed(new IllegalStateException("The Previous Connection Change Must Be Finished First"));
        };
    }

    private Async<Void> addEndpoint(Entry entry) {
        Journal journal = entry.journal;
        return ReProxyManager.serverChange(access.localTarget(), journal.connection, (api, connection) -> {
            requireClient(entry, api);
            requireConnection(connection, journal.connection);
            if (!journal.binding.equals(connection.binding().id())) throw new IllegalStateException("The Server Connection Target Changed");
            if ("PREPARED".equals(journal.phase)) {
                journal.revision = connection.revision();
                journal.phase = "ENDPOINT_REQUESTED";
                return save(entry).thenCompose(ignored -> requestEndpoint(entry, api));
            }
            return requestEndpoint(entry, api);
        }).thenCompose(operation -> settled(entry)).thenCompose(connection -> configured(entry, connection)).exceptionallyCompose(failure -> {
            if ("ENDPOINT_REQUESTED".equals(journal.phase) && revisionFailure(failure)) return reconcileAdd(entry, failure);
            if (!"ENDPOINT_PENDING".equals(journal.phase)) return Async.failed(failure);
            return terminal(entry).thenCompose(terminal -> terminal ? reconcileAdd(entry, failure) : Async.failed(failure));
        });
    }

    private Async<Operation> requestEndpoint(Entry entry, ReProxyClient api) {
        requireAuthority(entry);
        Journal journal = entry.journal;
        Async<Operation> request;
        if ("ENDPOINT_PENDING".equals(journal.phase)) request = api.operation(journal.operation);
        else {
            EndpointSpec spec = new EndpointSpec(journal.endpoint, integration(entry).name(), integration(entry).protocol(), "DEDICATED",
                    new Target(journal.binding, journal.targetPort), PublicPort.auto(), true);
            request = api.addEndpoint(journal.connection, spec, journal.revision, operation(journal, (2L << 32) ^ journal.endpointAttempt));
        }
        return request.thenCompose(receipt -> {
            journal.operation = receipt.id();
            journal.phase = "ENDPOINT_PENDING";
            return save(entry).thenApply(ignored -> receipt);
        });
    }

    private Async<Boolean> terminal(Entry entry) {
        Journal journal = entry.journal;
        String id = journal.operation;
        return ReProxyManager.serverRead(api -> { requireClient(entry, api); return api.operation(id); }).thenApply(receipt ->
                receipt != null && id.equals(receipt.id()) && journal.connection.equals(receipt.resourceId()) && (receipt.failed() || receipt.succeeded()))
                .exceptionally(failure -> false);
    }

    private Async<Void> reconcileAdd(Entry entry, Throwable failure) {
        Journal journal = entry.journal;
        return settled(entry).thenCompose(connection -> {
            requireConnection(connection, journal.connection);
            if (!journal.binding.equals(connection.binding().id())) throw new IllegalStateException("The Server Connection Target Changed");
            Endpoint endpoint = connection.endpoints().stream().filter(value -> journal.endpoint.equals(value.id())).findFirst().orElse(null);
            if (endpoint != null) return configured(entry, connection);
            return access.observe(integration(entry), journal.path).thenCompose(document -> {
                if (!document.equals(journal.original)) return abandon(entry, "The Plugin Configuration Changed. Retry To Use Its Current Settings");
                if (entry.endpointRetries++ >= 2) return abandon(entry, message(failure) + ". Connection Setup Stopped. Retry To Connect Again");
                journal.revision = connection.revision();
                journal.phase = "ENDPOINT_REQUESTED";
                journal.endpointAttempt++;
                journal.sequence++;
                return save(entry).thenCompose(ignored -> addEndpoint(entry));
            });
        });
    }

    private Async<Void> abandon(Entry entry, String hint) {
        entry.journal.phase = "ABANDONED";
        entry.journal.address = "";
        return save(entry).thenCompose(ignored -> Async.failed(new IllegalStateException(hint)));
    }

    private Async<Void> configured(Entry entry, Connection connection) {
        Journal journal = entry.journal;
        requireConnection(connection, journal.connection);
        Endpoint endpoint = connection.endpoints().stream().filter(value -> journal.endpoint.equals(value.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The Plugin Port Could Not Be Identified Safely"));
        requireEndpoint(entry, connection, endpoint);
        if (!endpoint.enabled() || endpoint.publicPort() < 1 || endpoint.publicHost() == null || endpoint.publicHost().isBlank()) {
            throw entry.desired == null ? new IllegalStateException("The Plugin Port Is Still Preparing") : new Waiting("Waiting For The Plugin's Public Address To Be Ready");
        }
        return ready(entry, endpoint.publicHost(), endpoint.publicPort(), journal.targetPort);
    }

    private Async<Void> ready(Entry entry, String host, int publicPort, int targetPort) {
        Journal journal = entry.journal;
        ReProxyIntegrations.Prepared prepared = ReProxyIntegrations.prepare(integration(entry), journal.path, journal.original.content(), access.serverPort());
        ReProxyIntegrations.Change change = prepared.connect(host, publicPort, targetPort);
        journal.source = journal.original;
        journal.targetPort = targetPort;
        journal.config = change.content();
        journal.originalValues = change.original();
        journal.desiredValues = change.desired();
        journal.currentValues = change.desired();
        journal.publicValues = change.desired();
        journal.address = change.publicAddress();
        journal.publicHost = host;
        journal.publicPort = publicPort;
        journal.phase = "CONFIG_READY";
        return save(entry).thenCompose(ignored -> apply(entry));
    }

    private Async<Void> apply(Entry entry) {
        Journal journal = entry.journal;
        return running().thenCompose(running -> {
            if (Boolean.TRUE.equals(running) && integration(entry).requiresStopped()) throw entry.desired == null ? new IllegalStateException("Stop The Server Before Connecting " + integration(entry).name()) : stop(entry, entry.desired);
            return access.observe(integration(entry), journal.path);
        }).thenCompose(current -> {
            if (!current.exists()) throw new IllegalStateException("The Plugin Configuration Was Removed");
            if (current.content().equals(journal.config)) return Async.completed(current);
            if (!current.equals(journal.source)) {
                return beginRestore(entry, current).thenCompose(ignored -> disconnect(entry)).thenCompose(ignored ->
                        Async.<Document>failed(new IllegalStateException("The Configuration Changed. Connection Setup Was Rolled Back. Retry To Use Its Current Settings")));
            }
            requireAuthority(entry);
            return mutate(entry, journal.path, journal.source, journal.config, applyOperation(journal));
        }).thenCompose(document -> {
            document(entry, document);
            journal.phase = "ACTIVE";
            return save(entry);
        });
    }

    private Async<Void> disconnect(Entry entry) {
        Journal journal = entry.journal;
        if ("ACTIVE".equals(journal.phase) && !journal.auxiliary.isEmpty()) return auxiliary(entry, true).thenCompose(ignored -> disconnect(entry));
        if ("ACTIVE".equals(journal.phase)) return access.observe(integration(entry), journal.path).thenCompose(current ->
                beginRestore(entry, current).thenCompose(ignored -> disconnect(entry)));
        if ("RESTORE_REQUESTED".equals(journal.phase)) return running().thenCompose(running -> {
            if (Boolean.TRUE.equals(running) && integration(entry).requiresStopped()) throw entry.desired == null ? new IllegalStateException("Stop The Server Before Disconnecting " + integration(entry).name()) : stop(entry, entry.desired);
            return access.observe(integration(entry), journal.path);
        }).thenCompose(current -> {
            if (!current.exists()) throw new IllegalStateException("Restore The Plugin Configuration Before Disconnecting");
            if (current.content().equals(journal.restoreContent)) return Async.completed(current);
            if (!current.equals(journal.restoreExpected)) {
                if (++entry.restores > 2) throw new IllegalStateException("The Plugin Configuration Keeps Changing. Retry After Its Changes Finish");
                journal.restoreAttempt++;
                journal.sequence++;
                return beginRestore(entry, current).thenCompose(ignored -> disconnect(entry)).thenApply(ignored -> (Document) null);
            }
            requireAuthority(entry);
            return mutate(entry, journal.path, journal.restoreExpected, journal.restoreContent, restoreOperation(journal));
        }).thenCompose(document -> {
            if (document == null) return Async.completed(null);
            document(entry, document);
            journal.phase = "RESTORED";
            return save(entry).thenCompose(saved -> disconnect(entry));
        });
        if ("RESTORED".equals(journal.phase)) {
            if (local(entry)) return removeEndpoint(entry);
            requireAuthority(entry);
            Async<Void> release = journal.networkRoute == null ? access.release(integration(entry), journal.port) : Async.completed(null);
            return release.thenCompose(ignored -> {
                journal.phase = "REMOVED";
                return save(entry).thenCompose(saved -> disconnect(entry));
            });
        }
        if ("REMOVE_REQUESTED".equals(journal.phase) || "REMOVE_PENDING".equals(journal.phase)) return removeEndpoint(entry);
        if ("REMOVED".equals(journal.phase)) {
            journal.phase = "OFF";
            journal.address = "";
            return save(entry);
        }
        return Async.failed(new IllegalStateException("The Previous Connection Change Must Be Finished First"));
    }

    private Async<Void> beginRestore(Entry entry, Document current) {
        if (!current.exists()) return Async.failed(new IllegalStateException("Restore The Plugin Configuration Before Disconnecting"));
        Journal journal = entry.journal;
        journal.restoreExpected = current;
        journal.restoreContent = ReProxyIntegrations.restore(integration(entry), current.content(), journal.originalValues, journal.desiredValues);
        journal.phase = "RESTORE_REQUESTED";
        return save(entry);
    }

    private Async<Void> removeEndpoint(Entry entry) {
        Journal journal = entry.journal;
        return settled(entry).thenCompose(connection -> {
            requireConnection(connection, journal.connection);
            Endpoint endpoint = connection.endpoints().stream().filter(value -> journal.endpoint.equals(value.id())).findFirst().orElse(null);
            if (endpoint == null) {
                if (connection.pendingOperationId() != null && !connection.pendingOperationId().isBlank()) return Async.failed(new IllegalStateException("The Server Connection Did Not Settle Safely"));
                journal.phase = "REMOVED";
                return save(entry).thenCompose(ignored -> disconnect(entry));
            }
            requireEndpoint(entry, connection, endpoint);
            return ReProxyManager.serverChange(access.localTarget(), journal.connection, (api, current) -> {
                requireClient(entry, api);
                requireConnection(current, journal.connection);
                Endpoint owned = current.endpoints().stream().filter(value -> journal.endpoint.equals(value.id())).findFirst().orElse(null);
                if (owned == null) throw new IllegalStateException("The Plugin Port Was Removed. Retry To Finish Disconnecting");
                requireEndpoint(entry, current, owned);
                if ("RESTORED".equals(journal.phase)) {
                    journal.removeRevision = current.revision();
                    journal.phase = "REMOVE_REQUESTED";
                    return save(entry).thenCompose(ignored -> requestRemoval(entry, api));
                }
                return requestRemoval(entry, api);
            }).thenCompose(operation -> {
                journal.phase = "REMOVED";
                return save(entry).thenCompose(ignored -> disconnect(entry));
            });
        }).exceptionallyCompose(failure -> {
            if ("REMOVE_REQUESTED".equals(journal.phase) && revisionFailure(failure)) return reconcileRemove(entry, failure);
            if (!"REMOVE_PENDING".equals(journal.phase)) return Async.failed(failure);
            return terminal(entry).thenCompose(terminal -> terminal ? reconcileRemove(entry, failure) : Async.failed(failure));
        });
    }

    private Async<Operation> requestRemoval(Entry entry, ReProxyClient api) {
        requireAuthority(entry);
        Journal journal = entry.journal;
        Async<Operation> request = "REMOVE_PENDING".equals(journal.phase) ? api.operation(journal.operation)
                : api.deleteEndpoint(journal.connection, journal.endpoint, journal.removeRevision, operation(journal, (5L << 32) ^ journal.removeAttempt));
        return request.thenCompose(receipt -> {
            journal.operation = receipt.id();
            journal.phase = "REMOVE_PENDING";
            return save(entry).thenApply(ignored -> receipt);
        });
    }

    private Async<Void> reconcileRemove(Entry entry, Throwable failure) {
        Journal journal = entry.journal;
        return settled(entry).thenCompose(connection -> {
            requireConnection(connection, journal.connection);
            Endpoint endpoint = connection.endpoints().stream().filter(value -> journal.endpoint.equals(value.id())).findFirst().orElse(null);
            if (endpoint == null) {
                journal.phase = "REMOVED";
                return save(entry).thenCompose(ignored -> disconnect(entry));
            }
            requireEndpoint(entry, connection, endpoint);
            if (entry.removeRetries++ >= 2) return Async.failed(new IllegalStateException(message(failure) + ". The Plugin Port Could Not Be Removed. Retry To Finish Disconnecting"));
            return access.observe(integration(entry), journal.path).thenCompose(document -> {
                if (!document.exists() || !ReProxyIntegrations.restore(integration(entry), document.content(), journal.originalValues, journal.desiredValues).equals(document.content())) {
                    throw new IllegalStateException("The Plugin Configuration Changed After Restore. Restore Its Saved Settings Before Retrying");
                }
                journal.removeRevision = connection.revision();
                journal.phase = "REMOVE_REQUESTED";
                journal.removeAttempt++;
                journal.sequence++;
                return save(entry).thenCompose(ignored -> removeEndpoint(entry));
            });
        });
    }

    private void requireEndpoint(Entry entry, Connection connection, Endpoint endpoint) {
        Journal journal = entry.journal;
        if (!integration(entry).name().equals(endpoint.name()) || !integration(entry).protocol().equals(endpoint.protocol())
                || !"DEDICATED".equals(endpoint.routingMode()) || endpoint.target() == null || endpoint.target().port() != journal.targetPort
                || !journal.binding.equals(endpoint.target().bindingId()) || !journal.binding.equals(connection.binding().id())) {
            throw new IllegalStateException("The Plugin Port Changed. Remove Its Connection Manually");
        }
    }

    private void requireConnection(Connection connection, String id) {
        ReProxyTarget target = access.localTarget();
        if (connection == null || !id.equals(connection.id()) || target == null || !target.matches(connection.binding())) {
            throw new IllegalStateException("This Connection Belongs To Another Server");
        }
    }

    private void requireClient(Entry entry, ReProxyClient api) {
        if (entry.client != api) throw new IllegalStateException("The Account Changed. Retry With The Original Account");
    }

    private void requireAuthority(Entry entry) {
        if (closed) throw new IllegalStateException("This Server Connection Was Closed. Reopen The Server");
        synchronized (this) {
            if (entry.desired != null) requireDesired(entry, entry.desired);
            if (!entry.resourceKey.isBlank()) {
                Admission current = resources.get(entry.resourceKey);
                if (current == null || current.entry() != entry || !entry.resourceStamp.equals(current.identity())) throw new IllegalStateException("The Plugin Resource Changed. Choose Its Connection Again");
            }
        }
        if (!entry.journal.authority.equals(access.authorityStamp())) throw new IllegalStateException("The Server Identity Changed. Restore Its Previous Connection Before Continuing");
        if (local(entry)) requireClient(entry, ReProxyManager.serverClient());
    }

    private Async<Void> save(Entry entry) {
        requireAuthority(entry);
        String content = encode(entry);
        if (entry.document.exists() && entry.document.content().equals(content)) return Async.completed(null);
        return access.mutate(integration(entry), entry.sidecar, entry.document, content, operation(entry.journal, ((long) phaseStep(entry.journal.phase) << 32) ^ entry.journal.sequence)).thenAccept(document -> {
            synchronized (this) {
                entry.document = document;
                watch(entry);
            }
        });
    }

    private String encode(Entry entry) {
        Journal journal = entry.journal;
        JsonObject json = new JsonObject();
        json.addProperty("version", journal.networkRoute != null || !journal.auxiliary.isEmpty() || !journal.networkGroup.isBlank() ? 2 : 1);
        json.addProperty("networkGroup", journal.networkGroup);
        json.addProperty("networkPending", journal.networkPending);
        json.addProperty("serverKey", access.serverKey());
        json.addProperty("authorityStamp", journal.authority);
        json.addProperty("local", access.local());
        if (journal.networkRoute != null) {
            JsonObject route = new JsonObject();
            route.addProperty("host", journal.networkRoute.host());
            route.addProperty("port", journal.networkRoute.port());
            json.add("networkRoute", route);
        }
        JsonObject auxiliary = new JsonObject();
        journal.auxiliary.forEach((path, edit) -> {
            JsonObject value = new JsonObject();
            value.add("original", document(edit.original));
            value.addProperty("content", edit.content);
            value.addProperty("applied", edit.applied);
            value.addProperty("changeId", edit.changeId);
            if (edit.previous != null) value.add("previous", document(edit.previous));
            auxiliary.add(path, value);
        });
        json.add("networkFiles", auxiliary);
        json.addProperty("integration", integration(entry).id());
        json.add("integrationDefinition", ReProxyIntegrations.definition(journal.definition == null ? entry.integration : journal.definition));
        json.addProperty("phase", journal.phase);
        json.addProperty("operationKey", journal.key);
        json.addProperty("path", journal.path);
        json.add("originalDocument", document(journal.original));
        json.add("sourceDocument", document(journal.source));
        json.addProperty("targetPort", journal.targetPort);
        json.addProperty("config", journal.config);
        json.add("original", values(journal.originalValues));
        json.add("desired", values(journal.desiredValues));
        json.add("current", values(journal.currentValues));
        json.add("public", values(journal.publicValues));
        json.add("moveOwned", values(journal.moveOwned));
        json.addProperty("address", journal.address);
        json.addProperty("publicHost", journal.publicHost);
        json.addProperty("publicPort", journal.publicPort);
        json.addProperty("bindingId", journal.binding);
        json.addProperty("connectionId", journal.connection);
        json.addProperty("connectionRevision", journal.revision);
        json.addProperty("endpointId", journal.endpoint);
        json.addProperty("operationId", journal.operation);
        json.addProperty("removeRevision", journal.removeRevision);
        json.add("restoreExpected", document(journal.restoreExpected));
        json.addProperty("restoreContent", journal.restoreContent);
        json.addProperty("restoreAttempt", journal.restoreAttempt);
        json.addProperty("endpointAttempt", journal.endpointAttempt);
        json.addProperty("removeAttempt", journal.removeAttempt);
        json.addProperty("sequence", journal.sequence);
        json.addProperty("applyAttempt", journal.applyAttempt);
        json.addProperty("syncAttempt", journal.syncAttempt);
        json.addProperty("syncPort", journal.syncPort);
        json.addProperty("syncRevision", journal.syncRevision);
        json.addProperty("movePort", journal.movePort);
        json.addProperty("moveAttempt", journal.moveAttempt);
        json.addProperty("moveHint", journal.moveHint);
        if (journal.syncPolicy != null) {
            JsonObject policy = new JsonObject();
            policy.addProperty("mode", journal.syncPolicy.mode());
            policy.addProperty("port", journal.syncPolicy.port());
            policy.addProperty("fallback", journal.syncPolicy.allowFallback());
            json.add("syncPolicy", policy);
        }
        if (journal.previousPort != null) json.add("previousPort", port(journal.previousPort));
        if (journal.nextPort != null) json.add("nextPort", port(journal.nextPort));
        if (journal.port != null) json.add("allocatedPort", port(journal.port));
        return JsonTreeParser.write(json);
    }

    private Journal decode(Entry entry, String content) {
        JsonObject json = JsonTreeParser.parse(content).getAsJsonObject();
        ReProxyIntegrations.Integration definition = json.has("integrationDefinition")
                ? ReProxyIntegrations.definition(json.getAsJsonObject("integrationDefinition")) : entry.integration;
        if (!Set.of(1, 2).contains(json.get("version").getAsInt()) || !access.serverKey().equals(string(json, "serverKey"))
                || json.get("local").getAsBoolean() != access.local() || !entry.integration.id().equals(string(json, "integration")) || !definition.id().equals(entry.integration.id())) {
            throw new IllegalStateException("The Saved Plugin Connection Belongs To Another Server");
        }
        Journal journal = new Journal();
        journal.networkGroup = string(json, "networkGroup");
        journal.networkPending = json.has("networkPending") && json.get("networkPending").getAsBoolean();
        if (journal.networkPending && journal.networkGroup.isBlank()) throw new IllegalStateException("Saved Network Plugin Setup Is Invalid");
        journal.networkReady = !journal.networkPending;
        if (json.has("networkRoute")) {
            JsonObject route = json.getAsJsonObject("networkRoute");
            journal.networkRoute = new NetworkRoute(string(route, "host"), route.get("port").getAsInt());
        }
        if (json.has("networkFiles")) {
            JsonObject files = json.getAsJsonObject("networkFiles");
            if (files.size() > 64) throw new IllegalStateException("Saved Network Plugin Files Are Invalid");
            files.entrySet().forEach(item -> {
                String path = ReProxyIntegrations.relativePath(item.getKey());
                if (!networkFile(definition, path)) throw new IllegalStateException("Saved Network Plugin File Is Invalid");
                JsonObject value = item.getValue().getAsJsonObject();
                String changeId = string(value, "changeId");
                if (!changeId.isBlank()) UUID.fromString(changeId);
                Document previous = value.has("previous") ? document(value.getAsJsonObject("previous")) : null;
                if (!changeId.isBlank() && previous == null) throw new IllegalStateException("Saved Network Plugin Restore Is Invalid");
                journal.auxiliary.put(path, new Auxiliary(document(value.getAsJsonObject("original")), string(value, "content"), value.get("applied").getAsBoolean(), changeId, previous));
            });
            journal.networkReady = !journal.networkPending && journal.auxiliary.values().stream().allMatch(edit -> edit.applied && edit.verified);
        }
        journal.definition = definition;
        journal.phase = string(json, "phase");
        journal.authority = string(json, "authorityStamp");
        if (!journal.authority.equals(access.authorityStamp())) throw new IllegalStateException("The Server Identity Changed. Restore Its Previous Connection Before Continuing");
        if (!PHASES.contains(journal.phase)) throw new IllegalStateException("The Saved Plugin Connection Is Invalid");
        journal.key = string(json, "operationKey");
        journal.path = ReProxyIntegrations.relativePath(string(json, "path"));
        UUID.fromString(journal.key);
        if (!definition.paths().contains(journal.path) || journal.key.isBlank()) throw new IllegalStateException("The Saved Plugin Connection Is Invalid");
        journal.original = document(json.getAsJsonObject("originalDocument"));
        journal.source = json.has("sourceDocument") ? document(json.getAsJsonObject("sourceDocument")) : journal.original;
        journal.targetPort = json.get("targetPort").getAsInt();
        ReProxyIntegrations.prepare(definition, journal.path, journal.original.content(), access.serverPort());
        journal.config = string(json, "config");
        journal.originalValues = values(json.getAsJsonObject("original"));
        journal.desiredValues = values(json.getAsJsonObject("desired"));
        journal.currentValues = json.has("current") ? values(json.getAsJsonObject("current")) : journal.desiredValues;
        journal.publicValues = json.has("public") ? values(json.getAsJsonObject("public")) : journal.currentValues;
        journal.moveOwned = json.has("moveOwned") ? values(json.getAsJsonObject("moveOwned")) : Map.of();
        if (!journal.originalValues.isEmpty()) ReProxyIntegrations.restore(definition, journal.original.content(), journal.originalValues, journal.desiredValues);
        journal.address = string(json, "address");
        journal.publicHost = string(json, "publicHost");
        journal.publicPort = json.get("publicPort").getAsInt();
        journal.binding = string(json, "bindingId");
        journal.connection = string(json, "connectionId");
        journal.revision = string(json, "connectionRevision");
        journal.endpoint = string(json, "endpointId");
        journal.operation = string(json, "operationId");
        journal.removeRevision = string(json, "removeRevision");
        journal.restoreExpected = document(json.getAsJsonObject("restoreExpected"));
        journal.restoreContent = string(json, "restoreContent");
        journal.restoreAttempt = json.get("restoreAttempt").getAsInt();
        journal.endpointAttempt = json.get("endpointAttempt").getAsInt();
        journal.removeAttempt = json.get("removeAttempt").getAsInt();
        journal.sequence = json.get("sequence").getAsInt();
        journal.applyAttempt = integer(json, "applyAttempt");
        journal.syncAttempt = integer(json, "syncAttempt");
        journal.syncPort = integer(json, "syncPort");
        journal.syncRevision = string(json, "syncRevision");
        journal.movePort = integer(json, "movePort");
        journal.moveAttempt = integer(json, "moveAttempt");
        journal.moveHint = string(json, "moveHint");
        if (json.has("syncPolicy")) {
            JsonObject policy = json.getAsJsonObject("syncPolicy");
            journal.syncPolicy = new PublicPort(string(policy, "mode"), policy.get("port").isJsonNull() ? null : policy.get("port").getAsInt(), policy.get("fallback").getAsBoolean());
        }
        if (json.has("previousPort")) journal.previousPort = port(json.getAsJsonObject("previousPort"));
        if (json.has("nextPort")) journal.nextPort = port(json.getAsJsonObject("nextPort"));
        if (journal.restoreAttempt < 0 || journal.endpointAttempt < 0 || journal.removeAttempt < 0 || journal.sequence < 0
                || journal.applyAttempt < 0 || journal.syncAttempt < 0 || journal.moveAttempt < 0) throw new IllegalStateException("The Saved Plugin Connection Is Invalid");
        if (json.has("allocatedPort")) {
            journal.port = port(json.getAsJsonObject("allocatedPort"));
            boolean initial = journal.port.operationId().equals(operation(journal, 1));
            boolean moved = reservation(journal, journal.port);
            if (!initial && !moved) throw new IllegalStateException("The Saved Port Does Not Belong To This Connection");
            if (!json.has("sourceDocument") && !(access.local() && journal.networkRoute == null) && "ACTIVE".equals(journal.phase)) journal.targetPort = journal.port.port();
        }
        if (!journal.original.exists() || journal.original.stamp().isBlank() || journal.targetPort < 1 || journal.targetPort > 65535) {
            throw new IllegalStateException("The Saved Plugin Configuration Is Invalid");
        }
        if (access.local() && journal.networkRoute == null) {
            if (journal.connection.isBlank() || journal.revision.isBlank() || journal.binding.isBlank()) throw new IllegalStateException("The Saved Plugin Connection Is Invalid");
            UUID.fromString(journal.endpoint);
        } else if (!Set.of("PREPARED", "ABANDONED").contains(journal.phase) && journal.port == null) {
            throw new IllegalStateException("The Saved Plugin Port Is Missing");
        }
        if ("ENDPOINT_PENDING".equals(journal.phase) || "REMOVE_PENDING".equals(journal.phase) || "SYNC_PENDING".equals(journal.phase)) UUID.fromString(journal.operation);
        if (!Set.of("ABANDONED", "PREPARED", "ALLOCATED", "ENDPOINT_REQUESTED", "ENDPOINT_PENDING", "MOVE_ROLLBACK").contains(journal.phase)) {
            int targetPort = moving(journal.phase) ? journal.movePort : journal.targetPort;
            boolean newAddress = "MOVE_CONFIG_READY".equals(journal.phase) || "MOVE_APPLIED".equals(journal.phase);
            String host = newAddress ? journal.nextPort.host() : journal.publicHost;
            int publicPort = newAddress ? journal.nextPort.port() : journal.publicPort;
            ReProxyIntegrations.Change initial = ReProxyIntegrations.prepare(definition, journal.path, journal.original.content(), targetPort).connect(host, publicPort, targetPort);
            ReProxyIntegrations.Change goal = ReProxyIntegrations.prepare(definition, journal.path, journal.source.content(), targetPort).connect(host, publicPort, targetPort);
            Map<String, ReProxyIntegrations.Value> current = ReProxyIntegrations.prepare(definition, journal.path, journal.config, targetPort).connect(host, publicPort, targetPort).original();
            if (!initial.original().equals(journal.originalValues) || !current.equals(journal.currentValues) || !goal.desired().equals(journal.publicValues)
                    || !journal.currentValues.keySet().equals(journal.desiredValues.keySet())) throw new IllegalStateException("The Saved Plugin Configuration Changes Are Invalid");
        }
        if (syncing(journal.phase) && (journal.syncPolicy == null || journal.syncPort < 1)) throw new IllegalStateException("The Saved Target Change Is Invalid");
        if (moving(journal.phase) && (journal.previousPort == null || journal.movePort < 1 || journal.movePort > 65535
                || !"MOVE_REQUESTED".equals(journal.phase) && journal.nextPort == null)) throw new IllegalStateException("The Saved Port Move Is Invalid");
        if (moving(journal.phase)) {
            if (!journal.previousPort.operationId().equals(operation(journal, 1)) && !reservation(journal, journal.previousPort)
                    || journal.nextPort != null && !journal.nextPort.operationId().equals(operation(journal, (7L << 32) ^ journal.moveAttempt))) {
                throw new IllegalStateException("The Saved Port Move Does Not Belong To This Connection");
            }
            ReProxyIntegrations.restore(definition, journal.original.content(), journal.originalValues, journal.moveOwned);
        }
        if ("MOVE_ROLLBACK".equals(journal.phase)) {
            String restored = ReProxyIntegrations.restore(definition, journal.restoreExpected.content(), journal.moveOwned, journal.desiredValues);
            if (!journal.restoreExpected.exists() || !restored.equals(journal.restoreContent) || journal.moveHint.isBlank()) {
                throw new IllegalStateException("The Saved Port Rollback Is Invalid");
            }
        }
        if (disconnecting(journal.phase) || "OFF".equals(journal.phase)) {
            String restored = ReProxyIntegrations.restore(definition, journal.restoreExpected.content(), journal.originalValues, journal.desiredValues);
            if (!journal.restoreExpected.exists() || !restored.equals(journal.restoreContent)) throw new IllegalStateException("The Saved Plugin Restore Is Invalid");
        }
        return journal;
    }

    private static JsonObject port(Port value) {
        JsonObject json = new JsonObject();
        json.addProperty("host", value.host());
        json.addProperty("port", value.port());
        json.addProperty("id", value.id());
        json.addProperty("created", value.created());
        json.addProperty("operationId", value.operationId());
        return json;
    }

    private static Port port(JsonObject json) {
        return new Port(string(json, "host"), json.get("port").getAsInt(), string(json, "id"), json.get("created").getAsBoolean(), string(json, "operationId"));
    }

    private static int integer(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsInt() : 0;
    }

    private static JsonObject document(Document document) {
        JsonObject json = new JsonObject();
        Document value = document == null ? new Document(false, "", "") : document;
        json.addProperty("exists", value.exists());
        json.addProperty("content", value.content());
        json.addProperty("stamp", value.stamp());
        return json;
    }

    private static Document document(JsonObject json) {
        return new Document(json.get("exists").getAsBoolean(), string(json, "content"), string(json, "stamp"));
    }

    private static JsonObject values(Map<String, ReProxyIntegrations.Value> values) {
        JsonObject json = new JsonObject();
        values.forEach((key, value) -> {
            JsonObject item = new JsonObject();
            item.addProperty("present", value.present());
            item.addProperty("text", value.text());
            json.add(key, item);
        });
        return json;
    }

    private static Map<String, ReProxyIntegrations.Value> values(JsonObject json) {
        Map<String, ReProxyIntegrations.Value> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            values.put(entry.getKey(), new ReProxyIntegrations.Value(value.get("present").getAsBoolean(), string(value, "text")));
        }
        return Map.copyOf(values);
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String identity(ResourceContainerItem resource) {
        return String.join("\u0000", ResourceContainerItem.key(resource),
                Objects.requireNonNullElse(resource.getFileHash(), "").trim().toLowerCase(Locale.ROOT),
                Boolean.toString(resource.isModpack()), resource.getType().name());
    }

    private static String stamp(ResourceContainerItem resource) {
        String file = resource.getFileName();
        if (file.regionMatches(true, file.length() - 9, ".disabled", 0, 9)) file = file.substring(0, file.length() - 9);
        return String.join("\u0000", ResourceContainerItem.key(resource), Objects.requireNonNullElse(resource.getProviderName(), ""),
                Objects.requireNonNullElse(resource.getProjectId(), ""), Objects.requireNonNullElse(resource.getFileHash(), ""),
                Objects.requireNonNullElse(resource.getVersionId(), ""), resource.getVersion(), resource.getName(), file,
                Boolean.toString(resource.isModpack()), resource.getType().name());
    }

    private static String operation(Journal journal, long step) {
        String key = UUID.fromString(journal.key).toString();
        long suffix = Long.parseLong(key.substring(24), 16) ^ step;
        String hex = "000000000000" + Long.toHexString(suffix);
        return key.substring(0, 24) + hex.substring(hex.length() - 12);
    }

    private static boolean reservation(Journal journal, Port port) {
        String key = UUID.fromString(journal.key).toString();
        String id = UUID.fromString(port.operationId()).toString();
        if (!key.substring(0, 24).equals(id.substring(0, 24))) return false;
        long step = Long.parseLong(key.substring(24), 16) ^ Long.parseLong(id.substring(24), 16);
        long attempt = step & 0xffffffffL;
        return step >>> 32 == 7 && attempt > 0 && attempt <= journal.moveAttempt;
    }

    private static String applyOperation(Journal journal) {
        return journal.applyAttempt == 0 ? operation(journal, 3) : operation(journal, (3L << 32) ^ journal.applyAttempt);
    }

    private static String restoreOperation(Journal journal) {
        return operation(journal, (4L << 32) ^ journal.restoreAttempt);
    }

    private static int phaseStep(String phase) {
        return switch (phase) {
            case "PREPARED" -> 101;
            case "ALLOCATED" -> 102;
            case "ENDPOINT_REQUESTED" -> 103;
            case "ENDPOINT_PENDING" -> 104;
            case "CONFIG_READY" -> 105;
            case "ACTIVE" -> 106;
            case "RESTORE_REQUESTED" -> 107;
            case "RESTORED" -> 108;
            case "REMOVE_REQUESTED" -> 109;
            case "REMOVE_PENDING" -> 110;
            case "REMOVED" -> 111;
            case "OFF" -> 112;
            case "ABANDONED" -> 113;
            case "SYNC_PREPARED" -> 114;
            case "SYNC_REQUESTED" -> 115;
            case "SYNC_PENDING" -> 116;
            case "MOVE_REQUESTED" -> 117;
            case "MOVE_ALLOCATED" -> 118;
            case "MOVE_CONFIG_READY" -> 119;
            case "MOVE_APPLIED" -> 120;
            case "MOVE_ROLLBACK" -> 121;
            default -> throw new IllegalStateException("The Saved Plugin Connection Is Invalid");
        };
    }

    private static boolean syncing(String phase) {
        return phase.equals("SYNC_PREPARED") || phase.equals("SYNC_REQUESTED") || phase.equals("SYNC_PENDING");
    }

    private static boolean moving(String phase) {
        return phase.equals("MOVE_REQUESTED") || phase.equals("MOVE_ALLOCATED") || phase.equals("MOVE_CONFIG_READY") || phase.equals("MOVE_APPLIED") || phase.equals("MOVE_ROLLBACK");
    }

    private static boolean inactive(String phase) {
        return phase.equals("OFF") || phase.equals("ABANDONED");
    }

    private static boolean revisionFailure(Throwable failure) {
        return failure instanceof ReProxyClient.Failure error && error.status() == 412;
    }

    private static boolean disconnecting(String phase) {
        return phase.equals("RESTORE_REQUESTED") || phase.equals("RESTORED") || phase.equals("REMOVE_REQUESTED")
                || phase.equals("REMOVE_PENDING") || phase.equals("REMOVED");
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause.getMessage() == null || cause.getMessage().startsWith("Async operation"))) cause = cause.getCause();
        return cause.getMessage() == null || cause.getMessage().isBlank() ? "Could Not Change The Plugin Connection" : cause.getMessage();
    }

    private final class Entry {
        private final ReProxyIntegrations.Integration integration;
        private final String sidecar;
        private Document document;
        private final Async<Void> loaded = Async.pending();
        private Document observed;
        private ReProxyIntegrations.Prepared prepared;
        private int preparedServerPort;
        private Map<String, ReProxyIntegrations.Value> actualValues = Map.of();
        private Throwable parseFailure;
        private AutoCloseable fileWatch;
        private final Map<String, AutoCloseable> networkWatches = new HashMap<>();
        private final Map<String, Auxiliary> networkObserved = new HashMap<>();
        private String configPath = "";
        private String watchedPath = "";
        private final List<Runnable> listeners = new ArrayList<>();
        private TaskScheduler.ScheduledTask notification;
        private long noticeGeneration;
        private long watchGeneration;
        private Async<Void> change;
        private Desired desired;
        private boolean dirty;
        private boolean force;
        private boolean changing;
        private boolean recover;
        private boolean waitingStop;
        private String resourceKey = "";
        private String resourceStamp = "";
        private ReProxyClient client;
        private volatile Journal journal;
        private boolean loading = true;
        private boolean busy;
        private int restores;
        private int endpointRetries;
        private int removeRetries;
        private String error = "";
        private String notice = "";
        private boolean refreshNeeded;
        private boolean proxy;
        private NetworkRoute networkRoute;
        private String networkGroup = "";
        private ReProxyIntegrations.Integration pathPolicy;
        private boolean pathProxy;
        private List<String> paths = List.of();

        private Entry(ReProxyIntegrations.Integration integration) {
            this.integration = integration;
            sidecar = ".remotely/plugin-forwarding-" + integration.id() + ".json";
        }
    }

    private ReProxyIntegrations.Integration integration(Entry entry) {
        Journal journal = entry.journal;
        return journal != null && journal.definition != null && !inactive(journal.phase) ? journal.definition : entry.integration;
    }

    public synchronized ReProxyIntegrations.Integration configIntegration(String relativePath) {
        Entry entry = config(relativePath);
        return entry == null ? null : integration(entry);
    }

    private static final class Journal {
        private NetworkRoute networkRoute;
        private String networkGroup = "";
        private boolean networkPending;
        private final Map<String, Auxiliary> auxiliary = new LinkedHashMap<>();
        private volatile boolean networkReady = true;
        private ReProxyIntegrations.Integration definition;
        private String phase = "OFF";
        private String authority = "";
        private String key = "";
        private String path = "";
        private Document original;
        private Document source;
        private int targetPort;
        private String config = "";
        private Map<String, ReProxyIntegrations.Value> originalValues = Map.of();
        private Map<String, ReProxyIntegrations.Value> desiredValues = Map.of();
        private Map<String, ReProxyIntegrations.Value> currentValues = Map.of();
        private Map<String, ReProxyIntegrations.Value> publicValues = Map.of();
        private String address = "";
        private String publicHost = "";
        private int publicPort;
        private String binding = "";
        private Port port;
        private String connection = "";
        private String revision = "";
        private String endpoint = "";
        private String operation = "";
        private String removeRevision = "";
        private Document restoreExpected;
        private String restoreContent = "";
        private int restoreAttempt;
        private int endpointAttempt;
        private int removeAttempt;
        private int sequence;
        private int applyAttempt;
        private int syncAttempt;
        private int syncPort;
        private String syncRevision = "";
        private PublicPort syncPolicy;
        private Map<String, ReProxyIntegrations.Value> moveOwned = Map.of();
        private Port previousPort;
        private Port nextPort;
        private int movePort;
        private int moveAttempt;
        private String moveHint = "";
    }

    private static final class Auxiliary {
        private final Document original;
        private String content;
        private boolean applied;
        private boolean verified;
        private long observation;
        private String changeId;
        private Document previous;

        private Auxiliary(Document original, String content, boolean applied, String changeId, Document previous) {
            this.original = original;
            this.content = Objects.requireNonNull(content, "Network File Content Is Required");
            this.applied = applied;
            this.changeId = changeId;
            this.previous = previous;
        }
    }
}
