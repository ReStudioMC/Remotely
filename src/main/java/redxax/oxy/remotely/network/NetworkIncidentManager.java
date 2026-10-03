package redxax.oxy.remotely.network;

import java.time.Duration;

import restudio.resync.network.NetworkEvent;
import restudio.resync.network.NetworkEventTopics;
import restudio.resync.network.NetworkPlayerLifecycle;
import restudio.resync.network.NetworkPlayerLifecycleCodec;
import restudio.resync.network.NetworkPlayerLifecycleType;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


public class NetworkIncidentManager {
    private static final int MAXIMUM_INCIDENTS_PER_NETWORK = 1000;
    private static final long RESOLVED_RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    private static final long REFRESH_INTERVAL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long HEAT_WINDOW_MILLIS = Duration.ofHours(24).toMillis();
    private static final Set<String> EXPECTED_DISCOVERY_FINDINGS = Set.of("member.external.unmanaged", "port.conflict.resolved-by-plan");
    private final NetworkIncidentRepository repository;
    private final Map<String, List<NetworkIncident>> histories = new LinkedHashMap<>();

    public NetworkIncidentManager(Path applicationDirectory) {
        repository = new NetworkIncidentRepository(applicationDirectory);
    }

    public synchronized void observeRuntime(NetworkDefinition network, NetworkRuntimeSnapshot snapshot) {
        if (network == null || snapshot == null || !network.networkId().equals(snapshot.networkId())) {
            return;
        }
        if (snapshot.state() == NetworkRuntimeConnectionState.CONNECTING) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        Map<String, Condition> connection = new LinkedHashMap<>();
        if (snapshot.state() == NetworkRuntimeConnectionState.RECONNECTING || snapshot.state() == NetworkRuntimeConnectionState.UNAVAILABLE) {
            NetworkIncidentSeverity severity = snapshot.state() == NetworkRuntimeConnectionState.UNAVAILABLE ? NetworkIncidentSeverity.CRITICAL : NetworkIncidentSeverity.WARNING;
            connection.put("runtime.connection", new Condition("", "runtime.connection", severity, titleCase(snapshot.state().name()) + " Runtime", snapshot.message()));
        }
        reconcile(network.networkId(), NetworkIncidentSource.RUNTIME, "runtime.connection", connection, now);
        if (snapshot.state() == NetworkRuntimeConnectionState.DISABLED) {
            reconcile(network.networkId(), NetworkIncidentSource.RUNTIME, "runtime.node.", Map.of(), now);
        }
        if (!snapshot.connected()) {
            return;
        }
        Map<String, Condition> nodes = new LinkedHashMap<>();
        for (NetworkMember member : network.members()) {
            if (!member.isProxy() && (!member.isManaged() || !member.resyncEnabled())) {
                continue;
            }
            NetworkRuntimeNodePresence presence = snapshot.node(member.nodeId()).orElse(null);
            if (presence == null) {
                nodes.put("runtime.node.missing:" + member.nodeId(), new Condition(member.nodeId(), "runtime.node.missing", NetworkIncidentSeverity.WARNING, member.routeName() + " Has No Runtime Presence", "The Runtime Is Connected But This Node Has Not Reported Presence"));
                continue;
            }
            if (presence.status() == NetworkRuntimeNodeStatus.OFFLINE || presence.status() == NetworkRuntimeNodeStatus.REVOKED) {
                String detail = presence.observedAt() > 0 ? "Last Runtime Observation " + presence.observedAt() : "No Runtime Observation Yet";
                nodes.put("runtime.node.status:" + member.nodeId(), new Condition(member.nodeId(), "runtime.node.status", NetworkIncidentSeverity.CRITICAL, member.routeName() + " Is " + titleCase(presence.status().name()), detail));
                continue;
            }
            if (presence.mspt() >= 50 || presence.tps() >= 0 && presence.tps() < 18) {
                NetworkIncidentSeverity severity = presence.mspt() >= 100 || presence.tps() >= 0 && presence.tps() < 15 ? NetworkIncidentSeverity.CRITICAL : NetworkIncidentSeverity.WARNING;
                String detail = presence.mspt() >= 0 ? String.format(Locale.ROOT, "%.1f MSPT • %.1f TPS", presence.mspt(), presence.tps()) : String.format(Locale.ROOT, "%.1f TPS", presence.tps());
                nodes.put("runtime.node.tick:" + member.nodeId(), new Condition(member.nodeId(), "runtime.performance.tick", severity, member.routeName() + " Is Falling Behind", detail));
            }
            if (presence.heapMaximum() > 0) {
                double heap = (double) presence.heapUsed() / presence.heapMaximum();
                if (heap >= 0.9) {
                    NetworkIncidentSeverity severity = heap >= 0.97 ? NetworkIncidentSeverity.CRITICAL : NetworkIncidentSeverity.WARNING;
                    nodes.put("runtime.node.memory:" + member.nodeId(), new Condition(member.nodeId(), "runtime.performance.memory", severity, member.routeName() + " Memory Is High", Math.round(heap * 100) + "% Heap Used"));
                }
            }
            if (presence.capacity() > 0 && presence.players() >= presence.capacity()) {
                nodes.put("runtime.node.capacity:" + member.nodeId(), new Condition(member.nodeId(), "runtime.capacity", NetworkIncidentSeverity.WARNING, member.routeName() + " Is At Capacity", presence.players() + "/" + presence.capacity() + " Players"));
            }
        }
        reconcile(network.networkId(), NetworkIncidentSource.RUNTIME, "runtime.node.", nodes, now);
    }

