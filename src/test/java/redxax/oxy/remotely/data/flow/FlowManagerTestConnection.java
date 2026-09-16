package redxax.oxy.remotely.data.flow;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class FlowManagerTestConnection {
    private FlowManagerTestConnection() {
    }

    static void install(FlowManager manager, String serverId, ReSyncFlowClient client,
                        ReSyncFrameTransport transport) throws Exception {
        int activeGeneration = 1;
        Field authenticated = ReSyncFlowClient.class.getDeclaredField("authenticated");
        authenticated.setAccessible(true);
        ((AtomicBoolean) authenticated.get(client)).set(true);
        Field generation = ReSyncFlowClient.class.getDeclaredField("connectionGeneration");
        generation.setAccessible(true);
        ((AtomicInteger) generation.get(client)).set(activeGeneration);
        set(client, "activeTransportGeneration", activeGeneration);
        set(client, "completedStartupGeneration", activeGeneration);
        installConnectionSource(client, activeGeneration);
        client.resourceRevisionReconciler().observeAuthorityEpoch(serverId, 1L);

        installCurrent(manager, serverId, client, transport);
        FlowManager.ServerConnectionToken token = manager.captureServerConnectionToken(serverId, client);
        if (!client.isConnectedState() || !manager.isCurrentServerConnection(token)) {
            throw new IllegalStateException("The test ReSync connection was not published as the current owner.");
        }
    }

    static void installCurrent(FlowManager manager, String serverId, ReSyncFlowClient client,
                               ReSyncFrameTransport transport) throws Exception {
        ReSyncConnectionManager connectionManager = connectionManager(manager);
        installOwner(connectionManager, serverId, client, transport);
    }

    static void installDirectCurrent(FlowManager manager, String serverId, ReSyncFlowClient client,
                                     String wsUrl, String apiKey) throws Exception {
        ReSyncConnectionManager connectionManager = connectionManager(manager);
        Field profilesField = ReSyncConnectionManager.class.getDeclaredField("flowProfiles");
        profilesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ReSyncConnectionManager.ReSyncConnectionProfile> profiles =
            (Map<String, ReSyncConnectionManager.ReSyncConnectionProfile>) profilesField.get(connectionManager);
        profiles.put(serverId, new ReSyncConnectionManager.ReSyncConnectionProfile(wsUrl, apiKey));
        installOwner(connectionManager, serverId, client, null);
    }

    private static void installOwner(ReSyncConnectionManager connectionManager, String serverId,
                                     ReSyncFlowClient client, ReSyncFrameTransport transport) throws Exception {
        Class<?> errorMode = Class.forName(ReSyncConnectionManager.class.getName() + "$ErrorMode");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object silent = Enum.valueOf((Class<? extends Enum>) errorMode, "SILENT");
        Method prepareOwner = ReSyncConnectionManager.class.getDeclaredMethod("prepareOwner", String.class,
            ReSyncFlowClient.class, errorMode, ReSyncFrameTransport.class);
        prepareOwner.setAccessible(true);
        Object owner = prepareOwner.invoke(connectionManager, serverId, client, silent, transport);

        Field ownersField = ReSyncConnectionManager.class.getDeclaredField("flowClients");
        ownersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> owners = (Map<String, Object>) ownersField.get(connectionManager);
        owners.put(serverId, owner);
    }

    static void observeCurrent(FlowManager manager, ReSyncFlowClient client, Consumer<String> connected,
                               Consumer<String> disconnected, ReSyncFlowClient.ErrorListener errors) throws Exception {
        ReSyncConnectionManager connectionManager = connectionManager(manager);
        Consumer<String> managerConnected = listener(connectionManager, "connectionListener");
        Consumer<String> managerDisconnected = listener(connectionManager, "disconnectListener");
        connectionManager.setConnectionListener(serverId -> {
            managerConnected.accept(serverId);
            if (connected != null) {
                connected.accept(serverId);
            }
        });
        connectionManager.setDisconnectListener(serverId -> {
            managerDisconnected.accept(serverId);
            if (disconnected != null) {
                disconnected.accept(serverId);
            }
        });
        Field errorListenerField = ReSyncFlowClient.class.getDeclaredField("errorListener");
        errorListenerField.setAccessible(true);
        ReSyncFlowClient.ErrorListener managerErrors =
            (ReSyncFlowClient.ErrorListener) errorListenerField.get(client);
        client.setErrorListener((nodeId, message) -> {
            if (managerErrors != null) {
                managerErrors.onError(nodeId, message);
            }
            if (errors != null) {
                errors.onError(nodeId, message);
            }
        });
    }

    static ReSyncFlowClient connectCurrent(FlowManager manager, String serverId) throws Exception {
        return ensureCurrent(manager, serverId, true);
    }

    static ReSyncFlowClient ensurePublicCurrent(FlowManager manager, String serverId) throws Exception {
        return connectionManager(manager).ensureFlowClient(serverId, false);
    }

    static void primeConnecting(ReSyncFlowClient client, int generation) throws Exception {
        Field connecting = ReSyncFlowClient.class.getDeclaredField("connecting");
        connecting.setAccessible(true);
        ((AtomicBoolean) connecting.get(client)).set(true);
        Field connectionGeneration = ReSyncFlowClient.class.getDeclaredField("connectionGeneration");
        connectionGeneration.setAccessible(true);
        ((AtomicInteger) connectionGeneration.get(client)).set(generation);
        Field pendingGeneration = ReSyncFlowClient.class.getDeclaredField("pendingHandshakeGeneration");
        pendingGeneration.setAccessible(true);
        ((AtomicInteger) pendingGeneration.get(client)).set(generation);
    }

    static int atomicInt(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((AtomicInteger) field.get(client)).get();
    }

    static boolean booleanValue(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(client);
    }

    private static ReSyncFlowClient ensureCurrent(FlowManager manager, String serverId,
                                                  boolean connectIfNeeded) throws Exception {
        ReSyncConnectionManager connectionManager = connectionManager(manager);
        Method ensure = ReSyncConnectionManager.class.getDeclaredMethod("ensureFlowClient", String.class,
            ReSyncConnectionManager.ReSyncConnectionProfile.class, boolean.class, boolean.class);
        ensure.setAccessible(true);
        return (ReSyncFlowClient) ensure.invoke(connectionManager, serverId, null, false, connectIfNeeded);
    }

    private static ReSyncConnectionManager connectionManager(FlowManager manager) throws Exception {
        Field connectionManagerField = FlowManager.class.getDeclaredField("connectionManager");
        connectionManagerField.setAccessible(true);
        return (ReSyncConnectionManager) connectionManagerField.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> listener(ReSyncConnectionManager manager, String name) throws Exception {
        Field field = ReSyncConnectionManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Consumer<String>) field.get(manager);
    }

    private static void set(ReSyncFlowClient client, String name, int value) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(client, value);
    }

    private static void installConnectionSource(ReSyncFlowClient client, int generation) throws Exception {
        Class<?> type = Class.forName(ReSyncFlowClient.class.getName() + "$ConnectionSource");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Field field = ReSyncFlowClient.class.getDeclaredField("connectionSource");
        field.setAccessible(true);
        field.set(client, constructor.newInstance(generation));
    }
}
