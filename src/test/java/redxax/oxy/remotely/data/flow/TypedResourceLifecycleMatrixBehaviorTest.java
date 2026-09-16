package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TypedResourceLifecycleMatrixBehaviorTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("resourceTypes")
    void listCreateAndReloadRemainDrivenByTypedMembership(ReSyncResourceType type) {
        String typeId = type.typeId();
        ReSyncProjectMetadata metadata = new ReSyncProjectMetadata("matrix-server");
        metadata.getResources().add(resource(typeId, "existing", "Existing Presentation", "Custom/Placement", 14));
        metadata.getResources().add(resource(typeId, "removed", "Removed", type.defaultFolder(), 15));

        List<ReSyncProjectMetadata.ResourceEntry> listed = project(metadata, membership(type,
            List.of("existing")));
        ReSyncProjectMetadata.ResourceEntry existing = find(listed, typeId, "existing");
        assertNotNull(existing);
        assertEquals("Existing Presentation", existing.getDisplayName());
        assertEquals("Custom/Placement", existing.getPath());
        assertNull(find(listed, typeId, "removed"));

        List<ReSyncProjectMetadata.ResourceEntry> created = project(metadata, membership(type,
            List.of("existing", "created")));
        ReSyncProjectMetadata.ResourceEntry createdResource = find(created, typeId, "created");
        assertNotNull(createdResource);
        assertEquals("created", createdResource.getDisplayName());
        assertEquals(type.defaultFolder(), createdResource.getPath());

        ReSyncProjectMetadata restartedPresentation = new ReSyncProjectMetadata("matrix-server");
        List<ReSyncProjectMetadata.ResourceEntry> reloaded = project(restartedPresentation, membership(type,
            List.of("existing", "created")));
        assertNotNull(find(reloaded, typeId, "existing"));
        assertNotNull(find(reloaded, typeId, "created"));
    }

    @Test
    void theSameIdInDifferentResourceFamiliesNeverCollides() {
        FlowManager.TypedResourceMembershipSnapshot membership = new FlowManager.TypedResourceMembershipSnapshot(
            "matrix-server", 3L,
            List.of(
                projectResource(ReSyncResourceType.FLOW, "shared"),
                projectResource(ReSyncResourceType.TAB, "shared"),
                projectResource(ReSyncResourceType.GUI, "shared"),
                projectResource(ReSyncResourceType.CHAT, "shared")
            ),
            Set.of(ReSyncResourceType.FLOW.typeId(), ReSyncResourceType.TAB.typeId(),
                ReSyncResourceType.GUI.typeId(), ReSyncResourceType.CHAT.typeId()));

        List<ReSyncProjectMetadata.ResourceEntry> resources = FlowManager.deriveProjectResources(
            ProjectMetadataSnapshot.from(new ReSyncProjectMetadata("matrix-server")), membership);

        assertEquals(4, resources.stream().filter(resource -> "shared".equals(resource.getId())).count());
        assertNotNull(find(resources, ReSyncResourceType.FLOW.typeId(), "shared"));
        assertNotNull(find(resources, ReSyncResourceType.TAB.typeId(), "shared"));
        assertNotNull(find(resources, ReSyncResourceType.GUI.typeId(), "shared"));
        assertNotNull(find(resources, ReSyncResourceType.CHAT.typeId(), "shared"));
    }

    private static Stream<ReSyncResourceType> resourceTypes() {
        return Stream.of(ReSyncResourceType.FLOW, ReSyncResourceType.TAB, ReSyncResourceType.GUI,
            ReSyncResourceType.CHAT);
    }

    private static List<ReSyncProjectMetadata.ResourceEntry> project(ReSyncProjectMetadata metadata,
                                                                      FlowManager.TypedResourceMembershipSnapshot membership) {
        return FlowManager.deriveProjectResources(ProjectMetadataSnapshot.from(metadata), membership);
    }

    private static FlowManager.TypedResourceMembershipSnapshot membership(ReSyncResourceType type, List<String> ids) {
        return new FlowManager.TypedResourceMembershipSnapshot("matrix-server", 2L,
            ids.stream().map(id -> projectResource(type, id)).toList(), Set.of(type.typeId()));
    }

    private static FlowManager.ProjectResource projectResource(ReSyncResourceType type, String id) {
        return new FlowManager.ProjectResource(type.typeId(), id, id, type.defaultFolder(), -1);
    }

    private static ReSyncProjectMetadata.ResourceEntry resource(String type, String id, String displayName,
                                                                String path, int order) {
        ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
        resource.setType(type);
        resource.setId(id);
        resource.setDisplayName(displayName);
        resource.setPath(path);
        resource.setSortOrder(order);
        return resource;
    }

    private static ReSyncProjectMetadata.ResourceEntry find(List<ReSyncProjectMetadata.ResourceEntry> resources,
                                                            String type, String id) {
        return resources.stream().filter(resource -> type.equals(resource.getType()) && id.equals(resource.getId()))
            .findFirst().orElse(null);
    }
}
