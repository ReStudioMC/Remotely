package redxax.oxy.remotely.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class NetworkLifecyclePlanner {
    public List<NetworkLifecycleStep> plan(NetworkDefinition network, NetworkLifecycleOperation operation) {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(operation, "operation");
        List<NetworkMember> startOrder = startOrder(network);
        List<NetworkMember> stopOrder = new ArrayList<>(startOrder);
        stopOrder.removeIf(NetworkMember::isProxy);
        Collections.reverse(stopOrder);
        stopOrder.addFirst(network.proxyMember());
        List<NetworkLifecycleStep> steps = new ArrayList<>();
        switch (operation) {
            case START -> start(network, startOrder, steps);
            case STOP -> addSteps(steps, stopOrder, NetworkLifecycleAction.STOP);
            case RESTART -> {
                addSteps(steps, stopOrder, NetworkLifecycleAction.STOP);
                start(network, startOrder, steps);
            }
            case ROLLING_RESTART -> rolling(network, startOrder, steps);
            case DRAIN -> addSteps(steps, startOrder.stream().filter(member -> !member.isProxy()).toList(),
                    NetworkLifecycleAction.DRAIN);
        }
        return List.copyOf(steps);
    }

    public List<NetworkLifecycleStep> planMember(NetworkDefinition network, NetworkMember member,
                                                 NetworkLifecycleOperation operation) {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(member, "member");
        if (!network.members().contains(member) || !member.isManaged()) {
            throw new IllegalArgumentException("Server does not belong to this network");
        }
        NetworkLifecycleAction action = switch (Objects.requireNonNull(operation, "operation")) {
            case START -> NetworkLifecycleAction.START;
            case STOP -> NetworkLifecycleAction.STOP;
            default -> throw new IllegalArgumentException("Individual servers can only be started or stopped");
        };
        List<NetworkLifecycleStep> steps = new ArrayList<>();
        addSteps(steps, List.of(member), action);
        if (action == NetworkLifecycleAction.START && network.runtime().enabled() && (member.isProxy() || member.resyncEnabled())) {
            addSteps(steps, List.of(member), NetworkLifecycleAction.HEALTH_GATE);
        }
        return List.copyOf(steps);
    }

    private void start(NetworkDefinition network, List<NetworkMember> startOrder, List<NetworkLifecycleStep> steps) {
        if (!network.runtime().enabled()) {
            addSteps(steps, startOrder, NetworkLifecycleAction.START);
            return;
        }
        NetworkMember proxy = Objects.requireNonNull(network.proxyMember(), "Network Proxy Is Required");
        addSteps(steps, List.of(proxy), NetworkLifecycleAction.START);
        addSteps(steps, List.of(proxy), NetworkLifecycleAction.HEALTH_GATE);
        List<NetworkMember> backends = startOrder.stream().filter(member -> !member.isProxy()).toList();
        addSteps(steps, backends, NetworkLifecycleAction.START);
        addSteps(steps, backends.stream().filter(NetworkMember::resyncEnabled).toList(), NetworkLifecycleAction.HEALTH_GATE);
    }

    private void rolling(NetworkDefinition network, List<NetworkMember> startOrder, List<NetworkLifecycleStep> steps) {
        List<NetworkMember> backends = new ArrayList<>(startOrder.stream().filter(member -> !member.isProxy()).toList());
        Collections.reverse(backends);
        if (!network.runtime().enabled() || backends.stream().anyMatch(member -> !member.resyncEnabled())) {
            throw new IllegalStateException("A rolling restart requires ReSync on every managed backend");
        }
        if (backends.size() < 2) {
            throw new IllegalStateException("A rolling restart requires at least two backends");
        }
        for (NetworkMember member : backends) {
            addSteps(steps, List.of(member), NetworkLifecycleAction.CAPACITY_GATE);
            addSteps(steps, List.of(member), NetworkLifecycleAction.MAINTENANCE);
            addSteps(steps, List.of(member), NetworkLifecycleAction.DRAIN);
            addSteps(steps, List.of(member), NetworkLifecycleAction.STOP);
            addSteps(steps, List.of(member), NetworkLifecycleAction.START);
            addSteps(steps, List.of(member), NetworkLifecycleAction.HEALTH_GATE);
            addSteps(steps, List.of(member), NetworkLifecycleAction.RESUME);
        }
    }

    private List<NetworkMember> startOrder(NetworkDefinition network) {
        Map<String, NetworkMember> membersByNode = new LinkedHashMap<>();
        network.members().forEach(member -> membersByNode.put(member.nodeId(), member));
        LinkedHashSet<NetworkMember> ordered = new LinkedHashSet<>();
        network.routingGroups().stream().filter(group -> group.id().equals("fallback"))
                .flatMap(group -> group.nodeIds().stream()).map(membersByNode::get)
                .filter(member -> member != null && member.isManaged() && !member.isProxy()).forEach(ordered::add);
        network.members().stream().filter(NetworkMember::isManaged).filter(member -> !member.isProxy())
                .sorted(Comparator.comparing(NetworkMember::routeName, String.CASE_INSENSITIVE_ORDER)).forEach(ordered::add);
        ordered.add(network.proxyMember());
        return ordered.stream().filter(Objects::nonNull).toList();
    }

    private void addSteps(List<NetworkLifecycleStep> steps, List<NetworkMember> members, NetworkLifecycleAction action) {
        for (NetworkMember member : members) {
            steps.add(NetworkLifecycleStep.pending(member, action, steps.size()));
        }
    }
}
