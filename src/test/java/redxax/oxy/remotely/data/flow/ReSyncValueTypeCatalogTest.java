package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogAuthoringPublicationCodec;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncValueTypeCatalogTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(7L, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @Test
    void projectsActiveCanonicalTypesAndFencesSourceKeyAndRevision() throws Exception {
        ReSyncFlowClient client = client(publication(CatalogCacheState.ACTIVE, false));
        try {
            ReSyncValueTypeCatalog.Snapshot snapshot = ReSyncValueTypeCatalog.active(client);
            assertNotNull(snapshot);
            assertEquals(List.of("string", "sample.plugin:widget"), snapshot.dataTypes().stream()
                .map(FlowDataType::getId).toList());
            assertEquals(List.of("String", "Widget"), snapshot.dataTypes().stream()
                .map(FlowDataType::getDisplayName).toList());
            assertTrue(ReSyncValueTypeCatalog.current(client, snapshot));

            ReSyncValueTypeCatalog.Snapshot staleKey = new ReSyncValueTypeCatalog.Snapshot(client,
                snapshot.connectionGeneration(), new CatalogCacheKey(SERVER,
                new CatalogBinding(8L, new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64))),
                CatalogProjectionVersion.current()), snapshot.revision(), snapshot.authoringChecksum(),
                snapshot.dataTypes());
            ReSyncValueTypeCatalog.Snapshot staleRevision = new ReSyncValueTypeCatalog.Snapshot(client,
                snapshot.connectionGeneration(), snapshot.key(), snapshot.revision() + 1L,
                snapshot.authoringChecksum(), snapshot.dataTypes());
            assertFalse(ReSyncValueTypeCatalog.current(client, staleKey));
            assertFalse(ReSyncValueTypeCatalog.current(client, staleRevision));

            ReSyncFlowClient replacement = new ReSyncFlowClient(SERVER.canonicalText(), new ScriptedReSyncTransport(),
                null, ReSyncCatalogPublicationCache.deferred());
            try {
                assertFalse(ReSyncValueTypeCatalog.current(replacement, snapshot));
            } finally {
                replacement.shutdown();
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    void acceptsSelectableReadOnlyTypesAndRejectsOpaqueUnavailableTypes() throws Exception {
        ReSyncFlowClient readOnly = client(publication(CatalogCacheState.READ_ONLY, false));
        ReSyncFlowClient opaque = client(publication(CatalogCacheState.UNAVAILABLE, true));
        try {
            ReSyncValueTypeCatalog.Snapshot snapshot = ReSyncValueTypeCatalog.active(readOnly);
            assertNotNull(snapshot);
            assertEquals(List.of("string", "sample.plugin:widget"), snapshot.dataTypes().stream()
                .map(FlowDataType::getId).toList());
            assertNull(ReSyncValueTypeCatalog.active(opaque));
        } finally {
            readOnly.shutdown();
            opaque.shutdown();
        }
    }

    private static ReSyncFlowClient client(CatalogCachePublication publication) throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), new ScriptedReSyncTransport(), null,
            ReSyncCatalogPublicationCache.deferred());
        CatalogCachePublicationCodec publicationCodec = new CatalogCachePublicationCodec();
        client.catalogPublicationProjection().acknowledgeActiveKey(publication.key());
        assertTrue(client.catalogPublicationProjection().apply(publication,
            publicationCodec.encodeBytes(publication)));
        CatalogAuthoringPublication authoring = publication.authoringPublication();
        assertTrue(client.catalogAuthoringProjection().apply(publication.key(), publication.revision(), authoring,
            new CatalogAuthoringPublicationCodec().encodeBytes(authoring)));
        Field authority = ReSyncFlowClient.class.getDeclaredField("catalogAuthority");
        authority.setAccessible(true);
        authority.set(client, ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION);
        return client;
    }

    private static CatalogCachePublication publication(CatalogCacheState state, boolean opaque) {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
        List<CatalogAuthoringPublication.Entry> types = opaque
            ? List.of(typeEntry("builtin", "string", "String", CatalogCacheState.UNAVAILABLE, true))
            : List.of(typeEntry("builtin", "string", "String", state, false),
                typeEntry("sample.plugin", "widget", "Widget", state, false));
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                true, state != CatalogCacheState.UNAVAILABLE, state, types),
            section(CatalogAuthoringPublication.Section.EDITORS),
            section(CatalogAuthoringPublication.Section.PREVIEWS),
            section(CatalogAuthoringPublication.Section.CAPABILITIES));
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), sections, Set.of());
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, BINDING, 3L, List.of(), authoring,
            Map.of());
    }

    private static CatalogAuthoringPublication.SectionProjection section(
        CatalogAuthoringPublication.Section section) {
        return new CatalogAuthoringPublication.SectionProjection(section, true, true, CatalogCacheState.ACTIVE,
            List.of());
    }

    private static CatalogAuthoringPublication.Entry typeEntry(String owner, String id, String displayName,
                                                                CatalogCacheState state, boolean opaque) {
        ContractRef<CapabilityId> reference = ContractRef.of(new OwnerId(owner), new CapabilityId(id));
        Map<String, Object> typeReference = Map.of("ownerId", owner, "localId", id);
        Map<String, Object> descriptor = Map.ofEntries(
            Map.entry("kind", "type"),
            Map.entry("id", typeReference),
            Map.entry("displayName", displayName),
            Map.entry("expression", Map.of("kind", "named", "type", typeReference, "arguments", List.of())),
            Map.entry("literalSchema", Map.of()),
            Map.entry("storageCodec", typeReference),
            Map.entry("networkCodec", typeReference),
            Map.entry("editor", Map.of("ownerId", "builtin", "localId", "generic-editor")),
            Map.entry("validators", List.of()),
            Map.entry("transportable", true),
            Map.entry("persistable", true));
        return new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.TYPES,
            reference.canonicalText(), state, Set.of(), Set.of(CatalogAuthoringPublication.Section.TYPES), opaque,
            CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(descriptor)));
    }
}
