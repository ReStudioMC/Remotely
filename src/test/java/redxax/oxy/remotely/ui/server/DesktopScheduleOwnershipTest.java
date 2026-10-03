package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import restudio.rebase.IRebaseManager;
import restudio.rebase.Rebase;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.backend.impl.LocalBackend;
import restudio.rebase.backup.BackupInfo;
import restudio.rebase.backup.BackupManager;
import restudio.rebase.instance.Instance;
import restudio.rebase.platform.jvm.JvmPersistedServerScheduleFeature;
import restudio.rebase.schedule.ServerScheduleModels;
import restudio.rescreen.ui.core.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class DesktopScheduleOwnershipTest {
    @TempDir
    Path temporary;
    private String previousData;
    private Field global;
    private Object previous;
    private BackupManager backups;
    private Instance managed;
    private LocalBackend backend;

    @BeforeEach
    void setup() throws Exception {
        previousData = System.getProperty("restudio.userDataDir");
        System.setProperty("restudio.userDataDir", temporary.toString());
        global = Rebase.class.getDeclaredField("manager");
        global.setAccessible(true);
        previous = global.get(null);
        backups = new BackupManager(temporary);
        global.set(null, Proxy.newProxyInstance(IRebaseManager.class.getClassLoader(), new Class<?>[]{IRebaseManager.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getBackupManager" -> backups;
                    case "isServerEnvironment" -> true;
                    default -> null;
                }));
        Path root = Files.createDirectories(temporary.resolve("server"));
        managed = new Instance("Server", "1", root.toString()) {
            @Override public LocalBackend getBackend() { return backend; }
        };
        managed.setServer(true);
        backend = new LocalBackend(new BackendConfig("LOCAL", Map.of()), managed);
    }

    @AfterEach
    void restore() throws Exception {
        if (backend != null) backend.close();
        Field scheduler = BackupManager.class.getDeclaredField("autoBackupScheduler");
        scheduler.setAccessible(true);
        ((ScheduledExecutorService) scheduler.get(backups)).shutdownNow();
        global.set(null, previous);
        if (previousData == null) System.clearProperty("restudio.userDataDir");
        else System.setProperty("restudio.userDataDir", previousData);
    }

    @Test
    void reopeningSettingsRetainsSchedulesAndBackupOwnershipWhileSettingsStayInTheDraft() throws Exception {
        var method = DesktopServerConfigurationUi.class.getDeclaredMethod("platform", Screen.class, Instance.class, Instance.class);
        method.setAccessible(true);
        Instance firstDraft = new Instance(managed, "Unsaved Name");
        var first = (ServerConfigurationUiPlatform) method.invoke(null, null, firstDraft, managed);
        var mutation = new ServerScheduleModels.Mutation("Test", false,
                new ServerScheduleModels.Timing(ServerScheduleModels.TimingType.CRON, "", "* * * * *", "UTC"), false,
                List.of(new ServerScheduleModels.Task("", 1, ServerScheduleModels.Action.BACKUP, "Backup", 0, false, 3, 0)));
        var schedule = first.scheduleProvider().createSchedule(mutation, "create").join();
        Path world = Files.writeString(Path.of(managed.getPath()).resolve("world.txt"), "World");
        BackupInfo backup = first.backupProvider().create("Backup", List.of(RemotePath.of(world.toString())),
                Duration.ZERO, null, null, null).join();
        assertEquals(managed.getInstanceId(), backup.getInstanceId());
        var second = (ServerConfigurationUiPlatform) method.invoke(null, null, new Instance(managed, managed.getName()), managed);
        assertEquals(schedule.id(), second.scheduleProvider().listSchedules().join().getFirst().id());
        assertEquals(backup.getBackupId(), second.backupProvider().backups().getFirst().getBackupId());
        first.backupProvider().autoBackups(true);
        assertTrue(firstDraft.isAutoBackupEnabled());
        assertFalse(managed.isAutoBackupEnabled());
        assertFalse(JvmPersistedServerScheduleFeature.hasSavedSchedules(firstDraft));
        assertTrue(JvmPersistedServerScheduleFeature.hasSavedSchedules(managed));
    }
}
