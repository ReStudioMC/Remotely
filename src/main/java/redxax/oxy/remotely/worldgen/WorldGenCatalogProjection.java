package redxax.oxy.remotely.worldgen;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncLifecycleDiagnostics;
import redxax.oxy.remotely.data.flow.ReSyncTypedInteractionProjection;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.identity.ServerId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;

public final class WorldGenCatalogProjection {
    private static final String WORLDGEN_SUFFIX = ":worldgen";
    private static final int MAX_TRACE_KEYS = 256;
    private static final long TRACE_INTERVAL_NANOS = ((250L) * 1_000_000L);
    private static final Map<String, Long> LAST_TRACES = BrowserSafeState.map();

    private WorldGenCatalogProjection() {
    }

    public enum Authority {
        TYPED_PUBLICATION,
        TYPED_RECONCILIATION,
        LEGACY_COMPATIBILITY,
        UNAVAILABLE
    }

    public static final class Snapshot {
        private final String serverId;
        private final Authority authority;
        private final CatalogCacheKey catalogKey;
        private final Map<String, NodeDefinition> definitions;
        private final String diagnostic;

        private Snapshot(String serverId, Authority authority, Map<String, NodeDefinition> definitions, String diagnostic) {
            this(serverId, authority, null, definitions, diagnostic, false);
        }

        private Snapshot(String serverId, Authority authority, CatalogCacheKey catalogKey,
                         Map<String, NodeDefinition> definitions, String diagnostic, boolean immutable) {
            this.serverId = normalizeBaseServerId(serverId);
            this.authority = Objects.requireNonNull(authority, "WorldGen catalog authority is required");
            this.catalogKey = catalogKey;
            this.definitions = immutable ? Objects.requireNonNull(definitions, "WorldGen definitions are required")
                : immutableDefinitions(definitions);
            this.diagnostic = diagnostic == null ? "" : diagnostic;
        }

        public String serverId() {
            return serverId;
        }

        public Authority authority() {
            return authority;
        }

        public Optional<CatalogCacheKey> catalogKey() {
            return Optional.ofNullable(catalogKey);
        }

        public Map<String, NodeDefinition> definitions() {
            return definitions;
        }

        public String diagnostic() {
            return diagnostic;
        }

        public boolean typedPublication() {
            return authority == Authority.TYPED_PUBLICATION;
        }

        public boolean legacyCompatibility() {
            return authority == Authority.LEGACY_COMPATIBILITY;
        }

        public boolean readOnly() {
            return authority != Authority.TYPED_PUBLICATION;
        }

