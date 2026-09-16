package redxax.oxy.remotely.flow.sync;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionCatalogSnapshotContractTest {
    @Test
    void readsTheSharedRichContextualCatalogFixture() throws Exception {
        OptionCatalogSnapshot snapshot = new Gson().fromJson(Files.readString(Path.of("contracts", "option-catalog-v2.fixture.json")), OptionCatalogSnapshot.class);

        assertEquals(3, OptionCatalogSnapshot.CURRENT_VERSION);
        assertEquals(2, snapshot.getVersion());
        assertEquals("server:request:quests", snapshot.getSourceId());
        assertEquals("{\"world\":\"world_nether\"}", snapshot.getContextKey());
        assertEquals("quests-42", snapshot.getRevision());
        assertEquals(42L, snapshot.getSequence());
        assertEquals(snapshot.getValues(), snapshot.getItems().stream().map(item -> item.getValue()).toList());
        assertEquals("Dragon Hunt", snapshot.getItems().getFirst().getLabel());
        assertEquals("Campaign", snapshot.getItems().getFirst().getGroup());
        assertTrue((Boolean) snapshot.getItems().getFirst().getMetadata().get("recommended"));
        assertFalse((Boolean) snapshot.getItems().getLast().getMetadata().get("available"));
    }
}
