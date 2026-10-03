package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rescreen.platform.Async;
import restudio.rebase.restudio.api.models.ServerModels;

import java.util.List;

public interface SubuserSettingsProvider {
    Async<List<ServerModels.Subuser>> getSubusers();
    Async<ServerModels.SystemPermissions> getSystemPermissions();
    Async<ServerModels.Subuser> createSubuser(String email, List<String> permissions);
    Async<ServerModels.Subuser> updateSubuser(String subuserUuid, List<String> permissions);
    Async<Void> deleteSubuser(String subuserUuid);
    Async<List<ServerModels.ReStudioUserInfo>> searchUsers(String query);
    default String collaborationResourceId() { return ""; }
    default boolean available() { return true; }
    default String unavailableReason() { return "Subuser Feature Is Unavailable"; }

    static SubuserSettingsProvider unavailable(String reason) {
        return new SubuserSettingsProvider() {
            private final String message = reason == null || reason.isBlank() ? "Subuser Feature Is Unavailable" : reason;
            public Async<List<ServerModels.Subuser>> getSubusers() { return failed(); }
            public Async<ServerModels.SystemPermissions> getSystemPermissions() { return failed(); }
            public Async<ServerModels.Subuser> createSubuser(String email, List<String> permissions) { return failed(); }
            public Async<ServerModels.Subuser> updateSubuser(String subuserUuid, List<String> permissions) { return failed(); }
            public Async<Void> deleteSubuser(String subuserUuid) { return failed(); }
            public Async<List<ServerModels.ReStudioUserInfo>> searchUsers(String query) { return failed(); }
            public boolean available() { return false; }
            public String unavailableReason() { return message; }
            private <T> Async<T> failed() { return Async.failed(new UnsupportedOperationException(message)); }
        };
    }
}
