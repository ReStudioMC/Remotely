package redxax.oxy.remotely.servers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.TaskSchedulers;
import redxax.oxy.remotely.servers.ReProxyConnectorCapability.Server;
import restudio.rebase.reproxy.ReProxyClient;
import restudio.rebase.reproxy.ReProxyModels.Address;
import restudio.rebase.reproxy.ReProxyModels.AddressCheck;
import restudio.rebase.reproxy.ReProxyModels.AddressSpec;
import restudio.rebase.reproxy.ReProxyModels.Binding;
import restudio.rebase.reproxy.ReProxyModels.Catalog;
import restudio.rebase.reproxy.ReProxyModels.Connection;
import restudio.rebase.reproxy.ReProxyModels.ConnectionSpec;
import restudio.rebase.reproxy.ReProxyModels.ConnectionPatch;
import restudio.rebase.reproxy.ReProxyModels.Endpoint;
import restudio.rebase.reproxy.ReProxyModels.EndpointSpec;
import restudio.rebase.reproxy.ReProxyModels.Operation;
import restudio.rebase.reproxy.ReProxyModels.PublicPort;
import restudio.rebase.reproxy.ReProxyModels.Suffix;
import restudio.rebase.reproxy.ReProxyModels.Target;
import restudio.rebase.reproxy.ReProxyModels.Summary;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

public final class ReProxyManager {
    private static final String CONNECTION_KEY = "reproxy.connectionId";
    private static final String BINDING_KEY = "reproxy.bindingId";
    private static final String SUFFIX_KEY = "reproxy.suffixId";
    private static final String QUICK_ENDPOINT_KEY = "reproxy.quickJavaEndpointId";
    private static final String CREATION_KEY = "reproxy.creationIntent";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_RECONNECTS = 5;
    private static final BrowserWork.Executor work = BrowserWork.serialExecutor("ReProxy ");
    private static final Map<String, Entry> entries = new HashMap<>();
    private static volatile Map<String, Status> statuses = Map.of();
    private static volatile Map<String, Status> connectionStatuses = Map.of();
    private static Map<String, Entry> connectionEntries = Map.of();
    private static volatile ReProxyClient client;
    private static volatile ReProxyConnectorCapability connector = ReProxyConnectorCapability.unavailable();
    private static long nextGeneration;
    private static volatile long accountGeneration;
    private static long summaryGeneration;
    private static volatile Summary summary;
    private static volatile Map<String, List<Connection>> serverConnections = Map.of();
    private static volatile Map<String, Connection> knownConnections = Map.of();
    private static final Map<String, String> selections = new HashMap<>();
    private static Async<Summary> summaryRequest;
    private static Catalog serverCatalog;
    private static long serverCatalogExpiresAt;
    private static Async<Catalog> catalogRequest;
    private static final Map<String, ArrayDeque<ServerJob<?>>> serverJobs = new HashMap<>();
    private static final Map<String, PrepareState> prepareStates = new HashMap<>();
    private static final Map<String, List<ServerWatch>> serverWatches = new HashMap<>();
    private static final int MAX_SERVER_JOBS = 16;
    private static final int MAX_PREPARES = 16;
    private static final Duration SERVER_WAIT = Duration.ofMinutes(2);
    private static volatile Supplier<CreationAdmission> creationAdmission = () -> {
        throw new IllegalStateException("Local Server Address Creation Is Unavailable");
    };

    private ReProxyManager() {
    }

    public record Status(String instanceId, String connectionId, String bindingId, String phase, String reason, List<Endpoint> endpoints, String address, int localPort) {
        public Status {
            endpoints = List.copyOf(endpoints);
        }
        public boolean ready() { return "ONLINE".equals(phase); }
    }

    public record CreationIntent(int schemaVersion, String account, String instanceId, String requestId, String name,
                                 int port, AddressSpec address, String addressKey, String bindingKey, String connectionKey,
                                 Address claimedAddress, Binding registeredBinding, ConnectionSpec connectionRequest,
                                 String updateKey, String updateConnectionId, String updateRevision, ConnectionPatch updateRequest) {
        public CreationIntent {
            if (schemaVersion != 1 || account == null || account.isBlank() || instanceId == null || instanceId.isBlank()
                    || requestId == null || requestId.isBlank() || name == null || name.isBlank() || port < 1 || port > 65535
                    || address == null || address.label() == null || !address.label().matches("[a-z0-9](?:[a-z0-9-]{1,30}[a-z0-9])")
                    || address.suffixId() == null || address.suffixId().isBlank()) {
                throw new IllegalArgumentException("Saved Server Address Request Is Invalid");
            }
            UUID.fromString(requestId);
            UUID.fromString(addressKey);
            UUID.fromString(bindingKey);
            UUID.fromString(connectionKey);
            UUID.fromString(updateKey);
            if (claimedAddress != null && !sameAddress(address, claimedAddress)) {
                throw new IllegalArgumentException("Saved Server Address Changed");
            }
            if (registeredBinding != null && (!"LOCAL".equals(registeredBinding.kind()) || !instanceId.equals(registeredBinding.instanceId()))) {
                throw new IllegalArgumentException("Saved Server Address Belongs To Another Server");
            }
            if (connectionRequest != null && (registeredBinding == null || claimedAddress == null
                    || !registeredBinding.id().equals(connectionRequest.bindingId()) || !claimedAddress.id().equals(connectionRequest.addressId())
                    || !name.equals(connectionRequest.name()) || !"STOPPED".equals(connectionRequest.desiredState())
                    || connectionRequest.endpoints().size() != 1 || connectionRequest.endpoints().getFirst().target() == null
                    || !registeredBinding.id().equals(connectionRequest.endpoints().getFirst().target().bindingId())
                    || port != connectionRequest.endpoints().getFirst().target().port()
                    || !"TCP".equals(connectionRequest.endpoints().getFirst().protocol())
                    || !"JAVA_HOSTNAME".equals(connectionRequest.endpoints().getFirst().routingMode())
                    || !connectionRequest.endpoints().getFirst().enabled() || connectionRequest.address() != null)) {
                throw new IllegalArgumentException("Saved Server Connection Request Is Invalid");
            }
            if (updateRequest != null && (updateConnectionId == null || updateConnectionId.isBlank()
                    || updateRevision == null || updateRevision.isBlank() || registeredBinding == null || claimedAddress == null
                    || updateRequest.name() != null || updateRequest.bindingId() != null || updateRequest.desiredState() != null
                    || updateRequest.addressId() != null && !claimedAddress.id().equals(updateRequest.addressId())
                    || updateRequest.endpoints() == null || updateRequest.endpoints().isEmpty() || updateRequest.endpoints().size() > 64
                    || updateRequest.endpoints().stream().anyMatch(endpoint -> endpoint.target() == null
                    || !registeredBinding.id().equals(endpoint.target().bindingId()) || endpoint.target().port() < 1 || endpoint.target().port() > 65535)
                    || updateRequest.endpoints().stream().filter(endpoint -> "JAVA_HOSTNAME".equals(endpoint.routingMode())
                    && "TCP".equals(endpoint.protocol()) && endpoint.enabled() && endpoint.target().port() == port).count() != 1)) {
                throw new IllegalArgumentException("Saved Server Address Change Is Invalid");
            }
        }

        public static CreationIntent create(String account, Server server, int port, AddressSpec address, String requestId) {
            if (server == null || !server.local()) throw new IllegalArgumentException("Choose A Local Server To Create An Address");
            return new CreationIntent(1, account, server.id(), requestId, server.name(), port, address,
                    operationKey(), operationKey(), operationKey(), null, null, null, operationKey(), "", "", null);
        }

        public boolean sameRequest(CreationIntent value) {
            return value != null && account.equals(value.account) && instanceId.equals(value.instanceId)
                    && requestId.equals(value.requestId) && name.equals(value.name) && port == value.port && address.equals(value.address)
                    && addressKey.equals(value.addressKey) && bindingKey.equals(value.bindingKey) && connectionKey.equals(value.connectionKey)
                    && updateKey.equals(value.updateKey);
        }

        private CreationIntent withAddress(Address value) {
            return new CreationIntent(schemaVersion, account, instanceId, requestId, name, port, address, addressKey,
                    bindingKey, connectionKey, value, registeredBinding, connectionRequest, updateKey, updateConnectionId, updateRevision, updateRequest);
        }

        private CreationIntent withBinding(Binding value) {
            return new CreationIntent(schemaVersion, account, instanceId, requestId, name, port, address, addressKey,
                    bindingKey, connectionKey, claimedAddress, value, connectionRequest, updateKey, updateConnectionId, updateRevision, updateRequest);
        }

        private CreationIntent withConnection(ConnectionSpec value) {
            return new CreationIntent(schemaVersion, account, instanceId, requestId, name, port, address, addressKey,
                    bindingKey, connectionKey, claimedAddress, registeredBinding, value, updateKey, updateConnectionId, updateRevision, updateRequest);
        }

        private CreationIntent withUpdate(Connection value, ConnectionPatch patch) {
            return new CreationIntent(schemaVersion, account, instanceId, requestId, name, port, address, addressKey,
                    bindingKey, connectionKey, claimedAddress == null ? value.address() : claimedAddress, value.binding(), connectionRequest,
                    updateKey, value.id(), value.revision(), patch);
        }
    }

    public record CreationAdmission(String account, BooleanSupplier current) {
        public CreationAdmission {
            Objects.requireNonNull(account, "Server Address Account Is Required");
            Objects.requireNonNull(current, "Server Address Ownership Is Required");
        }
    }

    public static void configureCreation(Supplier<CreationAdmission> admission) {
        creationAdmission = Objects.requireNonNull(admission, "Server Address Admission Is Required");
    }

    public static Async<Void> prepareCreation(Server instance, CreationIntent requested) {
        try {
            CreationAdmission admission = creationAdmission(requested);
            return prepareCreation(instance, requested, admission.current());
        } catch (Throwable failure) {
            return Async.failed(failure);
        }
    }

    public static CreationAdmission creationAdmission(CreationIntent requested) {
        CreationAdmission admission = creationAdmission.get();
        if (!admission.account().equals(requested.account())) throw new IllegalStateException("Sign In To The Account That Created This Server Address");
        if (!admission.current().getAsBoolean()) throw new Async.Cancellation();
        return admission;
    }

    private static final class CreationOwner {
        final Server instance;
        final BooleanSupplier admitted;
        CreationIntent intent;
        String stamp;

        CreationOwner(Server instance, CreationIntent intent, BooleanSupplier admitted) {
            this.instance = instance;
            this.intent = intent;
            this.admitted = admitted;
            stamp = instance.setting(CREATION_KEY, "");
        }
    }

    public static boolean pendingCreation(Server instance) {
        return instance != null && !instance.setting(CREATION_KEY, "").isBlank();
    }

    public static CreationIntent creationIntent(Server instance) {
        String saved = instance == null ? "" : instance.setting(CREATION_KEY, "");
        if (saved.isBlank()) return null;
        if (saved.length() > 32_768) throw new IllegalStateException("Saved Server Address Request Is Too Large");
        CreationIntent intent = GSON.fromJson(saved, CreationIntent.class);
        if (intent == null || !instance.id().equals(intent.instanceId())) throw new IllegalStateException("Saved Server Address Belongs To Another Server");
        return intent;
    }

    public static Async<Void> prepareCreation(Server instance, CreationIntent requested, BooleanSupplier admitted) {
        try {
            Objects.requireNonNull(requested, "Server Address Request Is Required");
            Objects.requireNonNull(admitted, "Server Address Ownership Is Required");
            if (instance == null || !instance.local() || !instance.id().equals(requested.instanceId()) || !admitted.getAsBoolean()) {
                throw new Async.Cancellation();
            }
            if (instance.port() != requested.port()) throw new IllegalStateException("Server Port Changed. Review Its Saved Address Request");
            CreationIntent saved = creationIntent(instance);
            if (saved != null && !requested.sameRequest(saved)) throw new IllegalStateException("Resume The Saved Server Address Before Choosing Another Address");
            CreationOwner owner = new CreationOwner(instance, saved == null ? requested : saved, admitted);
            return serverQueue(serverTarget(instance), owner, job -> prepareServer(job, "", owner.intent.address()).thenCompose(connection -> {
                checkServerJob(job);
                return serverJobRequest(job, setConnection(instance, connection, () -> {
                    checkServerJob(job);
                    return true;
                }));
            }).thenCompose(ignored -> clearCreation(job)));
        } catch (Throwable failure) {
            return Async.failed(failure);
        }
    }

