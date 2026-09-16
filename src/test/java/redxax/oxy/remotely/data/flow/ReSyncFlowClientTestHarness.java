package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.identity.ServerId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ReSyncFlowClientTestHarness implements AutoCloseable {
    private final ReSyncFlowClient client;
    private final ScriptedReSyncTransport transport;

    private ReSyncFlowClientTestHarness(ReSyncFlowClient client, ScriptedReSyncTransport transport) {
        this.client = client;
        this.transport = transport;
    }

    public static ReSyncFlowClientTestHarness connect(ServerId server, RemotelyClient owner, FlowManager manager,
                                                       ReSyncCatalogPublicationCache cache,
                                                       CatalogCachePublication publication) throws Exception {
        return connect(server, owner, manager, cache, publication, true);
    }

    public static ReSyncFlowClientTestHarness connectReconciling(ServerId server,
                                                                  ReSyncCatalogPublicationCache cache,
                                                                  CatalogCachePublication publication) throws Exception {
        return connect(server, null, null, cache, publication, false);
    }

    private static ReSyncFlowClientTestHarness connect(ServerId server, RemotelyClient owner, FlowManager manager,
                                                        ReSyncCatalogPublicationCache cache,
                                                        CatalogCachePublication publication,
                                                        boolean expectTypedAuthority) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, owner, cache);
        if (manager != null) {
            FlowManagerTestConnection.installCurrent(manager, server.canonicalText(), client, transport);
        }
        ReSyncFlowClientTestHarness harness = new ReSyncFlowClientTestHarness(client, transport);
        client.connect().join();
        transport.receiveHandshake(capabilities(server, publication));
        transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication), 2);
        harness.awaitPublication(expectTypedAuthority);
        return harness;
    }

    static ReSyncFlowClientTestHarness establish(ReSyncFlowClient client, ScriptedReSyncTransport transport,
                                                  CatalogCachePublication publication) throws Exception {
        ReSyncFlowClientTestHarness harness = new ReSyncFlowClientTestHarness(client, transport);
        client.connect().join();
        transport.receiveHandshake(capabilities(publication.key().serverId(), publication));
        transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication), 2);
        harness.awaitPublication(true);
        return harness;
    }

    public static ReSyncFlowClientTestHarness connect(ServerId server, ReSyncCatalogPublicationCache cache,
                                                       CatalogCachePublication publication) throws Exception {
        return connect(server, null, null, cache, publication);
    }

    public ReSyncFlowClient client() {
        return client;
    }

    ScriptedReSyncTransport transport() {
        return transport;
    }

    public void drain() throws Exception {
        drain(client);
    }

    public static void drain(ReSyncFlowClient client) throws Exception {
        drain(client, "submitConnectionDrain");
        drain(client, "submitConnectionCallbackDrain");
        ScreenManager.getInstance().processTasks();
    }

    private void awaitPublication(boolean expectTypedAuthority) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (System.nanoTime() < deadline) {
            drain();
            if ((expectTypedAuthority
                && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
                && client.typedInteractionProjection().isPresent())
                || (!expectTypedAuthority && client.catalogPublicationProjection().active().isPresent()
                && client.catalogPublicationProjection().acknowledgedKey().isPresent()
                && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertEquals(expectTypedAuthority ? ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
                : ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority(),
            () -> client.catalogAuthorityDiagnostic().orElse("Typed catalog authority was not established"));
    }

    private static void drain(ReSyncFlowClient client, String methodName) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        CompletableFuture<?> completion = (CompletableFuture<?>) method.invoke(client);
        completion.get(2L, TimeUnit.SECONDS);
    }

    private static String capabilities(ServerId server, CatalogCachePublication publication) {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", server.canonicalText());
        capabilities.addProperty("authorityEpoch", 1L);
        capabilities.addProperty("catalogPublicationKey", publication.key().canonicalText());
        return capabilities.toString();
    }

    @Override
    public void close() {
        closeClient(client);
    }

    static void closeClient(ReSyncFlowClient client) {
        Throwable failure = null;
        try {
            awaitCatalogPersistence(client);
        } catch (Throwable exception) {
            failure = exception;
        }
        try {
            client.shutdown();
        } catch (Throwable exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw new AssertionError("ReSync test client did not close cleanly", failure);
        }
    }

    private static void awaitCatalogPersistence(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("catalogPublicationReceiptHandler");
        field.setAccessible(true);
        ReSyncCatalogPublicationReceiptHandler handler =
            (ReSyncCatalogPublicationReceiptHandler) field.get(client);
        if (handler != null) {
            handler.cachePersistenceCompletion().join();
        }
    }
}
