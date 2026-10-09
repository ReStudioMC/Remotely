package redxax.oxy.remotely.servers.reproxy;

import restudio.rebase.ui.screens.resources.ResourceContainerItem;
import restudio.rebase.ui.screens.resources.ResourceForwarding;
import restudio.rescreen.platform.Async;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class NetworkPluginForwarding implements ResourceForwarding, AutoCloseable {
    public record Member(String id, String route, String hostScope, String address, int gamePort, boolean proxy, PluginForwarding forwarding) {
        public Member {
            Objects.requireNonNull(id, "Network Member Is Required");
            Objects.requireNonNull(route, "Network Route Is Required");
            Objects.requireNonNull(hostScope, "Network Host Is Required");
            Objects.requireNonNull(address, "Network Address Is Required");
        }
    }

    public record Listener(String memberId, String hostScope, int port, String endpoint) { }

    public record Network(String id, long revision, String memberId, List<Member> members, boolean hosted, List<Member> hosts, List<Listener> listeners) {
        public Network(String id, long revision, String memberId, List<Member> members) {
            this(id, revision, memberId, members, false);
        }
        public Network(String id, long revision, String memberId, List<Member> members, boolean hosted) {
            this(id, revision, memberId, members, hosted, members, List.of());
        }
        public Network {
            Objects.requireNonNull(id, "Network Is Required");
            Objects.requireNonNull(memberId, "Network Member Is Required");
            members = List.copyOf(members);
            hosts = List.copyOf(hosts);
            listeners = List.copyOf(listeners);
            if (members.stream().filter(Member::proxy).count() != 1 || members.stream().noneMatch(member -> member.id().equals(memberId))) {
                throw new IllegalArgumentException("Network Membership Is Unavailable");
            }
        }

        private Member proxy() { return members.stream().filter(Member::proxy).findFirst().orElseThrow(); }
        private Member member() { return members.stream().filter(value -> value.id().equals(memberId)).findFirst().orElseThrow(); }
        private String stamp() { return id + ":" + revision; }
    }

    public static final class Coordinator {
        private final Map<String, Job> changes = new HashMap<>();
        private final Map<String, Job> hosts = new HashMap<>();
        private final Map<String, Result> results = new HashMap<>();
        private record Job(String integration, Async<Void> result) { }

        private Async<Void> run(Network network, ReProxyIntegrations.Integration integration, Supplier<Async<Void>> action) {
            String key = network.id() + ":" + integration.id();
            Async<Void> result = Async.pending();
            Set<String> scopes = new HashSet<>();
            for (Member member : network.members()) if (member.forwarding() != null && member.forwarding().access().ports() != PluginForwarding.Access.Ports.ALLOCATED) scopes.add(member.hostScope());
            synchronized (this) {
                Job known = changes.get(network.id());
                if (known != null) return known.integration().equals(integration.id()) ? known.result()
                        : Async.failed(new IllegalStateException("Wait For The Current Network Plugin Setup To Finish"));
                if (scopes.stream().anyMatch(hosts::containsKey)) return Async.failed(new IllegalStateException("Wait For Plugin Setup On This Host To Finish"));
                changes.put(network.id(), new Job(integration.id(), result));
                for (String scope : scopes) hosts.put(scope, new Job(integration.id(), result));
                results.remove(key);
            }
            Async.supply(action).thenCompose(value -> value).whenComplete((ignored, failure) -> {
                synchronized (this) {
                    changes.remove(network.id());
                    for (String scope : scopes) hosts.remove(scope);
                    results.put(key, new Result(network.stamp(), failure == null ? "" : message(failure)));
                }
                if (failure == null) result.complete(null);
                else result.fail(failure);
            });
            return result;
        }

        private synchronized Result result(Network network, ReProxyIntegrations.Integration integration) {
            Result result = results.get(network.id() + ":" + integration.id());
            return result != null && result.stamp().equals(network.stamp()) ? result : null;
        }

        private synchronized boolean busy(Network network, ReProxyIntegrations.Integration integration) {
            return changes.containsKey(network.id());
        }

        private synchronized void invalidate(Network network) {
            results.keySet().removeIf(key -> key.startsWith(network.id() + ":"));
        }
    }

    private record Result(String stamp, String error) { }
    private record NetworkState(boolean connected, boolean busy, String address) { }
    private record Configuration(Member member, String path, PluginForwarding.Document document, ReProxyIntegrations.Prepared prepared) { }
    private record Plan(Network network, ReProxyIntegrations.Integration integration, List<Configuration> configurations,
                        Map<String, PluginForwarding.NetworkRoute> routes, Map<String, PluginForwarding.Document> secrets,
                        String secretPath, PluginForwarding.Document secret, String secretContent, String changeId) { }

    private final PluginForwarding own;
    private final Supplier<Async<Network>> source;
    private final Coordinator coordinator;
    private Map<String, ReProxyIntegrations.Integration> integrations = Map.of();
    private Map<String, Boolean> enabled = Map.of();
    private Network network;
    private Async<Network> loading;
    private long generation;
    private boolean admitted;
    private boolean closed;
    private String error = "";
    private final Set<String> direct = new HashSet<>();
    private final Map<String, NetworkState> states = new HashMap<>();
    private final Map<String, List<AutoCloseable>> watches = new HashMap<>();

    public NetworkPluginForwarding(PluginForwarding own, Supplier<Async<Network>> source, Coordinator coordinator) {
        this.own = Objects.requireNonNull(own, "Plugin Connection Is Required");
        this.source = Objects.requireNonNull(source, "Network Source Is Required");
        this.coordinator = Objects.requireNonNull(coordinator, "Network Changes Are Required");
    }

    public PluginForwarding connection() { return own; }

    @Override
    public synchronized void admit(List<ResourceContainerItem> inventory) {
        own.admit(inventory);
        Map<String, ReProxyIntegrations.Integration> next = new LinkedHashMap<>();
        Map<String, Boolean> available = new HashMap<>();
        admit(inventory, true, next, available);
        integrations = Map.copyOf(next);
        enabled = Map.copyOf(available);
        if (!admitted && loading == null) load();
        if (network != null) admit(network);
    }

    private void admit(List<ResourceContainerItem> inventory, boolean parentEnabled, Map<String, ReProxyIntegrations.Integration> next, Map<String, Boolean> available) {
        for (ResourceContainerItem resource : inventory == null ? List.<ResourceContainerItem>of() : inventory) {
            if (resource == null) continue;
            ReProxyIntegrations.Integration integration = ReProxyIntegrations.find(resource);
            boolean active = parentEnabled && resource.isEnabled();
            if (integration != null) {
                next.put(ResourceContainerItem.key(resource), integration);
                available.put(ResourceContainerItem.key(resource), active);
            }
            admit(resource.getChildren(), active, next, available);
        }
    }

    private synchronized void admit(Network value) {
        for (Member member : value.members()) if (member.forwarding() != null) {
            member.forwarding().access().admitNetwork(value.hosted() ? value.id() : null, value.revision());
            for (ReProxyIntegrations.Integration integration : integrations.values()) member.forwarding().admitIntegration(integration, member.proxy());
        }
        for (ReProxyIntegrations.Integration integration : integrations.values()) if (!watches.containsKey(integration.id()) && networked(integration)) {
            List<AutoCloseable> registrations = new ArrayList<>();
            watches.put(integration.id(), registrations);
            for (Member member : value.members()) if (member.forwarding() != null) registrations.add(member.forwarding().watchIntegration(integration, () -> update(value, integration)));
            update(value, integration);
        }
    }

    private synchronized void update(Network value, ReProxyIntegrations.Integration integration) {
        if (closed || !value.equals(network)) return;
        Member proxy = value.proxy();
        State state = proxy.forwarding() == null ? new State(false, false, false, "", "") : proxy.forwarding().state(integration);
        boolean connected = state.connected() && proxy.forwarding() != null && proxy.forwarding().networkGroup(integration, value.stamp());
        boolean busy = state.busy();
        if (!"geyser".equals(integration.id())) for (Member member : value.members()) if (!member.proxy()) {
            State current = member.forwarding() == null ? new State(false, false, false, "", "") : member.forwarding().state(integration);
            connected &= current.connected() && member.forwarding() != null && member.forwarding().networkGroup(integration, value.stamp());
            busy |= current.busy();
        }
        states.put(integration.id(), new NetworkState(connected, busy, state.address()));
    }

    private void clearWatches() {
        for (List<AutoCloseable> registrations : watches.values()) for (AutoCloseable registration : registrations) {
            try { registration.close(); } catch (Exception ignored) { }
        }
        watches.clear();
        states.clear();
    }

    private synchronized Async<Network> load() {
        if (closed) return Async.failed(new IllegalStateException("Server Details Were Closed"));
        if (loading != null) return loading;
        long stamp = generation;
        Async<Network> request = Async.pending();
        loading = request;
        Async.supply(source).thenCompose(value -> value).whenComplete((value, failure) -> {
            synchronized (this) {
                if (closed || loading != request || generation != stamp) {
                    request.fail(new IllegalStateException("Network Membership Changed. Retry The Plugin Setup"));
                    return;
                }
                loading = null;
                admitted = true;
                error = failure == null ? "" : message(failure);
                if (failure == null) {
                    if (!Objects.equals(network, value)) {
                        if (network != null) coordinator.invalidate(network);
                        clearWatches();
                        direct.clear();
                        network = value;
                    }
                    if (network != null) admit(network);
                    else own.access().admitNetwork(null, 0);
                }
            }
            if (failure == null) request.complete(value);
            else request.fail(failure);
        });
        return request;
    }

    public synchronized void invalidate() {
        generation++;
        Async<Network> previous = loading;
        loading = null;
        if (previous != null) previous.fail(new IllegalStateException("Network Membership Changed. Retry The Plugin Setup"));
        admitted = false;
        if (network != null) coordinator.invalidate(network);
        clearWatches();
        network = null;
        error = "";
        direct.clear();
        if (!integrations.isEmpty() && loading == null && !closed) load();
    }

    @Override
    public synchronized State state(ResourceContainerItem resource) {
        ReProxyIntegrations.Integration integration = integrations.get(ResourceContainerItem.key(resource));
        if (integration == null || !networked(integration)) return own.state(resource);
        if (!enabled.getOrDefault(ResourceContainerItem.key(resource), false)) return new State(true, false, false, "Enable " + integration.name() + " Before Connecting", "");
        if (loading != null || !admitted) return new State(true, false, true, "Loading Network", "");
        if (!error.isBlank()) return new State(true, false, false, error, "");
        if (network == null) return own.state(resource);
        if (direct.contains(integration.id())) return own.state(resource);
        Member proxy = network.proxy();
        if (proxy.forwarding() == null) return new State(true, false, false, "Network Proxy Is Unavailable", "");
        NetworkState state = states.getOrDefault(integration.id(), new NetworkState(false, true, ""));
        if (coordinator.busy(network, integration)) return new State(true, false, true, "Updating Network Plugin Settings", state.address());
        if (state.busy()) return new State(true, false, true, "Updating Network Plugin Settings", state.address());
        Result result = coordinator.result(network, integration);
        boolean connected = state.connected();
        if (result != null) return new State(true, connected && result.error().isBlank(), false, result.error().isBlank()
                ? connected ? "Network Settings Are Saved. Restart The Network To Apply Them" : "Network Plugin Settings Were Restored" : result.error(), state.address());
        return new State(true, connected, state.busy(), connected ? "Disconnect " + integration.name() + " From The Network"
                : "Configure " + integration.name() + " For The Network", state.address());
    }

    @Override
    public Async<Void> toggle(ResourceContainerItem resource) {
        ReProxyIntegrations.Integration integration;
        synchronized (this) { integration = integrations.get(ResourceContainerItem.key(resource)); }
        if (integration == null) return own.toggle(resource);
        if (!networked(integration)) return load().thenCompose(value -> value == null ? standalone(integration, resource)
                : coordinator.run(value, integration, () -> scoped(value, () -> own.toggle(resource))));
        synchronized (this) {
            if (!enabled.getOrDefault(ResourceContainerItem.key(resource), false)) return Async.failed(new IllegalStateException("Enable " + integration.name() + " Before Connecting"));
        }
        return load().thenCompose(value -> {
            if (closed) throw new IllegalStateException("Server Details Were Closed");
            if (value == null) {
                require(null);
                return standalone(integration, resource);
            }
            return coordinator.run(value, integration, () -> scoped(value, () -> prepare(value, integration).thenCompose(plan -> {
                if (plan == null) return own.toggle(resource);
                boolean connected = plan.configurations().stream().allMatch(configuration -> configuration.member().forwarding().state(integration).connected()
                        && configuration.member().forwarding().networkGroup(integration, value.stamp()));
                if (plan.configurations().stream().anyMatch(configuration -> configuration.member().forwarding().hasConnection(integration)
                        && !configuration.member().forwarding().networkGroup(integration, value.stamp()))) {
                    return disconnect(plan).thenCompose(ignored -> prepare(value, integration)).thenCompose(next -> validate(next).thenCompose(ignored -> connect(next)));
                }
                return connected ? disconnect(plan) : validate(plan).thenCompose(ignored -> connect(plan));
            }))).whenComplete((ignored, failure) -> update(value, integration));
        });
    }

    private Async<Void> scoped(Network value, Supplier<Async<Void>> action) {
        List<AutoCloseable> leases = new ArrayList<>();
        return Async.supply(() -> {
            require(value);
            for (Member member : value.members()) if (member.forwarding() != null) {
                leases.add(member.forwarding().access().networkChange(value.hosted() ? value.id() : null, value.revision()));
            }
            return action.get();
        }).thenCompose(result -> result).whenComplete((ignored, failure) -> {
            for (AutoCloseable lease : leases.reversed()) {
                try { lease.close(); } catch (Exception ignoredClose) { }
            }
        });
    }

    private Async<Void> standalone(ReProxyIntegrations.Integration integration, ResourceContainerItem resource) {
        Member member = new Member(own.access().serverKey(), "server", own.access().hostScope(), "127.0.0.1", own.access().serverPort(), true, own);
        return coordinator.run(new Network("server:" + member.id(), 0, member.id(), List.of(member)), integration, () -> {
            require(null);
            AutoCloseable lease = own.access().networkChange(null, 0);
            return Async.supply(() -> own.toggle(resource)).thenCompose(result -> result).whenComplete((ignored, failure) -> {
                try { lease.close(); } catch (Exception ignoredClose) { }
            });
        });
    }

    private Async<Plan> prepare(Network value, ReProxyIntegrations.Integration integration) {
        Member proxy = value.proxy();
        if (proxy.forwarding() == null) return Async.failed(new IllegalStateException("The Network Proxy Cannot Be Configured"));
        return configuration(proxy, integration, 0).thenCompose(found -> {
            if (found == null) {
                if (!"geyser".equals(integration.id())) {
                    synchronized (this) { direct.add(integration.id()); }
                    return Async.completed(null);
                }
                return Async.failed(new IllegalStateException("Install Geyser On The Network Proxy And Start It Once To Create Its Settings"));
            }
            List<Configuration> configurations = new ArrayList<>();
            configurations.add(found);
            Async<Void> preflight = stopped(found, integration);
            if (!"geyser".equals(integration.id())) for (Member member : value.members()) if (!member.proxy()) {
                if (member.forwarding() == null) return Async.failed(new IllegalStateException("Every Backend Must Be Managed Before Configuring Network Voice Chat"));
                preflight = preflight.thenCompose(ignored -> configuration(member, integration, 0).thenCompose(configuration -> {
                    if (configuration == null) throw new IllegalStateException("Install " + integration.name() + " On " + member.route() + " And Start It Once To Create Its Settings");
                    configurations.add(configuration);
                    return stopped(configuration, integration);
                }));
            }
            return preflight.thenCompose(ignored -> privateRoutes(value, integration, configurations)).thenCompose(routes -> {
                if (proxy.forwarding().access().ports() == PluginForwarding.Access.Ports.PRIVATE) throw new IllegalStateException("The Proxy Host Must Provide A Reachable UDP Port Before Configuring Network Plugins");
                if (!"plasmovoice".equals(integration.id())) return Async.completed(new Plan(value, integration, List.copyOf(configurations), routes, Map.of(), "", null, "", UUID.randomUUID().toString()));
                String path = secretPath(found.path());
                Map<String, PluginForwarding.Document> secrets = new LinkedHashMap<>();
                Async<Void> read = Async.completed(null);
                for (Configuration configuration : configurations) read = read.thenCompose(done -> configuration.member().forwarding().access().secretFiles()
                        .thenCompose(checked -> configuration.member().forwarding().access().observe(integration, secretPath(configuration.path())))
                        .thenAccept(secret -> secrets.put(configuration.member().id(), secret)));
                return read.thenApply(done -> {
                    PluginForwarding.Document secret = secrets.get(proxy.id());
                    String content = secret.exists() ? secret.content() : UUID.randomUUID() + "\n";
                    UUID.fromString(content.strip());
                    return new Plan(value, integration, List.copyOf(configurations), routes, Map.copyOf(secrets), path, secret, content, UUID.randomUUID().toString());
                });
            });
        });
    }

    private Async<Void> stopped(Configuration configuration, ReProxyIntegrations.Integration integration) {
        return configuration.member().forwarding().access().running().thenAccept(running -> {
            if (Boolean.TRUE.equals(running) && (!"geyser".equals(integration.id()) || integration.requiresStopped())) {
                throw new IllegalStateException("Stop The Network Before Configuring " + integration.name());
            }
        });
    }

    private Async<Map<String, PluginForwarding.NetworkRoute>> privateRoutes(Network value, ReProxyIntegrations.Integration integration, List<Configuration> configurations) {
        Map<String, Set<Integer>> occupied = new HashMap<>();
        Set<String> scopes = new HashSet<>();
        Map<String, Member> current = new HashMap<>();
        for (Configuration configuration : configurations) {
            Member member = configuration.member();
            current.put(member.id(), member);
            if (member.forwarding().access().ports() != PluginForwarding.Access.Ports.ALLOCATED) scopes.add(member.hostScope());
        }
        for (Listener listener : value.listeners()) if (scopes.contains(listener.hostScope())) {
            Member owner = current.get(listener.memberId());
            if (owner == null || !owner.forwarding().ownsEndpoint(integration, listener.endpoint())) {
                occupied.computeIfAbsent(listener.hostScope(), ignored -> new HashSet<>()).add(listener.port());
            }
        }
        Async<Void> check = Async.completed(null);
        for (Member member : value.hosts()) if (scopes.contains(member.hostScope()) && member.forwarding() != null
                && member.forwarding().access().ports() != PluginForwarding.Access.Ports.ALLOCATED) {
            for (ReProxyIntegrations.Integration other : ReProxyIntegrations.catalog()) if ("UDP".equals(other.protocol())
                    && (!other.id().equals(integration.id()) || !current.containsKey(member.id()))) {
                check = check.thenCompose(ignored -> configuration(member, other, 0).thenAccept(found -> {
                    if (found != null) occupied.computeIfAbsent(member.hostScope(), key -> new HashSet<>()).add(found.prepared().targetPort());
                }));
            }
        }
        return check.thenApply(ignored -> {
            for (Configuration configuration : configurations) {
                Member member = configuration.member();
                if (member.forwarding().access().ports() == PluginForwarding.Access.Ports.ALLOCATED) continue;
                Set<Integer> ports = occupied.computeIfAbsent(member.hostScope(), key -> new HashSet<>());
                if (member.proxy()) {
                    if (!ports.add(configuration.prepared().targetPort())) throw new IllegalStateException("Another Plugin Uses The Proxy UDP Port. Choose A Different Port In Its Settings");
                } else {
                    if (!member.hostScope().equals(value.proxy().hostScope())) throw new IllegalStateException("A Reachable UDP Route From The Proxy To " + member.route() + " Is Required");
                    if (member.forwarding().hasConnection(integration) && !ports.add(member.forwarding().targetPort(integration))) {
                        throw new IllegalStateException("Another Plugin Uses The Backend UDP Port. Restore Its Previous Setup And Choose A Different Port");
                    }
                }
            }
            Map<String, PluginForwarding.NetworkRoute> routes = new LinkedHashMap<>();
            for (Configuration configuration : configurations) {
                Member member = configuration.member();
                if (member.proxy() || member.forwarding().access().ports() == PluginForwarding.Access.Ports.ALLOCATED) continue;
                if (member.forwarding().hasConnection(integration)) {
                    PluginForwarding.NetworkRoute admitted = member.forwarding().networkRoute(integration);
                    if (admitted != null) routes.put(member.id(), admitted);
                    continue;
                }
                Set<Integer> ports = occupied.computeIfAbsent(member.hostScope(), key -> new HashSet<>());
                int port = configuration.prepared().targetPort();
                if (ports.contains(port)) port = member.gamePort();
                if (ports.contains(port) || port < 1 || port > 65535) {
                    port = 49152;
                    while (port <= 65535 && ports.contains(port)) port++;
                }
                if (port > 65535) throw new IllegalStateException("No Private UDP Port Is Available For " + member.route());
                ports.add(port);
                routes.put(member.id(), new PluginForwarding.NetworkRoute("127.0.0.1", port));
            }
            return Map.copyOf(routes);
        });
    }

    private Async<Configuration> configuration(Member member, ReProxyIntegrations.Integration integration, int index) {
        return configuration(member, integration, ReProxyIntegrations.paths(integration, member.proxy()), index);
    }

    private Async<Configuration> configuration(Member member, ReProxyIntegrations.Integration integration, List<String> paths, int index) {
        if (index >= paths.size()) return Async.completed(null);
        String path = paths.get(index);
        return member.forwarding().access().observe(integration, path).thenCompose(document -> document.exists() && !document.content().isBlank()
                ? Async.completed(new Configuration(member, path, document, ReProxyIntegrations.prepare(integration, path, document.content(), member.gamePort())))
                : configuration(member, integration, paths, index + 1));
    }

    private Async<Void> connect(Plan plan) {
        List<Configuration> changed = new ArrayList<>();
        Async<Void> result = Async.completed(null);
        for (Configuration configuration : plan.configurations()) {
            Member member = configuration.member();
            boolean connected = member.forwarding().hasConnection(plan.integration());
            PluginForwarding.NetworkRoute route = plan.routes().get(member.id());
            result = result.thenCompose(ignored -> {
                require(plan.network());
                if (!connected) changed.add(configuration);
                return member.forwarding().connect(plan.integration(), member.proxy(), route, plan.network().stamp());
            });
        }
        return result.thenCompose(ignored -> configure(plan)).thenCompose(ignored -> finish(plan)).exceptionallyCompose(failure -> rollback(plan, changed, failure));
    }

    private Async<Void> finish(Plan plan) {
        Async<Void> result = Async.completed(null);
        for (Configuration configuration : plan.configurations()) result = result.thenCompose(ignored -> {
            require(plan.network());
            return configuration.member().forwarding().finishNetwork(plan.integration(), plan.network().stamp());
        });
        return result;
    }

    private Async<Void> validate(Plan plan) {
        Async<Void> result = Async.completed(null);
        for (Configuration configuration : plan.configurations()) result = result.thenCompose(ignored -> {
            PluginForwarding forwarding = configuration.member().forwarding();
            return forwarding.validateNetwork(plan.integration(), configuration.member().proxy(), plan.routes().get(configuration.member().id()));
        });
        return result;
    }

    private Async<Void> configure(Plan plan) {
        if ("geyser".equals(plan.integration().id())) return Async.completed(null);
        Map<String, String> routes = new LinkedHashMap<>();
        Async<Void> result = Async.completed(null);
        for (Configuration configuration : plan.configurations()) if (!configuration.member().proxy()) {
            result = result.thenCompose(ignored -> {
                require(plan.network());
                Member member = configuration.member();
                PluginForwarding.NetworkRoute route = member.forwarding().route(plan.integration());
                if (route == null) throw new IllegalStateException("The Backend Voice Port Is Unavailable");
                String host = member.forwarding().access().ports() == PluginForwarding.Access.Ports.ALLOCATED ? route.host() : member.address();
                int port = member.forwarding().access().ports() == PluginForwarding.Access.Ports.ALLOCATED ? route.port() : member.forwarding().targetPort(plan.integration());
                routes.put("servers." + member.route(), quote(address(host, port)));
                return member.forwarding().access().observe(plan.integration(), configuration.path()).thenCompose(current -> {
                    Map<String, String> values = "voicechat".equals(plan.integration().id()) ? Map.of("bind_address", "*") : Map.of("host.ip", quote("0.0.0.0"));
                    List<PluginForwarding.NetworkEdit> edits = new ArrayList<>();
                    edits.add(new PluginForwarding.NetworkEdit(configuration.path(), current, ReProxyIntegrations.patch(plan.integration(), current.content(), values)));
                    if (!"plasmovoice".equals(plan.integration().id())) return member.forwarding().configureNetwork(plan.integration(), edits, plan.changeId());
                    String secret = secretPath(configuration.path());
                    edits.add(new PluginForwarding.NetworkEdit(secret, plan.secrets().get(member.id()), plan.secretContent()));
                    return member.forwarding().configureNetwork(plan.integration(), edits, plan.changeId());
                });
            });
        }
        return result.thenCompose(ignored -> {
            require(plan.network());
            Configuration proxy = plan.configurations().getFirst();
            return proxy.member().forwarding().access().observe(plan.integration(), proxy.path()).thenCompose(current -> {
                Map<String, String> values = "voicechat".equals(plan.integration().id()) ? Map.of("bind_address", "*") : routes;
                List<PluginForwarding.NetworkEdit> edits = new ArrayList<>();
                edits.add(new PluginForwarding.NetworkEdit(proxy.path(), current, ReProxyIntegrations.patch(plan.integration(), current.content(), values)));
                if ("plasmovoice".equals(plan.integration().id())) edits.add(new PluginForwarding.NetworkEdit(plan.secretPath(), plan.secret(), plan.secretContent()));
                return proxy.member().forwarding().configureNetwork(plan.integration(), edits, plan.changeId());
            });
        });
    }

    private Async<Void> disconnect(Plan plan) {
        Async<Void> result = Async.completed(null);
        for (Configuration configuration : plan.configurations().reversed()) result = result.thenCompose(ignored -> {
            require(plan.network());
            return configuration.member().forwarding().disconnect(plan.integration());
        });
        return result;
    }

    private Async<Void> rollback(Plan plan, List<Configuration> changed, Throwable failure) {
        Async<Void> result = Async.completed(null);
        for (Configuration configuration : plan.configurations().reversed()) if (!changed.contains(configuration)) result = result.thenCompose(ignored ->
                configuration.member().forwarding().rollbackNetwork(plan.integration(), plan.changeId())
                        .thenCompose(restored -> configuration.member().forwarding().finishNetwork(plan.integration(), plan.network().stamp())).exceptionally(error -> {
                    failure.addSuppressed(error);
                    return null;
                }));
        for (Configuration configuration : changed.reversed()) result = result.thenCompose(ignored ->
                configuration.member().forwarding().disconnect(plan.integration()).exceptionally(error -> {
                    failure.addSuppressed(error);
                    return null;
                }));
        return result.thenCompose(ignored -> Async.failed(failure));
    }

    private synchronized void require(Network value) {
        if (closed || !admitted || !Objects.equals(value, network)) throw new IllegalStateException("Network Membership Changed. Reopen The Server And Retry");
    }

    private static boolean networked(ReProxyIntegrations.Integration integration) {
        return List.of("geyser", "voicechat", "plasmovoice").contains(integration.id());
    }

    private static String secretPath(String config) {
        return config.startsWith("config/") ? "config/plasmovoice/server/forwarding-secret" : config.substring(0, config.lastIndexOf('/') + 1) + "forwarding-secret";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String address(String host, int port) {
        if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) throw new IllegalStateException("The Backend UDP Address Is Unavailable");
        return (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null || cause.getMessage().isBlank() ? "Network Plugin Setup Failed" : cause.getMessage();
    }

    @Override public void admitConfig(String path) { own.admitConfig(path); }
    @Override public State configState(String path) { return own.configState(path); }
    @Override public AutoCloseable watchConfig(String path, Runnable changed) { return own.watchConfig(path, changed); }
    @Override public Async<Void> changed(String path) { return own.changed(path); }
    @Override public Async<Void> unlinkConfig(String path) { return own.unlinkConfig(path); }
    @Override public Async<Void> reconcile() { return own.reconcile(); }
    @Override public boolean ownsPort(String id) { return own.ownsPort(id); }

    @Override
    public synchronized void close() {
        closed = true;
        if (loading != null) loading.cancel();
        loading = null;
        integrations = Map.of();
        enabled = Map.of();
        clearWatches();
        network = null;
    }
}