    public synchronized void observeDiscovery(NetworkDiscoveryResult discovery) {
        if (discovery == null || discovery.network() == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        Map<String, Condition> conditions = new LinkedHashMap<>();
        for (NetworkValidationIssue issue : discovery.issues()) {
            if (issue.severity() == NetworkValidationIssue.Severity.INFO || EXPECTED_DISCOVERY_FINDINGS.contains(issue.code())) {
                continue;
            }
            String nodeId = discovery.network().members().stream().filter(member -> member.nodeId().equals(issue.subject()) || member.instanceId().equals(issue.subject())).map(NetworkMember::nodeId).findFirst().orElse("");
            NetworkIncidentSeverity severity = issue.severity() == NetworkValidationIssue.Severity.ERROR ? NetworkIncidentSeverity.CRITICAL : NetworkIncidentSeverity.WARNING;
            String key = "discovery:" + issue.code() + ":" + issue.subject();
            conditions.put(key, new Condition(nodeId, "discovery." + issue.code(), severity, readableCode(issue.code()), issue.message()));
        }
        reconcile(discovery.network().networkId(), NetworkIncidentSource.DISCOVERY, "discovery:", conditions, now);
    }

    public synchronized void observeEvent(NetworkDefinition network, NetworkEvent event) {
        if (network == null || event == null || !network.networkId().equals(event.networkId()) || !NetworkEventTopics.PLAYER_LIFECYCLE.equals(event.channel())) {
            return;
        }
        NetworkPlayerLifecycle lifecycle = NetworkPlayerLifecycleCodec.decode(event.payload());
        if (lifecycle.type() != NetworkPlayerLifecycleType.TRANSFER_FAILED) {
            return;
        }
        List<NetworkIncident> incidents = new ArrayList<>(history(network.networkId()));
        String key = "event.transfer.failure:" + event.eventId();
        if (incidents.stream().anyMatch(incident -> incident.key().equals(key))) {
            return;
        }
        String route = lifecycle.targetRoute().isBlank() ? lifecycle.sourceRoute() : lifecycle.targetRoute();
        String nodeId = network.members().stream().filter(member -> member.routeName().equalsIgnoreCase(route)).map(NetworkMember::nodeId).findFirst().orElse("");
        String path = lifecycle.sourceRoute() + (lifecycle.targetRoute().isBlank() ? "" : " → " + lifecycle.targetRoute());
        String detail = path + (lifecycle.failure().isBlank() ? "" : " • " + titleCase(lifecycle.failure()));
        long occurredAt = Math.max(0, lifecycle.occurredAt());
        incidents.add(NetworkIncident.open(network.networkId(), key, nodeId, "transfer.failure", NetworkIncidentSource.EVENT, NetworkIncidentSeverity.WARNING, "Transfer Failed • " + lifecycle.playerName(), detail, occurredAt).resolve(occurredAt));
        persist(network.networkId(), incidents, Instant.now().toEpochMilli());
    }

    public synchronized List<NetworkIncident> incidents(String networkId) {
        List<NetworkIncident> retained = new ArrayList<>(history(networkId));
        long now = Instant.now().toEpochMilli();
        if (retained.removeIf(incident -> incident.status() == NetworkIncidentStatus.RESOLVED && incident.updatedAt() < now - RESOLVED_RETENTION_MILLIS)) {
            persist(networkId, retained, now);
        }
        return retained.stream().sorted(Comparator.comparingLong(NetworkIncident::updatedAt).reversed()).toList();
    }

    public synchronized int openCount(String networkId) {
        return (int) incidents(networkId).stream().filter(incident -> incident.status() == NetworkIncidentStatus.OPEN).count();
    }

    public synchronized Map<String, Integer> transferFailureHeat(String networkId) {
        long cutoff = Instant.now().toEpochMilli() - HEAT_WINDOW_MILLIS;
        Map<String, Integer> heat = new LinkedHashMap<>();
        incidents(networkId).stream().filter(incident -> incident.type().equals("transfer.failure") && incident.updatedAt() >= cutoff && !incident.nodeId().isBlank()).forEach(incident -> heat.merge(incident.nodeId(), 1, Integer::sum));
        return Map.copyOf(heat);
    }

    public synchronized void delete(String networkId) {
        histories.remove(networkId);
        repository.delete(networkId);
    }

    private void reconcile(String networkId, NetworkIncidentSource source, String namespace, Map<String, Condition> active, long now) {
        List<NetworkIncident> incidents = new ArrayList<>(history(networkId));
        boolean changed = false;
        for (Map.Entry<String, Condition> entry : active.entrySet()) {
            Condition condition = entry.getValue();
            NetworkIncident existing = incidents.stream().filter(incident -> incident.status() == NetworkIncidentStatus.OPEN && incident.source() == source && incident.key().equals(entry.getKey())).findFirst().orElse(null);
            if (existing == null) {
                incidents.add(NetworkIncident.open(networkId, entry.getKey(), condition.nodeId(), condition.type(), source, condition.severity(), condition.summary(), condition.detail(), now));
                changed = true;
            } else if (existing.severity() != condition.severity() || !existing.summary().equals(condition.summary()) || (!existing.detail().equals(condition.detail()) && now - existing.updatedAt() >= REFRESH_INTERVAL_MILLIS)) {
                incidents.set(incidents.indexOf(existing), existing.refresh(condition.severity(), condition.summary(), condition.detail(), now));
                changed = true;
            }
        }
        for (int index = 0; index < incidents.size(); index++) {
            NetworkIncident incident = incidents.get(index);
            if (incident.status() == NetworkIncidentStatus.OPEN && incident.source() == source && incident.key().startsWith(namespace) && !active.containsKey(incident.key())) {
                incidents.set(index, incident.resolve(now));
                changed = true;
            }
        }
        if (changed) {
            persist(networkId, incidents, now);
        }
    }

    private List<NetworkIncident> history(String networkId) {
        return histories.computeIfAbsent(networkId, repository::load);
    }

    private void persist(String networkId, List<NetworkIncident> incidents, long now) {
        incidents.removeIf(incident -> incident.status() == NetworkIncidentStatus.RESOLVED && incident.updatedAt() < now - RESOLVED_RETENTION_MILLIS);
        if (incidents.size() > MAXIMUM_INCIDENTS_PER_NETWORK) {
            List<NetworkIncident> retained = incidents.stream().sorted(Comparator.comparing((NetworkIncident incident) -> incident.status() == NetworkIncidentStatus.OPEN).reversed().thenComparing(NetworkIncident::updatedAt, Comparator.reverseOrder())).limit(MAXIMUM_INCIDENTS_PER_NETWORK).toList();
            incidents.clear();
            incidents.addAll(retained);
        }
        repository.save(networkId, incidents);
        histories.put(networkId, new ArrayList<>(incidents));
    }

    private String readableCode(String code) {
        return titleCase(code == null ? "Network Finding" : code.replace('.', ' '));
    }

    private static String titleCase(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (char character : normalized.toCharArray()) {
            result.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = character == ' ';
        }
        return result.toString();
    }

    private record Condition(String nodeId, String type, NetworkIncidentSeverity severity, String summary, String detail) {
    }
}
