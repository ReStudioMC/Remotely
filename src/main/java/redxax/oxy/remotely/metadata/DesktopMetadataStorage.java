package redxax.oxy.remotely.metadata;

import restudio.rescreen.platform.Async;
import restudio.resync.metadata.MetadataBundleId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class DesktopMetadataStorage implements MetadataStorage {
    private final Path directory;

    public DesktopMetadataStorage(Path directory) {
        this.directory = Objects.requireNonNull(directory, "Metadata cache directory is required");
    }

    @Override
    public Async<byte[]> read(MetadataBundleId bundleId) {
        return Async.supplyAsync(() -> {
            Path path = path(bundleId);
            try {
                return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read cached metadata bundle", exception);
            }
        });
    }

    @Override
    public Async<Void> write(MetadataBundleId bundleId, byte[] bytes) {
        byte[] content = Objects.requireNonNull(bytes, "Metadata bundle bytes are required").clone();
        return Async.supplyAsync(() -> {
            Path target = path(bundleId);
            Path temporary = directory.resolve(bundleId.canonicalText() + ".tmp");
            try {
                Files.createDirectories(directory);
                Files.write(temporary, content);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return null;
            } catch (IOException exception) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
                throw new IllegalStateException("Could not cache verified metadata bundle", exception);
            }
        });
    }

    private Path path(MetadataBundleId bundleId) {
        return directory.resolve(bundleId.canonicalText() + ".json");
    }
}
