package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.JsonElement;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

final class HistoryRebaseCoordinator<T> implements AutoCloseable {
    private static final int WORKER_COUNT = 2;
    private static final int WORK_QUEUE_LIMIT = 8;
    private static final int EDITOR_QUEUE_LIMIT = 8;
    private static final int TOTAL_REQUEST_LIMIT = 32;
    private static final BrowserSafeState.LongValue THREAD_IDS = new BrowserSafeState.LongValue();
    private static final BrowserSafeState.LongValue RETAINED_REQUESTS = new BrowserSafeState.LongValue();
    private static final BrowserWork.Executor WORKERS = BrowserWork.executor();

    private final StudioScreen.History<T> history;
    private final Supplier<String> documentKey;
    private final LongSupplier lifecycle;
    private final Runnable overflowHandler;
    private final Deque<Request<T>> pending = new ArrayDeque<>();
    private final BrowserSafeState.ReferenceValue<Completion<T>> completion = new BrowserSafeState.ReferenceValue<>();
    private Job<T> inFlight;
    private long nextRevision;
    private long settledRevision;
    private boolean scheduled;
    private volatile long workGeneration;
    private volatile boolean closed;

    HistoryRebaseCoordinator(StudioScreen.History<T> history, Supplier<String> documentKey,
                             LongSupplier lifecycle, Runnable overflowHandler) {
        this.history = Objects.requireNonNull(history);
        this.documentKey = Objects.requireNonNull(documentKey);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.overflowHandler = Objects.requireNonNull(overflowHandler);
    }

    void request(List<WorkspacePatch<JsonElement>> patches,
                 Function<List<WorkspacePatch<JsonElement>>, UnaryOperator<T>> transformerFactory) {
        if (closed || patches == null || patches.isEmpty() || transformerFactory == null) {
            return;
        }
        StudioScreen.History.RebaseState<T> captured = history.captureRebaseState();
        Request<T> request = new Request<>(++nextRevision, lifecycle.getAsLong(), documentKey.get(), captured,
            patches, transformerFactory);
        if (!retainRequest()) {
            invalidateHistory();
            return;
        }
        pending.addLast(request);
        schedule();
    }

    void drain() {
        if (closed) {
            return;
        }
        Completion<T> result = completion.getAndSet(null);
        if (result != null) {
            Job<T> job = inFlight;
            if (job == null || job.request() != result.request() || job.generation() != result.generation()) {
                schedule();
                return;
            }
            scheduled = false;
            inFlight = null;
            Request<T> request = result.request();
            boolean current = pending.peekFirst() == request && request.revision() > settledRevision
                && request.lifecycle() == lifecycle.getAsLong()
                && Objects.equals(request.documentKey(), documentKey.get());
            if (!current || result.failure() != null || result.prepared() == null) {
                invalidateHistory();
            } else if (history.commitRebase(request.captured(), result.prepared())) {
                remove(request);
                settledRevision = request.revision();
            } else {
                request.setCaptured(history.captureRebaseState());
            }
        }
        schedule();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        workGeneration++;
        completion.set(null);
        if (inFlight != null) {
            releaseRequest(inFlight.request());
            inFlight = null;
        }
        while (!pending.isEmpty()) {
            releaseRequest(pending.removeFirst());
        }
        scheduled = false;
    }

    private void schedule() {
        if (closed || scheduled || pending.isEmpty()) {
            return;
        }
        Request<T> request = pending.peekFirst();
        Job<T> job = new Job<>(request, request.captured(), workGeneration);
        inFlight = job;
        scheduled = true;
        try {
            WORKERS.execute(() -> prepare(job));
        } catch (IllegalStateException exception) {
            scheduled = false;
            inFlight = null;
        }
    }

