package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncConnectionManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncServerIdentity;
import redxax.oxy.remotely.flow.ui.ReSyncProvisioningService;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.host.ApplicationHostRegistry;
import restudio.rescreen.platform.Async;
import restudio.resync.contract.install.ReSyncInstallationStatus;
import restudio.rescreen.platform.http.HttpRequest;
import restudio.rescreen.platform.http.HttpResponse;
import restudio.rescreen.platform.http.HttpTransport;
import restudio.rebase.restudio.api.models.ServerModels;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

public final class BrowserReSyncProvisioningAdapter implements ReSyncProvisioningService.Adapter {
    private static final String LATEST_RELEASE_PATH = "/releases/resync/latest?channel=stable&platform=universal";

    private final BrowserApplicationHost owner;
    private final HttpTransport transport;
    private long lifecycleGeneration = 1L;
    private long operationGeneration = 1L;
    private String activeServerId = "";
    private final Set<String> knownServerIds = new HashSet<>();
    private final Map<String, Async<ReSyncProvisioningService.StartupProbeResult>> startupProbes = new HashMap<>();
    private boolean invalidated;

    public BrowserReSyncProvisioningAdapter() {
        this(null, new BrowserHttpTransport());
    }

    public BrowserReSyncProvisioningAdapter(HttpTransport transport) {
        this(null, transport);
    }

