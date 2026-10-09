package redxax.oxy.remotely.network;

import restudio.rebase.instance.Instance;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.backend.impl.PteroBackend;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class NetworkDiscoveryService {
    private final NetworkPortAllocator portAllocator;

    public NetworkDiscoveryService(NetworkPortAllocator portAllocator) {
        this.portAllocator = portAllocator == null ? new NetworkPortAllocator() : portAllocator;
    }

    public NetworkDiscoveryResult discover(NetworkDefinition network, Collection<Instance> instances, Collection<NetworkDefinition> allNetworks, Collection<PortReservation> externalReservations) {
        Map<String, Instance> byId = new LinkedHashMap<>();
        if (instances != null) {
            instances.stream().filter(instance -> instance != null && instance.getInstanceId() != null).forEach(instance -> byId.put(instance.getInstanceId(), instance));
        }
        List<NetworkValidationIssue> issues = new ArrayList<>(NetworkValidator.validate(network));
        List<NetworkMemberObservation> observations = new ArrayList<>();
        long observedAt = Instant.now().toEpochMilli();
        for (NetworkMember member : network.members()) {
            if (!member.isManaged()) {
                issues.add(warning("member.external.unmanaged", member.nodeId(), "External backend files, forwarding, lifecycle, and firewall policy are not managed by Remotely"));
                observations.add(new NetworkMemberObservation(member.nodeId(), member.instanceId(), true, member.hostScope(), member.port(), "external", NetworkObservationState.DRIFTED, observedAt));
                continue;
            }
            Instance instance = byId.get(member.instanceId());
            if (instance == null) {
                issues.add(error("instance.unavailable", member.instanceId(), "Network member is not available in Remotely"));
                observations.add(new NetworkMemberObservation(member.nodeId(), member.instanceId(), false, "", 0, "", NetworkObservationState.UNREACHABLE, observedAt));
                continue;
            }
            boolean providerManaged = providerManaged(instance);
            String hostScope = providerManaged ? member.hostScope() : NetworkHostScope.resolve(instance);
            int observedPort = providerManaged ? member.port() : member.isProxy() ? 0 : observedPort(instance);
            String software = instance.getServerSoftwareType().toLowerCase(Locale.ROOT);
            NetworkObservationState state = NetworkObservationState.HEALTHY;
            if (providerManaged) {
                issues.add(info("provider.allocation.managed", member.nodeId(), "Provider allocation " + member.address() + ":" + member.port() + " is externally assigned and revalidated before network mutations"));
            } else if (!hostScope.equals(member.hostScope())) {
                issues.add(warning("host.drift", member.nodeId(), "Member host changed from " + member.hostScope() + " to " + hostScope));
                state = NetworkObservationState.DRIFTED;
            }
            if (!providerManaged && observedPort > 0 && observedPort != member.port()) {
                issues.add(warning("port.drift", member.nodeId(), "Configured server port " + observedPort + " differs from desired port " + member.port()));
                state = NetworkObservationState.DRIFTED;
            }
            if (member.isProxy()) {
                if (!isVelocity(instance)) {
                    issues.add(error("proxy.velocity.required", member.nodeId(), "Managed networks require a Velocity proxy"));
                    state = NetworkObservationState.DEGRADED;
                }
            } else {
                if (instance.isProxyServer()) {
                    issues.add(error("backend.proxy.invalid", member.nodeId(), "A backend member cannot be proxy software"));
                    state = NetworkObservationState.DEGRADED;
                }
                if (network.forwarding().mode() == ForwardingMode.MODERN && !supportsModernForwarding(instance)) {
                    issues.add(error("backend.forwarding.unsupported", member.nodeId(), "Backend requires a supported modern-forwarding bridge before it can join this network"));
                    state = NetworkObservationState.DEGRADED;
                }
                if (Boolean.parseBoolean(instance.getServerProperties().getProperty("online-mode", "true"))) {
                    issues.add(warning("backend.online-mode.drift", member.nodeId(), "Backend online mode must be disabled only when proxy routing and security controls are ready"));
                    state = NetworkObservationState.DRIFTED;
                }
            }
            observations.add(new NetworkMemberObservation(member.nodeId(), member.instanceId(), true, hostScope, observedPort, software, state, observedAt));
        }
        List<NetworkDefinition> networksForPorts = new ArrayList<>();
        if (allNetworks != null) {
            allNetworks.stream().filter(candidate -> !candidate.networkId().equals(network.networkId())).forEach(networksForPorts::add);
        }
        networksForPorts.add(network);
        List<PortReservation> reservations = portAllocator.discover(networksForPorts, instances, externalReservations);
        Set<String> desiredEndpoints = network.members().stream().map(member -> member.hostScope() + ":" + member.port()).collect(Collectors.toSet());
        NetworkMember proxy = network.proxyMember();
        if (proxy != null && network.runtime().enabled() && network.runtime().hubPort() > 0) {
            desiredEndpoints.add(proxy.hostScope() + ":" + network.runtime().hubPort());
        }
        for (PortReservation conflict : portAllocator.conflicts(reservations).stream().filter(reservation -> desiredEndpoints.contains(reservation.hostScope() + ":" + reservation.port())).collect(Collectors.toMap(reservation -> reservation.hostScope() + ":" + reservation.port(), reservation -> reservation, (first, second) -> first, LinkedHashMap::new)).values()) {
            List<PortReservation> colliders = reservations.stream().filter(reservation -> reservation.hostScope().equals(conflict.hostScope()) && reservation.port() == conflict.port()).toList();
            if (colliders.stream().map(PortReservation::ownerId).distinct().count() > 1) {
                String owners = colliders.stream().map(PortReservation::label).collect(Collectors.joining(" and "));
                issues.add(error("port.conflict", conflict.ownerId(), "Port " + conflict.port() + " on " + conflict.hostScope() + " is reserved by " + owners));
            }
        }
        return new NetworkDiscoveryResult(network, byId, observations, reservations, issues);
    }

    private boolean isVelocity(Instance instance) {
        if (instance.getModLoader() == ModLoader.VELOCITY) {
            return true;
        }
        return instance.getServerSoftwareCompatibility().stream().anyMatch(value -> "velocity".equalsIgnoreCase(value));
    }

    public static boolean supportsModernForwarding(Instance instance) {
        return DesktopNetworkPlanInput.forwardingAdapter(instance) != NetworkBackendForwardingAdapter.UNSUPPORTED;
    }

    private boolean providerManaged(Instance instance) {
        return instance.getBackendConfig() != null && instance.getBackendConfig().type != null && (PteroBackend.isPanelType(instance.getBackendConfig().type) || "RESTUDIO".equalsIgnoreCase(instance.getBackendConfig().type));
    }

    private int observedPort(Instance instance) {
        try {
            return Integer.parseInt(instance.getServerProperties().getProperty("server-port", "0"));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private NetworkValidationIssue error(String code, String subject, String message) {
        return new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, code, subject, message);
    }

    private NetworkValidationIssue warning(String code, String subject, String message) {
        return new NetworkValidationIssue(NetworkValidationIssue.Severity.WARNING, code, subject, message);
    }

    private NetworkValidationIssue info(String code, String subject, String message) {
        return new NetworkValidationIssue(NetworkValidationIssue.Severity.INFO, code, subject, message);
    }
}
