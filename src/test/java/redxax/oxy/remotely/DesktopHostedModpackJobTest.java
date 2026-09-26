package redxax.oxy.remotely;

import org.junit.jupiter.api.Test;
import restudio.rebase.resource.marketplace.HostedModpackSelection;
import restudio.rebase.restudio.api.ReStudioApiClient;
import restudio.rebase.restudio.api.models.ServerModels;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopHostedModpackJobTest {
    @Test
    void waitsForDurableInstallAndKeepsDraftIdentity() {
        FakeClient client = new FakeClient();
        DesktopRemotelyServerApi api = new DesktopRemotelyServerApi(client);
        UUID jobId = UUID.randomUUID();
        client.submissions.add(CompletableFuture.completedFuture(job(jobId, "PENDING", null)));
        client.observations.add(CompletableFuture.completedFuture(job(jobId, "COMPLETED", null)));

        api.installHostedModpack("server", selection(), "draft:request-123").join();

        assertEquals(List.of("draft:request-123"), client.requestKeys);
        assertEquals("install", client.requests.getFirst().operation());
        assertEquals("version", client.requests.getFirst().versionId());
        assertEquals(List.of(jobId), client.observedJobs);
        assertFalse(client.legacyCalled);
    }

    @Test
    void failsWhenDurableInstallFails() {
        FakeClient client = new FakeClient();
        DesktopRemotelyServerApi api = new DesktopRemotelyServerApi(client);
        client.submissions.add(CompletableFuture.completedFuture(job(UUID.randomUUID(), "FAILED", "Install Failed")));

        assertThrows(RuntimeException.class, () -> api.installHostedModpack("server", selection(), "draft:request-123").join());
        assertEquals(1, client.requestKeys.size());
    }

    @Test
    void retriesTransportLossWithSameKeyButNotLocalValidation() {
        FakeClient client = new FakeClient();
        DesktopRemotelyServerApi api = new DesktopRemotelyServerApi(client);
        client.submissions.add(CompletableFuture.failedFuture(new IOException("Connection Lost")));
        client.submissions.add(CompletableFuture.completedFuture(job(UUID.randomUUID(), "COMPLETED", null)));

        api.installHostedModpack("server", selection(), "draft:request-123").join();

        assertEquals(List.of("draft:request-123", "draft:request-123"), client.requestKeys);
        client.submissions.add(CompletableFuture.failedFuture(new IllegalArgumentException("Invalid Request")));
        assertThrows(RuntimeException.class, () -> api.installHostedModpack("server", selection(), "draft:invalid-123").join());
        assertEquals(List.of("draft:request-123", "draft:request-123", "draft:invalid-123"), client.requestKeys);
    }

    private HostedModpackSelection selection() {
        return new HostedModpackSelection("Pack", "modrinth", "project", "version", "1.0",
                "https://cdn.modrinth.com/file", "1.21.1", "FABRIC");
    }

    private ServerModels.HostedModpackJob job(UUID id, String status, String error) {
        ServerModels.HostedModpackJob job = new ServerModels.HostedModpackJob();
        job.id = id.toString();
        job.serverId = "server";
        job.operation = "modpack.install";
        job.status = status;
        job.error = error;
        return job;
    }

    private static final class FakeClient extends ReStudioApiClient {
        private final Queue<CompletableFuture<ServerModels.HostedModpackJob>> submissions = new ArrayDeque<>();
        private final Queue<CompletableFuture<ServerModels.HostedModpackJob>> observations = new ArrayDeque<>();
        private final List<ServerModels.ModpackJobRequest> requests = new ArrayList<>();
        private final List<String> requestKeys = new ArrayList<>();
        private final List<UUID> observedJobs = new ArrayList<>();
        private boolean legacyCalled;

        @Override
        public CompletableFuture<ServerModels.HostedModpackJob> submitHostedModpackJob(String serverId,
                                                                                         ServerModels.ModpackJobRequest request,
                                                                                         String requestKey) {
            assertEquals("server", serverId);
            requests.add(request);
            requestKeys.add(requestKey);
            return submissions.remove();
        }

        @Override
        public CompletableFuture<ServerModels.HostedModpackJob> getHostedModpackJob(String serverId, UUID jobId) {
            assertEquals("server", serverId);
            observedJobs.add(jobId);
            return observations.remove();
        }

        @Override
        public CompletableFuture<Void> installModpack(String serverId, String provider, String projectId, String versionId,
                                                      String versionNumber, String downloadUrl, String minecraftVersion,
                                                      String software, String modpackResolutionId) {
            legacyCalled = true;
            throw new AssertionError("Legacy Modpack Install Was Called");
        }
    }
}
