package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.flow.data.FlowDataType;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReSyncValueTypeCatalog {
    private ReSyncValueTypeCatalog() {
    }

    public static Snapshot active(ReSyncFlowClient client) {
        if (client == null || client.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
            return null;
        }
        ReSyncCatalogPublicationProjection.Snapshot nodes = client.catalogPublicationProjection().active().orElse(null);
        ReSyncCatalogAuthoringProjection.Snapshot authoring = client.catalogAuthoringProjection().active().orElse(null);
        if (!aligned(client, nodes, authoring)) {
            return null;
        }
        CatalogAuthoringPublication publication = authoring.publication();
        CatalogAuthoringPublication.SectionProjection section = publication.section(
            CatalogAuthoringPublication.Section.TYPES);
        if (!publication.compatible() || section == null || !section.present() || !section.acknowledged()
            || !section.selectable() || section.entries().isEmpty()) {
            return null;
        }
        List<FlowDataType> dataTypes;
        try {
            dataTypes = project(section.entries());
        } catch (RuntimeException exception) {
            return null;
        }
        if (dataTypes.isEmpty()) {
            return null;
        }
        Snapshot snapshot = new Snapshot(client, client.activeTransportGeneration(), authoring.key(),
            authoring.revision(), authoring.checksum(), dataTypes);
        return current(client, snapshot) ? snapshot : null;
    }

    public static boolean current(ReSyncFlowClient client, Snapshot snapshot) {
        if (client == null || snapshot == null || snapshot.source() != client
            || snapshot.connectionGeneration() != client.activeTransportGeneration()
            || client.catalogAuthority() != ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
            return false;
        }
        ReSyncCatalogPublicationProjection.Snapshot nodes = client.catalogPublicationProjection().active().orElse(null);
        ReSyncCatalogAuthoringProjection.Snapshot authoring = client.catalogAuthoringProjection().active().orElse(null);
        return aligned(client, nodes, authoring)
            && snapshot.key().equals(authoring.key())
            && snapshot.revision() == authoring.revision()
            && snapshot.authoringChecksum().equals(authoring.checksum());
    }

    private static boolean aligned(ReSyncFlowClient client, ReSyncCatalogPublicationProjection.Snapshot nodes,
                                   ReSyncCatalogAuthoringProjection.Snapshot authoring) {
        return nodes != null && authoring != null
            && nodes.publication().key().equals(authoring.key())
            && nodes.publication().revision() == authoring.revision()
            && nodes.publication().catalogBinding().equals(authoring.publication().binding())
            && client.activeCatalogAuthoringPublication().orElse(null) == authoring.publication();
    }

    private static List<FlowDataType> project(List<CatalogAuthoringPublication.Entry> entries) {
        List<FlowDataType> result = new ArrayList<>(entries.size());
        Set<String> ids = new HashSet<>();
        for (CatalogAuthoringPublication.Entry entry : entries) {
            if (entry == null || entry.section() != CatalogAuthoringPublication.Section.TYPES) {
                throw new IllegalArgumentException("Value type entry identity is invalid");
            }
            if (!entry.selectable() || entry.opaque()) {
                continue;
            }
            Map<String, Object> descriptor = object(CanonicalJson.parse(entry.canonicalData()));
            if (!"type".equals(descriptor.get("kind"))) {
                throw new IllegalArgumentException("Value type descriptor kind is invalid");
            }
            Map<String, Object> identity = object(descriptor.get("id"));
            String owner = text(identity.get("ownerId"));
            String localId = text(identity.get("localId"));
            String displayName = text(descriptor.get("displayName"));
            if (!owner.equals(entry.reference().owner().canonicalText())
                || !localId.equals(entry.reference().id().canonicalText())) {
                throw new IllegalArgumentException("Value type descriptor identity does not match its entry");
            }
            String id = "builtin".equals(owner) ? localId : owner + ":" + localId;
            String normalized = id.toLowerCase(Locale.ROOT);
            if (!ids.add(normalized)) {
                throw new IllegalArgumentException("Value type ID is duplicated");
            }
            FlowDataType type = "builtin".equals(owner) ? FlowDataType.fromString(localId)
                : FlowDataType.serverType(id, displayName, 0x808080, null, true, owner);
            if (!type.isResolved()) {
                type = FlowDataType.serverType(id, displayName, 0x808080, null, true, owner);
            }
            if (!"execution".equals(type.getId())) {
                result.add(type);
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Value type descriptor is not an object");
        }
        for (Object key : raw.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException("Value type descriptor key is invalid");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Value type descriptor text is invalid");
        }
        return text;
    }

    public record Snapshot(ReSyncFlowClient source, int connectionGeneration, CatalogCacheKey key, long revision,
                           ContentHash authoringChecksum, List<FlowDataType> dataTypes) {
        public Snapshot {
            source = Objects.requireNonNull(source, "Value type catalog source is required");
            key = Objects.requireNonNull(key, "Value type catalog key is required");
            if (revision < 0L) {
                throw new IllegalArgumentException("Value type catalog revision cannot be negative");
            }
            authoringChecksum = Objects.requireNonNull(authoringChecksum,
                "Value type catalog authoring checksum is required");
            dataTypes = List.copyOf(Objects.requireNonNull(dataTypes, "Value type catalog entries are required"));
            if (dataTypes.isEmpty()) {
                throw new IllegalArgumentException("Value type catalog entries cannot be empty");
            }
        }
    }
}
