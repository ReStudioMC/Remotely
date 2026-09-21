package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.data.flow.FlowManager;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceState;
import restudio.rescreen.platform.Async;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.resync.contract.install.ReSyncInstallationStatus;

import java.util.Optional;

public final class DesktopReSyncProvisioningAdapter implements ReSyncProvisioningService.Adapter {
    private final DesktopReSyncProvisioningService delegate = new DesktopReSyncProvisioningService();

    @Override
    public Async<ReSyncProvisioningService.StartupProbeResult> computeStartupState(String serverId, ServerModels.ClientServerView startupServer, String loaderHint) {
        try {
            DesktopReSyncProvisioningService.StartupProbeResult result = delegate.computeStartupState(serverId, startupServer, loaderHint);
            return Async.completed(new ReSyncProvisioningService.StartupProbeResult(
                ReSyncProvisioningService.StartupStatus.valueOf(result.status().name()), result.updateAvailable(), result.updateChecked()));
        } catch (Throwable error) {
            return Async.failed(error);
        }
    }

    @Override
    public Async<Boolean> isUpdateAvailable(String serverId, ServerModels.ClientServerView startupServer) {
        try {
            return Async.completed(delegate.isReSyncUpdateAvailable(serverId, startupServer));
        } catch (Throwable error) {
            return Async.failed(error);
        }
    }

    @Override
    public Async<ReSyncProvisioningService.OperationResult> setup(String serverId, ServerModels.ClientServerView startupServer) {
        return execute(() -> delegate.setup(serverId, startupServer));
    }

    @Override
    public Async<ReSyncProvisioningService.OperationResult> update(String serverId, ServerModels.ClientServerView startupServer) {
        return execute(() -> delegate.update(serverId, startupServer));
    }

    @Override
    public boolean isInstalled(Object instance) {
        return instance instanceof Instance value && delegate.isInstalled(value);
    }

    @Override
    public ReSyncProvisioningService.OperationResult installLatest(Object instance) {
        if (!(instance instanceof Instance value)) {
            return ReSyncProvisioningService.OperationResult.failed("Server Not Found");
        }
        DesktopReSyncProvisioningService.OperationResult result = delegate.installLatest(value);
        return new ReSyncProvisioningService.OperationResult(result.success(), result.failureMessage());
    }

    @Override
    public String instanceId(Object instance) {
        return instance instanceof Instance value ? value.getInstanceId() : ReSyncProvisioningService.Adapter.super.instanceId(instance);
    }

    @Override
    public String instanceName(Object instance) {
        return instance instanceof Instance value ? value.getName() : ReSyncProvisioningService.Adapter.super.instanceName(instance);
    }

    @Override
    public String installedMessage(String serverId, ServerModels.ClientServerView startupServer) {
        Instance instance = findInstance(serverId, startupServer);
        boolean running = instance != null && instance.getState() == InstanceState.RUNNING;
        boolean ssh = instance != null && instance.getBackendConfig() != null && "SSH".equalsIgnoreCase(instance.getBackendConfig().type);
        StringBuilder message = new StringBuilder("ReSync Installed!");
        message.append(running ? "\nRestart Your Server To Activate" : "\nStart Your Server To Activate");
        if (ssh) {
            message.append("\nOpen Port ").append(ReSyncProvisioningService.RESYNC_PORT).append(" On Your Host");
        }
        return message.toString();
    }

    @Override
    public String updatedMessage(String serverId, ServerModels.ClientServerView startupServer) {
        Instance instance = findInstance(serverId, startupServer);
        return instance != null && instance.getState() == InstanceState.RUNNING
            ? "Updated! Restart Server To Activate" : "Updated! Start Server To Activate";
    }

    @Override
    public void clearReleaseCache() {
        delegate.clearReleaseCache();
    }

    @Override
    public Async<Optional<ReSyncInstallationStatus>> installationStatus(String serverId,
                                                                        ServerModels.ClientServerView startupServer) {
        try {
            return Async.completed(delegate.installationStatus(serverId, startupServer));
        } catch (Throwable error) {
            return Async.failed(error);
        }
    }

    @Override
    public Async<ReSyncProvisioningService.OperationResult> archiveLegacyData(String serverId,
                                                                              ServerModels.ClientServerView startupServer,
                                                                              ReSyncInstallationStatus status) {
        return execute(() -> delegate.archiveLegacyData(serverId, startupServer, status));
    }

    private Instance findInstance(String serverId, ServerModels.ClientServerView startupServer) {
        FlowManager manager = FlowManager.getInstance();
        return manager == null ? null : (Instance) manager.findInstanceByServerId(serverId, startupServer);
    }

    private Async<ReSyncProvisioningService.OperationResult> execute(Operation operation) {
        try {
            DesktopReSyncProvisioningService.OperationResult result = operation.run();
            return Async.completed(new ReSyncProvisioningService.OperationResult(result.success(), result.failureMessage()));
        } catch (Throwable error) {
            return Async.failed(error);
        }
    }

    @FunctionalInterface
    private interface Operation {
        DesktopReSyncProvisioningService.OperationResult run() throws Exception;
    }
}
