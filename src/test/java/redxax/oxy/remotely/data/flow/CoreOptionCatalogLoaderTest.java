package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreOptionCatalogLoaderTest {
    @Test
    void schemaDefaultsAreAutomaticWhileRequiredCallerFieldsAreNot() {
        TypeExpr.Named string = new TypeExpr.Named(new TypeReference("builtin", "string"), List.of());
        OptionQuerySchemaV1 empty = OptionQuerySchemaV1.empty();
        OptionCatalogLoader.CoreRequest supported = request(empty, Map.of(), Map.of(), null);
        TypedValue dependency = TypedValue.value(
            string, "project");
        OptionQuerySchemaV1 requiredDependency = new OptionQuerySchemaV1(null, Map.of(),
            Map.of("project", new OptionQuerySchemaV1.Field(string, true)));
        OptionCatalogLoader.CoreRequest unsupported = request(requiredDependency, Map.of(),
            Map.of("project", dependency), null);
        TypedValue defaultValue = TypedValue.value(string, "all");
        OptionQuerySchemaV1 defaultedContext = new OptionQuerySchemaV1(null,
            Map.of("mode", new OptionQuerySchemaV1.Field(string, true, defaultValue)), Map.of());
        OptionCatalogLoader.CoreRequest defaulted = request(defaultedContext, Map.of(), Map.of(), null);
        AtomicInteger dispatched = new AtomicInteger();
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());

        OptionCatalogLoader.CoreSnapshot first = OptionCatalogLoader.snapshot(supported,
            (request, forceRefresh) -> {
                dispatched.incrementAndGet();
                return outcome(cache.admit(request.key(), forceRefresh));
            }, cache);
        OptionCatalogLoader.CoreSnapshot second = OptionCatalogLoader.snapshot(unsupported,
            (request, forceRefresh) -> {
                dispatched.incrementAndGet();
                return OptionCatalogLoader.CoreRequestOutcome.STARTED;
            }, cache);

        assertTrue(supported.automaticSupported());
        assertTrue(defaulted.automaticSupported());
        assertEquals(defaultValue, defaulted.context().get("mode"));
        assertTrue(first.loading());
        assertFalse(unsupported.automaticSupported());
        assertEquals("unsupported", second.status());
        assertEquals(1, dispatched.get());
        assertNotEquals(supported.key(), unsupported.key());
        assertEquals(dependency, unsupported.dependencies().get("project"));
    }

    @Test
    void directResourceContextRequiresTheExactAdvertisedLocatorType() {
        ServerId server = ServerId.deterministic("core-option-context");
        ContractRef<ResourceTypeId> resourceType = ContractRef.of(OwnerId.of("owner"), ResourceTypeId.of("project"));
        TypeExpr.ResourceType resourceSchema = new TypeExpr.ResourceType(
            new TypeReference(resourceType.owner().canonicalText(), resourceType.id().value()));
        OptionQuerySchemaV1 querySchema = OptionQuerySchemaV1.requiredResource(resourceSchema);
        ServerResourceLocator matching = new ServerResourceLocator(server, resourceType, "current");
        ContractRef<ResourceTypeId> otherType = ContractRef.of(OwnerId.of("owner"), ResourceTypeId.of("other"));
        ServerResourceLocator other = new ServerResourceLocator(server, otherType, "current");

        assertTrue(request(querySchema, Map.of(), Map.of(), matching).automaticSupported());
        assertThrows(IllegalArgumentException.class, () -> request(querySchema, Map.of(), Map.of(), other));
    }

    @Test
    void nullAndEmptySearchRemainDistinctCacheIdentities() {
        OptionCatalogLoader.CoreRequest absent = request(OptionQuerySchemaV1.empty(), Map.of(), Map.of(), null,
            "absent-search", null);
        OptionCatalogLoader.CoreRequest empty = request(OptionQuerySchemaV1.empty(), Map.of(), Map.of(), null,
            "absent-search", "");

        assertNotEquals(absent.key(), empty.key());
        assertNull(absent.key().search());
        assertEquals("", empty.key().search());
    }

    @Test
    void busyAdmissionIsImmediatelyUnavailableWithoutCreatingAnotherKey() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        for (int index = 0; index < OptionCatalogCache.MAX_CORE_KEYS; index++) {
            OptionCatalogLoader.CoreRequest admitted = request(OptionQuerySchemaV1.empty(), Map.of(), Map.of(), null,
                "source-" + index, null);
            assertEquals(OptionCatalogCache.CoreAdmission.STARTED, cache.admit(admitted.key(), false));
        }
        OptionCatalogLoader.CoreRequest blocked = request(OptionQuerySchemaV1.empty(), Map.of(), Map.of(), null,
            "overflow", null);

        OptionCatalogLoader.CoreSnapshot snapshot = OptionCatalogLoader.snapshot(blocked,
            (request, forceRefresh) -> outcome(cache.admit(request.key(), forceRefresh)), cache);

        assertEquals("unavailable", snapshot.status());
        assertEquals("Option Catalog Capacity Is Busy", snapshot.diagnostic());
        assertFalse(snapshot.loading());
        assertEquals(OptionCatalogCache.MAX_CORE_KEYS, cache.coreMetrics().keys());
        assertEquals(OptionCatalogCache.MAX_CORE_KEYS, cache.coreMetrics().inFlight());
        assertEquals("missing", cache.coreSnapshot(blocked.key()).status());

        OptionCatalogLoader.CoreRequest rejected = request(OptionQuerySchemaV1.empty(), Map.of(), Map.of(), null,
            "rejected", null);
        OptionCatalogLoader.CoreSnapshot rejectedSnapshot = OptionCatalogLoader.snapshot(rejected,
            (request, forceRefresh) -> OptionCatalogLoader.CoreRequestOutcome.REJECTED, cache);
        assertEquals("unavailable", rejectedSnapshot.status());
        assertEquals("Option Catalog Request Is Unavailable", rejectedSnapshot.diagnostic());
        assertFalse(rejectedSnapshot.loading());
        assertEquals(OptionCatalogCache.MAX_CORE_KEYS, cache.coreMetrics().keys());
        assertEquals("missing", cache.coreSnapshot(rejected.key()).status());
    }

    private static OptionCatalogLoader.CoreRequest request(OptionQuerySchemaV1 querySchema,
                                                            Map<String, TypedValue> context,
                                                            Map<String, TypedValue> dependencies,
                                                            ServerResourceLocator resource) {
        return request(querySchema, context, dependencies, resource, "resources", null);
    }

    private static OptionCatalogLoader.CoreRequest request(OptionQuerySchemaV1 querySchema,
                                                            Map<String, TypedValue> context,
                                                            Map<String, TypedValue> dependencies,
                                                            ServerResourceLocator resource, String sourceId,
                                                            String search) {
        ServerId server = resource != null ? resource.serverId() : ServerId.deterministic("core-option-loader");
        OwnerId owner = OwnerId.of("owner");
        ContractRef<InspectorFieldId> reference = ContractRef.of(owner, InspectorFieldId.of(sourceId));
        ContractRef<ResourceTypeId> resourceType = ContractRef.of(owner, ResourceTypeId.of("resource"));
        TypeExpr.ResourceType optionType = new TypeExpr.ResourceType(
            new TypeReference(resourceType.owner().canonicalText(), resourceType.id().value()));
        InspectorOptionSource source = new InspectorOptionSource(reference.id(), "Resources",
            "Provides selectable resources.", optionType, querySchema,
            ContractRef.of(owner, CapabilityId.of("resource-options")), 25, "resources");
        return new OptionCatalogLoader.CoreRequest(server, reference, source, resource, context, dependencies, search,
            new CatalogBinding(1L, "1".repeat(64), "2".repeat(64)), new CatalogVersion(1, 3),
            new ContentHash("3".repeat(64)), 4L, 5L);
    }

    private static OptionCatalogLoader.CoreRequestOutcome outcome(OptionCatalogCache.CoreAdmission admission) {
        return switch (admission) {
            case STARTED -> OptionCatalogLoader.CoreRequestOutcome.STARTED;
            case FRESH -> OptionCatalogLoader.CoreRequestOutcome.FRESH;
            case COALESCED -> OptionCatalogLoader.CoreRequestOutcome.COALESCED;
            case BUSY -> OptionCatalogLoader.CoreRequestOutcome.BUSY;
        };
    }
}
