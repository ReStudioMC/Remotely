package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.util.DesktopTaskIdentities;
import restudio.rescreen.platform.Async;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.BackendFeature;
import restudio.rebase.backend.ExecutionProvider;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.instance.Instance;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncConnectionManagerProfileResolutionTest {
    private static Async.Snapshot asyncSnapshot;
    private static ExecutorService asyncPool;

    @BeforeAll
    static void setUpAll() {
        DesktopTaskIdentities.install();
        DesktopReSyncLocalInstances.install();
        DesktopReSyncDirectSockets.install();
        asyncSnapshot = Async.snapshot();
        asyncPool = Executors.newCachedThreadPool();
        Async.installExecutor(asyncPool::execute, ignored -> Thread.currentThread().interrupt());
    }

    @AfterAll
    static void tearDownAll() {
        if (asyncSnapshot != null) {
            Async.restore(asyncSnapshot);
        }
        if (asyncPool != null) {
            asyncPool.shutdownNow();
        }
    }

    @Test
    void backendResolutionReturnsImmediatelyDeduplicatesAndCaches() throws Exception {
        CompletableFuture<Boolean> exists = new CompletableFuture<>();
        TestBackend backend = new TestBackend(exists, "port=8765\napi-key=profile-key");
        TestInstance instance = new TestInstance("profile:server", "10.0.0.4", backend);
        AtomicReference<Instance> current = new AtomicReference<>(instance);
        TestConnectionManager manager = new TestConnectionManager(current);
        AtomicReference<Async<ReSyncConnectionManager.ProfileResolution>> first = new AtomicReference<>();
        try {
            assertTimeoutPreemptively(Duration.ofMillis(250),
                () -> first.set(manager.resolveAndStoreProfile(instance.getInstanceId(), null)));
            Async<ReSyncConnectionManager.ProfileResolution> second =
                manager.resolveAndStoreProfile(instance.getInstanceId(), null);

            assertSame(first.get(), second);
            assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                assertEquals("ReSyncProfileLoading", manager.getFlowAvailabilityIssue(instance.getInstanceId(), null)));
            assertNull(manager.getProfile(instance.getInstanceId()));
            assertTrue(backend.existsCalled.await(2, TimeUnit.SECONDS));
            assertTrue(backend.connectThread.get().startsWith("ReSync-Profile-"));

            exists.complete(true);
            ReSyncConnectionManager.ProfileResolution resolution = first.get().join();

            assertTrue(resolution.available());
            assertEquals("ws://10.0.0.4:8765", resolution.profile().wsUrl());
            assertEquals("profile-key", resolution.profile().apiKey());
            assertSame(resolution.profile(), manager.getProfile(instance.getInstanceId()));
            assertEquals(3, backend.existsCalls.get());

            ReSyncConnectionManager.ProfileResolution cached =
                manager.resolveAndStoreProfile(instance.getInstanceId(), null).join();
            assertNotNull(cached);
            assertEquals(resolution.profile(), cached.profile());
            assertEquals(3, backend.existsCalls.get());
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void desktopServerViewResolvesTheInstanceProfileAndCanonicalServerId() {
        TestBackend backend = new TestBackend(CompletableFuture.completedFuture(true),
            "port=8765\napi-key=desktop-key");
        TestInstance instance = new TestInstance("profile:desktop-view", "10.0.0.15", backend);
        TestConnectionManager manager = new TestConnectionManager(new AtomicReference<>(instance));
        ClientServerView view = new ClientServerView();
        view.identifier = instance.getInstanceId();
        view.uuid = instance.getInstanceId();
        view.name = "Desktop Server";
        view.backendType = "SSH";
        try {
            ReSyncConnectionManager.ProfileResolution resolution =
                manager.resolveAndStoreProfile(instance.getInstanceId(), view).join();

            assertTrue(resolution.available());
            assertEquals(backend.serverId, resolution.serverId());
            assertEquals("ws://10.0.0.15:8765", resolution.profile().wsUrl());
            assertEquals("desktop-key", resolution.profile().apiKey());
            assertSame(resolution.profile(), manager.getProfile(instance.getInstanceId()));
            assertSame(resolution.profile(), manager.getProfile(backend.serverId));
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void desktopInstanceProfileRemainsAuthoritativeOverThePlatformFallback() {
        TestBackend backend = new TestBackend(CompletableFuture.completedFuture(true),
            "port=8765\napi-key=instance-key");
        TestInstance instance = new TestInstance("profile:authoritative-instance", "10.0.0.16", backend);
        AtomicBoolean fallbackRead = new AtomicBoolean();
        ReSyncConnectionProfileProvider fallback = identity -> {
            fallbackRead.set(true);
            return new ReSyncConnectionManager.ReSyncConnectionProfile(
                "00000000-0000-0000-0000-000000000000", "ws://wrong.test:1", "wrong-key");
        };
        TestConnectionManager manager = new TestConnectionManager(new AtomicReference<>(instance), fallback);
        ClientServerView view = new ClientServerView();
        view.identifier = instance.getInstanceId();
        view.backendType = "SSH";
        try {
            ReSyncConnectionManager.ProfileResolution resolution =
                manager.resolveAndStoreProfile(instance.getInstanceId(), view).join();

            assertTrue(resolution.available());
            assertFalse(fallbackRead.get());
            assertEquals(backend.serverId, resolution.serverId());
            assertEquals("instance-key", resolution.profile().apiKey());
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void asyncClientAdmissionReturnsImmediatelyCoalescesAndFailsClosed() throws Exception {
        CompletableFuture<Boolean> exists = new CompletableFuture<>();
        TestBackend backend = new TestBackend(exists, "");
        TestInstance instance = new TestInstance("profile:admission", "10.0.0.11", backend);
        TestConnectionManager manager = new TestConnectionManager(new AtomicReference<>(instance));
        AtomicReference<Async<ReSyncFlowClient>> first = new AtomicReference<>();
        try {
            assertTimeoutPreemptively(Duration.ofMillis(250),
                () -> first.set(manager.ensureFlowClientAsync(instance.getInstanceId(), false)));
            Async<ReSyncFlowClient> second =
                manager.ensureFlowClientAsync(instance.getInstanceId(), false);

            assertSame(first.get(), second);
            assertTrue(backend.existsCalled.await(2, TimeUnit.SECONDS));
            exists.complete(false);
            assertNull(first.get().join());
            assertFalse(manager.hasFlowClients());
        } finally {
            exists.complete(false);
            manager.shutdownAll();
        }
    }

    @Test
    void currentOwnerActionIsRejectedBeforeClosedOwnerRetirement() throws Exception {
        TestBackend backend = new TestBackend(CompletableFuture.completedFuture(true),
            "port=8765\napi-key=owner-key");
        TestInstance instance = new TestInstance("profile:owner-action", "10.0.0.12", backend);
        TestConnectionManager manager = new TestConnectionManager(new AtomicReference<>(instance));
        ReSyncConnectionManager.ReSyncConnectionProfile profile =
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://10.0.0.12:8765", "owner-key");
        ReSyncFlowClient client = ensureWithoutConnecting(manager, instance.getInstanceId(), profile);
        AtomicBoolean invoked = new AtomicBoolean();
        try {
            assertTrue(manager.withCurrentFlowClientNow(instance.getInstanceId(), client, ignored -> invoked.set(true)));
            assertTrue(invoked.get());

            Method closeLifecycle = ReSyncConnectionManager.class.getDeclaredMethod("shutdownConnectionLifecycle");
            closeLifecycle.setAccessible(true);
            closeLifecycle.invoke(manager);
            assertTrue(manager.hasFlowClients());
            invoked.set(false);

            assertFalse(manager.withCurrentFlowClientNow(instance.getInstanceId(), client, ignored -> invoked.set(true)));
            assertFalse(invoked.get());
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void replacementInstanceFencesTheDisplacedResolution() throws Exception {
        CompletableFuture<Boolean> firstExists = new CompletableFuture<>();
        TestBackend firstBackend = new TestBackend(firstExists, "port=8765\napi-key=old-key");
        TestInstance firstInstance = new TestInstance("profile:replace", "10.0.0.5", firstBackend);
        TestBackend replacementBackend = new TestBackend(CompletableFuture.completedFuture(true),
            "port=9876\napi-key=new-key");
        TestInstance replacementInstance = new TestInstance("profile:replace", "10.0.0.6", replacementBackend);
        AtomicReference<Instance> current = new AtomicReference<>(firstInstance);
        TestConnectionManager manager = new TestConnectionManager(current);
        try {
            Async<ReSyncConnectionManager.ProfileResolution> displaced =
                manager.resolveAndStoreProfile(firstInstance.getInstanceId(), null);
            assertTrue(firstBackend.existsCalled.await(2, TimeUnit.SECONDS));

            current.set(replacementInstance);
            firstExists.complete(true);

            assertEquals("ReSyncProfileChanged", displaced.join().issue());
            Async<ReSyncConnectionManager.ProfileResolution> replacement =
                manager.resolveAndStoreProfile(replacementInstance.getInstanceId(), null);
            ReSyncConnectionManager.ProfileResolution resolved = replacement.join();
            assertTrue(resolved.available());
            assertEquals("ws://10.0.0.6:9876", resolved.profile().wsUrl());
            assertEquals("new-key", resolved.profile().apiKey());
            assertSame(resolved.profile(), manager.getProfile(replacementInstance.getInstanceId()));
        } finally {
            firstExists.complete(false);
            manager.shutdownAll();
        }
    }

    @Test
    void instanceAndBackendAliasesShareTheResolvedFlightAndCache() throws Exception {
        CompletableFuture<Boolean> exists = new CompletableFuture<>();
        TestBackend backend = new TestBackend(exists, "port=8765\napi-key=alias-key");
        TestInstance instance = new TestInstance("profile:instance", "10.0.0.7", backend);
        AtomicReference<Instance> current = new AtomicReference<>(instance);
        TestConnectionManager manager = new TestConnectionManager(current);
        try {
            Async<ReSyncConnectionManager.ProfileResolution> byInstance =
                manager.resolveAndStoreProfile(instance.getInstanceId(), null);
            assertTrue(backend.existsCalled.await(2, TimeUnit.SECONDS));

            Async<ReSyncConnectionManager.ProfileResolution> byBackend =
                manager.resolveAndStoreProfile(instance.backendIdentifier(), null);

            assertSame(byInstance, byBackend);
            exists.complete(true);
            ReSyncConnectionManager.ProfileResolution resolution = byBackend.join();
            assertTrue(resolution.available());
            assertSame(resolution.profile(), manager.getProfile(instance.getInstanceId()));
            assertSame(resolution.profile(), manager.getProfile(instance.backendIdentifier()));
            ReSyncFlowClient byInstanceClient = ensureWithoutConnecting(manager, instance.getInstanceId(), resolution.profile());
            ReSyncFlowClient byBackendClient = ensureWithoutConnecting(manager, instance.backendIdentifier(), resolution.profile());
            assertSame(byInstanceClient, byBackendClient);
        } finally {
            exists.complete(false);
            manager.shutdownAll();
        }
    }

    @Test
    void canonicalProfileCreatesOwnerWithoutResolvingTheInstanceAliasAgain() throws Exception {
        TestBackend backend = new TestBackend(CompletableFuture.completedFuture(false), "");
        TestInstance instance = new TestInstance("profile:instance-alias", "10.0.0.7", backend);
        TestConnectionManager manager = new TestConnectionManager(new AtomicReference<>(instance));
        String canonicalServerId = UUID.randomUUID().toString();
        ReSyncConnectionManager.ReSyncConnectionProfile profile =
            new ReSyncConnectionManager.ReSyncConnectionProfile(canonicalServerId, "ws://127.0.0.1:1", "canonical-key");
        try {
            Field profilesField = ReSyncConnectionManager.class.getDeclaredField("flowProfiles");
            profilesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ReSyncConnectionManager.ReSyncConnectionProfile> profiles =
                (Map<String, ReSyncConnectionManager.ReSyncConnectionProfile>) profilesField.get(manager);
            profiles.put(canonicalServerId, profile);

            assertNull(manager.getFlowAvailabilityIssueAsync(canonicalServerId, null).join());
            ReSyncFlowClient flowClient = manager.ensureFlowClient(canonicalServerId, false);

            assertNotNull(flowClient);
            assertSame(flowClient, manager.getFlowClient(canonicalServerId));
            assertEquals(0, backend.existsCalls.get());
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void canonicalServerIdReusesTheResolvedInstanceSource() throws Exception {
        TestBackend backend = new TestBackend(CompletableFuture.completedFuture(true),
            "port=8765\napi-key=canonical-source-key");
        TestInstance instance = new TestInstance("profile:canonical-source", "10.0.0.13", backend);
        TestConnectionManager manager = new TestConnectionManager(new AtomicReference<>(instance));
        try {
            ReSyncConnectionManager.ProfileResolution initial =
                manager.resolveAndStoreProfile(instance.getInstanceId(), null).join();
            int initialReads = backend.existsCalls.get();

            ReSyncConnectionManager.ProfileResolution canonical =
                manager.resolveAndStoreProfile(initial.serverId(), null).join();

            assertTrue(canonical.available());
            assertEquals(initial.profile(), canonical.profile());
            assertEquals(instance.getInstanceId(), canonical.instanceId());
            assertEquals(initialReads, backend.existsCalls.get());
            assertSame(canonical.profile(), manager.getProfile(initial.serverId()));
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void canonicalEnsureAfterProfileSourceInvalidationRetainsTheDirectOwnerAliases() throws Exception {
        TestBackend backend = new TestBackend(CompletableFuture.completedFuture(true),
            "port=8765\napi-key=retained-source-key");
        TestInstance instance = new TestInstance("profile:retained-source", "retained-backend", "10.0.0.14", backend);
        TestConnectionManager manager = new TestConnectionManager(new AtomicReference<>(instance));
        try {
            ReSyncConnectionManager.ProfileResolution initial =
                manager.resolveAndStoreProfile(instance.getInstanceId(), null).join();
            ReSyncFlowClient owner = ensureWithoutConnecting(manager, initial.serverId(), initial.profile());
            int initialReads = backend.existsCalls.get();

            Method invalidate = ReSyncConnectionManager.class.getDeclaredMethod("invalidateProfileSources");
            invalidate.setAccessible(true);
            invalidate.invoke(manager);

            ReSyncConnectionManager.ProfileResolution refreshed =
                manager.resolveAndStoreProfile(initial.serverId(), null).join();
            ReSyncFlowClient retained = ensureWithoutConnecting(manager, initial.serverId(), refreshed.profile());

            assertTrue(refreshed.available());
            assertEquals(instance.getInstanceId(), refreshed.instanceId());
            assertEquals(initial.serverId(), refreshed.serverId());
            assertEquals("retained-source-key", refreshed.profile().apiKey());
            assertTrue(backend.existsCalls.get() > initialReads);
            assertSame(owner, retained);
            assertSame(owner, manager.getFlowClient(initial.serverId()));
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void cachedBackendAliasFollowsTheExactReplacementInstance() throws Exception {
        TestBackend firstBackend = new TestBackend(CompletableFuture.completedFuture(true),
            "port=8765\napi-key=first-key");
        TestInstance firstInstance = new TestInstance("profile:first", "stable-backend", "10.0.0.8", firstBackend);
        TestBackend secondBackend = new TestBackend(CompletableFuture.completedFuture(true),
            "port=9876\napi-key=second-key");
        TestInstance secondInstance = new TestInstance("profile:second", "stable-backend", "10.0.0.9", secondBackend);
        AtomicReference<Instance> current = new AtomicReference<>(firstInstance);
        TestConnectionManager manager = new TestConnectionManager(current);
        try {
            ReSyncConnectionManager.ProfileResolution first =
                manager.resolveAndStoreProfile("stable-backend", null).join();
            assertEquals("first-key", first.profile().apiKey());

            current.set(secondInstance);
            ReSyncConnectionManager.ProfileResolution second =
                manager.resolveAndStoreProfile("stable-backend", null).join();

            assertTrue(second.available());
            assertEquals("profile:second", second.instanceId());
            assertEquals("ws://10.0.0.9:9876", second.profile().wsUrl());
            assertEquals("second-key", second.profile().apiKey());
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void definitiveNegativeRetiresTheOwnerUnderItsBackendAlias() throws Exception {
        TestBackend backend = new TestBackend(CompletableFuture.completedFuture(false), "");
        TestInstance instance = new TestInstance("profile:canonical", "profile:owner", "10.0.0.10", backend);
        AtomicReference<Instance> current = new AtomicReference<>(instance);
        TestConnectionManager manager = new TestConnectionManager(current);
        try {
            ReSyncConnectionManager.ReSyncConnectionProfile profile =
                new ReSyncConnectionManager.ReSyncConnectionProfile("ws://10.0.0.10:8765", "old-key");
            assertNotNull(ensureWithoutConnecting(manager, instance.backendIdentifier(), profile));

            ReSyncConnectionManager.ProfileResolution resolution =
                manager.resolveAndStoreProfile(instance.backendIdentifier(), null).join();

            assertEquals("ReSyncNotConfigured", resolution.issue());
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (manager.hasFlowClients()) {
                    Thread.onSpinWait();
                }
            });
            assertFalse(manager.hasFlowClients());
        } finally {
            manager.shutdownAll();
        }
    }

    private static ReSyncFlowClient ensureWithoutConnecting(ReSyncConnectionManager manager, String serverId,
                                                             ReSyncConnectionManager.ReSyncConnectionProfile profile)
        throws Exception {
        Method ensure = ReSyncConnectionManager.class.getDeclaredMethod("ensureFlowClient", String.class,
            ReSyncConnectionManager.ReSyncConnectionProfile.class, boolean.class, boolean.class);
        ensure.setAccessible(true);
        return (ReSyncFlowClient) ensure.invoke(manager, serverId, profile, false, false);
    }

    private static final class TestConnectionManager extends ReSyncConnectionManager {
        private final AtomicReference<Instance> current;

        private TestConnectionManager(AtomicReference<Instance> current) {
            super(null, null);
            this.current = current;
        }

        private TestConnectionManager(AtomicReference<Instance> current,
                                      ReSyncConnectionProfileProvider profileProvider) {
            super(null, null, ignored -> ReSyncCatalogPublicationCache.deferred(),
                ReSyncFlowClientFactory.unavailable(), profileProvider, ReSyncConnectionNotificationSink.noop(),
                null, null);
            this.current = current;
        }

        @Override
        public Instance findInstanceByServerId(String serverId, ClientServerView server) {
            Instance instance = current.get();
            return instance instanceof TestInstance testInstance
                && (testInstance.getInstanceId().equals(serverId) || testInstance.backendIdentifier().equals(serverId))
                ? instance : null;
        }
    }

    private static final class TestInstance extends Instance {
        private final String instanceId;
        private final BackendConfig backendConfig;
        private final ServerBackend backend;
        private final String path;

        private TestInstance(String instanceId, String host, ServerBackend backend) {
            this(instanceId, instanceId + ":backend", host, backend);
        }

        private TestInstance(String instanceId, String identifier, String host, ServerBackend backend) {
            super(instanceId, "1.21.1", Path.of(System.getProperty("java.io.tmpdir"),
                "resync-profile-" + UUID.randomUUID()).toString());
            this.instanceId = instanceId;
            this.backendConfig = new BackendConfig("SSH", Map.of("host", host, "identifier", identifier));
            this.backend = backend;
            this.path = super.getPath();
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public synchronized BackendConfig getBackendConfig() {
            return backendConfig;
        }

        @Override
        public synchronized ServerBackend getBackend() {
            return backend;
        }

        private String backendIdentifier() {
            return backendConfig.credentials.get("identifier");
        }
    }

    private static final class TestBackend implements ServerBackend {
        private final AtomicBoolean connected = new AtomicBoolean();
        private final AtomicReference<String> connectThread = new AtomicReference<>();
        private final AtomicInteger existsCalls = new AtomicInteger();
        private final CountDownLatch existsCalled = new CountDownLatch(1);
        private final String serverId = UUID.randomUUID().toString();
        private final FileSystemProvider fileSystem;

        private TestBackend(CompletableFuture<Boolean> exists, String content) {
            fileSystem = (FileSystemProvider) Proxy.newProxyInstance(FileSystemProvider.class.getClassLoader(),
                new Class<?>[]{FileSystemProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "exists" -> {
                        if (String.valueOf(args[0]).endsWith("active-root")) {
                            yield CompletableFuture.completedFuture(false);
                        }
                        existsCalls.incrementAndGet();
                        existsCalled.countDown();
                        yield exists;
                    }
                    case "read" -> CompletableFuture.completedFuture(String.valueOf(args[0]).endsWith("server-id")
                        ? serverId : content);
                    default -> CompletableFuture.failedFuture(new UnsupportedOperationException(method.getName()));
                });
        }

        @Override
        public void connect() {
            connectThread.set(Thread.currentThread().getName());
            connected.set(true);
        }

        @Override
        public void disconnect() {
            connected.set(false);
        }

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        @Override
        public FileSystemProvider getFileSystem() {
            return fileSystem;
        }

        @Override
        public ExecutionProvider getExecution() {
            return null;
        }

        @Override
        public <T extends BackendFeature> Optional<T> getFeature(Class<T> featureClass) {
            return Optional.empty();
        }

        @Override
        public <T extends BackendFeature> void registerFeature(Class<T> featureClass, T implementation) {
        }
    }
}
