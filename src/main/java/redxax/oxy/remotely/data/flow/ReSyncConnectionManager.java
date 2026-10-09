package redxax.oxy.remotely.data.flow;

import restudio.rescreen.platform.TaskScheduler;
import java.time.Duration;
import restudio.rescreen.platform.Async;
import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.identity.ServerId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class ReSyncConnectionManager {
    private static final int PROFILE_WORKERS = 2;
    private static final int PROFILE_QUEUE_CAPACITY = 32;
    private static final int PROFILE_CACHE_CAPACITY = 128;
    private static final long PROFILE_CACHE_MILLIS = 5_000L;
    private static final long PROFILE_RESOLUTION_TIMEOUT_SECONDS = 16L;
    private static final long FLOW_CLIENT_ADMISSION_RETRY_MILLIS = 50L;
    private static final long FLOW_CLIENT_READINESS_TIMEOUT_SECONDS = 16L;
    private static final long FLOW_CLIENT_READINESS_RETRY_MILLIS = 50L;
    private static final int CONNECTION_LIFECYCLE_WORKERS = 2;
    private static final int CONNECTION_LIFECYCLE_QUEUE_CAPACITY = 32;
    private static final int SHUTDOWN_DRAIN_ATTEMPTS = 2;
    private static final long CONNECTION_LIFECYCLE_SHUTDOWN_TIMEOUT_MILLIS = 1_000L;
    private static final long CONNECTION_RETIREMENT_SHUTDOWN_TIMEOUT_MILLIS = 1_000L;
    private static final BrowserSafeState.IntegerValue PROFILE_THREAD_SEQUENCE = new BrowserSafeState.IntegerValue();
    private static final BrowserSafeState.IntegerValue CONNECTION_LIFECYCLE_THREAD_SEQUENCE = new BrowserSafeState.IntegerValue();
    private static final BrowserSafeState.IntegerValue SHUTDOWN_THREAD_SEQUENCE = new BrowserSafeState.IntegerValue();
    private final RemotelyClient client;
    private final RemotelyServerApi apiClient;
    private final Function<String, ReSyncCatalogPublicationCache> catalogPublicationCacheFactory;
    private final ReSyncFlowClientFactory flowClientFactory;
    private final ReSyncConnectionProfileProvider profileProvider;
    private final ReSyncConnectionNotificationSink notificationSink;
    private final NodeRegistry nodeRegistry;
    private final ReSyncFlowClientContext flowClientContext;
    private final Map<String, OwnershipLock> connectionOwnershipLocks = BrowserSafeState.map();
    private final Map<String, OwnedFlowClient> flowClients = BrowserSafeState.map();
    private final Map<ReSyncFrameTransport, OwnedFlowClient> networkClients = BrowserSafeState.map();
    private final Map<String, ReSyncConnectionProfile> flowProfiles = BrowserSafeState.map();
    private final Map<String, String> profileConnectionKeys = BrowserSafeState.map();
    private final Set<String> staleProfileAliases = BrowserSafeState.set();
    private final Set<String> profileOwnerRefreshRequired = BrowserSafeState.set();
    private final Map<String, RetirementProgress> pendingRetirements = BrowserSafeState.map();
    private final Object profileResolutionLock = new Object();
    private final Map<String, CachedProfileResolution> profileResolutionCache = BrowserSafeState.map();
    private final Map<String, ProfileFlight> profileFlights = BrowserSafeState.map();
    private final Map<String, Long> profileResolutionGenerations = BrowserSafeState.map();
    private final Map<String, PendingProfileEnsure> pendingProfileEnsures = BrowserSafeState.map();
    private final Map<String, Async<ReSyncFlowClient>> pendingFlowClientAdmissions = BrowserSafeState.map();
    private final BrowserSafeState.LongValue nextProfileResolutionGeneration = new BrowserSafeState.LongValue();
    private final BrowserWork.Executor profileResolutionExecutor;
    private final BrowserWork.Executor profileTimeoutExecutor;
    private final Map<String, ProfileReplacement> pendingProfileReplacements = BrowserSafeState.map();
    private final Set<ProfileReplacement> dispatchedProfileReplacements = BrowserSafeState.set();
    private final Map<ProfileReplacement, Long> profileReplacementRetryAt = BrowserSafeState.map();
    private final BrowserSafeState.LongValue nextProfileReplacementGeneration = new BrowserSafeState.LongValue();
    private final BrowserWork.Executor connectionLifecycleExecutor;
    private final Object profileObject;
    private final Runnable profileInstanceChangeListener = this::invalidateProfileSources;
    private final Object connectionLifecycleAdmission = new Object();
    private volatile Consumer<String> connectionListener = serverId -> {};
    private volatile Consumer<String> disconnectListener = serverId -> {};
    private volatile boolean profileResolutionClosed;
    private volatile boolean connectionLifecycleClosed;
    private volatile boolean connectionLifecycleAbort;
    private volatile boolean shutdownTerminal;
    private final BrowserSafeState.LongValue nextConnectionGeneration = new BrowserSafeState.LongValue();
    private final ThreadLocal<Integer> ownerLeaseDepth = ThreadLocal.withInitial(() -> 0);

    private enum ErrorMode {
        NOTIFY,
        LIVE_SESSION,
        SILENT
    }

    private static final class OwnershipLock {
        private final BrowserSafeState.Lock lock = new BrowserSafeState.Lock();
        private int references;
        private boolean retiring;
    }

    private static final class OwnedFlowClient {
        private final ReSyncFlowClient client;
        private final long generation;
        private final ReSyncFrameTransport callbackTransport;
        private final Object leaseMonitor = new Object();
        private int activeLeases;
        private boolean connectClaimed;
        private boolean connectInvoking;
        private volatile boolean callbacksSuppressed;
        private Async<Void> connectDispatch = Async.completed(null);
        private ErrorMode errorMode;

        private OwnedFlowClient(ReSyncFlowClient client, long generation, ErrorMode errorMode,
                                ReSyncFrameTransport callbackTransport) {
            this.client = client;
            this.generation = generation;
            this.errorMode = errorMode;
            this.callbackTransport = callbackTransport;
        }
    }

    private static final class ShutdownContext {
        private final long deadlineNanos;
        private boolean interrupted;
        private boolean forced;
        private boolean timedOut;

        private ShutdownContext(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }

    private enum RetirementKind {
        CURRENT,
        SERVER
    }

    private static final class RetirementProgress {
        private final OwnedFlowClient owner;
        private final RetirementKind kind;
        private final ReSyncConnectionProfile profile;
        private final boolean profilePresent;
        private final Consumer<ReSyncFlowClient> onRetired;
        private final Runnable onCacheClear;
        private boolean shutdownComplete;
        private boolean retiredCallbackComplete;
        private boolean nodeRegistryComplete;
        private boolean cacheClearComplete;
        private boolean profileCleanupComplete;
        private boolean stepRunning;

        private RetirementProgress(OwnedFlowClient owner, RetirementKind kind,
                                   ReSyncConnectionProfile profile, boolean profilePresent,
                                   Consumer<ReSyncFlowClient> onRetired, Runnable onCacheClear) {
            this.owner = owner;
            this.kind = kind;
            this.profile = profile;
            this.profilePresent = profilePresent;
            this.onRetired = onRetired;
            this.onCacheClear = onCacheClear;
            this.retiredCallbackComplete = onRetired == null;
            this.nodeRegistryComplete = kind == RetirementKind.CURRENT;
            this.cacheClearComplete = kind == RetirementKind.CURRENT || onCacheClear == null;
            this.profileCleanupComplete = kind == RetirementKind.CURRENT;
        }

        private boolean complete() {
            return shutdownComplete && retiredCallbackComplete && nodeRegistryComplete
                && cacheClearComplete && profileCleanupComplete;
        }
    }

    private record ConnectClaim(boolean dispatch, Async<Void> completion) {
    }

    public record ReSyncConnectionProfile(String serverId, String wsUrl, String apiKey) {
        public ReSyncConnectionProfile(String wsUrl, String apiKey) {
            this("", wsUrl, apiKey);
        }

        public static ReSyncConnectionProfile apiManagedProfile() {
            return new ReSyncConnectionProfile("", "", "");
        }
    }

    public record ProfileResolution(String serverId, String instanceId, long generation,
                                    ReSyncConnectionProfile profile, String issue) {
        public boolean available() {
            return issue == null;
        }
    }

    private record ProfileTarget(String serverId, List<String> aliases, Object instance, String instanceId,
                                 String path, Object backendConfig, String backendType, String backendHost,
                                 Map<String, String> backendCredentials, boolean apiManaged, boolean resolved) {
    }

    private record ProfileRead(ReSyncConnectionProfile profile, String issue, boolean found) {
    }

    private record CachedProfileResolution(ProfileTarget target, ProfileResolution resolution, long expiresAt) {
    }

    private static final class ProfileFlight {
        private volatile ProfileTarget target;
        private volatile List<String> lookupAliases;
        private final CachedProfileResolution candidate;
        private final String expectedOwnerKey;
        private final OwnedFlowClient expectedOwner;
        private final long generation;
        private final Async<ProfileResolution> completion = Async.pending();
        private final BrowserSafeState.BooleanValue finished = new BrowserSafeState.BooleanValue();
        private volatile Async<Void> task;
        private volatile TaskScheduler.ScheduledTask timeout;
        private volatile Object taskThread;

        private ProfileFlight(ProfileTarget target, List<String> lookupAliases, CachedProfileResolution candidate,
                              String expectedOwnerKey, OwnedFlowClient expectedOwner, long generation) {
            this.target = target;
            this.lookupAliases = lookupAliases;
            this.candidate = candidate;
            this.expectedOwnerKey = expectedOwnerKey;
            this.expectedOwner = expectedOwner;
            this.generation = generation;
        }
    }

    private static final class PendingProfileEnsure {
        private final Async<ProfileResolution> future;
        private volatile boolean showNotifications;

        private PendingProfileEnsure(Async<ProfileResolution> future, boolean showNotifications) {
            this.future = future;
            this.showNotifications = showNotifications;
        }
    }

    private record ProfileReplacement(String serverId, OwnedFlowClient expectedOwner,
                                      ReSyncConnectionProfile profile, ReSyncLiveServerSession liveSession,
                                      ErrorMode errorMode, boolean connectIfNeeded, String resolutionFenceKey,
                                      long resolutionGeneration, long generation, String apiKey) {
    }

    public ReSyncConnectionManager(RemotelyClient client, RemotelyServerApi apiClient) {
        this(client, apiClient, ignored -> ReSyncCatalogPublicationCache.deferred());
    }

    public ReSyncConnectionManager(RemotelyClient client, RemotelyServerApi apiClient,
                                   Function<String, ReSyncCatalogPublicationCache> catalogPublicationCacheFactory) {
        this(client, apiClient, catalogPublicationCacheFactory, ReSyncFlowClientFactory.unavailable(),
            ReSyncConnectionProfileProvider.unavailable(), ReSyncConnectionNotificationSink.noop(), null, null);
    }

    public ReSyncConnectionManager(RemotelyClient client, RemotelyServerApi apiClient,
                                   Function<String, ReSyncCatalogPublicationCache> catalogPublicationCacheFactory,
                                   ReSyncFlowClientFactory flowClientFactory,
                                   ReSyncConnectionProfileProvider profileProvider,
                                   ReSyncConnectionNotificationSink notificationSink, NodeRegistry nodeRegistry,
                                   ReSyncFlowClientContext flowClientContext) {
        this.client = client;
        this.apiClient = apiClient;
        this.catalogPublicationCacheFactory = catalogPublicationCacheFactory != null
            ? catalogPublicationCacheFactory : ignored -> ReSyncCatalogPublicationCache.deferred();
        this.flowClientFactory = flowClientFactory == null ? ReSyncFlowClientFactory.unavailable() : flowClientFactory;
        this.profileProvider = profileProvider == null ? ReSyncConnectionProfileProvider.unavailable() : profileProvider;
        this.notificationSink = notificationSink == null ? ReSyncConnectionNotificationSink.noop() : notificationSink;
        this.nodeRegistry = nodeRegistry;
        this.flowClientContext = flowClientContext;
        this.profileResolutionExecutor = BrowserWork.executor("ReSync-Profile-");
        this.profileTimeoutExecutor = BrowserWork.executor();
        this.profileTimeoutExecutor.setRemoveOnCancelPolicy(true);
        this.connectionLifecycleExecutor = BrowserWork.executor("ReSync-Connection-Lifecycle-");
        Object instanceManager = null;
        try {
            instanceManager = ReSyncLocalInstances.access.manager();
            ReSyncLocalInstances.access.addListener(profileInstanceChangeListener);
        } catch (RuntimeException ignored) {
        }
        this.profileObject = instanceManager;
    }

    public RemotelyServerApi getApiClient() {
        return apiClient;
    }

    public ReSyncFlowClient getFlowClient(String serverId) {
        if (serverId != null && staleProfileAliases.contains(serverId)) {
            traceOwnerLifecycle(serverId, "connection_owner_lookup_rejected", null, "stale_profile_alias");
            return null;
        }
        serverId = connectionKey(serverId);
        if (serverId == null || serverId.isBlank()) {
            traceOwnerLifecycle(serverId, "connection_owner_lookup_rejected", null, "server_id_unavailable");
            return null;
        }
        if (pendingProfileReplacements.containsKey(serverId)) {
            traceOwnerLifecycle(serverId, "connection_owner_lookup_rejected", flowClients.get(serverId),
                "replacement_pending");
            return null;
        }
        OwnedFlowClient owner = flowClients.get(serverId);
        return owner == null ? null : owner.client;
    }

    boolean hasFlowClients() {
        return !flowClients.isEmpty();
    }

    public boolean withCurrentFlowClient(String serverId, ReSyncFlowClient expected, Consumer<ReSyncFlowClient> action) {
        if (connectionLifecycleClosed || (serverId != null && pendingProfileReplacements.containsKey(serverId))) {
            return false;
        }
        return withCurrentFlowClient(serverId, expected, ignored -> true, action);
    }

    public boolean withCurrentFlowClientNow(String serverId, ReSyncFlowClient expected, Consumer<ReSyncFlowClient> action) {
        if (connectionLifecycleClosed) {
            traceOwnerLifecycle(serverId, "connection_owner_callback_dropped", null, "lifecycle_closed");
            return false;
        }
        if (serverId != null && staleProfileAliases.contains(serverId)) {
            traceOwnerLifecycle(serverId, "connection_owner_callback_dropped", null, "stale_profile_alias");
            return false;
        }
        serverId = connectionKey(serverId);
        if (serverId == null || serverId.isBlank()) {
            traceOwnerLifecycle(serverId, "connection_owner_callback_dropped", null, "server_id_unavailable");
            return false;
        }
        if (expected == null) {
            traceOwnerLifecycle(serverId, "connection_owner_callback_dropped", flowClients.get(serverId),
                "expected_owner_unavailable");
            return false;
        }
        if (action == null) {
            traceOwnerLifecycle(serverId, "connection_owner_callback_dropped", flowClients.get(serverId),
                "callback_unavailable");
            return false;
        }
        if (pendingProfileReplacements.containsKey(serverId)) {
            traceOwnerLifecycle(serverId, "connection_owner_callback_dropped", flowClients.get(serverId),
                "replacement_pending");
            return false;
        }
        OwnershipLock ownership = tryAcquireOwnership(serverId);
        if (ownership == null) {
            traceOwnerLifecycle(serverId, "connection_owner_callback_dropped", flowClients.get(serverId),
                "ownership_lock_busy");
            return false;
        }
        OwnedFlowClient owner;
        try {
            owner = flowClients.get(serverId);
            String rejection = connectionLifecycleClosed ? "lifecycle_closed"
                : staleProfileAliases.contains(serverId) ? "stale_profile_alias"
                : ownership.retiring ? "owner_retiring"
                : owner == null ? "owner_unavailable"
                : owner.client != expected ? "expected_owner_mismatch"
                : !isOwner(serverId, owner) ? "owner_generation_stale" : null;
            if (rejection != null) {
                traceOwnerLifecycle(serverId, "connection_owner_callback_dropped", owner, rejection);
                releaseOwnership(serverId, ownership);
                return false;
            }
            acquireLease(owner);
            ownership.lock.unlock();
        } catch (RuntimeException | Error error) {
            if (ownership.lock.isHeldByCurrentThread()) {
                releaseOwnership(serverId, ownership);
            }
            throw error;
        }
        try {
            action.accept(expected);
            return true;
        } finally {
            ownership.lock.lock();
            releaseLease(owner);
            ownership.lock.signalAll();
            releaseOwnership(serverId, ownership);
        }
    }

    boolean withCurrentFlowClient(String serverId, ReSyncFlowClient expected,
                                  Predicate<ReSyncFlowClient> admission, Consumer<ReSyncFlowClient> action) {
        if (connectionLifecycleClosed || (serverId != null && staleProfileAliases.contains(serverId))) {
            return false;
        }
        serverId = connectionKey(serverId);
        if (serverId == null || serverId.isBlank() || expected == null || admission == null || action == null) {
            return false;
        }
        if (pendingProfileReplacements.containsKey(serverId)) {
            return false;
        }
        OwnershipLock ownership = acquireOwnership(serverId);
        OwnedFlowClient owner;
        try {
            if (ownership.retiring) {
                releaseOwnership(serverId, ownership);
                return false;
            }
            owner = flowClients.get(serverId);
            if (connectionLifecycleClosed || owner == null || owner.client != expected || !isOwner(serverId, owner)) {
                releaseOwnership(serverId, ownership);
                return false;
            }
            if (!admission.test(owner.client)) {
                releaseOwnership(serverId, ownership);
                return false;
            }
            acquireLease(owner);
            ownership.lock.unlock();
        } catch (RuntimeException | Error error) {
            if (ownership.lock.isHeldByCurrentThread()) {
                releaseOwnership(serverId, ownership);
            }
            throw error;
        }
        try {
            action.accept(expected);
            return true;
        } finally {
            ownership.lock.lock();
            releaseLease(owner);
            ownership.lock.signalAll();
            releaseOwnership(serverId, ownership);
        }
    }

    public ReSyncConnectionProfile getProfile(String serverId) {
        if (serverId != null && staleProfileAliases.contains(serverId)) {
            return null;
        }
        return flowProfiles.get(serverId);
    }

    public void disconnectServerConnection(String serverId) {
        closeServerConnectionAtomically(serverId, null, null);
    }

    private String connectionKey(String serverId) {
        return serverId == null ? null : profileConnectionKeys.getOrDefault(serverId, serverId);
    }

    public boolean isFlowClientConnected(String serverId) {
        if (serverId != null && staleProfileAliases.contains(serverId)) {
            return false;
        }
        serverId = connectionKey(serverId);
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        if (pendingProfileReplacements.containsKey(serverId)) {
            return false;
        }
        OwnedFlowClient owner = flowClients.get(serverId);
        return owner != null && owner.client.isConnectedState();
    }

    public boolean isFlowClientReady(String serverId) {
        return isFlowClientConnected(serverId);
    }

    public ReSyncFlowClient.ReadinessState getFlowClientReadiness(String serverId) {
        ReSyncFlowClient flowClient = getFlowClient(serverId);
        return flowClient == null ? ReSyncFlowClient.ReadinessState.DISCONNECTED : flowClient.readiness();
    }

    public ReSyncFlowClient.ConnectionState getFlowClientConnectionState(String serverId) {
        if (serverId != null && staleProfileAliases.contains(serverId)) {
            return ReSyncFlowClient.ConnectionState.DISCONNECTED;
        }
        serverId = connectionKey(serverId);
        if (serverId == null || serverId.isBlank()) {
            return ReSyncFlowClient.ConnectionState.DISCONNECTED;
        }
        if (pendingProfileReplacements.containsKey(serverId)) {
            return ReSyncFlowClient.ConnectionState.DISCONNECTED;
        }
        OwnedFlowClient owner = flowClients.get(serverId);
        return owner == null ? ReSyncFlowClient.ConnectionState.DISCONNECTED : owner.client.connectionState();
    }

    public ReSyncFlowClient ensureFlowClient(String serverId, boolean showNotifications) {
        if (serverId == null || serverId.isBlank() || connectionLifecycleClosed) {
            return null;
        }
        boolean staleProfile = staleProfileAliases.contains(serverId);
        String ownerKey = connectionKey(serverId);
        OwnedFlowClient owner = flowClients.get(ownerKey);
        if (!staleProfile && owner != null
            && owner.client.connectionState() != ReSyncFlowClient.ConnectionState.DISCONNECTED) {
            if (showNotifications) {
                promoteNotifications(ownerKey, owner);
            }
            return owner.client;
        }
        if (!staleProfile && owner != null && !owner.client.usesDirectWebSocketTransport()) {
            return ensureFlowClient(ownerKey, null, showNotifications, true);
        }
        ReSyncConnectionProfile storedProfile = flowProfiles.get(ownerKey);
        if (!staleProfile && storedProfile != null) {
            return ensureFlowClient(ownerKey, storedProfile, showNotifications, true);
        }
        Async<ProfileResolution> resolutionFuture = resolveAndStoreProfile(serverId, null);
        ProfileResolution resolution = resolutionFuture.getNow(null);
        if (resolution == null) {
            BrowserSafeState.BooleanValue attachEnsure = new BrowserSafeState.BooleanValue();
            PendingProfileEnsure pending = pendingProfileEnsures.compute(serverId, (ignored, current) -> {
                if (current != null && current.future == resolutionFuture) {
                    current.showNotifications |= showNotifications;
                    return current;
                }
                attachEnsure.set(true);
                return new PendingProfileEnsure(resolutionFuture, showNotifications);
            });
            if (attachEnsure.get()) {
                resolutionFuture.thenAccept(resolved -> {
                    if (pendingProfileEnsures.remove(serverId, pending) && resolved.available()
                        && !connectionLifecycleClosed) {
                        ensureFlowClient(resolved.serverId(), resolved.profile(), pending.showNotifications, true);
                    }
                });
            }
            return null;
        }
        if (!resolution.available()) {
            return null;
        }
        return ensureFlowClient(resolution.serverId(), resolution.profile(), showNotifications, true);
    }

    public ReSyncFlowClient ensureFlowClient(String serverId, ReSyncConnectionProfile profile) {
        if (serverId != null && staleProfileAliases.contains(serverId)) {
            resolveAndStoreProfile(serverId, null);
            return null;
        }
        return ensureFlowClient(connectionKey(serverId), profile, true, true);
    }

    public ReSyncFlowClient ensureFlowClient(String serverId) {
        return ensureFlowClient(serverId, true);
    }

    private void promoteNotifications(String serverId, OwnedFlowClient expected) {
        OwnershipLock ownership = acquireOwnership(serverId);
        try {
            if (!ownership.retiring && !connectionLifecycleClosed && isOwner(serverId, expected)
                && expected.errorMode == ErrorMode.SILENT) {
                expected.errorMode = ErrorMode.NOTIFY;
            }
        } finally {
            releaseOwnership(serverId, ownership);
        }
    }

    public Async<ReSyncFlowClient> ensureFlowClientAsync(String serverId, boolean showNotifications) {
        ReSyncFlowClient immediate = ensureFlowClient(serverId, showNotifications);
        if (immediate != null) {
            return Async.completed(immediate);
        }
        if (serverId == null || serverId.isBlank() || connectionLifecycleClosed) {
            return Async.completed(null);
        }
        Async<ReSyncFlowClient> admission = Async.pending();
        Async<ReSyncFlowClient> current = pendingFlowClientAdmissions.putIfAbsent(serverId, admission);
        if (current != null) {
            return current;
        }
        admission.whenComplete((ignored, error) -> pendingFlowClientAdmissions.remove(serverId, admission));
        continueFlowClientAdmission(serverId, showNotifications, admission,
            System.nanoTime() + ((PROFILE_RESOLUTION_TIMEOUT_SECONDS) * 1_000_000_000L));
        return admission;
    }

    public Async<ReSyncFlowClient.ReadinessState> awaitFlowClientConnected(String serverId,
                                                                            boolean showNotifications) {
        if (serverId == null || serverId.isBlank() || connectionLifecycleClosed) {
            return Async.completed(ReSyncFlowClient.ReadinessState.DISCONNECTED);
        }
        Async<ReSyncFlowClient.ReadinessState> result = Async.pending();
        ensureFlowClientAsync(serverId, showNotifications).whenComplete((flowClient, error) -> {
            if (error != null || flowClient == null || result.isDone()) {
                result.complete(ReSyncFlowClient.ReadinessState.DISCONNECTED);
                return;
            }
            awaitFlowClientReadiness(serverId, flowClient, result,
                System.nanoTime() + ((FLOW_CLIENT_READINESS_TIMEOUT_SECONDS) * 1_000_000_000L));
        });
        return result;
    }

    private void awaitFlowClientReadiness(String serverId, ReSyncFlowClient expected,
                                          Async<ReSyncFlowClient.ReadinessState> result, long deadline) {
        if (result.isDone()) {
            return;
        }
        ReSyncFlowClient current = getFlowClient(serverId);
        if (current != expected || connectionLifecycleClosed) {
            result.complete(ReSyncFlowClient.ReadinessState.DISCONNECTED);
            return;
        }
        ReSyncFlowClient.ReadinessState readiness = current.readiness();
        if (readiness == ReSyncFlowClient.ReadinessState.READY
            || readiness == ReSyncFlowClient.ReadinessState.INCOMPATIBLE) {
            result.complete(readiness);
            return;
        }
        if (readiness == ReSyncFlowClient.ReadinessState.DISCONNECTED
            && current.connectionFailure() != ReSyncFlowClient.ConnectionFailure.NONE) {
            result.complete(readiness);
            return;
        }
        if (System.nanoTime() >= deadline) {
            result.complete(readiness);
            return;
        }
        try {
            profileTimeoutExecutor.schedule(() -> awaitFlowClientReadiness(serverId, expected, result, deadline),
                Duration.ofMillis(FLOW_CLIENT_READINESS_RETRY_MILLIS));
        } catch (IllegalStateException rejected) {
            result.complete(ReSyncFlowClient.ReadinessState.DISCONNECTED);
        }
    }

    public ReSyncFlowClient retryFlowClient(String serverId, boolean showNotifications) {
        disconnectServerConnection(serverId);
        return ensureFlowClient(serverId, showNotifications);
    }

    private void continueFlowClientAdmission(String serverId, boolean showNotifications,
                                             Async<ReSyncFlowClient> admission, long deadline) {
        if (admission.isDone() || pendingFlowClientAdmissions.get(serverId) != admission) {
            return;
        }
        if (connectionLifecycleClosed) {
            admission.complete(null);
            return;
        }
        ReSyncFlowClient immediate = ensureFlowClient(serverId, showNotifications);
        if (immediate != null) {
            admission.complete(immediate);
            return;
        }
        getFlowAvailabilityIssueAsync(serverId, null).whenComplete((issue, error) -> {
            if (admission.isDone() || pendingFlowClientAdmissions.get(serverId) != admission) {
                return;
            }
            if (error != null || issue != null || connectionLifecycleClosed) {
                admission.complete(null);
                return;
            }
            ReSyncFlowClient resolved = ensureFlowClient(serverId, showNotifications);
            if (resolved != null) {
                admission.complete(resolved);
                return;
            }
            if (System.nanoTime() >= deadline) {
                admission.complete(null);
                return;
            }
            try {
                profileTimeoutExecutor.schedule(
                    () -> continueFlowClientAdmission(serverId, showNotifications, admission, deadline), Duration.ofMillis(FLOW_CLIENT_ADMISSION_RETRY_MILLIS));
            } catch (IllegalStateException rejected) {
                admission.complete(null);
            }
        });
    }

    public ReSyncFlowClient getNetworkClient(ReSyncFrameTransport transport) {
        if (transport == null || !transport.reconnectable()) return null;
        OwnedFlowClient owner = networkClients.get(transport);
        if (owner == null) return null;
        String serverId = owner.client.getServerId();
        if (!isOwner(serverId, owner) || owner.callbacksSuppressed || pendingProfileReplacements.containsKey(serverId)) return null;
        return owner.client;
    }

    public void cancelNetworkSession(String serverId, ReSyncFrameTransport transport) {
        if (transport == null) return;
        String canonicalId = serverId == null ? null : UUID.fromString(serverId).toString();
        synchronized (connectionLifecycleAdmission) {
            ProfileReplacement replacement = canonicalId == null ? null : pendingProfileReplacements.get(canonicalId);
            if (replacement != null && replacement.apiKey() != null && replacement.liveSession() != null
                && replacement.liveSession().transport() == transport && pendingProfileReplacements.remove(canonicalId, replacement)) {
                profileReplacementRetryAt.remove(replacement);
                if (!dispatchedProfileReplacements.contains(replacement)) replacement.expectedOwner().callbacksSuppressed = false;
            }
            OwnedFlowClient owner = networkClients.get(transport);
            if (owner != null && (canonicalId == null || owner.client.getServerId().equals(canonicalId))) {
                networkClients.remove(transport, owner);
            }
        }
        transport.close();
    }

    public ReSyncFlowClient activateNetworkSession(String serverId, String apiKey, ReSyncFrameTransport transport) {
        String canonicalId = UUID.fromString(Objects.requireNonNull(serverId, "serverId")).toString();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ReSync API Key Is Required");
        }
        Objects.requireNonNull(transport, "transport");
        if (!transport.reconnectable() && transport.state() != ReSyncFrameTransport.State.NEW) return null;
        return activateLiveSession(new ReSyncLiveServerSession(canonicalId, canonicalId, transport), apiKey);
    }

    public ReSyncFlowClient activateLiveSession(ReSyncLiveServerSession session) {
        return activateLiveSession(session, null);
    }

    private ReSyncFlowClient activateLiveSession(ReSyncLiveServerSession session, String apiKey) {
        if (session == null || session.serverId() == null || session.serverId().isBlank() || session.transport() == null
            || connectionLifecycleClosed) {
            return null;
        }
        String serverId = session.serverId();
        ServerId peerServerId = session.peerServerId().orElse(null);
        if (peerServerId != null && !peerServerId.canonicalText().equals(serverId)) {
            return null;
        }
        ProfileReplacement pendingReplacement = pendingProfileReplacements.get(serverId);
        if (pendingReplacement != null) {
            dispatchProfileReplacement(pendingReplacement);
            return null;
        }
        OwnedFlowClient owner = null;
        OwnedFlowClient displaced = null;
        ConnectClaim connectClaim = null;
        RuntimeException shutdownFailure = null;
        ReSyncFlowClient unpublished = null;
        Throwable transitionFailure = null;
        OwnershipLock ownership = acquireOwnership(serverId);
        try {
            requireAvailable(ownership);
            if (connectionLifecycleClosed) {
                return null;
            }
            owner = flowClients.get(serverId);
            if (owner != null && owner.client.usesFrameTransport(session.transport())
                && apiKey != null && !owner.client.matchesDirectProfile(null, apiKey)) {
                throw new IllegalArgumentException("Close The Current Resource Connection Before Changing Its API Key");
            }
            if (owner == null || !owner.client.usesFrameTransport(session.transport())) {
                requireNoCurrentThreadLease();
                displaced = owner;
                ReSyncConnectionProfile displacedProfile = flowProfiles.get(serverId);
                if (displaced != null) {
                    scheduleLiveReplacement(serverId, displaced, session, apiKey);
                    return null;
                }
                ReSyncFlowClient flowClient = createLiveFlowClient(serverId, session.transport(), apiKey);
                unpublished = flowClient;
                OwnedFlowClient replacement = prepareOwner(serverId, flowClient, ErrorMode.LIVE_SESSION,
                    session.transport());
                shutdownFailure = retireDisplaced(ownership, displaced);
                if (shutdownFailure != null) {
                    throw shutdownFailure;
                }
                if (displaced != null && !flowClients.remove(serverId, displaced)) {
                    throw new IllegalStateException("ReSync connection ownership changed during replacement.");
                }
                synchronized (connectionLifecycleAdmission) {
                    if (!connectionLifecycleClosed && (apiKey == null || session.transport().reconnectable()
                        || session.transport().state() == ReSyncFrameTransport.State.NEW)) {
                        flowClients.put(serverId, replacement);
                        if (apiKey != null) networkClients.put(session.transport(), replacement);
                        owner = replacement;
                        unpublished = null;
                        removeProfileIfSame(serverId, displacedProfile);
                        traceOwnerLifecycle(serverId, "connection_owner_published", owner, "live_session_owner_current");
                    }
                }
                if (unpublished != null) {
                    owner = null;
                    connectClaim = null;
                }
            } else {
                owner.errorMode = ErrorMode.LIVE_SESSION;
                shutdownFailure = null;
            }
            if (unpublished == null) {
                connectClaim = owner.client.isFrameTransportRetired() && !session.transport().reusableAfterDisconnect()
                    ? null : claimConnect(owner);
            }
        } catch (RuntimeException | Error error) {
            transitionFailure = error;
        } finally {
            releaseOwnership(serverId, ownership);
        }
        if (unpublished != null) {
            try {
                unpublished.shutdown();
            } catch (RuntimeException | Error error) {
                if (transitionFailure == null) {
                    transitionFailure = error;
                } else {
                    transitionFailure.addSuppressed(error);
                }
            }
        }
        if (transitionFailure != null) {
            if (transitionFailure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) transitionFailure;
        }
        if (unpublished != null) {
            return null;
        }
        finishOwnershipTransition(serverId, owner, connectClaim, shutdownFailure);
        return owner.client;
    }

    private ReSyncFlowClient ensureFlowClient(String serverId, ReSyncConnectionProfile profile, boolean showNotifications, boolean connectIfNeeded) {
        serverId = connectionKey(serverId);
        if (serverId == null || serverId.isBlank() || connectionLifecycleClosed) {
            return null;
        }
        ProfileReplacement pendingReplacement = pendingProfileReplacements.get(serverId);
        if (pendingReplacement != null) {
            dispatchProfileReplacement(pendingReplacement);
            return null;
        }
        OwnedFlowClient owner;
        ConnectClaim connectClaim;
        ReSyncFlowClient unpublished = null;
        OwnershipLock ownership = acquireOwnership(serverId);
        try {
            requireAvailable(ownership);
            if (connectionLifecycleClosed) {
                return null;
            }
            owner = flowClients.get(serverId);
            ErrorMode errorMode = showNotifications || owner != null && owner.errorMode == ErrorMode.NOTIFY
                ? ErrorMode.NOTIFY : ErrorMode.SILENT;
            if (profile != null) {
                flowProfiles.put(serverId, profile);
            }
            if (owner != null && profile != null && (profileOwnerRefreshRequired.contains(serverId)
                || !owner.client.matchesDirectProfile(profile.wsUrl(), profile.apiKey()))) {
                requireNoCurrentThreadLease();
                scheduleProfileReplacement(serverId, owner, profile, errorMode, connectIfNeeded);
                return null;
            }
            if (owner == null) {
                ReSyncFlowClient flowClient = createProfileFlowClient(serverId, profile);
                owner = publishOwner(serverId, flowClient, errorMode);
                if (owner == null) {
                    unpublished = flowClient;
                    connectClaim = null;
                } else {
                    profileOwnerRefreshRequired.remove(serverId);
                    connectClaim = connectIfNeeded ? claimConnect(owner) : null;
                }
            } else {
                if (owner.errorMode == ErrorMode.SILENT || owner.errorMode == ErrorMode.NOTIFY) {
                    owner.errorMode = errorMode;
                }
                connectClaim = connectIfNeeded ? claimConnect(owner) : null;
            }
        } finally {
            releaseOwnership(serverId, ownership);
        }
        if (unpublished != null) {
            try {
                unpublished.shutdown();
            } catch (RuntimeException ignored) {
            }
            return null;
        }
        finishOwnershipTransition(serverId, owner, connectClaim, null);
        return owner.client;
    }

    private ReSyncFlowClient createProfileFlowClient(String serverId, ReSyncConnectionProfile profile) {
        if (flowClientFactory.available()) {
            Object state = flowClientContext == null ? client : flowClientContext;
            String endpoint = profile == null ? null : profile.wsUrl();
            String credential = profile == null ? null : profile.apiKey();
            return Objects.requireNonNull(flowClientFactory.create(serverId, apiClient, endpoint, credential, null, state),
                "ReSync flow client factory returned no client");
        }
        return profile != null && profile.wsUrl() != null && !profile.wsUrl().isBlank()
            ? new ReSyncFlowClient(serverId, apiClient, profile.wsUrl(), profile.apiKey(), client)
            : new ReSyncFlowClient(serverId, apiClient, client);
    }

    private ReSyncFlowClient createLiveFlowClient(String serverId, ReSyncFrameTransport transport, String apiKey) {
        if (flowClientFactory.available()) {
            Object state = flowClientContext == null ? client : flowClientContext;
            return Objects.requireNonNull((apiKey == null ? flowClientFactory.createLive(serverId, transport, state)
                    : flowClientFactory.createNetwork(serverId, apiKey, transport, state)),
                "ReSync flow client factory returned no live client");
        }
        if (apiKey != null) {
            throw new IllegalStateException("ReSync Network Client Factory Is Unavailable");
        }
        ReSyncCatalogPublicationCache publicationCache = catalogPublicationCacheFactory.apply(serverId);
        return publicationCache == null ? new ReSyncFlowClient(serverId, transport, client)
            : new ReSyncFlowClient(serverId, transport, client, publicationCache);
    }

    private OwnedFlowClient publishOwner(String serverId, ReSyncFlowClient flowClient, ErrorMode errorMode) {
        OwnedFlowClient owner = prepareOwner(serverId, flowClient, errorMode, null);
        synchronized (connectionLifecycleAdmission) {
            if (connectionLifecycleClosed) {
                traceOwnerLifecycle(serverId, "connection_owner_publication_rejected", owner, "lifecycle_closed");
                return null;
            }
            flowClients.put(serverId, owner);
            traceOwnerLifecycle(serverId, "connection_owner_published", owner, "owner_current");
            return owner;
        }
    }

    private OwnedFlowClient prepareOwner(String serverId, ReSyncFlowClient flowClient, ErrorMode errorMode,
                                         ReSyncFrameTransport callbackTransport) {
        OwnedFlowClient owner = new OwnedFlowClient(flowClient, nextConnectionGeneration.incrementAndGet(), errorMode,
            callbackTransport);
        flowClient.setConnectionListener(() -> notifyConnected(serverId, owner));
        flowClient.setDisconnectListener(() -> notifyDisconnected(serverId, owner));
        flowClient.setErrorListener((nodeId, message) -> notifyError(serverId, owner, message));
        traceOwnerLifecycle(serverId, "connection_owner_listeners_bound", owner,
            callbackTransport == null ? "direct_callback" : "transport_callback");
        return owner;
    }

    private void scheduleProfileReplacement(String serverId, OwnedFlowClient owner,
                                            ReSyncConnectionProfile profile, ErrorMode errorMode,
                                            boolean connectIfNeeded) {
        if (connectionLifecycleClosed) {
            return;
        }
        ProfileReplacement replacement = new ProfileReplacement(serverId, owner, profile, null, errorMode,
            connectIfNeeded, null, 0L, nextProfileReplacementGeneration.incrementAndGet(), null);
        scheduleReplacement(replacement);
    }

    private void scheduleLiveReplacement(String serverId, OwnedFlowClient owner, ReSyncLiveServerSession session, String apiKey) {
        if (connectionLifecycleClosed) {
            return;
        }
        ProfileReplacement replacement = new ProfileReplacement(serverId, owner, null, session,
            ErrorMode.LIVE_SESSION, true, null, 0L, nextProfileReplacementGeneration.incrementAndGet(), apiKey);
        scheduleReplacement(replacement);
    }

    private void scheduleProfileRetirement(String serverId, String resolutionFenceKey, OwnedFlowClient owner,
                                           long resolutionGeneration) {
        if (connectionLifecycleClosed || owner == null || !owner.client.usesDirectWebSocketTransport()) {
            return;
        }
        ProfileReplacement replacement = new ProfileReplacement(serverId, owner, null, null, ErrorMode.SILENT,
            false, resolutionFenceKey, resolutionGeneration, nextProfileReplacementGeneration.incrementAndGet(), null);
        scheduleReplacement(replacement);
    }

    private void scheduleReplacement(ProfileReplacement replacement) {
        synchronized (connectionLifecycleAdmission) {
            if (connectionLifecycleClosed
                || pendingProfileReplacements.putIfAbsent(replacement.serverId(), replacement) != null) {
                return;
            }
            replacement.expectedOwner().callbacksSuppressed = true;
        }
        dispatchProfileReplacement(replacement);
    }

    private void dispatchProfileReplacement(ProfileReplacement replacement) {
        if (connectionLifecycleClosed) {
            return;
        }
        long now = System.nanoTime();
        Long retryAt = profileReplacementRetryAt.get(replacement);
        if (retryAt != null && retryAt > now) {
            return;
        }
        profileReplacementRetryAt.remove(replacement);
        if (!isCurrentProfileReplacement(replacement) || !dispatchedProfileReplacements.add(replacement)) {
            return;
        }
        try {
            connectionLifecycleExecutor.execute(() -> replaceConnection(replacement));
        } catch (IllegalStateException error) {
            dispatchedProfileReplacements.remove(replacement);
            if (profileReplacementRetryAt.putIfAbsent(replacement,
                now + ((1L) * 1_000_000_000L)) == null) {
                notifyProfileReplacementFailure(replacement);
                try {
                    profileTimeoutExecutor.schedule(() -> {
                        profileReplacementRetryAt.remove(replacement);
                        dispatchProfileReplacement(replacement);
                    }, Duration.ofSeconds(1L));
                } catch (IllegalStateException retryError) {
                    pendingProfileReplacements.remove(replacement.serverId(), replacement);
                    profileReplacementRetryAt.remove(replacement);
                    replacement.expectedOwner().callbacksSuppressed = false;
                }
            }
        }
    }

    private void replaceConnection(ProfileReplacement replacement) {
        OwnedFlowClient published = null;
        ReSyncFlowClient unpublished = null;
        ConnectClaim connectClaim = null;
        Throwable failure = null;
        boolean retired = false;
        boolean retryPending = false;
        OwnershipLock ownership = acquireOwnership(replacement.serverId());
        try {
            requireAvailable(ownership);
            if (!isCurrentProfileReplacement(replacement)
                || !isOwner(replacement.serverId(), replacement.expectedOwner())
                || !isReplacementSourceCurrent(replacement)) {
                replacement.expectedOwner().callbacksSuppressed = false;
                return;
            }
            RuntimeException shutdownFailure = retireDisplaced(ownership, replacement.expectedOwner());
            if (shutdownFailure != null) {
                throw shutdownFailure;
            }
            retired = true;
            if (!flowClients.remove(replacement.serverId(), replacement.expectedOwner())) {
                throw new IllegalStateException("ReSync connection ownership changed during profile replacement.");
            }
            boolean profileRetirement = replacement.profile() == null && replacement.liveSession() == null;
            if (!isCurrentProfileReplacement(replacement) || connectionLifecycleClosed
                || !isReplacementSourceCurrent(replacement)) {
                return;
            }
            if (profileRetirement) {
                return;
            }
            ReSyncFlowClient flowClient;
            ReSyncFrameTransport callbackTransport;
            if (replacement.liveSession() != null) {
                flowClient = createLiveFlowClient(replacement.serverId(), replacement.liveSession().transport(), replacement.apiKey());
                callbackTransport = replacement.liveSession().transport();
                flowProfiles.remove(replacement.serverId());
            } else {
                flowClient = createProfileFlowClient(replacement.serverId(), replacement.profile());
                callbackTransport = null;
            }
            published = prepareOwner(replacement.serverId(), flowClient, replacement.errorMode(), callbackTransport);
            synchronized (connectionLifecycleAdmission) {
                if (connectionLifecycleClosed || !isCurrentProfileReplacement(replacement) || !isReplacementSourceCurrent(replacement)) {
                    unpublished = flowClient;
                    published = null;
                } else {
                    flowClients.put(replacement.serverId(), published);
                    if (replacement.apiKey() != null) networkClients.put(callbackTransport, published);
                    profileOwnerRefreshRequired.remove(replacement.serverId());
                    traceOwnerLifecycle(replacement.serverId(), "connection_owner_published", published,
                        replacement.liveSession() == null ? "profile_replacement_current" : "live_replacement_current");
                }
            }
            if (published != null) {
                connectClaim = replacement.connectIfNeeded()
                    && (replacement.liveSession() == null || !published.client.isFrameTransportRetired()
                        || replacement.liveSession().transport().reusableAfterDisconnect())
                    ? claimConnect(published) : null;
            }
        } catch (Throwable error) {
            failure = error;
            if (retired) {
                flowClients.remove(replacement.serverId(), replacement.expectedOwner());
            } else if (isOwner(replacement.serverId(), replacement.expectedOwner())) {
                replacement.expectedOwner().callbacksSuppressed = true;
                retryPending = true;
            }
        } finally {
            dispatchedProfileReplacements.remove(replacement);
            if (retryPending && isCurrentProfileReplacement(replacement)) {
                profileReplacementRetryAt.put(replacement, System.nanoTime() + ((1L) * 1_000_000_000L));
            } else {
                pendingProfileReplacements.remove(replacement.serverId(), replacement);
                profileReplacementRetryAt.remove(replacement);
            }
            releaseOwnership(replacement.serverId(), ownership);
        }
        if (unpublished != null) {
            try {
                unpublished.shutdown();
            } catch (Throwable error) {
                failure = failure == null ? error : mergeFailure(asFailure(failure), error);
            }
        }
        if (published != null) {
            try {
                finishOwnershipTransition(replacement.serverId(), published, connectClaim, null);
            } catch (Throwable error) {
                failure = failure == null ? error : mergeFailure(asFailure(failure), error);
            }
        }
        if (failure != null) {
            notifyProfileReplacementFailure(replacement);
        }
    }

    private void notifyProfileReplacementFailure(ProfileReplacement replacement) {
        if (replacement.errorMode() != ErrorMode.NOTIFY) {
            return;
        }
        ScreenManager.getInstance().execute(() -> {
            if (!connectionLifecycleClosed && isCurrentProfileReplacement(replacement)
                && replacement.expectedOwner().callbacksSuppressed
                && isOwner(replacement.serverId(), replacement.expectedOwner())) {
                new Notification("ReSync", "ReSync Connection Couldn't Be Refreshed. Try Again",
                    Notification.Type.ERROR);
            }
        });
    }

    private boolean isCurrentProfileReplacement(ProfileReplacement replacement) {
        ProfileReplacement current = pendingProfileReplacements.get(replacement.serverId());
        return !connectionLifecycleClosed && current == replacement && current.generation() == replacement.generation();
    }

    private boolean isReplacementSourceCurrent(ProfileReplacement replacement) {
        if (replacement.profile() != null) {
            return flowProfiles.get(replacement.serverId()) == replacement.profile();
        }
        if (replacement.liveSession() != null) {
            return replacement.apiKey() == null || replacement.liveSession().transport().reconnectable()
                || replacement.liveSession().transport().state() == ReSyncFrameTransport.State.NEW;
        }
        CachedProfileResolution current = profileResolutionCache.get(replacement.resolutionFenceKey());
        return current != null && current.resolution().generation() == replacement.resolutionGeneration();
    }

    private void shutdownConnectionLifecycle() {
        synchronized (connectionLifecycleAdmission) {
            if (!connectionLifecycleClosed) {
                connectionLifecycleClosed = true;
            }
            pendingProfileReplacements.clear();
            dispatchedProfileReplacements.clear();
            profileReplacementRetryAt.clear();
            pendingFlowClientAdmissions.values().forEach(admission -> admission.complete(null));
            pendingFlowClientAdmissions.clear();
        }
        connectionLifecycleExecutor.shutdown();
    }

    private void awaitConnectionLifecycleTermination(ShutdownContext context) {
        if (connectionLifecycleExecutor.isTerminated()) {
            return;
        }
        if (TaskIdentities.access.name().startsWith("ReSync-Connection-Lifecycle-")) {
            context.forced = true;
            connectionLifecycleAbort = true;
            connectionLifecycleExecutor.shutdownNow();
            return;
        }
        while (!connectionLifecycleExecutor.isTerminated()) {
            long remaining = context.deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                context.forced = true;
                context.timedOut = true;
                connectionLifecycleAbort = true;
                connectionLifecycleExecutor.shutdownNow();
                return;
            }
            connectionLifecycleExecutor.awaitTermination(Math.min(Math.max(1L, remaining / 1_000_000L), 100L));
        }
    }

    private ConnectClaim claimConnect(OwnedFlowClient owner) {
        if (owner.connectInvoking || (owner.connectClaimed && (!owner.connectDispatch.isDone()
            || owner.client.connectionState() != ReSyncFlowClient.ConnectionState.DISCONNECTED))) {
            traceOwnerLifecycle(null, "connection_owner_connect_claim_reused", owner, "connect_already_claimed");
            return new ConnectClaim(false, owner.connectDispatch);
        }
        owner.connectClaimed = true;
        owner.connectDispatch = Async.pending();
        traceOwnerLifecycle(null, "connection_owner_connect_claimed", owner, "connect_dispatch_required");
        return new ConnectClaim(true, owner.connectDispatch);
    }

    private void connectOwned(String serverId, OwnedFlowClient owner, ConnectClaim claim) {
        if (claim == null) {
            traceOwnerLifecycle(serverId, "connection_owner_connect_dropped", owner, "claim_unavailable");
            return;
        }
        if (connectionLifecycleClosed) {
            traceOwnerLifecycle(serverId, "connection_owner_connect_dropped", owner, "lifecycle_closed");
            return;
        }
        if (!claim.dispatch()) {
            traceOwnerLifecycle(serverId, "connection_owner_connect_deduplicated", owner, "existing_dispatch");
            return;
        }
        OwnershipLock ownership = acquireOwnership(serverId);
        try {
            if (!isOwner(serverId, owner) || !owner.connectClaimed) {
                traceOwnerLifecycle(serverId, "connection_owner_connect_dropped", owner,
                    !isOwner(serverId, owner) ? "owner_generation_stale" : "claim_released");
                claim.completion().complete(null);
                return;
            }
            owner.connectInvoking = true;
            claim.completion().complete(null);
            acquireLease(owner);
            traceOwnerLifecycle(serverId, "connection_owner_connect_executing", owner, "connect_started");
        } finally {
            releaseOwnership(serverId, ownership);
        }
        RuntimeException failure = null;
        try {
            owner.client.connect();
        } catch (RuntimeException error) {
            failure = error;
            traceOwnerLifecycle(serverId, "connection_owner_connect_failed", owner, TaskIdentities.failureName(error));
            throw error;
        } finally {
            OwnershipLock completionOwnership = acquireOwnership(serverId);
            try {
                if (isOwner(serverId, owner)) {
                    owner.connectInvoking = false;
                    if (failure != null) {
                        owner.connectClaimed = false;
                    }
                }
                releaseLease(owner);
                completionOwnership.lock.signalAll();
            } finally {
                releaseOwnership(serverId, completionOwnership);
            }
        }
    }

    private RuntimeException shutdownDisplaced(OwnedFlowClient displaced) {
        if (displaced == null) {
            return null;
        }
        try {
            displaced.client.shutdown();
            if (displaced.callbackTransport != null) networkClients.remove(displaced.callbackTransport, displaced);
            return null;
        } catch (RuntimeException error) {
            return error;
        }
    }

    private RuntimeException retireDisplaced(OwnershipLock ownership, OwnedFlowClient displaced) {
        if (displaced == null) {
            return null;
        }
        displaced.callbacksSuppressed = true;
        ownership.retiring = true;
        try {
            awaitLeases(ownership, displaced);
            ownership.lock.unlock();
            try {
                RuntimeException failure = shutdownDisplaced(displaced);
                if (failure != null) {
                    displaced.callbacksSuppressed = false;
                }
                return failure;
            } catch (RuntimeException | Error error) {
                displaced.callbacksSuppressed = false;
                throw error;
            } finally {
                ownership.lock.lock();
            }
        } finally {
            ownership.retiring = false;
            ownership.lock.signalAll();
        }
    }

    private void finishOwnershipTransition(String serverId, OwnedFlowClient owner, ConnectClaim connectClaim,
                                           RuntimeException shutdownFailure) {
        try {
            connectOwned(serverId, owner, connectClaim);
        } catch (RuntimeException error) {
            if (shutdownFailure != null) {
                error.addSuppressed(shutdownFailure);
            }
            throw error;
        }
        if (shutdownFailure != null) {
            throw shutdownFailure;
        }
    }

    private void notifyConnected(String serverId, OwnedFlowClient owner) {
        OwnershipLock ownership = acquireOwnership(serverId);
        try {
            String rejection = connectionLifecycleClosed ? "lifecycle_closed"
                : ownership.retiring ? "owner_retiring"
                : owner.callbacksSuppressed ? "callbacks_suppressed"
                : pendingProfileReplacements.containsKey(serverId) ? "replacement_pending"
                : !isOwner(serverId, owner) ? "owner_generation_stale"
                : owner.client.connectionState() != ReSyncFlowClient.ConnectionState.CONNECTED
                ? "connection_state_not_connected" : null;
            if (rejection != null) {
                traceOwnerLifecycle(serverId, "connection_owner_connected_callback_dropped", owner, rejection);
                return;
            }
            owner.connectClaimed = true;
            traceOwnerLifecycle(serverId, "connection_owner_connected_callback_admitted", owner, "owner_current");
        } finally {
            releaseOwnership(serverId, ownership);
        }
        publishCallback(serverId, owner, () -> withCurrentFlowClient(serverId, owner.client,
            current -> !owner.callbacksSuppressed && !pendingProfileReplacements.containsKey(serverId)
                && current.connectionState() == ReSyncFlowClient.ConnectionState.CONNECTED,
            ignored -> connectionListener.accept(serverId)));
    }

    private void notifyDisconnected(String serverId, OwnedFlowClient owner) {
        OwnershipLock ownership = acquireOwnership(serverId);
        try {
            String rejection = connectionLifecycleClosed ? "lifecycle_closed"
                : ownership.retiring ? "owner_retiring"
                : owner.callbacksSuppressed ? "callbacks_suppressed"
                : pendingProfileReplacements.containsKey(serverId) ? "replacement_pending"
                : !isOwner(serverId, owner) ? "owner_generation_stale" : null;
            if (rejection != null) {
                traceOwnerLifecycle(serverId, "connection_owner_disconnected_callback_dropped", owner, rejection);
                return;
            }
            if (owner.client.connectionState() != ReSyncFlowClient.ConnectionState.DISCONNECTED) {
                traceOwnerLifecycle(serverId, "connection_owner_disconnected_callback_dropped", owner,
                    "connection_state_not_disconnected");
                return;
            }
            owner.connectClaimed = false;
            traceOwnerLifecycle(serverId, "connection_owner_disconnected_callback_admitted", owner, "owner_current");
        } finally {
            releaseOwnership(serverId, ownership);
        }
        publishCallback(serverId, owner, () -> withCurrentFlowClient(serverId, owner.client,
            current -> !owner.callbacksSuppressed && !pendingProfileReplacements.containsKey(serverId)
                && current.connectionState() == ReSyncFlowClient.ConnectionState.DISCONNECTED,
            ignored -> disconnectListener.accept(serverId)));
    }

    private void notifyError(String serverId, OwnedFlowClient owner, String message) {
        ErrorMode errorMode;
        if (!isOwner(serverId, owner)) {
            traceOwnerLifecycle(serverId, "connection_owner_error_callback_dropped", owner, "owner_generation_stale");
            return;
        }
        OwnershipLock ownership = acquireOwnership(serverId);
        try {
            String rejection = ownership.retiring ? "owner_retiring"
                : owner.callbacksSuppressed ? "callbacks_suppressed"
                : !isOwner(serverId, owner) ? "owner_generation_stale" : null;
            if (rejection != null) {
                traceOwnerLifecycle(serverId, "connection_owner_error_callback_dropped", owner, rejection);
                return;
            }
            if (owner.client.connectionState() == ReSyncFlowClient.ConnectionState.DISCONNECTED) {
                owner.connectClaimed = false;
            }
            errorMode = owner.errorMode;
        } finally {
            releaseOwnership(serverId, ownership);
        }
        String normalized = normalizeReSyncNotificationMessage(message);
        boolean transientLiveFailure = errorMode == ErrorMode.LIVE_SESSION
            && ("ReSync Connection Timed Out".equals(normalized) || "ReSync Connection Failed".equals(normalized));
        if (errorMode == ErrorMode.SILENT || transientLiveFailure) {
            traceOwnerLifecycle(serverId, "connection_owner_error_callback_dropped", owner,
                errorMode == ErrorMode.SILENT ? "silent_error_mode" : "live_session_transient_failure_suppressed");
            return;
        }
        ReSyncNotificationLevel level = errorMode == ErrorMode.NOTIFY && "ReSync Connection Timed Out".equals(normalized)
            ? ReSyncNotificationLevel.WARN : ReSyncNotificationLevel.ERROR;
        traceOwnerLifecycle(serverId, "connection_owner_error_callback_admitted", owner, "notification_queued");
        publishCallback(serverId, owner, () -> {
            if (!isOwner(serverId, owner)) {
                traceOwnerLifecycle(serverId, "connection_owner_error_callback_dropped", owner,
                    "owner_changed_before_notification");
                return;
            }
            OwnershipLock notificationOwnership = acquireOwnership(serverId);
            try {
                if (!notificationOwnership.retiring && !owner.callbacksSuppressed
                    && !pendingProfileReplacements.containsKey(serverId) && isOwner(serverId, owner)) {
                    traceOwnerLifecycle(serverId, "connection_owner_error_callback_executing", owner,
                        "notification_visible");
                    notificationSink.show("ReSync", normalized, level);
                } else {
                    traceOwnerLifecycle(serverId, "connection_owner_error_callback_dropped", owner,
                        "notification_fence_changed");
                }
            } finally {
                releaseOwnership(serverId, notificationOwnership);
            }
        });
    }

    private ReSyncFrameTransport.CallbackPublication publishCallback(String serverId, OwnedFlowClient owner,
                                                                     Runnable callback) {
        Runnable traced = () -> {
            try {
                callback.run();
            } catch (RuntimeException | Error error) {
                traceOwnerLifecycle(serverId, "connection_owner_callback_failed", owner,
                    TaskIdentities.failureName(error));
                throw error;
            }
        };
        if (owner.callbackTransport == null) {
            traced.run();
            return ReSyncFrameTransport.CallbackPublication.EXECUTED;
        }
        ReSyncFrameTransport.CallbackPublication publication = owner.callbackTransport.publishCallback(traced);
        if (publication == ReSyncFrameTransport.CallbackPublication.STALE) {
            traceOwnerLifecycle(serverId, "connection_owner_callback_dropped", owner, "stale");
        }
        return publication;
    }

    private boolean isOwner(String serverId, OwnedFlowClient owner) {
        OwnedFlowClient current = flowClients.get(serverId);
        return current != null && current.generation == owner.generation && current.client == owner.client;
    }

    private OwnershipLock acquireOwnership(String serverId) {
        OwnershipLock ownership = connectionOwnershipLocks.compute(serverId, (ignored, current) -> {
            OwnershipLock resolved = current != null ? current : new OwnershipLock();
            resolved.references++;
            return resolved;
        });
        ownership.lock.lock();
        return ownership;
    }

    private OwnershipLock tryAcquireOwnership(String serverId) {
        OwnershipLock ownership = connectionOwnershipLocks.compute(serverId, (ignored, current) -> {
            OwnershipLock resolved = current != null ? current : new OwnershipLock();
            resolved.references++;
            return resolved;
        });
        if (ownership.lock.tryLock()) {
            return ownership;
        }
        connectionOwnershipLocks.computeIfPresent(serverId, (ignored, current) -> {
            if (current != ownership) {
                return current;
            }
            current.references--;
            return current.references == 0 && !flowClients.containsKey(serverId)
                && !pendingRetirements.containsKey(serverId) ? null : current;
        });
        return null;
    }

    private void requireAvailable(OwnershipLock ownership) {
        if (ownership.retiring) {
            throw new IllegalStateException("Cannot Enter ReSync Ownership During Retirement");
        }
    }

    private void awaitLeases(OwnershipLock ownership, OwnedFlowClient owner) {
        awaitLeases(ownership, owner, Long.MAX_VALUE, false);
    }

    private boolean awaitLeases(OwnershipLock ownership, OwnedFlowClient owner, long deadlineNanos,
                                boolean stopOnInterrupt) {
        if (owner == null) {
            return true;
        }
        boolean interrupted = false;
        boolean complete = false;
        ownership.lock.unlock();
        try {
            synchronized (owner.leaseMonitor) {
                while (owner.activeLeases > 0) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    try {
                        if (deadlineNanos == Long.MAX_VALUE) {
                            owner.leaseMonitor.wait();
                        } else {
                            long waitNanos = Math.min(remaining, ((100L) * 1_000_000L));
                            long waitMillis = ((waitNanos) / 1_000_000L);
                            int waitNanosPart = (int) (waitNanos - ((waitMillis) * 1_000_000L));
                            if (waitMillis == 0L && waitNanosPart == 0) {
                                waitNanosPart = 1;
                            }
                            owner.leaseMonitor.wait(waitMillis, waitNanosPart);
                        }
                    } catch (InterruptedException error) {
                        interrupted = true;
                        if (stopOnInterrupt) {
                            break;
                        }
                    }
                }
                complete = owner.activeLeases == 0;
            }
        } finally {
            ownership.lock.lock();
        }
        if (interrupted && !stopOnInterrupt) {
            TaskIdentities.access.interrupt();
        }
        return complete;
    }

    private void acquireLease(OwnedFlowClient owner) {
        synchronized (owner.leaseMonitor) {
            owner.activeLeases++;
        }
        ownerLeaseDepth.set(ownerLeaseDepth.get() + 1);
    }

    private void releaseLease(OwnedFlowClient owner) {
        int depth = ownerLeaseDepth.get() - 1;
        if (depth == 0) {
            ownerLeaseDepth.remove();
        } else {
            ownerLeaseDepth.set(depth);
        }
        synchronized (owner.leaseMonitor) {
            owner.activeLeases--;
            owner.leaseMonitor.notifyAll();
        }
    }

    private void requireNoCurrentThreadLease() {
        if (ownerLeaseDepth.get() > 0) {
            throw new IllegalStateException("Cannot Retire ReSync Client During An Active Owner Action");
        }
    }

    private void releaseOwnership(String serverId, OwnershipLock ownership) {
        ownership.lock.unlock();
        connectionOwnershipLocks.computeIfPresent(serverId, (ignored, current) -> {
            if (current != ownership) {
                return current;
            }
            current.references--;
            return current.references == 0 && !flowClients.containsKey(serverId)
                && !pendingRetirements.containsKey(serverId) ? null : current;
        });
    }

    public boolean closeCurrentFlowClient(String serverId, Consumer<ReSyncFlowClient> onRemoved) {
        return closeRetirement(connectionKey(serverId), RetirementKind.CURRENT, onRemoved, null);
    }

    public void closeServerConnection(String serverId, Runnable onCacheClear) {
        closeServerConnection(serverId, null, onCacheClear);
    }

    public void closeServerConnection(String serverId, Consumer<ReSyncFlowClient> onRemoved, Runnable onCacheClear) {
        closeServerConnectionAtomically(serverId, source -> {
            if (source != null && onRemoved != null) {
                onRemoved.accept(source);
            }
        }, onCacheClear);
    }

    public boolean closeServerConnectionAtomically(String serverId, Consumer<ReSyncFlowClient> onRetired,
                                                   Runnable onCacheClear) {
        return closeRetirement(connectionKey(serverId), RetirementKind.SERVER, onRetired, onCacheClear);
    }

    private boolean closeRetirement(String serverId, RetirementKind kind,
                                    Consumer<ReSyncFlowClient> onRetired, Runnable onCacheClear) {
        return closeRetirement(serverId, kind, onRetired, onCacheClear, Long.MAX_VALUE, false);
    }

    private boolean closeRetirement(String serverId, RetirementKind kind,
                                    Consumer<ReSyncFlowClient> onRetired, Runnable onCacheClear,
                                    long deadlineNanos, boolean boundedShutdown) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        if (shouldStopRetirement(boundedShutdown) || connectionLifecycleClosed && !boundedShutdown) {
            return false;
        }
        if (kind == RetirementKind.SERVER) {
            invalidateProfileResolution(serverId, false);
        }
        OwnershipLock ownership = acquireOwnership(serverId);
        boolean retirementStarted = false;
        boolean keepRetiring = false;
        try {
            RetirementProgress progress = pendingRetirements.get(serverId);
            OwnedFlowClient owner = flowClients.get(serverId);
            if (progress != null) {
                if (progress.kind != kind) {
                    throw new IllegalStateException("ReSync connection has a different pending retirement operation.");
                }
                if (progress.owner != owner || progress.stepRunning) {
                    throw new IllegalStateException("ReSync connection retirement is already in progress.");
                }
            } else {
                requireAvailable(ownership);
                if (kind == RetirementKind.CURRENT && owner == null) {
                    return false;
                }
            }
            requireNoCurrentThreadLease();
            ownership.retiring = true;
            retirementStarted = true;
            if (progress == null) {
                progress = new RetirementProgress(owner, kind, flowProfiles.get(serverId),
                    flowProfiles.containsKey(serverId), onRetired, onCacheClear);
                pendingRetirements.put(serverId, progress);
                if (owner == null) {
                    progress.shutdownComplete = true;
                }
            }
            boolean leasesComplete;
            if (boundedShutdown) {
                leasesComplete = awaitLeases(ownership, owner, deadlineNanos, true);
            } else {
                awaitLeases(ownership, owner);
                leasesComplete = true;
            }
            if (!leasesComplete) {
                connectionLifecycleAbort = true;
                pendingRetirements.remove(serverId, progress);
                return false;
            }
            if (shouldStopRetirement(boundedShutdown)) {
                pendingRetirements.remove(serverId, progress);
                return false;
            }
            if (!progress.shutdownComplete) {
                progress.stepRunning = true;
                ownership.lock.unlock();
                Throwable shutdownError = null;
                try {
                    owner.client.shutdown();
                } catch (Throwable error) {
                    shutdownError = error;
                } finally {
                    ownership.lock.lock();
                    progress.stepRunning = false;
                }
                if (shutdownError != null) {
                    pendingRetirements.remove(serverId, progress);
                    throw asFailure(shutdownError);
                }
                if (shouldStopRetirement(boundedShutdown)) {
                    pendingRetirements.remove(serverId, progress);
                    return false;
                }
                progress.shutdownComplete = true;
            }
            if (shouldStopRetirement(boundedShutdown)) {
                pendingRetirements.remove(serverId, progress);
                return false;
            }
            RuntimeException failure;
            try {
                failure = finishRetirement(serverId, ownership, progress, boundedShutdown);
            } catch (Throwable error) {
                keepRetiring = true;
                throw asFailure(error);
            }
            if (failure != null || !progress.complete()) {
                keepRetiring = true;
                throw failure != null ? failure : new IllegalStateException("ReSync connection retirement is incomplete.");
            }
            if (owner != null && !flowClients.remove(serverId, owner)) {
                keepRetiring = true;
                throw new IllegalStateException("ReSync connection ownership changed during close.");
            }
            if (owner != null && owner.callbackTransport != null) networkClients.remove(owner.callbackTransport, owner);
            pendingRetirements.remove(serverId, progress);
            return owner != null;
        } finally {
            if (retirementStarted) {
                if (!keepRetiring) {
                    ownership.retiring = false;
                }
                ownership.lock.signalAll();
            }
            releaseOwnership(serverId, ownership);
        }
    }

    private RuntimeException finishRetirement(String serverId, OwnershipLock ownership,
                                              RetirementProgress progress, boolean boundedShutdown) {
        if (shouldStopRetirement(boundedShutdown)) {
            return new IllegalStateException("ReSync connection shutdown was forced.");
        }
        RuntimeException failure = null;
        if (!progress.retiredCallbackComplete) {
            failure = mergeFailure(failure, invokeRetiredCallback(ownership, progress));
        }
        if (shouldStopRetirement(boundedShutdown)) {
            return mergeFailure(failure, new IllegalStateException("ReSync connection shutdown was forced."));
        }
        if (progress.kind == RetirementKind.SERVER) {
            if (!progress.nodeRegistryComplete) {
                failure = mergeFailure(failure, clearNodeRegistry(serverId, ownership, progress));
            }
            if (shouldStopRetirement(boundedShutdown)) {
                return mergeFailure(failure, new IllegalStateException("ReSync connection shutdown was forced."));
            }
            if (!progress.cacheClearComplete) {
                failure = mergeFailure(failure, clearCache(ownership, progress));
            }
            if (shouldStopRetirement(boundedShutdown)) {
                return mergeFailure(failure, new IllegalStateException("ReSync connection shutdown was forced."));
            }
            if (!progress.profileCleanupComplete && failure == null) {
                failure = mergeFailure(failure, removeRetiredProfile(serverId, progress));
            }
        }
        return failure;
    }

    private boolean shouldStopRetirement(boolean boundedShutdown) {
        return shutdownTerminal || connectionLifecycleAbort && !boundedShutdown;
    }

    private RuntimeException invokeRetiredCallback(OwnershipLock ownership, RetirementProgress progress) {
        progress.stepRunning = true;
        Throwable error = null;
        ownership.lock.unlock();
        try {
            progress.onRetired.accept(progress.owner == null ? null : progress.owner.client);
        } catch (Throwable failure) {
            error = failure;
        } finally {
            ownership.lock.lock();
            progress.stepRunning = false;
        }
        if (error == null) {
            progress.retiredCallbackComplete = true;
            return null;
        }
        return asFailure(error);
    }

    private RuntimeException clearNodeRegistry(String serverId, OwnershipLock ownership,
                                               RetirementProgress progress) {
        progress.stepRunning = true;
        Throwable error = null;
        ownership.lock.unlock();
        try {
            NodeRegistry registry = nodeRegistry == null ? NodeRegistry.getInstance() : nodeRegistry;
            if (registry != null) {
                registry.clearServer(serverId);
            }
        } catch (Throwable failure) {
            error = failure;
        } finally {
            ownership.lock.lock();
            progress.stepRunning = false;
        }
        if (error == null) {
            progress.nodeRegistryComplete = true;
            return null;
        }
        return asFailure(error);
    }

    private RuntimeException clearCache(OwnershipLock ownership, RetirementProgress progress) {
        progress.stepRunning = true;
        Throwable error = null;
        ownership.lock.unlock();
        try {
            progress.onCacheClear.run();
        } catch (Throwable failure) {
            error = failure;
        } finally {
            ownership.lock.lock();
            progress.stepRunning = false;
        }
        if (error == null) {
            progress.cacheClearComplete = true;
            return null;
        }
        return asFailure(error);
    }

    private RuntimeException removeRetiredProfile(String serverId, RetirementProgress progress) {
        try {
            if (progress.profilePresent) {
                removeProfileIfSame(serverId, progress.profile);
            }
            progress.profileCleanupComplete = true;
            return null;
        } catch (Throwable error) {
            return asFailure(error);
        }
    }

    private RuntimeException asFailure(Throwable error) {
        return error instanceof RuntimeException runtimeException
            ? runtimeException : new IllegalStateException("ReSync connection cleanup failed.", error);
    }

    private void removeProfileIfSame(String serverId, ReSyncConnectionProfile profile) {
        if (profile != null) {
            flowProfiles.computeIfPresent(serverId, (ignored, current) -> current == profile ? null : current);
        }
    }

    private RuntimeException mergeFailure(RuntimeException current, Throwable next) {
        if (next == null) {
            return current;
        }
        RuntimeException failure = next instanceof RuntimeException runtimeException
            ? runtimeException : new IllegalStateException("ReSync connection cleanup failed.", next);
        if (current == null) {
            return failure;
        }
        if (current != failure) {
            current.addSuppressed(failure);
        }
        return current;
    }

    public void setDisconnectListener(Consumer<String> listener) {
        disconnectListener = listener != null ? listener : serverId -> {};
    }

    public void setConnectionListener(Consumer<String> listener) {
        connectionListener = listener != null ? listener : serverId -> {};
    }

    private LinkedHashSet<String> shutdownServerIds() {
        LinkedHashSet<String> serverIds = new LinkedHashSet<>();
        serverIds.addAll(flowClients.keySet());
        serverIds.addAll(pendingRetirements.keySet());
        serverIds.addAll(flowProfiles.keySet());
        return serverIds;
    }

    private boolean hasShutdownState(String serverId) {
        return flowClients.containsKey(serverId) || pendingRetirements.containsKey(serverId)
            || flowProfiles.containsKey(serverId);
    }

    private RuntimeException drainShutdownRetirements(ShutdownContext context) {
        LinkedHashSet<String> serverIds = shutdownServerIds();
        if (serverIds.isEmpty()) {
            return null;
        }
        Map<String, RuntimeException> failures = BrowserSafeState.map();
        BrowserSafeState.Latch finished = new BrowserSafeState.Latch(serverIds.size());
        for (String serverId : serverIds) {
            BrowserWork.execute(() -> {
                RuntimeException failure = null;
                try {
                    for (int attempt = 0; attempt < SHUTDOWN_DRAIN_ATTEMPTS && hasShutdownState(serverId); attempt++) {
                        if (System.nanoTime() >= context.deadlineNanos) {
                            break;
                        }
                        RetirementProgress progress = pendingRetirements.get(serverId);
                        RetirementKind kind = progress == null ? RetirementKind.SERVER : progress.kind;
                        try {
                            closeRetirement(serverId, kind, null, null, context.deadlineNanos, true);
                        } catch (Throwable error) {
                            failure = mergeFailure(failure, error);
                        }
                    }
                } catch (Throwable error) {
                    failure = mergeFailure(failure, error);
                } finally {
                    if (failure != null) {
                        failures.put(serverId, failure);
                    }
                    finished.countDown();
                }
            });
        }
        while (finished.getCount() > 0L) {
            long remaining = context.deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                context.forced = true;
                context.timedOut = true;
                break;
            }
            try {
                if (finished.await(remaining)) {
                    break;
                }
            } catch (InterruptedException error) {
                context.interrupted = true;
            }
        }
        RuntimeException failure = null;
        for (String serverId : serverIds) {
            failure = mergeFailure(failure, failures.get(serverId));
        }
        if (context.timedOut) {
            failure = mergeFailure(failure,
                new IllegalStateException("ReSync connection cleanup exceeded its deadline."));
        }
        return failure;
    }

    private void forceShutdownState() {
        connectionLifecycleAbort = true;
        LinkedHashSet<String> serverIds = shutdownServerIds();
        serverIds.addAll(connectionOwnershipLocks.keySet());
        for (String serverId : serverIds) {
            OwnedFlowClient owner = flowClients.remove(serverId);
            if (owner != null) {
                owner.callbacksSuppressed = true;
                owner.connectClaimed = false;
            }
            pendingRetirements.remove(serverId);
            flowProfiles.remove(serverId);
            profileOwnerRefreshRequired.remove(serverId);
            OwnershipLock ownership = connectionOwnershipLocks.remove(serverId);
            if (ownership != null && ownership.lock.tryLock()) {
                try {
                    ownership.retiring = false;
                    ownership.lock.signalAll();
                } finally {
                    ownership.lock.unlock();
                }
            }
        }
        flowClients.clear();
        networkClients.clear();
        pendingRetirements.clear();
        flowProfiles.clear();
        profileOwnerRefreshRequired.clear();
        connectionOwnershipLocks.clear();
    }

    public synchronized void shutdownAll() {
        if (shutdownTerminal) {
            return;
        }
        ShutdownContext lifecycleContext = new ShutdownContext(System.nanoTime()
            + ((CONNECTION_LIFECYCLE_SHUTDOWN_TIMEOUT_MILLIS) * 1_000_000L));
        ShutdownContext cleanupContext = new ShutdownContext(Long.MAX_VALUE);
        RuntimeException shutdownFailure = null;
        try {
            shutdownConnectionLifecycle();
            shutdownProfileResolution();
            awaitConnectionLifecycleTermination(lifecycleContext);
            if (lifecycleContext.timedOut) {
                shutdownFailure = mergeFailure(shutdownFailure,
                    new IllegalStateException("ReSync connection shutdown exceeded its deadline."));
            }
            cleanupContext = new ShutdownContext(System.nanoTime()
                + ((CONNECTION_RETIREMENT_SHUTDOWN_TIMEOUT_MILLIS) * 1_000_000L));
            shutdownFailure = mergeFailure(shutdownFailure, drainShutdownRetirements(cleanupContext));
            if (shutdownFailure != null) {
                throw shutdownFailure;
            }
        } finally {
            shutdownTerminal = true;
            forceShutdownState();
            if (lifecycleContext.interrupted || cleanupContext.interrupted) {
                TaskIdentities.access.interrupt();
            }
        }
    }

    public Async<ProfileResolution> resolveAndStoreProfile(String serverId, ClientServerView server) {
        ProfileTarget target = captureProfileRequest(serverId, server);
        if (target.serverId() == null || target.serverId().isBlank()) {
            return Async.completed(new ProfileResolution("", "", 0L, null, "ServerIdMissing"));
        }
        ProfileFlight flight;
        LinkedHashSet<ProfileFlight> displacedFlights = new LinkedHashSet<>();
        synchronized (profileResolutionLock) {
            CachedProfileResolution candidate = profileResolutionCache.get(target.serverId());
            LinkedHashSet<String> lookupAliases = new LinkedHashSet<>(target.aliases());
            if (candidate != null && sameProfileLookup(candidate.target(), target)) {
                lookupAliases.addAll(candidate.target().aliases());
            }
            List<String> admittedAliases = lookupAliases.stream().sorted().toList();
            ProfileFlight current = admittedAliases.stream().map(profileFlights::get)
                .filter(existing -> existing != null).findFirst().orElse(null);
            if (current != null && sameProfileRequest(current.target, target)) {
                return current.completion;
            }
            if (profileResolutionClosed) {
                return Async.completed(new ProfileResolution(target.serverId(), target.instanceId(),
                    0L, null, "ReSyncProfileResolutionClosed"));
            }
            long generation = nextProfileResolutionGeneration.incrementAndGet();
            String expectedOwnerKey = admittedAliases.stream().filter(alias -> flowClients.get(alias) != null)
                .findFirst().orElse(target.serverId());
            OwnedFlowClient expectedOwner = flowClients.get(expectedOwnerKey);
            flight = new ProfileFlight(target, admittedAliases, candidate, expectedOwnerKey, expectedOwner, generation);
            for (String alias : admittedAliases) {
                profileResolutionGenerations.put(alias, generation);
                profileResolutionCache.remove(alias);
                flowProfiles.remove(alias);
                ProfileFlight displaced = profileFlights.put(alias, flight);
                if (displaced != null && displaced != flight) {
                    displacedFlights.add(displaced);
                }
            }
        }
        displacedFlights.forEach(displaced -> {
            synchronized (profileResolutionLock) {
                for (String alias : displaced.lookupAliases) {
                    profileFlights.remove(alias, displaced);
                    if (profileResolutionGenerations.getOrDefault(alias, 0L) <= flight.generation) {
                        profileResolutionCache.remove(alias);
                        flowProfiles.remove(alias);
                        if (profileFlights.get(alias) == flight) {
                            profileResolutionGenerations.put(alias, flight.generation);
                        } else {
                            profileResolutionGenerations.remove(alias);
                        }
                    }
                }
            }
            retireProfileFlight(displaced);
            completeProfileFlight(displaced, "ReSyncProfileChanged");
        });
        startProfileFlight(flight);
        return flight.completion;
    }

    public Async<ProfileResolution> resolveConnectionProfileAsync(String serverId, ClientServerView server) {
        return resolveAndStoreProfile(serverId, server);
    }

    public ReSyncConnectionProfile resolveConnectionProfile(String serverId, ClientServerView server) {
        ProfileResolution resolution = resolveAndStoreProfile(serverId, server).getNow(null);
        return resolution != null ? resolution.profile() : null;
    }

    public Async<String> getFlowAvailabilityIssueAsync(String serverId, ClientServerView server) {
        if (serverId != null && !serverId.isBlank() && !staleProfileAliases.contains(serverId)
            && flowProfiles.get(connectionKey(serverId)) != null) {
            return Async.completed(null);
        }
        return resolveAndStoreProfile(serverId, server).thenCompose(resolution ->
            "ReSyncProfileChanged".equals(resolution.issue())
                ? resolveAndStoreProfile(serverId, server).thenApply(ProfileResolution::issue)
                : Async.completed(resolution.issue()));
    }

    public String getFlowAvailabilityIssue(String serverId, ClientServerView server) {
        String actualServerId = server != null && server.identifier != null && !server.identifier.isBlank()
            ? server.identifier : serverId;
        if (actualServerId == null || actualServerId.isBlank()) {
            return "ServerIdMissing";
        }
        if (!staleProfileAliases.contains(actualServerId)
            && flowProfiles.get(connectionKey(actualServerId)) != null) {
            return null;
        }
        ProfileResolution resolution = resolveAndStoreProfile(serverId, server).getNow(null);
        return resolution != null ? resolution.issue() : "ReSyncProfileLoading";
    }

    public void provisionReSyncForReStudioServer(String serverId, Consumer<Boolean> callback) {
        if (serverId == null || serverId.isBlank()) {
            if (callback != null) {
                callback.accept(false);
            }
            return;
        }
        if (apiClient == null) {
            ScreenManager.getInstance().execute(() -> new Notification("ReSync", "ReSync Isn't Installed/Enabled", Notification.Type.ERROR));
            if (callback != null) {
                callback.accept(false);
            }
            return;
        }
        apiClient.provisionReSync(serverId).thenAccept(response -> {
            boolean ok = response != null && response.success;
            ScreenManager.getInstance().execute(() -> {
                if (!ok) {
                    String message = response != null && response.message != null && !response.message.isBlank() ? response.message : "Provision Failed";
                    String normalized = normalizeReSyncNotificationMessage(message);
                    Notification.Type type = "ReSync Connection Timed Out".equals(normalized) ? Notification.Type.WARN : Notification.Type.ERROR;
                    new Notification("ReSync", normalized, type);
                }
            });
            if (callback != null) {
                callback.accept(ok);
            }
        }).exceptionally(error -> {
            ScreenManager.getInstance().execute(() -> {
                String reason = error != null && error.getMessage() != null ? error.getMessage() : "ProvisionFailed";
                String normalized = normalizeReSyncNotificationMessage(reason);
                Notification.Type type = "ReSync Connection Timed Out".equals(normalized) ? Notification.Type.WARN : Notification.Type.ERROR;
                new Notification("ReSync", normalized, type);
            });
            if (callback != null) {
                callback.accept(false);
            }
            return null;
        });
    }

    public void updateReSyncForReStudioServer(String serverId, Consumer<Boolean> callback) {
        if (serverId == null || serverId.isBlank()) {
            if (callback != null) {
                callback.accept(false);
            }
            return;
        }
        if (apiClient == null) {
            ScreenManager.getInstance().execute(() -> new Notification("ReSync", "ReSync Isn't Installed/Enabled", Notification.Type.ERROR));
            if (callback != null) {
                callback.accept(false);
            }
            return;
        }
        apiClient.updateReSync(serverId).thenAccept(response -> {
            boolean ok = response != null && response.success;
            ScreenManager.getInstance().execute(() -> {
                if (!ok) {
                    String message = response != null && response.message != null && !response.message.isBlank() ? response.message : "Update Failed";
                    String normalized = normalizeReSyncNotificationMessage(message);
                    Notification.Type type = "ReSync Connection Timed Out".equals(normalized) ? Notification.Type.WARN : Notification.Type.ERROR;
                    new Notification("ReSync", normalized, type);
                }
            });
            if (callback != null) {
                callback.accept(ok);
            }
        }).exceptionally(error -> {
            ScreenManager.getInstance().execute(() -> {
                String reason = error != null && error.getMessage() != null ? error.getMessage() : "UpdateFailed";
                String normalized = normalizeReSyncNotificationMessage(reason);
                Notification.Type type = "ReSync Connection Timed Out".equals(normalized) ? Notification.Type.WARN : Notification.Type.ERROR;
                new Notification("ReSync", normalized, type);
            });
            if (callback != null) {
                callback.accept(false);
            }
            return null;
        });
    }

    public Async<String> getReSyncVersionForReStudioServer(String serverId) {
        if (serverId == null || serverId.isBlank() || apiClient == null) {
            return Async.completed("");
        }
        Async<String> future = Async.pending();
        apiClient.getReSyncVersion(serverId).whenComplete((version, error) -> {
            if (error != null) {
                future.complete("");
            } else {
                future.complete(version == null ? "" : version);
            }
        });
        return future;
    }

    public Object getInstanceByServerId(String serverId) {
        return findInstanceByServerId(serverId, null);
    }

    public Object findInstanceByServerId(String serverId, ClientServerView server) {
        return ReSyncLocalInstances.access.find(serverId, server);
    }

    public RemotelyClient getClient() {
        return client;
    }

    private ProfileTarget captureProfileRequest(String serverId, ClientServerView server) {
        String requestedServerId = safeText(serverId).trim();
        String viewServerId = server != null ? safeText(server.identifier).trim() : "";
        String actualServerId = !requestedServerId.isBlank() ? requestedServerId : viewServerId;
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (!actualServerId.isBlank()) {
            aliases.add(actualServerId);
        }
        if (!requestedServerId.isBlank()) {
            aliases.add(requestedServerId);
        }
        if (!viewServerId.isBlank()) {
            aliases.add(viewServerId);
        }
        if (server == null && !actualServerId.isBlank()) {
            String ownerKey = profileConnectionKeys.getOrDefault(actualServerId, actualServerId);
            profileConnectionKeys.forEach((alias, owner) -> {
                if (ownerKey.equals(owner)) {
                    aliases.add(alias);
                }
            });
        }
        if (server != null && "RESTUDIO".equalsIgnoreCase(safeText(server.backendType))) {
            return new ProfileTarget(actualServerId, List.copyOf(aliases), null, "", "", null, "RESTUDIO", "",
                Map.of(), true, true);
        }
        return new ProfileTarget(actualServerId, List.copyOf(aliases), null, "", "", null, "", "", Map.of(),
            false, false);
    }

    private ProfileTarget resolveProfileTarget(ProfileTarget request) {
        Object instance = findInstanceByServerId(request.serverId(), null);
        if (instance == null) {
            instance = request.aliases().stream().map(alias -> findInstanceByServerId(alias, null))
                .filter(candidate -> candidate != null).findFirst().orElse(null);
        }
        if (instance == null) {
            return new ProfileTarget(request.serverId(), request.aliases(), null, "", "", null, "", "", Map.of(),
                false, true);
        }
        Object backendConfig = ReSyncLocalInstances.access.backendConfig(instance);
        String backendType = ReSyncLocalInstances.access.backendType(backendConfig);
        Map<String, String> credentials = ReSyncLocalInstances.access.credentials(backendConfig);
        String backendHost = ReSyncLocalInstances.access.backendHost(instance);
        String instanceId = safeText(ReSyncLocalInstances.access.instanceId(instance)).trim();
        String identifier = credentials != null ? safeText(credentials.get("identifier")).trim() : "";
        LinkedHashSet<String> aliases = new LinkedHashSet<>(request.aliases());
        if (!instanceId.isBlank()) {
            aliases.add(instanceId);
        }
        if (!identifier.isBlank()) {
            aliases.add(identifier);
        }
        List<String> resolvedAliases = aliases.stream().filter(alias -> alias != null && !alias.isBlank()).sorted().toList();
        String canonicalServerId = !instanceId.isBlank() ? instanceId
            : !identifier.isBlank() ? identifier : request.serverId();
        return new ProfileTarget(canonicalServerId, resolvedAliases, instance, instanceId,
            ReSyncLocalInstances.access.instancePath(instance), backendConfig, backendType, backendHost,
            snapshotCredentials(credentials), "RESTUDIO".equalsIgnoreCase(backendType), true);
    }

    private void startProfileFlight(ProfileFlight flight) {
        try {
            flight.timeout = profileTimeoutExecutor.schedule(
                () -> finishProfileFlight(flight, new ProfileRead(null, "ReSyncProfileResolutionTimedOut", false),
                    flight.target.resolved()), Duration.ofSeconds(PROFILE_RESOLUTION_TIMEOUT_SECONDS));
            flight.task = profileResolutionExecutor.submit(() -> {
                flight.taskThread = TaskIdentities.access.current();
                try {
                    if (!flight.target.resolved()) {
                        CachedProfileResolution candidate = flight.candidate;
                        ProfileTarget resolved = candidate != null && candidate.target().resolved()
                            && isProfileTargetCurrent(candidate.target())
                            ? candidate.target() : resolveProfileTarget(flight.target);
                        if (!adoptResolvedProfileTarget(flight, resolved)) {
                            completeChangedProfileFlight(flight);
                            return;
                        }
                    }
                    if (flight.finished.get()) {
                        return;
                    }
                    CachedProfileResolution candidate = flight.candidate;
                    if (candidate != null && candidate.expiresAt() > System.nanoTime()
                        && sameProfileTarget(candidate.target(), flight.target)) {
                        ProfileResolution cached = candidate.resolution();
                        finishProfileFlight(flight, new ProfileRead(cached.profile(), cached.issue(), true), true);
                        return;
                    }
                    finishProfileFlight(flight, readProfile(flight.target), true);
                } catch (RuntimeException error) {
                    finishProfileFlight(flight, new ProfileRead(null, "ReSyncConfigurationUnavailable", false),
                        flight.target.resolved());
                } finally {
                    flight.taskThread = null;
                }
            });
        } catch (IllegalStateException error) {
            retireProfileFlight(flight);
            finishProfileFlight(flight, new ProfileRead(null, "ReSyncProfileResolutionBusy", false), false);
        }
    }

    private boolean adoptResolvedProfileTarget(ProfileFlight flight, ProfileTarget resolved) {
        LinkedHashSet<ProfileFlight> displacedFlights = new LinkedHashSet<>();
        synchronized (profileResolutionLock) {
            if (profileResolutionClosed || flight.finished.get()) {
                return false;
            }
            for (String alias : flight.lookupAliases) {
                if (profileFlights.get(alias) != flight
                    || profileResolutionGenerations.getOrDefault(alias, 0L) != flight.generation) {
                    return false;
                }
            }
            for (String alias : resolved.aliases()) {
                if (profileResolutionGenerations.getOrDefault(alias, 0L) > flight.generation) {
                    return false;
                }
            }
            for (String alias : flight.lookupAliases) {
                if (!resolved.aliases().contains(alias)) {
                    profileFlights.remove(alias, flight);
                    profileResolutionGenerations.remove(alias, flight.generation);
                    profileConnectionKeys.remove(alias);
                }
            }
            flight.target = resolved;
            flight.lookupAliases = resolved.aliases();
            for (String alias : resolved.aliases()) {
                ProfileFlight displaced = profileFlights.put(alias, flight);
                if (displaced != null && displaced != flight) {
                    displacedFlights.add(displaced);
                }
                profileResolutionGenerations.put(alias, flight.generation);
                profileResolutionCache.remove(alias);
                flowProfiles.remove(alias);
            }
        }
        displacedFlights.forEach(displaced -> {
            synchronized (profileResolutionLock) {
                for (String alias : displaced.lookupAliases) {
                    profileFlights.remove(alias, displaced);
                    profileResolutionGenerations.remove(alias, displaced.generation);
                }
            }
            retireProfileFlight(displaced);
            completeProfileFlight(displaced, "ReSyncProfileChanged");
        });
        return true;
    }

    private void completeChangedProfileFlight(ProfileFlight flight) {
        synchronized (profileResolutionLock) {
            for (String alias : flight.lookupAliases) {
                profileFlights.remove(alias, flight);
                profileResolutionGenerations.remove(alias, flight.generation);
            }
        }
        retireProfileFlight(flight);
        completeProfileFlight(flight, "ReSyncProfileChanged");
    }

    private ProfileRead readProfile(ProfileTarget target) {
        if (!target.apiManaged() && target.instance() != null) {
            if (target.backendConfig() == null || target.backendType().isBlank()
                    || "LOCAL".equalsIgnoreCase(target.backendType())) {
                return tryReadLocalReSyncConfig(target.instance());
            }
            return tryReadReSyncConfigFromBackend(target);
        }
        ReSyncServerIdentity identity = new ReSyncServerIdentity(target.serverId(), "", target.backendType());
        ReSyncConnectionProfile provided = profileProvider.resolve(identity);
        if (provided != null) {
            if (!profileProvider.connectionAllowed(identity, provided)) {
                return new ProfileRead(null, "ReSyncUnavailable", false);
            }
            String providedServerId = provided.serverId() == null || provided.serverId().isBlank()
                ? target.serverId() : provided.serverId();
            return new ProfileRead(new ReSyncConnectionProfile(providedServerId, provided.wsUrl(), provided.apiKey()),
                null, true);
        }
        if (profileProvider.connectionPending(identity)) {
            return new ProfileRead(null, "ReSyncProfileLoading", false);
        }
        if (target.apiManaged()) {
            return new ProfileRead(null, null, true);
        }
        return new ProfileRead(null, "ServerNotFound", false);
    }

    private void finishProfileFlight(ProfileFlight flight, ProfileRead read, boolean verifyTarget) {
        if (!flight.finished.compareAndSet(false, true)) {
            return;
        }
        boolean targetCurrent = !verifyTarget || isProfileTargetCurrent(flight.target);
        ProfileResolution resolution;
        boolean retireProfile = false;
        synchronized (profileResolutionLock) {
            boolean ownsGeneration = targetCurrent && !profileResolutionClosed;
            for (String alias : flight.lookupAliases) {
                ownsGeneration &= profileFlights.get(alias) == flight
                    && profileResolutionGenerations.getOrDefault(alias, 0L) == flight.generation;
            }
            if (!ownsGeneration) {
                for (String alias : flight.lookupAliases) {
                    profileFlights.remove(alias, flight);
                    profileResolutionGenerations.remove(alias, flight.generation);
                }
                resolution = new ProfileResolution(flight.target.serverId(), flight.target.instanceId(),
                    flight.generation, null, "ReSyncProfileChanged");
            } else {
                String resolvedServerId = read.profile() != null && read.profile().serverId() != null
                    && !read.profile().serverId().isBlank() ? read.profile().serverId() : flight.target.serverId();
                resolution = new ProfileResolution(resolvedServerId, flight.target.instanceId(),
                    flight.generation, read.profile(), read.issue());
                long expiresAt = System.nanoTime() + ((PROFILE_CACHE_MILLIS) * 1_000_000L);
                CachedProfileResolution cached = new CachedProfileResolution(flight.target, resolution, expiresAt);
                String ownerKey = resolvedServerId;
                if (flight.expectedOwner != null && flight.candidate != null
                    && !sameProfileTarget(flight.candidate.target(), flight.target)) {
                    profileOwnerRefreshRequired.add(ownerKey);
                }
                LinkedHashSet<String> resolvedAliases = new LinkedHashSet<>(flight.lookupAliases);
                resolvedAliases.add(resolvedServerId);
                for (String alias : resolvedAliases) {
                    profileFlights.remove(alias, flight);
                    profileResolutionGenerations.remove(alias, flight.generation);
                    profileResolutionCache.put(alias, cached);
                    profileConnectionKeys.put(alias, ownerKey);
                    staleProfileAliases.remove(alias);
                    if (resolution.available() && resolution.profile() != null) {
                        flowProfiles.put(alias, resolution.profile());
                    } else {
                        flowProfiles.remove(alias);
                    }
                }
                if (resolution.available() && resolution.profile() != null) {
                    flowProfiles.put(ownerKey, resolution.profile());
                    if (flight.expectedOwner != null && flowClients.get(ownerKey) == flight.expectedOwner
                        && flight.expectedOwner.client.matchesDirectProfile(resolution.profile().wsUrl(),
                            resolution.profile().apiKey())) {
                        profileOwnerRefreshRequired.remove(ownerKey);
                    }
                } else {
                    flowProfiles.remove(ownerKey);
                }
                staleProfileAliases.remove(ownerKey);
                trimProfileResolutionCache();
                retireProfile = targetCurrent && definitiveProfileAbsence(read.issue());
            }
        }
        retireProfileFlight(flight);
        flight.completion.complete(resolution);
        if (retireProfile) {
            scheduleProfileRetirement(flight.expectedOwnerKey, flight.target.serverId(), flight.expectedOwner,
                flight.generation);
        }
    }

    private boolean definitiveProfileAbsence(String issue) {
        return "ServerNotFound".equals(issue) || "ReSyncNotConfigured".equals(issue)
            || "ReSyncPortMissing".equals(issue) || "ReSyncApiKeyMissing".equals(issue)
            || "ReSyncHostMissing".equals(issue);
    }

    private boolean isProfileTargetCurrent(ProfileTarget target) {
        if (target.apiManaged() && target.instance() == null) {
            return true;
        }
        Object current = findInstanceByServerId(target.serverId(), null);
        if (target.instance() == null) {
            return current == null;
        }
        if (current != target.instance()) {
            return false;
        }
        Object backendConfig = ReSyncLocalInstances.access.backendConfig(current);
        Map<String, String> credentials = ReSyncLocalInstances.access.credentials(backendConfig);
        String backendHost = ReSyncLocalInstances.access.backendHost(current);
        return backendConfig == target.backendConfig()
            && safeText(ReSyncLocalInstances.access.instanceId(current)).equals(target.instanceId())
            && safeText(ReSyncLocalInstances.access.instancePath(current)).equals(target.path())
            && ReSyncLocalInstances.access.backendType(backendConfig).equals(target.backendType())
            && backendHost.equals(target.backendHost())
            && snapshotCredentials(credentials).equals(target.backendCredentials());
    }

    private boolean sameProfileRequest(ProfileTarget first, ProfileTarget second) {
        return first.apiManaged() == second.apiManaged()
            && (first.serverId().equals(second.serverId()) || first.aliases().stream().anyMatch(second.aliases()::contains));
    }

    private boolean sameProfileLookup(ProfileTarget resolved, ProfileTarget request) {
        return resolved.apiManaged() == request.apiManaged()
            && (resolved.serverId().equals(request.serverId()) || request.aliases().stream().anyMatch(resolved.aliases()::contains));
    }

    private boolean sameProfileTarget(ProfileTarget first, ProfileTarget second) {
        return first.serverId().equals(second.serverId())
            && first.aliases().equals(second.aliases())
            && first.instance() == second.instance()
            && first.backendConfig() == second.backendConfig()
            && first.instanceId().equals(second.instanceId())
            && first.path().equals(second.path())
            && first.backendType().equals(second.backendType())
            && first.backendHost().equals(second.backendHost())
            && first.backendCredentials().equals(second.backendCredentials())
            && first.apiManaged() == second.apiManaged()
            && first.resolved() == second.resolved();
    }

    private Map<String, String> snapshotCredentials(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return Map.of();
        }
        Map<String, String> snapshot = new TreeMap<>();
        try {
            credentials.forEach((key, value) -> {
                if (key != null) {
                    snapshot.put(key, safeText(value));
                }
            });
        } catch (RuntimeException error) {
            return Map.of();
        }
        return Map.copyOf(snapshot);
    }

    private void trimProfileResolutionCache() {
        if (profileResolutionCache.size() <= PROFILE_CACHE_CAPACITY) {
            return;
        }
        long now = System.nanoTime();
        profileResolutionCache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        while (profileResolutionCache.size() > PROFILE_CACHE_CAPACITY) {
            String oldestServerId = null;
            long oldestExpiry = Long.MAX_VALUE;
            for (Map.Entry<String, CachedProfileResolution> entry : profileResolutionCache.entrySet()) {
                if (entry.getValue().expiresAt() < oldestExpiry) {
                    oldestServerId = entry.getKey();
                    oldestExpiry = entry.getValue().expiresAt();
                }
            }
            if (oldestServerId == null) {
                return;
            }
            profileResolutionCache.remove(oldestServerId);
        }
    }

    private void invalidateProfileResolution(String serverId, boolean removeProfile) {
        LinkedHashSet<ProfileFlight> flights = new LinkedHashSet<>();
        synchronized (profileResolutionLock) {
            nextProfileResolutionGeneration.incrementAndGet();
            ProfileFlight direct = profileFlights.get(serverId);
            if (direct != null) {
                flights.add(direct);
                for (String alias : direct.lookupAliases) {
                    profileFlights.remove(alias, direct);
                    profileResolutionCache.remove(alias);
                    profileResolutionGenerations.remove(alias);
                    profileConnectionKeys.remove(alias);
                    if (removeProfile) {
                        flowProfiles.remove(alias);
                    }
                }
            } else {
                CachedProfileResolution cached = profileResolutionCache.get(serverId);
                List<String> aliases = cached != null ? cached.target().aliases() : List.of(serverId);
                for (String alias : aliases) {
                    profileResolutionCache.remove(alias);
                    profileResolutionGenerations.remove(alias);
                    profileConnectionKeys.remove(alias);
                    if (removeProfile) {
                        flowProfiles.remove(alias);
                    }
                }
            }
        }
        flights.forEach(flight -> {
            retireProfileFlight(flight);
            completeProfileFlight(flight, "ReSyncProfileChanged");
        });
    }

    private void invalidateProfileSources() {
        LinkedHashSet<ProfileFlight> flights;
        synchronized (profileResolutionLock) {
            staleProfileAliases.addAll(profileConnectionKeys.keySet());
            profileConnectionKeys.values().stream().filter(key -> flowClients.get(key) != null)
                .forEach(profileOwnerRefreshRequired::add);
            flights = new LinkedHashSet<>(profileFlights.values());
            profileFlights.clear();
            profileResolutionCache.clear();
            profileResolutionGenerations.clear();
            nextProfileResolutionGeneration.incrementAndGet();
        }
        flights.forEach(flight -> {
            retireProfileFlight(flight);
            completeProfileFlight(flight, "ReSyncProfileChanged");
        });
    }

    private void shutdownProfileResolution() {
        LinkedHashSet<ProfileFlight> flights;
        synchronized (profileResolutionLock) {
            if (profileResolutionClosed) {
                return;
            }
            profileResolutionClosed = true;
            flights = new LinkedHashSet<>(profileFlights.values());
            profileFlights.clear();
            profileResolutionCache.clear();
            profileResolutionGenerations.clear();
            profileConnectionKeys.clear();
            staleProfileAliases.clear();
            profileOwnerRefreshRequired.clear();
            pendingProfileEnsures.clear();
        }
        profileTimeoutExecutor.shutdownNow();
        profileResolutionExecutor.shutdownNow();
        ReSyncLocalInstances.access.removeListener(profileInstanceChangeListener);
        flights.forEach(flight -> {
            retireProfileFlight(flight);
            completeProfileFlight(flight, "ReSyncProfileResolutionClosed");
        });
    }

    private void completeProfileFlight(ProfileFlight flight, String issue) {
        if (!flight.finished.compareAndSet(false, true)) {
            return;
        }
        flight.completion.complete(new ProfileResolution(flight.target.serverId(), flight.target.instanceId(),
            flight.generation, null, issue));
    }

    private void retireProfileFlight(ProfileFlight flight) {
        TaskScheduler.ScheduledTask timeout = flight.timeout;
        if (timeout != null) {
            timeout.cancel();
        }
        Async<Void> task = flight.task;
        if (task != null && !task.isDone() && flight.taskThread != TaskIdentities.access.current()) {
            task.cancel(true);
        }
    }

    private ProfileRead tryReadReSyncConfigFromBackend(ProfileTarget target) {
        return toProfileRead(ReSyncLocalInstances.access.readBackendProfile(target.instance(), target.path(),
            target.backendHost()));
    }

    private ProfileRead tryReadLocalReSyncConfig(Object instance) {
        return toProfileRead(ReSyncLocalInstances.access.readLocalProfile(instance));
    }

    private ProfileRead toProfileRead(ReSyncLocalInstances.LocalProfile profile) {
        if (profile == null) {
            return new ProfileRead(null, "ReSyncConfigurationUnavailable", false);
        }
        if (profile.content() == null) {
            return new ProfileRead(null, profile.issue() != null ? profile.issue() : "ReSyncConfigurationUnavailable",
                profile.retryable());
        }
        return parseReSyncProfile(profile.content(), profile.host(), profile.serverId());
    }

    private ProfileRead parseReSyncProfile(String content, String host, String serverId) {
        if (content == null || content.isBlank()) {
            return new ProfileRead(null, "ReSyncNotConfigured", true);
        }
        String port = "";
        String apiKey = "";
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            switch (key) {
                case "port" -> port = value;
                case "api-key" -> apiKey = value;
            }
        }
        if (port.isBlank()) {
            return new ProfileRead(null, "ReSyncPortMissing", true);
        }
        if (apiKey.isBlank()) {
            return new ProfileRead(null, "ReSyncApiKeyMissing", true);
        }
        if (host == null || host.isBlank()) {
            return new ProfileRead(null, "ReSyncHostMissing", true);
        }
        String canonicalServerId;
        try {
            canonicalServerId = UUID.fromString(safeText(serverId).trim()).toString();
        } catch (IllegalArgumentException exception) {
            return new ProfileRead(null, "ReSyncServerIdInvalid", true);
        }
        return new ProfileRead(new ReSyncConnectionProfile(canonicalServerId,
            normalizeWsUrl(host + ":" + port), apiKey), null, true);
    }

    private Object findInstanceByServerId(String serverId) {
        return findInstanceByServerId(serverId, null);
    }

    private String normalizeWsUrl(String value) {
        String raw = safeText(value).trim();
        if (raw.isBlank()) {
            return null;
        }
        if (raw.startsWith("ws://") || raw.startsWith("wss://")) {
            return raw;
        }
        if (raw.startsWith("http://")) {
            return "ws://" + raw.substring("http://".length());
        }
        if (raw.startsWith("https://")) {
            return "wss://" + raw.substring("https://".length());
        }
        return "ws://" + raw;
    }

    public String normalizeReSyncNotificationMessage(String message) {
        if (message == null || message.isBlank()) {
            return "ReSync Isn't Installed/Enabled";
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("timed out")) {
            return "ReSync Connection Timed Out";
        }
        if (normalized.contains("server not found")) {
            return "ReSync Couldn't Find This Server. Reconnect The Server And Try Again";
        }
        if (normalized.contains("connection refused") || normalized.contains("connectfailed")) {
            return "ReSync Couldn't Connect. Check That The Server And ReSync Are Running";
        }
        return switch (message) {
            case "ServerIdMissing" -> "Server ID Missing";
            case "ServerNotFound" -> "Server Not Found";
            case "ReSyncNotConfigured" -> "ReSync Not Configured";
            case "ReSyncPortMissing" -> "ReSync Port Missing";
            case "ReSyncApiKeyMissing" -> "ReSync API Key Missing";
            case "ReSyncHostMissing" -> "ReSync Host Missing";
            case "ReSyncProfileLoading" -> "Checking ReSync";
            case "ReSyncProfileResolutionTimedOut" -> "ReSync Connection Timed Out";
            case "ReSyncProfileResolutionBusy" -> "ReSync Check Is Busy. Try Again";
            case "ReSyncConfigurationUnavailable" -> "ReSync Configuration Couldn't Be Read";
            case "ReSyncProfileChanged" -> "ReSync Configuration Changed. Try Again";
            case "ReSyncProfileResolutionClosed" -> "ReSync Is Closing";
            case "ReSyncNotEnabled" -> "ReSync Isn't Installed/Enabled";
            case "ReSyncServerNotFound" -> "ReSync Server Not Found";
            default -> message;
        };
    }

    private void traceOwnerLifecycle(String serverId, String stage, OwnedFlowClient owner, String reason) {
        if (!ReSyncLifecycleDiagnostics.enabled() || !ownerLifecycleTraceAllowed(stage)) {
            return;
        }
        ReSyncFlowClient flowClient = owner == null ? null : owner.client;
        ReSyncFlowClient.traceLifecycle(serverId, stage, "serverId", serverId, "ownerGeneration",
            owner == null ? -1L : owner.generation, "transportGeneration",
            flowClient == null ? -1 : flowClient.activeTransportGeneration(), "authorityEpoch",
            flowClient == null ? 0L : flowClient.authorityEpoch(), "connectionState",
            flowClient == null ? ReSyncFlowClient.ConnectionState.DISCONNECTED : flowClient.connectionState(),
            "callbacksSuppressed", owner != null && owner.callbacksSuppressed, "replacementPending",
            serverId != null && pendingProfileReplacements.containsKey(serverId), "reason", reason);
    }

    private boolean ownerLifecycleTraceAllowed(String stage) {
        return switch (stage) {
            case "connection_owner_lookup_rejected", "connection_owner_callback_dropped",
                 "connection_owner_callback_failed", "connection_owner_published",
                 "connection_owner_publication_rejected", "connection_owner_listeners_bound",
                 "connection_owner_connect_claim_reused", "connection_owner_connect_claimed",
                 "connection_owner_connect_dropped", "connection_owner_connect_deduplicated",
                 "connection_owner_connect_executing", "connection_owner_connect_failed",
                 "connection_owner_connected_callback_dropped", "connection_owner_connected_callback_admitted",
                 "connection_owner_disconnected_callback_dropped", "connection_owner_disconnected_callback_admitted",
                 "connection_owner_error_callback_dropped", "connection_owner_error_callback_admitted",
                 "connection_owner_error_callback_executing" -> true;
            default -> false;
        };
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
