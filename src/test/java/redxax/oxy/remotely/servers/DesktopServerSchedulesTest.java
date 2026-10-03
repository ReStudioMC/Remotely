package redxax.oxy.remotely.servers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import restudio.rebase.backend.ExecutionProvider;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.backend.feature.ServerScheduleFeature;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.platform.jvm.JvmPersistedServerScheduleFeature;
import restudio.rebase.schedule.ServerScheduleModels;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class DesktopServerSchedulesTest {
    @TempDir
    Path temporary;
    private String previousData;

    @AfterEach
    void restore() {
        if (previousData == null) System.clearProperty("restudio.userDataDir");
        else System.setProperty("restudio.userDataDir", previousData);
    }

    @Test
    void persistedSchedulesResumeWithoutOpeningSettingsAndSurviveCatalogRefresh() throws Exception {
        previousData = System.getProperty("restudio.userDataDir");
        System.setProperty("restudio.userDataDir", temporary.toString());
        AtomicReference<CountDownLatch> ran = new AtomicReference<>(new CountDownLatch(2));
        AtomicReference<JvmPersistedServerScheduleFeature> active = new AtomicReference<>();
        ExecutionProvider execution = (ExecutionProvider) Proxy.newProxyInstance(ExecutionProvider.class.getClassLoader(),
                new Class<?>[]{ExecutionProvider.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("sendCommand")) ran.get().countDown();
                    return CompletableFuture.completedFuture(null);
                });
        Instance instance = new Instance("Server", "1", temporary.resolve("server").toString()) {
            private ServerBackend backend;
            @Override public synchronized ServerBackend getBackend() {
                if (backend == null) {
                    var feature = new JvmPersistedServerScheduleFeature(this, () -> execution, () -> null);
                    active.set(feature);
                    backend = (ServerBackend) Proxy.newProxyInstance(ServerBackend.class.getClassLoader(), new Class<?>[]{ServerBackend.class},
                            (proxy, method, arguments) -> method.getName().equals("getFeature") ? Optional.of(feature) : null);
                }
                return backend;
            }
            @Override public synchronized void closeBackend() {
                if (backend != null) {
                    active.get().close();
                    backend = null;
                }
            }
        };
        instance.setServer(true);
        String id;
        try (var feature = new JvmPersistedServerScheduleFeature(instance, () -> null, () -> null)) {
            id = feature.createSchedule(new ServerScheduleModels.Mutation("Every Second", true,
                    new ServerScheduleModels.Timing(ServerScheduleModels.TimingType.CRON, "", "* * * * * *", "UTC"), false,
                    List.of(new ServerScheduleModels.Task("", 1, ServerScheduleModels.Action.COMMAND, "say test", 0, false))), "create").join().id();
        }
        Constructor<InstanceManager> constructor = InstanceManager.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        InstanceManager manager = constructor.newInstance(temporary);
        Field local = InstanceManager.class.getDeclaredField("localInstances");
        local.setAccessible(true);
        @SuppressWarnings("unchecked")
        CopyOnWriteArrayList<Instance> values = (CopyOnWriteArrayList<Instance>) local.get(manager);
        values.add(instance);
        try (DesktopServerSchedules runtime = new DesktopServerSchedules(manager)) {
            runtime.start();
            manager.notifyChangeListeners();
            assertTrue(ran.get().await(8, TimeUnit.SECONDS));
            instance.closeBackend();
            ran.set(new CountDownLatch(2));
            manager.notifyChangeListeners();
            assertTrue(ran.get().await(8, TimeUnit.SECONDS));
            assertEquals(id, active.get().listSchedules().join().getFirst().id());
        } finally {
            instance.closeBackend();
        }
    }
}
