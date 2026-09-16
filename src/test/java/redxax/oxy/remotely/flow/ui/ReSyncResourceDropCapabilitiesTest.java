package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowResourceReference;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncResourceDropCapabilitiesTest {
    @Test
    void resolvesServerPublishedContributionsByTypedResourceAndCapability() {
        ReSyncResourceDropCapabilities.DropCatalog catalog = ReSyncResourceDropCapabilities.DropCatalog.of(
            new ReSyncResourceDropCapabilities.DropContribution(
                ReSyncResourceDragPayload.FLOW, "resync.flow.drop", "builtin", "flow.run", "selected_flow"));

        ReSyncResourceDropCapabilities.DropSpec spec = ReSyncResourceDropCapabilities.forResource(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.FLOW, "example", "Example", ""),
            catalog,
            "resync.flow.drop");

        assertTrue(spec.isAvailable());
        assertEquals("builtin", spec.target().owner());
        assertEquals("builtin:flow.run", spec.nodeType());
        assertEquals("resync.flow.drop", spec.capability());
        assertEquals("selected_flow", spec.inputPin());
        assertEquals(1, spec.inputValues("example").size());
        Object value = spec.inputValues("example").get("selected_flow");
        assertTrue(value instanceof FlowResourceReference);
        assertEquals(ReSyncResourceDragPayload.FLOW, ((FlowResourceReference) value).getKind());
        assertEquals("example", ((FlowResourceReference) value).getId());
        assertEquals("builtin", ((FlowResourceReference) value).getMetadata().get("dropTargetOwner"));
        assertEquals("flow.run", ((FlowResourceReference) value).getMetadata().get("dropTargetNodeId"));
    }

    @Test
    void missingCapabilityReturnsExplicitUnavailableResult() {
        ReSyncResourceDropCapabilities.DropCatalog catalog = ReSyncResourceDropCapabilities.DropCatalog.of(
            new ReSyncResourceDropCapabilities.DropContribution(
                ReSyncResourceDragPayload.COMMAND, "resync.command.drop", "builtin", "command.run", "command"));

        ReSyncResourceDropCapabilities.DropResult result = ReSyncResourceDropCapabilities.resolve(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.COMMAND, "example", "Example", ""),
            catalog,
            List.of("resync.other.drop"));

        assertFalse(result.isAvailable());
        assertTrue(result.isUnavailable());
        assertEquals(ReSyncResourceDropCapabilities.Availability.UNAVAILABLE, result.status());
        assertNotNull(result.spec());
        assertEquals(null, result.spec().nodeType());
        assertTrue(result.spec().inputValues("example").isEmpty());
    }

    @Test
    void unknownResourceDoesNotDeriveAFoundationNodeOrTargetFromResourceId() {
        ReSyncResourceDropCapabilities.DropSpec spec = ReSyncResourceDropCapabilities.forResource(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.FUNCTION, "function-id", "Function", ""));

        assertTrue(spec.isUnavailable());
        assertEquals(null, spec.nodeType());
        assertFalse(spec.reason().isBlank());
        assertTrue(spec.inputValues("function-id").isEmpty());
    }

    @Test
    void oneResourceWithMultipleCapabilitiesRequiresAnExplicitSelection() {
        ReSyncResourceDropCapabilities.DropCatalog catalog = ReSyncResourceDropCapabilities.DropCatalog.of(
            new ReSyncResourceDropCapabilities.DropContribution(
                ReSyncResourceDragPayload.WORLD, "world.open", "builtin", "world.world_get_by_name", "world_name"),
            new ReSyncResourceDropCapabilities.DropContribution(
                ReSyncResourceDragPayload.WORLD, "world.inspect", "extension", "world.inspect", "world"));

        ReSyncResourceDropCapabilities.DropResult result = ReSyncResourceDropCapabilities.resolve(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.WORLD, "world", "World", ""), catalog);

        assertTrue(result.isUnavailable());
        assertEquals("multiple published drop targets have equal priority for this resource type", result.reason());

        ReSyncResourceDropCapabilities.DropSpec selected = ReSyncResourceDropCapabilities.forResource(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.WORLD, "world", "World", ""), catalog,
            "world.inspect");
        assertTrue(selected.isAvailable());
        assertEquals("extension:world.inspect", selected.nodeType());
        assertEquals("extension", selected.target().owner());
    }

    @Test
    void publishedCatalogIsBoundToServerGenerationAndChecksum() {
        ReSyncResourceDropCapabilities.BundlePublicationOutcome publication = ReSyncResourceDropCapabilities.publishTypedCatalogBundle(
            "server-a", 7, "checksum-a", "projection-a", List.of(new ReSyncResourceDropCapabilities.PublishedDropContribution(
                ReSyncResourceDragPayload.FLOW, "resync.flow.drop", "builtin", "flow.run", "selected_flow", "flow", "server", 0)),
            boundaryCatalog());

        assertTrue(publication.accepted());
        assertTrue(ReSyncResourceDropCapabilities.activeCatalog("server-a", 7, "checksum-a").isPresent());
        assertTrue(ReSyncResourceDropCapabilities.activeCatalog("server-a", 8, "checksum-a").isEmpty());
        assertTrue(ReSyncResourceDropCapabilities.activeCatalog("server-a", 7, "checksum-b").isEmpty());

        ReSyncResourceDropCapabilities.DropResult mismatch = ReSyncResourceDropCapabilities.resolvePublished(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.FLOW, "example", "Example", ""),
            "server-a", 8, "checksum-a");
        assertTrue(mismatch.isUnavailable());
        assertEquals("catalog_mismatch", mismatch.code());

        ReSyncResourceDropCapabilities.DropResult projectionMismatch = ReSyncResourceDropCapabilities.resolvePublished(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.FLOW, "example", "Example", ""),
            "server-a", 7, "checksum-a", "projection-b");
        assertTrue(projectionMismatch.isUnavailable());
        assertEquals("catalog_mismatch", projectionMismatch.code());

        ReSyncResourceDropCapabilities.clearCatalog("server-a");
    }

    @Test
    void publishedCatalogRejectsStaleAndConflictingGenerations() {
        ReSyncResourceDropCapabilities.PublishedDropContribution contribution = new ReSyncResourceDropCapabilities.PublishedDropContribution(
            ReSyncResourceDragPayload.FLOW, "resync.flow.drop", "builtin", "flow.run", "selected_flow", "flow", "server", 0);
        try {
            ReSyncResourceDropCapabilities.BundlePublicationOutcome first = ReSyncResourceDropCapabilities.publishTypedCatalogBundle(
                "server-monotonic", 9, "checksum-9", "projection-9", List.of(contribution), boundaryCatalog());
            ReSyncResourceDropCapabilities.BundlePublicationOutcome stale = ReSyncResourceDropCapabilities.publishTypedCatalogBundle(
                "server-monotonic", 8, "checksum-8", "projection-8", List.of(contribution), boundaryCatalog());
            ReSyncResourceDropCapabilities.BundlePublicationOutcome conflict = ReSyncResourceDropCapabilities.publishTypedCatalogBundle(
                "server-monotonic", 9, "different", "projection-different", List.of(contribution), boundaryCatalog());

            assertTrue(first.accepted());
            assertFalse(stale.accepted());
            assertFalse(conflict.accepted());
            assertTrue(ReSyncResourceDropCapabilities.activeTypedCatalogBundle(
                "server-monotonic", 9, "checksum-9", "projection-9").isPresent());
        } finally {
            ReSyncResourceDropCapabilities.clearCatalog("server-monotonic");
        }
    }

    @Test
    void prioritySelectsOneTargetAndEqualPriorityIsExplicitlyAmbiguous() {
        ReSyncResourceDropCapabilities.DropCatalog catalog = ReSyncResourceDropCapabilities.DropCatalog.of(
            new ReSyncResourceDropCapabilities.DropContribution(
                ReSyncResourceDragPayload.WORLD, "world.low", "builtin", "world.low", "world", "world", "server", 1),
            new ReSyncResourceDropCapabilities.DropContribution(
                ReSyncResourceDragPayload.WORLD, "world.high", "builtin", "world.high", "world", "world", "server", 5));

        ReSyncResourceDropCapabilities.DropResult selected = ReSyncResourceDropCapabilities.resolve(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.WORLD, "world", "World", ""), catalog);
        assertTrue(selected.isAvailable());
         assertEquals("builtin:world.high", selected.spec().nodeType());

        ReSyncResourceDropCapabilities.DropCatalog ambiguous = ReSyncResourceDropCapabilities.DropCatalog.of(
            new ReSyncResourceDropCapabilities.DropContribution(
                ReSyncResourceDragPayload.WORLD, "world.a", "builtin", "world.a", "world", "world", "server", 5),
            new ReSyncResourceDropCapabilities.DropContribution(
                ReSyncResourceDragPayload.WORLD, "world.b", "builtin", "world.b", "world", "world", "server", 5));
        ReSyncResourceDropCapabilities.DropResult result = ReSyncResourceDropCapabilities.resolve(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.WORLD, "world", "World", ""), ambiguous);
        assertTrue(result.isUnavailable());
        assertEquals("ambiguous_drop_target", result.code());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void duplicatePublicationIsStructuredInvalidAndCannotBecomeActive() {
        ReSyncResourceDropCapabilities.PublishedDropContribution first = new ReSyncResourceDropCapabilities.PublishedDropContribution(
            ReSyncResourceDragPayload.COMMAND, "resync.command.drop", "builtin", "command.run", "command", "command", "server", 0);
        ReSyncResourceDropCapabilities.PublishedDropContribution duplicate = new ReSyncResourceDropCapabilities.PublishedDropContribution(
            ReSyncResourceDragPayload.COMMAND, "resync.command.drop", "builtin", "command.other", "command", "command", "server", 0);

        ReSyncResourceDropCapabilities.BundlePublicationOutcome publication = ReSyncResourceDropCapabilities.publishTypedCatalogBundle(
            "server-duplicate", 3, "checksum-duplicate", "projection-duplicate", List.of(first, duplicate), boundaryCatalog());

        assertFalse(publication.accepted());
        assertEquals("duplicate_drop_contribution", publication.code());
        assertFalse(ReSyncResourceDropCapabilities.currentTypedCatalogBundle("server-duplicate").isPresent());
    }

    @Test
    void resourceAndTargetOwnersRemainDistinctInDropKeys() {
        ReSyncResourceDropCapabilities.DropCatalog catalog = ReSyncResourceDropCapabilities.DropCatalog.of(
            new ReSyncResourceDropCapabilities.DropContribution("extension-a", "flow", "drop", "builtin", "flow.a", "flow", "flow", "extension-a", 0),
            new ReSyncResourceDropCapabilities.DropContribution("extension-b", "flow", "drop", "extension", "flow.b", "flow", "flow", "extension-b", 0));

        assertTrue(catalog.isValid());
        assertEquals(2, catalog.contributions().size());
        ReSyncResourceDropCapabilities.DropSpec spec = ReSyncResourceDropCapabilities.forResource(
            new ReSyncResourceDragPayload("extension-a:flow", "example", "Example", ""), catalog, "drop");

        assertTrue(spec.isAvailable());
        assertEquals("extension-a", spec.resourceOwner());
        assertEquals("flow", spec.resourceType());
        assertEquals("builtin:flow.a", spec.nodeType());
        assertFalse(ReSyncResourceDropCapabilities.forResource(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.FLOW, "example", "Example", ""), catalog, "drop").isAvailable());
    }

    @Test
    void malformedResourceIdsProduceInvalidResultsWithReasons() {
        for (String id : Arrays.asList(null, "", " ", " example", "example ", "example\n")) {
            ReSyncResourceDropCapabilities.DropResult result = ReSyncResourceDropCapabilities.resolve(
                new ReSyncResourceDragPayload(ReSyncResourceDragPayload.FLOW, id, "Example", ""),
                ReSyncResourceDropCapabilities.DropCatalog.empty());
            assertTrue(result.isInvalid(), String.valueOf(id));
            assertFalse(result.reason().isBlank(), String.valueOf(id));
        }
    }

    @Test
    void serverScopedResolutionNeverUsesTheUnboundCatalog() {
        ReSyncResourceDropCapabilities.DropContribution contribution = new ReSyncResourceDropCapabilities.DropContribution(
            ReSyncResourceDragPayload.FLOW, "resync.flow.drop", "builtin", "flow.run", "selected_flow");
        ReSyncResourceDropCapabilities.publish("server-bound", 12, "checksum-bound", List.of(contribution));

        ReSyncResourceDropCapabilities.DropResult result = ReSyncResourceDropCapabilities.resolvePublished(
            new ReSyncResourceDragPayload(ReSyncResourceDragPayload.FLOW, "example", "Example", ""),
            "other-server", 12, "checksum-bound");
        assertTrue(result.isUnavailable());
        assertEquals("missing_drop_catalog", result.code());
        ReSyncResourceDropCapabilities.clearCatalog("server-bound");
    }

    @Test
    void standaloneDropPublicationCannotBeConsumedWithoutTheTypedBundle() {
        ReSyncResourceDropCapabilities.DropContribution contribution = new ReSyncResourceDropCapabilities.DropContribution(
            ReSyncResourceDragPayload.FLOW, "resync.flow.drop", "builtin", "flow.run", "selected_flow");
        try {
            assertFalse(ReSyncResourceDropCapabilities.publish("server-unbundled", 12L, "checksum-unbundled", List.of(contribution)).isAccepted());
            ReSyncResourceDropCapabilities.DropResult result = ReSyncResourceDropCapabilities.resolvePublished(
                new ReSyncResourceDragPayload(ReSyncResourceDragPayload.FLOW, "example", "Example", ""),
                "server-unbundled", 12L, "checksum-unbundled");
            assertFalse(result.isAvailable());
            assertEquals("missing_drop_catalog", result.code());
        } finally {
            ReSyncResourceDropCapabilities.clearCatalog("server-unbundled");
        }
    }

    @Test
    void malformedHigherGenerationKeepsTheLastAtomicCatalogBundle() {
        String serverId = "atomic-retention";
        ReSyncResourceDropCapabilities.clearCatalog(serverId);
        FlowNodeWidget.FunctionBoundaryCatalog.remove(serverId);
        ReSyncResourceDropCapabilities.PublishedDropContribution contribution = new ReSyncResourceDropCapabilities.PublishedDropContribution(
            ReSyncResourceDragPayload.FLOW, "resync.flow.drop", "builtin", "flow.run", "selected_flow", "flow", "server", 0);
        FlowNodeWidget.FunctionBoundaryCatalog boundary = FlowNodeWidget.FunctionBoundaryCatalog.of(
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.INPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("flow.entry")), "next", List.of()),
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("flow.exit")), "previous", List.of()));
        try {
            assertTrue(ReSyncResourceDropCapabilities.publishTypedCatalogBundle(serverId, 7L, "checksum-7", "projection-7",
                List.of(contribution), boundary).accepted());
            assertFalse(ReSyncResourceDropCapabilities.publishTypedCatalogBundle(serverId, 8L, "checksum-8", "projection-8",
                List.of(contribution), FlowNodeWidget.FunctionBoundaryCatalog.unavailable()).accepted());
            assertTrue(ReSyncResourceDropCapabilities.activeTypedCatalogBundle(serverId, 7L, "checksum-7", "projection-7").isPresent());
            assertTrue(ReSyncResourceDropCapabilities.activeCatalog(serverId, 7L, "checksum-7").isPresent());
            assertEquals(7L, FlowNodeWidget.FunctionBoundaryCatalog.forServer(serverId).generation());
        } finally {
            ReSyncResourceDropCapabilities.clearCatalog(serverId);
            FlowNodeWidget.FunctionBoundaryCatalog.remove(serverId);
        }
    }

    @Test
    void invalidDropProjectionCannotPartiallyAdvanceTheAtomicCatalogBundle() {
        String serverId = "atomic-drop-retention";
        ReSyncResourceDropCapabilities.clearCatalog(serverId);
        FlowNodeWidget.FunctionBoundaryCatalog.remove(serverId);
        ReSyncResourceDropCapabilities.PublishedDropContribution contribution = new ReSyncResourceDropCapabilities.PublishedDropContribution(
            ReSyncResourceDragPayload.FLOW, "resync.flow.drop", "builtin", "flow.run", "selected_flow", "flow", "server", 0);
        FlowNodeWidget.FunctionBoundaryCatalog boundary = FlowNodeWidget.FunctionBoundaryCatalog.of(
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.INPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("flow.entry")), "next", List.of()),
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("flow.exit")), "previous", List.of()));
        try {
            assertTrue(ReSyncResourceDropCapabilities.publishTypedCatalogBundle(serverId, 3L, "checksum-3", "projection-3",
                List.of(contribution), boundary).accepted());
            ReSyncResourceDropCapabilities.PublishedDropContribution malformed = new ReSyncResourceDropCapabilities.PublishedDropContribution(
                ReSyncResourceDragPayload.FLOW, "resync.flow.drop", "", "flow.run", "selected_flow", "flow", "server", 0);
            assertFalse(ReSyncResourceDropCapabilities.publishTypedCatalogBundle(serverId, 4L, "checksum-4", "projection-4",
                List.of(malformed), boundary).accepted());
            assertTrue(ReSyncResourceDropCapabilities.activeTypedCatalogBundle(serverId, 3L, "checksum-3", "projection-3").isPresent());
            assertEquals(3L, FlowNodeWidget.FunctionBoundaryCatalog.forServer(serverId).generation());
        } finally {
            ReSyncResourceDropCapabilities.clearCatalog(serverId);
            FlowNodeWidget.FunctionBoundaryCatalog.remove(serverId);
        }
    }

    @Test
    void unqualifiedBoundaryCannotEnterTheTypedCatalogBundle() {
        String serverId = "typed-boundary-required";
        ReSyncResourceDropCapabilities.clearCatalog(serverId);
        FlowNodeWidget.FunctionBoundaryCatalog rawBoundary = FlowNodeWidget.FunctionBoundaryCatalog.of(
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.INPUTS, "flow.entry", "next"),
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS, "flow.exit", "previous"));
        ReSyncResourceDropCapabilities.PublishedDropContribution contribution = new ReSyncResourceDropCapabilities.PublishedDropContribution(
            ReSyncResourceDragPayload.FLOW, "resync.flow.drop", "builtin", "flow.run", "selected_flow", "flow", "server", 0);
        try {
            ReSyncResourceDropCapabilities.BundlePublicationOutcome result =
                ReSyncResourceDropCapabilities.publishTypedCatalogBundle(serverId, 1L, "checksum-1", "projection-1",
                    List.of(contribution), rawBoundary);
            assertFalse(result.accepted());
            assertEquals("unavailable_function_boundary_catalog", result.code());
            assertTrue(ReSyncResourceDropCapabilities.currentTypedCatalogBundle(serverId).isEmpty());
        } finally {
            ReSyncResourceDropCapabilities.clearCatalog(serverId);
        }
    }

    @Test
    void invalidTypedDropTargetIdentityFailsClosed() {
        assertThrows(IllegalArgumentException.class,
            () -> new ReSyncResourceDropCapabilities.DropTarget("invalid owner", "node"));
        assertThrows(IllegalArgumentException.class,
            () -> new ReSyncResourceDropCapabilities.DropTarget("builtin", "other:node"));
    }

    private FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog() {
        return FlowNodeWidget.FunctionBoundaryCatalog.of(
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.INPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("flow.entry")), "next", List.of()),
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("flow.exit")), "previous", List.of()));
    }
}
