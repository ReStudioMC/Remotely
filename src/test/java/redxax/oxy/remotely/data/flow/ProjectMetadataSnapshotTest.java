package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectMetadataSnapshotTest {

    @Test
    void materializePreservesSourceAndPersistentInsertionOrder() {
        ReSyncProjectMetadata metadata = new ReSyncProjectMetadata("server");
        metadata.setResources(new ArrayList<>(List.of(resource("flow", "zeta"), resource("flow", "alpha"))));
        metadata.setOpenDocuments(new ArrayList<>(List.of(document("flow", "zeta"), document("flow", "alpha"))));
        metadata.setInstalledBundles(new ArrayList<>(List.of(bundle("market", "zeta"), bundle("market", "alpha"))));

        ProjectMetadataSnapshot original = ProjectMetadataSnapshot.from(metadata);
        ProjectMetadataSnapshot.Editor edit = original.edit();
        edit.put(new ProjectMetadataSnapshot.Resource("flow", "middle", "middle", "Flows", 3));
        edit.put(new ProjectMetadataSnapshot.Document("flow", "middle", "middle", false));
        edit.put(new ProjectMetadataSnapshot.Bundle("market", "middle", "middle", "", "", "", "", true, List.of()));
        ProjectMetadataSnapshot changed = edit.freeze();

        assertEquals(List.of("zeta", "alpha"), original.materialize().getResources().stream().map(ReSyncProjectMetadata.ResourceEntry::getId).toList());
        assertEquals(List.of("zeta", "alpha", "middle"), changed.materialize().getResources().stream().map(ReSyncProjectMetadata.ResourceEntry::getId).toList());
        assertEquals(List.of("zeta", "alpha", "middle"), changed.materialize().getOpenDocuments().stream().map(ReSyncProjectMetadata.OpenDocumentEntry::getId).toList());
        assertEquals(List.of("zeta", "alpha", "middle"), changed.materialize().getInstalledBundles().stream().map(ReSyncProjectMetadata.InstalledBundleEntry::getListingSlug).toList());
    }

    @Test
    void balancedIdentityIndexHandlesSortedAndHashCollisionKeys() {
        ProjectMetadataSnapshot.Editor edit = ProjectMetadataSnapshot.from(new ReSyncProjectMetadata("server")).edit();
        for (int index = 0; index < 1_000; index++) {
            String id = "%04d".formatted(index);
            edit.put(new ProjectMetadataSnapshot.Resource("flow", id, id, "Flows", index));
        }
        edit.put(new ProjectMetadataSnapshot.Resource("flow", "Aa", "Aa", "Flows", 1_001));
        edit.put(new ProjectMetadataSnapshot.Resource("flow", "BB", "BB", "Flows", 1_002));
        ProjectMetadataSnapshot snapshot = edit.freeze();

        assertEquals("0000", snapshot.resource("flow", "0000").id());
        assertEquals("0999", snapshot.resource("flow", "0999").id());
        assertEquals("Aa", snapshot.resource("flow", "Aa").id());
        assertEquals("BB", snapshot.resource("flow", "BB").id());
        ProjectMetadataSnapshot.Editor removal = snapshot.edit();
        removal.removeResource(ReSyncProjectMetadata.resourceKey("flow", "0500"));
        assertNull(removal.freeze().resource("flow", "0500"));
    }

    @Test
    void reconnectRebaseKeepsLocalChangesAndServerChanges() {
        ReSyncProjectMetadata baseModel = new ReSyncProjectMetadata("server");
        baseModel.setResources(new ArrayList<>(List.of(resource("flow", "local"), resource("flow", "server"))));
        ProjectMetadataSnapshot base = ProjectMetadataSnapshot.from(baseModel);
        ProjectMetadataSnapshot.Editor localEdit = base.edit();
        localEdit.put(new ProjectMetadataSnapshot.Resource("flow", "local", "Local Edit", "Flows", 0));
        ProjectMetadataSnapshot.Editor serverEdit = base.edit();
        serverEdit.put(new ProjectMetadataSnapshot.Resource("flow", "server", "Server Edit", "Flows", 1));

        ProjectMetadataSnapshot rebased = ProjectMetadataSnapshot.rebase(base, localEdit.freeze(), serverEdit.freeze());

        assertEquals("Local Edit", rebased.resource("flow", "local").displayName());
        assertEquals("Server Edit", rebased.resource("flow", "server").displayName());
    }

    private static ReSyncProjectMetadata.ResourceEntry resource(String type, String id) {
        ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
        resource.setType(type);
        resource.setId(id);
        resource.setDisplayName(id);
        resource.setPath("Flows");
        return resource;
    }

    private static ReSyncProjectMetadata.OpenDocumentEntry document(String type, String id) {
        ReSyncProjectMetadata.OpenDocumentEntry document = new ReSyncProjectMetadata.OpenDocumentEntry();
        document.setType(type);
        document.setId(id);
        document.setDisplayName(id);
        return document;
    }

    private static ReSyncProjectMetadata.InstalledBundleEntry bundle(String marketplace, String listing) {
        ReSyncProjectMetadata.InstalledBundleEntry bundle = new ReSyncProjectMetadata.InstalledBundleEntry();
        bundle.setMarketplaceSlug(marketplace);
        bundle.setListingSlug(listing);
        bundle.setTitle(listing);
        return bundle;
    }
}
