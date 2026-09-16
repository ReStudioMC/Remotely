package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.nio.charset.StandardCharsets;

public final class ReSyncOpaqueCatalogInspector {
    private ReSyncOpaqueCatalogInspector() {
    }

    public static List<Inspection> inspect(ReSyncFlowClient client,
                                           Predicate<CatalogCachePublication.Entry> supportedDescriptor) {
        return inspect(ReSyncTypedCatalogConsumer.entries(client), supportedDescriptor);
    }

    public static List<Inspection> inspect(Collection<CatalogCachePublication.Entry> entries,
                                           Predicate<CatalogCachePublication.Entry> supportedDescriptor) {
        Objects.requireNonNull(entries, "Catalog entries are required");
        Predicate<CatalogCachePublication.Entry> support = supportedDescriptor != null
            ? supportedDescriptor : entry -> false;
        return entries.stream()
            .map(entry -> inspect(entry, support))
            .flatMap(Optional::stream)
            .toList();
    }

    public static Optional<Inspection> inspect(CatalogCachePublication.Entry entry,
                                               Predicate<CatalogCachePublication.Entry> supportedDescriptor) {
        if (entry == null) {
            return Optional.empty();
        }
        Status status;
        String reason;
        if (entry.tombstone()) {
            status = Status.TOMBSTONED;
            reason = "Removed by the server; editing is unavailable.";
        } else if (entry.opaque()) {
            status = Status.OPAQUE;
            reason = "This catalog entry is preserved but its descriptor is unsupported by this client.";
        } else if (entry.state() == CatalogCacheState.UNAVAILABLE) {
            status = Status.UNAVAILABLE;
            reason = "Required catalog capabilities are unavailable; editing is unavailable.";
        } else if (entry.state() == CatalogCacheState.READ_ONLY) {
            status = Status.READ_ONLY;
            reason = "This catalog entry is read-only; editing is unavailable.";
        } else {
            boolean supported;
            try {
                supported = supportedDescriptor != null && supportedDescriptor.test(entry);
            } catch (RuntimeException exception) {
                supported = false;
            }
            if (supported) {
                return Optional.empty();
            }
            status = Status.UNSUPPORTED;
            reason = "This client has no supported descriptor-to-widget mapping; editing is unavailable.";
        }
        return Optional.of(new Inspection(entry.definitionKey(), entry.revision(), status, reason,
            entry.data() != null ? Optional.of(entry.data().canonicalText()) : Optional.empty(),
            entry.requiredCapabilities(), entry.unknown()));
    }

    public enum Status {
        OPAQUE,
        UNAVAILABLE,
        TOMBSTONED,
        READ_ONLY,
        UNSUPPORTED
    }

    public record Inspection(ContractRef<NodeId> definitionKey,
                             long revision,
                             Status status,
                             String reason,
                             Optional<String> canonicalData,
                             Set<ContractRef<CapabilityId>> requiredCapabilities,
                             Map<String, Object> unknown) {
        public Inspection {
            definitionKey = Objects.requireNonNull(definitionKey, "Catalog definition key is required");
            if (revision < 0) {
                throw new IllegalArgumentException("Catalog entry revision cannot be negative");
            }
            status = Objects.requireNonNull(status, "Inspector status is required");
            reason = Objects.requireNonNull(reason, "Inspector reason is required");
            canonicalData = canonicalData == null ? Optional.empty() : canonicalData;
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
            unknown = unknown == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(unknown));
        }

        public boolean readOnly() {
            return true;
        }

        public byte[] canonicalDataBytes() {
            return canonicalData.map(value -> value.getBytes(StandardCharsets.UTF_8)).orElseGet(() -> new byte[0]);
        }
    }
}