    BrowserReSyncProvisioningAdapter(BrowserApplicationHost owner, HttpTransport transport) {
        this.owner = owner;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public void invalidateHost() {
        Set<String> affectedServers = new HashSet<>(knownServerIds);
        startupProbes.clear();
        activeServerId = "";
        advanceLifecycleGeneration();
        invalidateReadinessAndDisconnect(affectedServers);
        invalidated = true;
    }

    public void invalidateSession() {
        Set<String> affectedServers = new HashSet<>(knownServerIds);
        startupProbes.clear();
        advanceLifecycleGeneration();
        invalidateReadinessAndDisconnect(affectedServers);
    }

    public void invalidateServerContext(String serverId) {
        String previousServerId = activeServerId;
        startupProbes.clear();
        activeServerId = normalizeServerId(serverId);
        if (!activeServerId.isBlank()) {
            knownServerIds.add(activeServerId);
        }
        advanceLifecycleGeneration();
        invalidateReadinessAndDisconnect(previousServerId);
    }

    @Override
    public Async<ReSyncProvisioningService.StartupProbeResult> computeStartupState(String serverId,
                                                                                     ServerModels.ClientServerView startupServer,
                                                                                     String loaderHint) {
        String canonicalServerId = ReSyncServerIdentity.from(serverId, startupServer).serverId();
        Async<ReSyncProvisioningService.StartupProbeResult> existing = startupProbes.get(canonicalServerId);
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        Async<ReSyncProvisioningService.StartupProbeResult> result = computeStartupStateInternal(
            canonicalServerId, startupServer, loaderHint);
        if (!canonicalServerId.isBlank() && !result.isDone()) {
            startupProbes.put(canonicalServerId, result);
            result.whenComplete((ignored, error) -> startupProbes.remove(canonicalServerId, result));
        }
        return result;
    }

    private Async<ReSyncProvisioningService.StartupProbeResult> computeStartupStateInternal(String serverId,
                                                                                     ServerModels.ClientServerView startupServer,
                                                                                     String loaderHint) {
        LifecycleFence fence = beginOperation(serverId);
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId.isBlank()) {
            return Async.completed(new ReSyncProvisioningService.StartupProbeResult(
                ReSyncProvisioningService.StartupStatus.NOT_SUPPORTED, false, false));
        }
        if (!isCurrent(fence)) {
            return staleResult();
        }
        if (manager.isFlowClientConnected(serverId)) {
            if (currentProfile(manager, fence, serverId)) {
                return Async.completed(new ReSyncProvisioningService.StartupProbeResult(
                    ReSyncProvisioningService.StartupStatus.READY, false, false));
            }
            disconnect(manager, fence, serverId);
        }
        RemotelyServerApi api = api(manager);
        if (api == null) {
            disconnect(manager, fence, serverId);
            setRelayReadiness(fence, serverId, RemotelyServerApi.ReSyncReadinessReason.UNKNOWN);
            return unavailable("ReSync API Is Unavailable", RemotelyServerApi.ReSyncReadinessReason.UNKNOWN,
                ReSyncProvisioningService.StartupStatus.LOADING, fence);
        }
        return api.getServerCapabilities(serverId).handle((capabilities, failure) ->
                failure == null ? authoritativeCapabilityBlock(capabilities) : null)
            .thenCompose(block -> {
                if (!isCurrent(fence)) {
                    return staleResult();
                }
                if (block != null) {
                    disconnect(manager, fence, serverId);
                    setRelayReadiness(fence, serverId, block.reasonCode());
                    return unavailable(block.reason(), block.reasonCode(), block.status(), fence);
                }
                return api.getServerStatus(serverId).handle((status, failure) -> failure == null && status != null
                        && (status.installing || stopped(status.currentState))
                    ? ReSyncProvisioningService.StartupStatus.SERVER_STOPPED : null)
                .thenCompose(stopped -> {
                    if (!isCurrent(fence)) {
                        return staleResult();
                    }
                    if (stopped != null) {
                        setRelayReadiness(fence, serverId, RemotelyServerApi.ReSyncReadinessReason.UNKNOWN);
                        disconnect(manager, fence, serverId);
                        return Async.completed(new ReSyncProvisioningService.StartupProbeResult(stopped, false, false));
                    }
                    if (!isCurrent(fence)) {
                        return staleResult();
                    }
                    return api.getReSyncConfig(serverId).thenCompose(config -> {
                        if (!isCurrent(fence)) {
                            return staleResult();
                        }
                        if (!hasReSyncConfig(config)) {
                            setRelayReadiness(fence, serverId, RemotelyServerApi.ReSyncReadinessReason.PROVISIONING_INCOMPLETE);
                            disconnect(manager, fence, serverId);
                            return Async.completed(new ReSyncProvisioningService.StartupProbeResult(
                                ReSyncProvisioningService.StartupStatus.SETUP, false, false,
                                RemotelyServerApi.ReSyncReadinessReason.PROVISIONING_INCOMPLETE, "ReSync Setup Required"));
                        }
                        if (!isCurrent(fence)) {
                            return staleResult();
                        }
                        manager.ensureFlowClientForStartup(serverId, startupServer, true);
                        if (!isCurrent(fence)) {
                            return staleResult();
                        }
                        return awaitConnection(manager, serverId, fence);
                    });
                });
            }).exceptionally(ignored -> {
            if (!isCurrent(fence)) {
                return staleValue();
            }
            disconnect(manager, fence, serverId);
            setRelayReadiness(fence, serverId, RemotelyServerApi.ReSyncReadinessReason.UNKNOWN);
            return unavailableResult("ReSync Connection Probe Failed: " + errorMessage(ignored), RemotelyServerApi.ReSyncReadinessReason.UPSTREAM_UNREACHABLE,
                ReSyncProvisioningService.StartupStatus.LOADING, fence);
        });
    }

    @Override
    public Async<Boolean> isUpdateAvailable(String serverId, ServerModels.ClientServerView startupServer) {
        String canonicalServerId = ReSyncServerIdentity.from(serverId, startupServer).serverId();
        LifecycleFence fence = captureOperation(canonicalServerId);
        RemotelyServerApi api = api(FlowManager.getInstance());
        if (api == null || canonicalServerId.isBlank()) return Async.completed(false);
        return api.getReSyncVersion(canonicalServerId).thenCompose(installed -> {
            if (!isCurrent(fence)) return Async.completed(false);
            if (installed == null || installed.isBlank()) return Async.completed(false);
            return latestVersion().thenApply(latest -> isCurrent(fence) && newer(latest, installed));
        }).exceptionally(ignored -> false);
    }

