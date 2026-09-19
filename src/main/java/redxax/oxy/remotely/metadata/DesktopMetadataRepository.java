package redxax.oxy.remotely.metadata;

import restudio.rebase.platform.jvm.JvmHttpTransport;

import java.nio.file.Path;

public final class DesktopMetadataRepository {
    private static final String PUBLIC_API = "https://restudiomc.net/api/public/metadata/v1";

    private DesktopMetadataRepository() {
    }

    public static MetadataRepository create(Path cacheDirectory) {
        return create(cacheDirectory, PUBLIC_API);
    }

    public static MetadataRepository create(Path cacheDirectory, String publicApi) {
        return new MetadataRepository(new MetadataHttpTransport(new JvmHttpTransport(), publicApi),
            new DesktopMetadataStorage(cacheDirectory));
    }
}
