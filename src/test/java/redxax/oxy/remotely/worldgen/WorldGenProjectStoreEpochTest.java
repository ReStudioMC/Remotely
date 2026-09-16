package redxax.oxy.remotely.worldgen;

import com.google.gson.JsonParser;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import redxax.oxy.remotely.worldgen.data.WorldGenSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenProjectStoreEpochTest {
    @Test
    void epochRollbackRestoresAuthoritativeStateAndRetainsDraft() {
        WorldGenProjectStore store = new WorldGenProjectStore();
        WorldGenProject authoritative = new WorldGenProject();
        authoritative.setId("world");
        WorldGenProject draft = new WorldGenProject();
        draft.setId("world");
        store.setActiveProject("server", authoritative);
        store.setDraftProject("server", draft);
        store.setProjectList("server", List.of("world"));

        WorldGenProjectStore.EpochTransition transition = store.prepareAuthorityEpoch("server", 7L);
        assertNotNull(transition);
        assertEquals(0L, transition.previousEpoch());
        assertEquals(7L, transition.authorityEpoch());
        assertTrue(store.commitAuthorityEpoch(transition));
        assertEquals(7L, store.authorityEpoch("server"));
        assertNull(store.cachedProject("server", "world"));
        assertEquals(draft.getId(), store.getOrCreateProject("server", WorldGenProject::new).getId());

        store.rollbackAuthorityEpoch(transition);
        assertEquals(0L, store.authorityEpoch("server"));
        assertNotNull(store.cachedProject("server", "world"));
        assertEquals(draft.getId(), store.getOrCreateProject("server", WorldGenProject::new).getId());
        assertEquals(List.of("world"), store.getProjectIds("server"));
    }

    @Test
    void reconnectCacheClearPreservesAuthorityEpochUntilFullRetirement() {
        WorldGenProjectStore store = new WorldGenProjectStore();
        WorldGenProject draft = new WorldGenProject();
        draft.setId("draft");
        store.setDraftProject("server", draft);

        assertTrue(store.observeAuthorityEpoch("server", 7L).accepted());
        store.clearServer("server", true);

        assertEquals(7L, store.authorityEpoch("server"));
        assertEquals("draft", store.getOrCreateProject("server", WorldGenProject::new).getId());

        store.clearServer("server");
        assertEquals(0L, store.authorityEpoch("server"));
    }

    @Test
    void equalRevisionRequiresTheSameCanonicalProjectAndList() {
        WorldGenProject first = new WorldGenProject();
        first.setId("world");
        WorldGenProject equivalent = WorldGenSerializer.deserializeProject(WorldGenSerializer.serializeProject(first));
        WorldGenProject conflicting = new WorldGenProject();
        conflicting.setId("world");
        conflicting.getSettings().setDefaultBlock("minecraft:stone");

        WorldGenProjectStore store = new WorldGenProjectStore();
        assertTrue(store.acceptProjectContentRevision("server", first, 4L));
        assertTrue(store.acceptProjectContentRevision("server", equivalent, 4L));
        assertFalse(store.acceptProjectContentRevision("server", conflicting, 4L));

        assertTrue(store.acceptProjectList("server", List.of("world", "nether"), 9L));
        assertTrue(store.acceptProjectList("server", List.of("world", "nether"), 9L));
        assertFalse(store.acceptProjectList("server", List.of("world"), 9L));
        assertEquals(List.of("world", "nether"), store.getProjectIds("server"));
    }

    @Test
    void authoritativeStateTokenChangesOnlyWhenRevisionOrContentChanges() {
        WorldGenProject first = new WorldGenProject();
        first.setId("world");
        WorldGenProject equivalent = WorldGenSerializer.deserializeProject(WorldGenSerializer.serializeProject(first));
        WorldGenProjectStore store = new WorldGenProjectStore();

        assertNotNull(store.acceptAndSetActiveProject("server", first, 4L));
        WorldGenProjectStore.ProjectState firstState = store.projectState("server", "world");
        assertNotNull(store.acceptAndSetActiveProject("server", equivalent, 4L));
        assertEquals(firstState, store.projectState("server", "world"));

        assertNotNull(store.acceptAndSetActiveProject("server", equivalent, 5L));
        WorldGenProjectStore.ProjectState nextState = store.projectState("server", "world");
        assertNotEquals(firstState.stateRevision(), nextState.stateRevision());
        assertEquals(firstState.canonicalHash(), nextState.canonicalHash());
        assertEquals(5L, nextState.authorityRevision());
    }

    @Test
    void transferredDraftIdentityDetectsEditsWithoutRecopyingTheDraft() {
        WorldGenProject project = new WorldGenProject();
        project.setId("world");
        WorldGenProjectStore store = new WorldGenProjectStore();
        WorldGenProjectStore.ProjectTransfer transfer = store.transferProject(project, WorldGenProject::new);

        assertTrue(store.sameProjectContent(project, transfer.project()));
        store.setDraftProject("server", transfer);
        WorldGenProject authoritative = WorldGenSerializer.deserializeProject(WorldGenSerializer.serializeProject(project));
        WorldGenProject delivered = store.acceptAndSetActiveProject("server", authoritative, 4L);
        assertNotSame(transfer.project(), delivered);
        assertTrue(store.sameProjectContent(project, delivered));
        assertSame(project, store.getOrCreateProject("server", WorldGenProject::new));
        store.promoteDraftProject("server", transfer, 5L);
        assertEquals(5L, store.projectRevision("server", "world"));
        assertEquals(5L, store.projectState("server", "world").authorityRevision());
        assertTrue(store.sameProjectContent(transfer.project(), store.cachedProject("server", "world")));

        project.getSettings().setDefaultBlock("minecraft:diamond_block");
        store.invalidateProjectContent(project);
        assertFalse(store.sameProjectContent(project, transfer.project()));
    }

    @Test
    void preparedIdentityFastPathRequiresKnownExactContent() {
        WorldGenProject prepared = new WorldGenProject();
        prepared.setId("world");
        String serialized = WorldGenSerializer.serializeProject(prepared);
        WorldGenProjectStore store = new WorldGenProjectStore();
        store.transferPreparedProject(prepared, serialized, JsonParser.parseString(serialized).getAsJsonObject(), "");

        WorldGenProject matching = WorldGenSerializer.deserializeProject(serialized);
        assertNotNull(store.acceptAndSetActiveProject("server", matching, 4L));
        assertTrue(store.knownSameProjectContent(prepared, matching));

        WorldGenProject conflicting = WorldGenSerializer.deserializeProject(serialized);
        conflicting.getSettings().setDefaultBlock("minecraft:diamond_block");
        assertNotNull(store.acceptAndSetActiveProject("server", conflicting, 5L));
        assertFalse(store.knownSameProjectContent(prepared, conflicting));
    }

    @Test
    void preparedTransferRejectsProjectAndWorkspacePayloadDrift() {
        WorldGenProject project = new WorldGenProject();
        project.setId("world");
        String serialized = WorldGenSerializer.serializeProject(project);
        var document = JsonParser.parseString(serialized).getAsJsonObject();
        WorldGenProjectStore store = new WorldGenProjectStore();

        project.getSettings().setDefaultBlock("minecraft:diamond_block");
        assertNull(store.transferPreparedProject(project, serialized, document, ""));

        WorldGenProject restored = WorldGenSerializer.deserializeProject(serialized);
        document.addProperty("id", "other");
        assertNull(store.transferPreparedProject(restored, serialized, document, ""));
    }
}
