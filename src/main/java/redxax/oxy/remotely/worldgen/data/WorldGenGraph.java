package redxax.oxy.remotely.worldgen.data;

import com.google.gson.JsonObject;
import redxax.oxy.remotely.nodegraph.editor.GraphModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WorldGenGraph implements GraphModel {
    public static final int CURRENT_VERSION = 1;
    private String id;
    private int version;
    private Map<String, WorldGenNode> nodes;
    private List<WorldGenConnection> connections;
    private transient JsonObject opaquePayload;

    public WorldGenGraph() {
        this.id = UUID.randomUUID().toString();
        this.version = CURRENT_VERSION;
        this.nodes = new HashMap<>();
        this.connections = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, WorldGenNode> getNodes() {
        return nodes;
    }

    public void setNodes(Map<String, WorldGenNode> nodes) {
        this.nodes = nodes != null ? nodes : new HashMap<>();
    }

    public List<WorldGenConnection> getConnections() {
        return connections;
    }

    public void setConnections(List<WorldGenConnection> connections) {
        this.connections = connections != null ? connections : new ArrayList<>();
    }

    JsonObject opaquePayload() {
        return opaquePayload == null ? null : opaquePayload.deepCopy();
    }

    void setOpaquePayload(JsonObject opaquePayload) {
        this.opaquePayload = opaquePayload == null ? null : opaquePayload.deepCopy();
    }
}
