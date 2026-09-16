package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.sync.FlowResourceMetadata;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedResourceEditorModelTest {
    private static final String SERVER = UUID.fromString("1e539d56-b236-4f3c-912b-382149b55c0a").toString();

    @Test
    void unknownTypeRemainsOpaqueAndInspectableWithoutATypeCast() {
        ManagedResourceCatalog.Descriptor descriptor = ManagedResourceCatalog.opaque("extension_resource",
            "Descriptor Missing");

        ManagedResourceEditorModel model = ManagedResourceEditorModel.open(SERVER, descriptor, "one", null, false)
            .orElseThrow();

        assertEquals("extension_resource", model.resourceTypeId());
        assertEquals("one", model.resourceId());
        assertTrue(model.locator().isEmpty());
        assertTrue(model.opaque());
        assertTrue(model.readOnly());
        assertFalse(model.contentAvailable());
        assertEquals(ManagedResourceEditorModel.Route.OPAQUE, model.route(Set.of("text-template")));
    }

    @Test
    void nestedStructureAndAbsentValuesSurviveInspectionExactly() {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", "structured");
        JsonObject nested = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(true);
        items.add(4);
        nested.add("items", items);
        nested.add("a/b~c", new JsonObject());
        payload.add("nested", nested);
        ManagedResourceEditorModel model = model("structured_type", payload);

        assertEquals(List.of("", "/id", "/nested", "/nested/items", "/nested/items/0", "/nested/items/1",
                "/nested/a~1b~0c"), model.structure().stream().map(ManagedResourceEditorModel.Structure::pointer).toList());
        assertFalse(model.content().has("scope"));
        assertFalse(model.content().has("persistent"));
        JsonObject detached = model.content();
        detached.addProperty("added", true);
        assertFalse(model.content().has("added"));
    }

    @Test
    void variableTimerAndScheduleUseTheSameGenericDocumentContract() {
        for (String type : List.of("variable_definition", "timer_definition", "schedule_definition")) {
            JsonObject payload = new JsonObject();
            payload.addProperty("id", "definition");
            ManagedResourceEditorModel model = model(type, payload);

            assertEquals(type, model.locator().orElseThrow().resourceType().value());
            assertEquals(ManagedResourceEditorModel.Route.GENERIC, model.route(Set.of()));
            assertEquals(List.of("", "/id"), model.structure().stream()
                .map(ManagedResourceEditorModel.Structure::pointer).toList());
            assertFalse(model.content().has("scope"));
            assertFalse(model.content().has("persistent"));
            assertFalse(model.readOnly());
        }
    }

    @Test
    void operationAvailabilityComesFromTheAdvertisedCatalog() {
        FlowResourceMetadata metadata = new FlowResourceMetadata();
        metadata.setTypeId("managed");
        metadata.setDisplayName("Managed");
        metadata.setOwner("restudio.resync");
        metadata.setAvailable(true);
        metadata.setOperations(List.of("discover", "query", "get", "create", "validate", "save", "update",
            "duplicate", "delete", "apply"));
        metadata.setOperationAvailability(Map.of(
            "save", "available",
            "reload", "This resource domain does not expose an explicit reload operation",
            "apply", "available"
        ));

        ManagedResourceCatalog.Descriptor descriptor = ManagedResourceCatalog.fromMetadata("managed", metadata);

        assertTrue(descriptor.supports(ManagedResourceCatalog.Operation.DISCOVER));
        assertTrue(descriptor.supports(ManagedResourceCatalog.Operation.GET));
        assertTrue(descriptor.supports(ManagedResourceCatalog.Operation.SAVE));
        assertTrue(descriptor.supports(ManagedResourceCatalog.Operation.APPLY));
        assertFalse(descriptor.supports(ManagedResourceCatalog.Operation.RELOAD));
        assertEquals(ManagedResourceCatalog.State.UNSUPPORTED,
            descriptor.operation(ManagedResourceCatalog.Operation.RELOAD).state());
        assertTrue(descriptor.operation(ManagedResourceCatalog.Operation.RELOAD).reason().contains("does not expose"));
        assertEquals(List.of(ManagedResourceCatalog.Operation.DISCOVER, ManagedResourceCatalog.Operation.QUERY,
            ManagedResourceCatalog.Operation.GET, ManagedResourceCatalog.Operation.CREATE,
            ManagedResourceCatalog.Operation.VALIDATE, ManagedResourceCatalog.Operation.SAVE,
            ManagedResourceCatalog.Operation.UPDATE, ManagedResourceCatalog.Operation.DUPLICATE,
            ManagedResourceCatalog.Operation.RELOAD, ManagedResourceCatalog.Operation.DELETE,
            ManagedResourceCatalog.Operation.APPLY), ManagedResourceCatalog.editorOperations());
    }

    @Test
    void capabilityCatalogProjectsOnlyTheServerDeclaredOperations() {
        JsonObject descriptor = capabilityDescriptor("managed");
        descriptor.getAsJsonArray("operations").add("future_operation");
        descriptor.getAsJsonObject("operationAvailability").addProperty("future_operation", "available");

        ManagedResourceCatalog.Descriptor projected = ManagedResourceCatalog.fromCapabilities("managed",
            capabilities(1, descriptor));

        assertFalse(projected.opaque());
        assertTrue(projected.supports(ManagedResourceCatalog.Operation.DISCOVER));
        assertTrue(projected.supports(ManagedResourceCatalog.Operation.SAVE));
        assertFalse(projected.supports(ManagedResourceCatalog.Operation.DELETE));
        assertEquals("Deletion is disabled", projected.operation(ManagedResourceCatalog.Operation.DELETE).reason());
    }

    @Test
    void capabilityCatalogFailsClosedForMissingVersionsDuplicatesAndMalformedOperations() {
        assertTrue(ManagedResourceCatalog.fromCapabilities("managed", new JsonObject()).opaque());
        assertTrue(ManagedResourceCatalog.fromCapabilities("managed",
            capabilities(2, capabilityDescriptor("managed"))).opaque());

        JsonObject duplicateCapabilities = capabilities(1, capabilityDescriptor("managed"));
        duplicateCapabilities.getAsJsonObject("managedResources").getAsJsonArray("descriptors")
            .add(capabilityDescriptor("MANAGED"));
        ManagedResourceCatalog.Descriptor duplicate = ManagedResourceCatalog.fromCapabilities("managed",
            duplicateCapabilities);
        assertTrue(duplicate.opaque());
        assertTrue(duplicate.unavailableReason().contains("duplicate"));

        JsonObject malformed = capabilityDescriptor("managed");
        malformed.addProperty("operations", "save");
        ManagedResourceCatalog.Descriptor invalid = ManagedResourceCatalog.fromCapabilities("managed",
            capabilities(1, malformed));
        assertTrue(invalid.opaque());
        assertTrue(invalid.unavailableReason().contains("malformed"));
    }

    @Test
    void updateCapabilityKeepsTheGenericDocumentWritableWhenSaveIsUnsupported() {
        EnumMap<ManagedResourceCatalog.Operation, ManagedResourceCatalog.Access> operations = new EnumMap<>(
            ManagedResourceCatalog.Operation.class);
        operations.put(ManagedResourceCatalog.Operation.UPDATE, new ManagedResourceCatalog.Access(
            ManagedResourceCatalog.Operation.UPDATE, ManagedResourceCatalog.State.AVAILABLE, ""));
        ManagedResourceCatalog.Descriptor descriptor = ManagedResourceCatalog.descriptor("managed", "Managed",
            "restudio.resync", true, "", operations, Set.of());
        JsonObject payload = new JsonObject();
        payload.addProperty("id", "one");

        ManagedResourceEditorModel model = ManagedResourceEditorModel.open(SERVER, descriptor, "one", payload, true)
            .orElseThrow();

        assertFalse(model.readOnly());
    }

    @Test
    void specializedPresentationRequiresAnAdvertisedAndSupportedCapability() {
        EnumMap<ManagedResourceCatalog.Operation, ManagedResourceCatalog.Access> operations = new EnumMap<>(
            ManagedResourceCatalog.Operation.class);
        operations.put(ManagedResourceCatalog.Operation.SAVE, new ManagedResourceCatalog.Access(
            ManagedResourceCatalog.Operation.SAVE, ManagedResourceCatalog.State.AVAILABLE, ""));
        ManagedResourceCatalog.Descriptor descriptor = ManagedResourceCatalog.descriptor("automation", "Automation",
            "restudio.resync", true, "", operations, Set.of("automation-definition"));
        JsonObject payload = new JsonObject();
        payload.addProperty("id", "one");
        ManagedResourceEditorModel model = ManagedResourceEditorModel.open(SERVER, descriptor, "one", payload, true)
            .orElseThrow();

        assertEquals(ManagedResourceEditorModel.Route.GENERIC, model.route(Set.of("text-template")));
        assertEquals(ManagedResourceEditorModel.Route.SPECIALIZED,
            model.route(Set.of("automation-definition")));
    }

    private ManagedResourceEditorModel model(String type, JsonObject payload) {
        EnumMap<ManagedResourceCatalog.Operation, ManagedResourceCatalog.Access> operations = new EnumMap<>(
            ManagedResourceCatalog.Operation.class);
        operations.put(ManagedResourceCatalog.Operation.SAVE, new ManagedResourceCatalog.Access(
            ManagedResourceCatalog.Operation.SAVE, ManagedResourceCatalog.State.AVAILABLE, ""));
        ManagedResourceCatalog.Descriptor descriptor = ManagedResourceCatalog.descriptor(type, type,
            "restudio.resync", true, "", operations, Set.of());
        return ManagedResourceEditorModel.open(SERVER, descriptor, payload.get("id").getAsString(), payload, true)
            .orElseThrow();
    }

    private JsonObject capabilities(int version, JsonObject... descriptors) {
        JsonArray values = new JsonArray();
        for (JsonObject descriptor : descriptors) {
            values.add(descriptor);
        }
        JsonObject managed = new JsonObject();
        managed.addProperty("version", version);
        managed.add("descriptors", values);
        JsonObject capabilities = new JsonObject();
        capabilities.add("managedResources", managed);
        return capabilities;
    }

    private JsonObject capabilityDescriptor(String typeId) {
        JsonArray operations = new JsonArray();
        operations.add("discover");
        operations.add("save");
        JsonObject availability = new JsonObject();
        availability.addProperty("discover", "available");
        availability.addProperty("save", "available");
        availability.addProperty("delete", "Deletion is disabled");
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("schemaVersion", 2);
        descriptor.addProperty("typeId", typeId);
        descriptor.addProperty("displayName", "Managed");
        descriptor.addProperty("owner", "restudio.resync");
        descriptor.addProperty("defaultFolder", "managed");
        descriptor.addProperty("available", true);
        descriptor.add("operations", operations);
        descriptor.add("operationAvailability", availability);
        return descriptor;
    }
}
