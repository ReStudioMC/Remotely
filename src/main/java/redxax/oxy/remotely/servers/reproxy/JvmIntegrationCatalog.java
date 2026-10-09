package redxax.oxy.remotely.servers.reproxy;

import redxax.oxy.remotely.DesktopRemotelyPaths;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

public final class JvmIntegrationCatalog {
    private static final int MAX_BYTES = 256 * 1024;
    private static boolean initialized;

    private JvmIntegrationCatalog() {
    }

    public static Path path() {
        return DesktopRemotelyPaths.dataDir(DesktopRemotelyPaths.appDir()).resolve("reproxy/integrations.json");
    }

    public static synchronized void initialize() {
        if (initialized) return;
        String defaults;
        try (InputStream input = JvmIntegrationCatalog.class.getResourceAsStream("/assets/remotely/reproxy/integrations.json")) {
            if (input == null) throw new IOException("Plugin Integration Defaults Are Missing");
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Plugin Integration Defaults Are Too Large");
            defaults = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could Not Load Plugin Integration Defaults", failure);
        }
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                try {
                    Files.writeString(file, defaults, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException ignored) {
                }
            }
            String source;
            try (InputStream input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Plugin Integration Catalog Is Too Large");
                source = new String(bytes, StandardCharsets.UTF_8);
            }
            if (previousDefault(source)) {
                Path temporary = Files.createTempFile(file.getParent(), "integrations-", ".json");
                try {
                    Files.writeString(temporary, defaults, StandardCharsets.UTF_8);
                    if (Files.readString(file, StandardCharsets.UTF_8).equals(source)) {
                        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                        catch (AtomicMoveNotSupportedException failure) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
                        source = defaults;
                    }
                } finally { Files.deleteIfExists(temporary); }
            }
            ReProxyIntegrations.load(source);
        } catch (IOException | RuntimeException failure) {
            ReLog.logger(LogTypes.FILESYSTEM).source(LogSource.resource(file.toString(), "Plugin Integrations"))
                    .component(JvmIntegrationCatalog.class).warn("Could Not Load Plugin Integrations. Using Bundled Defaults; The Custom File Was Preserved", failure);
            ReProxyIntegrations.load(defaults);
        }
        initialized = true;
    }

    private static boolean previousDefault(String source) throws IOException {
        try (InputStream input = JvmIntegrationCatalog.class.getResourceAsStream("/assets/remotely/reproxy/integrations-v1.json")) {
            return input != null && source.equals(new String(input.readNBytes(MAX_BYTES + 1), StandardCharsets.UTF_8));
        }
    }

}
