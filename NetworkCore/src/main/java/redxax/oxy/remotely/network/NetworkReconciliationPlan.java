package redxax.oxy.remotely.network;

import java.util.List;
import java.util.UUID;

public record NetworkReconciliationPlan(String planId, String networkId, long networkRevision, long createdAt, List<NetworkConfigMutation> mutations, List<NetworkValidationIssue> issues, NetworkPlanStrategy strategy) {
    public NetworkReconciliationPlan(String planId, String networkId, long networkRevision, long createdAt, List<NetworkConfigMutation> mutations, List<NetworkValidationIssue> issues) {
        this(planId, networkId, networkRevision, createdAt, mutations, issues, NetworkPlanStrategy.RECONCILE);
    }

    public NetworkReconciliationPlan {
        planId = planId == null || planId.isBlank() ? UUID.randomUUID().toString() : planId;
        networkId = networkId == null ? "" : networkId.trim();
        createdAt = createdAt <= 0 ? NetworkClock.SYSTEM.millis() : createdAt;
        mutations = mutations == null ? List.of() : List.copyOf(mutations);
        issues = issues == null ? List.of() : List.copyOf(issues);
        strategy = strategy == null ? NetworkPlanStrategy.RECONCILE : strategy;
    }

    public boolean canApply() {
        return issues.stream().noneMatch(NetworkValidationIssue::blocksPersistence);
    }

    public boolean restartRequired() {
        return mutations.stream().filter(NetworkConfigMutation::changesValue).anyMatch(NetworkConfigMutation::restartRequired);
    }

    public List<NetworkConfigMutation> changes() {
        return mutations.stream().filter(NetworkConfigMutation::changesValue).toList();
    }
}
