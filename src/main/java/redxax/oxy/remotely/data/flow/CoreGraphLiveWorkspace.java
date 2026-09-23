package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.protocol.ProtocolEditability;
import restudio.resync.flow.workspace.CoreWorkspaceDocument;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.flow.workspace.LiveDocumentChannel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class CoreGraphLiveWorkspace implements ReSyncWorkspaceClient.Listener {
    private final ReSyncFlowClient client;
    private final CoreGraphEditorSession session;
    private final String type;
    private final String resourceId;
    private final CoreWorkspaceDocument initial;
    private final ArrayDeque<Input> inputs = new ArrayDeque<>();
    private volatile boolean closed;
    private Prepared prepared;
    private boolean awaitingSnapshot;
    private int retries;
    private long retryAt;
    private final Consumer<ReSyncWorkspaceClient.Awareness> awareness;
    private final Consumer<List<ReSyncWorkspaceClient.Awareness>> awarenessReplacement;
    private final Object awarenessGate = new Object();
    private volatile long inputGeneration;
    private CoreWorkspaceDocument confirmed;
    private long sequence = -1;
    private LiveDocumentChannel.PublicationStamp stamp;
    private String pending = "";
    private CoreWorkspaceDocument sent;
    private CoreGraphUiProjection.EditorSnapshot observed;
    private String conflict = "";

    public CoreGraphLiveWorkspace(ReSyncFlowClient client, CoreGraphEditorSession session, Consumer<ReSyncWorkspaceClient.Awareness> awareness) {
        this(client, session, awareness, ignored -> { });
    }

    public CoreGraphLiveWorkspace(ReSyncFlowClient client, CoreGraphEditorSession session,
                                  Consumer<ReSyncWorkspaceClient.Awareness> awareness,
                                  Consumer<List<ReSyncWorkspaceClient.Awareness>> awarenessReplacement) {
        this.client = client;
        this.awareness = awareness;
        this.awarenessReplacement = awarenessReplacement;
        this.session = session;
        this.type = session.resource().resourceType().value();
        this.resourceId = session.resource().id();
        this.initial = new CoreWorkspaceDocument(session.baselineGraphDocument(), session.baselineFunctionSourceDocument());
    }

    public CoreGraphEditorSession session() {
        return session;
    }

    public ReSyncFlowClient client() {
        return client;
    }

    public String type() {
        return type;
    }

    public String resourceId() {
        return resourceId;
    }

    public void join() {
        synchronized (this) {
            if (closed) {
                return;
            }
        }
        client.joinWorkspace(type, resourceId, this);
        boolean retire;
        synchronized (this) {
            retire = closed;
            if (!retire && inputs.stream().noneMatch(input -> input.snapshot() != null)) {
                awaitingSnapshot = true;
                retryAt = System.currentTimeMillis() + 3000L;
            }
        }
        if (retire) {
            client.leaveWorkspace(type, resourceId, this);
        }
    }

    public void close() {
        Prepared abandoned;
        String operation;
        synchronized (awarenessGate) {
            synchronized (this) {
                abandoned = prepared;
                operation = pending;
                pending = "";
                closed = true;
                inputs.clear();
                prepared = null;
                inputGeneration++;
            }
        }
        if (abandoned != null) {
            discard(abandoned);
        }
        if (!operation.isEmpty()) {
            client.workspaces().discard(operation);
        }
        client.leaveWorkspace(type, resourceId, this);
    }

    @Override
    public void onSnapshot(ReSyncWorkspaceClient.Snapshot snapshot) {
        synchronized (awarenessGate) {
            if (closed) {
                return;
            }
            offer(new Input(snapshot, null, ""), true);
            awarenessReplacement.accept(snapshot.awareness() != null ? List.copyOf(snapshot.awareness()) : List.of());
        }
    }

    @Override
    public void onOperation(ReSyncWorkspaceClient.Operation operation, boolean own) {
        offer(new Input(null, operation, ""), false);
    }

    @Override
    public void onAwareness(ReSyncWorkspaceClient.Awareness value) {
        synchronized (awarenessGate) {
            if (!closed) {
                awareness.accept(value);
            }
        }
    }

    @Override
    public void onResync(String reason) {
        synchronized (awarenessGate) {
            if (closed) {
                return;
            }
            offer(new Input(null, null, reason == null ? "Workspace Changed" : reason), true);
            awarenessReplacement.accept(List.of());
        }
    }

    private synchronized void offer(Input input, boolean reset) {
        if (closed) {
            return;
        }
        if (reset || inputs.size() >= 128) {
            inputs.clear();
            inputGeneration++;
            if (!reset) {
                inputs.add(new Input(null, null, "Workspace Queue Full"));
                return;
            }
        }
        inputs.add(input);
        if (input.snapshot() != null) {
            awaitingSnapshot = false;
            retryAt = 0L;
        }
    }

    public synchronized Request capture(CoreGraphUiProjection.EditorSnapshot snapshot) {
        if (closed || !conflict.isEmpty() || System.currentTimeMillis() < retryAt) {
            return null;
        }
        Input input = inputs.peek();
        if (input == null && awaitingSnapshot) {
            input = new Input(null, null, "Workspace Snapshot Timed Out");
            inputs.add(input);
        }
        if (input == null && (confirmed == null || !pending.isEmpty()
            || observed != null && observed.currentFor(session))) {
            return null;
        }
        CoreWorkspaceDocument base = confirmed != null ? confirmed : initial;
        boolean authorityChanged = base.document().revision() != snapshot.document().revision()
            || !base.document().catalogBinding().equals(snapshot.document().catalogBinding());
        if (authorityChanged) {
            if (input == null || input.snapshot() == null) {
                if (input == null || input.reason().isEmpty()) {
                    inputs.clear();
                    inputGeneration++;
                    input = new Input(null, null, "Resource Updated");
                    inputs.add(input);
                }
                return new Request(snapshot, base, input, inputGeneration, sequence, stamp, pending, sent);
            }
            base = new CoreWorkspaceDocument(session.baselineGraphDocument(), session.baselineFunctionSourceDocument());
        }
        return new Request(snapshot, base, input, inputGeneration, sequence, stamp, pending, authorityChanged ? null : sent);
    }

    public void prepareNext(Request request) {
        Prepared result = prepare(request);
        boolean retire;
        synchronized (this) {
            retire = closed;
            if (!retire) {
                prepared = result;
            }
        }
        if (retire) {
            discard(result);
        }
    }

    public synchronized Prepared takePrepared() {
        Prepared result = prepared;
        prepared = null;
        return result;
    }

    public void discard(Prepared result) {
        if (result.request().input() == null && result.operationId() != null && !result.operationId().isEmpty()) {
            synchronized (this) {
                if (!closed) {
                    pending = result.operationId();
                    sent = document(result.request().snapshot());
                    observed = null;
                }
            }
            client.workspaces().discard(result.operationId());
        }
    }

    public Prepared prepare(Request request) {
        try {
            Input input = request.input();
            if (input != null && !input.reason().isEmpty()) {
                if (retries >= 3) {
                    throw new IllegalStateException(input.reason() + ". Reopen the resource to retry collaboration");
                }
                if (client.isConnectedState()) {
                    client.resyncWorkspace(type, resourceId);
                }
                return new Prepared(request, null, null, null, -1, "", "");
            }
            CoreWorkspaceDocument current = document(request.snapshot());
            if (input == null) {
                List<WorkspacePatch<JsonElement>> patches = request.base().diff(current).stream()
                    .map(patch -> new WorkspacePatch<JsonElement>(patch.op(), patch.path(),
                        patch.value() != null ? gsonValue(patch.value()) : null)).toList();
                String operationId = "";
                if (!patches.isEmpty()) {
                    synchronized (this) {
                        if (closed || inputGeneration != request.generation()) {
                            return new Prepared(request, null, null, null, request.sequence(), null, "");
                        }
                    }
                    operationId = client.workspaces().publishOperation(type, resourceId, request.stamp(), patches,
                        () -> !closed && inputGeneration == request.generation());
                    if (operationId == null || operationId.isEmpty()) {
                        operationId = null;
                    }
                }
                return new Prepared(request, null, null, null, request.sequence(), operationId, "");
            }
            long nextSequence;
            CoreWorkspaceDocument latest;
            if (input.snapshot() != null) {
                if (input.snapshot().editability() != ProtocolEditability.EDITABLE) {
                    throw new IllegalStateException("This workspace is read only. Reconnect after updating ReSync");
                }
                latest = CoreWorkspaceDocument.decode(JsonValue.parse(input.snapshot().document().toString()));
                nextSequence = input.snapshot().sequence();
            } else {
                if (input.operation().sequence() != request.sequence() + 1L) {
                    throw new IllegalStateException("Workspace sequence changed. Reopen the resource to reconnect");
                }
                latest = request.base().apply(input.operation().patches().stream()
                    .map(patch -> new WorkspacePatch<>(patch.op(), patch.path(),
                        patch.value() != null ? JsonValue.parse(patch.value().toString()) : null)).toList());
                nextSequence = input.operation().sequence();
            }
            if (latest.document().revision() != current.document().revision()) {
                return new Prepared(request, null, null, latest, nextSequence, "", "");
            }
            CoreWorkspaceDocument mergeBase = request.base();
            CoreWorkspaceDocument mergeTarget = latest;
            if (request.sent() != null && input.snapshot() != null) {
                mergeTarget = request.base().rebase(request.sent(), latest);
                mergeBase = request.sent();
            } else if (request.sent() != null && input.operation() != null
                && request.pending().equals(input.operation().operationId())) {
                mergeBase = request.sent();
            }
            CoreGraphEditorSession.WorkspaceEdit edit = CoreGraphEditorSession.prepareWorkspaceEdit(current, mergeBase, mergeTarget);
            CoreWorkspaceDocument desired = edit.desired();
            CoreGraphUiProjection.ProjectionResult projection = edit.changed()
                ? new CoreGraphUiProjection().projectEditorSnapshot(new CoreGraphUiProjection.EditorSnapshot(session,
                    desired.graph(), desired.source())) : null;
            return new Prepared(request, edit, projection, latest, nextSequence, "", "");
        } catch (RuntimeException exception) {
            return new Prepared(request, null, null, null, request.sequence(), "",
                exception.getMessage() != null ? exception.getMessage() : "Workspace update failed");
        }
    }

    static JsonElement gsonValue(JsonValue value) {
        if (value instanceof JsonValue.JsonNull) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof JsonValue.JsonBoolean booleanValue) {
            return new JsonPrimitive(booleanValue.value());
        }
        if (value instanceof JsonValue.JsonNumber number) {
            return JsonParser.parseString(number.canonicalText());
        }
        if (value instanceof JsonValue.JsonString string) {
            return new JsonPrimitive(string.value());
        }
        if (value instanceof JsonValue.JsonArray array) {
            JsonArray converted = new JsonArray();
            for (JsonValue element : array.values()) {
                converted.add(gsonValue(element));
            }
            return converted;
        }
        if (value instanceof JsonValue.JsonObject object) {
            JsonObject converted = new JsonObject();
            List<String> keys = new ArrayList<>(object.values().keySet());
            keys.sort(CanonicalJson::compareCodePoints);
            for (String key : keys) {
                converted.add(key, gsonValue(object.value(key)));
            }
            return converted;
        }
        throw new IllegalArgumentException("Unsupported canonical JSON value");
    }

    public synchronized boolean current(Prepared result) {
        return !closed && result.request().generation() == inputGeneration
            && (result.request().input() == null || inputs.peek() == result.request().input());
    }

    public synchronized void defer(Prepared result) {
        if (current(result)) {
            retryAt = System.currentTimeMillis() + 250L;
        }
    }

    public void accept(Prepared result) {
        String retired = "";
        synchronized (this) {
            if (!current(result)) {
                return;
            }
            if (!result.failure().isEmpty()) {
                conflict = result.failure();
                return;
            }
            Input input = result.request().input();
            if (input != null) {
                inputs.removeFirst();
                if (!input.reason().isEmpty()) {
                    observed = null;
                    retries++;
                    awaitingSnapshot = true;
                    retryAt = System.currentTimeMillis() + 3000L;
                    return;
                }
                confirmed = result.latest();
                retries = 0;
                retryAt = 0L;
                sequence = result.sequence();
                stamp = input.snapshot() != null ? input.snapshot().stamp() : input.operation().stamp();
                if (input.snapshot() != null || input.operation() != null && pending.equals(input.operation().operationId())) {
                    retired = pending;
                    pending = "";
                    sent = null;
                }
                observed = null;
            } else {
                pending = result.operationId() != null ? result.operationId() : "";
                sent = pending.isEmpty() ? null : document(result.request().snapshot());
                observed = result.operationId() != null ? result.request().snapshot() : null;
                if (result.operationId() == null) {
                    retryAt = System.currentTimeMillis() + 250L;
                }
            }
        }
        if (!retired.isEmpty()) {
            client.workspaces().discard(retired);
        }
    }

    private static CoreWorkspaceDocument document(CoreGraphUiProjection.EditorSnapshot snapshot) {
        return new CoreWorkspaceDocument(snapshot.graphDocument(), snapshot.functionSourceDocument());
    }

    public record Input(ReSyncWorkspaceClient.Snapshot snapshot, ReSyncWorkspaceClient.Operation operation, String reason) {
    }

    public record Request(CoreGraphUiProjection.EditorSnapshot snapshot, CoreWorkspaceDocument base, Input input,
                          long generation, long sequence, LiveDocumentChannel.PublicationStamp stamp,
                          String pending, CoreWorkspaceDocument sent) {
    }

    public record Prepared(Request request, CoreGraphEditorSession.WorkspaceEdit edit,
                           CoreGraphUiProjection.ProjectionResult projection, CoreWorkspaceDocument latest,
                           long sequence, String operationId, String failure) {
    }
}
