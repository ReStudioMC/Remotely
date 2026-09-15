package redxax.oxy.remotely.network;

import redxax.oxy.remotely.network.config.NetworkConfigurationAdapter;
import redxax.oxy.remotely.network.config.NetworkConfigurationAdapters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class NetworkMutationEngine {
    private final NetworkConfigurationAdapters adapters;

    public NetworkMutationEngine(NetworkConfigurationAdapters adapters) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    public NetworkReconciliationPlan resolveCurrentValues(NetworkReconciliationPlan plan, Map<NetworkConfigDocumentKey, String> documents) {
        List<NetworkConfigMutation> resolved = new ArrayList<>();
        Map<NetworkConfigDocumentKey, String> workingDocuments = new LinkedHashMap<>();
        if (documents != null) {
            workingDocuments.putAll(documents);
        }
        for (NetworkConfigMutation mutation : plan.mutations()) {
            NetworkConfigDocumentKey documentKey = new NetworkConfigDocumentKey(mutation.instanceId(), mutation.path());
            String content = workingDocuments.getOrDefault(documentKey, "");
            NetworkConfigurationAdapter adapter = adapters.get(mutation.format());
            String currentValue = adapter.read(content, mutation.key());
            boolean currentPresent = adapter.contains(content, mutation.key());
            if (mutation.action() == NetworkMutationAction.REMOVE && !currentPresent) {
                currentPresent = !content.equals(adapter.remove(content, mutation.key()));
            }
            NetworkConfigMutation resolvedMutation = new NetworkConfigMutation(mutation.instanceId(), mutation.path(), mutation.format(), mutation.key(), currentValue, mutation.desiredValue(), mutation.sensitive(), mutation.restartRequired(), mutation.description(), mutation.action(), currentPresent);
            resolved.add(resolvedMutation);
            if (resolvedMutation.changesValue()) {
                workingDocuments.put(documentKey, resolvedMutation.action() == NetworkMutationAction.REMOVE ? adapter.remove(content, resolvedMutation.key()) : adapter.apply(content, resolvedMutation.key(), resolvedMutation.desiredValue()));
            }
        }
        return new NetworkReconciliationPlan(plan.planId(), plan.networkId(), plan.networkRevision(), plan.createdAt(), resolved, plan.issues(), plan.strategy());
    }

    public String apply(String content, List<NetworkConfigMutation> mutations) {
        String updated = content == null ? "" : content;
        if (mutations == null) {
            return updated;
        }
        for (NetworkConfigMutation mutation : mutations) {
            NetworkConfigurationAdapter adapter = adapters.get(mutation.format());
            updated = mutation.action() == NetworkMutationAction.REMOVE ? adapter.remove(updated, mutation.key()) : adapter.apply(updated, mutation.key(), mutation.desiredValue());
        }
        return updated;
    }
}
