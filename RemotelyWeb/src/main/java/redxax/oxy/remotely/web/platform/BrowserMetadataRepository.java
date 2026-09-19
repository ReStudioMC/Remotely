package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.metadata.MetadataHttpTransport;
import redxax.oxy.remotely.metadata.MetadataRepository;
import restudio.rescreen.platform.http.HttpTransport;

public final class BrowserMetadataRepository {
    private BrowserMetadataRepository() {
    }

    public static MetadataRepository create(HttpTransport transport) {
        String origin = BrowserLaunchSession.backendOrigin();
        if (origin == null || origin.isBlank()) {
            throw new IllegalStateException("Remotely Web Backend Is Not Configured");
        }
        return new MetadataRepository(new MetadataHttpTransport(transport, origin + "/api/public/metadata/v1"),
            new BrowserMetadataStorage());
    }
}
