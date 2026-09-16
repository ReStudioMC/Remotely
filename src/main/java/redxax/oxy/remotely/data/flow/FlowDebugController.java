package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.ui.FlowGraphDesignerScreen;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map;

public class FlowDebugController {
    private final FlowManager flowManager;
    private final Gson gson = new Gson();
    private final Map<String, Set<String>> breakpoints = BrowserSafeState.map();
    private final List<DebugRecord> recentRecords = new ArrayList<>();
    private final Map<String, DebugSession> sessions = BrowserSafeState.map();
    private volatile boolean enabled;

    public FlowDebugController(FlowManager flowManager) {
        this.flowManager = flowManager;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(String serverId, boolean enabled) {
        this.enabled = enabled;
        send(serverId, enabled ? "enable" : "disable", Map.of());
    }

    public void pause(String serverId) {
        send(serverId, "pause", Map.of());
    }

    public void resume(String serverId, String sessionId) {
        send(serverId, sessionId == null || sessionId.isBlank() ? "resumeAll" : "resume", Map.of("sessionId", safe(sessionId)));
    }

    public void stepInto(String serverId, String sessionId) {
        send(serverId, "stepInto", Map.of("sessionId", safe(sessionId)));
    }

    public void stepOver(String serverId, String sessionId) {
        send(serverId, "stepOver", Map.of("sessionId", safe(sessionId)));
    }

    public void stepOut(String serverId, String sessionId) {
        send(serverId, "stepOut", Map.of("sessionId", safe(sessionId)));
    }

    public void stop(String serverId, String sessionId) {
        send(serverId, "stop", Map.of("sessionId", safe(sessionId)));
    }

    public void clear(String serverId) {
        synchronized (recentRecords) {
            recentRecords.clear();
        }
        send(serverId, "clear", Map.of());
        notifyEditor(serverId);
    }

    public void startTestRun(String serverId, FlowGraph graph, String nodeId) {
        if (graph == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("graphId", graph.getId());
        payload.put("nodeId", safe(nodeId));
        send(serverId, "testRun", payload);
    }

    public boolean toggleBreakpoint(String serverId, FlowGraph graph, String nodeId) {
        if (graph == null || graph.getId() == null || nodeId == null || nodeId.isBlank()) {
            return false;
        }
        Set<String> graphBreakpoints = breakpoints.computeIfAbsent(graph.getId(), ignored -> BrowserSafeState.set());
        boolean enabledBreakpoint;
        if (graphBreakpoints.contains(nodeId)) {
            graphBreakpoints.remove(nodeId);
            enabledBreakpoint = false;
        } else {
            graphBreakpoints.add(nodeId);
            enabledBreakpoint = true;
        }
        sendBreakpoints(serverId, graph.getId());
        notifyEditor(serverId);
        return enabledBreakpoint;
    }

    public boolean hasBreakpoint(FlowGraph graph, String nodeId) {
        return graph != null && graph.getId() != null && breakpoints.getOrDefault(graph.getId(), Set.of()).contains(nodeId);
    }

    public Set<String> getBreakpoints(FlowGraph graph) {
        return graph != null && graph.getId() != null ? Set.copyOf(breakpoints.getOrDefault(graph.getId(), Set.of())) : Set.of();
    }

    public List<DebugRecord> getRecentRecords() {
        synchronized (recentRecords) {
            return List.copyOf(recentRecords);
        }
    }

    public DebugSession getActiveSession() {
        return sessions.values().stream()
            .filter(session -> "paused".equals(session.status()))
            .findFirst()
            .orElse(sessions.values().stream().findFirst().orElse(null));
    }

    public boolean isPausedAt(String graphId, String nodeId) {
        return sessions.values().stream()
            .anyMatch(session -> "paused".equals(session.status())
                && safe(graphId).equals(safe(session.currentGraphId()).isBlank() ? session.graphId() : session.currentGraphId())
                && safe(nodeId).equals(session.currentNodeId()));
    }

    public DebugRecord getLatestRecordForNode(String graphId, String nodeId) {
        synchronized (recentRecords) {
            for (int i = recentRecords.size() - 1; i >= 0; i--) {
                DebugRecord record = recentRecords.get(i);
                if (safe(graphId).equals(record.graphId()) && safe(nodeId).equals(record.nodeId())) {
                    return record;
                }
            }
        }
        return null;
    }

    public DebugRecord getLatestConnection(String graphId, String sourceNodeId, String sourcePin, String targetNodeId, String targetPin) {
        synchronized (recentRecords) {
            for (int i = recentRecords.size() - 1; i >= 0; i--) {
                DebugRecord record = recentRecords.get(i);
                if (!"connection".equals(record.eventType())) {
                    continue;
                }
                if (safe(graphId).equals(record.graphId())
                    && safe(sourceNodeId).equals(record.sourceNodeId())
                    && safe(sourcePin).equals(record.sourcePin())
                    && safe(targetNodeId).equals(record.targetNodeId())
                    && safe(targetPin).equals(record.targetPin())) {
                    return record;
                }
            }
        }
        return null;
    }

    public DebugRecord getLatestConnection(String graphId) {
        synchronized (recentRecords) {
            for (int i = recentRecords.size() - 1; i >= 0; i--) {
                DebugRecord record = recentRecords.get(i);
                if ("connection".equals(record.eventType()) && safe(graphId).equals(record.graphId())) {
                    return record;
                }
            }
        }
        return null;
    }

    public void applyTraceSnapshot(String serverId, String json) {
        synchronized (recentRecords) {
            recentRecords.clear();
            JsonArray array = gson.fromJson(json, JsonArray.class);
            if (array != null) {
                for (JsonElement element : array) {
                    DebugRecord record = parseRecord(element);
                    if (record != null) {
                        recentRecords.add(record);
                        upsertSession(record);
                    }
                }
            }
        }
        notifyEditor(serverId);
    }

    public void applyTraceEvent(String serverId, String json) {
        DebugRecord record = parseRecord(gson.fromJson(json, JsonElement.class));
        if (record == null) {
            return;
        }
        synchronized (recentRecords) {
            recentRecords.add(record);
            while (recentRecords.size() > 300) {
                recentRecords.removeFirst();
            }
        }
        upsertSession(record);
        notifyEditor(serverId);
    }

    public void applyDebugSnapshot(String serverId, String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null) {
            return;
        }
        enabled = bool(root, "enabled");
        JsonObject breakpointRoot = object(root, "breakpoints");
        if (breakpointRoot != null) {
            breakpoints.clear();
            for (Map.Entry<String, JsonElement> entry : breakpointRoot.entrySet()) {
                breakpoints.put(entry.getKey(), nodeSet(entry.getValue()));
            }
        }
        sessions.clear();
        JsonArray sessionArray = array(root, "sessions");
        if (sessionArray != null) {
            for (JsonElement element : sessionArray) {
                JsonObject item = element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
                if (item != null) {
                    DebugSession session = new DebugSession(
                        string(item, "sessionId"),
                        string(item, "graphId"),
                        string(item, "currentGraphId"),
                        string(item, "currentNodeId"),
                        string(item, "currentNodeType"),
                        string(item, "status"),
                        string(item, "reason"),
                        integer(item, "depth")
                    );
                    sessions.put(session.sessionId(), session);
                }
            }
        }
        notifyEditor(serverId);
    }

    private void sendBreakpoints(String serverId, String graphId) {
        Set<String> nodes = breakpoints.getOrDefault(graphId, Set.of());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("graphId", graphId);
        payload.put("nodeIds", new ArrayList<>(nodes));
        send(serverId, "breakpoints", payload);
    }

    private void send(String serverId, String action, Map<String, Object> payload) {
        if (flowManager == null || serverId == null || serverId.isBlank()) {
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("action", action);
        if (payload != null) {
            root.putAll(payload);
        }
        ReSyncFlowClient client = flowManager.ensureFlowClient(serverId);
        if (client != null) {
            client.sendDebugCommand(root);
        }
    }

    private void upsertSession(DebugRecord record) {
        if (record.debugSessionId().isBlank()) {
            return;
        }
        sessions.put(record.debugSessionId(), new DebugSession(
            record.debugSessionId(),
            record.graphId(),
            record.graphId(),
            record.nodeId(),
            record.nodeType(),
            record.status(),
            record.reason(),
            record.executionDepth()
        ));
    }

    private DebugRecord parseRecord(JsonElement element) {
        JsonObject root = element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        if (root == null) {
            return null;
        }
        return new DebugRecord(
            longValue(root, "sequence"),
            longValue(root, "timestamp"),
            string(root, "debugSessionId"),
            string(root, "eventType"),
            string(root, "graphId"),
            string(root, "nodeId"),
            string(root, "nodeType"),
            string(root, "status"),
            string(root, "reason"),
            string(root, "sourceNodeId"),
            string(root, "sourcePin"),
            string(root, "targetNodeId"),
            string(root, "targetPin"),
            string(root, "inputSummary"),
            string(root, "outputSummary"),
            string(root, "errorText"),
            integer(root, "executionDepth")
        );
    }

    private void notifyEditor(String serverId) {
        ScreenManager.getInstance().execute(() -> {
            Screen current = ScreenManager.getInstance().getCurrentScreen();
            if (current instanceof FlowGraphDesignerScreen editor && safe(serverId).equals(editor.getServerId())) {
                editor.refreshDebugState();
            }
        });
    }

    private Set<String> nodeSet(JsonElement element) {
        Set<String> values = new HashSet<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && !item.isJsonNull()) {
                    values.add(item.getAsString());
                }
            }
        }
        return values;
    }

    private JsonObject object(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : null;
    }

    private JsonArray array(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonArray() ? root.getAsJsonArray(key) : null;
    }

    private String string(JsonObject root, String key) {
        return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : "";
    }

    private boolean bool(JsonObject root, String key) {
        return root != null && root.has(key) && !root.get(key).isJsonNull() && root.get(key).getAsBoolean();
    }

    private int integer(JsonObject root, String key) {
        return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsInt() : 0;
    }

    private long longValue(JsonObject root, String key) {
        return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsLong() : 0L;
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    public record DebugSession(String sessionId, String graphId, String currentGraphId, String currentNodeId, String currentNodeType, String status, String reason, int depth) {
    }

    public record DebugRecord(long sequence, long timestamp, String debugSessionId, String eventType, String graphId, String nodeId,
                              String nodeType, String status, String reason, String sourceNodeId, String sourcePin,
                              String targetNodeId, String targetPin, String inputSummary, String outputSummary,
                              String errorText, int executionDepth) {
    }
}
