package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncContentBrowserPathProjectionTest {
    @Test
    void canonicalResourceFilePathProjectsIntoItsParentFolder() {
        assertEquals("Blueprints/Flows",
            ReSyncContentBrowserWidget.resourceFolderPath("Blueprints/Flows/example.json", "example"));
        assertEquals("", ReSyncContentBrowserWidget.resourceFolderPath("example.json", "example"));
    }

    @Test
    void legacyFolderOnlyPathRemainsACompatibilityInput() {
        assertEquals("Blueprints/Flows",
            ReSyncContentBrowserWidget.resourceFolderPath("Blueprints\\Flows/", "example"));
        assertEquals("folder/example.json",
            ReSyncContentBrowserWidget.resourceFolderPath("folder/example.json", "different"));
    }

    @Test
    void selectedResourceUsesItsParentFolderAsThePasteDestination() {
        ReSyncProjectMetadata.ResourceEntry canonical = resource("example", "Blueprints/Flows/example.json");
        ReSyncProjectMetadata.ResourceEntry legacy = resource("example", "Blueprints/Flows");

        assertEquals("Blueprints/Flows", ReSyncContentBrowserWidget.resourceSelectionDestination(canonical));
        assertEquals("Blueprints/Flows", ReSyncContentBrowserWidget.resourceSelectionDestination(legacy));
    }

    @Test
    void copiedResourcePathUsesTheTargetIdAsItsCanonicalFilename() {
        assertEquals("Blueprints/Flows/example_copy.json",
            ReSyncContentBrowserWidget.canonicalResourcePath("Blueprints/Flows", "example_copy"));
        assertEquals("example_copy.json", ReSyncContentBrowserWidget.canonicalResourcePath("", "example_copy"));
    }

    @Test
    void studioFolderTreeStopsBeforeTheCanonicalResourceFilename() {
        assertEquals(List.of("Blueprints", "Blueprints/Flows"),
            StudioScreen.studioResourceFolderHierarchy("Blueprints/Flows/example.json", "example"));
        assertEquals(List.of("Blueprints", "Blueprints/Flows"),
            StudioScreen.studioResourceFolderHierarchy("Blueprints/Flows", "example"));
    }

    @Test
    void resourceMembershipUsesTheDerivedFolderForCanonicalAndLegacyPaths() {
        assertTrue(ReSyncContentBrowserWidget.resourceWithinFolder("Blueprints/Flows/example.json", "example", "Blueprints/Flows"));
        assertTrue(ReSyncContentBrowserWidget.resourceWithinFolder("Blueprints/Flows/example.json", "example", "Blueprints"));
        assertTrue(ReSyncContentBrowserWidget.resourceWithinFolder("Blueprints/Flows", "example", "Blueprints"));
        assertFalse(ReSyncContentBrowserWidget.resourceWithinFolder("Blueprints/Other/example.json", "example", "Blueprints/Flows"));

        assertTrue(StudioScreen.studioResourceInFolder(resource("example", "Blueprints/Flows/example.json"), "Blueprints/Flows"));
        assertTrue(StudioScreen.studioResourceInFolder(resource("example", "Blueprints/Flows"), "Blueprints/Flows"));
        assertFalse(StudioScreen.studioResourceInFolder(resource("example", "Blueprints/Flows/example.json"), "Blueprints"));
    }

    @Test
    void pendingFolderDeletionHidesTheFolderAndEveryDescendant() {
        Set<String> pending = Set.of("Blueprints/Flows");

        assertTrue(ReSyncContentBrowserWidget.insidePendingFolder("Blueprints/Flows", pending));
        assertTrue(ReSyncContentBrowserWidget.insidePendingFolder("Blueprints/Flows/example.json", pending));
        assertFalse(ReSyncContentBrowserWidget.insidePendingFolder("Blueprints/Functions/example.json", pending));
    }

    private static ReSyncProjectMetadata.ResourceEntry resource(String id, String path) {
        ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
        resource.setId(id);
        resource.setPath(path);
        return resource;
    }
}
