package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class VersionedEditorDraft<T> implements AutoCloseable {
    private static final int DEFERRED_LIMIT = 512;
    private static final int PROJECTION_LIMIT = 16;
    private static final int CHECKPOINT_LIMIT = 16;
    private static final int REBASE_RETRY_LIMIT = 3;
    private static final int REPLAY_LIMIT = 24;
    private static final long REPLAY_NANOS = ((2L) * 1_000_000L);
    private static final BrowserWork.Executor SNAPSHOTS = BrowserWork.executor();
    private static final BrowserWork.Executor BACKGROUND = BrowserWork.executor();

    public record TypedKey(String type, String id) {
        public TypedKey {
            type = Objects.requireNonNull(type);
            id = Objects.requireNonNull(id);
        }
    }

    public static final class SaveSnapshot {
        private final VersionedEditorDraft<?> owner;
        private final TypedKey key;
        private final long version;
        private final long editVersion;
        private final String payload;
        private final Object worker;

        private SaveSnapshot(VersionedEditorDraft<?> owner, TypedKey key, long version, long editVersion, String payload) {
            this.owner = owner;
            this.key = key;
            this.version = version;
            this.editVersion = editVersion;
            this.payload = payload;
            this.worker = TaskIdentities.access.current();
        }

        public TypedKey key() {
            return key;
        }

        public long version() {
            return version;
        }

        public long editVersion() {
            return editVersion;
        }

        public boolean isCurrent() {
            return owner.latestSnapshotVersion.get() == version;
        }

        public boolean compareAndMarkSaved() {
            return owner.compareAndMarkSaved(version);
        }

        public <R> R serialize(Function<String, R> serializer) {
            if (TaskIdentities.access.current() != worker) {
                throw new IllegalStateException("Save Payload Is Worker-Owned");
            }
            return serializer.apply(payload);
        }
    }

    public enum Stage {
        SAVE,
        REBASE
    }

    public record Failure(TypedKey key, long version, Object request, Stage stage, RuntimeException cause) {
    }

    public record ProjectionSnapshot(TypedKey key, long version, long editVersion, String payload,
                                     RuntimeException failure) {
        public ProjectionSnapshot {
            key = Objects.requireNonNull(key);
            payload = payload != null ? payload : "";
        }

        public boolean successful() {
            return failure == null;
        }

        public boolean failed() {
            return failure != null;
        }
    }

    public record MutationResult(long beforeEditVersion, long afterEditVersion, RuntimeException failure) {
        public boolean successful() {
            return failure == null;
        }

        public boolean failed() {
            return failure != null;
        }
    }

    private record RebindContext(TypedKey key, long version, Object request, Stage stage) {
    }

    private record Prepared<T>(T replacement, Failure failure, PendingAck retry, PendingAck rebaseAttempt,
                               RebindContext context, boolean rebind) {
    }

    private record Checkpoint(TypedKey key, long version, long editVersion, String payload) {
    }

    private record PendingAck(Object request, Supplier<String> authoritative, int attempts) {
        private PendingAck nextAttempt() {
            return new PendingAck(request, authoritative, attempts + 1);
        }

        private PendingAck reset() {
            return new PendingAck(request, authoritative, 0);
        }
    }

    private static final class DeferredMutation {
        private final Runnable mutation;
        private final Consumer<MutationResult> completion;
        private final BrowserSafeState.BooleanValue settled = new BrowserSafeState.BooleanValue();

        private DeferredMutation(Runnable mutation, Consumer<MutationResult> completion) {
            this.mutation = mutation;
            this.completion = completion;
        }

        private boolean versioned() {
            return completion != null;
        }

        private void complete(MutationResult result) {
            if (!settled.compareAndSet(false, true) || completion == null) {
                return;
            }
            try {
                completion.accept(result);
            } catch (RuntimeException | Error ignored) {
            }
        }

        private void fail(long editVersion, RuntimeException failure) {
            complete(new MutationResult(editVersion, editVersion, failure));
        }
    }

    private static final class SnapshotJob<T> {
        private final T previous;
        private final TypedKey key;
        private final long version;
        private final long editVersion;
        private final Object request;
        private final Consumer<SaveSnapshot> saver;
        private final ProjectionRequest<T> projection;
        private final boolean rebind;
        private boolean cancelled;
        private boolean dispatched;

        private SnapshotJob(T previous, TypedKey key, long version, long editVersion, Object request,
                            Consumer<SaveSnapshot> saver) {
            this(previous, key, version, editVersion, request, saver, null, true);
        }

        private SnapshotJob(T previous, TypedKey key, long version, long editVersion, Object request,
                            Consumer<SaveSnapshot> saver, ProjectionRequest<T> projection, boolean rebind) {
            this.previous = previous;
            this.key = key;
            this.version = version;
            this.editVersion = editVersion;
            this.request = request;
            this.saver = saver;
            this.projection = projection;
            this.rebind = rebind;
        }

        private synchronized boolean claimDispatch() {
            if (cancelled) {
                return false;
            }
            dispatched = true;
            return true;
        }

        private synchronized boolean cancelBeforeDispatch() {
            if (dispatched) {
                return false;
            }
            cancelled = true;
            return true;
        }

        private synchronized boolean cancelled() {
            return cancelled;
        }
    }

    private static final class ProjectionRequest<T> {
        private final TypedKey key;
        private final Supplier<T> source;
        private final Consumer<ProjectionSnapshot> completion;
        private final BrowserSafeState.BooleanValue settled = new BrowserSafeState.BooleanValue();

        private ProjectionRequest(TypedKey key, Supplier<T> source, Consumer<ProjectionSnapshot> completion) {
            this.key = key;
            this.source = source;
            this.completion = completion;
        }

        private void complete(ProjectionSnapshot snapshot) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            if (completion == null) {
                return;
            }
            try {
                completion.accept(snapshot);
            } catch (RuntimeException | Error ignored) {
            }
        }

        private void fail(long version, long editVersion, RuntimeException failure) {
            complete(new ProjectionSnapshot(key, version, editVersion, "", failure));
        }
    }

    private final Function<T, String> serializer;
    private final Function<String, T> deserializer;
    private final BiConsumer<T, T> rebinder;
    private final Consumer<Failure> failureHandler;
    private final Runnable overflowHandler;
    private final Deque<DeferredMutation> deferred = new ArrayDeque<>();
    private final java.util.Queue<ProjectionRequest<T>> projections = BrowserSafeState.queue();
    private final Map<Object, Checkpoint> checkpoints = BrowserSafeState.map();
    private final BrowserSafeState.ReferenceValue<Prepared<T>> prepared = new BrowserSafeState.ReferenceValue<>();
    private final BrowserSafeState.LongValue latestSnapshotVersion = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue savedSnapshotVersion = new BrowserSafeState.LongValue();
    private SnapshotJob<T> inFlight;
    private BrowserSafeState.BooleanValue rebaseCancelled;
    private PendingAck pendingAck;
    private boolean rebaseBlocked;
    private T value;
    private Object renderThread;
    private long editVersion;
    private boolean frozen;
    private boolean replaying;
    private boolean overflowed;
    private boolean backpressured;
    private volatile boolean closed;

    public VersionedEditorDraft(T value, Function<T, String> serializer, Function<String, T> deserializer,
                                BiConsumer<T, T> rebinder, Consumer<Failure> failureHandler, Runnable overflowHandler) {
        this.value = Objects.requireNonNull(value);
        this.serializer = Objects.requireNonNull(serializer);
        this.deserializer = Objects.requireNonNull(deserializer);
        this.rebinder = Objects.requireNonNull(rebinder);
        this.failureHandler = Objects.requireNonNull(failureHandler);
        this.overflowHandler = Objects.requireNonNull(overflowHandler);
    }

    public static boolean submitBackground(Runnable task) {
        if (task == null) {
            return false;
        }
        try {
            BACKGROUND.execute(task);
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    public T value() {
        assertRenderThread();
        return value;
    }

    public long editVersion() {
        assertRenderThread();
        return editVersion;
    }

    public boolean isFrozen() {
        assertRenderThread();
        return frozen;
    }

    public boolean isSettled() {
        assertRenderThread();
        return !frozen && deferred.isEmpty() && prepared.get() == null && pendingAck == null;
    }

    public void markMutation() {
        assertRenderThread();
        if (!closed) {
            editVersion = nextEditVersion(editVersion);
        }
    }

    public boolean defer(Runnable mutation) {
        assertRenderThread();
        if (closed) {
            return true;
        }
        if (replaying || (!frozen && deferred.isEmpty())) {
            return false;
        }
        if (backpressured) {
            notifyOverflow();
            return true;
        }
        if (mutation == null) {
            return true;
        }
        if (deferred.size() < DEFERRED_LIMIT - 1) {
            deferred.addLast(new DeferredMutation(mutation, null));
            return true;
        }
        deferred.addLast(new DeferredMutation(mutation, null));
        enterBackpressure();
        return true;
    }

    public boolean runMutation(Runnable mutation, Consumer<MutationResult> completion) {
        assertRenderThread();
        DeferredMutation request = new DeferredMutation(mutation, completion);
        if (mutation == null || completion == null) {
            request.fail(editVersion, new IllegalArgumentException("Mutation And Completion Are Required"));
            return false;
        }
        if (closed) {
            request.fail(editVersion, new IllegalStateException("Editor Closed"));
            return false;
        }
        if (replaying || (!frozen && deferred.isEmpty())) {
            executeMutation(request);
            return true;
        }
        if (backpressured) {
            notifyOverflow();
            request.fail(editVersion, new IllegalStateException("Mutation Queue Full"));
            return false;
        }
        if (deferred.size() >= DEFERRED_LIMIT - 1) {
            enterBackpressure();
            request.fail(editVersion, new IllegalStateException("Mutation Queue Full"));
            return false;
        }
        try {
            deferred.addLast(request);
            return true;
        } catch (RuntimeException | Error exception) {
            deferred.removeLastOccurrence(request);
            request.fail(editVersion, asRuntimeException(exception));
            return false;
        }
    }

    public boolean defer(Runnable mutation, Consumer<MutationResult> completion) {
        return runMutation(mutation, completion);
    }

    public boolean capture(String type, String id, Consumer<SaveSnapshot> saver) {
        return capture(type, id, null, saver);
    }

    public boolean capture(String type, String id, Object request, Consumer<SaveSnapshot> saver) {
        assertRenderThread();
        if (closed || type == null || type.isBlank() || id == null || id.isBlank() || saver == null) {
            return false;
        }
        if (rebaseBlocked || pendingAck != null) {
            notifyOverflow();
            if (rebaseBlocked && pendingAck != null) {
                PendingAck retry = pendingAck.reset();
                pendingAck = null;
                rebaseBlocked = false;
                beginRebase(retry);
            }
            return false;
        }
        if (request != null && checkpoints.size() >= CHECKPOINT_LIMIT) {
            return false;
        }
        if (frozen || (!replaying && !deferred.isEmpty())) {
            return enqueue(() -> capture(type, id, request, saver));
        }
        TypedKey key = new TypedKey(type, id);
        long version = latestSnapshotVersion.incrementAndGet();
        long capturedEditVersion = editVersion;
        T previous = value;
        SnapshotJob<T> job = new SnapshotJob<>(previous, key, version, capturedEditVersion, request, saver);
        inFlight = job;
        frozen = true;
        try {
            SNAPSHOTS.execute(() -> prepare(job));
        } catch (RuntimeException | Error exception) {
            inFlight = null;
            frozen = false;
            reportFailure(new Failure(key, version, request, Stage.SAVE, asRuntimeException(exception)));
            return false;
        }
        return true;
    }

    public boolean requestProjection(String type, String id, Supplier<T> source,
                                     Consumer<ProjectionSnapshot> completion) {
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            if (completion != null) {
                completeProjection(completion, new ProjectionSnapshot(new TypedKey(
                    type != null && !type.isBlank() ? type : "invalid",
                    id != null && !id.isBlank() ? id : "invalid"), 0L, -1L, "",
                    new IllegalArgumentException("Projection Key Is Required")));
            }
            return false;
        }
        TypedKey key = new TypedKey(type, id);
        ProjectionRequest<T> request = new ProjectionRequest<>(key, source, completion);
        if (source == null || completion == null) {
            request.fail(0L, -1L, new IllegalArgumentException("Projection Source And Completion Are Required"));
            return false;
        }
        if (closed) {
            request.fail(latestSnapshotVersion.get(), -1L, new IllegalStateException("Editor Closed"));
            return false;
        }
        try {
            if (!projections.offer(request)) {
                request.fail(latestSnapshotVersion.get(), -1L, new IllegalStateException("Projection Queue Full"));
                return false;
            }
            if (closed && projections.remove(request)) {
                request.fail(latestSnapshotVersion.get(), -1L, new IllegalStateException("Editor Closed"));
                return false;
            }
        } catch (RuntimeException | Error exception) {
            projections.remove(request);
            request.fail(latestSnapshotVersion.get(), -1L, asRuntimeException(exception));
            return false;
        }
        return true;
    }

    public boolean captureProjection(String type, String id, Supplier<T> source,
                                     Consumer<ProjectionSnapshot> completion) {
        assertRenderThread();
        if (closed || type == null || type.isBlank() || id == null || id.isBlank()
            || source == null || completion == null) {
            TypedKey key = new TypedKey(type != null && !type.isBlank() ? type : "invalid",
                id != null && !id.isBlank() ? id : "invalid");
            new ProjectionRequest<T>(key, source, completion).fail(latestSnapshotVersion.get(), editVersion,
                new IllegalStateException(closed ? "Editor Closed" : "Projection Source And Completion Are Required"));
            return false;
        }
        if (frozen || (!replaying && !deferred.isEmpty())) {
            return requestProjection(type, id, source, completion);
        }
        TypedKey key = new TypedKey(type, id);
        if (rebaseBlocked || pendingAck != null) {
            new ProjectionRequest<T>(key, source, completion).fail(latestSnapshotVersion.get(), editVersion,
                new IllegalStateException("Editor Busy, Try Again"));
            return false;
        }
        ProjectionRequest<T> request = new ProjectionRequest<>(key, source, completion);
        return startProjection(request);
    }

    public boolean acknowledge(Object request, Supplier<String> authoritative) {
        assertRenderThread();
        if (closed || request == null || authoritative == null) {
            if (request != null) {
                checkpoints.remove(request);
            }
            return false;
        }
        if (backpressured) {
            PendingAck previous = pendingAck;
            if (previous != null && previous.request() != request) {
                checkpoints.remove(previous.request());
            }
            pendingAck = new PendingAck(request, authoritative, 0);
            return true;
        }
        if (frozen || (!replaying && !deferred.isEmpty())) {
            return enqueue(() -> acknowledge(request, authoritative));
        }
        return beginRebase(new PendingAck(request, authoritative, 0));
    }

    private boolean beginRebase(PendingAck ack) {
        Checkpoint checkpoint = checkpoints.get(ack.request());
        if (checkpoint == null) {
            return false;
        }
        T previous = value;
        BrowserSafeState.BooleanValue cancelled = new BrowserSafeState.BooleanValue();
        rebaseCancelled = cancelled;
        frozen = true;
        PendingAck attempt = ack.nextAttempt();
        try {
            SNAPSHOTS.execute(() -> prepareRebase(previous, checkpoint, attempt, cancelled));
        } catch (RuntimeException | Error exception) {
            prepared.compareAndSet(null, new Prepared<>(previous,
                new Failure(checkpoint.key(), checkpoint.version(), ack.request(), Stage.REBASE,
                    asRuntimeException(exception)), attempt, attempt,
                new RebindContext(checkpoint.key(), checkpoint.version(), ack.request(), Stage.REBASE), true));
        }
        return true;
    }

    public void discard(Object request) {
        assertRenderThread();
        if (request != null) {
            checkpoints.remove(request);
            if (pendingAck != null && pendingAck.request() == request) {
                pendingAck = null;
            }
        }
    }

    public void drain() {
        assertRenderThread();
        if (closed) {
            return;
        }
        Prepared<T> result = prepared.getAndSet(null);
        if (result != null) {
            T previous = value;
            Failure failure = result.failure();
            if (result.rebind() && failure == null) {
                RuntimeException rebindFailure = applyRebind(previous, result.replacement());
                if (rebindFailure == null) {
                    value = result.replacement();
                    if (result.rebaseAttempt() != null) {
                        checkpoints.remove(result.rebaseAttempt().request());
                    }
                } else {
                    value = previous;
                    RebindContext context = result.context();
                    failure = new Failure(context.key(), context.version(), context.request(), context.stage(), rebindFailure);
                    if (result.rebaseAttempt() != null) {
                        pendingAck = result.rebaseAttempt();
                        rebaseBlocked = result.rebaseAttempt().attempts() >= REBASE_RETRY_LIMIT;
                    }
                }
            }
            if (result.retry() != null) {
                pendingAck = result.retry();
                rebaseBlocked = result.retry().attempts() >= REBASE_RETRY_LIMIT;
            } else if (failure == null) {
                rebaseBlocked = false;
            }
            if (result.rebaseAttempt() != null && failure == null) {
                pendingAck = null;
                rebaseBlocked = false;
            }
            inFlight = null;
            rebaseCancelled = null;
            if (result.rebind() && failure != null
                && (failure.stage() != Stage.REBASE || rebaseBlocked)) {
                reportFailure(failure);
            }
            frozen = false;
        }
        if (frozen) {
            return;
        }
        if (deferred.isEmpty()) {
            finishReplay();
            if (!frozen) {
                processProjection();
            }
            return;
        }
        long deadline = System.nanoTime() + REPLAY_NANOS;
        int handled = 0;
        replaying = true;
        try {
            while (!frozen && handled < REPLAY_LIMIT && System.nanoTime() < deadline) {
                DeferredMutation mutation = deferred.pollFirst();
                if (mutation == null) {
                    break;
                }
                if (mutation.versioned()) {
                    executeMutation(mutation);
                } else {
                    try {
                        mutation.mutation.run();
                    } catch (RuntimeException | Error exception) {
                        reportFailure(new Failure(new TypedKey("editor", "mutation"), editVersion, null,
                            Stage.REBASE, asRuntimeException(exception)));
                    }
                }
                handled++;
            }
        } finally {
            replaying = false;
        }
        if (!frozen && deferred.isEmpty()) {
            finishReplay();
        }
        if (!frozen && deferred.isEmpty()) {
            processProjection();
        }
    }

    private void finishReplay() {
        backpressured = false;
        overflowed = false;
        PendingAck ack = pendingAck;
        if (ack != null && !rebaseBlocked) {
            pendingAck = null;
            beginRebase(ack);
        }
    }

    @Override
    public void close() {
        assertRenderThread();
        if (closed) {
            return;
        }
        closed = true;
        SnapshotJob<T> job = inFlight;
        if (job != null && job.projection != null) {
            job.projection.fail(job.version, job.editVersion, new IllegalStateException("Editor Closed"));
        }
        if (job != null) {
            boolean cancelledBeforeDispatch = job.cancelBeforeDispatch();
            if (cancelledBeforeDispatch && job.projection == null) {
                reportFailure(new Failure(job.key, job.version, job.request,
                    Stage.SAVE, new IllegalStateException("Editor Closed Before Save Snapshot Dispatch")));
            }
        }
        BrowserSafeState.BooleanValue cancelled = rebaseCancelled;
        if (cancelled != null) {
            cancelled.set(true);
        }
        frozen = false;
        inFlight = null;
        backpressured = false;
        DeferredMutation mutation;
        while ((mutation = deferred.pollFirst()) != null) {
            mutation.fail(editVersion, new IllegalStateException("Editor Closed"));
        }
        pendingAck = null;
        rebaseBlocked = false;
        prepared.set(null);
        checkpoints.clear();
        ProjectionRequest<T> projection;
        while ((projection = projections.poll()) != null) {
            projection.fail(latestSnapshotVersion.get(), -1L, new IllegalStateException("Editor Closed"));
        }
    }

    private boolean enqueue(Runnable action) {
        if (backpressured || action == null) {
            return false;
        }
        if (deferred.size() >= DEFERRED_LIMIT - 1) {
            deferred.addLast(new DeferredMutation(action, null));
            enterBackpressure();
            return true;
        }
        deferred.addLast(new DeferredMutation(action, null));
        return true;
    }

    private void enterBackpressure() {
        backpressured = true;
        if (!overflowed) {
            overflowed = true;
            notifyOverflow();
        }
        SnapshotJob<T> job = inFlight;
        if (job != null) {
            job.cancelBeforeDispatch();
        }
        BrowserSafeState.BooleanValue cancelled = rebaseCancelled;
        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    private void executeMutation(DeferredMutation mutation) {
        long before = editVersion;
        try {
            mutation.mutation.run();
            mutation.complete(new MutationResult(before, editVersion, null));
        } catch (RuntimeException | Error exception) {
            mutation.complete(new MutationResult(before, editVersion, asRuntimeException(exception)));
        }
    }

    private void notifyOverflow() {
        try {
            overflowHandler.run();
        } catch (RuntimeException | Error ignored) {
        }
    }

    private static long nextEditVersion(long version) {
        return version == Long.MAX_VALUE ? Long.MAX_VALUE : version + 1L;
    }

    private boolean startProjection(ProjectionRequest<T> request) {
        assertRenderThread();
        if (closed) {
            request.fail(latestSnapshotVersion.get(), editVersion, new IllegalStateException("Editor Closed"));
            return false;
        }
        if (frozen || (!replaying && !deferred.isEmpty())) {
            try {
                if (projections.offer(request)) {
                    return true;
                }
            } catch (RuntimeException | Error exception) {
                request.fail(latestSnapshotVersion.get(), editVersion, asRuntimeException(exception));
                return false;
            }
            request.fail(latestSnapshotVersion.get(), editVersion, new IllegalStateException("Projection Queue Full"));
            return false;
        }
        T previous;
        try {
            previous = Objects.requireNonNull(request.source.get());
        } catch (RuntimeException | Error exception) {
            request.fail(latestSnapshotVersion.get(), editVersion, asRuntimeException(exception));
            return false;
        }
        long version = latestSnapshotVersion.incrementAndGet();
        long capturedEditVersion = editVersion;
        SnapshotJob<T> job = new SnapshotJob<>(previous, request.key, version, capturedEditVersion, null, null,
            request, false);
        inFlight = job;
        frozen = true;
        try {
            SNAPSHOTS.execute(() -> prepare(job));
        } catch (RuntimeException | Error exception) {
            inFlight = null;
            frozen = false;
            request.fail(version, capturedEditVersion, asRuntimeException(exception));
            return false;
        }
        return true;
    }

    private void processProjection() {
        ProjectionRequest<T> request = projections.poll();
        if (request == null) {
            return;
        }
        if (rebaseBlocked || pendingAck != null) {
            request.fail(latestSnapshotVersion.get(), editVersion, new IllegalStateException("Editor Busy, Try Again"));
            return;
        }
        startProjection(request);
    }

    private static void completeProjection(Consumer<ProjectionSnapshot> completion, ProjectionSnapshot snapshot) {
        try {
            completion.accept(snapshot);
        } catch (RuntimeException | Error ignored) {
        }
    }

    private static RuntimeException asRuntimeException(Throwable exception) {
        return exception instanceof RuntimeException runtime
            ? runtime : new IllegalStateException(exception);
    }

    private void reportFailure(Failure failure) {
        try {
            failureHandler.accept(failure);
        } catch (RuntimeException | Error ignored) {
        }
    }

    private RuntimeException applyRebind(T previous, T replacement) {
        try {
            rebinder.accept(previous, replacement);
            return null;
        } catch (RuntimeException | Error exception) {
            RuntimeException failure = asRuntimeException(exception);
            try {
                rebinder.accept(previous, previous);
            } catch (RuntimeException | Error rollbackException) {
                addSuppressed(failure, rollbackException);
                try {
                    rebinder.accept(replacement, previous);
                } catch (RuntimeException | Error fallbackException) {
                    addSuppressed(failure, fallbackException);
                }
            }
            return failure;
        }
    }

    private static void addSuppressed(RuntimeException failure, Throwable suppressed) {
        RuntimeException suppressedFailure = asRuntimeException(suppressed);
        if (suppressedFailure == failure) {
            return;
        }
        try {
            failure.addSuppressed(suppressedFailure);
        } catch (RuntimeException | Error ignored) {
        }
    }

    private void prepare(SnapshotJob<T> job) {
        T replacement = job.previous;
        Failure failure = null;
        try {
            if (job.cancelled()) {
                throw new IllegalStateException("Editor Busy, Try Again");
            }
            String payload = serializer.apply(job.previous);
            if (job.cancelled() || (job.projection != null && closed)) {
                throw new IllegalStateException("Editor Busy, Try Again");
            }
            if (!job.claimDispatch()) {
                throw new IllegalStateException("Editor Busy, Try Again");
            }
            if (job.projection != null) {
                job.projection.complete(new ProjectionSnapshot(job.key, job.version, job.editVersion, payload, null));
            } else {
                replacement = Objects.requireNonNull(deserializer.apply(payload));
                if (job.request != null) {
                    checkpoints.put(job.request, new Checkpoint(job.key, job.version, job.editVersion, payload));
                }
                job.saver.accept(new SaveSnapshot(this, job.key, job.version, job.editVersion, payload));
            }
        } catch (RuntimeException | Error exception) {
            if (job.request != null) {
                checkpoints.remove(job.request);
            }
            RuntimeException cause = asRuntimeException(exception);
            failure = new Failure(job.key, job.version, job.request, Stage.SAVE, cause);
            if (job.projection != null) {
                job.projection.fail(job.version, job.editVersion, cause);
            }
        }
        if (!closed) {
            prepared.compareAndSet(null, new Prepared<>(replacement, failure, null, null,
                new RebindContext(job.key, job.version, job.request, Stage.SAVE), job.rebind));
            if (closed) {
                prepared.set(null);
            }
        }
    }

    private void prepareRebase(T previous, Checkpoint checkpoint, PendingAck attempt, BrowserSafeState.BooleanValue cancelled) {
        T replacement = previous;
        Failure failure = null;
        try {
            if (cancelled.get()) {
                throw new IllegalStateException("Editor Busy, Try Again");
            }
            String current = serializer.apply(previous);
            String authoritativePayload = Objects.requireNonNull(attempt.authoritative().get());
            if (cancelled.get()) {
                throw new IllegalStateException("Editor Busy, Try Again");
            }
            replacement = Objects.requireNonNull(deserializer.apply(merge(checkpoint.payload(), current, authoritativePayload)));
        } catch (RuntimeException | Error exception) {
            failure = new Failure(checkpoint.key(), checkpoint.version(), attempt.request(), Stage.REBASE,
                asRuntimeException(exception));
        }
        if (!closed) {
            prepared.compareAndSet(null, new Prepared<>(replacement, failure, failure != null ? attempt : null, attempt,
                new RebindContext(checkpoint.key(), checkpoint.version(), attempt.request(), Stage.REBASE), true));
            if (closed) {
                prepared.set(null);
            }
        }
    }

    private static String merge(String baseline, String current, String authoritative) {
        Gson gson = new Gson();
        JsonElement base = gson.fromJson(baseline, JsonElement.class);
        JsonElement edited = gson.fromJson(current, JsonElement.class);
        JsonElement normalized = gson.fromJson(authoritative, JsonElement.class);
        JsonElement merged = merge(base, edited, normalized);
        return gson.toJson(merged == null ? JsonNull.INSTANCE : merged);
    }

    private static JsonElement merge(JsonElement baseline, JsonElement current, JsonElement authoritative) {
        if (Objects.equals(current, baseline)) {
            return copy(authoritative);
        }
        if (Objects.equals(authoritative, baseline) || Objects.equals(current, authoritative)) {
            return copy(current);
        }
        if (baseline != null && current != null && authoritative != null
            && baseline.isJsonObject() && current.isJsonObject() && authoritative.isJsonObject()) {
            JsonObject merged = new JsonObject();
            Set<String> keys = new LinkedHashSet<>();
            baseline.getAsJsonObject().keySet().forEach(keys::add);
            authoritative.getAsJsonObject().keySet().forEach(keys::add);
            current.getAsJsonObject().keySet().forEach(keys::add);
            for (String key : keys) {
                JsonElement value = merge(baseline.getAsJsonObject().get(key), current.getAsJsonObject().get(key),
                    authoritative.getAsJsonObject().get(key));
                if (value != null) {
                    merged.add(key, value);
                }
            }
            return merged;
        }
        return copy(current);
    }

    private static JsonElement copy(JsonElement value) {
        return value == null ? null : value.deepCopy();
    }

    private boolean compareAndMarkSaved(long version) {
        if (latestSnapshotVersion.get() != version) {
            return false;
        }
        while (true) {
            long saved = savedSnapshotVersion.get();
            if (saved >= version) {
                return saved == version;
            }
            if (savedSnapshotVersion.compareAndSet(saved, version)) {
                return true;
            }
        }
    }

    private void assertRenderThread() {
        Object current = TaskIdentities.access.current();
        if (renderThread == null) {
            renderThread = current;
            return;
        }
        if (renderThread != current) {
            throw new IllegalStateException("Editor Draft Is Render-Owned");
        }
    }
}
