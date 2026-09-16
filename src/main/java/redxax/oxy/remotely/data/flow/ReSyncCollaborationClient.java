package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import redxax.oxy.remotely.collaboration.CollaborationService;

import java.util.List;

public final class ReSyncCollaborationClient extends CollaborationService {
    private final Gson gson;

    public ReSyncCollaborationClient(Gson gson, String clientId) {
        super(clientId);
        this.gson = gson;
    }

    public boolean applySnapshot(String json) {
        Snapshot snapshot;
        try {
            snapshot = gson.fromJson(json, Snapshot.class);
        } catch (RuntimeException exception) {
            return false;
        }
        return acceptSnapshot(snapshot != null ? snapshot.selfSessionId() : "",
            snapshot != null ? snapshot.selfIdentity() : null,
            snapshot != null ? snapshot.selfSessionIds() : List.of(),
            snapshot != null ? snapshot.collaborators() : List.of());
    }

    public void applyResourceChange(ResourceChange change) {
        acceptResourceChange(change);
    }

    public boolean applyMessage(String json) {
        Message message;
        try {
            message = gson.fromJson(json, Message.class);
        } catch (RuntimeException exception) {
            return false;
        }
        return acceptMessage(message);
    }

    private record Snapshot(String selfSessionId, Identity selfIdentity, List<String> selfSessionIds, List<Presence> collaborators) {
    }
}