    @Override
    public Async<Optional<ReSyncInstallationStatus>> installationStatus(String serverId,
                                                                        ServerModels.ClientServerView startupServer) {
        String canonicalServerId = ReSyncServerIdentity.from(serverId, startupServer).serverId();
        RemotelyServerApi api = api(FlowManager.getInstance());
        if (api == null || canonicalServerId.isBlank()) {
            return Async.completed(Optional.empty());
        }
        return api.getFileContent(canonicalServerId, ReSyncInstallationStatus.FILE_PATH)
            .thenApply(content -> Optional.of(ReSyncInstallationStatus.decode(content)))
            .exceptionally(ignored -> Optional.empty());
    }

    @Override
    public Async<ReSyncProvisioningService.OperationResult> archiveLegacyData(String serverId,
                                                                              ServerModels.ClientServerView startupServer,
                                                                              ReSyncInstallationStatus status) {
        String canonicalServerId = ReSyncServerIdentity.from(serverId, startupServer).serverId();
        RemotelyServerApi api = api(FlowManager.getInstance());
        if (api == null || canonicalServerId.isBlank()) {
            return Async.completed(ReSyncProvisioningService.OperationResult.failed("Server Files Are Unavailable"));
        }
        if (status == null || !status.blocksStartup()
            || !ReSyncInstallationStatus.ARCHIVE_MARKER_PATH.equals(status.markerPath())) {
            return Async.completed(ReSyncProvisioningService.OperationResult.failed(
                "ReSync Is Not Waiting For Legacy Data Archiving"));
        }
        return api.writeFile(canonicalServerId, status.markerPath(), UUID.randomUUID() + "\n")
            .thenApply(ignored -> ReSyncProvisioningService.OperationResult.successful())
            .exceptionally(error -> ReSyncProvisioningService.OperationResult.failed(errorMessage(error)));
    }

    @Override
    public Async<ReSyncProvisioningService.OperationResult> setup(String serverId,
                                                                     ServerModels.ClientServerView startupServer) {
        String canonicalServerId = ReSyncServerIdentity.from(serverId, startupServer).serverId();
        LifecycleFence fence = beginOperation(canonicalServerId);
        RemotelyServerApi api = api(FlowManager.getInstance());
        return api == null || !isCurrent(fence) ? unavailable() : operation(fence, api.provisionReSync(canonicalServerId));
    }

    @Override
    public Async<ReSyncProvisioningService.OperationResult> update(String serverId,
                                                                      ServerModels.ClientServerView startupServer) {
        String canonicalServerId = ReSyncServerIdentity.from(serverId, startupServer).serverId();
        LifecycleFence fence = beginOperation(canonicalServerId);
        RemotelyServerApi api = api(FlowManager.getInstance());
        return api == null || !isCurrent(fence) ? unavailable() : operation(fence, api.updateReSync(canonicalServerId));
    }

    @Override
    public String installedMessage(String serverId, ServerModels.ClientServerView startupServer) {
        return "ReSync Installed!\nRestart Your Server To Activate";
    }

    @Override
    public String updatedMessage(String serverId, ServerModels.ClientServerView startupServer) {
        return "Updated! Restart Server To Activate";
    }

    @Override
    public void clearReleaseCache() {
        operationGeneration = nextGeneration(operationGeneration);
    }

    private Async<ReSyncProvisioningService.OperationResult> operation(LifecycleFence fence,
                                                                        Async<ServerModels.ReSyncProvisionResult> request) {
        return request.thenApply(result -> {
            if (!isCurrent(fence)) {
                return ReSyncProvisioningService.OperationResult.failed("ReSync Provisioning Was Superseded");
            }
            if (result == null || !result.success) {
                return ReSyncProvisioningService.OperationResult.failed(result == null ? "ReSync Provisioning Failed" : result.message);
            }
            FlowManager manager = FlowManager.getInstance();
            if (manager != null && isCurrent(fence)) {
                manager.disconnectServerConnection(fence.serverId());
            }
            setRelayReadiness(fence, fence.serverId(), RemotelyServerApi.ReSyncReadinessReason.UNKNOWN);
            return ReSyncProvisioningService.OperationResult.successful();
        })
            .exceptionally(error -> ReSyncProvisioningService.OperationResult.failed(errorMessage(error)));
    }