        public Optional<NodeDefinition> definition(String nodeReference) {
            String identity = canonicalReference(nodeReference);
            return identity.isBlank() ? Optional.empty() : Optional.ofNullable(definitions.get(identity));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Snapshot snapshot)) {
                return false;
            }
            return serverId.equals(snapshot.serverId)
                && authority == snapshot.authority
                && Objects.equals(catalogKey, snapshot.catalogKey)
                && definitions.equals(snapshot.definitions)
                && diagnostic.equals(snapshot.diagnostic);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serverId, authority, catalogKey, definitions, diagnostic);
        }

        @Override
        public String toString() {
            return "Snapshot[serverId=" + serverId + ", authority=" + authority + ", catalogKey=" + catalogKey
                + ", definitions=" + definitions.size()
                + ", diagnostic=" + diagnostic + "]";
        }
    }

    public static Snapshot from(String serverId, ReSyncFlowClient client) {
        long startedAt = System.nanoTime();
        String normalizedServerId = normalizeBaseServerId(serverId);
        if (client == null || !normalizedServerId.equals(normalizeBaseServerId(client.getServerId()))) {
            return traced(unavailable(normalizedServerId, "CATALOG_CLIENT_UNAVAILABLE"), client, startedAt,
                client == null ? "client_missing" : "client_server_mismatch");
        }
        Snapshot result = switch (client.catalogAuthority()) {
            case TYPED_PUBLICATION -> ReSyncTypedInteractionProjection.from(client)
                .filter(projection -> matchesServerId(normalizedServerId, projection))
                .map(WorldGenCatalogProjection::typed)
                .orElseGet(() -> reconciliation(normalizedServerId, client.catalogAuthorityDiagnostic().orElse("CATALOG_TYPED_PUBLICATION_UNAVAILABLE")));
            case TYPED_RECONCILIATION -> reconciliation(normalizedServerId,
                client.catalogAuthorityDiagnostic().orElse("CATALOG_PUBLICATION_RECONCILIATION_REQUIRED"));
            case LEGACY_COMPATIBILITY -> legacy(normalizedServerId, Map.of(),
                client.catalogAuthorityDiagnostic().orElse("CATALOG_TYPED_PUBLICATION_UNAVAILABLE;LEGACY_COMPATIBILITY"));
            case UNAVAILABLE -> unavailable(normalizedServerId,
                client.catalogAuthorityDiagnostic().orElse("CATALOG_PUBLICATION_UNAVAILABLE"));
        };
        return traced(result, client, startedAt, result.definitions().isEmpty() ? "empty_projection" : "projected");
    }

    private static Snapshot traced(Snapshot snapshot, ReSyncFlowClient client, long startedAt, String reason) {
        if (!ReSyncLifecycleDiagnostics.enabled()) {
            return snapshot;
        }
        CatalogCacheKey key = snapshot.catalogKey().orElse(null);
        String traceKey = snapshot.serverId() + '|' + snapshot.authority() + '|'
            + (key != null ? key.canonicalText() : "none") + '|' + reason;
        if (!traceAllowed(traceKey)) {
            return snapshot;
        }
        Optional<ReSyncTypedInteractionProjection> interaction = ReSyncTypedInteractionProjection.from(client)
            .filter(projection -> key != null && key.equals(projection.key()));
        long revision = interaction.map(ReSyncTypedInteractionProjection::revision).orElse(-1L);
        ReSyncFlowClient.traceLifecycle(snapshot.serverId(), "worldgen_catalog_projection",
            "resourceKey", key != null ? key.canonicalText() : "worldgen:catalog", "operation",
            "project_worldgen_catalog", "requestId", "", "correlationId", "", "traceId", "", "mutationId", "",
            "generation", key != null ? key.catalogGeneration() : -1L, "authorityEpoch", -1L, "revision", revision,
            "authority", snapshot.authority(), "inputCount", client != null ? 1 : 0, "outputCount",
            snapshot.definitions().size(), "readOnly", snapshot.readOnly(), "diagnostic", snapshot.diagnostic(),
            "descriptorCount", interaction.map(value -> value.descriptors().size()).orElse(0), "widgetCount",
            interaction.map(value -> value.palette(false).definitions().size()).orElse(0), "worldGenPaletteCount",
            interaction.map(value -> value.palette(true).definitions().size()).orElse(0), "reason", reason,
            "rejectionReason", snapshot.readOnly() ? snapshot.diagnostic() : "", "elapsedMs", elapsedMillis(startedAt));
        return snapshot;
    }

    private static boolean traceAllowed(String key) {
        long now = System.nanoTime();
        while (true) {
            Long previous = LAST_TRACES.get(key);
            if (previous != null && now - previous < TRACE_INTERVAL_NANOS) {
                return false;
            }
            if (previous == null) {
                if (LAST_TRACES.size() >= MAX_TRACE_KEYS) {
                    LAST_TRACES.keySet().stream().findFirst().ifPresent(LAST_TRACES::remove);
                }
                if (LAST_TRACES.putIfAbsent(key, now) == null) {
                    return true;
                }
            } else if (LAST_TRACES.replace(key, previous, now)) {
                return true;
            }
        }
    }

    private static long elapsedMillis(long startedAt) {
        return BrowserSafeState.nanosToMillis(Math.max(0L, System.nanoTime() - startedAt));
    }

    private static Snapshot typed(ReSyncTypedInteractionProjection projection) {
        String serverId = projection.key().serverId().canonicalText();
        return new Snapshot(serverId, Authority.TYPED_PUBLICATION, projection.key(),
            projection.worldGenDefinitions(), "", true);
    }

    private static boolean matchesServerId(String serverId, ReSyncTypedInteractionProjection projection) {
        if (projection == null || projection.key() == null) {
            return false;
        }
        try {
            return ServerId.parseCanonicalText(normalizeBaseServerId(serverId)).equals(projection.key().serverId());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static Snapshot legacy(String serverId, Map<String, NodeDefinition> definitions, String diagnostic) {
        return new Snapshot(normalizeBaseServerId(serverId), Authority.LEGACY_COMPATIBILITY, definitions, diagnostic);
    }

    public static Snapshot reconciliation(String serverId, String diagnostic) {
        return new Snapshot(normalizeBaseServerId(serverId), Authority.TYPED_RECONCILIATION, Map.of(), diagnostic);
    }

    public static Snapshot unavailable(String serverId, String diagnostic) {
        return new Snapshot(normalizeBaseServerId(serverId), Authority.UNAVAILABLE, Map.of(), diagnostic);
    }

    public static String normalizeBaseServerId(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return "local";
        }
        String normalized = serverId.strip();
        return normalized.endsWith(WORLDGEN_SUFFIX)
            ? normalized.substring(0, normalized.length() - WORLDGEN_SUFFIX.length()) : normalized;
    }

    public static String scopedServerId(String serverId) {
        return normalizeBaseServerId(serverId) + WORLDGEN_SUFFIX;
    }

    public static String canonicalReference(String owner, String nodeId) {
        if (owner == null || nodeId == null || owner.isBlank() || nodeId.isBlank()
            || !owner.equals(owner.strip()) || !nodeId.equals(nodeId.strip())
            || owner.indexOf(':') >= 0 || nodeId.indexOf(':') >= 0) {
            return "";
        }
        return owner + ":" + nodeId;
    }

    public static String canonicalReference(String nodeReference) {
        if (nodeReference == null || nodeReference.isBlank() || !nodeReference.equals(nodeReference.strip())) {
            return "";
        }
        int separator = nodeReference.indexOf(':');
        if (separator <= 0 || separator == nodeReference.length() - 1
            || nodeReference.indexOf(':', separator + 1) >= 0) {
            return "";
        }
        return canonicalReference(nodeReference.substring(0, separator), nodeReference.substring(separator + 1));
    }

    public static boolean isWorldGenReference(String nodeReference) {
        String canonical = canonicalReference(nodeReference);
        return !canonical.isBlank() && canonical.startsWith("worldgen:");
    }

    public static boolean isWorldGenDefinition(NodeDefinition definition) {
        if (definition == null) {
            return false;
        }
        return isWorldGenOwner(definition.getOwner());
    }

    private static boolean isWorldGenOwner(String owner) {
        return "worldgen".equals(owner);
    }

    private static Map<String, NodeDefinition> immutableDefinitions(Map<String, NodeDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, NodeDefinition> result = new LinkedHashMap<>();
        definitions.forEach((key, definition) -> {
            String canonical = canonicalReference(definition != null ? definition.getOwner() : null,
                definition != null ? definition.getId() : null);
            if (!canonical.isBlank() && (key == null || key.equals(canonical))) {
                result.put(canonical, definition);
            }
        });
        return Collections.unmodifiableMap(result);
    }
}
