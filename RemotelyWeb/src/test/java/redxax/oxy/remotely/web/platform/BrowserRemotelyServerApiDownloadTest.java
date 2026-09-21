package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import restudio.rebase.backend.TransferSink;
import restudio.rebase.backend.TransferSource;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.http.HttpHeaders;
import restudio.rescreen.platform.http.HttpResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserRemotelyServerApiDownloadTest {
    @Test
    void successfulDownloadWaitsForWritesAndClosesOnce() throws Exception {
        RecordingSink sink = new RecordingSink();
        Async<Void> result = Async.pending();
        BrowserRemotelyServerApi.HostedDownloadLifecycle lifecycle = new BrowserRemotelyServerApi.HostedDownloadLifecycle(
                result, sink, null, () -> false, 1024);
        Async<HttpResponse<Void>> request = Async.pending();
        lifecycle.attach(request);

        lifecycle.accept(new byte[]{1, 2, 3});
        lifecycle.complete(response(200), null);

        assertFalse(result.isDone());
        sink.completeWrite();

        assertTrue(result.isDone());
        assertEquals(1, sink.closeCount.get());
        assertEquals(List.of(3), sink.chunks);
        assertFalse(request.isCancelled());
    }

    @Test
    void writeFailureCancelsRequestStopsChunksAndTerminatesSinkOnce() throws Exception {
        RecordingSink sink = new RecordingSink();
        sink.writeFailure = new IllegalStateException("sink failed");
        Async<Void> result = Async.pending();
        BrowserRemotelyServerApi.HostedDownloadLifecycle lifecycle = new BrowserRemotelyServerApi.HostedDownloadLifecycle(
                result, sink, null, () -> false, 1024);
        Async<HttpResponse<Void>> request = Async.pending();
        lifecycle.attach(request);

        assertThrows(IllegalStateException.class, () -> lifecycle.accept(new byte[]{1}));
        assertThrows(Async.Cancellation.class, () -> lifecycle.accept(new byte[]{2}));

        assertTrue(result.isDone());
        assertTrue(result.failure() instanceof IllegalStateException);
        assertTrue(request.isCancelled());
        assertEquals(1, sink.closeCount.get());
        assertEquals(List.of(), sink.chunks);
    }

    @Test
    void cancellationWaitsForQueuedWriteAndDoesNotFinalizeBeforeItSettles() throws Exception {
        RecordingSink sink = new RecordingSink();
        Async<Void> result = Async.pending();
        BrowserRemotelyServerApi.HostedDownloadLifecycle lifecycle = new BrowserRemotelyServerApi.HostedDownloadLifecycle(
                result, sink, null, () -> false, 1024);
        Async<HttpResponse<Void>> request = Async.pending();
        lifecycle.attach(request);

        lifecycle.accept(new byte[]{1});
        result.cancel();

        assertTrue(result.isCancelled());
        assertTrue(request.isCancelled());
        assertEquals(0, sink.closeCount.get());
        sink.completeWrite();
        assertEquals(1, sink.closeCount.get());
        assertThrows(Async.Cancellation.class, () -> lifecycle.accept(new byte[]{2}));
    }

    @Test
    void nonSuccessResponseAbortsAfterQueuedWritesWithoutFinalizingResult() throws Exception {
        RecordingSink sink = new RecordingSink();
        Async<Void> result = Async.pending();
        BrowserRemotelyServerApi.HostedDownloadLifecycle lifecycle = new BrowserRemotelyServerApi.HostedDownloadLifecycle(
                result, sink, null, () -> false, 1024);
        Async<HttpResponse<Void>> request = Async.pending();
        lifecycle.attach(request);

        lifecycle.accept(new byte[]{1});
        lifecycle.complete(response(503), null);

        assertFalse(result.isDone());
        sink.completeWrite();

        assertTrue(result.isDone());
        assertTrue(result.failure() instanceof IllegalStateException);
        assertTrue(request.isCancelled());
        assertEquals(1, sink.closeCount.get());
        assertEquals(1, sink.chunks.size());
    }

    @Test
    void deviceDownloadsStreamToNamedSinksInSelectionOrder() {
        List<String> destinations = new ArrayList<>();
        List<String> started = new ArrayList<>();
        Async<Void> first = Async.pending();
        Async<Void> result = BrowserRemotelyServerApi.downloadFiles(
                List.of("/world/level.dat", "/plugins/Essentials.jar"),
                name -> {
                    destinations.add(name);
                    return new RecordingSink();
                },
                (path, sink) -> {
                    started.add(path);
                    return started.size() == 1 ? first : Async.completed(null);
                });

        assertFalse(result.isDone());
        assertEquals(List.of("level.dat"), destinations);
        assertEquals(List.of("/world/level.dat"), started);
        first.complete(null);

        assertTrue(result.isDone());
        assertEquals(List.of("level.dat", "Essentials.jar"), destinations);
        assertEquals(List.of("/world/level.dat", "/plugins/Essentials.jar"), started);
    }

    @Test
    void deviceDownloadFailureStopsTheRemainingBatch() {
        List<String> started = new ArrayList<>();
        Async<Void> result = BrowserRemotelyServerApi.downloadFiles(
                List.of("/one.jar", "/two.jar", "/three.jar"), name -> new RecordingSink(),
                (path, sink) -> {
                    started.add(path);
                    return "/two.jar".equals(path)
                            ? Async.failed(new IllegalStateException("transfer failed")) : Async.completed(null);
                });

        assertTrue(result.isDone());
        assertTrue(result.failure() instanceof IllegalStateException);
        assertEquals(List.of("/one.jar", "/two.jar"), started);
    }

    @Test
    void failureBeforeStreamingAbortsTheDestination() {
        RecordingSink sink = new RecordingSink();
        Async<Void> result = BrowserRemotelyServerApi.downloadFiles(List.of("/one.jar"), name -> sink,
                (path, destination) -> { throw new IllegalStateException("request creation failed"); });

        assertTrue(result.isDone());
        assertTrue(result.failure() instanceof IllegalStateException);
        assertEquals(1, sink.closeCount.get());
    }

    @Test
    void cancellingDeviceDownloadCancelsTheActiveStreamAndStopsTheBatch() {
        Async<Void> transfer = Async.pending();
        List<String> started = new ArrayList<>();
        Async<Void> result = BrowserRemotelyServerApi.downloadFiles(
                List.of("/one.jar", "/two.jar"), name -> new RecordingSink(),
                (path, sink) -> {
                    started.add(path);
                    return transfer;
                });

        result.cancel();

        assertTrue(result.isCancelled());
        assertTrue(transfer.isCancelled());
        assertEquals(List.of("/one.jar"), started);
    }

    private static HttpResponse<Void> response(int status) {
        return new HttpResponse<>(status, HttpHeaders.empty(), null);
    }

    private static final class RecordingSink implements TransferSink {
        private final List<Integer> chunks = new ArrayList<>();
        private final AtomicInteger closeCount = new AtomicInteger();
        private Async<Void> pendingWrite;
        private Throwable writeFailure;

        @Override
        public String name() {
            return "download.bin";
        }

        @Override
        public Async<Void> write(TransferSource.Chunk chunk) {
            if (writeFailure != null) return Async.failed(writeFailure);
            chunks.add(chunk.size());
            pendingWrite = Async.pending();
            return pendingWrite;
        }

        @Override
        public Async<Void> close() {
            closeCount.incrementAndGet();
            return Async.completed(null);
        }

        private void completeWrite() {
            if (pendingWrite != null) {
                pendingWrite.complete(null);
                pendingWrite = null;
            }
        }
    }
}
