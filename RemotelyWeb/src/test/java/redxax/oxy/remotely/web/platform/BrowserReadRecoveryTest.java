package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.RemotelyCapabilityException;
import restudio.rebase.backend.CapabilityIds;
import restudio.rebase.backend.DeveloperCapabilityProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.http.HttpHeaders;
import restudio.rescreen.platform.http.HttpRequest;
import restudio.rescreen.platform.http.HttpResponse;
import restudio.rescreen.platform.http.HttpTransport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserReadRecoveryTest {
    private final RecordingTransport transport = new RecordingTransport();
    private long now = 1000;
    private BrowserLaunchSession.Metadata session = session("user-a", "ticket-a");
    private final BrowserRemotelyServerApi api = new BrowserRemotelyServerApi(transport, () -> now,
            TaskScheduler.unavailable(), (uri, headers, listener) -> Async.failed(new UnsupportedOperationException()),
            session, null, "https://example.test/api", "https://example.test/api/remotely-web/capabilities", () -> session);

    @AfterEach
    void close() {
        api.close();
    }

    @Test
    void accountWithoutAgentAccessDoesNotProbeDeveloperEndpoints() {
        DeveloperCapabilityProvider provider = api.developer("one");
        for (int attempt = 0; attempt < 100; attempt++) {
            assertEquals(null, provider.workspace().current().getNow(null));
            assertEquals(List.of(), provider.workspace().devices().getNow(null));
        }
        assertFalse(provider.capability(CapabilityIds.DEVELOPER).available());
        assertFalse(provider.workspace().capability(CapabilityIds.WORKSPACE).available());
        assertEquals(List.of(), provider.workspace().roots("device").getNow(null));
        assertEquals(List.of(), api.developerDevices().getNow(null));
        assertInstanceOf(UnsupportedOperationException.class, provider.workspace().bind("device", "root").failure());
        assertInstanceOf(UnsupportedOperationException.class, provider.workspace().clear().failure());
        assertInstanceOf(UnsupportedOperationException.class, provider.logicalFilesystem().read(RemotePath.root()).failure());
        assertInstanceOf(UnsupportedOperationException.class, api.developerJob(UUID.randomUUID(), "git", "{}").failure());
        assertEquals(0, transport.requests.size());
        api.browserSshSession("one");
        api.getFileContent("one", "server.properties");
        assertEquals(2, transport.requests.size());
    }

    @Test
    void agentScopeEnablesDiscoveryAndRevocationDiscardsPendingBinding() {
        session = agentSession("user-a", "agent-ticket");
        DeveloperCapabilityProvider provider = api.developer("one");
        Async<?> pending = provider.workspace().current();
        assertEquals(1, transport.requests.size());
        session = session("user-a", "without-agent-ticket");
        transport.reply(0, 200, "{\"deviceId\":\"00000000-0000-0000-0000-000000000001\",\"rootId\":\"root\"}");
        assertTrue(pending.isDone());
        assertEquals(null, pending.failure());
        assertEquals(null, pending.getNow(null));
        assertEquals(null, provider.workspace().current().getNow(null));
        assertInstanceOf(UnsupportedOperationException.class, provider.workspace().bind("device", "root").failure());
        assertEquals(1, transport.requests.size());
        session = agentSession("user-a", "new-agent-ticket");
        api.developer("one").workspace().current();
        transport.reply(1, 404);
        assertEquals(2, transport.requests.size());
    }

    @Test
    void developerProviderCannotReuseItsBindingForAnotherAccount() {
        session = agentSession("user-a", "agent-ticket-a");
        DeveloperCapabilityProvider provider = api.developer("one");
        session = agentSession("user-b", "agent-ticket-b");
        assertEquals(null, provider.workspace().current().getNow(null));
        assertInstanceOf(UnsupportedOperationException.class, provider.workspace().bind("device", "root").failure());
        assertEquals(0, transport.requests.size());
    }

    @Test
    void deniedPollsRetainTheirFailureUntilTheNextAllowedAttempt() {
        Async<?> first = read("one", "health");
        transport.reply(0, 403);
        for (int attempt = 0; attempt < 100; attempt++) {
            RemotelyCapabilityException failure = assertInstanceOf(RemotelyCapabilityException.class,
                    read("one", "health").failure());
            assertEquals(403, failure.status());
        }
        assertEquals(1, transport.requests.size());
        assertTrue(first.isDone());
        now += 29_999;
        read("one", "health");
        assertEquals(1, transport.requests.size());
        now++;
        Async<?> recovered = read("one", "health");
        transport.reply(1, 200);
        assertTrue(recovered.isDone());
        assertEquals(null, recovered.failure());
        assertEquals(null, read("one", "health").failure());
        assertEquals(2, transport.requests.size());
    }

    @Test
    void denialIsScopedToTheActorTicketServerAndOperation() {
        read("one", "health");
        transport.reply(0, 403);
        read("one", "stats");
        read("two", "health");
        session = session("user-a", "ticket-b");
        read("one", "health");
        session = session("user-b", "ticket-b");
        read("one", "health");
        assertEquals(5, transport.requests.size());
    }

    @Test
    void refreshClearsFailureAndRejectsPreviousPendingResults() {
        read("one", "health");
        transport.reply(0, 403);
        Async<?> pending = read("one", "stats");
        api.invalidateReadCache();
        Async<?> fresh = read("one", "stats");
        transport.reply(1, 403);
        transport.reply(2, 200);
        assertTrue(pending.isDone());
        assertEquals(null, fresh.failure());
        assertEquals(null, read("one", "stats").failure());
        read("one", "health");
        assertEquals(4, transport.requests.size());
    }

    @Test
    void unavailableReadsBackOffAndCancellationDoesNotBlockRecovery() {
        read("one", "health");
        transport.reply(0, 503);
        for (int attempt = 0; attempt < 50; attempt++) read("one", "health");
        assertEquals(1, transport.requests.size());
        now += 2000;
        Async<?> pending = read("one", "health");
        pending.cancel();
        Async<?> fresh = read("one", "health");
        assertEquals(3, transport.requests.size());
        transport.reply(2, 200);
        assertEquals(null, fresh.failure());
    }

    @Test
    void synchronousTransportFailureAlsoWaitsBeforeRetrying() {
        transport.immediateFailure = new IllegalStateException("Network Unavailable");
        assertInstanceOf(IllegalStateException.class, read("one", "stats").failure());
        transport.immediateFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) read("one", "stats");
        assertEquals(1, transport.requests.size());
        now += 2000;
        Async<?> recovered = read("one", "stats");
        transport.reply(1, 200);
        assertEquals(2, transport.requests.size());
        assertEquals(null, recovered.failure());
    }

    @Test
    void cancellingOneReaderKeepsTheSharedRequestForTheOtherReader() {
        Async<?> first = read("one", "stats");
        Async<?> second = read("one", "stats");
        first.cancel();
        assertFalse(second.isDone());
        assertEquals(1, transport.requests.size());
        transport.reply(0, 200);
        assertEquals(null, second.failure());
        assertTrue(second.isDone());
    }

    @Test
    void failureRetentionStaysBoundedAcrossManyServers() {
        for (int index = 0; index < 129; index++) {
            read("server-" + index, "health");
            transport.reply(index, 403);
        }
        read("server-128", "health");
        assertEquals(129, transport.requests.size());
        read("server-0", "health");
        assertEquals(130, transport.requests.size());
    }

    private Async<?> read(String server, String operation) {
        return api.networkRequest("GET", "/servers/" + server + "/" + operation, null);
    }

    private static BrowserLaunchSession.Metadata session(String subject, String ticket) {
        return new BrowserLaunchSession.Metadata("grant", ticket, "audience", Set.of("server.read"), "node", "expiry",
                subject, "", "", "", "", "");
    }

    private static BrowserLaunchSession.Metadata agentSession(String subject, String ticket) {
        return new BrowserLaunchSession.Metadata("grant", ticket, "audience", Set.of("server.read", "remotely.developer"),
                "node", "expiry", subject, "", "", "", "", "");
    }

    private static final class RecordingTransport implements HttpTransport {
        private final List<Async<HttpResponse<byte[]>>> requests = new ArrayList<>();
        private Throwable immediateFailure;

        @Override
        public <T> Async<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            Async<HttpResponse<byte[]>> response = immediateFailure == null ? Async.pending() : Async.failed(immediateFailure);
            requests.add(response);
            return response.thenApply(value -> {
                try {
                    return new HttpResponse<>(value.statusCode(), value.headers(), handler.apply(value.body()));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }

        @Override
        public Async<HttpResponse<Void>> sendStreaming(HttpRequest request, HttpResponse.ChunkConsumer consumer) {
            return Async.failed(new UnsupportedOperationException());
        }

        private void reply(int index, int status) {
            reply(index, status, "{}");
        }

        private void reply(int index, int status, String body) {
            requests.get(index).complete(new HttpResponse<>(status, HttpHeaders.empty(), body.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
