package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.JsonObject;
import restudio.resync.flow.cache.GraphDraft;
import restudio.resync.flow.cache.GraphResourceCache;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;

public final class ReSyncResourceRevisionReconciler {
    public enum Outcome {
        APPLIED,
        DUPLICATE,
        STALE,
        STALE_EPOCH,
        CONFLICT,
        INVALID
    }

    public record ResourceResult(String serverId, String typeId, String resourceId, long revision, String mutationId,
                                 String payloadHash, boolean deleted, JsonObject payload, long authorityEpoch) {
        public ResourceResult {
            serverId = normalize(serverId);
            typeId = normalize(typeId);
            resourceId = normalize(resourceId);
            mutationId = normalize(mutationId);
            payloadHash = normalize(payloadHash);
            if (authorityEpoch < 1L) {
                throw new IllegalArgumentException("Authority epoch must be positive");
            }
            if (payload != null) {
                payload = payload.deepCopy();
            }
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record Stamp(long revision, String mutationId, String payloadHash, long authorityEpoch, boolean deleted) {
        public static Stamp from(ResourceResult result) {
            return result == null ? null : new Stamp(result.revision(), result.mutationId(), result.payloadHash(),
                result.authorityEpoch(), result.deleted());
        }
    }

    public enum EpochOutcome {
        CURRENT,
        ADVANCED,
        STALE,
        INVALID
    }

    public record EpochDecision(EpochOutcome outcome, long previousEpoch, long authorityEpoch) {
        public boolean accepted() {
            return outcome == EpochOutcome.CURRENT || outcome == EpochOutcome.ADVANCED;
        }

        public boolean advanced() {
            return outcome == EpochOutcome.ADVANCED;
        }

        public boolean stale() {
            return outcome == EpochOutcome.STALE;
        }
    }

    public record Decision(Outcome outcome, ResourceResult current) {
        public boolean accepted() {
            return outcome == Outcome.APPLIED;
        }

        public boolean ignored() {
            return outcome == Outcome.DUPLICATE || outcome == Outcome.STALE || outcome == Outcome.STALE_EPOCH;
        }
    }

    public record Admission(Outcome outcome, ResourceResult incoming, ResourceResult current,
                            long previousEpoch, boolean advancesEpoch) {
        public Admission {
            incoming = copy(incoming);
            current = copy(current);
        }

        public boolean accepted() {
            return outcome == Outcome.APPLIED || outcome == Outcome.DUPLICATE;
        }

        public boolean repair() {
            return outcome == Outcome.DUPLICATE;
        }
    }

    private record ResourceKey(String serverId, String typeId, String resourceId) {
        private ResourceKey {
            serverId = serverId == null ? "" : serverId.trim();
            typeId = typeId == null ? "" : typeId.trim();
            resourceId = resourceId == null ? "" : resourceId.trim();
        }
    }

    private final Map<ResourceKey, ResourceResult> accepted = BrowserSafeState.map();
    private final Map<String, Long> authorityEpochs = BrowserSafeState.map();
    private final CoreGraphResourceCache graphResources = new CoreGraphResourceCache();

    public record Snapshot(Map<String, ResourceResult> accepted, Map<String, Long> authorityEpochs,
                           CoreGraphResourceCache.Snapshot graphResources) {
        public Snapshot {
            accepted = accepted == null ? Map.of() : Map.copyOf(accepted);
            authorityEpochs = authorityEpochs == null ? Map.of() : Map.copyOf(authorityEpochs);
            graphResources = Objects.requireNonNull(graphResources, "Graph resource snapshot is required");
        }
    }

    public synchronized Snapshot snapshot() {
        Map<String, ResourceResult> acceptedCopy = new LinkedHashMap<>();
        accepted.forEach((key, value) -> acceptedCopy.put(key.serverId() + "\u0000" + key.typeId() + "\u0000" + key.resourceId(), copy(value)));
        return new Snapshot(acceptedCopy, new LinkedHashMap<>(authorityEpochs), graphResources.snapshot());
    }

    public synchronized void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "Resource reconciliation snapshot is required");
        accepted.clear();
        snapshot.accepted().values().forEach(value -> accepted.put(
            new ResourceKey(value.serverId(), value.typeId(), value.resourceId()), copy(value)));
        authorityEpochs.clear();
        authorityEpochs.putAll(snapshot.authorityEpochs());
        graphResources.restore(snapshot.graphResources());
    }