    private Async<ReSyncProvisioningService.OperationResult> unavailable() {
        return Async.completed(ReSyncProvisioningService.OperationResult.failed("ReSync Provisioning Is Unavailable"));
    }

    private Async<ReSyncProvisioningService.StartupProbeResult> staleResult() {
        return Async.completed(staleValue());
    }

    private ReSyncProvisioningService.StartupProbeResult staleValue() {
        return new ReSyncProvisioningService.StartupProbeResult(
            ReSyncProvisioningService.StartupStatus.LOADING, false, false);
    }

    private void disconnect(FlowManager manager, LifecycleFence fence, String serverId) {
        if (manager != null && isCurrent(fence)) {
            manager.disconnectServerConnection(serverId);
        }
    }

    private LifecycleFence beginOperation(String serverId) {
        String normalized = normalizeServerId(serverId);
        if (!Objects.equals(activeServerId, normalized)) {
            activeServerId = normalized;
            advanceLifecycleGeneration();
        }
        if (!normalized.isBlank()) {
            knownServerIds.add(normalized);
        }
        operationGeneration = nextGeneration(operationGeneration);
        return captureFence(normalized);
    }

    private LifecycleFence captureOperation(String serverId) {
        String normalized = normalizeServerId(serverId);
        if (!Objects.equals(activeServerId, normalized)) {
            activeServerId = normalized;
            advanceLifecycleGeneration();
        }
        if (!normalized.isBlank()) {
            knownServerIds.add(normalized);
        }
        operationGeneration = nextGeneration(operationGeneration);
        return captureFence(normalized);
    }

    private LifecycleFence captureFence(String serverId) {
        BrowserLaunchSession.Metadata session = BrowserLaunchSession.metadata();
        return new LifecycleFence(lifecycleGeneration, operationGeneration, serverId,
            currentSubjectId(session), BrowserLaunchSession.ticket(), BrowserLaunchSession.authenticated());
    }

    private boolean isCurrent(LifecycleFence fence) {
        if (fence == null || invalidated || fence.lifecycleGeneration() != lifecycleGeneration
                || fence.operationGeneration() != operationGeneration
                || !Objects.equals(fence.serverId(), activeServerId)) {
            return false;
        }
        if (owner != null && ApplicationHostRegistry.current() != owner) {
            return false;
        }
        BrowserLaunchSession.Metadata session = BrowserLaunchSession.metadata();
        return fence.authenticated() == BrowserLaunchSession.authenticated()
            && Objects.equals(fence.ticket(), BrowserLaunchSession.ticket())
            && Objects.equals(fence.subjectId(), currentSubjectId(session));
    }

    private String currentSubjectId(BrowserLaunchSession.Metadata session) {
        return session == null || session.subjectId() == null ? "" : session.subjectId();
    }

    private String normalizeServerId(String serverId) {
        return serverId == null ? "" : serverId.trim();
    }

    private void advanceLifecycleGeneration() {
        lifecycleGeneration = nextGeneration(lifecycleGeneration);
    }

    private long nextGeneration(long value) {
        long next = value + 1L;
        return next <= 0L ? 1L : next;
    }

    private boolean currentProfile(FlowManager manager, LifecycleFence fence, String serverId) {
        if (!isCurrent(fence)) {
            return false;
        }
        ReSyncConnectionManager.ReSyncConnectionProfile profile = manager.reSyncConnectionProfile(serverId);
        return profile != null && Objects.equals(profile.apiKey(), fence.ticket())
            && Objects.equals(profile.wsUrl(), BrowserLaunchSession.reSyncUrl(serverId));
    }

    private void invalidateReadinessAndDisconnect(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        invalidateReadinessAndDisconnect(Set.of(serverId));
    }

