package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import redxax.oxy.remotely.flow.registry.NodeDefinition;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReSyncGenericWidgetCapabilitiesTest {
    private static final OwnerId OWNER = new OwnerId("extension.generic");
    @Test
    void mapsSafeDescriptorPinsWithoutRegisteringLegacyDefinitions() {
        CatalogCachePublication.Entry entry = entry(descriptor(), CatalogCacheState.ACTIVE);
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(entry,
            new ReSyncGenericDescriptorProjection.ClientCapabilities(Map.of(
                "extension.generic/text-editor", ReSyncGenericDescriptorProjection.EditorKind.TEXT))).orElseThrow();

        ReSyncGenericWidgetCapabilities.WidgetDefinition widget = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow();

        assertEquals("extension.generic", widget.definition().getOwner());
        assertEquals("generic.node", widget.definition().getId());
        assertEquals(2, widget.definition().getInputs().size());
        assertEquals(1, widget.definition().getOutputs().size());
        assertEquals(ContractRef.of(OWNER, new NodeId("generic.node")).canonicalText(), widget.identity());
        assertFalse(widget.readOnly());
    }

    @Test
    void mapsOnlyExactRepeatableGroupMembershipAndOrdering() {
        Map<String, Object> string = named("builtin", "string");
        Map<String, Object> execution = named("builtin", "execution");
        Map<String, Object> intent = Map.of("enabled", true, "minimum", 1, "maximum", 8,
            "ordered", true, "groupId", "cases");
        Map<String, Object> input = Map.of("id", "choice", "displayName", "Choice", "direction", "input",
            "type", string, "repeatable", intent);
        Map<String, Object> output = Map.of("id", "matched", "displayName", "Matched", "direction", "output",
            "type", execution, "repeatable", intent);
        Map<String, Object> group = Map.of("kind", "repeatable", "id", "cases", "title", "Case",
            "description", "Cases", "elementType", Map.of("kind", "tuple", "elements", List.of(string, execution)),
            "minimum", 1, "maximum", 8, "ordered", true, "members", List.of(
                Map.of("pinId", "choice", "direction", "input", "type", string),
                Map.of("pinId", "matched", "direction", "output", "type", execution)));
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node",
            "pins", List.of(input, output), "repeatables", List.of(group)));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
            .orElseThrow();

        NodeDefinition definition = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow().definition();

        for (NodeDefinition.PinDefinition pin : List.of(definition.getInputs().getFirst(),
            definition.getOutputs().getFirst())) {
            assertEquals("cases", pin.getRepeatable().getGroupId());
            assertEquals(1, pin.getRepeatable().getMinItems());
            assertEquals(8, pin.getRepeatable().getMaxItems());
            assertEquals("Case", pin.getRepeatable().getItemLabel());
            assertTrue(pin.getRepeatable().isOrdered());
        }

        Map<String, Object> mismatched = new LinkedHashMap<>(group);
        mismatched.put("members", List.of(Map.of("pinId", "choice", "direction", "output", "type", string)));
        String malformed = CanonicalJson.canonicalize(Map.of("id", "generic.node",
            "pins", List.of(input, output), "repeatables", List.of(mismatched)));
        ReSyncGenericDescriptorProjection.Projection malformedProjection = ReSyncGenericDescriptorProjection.open(
            entry(malformed, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
            .orElseThrow();
        assertTrue(ReSyncGenericWidgetCapabilities.from(malformedProjection).isEmpty());
    }

    @Test
    void opaqueAndMalformedDescriptorsDoNotBecomeWidgets() {
        CatalogCachePublication.Entry opaque = CatalogCachePublication.Entry.present(
            ContractRef.of(OWNER, new NodeId("opaque.node")), 8, CatalogCacheState.UNAVAILABLE, Set.of(), true,
            CatalogCacheOpaque.of("{\"future\":true}".getBytes(StandardCharsets.UTF_8)), Map.of("future", true));
        CatalogCachePublication.Entry malformed = entry(CanonicalJson.canonicalize(Map.of(
            "id", "generic.node", "pins", List.of(Map.of("id", "value", "direction", "input")))), CatalogCacheState.ACTIVE);

        assertTrue(ReSyncGenericWidgetCapabilities.from(ReSyncGenericDescriptorProjection.open(opaque,
            ReSyncGenericDescriptorProjection.ClientCapabilities.none()).orElseThrow()).isEmpty());
        assertTrue(ReSyncGenericWidgetCapabilities.from(ReSyncGenericDescriptorProjection.open(malformed,
            ReSyncGenericDescriptorProjection.ClientCapabilities.none()).orElseThrow()).isEmpty());
    }

    @Test
    void productionPropertiesRetainSelectorsTypedDefaultsConditionsAndWireTargets() {
        for (String id : List.of("player.properties", "entity.properties", "inventory.properties")) {
            boolean entity = id.startsWith("entity.");
            String prefix = entity ? "input_" : "";
            ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
                ReSyncProductionDescriptorFixture.entry(id), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
                .orElseThrow();
            ReSyncGenericWidgetCapabilities.Conversion conversion = ReSyncGenericWidgetCapabilities.convert(projection);
            ReSyncGenericWidgetCapabilities.WidgetDefinition widget = conversion.widget()
                .orElseThrow(() -> new AssertionError(id + ": " + conversion.reason()));
            NodeDefinition definition = widget.definition();
            NodeDefinition.PinDefinition target = definition.getInputs().stream()
                .filter(pin -> pin.getName().equals(prefix + "target")).findFirst().orElseThrow();
            NodeDefinition.PinDefinition action = definition.getInputs().stream()
                .filter(pin -> pin.getName().equals(prefix + "action")).findFirst().orElseThrow();
            NodeDefinition.PinDefinition property = definition.getInputs().stream()
                .filter(pin -> pin.getName().equals(prefix + "property")).findFirst().orElseThrow();

            assertFalse(widget.readOnly(), id);
            assertEquals(id.substring(0, id.indexOf('.')), target.getTypeRef().toString());
            assertEquals(null, target.getWidgetType());
            assertFalse(target.isOptional());
            assertEquals(NodeDefinition.WidgetType.DROPDOWN, action.getWidgetType());
            assertEquals(List.of("get", "has"), action.getOptions());
            assertEquals("get", action.getDefaultValue());
            assertEquals(NodeDefinition.WidgetType.SEARCHABLE_LIST, property.getWidgetType());
            assertTrue(property.getOptions().contains(entity ? "passengers" : id.startsWith("player.") ? "location" : "items"));
            for (NodeDefinition.PinDefinition output : definition.getOutputs()) {
                assertTrue(output.getTypeRef().isResolved());
                assertFalse(output.getVisibleWhen().isEmpty());
                assertTrue(output.getVisibleWhen().containsKey(prefix + "action"));
            }
        }
    }

    @Test
    void canonicalTypedDefaultsRetainBooleanNumberAndEmptyStringValues() {
        for (Object literal : List.of(false, 12, "")) {
            String typeId = literal instanceof Boolean ? "boolean" : literal instanceof Number ? "number" : "string";
            Map<String, Object> type = Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", typeId),
                "arguments", List.of());
            String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(
                Map.of("id", "value", "direction", "input", "type", type,
                    "default", Map.of("state", "value", "type", type, "value", literal),
                    "editor", Map.of("ownerId", "extension.generic", "localId", "generic-editor")))));
            ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
                entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
                .orElseThrow();
            NodeDefinition definition = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow().definition();

            assertEquals(literal.toString(), definition.getInputs().getFirst().getDefaultValue());
            assertEquals("value", definition.getInputs().getFirst().getTypedDefault().getState());
            assertEquals(CanonicalJson.canonicalize(literal),
                CanonicalJson.canonicalize(definition.getInputs().getFirst().getTypedDefault().getMaterial()));
        }
    }

    @Test
    void canonicalTypedNullDefaultRetainsItsStateWithoutMaterial() {
        Map<String, Object> type = named("builtin", "string");
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(Map.of(
            "id", "value", "direction", "input", "type", type,
            "default", Map.of("state", "null", "type", type),
            "editor", Map.of("ownerId", "extension.generic", "localId", "generic-editor")))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
            .orElseThrow();

        NodeDefinition.PinDefinition definition = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow()
            .definition().getInputs().getFirst();

        assertEquals("null", definition.getDefaultValue());
        assertEquals("null", definition.getTypedDefault().getState());
        assertTrue(definition.getTypedDefault().isNull());
        assertNull(definition.getTypedDefault().getMaterial());
    }

    @Test
    void absentAndUnionOpaqueDefaultsRemainExactAcrossWidgetProjection() {
        Map<String, Object> string = named("builtin", "string");
        Map<String, Object> opaque = Map.of("kind", "opaque", "raw", true,
            "type", Map.of("ownerId", "extension.generic", "localId", "future"));
        Map<String, Object> union = Map.of("kind", "union", "variants", List.of(
            Map.of("variantId", "future", "type", opaque),
            Map.of("variantId", "text", "type", string)));
        Map<String, Object> scene = resource("extension.generic", "scene");
        Map<String, Object> resourceUnion = Map.of("kind", "union", "variants", List.of(
            Map.of("variantId", "scene", "type", scene),
            Map.of("variantId", "text", "type", string)));
        Map<String, Object> absent = new LinkedHashMap<>();
        absent.put("state", "absent");
        absent.put("type", string);
        absent.put("absentFuture", Map.of("nested", List.of("kept")));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("state", "opaque");
        raw.put("type", union);
        raw.put("variantId", "future");
        raw.put("value", Map.of("raw", List.of("exact", true)));
        raw.put("opaqueFuture", Map.of("nested", List.of("kept")));
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("state", "locator");
        locator.put("type", resourceUnion);
        locator.put("variantId", "scene");
        locator.put("locator", Map.of("serverId", "11111111-1111-4111-8111-111111111111",
            "type", "scene", "id", "spawn"));
        locator.put("locatorFuture", Map.of("nested", List.of("kept")));
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(
            Map.of("id", "missing", "direction", "input", "type", string, "default", absent),
            Map.of("id", "raw", "direction", "input", "type", union, "default", raw),
            Map.of("id", "scene", "direction", "input", "type", resourceUnion, "default", locator))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.none())
            .orElseThrow();

        NodeDefinition definition = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow().definition();
        NodeDefinition.PinDefinition missing = definition.getInputs().stream()
            .filter(pin -> pin.getName().equals("missing")).findFirst().orElseThrow();
        NodeDefinition.PinDefinition rawPin = definition.getInputs().stream()
            .filter(pin -> pin.getName().equals("raw")).findFirst().orElseThrow();
        NodeDefinition.PinDefinition scenePin = definition.getInputs().stream()
            .filter(pin -> pin.getName().equals("scene")).findFirst().orElseThrow();
        NodeDefinition.TypedDefault missingDefault = missing.getTypedDefault();
        NodeDefinition.TypedDefault rawDefault = rawPin.getTypedDefault();

        assertNull(missing.getDefaultValue());
        assertTrue(missingDefault.isAbsent());
        assertEquals("absent", missingDefault.getState());
        assertEquals(Map.of("nested", List.of("kept")), missingDefault.getUnknown().get("absentFuture"));
        assertNull(rawPin.getDefaultValue());
        assertEquals("union<variant<future,opaque<extension.generic:future>>,variant<text,string>>",
            rawDefault.getTypeRef().toString());
        assertTrue(rawDefault.isOpaque());
        assertEquals("future", rawDefault.getVariantId());
        assertEquals(Map.of("raw", List.of("exact", true)), rawDefault.getMaterial());
        assertEquals(Map.of("nested", List.of("kept")), rawDefault.getUnknown().get("opaqueFuture"));
        assertEquals("spawn", scenePin.getDefaultValue());
        assertEquals("locator", scenePin.getTypedDefault().getState());
        assertEquals("scene", scenePin.getTypedDefault().getVariantId());
        assertEquals(Map.of("nested", List.of("kept")),
            scenePin.getTypedDefault().getUnknown().get("locatorFuture"));
        assertThrows(UnsupportedOperationException.class, () -> rawDefault.getUnknown().put("changed", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) rawDefault.getUnknown().get("opaqueFuture");
        assertThrows(UnsupportedOperationException.class, () -> nested.put("changed", true));
    }

    @Test
    void optionSourceAndLocatorDefaultSurviveTypedWidgetProjection() {
        Map<String, Object> type = resource("extension.generic", "scene");
        Map<String, Object> locator = Map.of(
            "serverId", "11111111-1111-4111-8111-111111111111",
            "type", "scene",
            "id", "spawn");
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("id", "scene");
        pin.put("direction", "input");
        pin.put("type", type);
        pin.put("optionSource", Map.of("ownerId", "extension.generic", "localId", "scenes"));
        pin.put("default", Map.of("state", "locator", "type", type, "locator", locator));
        pin.put("editor", Map.of("ownerId", "extension.generic", "localId", "resource-selector"));
        pin.put("presentation", Map.of("widget", "searchable_list"));
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(pin)));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), new ReSyncGenericDescriptorProjection.ClientCapabilities(Map.of(
                "extension.generic/resource-selector", ReSyncGenericDescriptorProjection.EditorKind.SELECT))).orElseThrow();

        ReSyncGenericWidgetCapabilities.WidgetDefinition widget = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow();
        NodeDefinition.PinDefinition definition = widget.definition().getInputs().getFirst();
        NodeDefinition.TypedDefault typedDefault = definition.getTypedDefault();

        assertFalse(widget.readOnly());
        assertEquals("resource_reference<extension.generic:scene>", definition.getTypeRef().toString());
        assertNull(definition.getOptionsSource());
        assertEquals(ContractRef.of(OWNER, InspectorFieldId.of("scenes")), definition.getOptionSourceRef());
        assertEquals("spawn", definition.getDefaultValue());
        assertEquals("locator", typedDefault.getState());
        assertEquals(definition.getTypeRef(), typedDefault.getTypeRef());
        assertEquals(locator, typedDefault.getMaterial());
        assertTrue(typedDefault.isLocator());
    }

    @Test
    void bareResourceReferenceFailsClosedWithoutAnyOrStringFallback() {
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(Map.of(
            "id", "resource", "direction", "input", "type", "resource_reference", "defaultValue", "unsafe",
            "editor", Map.of("ownerId", "extension.generic", "localId", "generic-editor")))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
            .orElseThrow();

        ReSyncGenericWidgetCapabilities.Conversion converted = ReSyncGenericWidgetCapabilities.convert(projection);

        assertTrue(projection.readOnly());
        assertTrue(converted.widget().isEmpty());
        assertEquals("pin_type_unresolved:resource", converted.reason());
        assertFalse(FlowTypeRef.parse("resource_reference").equals(FlowTypeRef.simple("any")));
        assertFalse(FlowTypeRef.parse("resource_reference").equals(FlowTypeRef.simple("string")));
    }

    @Test
    void readOnlyDescriptorRetainsOwnerQualifiedIdentity() {
        CatalogCachePublication.Entry entry = entry(descriptor(), CatalogCacheState.READ_ONLY);
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(entry,
            ReSyncGenericDescriptorProjection.ClientCapabilities.none()).orElseThrow();
        ReSyncGenericWidgetCapabilities.WidgetDefinition widget = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow();

        assertTrue(widget.readOnly());
        assertFalse(widget.identity().equals("generic.node"));
        assertEquals(ContractRef.of(OWNER, new NodeId("generic.node")).canonicalText(), widget.identity());
    }

    @Test
    void rejectsDuplicatePinIdsAndMalformedConstraints() {
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("id", "value");
        pin.put("direction", "input");
        pin.put("type", "string");
        String duplicate = CanonicalJson.canonicalize(Map.of(
            "id", "generic.node", "pins", List.of(pin, pin)));
        String malformedConstraints = CanonicalJson.canonicalize(Map.of(
            "id", "generic.node", "pins", List.of(Map.of(
                "id", "value", "direction", "input", "type", "number",
                "constraints", Map.of("min", 5, "max", 1)))));

        assertTrue(ReSyncGenericWidgetCapabilities.from(ReSyncGenericDescriptorProjection.open(
            entry(duplicate, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.none())
            .orElseThrow()).isEmpty());
        assertTrue(ReSyncGenericWidgetCapabilities.from(ReSyncGenericDescriptorProjection.open(
            entry(malformedConstraints, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.none())
            .orElseThrow()).isEmpty());
    }

    @Test
    void rejectsUnresolvedNestedTypeArguments() {
        String descriptor = CanonicalJson.canonicalize(Map.of(
            "id", "generic.node", "pins", List.of(Map.of(
                "id", "value", "direction", "input", "type", "list<extension:future>"))));

        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.none())
            .orElseThrow();
        ReSyncGenericWidgetCapabilities.Conversion conversion = ReSyncGenericWidgetCapabilities.convert(projection);

        assertTrue(conversion.widget().isEmpty());
        assertEquals("pin_type_unresolved:value", conversion.reason());
        assertEquals(conversion.widget(), ReSyncGenericWidgetCapabilities.from(projection));
    }

    @Test
    void resourceIdentityIsNominalAndKeepsItsOwnerThroughNestedTypes() {
        Map<String, Object> gui = resource("builtin", "gui");
        Map<String, Object> custom = resource("extension.generic", "gui");
        Map<String, Object> types = Map.of(
            "resource_reference<builtin:gui>", gui,
            "resource_reference<builtin:string>", resource("builtin", "string"),
            "resource_reference<builtin:command>", resource("builtin", "command"),
            "resource_reference<extension.generic:gui>", custom,
            "list<resource_reference<extension.generic:gui>>", Map.of("kind", "list", "element", custom),
            "result<resource_reference<builtin:gui>,any>", Map.of("kind", "result", "success", gui, "failure", named("builtin", "any")));
        for (Map.Entry<String, Object> test : types.entrySet()) {
            ReSyncGenericDescriptorProjection.Projection projection = projection(List.of(
                Map.of("id", "value", "direction", "output", "type", test.getValue())), Map.of());
            ReSyncGenericWidgetCapabilities.Conversion converted = ReSyncGenericWidgetCapabilities.convert(projection);
            assertTrue(converted.widget().isPresent(), converted.reason());
            NodeDefinition.PinDefinition pin = converted.widget().orElseThrow().definition().getOutputs().getFirst();
            assertEquals(test.getKey(), pin.getTypeRef().toString());
            assertTrue(pin.getTypeRef().isResolved());
            assertTrue(projection.fields().isEmpty());
            assertFalse(converted.widget().orElseThrow().readOnly());
        }
        FlowTypeRef builtin = FlowTypeRef.parse("resource_reference<builtin:gui>");
        FlowTypeRef extension = FlowTypeRef.parse("resource_reference<extension.generic:gui>");
        assertTrue(builtin.isAssignableFrom(FlowTypeRef.parse(builtin.toString())));
        assertFalse(builtin.isAssignableFrom(extension));
        assertFalse(extension.isAssignableFrom(builtin));
        assertFalse(builtin.isAssignableFrom(FlowTypeRef.simple("string")));
        for (String invalid : List.of("resource_reference<bad_owner:gui>", "resource_reference<builtin:>",
            "resource_reference<builtin:gui:extra>", "resource_reference<builtin:gui<string>>", "resource_reference<unknown>")) {
            assertFalse(FlowTypeRef.parse(invalid).isResolved(), invalid);
        }
        ReSyncGenericDescriptorProjection.Projection ordinary = projection(List.of(
            Map.of("id", "value", "direction", "output", "type", named("extension.generic", "gui"))), Map.of());
        assertEquals("pin_type_unresolved:value", ReSyncGenericWidgetCapabilities.convert(ordinary).reason());
    }

    @Test
    void genericVariablesRemainWireTypesWithoutBecomingLiteralEditors() {
        Map<String, Object> variable = named("type", "t");
        Map<String, Object> list = Map.of("kind", "list", "element", variable);
        ReSyncGenericDescriptorProjection.Projection projection = projection(List.of(
            Map.of("id", "list", "direction", "input", "type", list),
            Map.of("id", "value", "direction", "input", "type", variable),
            Map.of("id", "output_list", "direction", "output", "type", list)), Map.of());
        ReSyncGenericWidgetCapabilities.Conversion converted = ReSyncGenericWidgetCapabilities.convert(projection);
        assertTrue(converted.widget().isPresent(), converted.reason());
        NodeDefinition definition = converted.widget().orElseThrow().definition();
        NodeDefinition.PinDefinition value = definition.getInputs().get(1);
        assertTrue(projection.fields().isEmpty());
        assertFalse(converted.widget().orElseThrow().readOnly());
        assertEquals("type:t", value.getTypeRef().toString());
        assertTrue(value.getTypeRef().isTypeVariable());
        assertTrue(value.getTypeRef().isResolved());
        assertEquals("type:t", value.getDataType().getId());
        assertNotEquals(FlowDataType.ANY, value.getDataType());
        assertFalse(FlowDataType.fromString("type:t").isResolved());
        assertNull(value.getWidgetType());
        assertEquals("list<type:t>", definition.getInputs().getFirst().getTypeRef().toString());
        assertEquals("list<type:t>", definition.getOutputs().getFirst().getTypeRef().toString());
        assertTrue(value.getTypeRef().isAssignableFrom(FlowTypeRef.simple("number")));

        ReSyncGenericDescriptorProjection.Projection literal = projection(List.of(Map.of(
            "id", "value", "direction", "input", "type", variable,
            "presentation", Map.of("widget", "TEXT"),
            "editor", Map.of("ownerId", "extension.generic", "localId", "generic-editor"))), Map.of());
        assertTrue(literal.readOnly());
        assertFalse(literal.fields().getFirst().editable());
        assertTrue(ReSyncGenericWidgetCapabilities.convert(literal).widget().orElseThrow().readOnly());
    }

    @Test
    void zeroPinDefinitionsPreserveAuthoredVisibilityWithoutInventingEndpoints() {
        for (boolean hidden : List.of(false, true)) {
            ReSyncGenericDescriptorProjection.Projection projection = projection(List.of(),
                Map.of("authoredSource", Map.of("hidden", hidden, "hiddenReason", hidden ? "function-runtime" : "")));
            ReSyncGenericWidgetCapabilities.Conversion converted = ReSyncGenericWidgetCapabilities.convert(projection);
            assertTrue(converted.widget().isPresent(), converted.reason());
            NodeDefinition definition = converted.widget().orElseThrow().definition();
            assertEquals(hidden, definition.isHidden());
            assertTrue(definition.getInputs().isEmpty());
            assertTrue(definition.getOutputs().isEmpty());
            assertFalse(converted.widget().orElseThrow().readOnly());
        }
    }

    @Test
    void itemStackPreservesBuiltinIdentityAndItemAssignability() {
        assertSame(FlowDataType.ITEMSTACK, FlowDataType.fromString("itemstack"));
        assertNotEquals(FlowDataType.ITEM, FlowDataType.ITEMSTACK);
        assertTrue(FlowDataType.ITEM.isAssignableFrom(FlowDataType.ITEMSTACK));
        assertTrue(FlowDataType.MATERIAL.isAssignableFrom(FlowDataType.ITEMSTACK));
        assertFalse(FlowDataType.ITEMSTACK.isAssignableFrom(FlowDataType.ITEM));
        assertTrue(FlowTypeRef.parse("list<itemstack>").isResolved());

        Map<String, Object> type = Map.of("kind", "named", "type", Map.of("ownerId", "extension.generic", "localId", "itemstack"),
            "arguments", List.of());
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", List.of(
            Map.of("id", "stack", "direction", "output", "type", type))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
            .orElseThrow();

        assertEquals("pin_type_unresolved:stack", ReSyncGenericWidgetCapabilities.convert(projection).reason());
    }

    @Test
    void executionTypeRequiresTheBuiltinOwner() {
        String descriptor = CanonicalJson.canonicalize(Map.of(
            "id", "generic.node", "pins", List.of(Map.of(
                "id", "flow", "direction", "input", "type", Map.of(
                    "kind", "named", "type", Map.of("ownerId", "extension.generic", "localId", "execution"),
                    "arguments", List.of())))));

        assertTrue(ReSyncGenericWidgetCapabilities.from(ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.none())
            .orElseThrow()).isEmpty());
    }

    @Test
    void primitiveGenericEditorProducesEditableWidgetDefinition() {
        String descriptor = CanonicalJson.canonicalize(Map.of(
            "id", "generic.node", "pins", List.of(Map.of(
                "id", "value", "direction", "input", "type", Map.of("kind", "text"),
                "editor", Map.of("ownerId", "extension.generic", "localId", "generic-editor")))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.primitive())
            .orElseThrow();

        ReSyncGenericWidgetCapabilities.WidgetDefinition widget = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow();

        assertFalse(widget.readOnly());
        assertEquals(NodeDefinition.WidgetType.TEXT, widget.definition().getInputs().getFirst().getWidgetType());
    }

    @Test
    void preservesCorePinIdsSeparatelyFromDisplayLabels() {
        String descriptor = CanonicalJson.canonicalize(Map.of(
            "id", "generic.node", "pins", List.of(
                Map.of("id", "amount", "displayName", "Amount Label", "direction", "input", "type", "number"),
                Map.of("id", "result", "displayName", "Result Label", "direction", "output", "type", "string"))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(
            entry(descriptor, CatalogCacheState.ACTIVE), ReSyncGenericDescriptorProjection.ClientCapabilities.none())
            .orElseThrow();

        ReSyncGenericWidgetCapabilities.WidgetDefinition widget = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow();

        NodeDefinition.PinDefinition input = widget.definition().getInputs().getFirst();
        NodeDefinition.PinDefinition output = widget.definition().getOutputs().getFirst();
        assertEquals("amount", input.getId().value());
        assertEquals("amount", input.getName());
        assertEquals("Amount Label", input.getDisplayName());
        assertEquals(NodeDefinition.PinDirection.INPUT, input.getDirection());
        assertEquals("number", input.getTypeRef().getTypeId());
        assertEquals("result", output.getId().value());
        assertEquals("Result Label", output.getDisplayName());
        assertEquals(NodeDefinition.PinDirection.OUTPUT, output.getDirection());
        assertEquals("string", output.getTypeRef().getTypeId());
    }

    @Test
    void preservesExactNestedTypesAndRejectsMalformedStructures() {
        Map<String, Object> nested = Map.of("kind", "map",
            "key", Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", "string"),
                "arguments", List.of()),
            "value", Map.of("kind", "list", "element", Map.of("kind", "optional",
                "element", Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", "number"),
                    "arguments", List.of()))));
        Map<String, Object> union = Map.of("kind", "union", "variants", List.of(
            Map.of("variantId", "right", "type", Map.of("kind", "list", "element", Map.of("kind", "number"))),
            Map.of("variantId", "left", "type", Map.of("kind", "text"))));

        assertEquals("map<string,list<optional<number>>>",
            ReSyncGenericWidgetCapabilities.canonicalTypeExpression(nested));
        assertEquals("union<variant<left,string>,variant<right,list<number>>>",
            ReSyncGenericWidgetCapabilities.canonicalTypeExpression(union));
        assertEquals("", ReSyncGenericWidgetCapabilities.canonicalTypeExpression(Map.of("kind", "map",
            "key", Map.of("kind", "string"))));
        assertEquals("", ReSyncGenericWidgetCapabilities.canonicalTypeExpression(Map.of("kind", "opaque",
            "type", Map.of("localId", "future"))));
    }

    private static ReSyncGenericDescriptorProjection.Projection projection(List<Map<String, Object>> pins, Map<String, Object> metadata) {
        String descriptor = CanonicalJson.canonicalize(Map.of("id", "generic.node", "pins", pins, "metadata", metadata));
        return ReSyncGenericDescriptorProjection.open(entry(descriptor, CatalogCacheState.ACTIVE),
            ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()).orElseThrow();
    }

    private static Map<String, Object> named(String owner, String id) {
        return Map.of("kind", "named", "type", Map.of("ownerId", owner, "localId", id), "arguments", List.of());
    }

    private static Map<String, Object> resource(String owner, String id) {
        return Map.of("kind", "resource", "resourceType", Map.of("ownerId", owner, "localId", id));
    }

    private static CatalogCachePublication.Entry entry(String descriptor, CatalogCacheState state) {
        return CatalogCachePublication.Entry.present(ContractRef.of(OWNER, new NodeId("generic.node")), 7,
            state, Set.of(), false, CatalogCacheOpaque.of(descriptor.getBytes(StandardCharsets.UTF_8)), Map.of());
    }

    private static String descriptor() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", "value");
        input.put("direction", "input");
        input.put("type", Map.of("kind", "text"));
        input.put("description", "The value supplied to the operation.");
        input.put("editor", Map.of("ownerId", "extension.generic", "localId", "text-editor"));
        Map<String, Object> flowInput = new LinkedHashMap<>();
        flowInput.put("id", "flow");
        flowInput.put("direction", "input");
        flowInput.put("type", "execution");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", "result");
        output.put("direction", "output");
        output.put("type", Map.of("kind", "text"));
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("id", "generic.node");
        descriptor.put("displayName", "Generic Node");
        descriptor.put("description", "A generic operation.");
        descriptor.put("category", "utility");
        descriptor.put("pins", List.of(input, flowInput, output));
        return CanonicalJson.canonicalize(descriptor);
    }
}