    private static final class Entry {
        final String key;
        final long generation;
        Server instance;
        final ReProxyClient api;
        boolean managedQuick;
        boolean preparingQuick;
        int quickPort;
        int localPort;
        Async<Void> ready = Async.pending();
        Connection connection;
        Binding binding;
        ReProxyConnectorCapability.Handle handle;
        ReProxyClient.Poll poll;
        String pollId = "";
        TaskScheduler.ScheduledTask retry;
        Async<?> request;
        String phase = "STARTING";
        String reason = "";
        String startKey;
        boolean desired = true;
        boolean connecting;
        boolean settled = true;
        boolean activated;
        String activatedRevision = "";
        int attempts;
        long socketGeneration;

        Entry(String key, Server instance) {
            this(key, instance, false);
        }

        Entry(String key, Server instance, boolean managedQuick) {
            this.key = key;
            this.managedQuick = managedQuick;
            this.instance = instance;
            this.localPort = instance == null ? 0 : instance.port();
            this.api = client;
            this.generation = ++nextGeneration;
        }
    }

    private static final class ServerJob<T> {
        final ReProxyTarget target;
        final ReProxyClient api;
        final long generation;
        final Async<T> result;
        final Function<ServerJob<T>, Async<T>> action;
        final CreationOwner creation;
        long deadline;
        boolean started;
        Async<T> source;
        ReProxyClient.Poll poll;
        boolean ownsPoll;
        TaskScheduler.ScheduledTask expiry;

        ServerJob(ReProxyTarget target, Async<T> result, Function<ServerJob<T>, Async<T>> action, CreationOwner creation) {
            this.target = target;
            this.api = client;
            this.generation = accountGeneration;
            this.result = result;
            this.action = action;
            this.creation = creation;
        }
    }

    private static final class ServerWatch {
        final ReProxyTarget target;
        final Consumer<Connection> listener;
        long generation;
        volatile boolean closed;

        ServerWatch(ReProxyTarget target, Consumer<Connection> listener) {
            this.target = target;
            this.listener = listener;
            this.generation = client == null ? -1 : accountGeneration;
        }
    }

    private static final class PrepareState {
        final ReProxyClient api;
        final long generation;
        AddressSpec addressRequest;
        Address address;
        PrepareMutation mutation;

        PrepareState(ServerJob<?> job) {
            api = job.api;
            generation = job.generation;
        }
    }

    private static final class PrepareMutation {
        final String key;
        final AddressSpec address;
        final ConnectionSpec connection;
        Operation receipt;
        boolean uncertain;

        PrepareMutation(AddressSpec address, ConnectionSpec connection) {
            this(address, connection, operationKey());
        }

        PrepareMutation(AddressSpec address, ConnectionSpec connection, String key) {
            this.address = address;
            this.connection = connection;
            this.key = key;
        }
    }

