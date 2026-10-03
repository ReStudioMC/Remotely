package redxax.oxy.remotely.network;

import redxax.oxy.remotely.util.TaskSchedulers;

import redxax.oxy.remotely.util.AsyncTools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.rebase.api.unified.InstanceApi;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceState;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;



public class NetworkPreflightService {
    private static final int MAX_STATUS_PACKET = 1_048_576;
    private final NetworkDiscoveryService discoveryService;
    private final NetworkDesiredStatePlanner desiredStatePlanner;
    private final NetworkSecretStore secretStore;
    private final NetworkConfigurationTransaction configurationTransaction;
    private final NetworkProviderAllocationService providerAllocationService;

    public NetworkPreflightService(NetworkDiscoveryService discoveryService, NetworkDesiredStatePlanner desiredStatePlanner, NetworkSecretStore secretStore, NetworkConfigurationTransaction configurationTransaction) {
        this.discoveryService = discoveryService;
        this.desiredStatePlanner = desiredStatePlanner;
        this.secretStore = secretStore;
        this.configurationTransaction = configurationTransaction;
        this.providerAllocationService = new NetworkProviderAllocationService();
    }

    public Async<List<NetworkPreflightCheck>> run(NetworkDefinition network, Collection<Instance> instances, Collection<NetworkDefinition> networks) {
        Map<String, Instance> instancesById = new LinkedHashMap<>();
        if (instances != null) {
            instances.stream().filter(instance -> instance != null).forEach(instance -> instancesById.put(instance.getInstanceId(), instance));
        }
        NetworkDiscoveryResult discovery = discoveryService.discover(network, instances, networks, List.of());
        NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discovery), secretStore);
        List<NetworkPreflightCheck> immediate = new ArrayList<>();
        immediate.addAll(findingChecks(discovery.issues()));
        immediate.add(routingCheck(network));
        immediate.add(securityCheck(network));
        Async<NetworkPreflightCheck> configuration = configurationCheck(network, plan, instances);
        NetworkMember proxyMember = network.proxyMember();
        Instance proxy = proxyMember == null ? null : instancesById.get(proxyMember.instanceId());
        List<Async<NetworkPreflightCheck>> memberChecks = network.members().stream().map(member -> memberCheck(member, instancesById.get(member.instanceId()), proxy)).toList();
        Async<NetworkPreflightCheck> entry = entryCheck(network, proxy);
        List<Async<NetworkPreflightCheck>> asynchronous = new ArrayList<>();
        asynchronous.add(configuration);
        asynchronous.addAll(memberChecks);
        asynchronous.add(entry);
        return Async.allOf(asynchronous.toArray(Async[]::new)).thenApply(unused -> {
            List<NetworkPreflightCheck> checks = new ArrayList<>(immediate);
            asynchronous.forEach(future -> checks.add(future.join()));
            return List.copyOf(checks);
        });
    }

    private List<NetworkPreflightCheck> findingChecks(List<NetworkValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of(NetworkPreflightCheck.passed("topology", "network", "Topology And Compatibility", "No Discovery Findings"));
        }
        List<NetworkPreflightCheck> checks = new ArrayList<>();
        int index = 1;
        for (NetworkValidationIssue issue : issues) {
            NetworkPreflightCheck check = issue.blocksPersistence() ? NetworkPreflightCheck.failed("finding-" + index, issue.subject(), issue.message(), issue.code()) : NetworkPreflightCheck.warning("finding-" + index, issue.subject(), issue.message(), issue.code());
            checks.add(check);
            index++;
        }
        return List.copyOf(checks);
    }

    private Async<NetworkPreflightCheck> configurationCheck(NetworkDefinition network, NetworkReconciliationPlan plan, Collection<Instance> instances) {
        if (!plan.canApply()) {
            String detail = plan.issues().stream().filter(NetworkValidationIssue::blocksPersistence).map(NetworkValidationIssue::message).findFirst().orElse("Configuration validation failed");
            return Async.completed(NetworkPreflightCheck.failed("configuration", plan.networkId(), "Configuration Applied", detail));
        }
        return configurationTransaction.prepare(plan, instances).thenApply(prepared -> {
            List<NetworkJobDocument> changes = configurationTransaction.describe(prepared, network, instances).stream().filter(NetworkJobDocument::changed).toList();
            if (changes.isEmpty()) {
                return NetworkPreflightCheck.passed("configuration", plan.networkId(), "Configuration Applied", prepared.documents().size() + " Managed Files Match");
            }
            String paths = changes.stream().map(document -> document.key().path()).limit(3).collect(Collectors.joining(", "));
            return NetworkPreflightCheck.failed("configuration", plan.networkId(), "Configuration Applied", changes.size() + " Pending Files: " + paths + ". Stop The Network And Reapply");
        }).handle((check, throwable) -> throwable == null ? check : NetworkPreflightCheck.failed("configuration", plan.networkId(), "Configuration Applied", rootMessage(throwable)));
    }

    private Async<NetworkPreflightCheck> memberCheck(NetworkMember member, Instance instance, Instance proxy) {
        if (!member.isManaged()) {
            return externalMemberCheck(member, proxy);
        }
        if (instance == null) {
            return Async.completed(NetworkPreflightCheck.failed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " Ready", "Server Is Unavailable"));
        }
        Async<NetworkPreflightCheck> readiness = JvmAsyncBridge.fromFuture(InstanceApi.of(instance).console().getStatus()).handle((status, throwable) -> {
            if (throwable != null) {
                return NetworkPreflightCheck.failed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " Ready", rootMessage(throwable));
            }
            if (status.state() != InstanceState.RUNNING) {
                return NetworkPreflightCheck.failed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " Ready", "Observed " + status.state().name());
            }
            if (!status.ready()) {
                return NetworkPreflightCheck.failed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " Ready", status.detail().isBlank() ? "Listener Is Not Ready" : status.detail());
            }
            return NetworkPreflightCheck.passed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " Ready", status.detail().isBlank() ? "Listener Ready" : status.detail());
        });
        if (!providerAllocationService.isProviderManaged(instance)) {
            return readiness;
        }
        return readiness.thenCompose(check -> {
            if (check.status() == NetworkPreflightCheckStatus.FAILED) {
                return Async.completed(check);
            }
            return providerAllocationService.resolve(instance).handle((allocation, throwable) -> {
                if (throwable != null) {
                    return NetworkPreflightCheck.failed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " Provider Allocation", rootMessage(throwable));
                }
                if (allocation == null || !member.address().equalsIgnoreCase(allocation.address()) || member.port() != allocation.port() || !member.hostScope().equals(allocation.hostScope())) {
                    return NetworkPreflightCheck.failed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " Provider Allocation", "Provider Allocation Changed Since The Last Reviewed Revision");
                }
                return NetworkPreflightCheck.passed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " Ready", check.detail() + " • Provider Allocation Verified");
            });
        });
    }

    private Async<NetworkPreflightCheck> externalMemberCheck(NetworkMember member, Instance proxy) {
        BackendConfig proxyBackend = proxy == null ? null : proxy.getBackendConfig();
        boolean remoteLoopback = loopback(member.address()) && proxyBackend != null && proxyBackend.type != null && !"LOCAL".equalsIgnoreCase(proxyBackend.type);
        if (remoteLoopback) {
            return Async.completed(NetworkPreflightCheck.warning("member-" + member.nodeId(), member.nodeId(), member.routeName() + " External Readiness", "Loopback Route Must Be Verified On The Proxy Host"));
        }
        return AsyncTools.supply(TaskSchedulers.current(), () -> {
            try {
                StatusResponse response = minecraftStatus(member.address(), member.port());
                return NetworkPreflightCheck.passed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " External Readiness", response.version() + " • " + response.onlinePlayers() + "/" + response.maximumPlayers() + " Players");
            } catch (IOException | RuntimeException exception) {
                return NetworkPreflightCheck.failed("member-" + member.nodeId(), member.nodeId(), member.routeName() + " External Readiness", rootMessage(exception));
            }
        });
    }

    private Async<NetworkPreflightCheck> entryCheck(NetworkDefinition network, Instance proxy) {
        if (proxy == null || network.entryPoints().isEmpty()) {
            return Async.completed(NetworkPreflightCheck.failed("entry", network.networkId(), "Proxy Join Entry", "Proxy Entry Is Unavailable"));
        }
        NetworkEntryPoint entry = network.entryPoints().getFirst();
        NetworkMember proxyMember = network.proxyMember();
        String host = providerAllocationService.isProviderManaged(proxy) && proxyMember != null ? proxyMember.address() : probeHost(entry.bindAddress(), proxy);
        if (host.isBlank()) {
            return Async.completed(NetworkPreflightCheck.failed("entry", network.networkId(), "Proxy Join Entry", "A Reachable Proxy Address Could Not Be Resolved"));
        }
        return AsyncTools.supply(TaskSchedulers.current(), () -> {
            try {
                StatusResponse response = minecraftStatus(host, entry.port());
                return NetworkPreflightCheck.passed("entry", network.networkId(), "Proxy Join Entry", response.version() + " • " + response.onlinePlayers() + "/" + response.maximumPlayers() + " Players");
            } catch (IOException | RuntimeException exception) {
                return NetworkPreflightCheck.failed("entry", network.networkId(), "Proxy Join Entry", rootMessage(exception));
            }
        });
    }

    private NetworkPreflightCheck routingCheck(NetworkDefinition network) {
        List<String> routes = NetworkDesiredStatePlanner.fallbackRoutes(network);
        if (routes.isEmpty()) {
            return NetworkPreflightCheck.failed("routing", network.networkId(), "Fallback Route Published", "Fallback Order Is Empty");
        }
        return NetworkPreflightCheck.passed("routing", network.networkId(), "Fallback Route Published", routes.size() + " Fallback Servers");
    }

    private NetworkPreflightCheck securityCheck(NetworkDefinition network) {
        NetworkMember proxy = network.proxyMember();
        if (proxy == null) {
            return NetworkPreflightCheck.failed("security", network.networkId(), "Forwarding And Exposure", "Proxy Member Is Missing");
        }
        if (network.forwarding().mode() != ForwardingMode.MODERN) {
            return NetworkPreflightCheck.failed("security", network.networkId(), "Forwarding And Exposure", "Saved Forwarding Mode Is " + network.forwarding().mode().name() + ". Reapply Network Settings To Enable Modern Forwarding");
        }
        if (secretStore.resolveForwardingSecret(network.forwarding().secretReference()).isBlank()) {
            return NetworkPreflightCheck.failed("security", network.networkId(), "Forwarding And Exposure", "Forwarding Key Is Unavailable In The Credential Store");
        }
        boolean crossHost = network.members().stream().anyMatch(member -> !member.isProxy() && !member.hostScope().equals(proxy.hostScope()));
        if (crossHost && !network.forwarding().firewallVerified()) {
            return NetworkPreflightCheck.failed("security", network.networkId(), "Forwarding And Exposure", "Cross-Host Backend Protection Is Unverified");
        }
        boolean unsafeSameHost = network.members().stream().anyMatch(member -> !member.isProxy() && member.hostScope().equals(proxy.hostScope()) && !loopback(member.address()));
        if (unsafeSameHost) {
            return NetworkPreflightCheck.warning("security", network.networkId(), "Forwarding And Exposure", "Same-Host Backend Route Is Not Loopback");
        }
        if (network.members().stream().anyMatch(member -> !member.isManaged())) {
            return NetworkPreflightCheck.warning("security", network.networkId(), "Forwarding And Exposure", "External Backend Forwarding And Firewall Policy Require Manual Verification");
        }
        return NetworkPreflightCheck.passed("security", network.networkId(), "Forwarding And Exposure", crossHost ? "Cross-Host Protection Verified" : "Backends Use Loopback Routing");
    }

    StatusResponse minecraftStatus(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(3000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshake = new DataOutputStream(handshakeBytes);
            writeVarInt(handshake, 0);
            writeVarInt(handshake, -1);
            writeString(handshake, host);
            handshake.writeShort(port);
            writeVarInt(handshake, 1);
            writePacket(output, handshakeBytes.toByteArray());
            writePacket(output, new byte[]{0});
            int packetLength = readVarInt(input);
            if (packetLength < 1 || packetLength > MAX_STATUS_PACKET) {
                throw new IOException("Proxy Returned An Invalid Status Packet");
            }
            byte[] packetBytes = input.readNBytes(packetLength);
            if (packetBytes.length != packetLength) throw new IOException("Proxy Status Response Ended Early");
            DataInputStream packet = new DataInputStream(new ByteArrayInputStream(packetBytes));
            if (readVarInt(packet) != 0) throw new IOException("Proxy Returned An Invalid Status Packet");
            int jsonLength = readVarInt(packet);
            if (jsonLength < 2 || jsonLength != packet.available()) {
                throw new IOException("Proxy Returned An Invalid Status Payload");
            }
            byte[] jsonBytes = packet.readNBytes(jsonLength);
            if (jsonBytes.length != jsonLength) {
                throw new IOException("Proxy Status Response Ended Early");
            }
            JsonObject response = JsonParser.parseString(new String(jsonBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject version = response.has("version") && response.get("version").isJsonObject() ? response.getAsJsonObject("version") : new JsonObject();
            JsonObject players = response.has("players") && response.get("players").isJsonObject() ? response.getAsJsonObject("players") : new JsonObject();
            String versionName = version.has("name") ? version.get("name").getAsString() : "Minecraft";
            int online = players.has("online") ? players.get("online").getAsInt() : 0;
            int maximum = players.has("max") ? players.get("max").getAsInt() : 0;
            return new StatusResponse(versionName, online, maximum);
        }
    }

    private void writePacket(DataOutputStream output, byte[] payload) throws IOException {
        writeVarInt(output, payload.length);
        output.write(payload);
        output.flush();
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private void writeVarInt(DataOutputStream output, int value) throws IOException {
        int remaining = value;
        do {
            byte part = (byte) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining != 0) {
                part |= (byte) 0x80;
            }
            output.writeByte(part);
        } while (remaining != 0);
    }

    private int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        int position = 0;
        while (position < 35) {
            byte current = input.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        throw new IOException("Proxy Returned An Oversized VarInt");
    }

    private String probeHost(String bindAddress, Instance proxy) {
        String bind = bindAddress == null ? "" : bindAddress.trim();
        if (!bind.isBlank() && !bind.equals("0.0.0.0") && !bind.equals("::") && !loopback(bind)) {
            return bind;
        }
        BackendConfig backend = proxy.getBackendConfig();
        if (backend == null || backend.type == null || backend.type.isBlank() || backend.type.equalsIgnoreCase("LOCAL")) {
            return "127.0.0.1";
        }
        String host = backend.credentials == null ? "" : backend.credentials.getOrDefault("host", "").trim();
        if (host.contains("://")) {
            try {
                host = URI.create(host).getHost();
            } catch (IllegalArgumentException exception) {
                return "";
            }
        }
        return host == null ? "" : host.trim();
    }

    private boolean loopback(String address) {
        return address != null && (address.equalsIgnoreCase("localhost") || address.equals("127.0.0.1") || address.equals("::1"));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    record StatusResponse(String version, int onlinePlayers, int maximumPlayers) {
    }
}
