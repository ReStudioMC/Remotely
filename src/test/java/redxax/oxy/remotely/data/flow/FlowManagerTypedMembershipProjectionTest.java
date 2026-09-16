package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerTypedMembershipProjectionTest {
    private static final Path FLOW_MANAGER = Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java");

    @Test
    void authoritativeMembershipProjectsUnhydratedIdsWithoutReplacingPresentation() {
        ReSyncProjectMetadata metadata = new ReSyncProjectMetadata("server");
        metadata.getResources().add(resource(ReSyncResourceDragPayload.GUI, "hydrated", "Pinned Name", "Custom/Path", 37));
        metadata.getResources().add(resource(ReSyncResourceDragPayload.GUI, "deleted", "Deleted", "Custom/Path", 38));
        metadata.getResources().add(resource(ReSyncResourceDragPayload.TAB, "pending-list", "Pending", "Tabs", 39));

        FlowManager.TypedResourceMembershipSnapshot membership = new FlowManager.TypedResourceMembershipSnapshot(
            "server", 12L,
            List.of(
                new FlowManager.ProjectResource(ReSyncResourceDragPayload.GUI, "hydrated", "Payload Name", "Interfaces", -1),
                new FlowManager.ProjectResource(ReSyncResourceDragPayload.GUI, "list-only", "list-only", "Interfaces", -1),
                new FlowManager.ProjectResource(ReSyncResourceDragPayload.WORLDGEN, "terrain", "terrain", "World Generation", -1),
                new FlowManager.ProjectResource(ReSyncResourceDragPayload.WORLD, "overworld", "overworld", "Worlds", -1)
            ),
            Set.of(ReSyncResourceDragPayload.GUI, ReSyncResourceDragPayload.WORLDGEN, ReSyncResourceDragPayload.WORLD));

        List<ReSyncProjectMetadata.ResourceEntry> projected = FlowManager.deriveProjectResources(
            ProjectMetadataSnapshot.from(metadata), membership);

        ReSyncProjectMetadata.ResourceEntry hydrated = find(projected, ReSyncResourceDragPayload.GUI, "hydrated");
        assertNotNull(hydrated);
        assertEquals("Pinned Name", hydrated.getDisplayName());
        assertEquals("Custom/Path", hydrated.getPath());
        assertEquals(37, hydrated.getSortOrder());
        assertNull(find(projected, ReSyncResourceDragPayload.GUI, "deleted"));
        assertNotNull(find(projected, ReSyncResourceDragPayload.GUI, "list-only"));
        assertNotNull(find(projected, ReSyncResourceDragPayload.TAB, "pending-list"));
        assertNotNull(find(projected, ReSyncResourceDragPayload.WORLDGEN, "terrain"));
        assertNotNull(find(projected, ReSyncResourceDragPayload.WORLD, "overworld"));
    }

    @Test
    void creationAndReconnectUseTypedAuthorityAndGenerationFences() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String begin = methodBody(source, "String commandContext, Consumer<CreationResult> observer,\n                                                             String resourceTemplate)");
        String exists = methodBody(source, "private boolean creationResourceExists(");
        String hydrate = methodBody(source, "private void hydrateProjectMetadata(String serverId, ReSyncProjectMetadata metadata)");
        String projectResources = methodBody(source, "public List<ReSyncProjectMetadata.ResourceEntry> getProjectResources(String serverId)");
        String projectStamp = methodBody(source, "public long projectMetadataStamp(String serverId)");
        String membershipPublication = methodBody(source, "private void publishTypedMembership(");
        String refresh = methodBody(source, "void refreshStudioWorkspace(String serverId, boolean rebuildContentBrowser)");
        String reconnect = methodBody(source, "private void rehydrateRetainedCoreGraphSessions(");

        assertFalse(begin.contains("getProjectResource"));
        assertTrue(begin.contains("creationResourceExists(serverId, resourceType, resourceKeyId)"));
        assertTrue(exists.contains("authoritativeTypedMembershipContains(serverId, type, id)"));
        assertTrue(exists.contains("guiStore.getFromCache(serverId, id) != null"));
        assertTrue(exists.contains("store != null && store.getFromCache(serverId, id) != null"));
        assertFalse(hydrate.contains("applyTypedMembershipProjection"));
        assertTrue(projectResources.contains("deriveProjectResources(currentProjectMetadataSnapshot(serverId), snapshotTypedResourceMembership(serverId))"));
        assertFalse(projectStamp.contains("snapshotTypedResourceMembership"));
        assertTrue(projectStamp.contains("projectMembershipRevisions.getOrDefault(actualServerId, 0L)"));
        assertTrue(membershipPublication.contains("advanceProjectMembershipRevision(token.serverId())"));
        assertTrue(refresh.indexOf("rehydrateRetainedCoreGraphSessions(serverId)")
            < refresh.indexOf("scheduleStudioWorkspaceRefresh(serverId, rebuildContentBrowser, true)"));
        assertTrue(reconnect.contains("currentCoreGraphOwnerToken(serverId, source)"));
        assertTrue(reconnect.contains("isCurrentServerConnection(connectionToken)"));
        assertTrue(reconnect.contains("ownsCoreGraphOwnerToken(ownerToken)"));
        assertTrue(reconnect.contains("hydrateCoreGraphProjection(serverId, key.type(), key.id())"));
        assertTrue(reconnect.contains("reconcileCoreGraphSession(key, state, connectionToken)"));
    }

    private static ReSyncProjectMetadata.ResourceEntry resource(String type, String id, String displayName,
                                                                String path, int sortOrder) {
        ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
        resource.setType(type);
        resource.setId(id);
        resource.setDisplayName(displayName);
        resource.setPath(path);
        resource.setSortOrder(sortOrder);
        return resource;
    }

    private static ReSyncProjectMetadata.ResourceEntry find(List<ReSyncProjectMetadata.ResourceEntry> resources,
                                                            String type, String id) {
        return resources.stream().filter(resource -> type.equals(resource.getType()) && id.equals(resource.getId()))
            .findFirst().orElse(null);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open, index + 1);
            }
        }
        throw new IllegalStateException(signature);
    }
}
