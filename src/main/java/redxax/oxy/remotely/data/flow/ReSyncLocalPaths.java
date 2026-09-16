package redxax.oxy.remotely.data.flow;

import restudio.rescreen.config.Config;

import java.nio.file.Path;

public final class ReSyncLocalPaths {
    private ReSyncLocalPaths() {
    }

    public static Path appDir() {
        Object applicationDir = Config.applicationDir;
        if (applicationDir instanceof Path path) {
            return path;
        }
        return Path.of(".");
    }

    public static Path flowDir() {
        return appDir().resolve("data").resolve("flow");
    }
}
