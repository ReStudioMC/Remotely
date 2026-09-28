package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.backend.TransferSource;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserServerFileTransferTest {
    @Test
    void acceptsTheReportedWorldArchiveSize() {
        assertTrue(BrowserServerFileTransfer.MAX_UPLOAD_BYTES >= 2L * 1024 * 1024 * 1024);
    }

    @Test
    void streamsBoundedChunksThroughAHostedUploadSession() {
        FakeUploadApi api = new FakeUploadApi(TransferSource.MAX_CHUNK_BYTES);
        BrowserServerFileTransfer transfer = new BrowserServerFileTransfer(api, "server", TaskScheduler.direct());
        byte[] first = new byte[TransferSource.MAX_CHUNK_BYTES];
        byte[] second = new byte[17];
        TransferSource source = new TransferSource() {
            private int index;

            @Override
            public String name() {
                return "plugins.zip";
            }

            @Override
            public long size() {
                return first.length + second.length;
            }

            @Override
            public Async<Chunk> next() {
                return switch (index++) {
                    case 0 -> Async.completed(new Chunk(first, false));
                    case 1 -> Async.completed(new Chunk(second, true));
                    default -> Async.completed(Chunk.end());
                };
            }
        };

        Async<Void> result = transfer.upload(List.of(source), RemotePath.root(), null);

        assertTrue(result.isDone());
        assertNull(result.failure());
        assertEquals(List.of(first.length, second.length), api.chunks);
        assertEquals(1, api.completions);
    }

    @Test
    void reportsUploadProgressFromPreparationThroughCompletion() {
        FakeUploadApi api = new FakeUploadApi(TransferSource.MAX_CHUNK_BYTES);
        BrowserServerFileTransfer transfer = new BrowserServerFileTransfer(api, "server", TaskScheduler.direct());
        TransferSource source = TransferSource.fromBytes("plugin.jar", new byte[17]);
        List<Long> transferred = new ArrayList<>();
        List<Long> totals = new ArrayList<>();

        Async<Void> result = transfer.upload(List.of(source), RemotePath.root(), (sent, total) -> {
            transferred.add(sent);
            totals.add(total);
        });

        assertTrue(result.isDone());
        assertNull(result.failure());
        assertEquals(List.of(0L, 17L), transferred);
        assertEquals(List.of(17L, 17L), totals);
    }

    @Test
    void reportsTheSafeDeliveryFailureFromTheHostedSession() {
        FakeUploadApi api = new FakeUploadApi(TransferSource.MAX_CHUNK_BYTES);
        api.failure = "Server Restarted During Upload. Retry Upload";
        api.failOnPoll = true;
        BrowserServerFileTransfer transfer = new BrowserServerFileTransfer(api, "server", TaskScheduler.direct());

        Async<Void> result = transfer.upload(List.of(TransferSource.fromBytes("plugin.jar", new byte[1])), RemotePath.root(), null);

        assertTrue(result.isDone());
        assertEquals(api.failure, result.failure().getMessage());
        assertEquals(1, api.completions);
    }

    @Test
    void ambiguousCompletionUsesAcceptedStatusWithoutRepeatingTheRequest() {
        FakeUploadApi api = new FakeUploadApi(TransferSource.MAX_CHUNK_BYTES);
        api.ambiguousCompletion = true;
        BrowserServerFileTransfer transfer = new BrowserServerFileTransfer(api, "server", TaskScheduler.direct());

        Async<Void> result = transfer.upload(List.of(TransferSource.fromBytes("plugin.jar", new byte[1])), RemotePath.root(), null);

        assertTrue(result.isDone());
        assertNull(result.failure());
        assertEquals(1, api.completions);
    }

    @Test
    void oversizedUploadFailsBeforeTransport() {
        BrowserServerFileTransfer transfer = new BrowserServerFileTransfer(null, "server");
        TransferSource source = new TransferSource() {
            @Override
            public String name() {
                return "large.jar";
            }

            @Override
            public long size() {
                return BrowserServerFileTransfer.MAX_UPLOAD_BYTES + 1;
            }

            @Override
            public Async<Chunk> next() {
                throw new AssertionError("Oversized source must not be read");
            }
        };

        var result = transfer.upload(List.of(source), RemotePath.root(), (sent, total) -> {
        });

        assertTrue(result.isDone());
        assertTrue(result.failure() instanceof IllegalArgumentException);
    }

    @Test
    void emptyNonterminalSourceChunkFailsWithoutRecursing() {
        FakeUploadApi api = new FakeUploadApi(TransferSource.MAX_CHUNK_BYTES);
        BrowserServerFileTransfer transfer = new BrowserServerFileTransfer(api, "server", TaskScheduler.direct());
        TransferSource source = new TransferSource() {
            @Override
            public String name() {
                return "world.zip";
            }

            @Override
            public long size() {
                return 1;
            }

            @Override
            public Async<Chunk> next() {
                return Async.completed(new Chunk(new byte[0], false));
            }
        };

        Async<Void> result = transfer.upload(List.of(source), RemotePath.root(), null);

        assertTrue(result.isDone());
        assertTrue(result.failure() instanceof IllegalStateException);
    }

    @Test
    void virtualRootResolvesExistingServerProvider() {
        RemoteFileSystemProvider provider = RemoteFileSystemProvider.unavailable();
        BrowserServerFileRootProvider root = new BrowserServerFileRootProvider(List.of(
                new BrowserServerFileRootProvider.ServerRoot("server-1", "Survival", provider)));

        RemoteFileSystemProvider.FileEntry entry = root.ls(RemotePath.root()).value().getFirst();
        RemoteFileSystemProvider.DirectoryTarget target = root.resolveDirectory(entry);

        assertEquals("Survival", entry.displayName());
        assertSame(provider, target.provider());
        assertEquals(RemotePath.root(), target.path());
    }

    private static final class FakeUploadApi implements BrowserServerFileTransfer.UploadApi {
        private UUID uploadId;
        private final int chunkSize;
        private final List<Integer> chunks = new ArrayList<>();
        private long size;
        private long offset;
        private int completions;
        private String failure;
        private boolean failOnPoll;
        private boolean ambiguousCompletion;
        private boolean completedAfterAmbiguous;

        private FakeUploadApi(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        @Override
        public Async<BrowserRemotelyServerApi.HostedUploadView> startHostedUpload(String serverId, UUID uploadId, String directory, String filename, long size) {
            this.size = size;
            this.uploadId = uploadId;
            return Async.completed(view());
        }

        @Override
        public Async<BrowserRemotelyServerApi.HostedUploadView> hostedUploadStatus(String serverId, UUID uploadId) {
            BrowserRemotelyServerApi.HostedUploadView view = view();
            view.delivered = completedAfterAmbiguous;
            if (failOnPoll) {
                view.failed = true;
                view.failure = failure;
            }
            return Async.completed(view);
        }

        @Override
        public Async<BrowserRemotelyServerApi.HostedUploadView> writeHostedUpload(String serverId, UUID uploadId, long offset, byte[] bytes,
                                                                                  BiConsumer<Long, Long> progress) {
            chunks.add(bytes.length);
            if (progress != null) progress.accept((long) bytes.length, (long) bytes.length);
            this.offset += bytes.length;
            return Async.completed(view());
        }

        @Override
        public Async<BrowserRemotelyServerApi.HostedUploadView> completeHostedUpload(String serverId, UUID uploadId) {
            completions++;
            if (ambiguousCompletion && completions == 1) {
                completedAfterAmbiguous = true;
                return Async.failed(new IllegalStateException("Completion Response Was Lost"));
            }
            BrowserRemotelyServerApi.HostedUploadView view = view();
            view.delivered = failure == null;
            view.delivering = failOnPoll;
            view.failed = failure != null && !failOnPoll;
            view.failure = view.failed ? failure : null;
            return Async.completed(view);
        }

        @Override
        public Async<Void> cancelHostedUpload(String serverId, UUID uploadId) {
            return Async.completed(null);
        }

        private BrowserRemotelyServerApi.HostedUploadView view() {
            BrowserRemotelyServerApi.HostedUploadView view = new BrowserRemotelyServerApi.HostedUploadView();
            view.uploadId = uploadId;
            view.size = size;
            view.offset = offset;
            view.chunkSize = chunkSize;
            return view;
        }
    }
}
