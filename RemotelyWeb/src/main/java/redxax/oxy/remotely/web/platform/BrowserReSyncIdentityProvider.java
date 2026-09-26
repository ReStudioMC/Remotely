package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.collaboration.CollaborationService;
import redxax.oxy.remotely.data.flow.ReSyncIdentityProvider;
import redxax.oxy.remotely.util.NameUuid;

import java.util.UUID;

public final class BrowserReSyncIdentityProvider implements ReSyncIdentityProvider {
    private final String connectionIdentity = UUID.randomUUID().toString();
    public BrowserReSyncIdentityProvider(BrowserLaunchSession.Metadata metadata) {
    }

    @Override
    public String clientId(String serverId) {
        String server = serverId == null || serverId.isBlank() ? "default" : serverId.trim();
        return "remotely-web-" + NameUuid.from(connectionIdentity + ':' + server);
    }

    @Override
    public CollaborationService.Identity collaborationIdentity(String fallbackClientId) {
        BrowserLaunchSession.Metadata identity = metadata();
        String subject = identity.subjectId();
        if (subject == null || subject.isBlank()) subject = fallbackClientId;
        if (subject == null || subject.isBlank()) subject = "remotely-web";
        subject = sanitize(subject);
        String displayName = firstNonBlank(identity.displayName(), identity.username(), identity.sessionLabel(), "Browser Collaborator");
        return new CollaborationService.Identity(subject, displayName, identity.avatarUrl(), "restudio-web");
    }

    private BrowserLaunchSession.Metadata metadata() {
        return BrowserLaunchSession.metadata();
    }

    private String sanitize(String value) {
        String result = value.length() > 128 ? value.substring(0, 128) : value;
        return result.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