    public synchronized Optional<GraphResourceCache.Reconciliation> reconcile(
        CoreGraphResourceProjection.Projection incoming) {
        if (incoming == null || incoming.resource() == null) {
            return Optional.empty();
        }
        EpochDecision epoch = observeAuthorityEpoch(incoming.resource().serverId().canonicalText(), incoming.envelope().authorityEpoch());
        if (!epoch.accepted()) {
            return Optional.empty();
        }
        return graphResources.reconcile(incoming);
    }

    public Optional<GraphResourceState> graphState(ServerResourceLocator resource) {
        return graphResources.state(resource);
    }

    public Optional<CoreGraphResourceProjection.Projection> graphReadOnly(ServerResourceLocator resource) {
        return graphResources.readOnly(resource);
    }

    public GraphDraft putGraphDraft(GraphDraft draft) {
        return graphResources.putDraft(draft);
    }

    public Optional<GraphDraft> graphDraft(ServerResourceLocator resource) {
        return graphResources.draft(resource);
    }

    public Map<ServerResourceLocator, GraphResourceState> graphStates() {
        return graphResources.states();
    }

    public Map<ServerResourceLocator, GraphDraft> graphDrafts() {
        return graphResources.drafts();
    }

    public CoreGraphResourceCache graphResources() {
        return graphResources;
    }

    public synchronized EpochDecision observeAuthorityEpoch(String serverId, long authorityEpoch) {
        String normalized = serverId == null ? "" : serverId.trim();
        if (normalized.isBlank() || authorityEpoch < 1L) {
            return new EpochDecision(EpochOutcome.INVALID, 0L, authorityEpoch);
        }
        long current = authorityEpochs.getOrDefault(normalized, 0L);
        if (authorityEpoch < current) {
            return new EpochDecision(EpochOutcome.STALE, current, authorityEpoch);
        }
        if (authorityEpoch == current) {
            return new EpochDecision(EpochOutcome.CURRENT, current, authorityEpoch);
        }
        resetForEpoch(normalized, authorityEpoch);
        return new EpochDecision(EpochOutcome.ADVANCED, current, authorityEpoch);
    }

    public long authorityEpoch(String serverId) {
        String normalized = serverId == null ? "" : serverId.trim();
        return authorityEpochs.getOrDefault(normalized, 0L);
    }

    public synchronized Decision apply(ResourceResult incoming) {
        Admission admission = prepare(incoming);
        if (!admission.accepted()) {
            return new Decision(admission.outcome(), admission.current());
        }
        return commit(admission);
    }

    public synchronized Admission prepare(ResourceResult incoming) {
        if (!valid(incoming)) {
            return new Admission(Outcome.INVALID, incoming, null, 0L, false);
        }
        String server = incoming.serverId();
        long currentEpoch = authorityEpochs.getOrDefault(server, 0L);
        ResourceKey key = new ResourceKey(server, incoming.typeId(), incoming.resourceId());
        ResourceResult current = accepted.get(key);
        if (incoming.authorityEpoch() < currentEpoch) {
            return new Admission(Outcome.STALE_EPOCH, incoming, current, currentEpoch, false);
        }
        boolean advancesEpoch = incoming.authorityEpoch() > currentEpoch;
        if (advancesEpoch) {
            return new Admission(Outcome.APPLIED, incoming, null, currentEpoch, true);
        }
        if (current == null) {
            return new Admission(Outcome.APPLIED, incoming, null, currentEpoch, false);
        }
        if (incoming.revision() < current.revision()) {
            return new Admission(Outcome.STALE, incoming, current, currentEpoch, false);
        }
        if (incoming.revision() == current.revision()) {
            return new Admission(sameResult(current, incoming) ? Outcome.DUPLICATE : Outcome.CONFLICT,
                incoming, current, currentEpoch, false);
        }
        return new Admission(Outcome.APPLIED, incoming, current, currentEpoch, false);
    }

