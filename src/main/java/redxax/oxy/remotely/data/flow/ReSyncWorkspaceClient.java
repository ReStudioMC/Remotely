package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.collaboration.CollaborationService;
import restudio.resync.flow.workspace.LiveDocumentChannel;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.flow.workspace.WorkspaceTarget;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ReSyncWorkspaceClient {
    private final Gson gson;
    private final CollaborationService collaboration;
    private final LiveDocumentChannel<JsonObject, List<WorkspacePatch<JsonElement>>, JsonObject, CollaborationService.Identity> documents =
        new LiveDocumentChannel<>();
    private final Map<Listener, AdapterRegistration> adapters = new HashMap<>();
    private final Map<Listener, Set<String>> listenerTargets = new HashMap<>();
    private long registrationEpoch;
    private int connectionGeneration = Integer.MIN_VALUE;
    private int highestGeneration = Integer.MIN_VALUE;
    private int fallbackGeneration;
    private BooleanSupplier lifecycleAdmission = () -> true;
    private BooleanSupplier mutationAdmission = () -> true;

    public ReSyncWorkspaceClient(Gson gson) {
        this(gson, null);
    }

    public ReSyncWorkspaceClient(Gson gson, CollaborationService collaboration) {
        this.gson = gson;
        this.collaboration = collaboration;
    }

    void bindLifecycleAdmission(BooleanSupplier lifecycleAdmission) {
        this.lifecycleAdmission = Objects.requireNonNull(lifecycleAdmission, "Lifecycle admission is required");
    }

    void bindMutationAdmission(BooleanSupplier mutationAdmission) {
        this.mutationAdmission = Objects.requireNonNull(mutationAdmission, "Mutation admission is required");
    }

    public WorkspaceSource bind(LiveDocumentChannel.Transport<List<WorkspacePatch<JsonElement>>, JsonObject> transport) {
        return bind(transport, Runnable::run);
    }

    public WorkspaceSource bind(LiveDocumentChannel.Transport<List<WorkspacePatch<JsonElement>>, JsonObject> transport,
                                Consumer<Runnable> callbackDispatcher) {
        if (!lifecycleAllowed()) throw new IllegalStateException("Workspace client is shut down");
        synchronized (documents) {
            return new WorkspaceSource(documents.bind(transport, callbackDispatcher));
        }
    }

    public boolean connect() {
        if (!lifecycleAllowed()) return false;
        synchronized (documents) {
            fallbackGeneration = Math.max(fallbackGeneration, highestGeneration) + 1;
            return connect(fallbackGeneration);
        }
    }

    public boolean connect(int generation) {
        if (!lifecycleAllowed()) return false;
        synchronized (documents) {
            if (generation <= highestGeneration) {
                return false;
            }
            highestGeneration = generation;
            fallbackGeneration = Math.max(fallbackGeneration, generation);
            connectionGeneration = generation;
            if (documents.connect(generation)) {
                return true;
            }
            connectionGeneration = Integer.MIN_VALUE;
            return false;
        }
    }

    public boolean disconnect(String reason) {
        synchronized (documents) {
            int generation = connectionGeneration != Integer.MIN_VALUE ? connectionGeneration : highestGeneration;
            return disconnect(reason, generation);
        }
    }

    public boolean disconnect(String reason, int generation) {
        synchronized (documents) {
            if (generation < highestGeneration) {
                return false;
            }
            highestGeneration = generation;
            fallbackGeneration = Math.max(fallbackGeneration, generation);
            connectionGeneration = Integer.MIN_VALUE;
            return documents.disconnect(reason);
        }
    }

    public boolean join(String type, String resourceId, Listener listener) {
        if (!lifecycleAllowed()) return false;
        if (listener == null) {
            return false;
        }
        WorkspaceTarget target = target(type, resourceId);
        synchronized (documents) {
            Set<String> targets = listenerTargets.computeIfAbsent(listener, ignored -> new HashSet<>());
            if (!targets.add(target.key())) {
                return false;
            }
            AdapterRegistration registration = adapters.computeIfAbsent(listener,
                ignored -> new AdapterRegistration(listener, ++registrationEpoch));
            return documents.join(target, registration.adapter);
        }
    }

    public boolean leave(String type, String resourceId, Listener listener) {
        if (!lifecycleAllowed()) return false;
        if (listener == null) {
            return false;
        }
        WorkspaceTarget target = target(type, resourceId);
        synchronized (documents) {
            AdapterRegistration registration = adapters.get(listener);
            Set<String> targets = listenerTargets.get(listener);
            if (registration == null || targets == null || !targets.remove(target.key())) {
                return false;
            }
            if (targets.isEmpty()) {
                listenerTargets.remove(listener, targets);
                adapters.remove(listener, registration);
            }
            return documents.leave(target, registration.adapter);
        }
    }

    public void sent(String type, String resourceId, String operationId) {
        if (!lifecycleAllowed()) return;
        documents.sent(target(type, resourceId), operationId);
    }

    public void discard(String operationId) {
        if (!lifecycleAllowed()) return;
        documents.discard(operationId);
    }

    public long sequence(String type, String resourceId) {
        return documents.sequence(target(type, resourceId));
    }

    public String publishOperation(String type, String resourceId, List<WorkspacePatch<JsonElement>> patches) {
        if (!mutationAllowed()) return null;
        return patches == null || patches.isEmpty()
            ? "" : documents.publishOperation(target(type, resourceId), patches);
    }

    public boolean publishAwareness(String type, String resourceId, JsonObject state) {
        if (!mutationAllowed()) return false;
        return documents.publishAwareness(target(type, resourceId), state != null ? state : new JsonObject());
    }

    private void applySnapshot(String json, int generation, long sourceEpoch) {
        if (!lifecycleAllowed()) return;
        synchronized (documents) {
            if (generation != connectionGeneration) {
                return;
            }
            applyCurrentSnapshot(json, generation, sourceEpoch);
        }
    }

    private void applyCurrentSnapshot(String json, int generation, long sourceEpoch) {
        Snapshot snapshot = parse(json, Snapshot.class);
        if (snapshot == null || snapshot.document() == null) {
            return;
        }
        List<LiveDocumentChannel.Awareness<JsonObject, CollaborationService.Identity>> awareness = snapshot.awareness() == null
            ? List.of() : snapshot.awareness().stream().filter(value -> !isOwn(value.authorSessionId())).map(this::toGeneric).toList();
        documents.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(
            target(snapshot.type(), snapshot.resourceId()), snapshot.sequence(), snapshot.document(), awareness), generation, sourceEpoch);
    }

    private void applyOperation(String json, int generation, long sourceEpoch) {
        if (!lifecycleAllowed()) return;
        synchronized (documents) {
            if (generation != connectionGeneration) {
                return;
            }
            applyCurrentOperation(json, generation, sourceEpoch);
        }
    }

    private void applyCurrentOperation(String json, int generation, long sourceEpoch) {
        Operation operation = parse(json, Operation.class);
        if (operation == null || operation.patches() == null) {
            return;
        }
        documents.acceptOperation(new LiveDocumentChannel.Operation<>(
            target(operation.type(), operation.resourceId()), operation.sequence(), operation.operationId(),
            operation.authorSessionId(), operation.author(), operation.patches()), generation, sourceEpoch);
    }

    private void applyAwareness(String json, int generation, long sourceEpoch) {
        if (!lifecycleAllowed()) return;
        synchronized (documents) {
            if (generation != connectionGeneration) {
                return;
            }
            applyCurrentAwareness(json, generation, sourceEpoch);
        }
    }

    private void applyCurrentAwareness(String json, int generation, long sourceEpoch) {
        Awareness awareness = parse(json, Awareness.class);
        if (awareness != null && !isOwn(awareness.authorSessionId())) {
            documents.acceptAwareness(toGeneric(awareness), generation, sourceEpoch);
        }
    }

    private void applyResync(String json, int generation, long sourceEpoch) {
        if (!lifecycleAllowed()) return;
        synchronized (documents) {
            if (generation != connectionGeneration) {
                return;
            }
            applyCurrentResync(json, generation, sourceEpoch);
        }
    }

    private void applyCurrentResync(String json, int generation, long sourceEpoch) {
        Resync resync = parse(json, Resync.class);
        if (resync != null) {
            documents.acceptResync(target(resync.type(), resync.resourceId()), resync.reason(), generation, sourceEpoch);
        }
    }

    public void clear() {
        disconnect("Disconnected");
    }

    private boolean lifecycleAllowed() {
        return lifecycleAdmission.getAsBoolean();
    }

    private boolean mutationAllowed() {
        return mutationAdmission.getAsBoolean();
    }

    private LiveDocumentChannel.Listener<JsonObject, List<WorkspacePatch<JsonElement>>, JsonObject, CollaborationService.Identity> adapt(
        AdapterRegistration registration) {
        return new LiveDocumentChannel.Listener<>() {
            @Override
            public void onSnapshot(LiveDocumentChannel.Snapshot<JsonObject, JsonObject, CollaborationService.Identity> snapshot) {
                if (active(registration, snapshot.target())) {
                    registration.listener.onSnapshot(new Snapshot(snapshot.target().resourceType(), snapshot.target().resourceId(),
                        snapshot.sequence(), snapshot.document(),
                        snapshot.awareness().stream().map(ReSyncWorkspaceClient.this::fromGeneric).toList()));
                }
            }

            @Override
            public void onOperation(LiveDocumentChannel.Operation<List<WorkspacePatch<JsonElement>>, CollaborationService.Identity> operation,
                                    boolean own) {
                if (active(registration, operation.target())) {
                    registration.listener.onOperation(new Operation(operation.target().resourceType(), operation.target().resourceId(),
                        operation.sequence(), operation.operationId(), operation.authorSessionId(), operation.author(),
                        operation.operation()), own || isOwn(operation.authorSessionId()));
                }
            }

            @Override
            public void onAwareness(LiveDocumentChannel.Awareness<JsonObject, CollaborationService.Identity> awareness) {
                if (active(registration, awareness.target())) {
                    registration.listener.onAwareness(fromGeneric(awareness));
                }
            }

            @Override
            public void onResync(String reason) {
                if (active(registration)) {
                    registration.listener.onResync(reason);
                }
            }
        };
    }

    private boolean active(AdapterRegistration registration, WorkspaceTarget target) {
        synchronized (documents) {
            AdapterRegistration current = adapters.get(registration.listener);
            Set<String> targets = listenerTargets.get(registration.listener);
            return current != null && current.epoch == registration.epoch && targets != null && targets.contains(target.key());
        }
    }

    private boolean active(AdapterRegistration registration) {
        synchronized (documents) {
            AdapterRegistration current = adapters.get(registration.listener);
            return current != null && current.epoch == registration.epoch;
        }
    }

    private boolean isOwn(String sessionId) {
        return collaboration != null && collaboration.isOwnSession(sessionId);
    }

    private LiveDocumentChannel.Awareness<JsonObject, CollaborationService.Identity> toGeneric(Awareness awareness) {
        return new LiveDocumentChannel.Awareness<>(target(awareness.type(), awareness.resourceId()),
            awareness.authorSessionId(), awareness.author(), awareness.state(), awareness.updatedAt());
    }

    private Awareness fromGeneric(LiveDocumentChannel.Awareness<JsonObject, CollaborationService.Identity> awareness) {
        return new Awareness(awareness.target().resourceType(), awareness.target().resourceId(),
            awareness.authorSessionId(), awareness.author(), awareness.state(), awareness.updatedAt());
    }

    private WorkspaceTarget target(String type, String resourceId) {
        return new WorkspaceTarget(type, resourceId);
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return gson.fromJson(json, type);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public final class WorkspaceSource {
        private final long epoch;

        private WorkspaceSource(long epoch) {
            this.epoch = epoch;
        }

        public void applySnapshot(String json, int generation) {
            ReSyncWorkspaceClient.this.applySnapshot(json, generation, epoch);
        }

        public void applyOperation(String json, int generation) {
            ReSyncWorkspaceClient.this.applyOperation(json, generation, epoch);
        }

        public void applyAwareness(String json, int generation) {
            ReSyncWorkspaceClient.this.applyAwareness(json, generation, epoch);
        }

        public void applyResync(String json, int generation) {
            ReSyncWorkspaceClient.this.applyResync(json, generation, epoch);
        }
    }

    private final class AdapterRegistration {
        private final Listener listener;
        private final long epoch;
        private final LiveDocumentChannel.Listener<JsonObject, List<WorkspacePatch<JsonElement>>, JsonObject, CollaborationService.Identity> adapter;

        private AdapterRegistration(Listener listener, long epoch) {
            this.listener = listener;
            this.epoch = epoch;
            adapter = adapt(this);
        }
    }

    public interface Listener {
        void onSnapshot(Snapshot snapshot);

        void onOperation(Operation operation, boolean own);

        void onAwareness(Awareness awareness);

        void onResync(String reason);
    }

    public record Snapshot(String type, String resourceId, long sequence, JsonObject document, List<Awareness> awareness) {
    }

    public record Operation(String type, String resourceId, long sequence, String operationId, String authorSessionId,
                            ReSyncCollaborationClient.Identity author, List<WorkspacePatch<JsonElement>> patches) {
    }

    public record Awareness(String type, String resourceId, String authorSessionId,
                            ReSyncCollaborationClient.Identity author, JsonObject state, long updatedAt) {
    }

    private record Resync(String type, String resourceId, String reason) {
    }
}
