package redxax.oxy.remotely.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class NetworkExecutionPlan {
    private final NetworkMutationEngine mutations;

    public NetworkExecutionPlan(NetworkMutationEngine mutations) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
    }

    public NetworkPreparedPlan prepare(NetworkReconciliationPlan plan, Map<NetworkConfigDocumentKey, NetworkDocumentSnapshot> snapshots) {
        Objects.requireNonNull(plan, "plan");
        Map<NetworkConfigDocumentKey, NetworkDocumentSnapshot> documents = Map.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        Map<NetworkConfigDocumentKey, String> contents = new LinkedHashMap<>();
        documents.forEach((key, snapshot) -> {
            if (snapshot == null || !key.equals(snapshot.key())) {
                throw new IllegalArgumentException("Network document snapshot is invalid");
            }
            contents.put(key, snapshot.content());
        });
        return new NetworkPreparedPlan(mutations.resolveCurrentValues(plan, contents), documents);
    }

    public List<NetworkConfigDocumentKey> documents(NetworkReconciliationPlan plan) {
        return group(Objects.requireNonNull(plan, "plan").mutations()).keySet().stream()
                .sorted(Comparator.comparing(NetworkConfigDocumentKey::instanceId).thenComparing(NetworkConfigDocumentKey::path)).toList();
    }

    public List<NetworkDocumentChange> compile(NetworkPreparedPlan prepared, NetworkDefinition network) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(network, "network");
        NetworkReconciliationPlan plan = Objects.requireNonNull(prepared.plan(), "prepared.plan");
        if (!plan.networkId().equals(network.networkId()) || plan.networkRevision() != network.revision()) {
            throw new IllegalStateException("Network changed after this plan was created");
        }
        Map<NetworkConfigDocumentKey, List<NetworkConfigMutation>> grouped = group(plan.changes());
        if (!prepared.documents().keySet().containsAll(grouped.keySet())) {
            throw new IllegalArgumentException("Network plan was not prepared with every changed document");
        }
        List<PendingChange> pending = prepared.documents().entrySet().stream().map(entry -> {
            NetworkConfigDocumentKey key = entry.getKey();
            NetworkDocumentSnapshot snapshot = entry.getValue();
            if (snapshot == null || !key.equals(snapshot.key())) {
                throw new IllegalArgumentException("Network document snapshot is invalid");
            }
            List<NetworkConfigMutation> documentMutations = grouped.getOrDefault(key, List.of());
            return new PendingChange(key, snapshot, mutations.apply(snapshot.content(), documentMutations), documentMutations,
                    network.proxyInstanceId().equals(key.instanceId()));
        }).sorted(order(plan.strategy())).toList();
        List<NetworkDocumentChange> changes = new ArrayList<>(pending.size());
        for (int index = 0; index < pending.size(); index++) {
            PendingChange change = pending.get(index);
            changes.add(new NetworkDocumentChange(change.key(), index, change.original(), change.desired(), change.mutations()));
        }
        return List.copyOf(changes);
    }

    public List<NetworkJobDocument> describe(NetworkPreparedPlan prepared, NetworkDefinition network) {
        return compile(prepared, network).stream().map(NetworkDocumentChange::document).toList();
    }

    public static List<NetworkJobDocument> recover(List<NetworkJobDocument> stored, List<NetworkJobDocument> current) {
        List<NetworkJobDocument> previous = List.copyOf(Objects.requireNonNull(stored, "stored"));
        List<NetworkJobDocument> observedDocuments = List.copyOf(Objects.requireNonNull(current, "current"));
        Map<NetworkConfigDocumentKey, NetworkJobDocument> observed = new LinkedHashMap<>();
        for (NetworkJobDocument document : observedDocuments) {
            if (document == null || observed.putIfAbsent(document.key(), document) != null) {
                throw new IllegalStateException("Network plan shape changed and cannot be resumed safely");
            }
        }
        if (observed.size() != previous.size() || previous.stream().map(NetworkJobDocument::key).distinct().count() != previous.size()) {
            throw new IllegalStateException("Network plan shape changed and cannot be resumed safely");
        }
        List<NetworkJobDocument> recovered = new ArrayList<>(previous.size());
        for (NetworkJobDocument original : previous) {
            Objects.requireNonNull(original, "stored document");
            NetworkJobDocument currentDocument = observed.get(original.key());
            if (currentDocument == null || !currentDocument.desiredHash().equals(original.desiredHash())) {
                throw new IllegalStateException("Desired configuration changed for " + original.key().path());
            }
            NetworkJobDocumentState state;
            if (!original.changed()) {
                if (!matches(currentDocument, original.originalExists(), original.originalHash())) {
                    throw new IllegalStateException("Configuration drift prevents recovery of " + original.key().path());
                }
                state = NetworkJobDocumentState.UNCHANGED;
            } else if (matches(currentDocument, true, original.desiredHash())) {
                state = NetworkJobDocumentState.APPLIED;
            } else if (matches(currentDocument, original.originalExists(), original.originalHash())) {
                state = NetworkJobDocumentState.PENDING;
            } else {
                throw new IllegalStateException("Configuration drift prevents recovery of " + original.key().path());
            }
            recovered.add(new NetworkJobDocument(original.key(), original.applyOrder(), original.originalExists(), original.originalHash(),
                    original.desiredHash(), state));
        }
        return List.copyOf(recovered);
    }

    private static boolean matches(NetworkJobDocument observed, boolean exists, String hash) {
        return observed.originalExists() == exists && observed.originalHash().equals(hash);
    }

    public static String fingerprint(String content) {
        try {
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<NetworkConfigDocumentKey, List<NetworkConfigMutation>> group(List<NetworkConfigMutation> planned) {
        Map<NetworkConfigDocumentKey, List<NetworkConfigMutation>> grouped = new LinkedHashMap<>();
        for (NetworkConfigMutation mutation : planned) {
            NetworkConfigDocumentKey key = new NetworkConfigDocumentKey(mutation.instanceId(), mutation.path());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(mutation);
        }
        grouped.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(grouped);
    }

    private Comparator<PendingChange> order(NetworkPlanStrategy strategy) {
        Comparator<PendingChange> hosts = strategy == NetworkPlanStrategy.DETACH
                ? Comparator.comparing((PendingChange change) -> !change.proxy())
                : Comparator.comparing(PendingChange::proxy);
        return hosts.thenComparing(change -> change.key().instanceId())
                .thenComparingInt(change -> pathOrder(change.key().path()))
                .thenComparing(change -> change.key().path());
    }

    private int pathOrder(String path) {
        if (path.equals("forwarding.secret")) {
            return 0;
        }
        if (path.equals("velocity.toml")) {
            return 2;
        }
        return 1;
    }

    private record PendingChange(NetworkConfigDocumentKey key, NetworkDocumentSnapshot original, String desired,
                                 List<NetworkConfigMutation> mutations, boolean proxy) {
    }
}
