package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.workspace.LiveDocumentChannel;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.flow.workspace.WorkspaceTarget;

import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphLiveWorkspaceTest {
    @Test
    void workspacePatchConversionMatchesCanonicalJson() {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("zeta", JsonValue.array(List.of(JsonValue.of(new BigDecimal("1.2300")), JsonValue.nullValue(), JsonValue.of(true))));
        fields.put("alpha", JsonValue.object(Map.of("text", JsonValue.of("snowman ☃"))));
        JsonValue value = JsonValue.object(fields);

        JsonElement expected = JsonParser.parseString(value.canonicalText());
        JsonElement converted = CoreGraphLiveWorkspace.gsonValue(value);
        assertEquals(expected, converted);
        assertEquals(expected.toString(), converted.toString());
    }

    @Test
    void closeDuringJoinCannotLeaveAClosedEditorSubscribed() {
        ServerId server = ServerId.deterministic("workspace-lifecycle");
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), new ScriptedReSyncTransport(), null);
        AtomicReference<CoreGraphLiveWorkspace> live = new AtomicReference<>();
        AtomicInteger joins = new AtomicInteger();
        ReSyncWorkspaceClient workspaces = client.workspaces();
        workspaces.bindLifecycleAdmission(() -> true);
        workspaces.bind(new LiveDocumentChannel.Transport<List<WorkspacePatch<JsonElement>>, JsonObject>() {
            @Override
            public void join(WorkspaceTarget target) {
                joins.incrementAndGet();
                live.get().close();
            }

            @Override
            public void leave(WorkspaceTarget target) {
            }

            @Override
            public boolean publishOperation(WorkspaceTarget target, long sequence, String id, List<WorkspacePatch<JsonElement>> operation) {
                return true;
            }

            @Override
            public boolean publishAwareness(WorkspaceTarget target, JsonObject awareness) {
                return true;
            }
        });
        workspaces.connect(1);
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(server));
        live.set(new CoreGraphLiveWorkspace(client, session, ignored -> { }));
        live.get().join();
        assertEquals(1, joins.get());
        assertNull(live.get().capture(CoreGraphUiProjection.EditorSnapshot.capture(session)));
        workspaces.disconnect("test", 1);
        workspaces.connect(2);
        assertEquals(1, joins.get());
        client.shutdown();
    }

    @Test
    void snapshotsAndReconnectReplaceAwarenessAndRejectThePreviousGeneration() {
        ServerId server = ServerId.deterministic("workspace-awareness");
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), new ScriptedReSyncTransport(), null);
        ReSyncWorkspaceClient workspaces = client.workspaces();
        workspaces.bindLifecycleAdmission(() -> true);
        var source = workspaces.bind(new LiveDocumentChannel.Transport<List<WorkspacePatch<JsonElement>>, JsonObject>() {
            @Override
            public void join(WorkspaceTarget target) {
            }

            @Override
            public void leave(WorkspaceTarget target) {
            }

            @Override
            public boolean publishOperation(WorkspaceTarget target, long sequence, String id, List<WorkspacePatch<JsonElement>> operation) {
                return true;
            }

            @Override
            public boolean publishAwareness(WorkspaceTarget target, JsonObject awareness) {
                return true;
            }
        });
        workspaces.connect(1);
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(server));
        Map<String, ReSyncWorkspaceClient.Awareness> visible = new LinkedHashMap<>();
        CoreGraphLiveWorkspace live = new CoreGraphLiveWorkspace(client, session,
            value -> visible.put(value.authorSessionId(), value), values -> {
                visible.clear();
                values.forEach(value -> visible.put(value.authorSessionId(), value));
            });
        try {
            live.join();
            source.applySnapshot(snapshot(session.graphDocument(), 0, awareness("first", 10), awareness("second", 20)), 1);
            assertEquals(Set.of("first", "second"), visible.keySet());
            source.applySnapshot(snapshot(session.graphDocument(), 1, awareness("second", 30)), 1);
            assertEquals(Set.of("second"), visible.keySet());
            assertEquals(30, visible.get("second").state().get("x").getAsInt());

            workspaces.disconnect("Reconnect", 1);
            assertTrue(visible.isEmpty());
            workspaces.connect(2);
            source.applyAwareness(awareness("first", 40).toString(), 1);
            assertTrue(visible.isEmpty());
            source.applySnapshot(snapshot(session.graphDocument(), 1, awareness("second", 50)), 2);
            source.applyAwareness(awareness("second", 60).toString(), 2);
            source.applySnapshot(snapshot(session.graphDocument(), 2, awareness("first", 70)), 1);
            source.applyAwareness(awareness("second", 80).toString(), 1);
            assertEquals(Set.of("second"), visible.keySet());
            assertEquals(60, visible.get("second").state().get("x").getAsInt());
        } finally {
            live.close();
            client.shutdown();
        }
    }

    @Test
    void closedWorkspaceCannotPublishLateAwarenessOrReplaceTheNextEditorsPresence() {
        ServerId server = ServerId.deterministic("workspace-closed-awareness");
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), new ScriptedReSyncTransport(), null);
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(server));
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger replacements = new AtomicInteger();
        CoreGraphLiveWorkspace live = new CoreGraphLiveWorkspace(client, session,
            value -> updates.incrementAndGet(), values -> replacements.incrementAndGet());
        try {
            live.close();
            ReSyncWorkspaceClient.Awareness late = new ReSyncWorkspaceClient.Awareness("flow", "main", "peer", null,
                new JsonObject(), 1);
            live.onAwareness(late);
            live.onSnapshot(new ReSyncWorkspaceClient.Snapshot("flow", "main", 0,
                JsonParser.parseString(session.graphDocument().canonicalJson()).getAsJsonObject(), List.of(late)));
            live.onResync("Old Connection");
            assertEquals(0, updates.get());
            assertEquals(0, replacements.get());
            assertNull(live.capture(CoreGraphUiProjection.EditorSnapshot.capture(session)));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void ownEchoAndResnapshotPreserveEditsMadeWhilePublicationIsPending() {
        for (String mode : List.of("echo", "snapshot", "overtaken")) {
            ServerId server = ServerId.deterministic("workspace-echo-" + mode);
            ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), new ScriptedReSyncTransport(), null);
            AtomicReference<JsonObject> published = new AtomicReference<>();
            ReSyncWorkspaceClient workspaces = client.workspaces();
            workspaces.bindLifecycleAdmission(() -> true);
            workspaces.bindMutationAdmission(() -> true);
            var source = workspaces.bind(new LiveDocumentChannel.Transport<List<WorkspacePatch<JsonElement>>, JsonObject>() {
                @Override
                public void join(WorkspaceTarget target) {
                }

                @Override
                public void leave(WorkspaceTarget target) {
                }

                @Override
                public boolean publishOperation(WorkspaceTarget target, long sequence, String id, List<WorkspacePatch<JsonElement>> patches) {
                    JsonObject value = new JsonObject();
                    value.addProperty("type", "flow");
                    value.addProperty("resourceId", "main");
                    value.addProperty("sequence", sequence + 1);
                    value.addProperty("operationId", id);
                    value.addProperty("authorSessionId", "local");
                    value.add("patches", new Gson().toJsonTree(patches));
                    published.set(value);
                    return true;
                }

                @Override
                public boolean publishAwareness(WorkspaceTarget target, JsonObject awareness) {
                    return true;
                }
            });
            workspaces.connect(1);
            CoreGraphEditorSession session = new CoreGraphEditorSession(graph(server));
            CoreGraphLiveWorkspace live = new CoreGraphLiveWorkspace(client, session, ignored -> { });
            try {
                live.join();
                source.applySnapshot(snapshot(session.graphDocument(), 0), 1);
                apply(live, session);
                NodeInstanceId node = session.graphDocument().nodes().getFirst().instanceId();
                session.setNodePosition(node, 10, 0);
                var outgoing = live.prepare(live.capture(CoreGraphUiProjection.EditorSnapshot.capture(session)));
                assertEquals("", outgoing.failure());
                if (!mode.equals("overtaken")) {
                    live.accept(outgoing);
                }
                assertNotNull(published.get());
                GraphDocument sent = session.graphDocument();
                session.setNodePosition(node, 20, 0);
                if (!mode.equals("echo")) {
                    source.applySnapshot(snapshot(sent, 1), 1);
                    if (mode.equals("overtaken")) {
                        assertFalse(live.current(outgoing));
                        live.discard(outgoing);
                    }
                } else {
                    source.applyOperation(published.get().toString(), 1);
                }
                apply(live, session);
                assertEquals(20, session.graphDocument().nodes().getFirst().x());
                assertEquals(0, session.baselineGraphDocument().nodes().getFirst().x());
                assertTrue(session.isDirty());
                assertNotNull(live.capture(CoreGraphUiProjection.EditorSnapshot.capture(session)));
            } finally {
                live.close();
                client.shutdown();
            }
        }
    }

    private void apply(CoreGraphLiveWorkspace live, CoreGraphEditorSession session) {
        var request = live.capture(CoreGraphUiProjection.EditorSnapshot.capture(session));
        assertNotNull(request);
        var prepared = live.prepare(request);
        assertEquals("", prepared.failure());
        if (prepared.edit() != null) {
            assertTrue(session.applyWorkspaceEdit(prepared.edit()));
        }
        live.accept(prepared);
    }

    private String snapshot(GraphDocument graph, long sequence, JsonObject... awareness) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("type", "flow");
        snapshot.addProperty("resourceId", "main");
        snapshot.addProperty("sequence", sequence);
        snapshot.addProperty("editability", "EDITABLE");
        snapshot.add("document", JsonParser.parseString(graph.canonicalJson()));
        JsonArray peers = new JsonArray();
        for (JsonObject value : awareness) {
            peers.add(value);
        }
        snapshot.add("awareness", peers);
        return snapshot.toString();
    }

    private JsonObject awareness(String session, int x) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "flow");
        value.addProperty("resourceId", "main");
        value.addProperty("authorSessionId", session);
        value.addProperty("updatedAt", x);
        JsonObject state = new JsonObject();
        state.addProperty("x", x);
        value.add("state", state);
        return value;
    }

    private GraphDocument graph(ServerId server) {
        ServerResourceLocator resource = new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "main");
        GraphNode node = new GraphNode(NodeInstanceId.deterministic("workspace-node"),
            ContractRef.of(OwnerId.of("fixture"), NodeId.of("node")), 1, Map.of());
        return new GraphDocument(new CatalogVersion(1, 0), resource, 1,
            new CatalogBinding(1, "0".repeat(64), "1".repeat(64)), Set.of(), List.of(node), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.empty());
    }
}
