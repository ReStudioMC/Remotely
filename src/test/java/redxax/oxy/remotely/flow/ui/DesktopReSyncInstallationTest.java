package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.impl.LocalBackend;
import restudio.rebase.backend.feature.NetworkTransferFeature;
import restudio.rebase.instance.Instance;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HexFormat;
import java.util.function.BiConsumer;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopReSyncInstallationTest {
    @TempDir
    Path directory;

    @Test
    void corruptDownloadsAndFailedPublicationPreserveTheOldPluginAndItsSettings() throws Exception {
        Instance instance = new Instance("Test", "1.21", directory.toString());
        FileSystemProvider files = new LocalBackend(instance.getBackendConfig(), instance).getFileSystem();
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        Path target = plugins.resolve("ReSync.jar");
        byte[] old = "Previous Plugin".getBytes();
        Files.write(target, old);
        Path config = Files.createDirectories(plugins.resolve("ReSync")).resolve("config.properties");
        Files.writeString(config, "api-key=existing\nport=12445\n");
        byte[] settings = Files.readAllBytes(config);
        Path releaseFile = directory.resolve("release.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(releaseFile))) {
            for (String name : new String[]{"plugin.yml", "velocity-plugin.json"}) {
                jar.putNextEntry(new JarEntry(name));
                jar.write("valid".getBytes());
                jar.closeEntry();
            }
        }
        byte[] release = Files.readAllBytes(releaseFile);
        DesktopReSyncProvisioningService service = new DesktopReSyncProvisioningService();
        DesktopReSyncProvisioningService.ReSyncRelease metadata = new DesktopReSyncProvisioningService.ReSyncRelease("release-id", "1.3.0", "ReSync.jar",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(release)), "");
        NetworkTransferFeature corrupt = transfer(old);
        assertThrows(IOException.class, () -> service.installVerified(instance, files, corrupt, plugins, metadata));
        assertArrayEquals(old, Files.readAllBytes(target));
        FileSystemProvider interrupted = (FileSystemProvider) Proxy.newProxyInstance(FileSystemProvider.class.getClassLoader(), new Class<?>[]{FileSystemProvider.class}, (object, method, arguments) -> {
            if (method.getName().equals("rename") && arguments[0].toString().endsWith("ReSync.jar") && arguments[1].equals(target)) {
                return CompletableFuture.failedFuture(new IOException("Publish Interrupted"));
            }
            try { return method.invoke(files, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        assertThrows(Exception.class, () -> service.installVerified(instance, interrupted, transfer(release), plugins, metadata));
        assertArrayEquals(old, Files.readAllBytes(target));
        assertArrayEquals(settings, Files.readAllBytes(config));
        assertFalse(Files.exists(plugins.resolve(".resync-install.properties")));
        CompletableFuture<Void> delayed = new CompletableFuture<>();
        AtomicReference<Path> backup = new AtomicReference<>();
        FileSystemProvider slow = (FileSystemProvider) Proxy.newProxyInstance(FileSystemProvider.class.getClassLoader(), new Class<?>[]{FileSystemProvider.class}, (object, method, arguments) -> {
            if (method.getName().equals("rename") && arguments[0].equals(target)) {
                backup.set((Path) arguments[1]);
                return delayed;
            }
            try { return method.invoke(files, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        DesktopReSyncProvisioningService bounded = new DesktopReSyncProvisioningService(Duration.ofMillis(100));
        assertThrows(IOException.class, () -> bounded.installVerified(instance, slow, transfer(release), plugins, metadata));
        assertArrayEquals(old, Files.readAllBytes(target));
        assertTrue(Files.exists(plugins.resolve(".resync-install.properties")));
        assertThrows(IOException.class, () -> service.installVerified(instance, files, transfer(release), plugins, metadata));
        files.rename(target, backup.get()).get();
        delayed.complete(null);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Files.exists(plugins.resolve(".resync-install.properties")) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(Files.exists(plugins.resolve(".resync-install.properties")));
        assertArrayEquals(release, Files.readAllBytes(target));
        assertArrayEquals(settings, Files.readAllBytes(config));
    }

    private NetworkTransferFeature transfer(byte[] bytes) {
        return new NetworkTransferFeature() {
            @Override
            public CompletableFuture<Void> downloadFile(String url, Path destination, BiConsumer<Long, Long> progress) {
                try { Files.write(destination, bytes); return CompletableFuture.completedFuture(null); }
                catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
            }
        };
    }
}
