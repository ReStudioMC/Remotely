package redxax.oxy.remotely.network;

import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import restudio.rescreen.platform.Async;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface NetworkManager<T, R> extends AutoCloseable {
    default String getLoadError() { return ""; }
    default String getJobLoadError() { return ""; }
    default boolean available() { return true; }
    default List<NetworkDefinition> getNetworks() { return List.of(); }
    default Optional<NetworkDefinition> getNetwork(String networkId) { return Optional.empty(); }
    default Optional<NetworkDefinition> getNetworkForInstance(String instanceId) { return Optional.empty(); }
    default Optional<NetworkJob> getRecoverableCreationJob(String instanceId) { return Optional.empty(); }
    default NetworkDefinition save(NetworkDefinition network) { return unsupported(); }
    default Async<NetworkAdoptionReport> scanForAdoption(T proxy, Collection<T> instances) { return unsupported(); }
    default Async<NetworkAdoptionReport> scanLegacyMigration(T proxy, Collection<T> instances) { return unsupported(); }
    default Async<NetworkDefinition> adoptNetwork(String name, NetworkAdoptionReport report, Collection<T> instances) { return unsupported(); }
    default void discardPreparedCreation(NetworkCreationPreparedPlan creationPrepared) { }
    default void discardPreparedSecretRotation(NetworkSecretRotationPreparedPlan rotationPrepared) { }
    default Async<NetworkJob> enableReSyncSafely(NetworkDefinition network, Collection<String> instanceIds, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<NetworkAttachPreparedPlan> prepareAttach(NetworkDefinition network, T instance, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int preferredPort, int capacity, boolean resyncEnabled, Collection<T> instances, Collection<R> externalReservations) { return unsupported(); }
    default Async<NetworkCreationPreparedPlan> prepareCreation(NetworkCreationRequest request, Collection<T> instances, Collection<R> externalReservations) { return unsupported(); }
    default Async<NetworkAttachPreparedPlan> prepareExternalAttach(NetworkDefinition network, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int port, int capacity, Collection<T> instances, Collection<R> externalReservations) { return unsupported(); }
    default Async<NetworkRoutingPreparedPlan> prepareRouting(NetworkDefinition network, List<RoutingGroup> routingGroups, Collection<T> instances) { return unsupported(); }
    default Async<NetworkRealmPreparedPlan> prepareSharedData(NetworkDefinition network, List<SyncRealm> realms, Map<String, Boolean> features, Collection<T> instances) { return unsupported(); }
    default Async<NetworkRealmPreparedPlan> prepareSharedData(NetworkDefinition network, List<SyncRealm> realms, Map<String, Boolean> features, NetworkSharedDataPolicy sharedDataPolicy, Collection<T> instances) { return unsupported(); }
    default Async<NetworkSecretRotationPreparedPlan> prepareSecretRotation(NetworkDefinition network, Collection<T> instances) { return unsupported(); }
    default NetworkAdoptionReport resolveAdoptionRoute(NetworkAdoptionReport report, String routeName, T instance, Collection<T> instances) { return unsupported(); }
    default NetworkAdoptionReport resolveExternalAdoptionRoute(NetworkAdoptionReport report, String routeName) { return unsupported(); }
    default Async<NetworkDefinition> attach(NetworkDefinition network, T instance, String routeName, NetworkMemberRole role, String hostScope, String address, int port, int capacity, boolean resyncEnabled) { return unsupported(); }
    default Async<NetworkJob> updateRoutingSafely(NetworkDefinition network, List<RoutingGroup> routingGroups, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<NetworkRealmPreparedPlan> prepareRealms(NetworkDefinition network, List<SyncRealm> realms, Collection<T> instances) { return unsupported(); }
    default Async<NetworkJob> applyRealms(NetworkDefinition network, List<SyncRealm> realms, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<NetworkJob> attachSafely(NetworkDefinition network, T instance, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int preferredPort, int capacity, boolean resyncEnabled, Collection<T> instances, Collection<R> externalReservations, String initiator) { return unsupported(); }
    default Async<NetworkDefinition> detach(NetworkDefinition network, T instance) { return unsupported(); }
    default Async<NetworkJob> dissolveSafely(NetworkDefinition network, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<NetworkJob> detachSafely(NetworkDefinition network, T instance, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<NetworkJob> detachExternalSafely(NetworkDefinition network, NetworkMember member, Collection<T> instances, String initiator) { return unsupported(); }
    default void delete(String networkId, Collection<T> instances) { throw new UnsupportedOperationException("Network Capability Is Unavailable"); }
    default NetworkDiscoveryResult discover(NetworkDefinition network, Collection<T> instances, Collection<R> externalReservations) { return unsupported(); }
    default NetworkReconciliationPlan plan(NetworkDefinition network, Collection<T> instances, Collection<R> externalReservations) { return unsupported(); }
    default Async<NetworkJob> runJob(NetworkDefinition network, Collection<T> instances, Collection<R> externalReservations, NetworkJobType type, String initiator) { return unsupported(); }
    default Async<NetworkJob> resumeJob(String jobId, Collection<T> instances, Collection<R> externalReservations) { return unsupported(); }
    default Async<NetworkJob> rollbackJob(String jobId, Collection<T> instances) { return unsupported(); }
    default Async<NetworkPreparedPlan> prepare(NetworkReconciliationPlan plan, Collection<T> instances) { return unsupported(); }
    default Async<NetworkLifecycleJob> runLifecycle(NetworkDefinition network, Collection<T> instances, NetworkLifecycleOperation operation, String initiator) { return unsupported(); }
    default Async<NetworkLifecycleJob> runMemberLifecycle(NetworkDefinition network, NetworkMember member, Collection<T> instances, NetworkLifecycleOperation operation, String initiator) { return unsupported(); }
    default Async<NetworkLifecycleJob> resumeLifecycle(String jobId, Collection<T> instances) { return unsupported(); }
    default Async<NetworkPreflightReport> runPreflight(NetworkDefinition network, Collection<T> instances) { return unsupported(); }
    default Async<NetworkJob> runPreparedAttach(NetworkAttachPreparedPlan attachPrepared, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<NetworkJob> runPreparedCreation(NetworkCreationPreparedPlan creationPrepared, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<NetworkJob> runPreparedJob(NetworkPreparedPlan prepared, Collection<T> instances, NetworkJobType type, String initiator) { return unsupported(); }
    default Async<NetworkJob> runPreparedRealms(NetworkRealmPreparedPlan realmPrepared, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<NetworkJob> runPreparedRouting(NetworkRoutingPreparedPlan routingPrepared, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<NetworkJob> runPreparedSecretRotation(NetworkSecretRotationPreparedPlan rotationPrepared, Collection<T> instances, String initiator) { return unsupported(); }
    default Async<Void> recoverCompletedJobs(Collection<T> instances) { return unsupported(); }
    default List<NetworkValidationIssue> reconcileInstanceBindings(Collection<T> instances) { return List.of(); }
    default boolean editorConfigurable() { return false; }
    default Async<Void> configureEditor(String networkId, NetworkEditorConnection connection) { return unsupported(); }
    default boolean editorAvailable() { return false; }
    default ReSyncFrameTransport editorTransport(String networkId, String nodeId) { return unsupported(); }
    default NetworkRuntimeSnapshot getRuntimeSnapshot(String networkId) { return unsupported(); }
    default List<NetworkIncident> getIncidents(String networkId) { return List.of(); }
    default int getOpenIncidentCount(String networkId) { return 0; }
    default Map<String, Integer> getTransferFailureHeat(String networkId) { return Map.of(); }
    default void addRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) { }
    default void removeRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) { }
    default Async<Void> setRuntimeNodeMode(String networkId, String nodeId, NetworkRuntimeNodeStatus status) { return unsupported(); }
    default Async<Void> executeRuntimeProxyCommand(String networkId, String command) { return unsupported(); }
    default Async<Void> broadcastRuntimeMessage(String networkId, String message) { return unsupported(); }
    @Override default void close() { }

    private static <T> T unsupported() { throw new UnsupportedOperationException("Network Capability Is Unavailable"); }

    static boolean shouldRecoverLifecycle(NetworkLifecycleJob job, NetworkDefinition network) {
        if (job == null || network == null || job.status() != NetworkLifecycleStatus.SUCCEEDED || job.operation() == NetworkLifecycleOperation.DRAIN) return false;
        NetworkDesiredState desiredState = job.operation() == NetworkLifecycleOperation.STOP ? NetworkDesiredState.STOPPED : NetworkDesiredState.RUNNING;
        return network.revision() == job.networkRevision() || network.revision() == job.networkRevision() + 1 && network.desiredState() == desiredState;
    }

    static <T, R> NetworkManager<T, R> unavailable() { return (NetworkManager<T, R>) UnavailableNetworkManager.INSTANCE; }

    final class UnavailableNetworkManager implements NetworkManager<Object, Object> {
        private static final UnavailableNetworkManager INSTANCE = new UnavailableNetworkManager();
        private UnavailableNetworkManager() { }
        @Override public boolean available() { return false; }
    }
}
