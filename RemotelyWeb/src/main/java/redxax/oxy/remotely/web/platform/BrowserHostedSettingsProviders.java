package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.ui.settings.controllers.PortManagementSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.SubuserSettingsProvider;
import restudio.rebase.backend.feature.AsyncBackupFeature;
import restudio.rebase.backend.feature.AsyncServerScheduleFeature;
import restudio.rebase.backend.feature.BackupOperations;
import restudio.rebase.schedule.ServerScheduleModels;
import restudio.rescreen.platform.Async;
import restudio.rebase.restudio.api.models.ServerModels;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class BrowserHostedSettingsProviders {
    private BrowserHostedSettingsProviders() { }

    public static AsyncBackupFeature backups(BrowserRemotelyServerApi api, String serverId, RemotelyConfigStore config) {
        Objects.requireNonNull(api, "api");
        String id = requireServerId(serverId);
        return new AsyncBackupFeature() {
            public String ownerId() { return id; }
            public String operationOwnerId() { return BrowserLaunchSession.metadata().subjectId() + ":" + id; }
            public boolean durableOperations() { return true; }
            public BackupOperations.CreateRequest pendingCreate() {
                String key = createKey(id);
                if (config == null || key.isEmpty()) return null;
                String stored = config.get(key, "");
                if (stored.isBlank()) return null;
                BackupOperations.CreateRequest request = BackupOperations.decodeCreate(stored, id);
                if (request != null) return request;
                if (validRequestId(stored.split("\\|", -1)[0])) {
                    throw new IllegalStateException("Saved Backup Creation Requires Manual Recovery");
                }
                config.remove(key);
                config.save();
                return null;
            }
            public void saveCreate(BackupOperations.CreateRequest request) {
                String key = createKey(id);
                if (config == null || key.isEmpty()) throw new IllegalStateException("Backup Creation Identity Storage Is Unavailable");
                config.set(key, BackupOperations.encode(request));
                config.save();
                BackupOperations.CreateRequest saved = BackupOperations.decodeCreate(config.get(key, ""), id);
                if (!request.equals(saved)) throw new IllegalStateException("Backup Creation Identity Could Not Be Saved");
            }
            public void clearCreate(BackupOperations.CreateRequest request) {
                String key = createKey(id);
                if (config == null || key.isEmpty()) return;
                BackupOperations.CreateRequest saved = BackupOperations.decodeCreate(config.get(key, ""), id);
                if (saved == null || !request.requestId().equals(saved.requestId())) return;
                config.remove(key);
                config.save();
            }
            public BackupOperations.RestoreRequest pendingRestore() {
                String key = restoreKey(id);
                if (config == null || key.isEmpty()) return null;
                String stored = config.get(key, "");
                if (stored.isBlank()) return null;
                BackupOperations.RestoreRequest request = BackupOperations.decode(stored, id);
                if (request != null) return request;
                String[] fields = stored.split("\\|", -1);
                if (fields.length > 0 && validRequestId(fields[0])) {
                    throw new IllegalStateException("Saved Restore Requires Manual Recovery");
                }
                config.remove(key);
                config.save();
                return null;
            }
            public void saveRestore(BackupOperations.RestoreRequest request) {
                String key = restoreKey(id);
                if (config == null || key.isEmpty()) throw new IllegalStateException("Restore Identity Storage Is Unavailable");
                config.set(key, BackupOperations.encode(request));
                config.save();
                BackupOperations.RestoreRequest saved = BackupOperations.decode(config.get(key, ""), id);
                if (!request.equals(saved)) {
                    throw new IllegalStateException("Restore Identity Could Not Be Saved");
                }
            }
            public void clearRestore(BackupOperations.RestoreRequest request) {
                String key = restoreKey(id);
                if (config == null || key.isEmpty()) return;
                BackupOperations.RestoreRequest saved = BackupOperations.decode(config.get(key, ""), id);
                if (saved == null || !request.requestId().equals(saved.requestId())) return;
                config.remove(key);
                config.save();
            }
            public Async<List<ServerModels.Backup>> getBackups() { return api.getBackups(id); }
            public Async<ServerModels.Backup> createBackup(String name, List<String> ignoredFiles, boolean locked) { return api.createBackup(id, name, ignoredFiles, locked); }
            public Async<BackupOperations.CreateResult> createBackup(BackupOperations.CreateRequest request) { return api.createBackup(request); }
            public Async<BackupOperations.CreateResult> observeCreate(BackupOperations.CreateRequest request) { return api.observeCreate(request); }
            public Async<Void> deleteBackup(String backupUuid) { return api.deleteBackup(id, backupUuid); }
            public Async<String> getDownloadUrl(String backupUuid) { return api.getBackupDownloadUrl(id, backupUuid); }
            public Async<Void> restoreBackup(String backupUuid, boolean truncate) { return api.restoreBackup(id, backupUuid, truncate); }
            public Async<BackupOperations.RestoreResult> restoreBackup(BackupOperations.RestoreRequest request) { return api.restoreBackup(request); }
            public Async<BackupOperations.RestoreResult> observeRestore(BackupOperations.RestoreRequest request) { return api.observeRestore(request); }
            public Async<ServerModels.BackupRestoreDiscovery> discoverRestore() { return api.discoverRestore(id); }
            public Async<ServerModels.Backup> toggleBackupLock(String backupUuid) { return api.toggleBackupLock(id, backupUuid); }
        };
    }

    public static PortManagementSettingsProvider ports(BrowserRemotelyServerApi api, String serverId) {
        Objects.requireNonNull(api, "api");
        String id = requireServerId(serverId);
        return new PortManagementSettingsProvider() {
            public Async<List<ServerModels.Allocation>> getAllocations() { return api.getAllocations(id); }
            public Async<ServerModels.Allocation> createAllocation() { return api.createAllocation(id); }
            public Async<ServerModels.Allocation> updateAllocation(Integer allocationId, String notes, boolean primary) { return api.updateAllocation(id, allocationId, notes, primary); }
            public Async<Void> deleteAllocation(Integer allocationId) { return api.deleteAllocation(id, allocationId); }
        };
    }

    public static AsyncServerScheduleFeature schedules(BrowserRemotelyServerApi api, String serverId) {
        Objects.requireNonNull(api, "api");
        String id = requireServerId(serverId);
        return new AsyncServerScheduleFeature() {
            public ServerScheduleModels.Capabilities scheduleCapabilities() { return api.scheduleCapabilities(id); }
            public Async<List<ServerScheduleModels.Schedule>> listSchedules() { return api.listSchedules(id); }
            public Async<ServerScheduleModels.Schedule> createSchedule(ServerScheduleModels.Mutation mutation, String key) {
                return api.createSchedule(id, mutation, key);
            }
            public Async<ServerScheduleModels.Schedule> updateSchedule(String scheduleId, ServerScheduleModels.Mutation mutation,
                                                                        String revision, String key) {
                return api.updateSchedule(id, scheduleId, mutation, revision, key);
            }
            public Async<Void> deleteSchedule(String scheduleId, String revision, String key) {
                return api.deleteSchedule(id, scheduleId, revision, key);
            }
            public Async<ServerScheduleModels.Run> runSchedule(String scheduleId, String key) {
                return api.runSchedule(id, scheduleId, key);
            }
        };
    }

    public static SubuserSettingsProvider subusers(BrowserRemotelyServerApi api, String serverId) {
        Objects.requireNonNull(api, "api");
        String id = requireServerId(serverId);
        return new SubuserSettingsProvider() {
            public String collaborationResourceId() { return id; }
            public Async<List<ServerModels.Subuser>> getSubusers() { return api.getSubusers(id); }
            public Async<ServerModels.SystemPermissions> getSystemPermissions() { return api.getSystemPermissions(); }
            public Async<ServerModels.Subuser> createSubuser(String email, List<String> permissions) { return api.createSubuser(id, email, permissions); }
            public Async<ServerModels.Subuser> updateSubuser(String subuserUuid, List<String> permissions) { return api.updateSubuser(id, subuserUuid, permissions); }
            public Async<Void> deleteSubuser(String subuserUuid) { return api.deleteSubuser(id, subuserUuid); }
            public Async<List<ServerModels.ReStudioUserInfo>> searchUsers(String query) { return api.searchUsers(query); }
        };
    }

    private static String requireServerId(String serverId) {
        if (serverId == null || serverId.isBlank()) throw new IllegalArgumentException("Server Identifier Is Required");
        return serverId;
    }

    private static String restoreKey(String serverId) {
        return BackupOperations.storageKey(BrowserLaunchSession.metadata().subjectId(), serverId);
    }

    private static String createKey(String serverId) {
        return BackupOperations.createStorageKey(BrowserLaunchSession.metadata().subjectId(), serverId);
    }

    private static boolean validRequestId(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
