package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;

import java.util.List;
import java.util.Optional;

public final class ReSyncTypedCatalogConsumer {
    private ReSyncTypedCatalogConsumer() {
    }

    public static Optional<ReSyncCatalogPublicationProjection.Snapshot> active(ReSyncFlowClient client) {
        return client != null ? active(client.catalogPublicationProjection()) : Optional.empty();
    }

    public static boolean authoritative(ReSyncFlowClient client) {
        return typedAuthorityAdvertised(client);
    }

    public static boolean typedAuthorityAdvertised(ReSyncFlowClient client) {
        return client != null && (client.typedCatalogAuthorityAdvertised()
            || client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            || client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION);
    }

    public static Optional<CatalogCacheKey> authorityKey(ReSyncFlowClient client) {
        return authoritative(client)
            ? active(client).map(snapshot -> snapshot.publication().key())
            : Optional.empty();
    }

    public static Optional<ReSyncCatalogPublicationProjection.Snapshot> active(
        ReSyncCatalogPublicationProjection projection) {
        return projection != null ? projection.active() : Optional.empty();
    }

    public static List<CatalogCachePublication.Entry> entries(ReSyncFlowClient client) {
        return authoritative(client)
            ? active(client).map(snapshot -> List.copyOf(snapshot.entries().values())).orElse(List.of())
            : List.of();
    }

    public static List<CatalogCachePublication.Entry> entries(ReSyncCatalogPublicationProjection projection) {
        return active(projection).map(snapshot -> List.copyOf(snapshot.entries().values())).orElse(List.of());
    }

    public static Optional<CatalogCachePublication.Entry> find(ReSyncFlowClient client, String owner, String nodeId) {
        return authoritative(client)
            ? find(client.catalogPublicationProjection(), owner, nodeId)
            : Optional.empty();
    }

    public static Optional<CatalogCachePublication.Entry> find(ReSyncCatalogPublicationProjection projection,
                                                                String owner, String nodeId) {
        ContractRef<NodeId> key = key(owner, nodeId);
        return key != null ? active(projection).flatMap(snapshot -> snapshot.entry(key)) : Optional.empty();
    }

    public static Optional<ReSyncGenericDescriptorProjection.Projection> descriptor(
        ReSyncFlowClient client, String owner, String nodeId,
        ReSyncGenericDescriptorProjection.ClientCapabilities capabilities) {
        return find(client, owner, nodeId)
            .flatMap(entry -> ReSyncGenericDescriptorProjection.open(entry, capabilities));
    }

    public static Optional<ReSyncGenericDescriptorProjection.Projection> descriptor(
        ReSyncCatalogPublicationProjection projection, String owner, String nodeId,
        ReSyncGenericDescriptorProjection.ClientCapabilities capabilities) {
        return find(projection, owner, nodeId)
            .flatMap(entry -> ReSyncGenericDescriptorProjection.open(entry, capabilities));
    }

    public static boolean renderable(ReSyncFlowClient client, String owner, String nodeId) {
        return find(client, owner, nodeId).map(ReSyncTypedCatalogConsumer::renderable).orElse(false);
    }

    public static boolean renderable(CatalogCachePublication.Entry entry) {
        return entry != null && !entry.tombstone() && !entry.opaque()
            && entry.state() != CatalogCacheState.UNAVAILABLE;
    }

    public static boolean editable(CatalogCachePublication.Entry entry) {
        return entry != null && !entry.tombstone() && !entry.opaque()
            && entry.state() == CatalogCacheState.ACTIVE;
    }

    public static boolean editable(ReSyncFlowClient client, String owner, String nodeId) {
        return client != null && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
            && find(client, owner, nodeId).map(ReSyncTypedCatalogConsumer::editable).orElse(false);
    }

    public static boolean unavailable(CatalogCachePublication.Entry entry) {
        return entry == null || entry.tombstone() || entry.state() == CatalogCacheState.UNAVAILABLE;
    }

    private static ContractRef<NodeId> key(String owner, String nodeId) {
        if (owner == null || owner.isBlank() || nodeId == null || nodeId.isBlank()) {
            return null;
        }
        try {
            return ContractRef.of(new OwnerId(owner), new NodeId(nodeId));
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
