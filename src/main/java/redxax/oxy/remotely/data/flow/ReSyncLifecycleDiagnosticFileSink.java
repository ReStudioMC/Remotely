package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticIdentity;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.diagnostics.DiagnosticValue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public final class ReSyncLifecycleDiagnosticFileSink implements DiagnosticSink {
    public static final String DIRECTORY = "diagnostic-channel";
    public static final String FILE_NAME = "resync-lifecycle.jsonl";
    private static final String ROTATED_PREFIX = "resync-lifecycle.";
    private static final String ROTATED_SUFFIX = ".jsonl";
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final FailureProbe NO_FAILURE = step -> { };
    private final Mode mode;
    private final Path directory;
    private final Path activePath;
    private final long maxFileBytes;
    private final FailureProbe failureProbe;
    private final java.util.Queue<QueuedEvent> criticalQueue = BrowserSafeState.queue();
    private final java.util.Queue<QueuedEvent> normalQueue = BrowserSafeState.queue();
    private final BrowserSafeState.ReferenceValue<State> state = new BrowserSafeState.ReferenceValue<>(State.READY);
    private final Object lifecycleLock = new Object();
    private final BrowserSafeState.ReferenceValue<String> failureReason = new BrowserSafeState.ReferenceValue<>("");
    private final BrowserSafeState.LongValue offered = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue accepted = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue dropped = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue failed = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue droppedMode = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue droppedPaused = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue droppedClosed = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue droppedThrottle = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue droppedNormalCapacity = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue droppedCriticalCapacity = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue droppedCriticalPreempted = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue droppedTerminalCapacity = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue droppedFailed = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue sequence = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue acceptedSequence = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue confirmedSequence = new BrowserSafeState.LongValue();
    private final BrowserSafeState.ReferenceValue<FlushRequest> flushRequest = new BrowserSafeState.ReferenceValue<>();
    private final Map<String, Long> throttle = new LinkedHashMap<>();
    private final Map<String, long[]> progress = new LinkedHashMap<>();
    private final UUID processSessionId = UUID.randomUUID();
    private final Thread writerThread;
    private final BrowserSafeState.BooleanValue stopRequested = new BrowserSafeState.BooleanValue();
    private volatile BufferedWriter writer;
    private volatile long activeBytes;
    private volatile QueuedEvent bufferedNormal;
    private volatile long heldFirstSequence;
    private volatile long writingSequence;
    private long lastSummaryNanos = System.nanoTime();
    private long lastReportedDropped;
    private long lastReportedFailed;
    private long rotationSequence;

    public static ReSyncLifecycleDiagnosticFileSink open(Path logRoot) {
        Mode mode = ReSyncLifecycleDiagnosticPolicy.resolveFromProcess();
        return mode.enabled() && logRoot != null
            ? new ReSyncLifecycleDiagnosticFileSink(logRoot, mode)
            : disabled();
    }

    public static ReSyncLifecycleDiagnosticFileSink disabled() {
        return new ReSyncLifecycleDiagnosticFileSink(null, Mode.OFF);
    }

    private ReSyncLifecycleDiagnosticFileSink(Path logRoot, Mode mode) {
        this(logRoot, mode, ReSyncLifecycleDiagnosticPolicy.MAX_FILE_BYTES, NO_FAILURE);
    }

    ReSyncLifecycleDiagnosticFileSink(Path logRoot, Mode mode, long maxFileBytes, FailureProbe failureProbe) {
        this.mode = mode == null ? Mode.OFF : mode;
        this.maxFileBytes = maxFileBytes;
        this.failureProbe = failureProbe == null ? NO_FAILURE : failureProbe;
        if (this.mode.enabled()) {
            if (logRoot == null || maxFileBytes <= ReSyncLifecycleDiagnosticPolicy.MAX_LINE_BYTES * 2L) {
                throw new IllegalArgumentException("Diagnostic root and rotation size are required");
            }
            Path root = logRoot.toAbsolutePath().normalize();
            this.directory = root.resolve(DIRECTORY).normalize();
            if (!this.directory.startsWith(root)) {
                throw new IllegalArgumentException("Diagnostic directory escapes its root");
            }
            this.activePath = directory.resolve(FILE_NAME).normalize();
            this.writerThread = new Thread(this::runWriter, "Remotely-ReSync-Diagnostic-Writer");
            this.writerThread.setDaemon(true);
            this.writerThread.start();
        } else {
            this.directory = null;
            this.activePath = null;
            this.writerThread = null;
        }
    }

    public Path activePath() {
        return activePath;
    }

    @Override
    public Status status() {
        return new Status(mode, state.get(), offered.get(), accepted.get(), dropped.get(), failed.get(),
            failureReason.get());
    }

    @Override
    public Offer offer(DiagnosticEvent event) {
        if (event == null) {
            return Offer.REJECTED;
        }
        if (!mode.enabled()) {
            return Offer.DISABLED;
        }
        synchronized (lifecycleLock) {
            offered.incrementAndGet();
            State current = state.get();
            if (current == State.PAUSED) {
                recordDrop(droppedPaused);
                return Offer.PAUSED;
            }
            if (current == State.FAILED) {
                failed.incrementAndGet();
                recordDrop(droppedFailed);
                return Offer.FAILED;
            }
            if (current == State.CLOSED) {
                recordDrop(droppedClosed);
                return Offer.CLOSED;
            }
            if (!mode.accepts(event.priority())) {
                recordDrop(droppedMode);
                return Offer.REJECTED;
            }
            recordProgress(event);
            if (ReSyncLifecycleDiagnosticPolicy.healthyProgress(event) && throttled(event)) {
                recordDrop(droppedThrottle);
                return Offer.DROPPED;
            }
            long eventSequence = sequence.incrementAndGet();
            QueuedEvent queued = new QueuedEvent(event, Thread.currentThread().getName(), System.currentTimeMillis(),
                eventSequence);
            java.util.Queue<QueuedEvent> queue = event.priority().atLeast(Priority.IMPORTANT) ? criticalQueue : normalQueue;
            if (!offerQueued(queue, queued)) {
                return Offer.DROPPED;
            }
            accepted.incrementAndGet();
            acceptedSequence.accumulateAndGet(eventSequence, Math::max);
        }
        if (writerThread != null) writerThread.interrupt();
        return Offer.ACCEPTED;
    }

    @Override
    public Status pause() {
        state.compareAndSet(State.READY, State.PAUSED);
        return status();
    }

    @Override
    public Status resume() {
        state.compareAndSet(State.PAUSED, State.READY);
        return status();
    }

    @Override
    public Flush flush() {
        if (!mode.enabled()) {
            return Flush.DISABLED;
        }
        if (Thread.currentThread() == writerThread) {
            try {
                flushWriter();
                return Flush.FLUSHED;
            } catch (IOException exception) {
                failSink(exception, firstPendingSequence(), acceptedSequence.get(), "flush");
                return Flush.FAILED;
            }
        }
        FlushRequest request;
        synchronized (lifecycleLock) {
            if (state.get() == State.CLOSED) return Flush.CLOSED;
            if (state.get() == State.FAILED) return Flush.FAILED;
            request = flushRequest.get();
            if (request == null) {
                request = new FlushRequest(acceptedSequence.get());
                flushRequest.set(request);
            } else {
                request.watermark = Math.max(request.watermark, acceptedSequence.get());
            }
        }
        if (writerThread != null) writerThread.interrupt();
        try {
            if (!request.latch.awaitMillis(ReSyncLifecycleDiagnosticPolicy.CLOSE_TIMEOUT_MILLIS)) {
                return Flush.FAILED;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Flush.FAILED;
        }
        return state.get() == State.FAILED ? Flush.FAILED : Flush.FLUSHED;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            state.set(State.CLOSED);
            if (mode.enabled()) {
                stopRequested.set(true);
            }
        }
        if (!mode.enabled()) return;
        if (writerThread != null) writerThread.interrupt();
        if (Thread.currentThread() != writerThread) {
            join(ReSyncLifecycleDiagnosticPolicy.CLOSE_TIMEOUT_MILLIS);
            if (writerThread.isAlive()) {
                writerThread.interrupt();
                join(100L);
            }
        }
    }

    private void runWriter() {
        try {
            if (!openActiveRecovering()) {
                completeFlush();
                return;
            }
            while (!stopRequested.get() || !criticalQueue.isEmpty() || !normalQueue.isEmpty()
                || bufferedNormal != null || flushRequest.get() != null) {
                QueuedEvent first = pollNextEvent();
                if (first == null) {
                    if (!writeDropSummary()) {
                        completeFlush();
                        return;
                    }
                    completeFlush();
                    continue;
                }
                List<QueuedEvent> batch = new ArrayList<>(ReSyncLifecycleDiagnosticPolicy.MAX_BATCH_SIZE);
                batch.add(first);
                synchronized (lifecycleLock) {
                    while (batch.size() < ReSyncLifecycleDiagnosticPolicy.MAX_BATCH_SIZE) {
                        QueuedEvent next = pollAvailableNext();
                        if (next == null) break;
                        batch.add(next);
                    }
                }
                for (int index = 0; index < batch.size(); index++) {
                    QueuedEvent event = batch.get(index);
                    writingSequence = event.sequence();
                    if (!writeEvent(event)) {
                        completeFlush();
                        return;
                    }
                    heldFirstSequence = index + 1 < batch.size() ? batch.get(index + 1).sequence() : 0L;
                    writingSequence = 0L;
                }
                if (!writeDropSummary()) {
                    completeFlush();
                    return;
                }
                completeFlush();
            }
            writeDropSummary();
            completeFlush();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!stopRequested.get()) {
                failSink(exception, firstPendingSequence(), acceptedSequence.get(), "interrupted");
            }
            completeFlush();
        } catch (IOException exception) {
            failSink(exception, firstPendingSequence(), acceptedSequence.get(), "writer");
            completeFlush();
        } catch (RuntimeException exception) {
            failSink(exception, firstPendingSequence(), acceptedSequence.get(), "writer");
            completeFlush();
        } finally {
            closeWriterQuietly();
        }
    }

    private boolean openActiveRecovering() {
        IOException failure = null;
        for (int attempt = 1; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
            try {
                openActive(true);
                markRecovered(failure);
                return true;
            } catch (IOException exception) {
                failure = exception;
                noteFailure(exception, "open", attempt);
                closeWriterAfterFailure();
            }
        }
        failSink(failure, firstPendingSequence(), acceptedSequence.get(), "open-exhausted");
        return false;
    }

    private void openActive(boolean sessionHeader) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(directory)) {
            throw new IOException("Diagnostic directory is a symbolic link");
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(activePath)) {
            throw new IOException("Diagnostic file is a symbolic link");
        }
        if (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) && Files.size(activePath) >= maxFileBytes) {
            rotateAndOpen();
            retainFiles();
            return;
        }
        boolean recovered = recoverTrailingLine(activePath);
        activeBytes = Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) ? Files.size(activePath) : 0L;
        failureProbe.check("open");
        writer = Files.newBufferedWriter(activePath, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        if (!sessionHeader && activeBytes > 0L) {
            return;
        }
        Map<String, Object> header = header();
        header.put("tailRecovered", recovered);
        writeHeader(header);
        flushWriter();
        retainFiles();
    }

    private Map<String, Object> header() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("kind", "header");
        header.put("schema", 1);
        header.put("channel", "resync-lifecycle");
        header.put("processSessionId", processSessionId.toString());
        header.put("sequence", 0L);
        header.put("timestamp", Instant.now().toString());
        header.put("thread", ReSyncLifecycleDiagnosticPolicy.safeThread(writerThread.getName()));
        header.put("mode", mode.wireName());
        header.put("criticalCapacity", ReSyncLifecycleDiagnosticPolicy.CRITICAL_CAPACITY);
        header.put("normalCapacity", ReSyncLifecycleDiagnosticPolicy.NORMAL_CAPACITY);
        header.put("rotationBytes", maxFileBytes);
        header.put("retentionAgeMs", ReSyncLifecycleDiagnosticPolicy.RETENTION_AGE_MILLIS);
        header.put("retentionBytes", ReSyncLifecycleDiagnosticPolicy.RETENTION_BYTES);
        return header;
    }

    private void writeHeader(Map<String, Object> header) throws IOException {
        failureProbe.check("header");
        writeRawLine(json(header));
    }

    private boolean writeEvent(QueuedEvent queued) {
        DiagnosticEvent event = queued.event;
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", "event");
        document.put("schema", 1);
        document.put("processSessionId", processSessionId.toString());
        document.put("sequence", queued.sequence);
        document.put("timestamp", Instant.ofEpochMilli(queued.timestamp).toString());
        document.put("thread", ReSyncLifecycleDiagnosticPolicy.safeThread(queued.thread));
        document.put("stage", event.stage());
        document.put("priority", event.priority().wireName());
        document.put("elapsedMs", event.elapsedMillis());
        document.put("identity", identity(event.identity()));
        document.put("values", values(event.values()));
        return writeRecovering(json(document), queued.sequence, queued.sequence, "event");
    }

    private boolean writeDropSummary() {
        long droppedNow = dropped.get();
        long failedNow = failed.get();
        boolean healthDue = System.nanoTime() - lastSummaryNanos >= ((30L) * 1_000_000_000L);
        if (droppedNow == lastReportedDropped && failedNow == lastReportedFailed && !healthDue) {
            return true;
        }
        long summarySequence;
        synchronized (lifecycleLock) {
            if (!criticalQueue.isEmpty() || !normalQueue.isEmpty() || bufferedNormal != null) {
                return true;
            }
            summarySequence = sequence.incrementAndGet();
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", healthDue ? "health" : "drop-summary");
        document.put("schema", 1);
        document.put("processSessionId", processSessionId.toString());
        document.put("sequence", summarySequence);
        document.put("timestamp", Instant.now().toString());
        document.put("thread", ReSyncLifecycleDiagnosticPolicy.safeThread(writerThread.getName()));
        document.put("dropped", droppedNow);
        document.put("failed", failedNow);
        document.put("offered", offered.get());
        document.put("accepted", accepted.get());
        document.put("state", state.get().name());
        document.put("lastAcceptedSequence", acceptedSequence.get());
        document.put("reason", failureReason.get());
        document.put("progress", progressSnapshot());
        document.put("queuedCritical", criticalQueue.size());
        document.put("queuedNormal", normalQueue.size());
        document.put("droppedMode", droppedMode.get());
        document.put("droppedPaused", droppedPaused.get());
        document.put("droppedClosed", droppedClosed.get());
        document.put("droppedThrottle", droppedThrottle.get());
        document.put("droppedNormalCapacity", droppedNormalCapacity.get());
        document.put("droppedCriticalCapacity", droppedCriticalCapacity.get());
        document.put("droppedCriticalPreempted", droppedCriticalPreempted.get());
        document.put("droppedTerminalCapacity", droppedTerminalCapacity.get());
        document.put("droppedFailed", droppedFailed.get());
        writingSequence = summarySequence;
        if (!writeRecovering(json(document), summarySequence, summarySequence, "drop-summary")) {
            return false;
        }
        writingSequence = 0L;
        lastSummaryNanos = System.nanoTime();
        lastReportedDropped = droppedNow;
        lastReportedFailed = failedNow;
        return true;
    }

    private Map<String, Object> identity(DiagnosticIdentity identity) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (identity == null) {
            return result;
        }
        if (identity.serverId() != null) result.put("serverId",
            ReSyncLifecycleDiagnosticPolicy.safeText(identity.serverId().canonicalText()));
        if (identity.resource() != null) result.put("resource",
            identity.resource().canonicalText());
        if (identity.operation() != null) result.put("operation",
            identity.operation().canonicalText());
        if (identity.requestId() != null) result.put("requestId",
            ReSyncLifecycleDiagnosticPolicy.safeText(identity.requestId().toString()));
        if (identity.correlationId() != null) result.put("correlationId",
            ReSyncLifecycleDiagnosticPolicy.safeText(identity.correlationId().canonicalText()));
        if (identity.traceId() != null) result.put("traceId",
            ReSyncLifecycleDiagnosticPolicy.safeText(identity.traceId().canonicalText()));
        if (identity.mutationId() != null) result.put("mutationId",
            ReSyncLifecycleDiagnosticPolicy.safeText(identity.mutationId().toString()));
        if (identity.generation() != null) result.put("generation", identity.generation());
        if (identity.authorityEpoch() != null) result.put("authorityEpoch", identity.authorityEpoch());
        if (identity.revision() != null) result.put("revision", identity.revision().value());
        return result;
    }

    private Map<String, Object> values(Map<String, DiagnosticValue> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        int included = 0;
        int visited = 0;
        for (Map.Entry<String, DiagnosticValue> entry : source.entrySet()) {
            if (visited++ >= ReSyncLifecycleDiagnosticPolicy.MAX_FIELDS) break;
            if (ReSyncLifecycleDiagnosticPolicy.sensitiveField(entry.getKey())) continue;
            result.put(entry.getKey(), safeValue(entry.getValue() == null ? null : entry.getValue().toJava(), 0));
            included++;
        }
        if (source.size() > included) result.put("truncated", true);
        return result;
    }

    private Object safeValue(Object value, int depth) {
        if (value == null) return "";
        if (value instanceof String || value instanceof Character) return ReSyncLifecycleDiagnosticPolicy.safeText(value);
        if (value instanceof Boolean || value instanceof Number) return value;
        if (depth >= ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_DEPTH) return "[nested]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count >= ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS) break;
                if (!(entry.getKey() instanceof String key)
                    || ReSyncLifecycleDiagnosticPolicy.sensitiveField(key)) continue;
                result.put(key, safeValue(entry.getValue(), depth + 1));
                count++;
            }
            if (map.size() > count) result.put("truncated", true);
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS) break;
                result.add(safeValue(item, depth + 1));
            }
            return result;
        }
        return ReSyncLifecycleDiagnosticPolicy.safeText(value);
    }

    private String json(Map<String, Object> document) {
        String line = JsonValue.fromJava(document).canonicalText();
        if (line.getBytes(StandardCharsets.UTF_8).length < ReSyncLifecycleDiagnosticPolicy.MAX_LINE_BYTES) return line;
        LinkedHashMap<String, Object> reduced = new LinkedHashMap<>(document);
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        if (document.get("values") instanceof Map<?, ?> source) {
            for (String key : List.of("outcome", "diagnosticCode", "errorType", "reason", "status", "phase", "elapsedMs",
                "requestId", "correlationId", "mutationId", "generation", "revision")) {
                if (source.containsKey(key)) fields.put(key, source.get(key));
            }
            reduced.put("values", fields);
        }
        reduced.put("truncated", true);
        line = JsonValue.fromJava(reduced).canonicalText();
        while (line.getBytes(StandardCharsets.UTF_8).length >= ReSyncLifecycleDiagnosticPolicy.MAX_LINE_BYTES && !fields.isEmpty()) {
            fields.remove(new ArrayList<>(fields.keySet()).get(fields.size() - 1));
            line = JsonValue.fromJava(reduced).canonicalText();
        }
        if (line.getBytes(StandardCharsets.UTF_8).length >= ReSyncLifecycleDiagnosticPolicy.MAX_LINE_BYTES
            && reduced.get("identity") instanceof Map<?, ?> identity) {
            LinkedHashMap<String, Object> boundedIdentity = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : identity.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() != null) {
                    String text = entry.getValue().toString();
                    boundedIdentity.put(key, text.length() <= ReSyncLifecycleDiagnosticPolicy.MAX_VALUE_CHARS
                        ? entry.getValue() : "[truncated]");
                }
            }
            reduced.put("identity", boundedIdentity);
            line = JsonValue.fromJava(reduced).canonicalText();
        }
        return line;
    }

    private boolean writeRecovering(String line, long firstSequence, long lastSequence, String step) {
        IOException failure = null;
        for (int attempt = 1; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
            try {
                if (writer == null) {
                    openActive(false);
                }
                writeLine(line);
                flushWriter();
                confirmedSequence.accumulateAndGet(lastSequence, Math::max);
                markRecovered(failure);
                return true;
            } catch (IOException exception) {
                failure = exception;
                noteFailure(exception, step, attempt);
                closeWriterAfterFailure();
            }
        }
        failSink(failure, firstSequence, lastSequence, step + "-exhausted");
        return false;
    }

    private void writeLine(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ReSyncLifecycleDiagnosticPolicy.MAX_LINE_BYTES) throw new IOException("Diagnostic line is too large");
        if (activeBytes > 0L && activeBytes + bytes.length > maxFileBytes) {
            rotateAndOpen();
        }
        writeRawLine(line);
    }

    private void writeRawLine(String line) throws IOException {
        if (writer == null) throw new IOException("Diagnostic writer is unavailable");
        writer.write(line);
        writer.write('\n');
        activeBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    private void rotateAndOpen() throws IOException {
        closeWriter();
        if (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) && Files.size(activePath) > 0L) {
            moveWithoutReplacement(activePath, rotatedPath());
            failureProbe.check("post-move");
        }
        failureProbe.check("open");
        writer = Files.newBufferedWriter(activePath, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        activeBytes = Files.size(activePath);
        writeHeader(header());
        flushWriter();
        retainFiles();
    }

    private Path rotatedPath() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            rotationSequence++;
            Path target = directory.resolve(ROTATED_PREFIX + System.currentTimeMillis() + "."
                + rotationSequence + "." + processSessionId + ROTATED_SUFFIX);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target;
        }
        throw new IOException("Diagnostic rotation name is unavailable");
    }

    private void moveWithoutReplacement(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        } catch (FileAlreadyExistsException exception) {
            Files.move(source, rotatedPath());
        }
    }

    private void retainFiles() {
        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(this::rotated).sorted(Comparator.comparingLong(this::modified).reversed()).toList();
            long total = Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) ? Files.size(activePath) : 0L;
            long now = System.currentTimeMillis();
            for (Path path : files) {
                long size = Files.size(path);
                if (now - modified(path) > ReSyncLifecycleDiagnosticPolicy.RETENTION_AGE_MILLIS
                    || total + size > ReSyncLifecycleDiagnosticPolicy.RETENTION_BYTES) {
                    Files.deleteIfExists(path);
                } else {
                    total += size;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private boolean rotated(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(ROTATED_PREFIX) && name.endsWith(ROTATED_SUFFIX);
    }

    private long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private boolean recoverTrailingLine(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0L) return false;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.position(channel.size() - 1L);
            if (channel.read(last) != 1 || last.array()[0] == '\n') return false;
            long keep = channel.size();
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            while (keep > 0L) {
                int length = (int) Math.min(buffer.capacity(), keep);
                keep -= length;
                buffer.clear().limit(length);
                channel.position(keep);
                if (channel.read(buffer) <= 0) continue;
                for (int index = length - 1; index >= 0; index--) {
                    if (buffer.get(index) == '\n') {
                        channel.truncate(keep + index + 1L);
                        return true;
                    }
                }
            }
            channel.truncate(0L);
            return true;
        }
    }

    private void recordProgress(DiagnosticEvent event) {
        if (!ReSyncLifecycleDiagnosticPolicy.PROGRESS_STAGES.contains(event.stage())) return;
        long[] counts = progress.computeIfAbsent(event.stage(), ignored -> new long[3]);
        counts[0]++;
        counts[1] += event.elapsedMillis();
        counts[2] = Math.max(counts[2], event.elapsedMillis());
    }

    private Map<String, Object> progressSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        synchronized (lifecycleLock) {
            progress.forEach((stage, counts) -> snapshot.put(stage,
                Map.of("observed", counts[0], "totalElapsedMs", counts[1], "maxElapsedMs", counts[2])));
        }
        return snapshot;
    }

    private boolean throttled(DiagnosticEvent event) {
        String key = ReSyncLifecycleDiagnosticPolicy.progressKey(event);
        long now = System.nanoTime();
        synchronized (throttle) {
            Long previous = throttle.get(key);
            if (previous != null && now - previous < ReSyncLifecycleDiagnosticPolicy.PROGRESS_THROTTLE_NANOS) return true;
            if (previous == null && throttle.size() >= ReSyncLifecycleDiagnosticPolicy.MAX_THROTTLE_KEYS) {
                throttle.remove(throttle.keySet().iterator().next());
            }
            throttle.put(key, now);
            return false;
        }
    }

    private boolean offerQueued(java.util.Queue<QueuedEvent> queue, QueuedEvent queued) {
        if (queue.offer(queued)) {
            return true;
        }
        if (queued.event.priority() == Priority.TERMINAL) {
            QueuedEvent displaced = criticalQueue.stream()
                .filter(candidate -> candidate.event.priority() != Priority.TERMINAL)
                .findFirst().orElse(null);
            if (displaced != null && criticalQueue.remove(displaced) && criticalQueue.offer(queued)) {
                recordDrop(droppedCriticalPreempted);
                return true;
            }
            recordDrop(droppedTerminalCapacity);
            reportOutside("terminal-capacity", queued.sequence, queued.sequence);
            return false;
        }
        recordDrop(queue == criticalQueue ? droppedCriticalCapacity : droppedNormalCapacity);
        return false;
    }

    private void recordDrop(BrowserSafeState.LongValue reason) {
        dropped.incrementAndGet();
        reason.incrementAndGet();
    }

    private void noteFailure(Throwable failure, String step, int attempt) {
        failed.incrementAndGet();
        failureReason.set("recovering:" + step + ":" + failure.getClass().getSimpleName() + ":attempt=" + attempt);
    }

    private void markRecovered(Throwable failure) {
        if (failure != null) {
            failureReason.set("recovered:" + failure.getClass().getSimpleName());
        }
    }

    private void failSink(Throwable failure, long firstSequence, long lastSequence, String step) {
        failed.incrementAndGet();
        String type = failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
        long first = Math.max(1L, firstSequence);
        long last = Math.max(first, Math.max(lastSequence, acceptedSequence.get()));
        failureReason.set(step + ":" + type + ":lost=" + first + "-" + last);
        if (!stopRequested.get()) {
            state.set(State.FAILED);
            reportOutside(step + ":" + type, first, last);
        }
    }

    private long firstPendingSequence() {
        long first = Long.MAX_VALUE;
        long confirmed = confirmedSequence.get();
        if (writingSequence > confirmed) first = Math.min(first, writingSequence);
        if (heldFirstSequence > confirmed) first = Math.min(first, heldFirstSequence);
        QueuedEvent buffered = bufferedNormal;
        QueuedEvent critical = criticalQueue.peek();
        QueuedEvent normal = normalQueue.peek();
        if (buffered != null && buffered.sequence() > confirmed) first = Math.min(first, buffered.sequence());
        if (critical != null && critical.sequence() > confirmed) first = Math.min(first, critical.sequence());
        if (normal != null && normal.sequence() > confirmed) first = Math.min(first, normal.sequence());
        return first == Long.MAX_VALUE ? 0L : first;
    }

    private void reportOutside(String reason, long firstSequence, long lastSequence) {
        System.err.println("Remotely ReSync lifecycle diagnostics warning: reason=" + reason + " lostSequence="
            + firstSequence + "-" + lastSequence);
    }

    private void flushWriter() throws IOException {
        BufferedWriter current = writer;
        if (current == null) throw new IOException("Diagnostic writer is unavailable");
        failureProbe.check("flush");
        current.flush();
    }

    private void completeFlush() {
        FlushRequest request;
        synchronized (lifecycleLock) {
            request = flushRequest.get();
            if (request == null) return;
            if ((state.get() == State.READY || state.get() == State.PAUSED) && hasPendingAtOrBefore(request.watermark)) return;
            flushRequest.set(null);
        }
        request.latch.countDown();
    }

    private QueuedEvent pollNextEvent() throws InterruptedException, IOException {
        synchronized (lifecycleLock) {
            QueuedEvent next = pollAvailableNext();
            if (next != null) return next;
        }
        failureProbe.check("normal-poll");
        QueuedEvent normal = normalQueue.poll();
        if (normal == null) {
            Thread.sleep(ReSyncLifecycleDiagnosticPolicy.MAX_BATCH_DELAY_MILLIS);
            normal = normalQueue.poll();
        }
        if (normal == null) return null;
        bufferedNormal = normal;
        failureProbe.check("post-normal-poll");
        synchronized (lifecycleLock) {
            return pollAvailableNext();
        }
    }

    private QueuedEvent pollAvailableNext() {
        QueuedEvent critical = criticalQueue.peek();
        QueuedEvent normal = normalQueue.peek();
        QueuedEvent next = bufferedNormal;
        int source = next == null ? 0 : 1;
        if (critical != null && (next == null || critical.sequence() < next.sequence())) {
            next = critical;
            source = 2;
        }
        if (normal != null && (next == null || normal.sequence() < next.sequence())) {
            next = normal;
            source = 3;
        }
        if (source == 1) {
            bufferedNormal = null;
            holdSequence(next);
            return next;
        }
        if (source == 2) {
            next = criticalQueue.poll();
            holdSequence(next);
            return next;
        }
        if (source == 3) {
            next = normalQueue.poll();
            holdSequence(next);
            return next;
        }
        return null;
    }

    private void holdSequence(QueuedEvent event) {
        if (event != null && (heldFirstSequence <= 0L || event.sequence() < heldFirstSequence)) {
            heldFirstSequence = event.sequence();
        }
    }

    private boolean hasPendingAtOrBefore(long watermark) {
        return writingSequence > 0L && writingSequence <= watermark
            || heldFirstSequence > 0L && heldFirstSequence <= watermark
            || bufferedNormal != null && bufferedNormal.sequence() <= watermark
            || hasPendingAtOrBefore(criticalQueue, watermark) || hasPendingAtOrBefore(normalQueue, watermark);
    }

    private boolean hasPendingAtOrBefore(java.util.Queue<QueuedEvent> queue, long watermark) {
        for (QueuedEvent event : queue) {
            if (event.sequence <= watermark) return true;
        }
        return false;
    }

    private void closeWriter() throws IOException {
        BufferedWriter current = writer;
        writer = null;
        if (current == null) {
            return;
        }
        IOException failure = null;
        try {
            current.flush();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            current.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private void closeWriterQuietly() {
        try {
            closeWriter();
        } catch (IOException exception) {
            if (!stopRequested.get()) {
                failSink(exception, firstPendingSequence(), acceptedSequence.get(), "close");
            }
        }
    }

    private void closeWriterAfterFailure() {
        BufferedWriter current = writer;
        writer = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void join(long timeoutMillis) {
        try {
            writerThread.join(timeoutMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record QueuedEvent(DiagnosticEvent event, String thread, long timestamp, long sequence) {
    }

    @FunctionalInterface
    interface FailureProbe {
        void check(String step) throws IOException;
    }

    private static final class FlushRequest {
        private final BrowserSafeState.Latch latch = new BrowserSafeState.Latch(1);
        private long watermark;

        private FlushRequest(long watermark) {
            this.watermark = watermark;
        }
    }
}
