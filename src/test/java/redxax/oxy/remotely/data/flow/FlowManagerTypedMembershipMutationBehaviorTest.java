package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerTypedMembershipMutationBehaviorTest {
    @Test
    void committedCreateProjectsBeforeMetadataSagaCompletes() {
        ReSyncResourceType type = ReSyncResourceType.TAB;
        String path = FlowManager.canonicalResourcePath(type, "main", type.defaultFolder());
        FlowManager.TypedResourceMembershipSnapshot membership = new FlowManager.TypedResourceMembershipSnapshot(
            "server", 41L, List.of(new FlowManager.ProjectResource(type.typeId(), "main", "main", path, -1)),
            Set.of(), Set.of());

        List<ReSyncProjectMetadata.ResourceEntry> resources = FlowManager.deriveProjectResources(
            ProjectMetadataSnapshot.from(new ReSyncProjectMetadata("server")), membership);

        ReSyncProjectMetadata.ResourceEntry created = find(resources, type.typeId(), "main");
        assertNotNull(created);
        assertEquals(ReSyncProjectMetadata.normalizePath(type.defaultFolder() + "/main.json"), created.getPath());
        assertFalse(membership.completeTypes().contains(type.typeId()));
    }

    @Test
    void committedSaveUpsertsAndClearsAnEarlierTombstone() {
        FlowManager.TypedMembershipMutationResult mutation = FlowManager.mutateTypedMembershipState(
            List.of("existing"), Set.of("main"), "main", true);

        assertEquals(List.of("existing", "main"), mutation.ids());
        assertFalse(mutation.tombstones().contains("main"));
        assertEquals(mutation, FlowManager.mutateTypedMembershipState(
            mutation.ids(), mutation.tombstones(), "main", true));
    }

    @Test
    void committedDeleteRemovesPartialMetadataWithoutClaimingFullListAuthority() {
        ReSyncProjectMetadata metadata = new ReSyncProjectMetadata("server");
        metadata.getResources().add(resource(ReSyncResourceType.TAB, "main"));
        metadata.getResources().add(resource(ReSyncResourceType.TAB, "retained"));
        FlowManager.TypedMembershipMutationResult mutation = FlowManager.mutateTypedMembershipState(
            List.of("main"), Set.of(), "main", false);
        FlowManager.TypedResourceMembershipSnapshot membership = new FlowManager.TypedResourceMembershipSnapshot(
            "server", 42L, List.of(), Set.of(), Set.of(ReSyncProjectMetadata.resourceKey(
                ReSyncResourceType.TAB.typeId(), mutation.tombstones().iterator().next())));

        List<ReSyncProjectMetadata.ResourceEntry> resources = FlowManager.deriveProjectResources(
            ProjectMetadataSnapshot.from(metadata), membership);

        assertNull(find(resources, ReSyncResourceType.TAB.typeId(), "main"));
        assertNotNull(find(resources, ReSyncResourceType.TAB.typeId(), "retained"));
        assertTrue(membership.completeTypes().isEmpty());
    }

    @Test
    void fullListReplacementRemainsAuthoritative() {
        ReSyncProjectMetadata metadata = new ReSyncProjectMetadata("server");
        metadata.getResources().add(resource(ReSyncResourceType.SCOREBOARD, "stale"));
        metadata.getResources().add(resource(ReSyncResourceType.TAB, "unrelated"));
        FlowManager.TypedResourceMembershipSnapshot membership = new FlowManager.TypedResourceMembershipSnapshot(
            "server", 43L, List.of(new FlowManager.ProjectResource(ReSyncResourceType.SCOREBOARD.typeId(), "live", "live",
                FlowManager.canonicalResourcePath(ReSyncResourceType.SCOREBOARD, "live",
                    ReSyncResourceType.SCOREBOARD.defaultFolder()), -1)),
            Set.of(ReSyncResourceType.SCOREBOARD.typeId()), Set.of());

        List<ReSyncProjectMetadata.ResourceEntry> resources = FlowManager.deriveProjectResources(
            ProjectMetadataSnapshot.from(metadata), membership);

        assertNull(find(resources, ReSyncResourceType.SCOREBOARD.typeId(), "stale"));
        assertNotNull(find(resources, ReSyncResourceType.SCOREBOARD.typeId(), "live"));
        assertNotNull(find(resources, ReSyncResourceType.TAB.typeId(), "unrelated"));
    }

    @Test
    void everyGenericResourceTypeGetsACanonicalFilePath() {
        for (ReSyncResourceType type : ReSyncResourceType.values()) {
            if (!type.enabled() || type == ReSyncResourceType.PROJECT_METADATA) {
                continue;
            }
            assertEquals(ReSyncProjectMetadata.normalizePath(type.defaultFolder() + "/sample.json"),
                FlowManager.canonicalResourcePath(type, "sample", type.defaultFolder()), type.typeId());
            assertEquals(ReSyncProjectMetadata.normalizePath(type.defaultFolder() + "/sample.json"),
                FlowManager.canonicalResourcePath(type, "sample", "Custom/Nested/old.json"), type.typeId());
            assertEquals(ReSyncProjectMetadata.normalizePath(type.defaultFolder() + "/Nested/sample.json"),
                FlowManager.canonicalResourcePath(type, "sample", type.defaultFolder() + "/Nested/old.json"),
                type.typeId());
        }
    }

    private static ReSyncProjectMetadata.ResourceEntry resource(ReSyncResourceType type, String id) {
        ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
        resource.setType(type.typeId());
        resource.setId(id);
        resource.setDisplayName(id);
        resource.setPath(FlowManager.canonicalResourcePath(type, id, type.defaultFolder()));
        return resource;
    }

    private static ReSyncProjectMetadata.ResourceEntry find(List<ReSyncProjectMetadata.ResourceEntry> resources,
                                                            String type, String id) {
        return resources.stream().filter(resource -> type.equals(resource.getType()) && id.equals(resource.getId()))
            .findFirst().orElse(null);
    }
}