    private void prepare(Job<T> job) {
        StudioScreen.History.PreparedRebase<T> prepared = null;
        Throwable failure = null;
        try {
            List<WorkspacePatch<JsonElement>> copiedPatches = copyPatches(job.request().patches());
            UnaryOperator<T> transformer = job.request().transformerFactory().apply(copiedPatches);
            prepared = history.prepareRebase(job.captured(), transformer);
            if (prepared == null) {
                failure = new IllegalStateException("History Rebase Preparation Failed");
            }
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        if (!closed && job.generation() == workGeneration) {
            Completion<T> completed = new Completion<>(job.request(), prepared, job.generation(), failure);
            if (completion.compareAndSet(null, completed) && closed) {
                completion.compareAndSet(completed, null);
            }
        }
    }

    private void invalidateHistory() {
        if (closed) {
            return;
        }
        workGeneration++;
        completion.set(null);
        scheduled = false;
        if (inFlight != null) {
            releaseRequest(inFlight.request());
            inFlight = null;
        }
        while (!pending.isEmpty()) {
            releaseRequest(pending.removeFirst());
        }
        try {
            history.invalidate();
        } catch (RuntimeException | Error ignored) {
        }
        try {
            overflowHandler.run();
        } catch (RuntimeException | Error ignored) {
        }
    }

    private boolean retainRequest() {
        if (pending.size() >= EDITOR_QUEUE_LIMIT) {
            return false;
        }
        while (true) {
            long retained = RETAINED_REQUESTS.get();
            if (retained >= TOTAL_REQUEST_LIMIT
                || !RETAINED_REQUESTS.compareAndSet(retained, retained + 1L)) {
                if (retained >= TOTAL_REQUEST_LIMIT) {
                    return false;
                }
                continue;
            }
            return true;
        }
    }

    private void remove(Request<T> request) {
        if (pending.removeFirstOccurrence(request)) {
            releaseRequest(request);
        }
    }

    private void releaseRequest(Request<T> request) {
        if (request.release()) {
            RETAINED_REQUESTS.decrementAndGet();
        }
    }

    private static List<WorkspacePatch<JsonElement>> copyPatches(List<WorkspacePatch<JsonElement>> patches) {
        if (patches == null || patches.isEmpty()) {
            return List.of();
        }
        ArrayList<WorkspacePatch<JsonElement>> copy = new ArrayList<>(patches.size());
        for (WorkspacePatch<JsonElement> patch : patches) {
            if (patch == null) {
                throw new IllegalArgumentException("Workspace patch is required");
            }
            JsonElement value = patch.value();
            copy.add(new WorkspacePatch<>(patch.op(), patch.path(), value == null ? null : value.deepCopy()));
        }
        return List.copyOf(copy);
    }

    private static final class Request<T> {
        private final long revision;
        private final long lifecycle;
        private final String documentKey;
        private final List<WorkspacePatch<JsonElement>> patches;
        private final Function<List<WorkspacePatch<JsonElement>>, UnaryOperator<T>> transformerFactory;
        private StudioScreen.History.RebaseState<T> captured;
        private boolean retained = true;

        private Request(long revision, long lifecycle, String documentKey, StudioScreen.History.RebaseState<T> captured,
                        List<WorkspacePatch<JsonElement>> patches,
                        Function<List<WorkspacePatch<JsonElement>>, UnaryOperator<T>> transformerFactory) {
            this.revision = revision;
            this.lifecycle = lifecycle;
            this.documentKey = documentKey;
            this.captured = captured;
            this.patches = patches;
            this.transformerFactory = transformerFactory;
        }

        private long revision() {
            return revision;
        }

        private long lifecycle() {
            return lifecycle;
        }

        private String documentKey() {
            return documentKey;
        }

        private List<WorkspacePatch<JsonElement>> patches() {
            return patches;
        }

        private Function<List<WorkspacePatch<JsonElement>>, UnaryOperator<T>> transformerFactory() {
            return transformerFactory;
        }

        private StudioScreen.History.RebaseState<T> captured() {
            return captured;
        }

        private void setCaptured(StudioScreen.History.RebaseState<T> captured) {
            this.captured = captured;
        }

        private boolean release() {
            if (!retained) {
                return false;
            }
            retained = false;
            return true;
        }
    }

    private record Job<T>(Request<T> request, StudioScreen.History.RebaseState<T> captured, long generation) {
    }

    private record Completion<T>(Request<T> request, StudioScreen.History.PreparedRebase<T> prepared,
                                 long generation, Throwable failure) {
    }
}
