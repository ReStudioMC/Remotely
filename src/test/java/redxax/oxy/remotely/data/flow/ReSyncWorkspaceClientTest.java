package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.collaboration.CollaborationService;
import restudio.resync.flow.workspace.LiveDocumentChannel;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.flow.workspace.WorkspaceTarget;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncWorkspaceClientTest {
    @Test
    void ordersOperationsAndRecognizesOptimisticEchoes() {
        ReSyncWorkspaceClient client = new ReSyncWorkspaceClient(new Gson());
        ReSyncWorkspaceClient.WorkspaceSource source = client.bind(null);
        RecordingListener listener = new RecordingListener();
        client.join("flow", "main", listener);
        client.connect();
        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":2,"document":{"nodes":{},"connections":[]},"awareness":[]}
            """, 1);
        client.sent("flow", "main", "local-operation");
        source.applyOperation("""
            {
              "type":"flow",
              "resourceId":"main",
              "sequence":3,
              "operationId":"local-operation",
              "authorSessionId":"one",
              "patches":[{"op":"set","path":"/nodes/first","value":{"type":"log","x":10,"y":20}}]
            }
            """, 1);

        assertEquals(3L, client.sequence("flow", "main"));
        assertEquals(List.of(true), listener.ownOperations);
        assertEquals(1, listener.operations.size());
    }

    @Test
    void requestsAResyncWhenAnOrderedOperationIsMissing() {
        ReSyncWorkspaceClient client = new ReSyncWorkspaceClient(new Gson());
        ReSyncWorkspaceClient.WorkspaceSource source = client.bind(null);
        RecordingListener listener = new RecordingListener();
        client.join("flow", "main", listener);
        client.connect();
        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":4,"document":{"nodes":{},"connections":[]},"awareness":[]}
            """, 1);
        source.applyOperation("""
            {"type":"flow","resourceId":"main","sequence":6,"operationId":"remote","authorSessionId":"two","patches":[]}
            """, 1);

        assertEquals(List.of("Operation Gap"), listener.resyncReasons);
        assertTrue(listener.operations.isEmpty());
    }

    @Test
    void ignoresOperationsAlreadyCoveredByAResyncSnapshot() {
        ReSyncWorkspaceClient client = new ReSyncWorkspaceClient(new Gson());
        ReSyncWorkspaceClient.WorkspaceSource source = client.bind(null);
        RecordingListener listener = new RecordingListener();
        client.join("flow", "main", listener);
        client.connect();
        client.sent("flow", "main", "local-operation");
        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":5,"document":{"nodes":{},"connections":[]},"awareness":[]}
            """, 1);
        source.applyOperation("""
            {"type":"flow","resourceId":"main","sequence":5,"operationId":"local-operation","authorSessionId":"one","patches":[]}
            """, 1);

        assertEquals(5L, client.sequence("flow", "main"));
        assertTrue(listener.operations.isEmpty());
        assertTrue(listener.resyncReasons.isEmpty());
    }

    @Test
    void requestsOneResyncWhenOperationsArriveBeforeTheSnapshot() {
        ReSyncWorkspaceClient client = new ReSyncWorkspaceClient(new Gson());
        ReSyncWorkspaceClient.WorkspaceSource source = client.bind(null);
        RecordingListener listener = new RecordingListener();
        client.join("flow", "main", listener);
        client.connect();
        String operation = """
            {"type":"flow","resourceId":"main","sequence":1,"operationId":"remote","authorSessionId":"two","patches":[]}
            """;

        source.applyOperation(operation, 1);
        source.applyOperation(operation, 1);

        assertEquals(List.of("Operation Before Snapshot"), listener.resyncReasons);
        assertTrue(listener.operations.isEmpty());
    }

    @Test
    void treatsOperationsFromAnotherSessionAsRemote() {
        CollaborationService collaboration = new CollaborationService("direct");
        collaboration.identify(new CollaborationService.Identity("user", "Alex", "", "restudio"));
        ReSyncWorkspaceClient client = new ReSyncWorkspaceClient(new Gson(), collaboration);
        ReSyncWorkspaceClient.WorkspaceSource source = client.bind(null);
        RecordingListener listener = new RecordingListener();
        client.join("flow", "main", listener);
        client.connect();
        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":0,"document":{"nodes":{},"connections":[]},"awareness":[]}
            """, 1);
        source.applyOperation("""
            {
              "type":"flow",
              "resourceId":"main",
              "sequence":1,
              "operationId":"bridge-operation",
              "authorSessionId":"bridge",
              "author":{"subjectId":"user","displayName":"Alex","avatar":"","source":"minecraft"},
              "patches":[{"op":"set","path":"/nodes/first/x","value":10}]
            }
            """, 1);

        assertEquals(List.of(false), listener.ownOperations);
    }

    @Test
    void keepsAwarenessFromAnotherSessionVisible() {
        CollaborationService collaboration = new CollaborationService("direct");
        collaboration.identify(new CollaborationService.Identity("user", "Alex", "", "restudio"));
        ReSyncWorkspaceClient client = new ReSyncWorkspaceClient(new Gson(), collaboration);
        ReSyncWorkspaceClient.WorkspaceSource source = client.bind(null);
        RecordingListener listener = new RecordingListener();
        client.join("flow", "main", listener);
        client.connect();
        source.applySnapshot("""
            {
              "type":"flow",
              "resourceId":"main",
              "sequence":0,
              "document":{"nodes":{},"connections":[]},
              "awareness":[
                {"type":"flow","resourceId":"main","authorSessionId":"bridge","author":{"subjectId":"user","displayName":"Alex","avatar":"","source":"minecraft"},"state":{},"updatedAt":1},
                {"type":"flow","resourceId":"main","authorSessionId":"other","author":{"subjectId":"other","displayName":"Sam","avatar":"","source":"restudio"},"state":{},"updatedAt":1}
              ]
            }
            """, 1);
        source.applyAwareness("""
            {"type":"flow","resourceId":"main","authorSessionId":"bridge","author":{"subjectId":"user","displayName":"Alex","avatar":"","source":"minecraft"},"state":{},"updatedAt":2}
            """, 1);

        assertEquals(List.of("bridge", "other"), listener.snapshotAwareness);
        assertEquals(List.of("bridge", "other", "bridge"), listener.awareness);
    }

    @Test
    void registersAdapterBeforeSynchronousJoinDelivery() {
        ReSyncWorkspaceClient client = new ReSyncWorkspaceClient(new Gson());
        List<WorkspaceTarget> joined = new ArrayList<>();
        ReSyncWorkspaceClient.WorkspaceSource[] source = new ReSyncWorkspaceClient.WorkspaceSource[1];
        source[0] = client.bind(new LiveDocumentChannel.Transport<List<WorkspacePatch<JsonElement>>, JsonObject>() {
            @Override
            public void join(WorkspaceTarget target) {
                joined.add(target);
                source[0].applySnapshot("""
                    {"type":"flow","resourceId":"main","sequence":0,"document":{"nodes":{},"connections":[]},"awareness":[]}
                    """, 1);
            }

            @Override
            public void leave(WorkspaceTarget target) {
            }

            @Override
            public boolean publishOperation(WorkspaceTarget target, long baseSequence, String operationId,
                                            List<WorkspacePatch<JsonElement>> operation) {
                return true;
            }

            @Override
            public boolean publishAwareness(WorkspaceTarget target, JsonObject awareness) {
                return true;
            }
        });
        List<Long> snapshots = new ArrayList<>();
        ReSyncWorkspaceClient.Listener listener = new ReSyncWorkspaceClient.Listener() {
            @Override
            public void onSnapshot(ReSyncWorkspaceClient.Snapshot snapshot) {
                snapshots.add(snapshot.sequence());
                client.leave("flow", "main", this);
            }

            @Override
            public void onOperation(ReSyncWorkspaceClient.Operation operation, boolean own) {
            }

            @Override
            public void onAwareness(ReSyncWorkspaceClient.Awareness awareness) {
            }

            @Override
            public void onResync(String reason) {
            }
        };
        client.connect();

        assertTrue(client.join("flow", "main", listener));
        assertTrue(client.join("flow", "main", listener));

        assertEquals(2, joined.size());
        assertEquals(List.of(0L, 0L), snapshots);
    }

    @Test
    void dropsPacketsFromAnEarlierConnectionGeneration() {
        ReSyncWorkspaceClient client = new ReSyncWorkspaceClient(new Gson());
        ReSyncWorkspaceClient.WorkspaceSource source = client.bind(null);
        RecordingListener listener = new RecordingListener();
        client.join("flow", "main", listener);
        assertFalse(client.disconnect("Cleanup", 0));
        assertFalse(client.connect(0));
        assertTrue(client.connect(1));
        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":4,"document":{"version":"first"},"awareness":[]}
            """, 1);
        client.disconnect("Disconnected");
        assertFalse(client.connect(1));
        assertTrue(client.connect(2));

        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":10,"document":{"version":"stale"},"awareness":[]}
            """, 1);
        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":0,"document":{"version":"current"},"awareness":[]}
            """, 2);
        assertTrue(client.connect(3));
        assertFalse(client.disconnect("Stale Cleanup", 2));
        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":20,"document":{"version":"superseded stale"},"awareness":[]}
            """, 2);
        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":0,"document":{"version":"superseded current"},"awareness":[]}
            """, 3);

        assertEquals(0L, client.sequence("flow", "main"));
        assertEquals(List.of("first", "current", "superseded current"), listener.snapshotVersions);
    }

    @Test
    void lifecycleAndInboundRemainAvailableWhilePublicationIsBlocked() {
        ReSyncWorkspaceClient client = new ReSyncWorkspaceClient(new Gson());
        boolean[] publicationAllowed = {false};
        client.bindMutationAdmission(() -> publicationAllowed[0]);
        ReSyncWorkspaceClient.WorkspaceSource source = client.bind(null);
        RecordingListener listener = new RecordingListener();

        assertTrue(client.connect(1));
        assertTrue(client.join("flow", "main", listener));
        source.applySnapshot("""
            {"type":"flow","resourceId":"main","sequence":0,"document":{"nodes":{}},"awareness":[]}
            """, 1);
        assertEquals(List.of(0L), listener.snapshotSequences);
        assertNull(client.publishOperation("flow", "main", List.of()));
        assertFalse(client.publishAwareness("flow", "main", new JsonObject()));
        assertTrue(client.leave("flow", "main", listener));
        assertTrue(client.disconnect("Cleanup"));
    }

    private static final class RecordingListener implements ReSyncWorkspaceClient.Listener {
        private final List<ReSyncWorkspaceClient.Operation> operations = new ArrayList<>();
        private final List<Boolean> ownOperations = new ArrayList<>();
        private final List<String> resyncReasons = new ArrayList<>();
        private final List<String> snapshotAwareness = new ArrayList<>();
        private final List<String> awareness = new ArrayList<>();
        private final List<String> snapshotVersions = new ArrayList<>();
        private final List<Long> snapshotSequences = new ArrayList<>();

        @Override
        public void onSnapshot(ReSyncWorkspaceClient.Snapshot snapshot) {
            snapshotSequences.add(snapshot.sequence());
            snapshot.awareness().stream().map(ReSyncWorkspaceClient.Awareness::authorSessionId).forEach(snapshotAwareness::add);
            if (snapshot.document().has("version")) {
                snapshotVersions.add(snapshot.document().get("version").getAsString());
            }
        }

        @Override
        public void onOperation(ReSyncWorkspaceClient.Operation operation, boolean own) {
            operations.add(operation);
            ownOperations.add(own);
        }

        @Override
        public void onAwareness(ReSyncWorkspaceClient.Awareness awareness) {
            this.awareness.add(awareness.authorSessionId());
        }

        @Override
        public void onResync(String reason) {
            resyncReasons.add(reason);
        }
    }
}
