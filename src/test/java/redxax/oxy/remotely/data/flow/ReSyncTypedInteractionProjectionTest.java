package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncTypedInteractionProjectionTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final CatalogProjectionVersion VERSION = CatalogProjectionVersion.current();
    private static final ContentHash BINDING_HASH = new ContentHash("f".repeat(64));

    @Test
    void acknowledgedProductionFamiliesPreservePaletteVisibilityAndExactEndpointTypes() {
        ReSyncTypedInteractionProjection interaction = ReSyncProductionDescriptorFixture.interaction();
        for (String id : List.of("event.block.break", "event.command", "player.properties", "entity.properties",
            "inventory.properties", "world_get_all", "world_set_weather")) {
            ContractRef<NodeId> identity = ReSyncProductionDescriptorFixture.entry(id).definitionKey();
            assertFalse(interaction.descriptor(identity).orElseThrow().readOnly(),
                () -> ReSyncProductionDescriptorFixture.rejectionReason(interaction, id));
            assertEquals(!id.equals("world_set_weather"), interaction.palette(false).definition(id).isPresent(),
                () -> ReSyncProductionDescriptorFixture.rejectionReason(interaction, id));
            assertFalse(ReSyncProductionDescriptorFixture.widget(interaction, id).readOnly(), id);
        }
        assertTrue(ReSyncProductionDescriptorFixture.widget(interaction, "world_set_weather").definition().isHidden());
        ContractRef<NodeId> event = ReSyncProductionDescriptorFixture.entry("event.block.break").definitionKey();
        ContractRef<NodeId> player = ReSyncProductionDescriptorFixture.entry("player.properties").definitionKey();
        ContractRef<NodeId> entity = ReSyncProductionDescriptorFixture.entry("entity.properties").definitionKey();
        ContractRef<NodeId> inventory = ReSyncProductionDescriptorFixture.entry("inventory.properties").definitionKey();
        ContractRef<NodeId> worlds = ReSyncProductionDescriptorFixture.entry("world_get_all").definitionKey();
        assertEquals(interaction.pinType(event, "event.player", false), interaction.pinType(player, "target", true));
        assertEquals("entity", interaction.pinType(entity, "input_target", true).orElseThrow().toString());
        assertEquals("list<entity>", interaction.pinType(entity, "output_passengers", false).orElseThrow().toString());
        assertEquals("itemstack", interaction.pinType(player, "item_in_hand", false).orElseThrow().toString());
        assertEquals("list<item>", interaction.pinType(player, "armor", false).orElseThrow().toString());
        assertEquals("list<itemstack>", interaction.pinType(inventory, "items", false).orElseThrow().toString());
        assertEquals("list<world>", interaction.pinType(worlds, "worlds_list", false).orElseThrow().toString());
        assertTrue(interaction.widgetDefinition(event).orElseThrow().definition().isTrigger());
        assertTrue(interaction.pinType(player, "target", false).isEmpty());
    }

    @Test
    void commandSchemaThreePreservesOldPinsAndPublishesSixTypedOutputs() {
        ReSyncTypedInteractionProjection interaction = ReSyncProductionDescriptorFixture.interaction();
        ContractRef<NodeId> identity = ReSyncProductionDescriptorFixture.entry("event.command").definitionKey();
        ReSyncGenericDescriptorProjection.Projection descriptor = interaction.descriptor(identity).orElseThrow();
        NodeDefinition definition = ReSyncProductionDescriptorFixture.widget(interaction, "event.command").definition();

        assertEquals(3, ((Number) descriptor.descriptor().get("schemaVersion")).intValue());
        assertTrue(descriptor.fields().isEmpty());
        assertTrue(definition.getInputs().isEmpty());
        assertEquals(10, definition.getOutputs().size());
        assertTrue(interaction.palette(false).definition("event.command").isPresent());
        for (Map.Entry<String, String> expected : ReSyncProductionDescriptorFixture.commandOutputTypes().entrySet()) {
            NodeDefinition.PinDefinition output = definition.getOutputs().stream()
                .filter(pin -> pin.getName().equals(expected.getKey())).findFirst().orElseThrow();
            assertEquals(expected.getValue(), output.getTypeRef().toString(), expected.getKey());
            assertEquals(expected.getValue(), interaction.pinType(identity, expected.getKey(), false).orElseThrow().toString());
            assertTrue(interaction.pinType(identity, expected.getKey(), true).isEmpty());
            assertTrue(output.getVisibleWhen().isEmpty());
            assertNull(output.getWidgetType());
        }
    }

    @Test
    void derivesGenericBoundariesAndDropsFromTheAcknowledgedTypedPublication() {
        CatalogCacheKey key = key(4, "a");
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublication publication = publication(key, 4, "next", "previous");
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();

        assertTrue(projection.acknowledgeActiveKey(key));
        assertTrue(projection.apply(publication, codec.encodeBytes(publication)));

        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(
            projection.active().orElseThrow());
        ContractRef<NodeId> start = ContractRef.of(new OwnerId("builtin"), new NodeId("function.start"));
        ContractRef<NodeId> drop = ContractRef.of(new OwnerId("builtin"), new NodeId("resource.consume"));

        assertEquals(key, interaction.key());
        assertEquals(ReSyncTypedInteractionProjection.FunctionBoundaryRole.INPUTS,
            interaction.functionBoundary(start).orElseThrow().role());
        assertEquals("next", interaction.functionBoundary(start).orElseThrow().flowPin());
        assertEquals(2, interaction.functionBoundary(start).orElseThrow().parameterPins().size());
        assertEquals("number", interaction.functionBoundary(start).orElseThrow().parameterPins().get(1).typeRef());
        assertEquals(drop, interaction.allDropContributions().getFirst().target());
        assertEquals("flow", interaction.allDropContributions().getFirst().resourceType());
        assertEquals("flow", interaction.allDropContributions().getFirst().referenceKind());
        assertEquals(2, interaction.allDropContributions().getFirst().priority());
        assertEquals(1, interaction.allDropContributions().size());
        assertEquals(3, interaction.descriptors().size());
        assertEquals(key, interaction.palette(false).key());
        assertEquals(3, interaction.palette(false).definitions().size());
        assertEquals("function.start", interaction.palette(false).definition("function.start").orElseThrow().getId());
        assertEquals("resource.consume", interaction.palette(false).definition("resource.consume").orElseThrow().getId());
        assertTrue(interaction.widgetDefinition(drop).orElseThrow().readOnly());
        assertSame(interaction.widgetDefinition(start).orElseThrow().definition(),
            interaction.palette(false).definition("function.start").orElseThrow());
        assertTrue(interaction.palette(true).definitions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> interaction.palette(false).definitions().clear());
        assertEquals(key, CatalogCacheKey.parseCanonicalText(key.canonicalText()));
        assertThrows(IllegalArgumentException.class, () -> CatalogCacheKey.parseCanonicalText(" " + key.canonicalText()));
    }

    @Test
    void derivesCoreFunctionBoundariesFromThePublishedAuthoredHandlerConfig() {
        CatalogCacheKey key = key(5, "f");
        ContractRef<NodeId> start = ContractRef.of(new OwnerId("restudio.resync"), new NodeId("function_start"));
        ContractRef<NodeId> end = ContractRef.of(new OwnerId("restudio.resync"), new NodeId("function_end"));
        Map<String, Object> startDescriptor = new LinkedHashMap<>(descriptor("function_start", List.of(
            pin("flow", "output", named("execution"), "")), Map.of()));
        startDescriptor.put("metadata", Map.of("authoredSource", Map.of("handlerConfig", Map.of(
            "functionBoundary", Map.of("role", "inputs", "flowPin", "flow")))));
        Map<String, Object> endDescriptor = new LinkedHashMap<>(descriptor("function_end", List.of(
            pin("flow", "input", named("execution"), "")), Map.of()));
        endDescriptor.put("metadata", Map.of("authoredSource", Map.of("handlerConfig", Map.of(
            "functionBoundary", Map.of("role", "outputs", "flowPin", "flow")))));
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 5,
            List.of(entry(start, 5, startDescriptor), entry(end, 5, endDescriptor)));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();

        assertTrue(projection.apply(publication, codec.encodeBytes(publication)));
        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(
            projection.active().orElseThrow());

        assertEquals(ReSyncTypedInteractionProjection.FunctionBoundaryRole.INPUTS,
            interaction.functionBoundary(start).orElseThrow().role());
        assertEquals(ReSyncTypedInteractionProjection.FunctionBoundaryRole.OUTPUTS,
            interaction.functionBoundary(end).orElseThrow().role());
        assertEquals("flow", interaction.functionBoundary(start).orElseThrow().flowPin());
        assertEquals("flow", interaction.functionBoundary(end).orElseThrow().flowPin());
    }

    @Test
    void replacesAStaleInteractionProjectionOnlyAfterANewTypedPublication() {
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCacheKey firstKey = key(7, "b");
        CatalogCachePublication first = publication(firstKey, 7, "old-next", "old-previous");
        assertTrue(projection.apply(first, codec.encodeBytes(first)));
        ReSyncTypedInteractionProjection previous = ReSyncTypedInteractionProjection.from(
            projection.active().orElseThrow());

        CatalogCacheKey nextKey = key(8, "c");
        CatalogCachePublication next = publication(nextKey, 8, "new-next", "new-previous");
        assertTrue(projection.apply(next, codec.encodeBytes(next)));
        ReSyncTypedInteractionProjection current = ReSyncTypedInteractionProjection.from(
            projection.active().orElseThrow());
        ContractRef<NodeId> start = ContractRef.of(new OwnerId("builtin"), new NodeId("function.start"));

        assertEquals("old-next", previous.functionBoundary(start).orElseThrow().flowPin());
        assertEquals("new-next", current.functionBoundary(start).orElseThrow().flowPin());
        assertEquals(nextKey, current.key());
        assertFalse(current.key().equals(previous.key()));
    }

    @Test
    void retiringHiddenDefinitionsRemainAvailableWhileTheirActiveReplacementStaysInThePalette() {
        CatalogCacheKey key = key(15, "5");
        ContractRef<NodeId> identity = ContractRef.of(new OwnerId("builtin"), new NodeId("legacy.schedule"));
        ContractRef<NodeId> replacementIdentity = ContractRef.of(new OwnerId("builtin"), new NodeId("automation.schedule"));
        Map<String, Object> legacy = new LinkedHashMap<>(descriptor("legacy.schedule", List.of(
            pin("flow", "output", named("execution"), "")), Map.of()));
        legacy.put("lifecycle", "retiring");
        legacy.put("replacementFor", "automation.schedule");
        legacy.put("metadata", Map.of("authoredSource", Map.of(
            "deprecated", true,
            "hidden", true,
            "hiddenReason", "migration-only",
            "lifecycle", "retiring",
            "replacementFor", "automation.schedule")));
        Map<String, Object> replacement = descriptor("automation.schedule", List.of(
            pin("flow", "output", named("execution"), "")), Map.of());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            15, List.of(entry(identity, 15, legacy), entry(replacementIdentity, 15, replacement)));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();

        assertTrue(projection.apply(publication, codec.encodeBytes(publication)));
        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(
            projection.active().orElseThrow());

        assertEquals("legacy.schedule", interaction.widgetDefinition(identity).orElseThrow().definition().getId());
        assertTrue(interaction.widgetDefinition(identity).orElseThrow().definition().isHidden());
        assertTrue(interaction.palette(false).definition("legacy.schedule").isEmpty());
        assertEquals("automation.schedule", interaction.widgetDefinition(replacementIdentity).orElseThrow().definition().getId());
        assertTrue(interaction.palette(false).definition("automation.schedule").isPresent());
    }

    @Test
    void rejectsMalformedFunctionBoundaryParameters() {
        CatalogCacheKey key = key(9, "d");
        ContractRef<NodeId> malformed = ContractRef.of(new OwnerId("extension"), new NodeId("function.inputs"));
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 9,
            List.of(entry(malformed, 9, descriptor("function.inputs", List.of(
                pin("flow", "output", named("execution"), "")),
                Map.of("flowPin", "flow", "role", "inputs", "parameterPins",
                    List.of(Map.of("id", "value", "name", "Value", "typeRef", "invalid<")))))));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        assertTrue(projection.apply(publication, codec.encodeBytes(publication)));

        ReSyncTypedInteractionProjection.ValidationException failure = assertThrows(
            ReSyncTypedInteractionProjection.ValidationException.class,
            () -> ReSyncTypedInteractionProjection.from(projection.active().orElseThrow()));

        assertEquals("CATALOG_INTERACTION.BOUNDARY_PARAMETER_TYPE_INVALID:extension/function.inputs:value",
            failure.diagnostic());
    }

    @Test
    void rejectsConflictingAndIncompleteFunctionBoundaryRoles() {
        CatalogCacheKey conflictKey = key(10, "e");
        ContractRef<NodeId> builtinStart = ContractRef.of(new OwnerId("builtin"), new NodeId("function.start"));
        ContractRef<NodeId> extensionStart = ContractRef.of(new OwnerId("extension"), new NodeId("function.inputs"));
        ContractRef<NodeId> builtinEnd = ContractRef.of(new OwnerId("builtin"), new NodeId("function.end"));
        CatalogCachePublication conflict = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, conflictKey, 10,
            List.of(
                entry(builtinStart, 10, descriptor("function.start", List.of(
                    pin("flow", "output", named("execution"), "")), Map.of("flowPin", "flow", "role", "inputs"))),
                entry(extensionStart, 10, descriptor("function.inputs", List.of(
                    pin("flow", "output", named("execution"), "")), Map.of("flowPin", "flow", "role", "inputs"))),
                entry(builtinEnd, 10, descriptor("function.end", List.of(
                    pin("flow", "input", named("execution"), "")), Map.of("flowPin", "flow", "role", "outputs")))));
        ReSyncCatalogPublicationProjection conflictProjection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        assertTrue(conflictProjection.apply(conflict, codec.encodeBytes(conflict)));
        ReSyncTypedInteractionProjection.ValidationException conflictFailure = assertThrows(
            ReSyncTypedInteractionProjection.ValidationException.class,
            () -> ReSyncTypedInteractionProjection.from(conflictProjection.active().orElseThrow()));
        assertTrue(conflictFailure.diagnostic().startsWith("CATALOG_INTERACTION.BOUNDARY_ROLE_CONFLICT:"));

        CatalogCacheKey incompleteKey = key(11, "1");
        CatalogCachePublication incomplete = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, incompleteKey, 11,
            List.of(entry(builtinStart, 11, descriptor("function.start", List.of(
                pin("flow", "output", named("execution"), "")), Map.of("flowPin", "flow", "role", "inputs")))));
        ReSyncCatalogPublicationProjection incompleteProjection = new ReSyncCatalogPublicationProjection(SERVER);
        assertTrue(incompleteProjection.apply(incomplete, codec.encodeBytes(incomplete)));
        ReSyncTypedInteractionProjection.ValidationException incompleteFailure = assertThrows(
            ReSyncTypedInteractionProjection.ValidationException.class,
            () -> ReSyncTypedInteractionProjection.from(incompleteProjection.active().orElseThrow()));
        assertEquals("CATALOG_INTERACTION.BOUNDARY_OUTPUTS_MISSING", incompleteFailure.diagnostic());
    }

    @Test
    void ignoresUnavailableBoundaryBehaviorAndRejectsDropsWithoutAuthorableTargets() {
        CatalogCacheKey key = key(12, "2");
        ContractRef<NodeId> activeBoundary = ContractRef.of(new OwnerId("builtin"), new NodeId("function.start"));
        ContractRef<NodeId> unavailableBoundary = ContractRef.of(new OwnerId("extension"), new NodeId("function.inputs"));
        ContractRef<NodeId> dropNode = ContractRef.of(new OwnerId("extension"), new NodeId("resource.consume"));
        Map<String, Object> first = Map.of("resourceOwner", "builtin", "resourceType", "flow",
            "capability", "reference", "owner", "extension", "nodeId", "first", "inputPin", "resource",
            "referenceKind", "flow", "referenceOwner", "builtin", "priority", 1);
        Map<String, Object> second = Map.of("resourceOwner", "builtin", "resourceType", "flow",
            "capability", "reference", "owner", "extension", "nodeId", "second", "inputPin", "other",
            "referenceKind", "builtin:flow", "referenceOwner", "extension", "priority", 2);
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 12,
            List.of(
                entry(activeBoundary, 12, descriptor("function.start", List.of(
                    pin("flow", "output", named("execution"), "")), Map.of("flowPin", "flow", "role", "inputs"))),
                entry(unavailableBoundary, 12, CatalogCacheState.READ_ONLY, descriptor("function.inputs", List.of(
                    pin("flow", "output", named("execution"), "")),
                    Map.of("flowPin", "", "role", "inputs", "parameterPins", "invalid"))),
                entry(dropNode, 12, descriptor("resource.consume", List.of(),
                    Map.of("dropContributions", List.of(first, second))))));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        assertTrue(projection.apply(publication, codec.encodeBytes(publication)));

        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(
            projection.active().orElseThrow());

        assertTrue(interaction.functionBoundaries().isEmpty());
        assertTrue(interaction.allDropContributions().isEmpty());
    }

    @Test
    void collapsesSemanticDropsToOneStableCanonicalHigherPriorityContribution() {
        ContractRef<NodeId> target = ContractRef.of(new OwnerId("builtin"), new NodeId("resource.consume"));
        Map<String, Object> lowerPriority = Map.of("resourceOwner", "builtin", "resourceType", "flow",
            "capability", "reference", "owner", "builtin", "nodeId", "resource.consume", "inputPin", "resource",
            "referenceKind", "builtin:flow", "referenceOwner", "builtin", "priority", 2);
        Map<String, Object> higherPriority = Map.of("resourceOwner", "builtin", "resourceType", "flow",
            "capability", "reference", "owner", "builtin", "nodeId", "resource.consume", "inputPin", "resource",
            "referenceKind", "flow", "referenceOwner", "builtin", "priority", 7);

        ReSyncTypedInteractionProjection first = interactionWithDrops(key(13, "3"), 13, target,
            List.of(lowerPriority, higherPriority));
        ReSyncTypedInteractionProjection reversed = interactionWithDrops(key(14, "4"), 14, target,
            List.of(higherPriority, lowerPriority));
        ReSyncTypedInteractionProjection.DropContribution expected =
            new ReSyncTypedInteractionProjection.DropContribution("builtin", "flow", "reference", target,
                "resource", "flow", "builtin", 7);

        assertEquals(List.of(expected), first.allDropContributions());
        assertEquals(first.allDropContributions(), reversed.allDropContributions());
        assertEquals(List.of(expected), first.dropContributions(target));
    }

    @Test
    void admitsPublishedImplicitConversionEdges() {
        CatalogCacheKey key = key(16, "6");
        CatalogCachePublication publication = publication(key, 16, "next", "previous");
        ReSyncCatalogPublicationProjection catalog = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        assertTrue(catalog.apply(publication, codec.encodeBytes(publication)));
        CatalogAuthoringPublication.Entry conversion = new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.CONVERSIONS,
            ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("string-to-number")).canonicalText(),
            CatalogCacheState.ACTIVE, Set.of(), Set.of(CatalogAuthoringPublication.Section.CONVERSIONS), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(Map.of(
                "kind", "conversion",
                "source", named("string"),
                "target", named("number")))));
        List<CatalogAuthoringPublication.SectionProjection> sections = new ArrayList<>();
        for (CatalogAuthoringPublication.Section section : CatalogAuthoringPublication.Section.values()) {
            sections.add(new CatalogAuthoringPublication.SectionProjection(section, true, true,
                CatalogCacheState.ACTIVE, section == CatalogAuthoringPublication.Section.CONVERSIONS
                    ? List.of(conversion) : List.of()));
        }
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(key.catalogBinding(),
            new CatalogVersion(1, 0), VERSION, sections, Set.of());

        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(
            catalog.active().orElseThrow(), authoring);

        assertTrue(interaction.canConvert(FlowTypeRef.parse("string"), FlowTypeRef.parse("number")));
        assertFalse(interaction.canConvert(FlowTypeRef.parse("number"), FlowTypeRef.parse("string")));
    }

    private static ReSyncTypedInteractionProjection interactionWithDrops(CatalogCacheKey key, long revision,
                                                                          ContractRef<NodeId> target,
                                                                          List<Map<String, Object>> contributions) {
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            revision, List.of(entry(target, revision, descriptor("resource.consume", List.of(
                pin("resource", "input", resource("builtin", "flow"), "reference")),
                Map.of("dropContributions", contributions)))));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        assertTrue(projection.apply(publication, codec.encodeBytes(publication)));
        return ReSyncTypedInteractionProjection.from(projection.active().orElseThrow());
    }

    private static CatalogCachePublication publication(CatalogCacheKey key, long revision,
                                                       String startPin, String endPin) {
        ContractRef<NodeId> start = ContractRef.of(new OwnerId("builtin"), new NodeId("function.start"));
        ContractRef<NodeId> end = ContractRef.of(new OwnerId("builtin"), new NodeId("function.end"));
        ContractRef<NodeId> drop = ContractRef.of(new OwnerId("builtin"), new NodeId("resource.consume"));
        Map<String, Object> dropContribution = Map.of("resourceOwner", "builtin", "resourceType", "flow",
            "capability", "reference", "owner", "builtin", "nodeId", "resource.consume", "inputPin", "resource",
            "referenceKind", "builtin:flow", "referenceOwner", "builtin", "priority", 2);
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, revision, List.of(
            entry(start, revision, descriptor("function.start", List.of(
                pin("flow", "output", named("execution"), ""),
                pin("value", "output", named("string"), ""),
                pin("amount", "output", named("number"), "")),
                Map.of("flowPin", startPin, "role", "inputs"))),
            entry(end, revision, descriptor("function.end", List.of(
                pin("flow", "input", named("execution"), ""),
                pin("value", "input", named("string"), "")), Map.of("flowPin", endPin, "role", "outputs"))),
            entry(drop, revision, descriptor("resource.consume", List.of(
                pin("resource", "input", resource("builtin", "flow"), "reference")),
                Map.of("dropContributions", List.of(dropContribution))))));
    }

    private static CatalogCachePublication.Entry entry(ContractRef<NodeId> key, long revision,
                                                       Map<String, Object> descriptor) {
        return entry(key, revision, CatalogCacheState.ACTIVE, descriptor);
    }

    private static CatalogCachePublication.Entry entry(ContractRef<NodeId> key, long revision, CatalogCacheState state,
                                                       Map<String, Object> descriptor) {
        String canonical = CanonicalJson.canonicalize(descriptor);
        return CatalogCachePublication.Entry.present(key, revision, state, Set.of(), false,
            CatalogCacheOpaque.of(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> descriptor(String id, List<Map<String, Object>> pins,
                                                   Map<String, Object> boundary) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!boundary.isEmpty()) {
            if (boundary.containsKey("dropContributions")) {
                metadata.putAll(boundary);
            } else {
                metadata.put("functionBoundary", boundary);
            }
        }
        return Map.of(
            "kind", "node",
            "id", id,
            "displayName", id,
            "description", id,
            "domain", "flow",
            "family", "function",
            "pins", pins,
            "metadata", metadata,
            "inspector", Map.of("intent", "none", "sections", List.of()));
    }

    private static Map<String, Object> pin(String id, String direction, Map<String, Object> type,
                                            String resourceRole) {
        return Map.of("kind", "pin", "id", id, "direction", direction, "type", type,
            "displayName", id, "description", id, "resourceRole", resourceRole,
            "editor", Map.of("ownerId", "builtin", "localId", "generic-editor"));
    }

    private static Map<String, Object> named(String id) {
        return named("builtin", id);
    }

    private static Map<String, Object> named(String owner, String id) {
        return Map.of("kind", "named", "type", Map.of("ownerId", owner, "localId", id), "arguments", List.of());
    }

    private static Map<String, Object> resource(String owner, String id) {
        return Map.of("kind", "resource", "resourceType", Map.of("ownerId", owner, "localId", id));
    }

    private static CatalogCacheKey key(long generation, String checksumLetter) {
        return new CatalogCacheKey(SERVER, generation, new ContentHash(checksumLetter.repeat(64)), BINDING_HASH, VERSION);
    }
}
