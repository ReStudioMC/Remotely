package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.protocol.OptionItem;
import restudio.resync.flow.protocol.OptionQuery;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class OptionCatalogLoader {
    private OptionCatalogLoader() {
    }

    public static Profile profile(String... sources) {
        List<Request> requests = new ArrayList<>();
        if (sources != null) {
            for (String source : sources) {
                if (source != null && !source.isBlank()) {
                    requests.add(new Request(source, Map.of()));
                }
            }
        }
        return new Profile(requests);
    }

    public static Request request(String source) {
        return new Request(source, Map.of());
    }

    public static Request request(String source, Map<String, Object> context) {
        return new Request(source, context);
    }

    public static Optional<CoreRequest> coreRequest(String serverId, ContractRef<InspectorFieldId> source,
                                                    ServerResourceLocator resource,
                                                    Map<String, TypedValue> context,
                                                    Map<String, TypedValue> dependencies,
                                                    String search) {
        ReSyncFlowClient client = LegacyRequests.INSTANCE.client(serverId);
        return client == null ? Optional.empty()
            : client.coreOptionRequest(source, resource, context, dependencies, search);
    }

    public static Optional<CoreRequest> automaticCoreRequest(String serverId, ContractRef<InspectorFieldId> source,
                                                             ServerResourceLocator currentResource,
                                                             Map<String, TypedValue> context,
                                                             Map<String, TypedValue> dependencies,
                                                             String search) {
        ReSyncFlowClient client = LegacyRequests.INSTANCE.client(serverId);
        return automaticCoreRequest(client, source, currentResource, context, dependencies, search);
    }

    static Optional<CoreRequest> automaticCoreRequest(ReSyncFlowClient client,
                                                       ContractRef<InspectorFieldId> source,
                                                       ServerResourceLocator currentResource,
                                                       Map<String, TypedValue> context,
                                                       Map<String, TypedValue> dependencies,
                                                       String search) {
        return client == null ? Optional.empty()
            : client.automaticCoreOptionRequest(source, currentResource, context, dependencies, search);
    }

    public static CoreSnapshot snapshot(CoreRequest request) {
        return snapshot(request, CoreRequestTransport.INSTANCE, OptionCatalogCache.getInstance());
    }

    public static CoreSnapshot snapshot(CoreRequest request, CoreRequests requests, OptionCatalogCache cache) {
        if (request == null || requests == null || cache == null) {
            return CoreSnapshot.missing(request);
        }
        if (!request.automaticSupported()) {
            return CoreSnapshot.unsupported(request);
        }
        OptionCatalogCache.CoreCatalogSnapshot cached = cache.coreSnapshot(request.key());
        if (!cached.loading() && ("missing".equals(cached.status()) || "stale".equals(cached.status()))) {
            CoreRequestOutcome outcome = requests.request(request, false);
            cached = cache.coreSnapshot(request.key());
            if (outcome == CoreRequestOutcome.BUSY || outcome == CoreRequestOutcome.REJECTED) {
                String diagnostic = outcome == CoreRequestOutcome.BUSY
                    ? "Option Catalog Capacity Is Busy" : "Option Catalog Request Is Unavailable";
                List<OptionItem> retained = cached.catalog() != null ? cached.catalog().items() : List.of();
                return new CoreSnapshot(request, cached.catalog(), retained, false, "unavailable", diagnostic);
            }
        }
        List<OptionItem> items = cached.catalog() != null ? cached.catalog().items() : List.of();
        return new CoreSnapshot(request, cached.catalog(), items, cached.loading(), cached.status(), cached.diagnostic());
    }

    public static boolean refresh(CoreRequest request) {
        return request != null && request.automaticSupported()
            && CoreRequestTransport.INSTANCE.request(request, true) == CoreRequestOutcome.STARTED;
    }

    public static void preload(String serverId, String source) {
        preload(serverId, new Request(source, Map.of()));
    }

    public static void preload(String serverId, String source, Map<String, Object> context) {
        preload(serverId, new Request(source, context));
    }

    public static void preload(String serverId, Request... requests) {
        preload(serverId, requests != null ? Arrays.asList(requests) : List.of());
    }

    public static void preload(String serverId, Collection<Request> requests) {
        load(serverId, requests, false);
    }

    public static void refresh(String serverId, String source) {
        refresh(serverId, new Request(source, Map.of()));
    }

    public static void refresh(String serverId, String source, Map<String, Object> context) {
        refresh(serverId, new Request(source, context));
    }

    public static void refresh(String serverId, Request... requests) {
        load(serverId, requests != null ? Arrays.asList(requests) : List.of(), true);
    }

    public static Snapshot snapshot(String serverId, String source) {
        return snapshot(serverId, source, Map.of());
    }

    public static Snapshot snapshot(String serverId, String source, Map<String, Object> context) {
        Request request = new Request(source, context);
        return snapshot(serverId, request, LegacyRequests.INSTANCE, OptionCatalogCache.getInstance());
    }

    public static Snapshot snapshot(String serverId, Request request, Requests requests, OptionCatalogCache cache) {
        if (serverId == null || serverId.isBlank() || request == null || request.source().isBlank() || requests == null
            || cache == null) {
            return Snapshot.missing(request);
        }
        String contextKey = requests.contextKey(serverId, request.context());
        if (contextKey == null) {
            return Snapshot.missing(request);
        }
        boolean present = cache.hasCatalog(serverId, request.source(), contextKey);
        if (!present || cache.isStale(serverId, request.source(), contextKey)) {
            requests.request(serverId, request.source(), request.context(), false);
        }
        boolean loading = !present || cache.isStale(serverId, request.source(), contextKey)
            || cache.isRequestInFlight(serverId, request.source(), contextKey);
        return new Snapshot(request, contextKey, cache.getValues(serverId, request.source(), contextKey),
            cache.getItems(serverId, request.source(), contextKey), loading,
            cache.getStatus(serverId, request.source(), contextKey), cache.getDiagnostic(serverId, request.source(), contextKey));
    }

    public static ResourceRequest resourceRequest(ServerId serverId, ContractRef<ResourceTypeId> resourceType,
                                                  String source, Map<String, Object> context) {
        return new ResourceRequest(serverId, resourceType, source, context);
    }

    public static ResourceSnapshot snapshot(ResourceRequest request) {
        return snapshot(request, LegacyRequests.INSTANCE, OptionCatalogCache.getInstance());
    }

    public static ResourceSnapshot snapshot(ResourceRequest request, Requests requests, OptionCatalogCache cache) {
        if (request == null || requests == null || cache == null) {
            return ResourceSnapshot.missing(request);
        }
        String serverId = request.serverId().canonicalText();
        String contextKey = requests.contextKey(serverId, request.context());
        if (contextKey == null) {
            return ResourceSnapshot.missing(request);
        }
        OptionCatalogCache.CatalogKey key = new OptionCatalogCache.CatalogKey(request.serverId(), request.resourceType(),
            request.source(), contextKey);
        boolean present = cache.hasCatalog(key);
        if (!present || cache.isStale(key)) {
            requests.request(serverId, request.source(), request.context(), false);
        }
        boolean loading = !present || cache.isStale(key) || cache.isRequestInFlight(key);
        return new ResourceSnapshot(request, key, cache.getItems(key), loading, cache.getStatus(key), cache.getDiagnostic(key));
    }

    private static void load(String serverId, Collection<Request> requests, boolean forceRefresh) {
        load(serverId, requests, forceRefresh, LegacyRequests.INSTANCE);
    }

    public static void load(String serverId, Collection<Request> requests, boolean forceRefresh, Requests capability) {
        if (serverId == null || serverId.isBlank() || requests == null || requests.isEmpty() || capability == null) {
            return;
        }
        Map<String, Request> distinct = new LinkedHashMap<>();
        for (Request request : requests) {
            if (request == null || request.source().isBlank()) {
                continue;
            }
            String contextKey = capability.contextKey(serverId, request.context());
            if (contextKey == null) {
                continue;
            }
            String key = request.source() + "\u0000" + contextKey;
            distinct.putIfAbsent(key, request);
        }
        for (Request request : distinct.values()) {
            capability.request(serverId, request.source(), request.context(), forceRefresh);
        }
    }

    public interface Requests {
        String contextKey(String serverId, Map<String, Object> context);

        boolean request(String serverId, String source, Map<String, Object> context, boolean forceRefresh);
    }

    public interface CoreRequests {
        CoreRequestOutcome request(CoreRequest request, boolean forceRefresh);
    }

    public enum CoreRequestOutcome {
        STARTED,
        FRESH,
        COALESCED,
        BUSY,
        REJECTED
    }

    private enum CoreRequestTransport implements CoreRequests {
        INSTANCE;

        @Override
        public CoreRequestOutcome request(CoreRequest request, boolean forceRefresh) {
            ReSyncFlowClient client = request == null ? null : LegacyRequests.INSTANCE.client(request.serverId().canonicalText());
            return client != null ? client.requestCoreOptionCatalogOutcome(request, forceRefresh)
                : CoreRequestOutcome.REJECTED;
        }
    }

    private enum LegacyRequests implements Requests {
        INSTANCE;

        @Override
        public String contextKey(String serverId, Map<String, Object> context) {
            ReSyncFlowClient client = client(serverId);
            if (client == null) {
                FlowManager manager = FlowManager.getInstance();
                client = manager != null && serverId != null && !serverId.isBlank()
                    ? manager.ensureFlowClient(serverId) : null;
            }
            return client != null ? client.optionCatalogContextKey(context) : null;
        }

        @Override
        public boolean request(String serverId, String source, Map<String, Object> context, boolean forceRefresh) {
            ReSyncFlowClient client = client(serverId);
            if (client == null) {
                FlowManager manager = FlowManager.getInstance();
                client = manager != null && serverId != null && !serverId.isBlank()
                    ? manager.ensureFlowClient(serverId) : null;
            }
            if (client == null) {
                return false;
            }
            client.requestOptionCatalog(source, context, forceRefresh);
            return true;
        }

        private ReSyncFlowClient client(String serverId) {
            FlowManager manager = FlowManager.getInstance();
            return manager != null && serverId != null && !serverId.isBlank()
                ? manager.existingFlowClient(serverId) : null;
        }
    }

    public record Request(String source, Map<String, Object> context) {
        public Request {
            source = source != null ? source : "";
            if (context == null || context.isEmpty()) {
                context = Map.of();
            } else {
                Map<String, Object> copied = new LinkedHashMap<>();
                context.forEach((key, value) -> {
                    if (key != null) {
                        copied.put(key, value);
                    }
                });
                context = copied.isEmpty() ? Map.of() : Collections.unmodifiableMap(copied);
            }
        }
    }

    public record ResourceRequest(ServerId serverId, ContractRef<ResourceTypeId> resourceType, String source,
                                  Map<String, Object> context) {
        public ResourceRequest {
            if (serverId == null || resourceType == null) {
                throw new IllegalArgumentException("Resource catalog server and type are required");
            }
            Request normalized = new Request(source, context);
            if (normalized.source().isBlank()) {
                throw new IllegalArgumentException("Resource catalog source is required");
            }
            source = normalized.source();
            context = normalized.context();
        }
    }

    public record CoreRequest(ServerId serverId, ContractRef<InspectorFieldId> sourceReference,
                              InspectorOptionSource source, ServerResourceLocator resource,
                              Map<String, TypedValue> context, Map<String, TypedValue> dependencies, String search,
                              CatalogBinding catalogBinding, CatalogVersion publicationContractVersion,
                              ContentHash publicationChecksum, long publicationRevision, long authorityEpoch,
                              OptionCatalogCache.CoreKey key) {
        public CoreRequest(ServerId serverId, ContractRef<InspectorFieldId> sourceReference,
                           InspectorOptionSource source, ServerResourceLocator resource,
                           Map<String, TypedValue> context, Map<String, TypedValue> dependencies, String search,
                           CatalogBinding catalogBinding, CatalogVersion publicationContractVersion,
                           ContentHash publicationChecksum, long publicationRevision, long authorityEpoch) {
            this(serverId, sourceReference, source, resource, context, dependencies, search, catalogBinding,
                publicationContractVersion, publicationChecksum, publicationRevision, authorityEpoch, null);
        }

        public CoreRequest {
            Objects.requireNonNull(serverId, "Option query server is required");
            Objects.requireNonNull(sourceReference, "Advertised option source reference is required");
            Objects.requireNonNull(source, "Advertised option source is required");
            Objects.requireNonNull(catalogBinding, "Option query catalog binding is required");
            Objects.requireNonNull(publicationContractVersion, "Option query publication contract is required");
            Objects.requireNonNull(publicationChecksum, "Option query publication checksum is required");
            if (!sourceReference.id().equals(source.id())) {
                throw new IllegalArgumentException("Advertised option source identity does not match its reference");
            }
            OptionQuerySchemaV1.Normalized values = source.querySchema().normalize(serverId, resource,
                context == null ? Map.of() : context, dependencies == null ? Map.of() : dependencies);
            OptionQuery normalized = new OptionQuery(sourceReference, source.capability(), serverId, values.resource(),
                values.context(), values.dependencies(), null, source.pageLimit(), search, 0L,
                source.invalidationKey());
            resource = normalized.resource();
            context = normalized.context();
            dependencies = normalized.dependencies();
            search = normalized.search();
            if (publicationRevision < 0L || authorityEpoch < 1L) {
                throw new IllegalArgumentException("Option query publication and authority must be current");
            }
            OptionCatalogCache.CoreKey expected = new OptionCatalogCache.CoreKey(serverId, sourceReference,
                source.capability(), resource, canonicalValues(context), canonicalValues(dependencies), search,
                Set.copyOf(context.keySet()), Set.copyOf(dependencies.keySet()), catalogBinding,
                publicationContractVersion, publicationChecksum, publicationRevision, authorityEpoch);
            if (key != null && !key.equals(expected)) {
                throw new IllegalArgumentException("Option query cache key does not match its request");
            }
            key = expected;
        }

        public boolean automaticSupported() {
            OptionQuerySchemaV1 schema = source.querySchema();
            if (schema.resource() == null && resource != null
                || schema.resource() != null && schema.resource().required() && resource == null) {
                return false;
            }
            return automaticallyFilled(schema.context()) && automaticallyFilled(schema.dependencies());
        }

        public String unsupportedDiagnostic() {
            return "Option Source Context Is Not Supported";
        }

        private static String canonicalValues(Map<String, TypedValue> values) {
            Map<String, Object> canonical = new TreeMap<>();
            values.forEach((name, value) -> canonical.put(name, value.canonicalValue()));
            return CanonicalJson.canonicalize(canonical);
        }

        private static boolean automaticallyFilled(Map<String, OptionQuerySchemaV1.Field> fields) {
            return fields.values().stream().allMatch(field -> !field.required() || field.defaultValue() != null);
        }
    }

    public record Profile(List<Request> requests) {
        public Profile {
            requests = requests != null ? requests.stream().filter(request -> request != null && !request.source().isBlank()).toList() : List.of();
        }

        public void preload(String serverId) {
            OptionCatalogLoader.preload(serverId, requests);
        }

        public void refresh(String serverId) {
            OptionCatalogLoader.load(serverId, requests, true);
        }
    }

    public record Snapshot(Request request, String contextKey, List<String> values, List<OptionCatalogItem> items,
                           boolean loading, String status, String diagnostic) {
        public Snapshot {
            request = request != null ? request : new Request("", Map.of());
            contextKey = contextKey != null ? contextKey : "";
            values = values != null ? List.copyOf(values) : List.of();
            items = items != null ? items.stream().filter(item -> item != null).toList() : List.of();
            status = status != null ? status : "missing";
            diagnostic = diagnostic != null ? diagnostic : "";
        }

        private static Snapshot missing(Request request) {
            return new Snapshot(request, "", List.of(), List.of(), true, "missing", "Catalog has not been loaded");
        }
    }

    public record ResourceSnapshot(ResourceRequest request, OptionCatalogCache.CatalogKey key,
                                   List<OptionCatalogItem> items, boolean loading, String status, String diagnostic) {
        public ResourceSnapshot {
            items = items != null ? items.stream().filter(item -> item != null && item.isResource()).toList() : List.of();
            status = status != null ? status : "missing";
            diagnostic = diagnostic != null ? diagnostic : "";
        }

        private static ResourceSnapshot missing(ResourceRequest request) {
            return new ResourceSnapshot(request, null, List.of(), true, "missing", "Catalog has not been loaded");
        }
    }

    public record CoreSnapshot(CoreRequest request, OptionCatalogCache.CompletedCoreCatalog catalog,
                               List<OptionItem> items, boolean loading, String status, String diagnostic) {
        public CoreSnapshot {
            items = items == null ? List.of() : List.copyOf(items);
            status = status == null || status.isBlank() ? "missing" : status;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        private static CoreSnapshot missing(CoreRequest request) {
            return new CoreSnapshot(request, null, List.of(), false, "missing", "Option Source Is Unavailable");
        }

        private static CoreSnapshot unsupported(CoreRequest request) {
            return new CoreSnapshot(request, null, List.of(), false, "unsupported",
                request != null ? request.unsupportedDiagnostic() : "Option Source Is Unavailable");
        }
    }
}
