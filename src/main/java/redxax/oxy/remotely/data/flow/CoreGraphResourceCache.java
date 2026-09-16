package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.cache.GraphDraft;
import restudio.resync.flow.cache.GraphResourceCache;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CoreGraphResourceCache {
    private final GraphResourceCache cache;
    private Map<ProjectionKey, CoreGraphResourceProjection.Projection> sidecar = Map.of();
    private final Map<ServerResourceLocator, Long> generations = new LinkedHashMap<>();
    private long generation;
    private volatile PublishedState published;

    private record PublishedState(Map<ServerResourceLocator, GraphResourceState> authoritative,
                                  Map<ServerResourceLocator, GraphDraft> drafts,
                                  Map<ProjectionKey, CoreGraphResourceProjection.Projection> sidecar) {
        private PublishedState {
            authoritative = Objects.requireNonNull(authoritative, "authoritative");
            drafts = Objects.requireNonNull(drafts, "drafts");
            sidecar = Objects.requireNonNull(sidecar, "sidecar");
        }
    }

    public record Snapshot(Map<ServerResourceLocator, GraphResourceState> authoritative,
                           Map<ServerResourceLocator, GraphDraft> drafts,
                           Map<ProjectionKey, CoreGraphResourceProjection.Projection> sidecar) {
        public Snapshot {
            authoritative = authoritative == null ? Map.of() : immutableSnapshotMap(authoritative);
            drafts = drafts == null ? Map.of() : immutableSnapshotMap(drafts);
            sidecar = sidecar == null ? Map.of() : immutableSnapshotMap(sidecar);
        }
    }

    public record ResourceSnapshot(ServerResourceLocator resource, GraphResourceState authoritative,
                                   GraphDraft draft,
                                   Map<ProjectionKey, CoreGraphResourceProjection.Projection> sidecar,
                                   long generation) {
        public ResourceSnapshot(ServerResourceLocator resource, GraphResourceState authoritative,
                                GraphDraft draft,
                                Map<ProjectionKey, CoreGraphResourceProjection.Projection> sidecar) {
            this(resource, authoritative, draft, sidecar, 0L);
        }

        public ResourceSnapshot {
            resource = Objects.requireNonNull(resource, "resource");
            sidecar = sidecar == null ? Map.of() : immutableSnapshotMap(sidecar);
        }
    }

    public CoreGraphResourceCache() {
        this(new GraphResourceCache());
    }

    public CoreGraphResourceCache(GraphResourceCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
        GraphResourceCache.Snapshot initial = cache.snapshot();
        published = new PublishedState(initial.authoritative(), initial.drafts(), sidecar);
    }

    public synchronized Snapshot snapshot() {
        PublishedState current = published;
        return new Snapshot(current.authoritative(), current.drafts(), current.sidecar());
    }

    public synchronized ResourceSnapshot snapshot(ServerResourceLocator resource) {
        Objects.requireNonNull(resource, "resource");
        Map<ProjectionKey, CoreGraphResourceProjection.Projection> resourceSidecar = new LinkedHashMap<>();
        published.sidecar().forEach((key, projection) -> {
            if (resource.equals(key.resource())) {
                resourceSidecar.put(key, projection);
            }
        });
        return new ResourceSnapshot(resource, published.authoritative().get(resource), published.drafts().get(resource),
            resourceSidecar, currentGeneration(resource));
    }

    public synchronized boolean restoreIfCurrent(ResourceSnapshot before, ResourceSnapshot after) {
        if (before == null || after == null || !before.resource().equals(after.resource())) {
            return false;
        }
        ResourceSnapshot currentSnapshot = snapshot(before.resource());
        if (currentSnapshot.generation() != after.generation()
            || !Objects.equals(currentSnapshot.authoritative(), after.authoritative())
            || !Objects.equals(currentSnapshot.draft(), after.draft())
            || !Objects.equals(currentSnapshot.sidecar(), after.sidecar())) {
            return false;
        }
        ServerResourceLocator resource = before.resource();
        GraphResourceCache.Snapshot current = cache.snapshot();
        Map<ServerResourceLocator, GraphResourceState> authoritative = new LinkedHashMap<>(current.authoritative());
        Map<ServerResourceLocator, GraphDraft> drafts = new LinkedHashMap<>(current.drafts());
        if (before.authoritative() == null) {
            authoritative.remove(resource);
        } else {
            authoritative.put(resource, before.authoritative());
        }
        if (before.draft() == null) {
            drafts.remove(resource);
        } else {
            drafts.put(resource, before.draft());
        }
        cache.replace(authoritative, drafts);
        LinkedHashMap<ProjectionKey, CoreGraphResourceProjection.Projection> nextSidecar = new LinkedHashMap<>();
        sidecar.forEach((key, projection) -> {
            if (!resource.equals(key.resource())) {
                nextSidecar.put(key, projection);
            }
        });
        nextSidecar.putAll(before.sidecar());
        sidecar = immutableSidecar(nextSidecar);
        publish();
        touch(resource);
        return true;
    }

    public synchronized void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "Core graph cache snapshot is required");
        Set<ServerResourceLocator> resources = resourceKeys(published);
        resources.addAll(snapshot.authoritative().keySet());
        resources.addAll(snapshot.drafts().keySet());
        snapshot.sidecar().keySet().stream().map(ProjectionKey::resource).forEach(resources::add);
        cache.replace(snapshot.authoritative(), snapshot.drafts());
        sidecar = immutableSidecar(snapshot.sidecar());
        publish();
        resources.forEach(this::touch);
    }

    public synchronized boolean canReconcile(CoreGraphResourceProjection.Projection projection) {
        if (projection == null || projection.rejected() || projection.readOnly() || projection.revision() < 1L) {
            return false;
        }
        return mayReconcile(projection);
    }

    public synchronized Optional<GraphResourceCache.Reconciliation> reconcile(
        CoreGraphResourceProjection.Projection projection) {
        Objects.requireNonNull(projection, "projection");
        if (projection.rejected()) {
            return Optional.empty();
        }
        if (projection.revision() < 1L) {
            return Optional.empty();
        }
        ProjectionKey key = ProjectionKey.from(projection);
        ResourceSnapshot before = snapshot(projection.resource());
        if (projection.readOnly()) {
            retainReadOnly(projection, key);
            publish();
            touchIfChanged(before);
            return Optional.empty();
        }
        if (!mayReconcile(projection)) {
            return Optional.empty();
        }
        GraphResourceState prior = published.authoritative().get(projection.resource());
        GraphResourceState readOnlyPrior = priorReadOnlyState(projection.resource());
        if (readOnlyPrior != null && (prior == null || readOnlyPrior.revision() > prior.revision())) {
            prior = readOnlyPrior;
        }
        GraphResourceCache.Reconciliation reconciliation = cache.reconcile(toState(projection, prior));
        if (accepted(reconciliation.status())) {
            if (projection.tombstoned()) {
                removeSidecarEntries(projection.resource());
            } else {
                replaceSidecar(key, projection);
            }
        }
        publish();
        touchIfChanged(before);
        return Optional.of(reconciliation);
    }

    public synchronized List<GraphResourceCache.Reconciliation> reconcilePage(
        CoreGraphResourceProjection.PageProjection page) {
        Objects.requireNonNull(page, "page");
        if (!page.accepted()) {
            String reason = page.rejectionReason();
            throw new IllegalArgumentException(reason.isBlank() ? "The Core resource page was rejected." : reason);
        }
        Map<ServerResourceLocator, ResourceSnapshot> before = new LinkedHashMap<>();
        for (CoreGraphResourceProjection.Projection projection : page.items()) {
            before.putIfAbsent(projection.resource(), snapshot(projection.resource()));
        }
        PageBatchResult result = cache.withBatch(batch -> {
            SidecarBatch stagedSidecar = new SidecarBatch(sidecar);
            List<GraphResourceCache.Reconciliation> reconciliations = new ArrayList<>(page.items().size());
            for (CoreGraphResourceProjection.Projection projection : page.items()) {
                ProjectionKey key = ProjectionKey.from(projection);
                if (projection.readOnly()) {
                    retainReadOnly(projection, key, batch, stagedSidecar);
                    continue;
                }
                if (!mayReconcile(projection, batch, stagedSidecar)) {
                    continue;
                }
                GraphResourceState prior = batch.state(projection.resource()).orElse(null);
                GraphResourceState readOnlyPrior = priorReadOnlyState(projection.resource(), stagedSidecar);
                if (readOnlyPrior != null && (prior == null || readOnlyPrior.revision() > prior.revision())) {
                    prior = readOnlyPrior;
                }
                GraphResourceCache.Reconciliation reconciliation = batch.reconcile(toState(projection, prior));
                reconciliations.add(reconciliation);
                if (accepted(reconciliation.status())) {
                    if (projection.tombstoned()) {
                        stagedSidecar.remove(projection.resource());
                    } else {
                        stagedSidecar.replace(key, projection);
                    }
                }
            }
            return new PageBatchResult(List.copyOf(reconciliations), stagedSidecar.freeze());
        });
        sidecar = result.sidecar();
        publish();
        before.values().forEach(this::touchIfChanged);
        return result.reconciliations();
    }

    public List<GraphResourceCache.Reconciliation> reconcile(CoreGraphResourceProjection.PageProjection page) {
        return reconcilePage(page);
    }

    public synchronized Optional<GraphResourceState> state(ServerResourceLocator resource) {
        return Optional.ofNullable(published.authoritative().get(Objects.requireNonNull(resource, "resource")));
    }

    public synchronized Map<ServerResourceLocator, GraphResourceState> states() {
        return published.authoritative();
    }

    public synchronized Map<ServerResourceLocator, GraphDraft> drafts() {
        return published.drafts();
    }

    public synchronized GraphDraft putDraft(GraphDraft draft) {
        GraphDraft previous = cache.putDraft(Objects.requireNonNull(draft, "draft"));
        publish();
        touch(draft.resource());
        return previous;
    }

    public synchronized Optional<GraphDraft> draft(ServerResourceLocator resource) {
        return Optional.ofNullable(published.drafts().get(Objects.requireNonNull(resource, "resource")));
    }

    public synchronized GraphDraft removeDraft(ServerResourceLocator resource) {
        GraphDraft removed = cache.removeDraft(resource);
        if (removed != null) {
            publish();
            touch(resource);
        }
        return removed;
    }

    public synchronized Optional<CoreGraphResourceProjection.Projection> projection(ProjectionKey key) {
        return Optional.ofNullable(published.sidecar().get(Objects.requireNonNull(key, "projection key")));
    }

    public synchronized Optional<CoreGraphResourceProjection.Projection> projection(
        ServerResourceLocator resource, long revision, UUID mutationId, ContentHash protocolHash) {
        return projection(new ProjectionKey(resource, revision, mutationId, protocolHash));
    }

    public synchronized Optional<CoreGraphResourceProjection.Projection> readOnly(ServerResourceLocator resource) {
        Objects.requireNonNull(resource, "resource");
        return published.sidecar().values().stream()
            .filter(projection -> projection.readOnly() && resource.equals(projection.resource()))
            .max((left, right) -> {
                int revision = Long.compare(left.revision(), right.revision());
                if (revision != 0) {
                    return revision;
                }
                return ProjectionKey.from(left).compareTo(ProjectionKey.from(right));
            });
    }

    public synchronized Map<ProjectionKey, CoreGraphResourceProjection.Projection> sidecar() {
        return published.sidecar();
    }

    public synchronized Map<ProjectionKey, CoreGraphResourceProjection.Projection> readOnlyProjections() {
        LinkedHashMap<ProjectionKey, CoreGraphResourceProjection.Projection> result = new LinkedHashMap<>();
        published.sidecar().forEach((key, projection) -> {
            if (projection.readOnly()) {
                result.put(key, projection);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    public synchronized void clearSidecar(ServerResourceLocator resource) {
        removeSidecarEntries(Objects.requireNonNull(resource, "resource"));
        publish();
        touch(resource);
    }

    public synchronized void reconcileAuthoritativeMembership(String serverId, String resourceType,
                                                                Set<ServerResourceLocator> retained) {
        String normalizedServer = serverId == null ? "" : serverId.trim();
        String normalizedType = resourceType == null ? "" : resourceType.trim();
        Set<ServerResourceLocator> membership = retained == null ? Set.of() : Set.copyOf(retained);
        GraphResourceCache.Snapshot current = cache.snapshot();
        Map<ProjectionKey, CoreGraphResourceProjection.Projection> currentSidecar = sidecar;
        Map<ServerResourceLocator, GraphResourceState> authoritative = new LinkedHashMap<>(current.authoritative());
        authoritative.entrySet().removeIf(entry ->
            entry.getKey().serverId().canonicalText().equals(normalizedServer)
                && entry.getKey().resourceType().value().equals(normalizedType)
                && !membership.contains(entry.getKey()) && !entry.getValue().tombstone());
        if (authoritative.size() != current.authoritative().size()) {
            cache.replace(authoritative, current.drafts());
        }
        LinkedHashMap<ProjectionKey, CoreGraphResourceProjection.Projection> next = new LinkedHashMap<>();
        currentSidecar.forEach((key, projection) -> {
            boolean absentLiveMember = key.resource().serverId().canonicalText().equals(normalizedServer)
                && key.resource().resourceType().value().equals(normalizedType)
                && !membership.contains(key.resource()) && !projection.tombstoned();
            if (!absentLiveMember) {
                next.put(key, projection);
            }
        });
        sidecar = immutableSidecar(next);
        publish();
        Set<ServerResourceLocator> changed = new LinkedHashSet<>();
        current.authoritative().keySet().stream()
            .filter(resource -> resource.serverId().canonicalText().equals(normalizedServer)
                && resource.resourceType().value().equals(normalizedType))
            .forEach(changed::add);
        current.drafts().keySet().stream()
            .filter(resource -> resource.serverId().canonicalText().equals(normalizedServer)
                && resource.resourceType().value().equals(normalizedType))
            .forEach(changed::add);
        membership.stream()
            .filter(resource -> resource.serverId().canonicalText().equals(normalizedServer)
                && resource.resourceType().value().equals(normalizedType))
            .forEach(changed::add);
        currentSidecar.keySet().stream()
            .filter(key -> key.resource().serverId().canonicalText().equals(normalizedServer)
                && key.resource().resourceType().value().equals(normalizedType))
            .map(ProjectionKey::resource).forEach(changed::add);
        next.keySet().stream()
            .filter(key -> key.resource().serverId().canonicalText().equals(normalizedServer)
                && key.resource().resourceType().value().equals(normalizedType))
            .map(ProjectionKey::resource).forEach(changed::add);
        changed.forEach(this::touch);
    }

    public synchronized void clearServer(String serverId) {
        String normalized = serverId == null ? "" : serverId.trim();
        Set<ServerResourceLocator> resources = resourceKeys(published);
        GraphResourceCache.Snapshot current = cache.snapshot();
        Map<ServerResourceLocator, GraphResourceState> authoritative = new LinkedHashMap<>(current.authoritative());
        authoritative.entrySet().removeIf(entry -> entry.getKey().serverId().canonicalText().equals(normalized));
        Map<ServerResourceLocator, GraphDraft> drafts = new LinkedHashMap<>(current.drafts());
        drafts.keySet().removeIf(resource -> resource.serverId().canonicalText().equals(normalized));
        if (authoritative.size() != current.authoritative().size() || drafts.size() != current.drafts().size()) {
            cache.replace(authoritative, drafts);
        }
        LinkedHashMap<ProjectionKey, CoreGraphResourceProjection.Projection> next = new LinkedHashMap<>();
        sidecar.forEach((key, projection) -> {
            if (!key.resource().serverId().canonicalText().equals(normalized)) {
                next.put(key, projection);
            }
        });
        sidecar = immutableSidecar(next);
        publish();
        resources.removeIf(resource -> !resource.serverId().canonicalText().equals(normalized));
        resources.forEach(this::touch);
    }

    private boolean mayReconcile(CoreGraphResourceProjection.Projection projection) {
        CoreGraphResourceProjection.Projection current = latest(projection.resource()).orElse(null);
        if (current != null) {
            if (projection.revision() < current.revision()) {
                return false;
            }
        }
        GraphResourceState authoritative = published.authoritative().get(projection.resource());
        return authoritative == null || projection.revision() >= authoritative.revision();
    }

    private boolean mayReconcile(CoreGraphResourceProjection.Projection projection,
                                 GraphResourceCache.Batch batch, SidecarBatch stagedSidecar) {
        CoreGraphResourceProjection.Projection current = stagedSidecar.latest(projection.resource());
        if (current != null) {
            if (projection.revision() < current.revision()) {
                return false;
            }
        }
        GraphResourceState authoritative = batch.state(projection.resource()).orElse(null);
        return authoritative == null || projection.revision() >= authoritative.revision();
    }

    private void retainReadOnly(CoreGraphResourceProjection.Projection projection, ProjectionKey key) {
        GraphResourceState authoritative = published.authoritative().get(projection.resource());
        if (authoritative != null && projection.revision() <= authoritative.revision()) {
            return;
        }
        CoreGraphResourceProjection.Projection current = latest(projection.resource()).orElse(null);
        if (current != null) {
            ProjectionKey currentKey = ProjectionKey.from(current);
            if (projection.revision() < current.revision()
                || projection.revision() == current.revision() && !key.equals(currentKey)) {
                return;
            }
        }
        replaceSidecar(key, projection);
    }

    private void retainReadOnly(CoreGraphResourceProjection.Projection projection, ProjectionKey key,
                                GraphResourceCache.Batch batch, SidecarBatch stagedSidecar) {
        GraphResourceState authoritative = batch.state(projection.resource()).orElse(null);
        if (authoritative != null && projection.revision() <= authoritative.revision()) {
            return;
        }
        CoreGraphResourceProjection.Projection current = stagedSidecar.latest(projection.resource());
        if (current != null) {
            ProjectionKey currentKey = ProjectionKey.from(current);
            if (projection.revision() < current.revision()
                || projection.revision() == current.revision() && !key.equals(currentKey)) {
                return;
            }
        }
        stagedSidecar.replace(key, projection);
    }

    private Optional<CoreGraphResourceProjection.Projection> latest(ServerResourceLocator resource) {
        return sidecar.entrySet().stream()
            .filter(entry -> resource.equals(entry.getKey().resource()))
            .max((left, right) -> {
                int revision = Long.compare(left.getKey().revision(), right.getKey().revision());
                if (revision != 0) {
                    return revision;
                }
                return left.getKey().compareTo(right.getKey());
            })
            .map(Map.Entry::getValue);
    }

    private void replaceSidecar(ProjectionKey key, CoreGraphResourceProjection.Projection projection) {
        LinkedHashMap<ProjectionKey, CoreGraphResourceProjection.Projection> next = new LinkedHashMap<>();
        sidecar.forEach((existingKey, existingProjection) -> {
            if (!existingKey.resource().equals(key.resource())) {
                next.put(existingKey, existingProjection);
            }
        });
        next.put(key, projection);
        sidecar = immutableSidecar(next);
    }

    private void removeSidecarEntries(ServerResourceLocator resource) {
        LinkedHashMap<ProjectionKey, CoreGraphResourceProjection.Projection> next = new LinkedHashMap<>();
        sidecar.forEach((key, projection) -> {
            if (!resource.equals(key.resource())) {
                next.put(key, projection);
            }
        });
        sidecar = immutableSidecar(next);
    }

    private static boolean accepted(GraphResourceCache.Status status) {
        return status == GraphResourceCache.Status.NEW || status == GraphResourceCache.Status.NEWER
            || status == GraphResourceCache.Status.ACKNOWLEDGED || status == GraphResourceCache.Status.DUPLICATE;
    }

    private GraphResourceState toState(CoreGraphResourceProjection.Projection projection, GraphResourceState prior) {
        ServerResourceLocator resource = Objects.requireNonNull(projection.resource(), "projection resource");
        Map<String, Object> unknown = unknown(projection);
        if (projection.tombstoned()) {
            GraphResourceState previous = prior != null ? prior : priorReadOnlyState(resource);
            return GraphResourceState.tombstone(resource, projection.revision(),
                Objects.requireNonNull(projection.mutationId(), "tombstone mutation"),
                Objects.requireNonNull(projection.payloadHash(), "tombstone payload hash"),
                previous != null ? previous.assetHash() : null,
                previous != null ? previous.innerChecksum() : null,
                previous != null ? previous.catalogBinding() : null, unknown);
        }
        if (projection.hasGraphDocument()) {
            return GraphResourceState.live(resource, projection.revision(),
                Objects.requireNonNull(projection.mutationId(), "resource mutation"),
                Objects.requireNonNull(projection.payloadHash(), "resource payload hash"),
                Objects.requireNonNull(projection.assetHash(), "asset hash"), projection.graphDocument(),
                Objects.requireNonNull(projection.activationState(), "resource activation state"), unknown);
        }
        return GraphResourceState.live(resource, projection.revision(),
            Objects.requireNonNull(projection.mutationId(), "resource mutation"),
            Objects.requireNonNull(projection.payloadHash(), "resource payload hash"),
            Objects.requireNonNull(projection.assetHash(), "asset hash"),
            Objects.requireNonNull(projection.functionSourceDocument(), "Core payload"),
            Objects.requireNonNull(projection.activationState(), "resource activation state"), unknown);
    }

    private GraphResourceState priorReadOnlyState(ServerResourceLocator resource) {
        return readOnly(resource)
            .filter(projection -> projection.hasGraphDocument() || projection.hasFunctionSourceDocument())
            .map(projection -> {
                ContentHash protocolHash = Objects.requireNonNull(projection.payloadHash(), "projection payload hash");
                ContentHash assetHash = projection.assetHash();
                if (projection.hasGraphDocument()) {
                    return GraphResourceState.live(resource, projection.revision(),
                        Objects.requireNonNull(projection.mutationId(), "projection mutation"), protocolHash, assetHash,
                        projection.graphDocument(), Objects.requireNonNull(projection.activationState(), "activation state"));
                }
                return GraphResourceState.live(resource, projection.revision(),
                    Objects.requireNonNull(projection.mutationId(), "projection mutation"), protocolHash, assetHash,
                    projection.functionSourceDocument(), Objects.requireNonNull(projection.activationState(), "activation state"));
            })
            .orElse(null);
    }

    private GraphResourceState priorReadOnlyState(ServerResourceLocator resource, SidecarBatch stagedSidecar) {
        CoreGraphResourceProjection.Projection projection = stagedSidecar.readOnly(resource);
        if (projection == null || (!projection.hasGraphDocument() && !projection.hasFunctionSourceDocument())) {
            return null;
        }
        ContentHash protocolHash = Objects.requireNonNull(projection.payloadHash(), "projection payload hash");
        ContentHash assetHash = projection.assetHash();
        if (projection.hasGraphDocument()) {
            return GraphResourceState.live(resource, projection.revision(),
                Objects.requireNonNull(projection.mutationId(), "projection mutation"), protocolHash, assetHash,
                projection.graphDocument(), Objects.requireNonNull(projection.activationState(), "activation state"));
        }
        return GraphResourceState.live(resource, projection.revision(),
            Objects.requireNonNull(projection.mutationId(), "projection mutation"), protocolHash, assetHash,
            projection.functionSourceDocument(), Objects.requireNonNull(projection.activationState(), "activation state"));
    }

    private void publish() {
        GraphResourceCache.Snapshot cacheSnapshot = cache.snapshot();
        published = new PublishedState(cacheSnapshot.authoritative(), cacheSnapshot.drafts(), sidecar);
    }

    private long currentGeneration(ServerResourceLocator resource) {
        return generations.getOrDefault(resource, 0L);
    }

    private void touch(ServerResourceLocator resource) {
        long next = generation == Long.MAX_VALUE ? 1L : generation + 1L;
        generation = next;
        generations.put(resource, next);
    }

    private void touchIfChanged(ResourceSnapshot before) {
        ResourceSnapshot current = snapshot(before.resource());
        if (!Objects.equals(before.authoritative(), current.authoritative())
            || !Objects.equals(before.draft(), current.draft())
            || !Objects.equals(before.sidecar(), current.sidecar())) {
            touch(before.resource());
        }
    }

    private static Set<ServerResourceLocator> resourceKeys(PublishedState state) {
        LinkedHashSet<ServerResourceLocator> resources = new LinkedHashSet<>();
        resources.addAll(state.authoritative().keySet());
        resources.addAll(state.drafts().keySet());
        state.sidecar().keySet().stream().map(ProjectionKey::resource).forEach(resources::add);
        return resources;
    }

    private static Map<String, Object> unknown(CoreGraphResourceProjection.Projection projection) {
        LinkedHashMap<String, Object> unknown = new LinkedHashMap<>();
        if (!projection.envelopeProjection().unknownEnvelopeFields().isEmpty()) {
            unknown.put("envelope", projection.envelopeProjection().unknownEnvelopeFields());
        }
        if (!projection.envelopeProjection().unknownBodyFields().isEmpty()) {
            unknown.put("body", projection.envelopeProjection().unknownBodyFields());
        }
        return unknown;
    }

    private record PageBatchResult(List<GraphResourceCache.Reconciliation> reconciliations,
                                   Map<ProjectionKey, CoreGraphResourceProjection.Projection> sidecar) {
    }

    private static final class SidecarBatch {
        private final LinkedHashMap<ProjectionKey, CoreGraphResourceProjection.Projection> values;
        private final LinkedHashMap<ServerResourceLocator, LinkedHashSet<ProjectionKey>> keysByResource = new LinkedHashMap<>();
        private final LinkedHashMap<ServerResourceLocator, CoreGraphResourceProjection.Projection> latest = new LinkedHashMap<>();
        private final LinkedHashMap<ServerResourceLocator, CoreGraphResourceProjection.Projection> readOnly = new LinkedHashMap<>();

        private SidecarBatch(Map<ProjectionKey, CoreGraphResourceProjection.Projection> initial) {
            values = new LinkedHashMap<>(initial);
            initial.forEach((key, projection) -> {
                keysByResource.computeIfAbsent(key.resource(), ignored -> new LinkedHashSet<>()).add(key);
                latest.compute(key.resource(), (resource, current) -> later(key, current, projection));
                if (projection.readOnly()) {
                    readOnly.compute(key.resource(), (resource, current) -> laterReadOnly(key, current, projection));
                }
            });
        }

        private CoreGraphResourceProjection.Projection latest(ServerResourceLocator resource) {
            return latest.get(resource);
        }

        private CoreGraphResourceProjection.Projection readOnly(ServerResourceLocator resource) {
            return readOnly.get(resource);
        }

        private void replace(ProjectionKey key, CoreGraphResourceProjection.Projection projection) {
            remove(key.resource());
            values.put(key, projection);
            keysByResource.computeIfAbsent(key.resource(), ignored -> new LinkedHashSet<>()).add(key);
            latest.put(key.resource(), projection);
            if (projection.readOnly()) {
                readOnly.put(key.resource(), projection);
            }
        }

        private void remove(ServerResourceLocator resource) {
            LinkedHashSet<ProjectionKey> keys = keysByResource.remove(resource);
            if (keys != null) {
                keys.forEach(values::remove);
            }
            latest.remove(resource);
            readOnly.remove(resource);
        }

        private Map<ProjectionKey, CoreGraphResourceProjection.Projection> freeze() {
            return immutableSidecar(values);
        }

        private static CoreGraphResourceProjection.Projection later(
            ProjectionKey key, CoreGraphResourceProjection.Projection current,
            CoreGraphResourceProjection.Projection candidate) {
            return current == null || key.compareTo(ProjectionKey.from(current)) > 0 ? candidate : current;
        }

        private static CoreGraphResourceProjection.Projection laterReadOnly(
            ProjectionKey key, CoreGraphResourceProjection.Projection current,
            CoreGraphResourceProjection.Projection candidate) {
            if (current == null) {
                return candidate;
            }
            ProjectionKey currentKey = ProjectionKey.from(current);
            return key.revision() > currentKey.revision()
                || key.revision() == currentKey.revision() && key.compareTo(currentKey) > 0 ? candidate : current;
        }
    }

    private static Map<ProjectionKey, CoreGraphResourceProjection.Projection> immutableSidecar(
        Map<ProjectionKey, CoreGraphResourceProjection.Projection> values) {
        LinkedHashMap<ProjectionKey, CoreGraphResourceProjection.Projection> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> immutableSnapshotMap(Map<K, V> values) {
        Objects.requireNonNull(values, "snapshot map");
        LinkedHashMap<K, V> entries = new LinkedHashMap<>();
        values.forEach((key, value) -> entries.put(Objects.requireNonNull(key, "snapshot key"),
            Objects.requireNonNull(value, "snapshot value")));
        LinkedHashMap<K, V> sorted = new LinkedHashMap<>();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    public record ProjectionKey(ServerResourceLocator resource, long revision, UUID mutationId,
                                ContentHash protocolHash) implements Comparable<ProjectionKey> {
        public ProjectionKey {
            resource = Objects.requireNonNull(resource, "resource");
            if (revision < 1L) {
                throw new IllegalArgumentException("Projection revision must be positive");
            }
            mutationId = Objects.requireNonNull(mutationId, "mutationId");
            protocolHash = Objects.requireNonNull(protocolHash, "protocolHash");
        }

        public static ProjectionKey from(CoreGraphResourceProjection.Projection projection) {
            Objects.requireNonNull(projection, "projection");
            return new ProjectionKey(Objects.requireNonNull(projection.resource(), "projection resource"),
                projection.revision(), Objects.requireNonNull(projection.mutationId(), "projection mutation"),
                Objects.requireNonNull(projection.payloadHash(), "projection payload hash"));
        }

        @Override
        public int compareTo(ProjectionKey other) {
            int resourceOrder = resource.compareTo(other.resource);
            if (resourceOrder != 0) {
                return resourceOrder;
            }
            int revisionOrder = Long.compare(revision, other.revision);
            if (revisionOrder != 0) {
                return revisionOrder;
            }
            int mutationOrder = mutationId.toString().compareTo(other.mutationId.toString());
            if (mutationOrder != 0) {
                return mutationOrder;
            }
            return protocolHash.canonicalText().compareTo(other.protocolHash.canonicalText());
        }
    }
}