    private void invalidateReadinessAndDisconnect(Set<String> serverIds) {
        if (!ownerIsCurrent() || serverIds == null || serverIds.isEmpty()) {
            return;
        }
        ApplicationHost current = ApplicationHostRegistry.current();
        FlowManager manager = FlowManager.getInstance();
        for (String serverId : serverIds) {
            if (serverId == null || serverId.isBlank()) {
                continue;
            }
            if (current instanceof BrowserApplicationHost host) {
                host.setReSyncRelayReadiness(serverId, RemotelyServerApi.ReSyncReadinessReason.UNKNOWN);
            }
            if (manager != null) {
                manager.disconnectServerConnection(serverId);
            }
        }
    }

    private boolean ownerIsCurrent() {
        return !invalidated && (owner == null || ApplicationHostRegistry.current() == owner);
    }

    private record LifecycleFence(long lifecycleGeneration, long operationGeneration, String serverId,
                                  String subjectId, String ticket, boolean authenticated) {
    }

    private RemotelyServerApi api(FlowManager manager) {
        return manager == null ? null : manager.getApiClient();
    }

    private Async<ReSyncProvisioningService.StartupProbeResult> unavailable(String reason,
                                                                              RemotelyServerApi.ReSyncReadinessReason reasonCode,
                                                                              ReSyncProvisioningService.StartupStatus status,
                                                                              LifecycleFence fence) {
        return Async.completed(unavailableResult(reason, reasonCode, status, fence));
    }

    private ReSyncProvisioningService.StartupProbeResult unavailableResult(String reason,
                                                                             RemotelyServerApi.ReSyncReadinessReason reasonCode,
                                                                             ReSyncProvisioningService.StartupStatus status,
                                                                             LifecycleFence fence) {
        if (!isCurrent(fence)) {
            return staleValue();
        }
        ApplicationHost current = ApplicationHostRegistry.current();
        if (current instanceof BrowserApplicationHost host) {
            host.reportReSyncReadiness(reason);
        }
        return new ReSyncProvisioningService.StartupProbeResult(status, false, false, reasonCode, reason);
    }

    private void setRelayReadiness(LifecycleFence fence, String serverId,
                                   RemotelyServerApi.ReSyncReadinessReason reasonCode) {
        if (!isCurrent(fence)) return;
        ApplicationHost current = ApplicationHostRegistry.current();
        if (current instanceof BrowserApplicationHost host) {
            host.setReSyncRelayReadiness(serverId, reasonCode);
        }
    }

