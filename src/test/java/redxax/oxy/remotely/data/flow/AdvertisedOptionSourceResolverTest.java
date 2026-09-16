package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.type.TypedValue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdvertisedOptionSourceResolverTest {
    private static final OwnerId OWNER = OwnerId.of("owner");

    @Test
    void exactAdvertisedFieldReferenceResolvesItsDescriptorCapability() {
        CatalogAuthoringPublication publication = publication(List.of(entry("resources", "resource-options")));
        ContractRef<InspectorFieldId> reference = ContractRef.of(OWNER, InspectorFieldId.of("resources"));

        AdvertisedOptionSourceResolver.Resolved resolved = AdvertisedOptionSourceResolver.resolve(publication, reference)
            .orElseThrow();

        assertEquals(reference, resolved.reference());
        assertEquals(ContractRef.of(OWNER, CapabilityId.of("resource-options")), resolved.source().capability());
    }

    @Test
    void sourcesMayShareOneQueryCapability() {
        CatalogAuthoringPublication publication = publication(List.of(
            entry("first", "resource-options"), entry("second", "resource-options")));
        ContractRef<InspectorFieldId> firstReference = ContractRef.of(OWNER, InspectorFieldId.of("first"));
        ContractRef<InspectorFieldId> secondReference = ContractRef.of(OWNER, InspectorFieldId.of("second"));

        AdvertisedOptionSourceResolver.Resolved first = AdvertisedOptionSourceResolver.resolve(publication,
            firstReference).orElseThrow();
        AdvertisedOptionSourceResolver.Resolved second = AdvertisedOptionSourceResolver.resolve(publication,
            secondReference).orElseThrow();

        assertEquals(firstReference, first.reference());
        assertEquals(secondReference, second.reference());
        assertEquals(first.source().capability(), second.source().capability());
    }

    @Test
    void querySchemaDefaultsAndUnknownDataAreDecodedWithoutLosingTypeInformation() {
        String fieldType = "{\"arguments\":[],\"kind\":\"named\",\"type\":{\"localId\":\"string\",\"ownerId\":\"builtin\"}}";
        String schema = "{\"context\":{\"mode\":{\"defaultValue\":{\"futureValue\":true,\"state\":\"value\",\"type\":"
            + fieldType + ",\"value\":\"all\"},\"futureField\":\"keep\",\"required\":true,\"type\":"
            + fieldType + "}},\"dependencies\":{},\"futureSchema\":{\"keep\":true},\"resource\":null,\"schemaVersion\":1}";
        CatalogAuthoringPublication publication = publication(List.of(entry("resources", "resource-options", schema)));
        AdvertisedOptionSourceResolver.Resolved resolved = AdvertisedOptionSourceResolver.resolve(publication,
            ContractRef.of(OWNER, InspectorFieldId.of("resources"))).orElseThrow();

        assertEquals(Map.of("keep", true), resolved.source().querySchema().unknown().get("futureSchema"));
        assertEquals("keep", resolved.source().querySchema().context().get("mode").unknown().get("futureField"));
        TypedValue value = resolved.source().querySchema().context().get("mode").defaultValue();
        assertEquals(TypedValue.State.VALUE, value.state());
        assertEquals("all", value.value());
        assertEquals(true, value.unknown().get("futureValue"));
    }

    private static CatalogAuthoringPublication publication(List<CatalogAuthoringPublication.Entry> entries) {
        return publication(entries, CatalogCacheState.ACTIVE);
    }

    private static CatalogAuthoringPublication publication(List<CatalogAuthoringPublication.Entry> entries,
                                                             CatalogCacheState state) {
        CatalogAuthoringPublication.SectionProjection options = new CatalogAuthoringPublication.SectionProjection(
            CatalogAuthoringPublication.Section.OPTION_SOURCES, true, state != CatalogCacheState.UNAVAILABLE, state,
            entries);
        return new CatalogAuthoringPublication(
            new CatalogBinding(1L, "1".repeat(64), "2".repeat(64)),
            new CatalogVersion(1, 3), CatalogProjectionVersion.current(), List.of(options));
    }

    private static CatalogAuthoringPublication.Entry entry(String id, String capability) {
        return entry(id, capability,
            "{\"context\":{},\"dependencies\":{},\"resource\":null,\"schemaVersion\":1}");
    }

    private static CatalogAuthoringPublication.Entry entry(String id, String capability, String querySchema) {
        String data = "{\"capability\":{\"localId\":\"" + capability
            + "\",\"ownerId\":\"owner\"},\"description\":\"Provides selectable resources.\","
            + "\"id\":\"" + id + "\",\"invalidationKey\":\"" + id
            + "\",\"pageLimit\":25,\"querySchema\":" + querySchema
            + ",\"title\":\"Resources\",\"valueType\":{"
            + "\"kind\":\"resource\",\"resourceType\":{\"localId\":\"resource\",\"ownerId\":\"owner\"}}}";
        return new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.OPTION_SOURCES,
            ContractRef.of(OWNER, CapabilityId.of(id)).canonicalText(), CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(data.getBytes(StandardCharsets.UTF_8)));
    }
}