    public synchronized Decision commit(Admission admission) {
        if (admission == null) {
            return new Decision(Outcome.INVALID, null);
        }
        if (!admission.accepted()) {
            return new Decision(admission.outcome(), admission.current());
        }
        ResourceResult incoming = admission.incoming();
        if (!valid(incoming)) {
            return new Decision(Outcome.INVALID, null);
        }
        long currentEpoch = authorityEpochs.getOrDefault(incoming.serverId(), 0L);
        ResourceKey key = new ResourceKey(incoming.serverId(), incoming.typeId(), incoming.resourceId());
        ResourceResult current = accepted.get(key);
        if (currentEpoch != admission.previousEpoch()
            || (admission.advancesEpoch() ? currentEpoch != admission.previousEpoch() : !sameNullable(current, admission.current()))) {
            return new Decision(Outcome.CONFLICT, copy(current));
        }
        if (admission.advancesEpoch()) {
            resetForEpoch(incoming.serverId(), incoming.authorityEpoch());
        }
        if (admission.outcome() == Outcome.APPLIED) {
            accepted.put(key, copy(incoming));
            return new Decision(Outcome.APPLIED, incoming);
        }
        return new Decision(Outcome.DUPLICATE, copy(current));
    }

    public synchronized boolean rollback(Admission admission) {
        if (admission == null || !admission.accepted() || admission.advancesEpoch()) {
            return false;
        }
        ResourceResult incoming = admission.incoming();
        if (!valid(incoming)) {
            return false;
        }
        ResourceKey key = new ResourceKey(incoming.serverId(), incoming.typeId(), incoming.resourceId());
        ResourceResult current = accepted.get(key);
        if (!sameNullable(current, incoming)) {
            return false;
        }
        if (admission.current() == null) {
            accepted.remove(key);
        } else {
            accepted.put(key, copy(admission.current()));
        }
        return true;
    }

    public ResourceResult get(String serverId, String typeId, String resourceId) {
        return copy(accepted.get(new ResourceKey(serverId, typeId, resourceId)));
    }

    public Stamp stamp(String serverId, String typeId, String resourceId) {
        return Stamp.from(accepted.get(new ResourceKey(serverId, typeId, resourceId)));
    }

    public long revision(String serverId, String typeId, String resourceId) {
        ResourceResult result = get(serverId, typeId, resourceId);
        return result == null ? 0L : result.revision();
    }

    public synchronized void clearServer(String serverId) {
        String normalized = serverId == null ? "" : serverId.trim();
        accepted.keySet().removeIf(key -> key.serverId().equals(normalized));
        graphResources.clearServer(normalized);
    }

    private static boolean valid(ResourceResult result) {
        return result != null && !result.serverId().isBlank() && !result.typeId().isBlank() && !result.resourceId().isBlank()
            && result.revision() > 0L && !result.mutationId().isBlank() && !result.payloadHash().isBlank()
            && result.authorityEpoch() >= 1L && (result.deleted() || result.payload() != null);
    }

    private static boolean sameResult(ResourceResult current, ResourceResult incoming) {
        return Objects.equals(current.mutationId(), incoming.mutationId())
            && Objects.equals(current.payloadHash(), incoming.payloadHash())
            && current.deleted() == incoming.deleted()
            && current.authorityEpoch() == incoming.authorityEpoch()
            && Objects.equals(current.payload(), incoming.payload());
    }

    private static boolean sameNullable(ResourceResult left, ResourceResult right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.revision() == right.revision() && sameResult(left, right);
    }

    private static ResourceResult copy(ResourceResult result) {
        if (result == null) {
            return null;
        }
        return new ResourceResult(result.serverId(), result.typeId(), result.resourceId(), result.revision(), result.mutationId(),
            result.payloadHash(), result.deleted(), result.payload(), result.authorityEpoch());
    }

    private void resetForEpoch(String serverId, long authorityEpoch) {
        accepted.keySet().removeIf(key -> key.serverId().equals(serverId));
        Map<ServerResourceLocator, GraphDraft> drafts = new LinkedHashMap<>();
        graphResources.drafts().forEach((resource, draft) -> {
            if (resource.serverId().canonicalText().equals(serverId)) {
                drafts.put(resource, draft);
            }
        });
        graphResources.clearServer(serverId);
        drafts.values().forEach(graphResources::putDraft);
        authorityEpochs.put(serverId, authorityEpoch);
    }
}