    private Async<ReSyncProvisioningService.StartupProbeResult> awaitConnection(FlowManager manager,
                                                                                  String serverId,
                                                                                  LifecycleFence fence) {
        return manager.awaitFlowClientConnected(serverId, true).thenApply(readiness -> {
            if (!isCurrent(fence)) {
                return staleValue();
            }
            ReSyncFlowClient resolvedClient = manager.existingFlowClient(serverId);
            if (readiness == ReSyncFlowClient.ReadinessState.READY && manager.isFlowClientReady(serverId)) {
                setRelayReadiness(fence, serverId, RemotelyServerApi.ReSyncReadinessReason.NONE);
                return new ReSyncProvisioningService.StartupProbeResult(
                    ReSyncProvisioningService.StartupStatus.READY, false, false,
                    RemotelyServerApi.ReSyncReadinessReason.NONE, "");
            }
            if (readiness == ReSyncFlowClient.ReadinessState.INCOMPATIBLE) {
                String message = resolvedClient == null ? "ReSync Flow Contract Mismatch. Update ReSync And Remotely"
                    : resolvedClient.readinessFailureMessage();
                if (message == null || message.isBlank()) {
                    message = "ReSync Flow Contract Mismatch. Update ReSync And Remotely";
                }
                setRelayReadiness(fence, serverId, RemotelyServerApi.ReSyncReadinessReason.PROTOCOL_INCOMPATIBLE);
                return unavailableResult(message, RemotelyServerApi.ReSyncReadinessReason.PROTOCOL_INCOMPATIBLE,
                    ReSyncProvisioningService.StartupStatus.SECURE_CONNECTION_REPAIR, fence);
            }
            if (readiness == ReSyncFlowClient.ReadinessState.WAITING_FOR_REGISTRY
                    || readiness == ReSyncFlowClient.ReadinessState.CONNECTING) {
                return new ReSyncProvisioningService.StartupProbeResult(
                    ReSyncProvisioningService.StartupStatus.LOADING, false, false,
                    RemotelyServerApi.ReSyncReadinessReason.NONE, "");
            }
            ReSyncFlowClient failureClient = resolvedClient;
            if (failureClient == null) {
                return unavailableResult("ReSync Endpoint Unreachable. Check That The Server And ReSync Are Running",
                    RemotelyServerApi.ReSyncReadinessReason.UPSTREAM_UNREACHABLE,
                    ReSyncProvisioningService.StartupStatus.LOADING, fence);
            }
            RemotelyServerApi.ReSyncReadinessReason reasonCode = switch (failureClient.connectionFailure()) {
                case PROTOCOL_MISMATCH, RUNTIME_VERSION_MISMATCH, HANDSHAKE_REJECTED ->
                    RemotelyServerApi.ReSyncReadinessReason.PROTOCOL_INCOMPATIBLE;
                case ACCESS_DENIED -> RemotelyServerApi.ReSyncReadinessReason.CREDENTIAL_UNAVAILABLE;
                case ENDPOINT_UNREACHABLE, NONE -> RemotelyServerApi.ReSyncReadinessReason.UPSTREAM_UNREACHABLE;
            };
            if (failureClient.connectionFailure() == ReSyncFlowClient.ConnectionFailure.NONE) {
                return new ReSyncProvisioningService.StartupProbeResult(
                    ReSyncProvisioningService.StartupStatus.LOADING, false, false,
                    RemotelyServerApi.ReSyncReadinessReason.NONE, "");
            }
            String reason = readinessMessage(reasonCode);
            setRelayReadiness(fence, serverId, reasonCode);
            return unavailableResult(reason, reasonCode, statusFor(reasonCode), fence);
        });
    }

    private boolean stopped(String state) {
        String value = state == null ? "" : state.trim();
        return value.equalsIgnoreCase("STOPPED") || value.equalsIgnoreCase("OFFLINE")
            || value.equalsIgnoreCase("SHUTDOWN") || value.equalsIgnoreCase("SAVED");
    }

    private boolean hasReSyncConfig(ServerModels.ReSyncConfig config) {
        return config != null && config.port > 0;
    }

    private CapabilityBlock authoritativeCapabilityBlock(RemotelyServerApi.ServerCapabilities capabilities) {
        RemotelyServerApi.ReSyncAvailability reSync = capabilities == null ? null : capabilities.reSync();
        if (reSync == null) {
            return null;
        }
        RemotelyServerApi.ReSyncReadinessReason reasonCode = authoritativeReason(reSync.reasonCode())
            ? reSync.reasonCode() : null;
        RemotelyServerApi.ReSyncEndpointReadiness endpoint = reSync.endpoint();
        if (reasonCode == null && endpoint != null && authoritativeReason(endpoint.reasonCode())) {
            reasonCode = endpoint.reasonCode();
        }
        if (reasonCode == null) {
            return null;
        }
        String reason = reSync.reason();
        if (endpoint != null && endpoint.reasonCode() == reasonCode && !endpoint.reason().isBlank()) {
            reason = endpoint.reason();
        }
        if (reason == null || reason.isBlank()) {
            reason = readinessMessage(reasonCode);
        }
        return new CapabilityBlock(reasonCode, reason, statusFor(reasonCode));
    }

    private boolean authoritativeReason(RemotelyServerApi.ReSyncReadinessReason reasonCode) {
        return switch (reasonCode == null ? RemotelyServerApi.ReSyncReadinessReason.UNKNOWN : reasonCode) {
            case NOT_PROVISIONED, PROVISIONING_INCOMPLETE, CREDENTIAL_UNAVAILABLE, RUNTIME_VERSION_UNAVAILABLE,
                 INVALID_ENDPOINT, MISSING_TLS_PIN, PLAINTEXT_PUBLIC_ENDPOINT, RUNTIME_METADATA_STALE,
                 PROTOCOL_INCOMPATIBLE -> true;
            default -> false;
        };
    }