    public static void configure(ReProxyClient api, ReProxyConnectorCapability capability) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(capability, "capability");
        work.execute(() -> {
            if (client == api && connector == capability) return;
            cancelAll("ReProxy Runtime Changed");
            client = api;
            connector = capability;
        });
    }

    public static ReProxyConnectorCapability.Availability availability(Connection connection) {
        if (connection == null || connection.binding() == null) return new ReProxyConnectorCapability.Availability(false, "Choose A Server Binding");
        return connector.availability(connection.binding());
    }

    public static Summary accountSummary() { return summary; }

    public static Async<Summary> serverSummary() {
        Summary value = summary;
        return value == null ? refreshSummary() : Async.completed(value);
    }

    public static Async<Catalog> serverCatalog() {
        Async<Catalog> result = Async.pending();
        work.execute(() -> {
            try {
                requireRuntime();
                if (serverCatalog != null && Clock.system().millis() < serverCatalogExpiresAt) { result.complete(serverCatalog); return; }
                if (catalogRequest != null) { forward(catalogRequest, result); return; }
                ReProxyClient api = client;
                long generation = accountGeneration;
                Async<Catalog> request = serverRequest(api, generation, api.catalog());
                catalogRequest = request;
                forward(request, result);
                request.whenComplete((value, error) -> {
                    if (catalogRequest != request) return;
                    catalogRequest = null;
                    if (error == null) {
                        serverCatalog = value;
                        try { serverCatalogExpiresAt = Instant.parse(value.expiresAt()).toEpochMilli(); }
                        catch (RuntimeException ignored) { serverCatalogExpiresAt = 0; }
                    }
                });
            } catch (Throwable error) { result.fail(error); }
        });
        return result;
    }

    public static Connection serverConnection(ReProxyTarget target) {
        if (target == null) return null;
        if (!target.connectionId().isBlank()) {
            Connection connection = knownConnections.get(target.connectionId());
            if (connection == null) throw new IllegalStateException("The Saved Server Connection Is Unavailable");
            if (!target.matches(connection.binding()) && !legacyAttachment(target, connection)) throw new IllegalStateException("The Saved Connection Belongs To Another Server");
            return connection;
        }
        return connectionForServer(target.key());
    }

    public static AutoCloseable watchServer(ReProxyTarget target, Consumer<Connection> listener) {
        Objects.requireNonNull(target, "Server Is Required");
        Objects.requireNonNull(listener, "Connection Listener Is Required");
        ServerWatch watch = new ServerWatch(target, listener);
        work.execute(() -> {
            if (watch.closed) return;
            if (watch.generation < 0) watch.generation = accountGeneration;
            if (watch.generation != accountGeneration) return;
            serverWatches.computeIfAbsent(target.key(), ignored -> new ArrayList<>()).add(watch);
            notifyWatch(watch);
        });
        return () -> {
            watch.closed = true;
            work.execute(() -> {
                List<ServerWatch> watches = serverWatches.get(target.key());
                if (watches == null) return;
                watches.remove(watch);
                if (watches.isEmpty()) serverWatches.remove(target.key());
            });
        };
    }

    private static void notifyWatch(ServerWatch watch) {
        if (watch.closed || watch.generation != accountGeneration) return;
        Connection connection;
        try { connection = serverConnection(watch.target); }
        catch (IllegalStateException error) { connection = null; }
        try { watch.listener.accept(connection); }
        catch (RuntimeException ignored) { }
    }

    private static void notifyServer(String key) {
        for (ServerWatch watch : List.copyOf(serverWatches.getOrDefault(key, List.of()))) notifyWatch(watch);
    }

    private static <T> Async<T> serverQueue(ReProxyTarget target, Function<ServerJob<T>, Async<T>> action) {
        return serverQueue(target, null, action);
    }

    private static <T> Async<T> serverQueue(ReProxyTarget target, CreationOwner creation, Function<ServerJob<T>, Async<T>> action) {
        Async<T> result = Async.pending();
        work.execute(() -> {
            try {
                requireRuntime();
                if (target == null) throw new IllegalArgumentException("Choose A Server");
                ArrayDeque<ServerJob<?>> queue = serverJobs.computeIfAbsent(target.key(), ignored -> new ArrayDeque<>());
                if (queue.size() >= MAX_SERVER_JOBS) throw new IllegalStateException("This Server Has Too Many Changes Waiting");
                ServerJob<T> job = new ServerJob<>(target, result, action, creation);
                job.expiry = TaskSchedulers.current().schedule(() -> work.execute(() -> expireServerJob(job)), SERVER_WAIT);
                queue.addLast(job);
                result.onCancel(() -> work.execute(() -> expireServerJob(job)));
                if (queue.getFirst() == job) startServerJob(job);
            } catch (Throwable error) { result.fail(error); }
        });
        return result;
    }

    private static <T> void startServerJob(ServerJob<T> job) {
        if (job.result.isDone()) { finishServerJob(job); return; }
        job.started = true;
        job.deadline = Clock.system().millis() + SERVER_WAIT.toMillis();
        if (job.expiry != null) job.expiry.cancel();
        try {
            job.expiry = TaskSchedulers.current().schedule(() -> work.execute(() -> expireServerJob(job)), SERVER_WAIT);
            checkServerJob(job);
            Async<Void> admission = job.creation == null ? Async.completed(null) : saveCreation(job, job.creation.intent);
            job.source = admission.thenCompose(ignored -> recoverPrepare(job)).thenCompose(ignored -> { checkServerJob(job); return job.action.apply(job); });
            job.source.whenComplete((value, error) -> work.execute(() -> {
                if (client != job.api || accountGeneration != job.generation) job.result.fail(new Async.Cancellation());
                else if (error == null) job.result.complete(value); else job.result.fail(error);
                finishServerJob(job);
            }));
        } catch (Throwable error) { job.result.fail(error); finishServerJob(job); }
    }

    private static void expireServerJob(ServerJob<?> job) {
        PrepareState state = prepareStates.get(job.target.key());
        if (state != null && state.api == job.api && state.generation == job.generation && state.mutation != null && state.mutation.receipt == null) state.mutation.uncertain = true;
        if (!job.result.isDone()) job.result.fail(new IllegalStateException("This Server Is Taking Too Long To Update. Try Again Shortly"));
        if (job.poll != null && job.ownsPoll) job.poll.cancel();
        if (job.source != null) job.source.cancel();
        finishServerJob(job);
    }

    private static void finishServerJob(ServerJob<?> job) {
        if (job.expiry != null) job.expiry.cancel();
        ArrayDeque<ServerJob<?>> queue = serverJobs.get(job.target.key());
        if (queue == null || !queue.remove(job)) return;
        if (queue.isEmpty()) serverJobs.remove(job.target.key());
        else if (!queue.getFirst().started) startServerJob(queue.getFirst());
    }

    private static void checkServerJob(ServerJob<?> job) {
        if (job.api != client || job.generation != accountGeneration || job.result.isDone()) throw new Async.Cancellation();
        if (Clock.system().millis() >= job.deadline) throw new IllegalStateException("This Server Is Taking Too Long To Update. Try Again Shortly");
        CreationOwner owner = job.creation;
        if (owner != null && (!owner.admitted.getAsBoolean() || !owner.instance.local() || !owner.intent.instanceId().equals(owner.instance.id())
                || owner.instance.port() != owner.intent.port() || !owner.stamp.equals(owner.instance.setting(CREATION_KEY, "")))) throw new Async.Cancellation();
    }

    private static Async<Void> saveCreation(ServerJob<?> job, CreationIntent intent) {
        checkServerJob(job);
        CreationOwner owner = Objects.requireNonNull(job.creation, "Server Address Owner Is Required");
        owner.intent = intent;
        owner.stamp = GSON.toJson(intent);
        owner.instance.putSetting(CREATION_KEY, owner.stamp);
        return serverJobRequest(job, owner.instance.save());
    }

    private static Async<Void> clearCreation(ServerJob<?> job) {
        checkServerJob(job);
        CreationOwner owner = job.creation;
        String saved = owner.stamp;
        owner.stamp = "";
        owner.instance.putSetting(CREATION_KEY, "");
        return serverJobRequest(job, owner.instance.save()).exceptionallyCompose(failure -> {
            if (owner.admitted.getAsBoolean() && job.api == client && job.generation == accountGeneration
                    && owner.instance.setting(CREATION_KEY, "").isBlank()) owner.instance.putSetting(CREATION_KEY, saved);
            owner.stamp = saved;
            return Async.failed(failure);
        });
    }

    private static <T> Async<T> serverJobRequest(ServerJob<?> job, Async<T> source) {
        return serverRequest(job.api, job.generation, source).thenApply(value -> { checkServerJob(job); return value; });
    }

    private static <T> Async<T> serverJobCall(ServerJob<?> job, Supplier<Async<T>> request) {
        try {
            checkServerJob(job);
            return serverJobRequest(job, request.get());
        } catch (Throwable failure) {
            return Async.failed(failure);
        }
    }

    public static Async<Connection> serverReady(ReProxyTarget target, String connectionId) {
        return serverQueue(target, job -> serverReady(job, connectionId));
    }

    private static Async<Connection> serverReady(ServerJob<?> job, String connectionId) {
        return serverReady(job, connectionId, true);
    }

    private static Async<Connection> serverReady(ServerJob<?> job, String connectionId, boolean activate) {
        checkServerJob(job);
        String id = connectionId == null || connectionId.isBlank() ? job.target.connectionId() : connectionId;
        if (!job.target.connectionId().isBlank() && !job.target.connectionId().equals(id)) throw new IllegalStateException("The Saved Server Connection Changed");
        if (!id.isBlank()) return serverJobRequest(job, job.api.connection(id)).thenCompose(connection -> awaitServer(job, connection, 0, activate));
        return serverJobRequest(job, serverSummary()).thenCompose(ignored -> {
            checkServerJob(job);
            Connection connection = serverConnection(job.target);
            if (connection == null) throw new IllegalStateException("Choose A Minecraft Address For This Server");
            return serverJobRequest(job, job.api.connection(connection.id())).thenCompose(current -> awaitServer(job, current, 0, activate));
        });
    }

    private static Async<Connection> awaitServer(ServerJob<?> job, Connection connection, int attempt) {
        return awaitServer(job, connection, attempt, true);
    }

    private static Async<Connection> awaitServer(ServerJob<?> job, Connection connection, int attempt, boolean activate) {
        checkServerJob(job);
        boolean legacy = legacyAttachment(job.target, connection);
        if (!job.target.matches(connection.binding()) && !legacy) throw new IllegalStateException("The Saved Connection Belongs To Another Server");
        String pending = connection.pendingOperationId();
        if (pending != null && !pending.isBlank()) {
            if (attempt >= 8) throw new IllegalStateException("This Server Is Still Updating");
            return serverJobRequest(job, job.api.operation(pending)).thenCompose(receipt -> {
                if (!connection.id().equals(receipt.resourceId())) throw new IllegalStateException("The Server Change Belongs To Another Connection");
                return settleServerJob(job, receipt, false, activate);
            }).thenCompose(ignored -> serverJobRequest(job, job.api.connection(connection.id())))
                    .thenCompose(current -> awaitServer(job, current, attempt + 1, activate));
        }
        if (legacy && activate) return recoverAttachment(job, connection);
        admitServer(job.target, connection);
        if (!activate || !"ENABLED".equals(connection.desiredState())) return Async.completed(connection);
        return serverJobRequest(job, attachConnection(connection)).thenApply(ignored -> connection);
    }

    private static boolean legacyAttachment(ReProxyTarget target, Connection connection) {
        Binding binding = connection == null ? null : connection.binding();
        return target != null && connection != null && !target.connectionId().isBlank() && target.connectionId().equals(connection.id()) && binding != null
                && "LOCAL".equals(binding.kind()) && binding.instanceId() != null && binding.instanceId().startsWith("legacy:");
    }

    private static Async<Connection> recoverAttachment(ServerJob<?> job, Connection connection) {
        return serverJobRequest(job, serverSummary()).thenCompose(summary -> serverBinding(job, summary)).thenCompose(binding -> {
            checkServerJob(job);
            List<EndpointSpec> endpoints = connection.endpoints().stream().map(endpoint -> {
                if (endpoint.target() == null || !connection.binding().id().equals(endpoint.target().bindingId())) throw new IllegalStateException("A Saved Port Belongs To Another Server Target");
                return new EndpointSpec(endpoint.id(), endpoint.name(), endpoint.protocol(), endpoint.routingMode(), new Target(binding.id(), endpoint.target().port()),
                        Objects.requireNonNull(endpoint.publicPortPolicy(), "Saved Port Settings Are Missing"), endpoint.enabled());
            }).toList();
            ConnectionPatch patch = new ConnectionPatch(null, binding.id(), endpoints, null);
            return serverJobRequest(job, job.api.updateConnection(connection.id(), patch, connection.revision(), operationKey()))
                    .thenCompose(receipt -> settleServerJob(job, receipt, true));
        }).thenCompose(ignored -> serverJobRequest(job, job.api.connection(connection.id()))).thenCompose(current -> awaitServer(job, current, 0));
    }

    public static Async<Operation> serverChange(ReProxyTarget target, String connectionId, BiFunction<ReProxyClient, Connection, Async<Operation>> action) {
        return serverQueue(target, job -> serverReady(job, connectionId).thenCompose(connection -> {
            checkServerJob(job);
            return serverJobRequest(job, action.apply(job.api, connection)).thenCompose(receipt -> {
                if (!connection.id().equals(receipt.resourceId())) throw new IllegalStateException("The Server Change Belongs To Another Connection");
                return settleServerJob(job, receipt, true);
            });
        }));
    }

    private static Async<Operation> settleServerJob(ServerJob<?> job, Operation receipt, boolean requireSuccess) {
        return settleServerJob(job, receipt, requireSuccess, true);
    }

    private static void checkServerReceipt(ServerJob<?> job, Operation receipt, String resourceId) {
        checkServerJob(job);
        if (receipt == null || receipt.id() == null || receipt.id().isBlank() || receipt.resourceId() == null || receipt.resourceId().isBlank()
                || resourceId != null && !resourceId.equals(receipt.resourceId())) {
            throw new IllegalStateException("The Server Change Belongs To Another Resource");
        }
        if (!receipt.pending() && !receipt.succeeded() && !receipt.failed()) throw new IllegalStateException("The Server Change State Is Unavailable");
        Connection connection = receipt.connection();
        if (connection != null) checkServerConnection(job, connection, receipt.resourceId());
        else if (receipt.address() != null && !receipt.resourceId().equals(receipt.address().id())) throw new IllegalStateException("The Address Change Belongs To Another Resource");
    }

    private static void checkServerConnection(ServerJob<?> job, Connection connection, String connectionId) {
        checkServerJob(job);
        if (connection == null || !connectionId.equals(connection.id())) throw new IllegalStateException("The Server Change Belongs To Another Connection");
        if (!job.target.matches(connection.binding()) && !legacyAttachment(job.target, connection)) throw new IllegalStateException("The Saved Connection Belongs To Another Server");
        if (!job.target.connectionId().isBlank() && !job.target.connectionId().equals(connection.id())) throw new IllegalStateException("The Saved Server Connection Changed");
    }

    private static Async<Operation> settleServerJob(ServerJob<?> job, Operation receipt, boolean requireSuccess, boolean activate) {
        return settleServerJob(job, receipt, requireSuccess, activate, null);
    }

    private static void checkPreparedConnection(ConnectionSpec expected, Connection connection) {
        if (expected != null && connection != null && (!expected.bindingId().equals(connection.binding().id()) || connection.address() == null
                || !expected.addressId().equals(connection.address().id()))) throw new IllegalStateException("The Address Setup Returned Another Server Connection");
    }

    private static Async<Operation> settleServerJob(ServerJob<?> job, Operation receipt, boolean requireSuccess, boolean activate, ConnectionSpec expected) {
        checkServerJob(job);
        checkServerReceipt(job, receipt, null);
        checkPreparedConnection(expected, receipt.connection());
        admitReceipt(receipt, activate);
        Entry owner = byConnection(receipt.resourceId());
        boolean shared = owner != null && owner.poll != null && receipt.id().equals(owner.pollId) && !owner.poll.result().isDone();
        job.ownsPoll = !shared;
        job.poll = shared ? owner.poll : job.api.settle(receipt, Duration.ofMillis(Math.max(1, job.deadline - Clock.system().millis())), Clock.system(), TaskSchedulers.current());
        return serverJobRequest(job, job.poll.result()).thenCompose(operation -> {
            checkServerReceipt(job, operation, receipt.resourceId());
            checkPreparedConnection(expected, operation.connection());
            if (!receipt.id().equals(operation.id())) throw new IllegalStateException("The Server Change Identity Changed");
            Async<Operation> result = !requireSuccess || operation.succeeded() ? Async.completed(operation)
                    : Async.failed(new IllegalStateException(operation.error() == null ? "Could Not Complete This Change" : operation.error().message()));
            if (operation.connection() == null) return result;
            return serverJobRequest(job, job.api.connection(operation.connection().id())).thenCompose(connection -> {
                checkServerConnection(job, connection, receipt.resourceId());
                checkPreparedConnection(expected, connection);
                admitServer(job.target, connection);
                settleEntry(connection);
                if (!activate || !"ENABLED".equals(connection.desiredState()) || legacyAttachment(job.target, connection)) return result;
                return serverJobRequest(job, attachConnection(connection)).thenCompose(ignored -> result);
            });
        });
    }

    private static PrepareState prepareState(ServerJob<?> job, boolean create) {
        checkServerJob(job);
        PrepareState state = prepareStates.get(job.target.key());
        if (state != null && (state.api != job.api || state.generation != job.generation)) throw new Async.Cancellation();
        if (state == null && create) {
            if (prepareStates.size() >= MAX_PREPARES) throw new IllegalStateException("Finish A Previous Address Setup Before Creating Another");
            state = new PrepareState(job);
            prepareStates.put(job.target.key(), state);
        }
        return state;
    }

    private static Async<Void> recoverPrepare(ServerJob<?> job) {
        PrepareState state = prepareState(job, false);
        if (state == null || state.mutation == null) return Async.completed(null);
        if (job.creation != null && !state.mutation.key.equals(state.mutation.connection == null
                ? job.creation.intent.addressKey() : job.creation.intent.connectionKey())) {
            return Async.failed(new IllegalStateException("The Previous Server Address Request Still Needs Recovery"));
        }
        return prepareMutation(job, state, state.mutation, false).thenAccept(ignored -> { });
    }

    private static Async<Operation> prepareMutation(ServerJob<?> job, PrepareState state, PrepareMutation mutation, boolean requireSuccess) {
        checkServerJob(job);
        if (state.mutation != mutation) throw new IllegalStateException("The Address Setup Changed");
        Async<Operation> request = mutation.receipt != null ? Async.completed(mutation.receipt)
                : mutation.connection == null ? job.api.claimAddress(mutation.address.label(), mutation.address.suffixId(), mutation.key)
                : job.api.createConnection(mutation.connection, mutation.key);
        return serverJobRequest(job, request).thenCompose(receipt -> {
            checkServerReceipt(job, receipt, mutation.receipt == null ? null : mutation.receipt.resourceId());
            if (mutation.connection == null && receipt.connection() != null) throw new IllegalStateException("The Address Setup Returned Another Connection");
            mutation.receipt = receipt;
            return settleServerJob(job, receipt, false, true, mutation.connection);
        }).thenCompose(operation -> {
            if (!operation.succeeded()) return Async.completed(operation);
            if (mutation.connection == null) {
                Address address = operation.address();
                if (address == null || !operation.resourceId().equals(address.id()) || !sameAddress(mutation.address, address)) {
                    throw new IllegalStateException("The Address Setup Returned Another Address");
                }
                state.addressRequest = mutation.address;
                state.address = address;
                return job.creation == null ? Async.completed(operation)
                        : saveCreation(job, job.creation.intent.withAddress(address)).thenApply(ignored -> operation);
            }
            Async<Connection> canonical = operation.connection() == null ? serverJobRequest(job, job.api.connection(operation.resourceId()))
                    : Async.completed(knownConnections.get(operation.resourceId()));
            return canonical.thenApply(connection -> {
                checkServerConnection(job, connection, operation.resourceId());
                checkPreparedConnection(mutation.connection, connection);
                admitServer(job.target, connection);
                return operation;
            });
        }).thenApply(operation -> {
            state.mutation = null;
            if (mutation.connection != null && operation.succeeded() || state.address == null) prepareStates.remove(job.target.key(), state);
            if (requireSuccess && !operation.succeeded()) throw new IllegalStateException(operation.error() == null ? "Could Not Complete This Change" : operation.error().message());
            return operation;
        }).exceptionallyCompose(error -> {
            if (prepareStates.get(job.target.key()) == state && state.mutation == mutation && mutation.receipt == null) {
                boolean definite = error instanceof ReProxyClient.Failure failure && failure.status() >= 400 && failure.status() < 500
                        && failure.status() != 408 && failure.status() != 425 && failure.status() != 429;
                if (definite && !mutation.uncertain) {
                    state.mutation = null;
                    if (state.address == null) prepareStates.remove(job.target.key(), state);
                } else mutation.uncertain = true;
            }
            return Async.failed(error);
        });
    }

    private static boolean sameAddress(AddressSpec request, Address address) {
        return request != null && address != null && Objects.equals(request.label(), address.label()) && Objects.equals(request.suffixId(), address.suffixId());
    }

    private static Async<Address> claimServerAddress(ServerJob<?> job, AddressSpec address) {
        checkServerJob(job);
        if (job.creation != null && job.creation.intent.claimedAddress() != null) {
            String id = job.creation.intent.claimedAddress().id();
            return serverJobRequest(job, job.api.address(id)).thenApply(value -> {
                if (!sameAddress(address, value)) throw new IllegalStateException("Saved Server Address Changed");
                return value;
            });
        }
        PrepareState state = prepareState(job, true);
        if (state.mutation != null) throw new IllegalStateException("The Previous Address Setup Is Still Updating");
        if (address.equals(state.addressRequest) && state.address != null) return Async.completed(state.address);
        state.addressRequest = null;
        state.address = null;
        PrepareMutation mutation = new PrepareMutation(address, null, job.creation == null ? operationKey() : job.creation.intent.addressKey());
        state.mutation = mutation;
        return prepareMutation(job, state, mutation, true).thenApply(operation -> state.address);
    }

    private static Async<Connection> createServerConnection(ServerJob<?> job, ConnectionSpec spec) {
        PrepareState state = prepareState(job, true);
        if (state.mutation != null) throw new IllegalStateException("The Previous Address Setup Is Still Updating");
        ConnectionSpec frozen = job.creation == null ? spec : job.creation.intent.connectionRequest();
        if (frozen != null && !frozen.equals(spec)) throw new IllegalStateException("Saved Server Connection Changed. Resume Its Original Address Request");
        PrepareMutation mutation = new PrepareMutation(null, spec, job.creation == null ? operationKey() : job.creation.intent.connectionKey());
        state.mutation = mutation;
        Async<Void> saved = job.creation == null ? Async.completed(null) : saveCreation(job, job.creation.intent.withConnection(spec));
        return saved.thenCompose(ignored -> prepareMutation(job, state, mutation, true)).thenApply(operation -> {
            Connection connection = knownConnections.get(operation.resourceId());
            checkServerConnection(job, connection, operation.resourceId());
            return connection;
        });
    }

    public static Async<Connection> prepareServer(ReProxyTarget target, String addressId, AddressSpec address) {
        return serverQueue(target, job -> prepareServer(job, addressId, address));
    }

    private static Async<Connection> prepareServer(ServerJob<?> job, String addressId, AddressSpec address) {
        checkServerJob(job);
        ReProxyTarget target = job.target;
        if (!"LOCAL".equals(target.binding().kind())) throw new IllegalArgumentException("Choose A Private Server");
        if (target.port() < 1 || target.port() > 65535) throw new IllegalArgumentException("The Server Port Is Unavailable");
        Async<Connection> attachment = target.connectionId().isBlank() ? Async.completed(null) : serverReady(job, target.connectionId());
        return attachment.thenCompose(attached -> serverJobRequest(job, serverSummary()).thenCompose(value -> {
            Connection existing;
            try { existing = attached == null ? serverConnection(target) : attached; }
            catch (IllegalStateException error) {
                if (!target.connectionId().isBlank() || addressId == null || addressId.isBlank()) throw error;
                existing = value.connections().stream().filter(connection -> connection.address() != null && addressId.equals(connection.address().id()))
                        .filter(connection -> target.matches(connection.binding())).findFirst().orElse(null);
                if (existing == null) throw new IllegalStateException("Choose An Address Already Assigned To This Server");
            }
            Connection saved = existing;
            if (saved != null && !target.matches(saved.binding()) && !legacyAttachment(target, saved)) throw new IllegalStateException("Choose An Address For This Server");
            checkServerJob(job);
            if (address != null) {
                if (saved != null && sameAddress(address, saved.address())) return prepareServer(job, value, saved, saved.address());
                return claimServerAddress(job, address).thenCompose(claimed -> prepareServer(job, value, saved, claimed));
            }
            String selected = addressId == null ? "" : addressId;
            if (selected.isBlank() && saved != null && saved.address() != null) selected = saved.address().id();
            PrepareState pending = prepareState(job, false);
            if (selected.isBlank() && pending != null && pending.address != null) return prepareServer(job, value, saved, pending.address);
            String id = selected;
            Address chosen = value.addresses().stream().filter(item -> id.equals(item.id()) && usableAddress(item)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Choose An Available Address"));
            return prepareServer(job, value, saved, chosen);
        })).thenApply(connection -> {
            PrepareState state = prepareState(job, false);
            if (state != null && state.mutation == null) prepareStates.remove(target.key(), state);
            return connection;
        });
    }

    private static boolean usableAddress(Address address) {
        return "ACTIVE".equalsIgnoreCase(address.status()) || "AVAILABLE".equalsIgnoreCase(address.status());
    }

    private static Async<Connection> prepareServer(ServerJob<?> job, Summary summary, Connection existing, Address address) {
        checkServerJob(job);
        ReProxyTarget target = job.target;
        if (!usableAddress(address)) return Async.failed(new IllegalStateException("This Address Is Unavailable"));
        Connection owner = summary.connections().stream().filter(value -> value.address() != null && address.id().equals(value.address().id())).findFirst().orElse(null);
        if (owner != null && !target.matches(owner.binding()) && !legacyAttachment(target, owner)) return Async.failed(new IllegalStateException("This Address Is Used By Another Server"));
        if (owner != null) existing = owner;
        if (existing != null) return serverReady(job, existing.id()).thenCompose(connection -> prepareJava(job, connection, address.id(), 0));
        return serverBinding(job, summary).thenCompose(binding -> {
            checkServerJob(job);
            EndpointSpec minecraft = new EndpointSpec("Minecraft Java", "TCP", "JAVA_HOSTNAME", new Target(binding.id(), target.port()), PublicPort.auto(), true);
            ConnectionSpec spec = new ConnectionSpec(job.creation == null ? target.name() : job.creation.intent.name(), address.id(), null,
                    binding.id(), List.of(minecraft), "STOPPED");
            return createServerConnection(job, spec);
        }).thenApply(connection -> admitServer(target, connection));
    }

    private static Async<Binding> serverBinding(ServerJob<?> job, Summary value) {
        checkServerJob(job);
        if (job.creation != null && job.creation.intent.registeredBinding() != null) {
            Binding registered = job.creation.intent.registeredBinding();
            return serverJobRequest(job, job.api.bindings()).thenApply(bindings -> bindings.stream()
                    .filter(binding -> registered.id().equals(binding.id()) && job.target.matches(binding)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Saved Server Binding Is Unavailable")));
        }
        List<Binding> bindings = value.bindings().stream().filter(job.target::matches).toList();
        if (!job.target.bindingId().isBlank()) {
            Binding saved = bindings.stream().filter(binding -> job.target.bindingId().equals(binding.id())).findFirst().orElse(null);
            if (saved != null) return rememberBinding(job, saved);
        }
        if (bindings.size() > 1) return Async.failed(new IllegalStateException("This Server Has More Than One Connection Target"));
        if (!bindings.isEmpty()) return rememberBinding(job, bindings.getFirst());
        Async<Binding> registered = serverJobRequest(job, job.api.registerBinding(job.target.binding(),
                job.creation == null ? operationKey() : job.creation.intent.bindingKey())).thenCompose(binding -> rememberBinding(job, binding));
        registered.whenComplete((binding, error) -> {
            if (job.api == client && job.generation == accountGeneration) { summaryGeneration++; summary = null; }
        });
        return registered;
    }

    private static Async<Binding> rememberBinding(ServerJob<?> job, Binding binding) {
        if (!job.target.matches(binding)) return Async.failed(new IllegalStateException("Server Binding Belongs To Another Server"));
        return job.creation == null ? Async.completed(binding)
                : saveCreation(job, job.creation.intent.withBinding(binding)).thenApply(ignored -> binding);
    }

    private static Async<Connection> prepareJava(ServerJob<?> job, Connection connection, String addressId, int attempt) {
        checkServerJob(job);
        ReProxyTarget target = job.target;
        if (!target.matches(connection.binding())) return Async.failed(new IllegalStateException("This Connection Belongs To Another Server"));
        if (connection.pendingOperationId() != null && !connection.pendingOperationId().isBlank()) {
            return awaitServer(job, connection, 0).thenCompose(current -> prepareJava(job, current, addressId, attempt));
        }
        List<Endpoint> java = connection.endpoints().stream().filter(endpoint -> "JAVA_HOSTNAME".equals(endpoint.routingMode())).toList();
        if (java.size() > 1) return Async.failed(new IllegalStateException("This Server Has More Than One Minecraft Port"));
        Endpoint primary = java.isEmpty() ? null : java.getFirst();
        boolean move = connection.address() == null || !addressId.equals(connection.address().id());
        boolean update = primary == null || !primary.enabled() || !"TCP".equals(primary.protocol()) || primary.target() == null
                || !connection.binding().id().equals(primary.target().bindingId()) || primary.target().port() != target.port();
        if (!move && !update) return Async.completed(admitServer(target, connection));
        List<EndpointSpec> endpoints = new ArrayList<>();
        for (Endpoint endpoint : connection.endpoints()) {
            PublicPort preference = Objects.requireNonNull(endpoint.publicPortPolicy(), "Saved Port Settings Are Missing");
            boolean minecraft = primary != null && primary.id().equals(endpoint.id());
            endpoints.add(new EndpointSpec(endpoint.id(), endpoint.name(), minecraft ? "TCP" : endpoint.protocol(), endpoint.routingMode(),
                    minecraft ? new Target(connection.binding().id(), target.port()) : endpoint.target(), preference, minecraft || endpoint.enabled()));
        }
        if (primary == null) endpoints.add(new EndpointSpec("Minecraft Java", "TCP", "JAVA_HOSTNAME", new Target(connection.binding().id(), target.port()), PublicPort.auto(), true));
        ConnectionPatch patch = new ConnectionPatch(null, null, endpoints, null, move ? addressId : null);
        checkServerJob(job);
        Async<Operation> mutation;
        if (job.creation == null) mutation = serverJobRequest(job, job.api.updateConnection(connection.id(), patch, connection.revision(), operationKey()));
        else {
            CreationIntent saved = job.creation.intent;
            if (saved.updateRequest() != null && !connection.id().equals(saved.updateConnectionId())) {
                return Async.failed(new IllegalStateException("Saved Server Connection Changed"));
            }
            CreationIntent frozen = saved.updateRequest() == null ? saved.withUpdate(connection, patch) : saved;
            mutation = saveCreation(job, frozen).thenCompose(ignored -> serverJobCall(job, () -> job.api.updateConnection(
                    frozen.updateConnectionId(), frozen.updateRequest(), frozen.updateRevision(), frozen.updateKey())));
        }
        return mutation
                .thenCompose(receipt -> settleServerJob(job, receipt, true)).thenCompose(operation -> serverJobCall(job, () -> canonical(job.api, operation)))
                .thenApply(current -> admitServer(target, current)).exceptionallyCompose(error -> {
                    if (job.creation == null && attempt < 2 && error instanceof ReProxyClient.Failure failure && failure.status() == 412) {
                        checkServerJob(job);
                        return serverJobRequest(job, job.api.connection(connection.id())).thenCompose(current -> prepareJava(job, current, addressId, attempt + 1));
                    }
                    return Async.failed(error);
                });
    }

    private static Connection admitServer(ReProxyTarget target, Connection connection) {
        selectServerConnection(target.key(), connection.id());
        invalidate(connection);
        return connection;
    }

    private static void admitReceipt(Operation receipt) {
        admitReceipt(receipt, true);
    }

    private static void admitReceipt(Operation receipt, boolean connect) {
        Connection connection = receipt.connection();
        if (receipt.address() != null && connection == null) { summaryGeneration++; summary = null; }
        if (connection == null) return;
        Entry entry = byConnection(connection.id());
        if (entry != null && entry.binding != null && !entry.binding.id().equals(connection.binding().id())) {
            cancel(entry, "The Server Connection Target Changed");
            entry = null;
        }
        invalidate(connection);
        if (!connect || !"ENABLED".equals(connection.desiredState())) return;
        if (entry != null && receipt.pending()) {
            entry.settled = false;
            if (entry.ready.isDone()) entry.ready = Async.pending();
        }
        attachConnection(connection, false);
    }

    private static void settleEntry(Connection connection) {
        Entry entry = byConnection(connection.id());
        if (entry != null && entry.binding != null && !entry.binding.id().equals(connection.binding().id())) {
            cancel(entry, "The Server Connection Target Changed");
            entry = null;
        }
        if (entry == null) { invalidate(connection); return; }
        accept(entry, connection);
        entry.settled = true;
        if (entry.activated) online(entry);
    }

    private static Async<Operation> serverOperation(ReProxyClient api, long generation, Async<Operation> source) {
        return serverRequest(api, generation, source).thenCompose(receipt -> {
            admitReceipt(receipt);
            return serverRequest(api, generation, api.settle(receipt, SERVER_WAIT, Clock.system(), TaskSchedulers.current()).result()).thenCompose(operation -> {
                if (!operation.succeeded()) throw new IllegalStateException(operation.error() == null ? "Could Not Complete This Change" : operation.error().message());
                if (operation.connection() == null) return Async.completed(operation);
                return serverRequest(api, generation, canonical(api, operation)).thenCompose(connection -> {
                    settleEntry(connection);
                    if (!"ENABLED".equals(connection.desiredState())) return Async.completed(operation);
                    return serverRequest(api, generation, attachConnection(connection)).thenApply(ignored -> operation);
                });
            });
        });
    }

    private static <T> Async<T> serverRequest(ReProxyClient api, long generation, Async<T> source) {
        Async<T> result = Async.pending();
        source.whenComplete((value, error) -> work.execute(() -> {
            if (client != api || accountGeneration != generation) { result.fail(new Async.Cancellation()); return; }
            if (error == null) result.complete(value); else result.fail(error);
        }));
        return result;
    }

    public static ReProxyClient serverClient() {
        requireRuntime();
        return client;
    }

    public static Async<Operation> serverOperation(Function<ReProxyClient, Async<Operation>> action) {
        Async<Operation> result = Async.pending();
        work.execute(() -> {
            try {
                requireRuntime();
                ReProxyClient api = client;
                long generation = accountGeneration;
                forward(serverOperation(api, generation, action.apply(api)), result);
            }
            catch (Throwable error) { result.fail(error); }
        });
        return result;
    }

    public static <T> Async<T> serverRead(Function<ReProxyClient, Async<T>> action) {
        Async<T> result = Async.pending();
        work.execute(() -> {
            try {
                requireRuntime();
                ReProxyClient api = client;
                long generation = accountGeneration;
                forward(serverRequest(api, generation, action.apply(api)), result);
            } catch (Throwable error) { result.fail(error); }
        });
        return result;
    }

    public static Async<Void> startServer(ReProxyTarget target) {
        return serverQueue(target, job -> prepareServer(job, "", null).thenCompose(connection -> {
            checkServerJob(job);
            return serverJobRequest(job, startConnection(connection));
        }));
    }

    public static Async<Void> stopServer(ReProxyTarget target) {
        return serverQueue(target, job -> stopServer(job, ""));
    }

    private static Async<Void> stopServer(ServerJob<?> job, String connectionId) {
        return serverReady(job, connectionId, false).thenCompose(connection -> {
            checkServerJob(job);
            Entry entry = byConnection(connection.id());
            if (entry != null) cancel(entry, "Connection Stopped");
            if ("STOPPED".equals(connection.desiredState())) return Async.completed(null);
            return serverJobRequest(job, job.api.stop(connection.id(), connection.revision(), operationKey())).thenCompose(receipt -> {
                if (!connection.id().equals(receipt.resourceId())) throw new IllegalStateException("The Server Change Belongs To Another Connection");
                return settleServerJob(job, receipt, true, false);
            }).thenApply(ignored -> null);
        });
    }

    public static boolean isForwarded(ReProxyTarget target) {
        try {
            Connection connection = serverConnection(target);
            return connection != null && isForwarded(connection.id());
        } catch (IllegalStateException error) { return false; }
    }

    public static String getForwardedAddress(ReProxyTarget target) {
        try {
            Connection connection = serverConnection(target);
            if (connection == null || !isForwarded(connection.id())) return "";
            Status status = connectionStatuses.get(connection.id());
            return status == null ? "" : status.address();
        } catch (IllegalStateException error) { return ""; }
    }

    public static Async<Summary> refreshSummary() {
        Async<Summary> result = Async.pending();
        work.execute(() -> {
            try {
                requireRuntime();
                if (summaryRequest != null) { forward(summaryRequest, result); return; }
                long generation = accountGeneration;
                long stamp = summaryGeneration;
                ReProxyClient api = client;
                Async<Summary> projection = Async.pending();
                summaryRequest = projection;
                forward(projection, result);
                api.summary().whenComplete((value, error) -> work.execute(() -> {
                    if (generation != accountGeneration || client != api) { projection.fail(new Async.Cancellation()); return; }
                    summaryRequest = null;
                    if (error != null) { projection.fail(error); return; }
                    if (stamp != summaryGeneration) { forward(refreshSummary(), projection); return; }
                    summary = value;
                    Map<String, List<Connection>> index = new HashMap<>();
                    for (Connection connection : value.connections()) {
                        if (connection.binding() == null) continue;
                        Binding binding = connection.binding();
                        String server = "LOCAL".equals(binding.kind()) ? "local:" + binding.instanceId() : binding.serverId();
                        if (server == null || server.isBlank()) continue;
                        index.computeIfAbsent(server, ignored -> new ArrayList<>()).add(connection);
                    }
                    Map<String, List<Connection>> admitted = new HashMap<>();
                    index.forEach((key, connections) -> admitted.put(key, List.copyOf(connections)));
                    serverConnections = Map.copyOf(admitted);
                    Map<String, Connection> known = new HashMap<>();
                    for (Connection connection : value.connections()) known.put(connection.id(), connection);
                    knownConnections = Map.copyOf(known);
                    for (String key : List.copyOf(serverWatches.keySet())) notifyServer(key);
                    projection.complete(value);
                }));
            } catch (Throwable error) { result.fail(error); }
        });
        return result;
    }

    public static Connection connectionForServer(String serverId) {
        List<Connection> connections = serverConnections.getOrDefault(serverId, List.of());
        String selected;
        synchronized (selections) { selected = selections.get(serverId); }
        if (selected != null) return connections.stream().filter(connection -> selected.equals(connection.id())).findFirst().orElseThrow(() -> new IllegalStateException("Selected Connection Is Unavailable"));
        if (connections.size() > 1) throw new IllegalStateException("Choose This Server's Saved Connection");
        return connections.isEmpty() ? null : connections.getFirst();
    }

    public static Async<Connection> findConnection(Server server) {
        Async<Connection> result = Async.pending();
        work.execute(() -> {
            try {
                requireRuntime();
                if (server == null) throw new IllegalArgumentException("Choose A Server");
                String id = server.setting(CONNECTION_KEY, "");
                if (!id.isBlank()) {
                    forward(client.connection(id).thenApply(connection -> {
                        if (connection.binding() == null || !matches(server, connection.binding())) throw new IllegalStateException("Saved Connection Belongs To A Different Server");
                        return connection;
                    }), result);
                } else forward(refreshSummary().thenApply(ignored -> connectionForServer("local:" + server.id())), result);
            } catch (Throwable error) { result.fail(error); }
        });
        return result;
    }

    public static void selectServerConnection(String serverId, String connectionId) {
        if (serverId == null || serverId.isBlank() || connectionId == null || connectionId.isBlank()) throw new IllegalArgumentException("Server And Connection Are Required");
        synchronized (selections) { selections.put(serverId, connectionId); }
    }

    public static Status status(Connection connection) {
        if (connection == null) return null;
        return connectionStatuses.get(connection.id());
    }

    public static boolean isForwarded(String connectionId) {
        Status status = connectionStatuses.get(connectionId);
        return status != null && status.ready();
    }

    public static Async<Void> startConnection(Connection connection) {
        Async<Void> result = Async.pending();
        work.execute(() -> {
            try {
                requireRuntime();
                if (connection == null || connection.binding() == null) throw new IllegalArgumentException("Choose A Saved Connection");
                Entry existing = byConnection(connection.id());
                if (existing != null && existing.desired) { forward(existing.ready, result); return; }
                String key = connectionKey(connection);
                Entry occupied = entries.get(key);
                if (occupied != null && occupied.desired) throw new IllegalStateException("Another Connection Is Using This Binding");
                Entry entry = new Entry(key, null);
                entries.put(key, entry);
                forward(entry.ready, result);
                publish();
                observe(entry, entry.api.connection(connection.id()), current -> enable(entry, current));
            } catch (Throwable error) { result.fail(error); }
        });
        return result;
    }

    public static Status status(Server instance) {
        if (instance == null) return null;
        return statuses.get(instanceKey(instance));
    }

    public static void start(Server instance, Runnable onComplete) {
        start(instance, onComplete, true);
    }

    public static void startQuietly(Server instance, Runnable onComplete) {
        start(instance, onComplete, false);
    }

    private static void start(Server instance, Runnable onComplete, boolean notifications) {
        Async<Void> result;
        try {
            ReProxyTarget target = serverTarget(instance);
            result = serverQueue(target, job -> {
                if (target.connectionId().isBlank()) return serverJobRequest(job, startInstance(instance, notifications));
                return prepareServer(job, "", null).thenCompose(connection -> serverJobRequest(job, persist(instance, connection)))
                        .thenCompose(ignored -> { checkServerJob(job); return serverJobRequest(job, startInstance(instance, notifications)); });
            });
        } catch (Throwable error) { result = Async.failed(error); }
        complete(result, onComplete, notifications, "ReProxy Online");
    }

    private static ReProxyTarget serverTarget(Server instance) {
        if (instance == null) throw new IllegalArgumentException("Choose A Server");
        return new ReProxyTarget(instance.id(), instance.name(), instance.port(), new Binding("", "LOCAL", instance.id(), "", "", "", ""),
                instance.setting(CONNECTION_KEY, ""), instance.setting(BINDING_KEY, ""));
    }

    private static Async<Void> startInstance(Server instance, boolean notifications) {
        Async<Void> result = Async.pending();
        work.execute(() -> {
            try {
                requireRuntime();
                if (!localInstance(instance)) throw new IllegalArgumentException("Choose A Local Server");
                if (instance.port() < 1 || instance.port() > 65535) throw new IllegalArgumentException("Invalid Server Port");
                String key = instanceKey(instance);
                Entry existing = entries.get(key);
                if (existing != null && existing.desired) {
                    if (!notifications && quick(instance) && existing.connection != null && !existing.preparingQuick
                            && (!existing.managedQuick || existing.quickPort != instance.port())) {
                        existing.instance = instance;
                        existing.managedQuick = true;
                        existing.preparingQuick = true;
                        existing.settled = false;
                        existing.phase = "UPDATING";
                        if (existing.ready.isDone()) existing.ready = Async.pending();
                        publish();
                        observe(existing, owned(existing, existing.api.connection(existing.connection.id())).thenCompose(connection -> prepareQuick(existing, connection, 0)), connection -> {
                            existing.preparingQuick = false;
                            enable(existing, connection);
                        });
                    }
                    forward(existing.ready, result);
                    return;
                }
                Entry entry = new Entry(key, instance, !notifications && quick(instance));
                entry.preparingQuick = entry.managedQuick;
                entries.put(key, entry);
                publish();
                forward(entry.ready, result);
                observe(entry, resolve(entry).thenCompose(connection -> prepareQuick(entry, connection, 0)), connection -> {
                    entry.preparingQuick = false;
                    enable(entry, connection);
                });
            } catch (Throwable error) {
                result.fail(error);
            }
        });
        return result;
    }

    public static void startSaved(Server instance, Connection connection, Runnable onComplete) {
        setConnection(instance, connection).whenComplete((ignored, error) -> {
            if (error == null) start(instance, onComplete);
            else {
                try { notify(true, "ReProxy Unavailable", message(error), Notification.Type.ERROR); }
                finally { run(onComplete); }
            }
        });
    }

    public static Async<Void> setConnection(Server instance, Connection connection) {
        return setConnection(instance, connection, () -> true);
    }

    private static Async<Void> setConnection(Server instance, Connection connection, BooleanSupplier admitted) {
        Async<Void> result = Async.pending();
        work.execute(() -> {
            try {
                if (result.isDone()) throw new Async.Cancellation();
                if (!admitted.getAsBoolean()) throw new Async.Cancellation();
                if (instance == null || connection == null || connection.binding() == null || !matches(instance, connection.binding())) {
                    throw new IllegalArgumentException("Connection Belongs To A Different Server Binding");
                }
                Entry current = entries.get(instanceKey(instance));
                if (current != null && current.connection != null && !current.connection.id().equals(connection.id())) {
                    throw new IllegalStateException("Stop ReProxy Before Selecting Another Connection");
                }
                if (result.isDone() || !admitted.getAsBoolean()) throw new Async.Cancellation();
                forward(persist(instance, connection), result);
            } catch (Throwable error) {
                result.fail(error);
            }
        });
        return result;
    }

    public static Async<Void> attachConnection(Connection connection) {
        return attachConnection(connection, true);
    }

    private static Async<Void> attachConnection(Connection connection, boolean observePending) {
        Async<Void> result = Async.pending();
        work.execute(() -> {
            try {
                requireRuntime();
                if (connection == null || connection.binding() == null || !"ENABLED".equals(connection.desiredState())) {
                    throw new IllegalStateException("Start The Saved Connection First");
                }
                Entry existing = byConnection(connection.id());
                if (existing != null && existing.desired) {
                    accept(existing, connection);
                    forward(existing.ready, result);
                    return;
                }
                if (existing != null) cancel(existing, "ReProxy Is Reconnecting");
                ReProxyConnectorCapability.Availability availability = availability(connection);
                if (!availability.available()) throw new UnsupportedOperationException(availability.reason());
                Entry entry = new Entry(connectionKey(connection), null);
                Entry other = entries.values().stream().filter(value -> value.desired && value.binding != null && value.binding.id().equals(connection.binding().id())).findFirst().orElse(null);
                if (other != null) throw new IllegalStateException("Another Connection Is Using This Binding");
                entries.put(entry.key, entry);
                accept(entry, connection);
                forward(entry.ready, result);
                resume(entry, connection, observePending);
            } catch (Throwable error) {
                result.fail(error);
            }
        });
        return result;
    }

    private static Async<Connection> resolve(Entry entry) {
        Server instance = entry.instance;
        String savedId = instance.setting(CONNECTION_KEY, "");
        if (!savedId.isBlank()) {
            return owned(entry, entry.api.connection(savedId)).thenApply(connection -> {
                check(entry);
                if (connection.binding() == null || !matches(instance, connection.binding())) {
                    throw new IllegalStateException("Saved Connection Belongs To A Different Server Binding");
                }
                return connection;
            });
        }
        return binding(entry).thenCompose(binding -> owned(entry, entry.api.connections()).thenCompose(connections -> {
            check(entry);
            entry.binding = binding;
            List<Connection> owned = connections.stream().filter(value -> value.binding() != null && binding.id().equals(value.binding().id())).toList();
            if (owned.size() > 1) return Async.failed(new IllegalStateException("Choose This Server's Saved Connection"));
            if (owned.size() == 1) return Async.completed(owned.getFirst());
            if (!quick(instance)) return Async.failed(new IllegalStateException("Choose A Saved Connection"));
            return owned(entry, entry.api.catalog()).thenCompose(catalog -> create(entry, suffix(instance, catalog), 0));
        })).thenCompose(connection -> {
            check(entry);
            return owned(entry, persist(instance, connection)).thenApply(ignored -> connection);
        });
    }

    private static Async<Binding> binding(Entry entry) {
        Server instance = entry.instance;
        String saved = instance.setting(BINDING_KEY, "");
        return owned(entry, entry.api.bindings()).thenCompose(bindings -> {
            check(entry);
            List<Binding> matches = bindings.stream().filter(value -> matches(instance, value)).toList();
            if (!saved.isBlank()) {
                Binding selected = matches.stream().filter(value -> saved.equals(value.id())).findFirst().orElse(null);
                if (selected == null) return Async.failed(new IllegalStateException("Saved Server Binding Is Unavailable"));
                return Async.completed(selected);
            }
            if (matches.size() > 1) return Async.failed(new IllegalStateException("Choose A Server Binding"));
            if (matches.size() == 1) return Async.completed(matches.getFirst());
            return owned(entry, entry.api.registerBinding(new Binding("", "LOCAL", instance.id(), "", "", "", ""), operationKey()));
        });
    }

    private static Suffix suffix(Server instance, Catalog catalog) {
        String selected = instance.setting(SUFFIX_KEY, "");
        List<Suffix> ready = catalog.suffixes().stream().filter(value -> "READY".equalsIgnoreCase(value.readiness()) && !"DISABLED".equalsIgnoreCase(value.state()))
                .filter(value -> value.transports().contains("TCP") && value.routingModes().contains("JAVA_HOSTNAME")).toList();
        if (!selected.isBlank()) return ready.stream().filter(value -> selected.equals(value.id())).findFirst().orElseThrow(() -> new IllegalStateException("Selected Address Suffix Is Unavailable"));
        Suffix preferred = ready.stream().filter(value -> "reproxy.link".equalsIgnoreCase(value.suffix())).findFirst().orElse(null);
        if (preferred != null) return preferred;
        if (ready.size() == 1) return ready.getFirst();
        throw new IllegalStateException(ready.isEmpty() ? "ReProxy Address Infrastructure Is Unavailable" : "Choose An Address Suffix");
    }

    private static Async<Connection> create(Entry entry, Suffix suffix, int attempt) {
        check(entry);
        String base = label(entry.instance.name());
        if (attempt >= (quick(entry.instance) ? 5 : 1)) return Async.failed(new IllegalStateException("Choose Another ReProxy Address"));
        String candidate = attempt == 0 ? base : base.substring(0, Math.min(25, base.length())) + "-" + UUID.randomUUID().toString().substring(0, 4);
        return owned(entry, entry.api.availability(new AddressCheck(candidate, List.of(suffix.id()), List.of("TCP"), List.of("JAVA_HOSTNAME")))).thenCompose(available -> {
            check(entry);
            boolean free = available.results().stream().anyMatch(value -> suffix.id().equals(value.suffixId()) && "AVAILABLE".equalsIgnoreCase(value.nameState()) && "READY".equalsIgnoreCase(value.readiness()));
            if (!free) return create(entry, suffix, attempt + 1);
            EndpointSpec endpoint = new EndpointSpec("Minecraft Java", "TCP", "JAVA_HOSTNAME", new Target(entry.binding.id(), entry.instance.port()), PublicPort.auto(), true);
            ConnectionSpec spec = new ConnectionSpec(entry.instance.name(), "", new AddressSpec(candidate, suffix.id()), entry.binding.id(), List.of(endpoint), "STOPPED");
            return owned(entry, entry.api.createConnection(spec, operationKey())).thenCompose(operation -> owned(entry, settle(entry, operation))).thenCompose(operation -> owned(entry, canonical(entry.api, operation)));
        }).exceptionallyCompose(error -> {
            if (error instanceof ReProxyClient.Failure failure && "NAME_TAKEN".equals(failure.code()) && quick(entry.instance)) return create(entry, suffix, attempt + 1);
            return Async.failed(error);
        });
    }

    private static Async<Connection> prepareQuick(Entry entry, Connection connection, int attempt) {
        check(entry);
        if (!entry.managedQuick) return Async.completed(connection);
        accept(entry, connection);
        String pending = connection.pendingOperationId();
        if (pending != null && !pending.isBlank()) {
            if (attempt >= 2) return Async.failed(new IllegalStateException("Wait For The Saved Connection Change To Finish"));
            entry.settled = false;
            if ("ENABLED".equals(connection.desiredState())) connect(entry);
            return owned(entry, entry.api.operation(pending)).thenCompose(receipt -> owned(entry, settle(entry, receipt)))
                    .thenCompose(operation -> owned(entry, canonical(entry.api, operation)))
                    .thenCompose(current -> prepareQuick(entry, current, attempt + 1));
        }
        String endpointKey = QUICK_ENDPOINT_KEY + "." + connection.id();
        String selected = entry.instance.setting(endpointKey, "");
        List<Endpoint> candidates = connection.endpoints().stream().filter(endpoint -> "TCP".equals(endpoint.protocol()) && "JAVA_HOSTNAME".equals(endpoint.routingMode()))
                .filter(endpoint -> endpoint.target() != null && connection.binding().id().equals(endpoint.target().bindingId())).toList();
        Endpoint primary;
        if (!selected.isBlank()) {
            primary = candidates.stream().filter(endpoint -> selected.equals(endpoint.id())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("The Quick Server Minecraft Port Is Missing"));
        } else {
            if (candidates.size() != 1) return Async.failed(new IllegalStateException(candidates.isEmpty() ? "Add The Quick Server Minecraft Port" : "Choose The Quick Server Minecraft Port"));
            primary = candidates.getFirst();
        }
        Async<Void> saved = Async.completed(null);
        if (selected.isBlank()) {
            entry.instance.putSetting(endpointKey, primary.id());
            saved = owned(entry, entry.instance.save());
        }
        if (!primary.enabled()) return Async.failed(new IllegalStateException("Enable The Quick Server Minecraft Port"));
        int port = entry.instance.port();
        if (port < 1 || port > 65535) return Async.failed(new IllegalArgumentException("Invalid Server Port"));
        if (primary.target().port() == port) return saved.thenApply(ignored -> {
            entry.quickPort = port;
            entry.localPort = port;
            return connection;
        });
        String endpointId = primary.id();
        List<EndpointSpec> endpoints = connection.endpoints().stream().map(endpoint -> {
            PublicPort preference = Objects.requireNonNull(endpoint.publicPortPolicy(), "Saved Public Port Settings Are Missing");
            Target target = endpointId.equals(endpoint.id()) ? new Target(connection.binding().id(), port) : endpoint.target();
            return new EndpointSpec(endpoint.id(), endpoint.name(), endpoint.protocol(), endpoint.routingMode(), target, preference, endpoint.enabled());
        }).toList();
        ConnectionPatch patch = new ConnectionPatch(null, null, endpoints, null);
        String key = operationKey();
        return saved.thenCompose(ignored -> {
            check(entry);
            entry.settled = false;
            Async<Operation> update = owned(entry, entry.api.updateConnection(connection.id(), patch, connection.revision(), key)).exceptionallyCompose(error -> {
                check(entry);
                if (error instanceof ReProxyClient.Failure) return Async.failed(error);
                return owned(entry, entry.api.updateConnection(connection.id(), patch, connection.revision(), key));
            });
            return update.thenCompose(receipt -> {
                if (receipt.connection() != null) accept(entry, receipt.connection());
                if ("ENABLED".equals(connection.desiredState())) connect(entry);
                return owned(entry, settle(entry, receipt)).thenCompose(operation -> owned(entry, canonical(entry.api, operation))).thenApply(current -> {
                    entry.quickPort = port;
                    entry.localPort = port;
                    return current;
                });
            });
        }).exceptionallyCompose(error -> {
            if (attempt < 2 && error instanceof ReProxyClient.Failure failure && failure.status() == 412) {
                return owned(entry, entry.api.connection(connection.id())).thenCompose(current -> prepareQuick(entry, current, attempt + 1));
            }
            return Async.failed(error);
        });
    }

    private static void enable(Entry entry, Connection connection) {
        if (!current(entry)) return;
        Entry owner = byConnection(connection.id());
        if (owner != null && owner != entry && owner.desired) {
            entries.remove(entry.key, entry);
            forward(owner.ready, entry.ready);
            publish();
            return;
        }
        accept(entry, connection);
        ReProxyConnectorCapability.Availability availability = availability(connection);
        if (!availability.available()) {
            fail(entry, new UnsupportedOperationException(availability.reason()));
            return;
        }
        if ("ENABLED".equals(connection.desiredState())) {
            resume(entry, connection);
            return;
        }
        entry.settled = false;
        entry.startKey = operationKey();
        Async<Operation> start = owned(entry, entry.api.start(connection.id(), connection.revision(), entry.startKey)).exceptionallyCompose(error -> {
            check(entry);
            if (error instanceof ReProxyClient.Failure) return Async.failed(error);
            return owned(entry, entry.api.start(connection.id(), connection.revision(), entry.startKey));
        });
        observe(entry, start, receipt -> {
            if (receipt.connection() != null) accept(entry, receipt.connection());
            connect(entry);
            observe(entry, settle(entry, receipt), operation -> observe(entry, canonical(entry.api, operation), settled -> {
                accept(entry, settled);
                entry.settled = true;
                if (entry.activated) online(entry);
                else publish();
            }));
        });
    }

    private static void resume(Entry entry, Connection connection) {
        resume(entry, connection, true);
    }

    private static void resume(Entry entry, Connection connection, boolean observePending) {
        String operationId = connection.pendingOperationId();
        boolean pending = operationId != null && !operationId.isBlank();
        entry.settled = !pending;
        connect(entry);
        if (!pending) {
            if (entry.activated) online(entry);
            return;
        }
        if (!observePending) return;
        observe(entry, owned(entry, entry.api.operation(operationId)).thenCompose(receipt -> settle(entry, receipt)), operation ->
                observe(entry, canonical(entry.api, operation), settled -> {
                    accept(entry, settled);
                    entry.settled = true;
                    if (entry.activated) online(entry);
                    else publish();
                }));
    }

    private static Async<Operation> settle(Entry entry, Operation receipt) {
        check(entry);
        entry.pollId = receipt.id();
        entry.poll = entry.api.settle(receipt, Duration.ofMinutes(2), Clock.system(), TaskSchedulers.current());
        return entry.poll.result().thenApply(operation -> {
            if (!operation.succeeded()) throw new IllegalStateException(operation.error() == null ? "ReProxy Operation Failed" : operation.error().message());
            return operation;
        });
    }

    private static Async<Connection> canonical(ReProxyClient api, Operation operation) {
        if (operation.connection() != null) return Async.completed(operation.connection());
        return api.connection(operation.resourceId());
    }

    private static void connect(Entry entry) {
        if (!current(entry) || entry.connection == null || entry.connecting || entry.handle != null) return;
        entry.connecting = true;
        entry.phase = "CONNECTING";
        entry.reason = "";
        long socketGeneration = ++entry.socketGeneration;
        publish();
        observe(entry, entry.api.ticket(entry.connection.id()), ticket -> {
            if (socketGeneration != entry.socketGeneration) return;
            try {
                if (!entry.connection.id().equals(ticket.connectionId())) throw new IllegalStateException("Connector Ticket Belongs To Another Connection");
                ReProxyConnectorCapability.Handle handle = connector.open(ticket, entry.binding, entry.connection.endpoints(), new ReProxyConnectorCapability.Listener() {
                    @Override
                    public void authenticated() { event(entry, socketGeneration, () -> { entry.phase = "AUTHENTICATED"; publish(); }); }
                    @Override
                    public void activated(String revision) { event(entry, socketGeneration, () -> { entry.activatedRevision = revision; online(entry); }); }
                    @Override
                    public void changed(String revision) { event(entry, socketGeneration, () -> { entry.activatedRevision = revision; refresh(entry); }); }
                    @Override
                    public void failed(Throwable error) { event(entry, socketGeneration, () -> disconnected(entry, error)); }
                    @Override
                    public void closed() { event(entry, socketGeneration, () -> disconnected(entry, new IllegalStateException("Connector Disconnected"))); }
                });
                if (!current(entry) || socketGeneration != entry.socketGeneration) { handle.close(); return; }
                entry.handle = handle;
                handle.ready().whenComplete((ignored, error) -> event(entry, socketGeneration, () -> {
                    if (error == null) {
                        if (entry.activatedRevision.isBlank()) entry.activatedRevision = ticket.routeRevision();
                        online(entry);
                    } else disconnected(entry, error);
                }));
            } catch (Throwable error) {
                disconnected(entry, error);
            }
        }, error -> disconnected(entry, error));
    }

    private static void online(Entry entry) {
        if (!current(entry)) return;
        boolean first = !entry.activated;
        entry.connecting = false;
        entry.activated = entry.connection != null && Objects.equals(entry.activatedRevision, entry.connection.routeRevision());
        if (!entry.activated) { entry.phase = "UPDATING"; publish(); return; }
        entry.phase = entry.settled ? "ONLINE" : "ACTIVATING";
        entry.reason = "";
        entry.attempts = 0;
        publish();
        if (entry.settled) entry.ready.complete(null);
        if (first) refresh(entry);
    }

    private static void disconnected(Entry entry, Throwable error) {
        if (!current(entry)) return;
        entry.socketGeneration++;
        entry.connecting = false;
        entry.activated = false;
        entry.activatedRevision = "";
        if (entry.ready.isDone()) entry.ready = Async.pending();
        closeHandle(entry);
        entry.reason = message(error);
        entry.phase = "RECONNECTING";
        if (error instanceof ReProxyClient.Failure failure && failure.status() != 429 && failure.status() != 503) {
            fail(entry, error);
            return;
        }
        if (++entry.attempts > MAX_RECONNECTS) {
            fail(entry, new IllegalStateException("Reconnect Failed: " + entry.reason));
            return;
        }
        publish();
        try {
            entry.retry = TaskSchedulers.current().schedule(() -> work.execute(() -> {
                if (!current(entry)) return;
                entry.retry = null;
                observe(entry, entry.api.connection(entry.connection.id()), connection -> {
                    if (!"ENABLED".equals(connection.desiredState())) { cancel(entry, "Connection Stopped"); return; }
                    accept(entry, connection);
                    connect(entry);
                }, failure -> disconnected(entry, failure));
            }), Duration.ofSeconds(Math.min(30, entry.attempts * 3L)));
        } catch (Throwable failure) {
            fail(entry, failure);
        }
    }

    public static void connectionChanged(Connection connection) {
        if (connection == null) return;
        work.execute(() -> {
            invalidate(connection);
            Entry entry = byConnection(connection.id());
            if (entry == null) return;
            if (!"ENABLED".equals(connection.desiredState())) { cancel(entry, "Connection Stopped"); return; }
            accept(entry, connection);
            publish();
        });
    }

    public static void connectionDeleted(String id) {
        work.execute(() -> {
            summaryGeneration++;
            summary = null;
            synchronized (selections) { selections.values().removeIf(id::equals); }
            Map<String, Connection> known = new HashMap<>(knownConnections);
            known.remove(id);
            knownConnections = Map.copyOf(known);
            Map<String, List<Connection>> next = new HashMap<>();
            serverConnections.forEach((server, connections) -> next.put(server, connections.stream().filter(connection -> !id.equals(connection.id())).toList()));
            serverConnections = Map.copyOf(next);
            for (String key : List.copyOf(serverWatches.keySet())) notifyServer(key);
            Entry entry = byConnection(id);
            if (entry != null) cancel(entry, "Connection Deleted");
        });
    }

    private static void refresh(Entry entry) {
        observe(entry, entry.api.connection(entry.connection.id()), connection -> {
            if (!"ENABLED".equals(connection.desiredState())) cancel(entry, "Connection Stopped");
            else { accept(entry, connection); if (entry.activated) online(entry); else publish(); }
        }, error -> {
            if (error instanceof ReProxyClient.Failure failure && (failure.status() == 401 || failure.status() == 403 || failure.status() == 404)) {
                cancel(entry, message(error));
            } else {
                entry.reason = message(error);
                publish();
            }
        });
    }

    private static void accept(Entry entry, Connection connection) {
        if (connection.binding() == null) throw new IllegalStateException("Connection Binding Is Missing");
        if (entry.binding != null && !entry.binding.id().equals(connection.binding().id())) throw new IllegalStateException("Connection Binding Changed");
        if (entry.instance != null && !matches(entry.instance, connection.binding())) throw new IllegalStateException("Connection Belongs To A Different Server");
        if (entry.managedQuick && (entry.connection == null || !entry.connection.revision().equals(connection.revision()))) {
            String selected = entry.instance.setting(QUICK_ENDPOINT_KEY + "." + connection.id(), "");
            entry.quickPort = connection.endpoints().stream().filter(endpoint -> selected.equals(endpoint.id()) && "TCP".equals(endpoint.protocol()) && "JAVA_HOSTNAME".equals(endpoint.routingMode()))
                    .filter(endpoint -> endpoint.enabled() && endpoint.target() != null && connection.binding().id().equals(endpoint.target().bindingId()))
                    .map(endpoint -> endpoint.target().port()).findFirst().orElse(0);
        }
        Connection previous = entry.connection;
        if (!entry.managedQuick && (previous == null || !Objects.equals(previous.revision(), connection.revision()))) {
            List<Endpoint> minecraft = connection.endpoints().stream().filter(endpoint -> endpoint.enabled() && "JAVA_HOSTNAME".equals(endpoint.routingMode()))
                    .filter(endpoint -> endpoint.target() != null && connection.binding().id().equals(endpoint.target().bindingId())).toList();
            if (minecraft.size() == 1) entry.localPort = minecraft.getFirst().target().port();
        }
        boolean changedSession = previous != null && (!Objects.equals(previous.sessionId(), connection.sessionId()) || !Objects.equals(previous.generation(), connection.generation()));
        if (changedSession) {
            entry.socketGeneration++;
            entry.connecting = false;
            entry.activated = false;
            entry.activatedRevision = "";
            closeHandle(entry);
        }
        invalidate(connection);
        entry.connection = connection;
        entry.binding = connection.binding();
        entry.activated = !entry.activatedRevision.isBlank() && Objects.equals(entry.activatedRevision, connection.routeRevision());
        if (!entry.activated && entry.ready.isDone()) entry.ready = Async.pending();
        if (entry.handle != null) entry.handle.updateGrants(connection.endpoints());
        if (changedSession && "ENABLED".equals(connection.desiredState())) connect(entry);
        publish();
    }

    private static void invalidate(Connection connection) {
        if (connection.binding() == null) return;
        Binding binding = connection.binding();
        String server = "LOCAL".equals(binding.kind()) ? "local:" + binding.instanceId() : binding.serverId();
        if (server == null || server.isBlank()) return;
        Connection previous = knownConnections.get(connection.id());
        if (connection.equals(previous)) return;
        summaryGeneration++;
        summary = null;
        Map<String, Connection> known = new HashMap<>(knownConnections);
        known.put(connection.id(), connection);
        knownConnections = Map.copyOf(known);
        Map<String, List<Connection>> next = new HashMap<>();
        serverConnections.forEach((key, values) -> {
            List<Connection> remaining = values.stream().filter(value -> !connection.id().equals(value.id())).toList();
            if (!remaining.isEmpty()) next.put(key, remaining);
        });
        List<Connection> updated = new ArrayList<>(next.getOrDefault(server, List.of()).stream().filter(value -> !value.id().equals(connection.id())).toList());
        updated.add(connection);
        next.put(server, List.copyOf(updated));
        serverConnections = Map.copyOf(next);
        for (String key : List.copyOf(serverWatches.keySet())) {
            if (key.equals(server) || serverWatches.getOrDefault(key, List.of()).stream().anyMatch(watch -> connection.id().equals(watch.target.connectionId()))) notifyServer(key);
        }
    }

    public static void stop(Server instance, Runnable onComplete) { stop(instance, onComplete, true); }
    public static void stopQuietly(Server instance, Runnable onComplete) { stop(instance, onComplete, false); }

    private static void stop(Server instance, Runnable onComplete, boolean notifications) {
        if (instance == null) { run(onComplete); return; }
        Async<Void> result;
        try {
            result = serverQueue(serverTarget(instance), job -> {
                Entry entry = entries.get(instanceKey(instance));
                String id = job.target.connectionId();
                if (id.isBlank() && entry != null && entry.connection != null) id = entry.connection.id();
                if (id.isBlank() && connectionForServer(job.target.key()) == null) return Async.completed(null);
                return stopServer(job, id);
            });
        } catch (Throwable error) { result = Async.failed(error); }
        complete(result, onComplete, notifications, "ReProxy Offline");
    }

    public static void stop(int localPort, Runnable onComplete) { stopPort(localPort, onComplete, true); }
    public static void stopQuietly(int localPort, Runnable onComplete) { stopPort(localPort, onComplete, false); }

    private static void stopPort(int localPort, Runnable onComplete, boolean notifications) {
        stopSelected(() -> {
            List<Entry> matches = entries.values().stream().filter(value -> value.instance != null && value.localPort == localPort).toList();
            if (matches.size() > 1) throw new IllegalStateException("Choose The Server To Stop ReProxy");
            return matches.isEmpty() ? null : matches.getFirst();
        }, onComplete, notifications);
    }

    public static Async<Void> stopConnection(String id, Runnable onComplete) {
        return stopConnection(id, onComplete, true);
    }

    private static Async<Void> stopConnection(String id, Runnable onComplete, boolean notifications) {
        Async<Void> result = Async.pending();
        complete(result, onComplete, notifications, "ReProxy Offline");
        work.execute(() -> {
            try {
                requireRuntime();
                Entry entry = byConnection(id);
                if (entry != null) cancel(entry, "Connection Stopped");
                forward(stopRemote(client, id), result);
            } catch (Throwable error) { result.fail(error); }
        });
        return result;
    }

    private static Async<Void> stopRemote(ReProxyClient api, String id) {
        long generation = accountGeneration;
        return serverRequest(api, generation, api.connection(id))
                .thenCompose(connection -> serverOperation(api, generation, api.stop(id, connection.revision(), operationKey())))
                .thenCompose(operation -> serverRequest(api, generation, canonical(api, operation)))
                .thenApply(connection -> { invalidate(connection); return null; });
    }

    private static void stopSelected(Supplier<Entry> selection, Runnable onComplete, boolean notifications) {
        Async<Void> result = Async.pending();
        complete(result, onComplete, notifications, "ReProxy Offline");
        work.execute(() -> {
            try {
                Entry entry = selection.get();
                if (entry == null) { result.complete(null); return; }
                Connection connection = entry.connection;
                cancel(entry, "Connection Stopped");
                if (connection == null) { result.complete(null); return; }
                forward(stopRemote(entry.api, connection.id()), result);
            } catch (Throwable error) {
                result.fail(error);
            }
        });
    }

    public static void stopAll() {
        work.execute(() -> {
            List<Entry> all = new ArrayList<>(entries.values());
            for (Entry entry : all) stopSelected(() -> entries.get(entry.key), null, false);
        });
    }

    public static void cancelAll() {
        work.execute(() -> cancelAll("ReProxy Session Ended"));
    }

    private static void cancelAll(String reason) {
        accountGeneration++;
        summaryGeneration++;
        summary = null;
        serverConnections = Map.of();
        knownConnections = Map.of();
        serverCatalog = null;
        if (catalogRequest != null) catalogRequest.cancel();
        catalogRequest = null;
        List<ServerJob<?>> jobs = serverJobs.values().stream().flatMap(queue -> new ArrayList<>(queue).stream()).toList();
        serverJobs.clear();
        prepareStates.clear();
        for (ServerJob<?> job : jobs) {
            if (job.expiry != null) job.expiry.cancel();
            if (job.poll != null && job.ownsPoll) job.poll.cancel();
            if (job.source != null) job.source.cancel();
            job.result.fail(new Async.Cancellation());
        }
        for (List<ServerWatch> watches : serverWatches.values()) for (ServerWatch watch : watches) {
            try { if (!watch.closed) watch.listener.accept(null); }
            catch (RuntimeException ignored) { }
        }
        serverWatches.clear();
        synchronized (selections) { selections.clear(); }
        if (summaryRequest != null) summaryRequest.cancel();
        summaryRequest = null;
        for (Entry entry : new ArrayList<>(entries.values())) cancel(entry, reason);
    }

    private static void cancel(Entry entry, String reason) {
        entry.desired = false;
        entry.socketGeneration++;
        entries.remove(entry.key, entry);
        if (entry.retry != null) entry.retry.cancel();
        if (entry.poll != null) entry.poll.cancel();
        if (entry.request != null) entry.request.cancel();
        closeHandle(entry);
        publish();
        entry.ready.fail(new IllegalStateException(reason));
    }

    private static void fail(Entry entry, Throwable error) {
        if (!current(entry)) return;
        entry.phase = "UNAVAILABLE";
        entry.reason = message(error);
        entry.desired = false;
        entry.socketGeneration++;
        if (entry.retry != null) entry.retry.cancel();
        if (entry.poll != null) entry.poll.cancel();
        closeHandle(entry);
        publish();
        entry.ready.fail(error);
    }

    private static void closeHandle(Entry entry) {
        ReProxyConnectorCapability.Handle handle = entry.handle;
        entry.handle = null;
        if (handle != null) {
            try { handle.close(); }
            catch (RuntimeException error) { entry.reason = message(error); }
        }
    }

    private static void event(Entry entry, long generation, Runnable action) {
        work.execute(() -> {
            if (current(entry) && generation == entry.socketGeneration) action.run();
        });
    }

    private static boolean current(Entry entry) {
        Entry owner = entries.get(entry.key);
        return owner == entry && owner.generation == entry.generation && entry.desired;
    }
    private static void check(Entry entry) { if (!current(entry)) throw new Async.Cancellation(); }

    private static <T> Async<T> owned(Entry entry, Async<T> request) {
        Async<T> result = Async.pending();
        result.onCancel(request::cancel);
        request.whenComplete((value, error) -> work.execute(() -> {
            if (!current(entry)) result.fail(new Async.Cancellation());
            else if (error != null) result.fail(error);
            else result.complete(value);
        }));
        return result;
    }

    private static <T> void observe(Entry entry, Async<T> request, Consumer<T> success) {
        observe(entry, request, success, error -> fail(entry, error));
    }

    private static <T> void observe(Entry entry, Async<T> request, Consumer<T> success, Consumer<Throwable> failure) {
        entry.request = request;
        request.whenComplete((value, error) -> work.execute(() -> {
            if (!current(entry)) return;
            try {
                if (error == null) success.accept(value);
                else failure.accept(error);
            } catch (Throwable problem) {
                failure.accept(problem);
            }
        }));
    }

    private static Async<Void> persist(Server instance, Connection connection) {
        instance.putSetting(CONNECTION_KEY, connection.id());
        instance.putSetting(BINDING_KEY, connection.binding().id());
        if (connection.address() != null) instance.putSetting(SUFFIX_KEY, connection.address().suffixId());
        return instance.save();
    }

    private static void requireRuntime() {
        if (client == null) throw new IllegalStateException("ReProxy Runtime Is Unavailable");
    }

    public static boolean isForwarded(Server instance) {
        Status status = status(instance);
        return status != null && status.ready();
    }

    public static boolean isForwarded(int localPort) {
        return statuses.values().stream().anyMatch(value -> value.localPort() == localPort && value.ready());
    }

    public static String getForwardedAddress(Server instance) {
        Status status = status(instance);
        return status == null || !status.ready() ? "" : status.address();
    }

    public static List<String> listActiveTunnels() {
        return statuses.values().stream().filter(Status::ready).map(Status::address).filter(value -> !value.isBlank()).toList();
    }

    public static void setPreferredDomain(Server instance, String domainId) {
        if (instance == null || domainId == null) return;
        instance.putSetting(quick(instance) ? "reproxy.quickServerDomainId" : "reproxy.preferredDomainId", domainId);
        instance.save().whenComplete((ignored, error) -> {
            if (error != null) notify(true, "ReProxy Unavailable", message(error), Notification.Type.ERROR);
        });
    }

    public static String getPreferredDomainId(Server instance) {
        return instance == null ? "" : instance.setting(quick(instance) ? "reproxy.quickServerDomainId" : "reproxy.preferredDomainId", "");
    }

    static boolean sameInstance(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        try {
            Server first = left instanceof Server value ? value : connector.server(left);
            Server second = right instanceof Server value ? value : connector.server(right);
            return first.id() != null && !first.id().isBlank() && first.id().equals(second.id());
        } catch (UnsupportedOperationException error) {
            return false;
        }
    }

    private static boolean matches(Server instance, Binding binding) {
        return "LOCAL".equals(binding.kind()) && Objects.equals(instance.id(), binding.instanceId());
    }

    private static boolean localInstance(Server instance) {
        return instance != null && instance.local();
    }

    private static boolean quick(Server instance) {
        return "true".equalsIgnoreCase(instance.setting("quickServer.enabled", "false"));
    }

    private static String instanceKey(Server instance) {
        String id = instance.id();
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Server Identity Is Missing");
        return "instance:" + id;
    }

    private static String connectionKey(Connection connection) {
        Binding binding = connection.binding();
        return "LOCAL".equals(binding.kind()) && binding.instanceId() != null && !binding.instanceId().isBlank() ? "instance:" + binding.instanceId() : "binding:" + binding.id();
    }

    private static Entry byConnection(String id) {
        return connectionEntries.get(id);
    }

    private static String label(String name) {
        String value = name == null ? "server" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (value.length() < 3) value = "server-" + value;
        return value.substring(0, Math.min(32, value.length())).replaceAll("-$", "");
    }

    private static String operationKey() { return UUID.randomUUID().toString(); }

    private static void publish() {
        Map<String, Status> next = new HashMap<>();
        Map<String, Status> byConnection = new HashMap<>();
        Map<String, Entry> owners = new HashMap<>();
        for (Entry entry : entries.values()) {
            Connection connection = entry.connection;
            List<Endpoint> endpoints = connection == null ? List.of() : connection.endpoints();
            String address = endpoints.stream().filter(endpoint -> endpoint.enabled() && "JAVA_HOSTNAME".equals(endpoint.routingMode()))
                    .map(Endpoint::displayAddress).filter(value -> value != null && !value.isBlank()).findFirst()
                    .orElseGet(() -> endpoints.stream().filter(Endpoint::enabled).map(Endpoint::displayAddress).filter(value -> value != null && !value.isBlank()).findFirst()
                            .orElse(connection == null || connection.address() == null ? "" : connection.address().publicHost()));
            Status status = new Status(entry.instance == null ? entry.binding == null ? "" : Objects.toString(entry.binding.instanceId(), "") : entry.instance.id(), connection == null ? "" : connection.id(), entry.binding == null ? "" : entry.binding.id(), entry.phase, entry.reason, endpoints, address, entry.localPort);
            next.put(entry.key, status);
            if (connection != null) {
                byConnection.put(connection.id(), status);
                owners.put(connection.id(), entry);
            }
        }
        connectionEntries = Map.copyOf(owners);
        connectionStatuses = Map.copyOf(byConnection);
        statuses = Map.copyOf(next);
    }

    private static <T> void forward(Async<T> source, Async<T> target) {
        source.whenComplete((value, error) -> { if (error == null) target.complete(value); else target.fail(error); });
    }

    private static void complete(Async<Void> result, Runnable callback, boolean notifications, String success) {
        result.whenComplete((ignored, error) -> {
            try { notify(notifications, error == null ? success : "ReProxy Unavailable", error == null ? "" : message(error), error == null ? Notification.Type.INFO : Notification.Type.ERROR); }
            finally { run(callback); }
        });
    }

    private static void run(Runnable callback) {
        if (callback != null) callback.run();
    }

    public static String failureMessage(Throwable error) {
        while (error.getCause() != null && !(error instanceof ReProxyClient.Failure)) error = error.getCause();
        if (error instanceof ReProxyClient.Failure failure) {
            if (failure.status() == 429) {
                String after = failure.error().retryAfter();
                try {
                    long seconds = Long.parseLong(after);
                    return "Too Many Changes. Try Again In " + (seconds < 60 ? Math.max(1, seconds) + " Seconds" : ((seconds + 59) / 60) + " Minutes");
                } catch (RuntimeException ignored) { return "Too Many Changes. Try Again Shortly"; }
            }
            if (failure.status() == 412 || failure.status() == 428) return "This Address Changed. Choose It Again";
        }
        return message(error);
    }

    private static String message(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null || error.getMessage().isBlank() ? "Request Failed" : error.getMessage();
    }

    private static void notify(boolean enabled, String title, String description, Notification.Type type) {
        if (enabled) ScreenManager.getInstance().execute(() -> new Notification(title, description, type));
    }
}
