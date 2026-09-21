package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.host.ApplicationHostRegistry;
import restudio.rescreen.platform.Async;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.resync.contract.install.ReSyncInstallationStatus;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class ReSyncProvisioningService {
    public static final int RESYNC_PORT = 12441;

    public enum StartupStatus {
        LOADING,
        NOT_SUPPORTED,
        SETUP,
        SECURE_CONNECTION_REPAIR,
        SERVER_STOPPED,
        MIGRATION_REQUIRED,
        READY
    }

    public record StartupProbeResult(StartupStatus status, boolean updateAvailable, boolean updateChecked,
                                    RemotelyServerApi.ReSyncReadinessReason readinessReason, String readinessMessage) {
        public StartupProbeResult {
            status = status == null ? StartupStatus.LOADING : status;
            readinessReason = readinessReason == null ? RemotelyServerApi.ReSyncReadinessReason.UNKNOWN : readinessReason;
            readinessMessage = readinessMessage == null ? "" : readinessMessage.trim();
        }

        public StartupProbeResult(StartupStatus status, boolean updateAvailable, boolean updateChecked) {
            this(status, updateAvailable, updateChecked, RemotelyServerApi.ReSyncReadinessReason.UNKNOWN, "");
        }

        public StartupProbeResult(StartupStatus status, boolean updateAvailable) {
            this(status, updateAvailable, true);
        }
    }

    public record OperationResult(boolean success, String failureMessage) {
        public static OperationResult successful() {
            return new OperationResult(true, "");
        }

        public static OperationResult failed() {
            return new OperationResult(false, "");
        }

        public static OperationResult failed(String message) {
            return new OperationResult(false, message == null ? "" : message);
        }
    }

    public interface Adapter {
        Async<StartupProbeResult> computeStartupState(String serverId, ServerModels.ClientServerView startupServer, String loaderHint);

        Async<Boolean> isUpdateAvailable(String serverId, ServerModels.ClientServerView startupServer);

        Async<OperationResult> setup(String serverId, ServerModels.ClientServerView startupServer);

        Async<OperationResult> update(String serverId, ServerModels.ClientServerView startupServer);

        default boolean isInstalled(Object instance) {
            return false;
        }

        default OperationResult installLatest(Object instance) {
            return OperationResult.failed("ReSync Provisioning Is Unavailable");
        }

        default String instanceId(Object instance) {
            return instance == null ? "" : String.valueOf(instance);
        }

        default String instanceName(Object instance) {
            return instance == null ? "Server" : String.valueOf(instance);
        }

        default String installedMessage(String serverId, ServerModels.ClientServerView startupServer) {
            return "ReSync Installed!\nRestart Your Server To Activate";
        }

        default String updatedMessage(String serverId, ServerModels.ClientServerView startupServer) {
            return "Updated! Restart Server To Activate";
        }

        default void clearReleaseCache() {
        }

        default Async<Optional<ReSyncInstallationStatus>> installationStatus(String serverId,
                                                                               ServerModels.ClientServerView startupServer) {
            return Async.completed(Optional.empty());
        }

        default Async<OperationResult> archiveLegacyData(String serverId, ServerModels.ClientServerView startupServer,
                                                         ReSyncInstallationStatus status) {
            return Async.completed(OperationResult.failed("Legacy Data Archiving Is Unavailable"));
        }
    }

    public Async<StartupProbeResult> computeStartupState(String serverId, ServerModels.ClientServerView startupServer, String loaderHint) {
        Adapter adapter = adapter();
        if (adapter != null) {
            return adapter.computeStartupState(serverId, startupServer, loaderHint);
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return Async.completed(new StartupProbeResult(StartupStatus.NOT_SUPPORTED, false, false));
        }
        if (manager.isFlowClientReady(serverId)) {
            return Async.completed(new StartupProbeResult(StartupStatus.READY, false, false));
        }
        if (!pluginCompatible(startupServer, loaderHint)) {
            return Async.completed(new StartupProbeResult(StartupStatus.NOT_SUPPORTED, false, false));
        }
        RemotelyServerApi api = manager.getApiClient();
        if (api == null || serverId == null || serverId.isBlank()) {
            return Async.completed(new StartupProbeResult(StartupStatus.SETUP, false, false));
        }
        return api.getReSyncVersion(serverId)
            .thenCompose(version -> {
                if (version == null || version.isBlank()) {
                    return Async.completed(new StartupProbeResult(StartupStatus.SETUP, false, false));
                }
                return api.getServerStatus(serverId).handle((status, failure) -> {
                    if (failure != null || status == null) {
                        return new StartupProbeResult(StartupStatus.READY, false, false);
                    }
                    if (status.installing || isStopped(status.currentState)) {
                        return new StartupProbeResult(StartupStatus.SERVER_STOPPED, false, false);
                    }
                    return new StartupProbeResult(StartupStatus.READY, false, false);
                });
            })
            .exceptionally(ignored -> new StartupProbeResult(StartupStatus.SETUP, false, false));
    }

    public Async<Boolean> isReSyncUpdateAvailable(String serverId, ServerModels.ClientServerView startupServer) {
        Adapter adapter = adapter();
        return adapter != null ? adapter.isUpdateAvailable(serverId, startupServer) : Async.completed(false);
    }

    public Async<OperationResult> setup(String serverId, ServerModels.ClientServerView startupServer) {
        Adapter adapter = adapter();
        if (adapter != null) {
            return adapter.setup(serverId, startupServer);
        }
        return provision(serverId, false);
    }

    public Async<OperationResult> update(String serverId, ServerModels.ClientServerView startupServer) {
        Adapter adapter = adapter();
        if (adapter != null) {
            return adapter.update(serverId, startupServer);
        }
        return provision(serverId, true);
    }

    public boolean isInstalled(Object instance) {
        Adapter adapter = adapter();
        return adapter != null && adapter.isInstalled(instance);
    }

    public OperationResult installLatest(Object instance) {
        Adapter adapter = adapter();
        return adapter == null ? OperationResult.failed("ReSync Provisioning Is Unavailable") : adapter.installLatest(instance);
    }

    public String instanceId(Object instance) {
        Adapter adapter = adapter();
        return adapter == null ? instance == null ? "" : String.valueOf(instance) : adapter.instanceId(instance);
    }

    public String instanceName(Object instance) {
        Adapter adapter = adapter();
        return adapter == null ? instance == null ? "Server" : String.valueOf(instance) : adapter.instanceName(instance);
    }

    public String installedMessage(String serverId, ServerModels.ClientServerView startupServer) {
        Adapter adapter = adapter();
        return adapter == null ? "ReSync Installed!\nRestart Your Server To Activate" : adapter.installedMessage(serverId, startupServer);
    }

    public String updatedMessage(String serverId, ServerModels.ClientServerView startupServer) {
        Adapter adapter = adapter();
        return adapter == null ? "Updated! Restart Server To Activate" : adapter.updatedMessage(serverId, startupServer);
    }

    public void clearReleaseCache() {
        Adapter adapter = adapter();
        if (adapter != null) {
            adapter.clearReleaseCache();
        }
    }

    public Async<Optional<ReSyncInstallationStatus>> installationStatus(String serverId,
                                                                         ServerModels.ClientServerView startupServer) {
        Adapter adapter = adapter();
        if (adapter != null) {
            return adapter.installationStatus(serverId, startupServer);
        }
        FlowManager manager = FlowManager.getInstance();
        RemotelyServerApi api = manager == null ? null : manager.getApiClient();
        if (api == null || serverId == null || serverId.isBlank()) {
            return Async.completed(Optional.empty());
        }
        return api.getFileContent(serverId, ReSyncInstallationStatus.FILE_PATH)
            .thenApply(content -> Optional.of(ReSyncInstallationStatus.decode(content)))
            .exceptionally(ignored -> Optional.empty());
    }

    public Async<OperationResult> archiveLegacyData(String serverId, ServerModels.ClientServerView startupServer,
                                                    ReSyncInstallationStatus status) {
        if (status == null || !status.blocksStartup()
            || !ReSyncInstallationStatus.ARCHIVE_MARKER_PATH.equals(status.markerPath())) {
            return Async.completed(OperationResult.failed("ReSync Is Not Waiting For Legacy Data Archiving"));
        }
        Adapter adapter = adapter();
        if (adapter != null) {
            return adapter.archiveLegacyData(serverId, startupServer, status);
        }
        FlowManager manager = FlowManager.getInstance();
        RemotelyServerApi api = manager == null ? null : manager.getApiClient();
        if (api == null || serverId == null || serverId.isBlank()) {
            return Async.completed(OperationResult.failed("Server Files Are Unavailable"));
        }
        return api.writeFile(serverId, status.markerPath(), UUID.randomUUID() + "\n")
            .thenApply(ignored -> OperationResult.successful())
            .exceptionally(error -> OperationResult.failed(errorMessage(error)));
    }

    private Async<OperationResult> provision(String serverId, boolean update) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null || serverId.isBlank()) {
            return Async.completed(OperationResult.failed("Server Not Found"));
        }
        RemotelyServerApi api = manager.getApiClient();
        if (api == null) {
            return Async.completed(OperationResult.failed("ReSync Provisioning Is Unavailable"));
        }
        Async<ServerModels.ReSyncProvisionResult> request = update ? api.updateReSync(serverId) : api.provisionReSync(serverId);
        return request.thenApply(result -> {
            if (result == null) {
                return OperationResult.failed("ReSync Provisioning Failed");
            }
            return result.success ? OperationResult.successful() : OperationResult.failed(result.message);
        }).exceptionally(error -> OperationResult.failed(errorMessage(error)));
    }

    private Adapter adapter() {
        ApplicationHost host = ApplicationHostRegistry.current();
        if (host == null) return null;
        Object adapter = host.provisioningAdapter();
        return adapter instanceof Adapter value ? value : null;
    }

    private boolean pluginCompatible(ServerModels.ClientServerView server, String loaderHint) {
        String loader = server != null && server.loader != null && !server.loader.isBlank() ? server.loader : loaderHint;
        if (loader == null || loader.isBlank()) {
            return true;
        }
        return switch (loader.trim().toUpperCase(Locale.ROOT)) {
            case "PAPER", "FOLIA", "SPIGOT", "BUKKIT", "PURPUR", "LEAF", "VELOCITY", "WATERFALL", "BUNGEECORD" -> true;
            default -> false;
        };
    }

    private boolean isStopped(String currentState) {
        String state = currentState == null ? "" : currentState.trim().toUpperCase(Locale.ROOT);
        return state.equals("STOPPED") || state.equals("OFFLINE") || state.equals("SHUTDOWN") || state.equals("SAVED");
    }

    private String errorMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current == null ? "" : current.getMessage();
        return message == null || message.isBlank() ? "ReSync Provisioning Failed" : message;
    }
}