    private ReSyncProvisioningService.StartupStatus statusFor(RemotelyServerApi.ReSyncReadinessReason reasonCode) {
        return switch (reasonCode == null ? RemotelyServerApi.ReSyncReadinessReason.UNKNOWN : reasonCode) {
            case NOT_PROVISIONED, PROVISIONING_INCOMPLETE, CREDENTIAL_UNAVAILABLE, RUNTIME_VERSION_UNAVAILABLE ->
                ReSyncProvisioningService.StartupStatus.SETUP;
            case INVALID_ENDPOINT, MISSING_TLS_PIN, PLAINTEXT_PUBLIC_ENDPOINT, RUNTIME_METADATA_STALE,
                 PROTOCOL_INCOMPATIBLE -> ReSyncProvisioningService.StartupStatus.SECURE_CONNECTION_REPAIR;
            default -> ReSyncProvisioningService.StartupStatus.LOADING;
        };
    }

    private String readinessMessage(RemotelyServerApi.ReSyncReadinessReason reasonCode) {
        return switch (reasonCode == null ? RemotelyServerApi.ReSyncReadinessReason.UNKNOWN : reasonCode) {
            case MISSING_TLS_PIN, PLAINTEXT_PUBLIC_ENDPOINT, RUNTIME_METADATA_STALE -> "ReSync TLS Setup Required";
            case PROTOCOL_INCOMPATIBLE -> "ReSync Runtime Must Be Updated";
            case UPSTREAM_UNREACHABLE -> "ReSync Endpoint Unreachable. Check That The Server And ReSync Are Running";
            case INVALID_ENDPOINT -> "ReSync Endpoint Is Invalid";
            case NOT_PROVISIONED, PROVISIONING_INCOMPLETE, CREDENTIAL_UNAVAILABLE, RUNTIME_VERSION_UNAVAILABLE ->
                "ReSync Setup Required";
            default -> "";
        };
    }

    private Async<String> latestVersion() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BrowserLaunchSession.apiBaseUrl() + LATEST_RELEASE_PATH))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        return transport.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) return "";
            return BrowserJson.string(BrowserJson.object(response.body()), "version").trim();
        }).exceptionally(ignored -> "");
    }

    private boolean newer(String latest, String installed) {
        if (latest == null || latest.isBlank() || installed == null || installed.isBlank()) return false;
        if ("latest".equalsIgnoreCase(latest.trim())) return !"latest".equalsIgnoreCase(installed.trim());
        if ("latest".equalsIgnoreCase(installed.trim())) return false;
        String[] left = releaseVersion(latest).replaceFirst("^[vV]", "").split("\\.");
        String[] right = releaseVersion(installed).replaceFirst("^[vV]", "").split("\\.");
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int leftPart = index < left.length ? number(left[index]) : 0;
            int rightPart = index < right.length ? number(right[index]) : 0;
            if (leftPart != rightPart) return leftPart > rightPart;
        }
        return false;
    }

    private String releaseVersion(String version) {
        String value = version == null ? "" : version.trim();
        int dash = value.indexOf('-');
        int plus = value.indexOf('+');
        int separator = dash < 0 ? plus : plus < 0 ? dash : Math.min(dash, plus);
        return separator < 0 ? value : value.substring(0, separator);
    }

    private int number(String value) {
        int result = 0;
        boolean found = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') break;
            found = true;
            result = Math.min(1_000_000, result * 10 + character - '0');
        }
        return found ? result : 0;
    }

    private String errorMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) current = current.getCause();
        String message = current == null ? null : current.getMessage();
        return message == null || message.isBlank() ? "ReSync Provisioning Failed" : message;
    }

    private record CapabilityBlock(RemotelyServerApi.ReSyncReadinessReason reasonCode, String reason,
                                   ReSyncProvisioningService.StartupStatus status) {
    }
}
