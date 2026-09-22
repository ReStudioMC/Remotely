package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.collaboration.CollaborationService;
import redxax.oxy.remotely.flow.data.FlowJson;
import restudio.rescreen.util.JsonTreeParser;

import java.util.ArrayList;
import java.util.List;

public final class ReSyncCollaborationClient extends CollaborationService {
    public ReSyncCollaborationClient(Gson gson, String clientId) {
        super(clientId);
    }

    public boolean applySnapshot(String json) {
        JsonObject root;
        try {
            JsonElement parsed = JsonTreeParser.parse(json);
            root = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException exception) {
            return false;
        }
        if (root == null) {
            return false;
        }
        List<String> selfSessionIds = FlowJson.stringList(root, "selfSessionIds");
        List<Presence> collaborators = new ArrayList<>();
        FlowJson.array(root, "collaborators").forEach(value -> {
            if (value.isJsonObject()) {
                collaborators.add(presence(value.getAsJsonObject()));
            }
        });
        return acceptSnapshot(FlowJson.string(root, "selfSessionId", ""), identity(FlowJson.object(root, "selfIdentity")),
            selfSessionIds, collaborators);
    }

    public void applyResourceChange(ResourceChange change) {
        acceptResourceChange(change);
    }

    public boolean applyMessage(String json) {
        JsonObject root;
        try {
            JsonElement parsed = JsonTreeParser.parse(json);
            root = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException exception) {
            return false;
        }
        if (root == null) {
            return false;
        }
        return acceptMessage(new Message(FlowJson.string(root, "id", ""),
            FlowJson.string(root, "authorSessionId", ""), identity(FlowJson.object(root, "author")),
            FlowJson.string(root, "resourceType", ""), FlowJson.string(root, "resourceId", ""),
            FlowJson.integer(root, "color", 0), FlowJson.string(root, "message", ""),
            FlowJson.longValue(root, "sentAt", 0L)));
    }

    private Presence presence(JsonObject root) {
        return new Presence(FlowJson.string(root, "sessionId", ""), FlowJson.string(root, "clientId", ""),
            identity(FlowJson.object(root, "identity")), FlowJson.string(root, "resourceType", ""),
            FlowJson.string(root, "resourceId", ""), FlowJson.string(root, "viewId", ""),
            FlowJson.decimal(root, "x", 0.0), FlowJson.decimal(root, "y", 0.0),
            FlowJson.bool(root, "active", false), FlowJson.bool(root, "typing", false),
            FlowJson.integer(root, "color", 0), FlowJson.bool(root, "customColor", false),
            FlowJson.longValue(root, "updatedAt", 0L));
    }

    private Identity identity(JsonObject root) {
        return root == null ? null : new Identity(FlowJson.string(root, "subjectId", ""),
            FlowJson.string(root, "displayName", "Collaborator"), FlowJson.string(root, "avatar", ""),
            FlowJson.string(root, "source", ""));
    }
}
