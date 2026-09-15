package redxax.oxy.remotely.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record NetworkPlanInput(NetworkDefinition network, Map<String, NetworkServerDescriptor> servers, List<NetworkValidationIssue> issues) {
    public NetworkPlanInput {
        network = Objects.requireNonNull(network, "network");
        servers = servers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(servers));
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
