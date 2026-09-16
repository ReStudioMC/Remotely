package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class CoreGraphWorkspacePendingOperations {
    private static final int MAX_PENDING_OPERATIONS = 512;

    private final LinkedHashMap<String, PendingOperation> pending = new LinkedHashMap<>();

    public CoreGraphWorkspacePendingOperations() {
    }

    public CoreGraphWorkspacePendingOperations(List<PendingOperation> recovered) {
        restore(recovered);
    }

    public synchronized PendingOperation enqueue(String operationId, long baseSequence,
                                                  GraphDocument base, List<WorkspacePatch<JsonValue>> patches) {
        Objects.requireNonNull(base, "Base graph is required");
        Objects.requireNonNull(patches, "Patches are required");
        requireOperationId(operationId);
        if (pending.containsKey(operationId)) {
            throw new IllegalArgumentException("Duplicate pending operation: " + operationId);
        }
        if (pending.size() >= MAX_PENDING_OPERATIONS) {
            throw new IllegalStateException("Pending workspace operation limit reached");
        }
        List<WorkspacePatch<JsonValue>> checked = List.copyOf(patches);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("Pending workspace operation requires at least one patch");
        }
        PendingOperation tail = pending.isEmpty() ? null : pending.lastEntry().getValue();
        if (tail != null) {
            long expectedSequence = Math.addExact(tail.baseSequence(), 1L);
            if (baseSequence != expectedSequence || !tail.desired().canonicalJson().equals(base.canonicalJson())) {
                throw new IllegalArgumentException("Pending workspace operations must form one contiguous causal chain");
            }
        }
        GraphDocument desired = CoreGraphWorkspacePatch.apply(base, checked);
        PendingOperation operation = new PendingOperation(operationId, baseSequence, base, checked, desired);
        pending.put(operationId, operation);
        return operation;
    }

    public synchronized List<PendingOperation> reconnect() {
        return List.copyOf(pending.values());
    }

    public synchronized List<PendingOperation> rebase(long latestSequence, GraphDocument latest) {
        Objects.requireNonNull(latest, "Latest graph is required");
        if (latestSequence < 0) {
            throw new IllegalArgumentException("Latest workspace sequence cannot be negative");
        }
        if (pending.isEmpty()) {
            return List.of();
        }
        GraphDocument cursor = latest;
        ArrayList<PendingOperation> rebased = new ArrayList<>(pending.size());
        for (PendingOperation operation : pending.values()) {
            GraphDocument desired = CoreGraphWorkspacePatch.rebase(operation.base(), operation.desired(), cursor);
            List<WorkspacePatch<JsonValue>> patches = CoreGraphWorkspacePatch.diff(cursor, desired);
            if (patches.isEmpty()) {
                cursor = desired;
                continue;
            }
            long baseSequence = Math.addExact(latestSequence, rebased.size());
            rebased.add(new PendingOperation(operation.operationId(), baseSequence, cursor, patches, desired));
            cursor = desired;
        }
        pending.clear();
        rebased.forEach(operation -> pending.put(operation.operationId(), operation));
        return List.copyOf(rebased);
    }

    public synchronized boolean acknowledge(String operationId) {
        requireOperationId(operationId);
        if (!pending.containsKey(operationId)) {
            return false;
        }
        if (!pending.firstEntry().getKey().equals(operationId)) {
            throw new IllegalStateException("Pending workspace operations must be acknowledged in order");
        }
        pending.remove(operationId);
        return true;
    }

    public synchronized boolean discard(String operationId) {
        requireOperationId(operationId);
        if (!pending.containsKey(operationId)) {
            return false;
        }
        if (!pending.lastEntry().getKey().equals(operationId)) {
            throw new IllegalStateException("Only the last pending workspace operation can be discarded");
        }
        pending.remove(operationId);
        return true;
    }

    public synchronized List<PendingOperation> pending() {
        return List.copyOf(pending.values());
    }

    public synchronized void restore(List<PendingOperation> recovered) {
        Objects.requireNonNull(recovered, "Recovered pending operations are required");
        if (!pending.isEmpty()) {
            throw new IllegalStateException("Pending workspace operations are already initialized");
        }
        if (recovered.size() > MAX_PENDING_OPERATIONS) {
            throw new IllegalArgumentException("Recovered pending workspace operation limit exceeded");
        }
        LinkedHashMap<String, PendingOperation> checkedOperations = new LinkedHashMap<>();
        PendingOperation previous = null;
        for (PendingOperation operation : recovered) {
            PendingOperation checked = Objects.requireNonNull(operation, "Recovered pending operation is required");
            if (checkedOperations.put(checked.operationId(), checked) != null) {
                throw new IllegalArgumentException("Duplicate recovered pending operation: " + checked.operationId());
            }
            if (previous != null) {
                long expectedSequence = Math.addExact(previous.baseSequence(), 1L);
                if (checked.baseSequence() != expectedSequence
                    || !previous.desired().canonicalJson().equals(checked.base().canonicalJson())) {
                    throw new IllegalArgumentException("Recovered pending workspace operations do not form one causal chain");
                }
            }
            previous = checked;
        }
        pending.putAll(checkedOperations);
    }

    private static void requireOperationId(String operationId) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 128 || !operationId.equals(operationId.trim())) {
            throw new IllegalArgumentException("Pending operation ID is required");
        }
    }

    public record PendingOperation(String operationId, long baseSequence, GraphDocument base,
                                   List<WorkspacePatch<JsonValue>> patches, GraphDocument desired) {
        public PendingOperation {
            requireOperationId(operationId);
            if (baseSequence < 0) {
                throw new IllegalArgumentException("Pending operation base sequence cannot be negative");
            }
            base = Objects.requireNonNull(base, "Pending operation base graph is required");
            patches = List.copyOf(Objects.requireNonNull(patches, "Pending operation patches are required"));
            if (patches.isEmpty()) {
                throw new IllegalArgumentException("Pending operation requires at least one patch");
            }
            desired = Objects.requireNonNull(desired, "Pending operation desired graph is required");
            GraphDocument verified = CoreGraphWorkspacePatch.apply(base, patches);
            if (!verified.canonicalJson().equals(desired.canonicalJson())) {
                throw new IllegalArgumentException("Pending operation desired graph does not match its patch batch");
            }
        }
    }
}
