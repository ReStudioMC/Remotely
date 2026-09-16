package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerProjectMetadataHydrationTest {
    private static final Path FLOW_MANAGER = Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java");

    @Test
    void graphMetadataPruningRequiresCurrentAuthoritativeMembership() throws IOException {
        String source = Files.readString(FLOW_MANAGER);
        String hydration = methodBody(source, "private void hydrateProjectMetadata(String serverId, ReSyncProjectMetadata metadata)");
        String membership = methodBody(source, "private boolean missingFromAuthoritativeGraphList(");

        assertTrue(hydration.contains("missingFromAuthoritativeGraphList(serverId, type, resource.getId())"));
        assertFalse(hydration.contains("hasLoadedGraphList(serverId, type)"));
        assertFalse(hydration.contains("hasServerGraph(serverId, type, resource.getId())"));
        assertTrue(membership.contains("coreGraphUiProjection.tombstoned(serverId, type, id)"));
        assertTrue(membership.contains("connectionManager.getFlowClient(serverId)"));
        assertTrue(membership.contains("flowClient.isResourceListAuthoritative(type)"));
        assertTrue(membership.contains("flowStore.containsServerId(serverId, type, id)"));
        assertTrue(membership.contains("coreGraphResourceKnown(flowClient, type, id)"));
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
                return source.substring(open, index + 1).replace("\r\n", "\n");
            }
        }
        throw new IllegalStateException(signature);
    }
}
