package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.backend.TransferSink;
import restudio.rebase.backend.TransferSource;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.UUID;

final class BrowserServerFileTransfer {
    static final long MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long PROGRESS_UPDATE_INTERVAL_MILLIS = 150L;
    private static final long DELIVERY_WAIT_MILLIS = Duration.ofMinutes(65).toMillis();
    private final BrowserRemotelyServerApi api;
    private final UploadApi uploadApi;
    private final String serverId;
    private final TaskScheduler scheduler;

    BrowserServerFileTransfer(BrowserRemotelyServerApi api, String serverId) {
        this(api, serverId, TaskSchedulers.current());
    }

    BrowserServerFileTransfer(BrowserRemotelyServerApi api, String serverId, TaskScheduler scheduler) {
        this(api, api, serverId, scheduler);
    }

    BrowserServerFileTransfer(UploadApi uploadApi, String serverId, TaskScheduler scheduler) {
        this(null, uploadApi, serverId, scheduler);
    }

    private BrowserServerFileTransfer(BrowserRemotelyServerApi api, UploadApi uploadApi, String serverId, TaskScheduler scheduler) {
        this.api = api;
        this.uploadApi = uploadApi;
        this.serverId = serverId;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    Async<Void> upload(List<TransferSource> sources, RemotePath destination, BiConsumer<Long, Long> progress) {
        if (sources == null || sources.isEmpty()) return Async.completed(null);
        if (sources.stream().anyMatch(source -> source == null || invalidName(source.name()))) {
            sources.forEach(BrowserTransferBridge::release);
            return Async.failed(new IllegalArgumentException("File Name Is Invalid"));
        }
        long total = 0;
        for (TransferSource source : sources) {
            long size = source.size();
            if (size < 0 || size > MAX_UPLOAD_BYTES) {
                sources.forEach(BrowserTransferBridge::release);
                return Async.failed(new IllegalArgumentException("File Upload Exceeds Limit"));
            }
            try {
                total = Math.addExact(total, size);
            } catch (ArithmeticException exception) {
                sources.forEach(BrowserTransferBridge::release);
                return Async.failed(new IllegalArgumentException("File Upload Exceeds Limit"));
            }
        }
        Async<Void> result = Async.pending();
        BiConsumer<Long, Long> throttledProgress = throttleProgress(progress);
        Async<?>[] active = new Async<?>[1];
        UUID[] activeUpload = new UUID[1];
        result.onCancel(() -> {
            Async<?> request = active[0];
            if (request != null) request.cancel();
            UUID uploadId = activeUpload[0];
            if (uploadId != null) uploadApi.cancelHostedUpload(serverId, uploadId);
            sources.forEach(BrowserTransferBridge::release);
        });
        if (throttledProgress != null) throttledProgress.accept(0L, total);
        uploadNext(sources, 0, normalizeDirectory(destination), 0, total, throttledProgress, active, activeUpload, result);
        return result;
    }

    static BiConsumer<Long, Long> throttleProgress(BiConsumer<Long, Long> callback) {
        if (callback == null) return null;
        return new BiConsumer<>() {
            private long lastUpdate;
            private long lastTransferred = Long.MIN_VALUE;

            @Override
            public void accept(Long transferred, Long total) {
                long currentTransferred = transferred == null ? 0L : Math.max(0L, transferred);
                long currentTotal = total == null ? 0L : Math.max(0L, total);
                if (lastTransferred != Long.MIN_VALUE) currentTransferred = Math.max(currentTransferred, lastTransferred);
                if (currentTransferred == lastTransferred) return;
                long now = System.currentTimeMillis();
                if (currentTransferred < currentTotal && lastTransferred != Long.MIN_VALUE
                        && now - lastUpdate < PROGRESS_UPDATE_INTERVAL_MILLIS) return;
                lastTransferred = currentTransferred;
                lastUpdate = now;
                callback.accept(currentTransferred, currentTotal);
            }
        };
    }

    Async<Void> download(List<RemotePath> sources, TransferSink destination, BiConsumer<Long, Long> progress, BooleanSupplier cancelled) {
        if (sources == null || sources.size() != 1) return Async.failed(new IllegalArgumentException("Select One File To Download"));
        if (destination == null) return Async.failed(new IllegalArgumentException("Download Destination Is Required"));
        RemotePath source = sources.getFirst();
        if (source == null || source.isRoot()) return Async.failed(new IllegalArgumentException("Select A File To Download"));
        return api.downloadFileData(serverId, normalizeFile(source), destination, progress, cancelled);
    }

    private void uploadNext(List<TransferSource> sources, int index, String directory, long sent, long total,
                            BiConsumer<Long, Long> progress, Async<?>[] active, UUID[] activeUpload, Async<Void> result) {
        if (result.isDone()) return;
        if (index >= sources.size()) {
            result.complete(null);
            return;
        }
        TransferSource source = sources.get(index);
        UUID requestedUpload = UUID.randomUUID();
        activeUpload[0] = requestedUpload;
        Async<BrowserRemotelyServerApi.HostedUploadView> request = uploadApi.startHostedUpload(serverId, requestedUpload, directory, source.name(), source.size());
        active[0] = request;
        request.whenComplete((upload, failure) -> {
            if (result.isDone()) {
                if (upload != null && upload.uploadId != null) uploadApi.cancelHostedUpload(serverId, upload.uploadId);
                return;
            }
            if (failure != null) {
                fail(sources, activeUpload, result, failure);
                return;
            }
            if (upload == null || upload.uploadId == null || upload.size != source.size() || upload.offset != 0
                    || upload.chunkSize <= 0 || upload.chunkSize > 8 * 1024 * 1024) {
                fail(sources, activeUpload, result, new IllegalStateException("Hosted Upload Session Is Invalid"));
                return;
            }
            activeUpload[0] = upload.uploadId;
            uploadChunk(sources, index, source, upload, directory, sent, total, progress, active, activeUpload, result);
        });
    }

    private void uploadChunk(List<TransferSource> sources, int index, TransferSource source,
                             BrowserRemotelyServerApi.HostedUploadView upload, String directory, long sent, long total,
                             BiConsumer<Long, Long> progress, Async<?>[] active, UUID[] activeUpload, Async<Void> result) {
        if (result.isDone()) return;
        if (upload.offset == upload.size) {
            completeFile(sources, index, source, upload, directory, sent, total, progress, active, activeUpload, result);
            return;
        }
        Async<ChunkBatch> read = readBatch(source, upload.chunkSize);
        active[0] = read;
        read.whenComplete((batch, failure) -> {
            if (result.isDone()) return;
            if (failure != null) {
                fail(sources, activeUpload, result, failure);
                return;
            }
            if (batch.bytes.length == 0 || upload.offset > upload.size - batch.bytes.length) {
                fail(sources, activeUpload, result, new IllegalStateException("Upload Ended Before Its Declared Size"));
                return;
            }
            long offset = upload.offset;
            BiConsumer<Long, Long> chunkProgress = progress == null ? null
                    : (loaded, ignored) -> progress.accept(sent + offset + Math.min(batch.bytes.length, loaded), total);
            Async<BrowserRemotelyServerApi.HostedUploadView> delivered = deliver(upload.uploadId, offset, batch.bytes, 0, chunkProgress);
            active[0] = delivered;
            delivered.whenComplete((next, uploadFailure) -> {
                if (result.isDone()) return;
                if (uploadFailure != null) {
                    fail(sources, activeUpload, result, uploadFailure);
                    return;
                }
                if (progress != null) progress.accept(sent + next.offset, total);
                if (batch.last && next.offset != next.size || !batch.last && next.offset == next.size) {
                    fail(sources, activeUpload, result, new IllegalStateException("Upload Size Does Not Match Source"));
                    return;
                }
                uploadChunk(sources, index, source, next, directory, sent, total, progress, active, activeUpload, result);
            });
        });
    }

    private Async<BrowserRemotelyServerApi.HostedUploadView> deliver(UUID uploadId, long offset, byte[] bytes, int attempt,
                                                                     BiConsumer<Long, Long> progress) {
        return uploadApi.writeHostedUpload(serverId, uploadId, offset, bytes, progress).exceptionallyCompose(failure -> {
            if (attempt >= 2) return Async.failed(failure);
            return uploadApi.hostedUploadStatus(serverId, uploadId).thenCompose(current -> {
                long end = offset + bytes.length;
                if (current.offset == end) return Async.completed(current);
                if (current.offset == offset) return deliver(uploadId, offset, bytes, attempt + 1, progress);
                return Async.failed(new IllegalStateException("Upload Resume Offset Is Invalid"));
            });
        });
    }

    private void completeFile(List<TransferSource> sources, int index, TransferSource source,
                              BrowserRemotelyServerApi.HostedUploadView upload, String directory, long sent, long total,
                              BiConsumer<Long, Long> progress, Async<?>[] active, UUID[] activeUpload, Async<Void> result) {
        Async<Void> complete = complete(upload.uploadId);
        active[0] = complete;
        complete.whenComplete((ignored, failure) -> {
            BrowserTransferBridge.release(source);
            if (result.isDone()) return;
            if (failure != null) {
                fail(sources, activeUpload, result, failure);
                return;
            }
            activeUpload[0] = null;
            if (progress != null) progress.accept(sent + source.size(), total);
            uploadNext(sources, index + 1, directory, sent + source.size(), total, progress, active, activeUpload, result);
        });
    }

    private Async<Void> complete(UUID uploadId) {
        long deadline = System.currentTimeMillis() + DELIVERY_WAIT_MILLIS;
        return completeRequest(uploadId, 0).thenCompose(status -> awaitDelivery(uploadId, status, deadline));
    }

    private Async<BrowserRemotelyServerApi.HostedUploadView> completeRequest(UUID uploadId, int attempt) {
        return uploadApi.completeHostedUpload(serverId, uploadId).exceptionallyCompose(failure -> {
            if (attempt >= 2) return Async.failed(failure);
            return uploadApi.hostedUploadStatus(serverId, uploadId).thenCompose(status -> {
                if (status == null || status.delivering || status.delivered || status.failed) return Async.completed(status);
                return completeRequest(uploadId, attempt + 1);
            });
        });
    }

    private Async<Void> awaitDelivery(UUID uploadId, BrowserRemotelyServerApi.HostedUploadView status, long deadline) {
        if (status == null || status.offset != status.size) return Async.failed(new IllegalStateException("Upload Is Incomplete"));
        if (status.delivered) return Async.completed(null);
        if (status.failed) return Async.failed(new IllegalStateException(
                status.failure == null || status.failure.isBlank() ? "Remote File Transfer Failed. Retry Upload" : status.failure));
        if (!status.delivering) return Async.failed(new IllegalStateException("Hosted Upload Could Not Be Finalized"));
        if (System.currentTimeMillis() >= deadline) return Async.failed(new IllegalStateException("Remote File Transfer Timed Out. Retry Upload"));
        Async<Void> result = Async.pending();
        TaskScheduler.ScheduledTask task = scheduler.schedule(() -> uploadApi.hostedUploadStatus(serverId, uploadId)
                .whenComplete((next, failure) -> {
                    if (failure != null) result.fail(failure);
                    else awaitDelivery(uploadId, next, deadline).whenComplete((ignored, deliveryFailure) -> {
                        if (deliveryFailure == null) result.complete(null);
                        else result.fail(deliveryFailure);
                    });
                }), Duration.ofSeconds(2));
        result.onCancel(task::cancel);
        return result;
    }

    private Async<ChunkBatch> readBatch(TransferSource source, int limit) {
        Async<ChunkBatch> result = Async.pending();
        fillBatch(source, limit, new ByteArrayOutputStream(limit), result);
        return result;
    }

    private void fillBatch(TransferSource source, int limit, ByteArrayOutputStream output, Async<ChunkBatch> result) {
        if (result.isDone()) return;
        Async<TransferSource.Chunk> next = nextChunk(source);
        result.onCancel(next::cancel);
        next.whenComplete((chunk, failure) -> {
            if (result.isDone()) return;
            if (failure != null) {
                result.fail(failure);
                return;
            }
            byte[] bytes = chunk == null ? new byte[0] : chunk.bytes();
            if (bytes.length == 0 && chunk != null && !chunk.last()) {
                result.fail(new IllegalStateException("Transfer Source Returned An Empty Chunk"));
                return;
            }
            if (output.size() > limit - bytes.length) {
                result.fail(new IllegalStateException("Transfer Chunk Exceeds Upload Session Limit"));
                return;
            }
            output.write(bytes, 0, bytes.length);
            boolean last = chunk == null || chunk.last();
            if (last || output.size() == limit) result.complete(new ChunkBatch(output.toByteArray(), last));
            else fillBatch(source, limit, output, result);
        });
    }

    private void fail(List<TransferSource> sources, UUID[] activeUpload, Async<Void> result, Throwable failure) {
        UUID uploadId = activeUpload[0];
        activeUpload[0] = null;
        if (uploadId != null) uploadApi.cancelHostedUpload(serverId, uploadId);
        sources.forEach(BrowserTransferBridge::release);
        result.fail(failure);
    }

    private Async<TransferSource.Chunk> nextChunk(TransferSource source) {
        Async<TransferSource.Chunk> result = Async.pending();
        TaskScheduler.ScheduledTask[] scheduled = new TaskScheduler.ScheduledTask[1];
        Async<?>[] sourceRequest = new Async<?>[1];
        try {
            scheduled[0] = scheduler.schedule(() -> {
                if (result.isDone()) return;
                try {
                    Async<TransferSource.Chunk> request = Objects.requireNonNull(source.next(), "transfer chunk result");
                    sourceRequest[0] = request;
                    request.whenComplete((chunk, failure) -> {
                        if (result.isDone()) return;
                        if (failure == null) result.complete(chunk);
                        else result.fail(failure);
                    });
                } catch (Throwable failure) {
                    result.fail(failure);
                }
            }, Duration.ZERO);
        } catch (Throwable failure) {
            result.fail(failure);
        }
        result.onCancel(() -> {
            TaskScheduler.ScheduledTask task = scheduled[0];
            if (task != null) task.cancel();
            Async<?> request = sourceRequest[0];
            if (request != null) request.cancel();
        });
        return result;
    }

    private static String normalizeDirectory(RemotePath path) {
        String value = path == null || path.isRoot() ? "/" : path.asString();
        if (value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || value.contains("..")) throw new IllegalArgumentException("File Path Is Invalid");
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String normalizeFile(RemotePath path) {
        String value = path.asString();
        if (value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || value.contains("..")) throw new IllegalArgumentException("File Path Is Invalid");
        return value.startsWith("/") ? value : "/" + value;
    }

    private static boolean invalidName(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return true;
        return name.chars().anyMatch(character -> character < 32 || character == 127);
    }

    private record ChunkBatch(byte[] bytes, boolean last) {
    }

    interface UploadApi {
        Async<BrowserRemotelyServerApi.HostedUploadView> startHostedUpload(String serverId, UUID uploadId, String directory, String filename, long size);

        Async<BrowserRemotelyServerApi.HostedUploadView> hostedUploadStatus(String serverId, UUID uploadId);

        Async<BrowserRemotelyServerApi.HostedUploadView> writeHostedUpload(String serverId, UUID uploadId, long offset, byte[] bytes,
                                                                           BiConsumer<Long, Long> progress);

        Async<BrowserRemotelyServerApi.HostedUploadView> completeHostedUpload(String serverId, UUID uploadId);

        Async<Void> cancelHostedUpload(String serverId, UUID uploadId);
    }
}
