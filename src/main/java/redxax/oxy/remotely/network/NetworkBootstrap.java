package redxax.oxy.remotely.network;

import redxax.oxy.remotely.network.config.NetworkConfigurationAdapters;
import redxax.oxy.remotely.network.config.DesktopStructuredDocumentParser;

import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.util.Executors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class NetworkBootstrap {
    private static final int MAX_CONFIG_BYTES = 1_048_576;
    private static final long MAX_JAR_BYTES = 536_870_912;

    private NetworkBootstrap() {
    }

    public static CompletableFuture<Void> initialize(Instance instance) {
        if (instance.getModLoader() != ModLoader.VELOCITY) return CompletableFuture.completedFuture(null);
        ServerBackend backend = instance.getBackend();
        if (backend == null || backend.getFileSystem() == null || !backend.getFileSystem().supportsAtomicWrites()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Proxy Configuration Access Is Unavailable"));
        }
        FileSystemProvider files = backend.getFileSystem();
        Path config = Path.of(instance.getPath(), "velocity.toml");
        return files.exists(config).thenCompose(exists -> {
            if (exists) return CompletableFuture.completedFuture(null);
            return CompletableFuture.supplyAsync(() -> installedDefault(instance, files), Executors.IO)
                    .thenCompose(content -> files.exists(config).thenCompose(created -> created
                            ? CompletableFuture.completedFuture(null) : files.writeAtomic(config, content)));
        });
    }

    private static String installedDefault(Instance instance, FileSystemProvider files) {
        Path temporary = null;
        try {
            Path jar = instance.resolveServerJarPath();
            boolean local = instance.getBackendConfig() == null || "LOCAL".equalsIgnoreCase(instance.getBackendConfig().type);
            if (!local) {
                temporary = Files.createTempDirectory("remotely-proxy-config-");
                files.download(List.of(jar), temporary, (downloaded, total) -> {
                    if (downloaded > MAX_JAR_BYTES || total > MAX_JAR_BYTES) throw new IllegalStateException("Proxy Software Is Too Large");
                }).join();
                jar = temporary.resolve(jar.getFileName().toString());
            }
            if (!Files.isRegularFile(jar) || Files.size(jar) > MAX_JAR_BYTES) throw new IOException("Installed Proxy Software Is Unavailable");
            try (ZipFile archive = new ZipFile(jar.toFile())) {
                ZipEntry entry = archive.getEntry("default-velocity.toml");
                if (entry == null || entry.isDirectory() || entry.getSize() > MAX_CONFIG_BYTES) {
                    throw new IOException("Installed Proxy Has No Supported Default Configuration");
                }
                byte[] content;
                try (var input = archive.getInputStream(entry)) {
                    content = input.readNBytes(MAX_CONFIG_BYTES + 1);
                }
                if (content.length > MAX_CONFIG_BYTES) throw new IOException("Proxy Configuration Is Too Large");
                String text = new String(content, StandardCharsets.UTF_8);
                if (!new NetworkConfigurationAdapters(new DesktopStructuredDocumentParser()).get(ConfigurationFormat.TOML).contains(text, "config-version")) {
                    throw new IOException("Installed Proxy Configuration Version Is Missing");
                }
                return text;
            }
        } catch (IOException failure) {
            throw new CompletionException(failure);
        } finally {
            if (temporary != null) {
                try (var children = Files.list(temporary)) {
                    for (Path child : children.toList()) Files.deleteIfExists(child);
                } catch (IOException ignored) {
                }
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
