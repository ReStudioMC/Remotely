package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncGenericDescriptorProjectionTest {
    private static final OwnerId OWNER = new OwnerId("extension.generic");
    private static final CatalogCacheKey KEY = new CatalogCacheKey(
        new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")), 4,
        new ContentHash("a".repeat(64)), new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());

    @Test
    void productionEventsAndPropertyTargetsArePinsNotLiteralFields() {
        for (String id : List.of("event.block.break", "event.command", "player.properties", "entity.properties")) {
            CatalogCachePublication.Entry entry = ReSyncProductionDescriptorFixture.entry(id);
            ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(entry,
                ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()).orElseThrow();

            assertEquals(ReSyncGenericDescriptorProjection.Status.ACTIVE, projection.status(), id);
            assertFalse(projection.readOnly(), id);
            assertEquals(entry.data().canonicalText(), projection.canonicalData());
            assertTrue(projection.pins().stream().anyMatch(pin -> pin.direction()
                == ReSyncGenericDescriptorProjection.Direction.OUTPUT));
            assertTrue(projection.fields().stream().allMatch(field -> field.editable()
                && field.capability().equals("restudio.resync/generic-editor")));
            assertEquals(id.startsWith("event.") ? 0 : 2, projection.fields().size(), id);
            assertTrue(projection.fields().stream().noneMatch(field -> field.id().contains("target")));
        }
    }

    @Test
    void outputOnlyNonprimitiveNodeNeedsNoLiteralEditorCapability() {
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(
            Map.of("id", "player", "direction", "output", "type", named("player"),
                "editor", Map.of("ownerId", "extension.generic", "localId", "unknown-editor")))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            present("generic.node", descriptor, Map.of()), ReSyncGenericDescriptorProjection.ClientCapabilities.none())
            .orElseThrow();

        assertEquals(ReSyncGenericDescriptorProjection.Status.ACTIVE, projection.status());
        assertTrue(projection.fields().isEmpty());
        assertEquals(named("player"), projection.pin("player", false).orElseThrow().type());
    }

    @Test
    void genuineRequiredUnsupportedLiteralIsPreservedReadOnly() {
        Map<String, Object> richInput = Map.of("id", "target", "direction", "input", "requirement", "required",
            "type", named("player"), "editor", Map.of("ownerId", "extension.generic", "localId", "player-picker"),
            "presentation", Map.of("widget", "PLAYER_PICKER"));
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(richInput),
            "future", Map.of("retain", List.of("all", "values"))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            present("generic.node", descriptor, Map.of("retain", true)),
            ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()).orElseThrow();

        assertTrue(projection.readOnly());
        assertEquals(ReSyncGenericDescriptorProjection.Status.READ_ONLY, projection.status());
        assertEquals(descriptor, projection.canonicalData());
        assertEquals("pin:target", projection.fields().getFirst().id());
        assertFalse(projection.fields().getFirst().editable());
        assertEquals(Map.of("retain", true), projection.unknown());
        assertEquals(named("player"), projection.pin("target", true).orElseThrow().type());
    }

    @Test
    void requiredNonprimitiveDefaultRemainsALiteralEvenWithAGenericEditor() {
        Map<String, Object> type = named("player");
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(
            Map.of("id", "target", "direction", "input", "requirement", "defaulted", "type", type,
                "default", Map.of("state", "value", "type", type, "value", "player-id"),
                "editor", Map.of("ownerId", "extension.generic", "localId", "generic-editor")))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            present("generic.node", descriptor, Map.of()), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
            .orElseThrow();

        assertTrue(projection.readOnly());
        assertEquals(1, projection.fields().size());
        assertFalse(projection.fields().getFirst().editable());
        assertEquals(descriptor, projection.canonicalData());
    }

    private static Map<String, Object> named(String id) {
        return Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", id), "arguments", List.of());
    }

    @Test
    void projectsGenericFieldsByCapabilityAndPreservesDescriptorPayload() {
        String descriptor = descriptor();
        CatalogCachePublication.Entry entry = present("generic.node", descriptor, Map.of("future", Map.of("keep", true)));

        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(entry,
            new ReSyncGenericDescriptorProjection.ClientCapabilities(Map.of(
                "extension.generic/text-editor", ReSyncGenericDescriptorProjection.EditorKind.TEXT,
                "extension.generic/toggle-editor", ReSyncGenericDescriptorProjection.EditorKind.BOOLEAN)))
            .orElseThrow();

        assertEquals(ReSyncGenericDescriptorProjection.Status.READ_ONLY, projection.status());
        assertTrue(projection.readOnly());
        assertEquals(entry.definitionKey(), projection.definitionKey());
        assertEquals(entry.revision(), projection.revision());
        assertEquals(descriptor, projection.canonicalData());
        assertTrue(projection.fields().stream().anyMatch(field -> field.id().equals("pin:value") && field.editable()
            && field.editorKind() == ReSyncGenericDescriptorProjection.EditorKind.TEXT));
        assertTrue(projection.fields().stream().anyMatch(field -> field.id().equals("field:settings") && field.editable()
            && field.editorKind() == ReSyncGenericDescriptorProjection.EditorKind.BOOLEAN));
        assertTrue(projection.fields().stream().anyMatch(field -> field.id().equals("pin:future") && !field.editable()));
        assertEquals(Map.of("future", Map.of("keep", true)), projection.unknown());
    }

    @Test
    void retainsTypedIdentityRevisionAndTombstoneWithoutSynthesizingData() {
        CatalogCachePublication.Entry tombstone = CatalogCachePublication.Entry.tombstone(
            ContractRef.of(OWNER, new NodeId("removed.node")), 9, Map.of("futureTombstone", true));

        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(tombstone,
            ReSyncGenericDescriptorProjection.ClientCapabilities.none()).orElseThrow();

        assertEquals(ReSyncGenericDescriptorProjection.Status.TOMBSTONED, projection.status());
        assertTrue(projection.readOnly());
        assertEquals(tombstone.definitionKey(), projection.definitionKey());
        assertEquals(9, projection.revision());
        assertTrue(projection.canonicalData().isEmpty());
        assertTrue(projection.fields().isEmpty());
        assertEquals(tombstone.unknown(), projection.unknown());
    }

    @Test
    void rejectsDescriptorIdentityMismatchWithoutExposingEditableFields() {
        CatalogCachePublication.Entry entry = present("expected.node", descriptor().replace("generic.node", "wrong.node"), Map.of());

        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(entry,
            new ReSyncGenericDescriptorProjection.ClientCapabilities(Map.of(
                "extension.generic/text-editor", ReSyncGenericDescriptorProjection.EditorKind.TEXT)))
            .orElseThrow();

        assertEquals(ReSyncGenericDescriptorProjection.Status.INVALID, projection.status());
        assertTrue(projection.readOnly());
        assertTrue(projection.fields().isEmpty());
        assertFalse(projection.reason().isBlank());
    }

    @Test
    void primitiveCapabilityProjectionMapsSafeEditorIdsWithoutUnlockingRichEditors() {
        String descriptor = descriptor().replace("\"localId\":\"text-editor\"", "\"localId\":\"generic-editor\"")
            .replace("\"localId\":\"toggle-editor\"", "\"localId\":\"generic-editor\"")
            .replace("\"localId\":\"future-editor\"", "\"localId\":\"generic-editor\"");
        CatalogCachePublication.Entry entry = present("generic.node", descriptor, Map.of());

        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(entry,
            ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()).orElseThrow();

        assertEquals(ReSyncGenericDescriptorProjection.Status.ACTIVE, projection.status());
        assertTrue(projection.fields().stream().anyMatch(field -> field.id().equals("pin:value")
            && field.editorKind() == ReSyncGenericDescriptorProjection.EditorKind.TEXT && field.editable()));
        assertTrue(projection.fields().stream().anyMatch(field -> field.id().equals("field:settings")
            && field.editorKind() == ReSyncGenericDescriptorProjection.EditorKind.BOOLEAN && field.editable()));
        assertTrue(ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()
            .editor("extension.generic/resource-selector", Map.of("kind", "resource")).isEmpty());
    }

    @Test
    void exposesCorePinIdentityDirectionTypeAndDisplayLabelIndependently() {
        Map<String, Object> input = Map.of(
            "id", "amount", "displayName", "Amount Label", "direction", "input",
            "type", Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", "number")));
        Map<String, Object> output = Map.of(
            "id", "result", "displayName", "Result Label", "direction", "output",
            "type", Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", "string")));
        Map<String, Object> value = Map.of("id", "generic.node", "pins", List.of(input, output));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            present("generic.node", CanonicalJson.canonicalize(value), Map.of()),
            ReSyncGenericDescriptorProjection.ClientCapabilities.none()).orElseThrow();

        assertEquals(List.of("amount", "result"), projection.pins().stream().map(ReSyncGenericDescriptorProjection.Pin::id).toList());
        assertEquals(List.of("Amount Label", "Result Label"), projection.pins().stream()
            .map(ReSyncGenericDescriptorProjection.Pin::displayName).toList());
        assertEquals(List.of(ReSyncGenericDescriptorProjection.Direction.INPUT,
            ReSyncGenericDescriptorProjection.Direction.OUTPUT), projection.pins().stream()
            .map(ReSyncGenericDescriptorProjection.Pin::direction).toList());
        assertTrue(projection.pins().getFirst().typeExpression().contains("number"));
    }

    @Test
    void qualifiedResourceOptionsUseSelectWhileBareReferencesStayUnsupported() {
        Map<String, Object> qualified = Map.of("kind", "resource", "resourceType",
            Map.of("ownerId", "extension.generic", "localId", "scene"));
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(
            Map.of("id", "scene", "direction", "input", "type", qualified,
                "default", Map.of("state", "null", "type", qualified),
                "optionSource", Map.of("ownerId", "extension.generic", "localId", "scenes"),
                "presentation", Map.of("widget", "SEARCHABLE_LIST"),
                "editor", Map.of("ownerId", "extension.generic", "localId", "generic-editor")),
            Map.of("id", "bare", "direction", "input", "type", "resource_reference",
                "defaultValue", "unsafe", "editor",
                Map.of("ownerId", "extension.generic", "localId", "generic-editor")))));

        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            present("generic.node", descriptor, Map.of()), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
            .orElseThrow();

        assertEquals(ReSyncGenericDescriptorProjection.Status.READ_ONLY, projection.status());
        assertTrue(projection.fields().stream().filter(field -> field.id().equals("pin:scene"))
            .anyMatch(field -> field.editable()
                && field.editorKind() == ReSyncGenericDescriptorProjection.EditorKind.SELECT));
        assertTrue(projection.fields().stream().filter(field -> field.id().equals("pin:bare"))
            .anyMatch(field -> !field.editable()
                && field.editorKind() == ReSyncGenericDescriptorProjection.EditorKind.UNSUPPORTED));
        assertEquals(ContractRef.of(OWNER, InspectorFieldId.of("scenes")),
            projection.pins().getFirst().optionSource());
        assertTrue(ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()
            .editor("extension.generic/generic-editor", qualified).isEmpty());
        assertTrue(ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()
            .editor("extension.generic/generic-editor", "resource_reference").isEmpty());
    }

    @Test
    void retainsInspectorHierarchyBindingsAndRenderingMetadata() {
        Map<String, Object> type = Map.of("kind", "named", "type",
            Map.of("ownerId", "builtin", "localId", "string"), "arguments", List.of());
        Map<String, Object> defaultValue = Map.of("state", "null", "type", type);
        Map<String, Object> inspectorField = new LinkedHashMap<>();
        inspectorField.put("id", "target");
        inspectorField.put("kind", "selector");
        inspectorField.put("title", "Target");
        inspectorField.put("description", "Target field.");
        inspectorField.put("valueType", type);
        inspectorField.put("default", defaultValue);
        inspectorField.put("binding", Map.of("source", "pin", "id", "target"));
        inspectorField.put("optionSource", Map.of(
            "id", "targets", "title", "Targets", "description", "Provides target choices.",
            "valueType", type, "querySchema", emptyQuerySchema(),
            "capability", Map.of("ownerId", "other.provider", "localId", "targets"),
            "pageLimit", 25, "invalidationKey", "targets-v1"));
        inspectorField.put("query", Map.of("source", "catalog", "id", "targets"));
        inspectorField.put("readOnly", true);
        inspectorField.put("visibleWhen", Map.of("mode", "advanced"));
        inspectorField.put("editor", Map.of("ownerId", "extension.generic", "localId", "generic-editor"));
        Map<String, Object> row = Map.of("id", "target-row", "title", "Target Row",
            "visibility", Map.of("field", "mode"), "fields", List.of(inspectorField));
        Map<String, Object> section = Map.of("id", "main", "title", "Main",
            "visibleWhen", Map.of("mode", "advanced"), "rows", List.of(row));
        Map<String, Object> inspector = Map.of("id", "generic", "title", "Generic",
            "bindings", Map.of("target", "pin:target"), "sections", List.of(section));
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(),
            "inspector", inspector));

        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            present("generic.node", descriptor, Map.of()), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
            .orElseThrow();
        ReSyncGenericDescriptorProjection.Inspector projected = projection.inspector();
        ReSyncGenericDescriptorProjection.InspectorSection projectedSection = projected.sections().getFirst();
        ReSyncGenericDescriptorProjection.InspectorRow projectedRow = projectedSection.rows().getFirst();
        ReSyncGenericDescriptorProjection.InspectorField projectedField = projectedRow.fields().getFirst();

        assertTrue(projected.present());
        assertEquals("generic", projected.id());
        assertEquals(Map.of("target", "pin:target"), projected.bindings());
        assertEquals("main", projectedSection.id());
        assertEquals(Map.of("mode", "advanced"), projectedSection.visibility());
        assertEquals("target-row", projectedRow.id());
        assertEquals(Map.of("field", "mode"), projectedRow.visibility());
        assertEquals("target", projectedField.id());
        assertEquals("field:target", projectedField.path());
        assertEquals(type, projectedField.type());
        assertEquals(defaultValue, projectedField.defaultValue());
        assertEquals(Map.of("source", "pin", "id", "target"), projectedField.bindings());
        assertEquals(ContractRef.of(OWNER, InspectorFieldId.of("targets")), projectedField.optionSource());
        assertEquals(Map.of("source", "catalog", "id", "targets"), projectedField.query());
        assertEquals(Map.of("mode", "advanced"), projectedField.visibility());
        assertTrue(projectedField.readOnly());
        assertTrue(projection.readOnly());
        assertEquals(projected, ReSyncGenericWidgetCapabilities.from(projection).orElseThrow().inspector());
    }

    private static CatalogCachePublication.Entry present(String id, String descriptor, Map<String, Object> unknown) {
        return CatalogCachePublication.Entry.present(ContractRef.of(OWNER, new NodeId(id)), 7,
            CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(descriptor.getBytes(StandardCharsets.UTF_8)), unknown);
    }

    private static Map<String, Object> emptyQuerySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("schemaVersion", 1);
        schema.put("resource", null);
        schema.put("context", Map.of());
        schema.put("dependencies", Map.of());
        return schema;
    }

    private static String descriptor() {
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("id", "value");
        pin.put("direction", "input");
        pin.put("type", Map.of("kind", "text"));
        pin.put("displayName", "Value");
        pin.put("description", "The value supplied to the operation.");
        pin.put("editor", Map.of("ownerId", "extension.generic", "localId", "text-editor"));
        Map<String, Object> futurePin = new LinkedHashMap<>(pin);
        futurePin.put("id", "future");
        futurePin.put("editor", Map.of("ownerId", "extension.generic", "localId", "future-editor"));
        Map<String, Object> inspectorField = Map.of(
            "id", "settings", "kind", "scalar", "title", "Settings", "description", "Settings value.",
            "valueType", Map.of("kind", "boolean"),
            "editor", Map.of("ownerId", "extension.generic", "localId", "toggle-editor"));
        Map<String, Object> row = Map.of("id", "row", "title", "Settings", "description", "Settings.",
            "fields", List.of(inspectorField));
        Map<String, Object> section = Map.of("id", "section", "title", "Settings", "description", "Settings.",
            "rows", List.of(row));
        Map<String, Object> inspector = Map.of("intent", "generic", "id", "generic", "title", "Generic",
            "description", "Generic inspector.", "sections", List.of(section));
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("kind", "node");
        descriptor.put("id", "generic.node");
        descriptor.put("schemaVersion", 1);
        descriptor.put("lifecycle", "active");
        descriptor.put("domain", "flow");
        descriptor.put("family", "generic");
        descriptor.put("displayName", "Generic Node");
        descriptor.put("description", "A generic node with capability-driven fields.");
        descriptor.put("pins", List.of(pin, futurePin));
        descriptor.put("inspector", inspector);
        descriptor.put("unknownDescriptor", Map.of("provider", "future.extension"));
        return CanonicalJson.canonicalize(descriptor);
    }
}
