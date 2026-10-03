package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.util.DesktopAsyncTools;
import restudio.rebase.backend.feature.PortManagementFeature;
import restudio.rebase.backend.feature.SubuserFeature;
import restudio.rebase.backend.impl.ReStudioBackend;
import restudio.rebase.instance.Instance;
import restudio.rescreen.platform.Async;
import restudio.rebase.restudio.api.models.ServerModels;

import java.util.List;

public final class DesktopHostedSettingsProviders {
    private DesktopHostedSettingsProviders() { }

    public static PortManagementSettingsProvider ports(Object target) {
        if (!(target instanceof Instance instance) || instance.getBackend() == null) return PortManagementSettingsProvider.unavailable("Network Feature Is Unavailable");
        PortManagementFeature feature = instance.getBackend().getFeature(PortManagementFeature.class).orElse(null);
        if (feature == null) return PortManagementSettingsProvider.unavailable("Network Feature Is Unavailable");
        return new PortManagementSettingsProvider() {
            public Async<List<ServerModels.Allocation>> getAllocations() { return DesktopAsyncTools.adapt(feature.getAllocations()); }
            public Async<ServerModels.Allocation> createAllocation() { return DesktopAsyncTools.adapt(feature.createAllocation()); }
            public Async<ServerModels.Allocation> updateAllocation(Integer allocationId, String notes, boolean primary) { return DesktopAsyncTools.adapt(feature.updateAllocation(allocationId, notes, primary)); }
            public Async<Void> deleteAllocation(Integer allocationId) { return DesktopAsyncTools.adapt(feature.deleteAllocation(allocationId)); }
        };
    }

    public static SubuserSettingsProvider subusers(Object target) {
        if (!(target instanceof Instance instance) || instance.getBackend() == null) return SubuserSettingsProvider.unavailable("Subuser Feature Is Unavailable");
        SubuserFeature feature = instance.getBackend().getFeature(SubuserFeature.class).orElse(null);
        if (feature == null) return SubuserSettingsProvider.unavailable("Subuser Feature Is Unavailable");
        return new SubuserSettingsProvider() {
            public String collaborationResourceId() { return instance.getBackend() instanceof ReStudioBackend backend ? backend.getServerId() : ""; }
            public Async<List<ServerModels.Subuser>> getSubusers() { return DesktopAsyncTools.adapt(feature.getSubusers()); }
            public Async<ServerModels.SystemPermissions> getSystemPermissions() { return DesktopAsyncTools.adapt(feature.getSystemPermissions()); }
            public Async<ServerModels.Subuser> createSubuser(String email, List<String> permissions) { return DesktopAsyncTools.adapt(feature.createSubuser(email, permissions)); }
            public Async<ServerModels.Subuser> updateSubuser(String subuserUuid, List<String> permissions) { return DesktopAsyncTools.adapt(feature.updateSubuser(subuserUuid, permissions)); }
            public Async<Void> deleteSubuser(String subuserUuid) { return DesktopAsyncTools.adapt(feature.deleteSubuser(subuserUuid)); }
            public Async<List<ServerModels.ReStudioUserInfo>> searchUsers(String query) { return DesktopAsyncTools.adapt(feature.searchUsers(query)); }
        };
    }
}
