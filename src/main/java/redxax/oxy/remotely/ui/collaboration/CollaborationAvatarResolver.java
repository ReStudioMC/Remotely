package redxax.oxy.remotely.ui.collaboration;

import redxax.oxy.remotely.util.BrowserSafeState;

import redxax.oxy.remotely.collaboration.CollaborationService;
import redxax.oxy.remotely.data.player.model.PlayerAvatarUrl;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Identifier;

import java.util.Map;

public final class CollaborationAvatarResolver implements CollaborationOverlay.AvatarProvider {
    private final Map<String, Identifier> avatars = BrowserSafeState.map();

    public Identifier resolve(CollaborationService.Presence presence) {
        return resolve(presence != null ? presence.identity() : null);
    }

    @Override
    public Identifier resolve(CollaborationService.Identity identity) {
        if (identity == null) {
            return null;
        }
        String key = identity.source() + ':' + identity.subjectId() + ':' + identity.avatar();
        Identifier avatar = avatars.get(key);
        if (avatar != null) {
            return avatar;
        }
        String source = avatarSource(identity);
        if (source.isBlank()) {
            return null;
        }
        Identifier resolved = ScreenManager.getInstance().imageAssets().registerRemoteImage(source);
        if (resolved != null) {
            avatars.put(key, resolved);
        }
        return resolved;
    }

    private String avatarSource(CollaborationService.Identity identity) {
        String avatar = identity.avatar() == null ? "" : identity.avatar().trim();
        if (avatar.startsWith("https://") || avatar.startsWith("http://") || avatar.startsWith("data:") || avatar.startsWith("blob:")) {
            return avatar;
        }
        if (!"minecraft".equalsIgnoreCase(identity.source())) {
            return "";
        }
        String subject = avatar.isBlank() ? identity.displayName() : avatar;
        return PlayerAvatarUrl.resolve(subject, "", 64);
    }
}
